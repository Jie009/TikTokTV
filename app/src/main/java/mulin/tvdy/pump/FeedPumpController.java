package mulin.tvdy.pump;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.ServiceWorkerClientCompat;
import androidx.webkit.ServiceWorkerControllerCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mulin.tvdy.DeviceUtils;
import mulin.tvdy.DouyinConstants;
import mulin.tvdy.data.FeedRepository;
import mulin.tvdy.data.PageRequester;
import mulin.tvdy.data.WatchedAwemeStore;

/**
 * Owns the hidden "data pump" WebView: loads the real douyin.com page once,
 * keeps it alive for as long as the host Activity lives, hooks fetch/XHR to
 * capture the signed feed responses the page fetches for itself, and feeds
 * parsed items into {@link FeedRepository}.
 * <p>
 * After the first successful feed capture, pagination is triggered by nudging
 * the page to scroll so it fetches the next batch with a valid Argus signature.
 */
public final class FeedPumpController implements PageRequester {

    private static final String TAG = "FeedPump";
    private static final int MAX_RENDERER_REBUILDS = 3;
    private static final long[] KICKSTART_DELAYS_PHONE_MS = {800, 2_000, 5_000, 10_000};
    private static final long[] KICKSTART_DELAYS_TV_MS = {800, 2_000, 5_000, 10_000, 20_000, 35_000, 50_000};
    /** If {@code onPageFinished} never fires (common on slow TV WebViews), kick anyway. */
    private static final long PAGE_INTERACTIVE_FALLBACK_MS = 20_000;
    private static final long PROACTIVE_FETCH_TIMEOUT_MS = 15_000;
    private static final long SCROLL_FALLBACK_COOLDOWN_MS = 5_000;

    public interface Listener {
        void onPumpStatus(String message);

        void onPumpError(String message);
    }

    private final Activity host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FeedRepository repository = FeedRepository.getInstance();
    private final boolean television;
    private final long[] kickstartDelays;
    private final List<Runnable> kickstartRunnables = new ArrayList<>();
    private ViewGroup container;
    private WebView webView;
    private Listener listener;
    private int rendererRebuilds = 0;
    private int kickstartGeneration = 0;
    private int apiCallsSeen = 0;
    private boolean pageInteractive = false;
    private final FeedPaginationState paginationState = new FeedPaginationState();
    private Runnable hookTicker;
    private Runnable pageInteractiveFallback;
    private Runnable proactiveFetchTimeout;
    private boolean signedFetchInFlight = false;
    private boolean historyFetchInFlight = false;
    private long lastScrollFallbackAt = 0;
    private boolean loggedIn = false;
    private boolean historySyncRequested = false;
    private boolean pendingHistorySync = false;
    private boolean playbackStartedNotified = false;
    private long historyCursor = 0;
    private int historyPagesFetched = 0;
    private static final int MAX_HISTORY_PAGES = 5;

    public FeedPumpController(Activity host) {
        this.host = host;
        this.television = DeviceUtils.isTelevision(host);
        this.kickstartDelays = television ? KICKSTART_DELAYS_TV_MS : KICKSTART_DELAYS_PHONE_MS;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** When {@code true}, kickstarts use a signed cold feed + history sync. */
    public void setLoggedIn(boolean loggedIn) {
        this.loggedIn = loggedIn;
        if (loggedIn) {
            WatchedAwemeStore.getInstance().bindSession();
        }
    }

    /** Pulls account watch history into {@link WatchedAwemeStore} after login. */
    public void syncWatchHistory() {
        historySyncRequested = true;
        historyCursor = 0;
        historyPagesFetched = 0;
        pendingHistorySync = false;
    }

    /**
     * Defers {@link #syncWatchHistory()} until {@link #notifyPlaybackStarted()}
     * so the watched-id filter does not empty the first feed batch.
     */
    public void scheduleWatchHistorySync() {
        pendingHistorySync = true;
        if (playbackStartedNotified && loggedIn && !historySyncRequested) {
            handler.postDelayed(() -> {
                if (!loggedIn || historySyncRequested) return;
                syncWatchHistory();
                fetchNextHistoryPage();
            }, 1_000);
        }
    }

    /** Reports playback to Douyin so future feed requests exclude watched items. */
    public void reportPlay(String awemeId, long playMs) {
        if (webView == null || awemeId == null || awemeId.isEmpty()) return;
        webView.evaluateJavascript(FeedHookScripts.buildReportPlayScript(awemeId, playMs), null);
    }

    public void start(ViewGroup container) {
        if (webView != null) return;
        this.container = container;
        repository.setPageRequester(this);
        notifyStatus(television ? "正在加载抖音页面（TV 模式）…" : "正在加载抖音页面…");
        createWebView();
    }

    public void onResume() {
        if (webView != null) webView.onResume();
    }

    public void onPause() {
        if (webView != null) webView.onPause();
    }

    public void onDestroy() {
        cancelKickstart();
        stopHookTicker();
        cancelPageInteractiveFallback();
        cancelProactiveFetchTimeout();
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            container.removeView(webView);
            webView.destroy();
            webView = null;
        }
    }

