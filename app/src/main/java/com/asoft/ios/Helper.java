package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The PC installer's helper VM (ios-sweet installer/helper: arm64 Linux with apfs-fuse, hfsprogs,
 * rsync, python3, sgdisk) in this app's QEMU: what Build-iOSDisk.ps1 does on Windows. The job comes on
 * the kernel command line (asoft.job=NAME runs /asoft/job-NAME.sh), the disks by virtio serial; the
 * console says "ASOFT: progress N text", then "ASOFT: done RC" and the VM powers off. Image.gz and
 * initrd.gz come with the app (assets/helper). Its RAM follows the phone's (ramMiB); a failed run keeps how
 * it ended and its logs' ends for the setup's record (failuresSince, SetupRecord).
 */
final class Helper {
    private Helper() {}

    /** A disk for the job: its serial (ipsw, root, scratch, in, out, ...), the file, its format. */
    static final class Disk {
        final String serial;
        final File file;
        final String format;
        final boolean readOnly;
        /** A disk that is somebody's (a user disk): its writes are flushed, as when iOS itself runs on it. */
        final boolean flushed;

        Disk(String serial, File file, String format, boolean readOnly) {
            this(serial, file, format, readOnly, false);
        }

        Disk(String serial, File file, String format, boolean readOnly, boolean flushed) {
            this.serial = serial;
            this.file = file;
            this.format = format;
            this.readOnly = readOnly;
            this.flushed = flushed;
        }
    }

    interface Listener {
        /** A progress line of the job (0-100, its text). */
        void progress(int pct, String text);

        /** Any other ASOFT: line (a warning, a disk, a check). */
        void line(String text);
    }

    static final class Result {
        boolean nat;            // it ran with the native EL0 engine
        int rc = -1;            // the job's exit code ("ASOFT: done RC"), -1 when the VM ended without one
        long outBytes = -1;     // job-run.sh's "ASOFT: out BYTES"
        long seconds;
        String last = "";       // the last ASOFT: line, for the app's log
        String job = "";
        /** The guest RAM it ran with (ramMiB()). */
        int ramMiB;
        /** QEMU's raw wait status when it ended by itself (Render.endStatus), -1 when the app ended it or did not see it. */
        int status = -1;
        /** Set when the app ended it as hung: why. */
        String hung;
        /** The text of its last progress line ("unpacking the iOS images (7137 MB)"), and the last line its scripts printed. */
        String progress = "", output = "";
        long startedMs, endedMs;
        /** The lowest MemAvailable seen while it ran and QEMU's largest resident set, in kB; -1 when not read. */
        long lowestAvailKb = -1, peakRssKb = -1;
        /** On a failure: the end of its console (serial.txt) and of QEMU's stderr, read before the job folder goes. */
        List<String> console = new ArrayList<>(), stderr = new ArrayList<>();
        String cmdline = "";

        /** Why it failed, for the setup's error line (CrashText.helperFailure). */
        String failure() {
            return CrashText.helperFailure(rc, status, hung, progress, output);
        }

        /** Ended by signal 9 that the app did not send: Android's memory killer. */
        boolean killedForMemory() {
            return hung == null && rc == -1 && CrashText.killedForMemory(status);
        }

        /** "native EL0, 4096 MiB": how it ran, for the records. */
        String how() {
            return (nat ? "native EL0" : "TCG") + ", " + ramMiB + " MiB";
        }
    }

    /**
     * The failed runs of the last minutes, newest last (at most 4), with what they left: a setup step that fails
     * writes them into its record (SetupRecord), the native attempt and its TCG retry both. Its own lock.
     */
    private static final ArrayDeque<Result> failures = new ArrayDeque<>();

