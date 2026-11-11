package com.asoft.ios;

import android.content.Context;

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
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * iOS 16 from Apple, on this phone: what the PC's Install-ASoftware.ps1 and Send-ToPhone.ps1 do, with
 * the same helper VM jobs and tools, ending in the three files a PC bundle has (asoft-bundle.json,
 * payload.bin, disk.qcow2) in files/ios16, as Installer's import leaves them.
 *
 *   ipsw     only the members a step needs, into a sparse copy of the IPSW (IpswFetch)
 *   payload  the helper's job-run: tools/ipsw-devicetree.py, mkadt.py, mkpayload.py with our kernel
 *   disk     the helper's job-build-root.sh on the sparse IPSW (unchanged), 16 GiB root + 10 GiB scratch
 *   pack     qemu-img convert -c (zstd), as Send-ToPhone.ps1 packs the disk
 *   install  into files/ios16 through Installer.finish()
 *
 * What to fetch comes from assets/setup/asoft-setup.json, the list the PC installers share. Each step
 * leaves a marker in files/setup/ios16, so a run that stopped (the app killed, the phone restarted, no
 * room) goes on where it was.
 */
final class PhoneSetup {
    interface Progress {
        /** @step is one of STEPS, @pct its progress (-1: unknown), @text what it does now. */
        void step(String step, int pct, String text);

        /** The download's members (with their bytes so far) and the fetcher (speed, connections). */
        void fetch(List<IpswFetch.Item> items, IpswFetch f);

        void log(String line);
    }

    static final String[] STEPS = {"ipsw", "payload", "disk", "pack", "install", "firststart"};

    private PhoneSetup() {}

    static File work(Context c) {
        return new File(c.getFilesDir(), "setup/ios16");
    }

    private static File mark(Context c, String step) {
        return new File(work(c), step + ".done");
    }

    static boolean done(Context c, String step) {
        return mark(c, step).isFile();
    }

