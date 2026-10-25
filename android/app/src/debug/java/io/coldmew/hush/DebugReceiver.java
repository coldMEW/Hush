package io.coldmew.hush;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Debug builds only. Protected by the DUMP permission, which only the shell (adb) holds.
public class DebugReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if ("io.coldmew.hush.TEST_SKIP".equals(i.getAction())) {
            Skipper.debugForcePlaylistFix = i.getBooleanExtra("playlist", false);
            Skipper.debugNoButton = i.getBooleanExtra("nobutton", false);   // test the fallback
            Skipper.start(c, true);
        } else if ("io.coldmew.hush.TEST_SLEEP".equals(i.getAction())) {
            int m = i.getIntExtra("minutes", 0), s = i.getIntExtra("seconds", 0);
            if (s > 0) SleepTimer.setSeconds(c, s);
            else if (m == 0) SleepTimer.cancel(c); else SleepTimer.set(c, m);
        }
    }
}
