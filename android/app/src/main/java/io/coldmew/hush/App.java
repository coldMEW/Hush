package io.coldmew.hush;

import android.app.Application;

public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Hush.createChannels(this);
        Rules.installDefaults(this);
        SleepTimer.restore(this);
    }
}
