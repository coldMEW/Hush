package io.coldmew.hush.helper;

import android.content.AttributionSource;
import android.os.Bundle;
import java.lang.reflect.Method;

// Calls a content provider from the helper process, which has no Context. This goes through
// Android's internal activity-manager and provider interfaces, whose method signatures changed over
// the years; each known variant is handled (the same ones Shizuku handles).
final class Providers {
    private static final String SHELL_PACKAGE = "com.android.shell";

    private Providers() {}

    static Bundle call(String authority, int userId, String method, Bundle extras) throws Exception {
        Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Method get;
        Object holder;
        try {   // Android 10+: (name, userId, token, tag)
            get = Helper.method(am.getClass(), "getContentProviderExternal", 4);
            holder = get.invoke(am, authority, userId, null, "hush_helper");
        } catch (NoSuchMethodException e) {   // Android 8-9: (name, userId, token)
            get = Helper.method(am.getClass(), "getContentProviderExternal", 3);
            holder = get.invoke(am, authority, userId, null);
        }
        if (holder == null) return null;
        Object provider = holder.getClass().getField("provider").get(holder);
        try {
            return callProvider(provider, authority, method, extras);
        } finally {
            try { Helper.method(am.getClass(), "removeContentProviderExternal", 2).invoke(am, authority, null); }
            catch (Throwable ignored) {}
        }
    }

    private static Bundle callProvider(Object provider, String authority, String method, Bundle extras) throws Exception {
        Class<?> c = provider.getClass();
        int sdk = Helper.sdk();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            for (Method m : c.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("call") && p.length == 5 && p[0] == AttributionSource.class) {
                    AttributionSource src = new AttributionSource.Builder(HelperProtocol.SHELL_UID)
                            .setPackageName(SHELL_PACKAGE).build();
                    return (Bundle) m.invoke(provider, src, authority, method, null, extras);
                }
            }
        }
        if (sdk >= 30) {
            try { return (Bundle) Helper.method(c, "call", 6).invoke(provider, SHELL_PACKAGE, null, authority, method, null, extras); }
            catch (NoSuchMethodException ignored) {}
        }
        if (sdk >= 29) {
            try { return (Bundle) Helper.method(c, "call", 5).invoke(provider, SHELL_PACKAGE, authority, method, null, extras); }
            catch (NoSuchMethodException | IllegalArgumentException ignored) {}
        }
        return (Bundle) Helper.method(c, "call", 4).invoke(provider, SHELL_PACKAGE, method, null, extras);
    }
}
