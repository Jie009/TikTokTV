package mulin.tvdy.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

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
    /** Alternate CDN URLs to try when {@link #playUrl} returns 403. */
    public final List<String> playUrlCandidates;
    public final String authorName;
    public final String authorAvatarUrl;
    /** Douyin author.sec_uid — opens {@code /user/{sec_uid}} profile pages. */
    public final String authorSecUid;
    public final String authorUniqueId;

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
                      List<String> playUrlCandidates,
                      String authorName, String authorAvatarUrl,
                      String authorSecUid, String authorUniqueId,
                      long diggCount, long commentCount, long collectCount, long shareCount) {
        this.awemeId = awemeId;
        this.desc = desc;
        this.coverUrl = coverUrl;
        this.playUrl = playUrl;
        this.playUrlCandidates = playUrlCandidates;
        this.authorName = authorName;
        this.authorAvatarUrl = authorAvatarUrl;
        this.authorSecUid = authorSecUid;
        this.authorUniqueId = authorUniqueId;
        this.diggCount = diggCount;
        this.commentCount = commentCount;
        this.collectCount = collectCount;
        this.shareCount = shareCount;
    }

    /**
     * Returns {@code true} when Douyin's payload marks this item as already
     * consumed on the account (phone/web history), so it should not enter the
     * playback queue even on the first fetch after login.
     */
    public static boolean isMarkedWatchedByServer(JSONObject item) {
        if (item == null) return false;
        if (item.optBoolean("is_watched", false)) return true;
        if (item.optBoolean("watch_status", false)) return true;
        int itemWatchStatus = item.optInt("item_watch_status", -1);
        if (itemWatchStatus == 1 || itemWatchStatus == 2) return true;
        JSONObject video = item.optJSONObject("video");
        if (video != null) {
            if (video.optBoolean("is_watched", false)) return true;
            if (video.optInt("watch_progress", 0) > 0) return true;
        }
        return false;
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

        List<String> candidates = collectPlayUrls(video);
        if (candidates.isEmpty()) return null;
        String playUrl = candidates.get(0);

        // Field name for the cover image varies across item types; try the
        // common ones in order of preference. Not load-bearing for playback,
        // so any failure here just leaves coverUrl null.
        String coverUrl = firstUrl(video.optJSONObject("cover"));
        if (coverUrl == null) coverUrl = firstUrl(video.optJSONObject("origin_cover"));
        if (coverUrl == null) coverUrl = firstUrl(video.optJSONObject("dynamic_cover"));

        String desc = item.optString("desc", "");

        JSONObject author = item.optJSONObject("author");
        String authorName = author != null ? author.optString("nickname", "") : "";
        String authorSecUid = author != null ? author.optString("sec_uid", "") : "";
        String authorUniqueId = author != null ? author.optString("unique_id", "") : "";
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

        return new FeedVideo(awemeId, desc, coverUrl, playUrl, candidates,
                authorName, authorAvatarUrl, authorSecUid, authorUniqueId,
                diggCount, commentCount, collectCount, shareCount);
    }

    private static List<String> collectPlayUrls(JSONObject video) {
        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        List<String> ordered = new ArrayList<>();

        collectInto(video.optJSONObject("play_addr"), seenKeys, ordered);
        collectInto(video.optJSONObject("download_addr"), seenKeys, ordered);

        JSONArray bitRates = video.optJSONArray("bit_rate");
        if (bitRates != null) {
            for (int i = 0; i < bitRates.length(); i++) {
                JSONObject bitRate = bitRates.optJSONObject(i);
                if (bitRate == null) continue;
                collectInto(bitRate.optJSONObject("play_addr"), seenKeys, ordered);
            }
        }

        ordered.sort(FeedVideo::comparePlayUrlPreference);
        if (ordered.size() > 12) {
            return new ArrayList<>(ordered.subList(0, 12));
        }
        return ordered;
    }

    private static void collectInto(JSONObject urlHolder, LinkedHashSet<String> seenKeys,
                                    List<String> ordered) {
        if (urlHolder == null) return;
        JSONArray urlList = urlHolder.optJSONArray("url_list");
        if (urlList == null) return;
        for (int i = 0; i < urlList.length(); i++) {
            String url = urlList.optString(i, "");
            if (url.isEmpty()) continue;
            String key = urlDedupeKey(url);
            if (!seenKeys.add(key)) continue;
            ordered.add(url);
        }
    }

    /** Ignore cosmetic query reordering (e.g. cquery) when deduping mirrors. */
    private static String urlDedupeKey(String url) {
        if (url.contains("/aweme/v1/play/")) {
            int q = url.indexOf('?');
            return q > 0 ? url.substring(0, q) : url;
        }
        int q = url.indexOf('?');
        String base = q > 0 ? url.substring(0, q) : url;
        String sig = extractQueryParam(url, "signature");
        String br = extractQueryParam(url, "br");
        return base + "|" + br + "|" + sig;
    }

    private static String extractQueryParam(String url, String name) {
        int start = url.indexOf(name + "=");
        if (start < 0) return "";
        start += name.length() + 1;
        int end = url.indexOf('&', start);
        return end > start ? url.substring(start, end) : url.substring(start);
    }

    /** Prefer douyin play redirect, then v26 CDN direct links. */
    private static int comparePlayUrlPreference(String a, String b) {
        return Integer.compare(scorePlayUrl(b), scorePlayUrl(a));
    }

    private static int scorePlayUrl(String url) {
        if (url == null || url.isEmpty()) return 0;
        int score = 0;
        if (url.contains("/aweme/v1/play/")) score += 100;
        if (url.contains("douyin.com")) score += 40;
        if (url.contains("douyinvod.com")) score += 20;
        if (url.contains("v26-web")) score += 8;
        if (url.contains("v3-web")) score += 4;
        if (url.contains("mime_type=video_mp4")) score += 2;
        return score;
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
