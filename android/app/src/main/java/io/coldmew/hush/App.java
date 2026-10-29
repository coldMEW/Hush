package io.coldmew.hush;

import android.app.Application;

public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.keepDnsDefault(this);
        Hush.createChannels(this);
        Rules.installDefaults(this);
        SleepTimer.restore(this);
        HelperControl.init(this);
        Updater.schedule(this);
    }
}
