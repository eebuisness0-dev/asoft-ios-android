package com.asoft.ios;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/** Keeps iOS's QEMU in the foreground while it runs, whether or not its screen is open. */
public class GuestService extends Service {
    private static final String CHANNEL = "guest";
    private PowerManager.WakeLock wakeLock;
    /** Power saving switched on while iOS runs: say once that iOS slows down now. */
    private android.content.BroadcastReceiver powerChanged;

    private void watchPowerSaving() {
        if (powerChanged != null) {
            return;
        }
        powerChanged = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Guest.running() && PowerCheck.saverOn(c)) {
                    android.widget.Toast.makeText(c, "Power saving is on: iOS runs much slower until you turn it off",
                            android.widget.Toast.LENGTH_LONG).show();
                    note("power saving on while iOS runs" + (PowerCheck.capped() != null ? " (" + PowerCheck.capped() + ")" : ""));
                }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerChanged, f, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(powerChanged, f);
        }
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    /** The ongoing notification; every start command (re)posts it: startForegroundService wants that. */
    private void foreground(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "iOS", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ScreenActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent save = PendingIntent.getService(this, 1,
                new Intent(this, GuestService.class).setAction("save"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("ASoftware iOS")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true);
        if (!Guest.saving()) {
            // Turn off (no save: the next start boots) only in the app, behind its question: one tap here,
            // taken for "close", threw iOS's state away (2026-11-06)
            n.addAction(new Notification.Action.Builder(null, "Save and close", save).build());
        }
        startForeground(2, n.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if ("save".equals(action) || "stop".equals(action)) {
            // "stop" is the notification's old action: it saves too
            foreground("Saving iOS");
            saveThenStop();
            return START_NOT_STICKY;
        }
        if ("poweroff".equals(action)) {    // iOS's own shutdown, no save (Turn off)
            foreground("Turning iOS off");
            new Thread(() -> {
                note("power off: " + Guest.powerOff(this));
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }, "guest-poweroff").start();
            return START_NOT_STICKY;
        }
        if ("crashtest".equals(action)) {   // debug builds: a stand-in QEMU that aborts (Guest.startCrashTest)
            if (Guest.running()) {
                // this service keeps that iOS: stopping it would end iOS
                foreground(Guest.osName(Guest.os(this)) + " is running");
                note("crash test: not while " + Guest.osName(Guest.os(this)) + " runs");
                return START_NOT_STICKY;
            }
            foreground("Crash test");
            new Thread(() -> {
                try {
                    Guest.startCrashTest(this);
                    for (int i = 0; i < 300 && Guest.running(); i++) {
                        Thread.sleep(100);
                    }
                    CrashReport.awaitCapture(15_000);
                } catch (IOException e) {
                    note("crash test: " + e.getMessage());
                } catch (InterruptedException e) {
                    // stop anyway
                }
                if (!Guest.running()) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }, "crash-test").start();
            return START_NOT_STICKY;
        }
        if ("appcrash".equals(action)
                && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // debug builds: a Java crash of the app itself, for its record (AppExit's handler) and the notice after.
            // Not while iOS runs: the app's end would take it along
            if (Guest.running()) {
                foreground(Guest.osName(Guest.os(this)) + " is running");
                note("app crash test: not while " + Guest.osName(Guest.os(this)) + " runs");
                return START_NOT_STICKY;
            }
            note("app crash test: throwing on the main thread");
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                throw new IllegalStateException("app crash test (a debug build's GuestService appcrash)");
            });
            return START_NOT_STICKY;
        }
        if ("off".equals(action)) {     // QEMU stopped at once, no save, no shutdown (adb tests)
            foreground("Turning iOS off");
            Guest.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        // quick boot by default; "cold": a boot; "load": the saved state named in "tag", instead of the running iOS
        boolean load = "load".equals(action);
        String loadTag = load ? intent.getStringExtra("tag")
                : intent != null && intent.getBooleanExtra("cold", false) ? null : Guest.QUICK;
        foreground(Guest.osName(Guest.selected(this)) + " is running");
        watchPowerSaving();
        note("power: saving " + (PowerCheck.saverOn(this) ? "on" : "off") + ", " + PowerCheck.describe());
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "asoft:guest");
            wakeLock.acquire();
        }
        // fork from the app's large process: not on the main thread
        new Thread(() -> {
            try {
                if (load && Guest.running() && !Guest.saving()) {
                    Guest.state = "switching";
                    Guest.stopAndWait();
                } else if (Guest.running() && !Guest.saving()) {
                    return;     // iOS runs already: nothing to start
                }
                // the QEMU before may still be ending (a save and close's last second): it holds the disk
                if (!Guest.waitEnded(30_000)) {
                    note("start: the iOS before is still closing after 30 s");
                }
                Guest.startError = null;
                // iOS 26's one-time disk step for the 2x screen, off Guest's lock (the screens go on drawing)
                String tag = Guest.prepare(this, loadTag);
                if (!load && Guest.running() && !Guest.saving()) {
                    return;     // a second request waited for that step behind the first, which has started iOS
                }
                Guest.start(this, tag);
                MemoryCleaner.start(this);
                if (Guest.resumed && !resumedOk()) {
                    boolean forGood = Guest.loadFailedForGood(this);
                    // a state this QEMU cannot load (another build, other options): boot instead; the
                    // quick-boot state goes, a save the user named stays. One QEMU could not even open
                    // (the disk locked, no memory) says nothing about the state: it stays
                    note("the saved state " + loadTag + " did not load (" + Guest.lastWords(this) + ")"
                            + (forGood ? "; booting" : "; QEMU did not get to it, the state stays; booting"));
                    Guest.stopAndWait();
                    if (forGood && Guest.QUICK.equals(loadTag)) {
                        Guest.dropQuick(this);
                    }
                    Guest.waitEnded(30_000);
                    Guest.start(this, null);
                }
                if (Guest.ios26Running() && Guest.running()) {
                    AutoSave.start(this, Guest.pid(this), Guest.resumed);
                    HintBoost.start(this, Guest.pid(this));
                }
            } catch (IOException e) {
                note("start failed: " + e);
                if (!Guest.running()) {
                    // the screens say why (it was only in the log: "iOS 26 does not start", 2026-11-06)
                    Guest.startError = e.getMessage();
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            android.widget.Toast.makeText(this, Guest.osName(Guest.os(this)) + " did not start: " + e.getMessage(),
                                    android.widget.Toast.LENGTH_LONG).show());
                    stopSelf();
                }
            }
        }, "guest-start").start();
        return START_NOT_STICKY;
    }

    /** After a start from the saved state: true once iOS runs, false if QEMU gave up. */
    private boolean resumedOk() {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 15 * 60_000) {
            if (!Guest.running()) {
                return false;
            }
            Qmp q = Guest.qmp();
            if (q != null) {
                String r = q.call("{\"execute\":\"query-status\"}", 10_000);
                if (r != null && r.contains("\"running\"")) {
                    note("resumed in " + (System.currentTimeMillis() - t0) + " ms");
                    Guest.state = "running";
                    return true;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return true;
            }
        }
        return true;
    }

    private void saveThenStop() {
        new Thread(() -> {
            note("save: " + Guest.saveAndQuit(this));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, "guest-save").start();
    }

    /** Swiped away from Recents: keep iOS as it is for the next start, like closing the window on Windows. */
    @Override
    public void onTaskRemoved(Intent root) {
        if (Guest.running() && !Guest.saving()) {
            saveThenStop();
        }
    }

    @Override
    public void onDestroy() {
        if (powerChanged != null) {
            unregisterReceiver(powerChanged);
            powerChanged = null;
        }
        Guest.stop();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    void note(String s) {
        AppLog.note(this, s);
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, GuestService.class));
    }

    /** Boots iOS even when a saved state exists (the state stays until the next save). */
    static void startCold(Context c) {
        c.startForegroundService(new Intent(c, GuestService.class).putExtra("cold", true));
    }

    /** Replaces the running iOS (what happened since its last save is lost) with the save @tag. */
    static void load(Context c, String tag) {
        c.startForegroundService(new Intent(c, GuestService.class).setAction("load").putExtra("tag", tag));
    }

    /** iOS's own shutdown, no save; the next start boots. */
    static void powerOff(Context c) {
        c.startForegroundService(new Intent(c, GuestService.class).setAction("poweroff"));
    }

    static void save(Context c) {
        c.startForegroundService(new Intent(c, GuestService.class).setAction("save"));
    }
}
