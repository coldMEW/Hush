package io.coldmew.hush;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import io.coldmew.hush.adb.AdbKey;
import io.coldmew.hush.adb.AdbMdns;
import io.coldmew.hush.adb.AdbPairing;
import java.util.concurrent.CopyOnWriteArrayList;

// Pairs Hush with Wireless debugging while the user is in Settings. It watches for the pairing
// port (announced only while "Pair device with pairing code" is open) and then offers a reply field
// in a notification, so the six-digit code can be typed without leaving Settings. After pairing it
// starts Hush's helper. Runs as a short foreground service (at most a few minutes).
public class PairingService extends Service {
    static final String ACTION_START = "io.coldmew.hush.PAIR_START", ACTION_REPLY = "io.coldmew.hush.PAIR_REPLY",
            ACTION_CANCEL = "io.coldmew.hush.PAIR_CANCEL";
    static final String EXTRA_CODE = "code";
    private static final String KEY_CODE = "code";
    private static final int NOTIF = 5;

    enum State { IDLE, SEARCHING, FOUND, PAIRING, WRONG_CODE, STARTING, DONE, FAILED }

    private static volatile State state = State.IDLE;
    private static volatile int port = -1;
    // The pairing popup was open and then closed (e.g. the user switched back to Hush to type the
    // code): its code is no longer valid, and the setup screen explains how to try again.
    private static volatile boolean popupClosed;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private AdbMdns mdns;
    private volatile boolean busy;
    // Android's service discovery can miss the pairing dialog now and then: while nothing is found,
    // the search starts afresh every 20 s, and it ends after 3 minutes (on every Android version).
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable rediscover = () -> { if (state == State.SEARCHING && !busy) restartDiscovery(); };
    private final Runnable giveUp = () -> {
        if (!busy && (state == State.SEARCHING || state == State.FOUND || state == State.WRONG_CODE)) finish(State.IDLE);
    };

