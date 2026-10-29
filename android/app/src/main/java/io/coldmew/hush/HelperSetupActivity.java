package io.coldmew.hush;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.util.Locale;
import io.coldmew.hush.helper.HushHelper;

// Set up skipping with Hush alone (no other app): Developer options, pairing with Wireless
// debugging once, an extra step some phone makers need, then Hush starts its helper by itself.
// Each step opens the right place and is ticked off as soon as it is done.
public class HelperSetupActivity extends Activity {
    private static final int REQ_NOTIF = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private View stepDev, stepPair, stepMaker, stepReady, codeRow;
    private TextView intro, progress;
    private EditText code;
    private int makerStep;   // string resource of this phone's extra step, or 0

    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 1000); }
    };
    private final Runnable changed = () -> ui.post(this::refresh);
    private final HushHelper.Listener helperChanged = connected -> ui.post(this::refresh);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!HelperControl.supported()) {   // no Wireless debugging before Android 11: Shizuku's guide
            startActivity(new Intent(this, ShizukuSetupActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_helper);
        Insets.apply(this, findViewById(R.id.root));
        findViewById(R.id.back).setOnClickListener(v -> finish());
        intro = findViewById(R.id.intro);
        progress = findViewById(R.id.progress);
        codeRow = findViewById(R.id.code_row);
        code = findViewById(R.id.code);

        stepDev = step(R.id.step_dev, R.string.hs_dev, R.string.hs_dev_desc, R.string.action_open, v -> {
            new Prefs(this).devStep(true);   // ticked when the user comes back (see refresh)
            int f = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;
            if (!open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(f))) open(new Intent(Settings.ACTION_SETTINGS).addFlags(f));
        });
        stepPair = step(R.id.step_pair, R.string.hs_pair, R.string.hs_pair_desc, R.string.hs_pair_action, v -> startPairing());
        makerStep = makerStep();
        stepMaker = step(R.id.step_maker, R.string.hs_maker, makerStep == 0 ? R.string.hs_maker : makerStep, R.string.action_open,
                v -> openDeveloperOptions(false));
        if (makerStep != 0) {
            stepMaker.setVisibility(View.VISIBLE);
            findViewById(R.id.maker_divider).setVisibility(View.VISIBLE);
        }
        stepReady = step(R.id.step_ready, R.string.hs_ready, R.string.hs_ready_desc, R.string.hs_ready_action,
                v -> HelperControl.ensure(this, true, false));

        findViewById(R.id.code_send).setOnClickListener(v -> sendCode());
        code.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE) { sendCode(); return true; }
            return false;
        });
        findViewById(R.id.use_shizuku).setOnClickListener(v -> startActivity(new Intent(this, ShizukuSetupActivity.class)));
    }

    @Override protected void onResume() {
        super.onResume();
        PairingService.listen(changed);
        HushHelper.addListener(helperChanged);
        ui.post(ticker);
    }

    @Override protected void onPause() {
        super.onPause();
        PairingService.unlisten(changed);
        HushHelper.removeListener(helperChanged);
        ui.removeCallbacks(ticker);
    }

    // Pair: the code notification needs notification permission; then open Wireless debugging.
    private void startPairing() {
        new Prefs(this).devStep(true);   // moving on means step 1 is done; if not, Pair keeps waiting and says so
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;   // continues in onRequestPermissionsResult
        }
        PairingService.start(this);
        openDeveloperOptions(true);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req != REQ_NOTIF) return;
        // Without notifications the code can still be typed here (split screen).
        PairingService.start(this);
        openDeveloperOptions(true);
    }

    // Opens Developer options, scrolled to Wireless debugging if asked (the same key Settings uses
    // for search results). In Settings' own window, so Hush's screen is not left behind Settings;
    // CLEAR_TOP makes a Settings window left open elsewhere show Developer options, not the page it
    // showed last.
    private void openDeveloperOptions(boolean wireless) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (wireless) i.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
        if (!open(i)) open(new Intent(Settings.ACTION_SETTINGS));
    }

    private void sendCode() {
        String c = code.getText().toString().replaceAll("\\D", "");
        if (c.length() != 6) { code.setError(getString(R.string.pair_code_length)); return; }
        PairingService.submit(this, c);
        code.setText("");
    }

    private void refresh() {
        boolean ready = HushHelper.ready();
        // Newer Android versions report Developer options and USB debugging as off to apps, so the
        // step also counts as done once the user went through it (Open, or moving on to Pair).
        Prefs prefs = new Prefs(this);
        boolean dev = Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
                || Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1
                || HelperControl.debuggingOn(this) || prefs.devStep();
        boolean paired = prefs.paired() || ready;
        boolean wifi = HelperControl.onWifi(this);
        PairingService.State ps = PairingService.state();

        state(stepDev, dev || paired, true);

        int pairDesc;
        switch (ps) {
            case SEARCHING: pairDesc = PairingService.popupClosed() ? R.string.hs_pair_closed : R.string.hs_pair_searching; break;
            case FOUND: pairDesc = isInMultiWindowMode() ? R.string.hs_pair_found_here : R.string.hs_pair_found; break;
            case PAIRING: case STARTING: pairDesc = R.string.pair_working_text; break;
            case WRONG_CODE: pairDesc = R.string.pair_wrong_text; break;
            case FAILED: pairDesc = R.string.pair_failed_text; break;
            default: pairDesc = !wifi && !paired ? R.string.hs_pair_wifi : R.string.hs_pair_desc;
        }
        ((TextView) stepPair.findViewById(R.id.desc)).setText(pairDesc);
        boolean pairing = ps == PairingService.State.SEARCHING || ps == PairingService.State.FOUND
                || ps == PairingService.State.WRONG_CODE || ps == PairingService.State.PAIRING || ps == PairingService.State.STARTING;
        // Pair stays available while Hush is waiting for the code: tapping it again reopens Wireless
        // debugging and starts a fresh search. Only an actual pairing in progress disables it.
        boolean working = ps == PairingService.State.PAIRING || ps == PairingService.State.STARTING;
        state(stepPair, paired && !pairing, wifi && !working);   // Developer options cannot always be read
        // The code box here works only side by side with Settings (split screen): switching to Hush
        // in full screen closes the pairing popup, and its code with it. Otherwise the code goes into
        // Hush's notification, which pops up over Settings.
        boolean askCode = (ps == PairingService.State.FOUND || ps == PairingService.State.WRONG_CODE) && isInMultiWindowMode();
        int codeVisibility = askCode ? View.VISIBLE : View.GONE;
        if (codeRow.getVisibility() != codeVisibility) codeRow.setVisibility(codeVisibility);

        if (makerStep != 0) state(stepMaker, ready, true);   // cannot be checked from an app: done once skipping works

        ((TextView) stepReady.findViewById(R.id.desc)).setText(ready ? R.string.hs_ready_desc_done
                : paired && !HelperControl.debuggingOn(this) && !HelperControl.canToggleDebugging(this)
                ? R.string.hs_ready_desc_off : R.string.hs_ready_desc);
        state(stepReady, ready, paired && !pairing);

        int total = makerStep != 0 ? 4 : 3;
        int done = (dev || paired ? 1 : 0) + (paired ? 1 : 0) + (makerStep != 0 && ready ? 1 : 0) + (ready ? 1 : 0);
        progress.setText(ready ? getString(R.string.hs_progress_done) : String.format(Locale.getDefault(),
                getString(R.string.hs_progress), Math.min(done + 1, total), total));
        intro.setText(ready ? R.string.hs_done : R.string.hs_intro);
        if (ready) Hush.clearSkipAlert(this);
    }

    // The extra Developer options switch some makers require for Wireless debugging tools.
    private static int makerStep() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) return R.string.hs_maker_xiaomi;
        if (m.contains("oppo") || m.contains("oneplus") || m.contains("realme")) return R.string.hs_maker_oppo;
        if (m.contains("meizu")) return R.string.hs_maker_meizu;
        if (m.contains("huawei") || m.contains("honor")) return R.string.hs_maker_huawei;
        return 0;
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
        try { startActivity(i); return true; } catch (Exception e) { return false; }
    }
}
