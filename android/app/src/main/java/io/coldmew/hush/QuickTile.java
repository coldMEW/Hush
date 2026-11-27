package io.coldmew.hush;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

// Quick Settings tile: the master switch from the notification shade. Turning Hush off always works
// from here. Turning it on works from here whenever Hush needs nothing from the user; otherwise the
// tile opens Hush, which turns on as its power button does and asks for what is missing.
public class QuickTile extends TileService {
    static final String EXTRA_TURN_ON = "turn_on";

    private final Handler ui = new Handler(Looper.getMainLooper());
    // Hush can be switched while the shade is open (Turn off in its notification, the app): every
    // switch is logged, so the tile follows the log, as the main screen does.
    private final Runnable changed = () -> ui.post(this::render);

    @Override public void onStartListening() {
        Events.listen(changed);
        render();
    }

    @Override public void onStopListening() { Events.unlisten(changed); }

    @Override public void onDestroy() {
        Events.unlisten(changed);   // also when the shade went away without stopping the tile first
        super.onDestroy();
    }

    @Override public void onClick() {
        Prefs p = new Prefs(this);
        if (p.enabled()) {
            Hush.setEnabled(this, false);
            render();
            return;
        }
        if (needsApp(p)) { openApp(); return; }
        try {
            Hush.setEnabled(this, true);
        } catch (IllegalStateException e) {
            // Android 12+ may not let Hush start the DNS filter (a foreground service) from the
            // shade while it is in the background: the app, in front, finishes turning on.
            openApp();
            return;
        }
        // As the power button: skipping needs Hush's helper, started in the background. If it can't
        // start (no Wi-Fi, Wireless debugging off), its notification says what to do.
        if (Prefs.MODE_RESTART.equals(p.mode()) && !Shell.ready()) HelperControl.ensure(this, true, true);
        render();
    }

    // Turning on needs a screen for what the power button would ask for (see MainActivity.turnOn and
    // afterTurnOn): VPN permission, Shizuku's permission or the one-time skipping setup. Without
    // notification access Hush would be on but see no ads, so the app shows that step too.
    private boolean needsApp(Prefs p) {
        String mode = p.mode();
        if (p.dns() && VpnService.prepare(this) != null) return true;
        if (!Prefs.MODE_OFF.equals(mode) && !Hush.listenerEnabled(this)) return true;
        if (!Prefs.MODE_RESTART.equals(mode) || Shell.ready()) return false;
        if (Shell.running() && !p.paired()) return true;   // Shizuku asks for its permission
        return !((p.paired() || p.root()) && HelperControl.supported());   // the one-time setup
    }

    // Opens Hush, which turns on (see MainActivity). On the lock screen Android asks to unlock first;
    // wrapping this in unlockAndRun asks twice and the second launch is lost (seen on Android 17).
    @SuppressWarnings("deprecation")
    @SuppressLint("StartActivityAndCollapseDeprecated")   // the Intent form only below Android 14, which has no other
    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).putExtra(EXTRA_TURN_ON, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Android 14+ takes only a PendingIntent here (an Intent throws).
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 14, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        else startActivityAndCollapse(i);
    }

    private void render() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(new Prefs(this).enabled() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
