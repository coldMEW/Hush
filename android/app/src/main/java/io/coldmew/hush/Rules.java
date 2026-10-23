package io.coldmew.hush;

import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// rules.txt, one per line ('#' comments). A rule also covers subdomains.
//   block <domain>            A 0.0.0.0, other types empty   (esp32-c3-adblock behaviour)
//   nxdomain <domain>         NXDOMAIN
//   redirect <domain> <host>  A records of <host>, other types empty
//   ip <domain> <a.b.c.d>     that A record, other types empty
//   upstream <a.b.c.d>        resolver for everything else (default 1.1.1.1)
final class Rules {
    static final class Rule {
        final String kind; final String arg;
        Rule(String kind, String arg) { this.kind = kind; this.arg = arg; }
    }

    // Measured on Spotify 9.1 (docs/HOW-IT-WORKS.md, section 3): block the video-ad CDNs, and answer the
    // Ad Studio host with a Spotify Akamai edge, which returns 403 instead of a connection error
    // (a plain block there freezes background playback).
    static final String DEFAULTS = "# Hush DNS rules for Spotify\n"
            + "block video-fa.scdn.co\n"
            + "block video-cf.spotifycdn.com\n"
            + "block s0.2mdn.net\n"
            + "redirect adstudio-assets.scdn.co misc.scdn.co\n"
            + "upstream 1.1.1.1\n";
    private static final java.util.Set<String> KINDS = new java.util.HashSet<>(
            java.util.Arrays.asList("block", "nxdomain", "redirect", "ip", "upstream"));

    static File file(android.content.Context c) { return new File(c.getFilesDir(), "rules.txt"); }

    static void installDefaults(android.content.Context c) {
        File f = file(c);
        if (f.exists()) return;
        try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write(DEFAULTS); } catch (Exception ignored) {}
    }

    // Returns null if every line is valid, else "<line number>: <problem>".
    static String validate(String text) {
        String[] lines = text.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            int h = line.indexOf('#');
            if (h >= 0) line = line.substring(0, h);
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            String err = null;
            if (!KINDS.contains(p[0])) err = "unknown rule \"" + p[0] + "\"";
            else if (p.length < 2) err = "missing domain";
            else if (p[0].equals("upstream") || p[0].equals("ip")) {
                String ip = p[0].equals("upstream") ? p[1] : (p.length > 2 ? p[2] : "");
                if (!isIpv4(ip)) err = "needs an IPv4 address like 1.1.1.1";
            } else if (p[0].equals("redirect") && p.length < 3) err = "redirect needs a target host";
            if (err == null && !p[0].equals("upstream") && !isDomain(p[1]))
                err = "\"" + p[1] + "\" is not a domain";
            if (err == null && p[0].equals("redirect") && !isDomain(p[2]))
                err = "\"" + p[2] + "\" is not a domain";
            if (err != null) return (n + 1) + ": " + err;
        }
        return null;
    }

    static boolean isDomain(String s) {
        return s.length() <= 253 && s.matches("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    }

    static boolean isIpv4(String s) {
        String[] p = s.split("\\.", -1);
        if (p.length != 4) return false;
        for (String x : p) {
            if (!x.matches("[0-9]{1,3}") || Integer.parseInt(x) > 255) return false;
        }
        return true;
    }

    static byte[] ipv4(String s) {
        String[] p = s.split("\\.");
        byte[] a = new byte[4];
        for (int i = 0; i < 4 && i < p.length; i++) a[i] = (byte) Integer.parseInt(p[i]);
        return a;
    }

    private final File file;
    private long mtime = -1;
    private Map<String, Rule> rules = new HashMap<>();
    volatile String upstream = "1.1.1.1";
    private final Map<String, List<byte[]>> cache = new HashMap<>();
    private final Map<String, Long> cacheAt = new HashMap<>();

    Rules(File file) { this.file = file; }

    synchronized Rule match(String domain) {
        reload();
        String d = domain.toLowerCase(java.util.Locale.ROOT);
        while (true) {
            Rule r = rules.get(d);
            if (r != null) return r;
            int i = d.indexOf('.');
            if (i < 0) return null;
            d = d.substring(i + 1);
        }
    }

    private void reload() {
        long m = file.exists() ? file.lastModified() : 0;
        if (m == mtime) return;
        mtime = m;
        Map<String, Rule> out = new HashMap<>();
        String up = "1.1.1.1";
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    int h = line.indexOf('#');
                    if (h >= 0) line = line.substring(0, h);
                    line = line.trim();
                    if (line.isEmpty() || validate(line) != null) continue;   // skip anything invalid
                    String[] p = line.split("\\s+");
                    if (p[0].equals("upstream")) { up = p[1]; continue; }
                    out.put(p[1].toLowerCase(java.util.Locale.ROOT), new Rule(p[0], p.length >= 3 ? p[2] : null));
                }
            } catch (Exception e) { Log.w(Hush.TAG, "rules read: " + e); }
        }
        rules = out;
        upstream = up;
        cache.clear();
        cacheAt.clear();
        Log.i(Hush.TAG, "DNS rules loaded: " + out.size());
    }

    // IPv4 addresses of a redirect target, resolved by this app (which is outside the VPN).
    synchronized List<byte[]> resolve(String host) {
        Long at = cacheAt.get(host);
        if (at != null && System.currentTimeMillis() - at < 60_000) return cache.get(host);
        List<byte[]> ips = new ArrayList<>();
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) if (a instanceof Inet4Address) ips.add(a.getAddress());
        } catch (Exception e) { Log.w(Hush.TAG, "resolve " + host + ": " + e); }
        cache.put(host, ips); cacheAt.put(host, System.currentTimeMillis());
        return ips;
    }
}
