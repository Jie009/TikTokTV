package mulin.tvdy.auth;

import android.webkit.CookieManager;

import mulin.tvdy.DouyinConstants;

/**
 * The app's only login method: driving douyin's real web login page from a
 * WebView isn't viable at all - it's a JS-rendered page guarded by a
 * slide-to-verify anti-bot captcha that a headless, never-shown WebView has
 * no way to solve - so instead this lets the user log in once in any
 * ordinary browser they already have on a computer, copy the raw Cookie
 * header/{@code document.cookie} string out of that already-authenticated
 * session via the browser's own dev tools, and paste it in here. Applying it
 * this way never touches douyin.com from this app at all, so it can't trip
 * that same verification.
 */
public final class CookieImportHelper {

    private CookieImportHelper() {
    }

    /**
     * Parses a "name=value; name2=value2" cookie string - the same format
     * shown by a browser's "Cookie" request header or {@code document.cookie}
     * - and writes each pair into the process-wide {@link CookieManager} for
     * {@link DouyinConstants#FEED_URL}, exactly as if a real login had just
     * happened - the pump WebView shares this same process-wide
     * {@link CookieManager}, so it picks these up on its next request.
     *
     * @return {@code true} only if the pasted string actually contained a
     * non-empty {@code sessionid} - the one cookie douyin needs to treat a
     * session as signed-in - so an empty paste, garbage text, or a
     * logged-out session's cookies is rejected up front rather than
     * silently "succeeding" into a still-anonymous session.
     */
    public static boolean apply(String rawCookieHeader) {
        if (rawCookieHeader == null) return false;
        String trimmedInput = rawCookieHeader.trim();
        if (trimmedInput.isEmpty()) return false;

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        boolean sawNonEmptySessionId = false;
        for (String pair : trimmedInput.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (name.isEmpty() || value.isEmpty()) continue;
            cookieManager.setCookie(DouyinConstants.FEED_URL, name + "=" + value);
            if (name.equals("sessionid")) sawNonEmptySessionId = true;
        }
        cookieManager.flush();
        return sawNonEmptySessionId;
    }

    /**
     * Logs out by wiping every cookie the process-wide {@link CookieManager}
     * holds (sessionid, ttwid, etc.), reverting to the same blank slate as a
     * fresh install. Nukes the whole cookie jar rather than picking out just
     * douyin.com's cookies: this app never talks to any other origin (see
     * {@link DouyinConstants}), so there's nothing else in there to preserve.
     */
    public static void clear() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();
    }
}