    @Override
    public void requestNextPage() {
        if (webView == null) return;
        if (paginationState.isReady()) {
            int nextIndex = paginationState.getRefreshIndex();
            Log.d(TAG, "proactive pagination refresh_index=" + nextIndex);
            notifyStatus("正在加载更多视频…");
            scheduleScrollPaginationTimeout();
            String script = FeedHookScripts.buildProactiveFetchScript(
                    paginationState.getLastFeedUrl(),
                    paginationState.getMaxCursor(),
                    nextIndex);
            webView.evaluateJavascript(script, null);
            scheduleScrollPaginationFallback();
        } else if (loggedIn && pageInteractive && !historySyncRequested && !signedFetchInFlight) {
            webView.evaluateJavascript(FeedHookScripts.SIGNER_READY, value -> {
                if (webView == null) return;
                if ("true".equals(value)) {
                    runSignedColdFeed();
                } else {
                    Log.d(TAG, "signer not ready, scroll kickstart");
                    repository.releasePageRequest();
                    webView.evaluateJavascript(FeedHookScripts.TRIGGER_INITIAL_FEED, null);
                }
            });
        } else {
            Log.d(TAG, "passive kickstart (anonymous / pagination not ready)");
            webView.evaluateJavascript(FeedHookScripts.TRIGGER_INITIAL_FEED, null);
            repository.releasePageRequest();
        }
    }

    private void runSignedColdFeed() {
        Log.d(TAG, "signed cold feed (logged in)");
        notifyStatus("正在拉取账号推荐…");
        signedFetchInFlight = true;
        scheduleScrollPaginationTimeout();
        webView.evaluateJavascript(FeedHookScripts.buildFreshFeedScript(), null);
    }

    private Runnable scrollPaginationFallback;

    private void scheduleScrollPaginationFallback() {
        if (scrollPaginationFallback != null) {
            handler.removeCallbacks(scrollPaginationFallback);
        }
        scrollPaginationFallback = () -> {
            if (webView == null || repository.bufferSize() >= 5) return;
            Log.d(TAG, "scroll pagination fallback");
            webView.evaluateJavascript(FeedHookScripts.TRIGGER_PAGE_FEED, null);
        };
        handler.postDelayed(scrollPaginationFallback, 5_000);
    }

    private void cancelScrollPaginationFallback() {
        if (scrollPaginationFallback != null) {
            handler.removeCallbacks(scrollPaginationFallback);
            scrollPaginationFallback = null;
        }
    }

    public void notifyPlaybackStarted() {
        if (playbackStartedNotified) return;
        playbackStartedNotified = true;
        if (!loggedIn || historySyncRequested) return;
        handler.postDelayed(() -> {
            if (!loggedIn || historySyncRequested) return;
            syncWatchHistory();
            fetchNextHistoryPage();
        }, 3_000);
    }

    public void reloadFeed() {
        if (webView == null) return;
        resetPumpSession();
        notifyStatus("正在重新加载…");
        webView.reload();
    }

    public void hardRestart() {
        cancelKickstart();
        stopHookTicker();
        cancelPageInteractiveFallback();
        resetPumpSession();
        rendererRebuilds = 0;
        if (webView != null) {
            container.removeView(webView);
            webView.destroy();
            webView = null;
        }
        notifyStatus("正在重新初始化…");
        createWebView();
    }

    private void resetPumpSession() {
        apiCallsSeen = 0;
        pageInteractive = false;
        paginationState.reset();
        cancelProactiveFetchTimeout();
        cancelScrollPaginationFallback();
        signedFetchInFlight = false;
        historyFetchInFlight = false;
        historySyncRequested = false;
        historyCursor = 0;
        historyPagesFetched = 0;
        pendingHistorySync = false;
        playbackStartedNotified = false;
    }

