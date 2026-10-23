package io.coldmew.hush;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

// Activity log: kept in memory for the UI and appended to ads.log (private app storage),
// one line per event: epoch-seconds, HH:mm:ss, kind, message.
final class Events {
    static final String SKIP = "SKIP", MUTE = "MUTE", FAIL = "FAIL", INFO = "INFO", AD = "AD";
    private static final int KEEP = 60;

    static final class Event {
        final long time; final String kind; final String msg;
        Event(long time, String kind, String msg) { this.time = time; this.kind = kind; this.msg = msg; }
    }

    private static final ArrayDeque<Event> recent = new ArrayDeque<>();
    private static final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();
    private static boolean loaded;

    private Events() {}

    static File file(Context c) { return new File(c.getFilesDir(), "ads.log"); }

    static void add(Context c, String kind, String msg) {
        long now = System.currentTimeMillis();
        Log.i(Hush.TAG, kind + " " + msg);
        synchronized (recent) {
            load(c);
            recent.addFirst(new Event(now, kind, msg));
            while (recent.size() > KEEP) recent.removeLast();
            File f = file(c);
            if (f.length() > 256_000) f.delete();   // keep the log small; the newest entries stay in memory
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(now / 1000 + "\t" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now))
                        + "\t" + kind + "\t" + msg.replace('\n', ' ') + "\n");
            } catch (Exception ignored) {}
        }
        for (Runnable r : listeners) r.run();
    }

    static List<Event> recent(Context c) {
        synchronized (recent) { load(c); return new ArrayList<>(recent); }
    }

    static void clear(Context c) {
        synchronized (recent) { recent.clear(); loaded = true; }
        file(c).delete();
        for (Runnable r : listeners) r.run();
    }

    static void listen(Runnable r) { listeners.add(r); }
    static void unlisten(Runnable r) { listeners.remove(r); }

    // Reads the tail of ads.log once.
    private static void load(Context c) {
        if (loaded) return;
        loaded = true;
        File f = file(c);
        if (!f.exists()) return;
        ArrayDeque<Event> tail = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 4);
                if (p.length < 4) continue;
                long t;
                try { t = Long.parseLong(p[0]) * 1000; } catch (NumberFormatException e) { continue; }
                tail.addFirst(new Event(t, p[2], p[3]));
                if (tail.size() > KEEP) tail.removeLast();
            }
        } catch (Exception ignored) {}
        recent.addAll(tail);
    }
}
