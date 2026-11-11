package com.asoft.ios;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The records the app keeps when something ends that should not have, and the report a user can send from one.
 * Before these, users could only say "it crashes": the cause was gone with the next start. Three kinds, in one
 * place (files/run/records, the newest MAX_KEPT kept, each at most MAX_BYTES; Installer.remove leaves them):
 *   guest  a QEMU that ended by itself (reims gave up on a GPU format, a panic, Android's memory killer): QEMU's
 *          stderr, which says why and which the next start overwrites, with the run's app log (captureAsync).
 *          A notification says so, and the screens offer "Report this crash" until that system starts again.
 *   setup  a setup step or an import that failed (SetupRecord): the helper VM's console and stderr, how it ended.
 *   app    the app's own process that died (AppExit): a Java crash's stack trace, or Android's reason for
 *          closing the app while a guest or a setup ran.
 * The setup and app kinds are "unseen" until the user dismisses their notice (RecordNotice) or opens them in
 * Logs (LogsActivity). The report is a GitHub issue filled in with the record, personal data taken out
 * (CrashText.scrub), which the user reads and submits; Share hands the same scrubbed text to Android's share
 * sheet. Nothing is sent by itself.
 */
final class CrashReport {
    private CrashReport() {}

    static final String REPO = "alpersamur3/asoft-ios-android";
    /**
     * The issue link's length. Signed out, GitHub sends the link to its sign-in page as return_to, encoded once
     * more, and drops it when that gets too long (measured 2026-11-10: kept up to a 7,695-character redirect,
     * dropped at ~7,970), so the user would sign in and land on GitHub's home page. Encoded once more a link
     * grows at most 5/3 (%XX becomes %25XX): 4,500 stays under that. What does not fit waits on the clipboard.
     */
    private static final int MAX_URL = 4500;
    /** Records kept, of every kind together, and the size of one (a QEMU's stderr can run to tens of MB). */
    static final int MAX_KEPT = 10, MAX_BYTES = 200_000;
    static final String GUEST = "guest", SETUP = "setup", APP = "app";
    private static final String PREFS = "crash", CHANNEL = "crash";
    private static final int NOTE_ID = 40;

    /** What a record needs about one QEMU run; Guest fills it right before and after the spawn. */
    static final class Run {
        int pid;
        int os;
        /** a boot of PhoneSetup26's on a disk it is building */
        boolean setup;
        /** "a boot" or "the save <tag>" */
        String start;
        /** wall clock */
        long startedMs;
        /** where this run's lines start in files/run/app.txt */
        long appLogOffset;
        int cpus;
        /** 0: the setup boot's own device tree decides */
        int ramMiB;
        /** "786x1704"; "" for iOS 16 */
        String screen;
        /** the QEMU binary's path */
        String engine;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // -- where the records are

    /** files/run/records; the guest records of 0.1.5's first builds (files/run/last-crash*.txt) move in. */
    static File dir(Context c) {
        File run = Guest.runDir(c), d = new File(run, "records");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        for (String n : new String[] {"last-crash.txt", "last-crash.1.txt", "last-crash.2.txt"}) {
            File f = new File(run, n);
            if (f.isFile()) {
                Record r = parse(f, false);
                if (r == null || !f.renameTo(new File(d, r.id + ".txt"))) {
                    f.delete();
                }
            }
        }
        return d;
    }

    /** The record files, newest first (their names start with their time). */
    static List<File> files(Context c) {
        File[] l = dir(c).listFiles((d, n) -> n.endsWith(".txt"));
        List<File> out = new ArrayList<>(l == null ? Collections.<File>emptyList() : Arrays.asList(l));
        Collections.sort(out, (a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    private static String idOf(File f) {
        String n = f.getName();
        return n.endsWith(".txt") ? n.substring(0, n.length() - 4) : n;
    }

    /** Every record, newest first, with their logs. */
    static List<Record> all(Context c) {
        List<Record> out = new ArrayList<>();
        for (File f : files(c)) {
            Record r = parse(f, true);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** Changes whenever a record comes or goes, for a screen to see that cheaply. */
    static String stamp(Context c) {
        File d = dir(c);
        String[] n = d.list();
        return d.lastModified() + ":" + (n == null ? 0 : n.length);
    }

    // -- writing one

    private static final Object WRITE = new Object();

    /**
     * Writes a record of @kind: its id, kind and time, then @header and @sections, kept within MAX_BYTES
     * (CrashText.recordLines); then only the newest MAX_KEPT stay. @tag makes the id unique within a second (a
     * pid, "setup"). @external: the name of its copy in the external log folder (readable over adb on a release
     * build), null for none. @unseen: its notice shows until the user dismisses it. Returns the id, null when it
     * could not be written.
     */
    static String write(Context c, String kind, String tag, long whenMs, LinkedHashMap<String, String> header,
            LinkedHashMap<String, List<String>> sections, String external, boolean unseen) {
        synchronized (WRITE) {
            File d = dir(c);
            Date when = new Date(whenMs);
            String base = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(when) + "-" + tag, id = base;
            for (int n = 2; new File(d, id + ".txt").exists(); n++) {
                id = base + "-" + n;
            }
            LinkedHashMap<String, String> h = new LinkedHashMap<>();
            h.put("id", id);
            h.put("kind", kind);
            h.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(when));
            for (Map.Entry<String, String> e : header.entrySet()) {
                if (!h.containsKey(e.getKey())) {
                    h.put(e.getKey(), e.getValue());
                }
            }
            File tmp = new File(d, id + ".tmp"), f = new File(d, id + ".txt");
            try {
                Files.write(tmp.toPath(), CrashText.recordLines(h, sections, MAX_BYTES), StandardCharsets.UTF_8);
                if (!tmp.renameTo(f)) {
                    throw new IOException("cannot rename " + tmp);
                }
            } catch (IOException e) {
                tmp.delete();
                AppLog.note(c, kind + " record not written: " + e.getMessage());
                return null;
            }
            if (unseen) {
                Set<String> u = unseen(c);
                u.add(id);
                prefs(c).edit().putStringSet("unseen", u).commit();
            }
            prune(c);
            if (external != null) {
                try {
                    Files.copy(f.toPath(), new File(AppLog.dir(c), external).toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException | RuntimeException e) {
                    // the external copy is for adb only
                }
            }
            return id;
        }
    }

    /** Only the newest MAX_KEPT stay; the unseen list forgets what is gone. */
    private static void prune(Context c) {
        List<File> l = files(c);
        for (int i = MAX_KEPT; i < l.size(); i++) {
            l.get(i).delete();
        }
        File[] tmp = dir(c).listFiles((d, n) -> n.endsWith(".tmp"));
        for (File t : tmp == null ? new File[0] : tmp) {
            if (System.currentTimeMillis() - t.lastModified() > 60_000) {
                t.delete();        // a write a dead process left half done
            }
        }
        forgetGone(c);
    }

    private static void forgetGone(Context c) {
        Set<String> u = unseen(c);
        if (u.removeIf(id -> !new File(dir(c), id + ".txt").isFile())) {
            prefs(c).edit().putStringSet("unseen", u).commit();
        }
    }

    /** Deletes the record @id (Logs). */
    static void delete(Context c, String id) {
        synchronized (WRITE) {
            new File(dir(c), id + ".txt").delete();
            forgetGone(c);
        }
    }

    /** Deletes every record (Logs' Delete all); the guests' notices go with them. */
    static void deleteAll(Context c) {
        synchronized (WRITE) {
            for (File f : files(c)) {
                f.delete();
            }
            forgetGone(c);
        }
    }

    // -- seen or not (the setup and app kinds' notices)

    /** A copy: the set SharedPreferences hands out must not be changed. */
    static Set<String> unseen(Context c) {
        return new HashSet<>(prefs(c).getStringSet("unseen", Collections.<String>emptySet()));
    }

    /** The unseen records in one string, for a screen to see that one came or went. */
    static String unseenKey(Context c) {
        return String.join(" ", new TreeSet<>(unseen(c)));
    }

    static void seen(Context c, String id) {
        Set<String> u = unseen(c);
        if (u.remove(id)) {
            prefs(c).edit().putStringSet("unseen", u).apply();
        }
    }

    static void seenAll(Context c) {
        prefs(c).edit().remove("unseen").apply();
    }

    /** The newest record the user has not seen, but @except (one a screen shows already); null when none. */
    static Record newestUnseen(Context c, String except) {
        Set<String> u = unseen(c);
        if (u.isEmpty()) {
            return null;
        }
        for (File f : files(c)) {
            String id = idOf(f);
            if (u.contains(id) && !id.equals(except)) {
                Record r = parse(f, true);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    // -- the guest's record, written when QEMU has ended by itself

    private static volatile Thread capturing;

    /**
     * Writes the record of @r's end (@rawStatus: Render.endStatus) on a thread of its own: it reads QEMU's
     * stderr through (tens of MB) and may hash the engine once. Guest calls it with its lock held.
     */
    static void captureAsync(Context c, Run r, int rawStatus) {
        Context app = c.getApplicationContext();
        long endedMs = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                capture(app, r, rawStatus, endedMs);
            } catch (RuntimeException e) {
                AppLog.note(app, "crash record not written: " + e);
            }
        }, "crash-capture");
        capturing = t;
        t.start();
    }

    /** Until the record being written is done (at most @ms): the next start truncates the stderr it reads. */
    static void awaitCapture(long ms) {
        Thread t = capturing;
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void capture(Context c, Run r, int raw, long endedMs) {
        File run = Guest.runDir(c);
        List<String> ev = stderrEvidence(new File(run, "qemu-stderr.txt"));
        List<String> app = appLog(c, r.appLogOffset, -1, 150);
        String fatal = CrashText.fatalLine(ev);
        String cause = CrashText.cause(fatal, ev, raw);
        String status = CrashText.describeStatus(raw);
        boolean crash = CrashText.isCrash(raw, fatal);
        String guest = Guest.osName(r.os) + (r.setup ? " (setup boot)" : "");

        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("os", String.valueOf(r.os));
        h.put("guest", guest);
        h.put("start", r.start);
        h.put("uptime", duration(endedMs - r.startedMs));
        h.put("status", status);
        h.put("crash", crash ? "yes" : "no");
        h.put("cause", cause);
        h.put("fatal", fatal == null ? "" : fatal);
        h.put("app", appVersion(c));
        h.put("engine", engine(c, r.engine));
        h.put("phone", phone());
        h.put("memory", memory(c));
        h.put("thermal", thermal(c));
        h.put("hardware", r.cpus + " vCPUs, " + (r.ramMiB > 0 ? r.ramMiB + " MiB guest RAM" : "the setup's own guest RAM")
                + (r.screen == null || r.screen.isEmpty() ? "" : ", screen " + r.screen));
        LinkedHashMap<String, List<String>> s = new LinkedHashMap<>();
        s.put(CrashText.QEMU_STDERR, ev);
        s.put(CrashText.RUN_APPLOG, app);
        String id = write(c, GUEST, String.valueOf(r.pid), endedMs, h, s, "last-crash.txt", false);
        if (id == null) {
            return;
        }
        String said = "crash: " + guest + " QEMU " + status + ": " + cause;
        AppLog.note(c, said.length() > 300 ? said.substring(0, 300) : said);
        prefs(c).edit().putString("pending." + r.os, id).putInt("notified", r.os).commit();
        notify(c, id, guest, crash, cause);
    }

    /** "12 min 4 s" */
    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return s / 60 + " min " + s % 60 + " s";
        }
        return s / 3600 + " h " + s % 3600 / 60 + " min";
    }

    // -- the evidence, for every kind

    /** What of a QEMU's stderr goes into a record (CrashText.evidence: its tail, and reims' lines from before). */
    static List<String> stderrEvidence(File f) {
        // UTF-8 with bad bytes replaced (InputStreamReader does that): the record is UTF-8
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            return CrashText.evidence(in, 200, 80);
        } catch (IOException e) {
            return new ArrayList<>(Collections.singletonList("(" + f.getName() + " could not be read: " + e.getMessage() + ")"));
        }
    }

    /**
     * The last @last lines of the app's log (files/run/app.txt) between byte @from (-1: only its end) and @upTo
     * (-1: its end), one run's part of it. At most 2 MB are read (the log is never cut).
     */
    static List<String> appLog(Context c, long from, long upTo, int last) {
        File f = new File(Guest.runDir(c), "app.txt");
        long len = upTo >= 0 ? Math.min(upTo, f.length()) : f.length();
        long start = from >= 0 && from <= len ? from : Math.max(0, len - (256 << 10));
        if (len - start > (2 << 20)) {
            start = len - (2 << 20);
        }
        return lines(f, start, len, last, from >= 0 && start == from);
    }

    /** The last @last lines of a text file (its last MB read): a console log's tail. */
    static List<String> tail(File f, int last) {
        long len = f.length();
        return lines(f, Math.max(0, len - (1 << 20)), len, last, len <= (1 << 20));
    }

    /** The last @last lines of @f's bytes [@from, @to); @whole: @from starts a line (else the first, cut one goes). */
    private static List<String> lines(File f, long from, long to, int last, boolean whole) {
        ArrayDeque<String> ring = new ArrayDeque<>();
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            long end = Math.min(to, in.length()), start = Math.max(0, Math.min(from, end));
            byte[] b = new byte[(int) (end - start)];
            in.seek(start);
            in.readFully(b);
            // (bad UTF-8 is replaced: the record is UTF-8)
            String[] ls = new String(b, StandardCharsets.UTF_8).split("\n", -1);
            int n = ls.length > 0 && ls[ls.length - 1].isEmpty() ? ls.length - 1 : ls.length;
            for (int i = Math.max(!whole && start > 0 ? 1 : 0, n - last); i < n; i++) {
                String l = ls[i].replace("\r", "");
                ring.addLast(l.length() > CrashText.MAX_LINE ? l.substring(0, CrashText.MAX_LINE) + "…" : l);
            }
        } catch (IOException e) {
            ring.add("(" + f.getName() + " could not be read: " + e.getMessage() + ")");
        }
        return new ArrayList<>(ring);
    }

    /** The installed version, as Android has it (UpdateCheck.version takes a debug build's test override). */
    static String appVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * "libqemu26.so sha1 …, N bytes": which build crashed, for builds that kept their version. The SHA-1 of the
     * ~80 MB file is kept in prefs while its path, length and time stay (an app update gives it a new path).
     */
    private static String engine(Context c, String path) {
        if (path == null || path.isEmpty()) {
            return "unknown";
        }
        File f = new File(path);
        if (!f.isFile()) {
            return f.getName() + " (not found)";
        }
        long len = f.length();
        String key = "sha1." + f.getName(), want = path + "|" + len + "|" + f.lastModified() + "|";
        String had = prefs(c).getString(key, "");
        String sha1;
        if (had.startsWith(want)) {
            sha1 = had.substring(want.length());
        } else {
            sha1 = sha1(f);
            if (sha1 != null) {
                prefs(c).edit().putString(key, want + sha1).apply();
            }
        }
        return f.getName() + " sha1 " + (sha1 == null ? "unknown" : sha1) + ", " + len + " bytes";
    }

    private static String sha1(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = new byte[1 << 20];
            int n;
            while ((n = in.read(b)) > 0) {
                md.update(b, 0, n);
            }
            StringBuilder hex = new StringBuilder(40);
            for (byte x : md.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** The phone's make, model and Android: nothing that tells this phone from another of its kind. */
    static String phone() {
        StringBuilder s = new StringBuilder(Build.MANUFACTURER + " " + Build.MODEL);
        if (Build.VERSION.SDK_INT >= 31) {
            s.append(", SoC ").append(Build.SOC_MODEL);
        }
        return s.append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(')').toString();
    }

    static String memory(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        if (am == null) {
            return "unknown";
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return String.format(Locale.ROOT, "%.1f GB of %.1f GB available%s", mi.availMem / 1e9, mi.totalMem / 1e9,
                mi.lowMemory ? " (Android counts that as low)" : "");
    }

    /** A line of /proc/meminfo in kB ("MemTotal", "MemAvailable"); -1 when it is not there. */
    static long memInfoKb(String key) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/meminfo"), StandardCharsets.US_ASCII))) {
            String l;
            while ((l = in.readLine()) != null) {
                if (l.startsWith(key + ":")) {
                    String[] f = l.substring(key.length() + 1).trim().split("\\s+");
                    return Long.parseLong(f[0]);
                }
            }
        } catch (IOException | RuntimeException e) {
            // not readable: the caller falls back
        }
        return -1;
    }

    /** "MemTotal 7.4 GB, MemAvailable 2.1 GB" (the kernel's own numbers). */
    static String memInfo() {
        long t = memInfoKb("MemTotal"), a = memInfoKb("MemAvailable");
        return t < 0 ? "unknown" : String.format(Locale.ROOT, "MemTotal %.1f GB, MemAvailable %.2f GB", t * 1024 / 1e9, a * 1024 / 1e9);
    }

    private static final String[] THERMAL = {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"};

    static String thermal(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm == null) {
            return "unknown";
        }
        int t = pm.getCurrentThermalStatus();
        return t + (t >= 0 && t < THERMAL.length ? " (" + THERMAL[t] + ")" : "");
    }

    static String freeSpace(Context c) {
        return String.format(Locale.ROOT, "%.1f GB free", c.getFilesDir().getUsableSpace() / 1e9);
    }

    /** The notice of a new record; a tap opens the home screen's report (never the guest's screen: that starts it). */
    private static void notify(Context c, String id, String guest, boolean crash, String cause) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "iOS crashes", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_CRASH, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, NOTE_ID, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(guest + (crash ? " crashed" : " stopped by itself"))
                .setContentText(CrashText.shortCause(cause))
                .setStyle(new Notification.BigTextStyle().bigText(cause))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(NOTE_ID, n);
    }

    // -- the records, read back

    /** One record, as write() left it. */
    static final class Record {
        String id = "", kind = GUEST, guest = "", what = "", cause = "", fatal = "", status = "", time = "", uptime = "", start = "";
        int os;
        boolean crash;
        /** The header, key to value, in its order. */
        final LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        /** The logs, by name, in their order (empty when only the header was read). */
        final LinkedHashMap<String, List<String>> sections = new LinkedHashMap<>();
        List<String> stderr = new ArrayList<>(), app = new ArrayList<>();
        File file;

        String fact(String key) {
            String v = facts.get(key);
            return v == null ? "" : v;
        }

        /** One line for lists and notices: "iOS 26 crashed", "iOS 16 setup stopped at Build the iOS disk". */
        String summary() {
            String s = GUEST.equals(kind) ? guest + (crash ? " crashed" : " stopped by itself") : what;
            return test() ? "[test] " + s : s;
        }

        /** Made by the debug builds' logs-test (AppExit.testRecords), not by anything that happened. */
        boolean test() {
            return "yes".equals(fact("test"));
        }
    }

    /** The record in @f, null when there is none (or it is not one); @whole: its logs too, else the header only. */
    static Record parse(File f, boolean whole) {
        if (f == null || !f.isFile()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = in.readLine()) != null && (whole || CrashText.sectionName(l) == null)) {
                lines.add(l);
            }
        } catch (IOException e) {
            return null;
        }
        CrashText.Parsed p = CrashText.parseRecord(lines);
        if (p == null) {
            return null;
        }
        Record r = new Record();
        r.file = f;
        r.facts.putAll(p.header);
        r.sections.putAll(p.sections);
        List<String> err = p.sections.get(CrashText.QEMU_STDERR), app = p.sections.get(CrashText.RUN_APPLOG);
        r.stderr = err == null ? new ArrayList<>() : err;
        r.app = app == null ? new ArrayList<>() : app;
        r.id = r.fact("id");
        r.kind = r.fact("kind").isEmpty() ? GUEST : r.fact("kind");
        r.guest = r.fact("guest");
        r.what = r.fact("what");
        r.cause = r.fact("cause");
        r.fatal = r.fact("fatal");
        r.status = r.fact("status");
        r.time = r.fact("time");
        r.uptime = r.fact("uptime");
        r.start = r.fact("start");
        r.crash = "yes".equals(r.fact("crash"));
        try {
            r.os = Integer.parseInt(r.fact("os"));
        } catch (NumberFormatException e) {
            r.os = 0;
        }
        return r.id.isEmpty() ? null : r;
    }

    /** The record @id, whole; null when it is gone. */
    static Record find(Context c, String id) {
        return id == null || id.isEmpty() || id.contains("/") ? null : parse(new File(dir(c), id + ".txt"), true);
    }

    /** The newest guest record of @os among the kept ones. */
    static Record latestFor(Context c, int os) {
        for (File f : files(c)) {
            Record r = parse(f, false);
            if (r != null && GUEST.equals(r.kind) && r.os == os) {
                return parse(f, true);
            }
        }
        return null;
    }

    /** The record @id (a notification's), else the newest guest record. */
    static Record byId(Context c, String id) {
        Record r = find(c, id);
        if (r != null) {
            return r;
        }
        for (File f : files(c)) {
            Record g = parse(f, false);
            if (g != null && GUEST.equals(g.kind)) {
                return parse(f, true);
            }
        }
        return null;
    }

    /** The id of @os's record the screens still show, null when none: @os has not been started since. */
    static String pendingId(Context c, int os) {
        return prefs(c).getString("pending." + os, null);
    }

    /** The pending notices of every system in one string, for a screen to see that one came or went. */
    static String pendingKey(Context c) {
        return pendingId(c, Guest.IOS26) + " " + pendingId(c, Guest.WATCH) + " " + pendingId(c, Guest.IOS16);
    }

    /** @os's newest record while its notice stands (until @os is started again), else null. */
    static Record pendingFor(Context c, int os) {
        String id = pendingId(c, os);
        if (id == null) {
            return null;
        }
        Record r = latestFor(c, os);
        return r != null && id.equals(r.id) ? r : null;
    }

    /** @os starts again: its notice goes from the screens, and its notification if that is the one showing. */
    static void startedAgain(Context c, int os) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit().remove("pending." + os);
        if (p.getInt("notified", -1) == os) {
            e.remove("notified");
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTE_ID);
            }
        }
        e.apply();
    }

    // -- the report

    /** The issue's title: "iOS 26: renderCommandEncoderWithDescriptor:: BGR10_XR (552)", "iOS 16 setup: …". */
    static String reportTitle(Record r) {
        String t;
        if (GUEST.equals(r.kind)) {
            t = r.guest.replace(" (setup boot)", "") + ": " + CrashText.shortCause(CrashText.scrub(r.cause, null));
        } else if (SETUP.equals(r.kind)) {
            t = r.guest + " setup: " + CrashText.shortCause(CrashText.scrub(r.cause, null));
        } else {
            t = CrashText.shortCause(CrashText.scrub(r.what, null));
        }
        t = (r.test() ? "[test] " : "") + t;
        return t.length() > 120 ? t.substring(0, 119) + "…" : t;
    }

    /** The app's own folders and what the report calls them. */
    static Map<String, String> prefixes(Context c) {
        Map<String, String> m = new HashMap<>();
        String pkg = c.getPackageName();
        m.put("/data/user/0/" + pkg + "/files", "<files>");
        m.put("/data/data/" + pkg + "/files", "<files>");
        m.put("/data/user/0/" + pkg, "<data>");
        m.put("/data/data/" + pkg, "<data>");
        m.put(c.getFilesDir().getPath(), "<files>");
        if (c.getApplicationInfo().dataDir != null) {
            m.put(c.getApplicationInfo().dataDir, "<data>");
        }
        File ext = c.getExternalFilesDir(null);
        if (ext != null) {
            m.put(ext.getPath(), "<ext>");
        }
        if (c.getApplicationInfo().nativeLibraryDir != null) {
            m.put(c.getApplicationInfo().nativeLibraryDir, "<lib>");
        }
        return m;
    }

    static List<String> scrubAll(List<String> lines, Map<String, String> pre) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            out.add(CrashText.scrub(l, pre));
        }
        return out;
    }

    /** What a setup or app record's header keeps for itself: the rest goes into the report's table. */
    private static final Set<String> INTERNAL = new HashSet<>(Arrays.asList("id", "kind", "os", "guest", "crash", "cause",
            "fatal", "what", "test"));

    /** "free space" as the table says it: "Free space"; the time as "When". */
    private static String label(String key) {
        if ("time".equals(key)) {
            return "When";
        }
        return key.isEmpty() ? key : Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    /**
     * The issue's body for @r, scrubbed: the fatal line (else the cause), the facts, and the logs. @forUrl:
     * shortened to fit GitHub's link (CrashText.fitBody), else whole, for the clipboard.
     */
    static String report(Context c, Record r, boolean forUrl) {
        Map<String, String> pre = prefixes(c);
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        if (GUEST.equals(r.kind)) {
            String cause = CrashText.scrub(r.fatal.isEmpty() ? r.cause : r.fatal, pre);
            facts.put("App", r.fact("app"));
            facts.put("Engine", r.fact("engine"));
            facts.put("Guest", r.guest + ", " + r.start);
            facts.put("Ended", r.time + ", after " + r.uptime + ", " + r.status);
            facts.put("Hardware", r.fact("hardware"));
            facts.put("Phone", r.fact("phone"));
            facts.put("Memory", r.fact("memory"));
            facts.put("Thermal", r.fact("thermal"));
            for (Map.Entry<String, String> f : facts.entrySet()) {
                f.setValue(CrashText.scrub(f.getValue(), pre));
            }
            String what = r.guest + (r.crash ? " crashed" : " stopped by itself") + " after " + r.uptime + "; QEMU ended with " + r.status;
            List<String> err = scrubAll(r.stderr, pre), app = scrubAll(r.app, pre);
            return forUrl ? CrashText.fitBody(REPO, reportTitle(r), facts, what, cause, err, app, MAX_URL)
                    : CrashText.body(facts, what, cause, err, app, false);
        }
        for (Map.Entry<String, String> f : r.facts.entrySet()) {
            if (!INTERNAL.contains(f.getKey())) {
                facts.put(label(f.getKey()), CrashText.scrub(f.getValue(), pre));
            }
        }
        List<CrashText.Block> blocks = new ArrayList<>();
        for (Map.Entry<String, List<String>> s : r.sections.entrySet()) {
            String n = s.getKey();
            // what says why gets twice an app log's room in a shortened link
            int weight = n.contains("stderr") || n.contains("console") || n.contains("stack") || n.contains("trace") ? 2 : 1;
            blocks.add(new CrashText.Block(label(n), scrubAll(s.getValue(), pre), weight));
        }
        String what = CrashText.scrub(r.summary(), pre), cause = CrashText.scrub(r.cause, pre);
        return forUrl ? CrashText.fitBody(REPO, reportTitle(r), facts, what, cause, blocks, MAX_URL)
                : CrashText.body(facts, what, cause, blocks, false);
    }

    private static void copy(Context c, String s) {
        ClipboardManager cm = c.getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("ASoftware log", s));
        }
    }

    /** Opens @url (GitHub's new issue page); without a browser the link shows. */
    private static void open(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, url, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * "Report on GitHub": the new issue page with @r filled in (what fits the link), the whole report on the
     * clipboard for what did not. The user reads it there and submits it, or not. Off the UI thread: it scrubs.
     */
    static void openIssue(Activity a, Record r) {
        new Thread(() -> {
            String title = reportTitle(r), all = title + "\n\n" + report(a, r, false);
            String url = CrashText.issueUrl(REPO, title, report(a, r, true));
            a.runOnUiThread(() -> issueNow(a, all, url));
        }, "report-issue").start();
    }

    /** As above for a log that is no record (the app's log, the GPU check's): @blocks under @facts. */
    static void openIssue(Activity a, String title, String what, String cause, LinkedHashMap<String, String> facts,
            List<CrashText.Block> blocks) {
        new Thread(() -> {
            Map<String, String> pre = prefixes(a);
            List<CrashText.Block> clean = new ArrayList<>();
            for (CrashText.Block b : blocks) {
                clean.add(b.with(scrubAll(b.lines, pre)));
            }
            String w = CrashText.scrub(what, pre), cs = CrashText.scrub(cause, pre);
            String all = title + "\n\n" + CrashText.body(facts, w, cs, clean, false);
            String url = CrashText.issueUrl(REPO, title, CrashText.fitBody(REPO, title, facts, w, cs, clean, MAX_URL));
            a.runOnUiThread(() -> issueNow(a, all, url));
        }, "report-issue").start();
    }

    private static void issueNow(Activity a, String all, String url) {
        if (a.isFinishing() || a.isDestroyed()) {
            return;
        }
        // the link holds what fits; the whole report waits on the clipboard
        copy(a, all);
        Toast.makeText(a, "Opening GitHub. The whole log is also on the clipboard, if the issue needs it.", Toast.LENGTH_LONG).show();
        open(a, url);
    }

    /** The record as Share sends it and Logs shows it: its file's lines, personal data out. */
    static String shareText(Context c, Record r) {
        Map<String, String> pre = prefixes(c);
        StringBuilder b = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(r.file), StandardCharsets.UTF_8))) {
            String l;
            while ((l = in.readLine()) != null) {
                b.append(CrashText.scrub(l, pre)).append('\n');
            }
        } catch (IOException e) {
            b.append("(the record could not be read: ").append(e.getMessage()).append(")\n");
        }
        return b.toString();
    }

    /** "Share log": Android's share sheet with @r's scrubbed text; nothing goes anywhere unless the user picks where. */
    static void share(Activity a, Record r) {
        new Thread(() -> {
            String text = shareText(a, r);
            a.runOnUiThread(() -> share(a, r.summary(), text));
        }, "share-log").start();
    }

    /** The share sheet with @text (already scrubbed) about @subject. */
    static void share(Activity a, String subject, String text) {
        if (a.isFinishing() || a.isDestroyed()) {
            return;
        }
        a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "ASoftware iOS: " + subject)
                .putExtra(Intent.EXTRA_TEXT, text), "Share the log"));
    }

    /** The report of @r, as it would go to GitHub, with the ways to send it on (none of them sends anything itself). */
    static void show(Activity a, Record r) {
        show(a, r, null);
    }

    /** As show() above, with @lead (null: none) first: what the screen that opens it has to add (ScreenActivity.COMPAT_TEXT). */
    static void show(Activity a, Record r, String lead) {
        if (r == null) {
            Toast.makeText(a, "No crash was recorded", Toast.LENGTH_SHORT).show();
            return;
        }
        String title = reportTitle(r);
        String all = title + "\n\n" + report(a, r, false);
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Math.round(22 * d), Math.round(8 * d), Math.round(22 * d), Math.round(8 * d));
        if (lead != null) {
            TextView l = new TextView(a);
            l.setTextSize(15);
            l.setText(lead);
            l.setPadding(0, 0, 0, Math.round(12 * d));
            col.addView(l);
        }
        TextView explain = new TextView(a);
        explain.setTextSize(14);
        explain.setText("Nothing is sent by itself. \"Open on GitHub\" opens a new issue on GitHub (an account is needed) with this"
                + " text filled in; you read it there and submit it yourself. Paths inside the app are shortened and nothing"
                + " personal is included.");
        col.addView(explain);
        TextView text = new TextView(a);
        text.setText(all);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(11);
        text.setTextIsSelectable(true);
        text.setPadding(0, Math.round(14 * d), 0, 0);
        col.addView(text);
        ScrollView sv = new ScrollView(a);
        sv.addView(col);
        new AlertDialog.Builder(a)
                .setTitle("Report: " + r.summary())
                .setView(sv)
                .setPositiveButton("Open on GitHub", (dl, w) -> {
                    copy(a, all);
                    open(a, CrashText.issueUrl(REPO, title, report(a, r, true)));
                })
                .setNeutralButton("Copy", (dl, w) -> {
                    copy(a, all);
                    Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** The last @maxBytes of @f as text (RandomAccessFile: the app's log is never cut), for Logs and Share. */
    static String tailText(File f, int maxBytes) {
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            long len = in.length(), from = Math.max(0, len - maxBytes);
            byte[] b = new byte[(int) (len - from)];
            in.seek(from);
            in.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            int nl = from > 0 ? s.indexOf('\n') : -1;
            return nl >= 0 ? s.substring(nl + 1) : s;
        } catch (IOException e) {
            return "(" + f.getName() + " could not be read: " + e.getMessage() + ")";
        }
    }
}
