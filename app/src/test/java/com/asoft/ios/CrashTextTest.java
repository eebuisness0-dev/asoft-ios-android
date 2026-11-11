package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class CrashTextTest {
    /** The newer engine's fatal line (the crash test's). */
    private static final String FATAL_552 = "reims-vgpu: fatal: platform call renderCommandEncoderWithDescriptor:: the Vulkan runtime"
            + " refused it: unsupported: Unsupported { feature: Format, needs: NotImplemented, location: None, symbol: None };"
            + " the pass's attachments: colour 0 552 (BGR10_XR) Type2D usage 0x4 Private";
    /** The older engine's, with Metal's own format names. */
    private static final String CAUSE_555 = "platform call renderCommandEncoderWithDescriptor:: the Vulkan runtime refused it:"
            + " unsupported: Unsupported { feature: Format, needs: NotImplemented, location: None, symbol: None }; the pass's"
            + " attachments: colour 0 555 (MTLPixelFormatBGR10_XR_sRGB) Type2D usage 0x4 Private";

    @Test
    public void fatalLinePicksTheLastAndDropsItsPrefix() {
        List<String> lines = Arrays.asList(
                "reims-vgpu: fatal: the first one",
                "something else",
                "[12.345] qemu: reims-vgpu: fatal: the last one",
                "after it");
        assertEquals("reims-vgpu: fatal: the last one", CrashText.fatalLine(lines));
        assertNull(CrashText.fatalLine(Arrays.asList("reims[warn] nothing fatal", "ok")));
    }

    @Test
    public void evidenceKeepsEarlyReimsLinesAndExactlyTheTail() throws IOException {
        StringBuilder log = new StringBuilder();
        log.append("reims[warn] create::function: Metal refused the function's IR\n");
        log.append("boring 0\n");
        log.append("reims[warn] create::function: Metal refused the function's IR\n");
        log.append("reims-vgpu: fatal: early\n");
        log.append("reims[warn] create::function: Metal refused the function's IR\n");
        for (int i = 1; i <= 500; i++) {
            log.append("line ").append(i).append('\n');
        }
        List<String> ev = CrashText.evidence(new BufferedReader(new StringReader(log.toString())), 200, 80);
        assertEquals("--- reims/panic lines before the last 200 lines ---", ev.get(0));
        assertEquals("reims[warn] create::function: Metal refused the function's IR (×3)", ev.get(1));
        assertEquals("reims-vgpu: fatal: early", ev.get(2));
        assertEquals("--- the last 200 lines ---", ev.get(3));
        assertEquals(4 + 200, ev.size());
        assertEquals("line 301", ev.get(4));
        assertEquals("line 500", ev.get(ev.size() - 1));
        assertFalse(ev.contains("boring 0"));
        // the fatal line before the tail is still found
        assertEquals("reims-vgpu: fatal: early", CrashText.fatalLine(ev));
    }

    @Test
    public void evidenceOfAShortLogIsAllTail() throws IOException {
        List<String> ev = CrashText.evidence(new BufferedReader(new StringReader("a\r\nreims[warn] x\nb")), 200, 80);
        assertEquals(Arrays.asList("--- the last 3 lines ---", "a", "reims[warn] x", "b"), ev);
    }

    @Test
    public void evidenceCountsWhatItLeavesOut() throws IOException {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            log.append("reims[warn] distinct ").append(i).append('\n');
        }
        for (int i = 0; i < 5; i++) {
            log.append("tail ").append(i).append('\n');
        }
        List<String> ev = CrashText.evidence(new BufferedReader(new StringReader(log.toString())), 5, 4);
        assertEquals("reims[warn] distinct 3", ev.get(4));
        assertEquals("… 6 more distinct lines left out", ev.get(5));
    }

    @Test
    public void describeStatus() {
        assertEquals("signal 6 (SIGABRT)", CrashText.describeStatus(6));
        assertEquals("signal 6 (SIGABRT)", CrashText.describeStatus(0x86));     // with a core dump
        assertEquals("signal 9 (SIGKILL)", CrashText.describeStatus(9));
        assertEquals("exit code 1", CrashText.describeStatus(0x100));
        assertEquals("exit code 0", CrashText.describeStatus(0));
        assertEquals("unknown", CrashText.describeStatus(-1));
    }

    @Test
    public void isCrash() {
        assertFalse(CrashText.isCrash(0, null));
        assertTrue(CrashText.isCrash(0, "reims-vgpu: fatal: x"));
        assertTrue(CrashText.isCrash(6, null));
        assertTrue(CrashText.isCrash(0x100, null));
        assertTrue(CrashText.isCrash(-1, null));
    }

    @Test
    public void causeOfAFatalLine() {
        String c = CrashText.cause(FATAL_552, Collections.singletonList(FATAL_552), 6);
        assertTrue(c, c.startsWith("platform call renderCommandEncoderWithDescriptor:: the Vulkan runtime refused it"));
        assertTrue(c, c.contains("552 (BGR10_XR)"));
    }

    @Test
    public void causeOfARustPanicOnTwoLines() {
        List<String> tail = Arrays.asList(
                "something",
                "thread '<unnamed>' panicked at reims-vgpu/src/heap.rs:120:9:",
                "called `Option::unwrap()` on a `None` value",
                "note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace");
        assertEquals("thread '<unnamed>' panicked at reims-vgpu/src/heap.rs:120:9: called `Option::unwrap()` on a `None` value",
                CrashText.cause(null, tail, 6));
        // the older one-line form
        assertEquals("thread 'main' panicked at 'boom', src/main.rs:2:5",
                CrashText.cause(null, Arrays.asList("thread 'main' panicked at 'boom', src/main.rs:2:5", "note: x"), 0x6500));
    }

    @Test
    public void causeOfASigkillWithNoLines() {
        assertEquals("QEMU was ended by signal 9 (SIGKILL); Android ends an app's processes this way when the phone is short of memory",
                CrashText.cause(null, Collections.<String>emptyList(), 9));
        assertEquals("QEMU ended by itself", CrashText.cause(null, Collections.<String>emptyList(), -1));
        assertEquals("QEMU ended with exit code 1", CrashText.cause(null, Collections.singletonList("fine"), 0x100));
    }

    @Test
    public void causeOfStorageAndAssertions() {
        assertEquals("the phone's storage was full",
                CrashText.cause(null, Arrays.asList("qcow2: write failed: No space left on device", "x"), 6));
        String a = CrashText.cause(null, Collections.singletonList(
                "qemu: ../hw/core/x.c:10: f: Assertion `n > 0' failed."), 6);
        assertTrue(a, a.startsWith("qemu: ../hw/core/x.c:10: f: Assertion `n > 0' failed."));
        assertTrue(a, a.endsWith("QEMU was ended by signal 6 (SIGABRT)"));
    }

    @Test
    public void shortCauseOfTheOldFormatStyle() {
        String s = CrashText.shortCause(CAUSE_555);
        assertTrue(s, s.contains("renderCommandEncoderWithDescriptor:"));
        assertTrue(s, s.contains("BGR10_XR_sRGB (555)"));
        assertTrue(s, s.length() <= 100);
    }

    @Test
    public void shortCauseOfTheNewFormatStyle() {
        String cause = CrashText.cause(FATAL_552, Collections.singletonList(FATAL_552), 6);
        assertEquals("renderCommandEncoderWithDescriptor:: BGR10_XR (552)", CrashText.shortCause(cause));
    }

    @Test
    public void shortCauseWithoutAFormat() {
        assertEquals("MTLCreateSystemDefaultDevice(): the Vulkan runtime refused it: Vulkan failure -13",
                CrashText.shortCause("platform call MTLCreateSystemDefaultDevice(): the Vulkan runtime refused it: Vulkan failure -13"));
        assertEquals("newBufferWithBytesNoCopy:length:options:deallocator:: unsupported HostMemory",
                CrashText.shortCause("platform call newBufferWithBytesNoCopy:length:options:deallocator:: the Vulkan runtime"
                        + " refused it: unsupported: Unsupported { feature: HostMemory, needs: NotImplemented }"));
        String longOne = "something went wrong in a way that takes many words to say, more than a title can hold, and then some more";
        String s = CrashText.shortCause(longOne);
        assertTrue(s, s.length() <= 100 && s.length() > 90);
        assertTrue(s, s.endsWith("…"));
    }

    @Test
    public void scrub() {
        Map<String, String> pre = new HashMap<>();
        pre.put("/data/user/0/com.asoft.ios.dev/files", "<files>");
        pre.put("/data/user/0/com.asoft.ios.dev", "<data>");
        pre.put("/data/data/com.asoft.ios.dev", "<data>");
        assertEquals("open <files>/run/x", CrashText.scrub("open /data/user/0/com.asoft.ios.dev/files/run/x", pre));
        assertEquals("<data>/cache/y", CrashText.scrub("/data/data/com.asoft.ios.dev/cache/y", pre));
        // without the app's folders the path still goes
        assertEquals("open <path>", CrashText.scrub("open /data/user/0/com.asoft.ios.dev/files/run/x", null));
        // a file the user named, space and all
        String s = CrashText.scrub("import /storage/emulated/0/Download/Ali Veli.asoft", pre);
        assertEquals("import <path>", s);
        assertEquals("file=<path>,if=none", CrashText.scrub("file=/sdcard/Download/a b.qcow2,if=none", pre));
        assertEquals("'<path>': No such file", CrashText.scrub("'/storage/emulated/0/x.asoft': No such file", pre));
        assertEquals("<lib>/libqemu26.so", CrashText.scrub("/data/app/~~AbC==/com.asoft.ios.dev-XyZ==/lib/arm64/libqemu26.so", pre));
        assertEquals("dns <ip>, guest 10.0.2.15, local 127.0.0.1, any 0.0.0.0",
                CrashText.scrub("dns 192.168.1.23, guest 10.0.2.15, local 127.0.0.1, any 0.0.0.0", pre));
        assertEquals("version v1.2.3.4 and 1.2.3.4.5", CrashText.scrub("version v1.2.3.4 and 1.2.3.4.5", pre));
        assertEquals("mail <email>", CrashText.scrub("mail someone@example.com", pre));
        assertEquals("AppIcon60x60@2x.png", CrashText.scrub("AppIcon60x60@2x.png", pre));
        assertEquals("wifi <mac>", CrashText.scrub("wifi 02:1A:2b:3c:4D:5e", pre));
        assertEquals("found adb-<id>._adb-tls-connect._tcp",
                CrashText.scrub("found adb-R5GYB4V4PKL-AbCdEf._adb-tls-connect._tcp", pre));
        assertEquals("built in /home/~/reims/target", CrashText.scrub("built in /home/alper/reims/target", pre));
        assertEquals("/Users/~/src and C:\\Users\\~\\src", CrashText.scrub("/Users/alper/src and C:\\Users\\alper\\src", pre));
    }

    @Test
    public void issueUrlEncodesSpacesAndNewlines() {
        String u = CrashText.issueUrl("o/r", "a b", "x\ny+z");
        assertEquals("https://github.com/o/r/issues/new?title=a%20b&body=x%0Ay%2Bz", u);
    }

    @Test
    public void bodyKeepsItsFences() {
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("App", "0.1.2 | dev");
        String b = CrashText.body(facts, "iOS 26 crashed", "cause", Collections.singletonList("a ``` b"),
                Collections.singletonList("app"), false);
        assertTrue(b, b.contains("| App | 0.1.2 \\| dev |"));
        assertTrue(b, b.contains("a ''' b"));
        assertFalse(b, b.contains("shortened"));
        assertTrue(b, b.endsWith("_Written by ASoftware's crash report; nothing was sent by itself._\n"));
    }

    @Test
    public void fitBodyKeepsTheEarlierReimsLines() throws IOException {
        StringBuilder in = new StringBuilder("reims[warn] create::function: Metal refused the function's IR\n");
        for (int i = 0; i < 600; i++) {
            in.append("native-el0: stats line ").append(i).append(" with some more words to make it longer\n");
        }
        in.append(FATAL_552).append('\n');
        List<String> ev = CrashText.evidence(new BufferedReader(new StringReader(in.toString())), 200, 80);
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("App", "0.1.2-dev (4)");
        String b = CrashText.fitBody("o/r", "t", facts, "w", FATAL_552, ev, Collections.singletonList("app"), 7000);
        assertTrue(CrashText.issueUrl("o/r", "t", b).length() <= 7000);
        assertTrue(b, b.contains("reims[warn] create::function"));
        assertTrue(b, b.contains("--- reims/panic lines before the last 200 lines ---"));
        // the tail lost its oldest lines, and says how many it kept
        assertTrue(b, b.contains("stats line 599 with"));
        assertFalse(b, b.contains("stats line 401 with"));
        assertFalse(b, b.contains("--- the last 200 lines ---"));
        assertTrue(b, b.matches("(?s).*--- the last \\d+ lines ---.*"));
    }

    @Test
    public void fitBodyStaysWithinTheLink() {
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("App", "0.1.2-dev (4)");
        facts.put("Phone", "samsung SM-S938B, SoC SM8750, Android 16 (SDK 36)");
        List<String> err = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            err.add("reims[info] a long line of QEMU's output number " + i + " with some more words to make it longer");
        }
        List<String> app = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            app.add("11-10 12:00:00 app line " + i);
        }
        String title = "iOS 26: renderCommandEncoderWithDescriptor:: BGR10_XR (552)";
        String cause = CrashText.cause(FATAL_552, Collections.singletonList(FATAL_552), 6);
        String b = CrashText.fitBody("alpersamur3/asoft-ios-android", title, facts, "iOS 26 crashed", FATAL_552, err, app, 7000);
        String url = CrashText.issueUrl("alpersamur3/asoft-ios-android", title, b);
        assertTrue(url.length() + " > 7000", url.length() <= 7000);
        assertTrue(url.length() + " is far below 7000", url.length() > 6000);
        assertTrue(b.contains(FATAL_552));
        assertTrue(b.contains(cause));
        assertTrue(b.contains("_The logs were shortened"));
        // the newest lines stay
        assertTrue(b.contains("number 499 with"));
        assertTrue(b.contains("app line 149"));
        assertFalse(b.contains("number 0 with"));
        // a short report goes whole
        String whole = CrashText.fitBody("o/r", "t", facts, "w", "c", err.subList(0, 3), app.subList(0, 3), 7000);
        assertFalse(whole.contains("_The logs were shortened"));
        assertTrue(whole.contains("number 2 with"));
    }
}
