package mulin.tvdy.auth;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import mulin.tvdy.DouyinConstants;

/**
 * One-shot check of whether the Cookie currently sitting in the process-wide
 * {@code CookieManager} (applied via {@link CookieImportHelper}) is actually
 * accepted by douyin's own servers as a real signed-in session - not just
 * whether it merely *contained* a non-empty {@code sessionid} string, which
 * is all {@link CookieImportHelper} itself can verify from the pasted text
 * alone. A stale, revoked, or mistyped session id would still pass that
 * check and still show up in {@code document.cookie}, so it's not a
 * trustworthy "am I really logged in?" signal by itself.
 * <p>
 * This instead loads douyin.com's own account page in a throwaway hidden
 * WebView - sharing the same {@code CookieManager}, so whatever Cookie is
 * currently applied rides along automatically, exactly like a real browser
 * tab would - and reads back what that page itself decided: douyin renders
 * a literal "未登录" ("not logged in") heading in place of the real profile
 * content whenever the session it received wasn't accepted server-side,
 * which is a far stronger signal than anything inferable purely from the
 * cookie string. Confirmed by loading this page with no cookie at all
 * during development, which reliably shows that heading.
 * <p>
 * Deliberately independent of {@link mulin.tvdy.pump.FeedPumpController}'s
 * own WebView: reusing that one would mean navigating it away from the feed
 * URL and back, disrupting whatever pagination state it's mid-flight on.
 */
public final class LoginStatusChecker {

    private static final String CHECK_URL = "https://www.douyin.com/user/self?from_nav=1";
    /** Gives the page's own client-side rendering a moment to settle after load before reading it. */
    private static final long EXTRACT_DELAY_MS = 600;
    private static final long TIMEOUT_MS = 12_000;

    private static final String EXTRACT_SCRIPT =
            "(function(){"
                    + "try{"
                    + "var h=document.querySelector('h1,[role=\"heading\"]');"
                    + "var text=h?h.innerText.trim():'';"
                    + "if(!text||text.indexOf('\u672a\u767b\u5f55')!==-1){"
                    + "return JSON.stringify({loggedIn:false});"
                    + "}"
                    // No stable class name to key off of (these are hashed
                    // CSS-module names that change across deployments), so
                    // pick the avatar heuristically instead: the largest
                    // roughly-square image on the page. The one real
                    // candidate that beat this in testing was a background
                    // banner image, which is comfortably non-square, so the
                    // aspect-ratio filter alone rules it out.
                    + "var avatarUrl=null;"
                    + "try{"
                    + "var imgs=Array.prototype.slice.call(document.querySelectorAll('img'));"
                    + "var candidates=imgs.filter(function(i){"
                    + "var w=i.naturalWidth,hh=i.naturalHeight;"
                    + "return w>=80&&w<=900&&hh>=80&&hh<=900&&Math.abs(w-hh)<=Math.max(w,hh)*0.15;"
                    + "});"
                    + "candidates.sort(function(a,b){"
                    + "return (b.naturalWidth*b.naturalHeight)-(a.naturalWidth*a.naturalHeight);"
                    + "});"
                    + "if(candidates.length)avatarUrl=candidates[0].src;"
                    + "}catch(e){}"
                    + "return JSON.stringify({loggedIn:true,nickname:text,avatarUrl:avatarUrl});"
                    + "}catch(e){"
                    + "return JSON.stringify({loggedIn:false,error:String(e)});"
                    + "}"
                    + "})();";

    public interface Callback {
        /**
         * @param loggedIn  whether douyin's own account page rendered real
         *                  profile content instead of its logged-out state
         * @param nickname  the signed-in account's display name if {@code loggedIn}, else {@code null}
         * @param avatarUrl a best-effort guess at that account's avatar
         *                  image URL if {@code loggedIn} (see
         *                  {@link #EXTRACT_SCRIPT}), else {@code null} -
         *                  never guaranteed correct, so callers should treat
         *                  a failed/wrong image load as harmless
         */
        void onResult(boolean loggedIn, String nickname, String avatarUrl);

        /** The check page never finished loading/responding within {@link #TIMEOUT_MS}. */
        void onCheckFailed();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ViewGroup container;
    private WebView webView;
    private boolean finished = false;

    @SuppressWarnings("SetJavaScriptEnabled")
    public void check(Activity host, ViewGroup container, Callback callback) {
        this.container = container;
        webView = new WebView(host);
        // Same reasoning as FeedPumpController's pump WebView: kept tiny
        // rather than never attached at all, since some OEM WebView builds
        // throttle timers/rendering on zero-size/detached views.
        container.addView(webView, new ViewGroup.LayoutParams(4, 4));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setUserAgentString(DouyinConstants.DESKTOP_USER_AGENT);
        // No feed video playback happens on this throwaway profile page, but
        // block autoplay of any preview clips it might render anyway.
        settings.setMediaPlaybackRequiresUserGesture(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                handler.postDelayed(() -> runExtract(callback), EXTRACT_DELAY_MS);
            }
        });

        handler.postDelayed(() -> finishFailed(callback), TIMEOUT_MS);
        webView.loadUrl(CHECK_URL);
    }

    private void runExtract(Callback callback) {
        if (finished || webView == null) return;
        webView.evaluateJavascript(EXTRACT_SCRIPT, raw -> {
            try {
                // evaluateJavascript hands back the JS return value itself
                // JSON-encoded (i.e. a quoted string containing our
                // JSON.stringify output) - unwrap that outer layer first.
                String json = new JSONArray("[" + raw + "]").getString(0);
                JSONObject obj = new JSONObject(json);
                boolean loggedIn = obj.optBoolean("loggedIn", false);
                String nickname = loggedIn ? obj.optString("nickname", null) : null;
                String avatarUrl = loggedIn ? obj.optString("avatarUrl", null) : null;
                finishResult(callback, loggedIn, nickname, avatarUrl);
            } catch (Exception e) {
                finishFailed(callback);
            }
        });
    }

    private void finishResult(Callback callback, boolean loggedIn, String nickname, String avatarUrl) {
        if (!markFinished()) return;
        callback.onResult(loggedIn, nickname, avatarUrl);
    }

    private void finishFailed(Callback callback) {
        if (!markFinished()) return;
        callback.onCheckFailed();
    }

    /** @return {@code true} the first time this is called for this instance, {@code false} on every call after. */
    private boolean markFinished() {
        if (finished) return false;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            container.removeView(webView);
            webView.destroy();
            webView = null;
        }
        return true;
    }
}
