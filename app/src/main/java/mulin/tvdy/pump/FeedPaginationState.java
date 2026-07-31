package mulin.tvdy.pump;

import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

/**
 * Remembers the last captured feed URL and pagination cursors so the pump can
 * proactively request the next page instead of waiting for scroll events.
 */
final class FeedPaginationState {

    private static final String TAG = "FeedPagination";

    private String lastFeedUrl;
    private long maxCursor;
    private int refreshIndex;
    private boolean hasMore = true;
    private boolean bootstrapped;
    private boolean tabFeed;

    boolean isReady() {
        return bootstrapped && hasMore && lastFeedUrl != null && !lastFeedUrl.isEmpty();
    }

    boolean isTabFeed() {
        return tabFeed;
    }

    String getLastFeedUrl() {
        return lastFeedUrl;
    }

    long getMaxCursor() {
        return maxCursor;
    }

    int getRefreshIndex() {
        return refreshIndex;
    }

    boolean hasMore() {
        return hasMore;
    }

    void updateFromCapture(String url, JSONObject root) {
        if (url == null || url.isEmpty() || root == null) return;

        int urlRefreshIndex = 0;
        try {
            Uri uri = Uri.parse(url);
            String refreshParam = uri.getQueryParameter("refresh_index");
            if (refreshParam != null && !refreshParam.isEmpty()) {
                urlRefreshIndex = Integer.parseInt(refreshParam);
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to parse refresh_index from url", e);
        }

        if (bootstrapped && urlRefreshIndex > 0 && urlRefreshIndex + 1 <= refreshIndex) {
            Log.d(TAG, "ignoring duplicate capture refresh_index=" + urlRefreshIndex
                    + " (next=" + refreshIndex + ")");
            return;
        }

        lastFeedUrl = url;
        bootstrapped = true;
        tabFeed = url.contains("/tab/feed");

        if (root.has("max_cursor")) {
            maxCursor = root.optLong("max_cursor", maxCursor);
        }
        if (root.has("has_more")) {
            hasMore = root.optInt("has_more", 1) != 0;
        }

        if (urlRefreshIndex > 0) {
            refreshIndex = urlRefreshIndex + 1;
        } else if (root.has("refresh_index")) {
            refreshIndex = root.optInt("refresh_index", refreshIndex) + 1;
        } else {
            refreshIndex++;
        }

        if (!root.has("max_cursor")) {
            try {
                Uri uri = Uri.parse(url);
                String cursorParam = uri.getQueryParameter("max_cursor");
                if (cursorParam != null && !cursorParam.isEmpty()) {
                    maxCursor = Long.parseLong(cursorParam);
                }
            } catch (Exception e) {
                Log.w(TAG, "failed to parse max_cursor from url", e);
            }
        }

        Log.d(TAG, "updated tabFeed=" + tabFeed
                + " max_cursor=" + maxCursor
                + " refresh_index=" + refreshIndex
                + " has_more=" + hasMore
                + " fromUrlRefresh=" + urlRefreshIndex);
    }

    void reset() {
        lastFeedUrl = null;
        maxCursor = 0;
        refreshIndex = 0;
        hasMore = true;
        tabFeed = false;
        bootstrapped = false;
    }

    /** Bootstrap pagination state from a lite-binary feed URL we could not parse. */
    void seedFromUrl(String url) {
        if (url == null || url.isEmpty()) return;

        int urlRefreshIndex = 0;
        try {
            Uri uri = Uri.parse(url);
            String refreshParam = uri.getQueryParameter("refresh_index");
            if (refreshParam != null && !refreshParam.isEmpty()) {
                urlRefreshIndex = Integer.parseInt(refreshParam);
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to parse refresh_index from seed url", e);
        }

        lastFeedUrl = url;
        bootstrapped = true;
        tabFeed = url.contains("/tab/feed");
        refreshIndex = urlRefreshIndex > 0 ? urlRefreshIndex + 1 : 2;
        Log.d(TAG, "seeded from lite url refresh_index=" + refreshIndex);
    }
}
