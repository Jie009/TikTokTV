package mulin.tvdy.player;

import android.graphics.Bitmap;

/**
 * Cheap "blurred backdrop" bitmap prep - see {@link PlayerActivity}'s
 * {@code backdropImage}, which fills the pillarbox bars either side of a
 * vertical video on this app's forced-landscape TV layout with a blurred,
 * zoomed copy of that video's own cover art instead of plain black.
 */
final class BlurUtils {

    private BlurUtils() {
    }

    /**
     * Produces a small, blurred-looking copy of {@code source} meant to be
     * stretched across the whole screen (via a {@code centerCrop} ImageView).
     * Rather than a real blur convolution over a full-size bitmap - overkill
     * for a full-bleed backdrop nobody looks at closely, and comparatively
     * expensive on the low-power boxes this app targets - this downsamples
     * aggressively first and lets the ImageView's own bilinear upscaling do
     * most of the softening, with a couple of cheap box-blur passes on the
     * (already tiny) downsampled copy to smooth out any remaining blockiness.
     */
    static Bitmap makeBackdrop(Bitmap source) {
        if (source == null) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) return null;
        int targetWidth = 48;
        int targetHeight = Math.max(1, Math.round(height * (targetWidth / (float) width)));
        Bitmap small = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        return boxBlur(boxBlur(small));
    }

    /** One 3x3 box-blur pass - cheap given the bitmap is already tiny (see {@link #makeBackdrop}). */
    private static Bitmap boxBlur(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 3 || h < 3) return src;
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rSum = 0, gSum = 0, bSum = 0, count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= w) continue;
                        int p = pixels[ny * w + nx];
                        rSum += (p >> 16) & 0xFF;
                        gSum += (p >> 8) & 0xFF;
                        bSum += p & 0xFF;
                        count++;
                    }
                }
                out[y * w + x] = 0xFF000000 | ((rSum / count) << 16) | ((gSum / count) << 8) | (bSum / count);
            }
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        return result;
    }
}
