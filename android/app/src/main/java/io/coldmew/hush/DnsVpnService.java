package io.coldmew.hush;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// DNS-only VPN for Spotify: the tunnel carries just DNS server addresses (our fake one plus the
// common public resolvers, in case an app hard-codes one), so all other Spotify traffic and every
// other app go out normally. Each query is matched against Rules, answered locally or forwarded.
public class DnsVpnService extends VpnService {
    static final String ACTION_STOP = "io.coldmew.hush.STOP_VPN";
    static final String ACTION_RELOAD = "io.coldmew.hush.RELOAD_RULES";   // after the rules were edited
    static volatile boolean running;
    private static final String DNS_IP = "10.111.0.2";
    private static final String[] PUBLIC_DNS = {
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15",
    };

    private ParcelFileDescriptor tun;
    private ThreadPoolExecutor pool;
    private volatile Rules rules;
    private final AtomicLong hits = new AtomicLong();
    private boolean stopping;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stop(false); return START_NOT_STICKY; }
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            try { if (running) rules = new Rules(Rules.file(this)); } catch (Exception e) { Log.w(Hush.TAG, "rules not reloaded"); }
            return START_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(Hush.NOTIF_STATUS, Hush.statusNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(Hush.NOTIF_STATUS, Hush.statusNotification(this));
        Prefs p = new Prefs(this);
        if (!p.enabled() || !p.dns()) { stop(false); return START_NOT_STICKY; }
        if (!running) start();
        return START_STICKY;
    }

    private void start() {
        try {
            rules = new Rules(Rules.file(this));
            PendingIntent ui = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Builder b = new Builder().setSession(getString(R.string.app_name)).setConfigureIntent(ui)
                    .addAddress("10.111.0.1", 24).addDnsServer(DNS_IP).addRoute(DNS_IP, 32).setBlocking(true);
            for (String d : PUBLIC_DNS) b.addRoute(d, 32);
            b.addAllowedApplication(Hush.SPOTIFY);
            tun = b.establish();
            if (tun == null) { Events.add(this, Events.FAIL, "DNS filter could not start (VPN permission missing)"); stop(false); return; }
            running = true;
            stopping = false;
            // At most 16 lookups in flight and 64 waiting; beyond that queries are dropped (the
            // resolver retries), so a burst (e.g. skipping songs quickly) cannot exhaust memory.
            pool = new ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
                    new ThreadPoolExecutor.DiscardPolicy());
            pool.allowCoreThreadTimeOut(true);
            new Thread(this::loop, "hush-tun").start();
            Events.add(this, Events.INFO, "DNS filter started");
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            Events.add(this, Events.FAIL, "DNS filter not started: Spotify is not installed");
            stop(false);
        } catch (Exception e) {
            Events.add(this, Events.FAIL, "DNS filter error: " + e.getClass().getSimpleName());
            stop(false);
        }
    }

    private void loop() {
        FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
        byte[] buf = new byte[32767];
        long lastFlush = System.currentTimeMillis();
        while (running) {
            int n;
            try { n = in.read(buf); } catch (Exception e) { break; }
            if (System.currentTimeMillis() - lastFlush > 15_000) { flushHits(); lastFlush = System.currentTimeMillis(); }
            if (n <= 0) continue;
            Dns.Query q = Dns.parse(buf, n);
            if (q == null) continue;
            try { pool.execute(() -> handle(q, out)); } catch (Exception e) { break; }
        }
        if (running) stop(false);   // the tunnel failed on its own
    }

    private void handle(Dns.Query q, FileOutputStream out) {
        Rules.Rule r = rules.match(q.name);
        byte[] resp;
        if (r == null) {
            resp = forward(q);
            if (resp == null) return;
        } else {
            switch (r.kind) {
                case "nxdomain": resp = Dns.answer(q, 3, null); break;
                case "redirect": resp = Dns.answer(q, 0, rules.resolve(r.arg)); break;
                case "ip": resp = Dns.answer(q, 0, Collections.singletonList(Rules.ipv4(r.arg))); break;
                default: resp = Dns.answer(q, 0, Collections.singletonList(new byte[4])); break;
            }
            hits.incrementAndGet();
            if (BuildConfig.DEBUG) Log.d(Hush.TAG, "DNS " + r.kind + " " + q.name);
        }
        try { synchronized (out) { out.write(Dns.wrap(q, resp, resp.length)); } } catch (Exception e) { Log.w(Hush.TAG, "tun write failed"); }
    }

    // Sends the query upstream from a protected (non-VPN) socket. If the upstream does not answer
    // within 1.2 s, the query also goes to the network's own DNS servers: some networks (e.g. a
    // router running AdGuard Home or Pi-hole) block DNS to outside resolvers, and then every lookup
    // would time out. The first valid reply wins. A reply is accepted only from a server that was
    // asked, from port 53 and with our query's ID, so other hosts cannot inject answers.
    private byte[] forward(Dns.Query q) {
        try (DatagramSocket s = new DatagramSocket()) {
            protect(s);
            InetAddress up = InetAddress.getByName(rules.upstream);   // validated IPv4 literal: no lookup
            List<InetAddress> asked = new ArrayList<>();
            asked.add(up);
            s.send(new DatagramPacket(q.dns, q.dns.length, up, 53));
            byte[] b = new byte[4096];
            long start = System.currentTimeMillis(), end = start + 4000;
            boolean fallback = false;
            while (true) {
                long now = System.currentTimeMillis();
                long until = fallback ? end : Math.min(end, start + 1200);
                if (now >= until) {
                    if (fallback || now >= end) break;
                    fallback = true;
                    for (InetAddress d : networkDns()) {
                        if (asked.contains(d)) continue;
                        asked.add(d);
                        try { s.send(new DatagramPacket(q.dns, q.dns.length, d, 53)); } catch (Exception ignored) {}
                    }
                    continue;
                }
                s.setSoTimeout((int) Math.max(1, until - now));
                DatagramPacket p = new DatagramPacket(b, b.length);
                try { s.receive(p); } catch (java.net.SocketTimeoutException t) { continue; }
                if (p.getLength() >= 12 && asked.contains(p.getAddress()) && p.getPort() == 53
                        && b[0] == q.dns[0] && b[1] == q.dns[1]) return Arrays.copyOf(b, p.getLength());
            }
            Log.w(Hush.TAG, "DNS forward failed: no answer from " + asked.size() + " server(s)");
            return null;
        } catch (Exception e) {
            Log.w(Hush.TAG, "DNS forward failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    // The DNS servers of the phone's real networks (Wi-Fi or mobile, not this VPN), cached briefly.
    private volatile List<InetAddress> netDns = new ArrayList<>();
    private volatile long netDnsAt;
    private List<InetAddress> networkDns() {
        if (System.currentTimeMillis() - netDnsAt < 30_000) return netDns;
        List<InetAddress> out = new ArrayList<>();
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) for (InetAddress a : lp.getDnsServers()) if (!out.contains(a)) out.add(a);
            }
        } catch (Exception ignored) {}
        netDns = out;
        netDnsAt = System.currentTimeMillis();
        return out;
    }

    private void flushHits() {
        long n = hits.getAndSet(0);
        if (n > 0) new Prefs(this).add(Prefs.DNS_HITS, n);
    }

    private synchronized void stop(boolean revoked) {
        if (stopping) return;
        stopping = true;
        boolean was = running;
        running = false;
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        tun = null;
        if (pool != null) pool.shutdownNow();
        flushHits();
        if (was) Events.add(this, revoked ? Events.FAIL : Events.INFO,
                revoked ? "DNS filter stopped: another VPN app took over" : "DNS filter stopped");
        stopForeground(STOP_FOREGROUND_DETACH);
        Hush.refreshNotification(this);   // keeps the status notification while Hush is on
        stopSelf();
    }

    @Override public void onRevoke() { stop(true); }
    @Override public void onDestroy() { stop(false); super.onDestroy(); }
}
