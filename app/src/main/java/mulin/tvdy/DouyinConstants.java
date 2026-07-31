package mulin.tvdy;

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

    /** Logical desktop viewport the pump WebView presents to douyin.com. */
    public static final int PUMP_VIEWPORT_WIDTH = 1280;
    public static final int PUMP_VIEWPORT_HEIGHT = 720;
}
