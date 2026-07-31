package mulin.tvdy.data;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A single playable feed item, distilled from a raw {@code aweme_list} entry
 * returned by douyin's {@code /aweme/v2/web/module/feed/} endpoint. Items
 * without a usable {@code video.play_addr} (ad cards, live cards, etc.) never
 * become a {@link FeedVideo} - see {@link #fromAwemeItem}.
 */
public final class FeedVideo {

    public final String awemeId;
    public final String desc;
    public final String coverUrl;
    public final String playUrl;
    public final String authorName;
    public final String authorAvatarUrl;

    /**
     * Server-reported counts at the time this item was fetched. Display
     * only - there's no signed write-endpoint integration with the real
     * douyin account, no in-app comment thread, and no remote-control
     * binding to like/collect, so none of these ever change locally.
     */
    public final long diggCount;
    public final long commentCount;
    public final long collectCount;
    public final long shareCount;

    private FeedVideo(String awemeId, String desc, String coverUrl, String playUrl,
                       String authorName, String authorAvatarUrl,
                       long diggCount, long commentCount, long collectCount, long shareCount) {
        this.awemeId = awemeId;
        this.desc = desc;
        this.coverUrl = coverUrl;
        this.playUrl = playUrl;
        this.authorName = authorName;
        this.authorAvatarUrl = authorAvatarUrl;
        this.diggCount = diggCount;
        this.commentCount = commentCount;
        this.collectCount = collectCount;
        this.shareCount = shareCount;
    }

    /**
     * @return a {@link FeedVideo}, or {@code null} if this item has no
     * {@code aweme_id} or no directly playable url (which is the case for
     * ads, live-stream cards, and a few other non-video card types mixed
     * into the feed).
     */
    public static FeedVideo fromAwemeItem(JSONObject item) {
        if (item == null) return null;

        String awemeId = item.optString("aweme_id", "");
        if (awemeId.isEmpty()) return null;

        JSONObject video = item.optJSONObject("video");
        if (video == null) return null;

        String playUrl = firstUrl(video.optJSONObject("play_addr"));
        if (playUrl == null || playUrl.isEmpty()) return null;

        // Field name for the cover image varies across item types; try the
        // common ones in order of preference. Not load-bearing for playback,
        // so any failure here just leaves coverUrl null.
        String coverUrl = firstUrl(video.optJSONObject("cover"));
        if (coverUrl == null) coverUrl = firstUrl(video.optJSONObject("origin_cover"));
        if (coverUrl == null) coverUrl = firstUrl(video.optJSONObject("dynamic_cover"));

        String desc = item.optString("desc", "");

        JSONObject author = item.optJSONObject("author");
        String authorName = author != null ? author.optString("nickname", "") : "";
        String authorAvatarUrl = null;
        if (author != null) {
            authorAvatarUrl = firstUrl(author.optJSONObject("avatar_thumb"));
            if (authorAvatarUrl == null) authorAvatarUrl = firstUrl(author.optJSONObject("avatar_medium"));
            if (authorAvatarUrl == null) authorAvatarUrl = firstUrl(author.optJSONObject("avatar_larger"));
        }

        JSONObject statistics = item.optJSONObject("statistics");
        long diggCount = statistics != null ? statistics.optLong("digg_count", 0) : 0;
        long commentCount = statistics != null ? statistics.optLong("comment_count", 0) : 0;
        long collectCount = statistics != null ? statistics.optLong("collect_count", 0) : 0;
        long shareCount = statistics != null ? statistics.optLong("share_count", 0) : 0;

        return new FeedVideo(awemeId, desc, coverUrl, playUrl, authorName, authorAvatarUrl,
                diggCount, commentCount, collectCount, shareCount);
    }

    private static String firstUrl(JSONObject urlHolder) {
        if (urlHolder == null) return null;
        JSONArray urlList = urlHolder.optJSONArray("url_list");
        if (urlList == null || urlList.length() == 0) return null;
        String url = urlList.optString(0, null);
        return (url == null || url.isEmpty()) ? null : url;
    }

    @Override
    public String toString() {
        return "FeedVideo{awemeId=" + awemeId + ", desc=" + desc + ", playUrl=" + playUrl + "}";
    }
}
