package com.asoft.ios;

import android.content.Context;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * iOS 26 and watchOS from Apple, on this phone: what the PC's ios26/Install-iOS26.ps1 and
 * watchos/Install-watchOS.ps1 do, in one work folder laid out as theirs (files/setup/a26 is their <Work>),
 * with the same step bodies (asoft-ios ios26/steps and watchos/steps, shipped in the app's assets), run in
 * the helper VM as the PC's -Linux helper mode runs them. What the PC does on Windows is done here: Apple's
 * AEA images decrypted and IM4Ps unwrapped in Java (Unpack), the Python tools in the helper VM (its python3),
 * iOS's boots by the app's own iOS 26 QEMU (Guest.startSetup) with the guest's shell on a socket.
 *
 *   shared   once for both: cloudOS 26.4b's kernel, SPTM, TXM, device tree and ramdisk, darwin-vm's sysroot,
 *            the GraphicLoader plugin; unpacked; the shell ramdisk, the device trees (the phone's 19.2 MHz
 *            ones), the kernel with OpenGL ES's keys and the guest GPU driver (out of the cloudOS OS image,
 *            which goes once the driver is out)
 *   ios26    the iPhone17,3 images, decrypted; rootfs + trust cache, partition + tweaks, first boot, persona,
 *            activation, apps, gpu, the wallpaper boot (DataMigration), the phone's locale; packed into
 *            files/ios26 (Guest.dir26) as one compressed disk with the Always On Display tree and trust cache
 *   watchos  the watch's image, decrypted, its device tree; the watch kernel and trees and its GPU driver;
 *            rootfs, patches + trust cache, first boot, activation + locale; packed into files/watchos
 * Every step leaves a marker (files/setup/a26/phone/GROUP-STEP.done), so a run that stopped goes on at its
 * step; downloaded and intermediate files go as soon as no later step needs them.
 */
final class PhoneSetup26 {
    private PhoneSetup26() {}

    static final String SHARED = "shared", IOS26 = "ios26", WATCH = "watchos";

    /** Each group's steps, in order, and what the screen calls them. */
    static final String[][] SHARED_STEPS = {
            {"fetch", "Shared files from Apple (cloudOS 26.4) and GitHub, about 1 GB"},
            {"unpack", "Unpack the kernel, firmware and device tree"},
            {"build", "Ramdisk, device trees and the GPU driver (helper VM)"},
    };
    static final String[][] IOS26_STEPS = {
            {"fetch", "iOS 26.6.2 from Apple, about 10.2 GB"},
            {"decrypt", "Decrypt the iOS images"},
            {"rootfs", "Copy iOS onto its disk (helper VM, about 30 min)"},
            {"partition", "Partition the disk, prefs and keybag (helper VM)"},
            {"firstboot", "First boot of iOS (its persona files)"},
            {"persona", "Persona and MobileGestalt (helper VM)"},
            {"activation", "Activation (helper VM)"},
            {"apps", "Apps, dyld cache patches, gestures (helper VM)"},
            {"gpu", "The GPU driver on the disk (helper VM)"},
            {"wallpaper", "Second boot: DataMigration and the wallpaper (about an hour)"},
            {"locale", "This phone's time zone, region and clock (helper VM)"},
            {"pack", "Pack the iOS disk into the app"},
    };
    static final String[][] WATCH_STEPS = {
            {"fetch", "watchOS 27.2 from Apple, about 5 GB"},
            {"decrypt", "Decrypt the watchOS image"},
            {"prep", "Watch kernel, device tree and GPU driver (helper VM)"},
            {"rootfs", "Copy watchOS onto its disk (helper VM, about 30 min)"},
            {"patches", "Patches, apps and the GPU driver on it (helper VM)"},
            {"firstboot", "First boot of watchOS (until the watch face)"},
            {"activation", "Activation, time zone and region (helper VM)"},
            {"pack", "Pack the watch's disk into the app"},
    };

    static String[][] steps(String group) {
        return SHARED.equals(group) ? SHARED_STEPS : IOS26.equals(group) ? IOS26_STEPS : WATCH_STEPS;
    }

    static File work(Context c) {
        return new File(c.getFilesDir(), "setup/a26");
    }

    private static File in(Context c, String rel) {
        return new File(work(c), rel);
    }

    private static File mark(Context c, String group, String step) {
        return new File(work(c), "phone/" + group + "-" + step + ".done");
    }

    static boolean done(Context c, String group, String step) {
        return mark(c, group, step).isFile();
    }

