package mulin.tvdy.auth;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tiny single-purpose local HTTP server so a phone/PC browser already on
 * the same Wi-Fi can hand this app a pasted Cookie string directly, instead
 * of the user having to type a 200+ character string with a TV remote's
 * on-screen keyboard (see {@code PlayerActivity}'s cookie-paste overlay,
 * which this backs as the faster of its two options).
 * <p>
 * Hand-rolled on a raw {@link ServerSocket} rather than pulling in a real
 * server library: the protocol surface needed is exactly one GET (serve a
 * static form page) and one POST (read a single url-encoded field), so a
 * full HTTP stack would be pure overhead. Each connection is handled on its
 * own short-lived thread and closed immediately after one request -
 * there's no keep-alive/pipelining support, which a plain browser form
 * submit doesn't need anyway.
 */
public final class CookieHandoffServer {

    private static final String TAG = "CookieHandoffServer";
    private static final int PORT = 8899;

    public interface Callback {
        /** Called on the main thread once a browser has POSTed a (non-empty) cookie string. */
        void onCookieReceived(String rawCookie);
    }

    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public CookieHandoffServer(Callback callback) {
        this.callback = callback;
    }

    /**
     * Binds the server and starts accepting connections.
     *
     * @return the URL to show/enter on another device, or {@code null} if
     * no usable LAN address was found (e.g. no Wi-Fi/Ethernet) or the port
     * couldn't be bound - callers should just hide that option in that case.
     */
    public String start() {
        if (running.get()) return null;
        String ip = getLanIpAddress();
        if (ip == null) return null;
        try {
            serverSocket = new ServerSocket(PORT);
        } catch (IOException e) {
            Log.w(TAG, "failed to bind port " + PORT, e);
            return null;
        }
        running.set(true);
        new Thread(this::acceptLoop, "cookie-handoff-accept").start();
        return "http://" + ip + ":" + serverSocket.getLocalPort() + "/";
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                break; // expected once stop() closes serverSocket
            }
            new Thread(() -> handleConnection(socket), "cookie-handoff-conn").start();
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(10_000);
            InputStream in = s.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String method = requestLine.split(" ", 2)[0];

            int contentLength = 0;
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(header.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            OutputStream out = s.getOutputStream();
            if ("POST".equalsIgnoreCase(method)) {
                byte[] body = readExactly(in, contentLength);
                String cookie = extractFormField(new String(body, StandardCharsets.UTF_8), "cookie");
                if (cookie != null && !cookie.trim().isEmpty()) {
                    mainHandler.post(() -> callback.onCookieReceived(cookie));
                }
                writeHtmlResponse(out, SUCCESS_HTML);
            } else {
                writeHtmlResponse(out, FORM_HTML);
            }
        } catch (IOException e) {
            Log.w(TAG, "connection error", e);
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] buf = new byte[Math.max(0, length)];
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) break;
            read += n;
        }
        return buf;
    }

    /** Reads one CRLF (or bare LF) terminated line as raw bytes - deliberately not a buffered reader, so it never reads past the body that follows the header block. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (!any) return null;
        return buf.toString("UTF-8");
    }

    private static String extractFormField(String body, String field) {
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (!pair.substring(0, eq).equals(field)) continue;
            try {
                return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            } catch (Exception e) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static void writeHtmlResponse(OutputStream out, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    /** First non-loopback IPv4 address on an "up" interface - i.e. this device's Wi-Fi/Ethernet LAN address. */
    private static String getLanIpAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static final String FORM_HTML = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>粘贴Cookie到电视</title><style>"
            + "body{font-family:sans-serif;background:#111;color:#fff;padding:20px;margin:0;}"
            + "textarea{width:100%;height:160px;box-sizing:border-box;font-size:16px;"
            + "background:#222;color:#fff;border:1px solid #444;border-radius:8px;padding:10px;}"
            + "button{width:100%;padding:14px;margin-top:14px;font-size:16px;background:#FE2C55;"
            + "color:#fff;border:none;border-radius:8px;}"
            + "</style></head><body>"
            + "<h3>粘贴 Cookie 到电视</h3>"
            + "<p>先在<b>这台设备</b>的浏览器登录 douyin.com，用开发者工具复制完整 Cookie（需包含 sessionid），粘贴到下方后提交</p>"
            + "<form method=\"post\" action=\"/cookie\">"
            + "<textarea name=\"cookie\" placeholder=\"sessionid=xxxx; ttwid=yyyy; ...\" autofocus></textarea>"
            + "<button type=\"submit\">发送到电视</button>"
            + "</form></body></html>";

    private static final String SUCCESS_HTML = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>已发送</title><style>body{font-family:sans-serif;background:#111;color:#fff;"
            + "padding:40px;text-align:center;margin:0;}</style></head><body>"
            + "<h3>已发送到电视</h3><p>请查看电视是否显示登录成功，这个页面可以关闭了</p></body></html>";
}
