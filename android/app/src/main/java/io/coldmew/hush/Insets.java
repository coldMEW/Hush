package io.coldmew.hush;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

// Window chrome. Android 15 and later always draw the app edge to edge, so the content is padded
// clear of the status and navigation bars there; the bar icons are dark on the light theme.
final class Insets {
    private Insets() {}

    static void apply(Activity a, View root) {
        boolean light = a.getResources().getBoolean(R.bool.light_bars);
        if (Build.VERSION.SDK_INT >= 27 && light) {
            View d = a.getWindow().getDecorView();
            d.setSystemUiVisibility(d.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (Build.VERSION.SDK_INT < 35) return;
        int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom);
            return in;
        });
        root.requestApplyInsets();
    }
}
