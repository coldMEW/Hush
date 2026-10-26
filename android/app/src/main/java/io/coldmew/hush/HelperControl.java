package io.coldmew.hush;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import io.coldmew.hush.adb.AdbConnection;
import io.coldmew.hush.adb.AdbKey;
import io.coldmew.hush.adb.AdbMdns;
import io.coldmew.hush.helper.Commands;
import io.coldmew.hush.helper.HushHelper;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Keeps Hush's own helper running, so skipping works with Hush as the only app.
//  - start(): finds Wireless debugging's port on this phone (mDNS), connects with Hush's paired
//    key, starts the helper and waits for it to hand over its Binder.
//  - Self-healing: if the helper dies, it is started again (at most 3 times in 10 minutes); if that
//    is not possible, skipping falls back to Mute and one "tap to fix" notification is shown.
//  - Turning Wireless debugging on (by the user) starts the helper by itself.
//  - After a reboot (Android turns Wireless debugging off): one "Tap to resume" notification, or,
//    if the user opted in, an automatic restart followed by the per-phone test: Wireless debugging
//    is switched off again and kept off only if the helper survives that on this phone.
// Hush only ever changes the Wireless debugging setting (never USB debugging or Developer options),
// and only with WRITE_SECURE_SETTINGS (granted once by the helper) on Android 13+.
final class HelperControl {
    enum Result { READY, UNSUPPORTED, NOT_PAIRED, NO_WIFI, DEBUGGING_OFF, FAILED }

