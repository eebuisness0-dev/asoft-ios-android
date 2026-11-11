package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The record of a setup that stopped on an error (CrashReport's "setup" kind): before 0.1.5 a failure left one
 * red line on the screen ("building the iOS disk failed (progress 2 unpacking the iOS images (7137 MB))") and
 * nothing a user could send. Now SetupService writes, at the failure: which system and step, the error, every
 * helper VM run of that step that failed (how QEMU ended, native or TCG, its RAM, the lowest memory the phone
 * had meanwhile, the ends of its console and of QEMU's stderr, which Helper read before the job folder went), a
 * setup boot's console and stderr when the step was a boot, the phone's memory, space, heat and battery, and the
 * run's app log. Not for a Cancel or a Pause.
 */
final class SetupRecord {
    private SetupRecord() {}

    /** iOS 16's step names, as SetupScreen shows them. */
    private static String label16(String step) {
        for (String[] s : SetupScreen.STEPS) {
            if (s[0].equals(step)) {
                return s[1];
            }
        }
        return step.isEmpty() ? "its start" : step;
    }

    /** "iOS 26", "watchOS", "iOS 26 / watchOS (shared part)": whose step @step ("ios26:rootfs") is. */
    private static String system26(String step) {
        String g = step.contains(":") ? step.substring(0, step.indexOf(':')) : "";
        return PhoneSetup26.IOS26.equals(g) ? "iOS 26" : PhoneSetup26.WATCH.equals(g) ? "watchOS" : "iOS 26 / watchOS (shared part)";
    }

    private static int os26(String step) {
        return step.startsWith(PhoneSetup26.WATCH + ":") ? Guest.WATCH : Guest.IOS26;
    }

    /** What the screen calls step @step of PhoneSetup26 ("ios26:rootfs"). */
    private static String label26(String step) {
        int k = step.indexOf(':');
        if (k > 0) {
            for (String[] s : PhoneSetup26.steps(step.substring(0, k))) {
                if (s[0].equals(step.substring(k + 1))) {
                    return s[1];
                }
            }
        }
        return step.isEmpty() ? "its start" : step;
    }

    /** The step's name for the screens: "Build the iOS disk". */
    static String label(boolean a26, String step) {
        return a26 ? label26(step) : label16(step);
    }

    /** The steps that boot iOS or watchOS itself (Guest.startSetup, Guest.start): their QEMU's logs are the run folder's. */
    private static boolean bootStep(String step) {
        return step.endsWith("firststart") || step.endsWith(":firstboot") || step.endsWith(":wallpaper");
    }

    /**
     * Writes the record of a setup that stopped at @step (@pct, @text: its last progress) with @error (what the
     * screen says) from @e; @logAt: where the run's lines start in the app's log; @stepSince: when the step
     * started (its helper runs since then are the ones that failed in it); @runSince: when the run started.
     * Returns the record's id, null when none could be written.
     */
    static String write(Context c, boolean a26, String step, int pct, String text, Throwable e, String error, long logAt,
            long stepSince, long runSince) {
        try {
            return writeNow(c, a26, step, pct, text, e, error, logAt, stepSince, runSince);
        } catch (RuntimeException x) {
            AppLog.note(c, "setup record not written: " + x);
            return null;
        }
    }

    private static String writeNow(Context c, boolean a26, String step, int pct, String text, Throwable e, String error,
            long logAt, long stepSince, long runSince) {
        long now = System.currentTimeMillis();
        String system = a26 ? system26(step) : "iOS 16";
        String label = label(a26, step);
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("os", String.valueOf(a26 ? os26(step) : Guest.IOS16));
        h.put("guest", system);
        h.put("what", system + " setup stopped at " + label);
        h.put("cause", error == null ? String.valueOf(e) : error);
        h.put("crash", "yes");
        h.put("step", step + " (" + label + ")" + (pct >= 0 ? ", " + pct + " %" : "") + (text == null || text.isEmpty() ? "" : ": " + text));
        if (e != null && !String.valueOf(e.getMessage()).equals(error)) {
            h.put("error", e.toString());
        }
        List<Helper.Result> runs = Helper.failuresSince(stepSince);
        for (int i = 0; i < runs.size(); i++) {
            Helper.Result r = runs.get(i);
            h.put("helper " + (i + 1), String.format(Locale.ROOT, "%s job, %s: %s after %s; QEMU %s; lowest MemAvailable %s;"
                    + " QEMU's RSS up to %s", r.job, r.how(), r.failure(), CrashReport.duration(r.endedMs - r.startedMs),
                    CrashText.describeStatus(r.status), mb(r.lowestAvailKb), mb(r.peakRssKb)));
        }
        h.put("engine", Guest.nativeMissing().isEmpty()
                ? "native EL0 possible" + (Helper.useNative(c) ? "" : " (turned off)") : "TCG only (the CPU lacks " + Guest.nativeMissing() + ")");
        h.put("helper RAM", String.format(Locale.ROOT, "%d MiB for this phone's %s jobs (the phone has %.1f GB)",
                Helper.ramMiB(c, a26 ? Helper.FLOOR_WORK_IN_RAM : Helper.FLOOR_IOS16), a26 ? "iOS 26 / watchOS" : "iOS 16",
                Helper.phoneRam(c) / 1e9));
        h.put("app", CrashReport.appVersion(c));
        h.put("phone", CrashReport.phone());
        h.put("memory", CrashReport.memInfo() + "; " + CrashReport.memory(c));
        h.put("free space", CrashReport.freeSpace(c));
        h.put("thermal", CrashReport.thermal(c));
        h.put("power", PhoneSetup26.charging(c) ? "charging" : "on battery" + (PhoneSetup26.lowBattery(c) ? ", low" : ""));
        h.put("setup ran", CrashReport.duration(now - runSince) + " (this step " + CrashReport.duration(now - stepSince) + ")");

        LinkedHashMap<String, List<String>> s = new LinkedHashMap<>();
        for (int i = 0; i < runs.size(); i++) {
            Helper.Result r = runs.get(i);
            String n = "helper run " + (i + 1) + " (" + r.how() + ")";
            s.put(n + ": console (serial.txt)", r.console);
            s.put(n + ": QEMU stderr", r.stderr);
            s.put(n + ": command line", Collections.singletonList(r.cmdline));
        }
        if (bootStep(step)) {
            File run = Guest.runDir(c);
            s.put("the boot's console (serial.txt)", CrashReport.tail(new File(run, "serial.txt"), 150));
            s.put("the boot's QEMU stderr", CrashReport.stderrEvidence(new File(run, "qemu-stderr.txt")));
        }
        if (e != null && !(e instanceof java.io.IOException)) {
            s.put("java stack trace", stack(e));
        }
        s.put(CrashText.RUN_APPLOG, CrashReport.appLog(c, logAt, -1, 150));
        String id = CrashReport.write(c, CrashReport.SETUP, "setup", now, h, s, "last-setup-failure.txt", true);
        if (id != null) {
            AppLog.note(c, "setup record " + id + " (" + runs.size() + " failed helper runs)");
        }
        return id;
    }

    /** The record of an import (Set up's file from the PC) that failed with @error; @logAt as above. */
    static String writeImport(Context c, String error, Throwable e, long total, long logAt, long since) {
        try {
            long now = System.currentTimeMillis();
            LinkedHashMap<String, String> h = new LinkedHashMap<>();
            h.put("os", "0");
            h.put("guest", "Import");
            h.put("what", "The import of a bundle from the PC stopped");
            h.put("cause", error);
            h.put("crash", "yes");
            // (not the file's name: a user may have named it after themselves)
            h.put("file", total > 0 ? String.format(Locale.ROOT, "%.2f GB", total / 1e9) : "size unknown");
            if (e != null) {
                h.put("error", e.toString());
            }
            h.put("app", CrashReport.appVersion(c));
            h.put("phone", CrashReport.phone());
            h.put("memory", CrashReport.memInfo());
            h.put("free space", CrashReport.freeSpace(c));
            h.put("import ran", CrashReport.duration(now - since));
            LinkedHashMap<String, List<String>> s = new LinkedHashMap<>();
            if (e != null && !(e instanceof java.io.IOException)) {
                s.put("java stack trace", stack(e));
            }
            s.put(CrashText.RUN_APPLOG, CrashReport.appLog(c, logAt, -1, 150));
            return CrashReport.write(c, CrashReport.SETUP, "import", now, h, s, "last-setup-failure.txt", true);
        } catch (RuntimeException x) {
            AppLog.note(c, "import record not written: " + x);
            return null;
        }
    }

    private static String mb(long kb) {
        return kb < 0 ? "not read" : kb / 1024 + " MB";
    }

    /** @e's stack trace with its causes, as lines. */
    static List<String> stack(Throwable e) {
        java.io.StringWriter w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        List<String> out = new ArrayList<>();
        for (String l : w.toString().split("\n")) {
            out.add(l.replace("\r", ""));
            if (out.size() >= 300) {
                out.add("…");
                break;
            }
        }
        return out;
    }
}
