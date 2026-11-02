package io.coldmew.hush;

import android.content.Context;
import android.os.Process;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.coldmew.hush.helper.Commands;

// Hush 2.0.3 and 2.0.4 installed their in-app updates for every Android user on the phone. On
// phones with an app-clone space (Samsung's Dual Messenger, Android's clone profile) that put a
// second Hush there, shown as "Hush Clone". Updates now install for Hush's own user only. Once after
// an update, Hush removes such a copy. Android records no install time for a user that got the app
// through an install for every user (dumpsys shows 1970-01-01, i.e. 0), while a clone made on
// purpose (Android's clone settings use install-existing) gets the time it was made. So a copy with
// no install time, or one that arrived together with the update (within 10 s), is removed; any other
// copy is the user's and is left alone. Measured on Android 17 with a real clone profile.
final class UpdateCopies {
    private UpdateCopies() {}

    private static final long WINDOW_MS = 10_000;
    private static final Pattern USER = Pattern.compile("^\\s*User (\\d+): .*\\binstalled=(true|false)");
    private static final Pattern FIRST = Pattern.compile("^\\s*firstInstallTime=(.+?)\\s*$");
    private static final Pattern UPDATED = Pattern.compile("^\\s*lastUpdateTime=(.+?)\\s*$");

    // From Commands.HUSH_USERS's output (lines of `dumpsys package`): the users other than myUser
    // that got Hush with no install time of their own, or within WINDOW_MS of its last update.
    // Empty if anything is missing or unclear.
    static List<Integer> fromUpdate(String dump, int myUser) {
        List<Integer> out = new ArrayList<>();
        if (dump == null) return out;
        long updated = -1;
        int user = -1;
        boolean installed = false;
        List<long[]> users = new ArrayList<>();   // {user, firstInstallTime}
        for (String line : dump.split("\n")) {
            Matcher m;
            if (updated < 0 && (m = UPDATED.matcher(line)).find()) updated = time(m.group(1));
            else if ((m = USER.matcher(line)).find()) { user = Integer.parseInt(m.group(1)); installed = "true".equals(m.group(2)); }
            else if (user >= 0 && (m = FIRST.matcher(line)).find()) {
                if (installed) users.add(new long[]{user, time(m.group(1))});
                user = -1;
            }
        }
        if (updated <= 0) return out;
        for (long[] u : users)
            if (u[0] != myUser && (u[1] == 0 || u[1] > 0 && Math.abs(u[1] - updated) <= WINDOW_MS)) out.add((int) u[0]);
        return out;
    }

    // Milliseconds; 0 for "not set" (dumpsys prints time 0 in local time, 1969-12-31 or 1970-01-01);
    // -1 if unreadable.
    private static long time(String s) {
        try {
            long t = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s.trim()).getTime();
            return Math.abs(t) <= 86_400_000L ? 0 : t;
        } catch (ParseException e) { return -1; }
    }

    // Blocking (privileged commands). Called once after an update, with the helper or Shizuku ready.
    static void removeIfAny(Context c) {
        if (!Shell.ready()) return;
        int me = Process.myUid() / 100000;
        for (int user : fromUpdate(Shell.run(Commands.HUSH_USERS, c.getPackageName()), me)) {
            String out = Shell.run(Commands.REMOVE_HUSH_COPY, user + "/" + c.getPackageName());
            if (out.contains("Success"))
                Events.add(c, Events.INFO, "Removed the extra copy of Hush that the last update added to an app-clone space");
        }
    }
}
