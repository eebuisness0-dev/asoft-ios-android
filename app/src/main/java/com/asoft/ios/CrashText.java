package com.asoft.ios;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text of a crash report (CrashReport): what QEMU's stderr says about its end, the one-line cause, the
 * personal data taken out, and the GitHub issue it becomes. No Android here, so the JVM unit tests run it
 * (CrashTextTest).
 */
final class CrashText {
    private CrashText() {}

    /** reims ends QEMU with this line when the phone's GPU cannot do what iOS asked (a format, a feature). */
    static final String FATAL = "reims-vgpu: fatal";

    /** A stderr line longer than this is cut: one line of a runaway dump must not fill the report. */
    static final int MAX_LINE = 2000;

    /** The " (×N)" evidence() puts after a line it kept N times. */
    private static final Pattern REPEATS = Pattern.compile(" \\(×\\d+\\)$");

    /** The last reims fatal line, from its marker on (a log prefix before it dropped); null when there is none. */
    static String fatalLine(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i);
            int at = l.indexOf(FATAL);
            if (at >= 0) {
                return REPEATS.matcher(l.substring(at)).replaceFirst("");
            }
        }
        return null;
    }

    private static boolean notable(String l) {
        return l.contains(FATAL) || l.contains("reims[warn]") || l.contains("reims[error]") || l.contains("panicked at");
    }

    /**
     * What of QEMU's stderr goes into a crash record, read once from @stderr (it can be tens of MB, so it is not
     * held): the last @tail lines, and before them reims' warnings, errors and fatal lines and Rust panics from
     * earlier in the run, each distinct line once with how often it came, at most @maxKept of them. A warning
     * from minutes before is often what explains the end, and it would be far outside any tail.
     */
    static List<String> evidence(BufferedReader stderr, int tail, int maxKept) throws IOException {
        Evidence e = new Evidence(tail, maxKept);
        char[] buf = new char[1 << 16];
        StringBuilder line = new StringBuilder();
        boolean cut = false, any = false;
        int n;
        while ((n = stderr.read(buf)) > 0) {
            for (int i = 0; i < n; i++) {
                char ch = buf[i];
                if (ch == '\n') {
                    e.add(cut ? line.append('…').toString() : line.toString());
                    line.setLength(0);
                    cut = false;
                    any = false;
                } else if (ch != '\r') {
                    any = true;
                    if (line.length() < MAX_LINE) {
                        line.append(ch);
                    } else {
                        cut = true;
                    }
                }
            }
        }
        if (any) {
            e.add(cut ? line.append('…').toString() : line.toString());
        }
        return e.lines();
    }

    /** evidence()'s state: the tail as a ring; a line that falls out of it is kept when it is notable. */
    private static final class Evidence {
        final int tail, maxKept;
        final ArrayDeque<String> ring;
        final LinkedHashMap<String, int[]> kept = new LinkedHashMap<>();
        /** Hashes of the notable lines beyond maxKept, for their count (capped, so a flood cannot fill memory). */
        final HashSet<Integer> leftOut = new HashSet<>();
        boolean leftOutCapped;
        /** The line before was a "panicked at …:" one: this one is its message (newer Rust prints it on its own line). */
        boolean panicMessage;

        Evidence(int tail, int maxKept) {
            this.tail = Math.max(0, tail);
            this.maxKept = maxKept;
            ring = new ArrayDeque<>(this.tail + 1);
        }

        void add(String l) {
            ring.addLast(l);
            if (ring.size() > tail) {
                gone(ring.removeFirst());
            }
        }

        /** @l is before the tail window now. */
        void gone(String l) {
            boolean message = panicMessage;
            panicMessage = l.contains("panicked at") && l.trim().endsWith(":");
            if (!notable(l) && !(message && !l.trim().isEmpty())) {
                return;
            }
            int[] count = kept.get(l);
            if (count != null) {
                count[0]++;
            } else if (kept.size() < maxKept) {
                kept.put(l, new int[] {1});
            } else if (leftOut.size() < 200_000) {
                leftOut.add(l.hashCode());
            } else {
                leftOutCapped = true;
            }
        }

        List<String> lines() {
            List<String> out = new ArrayList<>(kept.size() + ring.size() + 3);
            if (!kept.isEmpty()) {
                out.add("--- reims/panic lines before the last " + tail + " lines ---");
                for (Map.Entry<String, int[]> k : kept.entrySet()) {
                    int count = k.getValue()[0];
                    out.add(count > 1 ? k.getKey() + " (×" + count + ")" : k.getKey());
                }
                if (!leftOut.isEmpty()) {
                    out.add("… " + (leftOutCapped ? "more than " : "") + leftOut.size() + " more distinct lines left out");
                }
            }
            out.add("--- the last " + ring.size() + " lines ---");
            out.addAll(ring);
            return out;
        }
    }

    private static final String[] SIGNALS = new String[16];

    static {
        SIGNALS[1] = "SIGHUP";
        SIGNALS[2] = "SIGINT";
        SIGNALS[4] = "SIGILL";
        SIGNALS[6] = "SIGABRT";
        SIGNALS[7] = "SIGBUS";
        SIGNALS[8] = "SIGFPE";
        SIGNALS[9] = "SIGKILL";
        SIGNALS[11] = "SIGSEGV";
        SIGNALS[13] = "SIGPIPE";
        SIGNALS[15] = "SIGTERM";
    }

    /** POSIX WIFSIGNALED of a raw wait status. */
    static boolean signalled(int raw) {
        int s = raw & 0x7f;
        return raw != -1 && s != 0 && s != 0x7f;
    }

    static int signal(int raw) {
        return raw & 0x7f;
    }

    static int exitCode(int raw) {
        return (raw >> 8) & 0xff;
    }

    private static String signalText(int sig) {
        String name = sig < SIGNALS.length ? SIGNALS[sig] : null;
        return "signal " + sig + (name != null ? " (" + name + ")" : "");
    }

    /** "signal 6 (SIGABRT)", "exit code 1", or "unknown" (-1: the app did not see QEMU end). */
    static String describeStatus(int raw) {
        if (raw == -1) {
            return "unknown";
        }
        return signalled(raw) ? signalText(signal(raw)) : "exit code " + exitCode(raw);
    }

    /** Signal 9 to a process the app did not end: Android's memory killer, almost always. */
    static boolean killedForMemory(int raw) {
        return signalled(raw) && signal(raw) == 9;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Why a helper VM job failed, for the setup's error line after "building the iOS disk failed: ". Before 0.1.5
     * the line ended in the job's last progress line ("(progress 2 unpacking the iOS images (7137 MB))"), which
     * read as the cause; now it says how the VM ended and what it was doing then. @rc: the job's own exit code
     * ("ASOFT: done RC"), -1 when the VM ended without one; @raw: QEMU's wait status (-1 unknown); @hung: the app
     * ended it as hung (Helper.HUNG_CHECKS), else null; @progress: its last progress text; @lastOutput: the last
     * line its scripts printed (a failing command's own words, under set -e).
     */
    static String helperFailure(int rc, int raw, String hung, String progress, String lastOutput) {
        String during = blank(progress) ? "" : " while " + progress.trim();
        if (!blank(hung)) {
            return hung.trim() + during;
        }
        if (rc != -1) {
            return "its job failed with exit code " + rc + during + (blank(lastOutput) ? "" : ": " + cap(lastOutput, 300));
        }
        if (killedForMemory(raw)) {
            return "the helper VM was killed (signal 9, probably Android freeing memory)" + during;
        }
        if (signalled(raw)) {
            return "the helper VM crashed (" + signalText(signal(raw)) + ")" + during;
        }
        if (raw != -1 && exitCode(raw) != 0) {
            return "the helper VM's QEMU ended with exit code " + exitCode(raw) + during;
        }
        // exit code 0 without "done": the VM's Linux powered off or panicked (QEMU runs it with -no-reboot)
        return "the helper VM stopped before its job was done" + during;
    }

    // -- the app's own end (AppExit): ApplicationExitInfo's reasons, as plain ints (some are newer than minSdk)

    static final int EXIT_UNKNOWN = 0, EXIT_SELF = 1, EXIT_SIGNALED = 2, EXIT_LOW_MEMORY = 3, EXIT_CRASH = 4,
            EXIT_CRASH_NATIVE = 5, EXIT_ANR = 6, EXIT_INITIALIZATION_FAILURE = 7, EXIT_PERMISSION_CHANGE = 8,
            EXIT_EXCESSIVE_RESOURCE_USAGE = 9, EXIT_USER_REQUESTED = 10, EXIT_USER_STOPPED = 11, EXIT_DEPENDENCY_DIED = 12,
            EXIT_OTHER = 13, EXIT_FREEZER = 14, EXIT_PACKAGE_STATE_CHANGE = 15, EXIT_PACKAGE_UPDATED = 16;
    /** No exit reason at all: Android 10 keeps none for apps. */
    static final int EXIT_NOT_KEPT = -2;

    private static final String[] EXIT_NAMES = {"UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR",
            "INITIALIZATION_FAILURE", "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE", "USER_REQUESTED", "USER_STOPPED",
            "DEPENDENCY_DIED", "OTHER", "FREEZER", "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED"};

    /** "LOW_MEMORY (3)", for the record. */
    static String exitName(int reason) {
        return (reason >= 0 && reason < EXIT_NAMES.length ? EXIT_NAMES[reason] : reason == EXIT_NOT_KEPT ? "not kept" : "reason")
                + " (" + reason + ")";
    }

    /**
     * How much an end of the app's process (@reason, @description: ApplicationExitInfo's) is worth a record: -1
     * never (the user ended it: a force stop, a swipe from Recents, an update, a permission taken back; or the
     * app itself), 1 always (a crash, a native crash, an ANR: bugs), 0 only while something was going on (a
     * guest, a setup, an import): Android closing an idle app in the background is how Android works.
     */
    static int exitWeight(int reason, String description) {
        switch (reason) {
            case EXIT_USER_REQUESTED:
            case EXIT_USER_STOPPED:
            case EXIT_PACKAGE_UPDATED:
            case EXIT_PACKAGE_STATE_CHANGE:
            case EXIT_PERMISSION_CHANGE:
            case EXIT_SELF:
                return -1;
            case EXIT_CRASH:
            case EXIT_CRASH_NATIVE:
            case EXIT_ANR:
            case EXIT_INITIALIZATION_FAILURE:
                return 1;
            case EXIT_OTHER: {
                // a swipe from Recents ends the task with "remove task"
                String d = description == null ? "" : description.toLowerCase(java.util.Locale.ROOT);
                return d.contains("remove task") || d.contains("swipe") || d.contains("user request") ? -1 : 0;
            }
            default:
                return 0;
        }
    }

    /** The reason in plain words: "low memory", "killed (signal 9), usually Android freeing memory". */
    static String exitWords(int reason, int status, String description) {
        switch (reason) {
            case EXIT_LOW_MEMORY:
                return "low memory";
            case EXIT_SIGNALED:
                return status == 9 ? "killed (signal 9), usually Android freeing memory" : "ended by " + signalText(status);
            case EXIT_CRASH:
                return "a crash (a Java exception)";
            case EXIT_CRASH_NATIVE:
                return "a crash in its native code" + (status > 0 && status < 64 ? " (" + signalText(status) + ")" : "");
            case EXIT_ANR:
                return "it stopped responding (ANR)";
            case EXIT_INITIALIZATION_FAILURE:
                return "it failed to start";
            case EXIT_EXCESSIVE_RESOURCE_USAGE:
                return "it used too much memory or CPU in the background";
            case EXIT_DEPENDENCY_DIED:
                return "a process it depended on ended";
            case EXIT_FREEZER:
                return "Android froze it, then closed it";
            case EXIT_OTHER:
                return "Android closed it" + (blank(description) ? "" : " (" + cap(description, 120) + ")");
            case EXIT_NOT_KEPT:
                return "Android 10 does not tell apps why";
            default:
                return "Android did not say why";
        }
    }

    /**
     * One line for the screens: "The app was closed by Android while the iOS 16 setup was running (Build the iOS
     * disk): low memory". @activity: what was going on ("the iOS 16 setup was running …"), null when nothing.
     */
    static String appExitSummary(int reason, int status, String description, String activity) {
        String during = blank(activity) ? "" : " while " + activity.trim();
        if (reason == EXIT_CRASH || reason == EXIT_CRASH_NATIVE) {
            return "The app crashed" + during + ": " + exitWords(reason, status, description);
        }
        if (reason == EXIT_ANR) {
            return "The app stopped responding" + during + ", and Android closed it";
        }
        if (reason == EXIT_NOT_KEPT) {
            return "The app was closed" + during + " (" + exitWords(reason, status, description) + ")";
        }
        return "The app was closed by Android" + during + ": " + exitWords(reason, status, description);
    }

    // -- a record's file (CrashReport): "key: value" lines, then sections, each under a header line

    static final String MAGIC = "ASoftware crash record";
    /** A section's header line: "== asoft: NAME ==". */
    private static final Pattern SECTION = Pattern.compile("^== asoft: (.+) ==$");
    /** The guest records before 0.1.5: their two sections' headers, and the names they stand for. */
    static final String OLD_STDERR = "== qemu stderr ==", OLD_APPLOG = "== app log of this run ==";
    static final String QEMU_STDERR = "qemu stderr", RUN_APPLOG = "app log of this run";

    static String sectionHeader(String name) {
        return "== asoft: " + name + " ==";
    }

    /** The section a header line starts, null for any other line. */
    static String sectionName(String line) {
        if (OLD_STDERR.equals(line)) {
            return QEMU_STDERR;
        }
        if (OLD_APPLOG.equals(line)) {
            return RUN_APPLOG;
        }
        Matcher m = SECTION.matcher(line);
        return m.matches() ? m.group(1) : null;
    }

    /** A record, parsed: its header in order, its sections in order. */
    static final class Parsed {
        final LinkedHashMap<String, String> header = new LinkedHashMap<>();
        final LinkedHashMap<String, List<String>> sections = new LinkedHashMap<>();
    }

    /** @lines as a record, null when they are not one (no magic line first). */
    static Parsed parseRecord(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).startsWith(MAGIC)) {
            return null;
        }
        Parsed p = new Parsed();
        List<String> into = null;
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i);
            String s = sectionName(l);
            if (s != null) {
                into = new ArrayList<>();
                p.sections.put(s, into);
            } else if (into != null) {
                into.add(l);
            } else {
                int k = l.indexOf(':');
                if (k > 0) {
                    String v = l.substring(k + 1);
                    p.header.put(l.substring(0, k), v.startsWith(" ") ? v.substring(1) : v);
                }
            }
        }
        return p;
    }

    private static int utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
    }

    /** A line of a record cut to fit (@max characters). */
    private static String cutLine(String l, int max) {
        return l.length() > max ? l.substring(0, max) + "…" : l;
    }

    /**
     * The lines of a record file: the magic line, @header (one line per key, newlines out), then each of
     * @sections under its header. Kept within @maxBytes (CrashReport.MAX_BYTES): first every line is cut to 500
     * characters, then the largest section loses its oldest lines (a log's newest lines say most), with a line
     * that says how many went.
     */
    static List<String> recordLines(LinkedHashMap<String, String> header, LinkedHashMap<String, List<String>> sections, int maxBytes) {
        List<String> head = new ArrayList<>();
        head.add(MAGIC + " 1");
        for (Map.Entry<String, String> h : header.entrySet()) {
            String v = h.getValue() == null ? "" : h.getValue().replace('\r', ' ').replace('\n', ' ');
            head.add(h.getKey() + ": " + cutLine(v, MAX_LINE));
        }
        LinkedHashMap<String, List<String>> s = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : sections.entrySet()) {
            List<String> ls = new ArrayList<>();
            for (String l : e.getValue()) {
                // (a header line inside a log would start a section when it is read back)
                ls.add(sectionName(l) != null ? " " + l : l);
            }
            s.put(e.getKey(), ls);
        }
        int total = 0;
        for (String l : head) {
            total += utf8(l);
        }
        int[] sizes = new int[s.size()];
        int i = 0;
        for (Map.Entry<String, List<String>> e : s.entrySet()) {
            sizes[i] = utf8(sectionHeader(e.getKey()));
            for (String l : e.getValue()) {
                sizes[i] += utf8(l);
            }
            total += sizes[i++];
        }
        if (total > maxBytes) {
            total = 0;
            i = 0;
            for (String l : head) {
                total += utf8(l);
            }
            for (Map.Entry<String, List<String>> e : s.entrySet()) {
                List<String> ls = e.getValue();
                sizes[i] = utf8(sectionHeader(e.getKey()));
                for (int k = 0; k < ls.size(); k++) {
                    ls.set(k, cutLine(ls.get(k), 500));
                    sizes[i] += utf8(ls.get(k));
                }
                total += sizes[i++];
            }
        }
        List<List<String>> lists = new ArrayList<>(s.values());
        int[] dropped = new int[lists.size()];
        // (the "… N older lines left out" line each shortened section gets needs its room too)
        final int mark = 80;
        while (total > maxBytes) {
            int big = 0;
            for (int k = 1; k < sizes.length; k++) {
                if (sizes[k] > sizes[big]) {
                    big = k;
                }
            }
            List<String> ls = lists.get(big);
            if (ls.isEmpty()) {
                break;      // only headers left: nothing more to take
            }
            int n = utf8(ls.remove(0));
            sizes[big] -= n;
            total -= n;
            if (dropped[big]++ == 0) {
                sizes[big] += mark;
                total += mark;
            }
        }
        List<String> out = new ArrayList<>(head);
        i = 0;
        for (Map.Entry<String, List<String>> e : s.entrySet()) {
            out.add(sectionHeader(e.getKey()));
            if (dropped[i] > 0) {
                out.add("… " + dropped[i] + " older lines left out (the record's size limit)");
            }
            out.addAll(e.getValue());
            i++;
        }
        return out;
    }

    /**
     * Whether an end the app did not ask for was a crash: a reims fatal line, a signal, an exit code other than 0,
     * or an end nobody saw. Exit code 0 without a fatal line is the guest's own shutdown or reset (QEMU runs with
     * -no-reboot), which the report calls "stopped by itself".
     */
    static boolean isCrash(int raw, String fatal) {
        return fatal != null || raw == -1 || signalled(raw) || exitCode(raw) != 0;
    }

    private static String cap(String s, int max) {
        s = s.trim();
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    /**
     * The cause in one line, for the screens, the notification and the report: reims' fatal line, else a Rust
     * panic, else what the log says of the phone's storage or Android's mapping limit, else the signal QEMU got,
     * else its last error line, else its exit code. @tailLines: evidence()'s lines.
     */
    static String cause(String fatal, List<String> tailLines, int raw) {
        if (fatal != null) {
            int at = fatal.indexOf("fatal: ");
            return cap(at >= 0 ? fatal.substring(at + 7) : fatal, 600);
        }
        List<String> lines = new ArrayList<>(tailLines.size());
        for (String l : tailLines) {
            lines.add(REPEATS.matcher(l).replaceFirst(""));
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i).trim();
            if (!l.contains("panicked at")) {
                continue;
            }
            // newer Rust: "thread 'x' panicked at file:line:col:", the message on the next line
            if (l.endsWith(":") && i + 1 < lines.size()) {
                String next = lines.get(i + 1).trim();
                if (!next.isEmpty() && !next.startsWith("note:") && !next.startsWith("stack backtrace")
                        && !next.startsWith("---")) {
                    l = l + " " + next;
                }
            }
            return cap(l, 600);
        }
        boolean full = false, mappings = false;
        for (String l : lines) {
            full |= l.contains("No space left on device") || l.contains("ENOSPC");
            mappings |= l.contains("max_map_count") || l.contains("kernel mappings");
        }
        if (full) {
            return "the phone's storage was full";
        }
        if (mappings) {
            return "Android's limit of 65,530 memory mappings per app was reached";
        }
        String errorLine = null, assertion = null;
        for (int i = lines.size() - 1; i >= 0 && errorLine == null; i--) {
            String l = lines.get(i);
            if (l.contains("error") || l.contains("Error") || l.contains("fatal") || l.contains("Assertion") || l.contains("assert")) {
                errorLine = l.trim();
                if (l.contains("Assertion") && l.contains("failed")) {
                    assertion = errorLine;
                }
            }
        }
        if (signalled(raw)) {
            int sig = signal(raw);
            String s = "QEMU was ended by " + signalText(sig);
            if (sig == 9) {
                s += "; Android ends an app's processes this way when the phone is short of memory";
            }
            // a failed assert() aborts: the assertion is the cause, the SIGABRT only its way out
            return cap(assertion != null && sig == 6 ? assertion + "; " + s : s, 600);
        }
        if (errorLine != null && !errorLine.isEmpty()) {
            return cap(errorLine, 600);
        }
        if (raw != -1) {
            return "QEMU ended with exit code " + exitCode(raw);
        }
        return "QEMU ended by itself";
    }

    /** "552 (BGR10_XR)" and the older "555 (MTLPixelFormatBGR10_XR_sRGB)": a Metal pixel format in reims' words. */
    private static final Pattern FORMAT = Pattern.compile("(?<![-\\w])(\\d+) \\((?:MTLPixelFormat)?([A-Z][A-Za-z0-9_]*)\\)");
    private static final Pattern FEATURE = Pattern.compile("feature: (\\w+)");

    /**
     * The cause for an issue's title, at most 100 characters: for reims' "platform call SEL: …" the Metal call and
     * the pixel formats it named (or the feature it lacked, or its first clause), else the cause cut.
     */
    static String shortCause(String cause) {
        if (cause == null) {
            return "";
        }
        cause = cause.trim();
        int pc = cause.indexOf("platform call ");
        if (pc >= 0) {
            String rest = cause.substring(pc + "platform call ".length());
            int end = rest.indexOf(": ");
            if (end > 0 && rest.substring(0, end).indexOf(' ') < 0) {
                String sel = rest.substring(0, end);
                rest = rest.substring(end + 2);
                List<String> formats = new ArrayList<>();
                Matcher m = FORMAT.matcher(rest);
                while (m.find()) {
                    String f = m.group(2) + " (" + m.group(1) + ")";
                    if (!formats.contains(f)) {
                        formats.add(f);
                    }
                }
                String what;
                if (!formats.isEmpty()) {
                    what = String.join(", ", formats);
                } else {
                    Matcher f = FEATURE.matcher(rest);
                    if (f.find()) {
                        what = "unsupported " + f.group(1);
                    } else {
                        int semi = rest.indexOf(';');
                        what = (semi >= 0 ? rest.substring(0, semi) : rest).trim();
                        if (what.length() > 60) {
                            what = what.substring(0, 60).trim() + "…";
                        }
                    }
                }
                return cut(sel + ": " + what, 100);
            }
        }
        return cut(cause, 100);
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1).trim() + "…" : s;
    }

    // -- personal data out

    private static final Pattern APP_LIBS = Pattern.compile("/data/app/\\S*?/lib/arm64");
    /**
     * A path in shared storage or another app's data: up to a quote, comma, ")" or ": ", spaces included (a
     * file the user named, "Ali Veli.asoft", goes whole; a little more than the path may go with it).
     */
    private static final Pattern PATHS = Pattern.compile(
            "(?:/storage|/sdcard|/mnt|/data/user|/data/data|/data/media)(?=[/\\s\"',):]|$)(?:[^\"',):\\r\\n]|:(?![\\s]|$))*");
    private static final Pattern HOME = Pattern.compile("/home/[^/\\s]+/");
    private static final Pattern USERS = Pattern.compile("/Users/[^/\\s]+/");
    private static final Pattern WIN_USERS = Pattern.compile("([A-Za-z]):\\\\Users\\\\[^\\\\\\s]+\\\\");
    /** (a domain that starts with a letter: iOS's "AppIcon60x60@2x.png" is no address) */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z][A-Za-z0-9-]*(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern MAC = Pattern.compile("(?<![0-9A-Fa-f:])[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}(?![0-9A-Fa-f:])");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?!\\.?\\d)");
    /** "adb-<serial>-<suffix>", not the service types "_adb-tls-connect" and "_adb-tls-pairing" */
    private static final Pattern ADB_ID = Pattern.compile("(?<!\\w)adb-(?!tls-)[A-Za-z0-9]+-[A-Za-z0-9]+");

    /**
     * @text without personal data, before anything is shown for sending: the app's own folders as their
     * placeholders (@prefixes: path → "<files>" and so on, longest first), other paths in shared storage or app
     * data as "<path>", user names in home folders, e-mail, MAC and IP addresses (not QEMU's own 10.0.2.x or
     * loopback), and adb's mDNS names (they carry the phone's serial number).
     */
    static String scrub(String text, Map<String, String> prefixes) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text;
        if (prefixes != null && !prefixes.isEmpty()) {
            List<Map.Entry<String, String>> p = new ArrayList<>(prefixes.entrySet());
            Collections.sort(p, (a, b) -> b.getKey().length() - a.getKey().length());
            for (Map.Entry<String, String> e : p) {
                if (e.getKey() != null && !e.getKey().isEmpty()) {
                    s = s.replace(e.getKey(), e.getValue());
                }
            }
        }
        s = APP_LIBS.matcher(s).replaceAll("<lib>");
        s = PATHS.matcher(s).replaceAll("<path>");
        s = HOME.matcher(s).replaceAll("/home/~/");
        s = USERS.matcher(s).replaceAll("/Users/~/");
        s = WIN_USERS.matcher(s).replaceAll("$1:\\\\Users\\\\~\\\\");
        s = EMAIL.matcher(s).replaceAll("<email>");
        s = MAC.matcher(s).replaceAll("<mac>");
        Matcher m = IPV4.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2)), c = Integer.parseInt(m.group(3)),
                    d = Integer.parseInt(m.group(4));
            boolean ip = a <= 255 && b <= 255 && c <= 255 && d <= 255;
            boolean keep = !ip || a == 127 || a == 10 && b == 0 && c == 2 || a == 0 && b == 0 && c == 0 && d == 0;
            m.appendReplacement(sb, keep ? Matcher.quoteReplacement(m.group()) : "<ip>");
        }
        m.appendTail(sb);
        s = sb.toString();
        s = ADB_ID.matcher(s).replaceAll("adb-<id>");
        return s;
    }

    // -- the GitHub issue

    private static String enc(String s) {
        try {
            // (the Charset overload is Android 13's; this one is everywhere)
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** GitHub's "new issue" page for @repo with @title and @body filled in. */
    static String issueUrl(String repo, String title, String body) {
        return "https://github.com/" + repo + "/issues/new?title=" + enc(title) + "&body=" + enc(body);
    }

    /** A line inside a fenced block: three backticks would close it. */
    private static String fenced(String l) {
        return l.replace("```", "'''");
    }

    private static void block(StringBuilder b, String summary, List<String> lines) {
        b.append("<details><summary>").append(summary).append("</summary>\n\n```\n");
        for (String l : lines) {
            b.append(fenced(l)).append('\n');
        }
        b.append("```\n\n</details>\n\n");
    }

    /** A folded log in the issue: its summary line, its lines, and its share of the link's room when they do not all fit. */
    static final class Block {
        final String summary;
        final List<String> lines;
        final int weight;

        Block(String summary, List<String> lines, int weight) {
            this.summary = summary;
            this.lines = lines;
            this.weight = Math.max(1, weight);
        }

        Block with(List<String> other) {
            return new Block(summary, other, weight);
        }
    }

    /** A guest crash's two logs: QEMU's stderr with twice the app log's room (it says why). */
    private static List<Block> guestBlocks(List<String> stderrLines, List<String> appLines) {
        List<Block> b = new ArrayList<>();
        b.add(new Block("QEMU's output (stderr), its last lines", stderrLines, 2));
        b.add(new Block("The app's log of this run", appLines, 1));
        return b;
    }

    /**
     * The issue's text in GitHub's Markdown: a place for the user's words first, what happened and its cause,
     * the facts as a table, then QEMU's stderr and the app's log folded away.
     */
    static String body(LinkedHashMap<String, String> facts, String what, String cause, List<String> stderrLines,
            List<String> appLines, boolean shortened) {
        return body(facts, what, cause, guestBlocks(stderrLines, appLines), shortened);
    }

    /** As above, with any logs (@blocks) folded away under the facts. */
    static String body(LinkedHashMap<String, String> facts, String what, String cause, List<Block> blocks, boolean shortened) {
        StringBuilder b = new StringBuilder();
        b.append("**What were you doing when it happened?** (optional)\n\n\n\n");
        b.append("**What happened:** ").append(what).append("\n\n");
        b.append("**Cause:**\n```\n").append(fenced(cause == null ? "" : cause)).append("\n```\n\n");
        b.append("| | |\n|---|---|\n");
        for (Map.Entry<String, String> f : facts.entrySet()) {
            String v = f.getValue() == null ? "" : f.getValue().replace("\r", " ").replace("\n", " ").replace("|", "\\|");
            b.append("| ").append(f.getKey()).append(" | ").append(v).append(" |\n");
        }
        b.append('\n');
        for (Block k : blocks) {
            block(b, k.summary, k.lines);
        }
        if (shortened) {
            b.append("_The logs were shortened to fit the link; the app copied the full report to the clipboard: paste it below._\n\n");
        }
        b.append("_Written by ASoftware's crash report; nothing was sent by itself._\n");
        return b.toString();
    }

    /** What one line adds to the issue's link. */
    private static int urlCost(String line) {
        return enc(fenced(line) + "\n").length();
    }

    /** How many of @costs' last entries fit in @room. */
    private static int newest(int[] costs, int room) {
        int n = 0, used = 0;
        for (int i = costs.length - 1; i >= 0 && used + costs[i] <= room; i--) {
            used += costs[i];
            n++;
        }
        return n;
    }

    private static int cost(List<String> lines) {
        int s = 0;
        for (String l : lines) {
            s += urlCost(l);
        }
        return s;
    }

    /** The last lines of @lines that fit in @room. */
    private static List<String> newestOf(List<String> lines, int room) {
        int[] c = new int[lines.size()];
        for (int i = 0; i < c.length; i++) {
            c[i] = urlCost(lines.get(i));
        }
        return lines.subList(lines.size() - newest(c, room), lines.size());
    }

    private static final String TAIL_MARK = "--- the last ";

    /**
     * evidence()'s lines cut to @room of link. Its first part, reims' warnings and fatal lines from before the tail,
     * is what a tail of the run's last lines cannot show: it keeps up to half the room (its newest lines, under its
     * own heading), and the tail's oldest lines go first.
     */
    static List<String> fitStderr(List<String> stderr, int room) {
        int m = -1;
        for (int i = 0; i < stderr.size() && m < 0; i++) {
            if (stderr.get(i).startsWith(TAIL_MARK)) {
                m = i;
            }
        }
        if (m < 0) {
            return newestOf(stderr, room);
        }
        List<String> head = stderr.subList(0, m), tail = stderr.subList(m + 1, stderr.size());
        List<String> h = head;
        if (!head.isEmpty() && cost(head) > room / 2) {
            List<String> rest = newestOf(head.subList(1, head.size()), room / 2 - urlCost(head.get(0)));
            h = new ArrayList<>();
            if (!rest.isEmpty()) {
                h.add(head.get(0));
                h.addAll(rest);
            }
        }
        List<String> t = newestOf(tail, room - cost(h) - urlCost(TAIL_MARK + tail.size() + " lines ---"));
        List<String> out = new ArrayList<>(h.size() + t.size() + 1);
        out.addAll(h);
        out.add(TAIL_MARK + t.size() + " lines ---");
        out.addAll(t);
        return out;
    }

    /**
     * body() as long as its issueUrl() stays within @maxUrl (CrashReport.MAX_URL: GitHub's sign-in drops longer
     * ones): the oldest log lines go
     * first (fitStderr), QEMU's stderr keeping about two thirds of the room; the cause always stays (cut to 1000
     * characters only when even no log lines would not fit).
     */
    static String fitBody(String repo, String title, LinkedHashMap<String, String> facts, String what, String cause,
            List<String> stderr, List<String> app, int maxUrl) {
        return fitBody(repo, title, facts, what, cause, guestBlocks(stderr, app), maxUrl);
    }

    /**
     * As above for any logs: each block gets the room its weight gives it, a block that needs less leaves the
     * rest to the others, and each keeps its newest lines (fitStderr: a kept head of reims lines stays).
     */
    static String fitBody(String repo, String title, LinkedHashMap<String, String> facts, String what, String cause,
            List<Block> blocks, int maxUrl) {
        String full = body(facts, what, cause, blocks, false);
        if (issueUrl(repo, title, full).length() <= maxUrl) {
            return full;
        }
        List<Block> none = new ArrayList<>(blocks.size());
        for (Block k : blocks) {
            none.add(k.with(Collections.<String>emptyList()));
        }
        int base = issueUrl(repo, title, body(facts, what, cause, none, true)).length();
        if (base > maxUrl) {
            String c = cause != null && cause.length() > 1000 ? cause.substring(0, 1000) + "…" : cause;
            return body(facts, what, c, none, true);
        }
        int room = maxUrl - base, n = blocks.size();
        int[] need = new int[n], share = new int[n];
        boolean[] fixed = new boolean[n];
        for (int i = 0; i < n; i++) {
            need[i] = cost(blocks.get(i).lines);
        }
        // a block that needs less than its part takes only that; the others share what is left by their weights
        while (true) {
            int left = room, weights = 0;
            for (int i = 0; i < n; i++) {
                if (fixed[i]) {
                    left -= share[i];
                } else {
                    weights += blocks.get(i).weight;
                }
            }
            if (weights == 0) {
                break;
            }
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                if (!fixed[i] && (long) need[i] * weights <= (long) left * blocks.get(i).weight) {
                    share[i] = need[i];
                    fixed[i] = true;
                    changed = true;
                }
            }
            if (!changed) {
                for (int i = 0; i < n; i++) {
                    if (!fixed[i]) {
                        share[i] = (int) ((long) left * blocks.get(i).weight / weights);
                        fixed[i] = true;
                    }
                }
                break;
            }
        }
        List<Block> fit = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            fit.add(blocks.get(i).with(fitStderr(blocks.get(i).lines, share[i])));
        }
        while (true) {
            String b = body(facts, what, cause, fit, true);
            int biggest = -1;
            for (int i = 0; i < n; i++) {
                if (!fit.get(i).lines.isEmpty() && (biggest < 0 || fit.get(i).lines.size() > fit.get(biggest).lines.size())) {
                    biggest = i;
                }
            }
            if (issueUrl(repo, title, b).length() <= maxUrl || biggest < 0) {
                return b;
            }
            // (the costs add up exactly; this is only a guard)
            List<String> l = fit.get(biggest).lines;
            fit.set(biggest, fit.get(biggest).with(l.subList(1, l.size())));
        }
    }
}
