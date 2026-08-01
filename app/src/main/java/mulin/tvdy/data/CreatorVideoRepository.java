package mulin.tvdy.data;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Buffer of one creator's posted videos, fed by the pump while its WebView
 * is on {@code /user/{sec_uid}} and drained by the creator grid / scoped
 * playback mode.
 */
public final class CreatorVideoRepository {

    private static final String TAG = "CreatorVideoRepo";
    private static final int MAX_SEEN_IDS = 2000;
    private static final long PAGE_REQUEST_COOLDOWN_MS = 1_200;

    private static CreatorVideoRepository instance;

    public static synchronized CreatorVideoRepository getInstance() {
        if (instance == null) {
            instance = new CreatorVideoRepository();
        }
        return instance;
    }

    public interface Listener {
        void onCreatorVideosChanged(int size);
    }

    private final List<FeedVideo> videos = new ArrayList<>();
    private final LinkedHashSet<String> seenAwemeIds = new LinkedHashSet<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private String creatorSecUid;
    private String creatorNickname;
    private String creatorAvatarUrl;
    private PageRequester pageRequester;
    private boolean pageRequestInFlight;
    private long lastPageRequestAt;
    private boolean hasMore = true;

    private CreatorVideoRepository() {
    }

    public void setPageRequester(PageRequester requester) {
        this.pageRequester = requester;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setCreator(String secUid, String nickname, String avatarUrl) {
        reset();
        this.creatorSecUid = secUid;
        this.creatorNickname = nickname;
        this.creatorAvatarUrl = avatarUrl;
    }

    public String getCreatorSecUid() {
        return creatorSecUid;
    }

    public String getCreatorNickname() {
        return creatorNickname;
    }

    public String getCreatorAvatarUrl() {
        return creatorAvatarUrl;
    }

    public boolean hasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    /** Seeds the grid instantly with the video the user opened the profile from. */
    public void addVideoIfMatching(FeedVideo video) {
        if (video == null || creatorSecUid == null || creatorSecUid.isEmpty()) return;
        if (video.authorSecUid == null || !creatorSecUid.equals(video.authorSecUid)) return;
        if (enqueue(video)) {
            notifyChanged();
        }
    }

    public void addAwemeList(JSONArray awemeList) {
        if (awemeList == null) {
            pageRequestInFlight = false;
            return;
        }

        int added = 0;
        for (int i = 0; i < awemeList.length(); i++) {
            JSONObject item = awemeList.optJSONObject(i);
            if (item == null) continue;

            FeedVideo video = FeedVideo.fromAwemeItem(item);
            if (video == null) continue;
            if (creatorSecUid != null && !creatorSecUid.isEmpty()) {
                if (video.authorSecUid == null || video.authorSecUid.isEmpty()
                        || !creatorSecUid.equals(video.authorSecUid)) {
                    continue;
                }
            }
            if (enqueue(video)) {
                added++;
            }
        }

        Log.d(TAG, "addAwemeList: +" + added + " total=" + videos.size());
        pageRequestInFlight = false;
        if (added > 0) {
            notifyChanged();
        }
        // Grid is append-only; next pages are requested when the user scrolls near the end.
    }

    public int size() {
        return videos.size();
    }

    public List<FeedVideo> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(videos));
    }

    public FeedVideo getAt(int index) {
        if (index < 0 || index >= videos.size()) return null;
        return videos.get(index);
    }

    public int indexOf(String awemeId) {
        if (awemeId == null) return -1;
        for (int i = 0; i < videos.size(); i++) {
            if (awemeId.equals(videos.get(i).awemeId)) return i;
        }
        return -1;
    }

    public void reset() {
        videos.clear();
        seenAwemeIds.clear();
        creatorSecUid = null;
        creatorNickname = null;
        creatorAvatarUrl = null;
        pageRequestInFlight = false;
        hasMore = true;
        notifyChanged();
    }

    public void releasePageRequest() {
        pageRequestInFlight = false;
    }

    /** Request the next /aweme/post page (e.g. grid scrolled near the end). */
    public void kickstartPaging() {
        if (pageRequester == null || pageRequestInFlight || !hasMore) return;
        long now = System.currentTimeMillis();
        if (now - lastPageRequestAt < PAGE_REQUEST_COOLDOWN_MS) return;
        pageRequestInFlight = true;
        lastPageRequestAt = now;
        Log.d(TAG, "request next page total=" + videos.size() + " hasMore=" + hasMore);
        pageRequester.requestNextPage();
    }

    private boolean enqueue(FeedVideo video) {
        if (video == null || video.awemeId == null || video.awemeId.isEmpty()) return false;
        if (!seenAwemeIds.add(video.awemeId)) return false;
        trimSeenIdsIfNeeded();
        videos.add(video);
        return true;
    }

    private void trimSeenIdsIfNeeded() {
        while (seenAwemeIds.size() > MAX_SEEN_IDS) {
            Iterator<String> it = seenAwemeIds.iterator();
            it.next();
            it.remove();
        }
    }

    private void notifyChanged() {
        int size = videos.size();
        for (Listener listener : listeners) {
            listener.onCreatorVideosChanged(size);
        }
    }
}
