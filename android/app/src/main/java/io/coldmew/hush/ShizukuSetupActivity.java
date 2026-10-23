package io.coldmew.hush;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import rikka.shizuku.Shizuku;

// Guided Shizuku setup. Android lets nothing but a computer or Shizuku's own wireless-debugging
// pairing start Shizuku, so Hush walks the user through it: each step opens the right place and
// is ticked off as soon as it is done. Once Shizuku runs, Hush asks for its permission by itself.
public class ShizukuSetupActivity extends Activity {
    private static final String PLAY = "market://details?id=" + Shell.SHIZUKU;
    private static final String DOWNLOAD = "https://github.com/RikkaApps/Shizuku/releases/latest";
    private static final String GUIDE = "https://shizuku.rikka.app/guide/setup/";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private View stepInstall, stepDev, stepStart, stepAllow;
    private boolean asked;

    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 1500); }
    };
    private final Shizuku.OnBinderReceivedListener binderUp = () -> ui.post(this::refresh);
    private final Shizuku.OnRequestPermissionResultListener result = (code, r) -> ui.post(this::refresh);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_shizuku);
        Insets.apply(this, findViewById(R.id.root));
        findViewById(R.id.back).setOnClickListener(v -> finish());

        stepInstall = step(R.id.step_install, R.string.sz_install, R.string.sz_install_desc, R.string.action_install, v -> {
            if (!open(new Intent(Intent.ACTION_VIEW, Uri.parse(PLAY))))
                open(new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD)));
        });
        stepDev = step(R.id.step_dev, R.string.sz_dev, R.string.sz_dev_desc, R.string.action_open, v -> {
            if (!open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))) open(new Intent(Settings.ACTION_SETTINGS));
        });
        stepStart = step(R.id.step_start, R.string.sz_start,
                Build.VERSION.SDK_INT >= 30 ? R.string.sz_start_desc : R.string.sz_start_desc_old, R.string.action_open,
                v -> open(getPackageManager().getLaunchIntentForPackage(Shell.SHIZUKU)));
        stepAllow = step(R.id.step_allow, R.string.sz_allow, R.string.sz_allow_desc, R.string.action_allow,
                v -> Shell.requestPermission());
        findViewById(R.id.guide).setOnClickListener(v -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(GUIDE))));

        try {
            Shizuku.addBinderReceivedListenerSticky(binderUp);
            Shizuku.addRequestPermissionResultListener(result);
        } catch (Throwable ignored) {}
    }

    @Override protected void onResume() { super.onResume(); ui.post(ticker); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(ticker); }

    @Override protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeBinderReceivedListener(binderUp);
            Shizuku.removeRequestPermissionResultListener(result);
        } catch (Throwable ignored) {}
    }

    private void refresh() {
        boolean installed = Shell.installed(this);
        boolean dev = Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        boolean running = Shell.running();
        boolean ready = Shell.ready();
        // A running Shizuku implies the earlier steps were done.
        state(stepInstall, installed || running, true);
        state(stepDev, dev || running, installed);
        state(stepStart, running, installed);
        state(stepAllow, ready, running);
        if (running && !ready && !asked) { asked = true; Shell.requestPermission(); }
        if (ready) {
            ((TextView) findViewById(R.id.intro)).setText(R.string.sz_done);
            Hush.clearShizukuAlert(this);
        }
    }

    private View step(int id, int title, int desc, int action, View.OnClickListener click) {
        View row = findViewById(id);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.desc)).setText(desc);
        Button b = row.findViewById(R.id.action);
        b.setText(action);
        b.setContentDescription(getString(action) + ": " + getString(title));
        b.setOnClickListener(click);
        return row;
    }

    // A finished step shows "Done" instead of its button; a step that cannot be done yet is dimmed.
    private void state(View row, boolean done, boolean available) {
        Button b = row.findViewById(R.id.action);
        TextView mark = row.findViewById(R.id.done);
        b.setVisibility(done ? View.GONE : View.VISIBLE);
        mark.setVisibility(done ? View.VISIBLE : View.GONE);
        b.setEnabled(available);
        row.setAlpha(done || available ? 1f : 0.5f);
    }

    private boolean open(Intent i) {
        if (i == null) return false;
        try { startActivity(i); return true; } catch (Exception e) { return false; }
    }
}
