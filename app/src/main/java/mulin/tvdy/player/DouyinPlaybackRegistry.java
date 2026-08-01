package mulin.tvdy.player;

import java.util.Map;
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
        String exact = URI_TO_AWEME.get(playUrl);
        if (exact != null) return exact;
        // Redirects / signed query reshuffles may not match the registered string.
        for (Map.Entry<String, String> e : URI_TO_AWEME.entrySet()) {
            if (samePlayResource(e.getKey(), playUrl)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean samePlayResource(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        String pathA = pathOnly(a);
        String pathB = pathOnly(b);
        if (!pathA.isEmpty() && pathA.equals(pathB)) return true;
        String fidA = queryParam(a, "fid");
        String fidB = queryParam(b, "fid");
        return !fidA.isEmpty() && fidA.equals(fidB)
                && pathA.contains("media-") == pathB.contains("media-");
    }

    private static String pathOnly(String url) {
        int scheme = url.indexOf("://");
        int start = scheme >= 0 ? url.indexOf('/', scheme + 3) : 0;
        if (start < 0) return url;
        int q = url.indexOf('?', start);
        return q > start ? url.substring(start, q) : url.substring(start);
    }

    private static String queryParam(String url, String name) {
        int start = url.indexOf(name + "=");
        if (start < 0) return "";
        start += name.length() + 1;
        int end = url.indexOf('&', start);
        return end > start ? url.substring(start, end) : url.substring(start);
    }
}
