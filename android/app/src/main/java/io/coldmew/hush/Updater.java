package io.coldmew.hush;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import io.coldmew.hush.helper.HushHelper;

// Updates from GitHub Releases. Once a day (unless turned off) Hush asks GitHub for the latest
// release: that request carries nothing about the user or the phone. A newer version is announced
// once, with the one-line summary that starts its release notes; "Not now" means that version is
// not announced again, and the Update row in Hush always offers the latest one. An update is
// installed only if the downloaded APK matches GitHub's SHA-256, is Hush, is newer and is signed
// with the same key as the installed Hush. With Hush's helper it installs without any prompt (and
// keeps all settings and the pairing); otherwise Android's installer asks once.
final class Updater {
    static final String ACTION_LATER = "io.coldmew.hush.UPDATE_LATER";
    static final String EXTRA_UPDATE = "update";
    private static final String API = "https://api.github.com/repos/coldMEW/Hush/releases/latest";
    private static final int JOB_ID = 42, NOTIF = 6;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    static final class Release {
        final String version, summary, apkUrl, sha256;
        final long size;
        Release(String version, String summary, String apkUrl, String sha256, long size) {
            this.version = version; this.summary = summary; this.apkUrl = apkUrl; this.sha256 = sha256; this.size = size;
        }
    }

    private Updater() {}

    // ---- checking ----

