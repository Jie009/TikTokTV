package mulin.tvdy.auth;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * Renders a plain black/white QR bitmap for {@link CookieHandoffServer}'s
 * URL, so it can be scanned with a phone camera instead of typed out - see
 * {@code PlayerActivity#startCookieLogin}. Uses ZXing's pure-Java encoder
 * (no Android-specific camera/scanning pieces are needed, only the encode
 * side) rather than hand-rolling QR's Reed-Solomon error correction.
 */
public final class QrCodeGenerator {

    private QrCodeGenerator() {
    }

    /** @return a {@code sizePx} x {@code sizePx} bitmap, or {@code null} if encoding failed. */
    public static Bitmap generate(String content, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
            for (int y = 0; y < sizePx; y++) {
                for (int x = 0; x < sizePx; x++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return bitmap;
        } catch (WriterException e) {
            return null;
        }
    }
}