    private void fetchNextHistoryPage() {
        if (!historySyncRequested || webView == null) return;
        if (historyPagesFetched >= MAX_HISTORY_PAGES) {
            historySyncRequested = false;
            return;
        }
        Log.d(TAG, "fetching watch history page cursor=" + historyCursor);
        historyFetchInFlight = true;
        webView.evaluateJavascript(FeedHookScripts.buildFetchHistoryScript(historyCursor), null);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void createWebView() {
        webView = new WebView(host);
        webView.setAlpha(0f);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.setClickable(false);
        webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (television) {
            webView.setLayerType(View.LAYER_TYPE_NONE, null);
        } else {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        container.addView(webView, 0, new ViewGroup.LayoutParams(
                DouyinConstants.PUMP_VIEWPORT_WIDTH,
                DouyinConstants.PUMP_VIEWPORT_HEIGHT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUserAgentString(DouyinConstants.DESKTOP_USER_AGENT);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(
                    WebView.RENDERER_PRIORITY_IMPORTANT, /* waivedWhenNotVisible= */ false);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        cookieManager.flush();

        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        installDocumentStartHooks();
        setupServiceWorkerInterception();

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage != null) {
                    Log.d(TAG, "console: " + consoleMessage.message());
                }
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 50) {
                    markPageInteractive("页面加载 " + newProgress + "%，等待视频…");
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                pageInteractive = false;
                injectHooks();
                startHookTicker();
                schedulePageInteractiveFallback();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectHooks();
                probePageState();
                markPageInteractive("页面已加载，等待视频…");
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null) {
                    String url = request.getUrl().toString();
                    if (isFeedApiUrl(url)) {
                        handler.post(() -> apiCallsSeen++);
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (failingUrl == null || !failingUrl.contains("douyin.com")) return;
                Log.w(TAG, "main frame load error: " + description);
                notifyError("页面加载失败：" + description);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request == null || !request.isForMainFrame()) return;
                String desc = error != null ? error.getDescription().toString() : "unknown";
                Log.w(TAG, "main frame load error: " + desc);
                notifyError("页面加载失败：" + desc);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request == null || !request.isForMainFrame()) return;
                int code = errorResponse != null ? errorResponse.getStatusCode() : -1;
                Log.w(TAG, "main frame HTTP error: " + code);
                if (code >= 400) {
                    notifyError("页面请求失败 (HTTP " + code + ")");
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.w(TAG, "renderer process gone (crashed=" + detail.didCrash() + "), rebuilding pump");
                container.removeView(view);
                view.destroy();
                webView = null;
                rendererRebuilds++;
                if (rendererRebuilds > MAX_RENDERER_REBUILDS) {
                    notifyError("WebView 多次崩溃，请更新系统 WebView");
                    return true;
                }
                notifyStatus("WebView 重启中…");
                handler.post(() -> createWebView());
                return true;
            }
        });

        webView.loadUrl(DouyinConstants.FEED_URL);
    }

    private void schedulePageInteractiveFallback() {
        cancelPageInteractiveFallback();
        pageInteractiveFallback = () -> {
            if (!pageInteractive && webView != null && repository.bufferSize() == 0) {
                Log.w(TAG, "forcing interactive kickstart (onPageFinished slow or missing)");
                markPageInteractive("页面加载较慢，继续等待视频…");
            }
        };
        handler.postDelayed(pageInteractiveFallback, PAGE_INTERACTIVE_FALLBACK_MS);
    }

    private void cancelPageInteractiveFallback() {
        if (pageInteractiveFallback != null) {
            handler.removeCallbacks(pageInteractiveFallback);
            pageInteractiveFallback = null;
        }
    }

    private void scheduleScrollPaginationTimeout() {
        cancelProactiveFetchTimeout();
        proactiveFetchTimeout = () -> {
            Log.w(TAG, "page feed request timeout");
            signedFetchInFlight = false;
            repository.releasePageRequest();
        };
        handler.postDelayed(proactiveFetchTimeout, PROACTIVE_FETCH_TIMEOUT_MS);
    }

    private void cancelProactiveFetchTimeout() {
        if (proactiveFetchTimeout != null) {
            handler.removeCallbacks(proactiveFetchTimeout);
            proactiveFetchTimeout = null;
        }
    }

