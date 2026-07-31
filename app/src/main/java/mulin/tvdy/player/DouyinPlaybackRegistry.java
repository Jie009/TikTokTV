package mulin.tvdy.player;

import java.util.concurrent.ConcurrentHashMap;

/** Maps play URLs to aweme ids so CDN requests can use a video-scoped Referer. */
final class DouyinPlaybackRegistry {

    private static final ConcurrentHashMap<String, String> URI_TO_AWEME = new ConcurrentHashMap<>();

    private DouyinPlaybackRegistry() {
    }

    static void register(String playUrl, String awemeId) {
        if (playUrl == null || playUrl.isEmpty() || awemeId == null || awemeId.isEmpty()) return;
        URI_TO_AWEME.put(playUrl, awemeId);
    }

    static String findAwemeId(String playUrl) {
        if (playUrl == null) return null;
        return URI_TO_AWEME.get(playUrl);
    }
}
