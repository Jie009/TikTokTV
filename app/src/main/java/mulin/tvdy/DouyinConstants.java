package mulin.tvdy;

import androidx.annotation.Nullable;
import android.webkit.CookieManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared identity used both by the hidden data-pump WebView (so the page
 * looks like a normal desktop browser) and by ExoPlayer's HTTP data source
 * (the CDN serving {@code play_addr} direct links rejects requests that
 * don't carry a matching Referer/User-Agent).
 */
public final class DouyinConstants {

    private DouyinConstants() {
    }

    public static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    public static final String FEED_URL = "https://www.douyin.com/";
    public static final String REFERER = "https://www.douyin.com/";
    public static final String ORIGIN = "https://www.douyin.com";

    /** Logical desktop viewport the pump WebView presents to douyin.com. */
    public static final int PUMP_VIEWPORT_WIDTH = 1280;
    public static final int PUMP_VIEWPORT_HEIGHT = 720;

    /**
     * Headers douyinvod CDN links expect. Cookies are read fresh on each
     * request because the pump WebView sets them after app startup.
     */
    public static Map<String, String> buildPlaybackRequestHeaders() {
        return buildPlaybackRequestHeaders(null);
    }

    public static Map<String, String> buildPlaybackRequestHeaders(@Nullable String awemeId) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (awemeId != null && !awemeId.isEmpty()) {
            headers.put("Referer", FEED_URL + "video/" + awemeId);
        } else {
            headers.put("Referer", REFERER);
        }
        headers.put("Origin", ORIGIN);
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8");
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.flush();
            String cookie = cookieManager.getCookie(FEED_URL);
            if (cookie != null && !cookie.isEmpty()) {
                headers.put("Cookie", cookie);
            }
        } catch (Exception ignored) {
        }
        return Collections.unmodifiableMap(headers);
    }

    public static int playbackCookieLength() {
        try {
            CookieManager.getInstance().flush();
            String cookie = CookieManager.getInstance().getCookie(FEED_URL);
            return cookie != null ? cookie.length() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
