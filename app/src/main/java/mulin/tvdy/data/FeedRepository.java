package mulin.tvdy.data;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * App-wide singleton buffer of playable videos, fed by the hidden data-pump
 * WebView and drained by the player UI.
 * <p>
 * Not thread-safe by design: every caller in this app (the pump's
 * JavascriptInterface callbacks and the player Activity) runs on the main
 * thread, so no internal locking is done. If a future caller needs to touch
 * this from a background thread, add synchronization then.
 */
public final class FeedRepository {

    private static final String TAG = "FeedRepository";

    // Once the buffer drops below this many un-watched videos, ask the pump
    // for another page rather than waiting for it to run dry.
    private static final int LOW_WATER_MARK = 5;

    // Caps memory growth of the dedup set over a long-running session; oldest
    // ids are evicted first since they're the least likely to reappear.
    private static final int MAX_SEEN_IDS = 2000;

    private static FeedRepository instance;

    public static synchronized FeedRepository getInstance() {
        if (instance == null) {
            instance = new FeedRepository();
        }
        return instance;
    }

    /** Notified whenever the buffer size changes (new data in, or one consumed). */
    public interface Listener {
        void onBufferChanged(int size);
    }

    private final ArrayDeque<FeedVideo> queue = new ArrayDeque<>();
    private final LinkedHashSet<String> seenAwemeIds = new LinkedHashSet<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private PageRequester pageRequester;
    private boolean pageRequestInFlight = false;

    private FeedRepository() {
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

    /**
     * Parses a raw {@code aweme_list} JSON array, drops items with no
     * playable url (ads, live cards, ...) and items already seen, and
     * enqueues the rest.
     */
    public void addAwemeList(JSONArray awemeList) {
        pageRequestInFlight = false;
        if (awemeList == null) {
            maybeRequestMore();
            return;
        }

        int added = 0;
        int skippedNoUrl = 0;
        int skippedDup = 0;
        for (int i = 0; i < awemeList.length(); i++) {
            JSONObject item = awemeList.optJSONObject(i);
            FeedVideo video = FeedVideo.fromAwemeItem(item);
            if (video == null) {
                skippedNoUrl++;
                continue;
            }
            if (!seenAwemeIds.add(video.awemeId)) {
                skippedDup++;
                continue;
            }
            trimSeenIdsIfNeeded();
            queue.addLast(video);
            added++;
        }

        Log.d(TAG, "addAwemeList: +" + added + " noUrl=" + skippedNoUrl
                + " dup=" + skippedDup + " bufferSize=" + queue.size());

        if (added > 0) {
            notifyBufferChanged();
        }
        maybeRequestMore();
    }

    /** Removes and returns the next video to play, or {@code null} if the buffer is empty. */
    public FeedVideo pollNext() {
        FeedVideo next = queue.pollFirst();
        notifyBufferChanged();
        maybeRequestMore();
        return next;
    }

    public int bufferSize() {
        return queue.size();
    }

    /** Ask the pump for another page when the buffer is below {@link #LOW_WATER_MARK}. */
    public void kickstartPaging() {
        maybeRequestMore();
    }

    private void maybeRequestMore() {
        if (pageRequester == null || pageRequestInFlight) return;
        if (queue.size() >= LOW_WATER_MARK) return;
        pageRequestInFlight = true;
        pageRequester.requestNextPage();
    }

    private void trimSeenIdsIfNeeded() {
        while (seenAwemeIds.size() > MAX_SEEN_IDS) {
            Iterator<String> it = seenAwemeIds.iterator();
            it.next();
            it.remove();
        }
    }

    private void notifyBufferChanged() {
        int size = queue.size();
        for (Listener listener : listeners) {
            listener.onBufferChanged(size);
        }
    }
}
