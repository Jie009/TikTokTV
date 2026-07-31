package mulin.tvdy;

import android.app.Application;

public class TvdyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        WebViewPumpWarmup.warm(this);
    }
}
