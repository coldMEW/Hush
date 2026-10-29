package io.coldmew.hush.helper;

import android.os.IBinder;

// What Hush and its helper agree on. Bump VERSION when a transaction changes; Hush refuses an older
// helper and starts a fresh one.
public final class HelperProtocol {
    public static final String DESCRIPTOR = "io.coldmew.hush.IHelper";
    public static final int VERSION = 4;
    public static final int SHELL_UID = 2000;

    public static final String AUTHORITY_SUFFIX = ".helper";
    public static final String METHOD_HELLO = "hello";
    public static final String EXTRA_BINDER = "binder";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_VERSION = "version";

    public static final int OK = 0, ERROR = 1;

    // Transactions. RUN executes one command from Commands (action id + argument, validated in the
    // helper); there is no way to run anything else.
    public static final int PING = IBinder.FIRST_CALL_TRANSACTION;
    public static final int FORCE_STOP = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int RUN = IBinder.FIRST_CALL_TRANSACTION + 2;
    public static final int EXIT = IBinder.FIRST_CALL_TRANSACTION + 3;
    public static final int FRONT = IBinder.FIRST_CALL_TRANSACTION + 4;
    // Read-only: Android's active projection types (Android Auto drives a car screen), which only
    // the shell may read. Version 4.
    public static final int PROJECTION = IBinder.FIRST_CALL_TRANSACTION + 5;
    // Brings an existing task (by id, as read with the app in front) back to the front: the same
    // window in the same state, not a new start of the app. Version 4.
    public static final int TO_FRONT = IBinder.FIRST_CALL_TRANSACTION + 6;
    // Installs an update of Hush: an open APK file (checked by Hush before: same package, newer
    // version, same signing key) is streamed into `pm install -r`, which keeps Hush's data. Version 4.
    public static final int INSTALL = IBinder.FIRST_CALL_TRANSACTION + 7;

    private HelperProtocol() {}
}
