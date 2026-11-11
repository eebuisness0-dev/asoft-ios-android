package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
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
 * initrd.gz come with the app (assets/helper).
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
        String last = "";       // the last ASOFT: line, for an error message
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
        File[] k = files(c);
        dir.mkdirs();
        File serial = new File(dir, "serial.txt");
        serial.delete();
        boolean nat = allowNative && useNative(c);
        List<String> cmd = new ArrayList<>(List.of(Emu.qemuPath(c),
                // the RAM a memfd: the native EL0 engine maps it into its stub processes
                "-M", "virt,gic-version=3,memory-backend=m0", "-object", "memory-backend-memfd,id=m0,size=4G,share=on",
                // cortex-a76, not max: pointer authentication, which max has, is slow under TCG (Build-iOSDisk.ps1).
                // Natively the guest reads the phone's counter: the same counter and frequency for its kernel.
                "-cpu", nat ? "cortex-a76,x-host-counter=on,cntfrq=" + hostCounterHz() : "cortex-a76",
                "-smp", String.valueOf(cpus), "-m", "4G",
                "-accel", "tcg,thread=multi,tb-size=512",
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
        try (PrintWriter w = new PrintWriter(new File(dir, "cmdline.txt"))) {
            w.println(String.join(" ", cmd));
        }
        List<String> envp = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            envp.add(e.getKey() + "=" + e.getValue());
        }
        // a helper of an app process that died (killed by Android, or the app updated) may still run, on
        // its own session: it would build into files this run is about to make again
        File pidFile = new File(dir, "pid");
        killLeftover(c, pidFile);
        long t0 = System.currentTimeMillis();
        int pid = Render.spawn(cmd.toArray(new String[0]), envp.toArray(new String[0]), new File(dir, "qemu-stderr.txt").getPath(),
                c.getFilesDir().getPath());
        if (pid < 0) {
            throw new IOException("cannot start the helper VM");
        }
        try (PrintWriter w = new PrintWriter(pidFile)) {
            w.println(pid);
        }
        AppLog.note(c, "helper " + job + ": QEMU pid " + pid + ", " + cpus + " vCPUs, " + (nat ? "native EL0" : "TCG"));
        Result r = new Result();
        r.nat = nat;
        long at = 0;
        StringBuilder partial = new StringBuilder();
        boolean alive = true;
        long checkedAt = android.os.SystemClock.elapsedRealtime(), cpuWas = -1, serialWas = 0;
        int quiet = 0;
        try {
            while (true) {
                alive = Render.alive(pid);
                at = follow(serial, at, partial, l, r);
                if (!alive) {
                    break;
                }
                // the hang watch (HUNG_CHECKS): only checks made on time count, so that a phone that slept, or an
                // app Android froze together with its QEMU, does not read as a hang; a held VM starts over
                long now = android.os.SystemClock.elapsedRealtime();
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
                        r.last = "the helper VM stopped responding (no output and no CPU time for "
                                + quiet * CHECK_MS / 60_000 + " minutes)";
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
        }
        pidFile.delete();
        r.seconds = (System.currentTimeMillis() - t0) / 1000;
        AppLog.note(c, "helper " + job + ": rc " + r.rc + " in " + r.seconds + " s" + (r.rc != 0 ? " (" + r.last + ")" : ""));
        return r;
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
                continue;
            }
            line = line.substring(i);
            Matcher m;
            if ((m = PROGRESS.matcher(line)).find()) {
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