    /** The failed runs that ended at or after @sinceMs. */
    static List<Result> failuresSince(long sinceMs) {
        synchronized (failures) {
            List<Result> out = new ArrayList<>();
            for (Result r : failures) {
                if (r.endedMs >= sinceMs) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    /**
     * Set while the phone is short of memory for the helper VM and the app could not free any (ramMiB(),
     * beforeJob): the setup screens show it as one calm line. Null otherwise.
     */
    static volatile String memoryNote;

    // -- its RAM

    /**
     * The least a job may run with. job-run.sh keeps /work, the job's files, in RAM (a tmpfs of 75 % of it) and
     * the iOS 26 scripts keep their Linux side in /root/ios26 (the initramfs: RAM too); the biggest is the
     * shared build, which holds the shell ramdisk (rd-shell.hfs, 512 MB with ~350 MB written) twice (in
     * /root/ios26/rd and its copy in /work), two 43 MB kernels and the sysroot twice, on top of the 101 MB
     * initramfs and apfs-fuse, rsync and python: ~1.2 GB that cannot be paged out (the VM has no swap). So the
     * job-run jobs (iOS 26, watchOS, the screen check) get at least 2 GiB. iOS 16's jobs keep nothing big in
     * RAM (build-root works on its scratch disk, payload's /work holds the kernel and payload.bin, ~200 MB):
     * 1.5 GiB. Read from the scripts (2026-11-12), not measured below 4 GiB.
     */
    static final int FLOOR_WORK_IN_RAM = 2048, FLOOR_IOS16 = 1536;
    /** The sizes, largest first: a retry after Android's memory killer steps down one (smaller()). */
    private static final int[] SIZES = {4096, 3072, 2048, 1536};

    /**
     * The helper VM's guest RAM for this phone, at least @floorMiB. Its guest RAM is a memfd the phone has to
     * hold whole once the guest has touched it, and a long copy does that (Linux's page cache fills what it is
     * given: "unpacking the iOS images", 7 GB written, filled all 4 GiB): before 0.1.5 every phone gave it 4 GiB,
     * and on 4 to 8 GB phones Android's memory killer ended it half way. By MemTotal (what a phone reports is a
     * little under its label: an "8 GB" one ~7.3-7.6 GB):
     *     10.5 GB and up (12 GB phones and more)   4096 MiB   as measured on the S25 Ultra, every job
     *      7   GB and up (8 GB)                     3072 MiB
     *      5   GB and up (6 GB)                     2048 MiB
     *      below (4 GB)                             1536 MiB  (iOS 16's jobs only; the floor lifts the others)
     * never above 4096. Debug builds: files/helper-ram-mib (a number) overrides it, to try the small sizes on a big phone.
     */
    static int ramMiB(Context c, int floorMiB) {
        if ((c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            File o = new File(c.getFilesDir(), "helper-ram-mib");
            if (o.isFile()) {
                try {
                    int v = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(o.toPath()), StandardCharsets.US_ASCII).trim());
                    return Math.max(512, Math.min(4096, v));
                } catch (IOException | NumberFormatException e) {
                    // a bad override: the phone's size
                }
            }
        }
        return sizeFor(phoneRam(c), floorMiB);
    }

    /** The table above: the size for a phone with @memTotal bytes, at least @floorMiB, at most 4096. */
    static int sizeFor(long memTotal, int floorMiB) {
        int mib = memTotal >= 10_500_000_000L ? 4096 : memTotal >= 7_000_000_000L ? 3072 : memTotal >= 5_000_000_000L ? 2048 : 1536;
        return Math.min(4096, Math.max(floorMiB, mib));
    }

    /** One size below @mib, not below @floorMiB (@mib itself when it is there already). */
    static int smaller(int mib, int floorMiB) {
        for (int s : SIZES) {
            if (s < mib) {
                return Math.max(floorMiB, s);
            }
        }
        return Math.max(floorMiB, mib);
    }

    /**
     * Whether a failed job gets the one retry the setups give: after a failure with the native engine (new for
     * Linux, 2026-11-08) and after Android's memory killer while a smaller size is left. The retry runs under TCG
     * with retryRam().
     */
    static boolean worthRetry(Result r, int floorMiB) {
        return r.rc != 0 && (r.nat || r.killedForMemory() && smaller(r.ramMiB, floorMiB) < r.ramMiB);
    }

    /** The retry's RAM: one size less after the memory killer, else the same. */
    static int retryRam(Result r, int floorMiB) {
        return r.killedForMemory() ? smaller(r.ramMiB, floorMiB) : r.ramMiB;
    }

    /** The phone's RAM in bytes (MemTotal). */
    static long phoneRam(Context c) {
        long kb = CrashReport.memInfoKb("MemTotal");
        if (kb > 0) {
            return kb * 1024;
        }
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        c.getSystemService(android.app.ActivityManager.class).getMemoryInfo(mi);
        return mi.totalMem;
    }

    /** The memory Android can still give (MemAvailable), in bytes. */
    static long available(Context c) {
        long kb = CrashReport.memInfoKb("MemAvailable");
        if (kb > 0) {
            return kb * 1024;
        }
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        c.getSystemService(android.app.ActivityManager.class).getMemoryInfo(mi);
        return mi.availMem;
    }

    /**
     * What a VM of @mib takes from the phone at most: its RAM, QEMU's translation cache (tbSizeMiB) and ~256 MB
     * of QEMU itself and the native engine's stubs.
     */
    private static long needBytes(int mib) {
        return ((long) mib + tbSizeMiB(mib) + 256) << 20;
    }

    /**
     * TCG's translation cache: 512 MiB as on the PC for the 4 GiB VM; a smaller VM on a smaller phone 256 (the
     * buffer fills over a long job and stays: it is the phone's memory too). A smaller cache translates again
     * more often, not wrongly.
     */
    private static int tbSizeMiB(int mib) {
        return mib >= 4096 ? 512 : 256;
    }

    /**
     * Before a VM of @mib starts: when MemAvailable is under what it takes, one round of the app's memory
     * freeing (MemoryCleaner, when the user allows it), else memoryNote for the setup screens. Never blocks the job.
     */
    private static void beforeJob(Context c, int mib) {
        long need = needBytes(mib), avail = available(c);
        if (avail >= need) {
            memoryNote = null;
            return;
        }
        if (MemoryCleaner.beforeHelper(c)) {
            avail = available(c);
        }
        if (avail >= need) {
            memoryNote = null;
            return;
        }
        // (the user's switch decides whether the app frees memory: the line says how to allow it, or that it did)
        memoryNote = MemoryCleaner.enabled(c)
                ? "This phone is short of memory for the build, even after the app freed some. Closing other apps may help."
                : MemoryCleaner.supported(c)
                ? "This phone is short of memory for the build. Turn on Free memory for iOS in Settings on the home screen"
                        + " so the app can free memory first, or close other apps."
                : "This phone is short of memory for the build. Pair Wireless debugging in Settings on the home screen"
                        + " (Free memory for iOS) so the app can free memory first, or close other apps.";
        AppLog.note(c, String.format(java.util.Locale.ROOT, "helper: short of memory: %.1f GB available, the VM takes up to %.1f GB",
                avail / 1e9, need / 1e9));
    }

    /**
     * Asked twice a second while a job runs: true holds the VM (SIGSTOP to QEMU and its native-engine stub
     * processes, SIGCONT once it says false). The setup sets it to its thermal check: a hot phone pauses a
     * build rather than stopping it (a job cannot be resumed half way, but a held one goes on as it was).
     */
    static volatile BooleanSupplier hold;
    static volatile boolean held;

    /** @pid and its children (the native EL0 engine's stubs), by /proc's parent links. */
    private static List<Integer> family(int pid) {
        List<Integer> out = new ArrayList<>();
        out.add(pid);
        File[] procs = new File("/proc").listFiles();
        if (procs != null) {
            for (File p : procs) {
                String n = p.getName();
                if (n.isEmpty() || !Character.isDigit(n.charAt(0))) {
                    continue;
                }
                try {
                    String st = new String(java.nio.file.Files.readAllBytes(new File(p, "stat").toPath()), StandardCharsets.US_ASCII);
                    String[] f = st.substring(st.lastIndexOf(')') + 2).split(" ");
                    if (Integer.parseInt(f[1]) == pid) {
                        out.add(Integer.parseInt(n));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // gone, or another app's
                }
            }
        }
        return out;
    }

    /** The CPU time @pid and its stubs have used so far, in clock ticks; -1 when /proc does not say. */
    private static long cpuTicks(int pid) {
        long sum = 0;
        boolean any = false;
        for (int p : family(pid)) {
            try {
                String st = new String(java.nio.file.Files.readAllBytes(new File("/proc/" + p + "/stat").toPath()), StandardCharsets.US_ASCII);
                String[] f = st.substring(st.lastIndexOf(')') + 2).split(" ");
                sum += Long.parseLong(f[11]) + Long.parseLong(f[12]);      // utime, stime
                any = true;
            } catch (IOException | RuntimeException ignored) {
                // gone
            }
        }
        return any ? sum : -1;
    }

    /**
     * A VM that hangs: no console output and next to no CPU time (under a second) in each of this many checks
     * in a row, half a minute apart. A job that works always burns CPU, even one that prints nothing for half
     * an hour (the rootfs copy). The VM is ended then, which the caller sees as a failed job: natively it runs
     * again under TCG (PhoneSetup26.runJob). Before 0.1.5 the setup waited on a hung VM for ever.
     */
    private static final int HUNG_CHECKS = 10;
    private static final long CHECK_MS = 30_000;

    private static void signal(int pid, int sig) {
        for (int p : family(pid)) {
            android.os.Process.sendSignal(p, sig);
        }
    }

    private static final Pattern PROGRESS = Pattern.compile("^ASOFT: progress (\\d+) (.*)$");
    private static final Pattern DONE = Pattern.compile("^ASOFT: done (-?\\d+)");
    private static final Pattern OUT = Pattern.compile("^ASOFT: out (\\d+)");

    /**
     * The native EL0 engine for the helper's Linux userland: the phone's CPU has what it needs
     * (Guest.nativeMissing) and the setting is on. It runs with Image16k.gz, a kernel with 16 KiB
     * pages and a 36-bit user address space, so Linux's user space falls inside the engine's window
     * (4-64 GiB, made for XNU); the PC's Image.gz (4 KiB, 48-bit) puts it at 0xaaaa... and 0xffff...
     */
    static boolean useNative(Context c) {
        return Guest.nativeMissing().isEmpty()
                && c.getSharedPreferences("setup", Context.MODE_PRIVATE).getBoolean("helper-native", true);
    }

    /** The phone's counter frequency (CNTFRQ_EL0), which the guest gets with x-host-counter. */
    static long hostCounterHz() {
        return 19_200_000L;     // every Snapdragon since the 8 Gen 1; the S25 Ultra's (2026-11-08)
    }

    /** The helper's kernels and initramfs in files/helper, copied from the app's assets when they changed. */
    static File[] files(Context c) throws IOException {
        File d = new File(c.getFilesDir(), "helper");
        d.mkdirs();
        File[] out = {new File(d, "Image.gz"), new File(d, "initrd.gz"), new File(d, "Image16k.gz")};
        for (File f : out) {
            long want;
            // stored as NAME.bin: the Android build would un-gzip an asset named *.gz (pull-setup.sh)
            String asset = "helper/" + f.getName() + ".bin";
            try (android.content.res.AssetFileDescriptor a = c.getAssets().openFd(asset)) {
                want = a.getLength();
            }
            if (f.length() != want) {
                File part = new File(d, f.getName() + ".part");
                try (InputStream in = c.getAssets().open(asset)) {
                    TarIo.copy(in, part);
                }
                if (!part.renameTo(f)) {
                    throw new IOException("cannot install " + f);
                }
            }
        }
        return out;
    }

    /** A comma in a -drive file name must be doubled (an IPSW's name has one: iPhone10,3_...). */
    private static String escape(String p) {
        return p.replace(",", ",,");
    }

    /**
     * Runs job @job with @disks; @dir gets serial.txt, qemu-stderr.txt and cmdline.txt. Blocks until the
     * VM ends or @cancelled says so (then QEMU is stopped and an IOException thrown).
     */
    static Result run(Context c, String job, List<Disk> disks, int cpus, File dir, Listener l, BooleanSupplier cancelled)
            throws IOException {
        return run(c, job, disks, cpus, dir, l, cancelled, true);
    }

    /** As run() above; @allowNative false keeps the job under TCG (the retry after a failure natively). */
    static Result run(Context c, String job, List<Disk> disks, int cpus, File dir, Listener l, BooleanSupplier cancelled,
            boolean allowNative) throws IOException {
        return run(c, job, disks, cpus, dir, l, cancelled, allowNative, ramMiB(c, FLOOR_WORK_IN_RAM));
    }

    /** As run() above, with @ramMiB of guest RAM (ramMiB(), or smaller() for the retry after the memory killer). */
    static Result run(Context c, String job, List<Disk> disks, int cpus, File dir, Listener l, BooleanSupplier cancelled,
            boolean allowNative, int ramMiB) throws IOException {
        File[] k = files(c);
        dir.mkdirs();
        File serial = new File(dir, "serial.txt");
        serial.delete();
        boolean nat = allowNative && useNative(c);
        // (-m and the memfd's size are one number: the memfd is the guest's RAM)
        List<String> cmd = new ArrayList<>(List.of(Emu.qemuPath(c),
                // the RAM a memfd: the native EL0 engine maps it into its stub processes
                "-M", "virt,gic-version=3,memory-backend=m0", "-object", "memory-backend-memfd,id=m0,size=" + ramMiB + "M,share=on",
                // cortex-a76, not max: pointer authentication, which max has, is slow under TCG (Build-iOSDisk.ps1).
                // Natively the guest reads the phone's counter: the same counter and frequency for its kernel.
                "-cpu", nat ? "cortex-a76,x-host-counter=on,cntfrq=" + hostCounterHz() : "cortex-a76",
                "-smp", String.valueOf(cpus), "-m", ramMiB + "M",
                "-accel", "tcg,thread=multi,tb-size=" + tbSizeMiB(ramMiB),
                "-kernel", (nat ? k[2] : k[0]).getPath(), "-initrd", k[1].getPath(),
                "-append", "console=ttyAMA0 loglevel=4 asoft.job=" + job,
                "-display", "none", "-monitor", "none", "-parallel", "none", "-no-reboot", "-nic", "none",
                "-serial", "file:" + serial.getPath()));
        for (Disk d : disks) {
            // cache=unsafe: no flushes while a disk is built; a crash means building it again anyway
            cmd.add("-drive");
            cmd.add("file=" + escape(d.file.getPath()) + ",format=" + d.format + ",if=none,id=" + d.serial
                    + (d.readOnly ? ",readonly=on" : d.flushed ? ",cache=writeback" : ",cache=unsafe"));
            cmd.add("-device");
            cmd.add("virtio-blk-device,drive=" + d.serial + ",serial=" + d.serial);
        }
        Map<String, String> env = new java.util.HashMap<>(System.getenv());
        Emu.baseEnv(c, Emu.linkLibraries(c), env);
        if (nat) {
            env.put("ASOFT_NATIVE_EL0", "1");
            env.put("ASOFT_NATIVE_EL0_STUBS", "8");
            env.put("ASOFT_NATIVE_EL0_DELAY_MS", "0");
            // Ubuntu's binaries sign return addresses (paciasp/autiasp: NOPs on the guest's cortex-a76); the
            // phone's CPU would sign them with each stub process's own key, and a forked child in another
            // stub could not authenticate them (2026-11-08)
            env.put("ASOFT_NATIVE_EL0_PAC", "off");
            env.put("ASOFT_NATIVE_EL0_STATS", "1");
        }
        String cmdline = String.join(" ", cmd);
        try (PrintWriter w = new PrintWriter(new File(dir, "cmdline.txt"))) {
            w.println(cmdline);
        }
        List<String> envp = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            envp.add(e.getKey() + "=" + e.getValue());
        }
        // a helper of an app process that died (killed by Android, or the app updated) may still run, on
        // its own session: it would build into files this run is about to make again
        File pidFile = new File(dir, "pid");
        killLeftover(c, pidFile);
        beforeJob(c, ramMiB);
        AppLog.note(c, String.format(java.util.Locale.ROOT, "helper run: %s of RAM (phone has %.1f GB, %.1f GB available)",
                ramMiB % 1024 == 0 ? ramMiB / 1024 + " GB" : String.format(java.util.Locale.ROOT, "%.1f GB", ramMiB / 1024.0),
                phoneRam(c) / 1e9, available(c) / 1e9));
        long t0 = System.currentTimeMillis();
        int pid = Render.spawn(cmd.toArray(new String[0]), envp.toArray(new String[0]), new File(dir, "qemu-stderr.txt").getPath(),
                c.getFilesDir().getPath());
        if (pid < 0) {
            throw new IOException("cannot start the helper VM");
        }
        try (PrintWriter w = new PrintWriter(pidFile)) {
            w.println(pid);
        }
        AppLog.note(c, "helper " + job + ": QEMU pid " + pid + ", " + cpus + " vCPUs, " + (nat ? "native EL0" : "TCG") + ", " + ramMiB + " MiB");
        // a death of the app's process while this runs: its record reads this folder's logs (AppExit)
        AppExit.helper(c, dir);
        Result r = new Result();
        r.nat = nat;
        r.job = job;
        r.ramMiB = ramMiB;
        r.startedMs = t0;
        r.cmdline = cmdline;
        long at = 0;
        StringBuilder partial = new StringBuilder();
        boolean alive = true;
        long checkedAt = android.os.SystemClock.elapsedRealtime(), cpuWas = -1, serialWas = 0, memAt = 0;
        int quiet = 0;
        try {
            while (true) {
                alive = Render.alive(pid);
                at = follow(serial, at, partial, l, r);
                if (!alive) {
                    r.status = Render.endStatus(pid);       // the alive() just above reaped it
                    break;
                }
                long now = android.os.SystemClock.elapsedRealtime();
                // what the phone has left and what QEMU holds, for a record of an end by the memory killer
                if (now - memAt >= 5000) {
                    memAt = now;
                    long a = CrashReport.memInfoKb("MemAvailable"), rss = rssKb(pid);
                    if (a >= 0) {
                        r.lowestAvailKb = r.lowestAvailKb < 0 ? a : Math.min(r.lowestAvailKb, a);
                    }
                    r.peakRssKb = Math.max(r.peakRssKb, rss);
                }
                // the hang watch (HUNG_CHECKS): only checks made on time count, so that a phone that slept, or an
                // app Android froze together with its QEMU, does not read as a hang; a held VM starts over
                if (held || now - checkedAt > 2 * CHECK_MS) {
                    checkedAt = now;
                    quiet = 0;
                    cpuWas = cpuTicks(pid);
                    serialWas = serial.length();
                } else if (now - checkedAt >= CHECK_MS) {
                    long cpu = cpuTicks(pid), len = serial.length();
                    quiet = cpu >= 0 && cpuWas >= 0 && cpu - cpuWas < 100 && len == serialWas ? quiet + 1 : 0;
                    checkedAt = now;
                    cpuWas = cpu;
                    serialWas = len;
                    if (quiet >= HUNG_CHECKS) {
                        AppLog.note(c, "helper " + job + ": no output and no CPU time for " + quiet * CHECK_MS / 60_000
                                + " min: taken for hung, ended");
                        Render.forceKill(pid);
                        for (int i = 0; i < 50 && Render.alive(pid); i++) {
                            Thread.sleep(100);
                        }
                        r.rc = -1;
                        r.hung = "the helper VM stopped responding (no output and no CPU time for "
                                + quiet * CHECK_MS / 60_000 + " minutes)";
                        r.last = r.hung;
                        break;
                    }
                }
                BooleanSupplier h = hold;
                boolean want = h != null && h.getAsBoolean();
                if (want != held) {
                    held = want;
                    signal(pid, want ? 19 : 18);        // SIGSTOP, SIGCONT
                    AppLog.note(c, "helper " + job + ": " + (want ? "held (the phone is hot)" : "goes on"));
                }
                if (cancelled.getAsBoolean()) {
                    if (held) {
                        signal(pid, 18);
                        held = false;
                    }
                    Render.terminate(pid);
                    for (int i = 0; i < 50 && Render.alive(pid); i++) {
                        Thread.sleep(100);
                    }
                    Render.forceKill(pid);
                    throw new IOException("stopped");
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Render.forceKill(pid);
            Thread.currentThread().interrupt();
            throw new IOException("stopped");
        } finally {
            AppExit.helper(c, null);
        }
        pidFile.delete();
        r.endedMs = System.currentTimeMillis();
        r.seconds = (r.endedMs - t0) / 1000;
        if (r.rc != 0) {
            // what says why, before the caller deletes the job folder or a retry writes over it
            r.console = CrashReport.tail(serial, 150);
            r.stderr = CrashReport.stderrEvidence(new File(dir, "qemu-stderr.txt"));
            synchronized (failures) {
                failures.addLast(r);
                while (failures.size() > 4) {
                    failures.removeFirst();
                }
            }
        }
        AppLog.note(c, "helper " + job + ": rc " + r.rc + " in " + r.seconds + " s" + (r.rc != 0 ? " (" + r.failure() + "; QEMU "
                + CrashText.describeStatus(r.status) + ", lowest MemAvailable " + r.lowestAvailKb / 1024 + " MB, QEMU's RSS up to "
                + r.peakRssKb / 1024 + " MB)" : ""));
        return r;
    }

    /** QEMU's resident set (VmRSS, the guest RAM it has touched with it), in kB; -1 when /proc does not say. */
    private static long rssKb(int pid) {
        try {
            for (String l : java.nio.file.Files.readAllLines(new File("/proc/" + pid + "/status").toPath(), StandardCharsets.US_ASCII)) {
                if (l.startsWith("VmRSS:")) {
                    return Long.parseLong(l.substring(6).trim().split("\\s+")[0]);
                }
            }
        } catch (IOException | RuntimeException e) {
            // gone
        }
        return -1;
    }

    /** Ends a helper VM a dead app process left running for job folder @dir (call before its files are made again). */
    static void endLeftover(Context c, File dir) {
        killLeftover(c, new File(dir, "pid"));
    }

    /** Ends the helper QEMU @pidFile names when it is still there (a process of this app, a helper's command line). */
    private static void killLeftover(Context c, File pidFile) {
        if (!pidFile.isFile()) {
            return;
        }
        try {
            int old = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(pidFile.toPath()), StandardCharsets.US_ASCII).trim());
            byte[] cl = java.nio.file.Files.readAllBytes(new File("/proc/" + old + "/cmdline").toPath());
            if (new String(cl, StandardCharsets.ISO_8859_1).contains("asoft.job=")) {
                android.os.Process.killProcess(old);       // SIGKILL; this app's own uid
                AppLog.note(c, "helper: ended the leftover helper VM " + old + " of an app process before");
                for (int i = 0; i < 50 && new File("/proc/" + old).exists(); i++) {
                    Thread.sleep(100);
                }
            }
        } catch (IOException | NumberFormatException e) {
            // gone already, or not ours
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pidFile.delete();
    }

    /** Reads what the console file has after @at; returns the new end. */
    private static long follow(File f, long at, StringBuilder partial, Listener l, Result r) throws IOException {
        if (!f.isFile() || f.length() <= at) {
            return at;
        }
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            in.seek(at);
            byte[] b = new byte[(int) Math.min(1 << 20, f.length() - at)];
            in.readFully(b);
            at += b.length;
            partial.append(new String(b, StandardCharsets.UTF_8));
        }
        int nl;
        while ((nl = partial.indexOf("\n")) >= 0) {
            String line = partial.substring(0, nl).replace("\r", "");
            partial.delete(0, nl + 1);
            int i = line.indexOf("ASOFT: ");
            if (i < 0) {
                // the scripts' own words (a failing command's, under set -e) and the kernel's (its OOM killer)
                // (not after "ASOFT: done": the power-off's own lines)
                if (!line.trim().isEmpty() && r.rc == -1) {
                    r.output = line.trim().length() > 300 ? line.trim().substring(0, 300) : line.trim();
                }
                continue;
            }
            line = line.substring(i);
            Matcher m;
            if ((m = PROGRESS.matcher(line)).find()) {
                r.progress = m.group(2).trim();
                l.progress(Integer.parseInt(m.group(1)), m.group(2));
            } else if ((m = DONE.matcher(line)).find()) {
                r.rc = Integer.parseInt(m.group(1));
            } else if ((m = OUT.matcher(line)).find()) {
                r.outBytes = Long.parseLong(m.group(1));
            } else {
                l.line(line.substring(7));
            }
            if (!line.startsWith("ASOFT: done")) {
                r.last = line.substring(7);
            }
        }
        return at;
    }
}
