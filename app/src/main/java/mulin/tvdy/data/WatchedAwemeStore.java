package mulin.tvdy.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import mulin.tvdy.DouyinConstants;

/**
 * Remembers which {@code aweme_id}s the current Douyin account has already
 * watched — both from the account's server-side history and from playback in
 * this app — so the feed buffer can skip them before they reach ExoPlayer.
 */
public final class WatchedAwemeStore {

    private static final String PREFS = "watched_aweme_store";
    private static final int MAX_IDS = 5_000;

    private static WatchedAwemeStore instance;

    public static synchronized WatchedAwemeStore getInstance() {
        if (instance == null) {
            instance = new WatchedAwemeStore();
        }
        return instance;
    }

    private Context appContext;
    private String sessionKey = "";
    private final LinkedHashSet<String> watchedIds = new LinkedHashSet<>();

    private WatchedAwemeStore() {
    }

    public void init(Context context) {
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
        bindSession();
    }

    /** Re-loads the watched-id set when the Douyin session cookie changes. */
    public void bindSession() {
        String sessionId = readSessionId();
        String key = sessionId.isEmpty() ? "anon" : String.valueOf(sessionId.hashCode());
        if (key.equals(sessionKey)) return;
        sessionKey = key;
        watchedIds.clear();
        if (appContext == null) return;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(sessionKey, null);
        if (saved != null) {
            for (String id : saved) {
                if (id != null && !id.isEmpty()) watchedIds.add(id);
            }
        }
    }

    public boolean isWatched(String awemeId) {
        return awemeId != null && !awemeId.isEmpty() && watchedIds.contains(awemeId);
    }

    public void markWatched(String awemeId) {
        if (awemeId == null || awemeId.isEmpty()) return;
        if (!watchedIds.add(awemeId)) return;
        trimIfNeeded();
        persist();
    }

    /** Parses a {@code history/read} (or similar) {@code aweme_list} payload. */
    public void ingestHistoryList(JSONArray awemeList) {
        if (awemeList == null) return;
        boolean changed = false;
        for (int i = 0; i < awemeList.length(); i++) {
            JSONObject item = awemeList.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("aweme_id", "");
            if (id.isEmpty()) continue;
            if (watchedIds.add(id)) changed = true;
        }
        if (changed) {
            trimIfNeeded();
            persist();
        }
    }

    public int size() {
        return watchedIds.size();
    }

    public void clearSession() {
        watchedIds.clear();
        sessionKey = "";
    }

    private void trimIfNeeded() {
        while (watchedIds.size() > MAX_IDS) {
            Iterator<String> it = watchedIds.iterator();
            it.next();
            it.remove();
        }
    }

    private void persist() {
        if (appContext == null || sessionKey.isEmpty()) return;
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(sessionKey, new LinkedHashSet<>(watchedIds))
                .apply();
    }

    private static String readSessionId() {
        String cookie = CookieManager.getInstance().getCookie(DouyinConstants.FEED_URL);
        if (cookie == null) return "";
        for (String pair : cookie.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if ("sessionid".equals(pair.substring(0, eq).trim())) {
                return pair.substring(eq + 1).trim();
            }
        }
        return "";
    }
}