    static State state() { return state; }
    static boolean portFound() { return port > 0; }
    static boolean popupClosed() { return popupClosed; }
    static void listen(Runnable r) { listeners.addIfAbsent(r); }
    static void unlisten(Runnable r) { listeners.remove(r); }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, PairingService.class).setAction(ACTION_START));
    }

    // A code typed in Hush itself (split screen) instead of the notification.
    static void submit(Context c, String code) {
        c.startService(new Intent(c, PairingService.class).setAction(ACTION_REPLY).putExtra(EXTRA_CODE, code));
    }

    private void set(State s) {
        state = s;
        for (Runnable r : listeners) r.run();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? ACTION_START : i.getAction();
        if (ACTION_CANCEL.equals(a)) { finish(State.IDLE); return START_NOT_STICKY; }
        if (ACTION_REPLY.equals(a)) {
            String code = i.getStringExtra(EXTRA_CODE);
            Bundle r = RemoteInput.getResultsFromIntent(i);
            if (code == null && r != null && r.getCharSequence(KEY_CODE) != null) code = r.getCharSequence(KEY_CODE).toString();
            if (code != null) pair(code.replaceAll("\\D", ""));
            return START_NOT_STICKY;
        }
        // Start (also again, when the user taps Pair once more): foreground at once, then a fresh
        // search for the pairing port.
        foreground(note(getString(R.string.pair_searching_title), getString(R.string.pair_searching_text), false));
        if (busy) return START_NOT_STICKY;   // a pairing is already running
        popupClosed = false;
        set(State.SEARCHING);
        restartDiscovery();
        handler.removeCallbacks(giveUp);
        handler.postDelayed(giveUp, 180_000);
        return START_NOT_STICKY;
    }

    private void restartDiscovery() {
        if (mdns != null) mdns.stop();
        mdns = new AdbMdns(this, AdbMdns.TLS_PAIRING, p -> {
            port = p;
            if (busy) return;
            if (p > 0) {
                popupClosed = false;
                set(State.FOUND);
                show(note(getString(R.string.pair_found_title), getString(R.string.pair_found_text), true));
            } else if (state == State.FOUND || state == State.WRONG_CODE) {
                popupClosed = true;
                set(State.SEARCHING);
                show(note(getString(R.string.pair_searching_title), getString(R.string.pair_closed_text), false));
            }
        });
        mdns.start();
        handler.removeCallbacks(rediscover);
        handler.postDelayed(rediscover, 20_000);
    }

    private void pair(String code) {
        if (busy) return;
        if (code.length() != 6) {
            set(State.WRONG_CODE);
            show(note(getString(R.string.pair_wrong_title), getString(R.string.pair_code_length), true));
            return;
        }
        int p = port;
        if (p <= 0) {
            show(note(getString(R.string.pair_searching_title), getString(R.string.pair_searching_text), false));
            return;
        }
        busy = true;
        set(State.PAIRING);
        show(note(getString(R.string.pair_working_title), getString(R.string.pair_working_text), false));
        new Thread(() -> {
            try {
                AdbPairing.pair(AdbKey.get(this), "127.0.0.1", p, code, HelperControl.PAIR_NAME);
                new Prefs(this).paired(true);
                Events.add(this, Events.INFO, "Paired with Wireless debugging");
                set(State.STARTING);
                show(note(getString(R.string.pair_starting_title), getString(R.string.pair_starting_text), false));
                if (mdns != null) mdns.stop();
                HelperControl.Result r = HelperControl.start(this, false);
                if (r == HelperControl.Result.READY) {
                    Events.add(this, Events.INFO, "Skipping ready (Hush helper)");
                    done(true);
                } else {
                    Events.add(this, Events.FAIL, "Paired, but the helper did not start: " + r);
                    done(false);
                }
            } catch (AdbPairing.WrongCodeException e) {
                busy = false;
                set(State.WRONG_CODE);
                show(note(getString(R.string.pair_wrong_title), getString(R.string.pair_wrong_text), true));
            } catch (Exception e) {
                Log.w(Hush.TAG, "pairing failed", e);
                busy = false;
                Events.add(this, Events.FAIL, "Pairing failed: " + e.getClass().getSimpleName());
                set(State.FAILED);
                show(note(getString(R.string.pair_failed_title), getString(R.string.pair_failed_text), true));
            }
        }, "hush-pair").start();
    }

    // Ends pairing: a short "ready" notice (or the failure notice) replaces the ongoing one.
    private void done(boolean ok) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        Intent open = new Intent(this, ok ? MainActivity.class : HelperSetupActivity.class);
        nm.notify(NOTIF, new Notification.Builder(this, Hush.CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_hush).setColor(getColor(R.color.accent))
                .setContentTitle(getString(ok ? R.string.pair_done_title : R.string.pair_failed_title))
                .setContentText(getString(ok ? R.string.pair_done_text : R.string.helper_notice_fix))
                .setContentIntent(PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setTimeoutAfter(ok ? 8000 : 0)
                .build());
        finish(ok ? State.DONE : State.FAILED);
    }

    private void finish(State end) {
        handler.removeCallbacksAndMessages(null);
        if (mdns != null) { mdns.stop(); mdns = null; }
        port = -1;
        busy = false;
        set(end);
        if (end == State.IDLE) getSystemService(NotificationManager.class).cancel(NOTIF);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // shortService (Android 14+) ends after about 3 minutes.
    @Override public void onTimeout(int startId) { finish(State.IDLE); }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (mdns != null) { mdns.stop(); mdns = null; }
        if (state == State.SEARCHING || state == State.FOUND || state == State.WRONG_CODE) set(State.IDLE);
        super.onDestroy();
    }

    private void foreground(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
        else startForeground(NOTIF, n);
    }

    private void show(Notification n) {
        getSystemService(NotificationManager.class).notify(NOTIF, n);
    }

    private Notification note(String title, String text, boolean askCode) {
        PendingIntent cancel = PendingIntent.getService(this, 8, new Intent(this, PairingService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, askCode ? Hush.CH_PAIR : Hush.CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_hush).setColor(getColor(R.color.accent))
                .setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true).setOnlyAlertOnce(!askCode)
                .addAction(new Notification.Action.Builder(null, getString(android.R.string.cancel), cancel).build());
        // Opening Hush would close the pairing popup in Settings and make its code useless, so the
        // code notification opens nothing: its Enter code button takes the code in place.
        if (!askCode) b.setContentIntent(PendingIntent.getActivity(this, 9, new Intent(this, HelperSetupActivity.class), PendingIntent.FLAG_IMMUTABLE));
        if (askCode) {
            // The reply field needs a mutable PendingIntent: Android fills in the typed text.
            PendingIntent reply = PendingIntent.getService(this, 10, new Intent(this, PairingService.class).setAction(ACTION_REPLY),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            RemoteInput input = new RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.pair_code_hint)).build();
            b.addAction(new Notification.Action.Builder(null, getString(R.string.pair_enter_code), reply).addRemoteInput(input).build());
        }
        return b.build();
    }
}
