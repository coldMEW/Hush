package io.coldmew.hush.helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

// Receives the Binder the helper process hands over. The helper (uid 2000) reaches this provider
// through getContentProviderExternal + call(); the manifest guards it with INTERACT_ACROSS_USERS_FULL
// so only a shell/privileged caller can invoke it. The token (checked in HushHelper.onHello) makes
// sure the handoff is the one Hush actually started.
public final class HelperProvider extends ContentProvider {

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!HelperProtocol.METHOD_HELLO.equals(method) || extras == null) return null;
        // Only the shell user (the helper) may hand a binder over.
        if (Binder.getCallingUid() != HelperProtocol.SHELL_UID) return null;

        extras.setClassLoader(getClass().getClassLoader());
        IBinder helperBinder = extras.getBinder(HelperProtocol.EXTRA_BINDER);
        String token = extras.getString(HelperProtocol.EXTRA_TOKEN);
        int version = extras.getInt(HelperProtocol.EXTRA_VERSION, -1);
        if (helperBinder == null || token == null) return null;

        IBinder liveness = HushHelper.onHello(getContext(), helperBinder, token, version);
        if (liveness == null) return null;

        Bundle reply = new Bundle();
        reply.putBinder(HelperProtocol.EXTRA_BINDER, liveness);
        return reply;
    }

    // Unused ContentProvider surface.
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
