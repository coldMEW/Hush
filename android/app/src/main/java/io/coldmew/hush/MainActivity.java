package io.coldmew.hush;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import rikka.shizuku.Shizuku;

// Main screen: master switch with status, setup steps, statistics, ad handling, settings, activity.
public class MainActivity extends Activity {
    private static final int REQ_VPN_ENABLE = 1, REQ_VPN_DNS = 2, REQ_NOTIF = 3;

    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private View optSleep, statusDetails, setupSection, choiceSkip, choiceMute, choiceOff, optDns;
    private ImageButton power;
    private TextView statusTitle, statusDesc;
    private TextView statSkipped, statResume, statMuted, statDns;
    private LinearLayout setupList, events;
    private boolean shizukuSeen;

    private final Runnable refreshEvents = () -> ui.post(this::refresh);
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 2000); }
    };
    private final Shizuku.OnBinderReceivedListener binderUp = () -> ui.post(this::refresh);
    private final Shizuku.OnBinderDeadListener binderDown = () -> ui.post(this::refresh);
    private final Shizuku.OnRequestPermissionResultListener shizukuResult = (code, result) -> ui.post(this::refresh);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        Insets.apply(this, findViewById(R.id.root));
        prefs = new Prefs(this);

        power = findViewById(R.id.power);
        statusTitle = findViewById(R.id.status_title);
        statusDesc = findViewById(R.id.status_desc);
        statusDetails = findViewById(R.id.status_details);
        setupSection = findViewById(R.id.setup_section);
        setupList = findViewById(R.id.setup_list);
        statSkipped = findViewById(R.id.stat_skipped);
        statResume = findViewById(R.id.stat_resume);
        statMuted = findViewById(R.id.stat_muted);
        statDns = findViewById(R.id.stat_dns);
        events = findViewById(R.id.events);

        label(R.id.st_detect, R.string.st_detect);
        label(R.id.st_skip, R.string.st_skip);
        label(R.id.st_dns, R.string.st_dns);
        choiceSkip = row(R.id.choice_skip, R.string.mode_skip, R.string.mode_skip_desc);
        choiceMute = row(R.id.choice_mute, R.string.mode_mute, R.string.mode_mute_desc);
        choiceOff = row(R.id.choice_off, R.string.mode_off, R.string.mode_off_desc);
        optDns = row(R.id.opt_dns, R.string.opt_dns, R.string.opt_dns_desc);
        optSleep = row(R.id.opt_sleep, R.string.opt_sleep, R.string.sleep_desc_off);
        View optRules = row(R.id.opt_rules, R.string.opt_rules, R.string.opt_rules_desc);
        View optTest = row(R.id.opt_test, R.string.opt_test, R.string.opt_test_desc);
        optTest.findViewById(R.id.chevron).setVisibility(View.GONE);

        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.version)).setText(getString(R.string.version, v));
        } catch (PackageManager.NameNotFoundException ignored) {}

        power.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            if (prefs.enabled()) Hush.setEnabled(this, false);
            else turnOn();
            refresh();
        });

        choiceSkip.setOnClickListener(v -> setMode(Prefs.MODE_RESTART));
        choiceMute.setOnClickListener(v -> setMode(Prefs.MODE_MUTE));
        choiceOff.setOnClickListener(v -> setMode(Prefs.MODE_OFF));

        optDns.setOnClickListener(v -> {
            boolean on = !prefs.dns();
            prefs.dns(on);
            Intent consent = on && prefs.enabled() ? VpnService.prepare(this) : null;
            if (consent != null) startActivityForResult(consent, REQ_VPN_DNS);
            else Hush.applyDns(this);
            refresh();
            ui.postDelayed(this::refresh, 400);
        });
        optSleep.setOnClickListener(v -> chooseSleep());
        optRules.setOnClickListener(v -> startActivity(new Intent(this, RulesActivity.class)));
        if (BuildConfig.DEBUG) optTest.setOnLongClickListener(v -> {
            Skipper.debugForcePlaylistFix = true;
            toast(Skipper.start(this, true) ? R.string.test_started : R.string.test_busy);
            return true;
        });
        optTest.setOnClickListener(v -> {
            if (!Shell.ready()) { toast(R.string.test_needs_shizuku); Shell.requestPermission(); return; }
            toast(Skipper.start(this, true) ? R.string.test_started : R.string.test_busy);
        });
        findViewById(R.id.stats_reset).setOnClickListener(v -> { prefs.resetStats(); Hush.refreshNotification(this); refresh(); });
        findViewById(R.id.events_clear).setOnClickListener(v -> Events.clear(this));

        try {
            Shizuku.addBinderReceivedListenerSticky(binderUp);
            Shizuku.addBinderDeadListener(binderDown);
            Shizuku.addRequestPermissionResultListener(shizukuResult);
        } catch (Throwable ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        Events.listen(refreshEvents);
        ui.post(ticker);
        if (prefs.enabled()) Hush.applyDns(this);   // e.g. VPN was taken over by another app meanwhile
    }

    @Override protected void onPause() {
        super.onPause();
        Events.unlisten(refreshEvents);
        ui.removeCallbacks(ticker);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeBinderReceivedListener(binderUp);
            Shizuku.removeBinderDeadListener(binderDown);
            Shizuku.removeRequestPermissionResultListener(shizukuResult);
        } catch (Throwable ignored) {}
    }

    // Same choices as Spotify's own sleep timer, a custom time, and "Turn off" while one is running.
    private void chooseSleep() {
        int n = SleepTimer.CHOICES.length;
        boolean on = SleepTimer.active(this);
        CharSequence[] items = new CharSequence[on ? n + 2 : n + 1];
        for (int i = 0; i < n; i++) {
            int m = SleepTimer.CHOICES[i];
            items[i] = m == SleepTimer.END_OF_TRACK ? getString(R.string.sleep_eot)
                    : m == 60 ? getString(R.string.sleep_hour) : getString(R.string.sleep_minutes, m);
        }
        items[n] = getString(R.string.sleep_custom);
        if (on) items[n + 1] = getString(R.string.sleep_off);
        new AlertDialog.Builder(this)
                .setTitle(R.string.opt_sleep)
                .setItems(items, (d, which) -> {
                    if (which == n) { chooseCustomSleep(); return; }
                    if (which == n + 1) SleepTimer.cancel(this);
                    else SleepTimer.set(this, SleepTimer.CHOICES[which]);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // Any time from 1 second to 23 h 59 min 59 s, picked as hours, minutes and seconds.
    private void chooseCustomSleep() {
        NumberPicker hours = picker(SleepTimer.MAX_SECONDS / 3600, 1), minutes = picker(59, 0), seconds = picker(59, 0);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, 0);
        row.addView(hours);
        row.addView(unit(R.string.sleep_custom_hours, pad));
        row.addView(minutes);
        row.addView(unit(R.string.sleep_custom_minutes, pad));
        row.addView(seconds);
        row.addView(unit(R.string.sleep_custom_seconds, pad));
        new AlertDialog.Builder(this)
                .setTitle(R.string.sleep_custom_title)
                .setView(row)
                .setPositiveButton(R.string.sleep_custom_start, (d, w) -> {
                    int total = hours.getValue() * 3600 + minutes.getValue() * 60 + seconds.getValue();
                    if (total < 1) { Toast.makeText(this, R.string.sleep_custom_zero, Toast.LENGTH_SHORT).show(); return; }
                    SleepTimer.setSeconds(this, total);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private NumberPicker picker(int max, int value) {
        NumberPicker p = new NumberPicker(this);
        p.setMinValue(0);
        p.setMaxValue(max);
        p.setValue(value);
        return p;
    }

    private TextView unit(int text, int pad) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(pad / 4, 0, pad / 2, 0);
        return t;
    }

    private void setMode(String m) {
        prefs.mode(m);
        AdWatcher.recheck();
        refresh();
    }

    private void turnOn() {
        if (prefs.dns()) {
            Intent consent = VpnService.prepare(this);
            if (consent != null) { startActivityForResult(consent, REQ_VPN_ENABLE); return; }
        }
        Hush.setEnabled(this, true);
        askNotificationPermission();
        afterTurnOn();
    }

    // Skipping needs Shizuku: ask for its permission if it runs, otherwise show the setup guide.
    private void afterTurnOn() {
        if (!Prefs.MODE_RESTART.equals(prefs.mode()) || Shell.ready()) return;
        if (Shell.running()) Shell.requestPermission();
        else startActivity(new Intent(this, ShizukuSetupActivity.class));
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_VPN_ENABLE) {
            if (res != RESULT_OK) { prefs.dns(false); toast(R.string.vpn_denied); }
            Hush.setEnabled(this, true);
            askNotificationPermission();
            afterTurnOn();
        } else if (req == REQ_VPN_DNS) {
            if (res == RESULT_OK) Hush.applyDns(this);
            else { prefs.dns(false); toast(R.string.vpn_denied); }
        }
        refresh();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        Hush.refreshNotification(this);
        refresh();
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) refresh();
    }

    // ---- rendering ----

    private void refresh() {
        boolean on = prefs.enabled();
        boolean listener = Hush.listenerEnabled(this);
        boolean shizuku = Shell.ready();
        if (shizuku && !shizukuSeen) BackgroundAudio.apply(this);
        shizukuSeen = shizuku;
        if (shizuku) Hush.clearShizukuAlert(this);
        boolean dnsRunning = DnsVpnService.running;
        String m = prefs.mode();
        boolean skipMode = Prefs.MODE_RESTART.equals(m), muteMode = Prefs.MODE_MUTE.equals(m), offMode = Prefs.MODE_OFF.equals(m);

        // Status card
        power.setBackgroundResource(on ? R.drawable.bg_power_on : R.drawable.bg_power_off);
        power.setImageTintList(ColorStateList.valueOf(getColor(on ? R.color.on_accent : R.color.text_dim)));
        power.setContentDescription(getString(on ? R.string.power_turn_off : R.string.power_turn_on));
        int title, desc;
        if (!on) { title = R.string.status_off; desc = R.string.status_off_desc; }
        else if (!listener && !offMode) { title = R.string.status_attention; desc = R.string.status_need_listener; }
        else if (skipMode && !shizuku) { title = R.string.status_attention; desc = R.string.status_need_shizuku; }
        else if (prefs.dns() && !dnsRunning) { title = R.string.status_attention; desc = R.string.status_need_dns; }
        else if (offMode) { title = R.string.status_dns_only; desc = R.string.status_dns_only_desc; }
        else if (muteMode) { title = R.string.status_mute; desc = R.string.status_mute_desc; }
        else { title = R.string.status_skip; desc = R.string.status_skip_desc; }
        statusTitle.setText(title);
        statusDesc.setText(desc);
        if (Build.VERSION.SDK_INT >= 30) power.setStateDescription(getString(on ? R.string.state_on : R.string.state_off));

        statusDetails.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            value(R.id.st_detect, offMode ? R.string.v_off : listener ? R.string.v_active : R.string.v_no_access,
                    offMode ? R.color.neutral : listener ? R.color.good : R.color.bad);
            value(R.id.st_skip, offMode ? R.string.v_off : muteMode ? R.string.v_mute : shizuku ? R.string.v_skip : R.string.v_mute_fallback,
                    offMode ? R.color.neutral : skipMode && !shizuku ? R.color.warn : R.color.good);
            value(R.id.st_dns, !prefs.dns() ? R.string.v_off : dnsRunning ? R.string.v_running : R.string.v_stopped,
                    !prefs.dns() ? R.color.neutral : dnsRunning ? R.color.good : R.color.warn);
        }

        // Ad handling and settings
        choose(choiceSkip, skipMode);
        choose(choiceMute, muteMode);
        choose(choiceOff, offMode);
        ((Switch) optDns.findViewById(R.id.toggle)).setChecked(prefs.dns());
        if (Build.VERSION.SDK_INT >= 30) optDns.setStateDescription(getString(prefs.dns() ? R.string.state_on : R.string.state_off));

        ((TextView) optSleep.findViewById(R.id.summary)).setText(SleepTimer.describe(this));

        // Statistics
        long skipped = prefs.get(Prefs.SKIPPED);
        stat(R.id.cell_skipped, statSkipped, String.valueOf(skipped), R.string.stat_skipped);
        stat(R.id.cell_resume, statResume, skipped == 0 ? getString(R.string.stat_none)
                : String.format(Locale.getDefault(), "%.1f s", prefs.get(Prefs.RESUME_MS) / 1000.0 / skipped), R.string.stat_resume);
        stat(R.id.cell_muted, statMuted, String.valueOf(prefs.get(Prefs.MUTED)), R.string.stat_muted);
        stat(R.id.cell_dns, statDns, String.valueOf(prefs.get(Prefs.DNS_HITS)), R.string.stat_dns);

        renderSetup(listener, shizuku);
        renderEvents();
    }

    private void label(int rowId, int text) {
        ((TextView) findViewById(rowId).findViewById(R.id.label)).setText(text);
    }

    private void value(int rowId, int text, int color) {
        TextView v = findViewById(rowId).findViewById(R.id.value);
        v.setText(text);
        Drawable d = getDrawable(R.drawable.dot).mutate();
        d.setTint(getColor(color));
        d.setBounds(0, 0, dp(8), dp(8));
        v.setCompoundDrawablesRelative(d, null, null, null);
    }

    private View row(int id, int title, int summary) {
        View r = findViewById(id);
        ((TextView) r.findViewById(R.id.title)).setText(title);
        ((TextView) r.findViewById(R.id.summary)).setText(summary);
        return r;
    }

    private void choose(View row, boolean selected) {
        ((RadioButton) row.findViewById(R.id.radio)).setChecked(selected);
        row.setSelected(selected);
        if (Build.VERSION.SDK_INT >= 30) row.setStateDescription(selected ? getString(R.string.state_on) : null);
    }

    private void stat(int cell, TextView value, String text, int label) {
        value.setText(text);
        findViewById(cell).setContentDescription(getString(label) + ": " + text);
    }

    private void renderSetup(boolean listener, boolean shizuku) {
        setupList.removeAllViews();
        if (getPackageManager().getLaunchIntentForPackage(Hush.SPOTIFY) == null)
            setupRow(R.string.setup_spotify, R.string.setup_spotify_missing, R.string.action_install,
                    v -> open(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Hush.SPOTIFY))));
        if (!listener)
            setupRow(R.string.setup_listener, R.string.setup_listener_desc, R.string.action_allow, v -> {
                Intent i = Build.VERSION.SDK_INT >= 30
                        ? new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new ComponentName(this, AdWatcher.class).flattenToString())
                        : new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                if (!open(i)) open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            });
        if (prefs.dns() && VpnService.prepare(this) != null)
            setupRow(R.string.setup_vpn, R.string.setup_vpn_desc, R.string.action_allow, v -> {
                Intent consent = VpnService.prepare(this);
                if (consent != null) startActivityForResult(consent, REQ_VPN_DNS);
            });
        if (Prefs.MODE_RESTART.equals(prefs.mode()) && !shizuku) {
            int desc = !Shell.installed(this) ? R.string.setup_shizuku_missing
                    : !Shell.running() ? R.string.setup_shizuku_stopped : R.string.setup_shizuku_permission;
            int action = !Shell.installed(this) ? R.string.action_set_up : !Shell.running() ? R.string.action_open : R.string.action_allow;
            setupRow(R.string.setup_shizuku, desc, action, v -> {
                if (Shell.running() && !Shell.ready()) Shell.requestPermission();
                else startActivity(new Intent(this, ShizukuSetupActivity.class));
            });
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            setupRow(R.string.setup_notifications, R.string.setup_notifications_desc, R.string.action_allow,
                    v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()))
            setupRow(R.string.setup_battery, R.string.setup_battery_desc, R.string.action_allow,
                    v -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        setupSection.setVisibility(setupList.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void setupRow(int title, int desc, int action, View.OnClickListener click) {
        if (setupList.getChildCount() > 0) setupList.addView(divider());
        View row = LayoutInflater.from(this).inflate(R.layout.row_setup, setupList, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.desc)).setText(desc);
        Button b = row.findViewById(R.id.action);
        b.setText(action);
        b.setContentDescription(getString(action) + ": " + getString(title));
        b.setOnClickListener(click);
        setupList.addView(row);
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(getColor(R.color.divider));
        d.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMarginStart(dp(20));
        lp.setMarginEnd(dp(20));
        d.setLayoutParams(lp);
        return d;
    }

    private void renderEvents() {
        List<Events.Event> list = Events.recent(this);
        events.removeAllViews();
        int n = Math.min(list.size(), 12);
        DateFormat timeFmt = android.text.format.DateFormat.getTimeFormat(this);
        DateFormat dayFmt = DateFormat.getDateInstance(DateFormat.SHORT);
        for (int i = 0; i < n; i++) {
            Events.Event e = list.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.row_event, events, false);
            ((ImageView) row.findViewById(R.id.dot)).setImageTintList(ColorStateList.valueOf(getColor(kindColor(e.kind))));
            ((TextView) row.findViewById(R.id.msg)).setText(e.msg);
            Date d = new Date(e.time);
            ((TextView) row.findViewById(R.id.time)).setText(DateUtils.isToday(e.time) ? timeFmt.format(d) : dayFmt.format(d));
            events.addView(row);
        }
        if (n == 0) {
            TextView empty = new TextView(this, null, 0, R.style.Text_Secondary);
            empty.setText(R.string.activity_empty);
            empty.setPadding(dp(20), dp(12), dp(20), dp(12));
            events.addView(empty);
        }
    }

    private static int kindColor(String kind) {
        switch (kind) {
            case Events.SKIP: return R.color.good;
            case Events.MUTE: return R.color.accent;
            case Events.FAIL: return R.color.bad;
            case Events.AD: return R.color.warn;
            default: return R.color.neutral;
        }
    }

    private boolean open(Intent i) {
        if (i == null) return false;
        try { startActivity(i); return true; } catch (Exception e) { return false; }
    }

    private void toast(int res) { Toast.makeText(this, res, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
