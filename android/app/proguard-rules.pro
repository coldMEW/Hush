# Shell.sh() calls Shizuku.newProcess by reflection.
-keep class rikka.shizuku.Shizuku { *; }

# No verbose, debug or info logging in release builds (track names, domains).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
