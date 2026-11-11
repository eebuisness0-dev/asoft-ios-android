package com.asoft.ios;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Installs iOS 16 from Apple in the foreground (PhoneSetup), with a notification that says how far it
 * is and Pause / Cancel on it. Its state is static, as Guest's is: the setup screen reads it twice a
 * second. Pause keeps everything done so far: the download stops at once (finished chunks stay), a
 * helper VM step finishes first (it cannot be resumed half way). Cancel deletes what was made.
 */
public class SetupService extends Service {
    private static final String CHANNEL = "setup";
    private static final int NOTE = 3;

    /** What the screens show; written by the setup thread. */
    static final class State {
        volatile String step = "";              // PhoneSetup.STEPS, "done" at the end, "" before a run
        volatile int pct = -1;
        volatile String text = "";
        volatile String error;                  // why it stopped, in plain words; null while it runs or when done
        volatile String record;                 // the record of that stop (SetupRecord), for the screens' Share log; null without one
        volatile boolean running, paused, waitingForWifi, waitingForNetwork, finished, held;
        volatile String target = "ios16";       // "ios16" (PhoneSetup) or "a26" (PhoneSetup26: iOS 26 and/or watchOS)
        volatile List<IpswFetch.Item> items = new ArrayList<>();
        volatile long received;
        volatile int connections;
        volatile String servers = "";
        volatile double mbps;                   // smoothed over the last seconds
        final List<String> log = new ArrayList<>();
    }

    static final State state = new State();
    private static volatile boolean cancel, pause;
    private static volatile Thread worker;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback netCallback;
    private volatile boolean unmetered = true, online = true;

    static boolean wifiOnly(Context c) {
        return c.getSharedPreferences("setup", MODE_PRIVATE).getBoolean("wifi-only", true);
    }

