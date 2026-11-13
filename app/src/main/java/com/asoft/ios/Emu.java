package com.asoft.ios;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/** Where QEMU and its libraries are, and how to start it. */
final class Emu {
    private Emu() {}

    /**
     * An APK can only carry lib*.so, so a library with the soname libz.so.1 is packed as
     * lib_libz.so.1.so. The loader looks for the soname: give it a directory of links.
     */
    static File linkLibraries(Context c) throws IOException {
        File links = new File(c.getFilesDir(), "lib");
        if (!links.isDirectory() && !links.mkdirs()) {
            throw new IOException("cannot create " + links);
        }
        File[] packed = new File(c.getApplicationInfo().nativeLibraryDir).listFiles();
        if (packed == null) {
            throw new IOException("no native library directory");
        }
        for (File f : packed) {
            String n = f.getName();
            if (!n.startsWith("lib_") || !n.endsWith(".so")) {
                continue;
            }
            File link = new File(links, n.substring(4, n.length() - 3));
            try {
                if (f.getPath().equals(Os.readlink(link.getPath()))) {
                    continue;       // as it was: a QEMU starting now finds every link
                }
            } catch (ErrnoException ignored) {
                // no link yet
            }
            link.delete();
            try {
                Os.symlink(f.getPath(), link.getPath());
            } catch (ErrnoException e) {
                throw new IOException("symlink " + link, e);
            }
        }
        return links;
    }

    /**
     * The same links without the Khronos loader and Turnip's driver (libvulkan.so, libvulkan_freedreno.so): a QEMU
     * with this as its LD_LIBRARY_PATH that dlopens libvulkan.so gets the phone's own loader and driver
     * (GpuCheck.useSystem, Guest.start26).
     */
    static File linkLibrariesSystemVulkan(Context c) throws IOException {
        File all = linkLibraries(c);
        File dir = new File(c.getFilesDir(), "lib-sysvk");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        new File(dir, "libvulkan.so").delete();
        new File(dir, "libvulkan_freedreno.so").delete();
        File[] names = all.listFiles();
        if (names == null) {
            throw new IOException("no library links in " + all);
        }
        for (File f : names) {
            String n = f.getName();
            if (n.equals("libvulkan.so") || n.equals("libvulkan_freedreno.so")) {
                continue;
            }
            File link = new File(dir, n);
            try {
                String target = Os.readlink(f.getPath());
                try {
                    if (target.equals(Os.readlink(link.getPath()))) {
                        continue;
                    }
                } catch (ErrnoException ignored) {
                    // no link yet
                }
                link.delete();
                Os.symlink(target, link.getPath());
            } catch (ErrnoException e) {
                throw new IOException("symlink " + link, e);
            }
        }
        return dir;
    }

    static String qemuPath(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/libqemu.so";
    }

    /**
     * iOS 26's QEMU: the NDK build with reims-vgpu + metal2vulkan linked in (13-qemu.sh in WSL),
     * packed from jniLibs26 (pull-libs26.sh) beside Turnip and the Vulkan loader.
     */
    static String qemu26Path(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/libqemu26.so";
    }

    /** qemu-img, packed as libqemuimg.so (pull-libs.sh). */
    static String imgPath(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/libqemuimg.so";
    }

    /** One qemu-img at a time: two of them on one disk refuse each other's lock (2026-11-05). */
    private static final Object IMG_LOCK = new Object();

    /**
     * Runs qemu-img with @args and waits for it: [exit code, output]. A short tool, so the JVM's
     * ProcessBuilder is fine here (it needs no inherited descriptor). The disk's lock may still be
     * held for a moment by a QEMU that is ending (or another app's qemu-img): retried for ~5 s.
     */
    static String[] qemuImg(Context c, String... args) throws IOException {
        synchronized (IMG_LOCK) {
            String[] r = null;
            for (int attempt = 0; attempt < 16; attempt++) {
                r = qemuImgOnce(c, args);
                if ("0".equals(r[0]) || !(r[1].contains("Failed to get") && r[1].contains("lock"))) {
                    break;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (!"0".equals(r[0])) {
                String t = r[1].trim();
                AppLog.note(c, "qemu-img " + String.join(" ", args) + ": exit " + r[0] + ": "
                        + (t.length() > 300 ? t.substring(t.length() - 300) : t));
            }
            return r;
        }
    }

    private static String[] qemuImgOnce(Context c, String... args) throws IOException {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(imgPath(c));
        cmd.addAll(java.util.Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        baseEnv(c, linkLibraries(c), pb.environment());
        Process p = pb.start();
        String out = new String(Io.readAll(p.getInputStream()), java.nio.charset.StandardCharsets.UTF_8);
        try {
            int code = p.waitFor();
            return new String[] { String.valueOf(code), out };
        } catch (InterruptedException e) {
            p.destroy();
            throw new IOException("qemu-img interrupted", e);
        }
    }

    static File copyAsset(Context c, String name) throws IOException {
        File out = new File(c.getFilesDir(), name);
        try (InputStream in = c.getAssets().open(name); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
            }
        }
        return out;
    }

    /** The environment every QEMU child gets; the caller adds the engine's switches. */
    static void baseEnv(Context c, File links, Map<String, String> env) {
        env.put("LD_LIBRARY_PATH", links.getPath());
        env.put("HOME", c.getFilesDir().getPath());
        env.put("TMPDIR", c.getCacheDir().getPath());
    }
}
