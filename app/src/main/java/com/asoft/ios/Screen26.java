package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * iOS 26's screen and its MobileGestalt cache. iOS works its screen out once (pixels, scale, the island's
 * place) and keeps the answers in a cache file on its Data volume, made again only when the OS build changes.
 * A user disk for the 2x screen starts as a copy-on-write of the installed disk, whose last boot (the setup's)
 * ran at 3x: with that cache iOS lays out for 1179x2556 @3x on the 786x1704 display, so the status bar finds
 * no layout (the red "rdar:45025538" strip) and the island and icons come out 1.5 times too big (2026-11-09).
 * iOS's own root shell may not touch the file (EPERM), so the helper VM does: before a disk's first start at
 * 2x with this build it looks at the cache (setup/phone/gestalt.py) and moves one made for another screen
 * aside; iOS writes a new one at that boot. Once per disk: a mark in the "guest" preferences.
 *
 * The same run puts the folder of iOS's Auto-Lock setting right (setup/phone/autolock.py): an earlier setup
 * made it as root, iOS could not write its effective settings there, kept its own one minute instead of the
 * setup's Never, and went to its Always On Display by itself, where the app cannot follow it (2026-11-10).
 * That counts only for a start that boots (a loaded save brings the disk back as it was): lockFixed.
 */
final class Screen26 {
    private Screen26() {}

    private static String key(String disk) {
        return "screen-cache-" + disk;
    }

    /** @disk's cache is known to be for a screen @width pixels wide (or not there: iOS makes it at this one). */
    static boolean fits(Context c, String disk, int width) {
        return c.getSharedPreferences("guest", Context.MODE_PRIVATE).getInt(key(disk), 0) == width;
    }

    static void mark(Context c, String disk, int width) {
        c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit().putInt(key(disk), width).commit();
    }

    /** The disk went back to a save (its cache with it), or is gone: looked at again before its next boot. */
    static void forget(Context c, String disk) {
        c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit().remove(key(disk)).remove(lockKey(disk)).commit();
    }

    private static String lockKey(String disk) {
        return "autolock-folder-" + disk;
    }

    /** When @disk booted with its Auto-Lock folder put right (ms since 1970); 0: not yet. */
    static long lockFixed(Context c, String disk) {
        return c.getSharedPreferences("guest", Context.MODE_PRIVATE).getLong(lockKey(disk), 0);
    }

    static void markLock(Context c, String disk) {
        c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit().putLong(lockKey(disk), System.currentTimeMillis()).commit();
    }

    /** A save from before that boot was loaded: the disk is as it was then, the folder with it. */
    static void forgetLock(Context c, String disk) {
        c.getSharedPreferences("guest", Context.MODE_PRIVATE).edit().remove(lockKey(disk)).commit();
    }

    private static String asset(Context c, String name) throws IOException {
        try (InputStream s = c.getAssets().open(name)) {
            return new String(Io.readAll(s), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.FileNotFoundException e) {
            throw new IOException("this build has no " + name + " (pull-setup.sh)");
        }
    }

    /** fit()'s helper VM folder; only iOS 26's (Installer.remove takes it with iOS 26). */
    static File jobDir(Context c) {
        return new File(c.getFilesDir(), "helper-jobs/ios26-screen");
    }

    /**
     * Has the helper VM look at @userDisk's cache, for a screen @width x @height pixels; iOS must not run on
     * the disk. True when the cache was another screen's and was moved aside: the disk's saves then hold iOS
     * as it ran with it (and the cache itself), so the caller drops the quick ones. About half a minute.
     */
    static boolean fit(Context c, File userDisk, int width, int height) throws IOException {
        File jd = jobDir(c);
        Helper.endLeftover(c, jd);
        Installer.deleteTree(jd);
        jd.mkdirs();
        if (!jd.isDirectory()) {
            throw new IOException("cannot make " + jd);
        }
        Map<String, Object> files = new LinkedHashMap<>();
        files.put(".job/run", "run.sh\n");
        files.put(".job/disks", "d0 user.qcow2\n");
        files.put(".job/out", "");
        files.put(".job/scratch", "");
        files.put("run.sh", "export ASOFT_S=scripts\n" + asset(c, "setup/phone/prelude.sh") + "\n"
                + asset(c, "setup/phone/jobs/ios26-screen.sh").replace("{WIDTH}", String.valueOf(width))
                        .replace("{HEIGHT}", String.valueOf(height)));
        files.put("scripts/root26-do.sh", asset(c, "setup/scripts/ios26/root26-do.sh"));
        files.put("phone/gestalt.py", asset(c, "setup/phone/gestalt.py"));
        files.put("phone/autolock.py", asset(c, "setup/phone/autolock.py"));
        File inTar = new File(jd, "in.tar"), outRaw = new File(jd, "out.raw");
        TarIo.write(inTar, files);
        try (RandomAccessFile r = new RandomAccessFile(outRaw, "rw")) {
            r.setLength(1 << 20);
        }
        List<Helper.Disk> disks = new ArrayList<>();
        disks.add(new Helper.Disk("in", inTar, "raw", true));
        disks.add(new Helper.Disk("out", outRaw, "raw", false));
        disks.add(new Helper.Disk("d0", userDisk, "qcow2", false, true));
        String[] said = {null, null};
        Helper.Listener l = new Helper.Listener() {
            @Override
            public void progress(int pct, String text) {
            }

            @Override
            public void line(String text) {
                if (text.startsWith("gestalt ")) {
                    said[0] = text.substring(8).trim();
                } else if (text.startsWith("autolock ")) {
                    said[1] = text.substring(9).trim();
                }
            }
        };
        int cpus = Math.min(8, Runtime.getRuntime().availableProcessors());
        // the setup's thermal hold is its own: this half minute runs as a start does
        BooleanSupplier hold = Helper.hold;
        Helper.hold = null;
        Helper.Result r;
        try {
            r = Helper.run(c, "run", disks, cpus, jd, l, () -> false, true);
            if (r.rc != 0 && r.nat) {
                AppLog.note(c, "screen26: the helper VM failed natively (" + r.last + "); again under TCG");
                said[0] = said[1] = null;
                r = Helper.run(c, "run", disks, cpus, jd, l, () -> false, false);
            }
        } finally {
            Helper.hold = hold;
        }
        if (r.rc != 0 || said[0] == null || said[1] == null) {
            throw new IOException("iOS 26's disk could not be checked before its start: the helper VM "
                    + (r.rc != 0 ? "failed (" + r.last + ")" : "said nothing about " + (said[0] == null ? "the cache" : "the Auto-Lock folder"))
                    + "; its log is in " + new File(jd, "serial.txt"));
        }
        AppLog.note(c, "screen26: " + userDisk.getName() + " for " + width + "x" + height + ": MobileGestalt cache "
                + said[0] + ", Auto-Lock folder " + said[1] + ", " + r.seconds + " s" + (r.nat ? " (native)" : " (TCG)"));
        Installer.deleteTree(jd);
        return said[0].startsWith("moved");
    }
}
