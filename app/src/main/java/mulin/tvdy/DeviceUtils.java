package mulin.tvdy;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;

public final class DeviceUtils {

    private DeviceUtils() {
    }

    public static boolean isTelevision(Context context) {
        UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }
}
