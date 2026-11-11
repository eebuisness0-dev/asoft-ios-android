package com.asoft.ios;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The app's own end, noticed at its next start (CrashReport's "app" kind). Before 0.1.5 a phone that closed the
 * app (Android's memory killer while iOS ran or a setup built, a crash) left nothing behind: users could only
 * say "it crashes". Three sources:
 *   a marker (files/run/going-on.txt) that says what was going on: a guest, a setup (and its step), an import,
 *     the helper VM job's folder. A clean end clears its part; one still there at the next start was left by a
 *     process that died while it went on;
 *   an uncaught-exception handler that writes the Java stack trace into a record before the process dies, and
 *     then hands the exception on to Android's own handler, which ends the app as before;
 *   on Android 11 and later, ActivityManager.getHistoricalProcessExitReasons: why the process ended.
 * A force stop, a swipe from Recents, an app update, a permission the user took back or the phone's own restart is
 * not a crash: no record, no notice (CrashText.exitWeight, check()). An idle app that Android closes in the background is how Android works: then
 * only a crash, a native crash or an ANR is recorded. Android 10 keeps no reasons: there a marker left behind is
 * recorded as "closed while …, Android 10 does not tell apps why" (unless the app was updated meanwhile).
 */
final class AppExit {
    private AppExit() {}

    /** What can be going on: the marker's keys. */
    static final String GUEST = "guest", SETUP = "setup", IMPORT = "import";
    private static final List<String> WHATS = Arrays.asList(SETUP, IMPORT, GUEST);
    private static final String MARKER = "going-on.txt", PREFS = "crash";

    /** What goes on in this process, as the marker has it. */
    private static final Properties now = new Properties();

    private static File marker(Context c) {
        return new File(Guest.runDir(c), MARKER);
    }

    private static long appLogLength(Context c) {
        return new File(Guest.runDir(c), "app.txt").length();
    }

    // -- the marker

    /** @what (GUEST, SETUP, IMPORT) begins: @text says it for a record ("the iOS 16 setup was running"). */
    static synchronized void begin(Context c, String what, String text) {
        now.setProperty(what, text);
        now.setProperty(what + ".logAt", String.valueOf(appLogLength(c)));
        now.setProperty(what + ".since", String.valueOf(System.currentTimeMillis()));
        now.remove(what + ".detail");
        save(c);
    }

    /** Where @what is now ("Build the iOS disk"), when it is going on. */
    static synchronized void detail(Context c, String what, String detail) {
        if (now.containsKey(what) && !detail.equals(now.getProperty(what + ".detail"))) {
            now.setProperty(what + ".detail", detail);
            save(c);
        }
    }

    /** @what has ended cleanly (or the app saw it end). */
    static synchronized void end(Context c, String what) {
        if (c == null || !now.containsKey(what)) {
            return;
        }
        for (String k : new String[] {what, what + ".logAt", what + ".since", what + ".detail"}) {
            now.remove(k);
        }
        save(c);
    }

    /** The helper VM job running in @dir (null: none now), whose logs a record of the app's end reads. */
    static synchronized void helper(Context c, File dir) {
        if (dir == null) {
            if (now.remove("helper") != null) {
                save(c);
            }
            return;
        }
        now.setProperty("helper", dir.getPath());
        save(c);
    }

    private static long versionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return -1;
        }
    }

    /** The marker as @now says, gone when nothing goes on. Written whole and renamed: a death half way leaves the old one. */
    private static void save(Context c) {
        File f = marker(c);
        boolean any = false;
        for (String w : WHATS) {
            any |= now.containsKey(w);
        }
        if (!any) {
            f.delete();
            return;
        }
        Properties p = new Properties();
        p.putAll(now);
        p.setProperty("pid", String.valueOf(android.os.Process.myPid()));
        p.setProperty("version", String.valueOf(versionCode(c)));
        p.setProperty("saved", String.valueOf(System.currentTimeMillis()));
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            p.store(o, "what the app was doing (AppExit)");
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(f);
    }

    private static Properties load(File f) {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return p;
    }

    /** What @p says was going on, the setup first: "the iOS 16 setup was running (Build the iOS disk)"; null: nothing. */
    private static String activity(Properties p) {
        for (String w : WHATS) {
            String t = p.getProperty(w);
            if (t != null) {
                String d = p.getProperty(w + ".detail");
                return d == null ? t : t + " (" + d + ")";
            }
        }
        return null;
    }

    /** Where the log of what was going on starts in the app's log, -1 when the marker does not say. */
    private static long logAt(Properties p) {
        for (String w : WHATS) {
            if (p.getProperty(w) != null) {
                return number(p.getProperty(w + ".logAt"), -1);
            }
        }
        return -1;
    }

    private static long since(Properties p) {
        for (String w : WHATS) {
            if (p.getProperty(w) != null) {
                return number(p.getProperty(w + ".since"), -1);
            }
        }
        return -1;
    }

    private static long number(String s, long otherwise) {
        try {
            return s == null ? otherwise : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // -- the start

    /**
     * The main process starts (AsoftApp): the handler goes in, the marker a process before left is read (and
     * removed, before anything of this process writes its own), and its end is looked into off the main thread.
     */
    static void onStart(Application a) {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                crashed(a, t, e);
            } catch (Throwable ignored) {
                // Android's handler below must run whatever happens here
            }
            if (before != null) {
                before.uncaughtException(t, e);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
        File f = marker(a);
        Properties old = f.isFile() ? load(f) : null;
        f.delete();
        if (old != null && String.valueOf(android.os.Process.myPid()).equals(old.getProperty("pid"))) {
            old = null;
        }
        // the app's log up to here is the process before's: a record of its end reads up to this point
        long logEnd = appLogLength(a);
        Properties left = old;
        Thread t = new Thread(() -> {
            try {
                check(a, left, logEnd);
                testRecords(a);
            } catch (RuntimeException e) {
                AppLog.note(a, "app exit check: " + e);
            }
        }, "app-exit-check");
        t.setDaemon(true);
        t.start();
    }

    // -- a Java crash

    private static volatile boolean crashing;

    /** The uncaught exception @e on @t: its record, before the process goes. Quick: no QEMU logs read here. */
    private static void crashed(Context c, Thread t, Throwable e) {
        if (crashing) {
            return;
        }
        crashing = true;
        String activity;
        long logAt;
        synchronized (AppExit.class) {
            activity = activity(now);
            logAt = logAt(now);
        }
        String msg = e.getMessage();
        String what = "The app crashed" + (activity == null ? "" : " while " + activity) + ": " + e.getClass().getSimpleName()
                + (msg == null ? "" : ": " + msg);
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("os", "0");
        h.put("guest", "The app");
        h.put("what", what.length() > 300 ? what.substring(0, 299) + "…" : what);
        h.put("cause", String.valueOf(e));
        h.put("crash", "yes");
        h.put("reason", CrashText.exitName(CrashText.EXIT_CRASH) + ", seen by the app's uncaught-exception handler");
        h.put("thread", t.getName());
        h.put("while", activity == null ? "nothing the app was running" : activity);
        h.put("app", CrashReport.appVersion(c));
        h.put("phone", CrashReport.phone());
        h.put("memory", CrashReport.memInfo());
        LinkedHashMap<String, List<String>> s = new LinkedHashMap<>();
        s.put("java stack trace", SetupRecord.stack(e));
        s.put(CrashText.RUN_APPLOG, CrashReport.appLog(c, logAt, -1, 150));
        String id = CrashReport.write(c, CrashReport.APP, "crash-" + android.os.Process.myPid(), System.currentTimeMillis(), h, s,
                "last-app-exit.txt", true);
        // its exit reason (CRASH) at the next start is this record: no second one
        prefs(c).edit().putInt("handled.pid", android.os.Process.myPid()).commit();
        AppLog.note(c, "app crash: " + e + (id == null ? "" : " (record " + id + ")"));
    }

    // -- the end of the process before

    /** ApplicationExitInfo's facts (Android 11 and later). */
    private static final class Exit {
        int pid, reason, status, importance;
        long time, pssKb, rssKb;
        String description;
        List<String> trace = new ArrayList<>();
    }

    /** The main process's ends Android kept, newest first; none before Android 11. */
    private static List<Exit> exits(Context c) {
        List<Exit> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 30) {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            List<android.app.ApplicationExitInfo> l;
            try {
                l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 16);
            } catch (RuntimeException e) {
                return out;
            }
            for (android.app.ApplicationExitInfo i : l) {
                if (!c.getPackageName().equals(i.getProcessName())) {
                    continue;       // the GPU check's own process (GpuCheckService) says its end itself
                }
                Exit x = new Exit();
                x.pid = i.getPid();
                x.reason = i.getReason();
                x.status = i.getStatus();
                x.importance = i.getImportance();
                x.time = i.getTimestamp();
                x.pssKb = i.getPss();
                x.rssKb = i.getRss();
                x.description = i.getDescription();
                if (x.reason == CrashText.EXIT_ANR) {
                    // the threads' stacks Android took: the main thread comes first
                    try (InputStream in = i.getTraceInputStream()) {
                        if (in != null) {
                            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                            String line;
                            while ((line = r.readLine()) != null && x.trace.size() < 400) {
                                x.trace.add(line);
                            }
                        }
                    } catch (IOException | RuntimeException e) {
                        x.trace.add("(the trace could not be read: " + e.getMessage() + ")");
                    }
                }
                out.add(x);
            }
        }
        return out;
    }

    /**
     * Looks into the end of the process before: @old its marker (null when it ended with nothing going on),
     * @logEnd where its part of the app's log ends.
     */
    private static void check(Context c, Properties old, long logEnd) {
        SharedPreferences p = prefs(c);
        int handled = p.getInt("handled.pid", -1);
        long seen = p.getLong("exits.seen", -1), newest = Math.max(0, seen);
        p.edit().remove("handled.pid").apply();
        int oldPid = old == null ? -1 : (int) number(old.getProperty("pid"), -1);
        List<Exit> exits = exits(c);
        Exit forMarker = null;
        for (Exit x : exits) {
            if (x.pid == oldPid && forMarker == null) {
                forMarker = x;
            }
            newest = Math.max(newest, x.time);
        }
        String activity = old == null ? null : activity(old);
        if (activity != null && oldPid != handled) {
            boolean updated = !String.valueOf(versionCode(c)).equals(old.getProperty("version"));
            // a marker from before the phone's last start: it was switched off or restarted (or its battery ran
            // out) while something went on, which is not the app's end unless Android kept one for that process
            long bootMs = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
            boolean restarted = number(old.getProperty("saved"), Long.MAX_VALUE) < bootMs;
            if (updated) {
                AppLog.note(c, "app exit: the app was updated while " + activity + ": no record");
            } else if (restarted && (forMarker == null || Build.VERSION.SDK_INT < 30)) {
                AppLog.note(c, "app exit: the phone restarted while " + activity + ": no record");
            } else if (Build.VERSION.SDK_INT < 30) {
                record(c, old, null, CrashText.EXIT_NOT_KEPT, logEnd);
            } else if (forMarker == null) {
                record(c, old, null, CrashText.EXIT_UNKNOWN, logEnd);
            } else if (CrashText.exitWeight(forMarker.reason, forMarker.description) >= 0) {
                record(c, old, forMarker, forMarker.reason, logEnd);
            } else {
                AppLog.note(c, "app exit: " + CrashText.exitName(forMarker.reason) + " (" + forMarker.description + ") while "
                        + activity + ": the user's, no record");
            }
        }
        // crashes and ANRs with nothing going on, since the last look (none the first time: older ones are another version's)
        if (seen >= 0) {
            for (Exit x : exits) {
                if (x.time > seen && x.pid != oldPid && x.pid != handled && CrashText.exitWeight(x.reason, x.description) == 1) {
                    record(c, null, x, x.reason, logEnd);
                }
            }
        }
        p.edit().putLong("exits.seen", newest).apply();
    }

    private static String date(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date(ms));
    }

    /** The record of the process before's end: @x Android's facts (null: none), @reason its reason (or EXIT_NOT_KEPT). */
    private static void record(Context c, Properties old, Exit x, int reason, long logEnd) {
        int status = x == null ? 0 : x.status;
        String desc = x == null ? null : x.description;
        String activity = old == null ? null : activity(old);
        String summary = CrashText.appExitSummary(reason, status, desc, activity);
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("os", "0");
        h.put("guest", "The app");
        h.put("what", summary);
        h.put("cause", CrashText.exitWords(reason, status, desc) + (desc == null || desc.isEmpty() ? "" : "; Android says: " + desc));
        h.put("crash", "yes");
        h.put("reason", CrashText.exitName(reason) + (x == null ? "" : ", status " + status + ", importance " + x.importance));
        h.put("ended", x == null ? "before " + date(System.currentTimeMillis()) : date(x.time));
        if (x != null) {
            h.put("memory at the end", "PSS " + x.pssKb / 1024 + " MB, RSS " + x.rssKb / 1024 + " MB (the app's own process; QEMU is apart)");
        }
        h.put("while", activity == null ? "nothing the app was running" : activity);
        long since = old == null ? -1 : since(old);
        if (since > 0 && x != null) {
            h.put("it had run", CrashReport.duration(x.time - since));
        }
        h.put("app", CrashReport.appVersion(c));
        h.put("phone", CrashReport.phone());
        h.put("memory now", CrashReport.memInfo());
        h.put("free space", CrashReport.freeSpace(c));
        LinkedHashMap<String, List<String>> s = new LinkedHashMap<>();
        if (x != null && !x.trace.isEmpty()) {
            s.put("ANR trace", x.trace);
        }
        String helper = old == null ? null : old.getProperty("helper");
        if (helper != null) {
            File d = new File(helper);
            s.put("the helper VM's console (serial.txt)", CrashReport.tail(new File(d, "serial.txt"), 150));
            s.put("the helper VM's QEMU stderr", CrashReport.stderrEvidence(new File(d, "qemu-stderr.txt")));
        }
        if (old != null && old.getProperty(GUEST) != null) {
            File run = Guest.runDir(c);
            s.put("the guest's QEMU stderr", CrashReport.stderrEvidence(new File(run, "qemu-stderr.txt")));
            s.put("the guest's console (serial.txt)", CrashReport.tail(new File(run, "serial.txt"), 100));
        }
        long logAt = old == null ? -1 : logAt(old);
        s.put("app log of that run", CrashReport.appLog(c, logAt, logEnd, 150));
        String id = CrashReport.write(c, CrashReport.APP, "exit-" + (x == null ? oldPidOf(old) : x.pid),
                x == null ? System.currentTimeMillis() : x.time, h, s, "last-app-exit.txt", true);
        AppLog.note(c, "app exit: " + summary + (id == null ? "" : " (record " + id + ")"));
    }

    private static String oldPidOf(Properties old) {
        return old == null ? "0" : old.getProperty("pid", "0");
    }

    // -- debug builds: test records

    /**
     * Debug builds: while files/logs-test is there at a start, one made-up setup failure and one made-up "app was
     * closed" record, to see the notices, Logs, Share and the GitHub link without breaking anything. The file goes
     * first (a crash in here must not repeat at every start). The records say "test: yes" and "[test]".
     */
    static void testRecords(Context c) {
        if ((c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return;
        }
        File flag = new File(c.getFilesDir(), "logs-test");
        if (!flag.exists() || !flag.delete()) {
            return;
        }
        long t = System.currentTimeMillis();
        String during = "unpacking the iOS images (7137 MB)";
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("os", String.valueOf(Guest.IOS16));
        h.put("guest", "iOS 16");
        h.put("what", "iOS 16 setup stopped at Build the iOS disk");
        h.put("cause", "building the iOS disk failed: " + CrashText.helperFailure(-1, 9, null, during, ""));
        h.put("crash", "yes");
        h.put("test", "yes");
        h.put("step", "disk (Build the iOS disk), 2 %: " + during);
        h.put("helper 1", "build-root job, native EL0, 4096 MiB: " + CrashText.helperFailure(-1, 9, null, during, "")
                + " after 2 min 10 s; QEMU signal 9 (SIGKILL); lowest MemAvailable 180 MB; QEMU's RSS up to 3900 MB");
        h.put("app", CrashReport.appVersion(c));
        h.put("phone", CrashReport.phone());
        h.put("memory", CrashReport.memInfo());
        h.put("free space", CrashReport.freeSpace(c));
        h.put("thermal", CrashReport.thermal(c));
        LinkedHashMap<String, List<String>> s = new LinkedHashMap<>();
        s.put("helper run 1 (native EL0, 4096 MiB): console (serial.txt)", Arrays.asList(
                "ASOFT: helper up, job build-root, 8 CPUs, 4012345 kB", "ASOFT: progress 1 reading the IPSW",
                "ASOFT: progress 2 " + during));
        s.put("helper run 1 (native EL0, 4096 MiB): QEMU stderr", Arrays.asList("--- the last 1 lines ---",
                "native-el0: 8 stub processes (a made-up line of the logs test)"));
        s.put(CrashText.RUN_APPLOG, CrashReport.appLog(c, -1, -1, 20));
        CrashReport.write(c, CrashReport.SETUP, "setup", t - 1000, h, s, null, true);

        LinkedHashMap<String, String> a = new LinkedHashMap<>();
        a.put("os", "0");
        a.put("guest", "The app");
        a.put("what", CrashText.appExitSummary(CrashText.EXIT_LOW_MEMORY, 0, null,
                "the iOS 16 setup was running (Build the iOS disk)"));
        a.put("cause", CrashText.exitWords(CrashText.EXIT_LOW_MEMORY, 0, null));
        a.put("crash", "yes");
        a.put("test", "yes");
        a.put("reason", CrashText.exitName(CrashText.EXIT_LOW_MEMORY) + ", status 0, importance 125");
        a.put("while", "the iOS 16 setup was running (Build the iOS disk)");
        a.put("app", CrashReport.appVersion(c));
        a.put("phone", CrashReport.phone());
        a.put("memory now", CrashReport.memInfo());
        LinkedHashMap<String, List<String>> as = new LinkedHashMap<>();
        as.put("app log of that run", CrashReport.appLog(c, -1, -1, 20));
        CrashReport.write(c, CrashReport.APP, "exit-test", t, a, as, null, true);
        AppLog.note(c, "logs-test: two test records written");
    }
}