    static void setWifiOnly(Context c, boolean on) {
        c.getSharedPreferences("setup", MODE_PRIVATE).edit().putBoolean("wifi-only", on).apply();
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, SetupService.class).setAction("start").putExtra("what", "ios16"));
    }

    /** iOS 26 and/or watchOS (PhoneSetup26.setWants says which), from Apple. */
    static void startA26(Context c) {
        c.startForegroundService(new Intent(c, SetupService.class).setAction("start").putExtra("what", "a26"));
    }

    private static String title(boolean done) {
        if (!"a26".equals(state.target)) {
            return done ? "iOS 16 is set up" : "Installing iOS 16";
        }
        return done ? "iOS 26 / watchOS are set up" : "Installing iOS 26 / watchOS";
    }

    /** The step can pause: a download, a decrypt (both go on where they were), a helper VM job (held). */
    static boolean pausable(String step) {
        return "ipsw".equals(step) || step.isEmpty() || step.endsWith(":fetch") || step.endsWith(":decrypt")
                || step.contains(":") && !(step.endsWith(":firstboot") || step.endsWith(":wallpaper") || step.endsWith(":pack")
                        || step.endsWith(":unpack"));
    }

    static void pause(Context c) {
        c.startService(new Intent(c, SetupService.class).setAction("pause"));
    }

    static void cancel(Context c) {
        c.startService(new Intent(c, SetupService.class).setAction("cancel"));
    }

    static boolean busy() {
        return worker != null;
    }

    /**
     * A system that a finished run of @target ("ios16" or "a26") set up was removed (Installer.remove): the setup
     * screens stop saying it is set up, and so does the notification the run left.
     */
    static void forget(Context c, String target) {
        if (worker != null || !target.equals(state.target) || !state.finished) {
            return;
        }
        state.finished = false;
        state.step = "";
        state.pct = -1;
        state.text = "";
        state.items = new ArrayList<>();
        c.getSystemService(NotificationManager.class).cancel(NOTE);
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private Notification note(String text, int pct) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SetupActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title(state.finished))
                .setContentText(text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(worker != null);
        if (worker != null) {
            if (pct >= 0) {
                n.setProgress(100, pct, false);
            } else {
                n.setProgress(0, 0, true);
            }
            if (state.paused || pause || pausable(state.step)) {
                n.addAction(new Notification.Action.Builder(null, state.paused || pause ? "Resume" : "Pause",
                        PendingIntent.getService(this, 1, new Intent(this, SetupService.class)
                                .setAction(state.paused || pause ? "start" : "pause"), PendingIntent.FLAG_IMMUTABLE)).build());
            }
            n.addAction(new Notification.Action.Builder(null, "Cancel",
                    PendingIntent.getService(this, 2, new Intent(this, SetupService.class).setAction("cancel"),
                            PendingIntent.FLAG_IMMUTABLE)).build());
        }
        return n.build();
    }

    private void post(String text, int pct) {
        getSystemService(NotificationManager.class).notify(NOTE, note(text, pct));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null || intent.getAction() == null ? "start" : intent.getAction();
        startForeground(NOTE, note(state.text.isEmpty() ? "Starting" : state.text, state.pct),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        switch (a) {
            case "pause":
                if (pausable(state.step) || worker == null) {
                    pause = true;
                    post("Pausing: " + state.text, state.pct);
                }
                break;
            case "cancel":
                cancel = true;
                pause = false;
                if (worker == null) {
                    new Thread(() -> {
                        if ("a26".equals(state.target)) {
                            cancelA26(this);
                        } else {
                            Installer.deleteTree(PhoneSetup.work(this));
                        }
                        state.step = "";
                        state.items = new ArrayList<>();
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf();
                    }, "setup-cancel").start();
                }
                break;
            default:
                pause = false;
                if (worker == null) {
                    String what = intent == null ? null : intent.getStringExtra("what");
                    if (what != null) {
                        state.target = what;
                    }
                    begin();
                }
                break;
        }
        return START_NOT_STICKY;
    }

    private void watchNetwork() {
        if (netCallback != null) {
            return;
        }
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                online = true;
            }

            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                online = true;
                unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            }

            @Override
            public void onLost(Network n) {
                online = false;
                unmetered = false;
            }
        };
        cm.registerDefaultNetworkCallback(netCallback);
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        online = caps != null;
        unmetered = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    /** Still downloading: nothing after the download needs the network. */
    private boolean downloadLeft() {
        if ("a26".equals(state.target)) {
            // from the markers: at a resume the step is still empty, and a run past its downloads waited for a
            // network it did not need (2026-11-09: the iOS 26 wallpaper boot sat 30 min on "No internet connection")
            return PhoneSetup26.downloadLeft(this);
        }
        return !PhoneSetup.done(this, "ipsw") && !PhoneSetup.done(this, "disk");
    }

    /** The download waits for Wi-Fi (or any unmetered network) when the user asked for it. */
    private boolean noWifi() {
        return wifiOnly(this) && online && !unmetered && downloadLeft();
    }

    /** No network at all: the download waits for one and goes on by itself (a lost signal, flight mode). */
    private boolean noNetwork() {
        return !online && downloadLeft();
    }

    /** When the step that runs now started: its failed helper runs since then go into its record (SetupRecord). */
    private volatile long stepSince;

    /** "the iOS 16 setup", "the iOS 26 and watchOS setup": what runs, for the app's own end (AppExit). */
    private String setupName(boolean a26) {
        if (!a26) {
            return "the iOS 16 setup";
        }
        boolean i26 = PhoneSetup26.wants(this, PhoneSetup26.IOS26), w = PhoneSetup26.wants(this, PhoneSetup26.WATCH);
        return "the " + (i26 && w ? "iOS 26 and watchOS" : w ? "watchOS" : "iOS 26") + " setup";
    }

    private void begin() {
        if (Guest.running()) {
            state.error = "Close iOS first (side bar > Saves > Save and close): the setup needs its memory.";
            state.record = null;
            post(state.error, -1);
            stopSelf();
            return;
        }
        cancel = false;
        state.error = null;
        state.record = null;
        Helper.memoryNote = null;
        state.finished = false;
        state.running = true;
        state.paused = false;
        watchNetwork();
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "asoft:setup");
            wakeLock.acquire();
        }
        IpswFetch[] current = new IpswFetch[1];
        boolean a26 = "a26".equals(state.target);
        // a helper VM job is held (SIGSTOP) while paused, too hot or low on battery off the charger, and goes on after
        Helper.hold = () -> {
            boolean h = pause || PhoneSetup26.shouldHold(this);
            state.held = h;
            return h;
        };
        worker = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            // where this run's lines start in the app's log, for a record of its end
            long logAt = new File(Guest.runDir(this), "app.txt").length();
            stepSince = t0;
            AppExit.begin(this, AppExit.SETUP, setupName(a26) + " was running");
            AppLog.note(this, "setup: start (" + getFilesDir().getUsableSpace() / 1_000_000_000L + " GB free)");
            try {
                while (!cancel) {
                    // paused, or waiting for Wi-Fi: what is done stays done, the run goes on from it
                    while (!cancel && (pause || noWifi() || noNetwork())) {
                        state.paused = true;
                        state.waitingForWifi = !pause && noWifi();
                        state.waitingForNetwork = !pause && noNetwork();
                        Thread.sleep(1000);
                    }
                    state.paused = false;
                    state.waitingForWifi = false;
                    state.waitingForNetwork = false;
                    if (cancel) {
                        break;
                    }
                    try {
                        long free = getFilesDir().getUsableSpace();
                        long need = a26 ? PhoneSetup26.needBytes(this) : PhoneSetup.needBytes(this);
                        if (free < need) {
                            throw new IOException(String.format(Locale.ROOT,
                                    "Not enough room: %.1f GB free, about %.1f GB needed. Free some space, then tap Resume.", free / 1e9, need / 1e9));
                        }
                        // the helper VM gets every core, as Build-iOSDisk.ps1 gives it up to 8 (measured with 8)
                        if (a26) {
                            PhoneSetup26.run(this, Math.min(8, Runtime.getRuntime().availableProcessors()), progress(), this::stopNow, current);
                        } else {
                            PhoneSetup.run(this, Math.min(8, Runtime.getRuntime().availableProcessors()), progress(), this::stopNow, current);
                        }
                        state.finished = true;
                        state.step = "done";
                        state.text = title(true);
                        AppLog.note(this, "setup: done in " + (System.currentTimeMillis() - t0) / 1000 + " s");
                        break;
                    } catch (IOException e) {
                        if (cancel) {
                            break;
                        }
                        if (pause || noWifi() || noNetwork()) {
                            continue;       // the loop above waits; the next run goes on where this one stopped
                        }
                        state.error = plain(e);
                        AppLog.note(this, "setup stopped: " + e);
                        state.record = SetupRecord.write(this, a26, state.step, state.pct, state.text, e, state.error, logAt, stepSince, t0);
                        break;
                    } catch (RuntimeException e) {
                        // a bug of the app's: the setup stops with it, the app stays (a Resume tries again)
                        state.error = "The setup stopped on an error of the app's (" + e + "). Tap Resume to try again; please share the log.";
                        AppLog.note(this, "setup stopped: " + e);
                        state.record = SetupRecord.write(this, a26, state.step, state.pct, state.text, e, state.error, logAt, stepSince, t0);
                        break;
                    }
                }
                if (cancel) {
                    if (a26) {
                        cancelA26(this);
                    } else {
                        Installer.deleteTree(PhoneSetup.work(this));
                    }
                    state.step = "";
                    state.items = new ArrayList<>();
                    state.text = "Cancelled";
                    AppLog.note(this, "setup: cancelled");
                }
            } catch (InterruptedException e) {
                state.error = "Stopped";
            } finally {
                state.running = false;
                state.paused = false;
                state.held = false;
                Helper.hold = null;
                Helper.memoryNote = null;
                AppExit.end(this, AppExit.SETUP);
                worker = null;
                if (state.finished) {
                    post(a26 ? "Set up: open ASoftware iOS and choose iOS 26 or watchOS" : "iOS 16 is set up: open ASoftware iOS and tap Start iOS 16", 100);
                    stopForeground(STOP_FOREGROUND_DETACH);
                } else if (state.error != null) {
                    post("Stopped: " + state.error, -1);
                    stopForeground(STOP_FOREGROUND_DETACH);
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                }
                stopSelf();
            }
        }, "setup");
        worker.start();
    }

    /**
     * The fetcher and the helper ask this between pieces of work. A download stops for Pause or for
     * want of Wi-Fi; a helper VM step only for Cancel (it would have to start over).
     */
    private boolean stopNow() {
        if (cancel) {
            return true;
        }
        boolean downloading = "ipsw".equals(state.step) || state.step.endsWith(":fetch");
        // a decrypt stops at its next cluster for Pause (it goes on from there); a helper job is held instead
        if (state.step.endsWith(":decrypt") && pause) {
            return true;
        }
        return downloading && (pause || noWifi() || noNetwork());
    }

    private PhoneSetup.Progress progress() {
        return new PhoneSetup.Progress() {
            long lastPost, lastBytes, lastMs;

            String loggedStep = "";
            int loggedPct = -100;
            /** The step of the last call, null before the first. */
            String stepWas;

            @Override
            public void step(String step, int pct, String text) {
                // the app's log keeps where the run is: each step's start, then every 10 % (adb reads it)
                if (!step.equals(stepWas)) {
                    // (the run's first step counts from the run's start: begin() set that)
                    if (stepWas != null) {
                        stepSince = System.currentTimeMillis();
                    }
                    stepWas = step;
                    AppExit.detail(SetupService.this, AppExit.SETUP, SetupRecord.label("a26".equals(state.target), step));
                }
                if (!step.equals(loggedStep) || (pct >= 0 && pct >= loggedPct + 10)) {
                    loggedStep = step;
                    loggedPct = pct;
                    AppLog.note(SetupService.this, "setup: " + step + (pct >= 0 ? " " + pct + "%" : "") + ": " + text
                            + ("ipsw".equals(step) || step.endsWith(":fetch") ? String.format(Locale.ROOT, " (%.1f MB/s, %d connections)", state.mbps, state.connections) : ""));
                }
                state.step = step;
                state.pct = pct;
                state.text = text;
                long now = System.currentTimeMillis();
                if (now - lastPost > 2000) {
                    lastPost = now;
                    post(text + (pct >= 0 ? " (" + pct + "%)" : ""), pct);
                }
            }

            @Override
            public void fetch(List<IpswFetch.Item> items, IpswFetch f) {
                state.items = items;
                state.received = f.received.get();
                state.connections = f.active.get();
                CdnEdges cdn = f.cdn();
                state.servers = cdn == null ? "" : cdn.summary();
                long now = System.currentTimeMillis();
                if (now - lastMs >= 2000) {
                    double s = (now - lastMs) / 1e3;
                    double rate = lastMs == 0 ? 0 : (state.received - lastBytes) / 1e6 / s;
                    state.mbps = state.mbps <= 0 ? rate : 0.7 * state.mbps + 0.3 * rate;
                    lastMs = now;
                    lastBytes = state.received;
                }
                long done = 0, total = 0;
                for (IpswFetch.Item it : items) {
                    done += it.done.get();
                    total += it.total;
                }
                step("a26".equals(state.target) ? state.step : "ipsw", total > 0 ? (int) (done * 100 / total) : -1,
                        String.format(Locale.ROOT, "Downloading from Apple: %.1f of %.1f GB", done / 1e9, total / 1e9));
            }

            int logged;

            @Override
            public void log(String line) {
                // the lines that say how a run went into the app's log too: where a download resumed, which
                // servers answered; retries only a few (a slow evening makes hundreds)
                boolean keep = line.contains("chunks to fetch") || line.startsWith("CDN servers") || line.startsWith("IPSW dropped")
                        || (line.contains("retry") && logged++ < 20);
                if (keep) {
                    AppLog.note(SetupService.this, "setup: " + line);
                }
                synchronized (state.log) {
                    state.log.add(line);
                    if (state.log.size() > 200) {
                        state.log.remove(0);
                    }
                }
            }
        };
    }

    /**
     * Cancel of iOS 26 / watchOS: what was downloaded and built for them goes; the shared part stays when an OS
     * already rests on it (it is small, and the other OS would need it again), and an installed OS stays.
     */
    static void cancelA26(Context c) {
        File w = PhoneSetup26.work(c);
        boolean keepShared = PhoneSetup26.installed(c, PhoneSetup26.IOS26) || PhoneSetup26.installed(c, PhoneSetup26.WATCH);
        if (!keepShared) {
            Installer.deleteTree(w);
            return;
        }
        for (String n : new String[] {"iphone", "os", "install", "dsc", "dscpatch"}) {
            Installer.deleteTree(new File(w, n));
        }
        File[] l = w.listFiles();
        if (l != null) {
            for (File f : l) {
                if (f.getName().endsWith(".qcow2")) {
                    f.delete();
                }
            }
        }
        for (String g : new String[] {PhoneSetup26.IOS26, PhoneSetup26.WATCH}) {
            if (PhoneSetup26.installed(c, g)) {
                continue;
            }
            if (PhoneSetup26.WATCH.equals(g)) {
                Installer.deleteTree(new File(w, "watchos"));
            }
            for (String[] st : PhoneSetup26.steps(g)) {
                new File(w, "phone/" + g + "-" + st[0] + ".done").delete();
            }
        }
    }

    /** An exception as the user should read it. */
    static String plain(IOException e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof java.net.UnknownHostException) {
            return "No internet connection (Apple's server cannot be found). Tap Resume once the phone is online.";
        }
        if (e instanceof java.net.SocketTimeoutException || m.contains("timed out") || m.contains("connection ended")
                || m.contains("closed the connection")) {
            return "Apple's servers stopped answering. Tap Resume: it goes on where it stopped.";
        }
        if (e instanceof IpswFetch.HttpError) {
            int code = ((IpswFetch.HttpError) e).code;
            return code == 404 || code == 410 ? "Apple no longer serves this iOS file." : "Apple's server refused the download (HTTP " + code + ").";
        }
        if (m.contains("ENOSPC") || m.contains("No space left")) {
            return "The phone is full. Free some space, then tap Resume.";
        }
        if (m.contains("damaged in transfer")) {
            return "A file came damaged; that part is downloaded again when you tap Resume.";
        }
        return m;
    }

    @Override
    public void onDestroy() {
        if (netCallback != null) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
            netCallback = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
