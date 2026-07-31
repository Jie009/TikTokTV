package mulin.tvdy.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mulin.tvdy.DouyinConstants;

/**
 * Minimal cover-thumbnail fetcher - just enough to show a still frame behind
 * the loading spinner while ExoPlayer buffers the real video, plus (see
 * {@link #loadWithBackdrop}) a blurred copy of the same image for the
 * pillarbox backdrop behind vertical videos. No caching or request
 * de-duplication; a full image library (Glide/Coil) would be overkill for a
 * single small thumbnail per video.
 */
final class SimpleImageLoader {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private SimpleImageLoader() {
    }

    static void load(String url, ImageView target) {
        target.setTag(url);
        if (url == null || url.isEmpty()) {
            target.setImageDrawable(null);
            return;
        }
        EXECUTOR.execute(() -> {
            Bitmap bitmap = fetch(url);
            MAIN_HANDLER.post(() -> {
                // Guard against the target having moved on to a different
                // video by the time this slow network fetch finishes.
                if (url.equals(target.getTag())) {
                    target.setImageBitmap(bitmap);
                }
            });
        });
    }

    /**
     * Like {@link #load}, but also derives a blurred copy of the same
     * (single) fetched bitmap for {@code backdropTarget} - used to fill the
     * pillarbox bars around a vertical video with a soft, zoomed version of
     * its own cover art instead of plain black. See {@link BlurUtils}.
     */
    static void loadWithBackdrop(String url, ImageView sharpTarget, ImageView backdropTarget) {
        sharpTarget.setTag(url);
        backdropTarget.setTag(url);
        if (url == null || url.isEmpty()) {
            sharpTarget.setImageDrawable(null);
            backdropTarget.setImageDrawable(null);
            return;
        }
        EXECUTOR.execute(() -> {
            Bitmap bitmap = fetch(url);
            Bitmap backdrop = BlurUtils.makeBackdrop(bitmap);
            MAIN_HANDLER.post(() -> {
                if (url.equals(sharpTarget.getTag())) {
                    sharpTarget.setImageBitmap(bitmap);
                }
                if (url.equals(backdropTarget.getTag())) {
                    backdropTarget.setImageBitmap(backdrop);
                }
            });
        });
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("User-Agent", DouyinConstants.DESKTOP_USER_AGENT);
            connection.setRequestProperty("Referer", DouyinConstants.REFERER);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try (InputStream in = connection.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