    static final String ADB_WIFI = "adb_wifi_enabled";
    static final String PAIR_NAME = "Hush";
    private static final long WINDOW = 10 * 60_000L;
    private static final int MAX_RESTARTS = 3;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "hush-helper"));
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<Long> restarts = new ArrayDeque<>();
    private static volatile Result last;
    private static volatile String lastError = "", lastLogged = "";
    private static volatile boolean pending, phoneTestRunning, booting, turnedOn;
    private static boolean initialized;
    private static ConnectivityManager.NetworkCallback wifiWait;

    private HelperControl() {}

    // From App.onCreate.
    static synchronized void init(Context ctx) {
        if (initialized) return;
        initialized = true;
        Context c = ctx.getApplicationContext();
        HushHelper.addListener(connected -> {
            if (connected) onConnected(c); else onDied(c);
        });
        if (Build.VERSION.SDK_INT >= 30) {
            c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(ADB_WIFI), false,
                    new ContentObserver(main) {
                        @Override public void onChange(boolean self) {
                            // The user (or Hush) turned Wireless debugging on: start the helper. A
                            // running helper may be handing its Binder over again right now (Hush's
                            // process was restarted), so look again after a moment.
                            main.postDelayed(() -> {
                                if (debuggingOn(c) && wanted(c) && !HushHelper.ready() && !phoneTestRunning)
                                    ensure(c, false, false);
                            }, 2500);
                        }
                    });
            // A helper that outlived Hush's process hands its Binder over within about a second; if
            // none came (after a reboot, or it died meanwhile), act now as after a boot. Android
            // starts Hush's process right after the unlock (to bind the ad watcher), but delivers
            // BOOT_COMPLETED to it only after many other apps: up to ~16 s later.
            // After a reboot no helper can have survived, so there is nothing to wait for.
            main.postDelayed(() -> onBoot(c), new Prefs(c).helperBoot() == bootCount(c) ? 3000 : 0);
        }
    }

    static boolean supported() { return Build.VERSION.SDK_INT >= 30; }

    // Skipping should run through Hush's helper.
    static boolean wanted(Context c) {
        Prefs p = new Prefs(c);
        return supported() && p.enabled() && Prefs.MODE_RESTART.equals(p.mode()) && p.paired();
    }

    static Result lastResult() { return last; }

    // ---- Wireless debugging setting ----

    static boolean debuggingOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), ADB_WIFI, 0) == 1;
    }

    // Hush may switch Wireless debugging itself (Android 13+, after the helper granted the permission).
    static boolean canToggleDebugging(Context c) {
        return Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean setDebugging(Context c, boolean on) {
        if (!canToggleDebugging(c)) return false;
        if (BuildConfig.DEBUG) Log.d(Hush.TAG, "wireless debugging -> " + on, new Throwable());
        try {
            return Settings.Global.putInt(c.getContentResolver(), ADB_WIFI, on ? 1 : 0);
        } catch (SecurityException e) {
            return false;
        }
    }

    // Turns Wireless debugging off and keeps it off for a few seconds: right after a reboot Android
    // can switch it on again by itself (seen on a recent Android version about 20 s after boot), shortly after
    // Hush switched it off. At most three corrections, so Hush never fights the system for long.
    static void keepDebuggingOff(Context c) {
        setDebugging(c, false);
        for (int i = 0; i < 3; i++) {
            SystemClock.sleep(2000);
            if (!debuggingOn(c)) continue;
            Log.i(Hush.TAG, "Wireless debugging was switched on again; switching it off");
            setDebugging(c, false);
        }
    }

    static boolean onWifi(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        for (Network n : cm.getAllNetworks()) {   // Wi-Fi may be up while mobile data is the default
            NetworkCapabilities x = cm.getNetworkCapabilities(n);
            if (x != null && x.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }

    // ---- starting ----

    // Blocking (never on the main thread). mayEnable: Hush may turn Wireless debugging on first.
    static synchronized Result start(Context ctx, boolean mayEnable) {
        Context c = ctx.getApplicationContext();
        Result r = startLocked(c, mayEnable);
        last = r;
        Log.i(Hush.TAG, "helper start: " + r + (r == Result.READY ? "" : " " + lastError));
        // A failed start is shown in the activity log (once per reason), for the user and bug reports.
        String failure = r == Result.READY || r == Result.UNSUPPORTED ? "" : lastError;
        if (!failure.isEmpty() && !failure.equals(lastLogged)) Events.add(c, Events.INFO, "Skipping helper didn't start: " + failure);
        lastLogged = failure;
        return r;
    }

    private static Result startLocked(Context c, boolean mayEnable) {
        if (HushHelper.ready()) return Result.READY;
        if (!supported()) return Result.UNSUPPORTED;
        if (!onWifi(c)) { lastError = "no Wi-Fi"; return Result.NO_WIFI; }
        boolean wasOn = debuggingOn(c);
        if (!wasOn && !(mayEnable && setDebugging(c, true))) { lastError = "Wireless debugging is off"; return Result.DEBUGGING_OFF; }
        turnedOn = !wasOn;
        int port = AdbMdns.find(c, AdbMdns.TLS_CONNECT, wasOn ? 4000 : 10000);
        if (port <= 0) {
            lastError = "Wireless debugging port not found";
            return debuggingOn(c) ? Result.FAILED : Result.DEBUGGING_OFF;
        }
        // The connection stays open until the helper has handed over its Binder: closing it right
        // after the start command can end the helper before it has detached from the session.
        for (int attempt = 1; ; attempt++) {
            try (AdbConnection adb = AdbConnection.open(AdbKey.get(c), "127.0.0.1", port)) {
                adb.shell(HushHelper.startCommand(c));
                long end = SystemClock.elapsedRealtime() + 8000;
                while (!HushHelper.ready() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50);
                break;
            } catch (AdbConnection.NotPairedException e) {
                // Right after Wireless debugging is switched on, adbd can drop the first connection
                // while it starts up. Only a second refusal means the pairing is really gone.
                if (attempt < 2) { SystemClock.sleep(1500); continue; }
                new Prefs(c).paired(false);
                lastError = "not paired";
                return Result.NOT_PAIRED;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                return Result.FAILED;
            }
        }
        if (!HushHelper.ready()) { lastError = "helper did not connect"; return Result.FAILED; }
        new Prefs(c).paired(true);
        lastError = "";
        return Result.READY;
    }

    // Starts the helper in the background unless a start is already queued.
    static void ensure(Context ctx, boolean mayEnable, boolean notifyOnFailure) {
        Context c = ctx.getApplicationContext();
        if (pending) return;
        pending = true;
        worker.execute(() -> {
            pending = false;
            turnedOn = false;
            Result r = start(c, mayEnable);
            if (r != Result.READY && notifyOnFailure) notifyStopped(c, r);
            // Hush switched Wireless debugging on just for this start: switch it off again where the
            // phone passed the test (never when the user turned it on).
            Prefs p = new Prefs(c);
            if (r == Result.READY && turnedOn && p.autoRestart() && Prefs.TEST_SAFE.equals(p.phoneTest())) keepDebuggingOff(c);
        });
    }

    // ---- events ----

    static int bootCount(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private static void onConnected(Context c) {
        cancelNotice(c);
        new Prefs(c).helperBoot(bootCount(c));
        worker.execute(() -> {
            // Background audio for the new backend, and the permission that lets Hush switch
            // Wireless debugging itself later (granted only to Hush, once).
            BackgroundAudio.apply(c);
            if (Build.VERSION.SDK_INT >= 33 && !canToggleDebugging(c)) Shell.run(Commands.GRANT_SECURE_SETTINGS, c.getPackageName());
        });
    }

    private static void onDied(Context c) {
        if (!wanted(c) || phoneTestRunning) return;
        long now = SystemClock.elapsedRealtime();
        Prefs p = new Prefs(c);
        synchronized (restarts) {
            while (!restarts.isEmpty() && now - restarts.peekFirst() > WINDOW) restarts.pollFirst();
            if (restarts.size() >= MAX_RESTARTS) {
                if (Prefs.TEST_SAFE.equals(p.phoneTest()) && !debuggingOn(c)) {
                    // The phone passed the test once but now keeps stopping the helper while
                    // Wireless debugging is off (e.g. after a system update): leave it on from now on.
                    p.phoneTest(Prefs.TEST_NEEDS_ON);
                    Events.add(c, Events.INFO, "This phone now needs Wireless debugging on to keep skipping ready");
                    restarts.clear();
                } else {
                    Events.add(c, Events.FAIL, "Skipping helper keeps stopping; ads are muted until it is fixed");
                    notifyStopped(c, Result.FAILED);
                    return;
                }
            }
            restarts.addLast(now);
        }
        boolean auto = p.autoRestart() && canToggleDebugging(c);
        worker.execute(() -> {
            SystemClock.sleep(1000);
            Result r = start(c, auto);
            if (r == Result.READY) {
                Events.add(c, Events.INFO, "Skipping helper restarted");
                if (auto && Prefs.TEST_SAFE.equals(p.phoneTest())) keepDebuggingOff(c);
            } else {
                notifyStopped(c, r);
            }
        });
    }

    // Hush's process starts after a reboot, or after an update (which also ends the old helper).
    // Called both when the process starts (init) and on BOOT_COMPLETED; the second call finds the
    // helper ready (or a start already running) and does nothing.
    static void onBoot(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!wanted(c) || HushHelper.ready() || booting) return;
        Prefs p = new Prefs(c);
        if (p.autoRestart() && canToggleDebugging(c)) {
            if (onWifi(c)) {
                booting = true;
                worker.execute(() -> { try { autoStart(c); } finally { booting = false; } });
            } else waitForWifi(c);
        } else if (debuggingOn(c)) {
            ensure(c, false, true);
        } else {
            notifyStopped(c, Result.DEBUGGING_OFF);
        }
    }

    // Opt-in automatic restart: turn Wireless debugging on, start, then apply the per-phone result.
    private static void autoStart(Context c) {
        Result r = start(c, true);
        if (r != Result.READY) { notifyStopped(c, r); return; }
        Prefs p = new Prefs(c);
        String test = p.phoneTest();
        if (Prefs.TEST_SAFE.equals(test)) keepDebuggingOff(c);
        else if (Prefs.TEST_NONE.equals(test)) phoneTest(c);
    }

    // The per-phone test: does the helper keep running here with Wireless debugging off?
    static void phoneTest(Context c) {
        Prefs p = new Prefs(c);
        if (!HushHelper.ready() || !canToggleDebugging(c)) return;
        phoneTestRunning = true;
        boolean alive = true;
        try {
            setDebugging(c, false);
            for (int i = 0; i < 20 && alive; i++) {   // 10 s
                SystemClock.sleep(500);
                if (debuggingOn(c)) setDebugging(c, false);   // switched on again meanwhile (see keepDebuggingOff)
                alive = HushHelper.ping() != null;
            }
        } finally {
            phoneTestRunning = false;
        }
        if (alive) {
            p.phoneTest(Prefs.TEST_SAFE);
            Events.add(c, Events.INFO, "This phone keeps skipping ready with Wireless debugging off");
        } else {
            p.phoneTest(Prefs.TEST_NEEDS_ON);
            Events.add(c, Events.INFO, "This phone needs Wireless debugging on to keep skipping ready");
            Result r = start(c, true);
            if (r != Result.READY) notifyStopped(c, r);
        }
    }

    // Opting in from Settings: run the test now, so the user sees the result at once. Opting out
    // forgets the result, so switching it off and on again is how a user asks for a new test (Hush
    // never re-tests by itself).
    static void autoRestartChanged(Context ctx, boolean on) {
        Context c = ctx.getApplicationContext();
        Prefs p = new Prefs(c);
        p.autoRestart(on);
        if (!on) p.phoneTest(Prefs.TEST_NONE);
        if (!on || !HushHelper.ready()) return;
        if (Prefs.TEST_NONE.equals(p.phoneTest())) worker.execute(() -> phoneTest(c));
        else if (Prefs.TEST_SAFE.equals(p.phoneTest())) worker.execute(() -> keepDebuggingOff(c));
    }

    // Wi-Fi is back after the helper could not start without it (Android starts Wireless debugging
    // only on Wi-Fi): try again, as after a boot.
    private static void onWifiBack(Context c) {
        if (!wanted(c) || HushHelper.ready()) return;
        Prefs p = new Prefs(c);
        if (p.autoRestart() && canToggleDebugging(c)) { autoStart(c); return; }
        SystemClock.sleep(3000);   // Wireless debugging, if on, needs a moment on the new network
        Result r = start(c, false);
        if (r != Result.READY) notifyStopped(c, r);
    }

    private static synchronized void waitForWifi(Context c) {
        if (wifiWait != null) return;
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        wifiWait = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                synchronized (HelperControl.class) {
                    if (wifiWait == null) return;
                    try { cm.unregisterNetworkCallback(this); } catch (RuntimeException ignored) {}
                    wifiWait = null;
                }
                worker.execute(() -> onWifiBack(c));
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiWait);
        } catch (RuntimeException e) {
            wifiWait = null;
        }
    }

    // ---- notice ----

    // One notification when skipping cannot run through the helper. The tap resumes: it turns
    // Wireless debugging on if Hush may, or opens the setup screen.
    static void notifyStopped(Context c, Result r) {
        if (r == null || r == Result.READY) return;
        if (!wanted(c) && r != Result.NOT_PAIRED) return;
        int text;
        boolean tapResumes = false;
        switch (r) {
            case NOT_PAIRED: text = R.string.helper_notice_pair; break;
            case NO_WIFI: text = R.string.helper_notice_wifi; waitForWifi(c); break;
            case DEBUGGING_OFF:
                tapResumes = canToggleDebugging(c);
                text = tapResumes ? R.string.helper_notice_resume : R.string.helper_notice_debugging;
                break;
            default: text = R.string.helper_notice_fix; break;
        }
        PendingIntent tap = tapResumes
                ? PendingIntent.getBroadcast(c, 4, new Intent(c, Hush.Receiver.class).setAction(Hush.ACTION_RESUME), PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getActivity(c, 5, new Intent(c, HelperSetupActivity.class), PendingIntent.FLAG_IMMUTABLE);
        String body = c.getString(text);
        c.getSystemService(NotificationManager.class).notify(Hush.NOTIF_HELPER, new Notification.Builder(c, Hush.CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_hush).setColor(c.getColor(R.color.accent))
                .setContentTitle(c.getString(R.string.helper_notice_title))
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true).build());
    }

    static void cancelNotice(Context c) {
        c.getSystemService(NotificationManager.class).cancel(Hush.NOTIF_HELPER);
    }

    // The notification tap: resume in the background, notify again only if it did not work.
    static void resumeFromNotice(Context ctx, Runnable done) {
        Context c = ctx.getApplicationContext();
        worker.execute(() -> {
            try {
                Result r = start(c, true);
                if (r == Result.READY) {
                    Prefs p = new Prefs(c);
                    if (p.autoRestart() && Prefs.TEST_SAFE.equals(p.phoneTest())) keepDebuggingOff(c);
                } else notifyStopped(c, r);
            } finally {
                done.run();
            }
        });
    }

    // For the diagnostics report: nothing personal, only what helps to fix a problem.
    static String diagnostics(Context c) {
        Prefs p = new Prefs(c);
        int[] ping = HushHelper.ping();
        return "backend: " + (HushHelper.ready() ? "hush-helper v" + (ping == null ? "?" : ping[0])
                        : Shell.shizukuReady() ? "shizuku" : "none")
                + "\npaired: " + p.paired()
                + "\nauto restart: " + p.autoRestart() + " (can switch debugging: " + canToggleDebugging(c) + ")"
                + "\nper-phone test: " + (p.phoneTest().isEmpty() ? "not run" : p.phoneTest())
                + "\nwireless debugging: " + (debuggingOn(c) ? "on" : "off")
                + "\nlast start: " + last + (lastError.isEmpty() ? "" : " (" + lastError + ")");
    }
}
