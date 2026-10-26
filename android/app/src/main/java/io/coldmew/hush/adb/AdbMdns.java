package io.coldmew.hush.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Finds this phone's own Wireless debugging ports. adbd announces them over mDNS: the pairing port
// while the "Pair device with pairing code" dialog is open, the connect port while Wireless
// debugging is on. Only services that resolve to one of this phone's own addresses count (other
// phones on the same Wi-Fi announce theirs too).
public final class AdbMdns {
    public static final String TLS_CONNECT = "_adb-tls-connect._tcp";
    public static final String TLS_PAIRING = "_adb-tls-pairing._tcp";

    public interface Callback {
        // A port was found, or -1 when the service went away.
        void onPort(int port);
    }

    private final NsdManager nsd;
    private final String type;
    private final Callback callback;
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private boolean running, resolving;
    private String found;   // service name of the port we reported

    public AdbMdns(Context c, String type, Callback callback) {
        this.nsd = c.getSystemService(NsdManager.class);
        this.type = type;
        this.callback = callback;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            running = false;
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        pending.clear();
        try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) {}
    }

    // Waits up to timeoutMs for the port of this phone's service of the given type; -1 if none.
    public static int find(Context c, String type, long timeoutMs) {
        AtomicInteger port = new AtomicInteger(-1);
        CountDownLatch done = new CountDownLatch(1);
        AdbMdns m = new AdbMdns(c, type, p -> {
            if (p > 0 && port.compareAndSet(-1, p)) done.countDown();
        });
        m.start();
        try { done.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        m.stop();
        return port.get();
    }

    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String t) {}
        @Override public void onDiscoveryStopped(String t) {}
        @Override public void onStartDiscoveryFailed(String t, int error) { synchronized (AdbMdns.this) { running = false; } }
        @Override public void onStopDiscoveryFailed(String t, int error) {}
        @Override public void onServiceFound(NsdServiceInfo info) {
            synchronized (AdbMdns.this) {
                if (!running) return;
                pending.add(info);
                resolveNext();
            }
        }
        @Override public void onServiceLost(NsdServiceInfo info) {
            boolean lost;
            synchronized (AdbMdns.this) { lost = info.getServiceName().equals(found); if (lost) found = null; }
            if (lost) callback.onPort(-1);
        }
    };

    // NsdManager resolves one service at a time on older Android versions.
    @SuppressWarnings("deprecation")
    private synchronized void resolveNext() {
        if (resolving || pending.isEmpty() || !running) return;
        resolving = true;
        NsdServiceInfo next = pending.poll();
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int error) { done(); }
                @Override public void onServiceResolved(NsdServiceInfo info) {
                    if (isOwn(info)) {
                        if (!inUse(info.getPort())) {
                            // A cached record of an earlier port (Wireless debugging was just turned
                            // on again): look again shortly, until the current one is announced.
                            retry.postDelayed(() -> {
                                synchronized (AdbMdns.this) { if (running) { pending.add(next); resolveNext(); } }
                            }, 500);
                            done();
                            return;
                        }
                        synchronized (AdbMdns.this) { if (!running) { done(); return; } found = info.getServiceName(); }
                        callback.onPort(info.getPort());
                    }
                    done();
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
            resolveNext();
        }
    }

    private synchronized void done() {
        resolving = false;
        resolveNext();
    }

    private final android.os.Handler retry = new android.os.Handler(android.os.Looper.getMainLooper());

    // Something (adbd) listens on this local port: binding to it fails. Unlike a test connection,
    // this does not touch adbd.
    static boolean inUse(int port) {
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.bind(new java.net.InetSocketAddress("127.0.0.1", port), 1);
            return false;
        } catch (java.io.IOException e) {
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isOwn(NsdServiceInfo info) {
        List<InetAddress> hosts = Build.VERSION.SDK_INT >= 34 ? info.getHostAddresses()
                : info.getHost() == null ? Collections.emptyList() : Collections.singletonList(info.getHost());
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    for (InetAddress h : hosts)
                        if (a.equals(h)) return true;
        } catch (Exception ignored) {}
        return false;
    }
}
