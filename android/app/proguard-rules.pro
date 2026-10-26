# Shell.sh() calls Shizuku.newProcess by reflection.
-keep class rikka.shizuku.Shizuku { *; }

# No verbose, debug or info logging in release builds (track names, domains).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Hush's helper is started by name with app_process (see helper/HushHelper.startCommand); nothing
# in the app calls it, so R8 must keep it.
-keep class io.coldmew.hush.helper.Helper { public static void main(java.lang.String[]); }
