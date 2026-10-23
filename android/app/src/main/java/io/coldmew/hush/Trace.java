package io.coldmew.hush;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

// Diagnostics (debug builds only): records every change of Spotify's media session (all metadata fields, playback
// state and allowed actions) to meta.log, so ads that are not labelled "Advertisement" can be
// found and recognised. Bitmaps are skipped; the file is capped at 1 MB.
final class Trace {
    private static final String TAG = "HushMeta";
    private static final Set<String> TEXT = new HashSet<>(Arrays.asList(
            MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_AUTHOR, MediaMetadata.METADATA_KEY_WRITER,
            MediaMetadata.METADATA_KEY_COMPOSER, MediaMetadata.METADATA_KEY_COMPILATION, MediaMetadata.METADATA_KEY_DATE,
            MediaMetadata.METADATA_KEY_GENRE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
            MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_MEDIA_URI, MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
    private static final Set<String> LONG = new HashSet<>(Arrays.asList(
            MediaMetadata.METADATA_KEY_DURATION, MediaMetadata.METADATA_KEY_TRACK_NUMBER,
            MediaMetadata.METADATA_KEY_NUM_TRACKS, MediaMetadata.METADATA_KEY_DISC_NUMBER,
            MediaMetadata.METADATA_KEY_YEAR, MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE));
    private static String last;

    private Trace() {}

    @SuppressLint("WrongConstant")   // Spotify-specific metadata keys
    static synchronized void log(Context c, MediaController mc) {
        StringBuilder b = new StringBuilder();
        MediaMetadata m = mc.getMetadata();
        if (m != null) {
            for (String k : m.keySet()) {
                String name = k.replace("android.media.metadata.", "");
                if (TEXT.contains(k)) b.append(name).append('=').append(m.getText(k)).append("; ");
                else if (LONG.contains(k)) b.append(name).append('=').append(m.getLong(k)).append("; ");
                else if (!k.contains("ART") && !k.contains("ICON")) {
                    Object v = null;
                    try { v = m.getString(k); } catch (Exception ignored) {}
                    if (v == null && k.endsWith("ADVERTISEMENT")) v = m.getLong(k);
                    b.append(name).append(v == null ? "" : "=" + v).append("; ");
                }
            }
        }
        Bundle ex = mc.getExtras();
        if (ex != null) for (String k : ex.keySet()) b.append(" ex:").append(k).append('=').append(ex.get(k));
        PlaybackState ps = mc.getPlaybackState();
        if (ps != null) {
            b.append("| state=").append(ps.getState()).append(" actions=").append(ps.getActions());
            for (PlaybackState.CustomAction a : ps.getCustomActions()) b.append(" [").append(a.getName()).append(']');
            Bundle x = ps.getExtras();
            if (x != null) for (String k : x.keySet()) b.append(" x:").append(k).append('=').append(x.get(k));
        }
        String s = b.toString();
        if (s.equals(last)) return;
        last = s;
        Log.d(TAG, s);
        File f = new File(c.getExternalFilesDir(null), "meta.log");
        if (f.length() > 1_000_000) f.delete();
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\t" + s + "\n");
        } catch (Exception ignored) {}
    }
}