    private void fallbackToScrollTrigger(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastScrollFallbackAt < SCROLL_FALLBACK_COOLDOWN_MS) {
            Log.d(TAG, "scroll fallback suppressed (" + reason + ")");
            return;
        }
        lastScrollFallbackAt = now;
        Log.d(TAG, "scroll fallback: " + reason);
        if (webView != null) {
            webView.evaluateJavascript(FeedHookScripts.TRIGGER_INITIAL_FEED, null);
        }
    }

    private void markPageInteractive(String status) {
        if (pageInteractive) return;
        pageInteractive = true;
        cancelPageInteractiveFallback();
        notifyStatus(status);
        scheduleFeedKickstart();
    }

    private void startHookTicker() {
        stopHookTicker();
        hookTicker = new Runnable() {
            @Override
            public void run() {
                if (repository.bufferSize() > 0 || webView == null) return;
                injectHooks();
                handler.postDelayed(this, 2_000);
            }
        };
        handler.postDelayed(hookTicker, 2_000);
    }

    private void stopHookTicker() {
        if (hookTicker != null) {
            handler.removeCallbacks(hookTicker);
            hookTicker = null;
        }
    }

    private static boolean isFeedApiUrl(String url) {
        if (url == null || !url.contains("douyin.com")) return false;
        if (url.contains("/history/")) return false;
        return url.contains("/tab/feed")
                || url.contains("/module/feed")
                || (url.contains("/aweme/") && url.contains("/feed"));
    }

    private static boolean isHistoryApiUrl(String url) {
        return url != null && url.contains("douyin.com") && url.contains("history/read");
    }

    private void installDocumentStartHooks() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        FeedHookScripts.INSTALL_HOOKS,
                        Collections.singleton("*"));
                Log.d(TAG, "document-start hooks installed");
            }
        } catch (Exception e) {
            Log.w(TAG, "document-start hooks unavailable", e);
        }
    }

    private void scheduleFeedKickstart() {
        cancelKickstart();
        final int generation = kickstartGeneration;
        for (int i = 0; i < kickstartDelays.length; i++) {
            final int attempt = i + 1;
            Runnable runnable = () -> runFeedKickstart(generation, attempt);
            kickstartRunnables.add(runnable);
            handler.postDelayed(runnable, kickstartDelays[i]);
        }
    }

    private void cancelKickstart() {
        kickstartGeneration++;
        for (Runnable runnable : kickstartRunnables) {
            handler.removeCallbacks(runnable);
        }
        kickstartRunnables.clear();
    }

    private void runFeedKickstart(int generation, int attempt) {
        if (generation != kickstartGeneration || webView == null) return;
        if (repository.bufferSize() > 0) return;

        Log.d(TAG, "feed kickstart attempt " + attempt + " apiCallsSeen=" + apiCallsSeen);
        injectHooks();
        requestNextPage();
        probePageState();

        if (attempt == 2) {
            notifyStatus("仍在等待视频，尝试触发加载…");
        } else if (attempt == kickstartDelays.length) {
            if (apiCallsSeen == 0) {
                notifyStatus("未检测到 API 请求，请更新系统 WebView 或等待自动重试");
                notifyError("WebView 未捕获到抖音 API 请求");
            } else {
                notifyStatus("API 已响应但无视频，等待自动重试…");
                notifyError("长时间未获取到视频");
            }
        }
    }

    private void probePageState() {
        if (webView != null) {
            webView.evaluateJavascript(FeedHookScripts.PROBE_PAGE_STATE, null);
        }
    }

    private void setupServiceWorkerInterception() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
                ServiceWorkerControllerCompat swController = ServiceWorkerControllerCompat.getInstance();
                swController.setServiceWorkerClient(new ServiceWorkerClientCompat() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                        return null;
                    }
                });
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to install service worker interception", e);
        }
    }

    private void injectHooks() {
        if (webView != null) {
            webView.evaluateJavascript(FeedHookScripts.INSTALL_HOOKS, null);
        }
    }

    private void notifyStatus(String message) {
        if (listener != null) listener.onPumpStatus(message);
    }

    private void notifyError(String message) {
        if (listener != null) listener.onPumpError(message);
    }

    private class JsBridge {
        @JavascriptInterface
        public void onFeedData(final String url, final String json) {
            handler.post(() -> handleFeedData(url, json));
        }

        @JavascriptInterface
        public void onUrlSeen(final String url, final String note) {
            handler.post(() -> {
                apiCallsSeen++;
                Log.d(TAG, "saw api call: " + url + " (" + note + ")");
            });
        }

        @JavascriptInterface
        public void onPageProbe(final String json) {
            handler.post(() -> handlePageProbe(json));
        }

        @JavascriptInterface
        public void onProactiveFetchOk(final String url) {
            handler.post(() -> {
                cancelProactiveFetchTimeout();
                signedFetchInFlight = false;
                Log.d(TAG, "signed cold feed dispatched: " + url);
            });
        }

        @JavascriptInterface
        public void onProactiveFetchFailed(final String reason) {
            handler.post(() -> handleProactiveFetchFailed(reason));
        }

        @JavascriptInterface
        public void onHistoryData(final String url, final String json) {
            handler.post(() -> handleHistoryData(url, json));
        }
    }

    private void handleProactiveFetchFailed(String reason) {
        if (historyFetchInFlight) {
            historyFetchInFlight = false;
            Log.w(TAG, "history fetch failed: " + reason);
            return;
        }
        cancelProactiveFetchTimeout();
        cancelScrollPaginationFallback();
        signedFetchInFlight = false;
        Log.w(TAG, "proactive fetch failed: " + reason + ", falling back to scroll");
        repository.releasePageRequest();
        if (webView != null) {
            webView.evaluateJavascript(FeedHookScripts.TRIGGER_PAGE_FEED, null);
        }
    }

    private void handleHistoryData(String url, String json) {
        historyFetchInFlight = false;
        if (json == null || json.isEmpty() || !json.trim().startsWith("{")) return;
        try {
            handleHistoryData(url, new JSONObject(json.trim()));
        } catch (Exception e) {
            Log.w(TAG, "history data parse failed", e);
        }
    }

    private void handlePageProbe(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(json);
            int w = obj.optInt("w", 0);
            Log.d(TAG, "page probe: " + w + "x" + obj.optInt("h", 0)
                    + " videos=" + obj.optInt("videos", 0)
                    + " hooked=" + obj.optBoolean("hooked", false));
            if (w > 0 && w < 320) {
                notifyStatus("视口过小(" + w + "px)，正在尝试修复…");
                requestNextPage();
            }
        } catch (Exception e) {
            Log.w(TAG, "page probe parse failed: " + json, e);
        }
    }

    private void handleFeedData(String url, String json) {
        if (json == null || json.isEmpty()) return;
        String trimmed = json.trim();
        if (!trimmed.startsWith("{")) {
            Log.w(TAG, "non-JSON feed payload from " + url + ": "
                    + trimmed.substring(0, Math.min(trimmed.length(), 40)));
            if (isFeedApiUrl(url)) {
                signedFetchInFlight = false;
                repository.releasePageRequest();
            }
            return;
        }
        try {
            JSONObject root = new JSONObject(trimmed);
            cancelProactiveFetchTimeout();
            cancelScrollPaginationFallback();
            signedFetchInFlight = false;

            if (isHistoryApiUrl(url)) {
                handleHistoryData(url, root);
                return;
            }

            if (!isFeedApiUrl(url)) {
                return;
            }

            paginationState.updateFromCapture(url, root);

            JSONArray list = root.optJSONArray("aweme_list");
            if (list == null) {
                Log.d(TAG, "no aweme_list in payload from " + url
                        + " status_code=" + root.optInt("status_code", -1));
                repository.releasePageRequest();
                if (paginationState.hasMore() && webView != null) {
                    webView.evaluateJavascript(FeedHookScripts.TRIGGER_PAGE_FEED, null);
                }
                return;
            }
            cancelKickstart();
            stopHookTicker();
            notifyStatus("已获取视频，准备播放…");
            repository.addAwemeList(list);
        } catch (Exception e) {
            Log.e(TAG, "failed to parse feed json from " + url, e);
            repository.releasePageRequest();
        }
    }

    private void handleHistoryData(String url, JSONObject root) {
        JSONArray list = root.optJSONArray("aweme_list");
        if (list != null && list.length() > 0) {
            WatchedAwemeStore.getInstance().ingestHistoryList(list);
            Log.d(TAG, "history sync: +" + list.length()
                    + " ids, storeSize=" + WatchedAwemeStore.getInstance().size());
        }
        historyPagesFetched++;
        if (root.has("max_cursor")) {
            long next = root.optLong("max_cursor", 0);
            boolean hasMore = root.optInt("has_more", 0) != 0;
            if (hasMore && next > 0 && next != historyCursor
                    && historyPagesFetched < MAX_HISTORY_PAGES) {
                historyCursor = next;
                fetchNextHistoryPage();
                return;
            }
        }
        historySyncRequested = false;
        Log.d(TAG, "history sync complete, pages=" + historyPagesFetched);
    }
}
