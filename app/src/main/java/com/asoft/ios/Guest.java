package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The iOS 16 machine, as qemu-sweet.py builds it, and its one QEMU process. The guest's files
 * (ram.bin and stub.bin from qemu-sweet.py, the root disk) are in a directory the app can read.
 */
final class Guest {
    static final long RAM_BASE = 0x40000000L;
    static final long FB_BASE = 0x9c000000L;        // qemu-sweet.py FB_BASE: the guest's ramfb framebuffer
    static final int FB_W = 1080, FB_H = 2400, FB_STRIDE = FB_W * 4;
    static final long STUB_PA = 0x40200000L;
    static final long KERNEL_PA = GuestImage.KERNEL_PA;     // ram.bin's load address for this guest

    static final long RAM_SIZE = 7L << 30;

    // -- which iOS: 16 (-M virt, ramfb, no GPU) or 26 (-M vresearch, the phone's GPU through reims)

    /** WATCH: watchOS 27.2 on iOS 26's machine (WatchProfile), with its own disk, screen and buttons. */
    static final int IOS16 = 16, IOS26 = 26, WATCH = 27;

    /** What the screens call @os. */
    static String osName(int os) {
        return os == WATCH ? "watchOS" : "iOS " + os;
    }
    /** The iOS of the QEMU that runs, or ran last. */
    private static volatile int runningOs = IOS16;

    /** The iOS the user chose last (the one Start, Restart and the notification start). */
    static int selected(Context c) {
        return c.getSharedPreferences("guest", Context.MODE_PRIVATE).getInt("os", IOS16);
    }

    static void select(Context c, int os) {
        c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit().putInt("os", os).apply();
    }

    /** The iOS the screens are about: the running one, else the one chosen last. */
    static int os(Context c) {
        return running() ? runningOs : selected(c);
    }

    /** A -M vresearch guest runs (iOS 26 or watchOS): its frames, buttons and end are iOS 26's. */
    static boolean ios26Running() {
        return runningOs == IOS26 || runningOs == WATCH;
    }

    static boolean watchRunning() {
        return runningOs == WATCH;
    }

    /** iOS 26's screen: the console surface reims reads each frame back into (ASOFT_FRAME_FD). */
    static final int FB26_W = 1179, FB26_H = 2556;
    /** The running iOS 26's screen: FB26_W x FB26_H (@3x), or what ios26.txt's fb= set (786x1704 @2x). */
    static int fb26W = FB26_W, fb26H = FB26_H;

    static int fbW() {
        return ios26Running() ? fb26W : FB_W;
    }

    static int fbH() {
        return ios26Running() ? fb26H : FB_H;
    }

    /** Maps the running guest's screen for Render.frame(); null when done, else why not. */
    static String openScreen(int forPid) {
        return ios26Running() ? Render.openFramebuffer(forPid, -1, fb26W, fb26H, fb26W * 4)
                : Render.openFramebuffer(forPid, FB_BASE - RAM_BASE, FB_W, FB_H, FB_STRIDE);
    }

