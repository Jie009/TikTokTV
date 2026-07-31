package mulin.tvdy.pump;

import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors signed douyin.com feed XHRs at the WebView layer so feed JSON is
 * captured even when in-page hooks miss {@code responseText}.
 */
final class FeedResponseInterceptor {

    interface Callback {
        void onMirroredResponse(String url, int statusCode, @Nullable String body);
    }

    private FeedResponseInterceptor() {
    }

    static boolean shouldMirror(String url) {
        if (url == null || !url.contains("douyin.com")) {
            return false;
        }
        if (url.contains("/history/")) {
            return false;
        }
        return url.contains("/tab/feed")
                || url.contains("/module/feed")
                || url.contains("/aweme/favorite")
                || url.contains("/aweme/post")
                || url.contains("/mix/listcollection");
    }

    @Nullable
    static WebResourceResponse mirror(WebResourceRequest request, Callback callback) {
        if (request == null || request.getUrl() == null) {
            return null;
        }
        String urlStr = request.getUrl().toString();
        if (!shouldMirror(urlStr)) {
            return null;
        }
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        try {
            return execute(urlStr, request, callback);
        } catch (Exception e) {
            return null;
        }
    }

    private static WebResourceResponse execute(
            String urlStr,
            WebResourceRequest request,
            Callback callback) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(request.getMethod());
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);

        Map<String, String> reqHeaders = request.getRequestHeaders();
        if (reqHeaders != null) {
            for (Map.Entry<String, String> entry : reqHeaders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.flush();
        String cookie = cookieManager.getCookie(urlStr);
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }

        int status = conn.getResponseCode();
        InputStream raw = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        byte[] bodyBytes = raw != null ? readAll(raw) : new byte[0];

        String mimeType = "application/json";
        String encoding = "utf-8";
        String contentType = conn.getContentType();
        if (contentType != null) {
            int semi = contentType.indexOf(';');
            mimeType = semi >= 0 ? contentType.substring(0, semi).trim() : contentType.trim();
            int charsetIdx = contentType.toLowerCase().indexOf("charset=");
            if (charsetIdx >= 0) {
                encoding = contentType.substring(charsetIdx + 8).trim();
            }
        }

        Map<String, String> responseHeaders = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            responseHeaders.put(entry.getKey(), entry.getValue().get(0));
        }

        conn.disconnect();

        String body = bodyBytes.length > 0
                ? new String(bodyBytes, StandardCharsets.UTF_8)
                : null;
        callback.onMirroredResponse(urlStr, status, body);

        return new WebResourceResponse(
                mimeType,
                encoding,
                status,
                status == 200 ? "OK" : "HTTP " + status,
                responseHeaders,
                new ByteArrayInputStream(bodyBytes));
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = input.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