    private static void setDone(Context c, String group, String step) throws IOException {
        File m = mark(c, group, step);
        m.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(m)) {
            o.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** The group is installed in the app (its pack step is done and its folder has its disk). */
    static boolean installed(Context c, String group) {
        return IOS26.equals(group) ? new File(c.getFilesDir(), "ios26/" + Guest.AOD26_BASE).isFile() && done(c, IOS26, "pack")
                : WATCH.equals(group) ? new File(c.getFilesDir(), "watchos/" + WatchProfile.BASE).isFile() && done(c, WATCH, "pack")
                : done(c, SHARED, "build");
    }

    /** What the user asked to install (Set up's choice, kept for a resume). */
    static boolean wants(Context c, String group) {
        return c.getSharedPreferences("setup", Context.MODE_PRIVATE).getBoolean("a26-" + group, false);
    }

    static void setWants(Context c, boolean ios26, boolean watch) {
        c.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("a26-" + IOS26, ios26)
                .putBoolean("a26-" + WATCH, watch).apply();
    }

    /** @group was removed from the phone (Installer.remove): Set up no longer counts it as asked for, nor offers its Resume. */
    static void dropWant(Context c, String group) {
        c.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().remove("a26-" + group).commit();
    }

    /** A setup of @group was asked for and has not finished: Resume goes on with it, on the shared part. */
    static boolean pending(Context c, String group) {
        return wants(c, group) && !installed(c, group) && work(c).isDirectory();
    }

    /**
     * What of the work folder is @group's own, IOS26 or WATCH (never the shared part's, nor the other's): for iOS 26 its
     * downloads (iphone/), decrypted images (os/), the disks its steps build at the top (root26*.qcow2, root.qcow2) with
     * their trust caches and hashes (root*.tc, root26.qcow2.hashes); for the watch its folder (watchos/: downloads,
     * images, kernel, trees, GPU driver copy, disks); for either its helper VM jobs (install/helper/) and their logs
     * (phone/logs/), by its jobs' names, and its step marks. Installer.remove deletes these, so that Set up installs it
     * afresh while the shared part stays for the other.
     */
    static List<File> own(Context c, String group) {
        List<File> out = new ArrayList<>();
        File w = work(c);
        String jobs;            // the start of its jobs' names (Job)
        if (IOS26.equals(group)) {
            out.add(in(c, "iphone"));
            out.add(in(c, "os"));
            File[] top = w.listFiles((d, n) -> n.startsWith("root"));
            for (File f : top == null ? new File[0] : top) {
                out.add(f);
            }
            jobs = "ios26-";
        } else {
            out.add(in(c, "watchos"));
            jobs = "watch-";
        }
        for (String rel : new String[] {"phone/logs", "install/helper"}) {
            File[] l = in(c, rel).listFiles((d, n) -> n.startsWith(jobs));
            for (File f : l == null ? new File[0] : l) {
                out.add(f);
            }
        }
        for (String[] s : steps(group)) {
            out.add(mark(c, group, s[0]));
        }
        return out;
    }

    // -- space ------------------------------------------------------------------------------------------

    /**
     * GB (10^9) each step adds on top of what is there, at most (the PC's measured $Space, Install-iOS26.ps1 and
     * Install-watchOS.ps1, with the phone's differences: a decrypt frees its input as it goes, the disk is packed).
     */
    private static double addGB(String group, String step) {
        switch (group + ":" + step) {
            case "shared:fetch": return 1.1;
            case "shared:unpack": return 0.4;
            case "shared:build": return 1.6;
            case "ios26:fetch": return 10.3;
            case "ios26:decrypt": return 1.0;
            case "ios26:rootfs": return 16.0;
            case "ios26:partition": return 18.5;
            case "ios26:firstboot": return 1.5;
            case "ios26:apps": return 3.5;
            case "ios26:gpu": return 0.5;
            case "ios26:wallpaper": return 3.0;
            case "ios26:pack": return 12.0;
            case "watchos:fetch": return 5.1;
            case "watchos:decrypt": return 1.0;
            case "watchos:rootfs": return 13.0;
            case "watchos:patches": return 1.5;
            case "watchos:firstboot": return 1.5;
            case "watchos:pack": return 4.0;
            default: return 0.2;
        }
    }

    /** Bytes the next step needs free (its addGB and 1.5 GB of margin), for the screen and the check. */
    static long needBytes(Context c) {
        for (String g : groups(c)) {
            for (String[] s : steps(g)) {
                if (!done(c, g, s[0])) {
                    return (long) ((addGB(g, s[0]) + 1.5) * 1e9);
                }
            }
        }
        return 0;
    }

    /** A fetch step of this run is not done yet (the only steps that need the network). */
    static boolean downloadLeft(Context c) {
        for (String g : groups(c)) {
            if (!done(c, g, "fetch")) {
                return true;
            }
        }
        return false;
    }

    /** The groups a run makes, in order: the shared part first, then what was asked for. */
    static List<String> groups(Context c) {
        List<String> g = new ArrayList<>();
        g.add(SHARED);
        if (wants(c, WATCH)) {
            g.add(WATCH);       // the smaller one first: the user has something to start sooner
        }
        if (wants(c, IOS26)) {
            g.add(IOS26);
        }
        return g;
    }

    /** The lowest free space seen since the run started, for the screen's "peak" line. */
    static volatile long startFree = -1, lowestFree = -1;

    // -- the run ----------------------------------------------------------------------------------------

    static void run(Context c, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled, IpswFetch[] current) throws IOException {
        work(c).mkdirs();
        if (!GpuCheck.passed(c)) {
            throw new IOException("This phone's GPU has not passed the GPU check: iOS 26 and watchOS need it.");
        }
        JSONObject m = PhoneSetup.manifest(c);
        startFree = c.getFilesDir().getUsableSpace();
        lowestFree = startFree;
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                lowestFree = Math.min(lowestFree, c.getFilesDir().getUsableSpace());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "setup26-space");
        sampler.setDaemon(true);
        sampler.start();
        try {
            for (String g : groups(c)) {
                for (String[] s : steps(g)) {
                    if (done(c, g, s[0])) {
                        continue;
                    }
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("stopped");
                    }
                    long need = (long) ((addGB(g, s[0]) + 1.5) * 1e9), free = c.getFilesDir().getUsableSpace();
                    if (free < need) {
                        throw new IOException(String.format(Locale.ROOT, "Not enough room: %.1f GB free, the next step needs about %.1f GB."
                                + " Free some space, then tap Resume.", free / 1e9, need / 1e9));
                    }
                    long t0 = System.currentTimeMillis();
                    p.step(g + ":" + s[0], 0, s[1]);
                    step(c, m, g, s[0], cpus, p, cancelled, current);
                    setDone(c, g, s[0]);
                    AppLog.note(c, String.format(Locale.ROOT, "setup26: %s:%s done in %d s, %.1f GB free (lowest %.1f)", g, s[0],
                            (System.currentTimeMillis() - t0) / 1000, c.getFilesDir().getUsableSpace() / 1e9, lowestFree / 1e9));
                }
            }
        } finally {
            sampler.interrupt();
            lowestFree = Math.min(lowestFree, c.getFilesDir().getUsableSpace());
        }
        AppLog.note(c, String.format(Locale.ROOT, "setup26: peak use %.1f GB (%.1f GB free at the start, %.1f at the lowest)",
                (startFree - lowestFree) / 1e9, startFree / 1e9, lowestFree / 1e9));
    }

    private static void step(Context c, JSONObject m, String g, String s, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled,
            IpswFetch[] current) throws IOException {
        String id = g + ":" + s;
        switch (id) {
            case "shared:fetch": sharedFetch(c, m, p, cancelled, current); break;
            case "shared:unpack": sharedUnpack(c, m, p, cancelled); break;
            case "shared:build": sharedBuild(c, m, cpus, p, cancelled); break;
            case "ios26:fetch": osFetch(c, m, IOS26, "iphone", p, cancelled, current); break;
            case "ios26:decrypt": iosDecrypt(c, m, p, cancelled); break;
            case "ios26:rootfs": iosRootfs(c, m, cpus, p, cancelled); break;
            case "ios26:partition": iosPartition(c, m, cpus, p, cancelled); break;
            case "ios26:firstboot": iosFirstBoot(c, p, cancelled); break;
            case "ios26:persona": iosPersona(c, m, cpus, p, cancelled); break;
            case "ios26:activation": iosActivation(c, m, cpus, p, cancelled); break;
            case "ios26:apps": iosApps(c, m, cpus, p, cancelled); break;
            case "ios26:gpu": iosGpu(c, m, cpus, p, cancelled); break;
            case "ios26:wallpaper": iosWallpaper(c, p, cancelled); break;
            case "ios26:locale": iosLocale(c, m, cpus, p, cancelled); break;
            case "ios26:pack": iosPack(c, p); break;
            case "watchos:fetch": osFetch(c, m, WATCH, "watchos/ipsw", p, cancelled, current); break;
            case "watchos:decrypt": watchDecrypt(c, m, p, cancelled); break;
            case "watchos:prep": watchPrep(c, m, cpus, p, cancelled); break;
            case "watchos:rootfs": watchRootfs(c, m, cpus, p, cancelled); break;
            case "watchos:patches": watchPatches(c, m, cpus, p, cancelled); break;
            case "watchos:firstboot": watchFirstBoot(c, p, cancelled); break;
            case "watchos:activation": watchActivation(c, m, cpus, p, cancelled); break;
            case "watchos:pack": watchPack(c, p); break;
            default: throw new IOException("no step " + id);
        }
    }

    // -- the manifest -------------------------------------------------------------------------------------

    private static JSONObject group(JSONObject m, String g) throws IOException {
        try {
            return SHARED.equals(g) ? m.getJSONObject("shared") : m.getJSONObject("os").getJSONObject(g);
        } catch (JSONException e) {
            throw new IOException("asoft-setup.json has no " + g + ": " + e.getMessage());
        }
    }

    /** The placeholders for a group's bodies and job scripts: the shared vars, the group's, then @extra. */
    private static Map<String, String> vars(JSONObject m, String g, Map<String, String> extra) throws IOException {
        Map<String, String> v = new LinkedHashMap<>();
        for (String k : new String[] {SHARED, g}) {
            JSONObject o = group(m, k).optJSONObject("vars");
            if (o != null) {
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String key = it.next();
                    v.put(key, o.optString(key));
                }
            }
        }
        if (extra != null) {
            v.putAll(extra);
        }
        return v;
    }

    private static String fill(String t, Map<String, String> v) {
        for (Map.Entry<String, String> e : v.entrySet()) {
            t = t.replace("{" + e.getKey() + "}", e.getValue());
        }
        return t;
    }

    private static String var(JSONObject m, String g, String key) throws IOException {
        String s = vars(m, g, null).get(key);
        if (s == null || s.isEmpty()) {
            throw new IOException("asoft-setup.json: no " + key + " for " + g);
        }
        return s;
    }

    // -- downloads ------------------------------------------------------------------------------------------

    /** A file of the manifest's shared.files (sysroot, dtFixup, gpuPlugin): fetched whole, its SHA-256 checked. */
    private static void urlFile(Context c, JSONObject m, String key, String rel, PhoneSetup.Progress p) throws IOException {
        JSONObject f;
        try {
            f = group(m, SHARED).getJSONObject("files").getJSONObject(key);
        } catch (JSONException e) {
            throw new IOException("asoft-setup.json: shared.files." + key);
        }
        File out = in(c, rel);
        String want = f.optString("sha256");
        if (out.isFile() && want.equalsIgnoreCase(Unpack.sha256(out))) {
            return;
        }
        p.log("fetching " + f.optString("url"));
        byte[] b = Unpack.get(f.optString("url"), 64 << 20);
        out.getParentFile().mkdirs();
        File part = new File(out.getPath() + ".part");
        java.nio.file.Files.write(part.toPath(), b);
        if (!want.equalsIgnoreCase(Unpack.sha256(part))) {
            part.delete();
            throw new IOException(key + ": the download has another SHA-256 than the pinned one");
        }
        if (!part.renameTo(out)) {
            throw new IOException("cannot write " + out);
        }
    }

    /** The members of a group's IPSW (FILE items, named as the PC installers name them), fetched with IpswFetch. */
    private static void members(Context c, String url, List<String[]> want, PhoneSetup.Progress p, BooleanSupplier cancelled,
            IpswFetch[] current) throws IOException {
        IpswFetch f = new IpswFetch(url, new IpswFetch.Listener() {
            @Override
            public void progress(IpswFetch.Item item) {
            }

            @Override
            public void log(String line) {
                p.log(line);
            }
        });
        current[0] = f;
        List<IpswFetch.Item> items = new ArrayList<>();
        for (String[] w : want) {
            File out = in(c, w[1]);
            out.getParentFile().mkdirs();
            items.add(new IpswFetch.Item(w[0], out, IpswFetch.Kind.FILE, 0));
        }
        Thread watch = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (cancelled.getAsBoolean()) {
                    f.stop();
                }
                p.fetch(items, f);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "setup26-fetch-watch");
        watch.start();
        try {
            IpswFetch.Index ix = f.index();
            f.prepare(ix, items);
            f.run(items);
        } finally {
            watch.interrupt();
            p.fetch(items, f);
            current[0] = null;
        }
    }

    private static void sharedFetch(Context c, JSONObject m, PhoneSetup.Progress p, BooleanSupplier cancelled, IpswFetch[] current)
            throws IOException {
        urlFile(c, m, "sysroot", "downloads/ios_sysroot.tar.gz", p);
        urlFile(c, m, "dtFixup", "tools/qemu-sptm/scripts/darwin/vresearch_dt_fixup.py", p);
        urlFile(c, m, "gpuPlugin", "downloads/" + var(m, SHARED, "PLUGIN"), p);
        JSONObject src;
        JSONArray ms;
        try {
            src = group(m, SHARED).getJSONObject("sources").getJSONObject("cloudos");
            ms = src.getJSONArray("members");
        } catch (JSONException e) {
            throw new IOException("asoft-setup.json: shared.sources.cloudos");
        }
        List<String[]> want = new ArrayList<>();
        for (int i = 0; i < ms.length(); i++) {
            String n = ms.optJSONObject(i).optString("name");
            want.add(new String[] {n, "cloudos/" + n});
        }
        members(c, src.optString("url"), want, p, cancelled, current);
    }

    private static void osFetch(Context c, JSONObject m, String g, String dir, PhoneSetup.Progress p, BooleanSupplier cancelled,
            IpswFetch[] current) throws IOException {
        JSONObject src;
        JSONArray ms;
        try {
            JSONObject sources = group(m, g).getJSONObject("sources");
            src = sources.getJSONObject(IOS26.equals(g) ? "iphone" : "watch");
            ms = src.getJSONArray("members");
        } catch (JSONException e) {
            throw new IOException("asoft-setup.json: " + g + "'s sources");
        }
        List<String[]> want = new ArrayList<>();
        for (int i = 0; i < ms.length(); i++) {
            String n = ms.optJSONObject(i).optString("name");
            // into iphone/ (or watchos/ipsw/) without the member's folder, as the PC installers put them
            String leaf = n.substring(n.lastIndexOf('/') + 1);
            // an image decrypted already needs no download again (a resume after the decrypt step began)
            String plain = (IOS26.equals(g) ? "os/" : "watchos/os/") + leaf.replace(".aea", "");
            if (leaf.endsWith(".aea") && in(c, plain).isFile() && !new File(in(c, plain).getPath() + ".progress").isFile()) {
                continue;
            }
            want.add(new String[] {n, dir + "/" + leaf});
        }
        if (!want.isEmpty()) {
            members(c, src.optString("url"), want, p, cancelled, current);
        }
    }

    // -- decrypting, unpacking ---------------------------------------------------------------------------------

    private static Unpack.KeySource keys(Context c, JSONObject m, PhoneSetup.Progress p) throws IOException {
        JSONObject f;
        try {
            f = group(m, SHARED).getJSONObject("files").getJSONObject("fcsKeys");
        } catch (JSONException e) {
            throw new IOException("asoft-setup.json: shared.files.fcsKeys");
        }
        File cache = in(c, "downloads");
        cache.mkdirs();
        return Unpack.keySource(cache, f.optString("url"), f.optString("sha256"), p::log);
    }

    /** @aea into @plain (freeing the encrypted bytes as it goes), then the .aea deleted. */
    private static void decrypt(Context c, JSONObject m, String g, String aeaRel, String plainRel, int from, int to,
            PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        File aea = in(c, aeaRel), plain = in(c, plainRel);
        if (plain.isFile() && !new File(plain.getPath() + ".progress").isFile() && !aea.isFile()) {
            return;         // done before
        }
        if (!aea.isFile()) {
            throw new IOException(aeaRel + " is not there: tap Resume to download it again");
        }
        plain.getParentFile().mkdirs();
        Unpack.AeaHead h = Unpack.head(aea);
        byte[] key = Unpack.key(h, keys(c, m, p));
        String name = plain.getName();
        long t0 = System.currentTimeMillis();
        long size = Unpack.decryptAea(aea, plain, key, true, done -> {
            long total = Math.max(1, aea.length());
            p.step(g + ":decrypt", from + (int) Math.min(to - from, (to - from) * done / (total * 1.05)),
                    String.format(Locale.ROOT, "Decrypting %s: %.1f GB", name, done / 1e9));
            holdWhileHot(c, cancelled);
        }, cancelled);
        AppLog.note(c, String.format(Locale.ROOT, "setup26: %s decrypted, %.2f GB in %d s", name, size / 1e9,
                (System.currentTimeMillis() - t0) / 1000));
        aea.delete();
        IpswFetch.doneFile(new IpswFetch.Item("", aea, IpswFetch.Kind.FILE, 0)).delete();
        IpswFetch.okFile(new IpswFetch.Item("", aea, IpswFetch.Kind.FILE, 0)).delete();
    }

    private static void sharedUnpack(Context c, JSONObject m, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        String[][] w = {
                {"cloudos/Firmware/all_flash/DeviceTree.vphone600ap.im4p", "cloud/vphone600ap.adt"},
                {"cloudos/kernelcache.research.vphone600", "cloud/kernelcache.research.vphone600.decompressed"},
                {"cloudos/Firmware/sptm.vresearch1.release.im4p", "fw/sptm"},
                {"cloudos/Firmware/txm.iphoneos.research.im4p", "fw/txm"},
                {"cloudos/" + var(m, SHARED, "RAMDISK"), "install/work/rd.apfs"},
        };
        int i = 0;
        for (String[] x : w) {
            p.step("shared:unpack", 10 + 15 * i++, "Unpacking " + x[0].substring(x[0].lastIndexOf('/') + 1));
            File out = in(c, x[1]);
            out.getParentFile().mkdirs();
            String how = Unpack.im4p(in(c, x[0]), out);
            p.log(x[1] + ": " + how + ", " + out.length() + " bytes");
        }
        java.nio.file.Files.copy(in(c, "cloud/vphone600ap.adt").toPath(), in(c, "fw/dtree.orig").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        byte[] magic = new byte[4];
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(in(c, "cloud/kernelcache.research.vphone600.decompressed"), "r")) {
            f.readFully(magic);
        }
        if ((magic[0] & 0xff) != 0xcf || (magic[1] & 0xff) != 0xfa || (magic[2] & 0xff) != 0xed || (magic[3] & 0xff) != 0xfe) {
            throw new IOException("the kernelcache did not unpack to a Mach-O");
        }
        // the GPU driver's image: its key is in its own header
        p.step("shared:unpack", 85, "Decrypting cloudOS's OS image (the GPU driver is in it)");
        String img = var(m, SHARED, "IMG");
        decrypt(c, m, SHARED, "cloudos/" + img + ".aea", "install/work/gpu-img/" + img, 85, 99, p, cancelled);
        in(c, "cloudos/" + var(m, SHARED, "RAMDISK")).delete();      // unwrapped into rd.apfs
    }

    private static void iosDecrypt(Context c, JSONObject m, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        String sys = var(m, IOS26, "SYSOS"), os = var(m, IOS26, "OS");
        decrypt(c, m, IOS26, "iphone/" + sys + ".aea", "os/" + sys, 1, 20, p, cancelled);
        decrypt(c, m, IOS26, "iphone/" + os + ".aea", "os/" + os, 20, 99, p, cancelled);
    }

    private static void watchDecrypt(Context c, JSONObject m, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        String os = var(m, WATCH, "OS");
        decrypt(c, m, WATCH, "watchos/ipsw/" + os + ".aea", "watchos/os/" + os, 1, 95, p, cancelled);
        p.step("watchos:decrypt", 97, "Unwrapping the watch's device tree");
        Unpack.im4p(in(c, "watchos/ipsw/DeviceTree.n207sap.im4p"), in(c, "watchos/ipsw/DeviceTree.n207sap.raw"));
    }

    // -- helper VM jobs -----------------------------------------------------------------------------------------

    /** A Linux job: its script (setup/phone/jobs/NAME.sh), disks, files in and out, as Run-Helper's. */
    private static final class Job {
        final String name, group;
        final List<String[]> disks = new ArrayList<>();     // {rel, ro|rw|new, at or null}
        final Map<String, Object> in = new LinkedHashMap<>();
        final List<String> out = new ArrayList<>();
        final List<String> scratch = new ArrayList<>();
        final Map<String, String> vars = new LinkedHashMap<>();
        long scratchBytes = 8L << 30, outBytes = 2L << 30;

        Job(String name, String group) {
            this.name = name;
            this.group = group;
        }

        Job disk(String rel, String mode) {
            disks.add(new String[] {rel, mode, null});
            return this;
        }

        Job disk(String rel, String mode, String at) {
            disks.add(new String[] {rel, mode, at});
            return this;
        }

        Job file(String rel) {
            in.put(rel, rel);           // resolved in the work folder
            return this;
        }
    }

    private static String asset(Context c, String name) throws IOException {
        try (InputStream s = c.getAssets().open(name)) {
            return new String(Io.readAll(s), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static void assetDir(Context c, String dir, String to, Map<String, String> v, Map<String, Object> files) throws IOException {
        String[] names = c.getAssets().list(dir);
        if (names == null || names.length == 0) {
            throw new IOException("this build has no " + dir + " (pull-setup.sh)");
        }
        for (String n : names) {
            String t = asset(c, dir + "/" + n);
            files.put(to + "/" + n, v == null ? t : fill(t, v));
        }
    }

    private static void addTree(File f, String rel, Map<String, Object> files) {
        if (f.isDirectory()) {
            File[] l = f.listFiles();
            if (l != null) {
                for (File x : l) {
                    addTree(x, rel + "/" + x.getName(), files);
                }
            }
        } else if (f.isFile()) {
            files.put(rel, f);
        }
    }

    private static void runJob(Context c, JSONObject m, Job j, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled)
            throws IOException {
        File jd = in(c, "install/helper/" + j.name);
        Helper.endLeftover(c, jd);
        Installer.deleteTree(jd);
        jd.mkdirs();
        boolean watch = WATCH.equals(j.group);
        Map<String, String> v = vars(m, j.group, j.vars);
        Map<String, Object> files = new LinkedHashMap<>();
        String s = watch ? "watchos/scripts" : "scripts";
        files.put(".job/run", "run.sh\n");
        StringBuilder links = new StringBuilder(), outs = new StringBuilder(), scr = new StringBuilder();
        List<Helper.Disk> disks = new ArrayList<>();
        int i = 0;
        for (String[] d : j.disks) {
            File f = in(c, d[0]);
            if ("new".equals(d[1]) || !f.isFile()) {
                if (!"new".equals(d[1])) {
                    throw new IOException(d[0] + " is not there (a step before makes it)");
                }
            }
            boolean qcow = f.getName().endsWith(".qcow2");
            if (!qcow && f.length() % 512 != 0) {
                throw new IOException(d[0] + " is " + f.length() + " bytes, not a multiple of 512: the helper VM cannot read it as a disk");
            }
            disks.add(new Helper.Disk("d" + i, f, qcow ? "qcow2" : "raw", "ro".equals(d[1])));
            links.append("d").append(i).append(' ').append(d[2] != null ? d[2] : d[0]).append('\n');
            i++;
        }
        files.put(".job/disks", links.toString());
        for (String o : j.out) {
            outs.append(o).append('\n');
        }
        files.put(".job/out", outs.toString());
        for (String x : j.scratch) {
            scr.append(x).append('\n');
        }
        files.put(".job/scratch", scr.toString());
        files.put("run.sh", "export ASOFT_S=" + s + "\n" + asset(c, "setup/phone/prelude.sh") + "\n"
                + fill(asset(c, "setup/phone/jobs/" + j.name + ".sh"), v));
        assetDir(c, "setup/steps/" + (watch ? "watchos" : "ios26"), "steps", v, files);
        // the scripts: ios26's, then (the watch) watchos's over them, as Install-watchOS.ps1's check step copies them
        assetDir(c, "setup/scripts/ios26", s, null, files);
        if (watch) {
            assetDir(c, "setup/scripts/watchos", s, null, files);
        }
        for (String n : new String[] {"locale.py"}) {
            files.put("phone/" + n, asset(c, "setup/phone/" + n));
        }
        for (Map.Entry<String, Object> e : j.in.entrySet()) {
            Object val = e.getValue();
            File f = val instanceof File ? (File) val : in(c, (String) val);
            if (!f.exists()) {
                throw new IOException(e.getKey() + " is not there (a step before makes it)");
            }
            addTree(f, e.getKey(), files);
        }
        File inTar = new File(jd, "in.tar"), outRaw = new File(jd, "out.raw");
        TarIo.write(inTar, files);
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(outRaw, "rw")) {
            r.setLength(j.outBytes);
        }
        List<Helper.Disk> all = new ArrayList<>();
        all.add(new Helper.Disk("in", inTar, "raw", true));
        all.add(new Helper.Disk("out", outRaw, "raw", false));
        File scratch = new File(jd, "scratch.qcow2");
        if (!j.scratch.isEmpty()) {
            qemuImg(c, "create", "-q", "-f", "qcow2", scratch.getPath(), String.valueOf(j.scratchBytes));
            all.add(new Helper.Disk("scratch", scratch, "qcow2", false));
        }
        all.addAll(disks);
        String stepId = j.group + ":" + currentStep;
        Helper.Listener l = new Helper.Listener() {
            @Override
            public void progress(int pct, String text) {
                p.step(stepId, pct, text);
            }

            @Override
            public void line(String text) {
                p.log(text);
                if (text.startsWith("time zone ") || text.startsWith("region ") || text.endsWith("-hour clock")) {
                    AppLog.note(c, "setup26: " + text.trim());
                }
            }
        };
        Helper.Result r = Helper.run(c, "run", all, cpus, jd, l, cancelled, true, Helper.ramMiB(c, Helper.FLOOR_WORK_IN_RAM));
        if (Helper.worthRetry(r, Helper.FLOOR_WORK_IN_RAM) && !cancelled.getAsBoolean()) {
            // the native engine for Linux is new (2026-11-08): once more under TCG, with the new disks made again;
            // after Android's memory killer with less RAM too
            int ram = Helper.retryRam(r, Helper.FLOOR_WORK_IN_RAM);
            AppLog.note(c, "setup26: job " + j.name + " failed (" + r.failure() + ", " + r.how() + "); again under TCG with "
                    + ram + " MiB");
            remakeNew(c, j);
            if (!j.scratch.isEmpty()) {
                scratch.delete();
                qemuImg(c, "create", "-q", "-f", "qcow2", scratch.getPath(), String.valueOf(j.scratchBytes));
            }
            r = Helper.run(c, "run", all, cpus, jd, l, cancelled, false, ram);
        }
        if (r.rc != 0) {
            // (its console and stderr went into Helper's failures for the setup's record: the screen says what it was)
            throw new IOException("the helper VM's " + j.name + " job failed: " + r.failure());
        }
        if (!j.out.isEmpty()) {
            if (r.outBytes <= 0) {
                throw new IOException("the helper VM's " + j.name + " job handed nothing back");
            }
            TarIo.read(outRaw, r.outBytes, work(c));
        }
        AppLog.note(c, "setup26: job " + j.name + " rc 0 in " + r.seconds + " s" + (r.nat ? " (native)" : " (TCG)"));
        // the job's console (what its scripts printed: time zone, region, crash lists) stays for later questions
        File logs = new File(work(c), "phone/logs");
        logs.mkdirs();
        File serial = new File(jd, "serial.txt");
        if (serial.isFile() && !serial.renameTo(new File(logs, j.name + ".txt"))) {
            AppLog.note(c, "setup26: could not keep " + serial);
        }
        Installer.deleteTree(jd);
    }

    /** The step that runs now, for the jobs' progress lines. */
    private static volatile String currentStep = "";

    private static final Map<String, String> NEW_SIZES = new LinkedHashMap<>();

    private static void remakeNew(Context c, Job j) throws IOException {
        for (String[] d : j.disks) {
            if ("new".equals(d[1])) {
                String size = NEW_SIZES.get(d[0]);
                in(c, d[0]).delete();
                qemuImg(c, "create", "-q", "-f", "qcow2", in(c, d[0]).getPath(), size);
            }
        }
    }

    private static void newDisk(Context c, String rel, String size) throws IOException {
        File f = in(c, rel);
        f.delete();
        f.getParentFile().mkdirs();
        NEW_SIZES.put(rel, size);
        qemuImg(c, "create", "-q", "-f", "qcow2", f.getPath(), size);
    }

    /** @rel, a new qcow2 overlay on @base (its backing name relative, as the PC launcher's). */
    private static void overlay(Context c, String base, String rel) throws IOException {
        File f = in(c, rel);
        f.delete();
        File b = in(c, base);
        if (!b.isFile()) {
            throw new IOException(base + " is not there (a step before makes it)");
        }
        String backing = b.getParentFile().equals(f.getParentFile()) ? b.getName() : b.getPath();
        String[] r = Emu.qemuImg(c, "create", "-q", "-f", "qcow2", "-b", backing, "-F", "qcow2", f.getPath());
        if (!"0".equals(r[0])) {
            throw new IOException("qemu-img create " + rel + ": " + r[1].trim());
        }
    }

    private static void qemuImg(Context c, String... args) throws IOException {
        String[] r = Emu.qemuImg(c, args);
        if (!"0".equals(r[0])) {
            throw new IOException("qemu-img " + args[0] + ": " + r[1].trim());
        }
    }

    private static long virtualSize(Context c, String rel) throws IOException {
        String[] r = Emu.qemuImg(c, "info", "--output=json", in(c, rel).getPath());
        if (!"0".equals(r[0])) {
            throw new IOException("qemu-img info " + rel + ": " + r[1].trim());
        }
        try {
            return new JSONObject(r[1].substring(r[1].indexOf('{'))).getLong("virtual-size");
        } catch (JSONException | StringIndexOutOfBoundsException e) {
            throw new IOException("qemu-img info " + rel + ": " + e.getMessage());
        }
    }

    // -- the shared build ---------------------------------------------------------------------------------------

    private static void sharedBuild(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled)
            throws IOException {
        currentStep = "build";
        String img = var(m, SHARED, "IMG"), rd = var(m, SHARED, "RAMDISK"), bundle = var(m, SHARED, "BUNDLE");
        Job j = new Job("shared-build", SHARED);
        j.disk("install/work/rd.apfs", "ro", "/root/ios26/rd/rd.apfs");
        j.disk("install/work/gpu-img/" + img, "ro", "cloudos/" + img);
        j.file("downloads/ios_sysroot.tar.gz").file("cloudos/Firmware/" + rd + ".trustcache").file("fw/dtree.orig")
                .file("cloud/kernelcache.research.vphone600.decompressed").file("tools/qemu-sptm")
                .file("cloudos/Firmware/" + img + ".aea.trustcache").file("downloads/" + var(m, SHARED, "PLUGIN"));
        j.out.addAll(List.of("rd-shell.hfs", "cloud/rd.trustcache.im4p", "dtree-smp4", "dtree-phone", "dtree-phone-aod", "fw/bootkc",
                "install/work/gpu"));
        Job shared = j;
        // (the shared group's bodies are iOS 26's)
        runJob(c, m, sharedAs(shared), cpus, p, cancelled);
        // the driver kept for every guest (copied under another name and renamed: a cut copy never passes for it)
        File from = in(c, "install/work/gpu/" + bundle), to = in(c, "gpu-driver/" + bundle), tmp = in(c, "gpu-driver/.new");
        if (!new File(from, "AppleParavirtGPUMetalIOGPUFamily").isFile() || !new File(from, var(m, SHARED, "PLUGIN")).isFile()) {
            throw new IOException("the GPU driver did not come out whole");
        }
        Installer.deleteTree(tmp);
        tmp.mkdirs();
        if (!from.renameTo(new File(tmp, bundle))) {
            throw new IOException("cannot keep the GPU driver");
        }
        Installer.deleteTree(to);
        if (!new File(tmp, bundle).renameTo(to)) {
            throw new IOException("cannot keep the GPU driver");
        }
        tmp.delete();
        // only the build read these
        Installer.deleteTree(in(c, "install/work/gpu-img"));
        Installer.deleteTree(in(c, "install/work/gpu"));
        in(c, "install/work/rd.apfs").delete();
        in(c, "cloudos/" + img + ".aea").delete();
    }

    /** The shared build runs iOS 26's bodies and scripts. */
    private static Job sharedAs(Job j) {
        Job k = new Job(j.name, IOS26);
        k.disks.addAll(j.disks);
        k.in.putAll(j.in);
        k.out.addAll(j.out);
        k.vars.putAll(j.vars);
        return k;
    }

    // -- iOS 26 ---------------------------------------------------------------------------------------------

    private static void iosRootfs(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        currentStep = "rootfs";
        String os = var(m, IOS26, "OS"), sys = var(m, IOS26, "SYSOS"), app = var(m, IOS26, "APPOS");
        newDisk(c, "root26.qcow2", "32G");
        Job j = new Job("ios26-rootfs", IOS26);
        j.disk("os/" + os, "ro").disk("os/" + sys, "ro").disk("iphone/" + app, "ro").disk("root26.qcow2", "new");
        j.file("downloads/ios_sysroot.tar.gz").file("iphone/" + os + ".aea.trustcache").file("iphone/" + sys + ".aea.trustcache")
                .file("iphone/" + app + ".trustcache");
        j.out.addAll(List.of("root26.qcow2.hashes", "root26.tc"));
        runJob(c, m, j, cpus, p, cancelled);
        in(c, "os/" + sys).delete();        // on the disk now (mk-root26.sh DROP_SYSOS)
        in(c, "iphone/" + app).delete();
    }

    private static void iosPartition(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled)
            throws IOException {
        currentStep = "partition";
        long size = virtualSize(c, "root26.qcow2") + (16L << 30) + (2L << 20);
        newDisk(c, "root26g.qcow2", String.valueOf(size));
        Job j = new Job("ios26-partition", IOS26);
        j.disk("root26.qcow2", "ro").disk("root26g.qcow2", "new");
        runJob(c, m, j, cpus, p, cancelled);
        in(c, "root26.qcow2").delete();     // partitioned now
    }

    private static void iosPersona(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        currentStep = "persona";
        Job j = new Job("ios26-persona", IOS26);
        j.disk("root26g.qcow2", "rw");
        runJob(c, m, j, cpus, p, cancelled);
    }

    private static void iosActivation(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled)
            throws IOException {
        currentStep = "activation";
        overlay(c, "root26g.qcow2", "root26-activation.qcow2");
        Job j = new Job("ios26-activation", IOS26);
        j.disk("root26-activation.qcow2", "rw");
        j.file("root26.tc");
        j.out.add("root26-activation.tc");
        runJob(c, m, j, cpus, p, cancelled);
    }

    private static String patchArgs(JSONObject m, String key) throws IOException {
        try {
            JSONArray a = group(m, IOS26).getJSONObject("dscPatches").getJSONArray(key);
            List<String> s = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                s.add(a.getString(i));
            }
            return String.join(" ", s);
        } catch (JSONException e) {
            throw new IOException("asoft-setup.json: ios26.dscPatches." + key);
        }
    }

    private static void iosApps(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        currentStep = "apps";
        overlay(c, "root26-activation.qcow2", "root26-apps.qcow2");
        Job j = new Job("ios26-apps", IOS26);
        j.disk("root26-apps.qcow2", "rw").disk("os/" + var(m, IOS26, "OS"), "ro");
        j.file("root26-activation.tc");
        j.out.add("root26-apps.tc");
        j.scratch.addAll(List.of("dsc", "dscpatch", "dscpatch-pb", "dscpatch-lp", "dscpatch-edge", "dscpatch-aod"));
        j.scratchBytes = 12L << 30;
        j.vars.put("POSTERBOARD", patchArgs(m, "posterBoard"));
        j.vars.put("LOCKLONGPRESS", patchArgs(m, "lockLongPress"));
        j.vars.put("AOD", patchArgs(m, "aod"));
        String[] dsc = patchArgs(m, "files").split(" ");
        for (int i = 0; i < 3; i++) {
            j.vars.put("DSC" + i, dsc[i]);
        }
        runJob(c, m, j, cpus, p, cancelled);
        in(c, "os/" + var(m, IOS26, "OS")).delete();     // nothing after this reads the OS image
    }

    private static void iosGpu(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        currentStep = "gpu";
        String bundle = var(m, IOS26, "BUNDLE");
        overlay(c, "root26-apps.qcow2", "root26-gpu.qcow2");
        Job j = new Job("ios26-gpu", IOS26);
        j.disk("root26-gpu.qcow2", "rw");
        j.file("root26-apps.tc");
        j.in.put("install/work/gpu/" + bundle, in(c, "gpu-driver/" + bundle));
        j.out.add("root26-gpu.tc");
        runJob(c, m, j, cpus, p, cancelled);
    }

    private static void iosLocale(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        currentStep = "locale";
        Job j = new Job("ios26-locale", IOS26);
        j.disk("root.qcow2", "rw");
        j.vars.put("LOCALE", localeArgs());
        runJob(c, m, j, cpus, p, cancelled);
    }

    /** This phone's time zone, region and clock as locale.py's arguments. */
    static String localeArgs() {
        java.util.Locale l = java.util.Locale.getDefault();
        StringBuilder s = new StringBuilder("--tz " + java.util.TimeZone.getDefault().getID());
        String region = l.getCountry();
        if (region.matches("[A-Z]{2}")) {
            s.append(" --region ").append(region);
        }
        String pattern = ((java.text.SimpleDateFormat) java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, l)).toPattern();
        s.append(" --clock ").append(pattern.contains("H") || pattern.contains("k") ? "24" : "12");
        return s.toString();
    }

    // -- boots -----------------------------------------------------------------------------------------------

    private static Guest.SetupBoot boot26(Context c, String disk, String kernel, String tree, String tc) {
        Guest.SetupBoot b = new Guest.SetupBoot();
        b.disk = in(c, disk);
        b.bootkc = in(c, kernel);
        b.sptm = in(c, "fw/sptm");
        b.txm = in(c, "fw/txm");
        b.dtree = in(c, tree);
        b.tc = in(c, tc);
        b.ramdisk = in(c, "rd-shell.hfs");
        return b;
    }

    /** Ends a setup boot: the guest's disks synced through its shell, QEMU quit; true when sync answered. */
    private static boolean endBoot(Context c, GuestConsole con) {
        boolean synced = con != null && con.run("sync", 180_000, 60_000) != null;
        Qmp q = Guest.qmp();
        Guest.willEnd();        // this quit is the setup's: no crash record
        if (q != null) {
            q.call("{\"execute\":\"quit\"}", 10_000);
        }
        for (int i = 0; i < 1200 && Guest.running(); i++) {
            sleep(100);
        }
        if (Guest.running()) {
            Guest.stopAndWait();
        }
        if (con != null) {
            con.close();
        }
        return synced;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Starts a setup boot once the phone is cool and charged enough (shouldHold, the helper VM's rule): a boot
     * cannot be paused (the guest's clock is the host's counter, so its watchdogs would fire), and a hot phone
     * slows the guest down until datamigrator's own hang limit panics it (2026-11-09: thermal SEVERE, 44 C).
     */
    private static void startCool(Context c, Guest.SetupBoot b, PhoneSetup.Progress p, String step, BooleanSupplier cancelled)
            throws IOException {
        boolean said = false;
        while (shouldHold(c)) {
            if (cancelled.getAsBoolean()) {
                throw new IOException("stopped");
            }
            if (!said) {
                said = true;
                AppLog.note(c, "setup26: " + step + " waits for the phone to cool (thermal " + thermal(c) + ")"
                        + (lowBattery(c) ? " and for its charger" : ""));
            }
            p.step(step, 0, "Waiting for the phone to cool down" + (lowBattery(c) ? " and for its charger" : "") + " before iOS boots");
            sleep(15_000);
        }
        if (said) {
            AppLog.note(c, "setup26: " + step + ": cool enough (thermal " + thermal(c) + ")");
        }
        // mustRun looks for a panic in this boot's console log: the last boot's must not count
        File serial = new File(Guest.runDir(c), "serial.txt");
        if (serial.exists() && !serial.delete()) {
            throw new IOException("could not clear " + serial);
        }
        Guest.startSetup(c, b);
    }

    private static void mustRun(Context c, String what) throws IOException {
        if (!Guest.running()) {
            String why = Guest.stopReason(c);
            throw new IOException("QEMU ended during " + what + (why.isEmpty() ? "" : ": " + why) + "; tap Resume to boot again");
        }
        // a panicked guest keeps QEMU busy for ever (wdt=-1, no reboot): 2026-11-09 the wallpaper boot sat 50 min
        // on "userspace panic: datamigrator plugin SpringBoard.migrator hang" before anyone looked
        String panic = panicLine(new File(Guest.runDir(c), "serial.txt"));
        if (panic != null) {
            AppLog.note(c, "setup26: " + panic);
            throw new IOException("iOS panicked during " + what + " (" + panic + "); tap Resume to boot again");
        }
    }

    /** The guest's panic line in the last 64 KB of its console log, or null. */
    private static String panicLine(File serial) {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(serial, "r")) {
            long at = Math.max(0, f.length() - 65536);
            byte[] b = new byte[(int) (f.length() - at)];
            f.seek(at);
            f.readFully(b);
            String s = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
            int i = s.lastIndexOf("panic(cpu ");
            if (i < 0) {
                return null;
            }
            int e = s.indexOf('\n', i);
            return s.substring(i, e < 0 ? s.length() : e).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void iosFirstBoot(Context c, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        // 4.5: usermanagerd writes the persona manifest and the user session (persona.kb, usersession.kb); the
        // disk has no libMobileGestalt patch yet (the apps step's), so the plain tree and kernel, as the PC's -NoAod
        startCool(c, boot26(c, "root26g.qcow2", "cloud/kernelcache.research.vphone600.decompressed", "dtree-phone", "root26.tc"), p, "ios26:firstboot", cancelled);
        GuestConsole con = new GuestConsole(Guest.consoleSocket(c));
        long t0 = System.currentTimeMillis();
        try {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("stopped");
                }
                mustRun(c, "iOS's first boot");
                long min = (System.currentTimeMillis() - t0) / 60_000;
                p.step("ios26:firstboot", (int) Math.min(95, min * 100 / 30), "iOS is booting for the first time (" + min + " min; on a PC about 10)");
                if (con.says("test -e /private/var/keybags/persona.kb && test -e /private/var/keybags/usersession.kb && echo __READY__ || echo __WAIT__",
                        "__READY__", 90_000)) {
                    break;
                }
                if (min > 60) {
                    throw new IOException("iOS wrote no persona files in 60 min (" + Guest.stopReason(c) + ")");
                }
                sleep(30_000);
            }
            AppLog.note(c, "setup26: persona files after " + (System.currentTimeMillis() - t0) / 1000 + " s");
            sleep(30_000);
            p.step("ios26:firstboot", 97, "Saving iOS's first boot");
            if (!endBoot(c, con)) {
                throw new IOException("the guest did not answer sync; tap Resume to redo the first boot");
            }
        } catch (IOException | RuntimeException e) {
            if (Guest.running()) {
                endBoot(c, null);
            }
            throw e;
        }
    }

    private static int framesShown(Context c) {
        int n = 0;
        File log = new File(Guest.runDir(c), "qemu-stderr.txt");
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(log, "r")) {
            long len = f.length();
            long from = Math.max(0, len - (256 << 10));
            byte[] b = new byte[(int) (len - from)];
            f.seek(from);
            f.readFully(b);
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("frame (\\d+) shown").matcher(new String(b, StandardCharsets.ISO_8859_1));
            while (mm.find()) {
                n = Math.max(n, Integer.parseInt(mm.group(1)));
            }
        } catch (IOException | NumberFormatException ignored) {
            // not yet
        }
        return n;
    }

    private static void iosWallpaper(Context c, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        // 5.3.6: root.qcow2 on the top layer, booted once: DataMigration and then PosterBoard's first run write
        // into it (a disk stopped before DataMigration ends runs it again at every start)
        // debug: files/setup/a26/phone/keep-root boots the last run's root.qcow2 again (what its DataMigration
        // left: its crash reports, whether it goes on from there) instead of a fresh layer
        if ((c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && new File(work(c), "phone/keep-root").isFile() && in(c, "root.qcow2").isFile()) {
            AppLog.note(c, "setup26: keep-root: booting the last run's root.qcow2 again");
        } else {
            overlay(c, "root26-gpu.qcow2", "root.qcow2");
        }
        java.nio.file.Files.copy(in(c, "root26-gpu.tc").toPath(), in(c, "root.tc").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        startCool(c, boot26(c, "root.qcow2", "fw/bootkc", "dtree-phone-aod", "root.tc"), p, "ios26:wallpaper", cancelled);
        GuestConsole con = new GuestConsole(Guest.consoleSocket(c));
        long t0 = System.currentTimeMillis();
        try {
            // the PC's Wait-Ui: "frame N shown" with N >= 5 (or the screen past the logo: the watch's reims logged two)
            while (framesShown(c) < 5 && Guest.frameVariety() <= 20) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("stopped");
                }
                mustRun(c, "iOS's second boot");
                long min = (System.currentTimeMillis() - t0) / 60_000;
                if (min > 60) {
                    throw new IOException("iOS showed no picture in 60 min");
                }
                p.step("ios26:wallpaper", (int) Math.min(15, min), "iOS is starting (" + min + " min)");
                sleep(10_000);
            }
            AppLog.note(c, "setup26: UI after " + (System.currentTimeMillis() - t0) / 1000 + " s");
            String info = con.run("ls /System/Library/DataClassMigrators | tr '\\n' ' '; for f in /System/Library/DataClassMigrators/SpringBoard.migrator/Info.plist;"
                    + " do echo \" $f:\"; tr -c '[:print:]' ' ' < $f | tr -s ' ' | head -c 1500; done", 90_000, 60_000);
            AppLog.note(c, "setup26: migrators: " + (info == null ? "(no answer)" : info.trim()));
            long t1 = System.currentTimeMillis();
            long lastDiag = -1;
            boolean diagOff = false;
            String plist = "/var/mobile/Library/Preferences/com.apple.migration.plist";
            while (true) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("stopped");
                }
                mustRun(c, "DataMigration");
                long min = (System.currentTimeMillis() - t1) / 60_000;
                p.step("ios26:wallpaper", 15 + (int) Math.min(70, min * 70 / 60),
                        "DataMigration is setting iOS up (" + min + " min; on a PC about 50). The phone may get warm.");
                String o = con.run("cat " + plist, 90_000, 60_000);
                // (or in an answer that came after run() had sent the command again with a new tag)
                if (o != null && o.contains("LastSystemVersion") || con.seen("LastSystemVersion")) {
                    break;
                }
                if (min / 5 != lastDiag && !diagOff) {
                    // which migrator plugin is slow (datamigrator writes a stacks report per hang; 2026-11-09 the
                    // phone panicked on a SpringBoard.migrator hang that the PC only reports), and what crashes
                    lastDiag = min / 5;
                    // the newest report only, and only its first 3 KB (the reason is there): reading every report
                    // line by line in bash took longer than the 5 min between these and queued the plist checks
                    // behind it, so a DataMigration that had finished went unnoticed (2026-11-09)
                    String d = con.run("cd /private/var/mobile/Library/Logs/CrashReporter && ls | cut -d- -f1 | sort | uniq -c"
                            + " | sort -rn | head -8 | tr '\n' ';'; s=$(ls -t stacks+com.apple.datamigrator* 2> /dev/null | head -1);"
                            + " [ -n \"$s\" ] && head -c 3000 \"$s\" | tr ',' '\n' | while IFS= read -r l; do case \"$l\" in"
                            + " *Plugin:*|*\\\"reason\\\"*) echo \" ${l:0:260}\"; break;; esac; done", 60_000, 60_000);
                    diagOff = d == null;        // a busy guest: no more of these in this boot
                    AppLog.note(c, "setup26: DataMigration " + min + " min, thermal " + thermal(c) + ", crash reports: "
                            + (d == null ? "(no answer)" : d.trim()));
                }
                if (min > 180) {
                    throw new IOException("DataMigration was not done in 3 hours; tap Resume to redo this boot");
                }
                sleep(30_000);
            }
            AppLog.note(c, "setup26: DataMigration done after " + (System.currentTimeMillis() - t1) / 1000 + " s");
            long t2 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t2 < 6 * 60_000L) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("stopped");
                }
                mustRun(c, "PosterBoard's first run");
                p.step("ios26:wallpaper", 85 + (int) ((System.currentTimeMillis() - t2) / 30_000), "PosterBoard writes its poster store");
                sleep(10_000);
            }
            p.step("ios26:wallpaper", 98, "Saving the disk");
            if (!endBoot(c, con)) {
                throw new IOException("the guest did not answer sync; tap Resume to redo this boot");
            }
        } catch (IOException | RuntimeException e) {
            if (Guest.running()) {
                endBoot(c, null);
            }
            throw e;
        }
    }

    // -- packing into the app ------------------------------------------------------------------------------------

    /** @from as @to in the app's folder: a hard link (no copy; the work folder's name may go later). */
    private static void place(File from, File to) throws IOException {
        if (!from.isFile()) {
            throw new IOException(from + " is not there");
        }
        to.getParentFile().mkdirs();
        to.delete();
        try {
            Os.link(from.getPath(), to.getPath());
        } catch (android.system.ErrnoException e) {
            java.nio.file.Files.copy(from.toPath(), to.toPath());
        }
    }

    private static void pack(Context c, String step, String top, File out, PhoneSetup.Progress p) throws IOException {
        File part = new File(out.getPath() + ".part");
        part.delete();
        out.getParentFile().mkdirs();
        p.step(step, 5, "Packing the disk (qemu-img convert, zstd)");
        long t0 = System.currentTimeMillis();
        String[] r = Emu.qemuImg(c, "convert", "-O", "qcow2", "-c", "-o", "compression_type=zstd", in(c, top).getPath(), part.getPath());
        if (!"0".equals(r[0])) {
            part.delete();
            throw new IOException("packing the disk failed: " + r[1].trim());
        }
        if (!part.renameTo(out)) {
            throw new IOException("cannot write " + out);
        }
        AppLog.note(c, String.format(Locale.ROOT, "setup26: packed %s into %s, %.2f GB in %d s", top, out.getName(), out.length() / 1e9,
                (System.currentTimeMillis() - t0) / 1000));
    }

    /** A user disk on the old base cannot boot on the new one: kept aside as .old, as the PC keeps it. */
    private static void retire(Context c, String... names) {
        for (String n : names) {
            File f = new File(c.getFilesDir(), n);
            if (f.exists()) {
                File old = new File(f.getPath() + ".old");
                old.delete();
                f.renameTo(old);
                AppLog.note(c, "setup26: " + n + " rested on the old disk: kept as " + old.getName());
            }
        }
    }

    private static void writeInfo(File dir, String what) throws IOException {
        try (FileOutputStream o = new FileOutputStream(new File(dir, "install.json"))) {
            JSONObject j = new JSONObject();
            j.put("format", 1);
            j.put("what", what);
            j.put("madeOn", "phone");
            j.put("made", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(new java.util.Date()));
            o.write(j.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static void iosPack(Context c, PhoneSetup.Progress p) throws IOException {
        if (Guest.running()) {
            throw new IOException("iOS is running: close it first");
        }
        File d = new File(c.getFilesDir(), "ios26");
        retire(c, "ios26-user.qcow2", "ios26-2x-user.qcow2");
        pack(c, "ios26:pack", "root.qcow2", new File(d, Guest.AOD26_BASE), p);
        p.step("ios26:pack", 95, "Setting iOS 26 up in the app");
        place(in(c, "root.tc"), new File(d, Guest.AOD26_TC));
        place(in(c, "fw/bootkc"), new File(d, "bootkc"));
        place(in(c, "fw/sptm"), new File(d, "sptm"));
        place(in(c, "fw/txm"), new File(d, "txm"));
        place(in(c, "rd-shell.hfs"), new File(d, "rd-shell.hfs"));
        place(in(c, "dtree-phone"), new File(d, Guest.DTREE26));
        place(in(c, "dtree-phone-aod"), new File(d, Guest.DTREE26_AOD));
        new File(c.getFilesDir(), Guest.DTREE26 + "-2x").delete();       // made again from the new tree
        writeInfo(d, "ios26");
        for (String n : new String[] {"root.qcow2", "root26-gpu.qcow2", "root26-apps.qcow2", "root26-activation.qcow2", "root26g.qcow2",
                "root26.qcow2", "root26.qcow2.hashes"}) {
            in(c, n).delete();
        }
        Installer.deleteTree(in(c, "iphone"));
        Installer.deleteTree(in(c, "os"));
        Installer.deleteTree(in(c, "install/work"));
    }

    // -- watchOS ----------------------------------------------------------------------------------------------

    private static void watchPrep(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        currentStep = "prep";
        String bundle = var(m, WATCH, "BUNDLE");
        Job j = new Job("watch-prep", WATCH);
        j.file("cloud/kernelcache.research.vphone600.decompressed").file("dtree-smp4").file("watchos/ipsw/DeviceTree.n207sap.raw")
                .file("tools/qemu-sptm").file("gpu-driver/" + bundle);
        j.out.addAll(List.of("watchos/bootkc-w", "watchos/dtree-w", "watchos/dtree-w-3g-19m2", "watchos/gpu"));
        runJob(c, m, j, cpus, p, cancelled);
    }

    private static void watchRootfs(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        currentStep = "rootfs";
        String os = var(m, WATCH, "OS");
        // mk-rootw.sh's size: 2048 sectors, 16 GiB root, 8 GiB Data, 2048 sectors
        newDisk(c, "watchos/rootw.qcow2", String.valueOf((24L << 30) + (2L << 20)));
        Job j = new Job("watch-rootfs", WATCH);
        j.disk("watchos/os/" + os, "ro").disk("watchos/rootw.qcow2", "new");
        j.file("downloads/ios_sysroot.tar.gz");
        j.out.add("watchos/rootw.qcow2.hashes");
        runJob(c, m, j, cpus, p, cancelled);
        in(c, "watchos/os/" + os).delete();       // on the disk now
    }

    private static void watchPatches(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled)
            throws IOException {
        currentStep = "patches";
        String os = var(m, WATCH, "OS");
        overlay(c, "watchos/rootw.qcow2", "watchos/rootw-p.qcow2");
        Job j = new Job("watch-patches", WATCH);
        j.disk("watchos/rootw-p.qcow2", "rw");
        j.file("watchos/gpu").file("watchos/ipsw/" + os + ".aea.trustcache").file("watchos/rootw.qcow2.hashes");
        j.scratch.add("watchos/build");
        j.out.addAll(List.of("watchos/rootw-p.hashes", "watchos/rootw.tc"));
        runJob(c, m, j, cpus, p, cancelled);
    }

    private static void watchFirstBoot(Context c, PhoneSetup.Progress p, BooleanSupplier cancelled) throws IOException {
        overlay(c, "watchos/rootw-p.qcow2", "watchos/watch.qcow2");
        Guest.SetupBoot b = boot26(c, "watchos/watch.qcow2", "watchos/bootkc-w", "watchos/dtree-w-3g-19m2", "watchos/rootw.tc");
        b.watch = true;
        startCool(c, b, p, "watchos:firstboot", cancelled);
        GuestConsole con = new GuestConsole(Guest.consoleSocket(c));
        long t0 = System.currentTimeMillis();
        boolean ark = false;
        long lastLogged = -1;
        try {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("stopped");
                }
                mustRun(c, "the watch's first boot");
                long min = (System.currentTimeMillis() - t0) / 60_000;
                p.step("watchos:firstboot", (int) Math.min(95, min * 100 / 20),
                        (ark ? "The watch sets its face up (" : "watchOS is booting for the first time (") + min + " min; on a PC 14)");
                if (!ark && con.says("ls /private/var/containers/Data/System/*/Library/internal/data_ark.plist > /dev/null 2>&1"
                        + " && echo __READY__ || echo __WAIT__", "__READY__", 90_000)) {
                    ark = true;
                    AppLog.note(c, "setup26: the watch's data ark after " + (System.currentTimeMillis() - t0) / 1000 + " s");
                }
                int variety = Guest.frameVariety();
                if (ark && min != lastLogged) {
                    lastLogged = min;
                    AppLog.note(c, "setup26: watch first boot " + min + " min, the screen has " + variety + " colours");
                    if (min % 3 == 0) {
                        // what keeps crashing (a face that did not come once: 2026-11-08, a report every 40-60 s); the guest has no sed or grep
                        String cr = con.run("ls /private/var/mobile/Library/Logs/CrashReporter | cut -d- -f1 | sort | uniq -c"
                                + " | sort -rn | head -6 | tr '\\n' ';'", 60_000, 60_000);
                        AppLog.note(c, "setup26: crash reports: " + (cr == null ? "(no answer)" : cr.trim()));
                    }
                }
                // the Apple logo samples to 8 colours, the face to ~30 (2026-11-08, Modular face at 11 min)
                if (ark && variety > 20) {
                    AppLog.note(c, "setup26: the watch face after " + (System.currentTimeMillis() - t0) / 1000 + " s (" + variety + " colours)");
                    break;
                }
                if (min > 60) {
                    if (!ark) {
                        throw new IOException("no data ark after 60 min");
                    }
                    AppLog.note(c, "setup26: no watch face after 60 min: the first start goes on with what this boot did not finish");
                    break;
                }
                sleep(20_000);
            }
            sleep(60_000);
            p.step("watchos:firstboot", 97, "Saving the watch's first boot");
            if (!endBoot(c, con)) {
                throw new IOException("the guest did not answer sync; tap Resume to redo the first boot");
            }
        } catch (IOException | RuntimeException e) {
            if (Guest.running()) {
                endBoot(c, null);
            }
            throw e;
        }
    }

    private static void watchActivation(Context c, JSONObject m, int cpus, PhoneSetup.Progress p, BooleanSupplier cancelled)
            throws IOException {
        currentStep = "activation";
        Job j = new Job("watch-activation", WATCH);
        j.disk("watchos/watch.qcow2", "rw");
        j.vars.put("LOCALE", localeArgs());
        runJob(c, m, j, cpus, p, cancelled);
    }

    private static void watchPack(Context c, PhoneSetup.Progress p) throws IOException {
        if (Guest.running()) {
            throw new IOException("iOS is running: close it first");
        }
        File d = new File(c.getFilesDir(), "watchos");
        retire(c, "watch-user.qcow2");
        pack(c, "watchos:pack", "watchos/watch.qcow2", new File(d, WatchProfile.BASE), p);
        p.step("watchos:pack", 95, "Setting watchOS up in the app");
        place(in(c, "watchos/rootw.tc"), new File(d, WatchProfile.TC));
        place(in(c, "watchos/bootkc-w"), new File(d, WatchProfile.KERNEL));
        place(in(c, "watchos/dtree-w-3g-19m2"), new File(d, WatchProfile.DTREE));
        // iOS 26's three, in the watch's folder: it runs without an iOS 26 install
        place(in(c, "fw/sptm"), new File(d, "sptm"));
        place(in(c, "fw/txm"), new File(d, "txm"));
        place(in(c, "rd-shell.hfs"), new File(d, "rd-shell.hfs"));
        writeInfo(d, "watchos");
        for (String n : new String[] {"watchos/watch.qcow2", "watchos/rootw-p.qcow2", "watchos/rootw.qcow2"}) {
            in(c, n).delete();
        }
        Installer.deleteTree(in(c, "watchos/os"));
        Installer.deleteTree(in(c, "watchos/build"));
        in(c, "watchos/ipsw/" + "DeviceTree.n207sap.im4p").delete();
    }

    // -- thermal ----------------------------------------------------------------------------------------------

    /** Hot (Android's thermal status SEVERE or worse) until it is back to LIGHT; or the battery low off the charger. */
    private static volatile boolean hot;

    /** Android's thermal status (0 none … 3 severe … 6 shutdown). */
    static int thermal(Context c) {
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        return pm == null ? -1 : pm.getCurrentThermalStatus();
    }

    static boolean shouldHold(Context c) {
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        int t = pm == null ? 0 : pm.getCurrentThermalStatus();
        if (t >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
            hot = true;
        } else if (t <= android.os.PowerManager.THERMAL_STATUS_LIGHT) {
            hot = false;
        }
        return hot || lowBattery(c);
    }

    /** Below 15 % and not charging: the setup waits for the charger (an hour-long build would run it flat). */
    static boolean lowBattery(Context c) {
        android.content.Intent b = c.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (b == null) {
            return false;
        }
        int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100), scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        boolean plugged = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return !plugged && level * 100 < 15 * scale;
    }

    static boolean charging(Context c) {
        android.content.Intent b = c.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        return b != null && b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** Waits (a decrypt between clusters) while the phone is too hot or its battery too low. */
    private static void holdWhileHot(Context c, BooleanSupplier cancelled) {
        while (shouldHold(c) && !cancelled.getAsBoolean()) {
            sleep(5000);
        }
    }
}