    /**
     * One of the iPhone's buttons (home, power, volup, voldown), @down or up: held until the up, so
     * iOS repeats a held volume button. iOS 16 has them as keys F1-F4 (bsd/dev/sweet_vinput.c);
     * iOS 26 as QEMU's usb-consumer device's down/up (IOS26-INPUT §2.6). An up comes at least
     * MIN_HOLD_MS after its down: iOS reads a button only once it has been down.
     */
    static void button(String b, boolean down) {
        input();
        if (b.equals("power")) {
            lastPowerMs = lastInputMs;
        }
        Qmp q = qmp;
        if (q == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (down) {
            held.put(b, now);
            buttonEdge(q, b, true);
            return;
        }
        Long since = held.remove(b);
        long wait = since == null ? 0 : MIN_HOLD_MS - (now - since);
        if (wait <= 0) {
            buttonEdge(q, b, false);
        } else {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ignored) {
                    // up at once
                }
                buttonEdge(q, b, false);
            }, "button-up");
            t.setDaemon(true);
            t.start();
        }
    }

    private static final long MIN_HOLD_MS = 80;
    /** The buttons down now, with when (uptime ms). */
    private static final Map<String, Long> held = new java.util.concurrent.ConcurrentHashMap<>();

    private static void buttonEdge(Qmp q, String b, boolean down) {
        if (ios26Running()) {
            q.consumer(down ? "down" : "up", b);
        } else {
            q.keyEvent(b.equals("home") ? "f1" : b.equals("power") ? "f2" : b.equals("voldown") ? "f3" : "f4", down);
        }
    }

    /** Lets go of every button held (the screen lost the keys: their ups will not come). */
    static void releaseButtons() {
        for (String b : held.keySet()) {
            button(b, false);
        }
    }

    /** When the user last touched iOS or pressed one of its buttons (uptime ms): AutoSave waits for a quiet moment. */
    static volatile long lastInputMs = android.os.SystemClock.uptimeMillis();

    static void input() {
        lastInputMs = android.os.SystemClock.uptimeMillis();
    }

    /** When the app last pressed the side button (uptime ms): a display sleep right after it is not iOS's Auto-Lock. */
    static volatile long lastPowerMs;

    /** A press and release of @b. */
    static void button(String b) {
        input();
        if (b.equals("power")) {
            lastPowerMs = lastInputMs;
        }
        Qmp q = qmp;
        if (q == null) {
            return;
        }
        if (ios26Running()) {
            q.press(b);
        } else {
            q.key(b.equals("home") ? "f1" : b.equals("power") ? "f2" : b.equals("voldown") ? "f3" : "f4");
        }
    }

    /** watchOS: turns the Digital Crown by @units (WatchControls). */
    static void crown(int units) {
        input();
        Qmp q = qmp;
        if (q != null && runningOs == WATCH) {
            q.crown(units);
        }
    }

    /**
     * iOS 26's files: brought into the app (files/ios26), else the folder adb pushed them to for
     * the Termux runs (ANDROID-S25.md §8.8), read where they are: the disk chain is 26 GB.
     */
    static final File TEST26_DIR = new File("/data/local/tmp/asoft/ios26");
    static final String DTREE26 = "dtree-root26v-smp4-19m2";    // timebase 19.2 MHz: x-host-counter
    static final String[] PARTS26 = {"bootkc", "sptm", "txm", DTREE26, "root26m.tc", "rd-shell.hfs", "root26r.qcow2"};
    /**
     * iOS 26's Always On Display (APK-PLAN §6.23), optional files beside PARTS26: a layer on root26r with
     * libMobileGestalt's AlwaysOnDisplayFlipbook answer made no (the hardware flipbook asserts on the
     * paravirtual display), its trust cache (root26m.tc + that subcache's CDHash) and the device tree with
     * /arm-io/disp0 always-on-time. A user disk on AOD26_BASE boots with these two; one on root26r without.
     */
    static final String AOD26_BASE = "root26s-aod.qcow2", AOD26_TC = "root26s-aod.tc", DTREE26_AOD = DTREE26 + "-aod";

    /** The AOD files are all in @g: new user disks go on AOD26_BASE. */
    static boolean aodFiles(File g) {
        return new File(g, AOD26_BASE).canRead() && new File(g, AOD26_TC).canRead() && new File(g, DTREE26_AOD).canRead();
    }

    /** The backing file named in a qcow2's header (its own text, maybe relative), null if none or unreadable. */
    static String backingOf(File qcow2) {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(qcow2, "r")) {
            if (f.readInt() != 0x514649fb) {     // "QFI\xfb"
                return null;
            }
            f.seek(8);
            long off = f.readLong();
            int len = f.readInt();
            if (off <= 0 || len <= 0 || len > 1023) {
                return null;
            }
            byte[] b = new byte[len];
            f.seek(off);
            f.readFully(b);
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** The iOS 26 running now has its Always On Display (its disk is on AOD26_BASE): the screen watches for it. */
    static volatile boolean aodRunning;

    /** The user disk is on the AOD base: it boots with the AOD device tree and trust cache. */
    static boolean aodDisk(File userDisk) {
        String b = backingOf(userDisk);
        return b != null && new File(b).getName().equals(AOD26_BASE);
    }

    /**
     * What the phone's own setup (PhoneSetup26) leaves in files/ios26: one packed disk with the Always On Display
     * patch, its trust cache and both trees, no root26r: every user disk goes on AOD26_BASE.
     */
    static final String[] PHONE_PARTS26 = {"bootkc", "sptm", "txm", DTREE26, DTREE26_AOD, AOD26_TC, "rd-shell.hfs", AOD26_BASE};

    static File dir26(Context c) {
        File d = new File(c.getFilesDir(), "ios26");
        return new File(d, "root26r.qcow2").isFile() || new File(d, AOD26_BASE).isFile() ? d : TEST26_DIR;
    }

    /** iOS 26 as the phone's own setup made it (files/ios26/install.json). */
    static boolean phoneMade26(File d) {
        return new File(d, "install.json").isFile() && !new File(d, "root26r.qcow2").isFile();
    }

    /** The first of iOS 26's files this app cannot read, null when all are there. */
    static String missing26(Context c) {
        File d = dir26(c);
        for (String p : phoneMade26(d) ? PHONE_PARTS26 : PARTS26) {
            if (!new File(d, p).canRead()) {
                return p;
            }
        }
        return null;
    }

    static boolean available26(Context c) {
        return missing26(c) == null;
    }

    /** Turnip (Mesa's Adreno Vulkan driver, with host-pointer import over KGSL) for the Vulkan loader. */
    static File writeVulkanIcd(Context c, File links) throws IOException {
        File d = new File(c.getFilesDir(), "vulkan");
        d.mkdirs();
        File json = new File(d, "turnip_icd.json");
        try (PrintWriter w = new PrintWriter(new FileWriter(json))) {
            w.println("{\"file_format_version\": \"1.0.1\", \"ICD\": {\"library_path\": \""
                    + new File(links, "libvulkan_freedreno.so").getPath()
                    + "\", \"api_version\": \"1.4.354\", \"library_arch\": \"64\"}}");
        }
        return json;
    }
    /**
     * The quick-boot state's name inside the user disk (qcow2 internal snapshot). It lives in one of
     * two slots, QUICK and QUICK_B: a new quick save goes into the other slot and the old one is
     * deleted only once the new one is complete. QEMU's savevm deletes a snapshot of the same name
     * before it writes the new one, so a save that died half way (the app killed, the phone full) lost
     * the old state too and the next start booted (2026-11-06). With too little room for two, a save
     * still goes over the old slot.
     */
    static final String QUICK = "asoft-quick";
    static final String QUICK_B = "asoft-quick-b";

    static boolean isQuick(String tag) {
        return QUICK.equals(tag) || QUICK_B.equals(tag);
    }

    /** The newest quick slot in @saves (newest first, as snapshots() gives them), else null. */
    static Snap newestQuick(List<Snap> saves) {
        for (Snap s : saves) {
            if (isQuick(s.tag)) {
                return s;
            }
        }
        return null;
    }

    /** "3.21 GiB" (a VM SIZE of the snapshot list) in bytes, 0 when it cannot be read. */
    static long sizeBytes(String size) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([\\d.]+)\\s*([KMGT]?)i?B?").matcher(size.trim());
        if (!m.find()) {
            return 0;
        }
        double v = Double.parseDouble(m.group(1));
        String u = m.group(2);
        int shift = u.equals("K") ? 10 : u.equals("M") ? 20 : u.equals("G") ? 30 : u.equals("T") ? 40 : 0;
        return (long) (v * (1L << shift));
    }

    /** Why the last start did not happen (no room, ...), for the screens; null once a start is under way. */
    static volatile String startError;
    private static int pid = -1;
    private static volatile Qmp qmp;
    static volatile String state = "stopped";
    /** What a start does before QEMU runs when it takes a while (state "preparing"), for the screen; else null. */
    static volatile String preparing;
    /** True when this QEMU was started from the saved state. */
    static volatile boolean resumed;
    private static final java.util.concurrent.atomic.AtomicBoolean saving = new java.util.concurrent.atomic.AtomicBoolean();

    static File runDir(Context c) {
        File d = new File(c.getFilesDir(), "run");
        d.mkdirs();
        return d;
    }

    static String qmpPath(Context c) {
        return new File(runDir(c), "qmp.sock").getPath();
    }

    /** The adb test folder (debug builds): ram.bin, stub.bin and disk.qcow2 made on the PC. */
    static final File TEST_DIR = new File("/data/local/tmp/asoft/ios16-app");

    /** Where payload.bin (or ram.bin and stub.bin) and disk.qcow2 are: what Set up brought in, else the test folder. */
    static File guestDir(Context c) {
        if (Installer.installed(c)) {
            return Installer.dir(c);
        }
        return TEST_DIR;
    }

    /**
     * What iOS's apps may run that this phone's CPU cannot: the native EL0 engine runs them on
     * the CPU as they are, and the guest CPU (cortex-a76, ARMv8.2) tells iOS it has LSE atomics,
     * RDM, dot product, FP16, RCpc and DC CVAP, so its libraries use them. Without one of them a
     * process would die of an undefined instruction: such a phone (an ARMv8.0 Cortex-A53/A73, say)
     * runs iOS under TCG only. Empty when the CPU has them all.
     */
    static String nativeMissing() {
        String[] need = {"atomics", "asimdrdm", "asimddp", "fphp", "asimdhp", "lrcpc", "dcpop"};
        java.util.Set<String> have = new java.util.HashSet<>();
        try {
            for (String l : java.nio.file.Files.readAllLines(new File("/proc/cpuinfo").toPath())) {
                if (l.startsWith("Features")) {
                    have.addAll(java.util.Arrays.asList(l.substring(l.indexOf(':') + 1).trim().split("\\s+")));
                    break;     // every core of a phone has the same feature list
                }
            }
        } catch (IOException e) {
            return "a readable /proc/cpuinfo";
        }
        StringBuilder m = new StringBuilder();
        for (String f : need) {
            if (!have.contains(f)) {
                m.append(m.length() > 0 ? " " : "").append(f);
            }
        }
        return m.toString();
    }

    /** Something to start: iOS was set up, or a debug build has the test folder. */
    static boolean available(Context c) {
        return Installer.installed(c)
                || (c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && new File(TEST_DIR, "ram.bin").canRead();
    }

    /**
     * What iOS writes (settings, apps, the quick-boot state) goes to this overlay of the
     * read-only root disk; deleting it erases the iPhone.
     */
    static File userDisk(Context c) {
        return userDisk(c, os(c));
    }

    static File userDisk(Context c, int forOs) {
        return new File(c.getFilesDir(), diskName(c, forOs) + ".qcow2");
    }

    /**
     * "ios16-user", "ios26-user", or "ios26-2x-user" for iOS 26 with the 2x screen: iOS keeps its
     * screen's scale in its MobileGestalt cache from the first boot on, so each scale has its own disk
     * (its own iPhone, settings, apps and saves).
     */
    private static String diskName(Context c, int forOs) {
        String[] n = diskNames(forOs);
        return forOs == IOS26 && scale26(c) == 2 ? n[1] : n[0];
    }

    /** Every name diskName gives @forOs: iOS 26 has one per screen scale (3x, then 2x). */
    private static String[] diskNames(int forOs) {
        return forOs == WATCH ? new String[] {"watch-user"} : forOs == IOS26 ? new String[] {"ios26-user", "ios26-2x-user"}
                : new String[] {"ios" + forOs + "-user"};
    }

    /**
     * Every file of @forOs's own among the app's files (its install folder is Installer's): what one of its disk names
     * starts (diskNames: the user disks, one an install kept as .old, the home screen's picture, each save's notes,
     * which erase deletes for one disk), and the device trees start26 made from its own (tree(): its RAM size, the 2x
     * screen; iOS 26's AOD tree's too). For Installer.remove.
     */
    static List<File> ownFiles(Context c, int forOs) {
        List<String> starts = new ArrayList<>();
        for (String d : diskNames(forOs)) {
            starts.add(d + ".");
        }
        if (forOs == IOS26) {
            starts.add(DTREE26);
        } else if (forOs == WATCH) {
            starts.add(WatchProfile.DTREE);
        }
        List<File> out = new ArrayList<>();
        File[] f = c.getFilesDir().listFiles();
        for (File x : f == null ? new File[0] : f) {
            for (String s : starts) {
                if (x.getName().startsWith(s)) {
                    out.add(x);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * After Installer.remove: what the "guest" preferences keep of @forOs goes, each of its disks' marks (a stale quick
     * save, dirty, checked, a Go back's RAM, Screen26's cache and Auto-Lock marks) and its screen and memory choices, so
     * that a new install starts from the defaults. When it was the one chosen, the choice goes to a system still on the
     * phone (iOS 26, the watch, iOS 16; with none, as on a fresh app), its start error with it. "stale-from-log-done"
     * stays: the app's log still holds the old iOS 16's lines, which must not mark a new one's quick save stale.
     */
    static void forgetSystem(Context c, int forOs) {
        android.content.SharedPreferences.Editor e = c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit();
        for (String d : diskNames(forOs)) {
            e.remove(staleKey(d)).remove(dirtyKey(d)).remove(CHECKED + d).remove(keptRamKey(d));
            Screen26.forget(c, d);
        }
        if (forOs == IOS26) {
            e.remove(SCALE26_KEY).remove(ramKey(IOS26));
        } else if (forOs == WATCH) {
            e.remove(ramKey(WATCH));
        }
        if (selected(c) == forOs) {
            int next = available26(c) ? IOS26 : WatchProfile.available(c) ? WATCH : IOS16;
            if (next == IOS16 && !available(c)) {
                e.remove("os");
            } else {
                e.putInt("os", next);
            }
            startError = null;
        }
        e.commit();
    }

    /**
     * A small picture of @forOs's screen as it last looked (ScreenActivity.keepThumb writes it), for the home
     * screen's device (DeviceView): beside the user disk, one per disk.
     */
    static File thumbFile(Context c, int forOs) {
        return new File(c.getFilesDir(), diskName(c, forOs) + ".thumb.jpg");
    }

    /** What sits next to the user disk for one save: "ios16-user.<tag>.leds" and the like. */
    private static String userPrefix(Context c) {
        return diskName(c, os(c)) + ".";
    }

    /**
     * iOS 26's screen scale, 3 (1179x2556, an iPhone's own) or 2 (786x1704: the same 393x852 points with
     * 2.25 times fewer pixels to draw). Chosen in iOS 26's menu on the home screen. Unchosen it is 2, except where iOS 26 already ran at 3
     * before 2 became the default: that disk holds its apps and saves.
     */
    static int scale26(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("guest", Context.MODE_PRIVATE);
        if (p.contains(SCALE26_KEY)) {
            return p.getInt(SCALE26_KEY, 2) == 2 ? 2 : 3;
        }
        return new File(c.getFilesDir(), "ios26-user.qcow2").exists() ? 3 : 2;
    }

    private static final String SCALE26_KEY = "ios26-scale";

    static void setScale26(Context c, int scale) {
        c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit().putInt(SCALE26_KEY, scale == 2 ? 2 : 3).apply();
    }

    /**
     * The guest's RAM in MiB, per OS, chosen in its menu on the home screen (iOS 26 3, 4 or 6 GiB; the watch 2 or 3 GiB). Unchosen:
     * 4 / 3 GiB (the trees' own) on a phone with ~12 GB, 3 / 2 GiB on a smaller one (2 GiB slowed the watch's
     * cold Settings from 5.5 to 27 s but left ~1.3 GB more to Android, WATCHOS.md). A save always loads with
     * the RAM it was made with (savedRam), so a new size takes effect at the next full start.
     */
    static final int[] RAM_CHOICES_26 = {3072, 4096, 6144}, RAM_CHOICES_WATCH = {2048, 3072};
    /** Above this Android has so little left that it may close the guest: MainActivity.chooseRam warns. */
    static final int RAM_TIGHT = 4096;
    // 6 GB needs qemu-sptm asoft-android 6c733cf or later: from 5 GB of RAM vresearch's framebuffer goes past the end
    // of RAM. A save made below 5 GB keeps the old address, 0x1c0000000, so it still loads.

    static int ramDefault(Context c, int forOs) {
        boolean small = GpuCheck.ram(c) < 10_000_000_000L;
        return forOs == WATCH ? (small ? 2048 : 3072) : (small ? 3072 : 4096);
    }

    private static String ramKey(int forOs) {
        return forOs == WATCH ? "watch-ram" : "ios26-ram";
    }

    static int ramMiB(Context c, int forOs) {
        int mib = c.getSharedPreferences("guest", Context.MODE_PRIVATE).getInt(ramKey(forOs), ramDefault(c, forOs));
        for (int ok : forOs == WATCH ? RAM_CHOICES_WATCH : RAM_CHOICES_26) {
            if (ok == mib) {
                return mib;
            }
        }
        return ramDefault(c, forOs);   // a size not offered
    }

    static void setRamMiB(Context c, int forOs, int mib) {
        // a new size: a size accepted by a Go back no longer counts (keptRamKey)
        c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit().putInt(ramKey(forOs), mib)
                .remove(keptRamKey(c, forOs)).apply();
    }

    /** The RAM (MiB) the running iOS 26 or watch booted with, or loaded a save of (kept with each save). */
    private static volatile int bootRamMiB;

    static int bootRamMiB() {
        return bootRamMiB;
    }

    private static File savedRamFile(Context c, String tag) {
        return new File(c.getFilesDir(), userPrefix(c) + tag + ".ram");
    }

    /** The RAM save @tag was made with; saves from before the setting (2026-11-09) ran on the trees' own size. */
    private static int savedRam(Context c, String tag, int forOs) {
        try {
            return Integer.parseInt(new String(java.nio.file.Files.readAllBytes(
                    new File(c.getFilesDir(), diskName(c, forOs) + "." + tag + ".ram").toPath()),
                    java.nio.charset.StandardCharsets.US_ASCII).trim());
        } catch (IOException | NumberFormatException e) {
            return forOs == WATCH ? 3072 : 4096;
        }
    }

    /** What save @tag of @forOs opens with: "3 GB · 4 vCPU" (iOS 16: its vCPUs; its RAM is fixed). */
    static String hardware(Context c, int forOs, String tag) {
        int cpus = savedCpus(c, forOs, tag);
        return (forOs == IOS16 ? "" : savedRam(c, tag, forOs) / 1024 + " GB · ") + cpus + " vCPU";
    }

    /**
     * Save @tag of @forOs was made with other hardware than a boot gets now (the memory setting changed since):
     * its quick save is then a way back, not the next start (the start boots with the setting). A Go back to such a
     * save accepts its size (keptRamKey), so that run's own later quick saves resume as usual, until the memory
     * setting changes again.
     */
    static boolean otherHardware(Context c, int forOs, String tag) {
        if (forOs == IOS16) {
            return false;
        }
        int ram = savedRam(c, tag, forOs);
        return ram != ramMiB(c, forOs) && ram != c.getSharedPreferences("guest", Context.MODE_PRIVATE).getInt(keptRamKey(c, forOs), 0);
    }

    private static String keptRamKey(Context c, int forOs) {
        return keptRamKey(diskName(c, forOs));
    }

    private static String keptRamKey(String disk) {
        return "kept-ram-" + disk;
    }

    /**
     * @src with @mib of RAM (/chosen dram-size, the size in /memory reg, /product/util's base right after it:
     * dt-phone.py --ram-gib) and, @twoX, /chosen/display-scale 2; written next to the app's files when missing or
     * older. The tree itself is returned when nothing changes.
     */
    private static File tree(Context c, File src, int mib, boolean twoX) throws IOException {
        byte[] b = java.nio.file.Files.readAllBytes(src.toPath());
        long size = (long) mib << 20;
        long[] at = adtRam(b, src.getName());
        if (!twoX && at[3] == size) {
            return src;
        }
        File out = new File(c.getFilesDir(), src.getName() + (twoX ? "-2x" : "") + (at[3] == size ? "" : "-" + mib + "m"));
        if (out.isFile() && out.lastModified() >= src.lastModified()) {
            return out;
        }
        if (twoX) {
            b = twoX(b, src.getName());
        }
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long base = bb.getLong((int) at[1]);
        bb.putLong((int) at[0], size);
        bb.putLong((int) at[1] + 8, size);
        bb.putLong((int) at[2], base + size);
        File tmp = new File(out.getPath() + ".tmp");
        java.nio.file.Files.write(tmp.toPath(), b);
        if (!tmp.renameTo(out)) {
            throw new IOException("cannot write " + out);
        }
        return out;
    }

    /** Offsets of /chosen dram-size, /memory reg and /product/util reg in an Apple device tree, and the RAM size. */
    private static long[] adtRam(byte[] b, String name) throws IOException {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long[] r = {-1, -1, -1, 0};
        int end = adtWalk(bb, 0, "", r);
        if (end != b.length || r[0] < 0 || r[1] < 0 || r[2] < 0) {
            throw new IOException(name + ": no /chosen dram-size, /memory reg or /product/util reg");
        }
        r[3] = bb.getLong((int) r[0]);
        return r;
    }

    private static int adtWalk(java.nio.ByteBuffer bb, int o, String path, long[] r) {
        int np = bb.getInt(o), nc = bb.getInt(o + 4);
        o += 8;
        String nodeName = "?";
        java.util.Map<String, Integer> props = new java.util.HashMap<>();
        for (int i = 0; i < np; i++) {
            byte[] n = new byte[32];
            bb.position(o);
            bb.get(n);
            int z = 0;
            while (z < 32 && n[z] != 0) {
                z++;
            }
            String pn = new String(n, 0, z, java.nio.charset.StandardCharsets.US_ASCII);
            int ln = bb.getInt(o + 32) & 0x7fffffff;
            props.put(pn, o + 36);
            if ("name".equals(pn)) {
                int k = 0;
                while (k < ln && bb.get(o + 36 + k) != 0) {
                    k++;
                }
                byte[] v = new byte[k];
                bb.position(o + 36);
                bb.get(v);
                nodeName = new String(v, java.nio.charset.StandardCharsets.US_ASCII);
            }
            o += 36 + ((ln + 3) & ~3);
        }
        String here = path.isEmpty() ? nodeName : path + "/" + nodeName;
        if (nodeName.equals("chosen") && props.containsKey("dram-size")) {
            r[0] = props.get("dram-size");
        } else if (nodeName.equals("memory") && props.containsKey("reg")) {
            r[1] = props.get("reg");
        } else if (here.endsWith("/product/util") && props.containsKey("reg")) {
            r[2] = props.get("reg");
        }
        for (int i = 0; i < nc; i++) {
            o = adtWalk(bb, o, here, r);
        }
        return o;
    }

    /** The 2x screen's tree: /chosen/display-scale 2 (a 32-byte property name, a 4-byte length, the value). */
    private static byte[] twoX(byte[] b, String tree) throws IOException {
        byte[] name = java.util.Arrays.copyOf("display-scale".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 32);
        int at = -1;
        for (int i = 0; i + 40 <= b.length; i += 4) {
            int k = 0;
            while (k < 32 && b[i + k] == name[k]) {
                k++;
            }
            if (k == 32) {
                if (at >= 0) {
                    throw new IOException(tree + " has two display-scale properties");
                }
                at = i;
            }
        }
        if (at < 0 || (b[at + 32] & 0xff) != 4) {
            throw new IOException(tree + " has no 4-byte display-scale");
        }
        b[at + 36] = 2;
        b[at + 37] = b[at + 38] = b[at + 39] = 0;
        return b;
    }

    private static void ensureUserDisk(Context c, File g) throws IOException {
        File u = userDisk(c);
        if (u.exists()) {
            return;
        }
        String base = os(c) == WATCH ? WatchProfile.BASE
                : os(c) != IOS26 ? "disk.qcow2" : aodFiles(g) ? AOD26_BASE : "root26r.qcow2";
        String[] r = Emu.qemuImg(c, "create", "-q", "-f", "qcow2", "-F", "qcow2",
                "-b", new File(g, base).getPath(), u.getPath());
        if (!"0".equals(r[0])) {
            u.delete();
            throw new IOException("qemu-img create: " + r[1].trim());
        }
        if (os(c) == IOS26) {
            Screen26.forget(c, diskName(c, IOS26));     // a new disk: its cache is the installed disk's (fitScreen26)
        }
    }

    /**
     * iOS 26's disk and the screen it is about to start at (Screen26): a 2x disk whose MobileGestalt cache has not
     * been looked at with this build goes through the helper VM once, about half a minute. A cache of another
     * screen is moved aside there; the quick saves then hold iOS as it ran with it, and loading one would bring
     * the cache back with the rest of the disk, so they go and iOS boots. A 3x disk has the installed disk's own
     * screen (the setup and a PC boot iOS at 3x only). Returns the state to load, or null for a boot.
     *
     * The same helper VM run puts the Auto-Lock folder right (Screen26.lockFixed), which holds only for a start
     * that boots: so a disk not yet booted with it goes through the run before its next boot, at either scale.
     */
    private static String fitScreen26(Context c, String loadTag) throws IOException {
        boolean twoX = scale26(c) == 2;
        int w = twoX ? FB26_W * 2 / 3 : FB26_W, h = twoX ? FB26_H * 2 / 3 : FB26_H;
        String disk = diskName(c, IOS26);
        boolean screenDue = twoX && !Screen26.fits(c, disk, w);
        if (!twoX && !Screen26.fits(c, disk, w)) {
            Screen26.mark(c, disk, w);
        }
        boolean lockDue = Screen26.lockFixed(c, disk) == 0;
        if (!screenDue && !(lockDue && willBoot(c, loadTag))) {
            return loadTag;
        }
        String before = state;
        preparing = screenDue ? "Setting iOS 26 up for the 2x screen (once, about half a minute)\u2026"
                : "Checking iOS 26's settings before this boot (once, about half a minute)\u2026";
        state = "preparing";
        try {
            if (Screen26.fit(c, userDisk(c, IOS26), w, h)) {
                String why = dropQuick(c);
                AppLog.note(c, "screen26: the quick saves were made with the other screen's cache: "
                        + (why == null ? "deleted" : "not loaded (" + why + ")") + "; booting");
                loadTag = null;
            }
            Screen26.mark(c, disk, w);
            if (willBoot(c, loadTag)) {
                Screen26.markLock(c, disk);     // this boot has the folder; a loaded save would not
            }
        } finally {
            state = before;
            preparing = null;
        }
        return loadTag;
    }

    /**
     * A start of iOS 26 asked to load @loadTag will boot instead: none asked for, no such save, or the quick save
     * is only a way back (after a Turn off, or made with other hardware than the settings: dropStaleQuick,
     * quickWithOtherHardware decide the same in start26).
     */
    private static boolean willBoot(Context c, String loadTag) {
        if (loadTag == null) {
            return true;
        }
        List<Snap> saves = snapshots(c, IOS26);
        if (isQuick(loadTag)) {
            Snap q = newestQuick(saves);
            return q == null || quickStale(c, IOS26) || otherHardware(c, IOS26, q.tag);
        }
        for (Snap s : saves) {
            if (s.tag.equals(loadTag)) {
                return false;
            }
        }
        return true;
    }

    /** One saved state (qcow2 internal snapshot) in the user disk. */
    static final class Snap {
        final String tag, size, date;

        Snap(String tag, String size, String date) {
            this.tag = tag;
            this.size = size;
            this.date = date;
        }

        /** What the user calls it: the quick-boot state is "Last close", "_" is a space. */
        String label() {
            return isQuick(tag) ? "Last close" : tag.replace('_', ' ');
        }
    }

    /** A row of "info snapshots" or "qemu-img snapshot -l": ID, TAG, VM size (number and unit), date. */
    private static final java.util.regex.Pattern SNAP_ROW = java.util.regex.Pattern.compile(
            "^\\s*\\S+\\s+(\\S+)\\s+([\\d.]+\\s*\\S+)\\s+(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)");

    /** The saved states, newest first; through QMP while iOS runs (the disk is open then). Not on the UI thread. */
    static List<Snap> snapshots(Context c) {
        return snapshots(c, os(c));
    }

    static List<Snap> snapshots(Context c, int forOs) {
        String text = "";
        Qmp q = qmp;
        if (running() && runningOs == forOs && q != null) {
            String r = q.hmp("info snapshots", 15000);
            try {
                text = r == null ? "" : new org.json.JSONObject(r).optString("return", "");
            } catch (org.json.JSONException ignored) {
                // no list
            }
        } else if (userDisk(c, forOs).exists() && !(running() && runningOs == forOs)) {
            try {
                text = Emu.qemuImg(c, "snapshot", "-l", userDisk(c, forOs).getPath())[1];
            } catch (IOException ignored) {
                // no list
            }
        }
        List<Snap> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            java.util.regex.Matcher m = SNAP_ROW.matcher(line);
            if (m.find()) {
                out.add(new Snap(m.group(1), m.group(2), m.group(3)));
            }
        }
        out.sort((a, b) -> b.date.compareTo(a.date));
        // both quick slots: a save's last second (the old one not deleted yet); only the newer counts
        boolean seen = false;
        for (java.util.Iterator<Snap> it = out.iterator(); it.hasNext(); ) {
            if (isQuick(it.next().tag)) {
                if (seen) {
                    it.remove();
                }
                seen = true;
            }
        }
        return out;
    }

    /** The quick slots in the user disk, newest first, both of them (QEMU not running on it). */
    private static List<Snap> quickSlots(Context c, int forOs) {
        List<Snap> out = new ArrayList<>();
        try {
            String text = Emu.qemuImg(c, "snapshot", "-l", userDisk(c, forOs).getPath())[1];
            for (String line : text.split("\\r?\\n")) {
                java.util.regex.Matcher m = SNAP_ROW.matcher(line);
                if (m.find() && isQuick(m.group(1))) {
                    out.add(new Snap(m.group(1), m.group(2), m.group(3)));
                }
            }
        } catch (IOException ignored) {
            // no list
        }
        out.sort((a, b) -> b.date.compareTo(a.date));
        return out;
    }

    /**
     * Before a start that wants the quick-boot state: the newest quick slot's tag (an older one, left by a
     * save whose last second did not come, goes now), or QUICK when there is none (then the start boots).
     */
    private static String resolveQuick(Context c, int forOs, String loadTag) {
        if (!QUICK.equals(loadTag) || !userDisk(c, forOs).exists()) {
            return loadTag;
        }
        List<Snap> q = quickSlots(c, forOs);
        for (int i = 1; i < q.size(); i++) {
            String why = deleteSnapshot(c, q.get(i).tag);
            AppLog.note(c, "an older quick save " + q.get(i).tag + " (" + q.get(i).date + ") beside "
                    + q.get(0).tag + ": " + (why == null ? "deleted" : "not deleted: " + why));
        }
        return q.isEmpty() ? QUICK : q.get(0).tag;
    }

    /**
     * A quick save of the running iOS through @q, QEMU paused or not: into the slot the newest quick save
     * is not in, then the old one goes; over the old slot when the phone has no room for both. Null when
     * saved, else why not.
     */
    private static String saveQuick(Context c, Qmp q) {
        List<Snap> now = new ArrayList<>();
        String r = q.hmp("info snapshots", 15000);
        try {
            String text = r == null ? "" : new org.json.JSONObject(r).optString("return", "");
            for (String line : text.split("\\r?\\n")) {
                java.util.regex.Matcher m = SNAP_ROW.matcher(line);
                if (m.find() && isQuick(m.group(1))) {
                    now.add(new Snap(m.group(1), m.group(2), m.group(3)));
                }
            }
        } catch (org.json.JSONException ignored) {
            // no list: a save over QUICK
        }
        now.sort((a, b) -> b.date.compareTo(a.date));
        Snap old = now.isEmpty() ? null : now.get(0);
        String target = old == null || QUICK_B.equals(old.tag) ? QUICK : QUICK_B;
        long free = c.getFilesDir().getUsableSpace();
        long room = (old == null ? 4L << 30 : sizeBytes(old.size)) + (1L << 30);
        if (old != null && free < room) {
            target = old.tag;           // no room for two: QEMU deletes the old one first, as before
            AppLog.note(c, String.format(java.util.Locale.ROOT, "quick save over %s: %.1f GB free, two saves need %.1f GB",
                    old.tag, free / 1e9, room / 1e9));
        }
        synchronized (KBD) {        // the save and its note of the machine it was made in: no unplug in between
            String s = q.hmp("savevm " + target, 600000);
            if (s == null || !s.replace(" ", "").equals("{\"return\":\"\"}")) {
                return "savevm " + target + ": " + s;
            }
            keepLeds(c, target);
        }
        for (Snap o : now) {
            if (!o.tag.equals(target)) {
                String d = q.hmp("delvm " + o.tag, 60000);
                savedLedsFile(c, o.tag).delete();
                savedCpusFile(c, o.tag).delete();
                savedClockFile(c, o.tag).delete();
                savedRamFile(c, o.tag).delete();
                savedNoKbdFile(c, o.tag).delete();
                if (d == null || !d.replace(" ", "").equals("{\"return\":\"\"}")) {
                    AppLog.note(c, "the older quick save " + o.tag + " stays until the next start: delvm " + d);
                }
            }
        }
        return null;
    }

    static boolean hasSnapshot(Context c, String tag) {
        for (Snap s : snapshots(c)) {
            if (s.tag.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    /** A user's name for a save as a snapshot tag: letters, digits, - and _ (a space becomes _). */
    static String tagFor(String name) {
        String t = name.trim().replace(' ', '_').replaceAll("[^A-Za-z0-9_-]", "");
        if (t.length() > 40) {
            t = t.substring(0, 40);
        }
        return t.isEmpty() || isQuick(t) ? null : t;
    }

    /** Drops a saved state; with iOS running through QMP, else with qemu-img. Not on the UI thread. */
    static String deleteSnapshot(Context c, String tag) {
        savedLedsFile(c, tag).delete();
        savedCpusFile(c, tag).delete();
        savedClockFile(c, tag).delete();
        savedRamFile(c, tag).delete();
        savedNoKbdFile(c, tag).delete();
        Qmp q = qmp;
        if (running() && q != null) {
            String r = q.hmp("delvm " + tag, 60000);
            return r != null && r.replace(" ", "").equals("{\"return\":\"\"}") ? null : "delvm: " + r;
        }
        try {
            String[] r = Emu.qemuImg(c, "snapshot", "-d", tag, userDisk(c).getPath());
            // gone already counts as deleted
            return "0".equals(r[0]) || r[1].contains("not found") ? null : r[1].trim();
        } catch (IOException e) {
            return e.toString();
        }
    }

    /** Both quick slots (deleteSnapshot counts a missing one as deleted): null when both are gone, else why not. */
    private static String deleteQuick(Context c) {
        // delvm (QEMU running) skips a slot that is not there; qemu-img's "not found" counts as deleted
        String a = deleteSnapshot(c, QUICK);
        String b = deleteSnapshot(c, QUICK_B);
        return a != null ? a : b;
    }

    /**
     * Drops the quick-boot state (a load of it failed for good): the next start boots iOS. Null when it is
     * gone; else why not, and it stays marked stale: never loaded by itself (quickStale).
     */
    static String dropQuick(Context c) {
        setQuickStale(c, os(c), true);
        String why = deleteQuick(c);
        if (why == null) {
            setQuickStale(c, os(c), false);
        } else {
            AppLog.note(c, "the quick save stays until the next start (then deleted, never loaded): " + why);
        }
        return why;
    }

    /**
     * The quick save is not where iOS is any more: Turn off (iOS shut down after it; it stays as a way back
     * until the next quick save replaces it), or a load of it that failed and could not be deleted. It never
     * loads by itself, or the disk would go back to that save; only goBack loads it, when the user asks.
     */
    private static String staleKey(Context c, int forOs) {
        return staleKey(diskName(c, forOs));
    }

    private static String staleKey(String disk) {
        return "quick-stale-" + disk;
    }

    static boolean quickStale(Context c, int forOs) {
        return c.getSharedPreferences("guest", Context.MODE_PRIVATE).getBoolean(staleKey(c, forOs), false);
    }

    private static void setQuickStale(Context c, int forOs, boolean stale) {
        c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit().putBoolean(staleKey(c, forOs), stale).commit();
    }

    /**
     * 0.1.1 left no mark when its Turn off could not delete the quick save: its own log shows it, a
     * "power off" after the last "save". Read once, for iOS 16 (0.1.1 had no other).
     */
    private static void staleFromOldLog(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("guest", Context.MODE_PRIVATE);
        if (p.getBoolean("stale-from-log-done", false)) {
            return;
        }
        int lastSave = -1, lastOff = -1, lastFail = -1, i = 0;
        try {
            for (String l : java.nio.file.Files.readAllLines(new File(runDir(c), "app.txt").toPath())) {
                if (l.contains(" save: saved in ")) {
                    lastSave = i;
                } else if (l.contains(" power off: ")) {
                    lastOff = i;
                } else if (l.contains("snapshot -d " + QUICK) && l.contains("lock")) {
                    lastFail = i;
                }
                i++;
            }
        } catch (IOException ignored) {
            // no log: nothing to read
        }
        if (lastOff > lastSave && lastFail > lastSave && userDisk(c, IOS16).exists()) {
            setQuickStale(c, IOS16, true);
            AppLog.note(c, "the log shows a Turn off whose quick-save delete failed: the old quick save is not loaded");
        }
        p.edit().putBoolean("stale-from-log-done", true).apply();
    }

    /**
     * Before a start: a stale quick save (the way back after a Turn off) stays on the disk, but @loadTag never
     * names it: the start boots. The next quick save replaces it (saveQuick's rotation), or goBack loads it.
     */
    private static String dropStaleQuick(Context c, int forOs, String loadTag) {
        if (forOs == IOS16) {
            staleFromOldLog(c);
        }
        if (!quickStale(c, forOs)) {
            return resolveQuick(c, forOs, loadTag);
        }
        if (isQuick(loadTag)) {
            AppLog.note(c, "the quick save is a way back (after a Turn off), not loaded: booting");
        }
        return isQuick(loadTag) ? null : loadTag;
    }

    /**
     * Quick save @quick of @forOs is a way back, not the next start: kept after a Turn off, or made with other
     * hardware than the settings give a boot now.
     */
    static boolean isWayBack(Context c, int forOs, Snap quick) {
        return quick != null && isQuick(quick.tag) && (quickStale(c, forOs) || otherHardware(c, forOs, quick.tag));
    }

    /**
     * The user chose to go back to @forOs's way back @tag: it is the normal quick save again and opens with its own
     * hardware; its memory size counts as accepted for this disk's quick saves until the setting changes (boots keep
     * the setting). The caller then starts iOS, or loads it into the running one.
     */
    static void goBack(Context c, int forOs, String tag) {
        setQuickStale(c, forOs, false);
        if (otherHardware(c, forOs, tag)) {
            c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit().putInt(keptRamKey(c, forOs), savedRam(c, tag, forOs)).commit();
        }
        AppLog.note(c, "going back to the quick save " + tag + " (" + hardware(c, forOs, tag) + ")");
    }

    /**
     * Before a start that wants the quick save: one made with other hardware (the memory setting changed since)
     * becomes a way back and the start boots with the setting (goBack opens it).
     */
    private static String quickWithOtherHardware(Context c, int forOs, String loadTag) {
        if (!isQuick(loadTag) || !userDisk(c, forOs).exists() || !otherHardware(c, forOs, loadTag)) {
            return loadTag;
        }
        setQuickStale(c, forOs, true);
        AppLog.note(c, "the quick save opens with " + hardware(c, forOs, loadTag) + ", the setting is now "
                + ramMiB(c, forOs) / 1024 + " GB: it stays as a way back; booting");
        return null;
    }

    // -- the user disk's health

    private static String dirtyKey(Context c, int forOs) {
        return dirtyKey(diskName(c, forOs));
    }

    private static String dirtyKey(String disk) {
        return "dirty-" + disk;
    }

    /** Set while QEMU runs on the disk; cleared when the run ends cleanly (save and quit, shutdown, stop + quit). */
    private static void setDirty(Context c, int forOs, boolean dirty) {
        c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit().putBoolean(dirtyKey(c, forOs), dirty).commit();
    }

    /** Checked once after this build is installed, and after every run that did not end cleanly. */
    private static final String CHECKED = "disk-checked-v2-";

    /**
     * qemu-img check of the user disk (read only, ~seconds). Leaks (clusters nothing uses: what a
     * killed QEMU leaves) are harmless and freed at once. Errors in the disk's tables stop the start
     * with the counts, the disk as it is: what to do with it is the user's call.
     */
    private static void checkDisk(Context c, int forOs) throws IOException {
        File u = userDisk(c, forOs);
        android.content.SharedPreferences p = c.getSharedPreferences("guest", Context.MODE_PRIVATE);
        boolean dirty = p.getBoolean(dirtyKey(c, forOs), false);
        if (!u.exists() || !dirty && p.getBoolean(CHECKED + diskName(c, forOs), false)) {
            return;
        }
        long t0 = System.currentTimeMillis();
        String[] r = Emu.qemuImg(c, "check", "--output=json", u.getPath());
        long corrupt = -1, leaks = -1, errors = -1;
        String out = r[1];
        int brace = out.indexOf('{');
        if (brace >= 0) {
            try {
                org.json.JSONObject j = new org.json.JSONObject(out.substring(brace));
                corrupt = j.optLong("corruptions", 0);
                leaks = j.optLong("leaks", 0);
                errors = j.optLong("check-errors", 0);
            } catch (org.json.JSONException ignored) {
                // counts unknown
            }
        }
        AppLog.note(c, "disk check " + u.getName() + (dirty ? " (the last run did not end cleanly)" : "") + ": exit "
                + r[0] + ", corruptions " + corrupt + ", leaks " + leaks + ", check errors " + errors + ", "
                + (System.currentTimeMillis() - t0) + " ms");
        if ("2".equals(r[0]) || corrupt > 0) {
            throw new IOException(osName(forOs) + "'s disk has " + corrupt + " errors in its tables (qemu-img check);"
                    + " it was left as it is. The app's log has the details.");
        }
        if ("3".equals(r[0]) || leaks > 0) {
            String[] fix = Emu.qemuImg(c, "check", "-r", "leaks", u.getPath());
            AppLog.note(c, "disk check: freed the leaked clusters, exit " + fix[0]);
        }
        p.edit().putBoolean(CHECKED + diskName(c, forOs), true).putBoolean(dirtyKey(c, forOs), false).commit();
    }

    /** iOS runs, writes and saves into the user disk: refuse to start below this much free room. */
    /**
     * A first save (RAM: ~3.7 / ~3 GB, 5.1 GiB at iOS 26's 6 GB) and room to write; a quick save already in the
     * disk lowers it, since a save with too little room for two goes over it (saveQuick), down to 2 GB of room to write.
     */
    private static void checkRoom(Context c, int forOs) throws IOException {
        long free = c.getFilesDir().getUsableSpace();
        List<Snap> q = userDisk(c, forOs).exists() ? quickSlots(c, forOs) : new ArrayList<>();
        long quick = q.isEmpty() ? 0 : sizeBytes(q.get(0).size);
        long iphone = (forOs == IOS26 ? (ramMiB(c, forOs) > RAM_TIGHT ? 8L : 6L) : 4L) << 30;
        long need = Math.max(2L << 30, (forOs == WATCH ? WatchProfile.ROOM : iphone) - quick);
        AppLog.note(c, String.format(java.util.Locale.ROOT, "room: %.1f GB free, user disk %.1f GB, quick save %.1f GB,"
                + " needs %.1f GB", free / 1e9, userDisk(c, forOs).length() / 1e9, quick / 1e9, need / 1e9));
        if (free < need) {
            throw new IOException(String.format(java.util.Locale.ROOT, "Only %.1f GB free on this phone: %s needs"
                    + " %.1f GB to run and to save. Free space on the phone, or delete saves (side bar > Saves).",
                    free / 1e9, osName(forOs), need / 1e9));
        }
    }

    /** The disk's request counts from QMP (query-blockstats), for the log: failed ones mean lost writes. */
    private static void logDiskStats(Context c, Qmp q) {
        if (q == null) {
            return;
        }
        String r = q.call("{\"execute\":\"query-blockstats\"}", 5000);
        if (r == null) {
            return;
        }
        try {
            org.json.JSONArray a = new org.json.JSONObject(r).getJSONArray("return");
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject s = a.getJSONObject(i).optJSONObject("stats");
                if (s == null || s.optLong("rd_operations") + s.optLong("wr_operations") == 0) {
                    continue;
                }
                AppLog.note(c, "disk " + a.getJSONObject(i).optString("device", "?") + ": reads " + s.optLong("rd_operations")
                        + " (failed " + s.optLong("failed_rd_operations") + "), writes " + s.optLong("wr_operations")
                        + " (failed " + s.optLong("failed_wr_operations") + "), flushes " + s.optLong("flush_operations")
                        + " (failed " + s.optLong("failed_flush_operations") + ")");
            }
        } catch (org.json.JSONException ignored) {
            // no stats
        }
    }

    static File ledsFile(Context c) {
        return new File(runDir(c), "leds");
    }

    /**
     * Erase @forOs's iPhone (or watch): its user disk and what belongs to its saved states. The OS is
     * always named: an iOS 16 import once erased whichever OS was selected (2026-11-08), iOS 26's or the
     * watch's disk and saves among them.
     */
    static void erase(Context c, int forOs) {
        userDisk(c, forOs).delete();
        thumbFile(c, forOs).delete();
        String prefix = diskName(c, forOs) + ".";
        File[] f = c.getFilesDir().listFiles((d, n) -> n.startsWith(prefix)
                && (n.endsWith(".leds") || n.endsWith(".cpus") || n.endsWith(".clock") || n.endsWith(".ram")
                || n.endsWith(".nokbd")));
        if (f != null) {
            for (File x : f) {
                x.delete();
            }
        }
    }

    /** The LED byte when @tag was saved (the display's power then), next to the user disk. */
    private static File savedLedsFile(Context c, String tag) {
        return new File(c.getFilesDir(), userPrefix(c) + tag + ".leds");
    }

    private static int savedLeds(Context c, String tag) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(savedLedsFile(c, tag))) {
            int b = in.read();
            return b < 0 ? 0 : b;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void keepLeds(Context c, String tag) {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(savedLedsFile(c, tag))) {
            o.write(currentLeds(c) | (runningOs == IOS26 && aod26 ? AOD_SAVED : 0));
        } catch (IOException ignored) {
            // the display then reads on after a load
        }
        keepCpus(c, tag);
        File noKbd = savedNoKbdFile(c, tag);
        if (kbd26 || !ios26Running()) {
            noKbd.delete();
        } else {
            try {
                noKbd.createNewFile();
            } catch (IOException e) {
                // read as a save with the keyboard: its load would fail, and the start falls back to a boot
                AppLog.note(c, "cannot note that " + tag + " has no USB keyboard: " + e.getMessage());
            }
        }
        if (ios26Running()) {       // iOS 26 and the watch run on the host counter (the watch on its 19.2 MHz tree)
            try (PrintWriter w = new PrintWriter(new FileWriter(savedClockFile(c, tag)))) {
                w.println(runningOs == WATCH ? WatchProfile.CLOCK : CLOCK_V1);
            } catch (IOException ignored) {
                // then it counts as a save from before the clock fix
            }
            if (bootRamMiB > 0) {
                try (PrintWriter w = new PrintWriter(new FileWriter(savedRamFile(c, tag)))) {
                    w.println(bootRamMiB);
                } catch (IOException ignored) {
                    // then it loads with the trees' own size, which may not be its own
                }
            }
        }
    }

    /**
     * iOS 26 runs on the phone's own counter (x-host-counter), which starts again near 0 when the phone
     * restarts. A save made before QEMU carried the counter in its state (2026-11-05) cannot load after
     * a restart: XNU panicked (sched_clutch, the counter went back). Saves made since have this file.
     */
    private static final String CLOCK_V1 = "host-counter-in-state";

    private static File savedClockFile(Context c, String tag) {
        return new File(c.getFilesDir(), userPrefix(c) + tag + ".clock");
    }

    /** Why iOS 26's save @tag cannot load on this phone now, or null when it can. */
    private static String unloadable26(Context c, String tag) {
        if (runningOs == WATCH) {
            // a watch save loads only if it ran on the host counter with the 19.2 MHz tree, as the watch runs now:
            // one from QEMU's own 24 MHz counter (before 2026-11-07 20:40) would tick at the wrong rate
            String said = "";
            try {
                said = new String(java.nio.file.Files.readAllBytes(savedClockFile(c, tag).toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {
                // no file: an old save
            }
            return WatchProfile.CLOCK.equals(said) ? null
                    : "it was saved on the watch's older clock (QEMU's own counter), which the watch no longer runs on";
        }
        if (savedClockFile(c, tag).isFile()) {
            return null;
        }
        long saved = savedLedsFile(c, tag).lastModified();
        long phoneUp = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        if (saved > 0 && saved < phoneUp) {
            return "the phone restarted since it was saved (" + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
                    .format(new java.util.Date(saved)) + "), and it is older than the clock fix";
        }
        return null;
    }

    /**
     * iOS 26's QEMU has its USB keyboard (usb-kbd, "ukbd"). iOS shows its own keyboard only while no hardware
     * keyboard is attached (2026-11-10: with one, a text field came up with no keys under it), and the phone has
     * none to pass on: iOS 26 boots without the device. A save loads only into the machine it was made in, so
     * one made with the keyboard (every save before this) loads with it and the keyboard is unplugged once it
     * runs (unplugKeyboard); a save made without it has a ".nokbd" file beside it. The watch and the setup's
     * boots keep the keyboard, as they were.
     */
    private static volatile boolean kbd26;
    /** This QEMU loaded a save made with the keyboard: unplugged once QMP is up. */
    private static volatile boolean unplug26;
    /** A save and its ".nokbd" note are one step, and so is the unplug: neither in the middle of the other. */
    private static final Object KBD = new Object();

    private static File savedNoKbdFile(Context c, String tag) {
        return new File(c.getFilesDir(), userPrefix(c) + tag + ".nokbd");
    }

    /**
     * Unplugs iOS 26's USB keyboard from the running QEMU (device_del: iOS sees a keyboard pulled out, and its
     * own comes up at the next text field). The answer comes before the device is gone, so QEMU's list of
     * devices is asked until it is.
     */
    private static void unplugKeyboard(Context c, Qmp q) {
        synchronized (KBD) {
            if (!kbd26 || qmp != q) {
                return;
            }
            String r = q.call("{\"execute\":\"device_del\",\"arguments\":{\"id\":\"ukbd\"}}", 10_000);
            for (int i = 0; i < 50 && r != null; i++) {
                String l = q.call("{\"execute\":\"qom-list\",\"arguments\":{\"path\":\"/machine/peripheral\"}}", 5_000);
                if (l == null) {
                    return;         // QEMU went away
                }
                if (!l.contains("\"ukbd\"")) {
                    kbd26 = false;
                    AppLog.note(c, "keyboard: the save's USB keyboard is unplugged, iOS uses its own");
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
            AppLog.note(c, "keyboard: the USB keyboard did not unplug (device_del: " + r + "); iOS shows no keyboard of its own");
        }
    }

    /** The vCPU count of the running iOS: a save loads only into a QEMU with the same -smp. */
    private static int bootCpus = 4;

    private static File savedCpusFile(Context c, String tag) {
        return new File(c.getFilesDir(), userPrefix(c) + tag + ".cpus");
    }

    /** A save's vCPU count; 4 for the saves made before 0.1.2, which all had four. */
    static int savedCpus(Context c, String tag) {
        return savedCpus(c, os(c), tag);
    }

    static int savedCpus(Context c, int forOs, String tag) {
        try {
            int n = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(
                    new File(c.getFilesDir(), diskName(c, forOs) + "." + tag + ".cpus").toPath())).trim());
            return n >= 1 && n <= 8 ? n : 4;
        } catch (IOException | NumberFormatException e) {
            return 4;
        }
    }

    private static void keepCpus(Context c, String tag) {
        try (PrintWriter w = new PrintWriter(new FileWriter(savedCpusFile(c, tag)))) {
            w.println(bootCpus);
        } catch (IOException ignored) {
            // read as 4: a load then fails and the start falls back to a boot
        }
    }

    static int currentLeds(Context c) {
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(ledsFile(c), "r")) {
            int b = in.read();
            return b < 0 ? 0 : b;
        } catch (IOException e) {
            return 0;
        }
    }

    /** QEMU's Scroll Lock LED bit: ASoftware's XNU lights it while iOS has the display off (xnu 0102). */
    static final int SCROLL_LOCK_LED = 1;

    /**
     * iOS 26 is in its Always On Display as far as the app knows: it went there at the app's side button (iOS's
     * own Auto-Lock is Never, APK-PLAN §6.23), and a touch has not woken it since. Kept with a save, in a bit
     * of the save's LED byte no keyboard LED uses.
     */
    static volatile boolean aod26;
    private static final int AOD_SAVED = 0x80;

    static synchronized boolean running() {
        boolean a = Render.alive(pid);
        if (!a && pid > 0) {
            noticeEnd(pid);
        }
        return a;
    }

    // -- QEMU's own ends, for the crash report (CrashReport)

    /** The QEMU the app asked to end (a stop, a save's quit, a turn off, the setup's end of a boot): its end is no crash. */
    private static volatile int endingByApp = -1;
    /** The QEMU whose end was looked at already. */
    private static int noticedPid = -1;
    /** What the crash report needs of the QEMU that runs (or ran last). */
    private static volatile CrashReport.Run run;

    /**
     * QEMU @p has ended (it was reaped): unless the app asked for that, its end is recorded (CrashReport). Once per
     * QEMU; with this class's lock held.
     */
    private static void noticeEnd(int p) {
        if (p <= 0 || p == noticedPid) {
            return;
        }
        noticedPid = p;
        CrashReport.Run r = run;
        if (endingByApp == p || r == null || r.pid != p || appContext == null) {
            return;
        }
        CrashReport.captureAsync(appContext, r, Render.endStatus(p));
    }

    /**
     * The app is about to end QEMU @p. One that ended before it asked ended by itself (the service's stop after a
     * load that failed comes after QEMU died): that end is looked at first.
     */
    private static synchronized void appEnds(int p) {
        if (p > 0 && !Render.alive(p)) {
            noticeEnd(p);
        }
        endingByApp = p;
    }

    /** The setup (PhoneSetup26.endBoot) quits its boot's QEMU itself: no crash. */
    static void willEnd() {
        int p;
        synchronized (Guest.class) {
            p = pid;
        }
        appEnds(p);
    }

    /**
     * Looks at QEMU @forPid twice a second until it ends, so an end nobody is waiting for (the screen closed, the
     * phone asleep) is recorded while its stderr is still there.
     */
    private static void watch(int forPid) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (Guest.class) {
                    if (pid != forPid) {
                        return;
                    }
                    if (!Render.alive(forPid)) {
                        noticeEnd(forPid);
                        return;
                    }
                }
            }
        }, "qemu-watch");
        t.setDaemon(true);
        t.start();
    }

    /** The record of the QEMU just spawned (pid), and its watcher; with this class's lock held. */
    private static void track(int os, boolean setup, String start, long logAt, int cpus, int ramMiB, String screen, String engine) {
        CrashReport.Run r = new CrashReport.Run();
        r.pid = pid;
        r.os = os;
        r.setup = setup;
        r.start = start;
        r.startedMs = System.currentTimeMillis();
        r.appLogOffset = logAt;
        r.cpus = cpus;
        r.ramMiB = ramMiB;
        r.screen = screen;
        r.engine = engine;
        run = r;
        watch(pid);
    }

    /** A stand-in QEMU for the crash test: reims' last words, as a real one says them, then SIGABRT. */
    private static final String CRASH_TEST = "cat >&2 <<'EOF'\n"
            + "reims[warn] create::function: Metal refused the function's IR: Error Domain=MTLLibraryErrorDomain Code=1\n"
            + "reims[warn] create::function: Metal refused the function's IR: Error Domain=MTLLibraryErrorDomain Code=1\n"
            + "reims-vgpu: fatal: platform call renderCommandEncoderWithDescriptor:: the Vulkan runtime refused it: unsupported:"
            + " Unsupported { feature: Format, needs: NotImplemented, location: None, symbol: None }; the pass's attachments:"
            + " colour 0 552 (BGR10_XR) Type2D usage 0x4 Private\n"
            + "EOF\n"
            // long enough to open the guest's screen over it (a screen opened on nothing running starts the guest)
            + "sleep 10\n"
            + "kill -ABRT $$\n";

    /**
     * Debug builds: the whole crash report path on the phone without a guest or a disk. A shell stands in for
     * QEMU, says what reims says when it gives up, and aborts; the record, the notification and the screens'
     * "Report this crash" follow as for a real one.
     */
    static synchronized void startCrashTest(Context c) throws IOException {
        if ((c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            throw new IOException("the crash test is in debug builds only");
        }
        if (running()) {
            throw new IOException(osName(runningOs) + " is running: close it first");
        }
        if (qmp != null) {
            qmp.close();
            qmp = null;
        }
        appContext = c.getApplicationContext();
        int os = selected(c);
        runningOs = os;
        CrashReport.awaitCapture(15_000);
        long logAt = new File(runDir(c), "app.txt").length();
        CrashReport.startedAgain(c, os);
        AppLog.keepRunLogs(c);
        AppLog.note(c, "crash test: a stand-in QEMU that aborts");
        List<String> envp = new ArrayList<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            envp.add(e.getKey() + "=" + e.getValue());
        }
        endingByApp = -1;
        noticedPid = -1;
        resumed = false;
        loadedTag = null;
        // (the files dir as every QEMU's: Render.spawn)
        pid = Render.spawn(new String[] {"/system/bin/sh", "-c", CRASH_TEST}, envp.toArray(new String[0]),
                new File(runDir(c), "qemu-stderr.txt").getPath(), c.getFilesDir().getPath());
        if (pid < 0) {
            throw new IOException("cannot start the crash test");
        }
        state = "booting";
        boolean v = os != IOS16;
        // the engine the system runs on, so the record's engine line (and its SHA-1) is a real one
        track(os, false, "a crash test", logAt, bootCpus, v ? ramMiB(c, os) : 7168, v ? fb26W + "x" + fb26H : "",
                v ? Emu.qemu26Path(c) : Emu.qemuPath(c));
    }

    static synchronized int pid(Context c) {
        return pid;
    }

    /** The guest's one QMP connection, or null while there is none (input is dropped then). */
    static Qmp qmp() {
        return qmp;
    }

    /** Connects QMP in the background once QEMU listens; QEMU takes one client, this is it. */
    private static void connectQmp(Context c, int forPid) {
        Thread t = new Thread(() -> {
            File sock = new File(qmpPath(c));
            // a saved state loads before QEMU answers: minutes, on a cold phone
            for (int i = 0; i < 3000 && Render.alive(forPid) && pid == forPid; i++) {
                if (sock.exists()) {
                    Qmp q = new Qmp();
                    try {
                        q.connect(sock.getPath(), 5000);
                        qmp = q;
                        if (unplug26 && runningOs == IOS26 && pid == forPid) {
                            unplugKeyboard(c, q);
                        }
                        return;
                    } catch (IOException e) {
                        q.close();
                    }
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "qmp-connect");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The DNS servers of the active network, for slirp, which reads a relative resolv.conf from QEMU's
     * working directory, the files dir (patch-slirp.py, Render.spawn); public ones as a fallback.
     */
    private static void writeResolvConf(Context c) throws IOException {
        StringBuilder sb = new StringBuilder();
        android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
        android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp != null) {
            for (java.net.InetAddress a : lp.getDnsServers()) {
                sb.append("nameserver ").append(a.getHostAddress()).append('\n');
            }
        }
        sb.append("nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(c.getFilesDir(), "resolv.conf")))) {
            w.print(sb);
        }
    }

    /** The end of QEMU's stderr: why it stopped, for the screen. */
    static String lastWords(Context c) {
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(new File(runDir(c), "qemu-stderr.txt").toPath());
            for (int i = lines.size() - 1; i >= 0 && i >= lines.size() - 40; i--) {
                String l = lines.get(i);
                if (l.contains("host signal") || l.contains("died") || l.contains("Error") || l.contains("error")) {
                    return l.length() > 200 ? l.substring(0, 200) : l;
                }
            }
        } catch (IOException ignored) {
            // no log yet
        }
        return "";
    }

    /**
     * Why the guest that ran last stopped, in the screen's words, from QEMU's stderr: the phone's storage, the
     * GPU layer, Android's mapping limit, else QEMU's own last error line; "" when it said nothing.
     */
    static String stopReason(Context c) {
        String err;
        try {
            err = new String(java.nio.file.Files.readAllBytes(new File(runDir(c), "qemu-stderr.txt").toPath()),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            err = "";
        }
        String tail = err.length() > 20_000 ? err.substring(err.length() - 20_000) : err;
        if (tail.contains("No space left on device") || tail.contains("ENOSPC")) {
            return String.format(java.util.Locale.ROOT, "the phone's storage is full (%.1f GB free). Free some space,"
                    + " then start it again: its disk and saves are kept.", c.getFilesDir().getUsableSpace() / 1e9);
        }
        if (tail.contains("reims-vgpu: fatal")) {
            // metal2vulkan met something it does not translate yet (2026-11-07: a compute kernel's texture
            // format on the watch) and reims ends QEMU rather than draw wrong
            return "its graphics asked the phone's GPU for something this version cannot do yet. Start it again:"
                    + " its disk and saves are kept.";
        }
        String why = lastWords(c);
        if (why.contains("max_map_count") || why.contains("kernel mappings")) {
            // reims' views of iOS's graphics memory take a mapping per 16 KiB page: Android allows an app
            // 65,530 (APK-PLAN §6.19)
            return "its graphics memory used up the 65,530 memory mappings Android gives an app (a known limit"
                    + " of this version). Start it again; the 2x screen (iOS 26's menu on the home screen) needs fewer.";
        }
        return why;
    }

    /** The last iOS 26 or watchOS QEMU started in the GPU's compatibility mode (start26, GpuCheck.noSparse). */
    static volatile boolean compat26;
    /** ...and it was one of the setup's own boots, whose failures the setup reports itself. */
    private static volatile boolean compatSetupRun;
    /** The QEMU whose end in that mode the screen has told of (once per QEMU). */
    private static int compatToldPid = -1;

    /**
     * True once per QEMU: the iOS 26 or watchOS that ran in the compatibility mode ended by itself. The app's own
     * ends (Save and close, Turn off, stop) let go of the pid (forget, stop), and a save or a Turn off in progress
     * has its own state; a QEMU that died while it loaded a save ("resuming") is the start's own failure, which
     * GuestService answers with a boot.
     */
    static synchronized boolean endedInCompatMode() {
        if (!compat26 || compatSetupRun || !ios26Running() || pid <= 0 || pid == compatToldPid || Render.alive(pid)
                || !("running".equals(state) || "booting".equals(state))) {
            return false;
        }
        compatToldPid = pid;
        return true;
    }

    /** The saved state this QEMU started from, null after a boot. */
    static volatile String loadedTag;

    /**
     * The QEMU stop() let go of (iOS 26's ends on a thread of its own, iOS 16's on SIGTERM): it may
     * still hold the user disk for a few seconds.
     */
    private static volatile int endingPid = -1;

    /**
     * Until the QEMU before is gone (at most @ms): the last second of a save and close (QEMU has
     * quit but is not reaped yet), a stop() whose QEMU still ends. A start then would find the disk
     * locked, or find iOS "running" and do nothing (2026-11-05: the service then took the saved state
     * for one that does not load, and dropped it). True when it is gone.
     */
    static boolean waitEnded(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            int e = endingPid;
            if (e > 0 && !Render.alive(e)) {
                endingPid = -1;     // reaped: never wait on a pid a later QEMU may get
                e = -1;
            }
            if (!saving() && e <= 0 && ending26.get() == 0) {
                return true;
            }
            if (System.currentTimeMillis() > end) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                return false;
            }
        }
    }

    /**
     * True when a start from @tag failed for the state itself (QEMU could not load it), false when
     * QEMU never got that far (the disk locked by the QEMU before, no memory): then the state stays.
     */
    static boolean loadFailedForGood(Context c) {
        try {
            String err = new String(java.nio.file.Files.readAllBytes(new File(runDir(c), "qemu-stderr.txt").toPath()),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            return !(err.contains("Failed to get") && err.contains("lock")) && !err.contains("Cannot allocate memory");
        } catch (IOException e) {
            return false;
        }
    }

    private static final Object PREPARING = new Object();

    /**
     * What a start does before start(), off this class's lock: the screens ask running() many times a second
     * and would stand still behind a start that held the lock for half a minute. That is iOS 26's 2x disk and
     * its screen (fitScreen26, the state "preparing" meanwhile); start26 does the same itself for a start that
     * did not come through here. Returns the state to load, or null for a boot.
     */
    static String prepare(Context c, String loadTag) throws IOException {
        synchronized (PREPARING) {
            if (setupBoot != null || running() || selected(c) != IOS26 || missing26(c) != null
                    || (scale26(c) != 2 || Screen26.fits(c, diskName(c, IOS26), FB26_W * 2 / 3))
                    && Screen26.lockFixed(c, diskName(c, IOS26)) != 0) {
                return loadTag;
            }
            ensureUserDisk(c, dir26(c));
            checkDisk(c, IOS26);
            return fitScreen26(c, loadTag);
        }
    }

    /** Starts iOS: from the saved state @loadTag when there is one (QUICK: quick boot), else a boot. */
    static synchronized void start(Context c, String loadTag) throws IOException {
        resumed = false;    // until this start says otherwise: the service reads it after
        if (running()) {
            throw new IOException("iOS is running already");
        }
        if (qmp != null) {
            qmp.close();                // of a QEMU that is gone
            qmp = null;
        }
        File links = Emu.linkLibraries(c);
        writeResolvConf(c);
        if (selected(c) == IOS26 || selected(c) == WATCH) {
            start26(c, links, loadTag);
            return;
        }
        runningOs = IOS16;
        appContext = c.getApplicationContext();
        File g = guestDir(c);
        File run = runDir(c);
        ensureUserDisk(c, g);
        loadTag = dropStaleQuick(c, IOS16, loadTag);
        loadTag = quickWithOtherHardware(c, IOS16, loadTag);
        checkRoom(c, IOS16);
        checkDisk(c, IOS16);
        List<Snap> saves = loadTag == null ? new ArrayList<>() : snapshots(c);
        resumed = false;
        StringBuilder tags = new StringBuilder();
        for (Snap s : saves) {
            resumed |= s.tag.equals(loadTag);
            tags.append(tags.length() > 0 ? ", " : "").append(s.tag);
        }
        loadedTag = resumed ? loadTag : null;
        // the record of the QEMU before reads its stderr, which this start truncates
        CrashReport.awaitCapture(15_000);
        long logAt = new File(runDir(c), "app.txt").length();
        CrashReport.startedAgain(c, IOS16);
        AppLog.keepRunLogs(c);
        AppLog.note(c, "start: wanted " + (loadTag == null ? "a boot" : loadTag) + ", saves [" + tags + "] -> "
                + (resumed ? "-loadvm " + loadTag : "boot"));
        CpuPlan plan = CpuPlan.forThisPhone(c);
        // a saved state needs the vCPU count it was saved with
        int smp = resumed ? savedCpus(c, loadTag) : plan.smp;
        AppLog.note(c, "cpus: " + smp + " vCPUs" + (resumed && smp != plan.smp ? " (the save's)" : "")
                + ", pin " + (plan.pin.isEmpty() ? "none" : plan.pin) + "; " + plan.why);
        if (!resumed) {
            Installer.prepareBoot(c, g, smp);   // this phone's time zone, region, clock and cpus= in the boot-args
        }
        bootCpus = smp;
        new File(run, "qmp.sock").delete();
        // the guest keyboard's LEDs, one byte QEMU keeps current (ASOFT_LEDS_FILE): zero until iOS says
        // (written in place, never truncated: the screen reads it while this runs). QEMU's LED state
        // is not in a saved state and XNU only reports changes: a resumed guest starts from the LEDs
        // it had when it was saved, so a display saved off still reads off.
        try (java.io.RandomAccessFile o = new java.io.RandomAccessFile(ledsFile(c), "rw")) {
            o.write(resumed ? savedLeds(c, loadTag) : 0);
        }
        int ramFd = Render.createGuestRam(RAM_SIZE);
        if (ramFd < 0) {
            throw new IOException("no memfd for the guest's RAM");
        }
        List<String> cmd = new ArrayList<>(List.of(Emu.qemuPath(c),
                // no loadvm-catch-up: moving the clock under a loaded state trips XNU's spinlock timeouts
                // (a lock held at the save looks held for minutes); the kernel (xnu 0103) sets its
                // calendar from the PL031 within 5 s instead
                "-M", "virt,gic-version=3,memory-backend=m0",
                "-object", "memory-backend-memfd,id=m0,size=7G,share=on",
                "-cpu", "cortex-a76", "-smp", String.valueOf(smp), "-m", "7G",
                "-display", "none", "-monitor", "none", "-parallel", "none",
                "-device", "ramfb", "-device", "virtio-keyboard-device", "-device", "virtio-tablet-device",
                "-qmp", "unix:" + qmpPath(c) + ",server,nowait",
                "-serial", "file:" + new File(run, "serial.txt").getPath(),
                "-d", "guest_errors,unimp", "-D", new File(run, "qemu.log").getPath(),
                "-no-reboot", "-nic", "none",
                "-device", "loader,file=" + new File(g, "ram.bin").getPath() + ",addr=0x" + Long.toHexString(KERNEL_PA) + ",force-raw=on",
                "-device", "loader,file=" + new File(g, "stub.bin").getPath() + ",addr=0x" + Long.toHexString(STUB_PA) + ",force-raw=on",
                "-device", "loader,addr=0x" + Long.toHexString(STUB_PA) + ",cpu-num=0",
                "-global", "virtio-mmio.force-legacy=false",
                "-name", "ASoftware iOS",
                "-netdev", "user,id=net0", "-device", "virtio-net-device,netdev=net0",
                // cache=none (O_DIRECT): a save writes ~3 GB of RAM into the disk; through the page
                // cache that filled the phone's memory and lmkd killed the app (QEMU with it) mid-save
                "-drive", "file=" + userDisk(c).getPath() + ",if=none,id=root,cache=none,format=qcow2",
                "-device", "virtio-blk-device,drive=root"));
        if (resumed) {
            cmd.add("-loadvm");
            cmd.add(loadTag);
        }
        Map<String, String> env = new java.util.HashMap<>(System.getenv());
        Emu.baseEnv(c, links, env);
        env.put("ASOFT_GUEST_RAM_FD", String.valueOf(ramFd));    // our memfd, inherited: see render.c
        String missing = nativeMissing();
        if (missing.isEmpty()) {
            env.put("ASOFT_NATIVE_EL0", "1");
            env.put("ASOFT_NATIVE_EL0_TPIDRRO", "1");
            env.put("ASOFT_NATIVE_EL0_STUBS", "8");
            env.put("ASOFT_NATIVE_EL0_DELAY_MS", "20000");
            env.put("ASOFT_NATIVE_EL0_STATS", "1");
            // while this file exists EL0 stays under TCG: for comparing the two without a reboot
            env.put("ASOFT_NATIVE_EL0_OFF_FILE", new File(run, "native-off").getPath());
            if (!plan.pin.isEmpty()) {
                env.put("ASOFT_NATIVE_EL0_STUB_CPUS", plan.pin);
            }
        } else {
            AppLog.note(c, "native EL0 off: this CPU lacks " + missing + "; iOS runs under TCG only");
        }
        if (!plan.pin.isEmpty()) {
            env.put("ASOFT_VCPU_CPUS", plan.pin);   // QEMU pins each vCPU thread (tcg-accel-ops-mttcg.c)
        }
        env.put("ASOFT_EL_SAMPLER", "30");
        env.put("ASOFT_LEDS_FILE", ledsFile(c).getPath());
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(run, "cmdline.txt")))) {
            w.println(String.join(" ", cmd));
        }
        List<String> envp = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            envp.add(e.getKey() + "=" + e.getValue());
        }
        setDirty(c, IOS16, true);
        endingByApp = -1;
        noticedPid = -1;
        pid = Render.spawn(cmd.toArray(new String[0]), envp.toArray(new String[0]),
                new File(run, "qemu-stderr.txt").getPath(), c.getFilesDir().getPath());
        if (pid < 0) {
            throw new IOException("cannot start QEMU");
        }
        state = resumed ? "resuming" : "booting";
        track(IOS16, false, resumed ? "the save " + loadTag : "a boot", logAt, smp, 7168, "", Emu.qemuPath(c));
        connectQmp(c, pid);
    }

    /** For the ends that come without one (stop() on the UI thread, end26 on its own). */
    private static volatile Context appContext;

    /**
     * iOS 26: -M vresearch with the GPU through reims + metal2vulkan on Turnip (libqemu26.so, the
     * NDK build with reims linked in), the command line of the Termux runs (run-ios26-nat.sh) with
     * the flags measured on 2026-11-05 (ANDROID-S25.md §8.13): 24 stubs, the host's counter
     * (19.2 MHz device tree), the TLBI filter on. Its frames come back through ASOFT_FRAME_FD.
     */
    static final String CPU26 = "max,pauth-impdef=on,x-el0-pac-off=on,sve=off,sme=off,x-host-counter=on,x-keep-kernel-tlb=off";

    /**
     * Debug builds: ios26.txt in the app's external folder changes one start for a test, a line each:
     * cpu=..., dtree=NAME (in the iOS 26 folder), cache=writeback, env.NAME=VALUE (QEMU's environment),
     * arg=... (one more QEMU argument, in order). Release builds read nothing.
     */
    /** The running iOS 26's ios26.txt settings (AutoSave reads its own from them); empty in release builds. */
    private static volatile Map<String, String> lastOverrides26 = java.util.Collections.emptyMap();

    static Map<String, String> overrides26() {
        return lastOverrides26;
    }

    private static Map<String, String> overrides26(Context c, List<String> args) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        if ((c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return m;
        }
        try {
            for (String l : java.nio.file.Files.readAllLines(new File(c.getExternalFilesDir(null), "ios26.txt").toPath())) {
                l = l.trim();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq < 1) {
                    continue;
                }
                if (l.startsWith("arg=")) {
                    args.add(l.substring(4));
                } else {
                    m.put(l.substring(0, eq), l.substring(eq + 1));
                }
            }
        } catch (IOException ignored) {
            // no test file
        }
        return m;
    }

    /**
     * A boot the setup from Apple makes on a disk it is building (PhoneSetup26: iOS 26's first boot and its
     * wallpaper boot, the watch's first boot), as the PC installer's Start-iOS26.ps1 -Keep / Start-watchOS.ps1
     * -FirstBoot do: that disk itself (no user disk, no quick boot), the given kernel, trees and trust cache,
     * and the guest's root shell on a UNIX socket (console.sock, logged to serial.txt) for the setup's checks.
     */
    static final class SetupBoot {
        boolean watch;
        File disk, bootkc, sptm, txm, dtree, tc, ramdisk;
    }

    /** The setup boot the next start26 makes, or null for the user's own iOS. */
    private static volatile SetupBoot setupBoot;

    /** Starts @b (blocking until QEMU is up, as start does); the setup ends it with stopAndWait or a QMP quit. */
    static synchronized void startSetup(Context c, SetupBoot b) throws IOException {
        if (running()) {
            throw new IOException("iOS is running: close it first");
        }
        if (qmp != null) {
            qmp.close();
            qmp = null;
        }
        File links = Emu.linkLibraries(c);
        writeResolvConf(c);
        setupBoot = b;
        try {
            start26(c, links, null);
        } finally {
            setupBoot = null;
        }
    }

    /** The memfd iOS 26's frames come in (this process's descriptor), -1 before a start. */
    private static volatile int frameFd26 = -1;

    /**
     * How many different colours a sample of the running iOS 26's or watch's frame has (every 7th pixel of
     * every 7th row), -1 when it cannot be read: the setup tells the Apple logo (a few) from a face or a
     * home screen (hundreds), as the PC installer tells them by the size of a screendump.
     */
    static int frameVariety() {
        if (frameFd26 < 0) {
            return -1;
        }
        // (pread on the memfd: /proc/self/fd/N of a memfd does not open for an app)
        int w = fb26W, h = fb26H;
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        byte[] row = new byte[w * 4];
        for (int y = 0; y < h; y += 7) {
            if (Render.readFrame((long) y * w * 4, row, row.length) != row.length) {
                return -1;
            }
            for (int x = 0; x < w; x += 7) {
                seen.add((row[x * 4] & 0xf0) << 8 | (row[x * 4 + 1] & 0xf0) << 4 | (row[x * 4 + 2] & 0xf0));
            }
        }
        return seen.size();
    }

    static File consoleSocket(Context c) {
        return new File(runDir(c), "console.sock");
    }

    /** iOS 26, or watchOS on the same machine (selected WATCH: WatchProfile's files, screen, CPU and buttons). */
    private static void start26(Context c, File links, String loadTag) throws IOException {
        SetupBoot sb = setupBoot;
        boolean watch = sb != null ? sb.watch : selected(c) == WATCH;
        int os = watch ? WATCH : IOS26;
        runningOs = os;
        appContext = c.getApplicationContext();
        File g = watch ? WatchProfile.dir(c) : dir26(c);
        File run = runDir(c);
        String miss = sb != null ? null : watch ? WatchProfile.missing(c) : missing26(c);
        if (miss != null) {
            throw new IOException(osName(os) + " is not on this phone: no " + new File(g, miss));
        }
        if (sb == null) {
            ensureUserDisk(c, g);
            loadTag = dropStaleQuick(c, os, loadTag);
            loadTag = quickWithOtherHardware(c, os, loadTag);
        }
        // the clock check: iOS 26's after a phone restart, the watch's counter mode (unloadable26)
        String cannot = loadTag == null ? null : unloadable26(c, loadTag);
        if (cannot != null) {
            AppLog.note(c, "not loading " + loadTag + ": " + cannot + "; booting");
            if (isQuick(loadTag)) {
                dropQuick(c);       // it never loads on this phone again (a named save stays)
            }
            loadTag = null;
        }
        if (sb == null) {
            checkRoom(c, os);
            checkDisk(c, os);
            if (!watch) {
                loadTag = fitScreen26(c, loadTag);
            }
        }
        List<Snap> saves = loadTag == null ? new ArrayList<>() : snapshots(c, os);
        resumed = false;
        StringBuilder tags = new StringBuilder();
        for (Snap s : saves) {
            resumed |= s.tag.equals(loadTag);
            tags.append(tags.length() > 0 ? ", " : "").append(s.tag);
        }
        loadedTag = resumed ? loadTag : null;
        if (resumed && !watch && sb == null && !isQuick(loadTag)) {
            // a save the user named takes the disk back to its day, MobileGestalt cache and all: maybe one made
            // before the cache was looked at, so it is looked at again before the next boot (fitScreen26)
            Screen26.forget(c, diskName(c, IOS26));
        } else if (resumed && !watch && sb == null
                && savedLedsFile(c, loadTag).lastModified() < Screen26.lockFixed(c, diskName(c, IOS26))) {
            // a quick save from before the boot that had the Auto-Lock folder put right (a way back): the same
            Screen26.forgetLock(c, diskName(c, IOS26));
        }
        // the record of the QEMU before reads its stderr, which this start truncates
        CrashReport.awaitCapture(15_000);
        long logAt = new File(runDir(c), "app.txt").length();
        if (sb == null) {
            CrashReport.startedAgain(c, os);
        }
        AppLog.keepRunLogs(c);
        AppLog.note(c, "start " + osName(os) + " from " + g + ": wanted " + (loadTag == null ? "a boot" : loadTag) + ", saves ["
                + tags + "] -> " + (resumed ? "-loadvm " + loadTag : "boot"));
        CpuPlan plan = CpuPlan.forThisPhone(c);
        int smp = resumed ? savedCpus(c, loadTag) : plan.smp;
        AppLog.note(c, "cpus: " + smp + " vCPUs, pin " + (plan.pin.isEmpty() ? "none" : plan.pin) + "; " + plan.why);
        bootCpus = smp;
        new File(run, "qmp.sock").delete();
        int leds = resumed ? savedLeds(c, loadTag) : 0;
        aod26 = (leds & AOD_SAVED) != 0;    // a save taken in AOD loads in it
        try (java.io.RandomAccessFile o = new java.io.RandomAccessFile(ledsFile(c), "rw")) {
            o.write(leds & ~AOD_SAVED);
        }
        Render.closeGuestRam();     // iOS 16's, if it ran before: QEMU makes iOS 26's own
        File icd = writeVulkanIcd(c, links);
        List<String> extra = new ArrayList<>();
        // ios26.txt (debug builds) is iOS 26's: the watch takes none of it
        Map<String, String> ov = watch || sb != null ? new java.util.HashMap<>() : overrides26(c, extra);
        lastOverrides26 = ov;
        if (!ov.isEmpty() || !extra.isEmpty()) {
            AppLog.note(c, "ios26.txt: " + ov + " " + extra);
        }
        boolean twoX = !watch && sb == null && scale26(c) == 2;
        fb26W = watch ? WatchProfile.FB_W : twoX ? FB26_W * 2 / 3 : FB26_W;     // 786x1704 @2x
        fb26H = watch ? WatchProfile.FB_H : twoX ? FB26_H * 2 / 3 : FB26_H;
        String fb = ov.get("fb");
        if (fb != null && fb.matches("\\d+x\\d+")) {
            fb26W = Integer.parseInt(fb.substring(0, fb.indexOf('x')));
            fb26H = Integer.parseInt(fb.substring(fb.indexOf('x') + 1));
        }
        int frameFd = Render.createFrameMemory((long) fb26W * fb26H * 4);
        if (frameFd < 0) {
            throw new IOException("no memfd for iOS 26's frames");
        }
        frameFd26 = frameFd;
        // a disk on the AOD base gets the AOD tree and trust cache; one on root26r keeps the plain ones (the AOD
        // tree over an unpatched libMobileGestalt takes the hardware flipbook, which kills backboardd)
        boolean aod = sb == null && !watch && aodDisk(userDisk(c, IOS26)) && aodFiles(g);
        aodRunning = aod;
        String tree = aod ? DTREE26_AOD : DTREE26;
        // the RAM: a save's own (it only loads into the size it was made with), else the one chosen on the home screen
        String dtree, tc;
        if (sb != null) {
            dtree = sb.dtree.getPath();     // the setup's own trees, their own RAM
            tc = sb.tc.getPath();
            bootRamMiB = 0;
        } else {
            int ram = resumed ? savedRam(c, loadTag, os) : ramMiB(c, os);
            bootRamMiB = ram;
            dtree = ov.containsKey("dtree") && !watch ? new File(g, ov.get("dtree")).getPath()
                    : tree(c, watch ? WatchProfile.part(c, WatchProfile.DTREE) : new File(g, tree), ram, twoX).getPath();
            tc = watch ? WatchProfile.part(c, WatchProfile.TC).getPath() : new File(g, aod ? AOD26_TC : "root26m.tc").getPath();
            AppLog.note(c, "guest RAM " + ram + " MiB" + (resumed ? " (the save's)" : ""));
        }
        AppLog.note(c, "screen " + fb26W + "x" + fb26H + (twoX ? " (2x)" : "") + ", device tree " + dtree
                + (aod ? ", Always On Display (" + AOD26_BASE + ")" : ""));
        List<String> cmd = new ArrayList<>(List.of(Emu.qemu26Path(c), "-name", watch ? "ASoftware watchOS" : "ASoftware iOS 26",
                "-M", watch ? WatchProfile.machine() : "vresearch,fb=" + fb26W + "x" + fb26H
                        + ",unauth-root=on,pvg=reims,aks-stub=on,amfi-lc-off=on,aks-crypt=on,acm-stub=on,hfs-dp-stub=on",
                "-bootkc", (sb != null ? sb.bootkc : watch ? WatchProfile.part(c, WatchProfile.KERNEL) : new File(g, "bootkc")).getPath(),
                "-sptm", (sb != null ? sb.sptm : watch ? WatchProfile.part(c, "sptm") : new File(g, "sptm")).getPath(),
                "-txm", (sb != null ? sb.txm : watch ? WatchProfile.part(c, "txm") : new File(g, "txm")).getPath(), "-dtree", dtree,
                "-tc", tc, "-ramdisk", (sb != null ? sb.ramdisk : watch ? WatchProfile.part(c, "rd-shell.hfs") : new File(g, "rd-shell.hfs")).getPath(),
                "-args", "serial=3 -v debug=0x14e cpus=" + smp + " rd=disk0s1 wdt=-1 keepsyms=1 -noprogress "
                        + "launchd_unsecure_cache=1 launchd_disable_all_timeouts=1 aks-endpoint=0 cpu_boot_timeout_secs=30",
                "-display", "none", "-monitor", "none", "-parallel", "none",
                // the guest's root shell (bash on the serial console) on files/run/console.sock, still logged to
                // serial.txt: the setup's commands, Turn off's sync (powerOff), and adb (run-as + toybox nc -U). With no
                // client QEMU drops the console's output there (wait=off), so nothing waits on it
                "-chardev", "socket,id=con,path=" + new File(run, "console.sock").getPath() + ",server=on,wait=off,logfile="
                        + new File(run, "serial.txt").getPath(),
                "-serial", "chardev:con",
                "-d", "guest_errors,unimp", "-D", new File(run, "qemu.log").getPath(),
                "-cpu", watch ? WatchProfile.CPU : ov.getOrDefault("cpu", CPU26),
                "-smp", String.valueOf(smp), "-accel", "tcg,thread=multi,tb-size=768",
                // cache=none (O_DIRECT), as iOS 16's: a save's 4 GB do not go through the page cache (lmkd).
                // Before QEMU's alignment fix (block/file-posix.c, 2026-11-05) a third of iOS 26's writes
                // failed this way: this phone's f2fs with inline encryption fails a buffer that is not
                // 4 KiB-aligned with EIO, which QEMU's probe took for "aligned"; since then 0 of 2,833
                "-drive", "file=" + (sb != null ? sb.disk : userDisk(c, os)).getPath() + ",if=none,id=root,cache=" + ov.getOrDefault("cache", "none")
                        + ",discard=unmap,format=qcow2",
                "-object", "iothread,id=disk",
                // ios26.txt disk-iothread=on: the disk's requests and completions on their own thread, off the
                // main loop and the BQL; it moved them (bqlprof) but sped nothing up measurably (2026-11-05)
                "-device", "virtio-blk-pci,drive=root" + ("on".equals(ov.get("disk-iothread")) ? ",iothread=disk" : ""),
                "-qmp", "unix:" + qmpPath(c) + ",server,nowait",
                "-device", "qemu-xhci,id=xhci,conditional-intr-mapping=on,nonremovable=2"));
        // iOS 26 without a USB keyboard, so that iOS shows its own (kbd26); a save made with one loads with it
        boolean kbd = watch || sb != null || resumed && !savedNoKbdFile(c, loadTag).isFile();
        kbd26 = kbd;
        unplug26 = kbd && !watch && sb == null;
        if (kbd) {
            cmd.add("-device");
            cmd.add("usb-kbd,bus=xhci.0,port=1,id=ukbd,idle-reports=off");
        }
        cmd.add("-device");
        cmd.add("usb-touchscreen,bus=xhci.0,port=2,id=utouch");
        AppLog.note(c, "keyboard: " + (!kbd ? "iOS's own (no USB keyboard)" : unplug26 ? "the save has a USB keyboard: unplugged once it runs"
                : "a USB keyboard"));
        if (watch) {
            WatchProfile.addButtons(cmd);
            WatchProfile.addNetwork(cmd);
        } else {
            cmd.add("-device");
            cmd.add("usb-consumer,bus=xhci.0,port=3,id=ucons");
        }
        cmd.add("-no-reboot");
        if ((c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // tests: a second QMP for adb (run-as ... toybox nc -U), the app keeps the first
            cmd.add("-qmp");
            cmd.add("unix:" + new File(run, "qmp-test.sock").getPath() + ",server,nowait");
        }
        cmd.addAll(extra);
        if (resumed) {
            cmd.add("-loadvm");
            cmd.add(loadTag);
        }
        Map<String, String> env = new java.util.HashMap<>(System.getenv());
        Emu.baseEnv(c, links, env);
        env.put("ASOFT_FRAME_FD", String.valueOf(frameFd));
        env.put("VK_DRIVER_FILES", icd.getPath());
        env.put("REIMS_VGPU_LOG", "info");
        env.put("RUST_BACKTRACE", "1");
        env.put("ASOFT_PAGE_BITS", "14");
        String missing = nativeMissing();
        if (missing.isEmpty()) {
            env.put("ASOFT_NATIVE_EL0", "1");
            env.put("ASOFT_NATIVE_EL0_TPIDRRO", "1");
            env.put("ASOFT_NATIVE_EL0_STUBS", watch ? WatchProfile.STUBS : "24");
            env.put("ASOFT_NATIVE_EL0_DELAY_MS", "20000");
            env.put("ASOFT_NATIVE_EL0_PAC", "off");
            env.put("ASOFT_NATIVE_EL0_STATS", "1");
            env.put("ASOFT_NATIVE_EL0_OFF_FILE", new File(run, "native-off").getPath());
            if (!plan.pin.isEmpty()) {
                env.put("ASOFT_NATIVE_EL0_STUB_CPUS", plan.pin);
            }
        } else {
            AppLog.note(c, "native EL0 off: this CPU lacks " + missing + "; iOS runs under TCG only");
        }
        if (!plan.pin.isEmpty()) {
            env.put("ASOFT_VCPU_CPUS", plan.pin);
        }
        env.put("ASOFT_EL_SAMPLER", "30");
        env.put("ASOFT_LEDS_FILE", ledsFile(c).getPath());
        // A GPU check passed in the compatibility mode (GpuCheck.noSparse: Turnip's sparse binds from host memory
        // read back zeros, Adreno 750): reims' older views, which import the guest's RAM by pointer and bind
        // nothing sparse from it. The setup's own boots too: they draw through the same reims
        boolean noSparse = GpuCheck.noSparse(c);
        if (noSparse) {
            env.put("REIMS_VGPU_DEFERRED_VIEWS", "0");
        }
        for (Map.Entry<String, String> e : ov.entrySet()) {
            if (e.getKey().startsWith("env.")) {
                env.put(e.getKey().substring(4), e.getValue());
            }
        }
        // (an ios26.txt env.REIMS_VGPU_DEFERRED_VIEWS=1 of a test turns it back on: then it is no compatibility mode)
        compat26 = noSparse && "0".equals(env.get("REIMS_VGPU_DEFERRED_VIEWS"));
        compatSetupRun = sb != null;
        if (compat26) {
            AppLog.note(c, "gpu: compatibility mode (no sparse binds from host memory)");
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(run, "cmdline.txt")))) {
            w.println(String.join(" ", cmd));
        }
        List<String> envp = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            envp.add(e.getKey() + "=" + e.getValue());
        }
        if (sb == null) {
            setDirty(c, os, true);
        } else {
            new File(run, "console.sock").delete();
        }
        endingByApp = -1;
        noticedPid = -1;
        pid = Render.spawn(cmd.toArray(new String[0]), envp.toArray(new String[0]),
                new File(run, "qemu-stderr.txt").getPath(), c.getFilesDir().getPath());
        if (pid < 0) {
            throw new IOException("cannot start QEMU");
        }
        AppLog.note(c, osName(os) + " QEMU pid " + pid);
        state = resumed ? "resuming" : "booting";
        track(os, sb != null, resumed ? "the save " + loadTag : "a boot", logAt, smp, bootRamMiB, fb26W + "x" + fb26H,
                Emu.qemu26Path(c));
        connectQmp(c, pid);
    }

    /**
     * Quick boot's save: pause iOS, write its whole state into the user disk, quit QEMU.
     * Null when saved, else why not (QEMU is stopped either way). Not synchronized: it takes
     * tens of seconds, and the screen asks running() every frame.
     */
    /** An automatic save may be running (AutoSave): a close or a turn off waits for it, at most 2 minutes. */
    private static void waitForSave() {
        for (int i = 0; i < 1200 && saving.get(); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Save and close only (never saveNamed or the automatic save, after which iOS goes on): QEMU paused, the
     * release-ram capability makes the save free each page of iOS's RAM once it is written, so Android's
     * memory falls during the save instead of every page it had swapped out coming back at once (lmkd ended
     * the app, and the save, that way). QEMU then refuses to run that machine again; it only quits or loads.
     * An engine that refuses the capability saves as before, and so does one that accepts it without the
     * release (iOS 16's libqemu.so, iOS 26 engines from before 2026-11-10).
     */
    private static void releaseRamWhileSaving(Context c, Qmp q) {
        String r = q.call("{\"execute\":\"migrate-set-capabilities\",\"arguments\":{\"capabilities\":"
                + "[{\"capability\":\"release-ram\",\"state\":true}]}}", 10000);
        AppLog.note(c, "save and close: release-ram " + (r == null ? "got no answer"
                : r.contains("\"error\"") ? "refused: " + r : "set"));
    }

    static String saveAndQuit(Context c) {
        waitForSave();
        if (!saving.compareAndSet(false, true)) {
            return "already saving";
        }
        try {
            Qmp q = qmp;
            int p = pid;
            if (q == null || !Render.alive(p)) {
                return "iOS is not running";
            }
            state = "saving";
            int forOs = runningOs;
            long t0 = System.currentTimeMillis();
            String why = null;
            if (q.call("{\"execute\":\"stop\"}", 30000) == null) {
                why = "stop: no answer";
            } else {
                logDiskStats(c, q);
                releaseRamWhileSaving(c, q);
                MemoryCleaner.beforeSave(c);
                why = saveQuick(c, q);
            }
            long saved = System.currentTimeMillis() - t0;
            if (why == null) {
                setQuickStale(c, forOs, false);     // a new quick save replaced the stale one
            }
            appEnds(p);     // (not before the save: QEMU ending in savevm is a crash)
            q.call("{\"execute\":\"quit\"}", 10000);
            for (int i = 0; i < 100 && Render.alive(p); i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (Render.alive(p)) {
                stop();
            } else {
                forget();
                setDirty(c, forOs, false);     // QEMU flushed the disk and ended by itself
            }
            return why != null ? why : "saved in " + saved + " ms";
        } finally {
            saving.set(false);
        }
    }

    /**
     * A named save while iOS goes on: iOS pauses for it (~25 s) and continues. Null when saved,
     * else why not. Not on the UI thread.
     */
    static String saveNamed(Context c, String tag) {
        if (!saving.compareAndSet(false, true)) {
            return "already saving";
        }
        try {
            Qmp q = qmp;
            if (q == null || !running()) {
                return "iOS is not running";
            }
            if (isQuick(tag)) {
                String why = saveQuick(c, q);
                if (why == null) {
                    setQuickStale(c, runningOs, false);     // AutoSave's: it replaced a stale one
                }
                return why;
            }
            synchronized (KBD) {
                String r = q.hmp("savevm " + tag, 600000);
                if (r == null || !r.replace(" ", "").equals("{\"return\":\"\"}")) {
                    return "savevm: " + r;
                }
                keepLeds(c, tag);
            }
            return null;
        } finally {
            saving.set(false);
        }
    }

    /**
     * Turn off without a save. iOS 16: iOS's own shutdown through QEMU's power key (system_powerdown;
     * xnu 0100: every process gets SIGTERM, the disks are synced and unmounted, then PSCI
     * SYSTEM_OFF ends QEMU), as holding an iPhone's side button and sliding does; at most 60 s,
     * then QEMU is stopped. iOS 26 and the watch: the guest's sync on its root shell, then QEMU quits
     * (vresearch has no power key; the PC launcher does the same). The quick-boot state stays, marked stale: never loaded by itself (that would put
     * the disk back to that close), so the next start boots from the disk as iOS left it; it is offered as a
     * way back (goBack) until the next quick save replaces it. Not on the UI thread.
     */
    static String powerOff(Context c) {
        waitForSave();
        if (!saving.compareAndSet(false, true)) {
            return "busy saving";
        }
        try {
            Qmp q = qmp;
            int p = pid;
            if (!Render.alive(p)) {
                return "iOS is not running";
            }
            appEnds(p);
            state = "turning off";
            int forOs = runningOs;
            // from here on the quick save is not where iOS is: never loaded again, even if its delete fails
            setQuickStale(c, forOs, true);
            logDiskStats(c, q);
            long t0 = System.currentTimeMillis();
            boolean clean = false;
            String synced = null;
            if (forOs != IOS16) {
                // vresearch has no power key: system_powerdown reaches nothing there (no powerdown notifier in
                // hw/arm/vresearch.c), and the guest has no launchctl/reboot/halt for its own shutdown. So, as the PC
                // launcher does (Qmp.SerialSync): the guest's sync on its root shell, then QEMU stops and quits (its
                // own caches flushed). Open apps close without their own save, as a pulled battery would.
                try (GuestConsole con = new GuestConsole(consoleSocket(c))) {
                    String r = con.run("sync", 45_000, 45_000);
                    synced = r == null ? "no answer from the guest's shell in 45 s" : null;
                }
                long syncMs = System.currentTimeMillis() - t0;
                if (q != null) {
                    q.call("{\"execute\":\"stop\"}", 10000);
                    q.call("{\"execute\":\"quit\"}", 10000);
                }
                for (int i = 0; i < 300 && Render.alive(p); i++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                clean = !Render.alive(p);
                AppLog.note(c, "turn off: " + (synced == null ? "guest synced in " + syncMs + " ms" : synced)
                        + (clean ? ", QEMU quit" : ", QEMU did not quit"));
            } else if (q != null && q.call("{\"execute\":\"system_powerdown\"}", 10000) != null) {
                for (int i = 0; i < 600 && Render.alive(p); i++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                clean = !Render.alive(p);
            }
            if (clean) {
                forget();
                setDirty(c, forOs, synced != null);     // without the guest's sync the next start checks the disk
            } else {
                stopAndWait();
            }
            long ms = System.currentTimeMillis() - t0;
            return (clean ? "turned off in " + ms + " ms" : "iOS did not turn off; QEMU stopped after " + ms + " ms")
                    + "; the quick save stays as a way back";
        } finally {
            saving.set(false);
        }
    }

    static boolean turningOff() {
        return "turning off".equals(state);
    }

    /** QEMU has ended by itself (and was reaped): let go of it without signalling a pid that may be reused. */
    private static synchronized void forget() {
        if (qmp != null) {
            qmp.close();
            qmp = null;
        }
        pid = -1;
        state = "stopped";
        // iOS 16's RAM is this app's memfd: its pages go back to the phone now (Render.releaseGuestRam)
        Render.releaseGuestRam();
    }

    static boolean saving() {
        return saving.get();
    }

    /** stop(), and wait (at most 30 s) until QEMU has gone and let go of the user disk. */
    static void stopAndWait() {
        int p = pid;
        appEnds(p);
        if (ios26Running()) {
            Qmp q;
            synchronized (Guest.class) {
                q = qmp;
                qmp = null;
            }
            end26(q, p);
        }
        Render.terminate(p);
        for (int i = 0; i < 300 && Render.alive(p); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        boolean ended = !Render.alive(p);
        stop();
        if (ended) {
            Render.releaseGuestRam();       // as forget(): QEMU is gone, its RAM's pages need not stay
        }
    }

    /**
     * Ends iOS 26's QEMU, which does not end on SIGTERM (reims' teardown waits for GPU work that
     * never finishes once the vCPUs stop). A bare SIGKILL loses what qcow2 keeps in QEMU's memory
     * (the user disk's L2 and refcount tables): the next boot found iOS's Data volume corrupted
     * (2026-11-05). So QMP stop first, which pauses the vCPUs and flushes every disk, then quit,
     * SIGKILL last. Up to ~20 s: not on the UI thread.
     */
    /** iOS 26's ends in progress (end26 on its thread): a start waits for them. */
    private static final java.util.concurrent.atomic.AtomicInteger ending26 = new java.util.concurrent.atomic.AtomicInteger();

    private static void end26(Qmp q, int p) {
        ending26.incrementAndGet();
        try {
            end26Now(q, p);
        } finally {
            ending26.decrementAndGet();
        }
    }

    private static void end26Now(Qmp q, int p) {
        boolean asked = false;
        if (q != null && Render.alive(p)) {
            Context c = appContext;
            if (c != null) {
                logDiskStats(c, q);
            }
            asked = q.call("{\"execute\":\"stop\"}", 15000) != null;
            q.call("{\"execute\":\"quit\"}", 3000);
        }
        for (int i = 0; i < 50 && Render.alive(p); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (Render.alive(p)) {
            Render.forceKill(p);
        } else if (asked && appContext != null) {
            setDirty(appContext, runningOs, false);     // stop flushed the disk, quit ended QEMU
        }
        if (q != null) {
            q.close();
        }
    }

    static synchronized void stop() {
        if (pid > 0) {
            appEnds(pid);
        }
        if (ios26Running() && pid > 0) {
            Qmp q = qmp;
            int p = pid;
            qmp = null;
            pid = -1;
            state = "stopped";
            ending26.incrementAndGet();     // counted before the thread runs: a start right after waits
            Thread t = new Thread(() -> {
                try {
                    end26(q, p);
                } finally {
                    ending26.decrementAndGet();
                }
            }, "qemu-end");
            t.start();
            return;
        }
        endingPid = pid;
        Render.terminate(pid);
        if (qmp != null) {
            qmp.close();
            qmp = null;
        }
        pid = -1;
        state = "stopped";
    }
}