    // The daily check, as a job that runs only with a network connection.
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!new Prefs(c).updateCheck()) { js.cancel(JOB_ID); return; }
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Job.class))
                .setPeriodic(24 * 60 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    public static class Job extends JobService {
        @Override public boolean onStartJob(JobParameters params) {
            Context c = getApplicationContext();
            worker.execute(() -> {
                try {
                    Release r = latest(c);
                    if (r != null && newer(r.version, version(c)) && !r.version.equals(new Prefs(c).updateNotified())) notifyAvailable(c, r);
                } finally {
                    jobFinished(params, false);
                }
            });
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) { return true; }
    }

    // The latest release on GitHub, or null (no network, no APK attached, unexpected answer).
    // Remembered for the Update row.
    static Release latest(Context c) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(API).openConnection();
            h.setConnectTimeout(10_000);
            h.setReadTimeout(10_000);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("User-Agent", "Hush");
            if (h.getResponseCode() != 200) return null;
            JSONObject j;
            try (InputStream in = h.getInputStream()) { j = new JSONObject(new String(readAll(in), "UTF-8")); }
            String version = j.optString("tag_name").replaceFirst("^v", "");
            JSONArray assets = j.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (!a.optString("name").endsWith(".apk")) continue;
                String digest = a.optString("digest");
                Release r = new Release(version, summary(j.optString("body")), a.optString("browser_download_url"),
                        digest.startsWith("sha256:") ? digest.substring(7) : "", a.optLong("size"));
                new Prefs(c).latest(r.version, r.summary);
                return r;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // The first line of the release notes, without Markdown: each release starts with a one-line
    // summary of what changed.
    static String summary(String body) {
        for (String line : body.split("\\r?\\n")) {
            String t = line.replaceAll("[*_`#>]", "").replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1").replaceFirst("^\\s*-\\s*", "").trim();
            if (t.isEmpty()) continue;
            return t.length() > 140 ? t.substring(0, 137).trim() + "…" : t;
        }
        return "";
    }

    // "2.0.10" > "2.0.9"; non-numeric parts count as 0.
    static boolean newer(String latest, String current) {
        String[] a = latest.split("\\."), b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? num(a[i]) : 0, y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*", "")); } catch (NumberFormatException e) { return 0; }
    }

    static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return "0"; }
    }

    // ---- telling the user ----

    private static void notifyAvailable(Context c, Release r) {
        // Not allowed to show it (yet): e.g. the first check runs right after install, before the
        // user allowed notifications. Then the version is not marked as announced: the next daily
        // check tries again (and the Update row in Hush shows it meanwhile).
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        android.app.NotificationChannel ch = nm.getNotificationChannel(Hush.CH_ALERT);
        if (!nm.areNotificationsEnabled() || ch == null || ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
        new Prefs(c).updateNotified(r.version);   // once per version, whatever the answer
        Intent update = new Intent(c, MainActivity.class).putExtra(EXTRA_UPDATE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent yes = PendingIntent.getActivity(c, 11, update, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent later = PendingIntent.getBroadcast(c, 12, new Intent(c, Hush.Receiver.class).setAction(ACTION_LATER), PendingIntent.FLAG_IMMUTABLE);
        String text = r.summary.isEmpty() ? c.getString(R.string.update_text_plain) : r.summary;
        c.getSystemService(NotificationManager.class).notify(NOTIF, new Notification.Builder(c, Hush.CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_hush).setColor(c.getColor(R.color.accent))
                .setContentTitle(c.getString(R.string.update_title, r.version))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text + "\n" + c.getString(R.string.update_kept)))
                .setContentIntent(yes).setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.update_now), yes).build())
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.update_later), later).build())
                .build());
    }

    static void cancelNotice(Context c) { c.getSystemService(NotificationManager.class).cancel(NOTIF); }

    // ---- installing ----

    enum Result { UP_TO_DATE, NO_NETWORK, BAD_DOWNLOAD, NEEDS_PERMISSION, PROMPTED, INSTALLED, FAILED }

    interface Done { void done(Result r, Release release); }

    // Check, download, verify and install the latest release in the background; done runs on that
    // thread (the caller posts to the UI).
    static void updateNow(Context ctx, Done done) {
        Context c = ctx.getApplicationContext();
        worker.execute(() -> {
            Release r = latest(c);
            if (r == null) { done.done(Result.NO_NETWORK, null); return; }
            if (!newer(r.version, version(c))) { done.done(Result.UP_TO_DATE, r); return; }
            File apk = new File(c.getCacheDir(), "update.apk");
            if (!download(r, apk) || !verified(c, apk)) { apk.delete(); done.done(Result.BAD_DOWNLOAD, r); return; }
            Events.add(c, Events.INFO, "Installing Hush " + r.version);
            cancelNotice(c);
            new Prefs(c).updating(true);
            String out = HushHelper.ready() ? HushHelper.install(apk) : null;
            if (out != null && out.contains("Success")) { done.done(Result.INSTALLED, r); return; }
            if (HushHelper.ready() && out != null) Events.add(c, Events.FAIL, "Update through the helper failed: " + out);
            done.done(installWithPrompt(c, apk), r);
        });
    }

    private static boolean download(Release r, File to) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(r.apkUrl).openConnection();   // follows GitHub's redirect
            h.setConnectTimeout(15_000);
            h.setReadTimeout(30_000);
            h.setRequestProperty("User-Agent", "Hush");
            if (h.getResponseCode() != 200) return false;
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] b = new byte[65536];
                for (int n; (n = in.read(b)) > 0; ) { out.write(b, 0, n); sha.update(b, 0, n); }
            }
            if (r.size > 0 && to.length() != r.size) return false;
            return r.sha256.isEmpty() || hex(sha.digest()).equalsIgnoreCase(r.sha256);
        } catch (Exception e) {
            return false;
        }
    }

    // Hush, newer than the installed version, and signed with exactly the same key(s).
    static boolean verified(Context c, File apk) {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        try {
            PackageInfo a = pm.getPackageArchiveInfo(apk.getPath(), flags);
            PackageInfo me = pm.getPackageInfo(c.getPackageName(), flags);
            if (a == null || !c.getPackageName().equals(a.packageName)) return false;
            if (Build.VERSION.SDK_INT >= 28 ? a.getLongVersionCode() <= me.getLongVersionCode() : a.versionCode <= me.versionCode) return false;
            return Arrays.equals(signers(a), signers(me));
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo p) {
        if (Build.VERSION.SDK_INT >= 28)
            return p.signingInfo == null ? null : p.signingInfo.hasMultipleSigners()
                    ? p.signingInfo.getApkContentsSigners() : p.signingInfo.getSigningCertificateHistory();
        return p.signatures;
    }

    // Without the helper: Android's installer, which asks once to confirm (and, the first time,
    // to allow Hush to install apps).
    private static Result installWithPrompt(Context c, File apk) {
        if (!c.getPackageManager().canRequestPackageInstalls()) return Result.NEEDS_PERMISSION;
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(c.getPackageName());
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id);
                 InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("hush.apk", 0, apk.length())) {
                byte[] b = new byte[65536];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                s.fsync(out);
                out.close();
                Intent status = new Intent(c, InstallStatus.class);
                s.commit(PendingIntent.getBroadcast(c, 13, status, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
            }
            return Result.PROMPTED;
        } catch (Exception e) {
            return Result.FAILED;
        }
    }

    // Android's installer reports here; when it needs the user's confirmation, its screen is shown.
    public static class InstallStatus extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                Events.add(c, Events.FAIL, "Update not installed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384];
        for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        return o.toByteArray();
    }

    private static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format("%02x", x));
        return s.toString();
    }
}
