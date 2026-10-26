package io.coldmew.hush;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import io.coldmew.hush.helper.Commands;
import io.coldmew.hush.helper.HushHelper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
        } else if ("io.coldmew.hush.TEST_PAIR".equals(i.getAction())) {
            // Pairing as the setup screen does it: no extra starts the pairing service; --es code N
            // submits a code as if typed into the notification.
            String code = i.getStringExtra("code");
            if (code == null) PairingService.start(c); else PairingService.submit(c, code);
        } else if ("io.coldmew.hush.TEST_HELPER".equals(i.getAction())) {
            // Helper status. extras: --ez start true writes a start command to files/helper-start.sh
            // (run it with: adb shell "$(adb shell run-as <pkg> cat files/helper-start.sh)"; the token
            // is never logged); --ez forcestop true times a force-stop of Spotify; --ez stop true
            // stops the helper.
            Log.i("Hush", "HELPER ready=" + HushHelper.ready() + " dev="
                    + android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1)
                    + " wifi=" + HelperControl.onWifi(c) + " debugging=" + HelperControl.debuggingOn(c)
                    + " canToggle=" + HelperControl.canToggleDebugging(c) + "\n" + HelperControl.diagnostics(c));
            if (i.getBooleanExtra("start", false)) {
                try (FileOutputStream f = new FileOutputStream(new File(c.getFilesDir(), "helper-start.sh"))) {
                    f.write(HushHelper.startCommand(c).getBytes(StandardCharsets.UTF_8));
                    Log.i("Hush", "HELPER start command written");
                } catch (IOException e) { Log.w("Hush", "HELPER start command not written", e); }
            }
            if (HushHelper.ready()) {
                int[] p = HushHelper.ping();
                Log.i("Hush", "HELPER ping version=" + (p == null ? "?" : p[0]) + " pid=" + (p == null ? "?" : p[1]));
                Log.i("Hush", "HELPER is-frozen=" + HushHelper.run(Commands.IS_FROZEN, null));
                Object[] f = HushHelper.front();
                long t = android.os.SystemClock.elapsedRealtime();
                String shellFront = Skipper.frontPackage(HushHelper.run(Commands.FRONT, null));
                Log.i("Hush", "HELPER front direct=" + (f == null ? null : f[0] + " (" + f[1] + " us)")
                        + " shell=" + shellFront + " (" + (android.os.SystemClock.elapsedRealtime() - t) + " ms)");
                if (i.getBooleanExtra("forcestop", false)) {
                    boolean[] d = new boolean[1];
                    long us = HushHelper.forceStopSpotify(d);
                    Log.i("Hush", "HELPER force-stop micros=" + us + " direct=" + d[0]);
                }
                if (i.getBooleanExtra("stop", false)) HushHelper.stop();
            }
        }
    }
}
