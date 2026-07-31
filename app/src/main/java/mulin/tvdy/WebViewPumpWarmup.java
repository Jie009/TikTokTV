package mulin.tvdy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Pays the one-time Chromium/WebView cold-start cost as early as possible
 * (Application.onCreate) so the feed pump in {@link mulin.tvdy.player.PlayerActivity}
 * does not eat that time on the critical path to the first video.
 */
public final class WebViewPumpWarmup {

    private static final String TAG = "WebViewWarmup";
    private static boolean warmed = false;

    private WebViewPumpWarmup() {
    }

    public static synchronized void warm(Context context) {
        if (warmed) return;
        warmed = true;
        Context app = context.getApplicationContext();
        CookieManager.getInstance().setAcceptCookie(true);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                WebView probe = new WebView(app);
                WebSettings settings = probe.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                probe.destroy();
                Log.d(TAG, "WebView engine warmed");
            } catch (Exception e) {
                Log.w(TAG, "WebView warmup failed", e);
            }
        });
    }
}
