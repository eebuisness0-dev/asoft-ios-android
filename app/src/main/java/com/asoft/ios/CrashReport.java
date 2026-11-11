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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the app keeps when a QEMU ends by itself (reims gave up on a GPU format, a panic, Android's memory
 * killer), and the report a user can file from it. QEMU's stderr, which says why, is overwritten by the next
 * start, and before this the cause was gone with it: users could only say "it stopped". So at the end Guest
 * notices (it did not ask for it) the evidence goes into a record in files/run (the last three kept, the newest
 * also in the external log folder, readable over adb on a release build), a notification says so, and the
 * screens offer "Report this crash": a GitHub issue filled in with the record, personal data taken out
 * (CrashText.scrub), which the user reads and submits. Nothing is sent by itself.
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
    private static final String PREFS = "crash", CHANNEL = "crash";
    private static final int NOTE_ID = 40;
    private static final String MAGIC = "ASoftware crash record", STDERR = "== qemu stderr ==", APPLOG = "== app log of this run ==";
    private static final int KEEP = 3;

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

    /** The record @n starts behind the newest (0: last-crash.txt, then last-crash.1.txt, last-crash.2.txt). */
    static File file(Context c, int n) {
        return new File(Guest.runDir(c), n == 0 ? "last-crash.txt" : "last-crash." + n + ".txt");
    }

    // -- the record, written when QEMU has ended by itself

    private static volatile Thread capturing;
    /** One capture writes at a time: records rotate. */
    private static final Object WRITE = new Object();

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
        synchronized (WRITE) {
            File run = Guest.runDir(c);
            List<String> ev;
            // UTF-8 with bad bytes replaced (InputStreamReader does that): the record is UTF-8
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    new FileInputStream(new File(run, "qemu-stderr.txt")), StandardCharsets.UTF_8))) {
                ev = CrashText.evidence(in, 200, 80);
            } catch (IOException e) {
                ev = new ArrayList<>(Collections.singletonList("(QEMU's output could not be read: " + e.getMessage() + ")"));
            }
            List<String> app = appLog(new File(run, "app.txt"), r.appLogOffset, 150);
            String fatal = CrashText.fatalLine(ev);
            String cause = CrashText.cause(fatal, ev, raw);
            String status = CrashText.describeStatus(raw);
            boolean crash = CrashText.isCrash(raw, fatal);
            Date ended = new Date(endedMs);
            String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(ended) + "-" + r.pid;
            String guest = Guest.osName(r.os) + (r.setup ? " (setup boot)" : "");

            List<String> out = new ArrayList<>(ev.size() + app.size() + 24);
            out.add(MAGIC + " 1");
            header(out, "id", id);
            header(out, "time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(ended));
            header(out, "os", String.valueOf(r.os));
            header(out, "guest", guest);
            header(out, "start", r.start);
            header(out, "uptime", duration(endedMs - r.startedMs));
            header(out, "status", status);
            header(out, "crash", crash ? "yes" : "no");
            header(out, "cause", cause);
            header(out, "fatal", fatal == null ? "" : fatal);
            header(out, "app", appVersion(c));
            header(out, "engine", engine(c, r.engine));
            header(out, "phone", phone());
            header(out, "memory", memory(c));
            header(out, "thermal", thermal(c));
            header(out, "hardware", r.cpus + " vCPUs, " + (r.ramMiB > 0 ? r.ramMiB + " MiB guest RAM" : "the setup's own guest RAM")
                    + (r.screen == null || r.screen.isEmpty() ? "" : ", screen " + r.screen));
            out.add(STDERR);
            out.addAll(ev);
            out.add(APPLOG);
            out.addAll(app);

            File tmp = new File(run, "last-crash.tmp");
            try {
                Files.write(tmp.toPath(), out, StandardCharsets.UTF_8);
                file(c, KEEP - 1).delete();
                for (int n = KEEP - 2; n >= 0; n--) {
                    file(c, n).renameTo(file(c, n + 1));
                }
                if (!tmp.renameTo(file(c, 0))) {
                    throw new IOException("cannot rename " + tmp);
                }
            } catch (IOException e) {
                AppLog.note(c, "crash record not written: " + e.getMessage());
                return;
            }
            try {
                Files.copy(file(c, 0).toPath(), new File(AppLog.dir(c), "last-crash.txt").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                // the external copy is for adb only
            }
            String said = "crash: " + guest + " QEMU " + status + ": " + cause;
            AppLog.note(c, said.length() > 300 ? said.substring(0, 300) : said);
            prefs(c).edit().putString("pending." + r.os, id).putInt("notified", r.os).commit();
            notify(c, id, guest, crash, cause);
        }
    }

    private static void header(List<String> out, String key, String value) {
        out.add(key + ": " + (value == null ? "" : value.replace('\r', ' ').replace('\n', ' ')));
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

    /** The last @last lines of @f from @from on: this run's part of the app's log. */
    private static List<String> appLog(File f, long from, int last) {
        ArrayDeque<String> ring = new ArrayDeque<>();
        try (InputStream raw = new FileInputStream(f)) {
            if (from > 0 && from <= f.length()) {
                long left = from;
                while (left > 0) {
                    long n = raw.skip(left);
                    if (n <= 0) {
                        break;
                    }
                    left -= n;
                }
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8));
            String l;
            while ((l = in.readLine()) != null) {
                ring.addLast(l.length() > CrashText.MAX_LINE ? l.substring(0, CrashText.MAX_LINE) + "…" : l);
                if (ring.size() > last) {
                    ring.removeFirst();
                }
            }
        } catch (IOException e) {
            ring.add("(the app's log could not be read: " + e.getMessage() + ")");
        }
        return new ArrayList<>(ring);
    }

    /** The installed version, as Android has it (UpdateCheck.version takes a debug build's test override). */
    private static String appVersion(Context c) {
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
    private static String phone() {
        StringBuilder s = new StringBuilder(Build.MANUFACTURER + " " + Build.MODEL);
        if (Build.VERSION.SDK_INT >= 31) {
            s.append(", SoC ").append(Build.SOC_MODEL);
        }
        return s.append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(')').toString();
    }

    private static String memory(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        if (am == null) {
            return "unknown";
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return String.format(Locale.ROOT, "%.1f GB of %.1f GB available%s", mi.availMem / 1e9, mi.totalMem / 1e9,
                mi.lowMemory ? " (Android counts that as low)" : "");
    }

    private static final String[] THERMAL = {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"};

    private static String thermal(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm == null) {
            return "unknown";
        }
        int t = pm.getCurrentThermalStatus();
        return t + (t >= 0 && t < THERMAL.length ? " (" + THERMAL[t] + ")" : "");
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

    /** One record, as capture wrote it. */
    static final class Record {
        String id = "", guest = "", cause = "", fatal = "", status = "", time = "", uptime = "", start = "";
        int os;
        boolean crash;
        /** The header, key to value, in its order. */
        final LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        final List<String> stderr = new ArrayList<>(), app = new ArrayList<>();
        File file;

        String fact(String key) {
            String v = facts.get(key);
            return v == null ? "" : v;
        }
    }

    /** The record in @f, null when there is none (or it is not one). */
    static Record parse(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = in.readLine()) != null) {
                lines.add(l);
            }
        } catch (IOException e) {
            return null;
        }
        if (lines.isEmpty() || !lines.get(0).startsWith(MAGIC)) {
            return null;
        }
        Record r = new Record();
        r.file = f;
        int i = 1;
        for (; i < lines.size() && !lines.get(i).equals(STDERR); i++) {
            String l = lines.get(i);
            int k = l.indexOf(':');
            if (k > 0) {
                String v = l.substring(k + 1);
                r.facts.put(l.substring(0, k), v.startsWith(" ") ? v.substring(1) : v);
            }
        }
        for (i++; i < lines.size() && !lines.get(i).equals(APPLOG); i++) {
            r.stderr.add(lines.get(i));
        }
        for (i++; i < lines.size(); i++) {
            r.app.add(lines.get(i));
        }
        r.id = r.fact("id");
        r.guest = r.fact("guest");
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

    /** The newest record, of any system. */
    static Record latest(Context c) {
        return parse(file(c, 0));
    }

    /** The newest record of @os among the kept ones. */
    static Record latestFor(Context c, int os) {
        for (int n = 0; n < KEEP; n++) {
            Record r = parse(file(c, n));
            if (r != null && r.os == os) {
                return r;
            }
        }
        return null;
    }

    /** The record @id (a notification's), else the newest. */
    static Record byId(Context c, String id) {
        for (int n = 0; id != null && n < KEEP; n++) {
            Record r = parse(file(c, n));
            if (r != null && id.equals(r.id)) {
                return r;
            }
        }
        return latest(c);
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

    /** The issue's title: "iOS 26: renderCommandEncoderWithDescriptor:: BGR10_XR (552)". */
    static String reportTitle(Record r) {
        String t = r.guest.replace(" (setup boot)", "") + ": " + CrashText.shortCause(CrashText.scrub(r.cause, null));
        return t.length() > 120 ? t.substring(0, 119) + "…" : t;
    }

    /** The app's own folders and what the report calls them. */
    private static Map<String, String> prefixes(Context c) {
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

    private static List<String> scrubAll(List<String> lines, Map<String, String> pre) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            out.add(CrashText.scrub(l, pre));
        }
        return out;
    }

    /**
     * The issue's body for @r, scrubbed: the fatal line (else the cause), the facts, QEMU's stderr and the run's
     * app log. @forUrl: shortened to fit GitHub's link (CrashText.fitBody), else whole, for the clipboard.
     */
    static String report(Context c, Record r, boolean forUrl) {
        Map<String, String> pre = prefixes(c);
        String cause = CrashText.scrub(r.fatal.isEmpty() ? r.cause : r.fatal, pre);
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
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

    private static void copy(Context c, String s) {
        ClipboardManager cm = c.getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("crash report", s));
        }
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
                .setTitle("Report: " + r.guest + (r.crash ? " crashed" : " stopped by itself"))
                .setView(sv)
                .setPositiveButton("Open on GitHub", (dl, w) -> {
                    // the link holds what fits; the whole report waits on the clipboard
                    copy(a, all);
                    String url = CrashText.issueUrl(REPO, title, report(a, r, true));
                    try {
                        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(a, url, Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Copy", (dl, w) -> {
                    copy(a, all);
                    Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
