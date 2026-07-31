package mulin.tvdy.player;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import mulin.tvdy.R;

/**
 * One full-screen video layer used in the dual-page TikTok-style slide
 * transition: the outgoing page shows a frozen snapshot while the incoming
 * page (already playing off-screen) slides into view.
 */
final class VideoSlidePage {

    final FrameLayout root;
    final ImageView backdropImage;
    final ImageView coverImage;
    final ImageView snapshotImage;
    final PlayerView playerView;

    /** When false, async cover loads must not re-opaque the mask over live video. */
    private boolean coverMaskActive = false;

    VideoSlidePage(View rootView) {
        root = (FrameLayout) rootView;
        backdropImage = root.findViewById(R.id.backdropImage);
        coverImage = root.findViewById(R.id.coverImage);
        snapshotImage = root.findViewById(R.id.snapshotImage);
        playerView = root.findViewById(R.id.playerView);
        playerView.setShutterBackgroundColor(Color.TRANSPARENT);
        playerView.setKeepContentOnPlayerReset(true);
    }

    void attachPlayer(ExoPlayer player) {
        playerView.setPlayer(player);
    }

    void detachPlayer() {
        playerView.setPlayer(null);
    }

    /**
     * Composites blurred pillarbox backdrop + fitted video into one bitmap.
     * TextureView does not render into {@link View#draw(Canvas)}, so we
     * assemble the layers manually to match on-screen layout.
     */
    Bitmap capturePageSnapshot() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0) {
            return captureVideoFrame();
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);

        if (backdropImage.getDrawable() != null) {
            backdropImage.draw(canvas);
        } else {
            canvas.drawColor(Color.BLACK);
        }

        Bitmap video = captureVideoFrame();
        if (video != null) {
            Rect dest = fitCenterRect(w, h, video.getWidth(), video.getHeight());
            canvas.drawBitmap(video, null, dest, null);
        }

        return result;
    }

    Bitmap captureVideoFrame() {
        View surface = playerView.getVideoSurfaceView();
        if (surface instanceof TextureView) {
            return ((TextureView) surface).getBitmap();
        }
        return null;
    }

    private static Rect fitCenterRect(int viewW, int viewH, int contentW, int contentH) {
        float viewAspect = (float) viewW / viewH;
        float contentAspect = (float) contentW / contentH;
        int dw;
        int dh;
        int dx;
        int dy;
        if (contentAspect > viewAspect) {
            dw = viewW;
            dh = (int) (viewW / contentAspect);
            dx = 0;
            dy = (viewH - dh) / 2;
        } else {
            dh = viewH;
            dw = (int) (viewH * contentAspect);
            dy = 0;
            dx = (viewW - dw) / 2;
        }
        return new Rect(dx, dy, dx + dw, dy + dh);
    }

    void showSnapshot(Bitmap frame) {
        if (frame == null) {
            clearSnapshot();
            return;
        }
        snapshotImage.setImageBitmap(frame);
        snapshotImage.setVisibility(View.VISIBLE);
    }

    void clearSnapshot() {
        snapshotImage.setVisibility(View.GONE);
        snapshotImage.setImageBitmap(null);
    }

    void stageForSlide(float offScreenY) {
        clearSnapshot();
        showBlackUntilCover();
        root.animate().cancel();
        root.setTranslationY(offScreenY);
    }

    void resetCoverMaskForPlayback() {
        coverMaskActive = true;
        coverImage.animate().cancel();
        coverImage.setAlpha(1f);
    }

    boolean isCoverMaskActive() {
        return coverMaskActive;
    }

    /** Opaque black (or later the cover bitmap) fully masks the player surface. */
    void showBlackUntilCover() {
        coverMaskActive = true;
        coverImage.animate().cancel();
        coverImage.setImageDrawable(null);
        coverImage.setAlpha(1f);
        backdropImage.setImageDrawable(null);
    }

    void revealCoverMask() {
        if (!coverMaskActive) return;
        if (coverImage.getDrawable() != null) {
            coverImage.setAlpha(1f);
        }
    }

    void dismissCoverMask() {
        coverMaskActive = false;
        coverImage.animate().cancel();
        coverImage.animate().alpha(0f).setDuration(120).start();
    }

    void resetForReuse(float offScreenY) {
        clearSnapshot();
        detachPlayer();
        coverImage.animate().cancel();
        coverImage.setAlpha(1f);
        coverImage.setImageDrawable(null);
        backdropImage.setImageDrawable(null);
        root.animate().cancel();
        root.setTranslationY(offScreenY);
    }
}
