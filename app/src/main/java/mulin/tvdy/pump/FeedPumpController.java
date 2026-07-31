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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mulin.tvdy.DeviceUtils;
import mulin.tvdy.DouyinConstants;
import mulin.tvdy.data.FeedRepository;
import mulin.tvdy.data.PageRequester;

/**
 * Owns the hidden "data pump" WebView: loads the real douyin.com page once,
 * keeps it alive for as long as the host Activity lives, hooks fetch/XHR to
 * capture the signed feed responses the page fetches for itself, and feeds
 * parsed items into {@link FeedRepository}.
 */
public final class FeedPumpController implements PageRequester {

    private static final String TAG = "FeedPump";
    private static final int MAX_RENDERER_REBUILDS = 3;
    private static final long[] KICKSTART_DELAYS_PHONE_MS = {800, 2_000, 5_000, 10_000};
    private static final long[] KICKSTART_DELAYS_TV_MS = {800, 2_000, 5_000, 10_000, 20_000, 35_000, 50_000};
    /** If {@code onPageFinished} never fires (common on slow TV WebViews), kick anyway. */
    private static final long PAGE_INTERACTIVE_FALLBACK_MS = 20_000;

    public interface Listener {
        void onPumpStatus(String message);

        void onPumpError(String message);
    }

    private final Activity host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FeedRepository repository = FeedRepository.getInstance();
    private final boolean television;
    private final long[] kickstartDelays;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private ViewGroup container;
    private WebView webView;
    private Listener listener;
    private int rendererRebuilds = 0;
    private int kickstartGeneration = 0;
    private int apiCallsSeen = 0;
    private boolean pageInteractive = false;
    private final List<Runnable> kickstartRunnables = new ArrayList<>();
    private Runnable hookTicker;
    private Runnable pageInteractiveFallback;

    public FeedPumpController(Activity host) {
        this.host = host;
        this.television = DeviceUtils.isTelevision(host);
        this.kickstartDelays = television ? KICKSTART_DELAYS_TV_MS : KICKSTART_DELAYS_PHONE_MS;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
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
        handler.removeCallbacksAndMessages(null);
        networkExecutor.shutdownNow();
        if (webView != null) {
            container.removeView(webView);
            webView.destroy();
            webView = null;
        }
    }

    @Override
    public void requestNextPage() {
        if (webView == null) return;
        webView.evaluateJavascript(FeedHookScripts.TRIGGER_INITIAL_FEED, null);
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
        settings.setDatabaseEnabled(true);
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
                        if ("GET".equalsIgnoreCase(request.getMethod())) {
                            mirrorGetFeedRequest(url, request);
                        }
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
        return url.contains("/aweme/") && (url.contains("feed") || url.contains("module"));
    }

    private void mirrorGetFeedRequest(String url, WebResourceRequest request) {
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(20_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", DouyinConstants.DESKTOP_USER_AGENT);
                connection.setRequestProperty("Referer", DouyinConstants.REFERER);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) {
                    connection.setRequestProperty("Cookie", cookie);
                }
                Map<String, String> headers = request.getRequestHeaders();
                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            connection.setRequestProperty(entry.getKey(), entry.getValue());
                        }
                    }
                }
                int code = connection.getResponseCode();
                InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) return;
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                String body = buffer.toString(StandardCharsets.UTF_8.name());
                Log.d(TAG, "mirrored GET feed response code=" + code + " bytes=" + body.length());
                handler.post(() -> handleFeedData(url, body));
            } catch (Exception e) {
                Log.w(TAG, "mirror GET feed failed for " + url, e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
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
        repository.kickstartPaging();
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
        try {
            JSONObject root = new JSONObject(json);
            JSONArray list = root.optJSONArray("aweme_list");
            if (list == null) {
                Log.d(TAG, "no aweme_list in payload from " + url
                        + " status_code=" + root.optInt("status_code", -1));
                return;
            }
            cancelKickstart();
            stopHookTicker();
            notifyStatus("已获取视频，准备播放…");
            repository.addAwemeList(list);
        } catch (Exception e) {
            Log.e(TAG, "failed to parse feed json from " + url, e);
        }
    }
}