    private static void setDone(Context c, String step) throws IOException {
        try (FileOutputStream o = new FileOutputStream(mark(c, step))) {
            o.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** The bundled list (the PC installers' asoft-setup.json). */
    static JSONObject manifest(Context c) throws IOException {
        try (InputStream in = c.getAssets().open("setup/asoft-setup.json")) {
            return new JSONObject(new String(Io.readAll(in), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("bad asoft-setup.json: " + e.getMessage());
        }
    }

    /**
     * Bytes the rest of the run needs free at its height, beyond what it has on disk already (the screen
     * and the checks): measured on the S25 (2026-11-08) the members 5.84 GB, the scratch disk 7.32 GB and
     * the root 12.24 GB at once, then the root and its packed copy (6.19 GB), then the first start's
     * writes and save (3.9 GB). Bytes already downloaded count against the members.
     */
    static long needBytes(Context c) {
        File w = work(c);
        long need;
        if (!done(c, "disk")) {
            // the height: the members, the scratch disk and the root disk at once; later steps free more than they add
            need = (done(c, "ipsw") ? 0 : Math.max(0, 5_840_000_000L - allocated(new File(w, "ipsw-sparse.ipsw"))))
                    + 7_400_000_000L + 12_300_000_000L - allocated(new File(w, "root.qcow2")) - allocated(new File(w, "scratch.qcow2"));
        } else if (!done(c, "pack")) {
            // the IPSW and the scratch disk go before the packed copy is written
            need = 6_300_000_000L - allocated(new File(w, "ipsw-sparse.ipsw")) - allocated(new File(w, "scratch.qcow2"))
                    - allocated(new File(w, "disk.qcow2"));
        } else if (!done(c, "firststart")) {
            need = 4_000_000_000L - allocated(new File(w, "root.qcow2"));      // the root goes before iOS's first start
        } else {
            need = 0;
        }
        return Math.max(0, need) + 1_000_000_000L;
    }

    /** The blocks a (sparse) file holds, as du counts them. */
    static long allocated(File f) {
        try {
            return android.system.Os.stat(f.getPath()).st_blocks * 512L;
        } catch (android.system.ErrnoException e) {
            return 0;
        }
    }

    /** Runs what is still to do; throws with a message for the screen when it stops. */
    static void run(Context c, int cpus, Progress p, BooleanSupplier cancelled, IpswFetch[] current) throws IOException {
        File w = work(c);
        w.mkdirs();
        JSONObject ios16;
        try {
            ios16 = manifest(c).getJSONObject("os").getJSONObject("ios16");
        } catch (JSONException e) {
            throw new IOException("asoft-setup.json has no iOS 16: " + e.getMessage());
        }
        File ipsw = new File(w, "ipsw-sparse.ipsw");
        // (once the disk is built the IPSW is not needed: it may have gone early, see disk())
        if (!done(c, "ipsw") && !done(c, "disk")) {
            fetch(c, ios16, ipsw, p, cancelled, current);
            setDone(c, "ipsw");
        }
        File payload = new File(w, "payload.bin");
        if (!done(c, "payload")) {
            payload(c, ios16, ipsw, payload, cpus, p, cancelled);
            setDone(c, "payload");
        }
        File root = new File(w, "root.qcow2"), scratch = new File(w, "scratch.qcow2");
        if (!done(c, "disk")) {
            disk(c, ipsw, root, scratch, cpus, p, cancelled);
            setDone(c, "disk");
        }
        // the IPSW's members are in the disk and the payload now; Apple keeps serving them
        deleteSparse(ipsw);
        scratch.delete();
        File disk = new File(w, "disk.qcow2");
        if (!done(c, "pack")) {
            p.step("pack", -1, "Packing the iOS disk");
            disk.delete();
            String[] r = Emu.qemuImg(c, "convert", "-O", "qcow2", "-c", "-o", "compression_type=zstd", root.getPath(), disk.getPath());
            if (!"0".equals(r[0])) {
                disk.delete();
                throw new IOException("packing the disk failed: " + r[1].trim());
            }
            setDone(c, "pack");
        }
        root.delete();
        if (!done(c, "install")) {
            p.step("install", -1, "Setting iOS up in the app");
            try (FileOutputStream o = new FileOutputStream(new File(w, "asoft-bundle.json"))) {
                JSONObject m = new JSONObject();
                m.put("format", 1);
                m.put("ios", "16");
                m.put("build", ios16.optString("build", "20H392"));
                m.put("kernel", "out-0103");
                m.put("withMyData", false);
                m.put("made", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()));
                m.put("diskBytes", disk.length());
                m.put("madeOn", "phone");
                o.write(m.toString(1).getBytes(StandardCharsets.UTF_8));
            } catch (JSONException e) {
                throw new IOException(e.getMessage());
            }
            Installer.finish(c, w);
            setDone(c, "install");
        }
        if (!done(c, "firststart")) {
            firstStart(c, p, cancelled);
            setDone(c, "firststart");
        }
        // what stays: the markers (the screen shows the steps done); the files went into files/ios16
        for (String n : new String[] {"payload.bin", "disk.qcow2", "asoft-bundle.json"}) {
            new File(w, n).delete();
        }
    }

    // -- first start ------------------------------------------------------------------------------

    /**
     * The PC installer's step 5: iOS's first start sets up what it needs once (on the S25, 38 min on
     * 2026-11-08: app plug-ins, Spotlight, the lock screen's posters, the icon cache; DataMigration is
     * already off, sweet-root-tweaks.py). Done here, the user's first start is the quick boot's ~10 s:
     * iOS runs until SpringBoard is up (the console's "display off ignored", then "display on"),
     * settles for a minute and is saved.
     */
    private static void firstStart(Context c, Progress p, BooleanSupplier cancelled) throws IOException {
        if (Guest.running()) {
            throw new IOException("iOS is running: close it first (side bar > Saves > Save and close)");
        }
        p.step("firststart", 0, "Starting iOS for the first time");
        File serial = new File(Guest.runDir(c), "serial.txt");
        long t0 = System.currentTimeMillis();
        serial.delete();
        // A boot, in this foreground service's own process: GuestService (another foreground service)
        // may not be started from the background on Android 12 and later, and the screen may be off by now.
        // Guest.start is what GuestService runs for a boot; the screen attaches to it if the user opens iOS.
        Guest.select(c, Guest.IOS16);
        Guest.startError = null;
        Guest.start(c, null);
        boolean offSeen = false, up = false;
        long at = 0;
        StringBuilder carry = new StringBuilder();
        try {
            while (!up) {
                if (cancelled.getAsBoolean()) {
                    end(c);                         // what it did so far stays in a save
                    throw new IOException("stopped");
                }
                long s = (System.currentTimeMillis() - t0) / 1000;
                if (s > 20 && !Guest.running() && !"booting".equals(Guest.state)) {
                    throw new IOException("iOS stopped during its first start (" + Guest.startError + ")");
                }
                if (s > 120 * 60) {
                    throw new IOException("iOS did not finish its first start in 2 hours");
                }
                if (serial.isFile() && serial.length() > at) {
                    try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(serial, "r")) {
                        f.seek(at);
                        byte[] b = new byte[(int) Math.min(1 << 20, f.length() - at)];
                        f.readFully(b);
                        at += b.length;
                        carry.append(new String(b, StandardCharsets.ISO_8859_1));
                    }
                    int nl;
                    while ((nl = carry.indexOf("\n")) >= 0) {
                        String line = carry.substring(0, nl);
                        carry.delete(0, nl + 1);
                        if (line.contains("SWEET FB: display off")) {
                            offSeen = true;
                        } else if (offSeen && line.contains("SWEET FB: display on")) {
                            up = true;
                        }
                    }
                }
                // 38 min on the S25 (PC 21): the bar fills toward that and waits at 95
                p.step("firststart", (int) Math.min(95, s * 100 / (40 * 60)),
                        String.format(java.util.Locale.ROOT, "iOS is setting itself up (%d min of about 40)", s / 60));
                Thread.sleep(2000);
            }
            p.step("firststart", 97, "iOS is up; saving it");
            Thread.sleep(60_000);                   // let it settle, as the PC installer does
            String saved = end(c);
            if (Guest.running()) {
                throw new IOException("iOS did not close after its first start (" + saved + ")");
            }
            AppLog.note(c, "setup: first start done in " + (System.currentTimeMillis() - t0) / 1000 + " s, " + saved);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            end(c);
            throw new IOException("stopped");
        }
    }

    /**
     * Saves iOS into its quick-boot slot and ends it (Save and close's own path; it waits for QEMU to
     * end), then lets go of the guest RAM memfd: the app keeps it after iOS 16 ends, and after a first
     * start it holds gigabytes of pages nobody needs until the user starts iOS.
     */
    private static String end(Context c) {
        String r = Guest.running() ? Guest.saveAndQuit(c) : "not running";
        if (!Guest.running()) {
            Render.closeGuestRam();
        }
        return r;
    }

    // -- ipsw -----------------------------------------------------------------------------------

    private static void fetch(Context c, JSONObject ios16, File ipsw, Progress p, BooleanSupplier cancelled, IpswFetch[] current)
            throws IOException {
        JSONObject src;
        JSONArray members;
        try {
            src = ios16.getJSONObject("sources").getJSONObject("ipsw");
            members = src.getJSONArray("members");
        } catch (JSONException e) {
            throw new IOException("asoft-setup.json: " + e.getMessage());
        }
        p.step("ipsw", 0, "Reading the IPSW's directory");
        IpswFetch f = new IpswFetch(src.optString("url"), new IpswFetch.Listener() {
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
        for (int i = 0; i < members.length(); i++) {
            JSONObject m = members.optJSONObject(i);
            items.add(new IpswFetch.Item(m.optString("name"), ipsw, IpswFetch.Kind.SPARSE, m.optLong("bytes")));
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
        }, "setup-fetch-watch");
        watch.start();
        try {
            IpswFetch.Index ix = f.index();
            if (src.optLong("bytes") > 0 && ix.size != src.optLong("bytes")) {
                throw new IOException("Apple's file has another size (" + ix.size + " bytes) than the one ASoftware knows");
            }
            f.prepare(ix, items);
            // the list's crc32 must be the zip's own: the IPSW is the one the PC installer pins
            for (int i = 0; i < members.length(); i++) {
                String want = members.optJSONObject(i).optString("crc32");
                IpswFetch.Item it = items.get(i);
                if (!want.isEmpty() && Long.parseLong(want, 16) != it.entry.crc) {
                    throw new IOException(it.member + ": not the file ASoftware knows (CRC-32 differs)");
                }
            }
            p.step("ipsw", 1, "Downloading from Apple");
            f.run(items);
        } finally {
            watch.interrupt();
            p.fetch(items, f);
            current[0] = null;
        }
    }

    /** The sparse IPSW's blocks back to the phone. */
    static void deleteSparse(File ipsw) {
        File[] side = ipsw.getParentFile().listFiles((d, n) -> n.startsWith(ipsw.getName()));
        if (side != null) {
            for (File s : side) {
                s.delete();
            }
        }
    }

    // -- payload --------------------------------------------------------------------------------

    private static File asset(Context c, String name, File to) throws IOException {
        to.getParentFile().mkdirs();
        try (InputStream in = c.getAssets().open(name)) {
            TarIo.copy(in, to);
        }
        return to;
    }

    private static void payload(Context c, JSONObject ios16, File ipsw, File out, int cpus, Progress p, BooleanSupplier cancelled)
            throws IOException {
        p.step("payload", 0, "The kernel and this iPhone's device tree");
        File jd = new File(work(c), "payload-job");
        Helper.endLeftover(c, jd);
        Installer.deleteTree(jd);
        jd.mkdirs();
        Map<String, Object> files = new LinkedHashMap<>();
        files.put(".job/run", "run.sh\n");
        files.put(".job/disks", "ipsw ipsw.ipsw\n");
        files.put(".job/out", "payload.bin\n");
        files.put("run.sh", "set -e\n"
                + "echo 'ASOFT: progress 10 device tree'\n"
                + "python3 tools/ipsw-devicetree.py ipsw.ipsw d22ap.adt\n"
                + "echo 'ASOFT: progress 40 ADT'\n"
                + "ASOFT_DEVICE_TREE=$PWD/d22ap.adt python3 tools/mkadt.py qemu.adt --qemu --highmem\n"
                + "echo 'ASOFT: progress 70 payload'\n"
                + "python3 tools/mkpayload.py kernel qemu.adt payload.bin --cmdline \"$(cat cmdline.txt)\"\n");
        files.put("cmdline.txt", ios16.optString("bootArgs"));
        files.put("kernel", asset(c, "xnu/kernel.development.sweet", new File(jd, "kernel")));
        for (String t : new String[] {"ipsw-devicetree.py", "lzfse.py", "mkadt.py", "adt.py", "mkpayload.py"}) {
            files.put("tools/" + t, asset(c, "setup/tools/" + t, new File(jd, "tools/" + t)));
        }
        File in = new File(jd, "in.tar"), o = new File(jd, "out.raw");
        TarIo.write(in, files);
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(o, "rw")) {
            r.setLength(64L << 20);
        }
        List<Helper.Disk> disks = new ArrayList<>();
        disks.add(new Helper.Disk("in", in, "raw", true));
        disks.add(new Helper.Disk("out", o, "raw", false));
        disks.add(new Helper.Disk("ipsw", ipsw, "raw", true));
        Helper.Listener l = new Helper.Listener() {
            @Override
            public void progress(int pct, String text) {
                p.step("payload", pct, text);
            }

            @Override
            public void line(String text) {
                p.log(text);
            }
        };
        Helper.Result r = Helper.run(c, "run", disks, Math.min(cpus, 4), jd, l, cancelled, true, Helper.ramMiB(c, Helper.FLOOR_IOS16));
        if (Helper.worthRetry(r, Helper.FLOOR_IOS16) && !cancelled.getAsBoolean()) {
            int ram = Helper.retryRam(r, Helper.FLOOR_IOS16);
            AppLog.note(c, "setup: the kernel job failed (" + r.failure() + "); again under TCG with " + ram + " MiB");
            r = Helper.run(c, "run", disks, Math.min(cpus, 4), jd, l, cancelled, false, ram);
        }
        if (r.rc != 0) {
            throw new IOException("making the kernel failed: " + r.failure());
        }
        if (r.outBytes <= 0) {
            throw new IOException("making the kernel failed: the helper VM handed nothing back");
        }
        File got = new File(jd, "got");
        TarIo.read(o, r.outBytes, got);
        File pb = new File(got, "payload.bin");
        if (!pb.isFile() || !pb.renameTo(out)) {
            throw new IOException("the helper made no payload.bin");
        }
        Installer.deleteTree(jd);
    }

    // -- disk -----------------------------------------------------------------------------------

    private static void disk(Context c, File ipsw, File root, File scratch, int cpus, Progress p, BooleanSupplier cancelled)
            throws IOException {
        p.step("disk", 0, "Building the iOS disk");
        Helper.endLeftover(c, new File(work(c), "disk-job"));
        root.delete();
        scratch.delete();
        for (String[] a : new String[][] {{root.getPath(), "16G"}, {scratch.getPath(), "10G"}}) {
            String[] r = Emu.qemuImg(c, "create", "-q", "-f", "qcow2", a[0], a[1]);
            if (!"0".equals(r[0])) {
                throw new IOException("qemu-img create failed: " + r[1].trim());
            }
        }
        List<Helper.Disk> disks = new ArrayList<>();
        disks.add(new Helper.Disk("ipsw", ipsw, "raw", true));
        disks.add(new Helper.Disk("root", root, "qcow2", false));
        disks.add(new Helper.Disk("scratch", scratch, "qcow2", false));
        Helper.Result r = buildRoot(c, disks, cpus, root, ipsw, p, cancelled, true, Helper.ramMiB(c, Helper.FLOOR_IOS16));
        if (Helper.worthRetry(r, Helper.FLOOR_IOS16) && !cancelled.getAsBoolean() && ipsw.length() > 0) {
            // the native engine for Linux is new (2026-11-08): once more under TCG, as on the PC; after Android's
            // memory killer with less RAM too
            int ram = Helper.retryRam(r, Helper.FLOOR_IOS16);
            AppLog.note(c, "setup: the disk job failed (" + r.failure() + ", " + r.how() + "); again under TCG with " + ram + " MiB");
            for (File f : new File[] {root, scratch}) {
                f.delete();
            }
            for (String[] a : new String[][] {{root.getPath(), "16G"}, {scratch.getPath(), "10G"}}) {
                String[] q = Emu.qemuImg(c, "create", "-q", "-f", "qcow2", a[0], a[1]);
                if (!"0".equals(q[0])) {
                    throw new IOException("qemu-img create failed: " + q[1].trim());
                }
            }
            r = buildRoot(c, disks, cpus, root, ipsw, p, cancelled, false, ram);
        }
        if (r.rc != 0) {
            root.delete();
            scratch.delete();
            throw new IOException("building the iOS disk failed: " + r.failure());
        }
    }

    private static Helper.Result buildRoot(Context c, List<Helper.Disk> disks, int cpus, File root, File ipsw, Progress p,
            BooleanSupplier cancelled, boolean allowNative, int ramMiB) throws IOException {
        return Helper.run(c, "build-root", disks, cpus, new File(work(c), "disk-job"), new Helper.Listener() {
            boolean dropped;

            @Override
            public void progress(int pct, String text) {
                p.step("disk", pct, text);
                // job-build-root.sh has unpacked the three images onto scratch ("copying iOS") and reads the
                // IPSW no more. When the phone is short of room for the root disk still to come (~12.3 GB),
                // the IPSW's blocks go now (truncated: QEMU still holds it open, an unlink would free
                // nothing); a failure after this downloads it again.
                if (!dropped && text.startsWith("copying iOS") && c.getFilesDir().getUsableSpace() < 14_000_000_000L) {
                    dropped = true;
                    try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(ipsw, "rw")) {
                        f.setLength(0);
                    } catch (IOException e) {
                        p.log("could not drop the IPSW early: " + e.getMessage());
                    }
                    mark(c, "ipsw").delete();
                    deleteSparse(ipsw);
                    p.log("IPSW dropped while the disk is copied (short of room)");
                }
            }

            @Override
            public void line(String text) {
                p.log(text);
            }
        }, cancelled, allowNative, ramMiB);
    }
}
