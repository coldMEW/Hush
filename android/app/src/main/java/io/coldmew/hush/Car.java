package io.coldmew.hush;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import io.coldmew.hush.helper.HushHelper;

// Android Auto. While the phone drives a car screen, a skip must not switch apps on the phone:
// bringing an app to the front can restart the Android Auto session and reset navigation. Users
// also choose what happens to ads in the car: skip, mute, or nothing (Prefs.carMode).
final class Car {
    private Car() {}

    // Car mode, or an active automotive projection (what Android Auto registers while it drives a
    // car screen; only the shell may read it, so Hush asks its helper).
    static boolean connected(Context c) {
        UiModeManager um = c.getSystemService(UiModeManager.class);
        if (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR) return true;
        int types = HushHelper.projection();
        return types > 0 && (types & PROJECTION_AUTOMOTIVE) != 0;
    }

    private static final int PROJECTION_AUTOMOTIVE = 1;   // UiModeManager.PROJECTION_TYPE_AUTOMOTIVE
}
