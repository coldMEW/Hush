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
        AdWatcher.ensureConnected(this);   // Hush may have been killed with its watcher (see there)
        // A force-stop (App info, or a phone's cleaner) also removes Hush's status notification,
        // and nothing else posts it again until the next ad or skip.
        if (new Prefs(this).enabled()) Hush.refreshNotification(this);
    }
}
