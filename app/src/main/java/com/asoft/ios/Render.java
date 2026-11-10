package com.asoft.ios;

import android.view.Surface;

/** render.c: the guest framebuffer, from QEMU's RAM memfd, onto a Surface with GLES. */
final class Render {
    static {
        System.loadLibrary("render");
    }

    private Render() {}

    /** A memfd of @size for the guest's RAM, inherited by the next spawn(); its fd number, or -1. */
    static native int createGuestRam(long size);

    /** Lets go of the guest RAM memfd (its pages go back to the phone). */
    static native void closeGuestRam();

    /** Gives the guest RAM memfd's pages back once QEMU has ended (the fd stays, at its size); false if none. */
    static native boolean releaseGuestRam();

    /** A memfd of @size for iOS 26's frames (QEMU's ASOFT_FRAME_FD), inherited by the next spawn(); its fd, or -1. */
    static native int createFrameMemory(long size);

    /** @len bytes of iOS 26's frame memory from @off into @out; bytes read, -1 before a start. */
    static native int readFrame(long off, byte[] out, int len);

    /** Starts @argv with @envp (K=V), output to @log, keeping the guest RAM fd; the pid, or -1. */
    static native int spawn(String[] argv, String[] envp, String log);

    static native boolean alive(int pid);

    /** The raw wait status @pid ended with, as the alive() that reaped it saw it; -1 when unknown. */
    static native int endStatus(int pid);

    static native void terminate(int pid);

    /** SIGKILL, for a QEMU that does not end on terminate() (iOS 26's). */
    static native void forceKill(int pid);

    /** Maps the framebuffer of QEMU process @pid (@offset in its RAM; -1: the frame memfd); null when done, else what went wrong. */
    static native String openFramebuffer(int pid, long offset, int width, int height, int stride);

    /** Binds GLES to @s; call on the render thread, like every call below. */
    static native boolean attach(Surface s);

    static native void detach();

    /** One display frame; false when nothing changed (nothing drawn). */
    static native boolean frame(int width, int height);

    static native long uploads();

    /** Unmaps the framebuffer and blackens the surface (render thread). */
    static native void clear();
}
