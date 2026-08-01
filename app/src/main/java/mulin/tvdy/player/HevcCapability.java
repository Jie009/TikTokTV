package mulin.tvdy.player;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Log;

/**
 * Cached probe for usable HEVC decoders. Emulator / goldfish stubs often
 * advertise {@code video/hevc} but reject Douyin {@code hvc1.1.6.L*} profiles,
 * so those names are ignored.
 */
final class HevcCapability {

    private static final String TAG = "HevcCapability";
    private static final Object LOCK = new Object();
    private static Boolean supported;

    private HevcCapability() {
    }

    static boolean isSupported() {
        synchronized (LOCK) {
            if (supported != null) return supported;
            supported = probe();
            Log.i(TAG, "hevcSupported=" + supported);
            return supported;
        }
    }

    private static boolean probe() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) continue;
                String name = info.getName() != null ? info.getName().toLowerCase() : "";
                if (name.contains("goldfish") || name.contains("ranchu")) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!"video/hevc".equalsIgnoreCase(type)) continue;
                    if (Build.VERSION.SDK_INT >= 29) {
                        // Prefer HW; software android.hevc is a weak fallback.
                        if (info.isHardwareAccelerated()) return true;
                        if (name.contains("c2.android") || name.contains("omx.google")) {
                            continue;
                        }
                    }
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "HEVC probe failed", t);
        }
        return false;
    }
}
