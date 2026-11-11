package com.asoft.ios;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Set up's "iOS 26 and watchOS from Apple": the GPU check first (no download without a pass; a failure says
 * the GPU is not supported yet and offers its log to copy or share), then the choice (iOS 26, watchOS or
 * both; the shared part once), the download's files, every step with its marker, the space (free, what the
 * next step needs, the peak so far) and why it waits (Wi-Fi, heat, battery). SetupScreen's style.
 */
final class Setup26Screen {
    private static final int CARD = 0xff1c1c1e, LINE = 0xff38383a, GRAY = 0xff9a9a9a, DIM = 0xff777777,
            RED = 0xffff453a, GREEN = 0xff30d158, ORANGE = 0xffff9f0a;

    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("kernelcache.research.vphone600", "cloudOS research kernel");
        NAMES.put("Firmware/sptm.vresearch1.release.im4p", "SPTM");
        NAMES.put("Firmware/txm.iphoneos.research.im4p", "TXM");
        NAMES.put("Firmware/all_flash/DeviceTree.vphone600ap.im4p", "Device tree");
        NAMES.put("094-39914-031.dmg", "cloudOS ramdisk");
        NAMES.put("094-39278-029.dmg.aea", "cloudOS system (the GPU driver)");
        NAMES.put("094-97246-090.dmg.aea", "iOS 26 itself");
        NAMES.put("094-95368-092.dmg.aea", "System libraries (SystemOS)");
        NAMES.put("094-96441-094.dmg", "Built-in apps (AppOS)");
        NAMES.put("141-08453-168.dmg.aea", "watchOS itself");
        NAMES.put("Firmware/all_flash/DeviceTree.n207sap.im4p", "Watch device tree");
    }

    private final Activity a;
    private final LinearLayout root;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView gpu, free, total, details, error, memory;
    /** Share log and Report on GitHub under a failed step's red line (SetupScreen.logButtons). */
    private View errorButtons;
    private ProgressBar totalBar;
    private LinearLayout gpuButtons, choice, files, steps;
    private CheckBox want26, wantWatch;
    private Button go, cancel;
    private final Map<String, TextView[]> fileRows = new LinkedHashMap<>();
    private final Map<String, ProgressBar> fileBars = new LinkedHashMap<>();
    private final Map<String, TextView> stepRows = new LinkedHashMap<>();
    private volatile GpuCheck.Result gpuResult;
    private String builtFor = "";

    Setup26Screen(Activity a) {
        this.a = a;
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        gpuResult = GpuCheck.dir(a).isDirectory() ? GpuCheck.last(a) : null;
        build();
        tick();
    }

    View view() {
        if (root.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) root.getParent()).removeView(root);
        }
        return root;
    }

    private LinearLayout card(LinearLayout to, int top) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(48, 36, 48, 36);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(36);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        to.addView(c, lp);
        return c;
    }

    private TextView text(LinearLayout to, String s, float size, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        to.addView(t);
        return t;
    }

    private void line(LinearLayout to) {
        View v = new View(a);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 2);
        lp.topMargin = 20;
        lp.bottomMargin = 20;
        to.addView(v, lp);
    }

    private Button button(LinearLayout to, String s, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        to.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        return b;
    }

    private void build() {
        TextView title = text(root, "iOS 26 and watchOS from Apple", 18, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 56, 0, 8);
        text(root, "iOS 26.6.2 and watchOS 27.2 run on the cloudOS 26.4 research kernel and draw through the phone's GPU. "
                + "The phone downloads Apple's files itself and builds their disks as the PC installer does: iOS 26 is about "
                + "10 GB from Apple and several hours of work, watchOS about 5 GB and about an hour, and their shared part "
                + "1 GB once. Keep the phone on its charger and on Wi-Fi.", 15, GRAY).setPadding(0, 0, 0, 16);

        LinearLayout g = card(root, 16);
        gpu = text(g, "", 15, Color.WHITE);
        gpuButtons = new LinearLayout(a);
        gpuButtons.setOrientation(LinearLayout.HORIZONTAL);
        gpuButtons.setPadding(0, 16, 0, 0);
        g.addView(gpuButtons);

        choice = card(root, 24);
        want26 = check(choice, "iOS 26", PhoneSetup26.wants(a, PhoneSetup26.IOS26));
        line(choice);
        wantWatch = check(choice, "watchOS (Apple Watch Series 9)", PhoneSetup26.wants(a, PhoneSetup26.WATCH));

        free = text(root, "", 13, DIM);
        free.setPadding(0, 16, 0, 0);
        LinearLayout dl = card(root, 16);
        total = text(dl, "", 16, Color.WHITE);
        totalBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        totalBar.setMax(1000);
        dl.addView(totalBar, new LinearLayout.LayoutParams(-1, 24));
        details = text(dl, "", 13, GRAY);
        details.setPadding(0, 12, 0, 0);
        files = new LinearLayout(a);
        files.setOrientation(LinearLayout.VERTICAL);
        dl.addView(files);

        steps = card(root, 24);
        error = text(root, "", 15, RED);
        error.setPadding(8, 24, 8, 0);
        errorButtons = SetupScreen.logButtons(a, root, () -> SetupService.state.record);
        memory = text(root, "", 13, GRAY);
        memory.setPadding(8, 16, 8, 0);

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, 24, 0, 0);
        go = new Button(a);
        go.setAllCaps(false);
        go.setTextSize(17);
        go.setOnClickListener(v -> onGo());
        cancel = new Button(a);
        cancel.setAllCaps(false);
        cancel.setText("Cancel");
        cancel.setTextColor(RED);
        cancel.setOnClickListener(v -> new android.app.AlertDialog.Builder(a)
                .setTitle("Cancel the install?")
                .setMessage("What was downloaded and built for it is deleted (an installed iOS 26 or watchOS stays).")
                .setPositiveButton("Cancel install", (d, w) -> SetupService.cancel(a))
                .setNegativeButton("Keep going", null)
                .show());
        buttons.addView(go, new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
        root.addView(buttons);
    }

    private CheckBox check(LinearLayout to, String s, boolean on) {
        CheckBox b = new CheckBox(a);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setChecked(on);
        to.addView(b);
        return b;
    }

    private boolean mine() {
        return "a26".equals(SetupService.state.target);
    }

    private void onGo() {
        SetupService.State s = SetupService.state;
        if (s.running && mine()) {
            if (s.paused) {
                SetupService.startA26(a);
            } else {
                SetupService.pause(a);
            }
            return;
        }
        if (s.running) {
            error.setText("Another install is running: let it finish first.");
            return;
        }
        if (Guest.running()) {
            error.setText("Close iOS first (side bar > Saves > Save and close): the setup needs its memory and its QEMU.");
            return;
        }
        if (!GpuCheck.passed(a)) {
            runCheck();
            return;
        }
        boolean i26 = want26.isChecked() && !PhoneSetup26.installed(a, PhoneSetup26.IOS26);
        boolean w = wantWatch.isChecked() && !PhoneSetup26.installed(a, PhoneSetup26.WATCH);
        if (!i26 && !w) {
            error.setText("Choose iOS 26, watchOS or both.");
            return;
        }
        PhoneSetup26.setWants(a, i26, w);
        error.setText("");
        SetupService.startA26(a);
    }

    private void runCheck() {
        if (GpuCheck.running) {
            return;
        }
        gpu.setText("Checking this phone's GPU… (about 10 s)");
        gpu.setTextColor(Color.WHITE);
        new Thread(() -> {
            GpuCheck.Result r = GpuCheck.run(a);
            gpuResult = r;
            a.runOnUiThread(() -> builtFor = "");
        }, "gpucheck-run").start();
    }

    private void gpuCard() {
        GpuCheck.Result r = gpuResult;
        String key = GpuCheck.running ? "running" : r == null ? "none" : r.pass + (GpuCheck.passed(a) ? "p" : "");
        if (key.equals(builtFor)) {
            return;
        }
        builtFor = key;
        gpuButtons.removeAllViews();
        if (GpuCheck.running) {
            gpu.setText("Checking this phone's GPU… (about 10 s)");
            gpu.setTextColor(Color.WHITE);
            return;
        }
        if (r == null || !r.ran || r.pass && !GpuCheck.passed(a)) {
            // (a pass of another build of the app, or of its Turnip, does not count: checked again)
            gpu.setText("First, a short check that this phone's GPU can run them (a few tests with Vulkan, nothing is downloaded).");
            gpu.setTextColor(Color.WHITE);
            return;                     // the screen's own button below says "Check the GPU" (onGo)
        }
        if (r.pass && GpuCheck.passed(a)) {
            String warn = GpuCheck.ramWarning(a);
            String head = "✓ " + r.summary;
            // a pass in the compatibility mode (GpuCheck.noSparse): said in amber right under the green line
            String compat = r.nosparse ? "\nThis GPU's driver cannot do sparse binds from host memory, so iOS 26 and watchOS run in"
                    + " a compatibility mode that can stop after long use. The app saves before that when it can." : "";
            android.text.SpannableString t = new android.text.SpannableString(head + compat
                    + (warn.isEmpty() ? "" : "\n\n" + warn) + (r.slow.isEmpty() ? "" : "\n\n" + r.slow));
            if (!compat.isEmpty()) {
                t.setSpan(new android.text.style.ForegroundColorSpan(ORANGE), head.length(), head.length() + compat.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            gpu.setText(t);
            gpu.setTextColor(warn.isEmpty() ? GREEN : ORANGE);
            return;
        }
        StringBuilder t = new StringBuilder(r.summary);
        if (!r.why.isEmpty()) {
            t.append("\n\nIt needs:");
            for (String w : r.why) {
                t.append("\n• ").append(w);
            }
        }
        if (!r.slow.isEmpty()) {
            t.append("\n\n").append(r.slow);
        }
        t.append(r.never
                ? "\n\nThere is no mode without the GPU, so they cannot be installed here. You can still send us the check's log: "
                        + "it helps us know which phones people have."
                : "\n\nThere is no mode without the GPU, so they cannot be installed yet. Please send us the check's log: it says "
                        + "what this GPU's driver lacks and helps us add it.");
        gpu.setText(t.toString());
        gpu.setTextColor(ORANGE);
        button(gpuButtons, "Copy log", v -> {
            ClipboardManager cm = a.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("ASoftware GPU check", r.report));
            android.widget.Toast.makeText(a, "The log is copied", android.widget.Toast.LENGTH_SHORT).show();
        });
        button(gpuButtons, "Share log", v -> a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "ASoftware iOS GPU check: " + android.os.Build.MODEL)
                .putExtra(Intent.EXTRA_TEXT, r.report), "Send the GPU check's log")));
        button(gpuButtons, "Check again", v -> runCheck());
    }

    private static String gb(long b) {
        return b >= 1e9 ? String.format(Locale.ROOT, "%.2f GB", b / 1e9)
                : b >= 1e6 ? String.format(Locale.ROOT, "%.0f MB", b / 1e6) : String.format(Locale.ROOT, "%.0f KB", b / 1e3);
    }

    private static String minutes(double s) {
        if (s < 60) {
            return "less than a minute";
        }
        long m = Math.round(s / 60);
        return m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min";
    }

    private void tick() {
        if (a.isFinishing() || a.isDestroyed()) {
            return;
        }
        update();
        h.postDelayed(this::tick, 500);
    }

    private void update() {
        gpuCard();
        boolean pass = GpuCheck.passed(a);
        SetupService.State s = SetupService.state;
        boolean running = s.running && mine();
        boolean i26done = PhoneSetup26.installed(a, PhoneSetup26.IOS26), wdone = PhoneSetup26.installed(a, PhoneSetup26.WATCH);
        want26.setText(i26done ? "iOS 26 ✓ installed" : "iOS 26 (about 10.2 GB from Apple; needs ~46 GB free while it builds, keeps ~12 GB)");
        wantWatch.setText(wdone ? "watchOS ✓ installed" : "watchOS 27.2 (about 5 GB from Apple; needs ~20 GB free while it builds, keeps ~4 GB)");
        want26.setEnabled(!running && !i26done);
        wantWatch.setEnabled(!running && !wdone);
        choice.setVisibility(pass ? View.VISIBLE : View.GONE);

        long fr = a.getFilesDir().getUsableSpace(), need = PhoneSetup26.needBytes(a);
        String peak = PhoneSetup26.startFree > 0 ? String.format(Locale.ROOT, "; this run's peak so far %s",
                gb(Math.max(0, PhoneSetup26.startFree - PhoneSetup26.lowestFree))) : "";
        free.setText(String.format(Locale.ROOT, "%s free on this phone%s%s", gb(fr), need > 0 && running ? "; the next step needs about " + gb(need) : "", peak));
        free.setTextColor(fr < need ? ORANGE : DIM);
        free.setVisibility(pass ? View.VISIBLE : View.GONE);

        List<IpswFetch.Item> items = running ? s.items : java.util.Collections.emptyList();
        long got = 0, size = 0;
        for (IpswFetch.Item it : items) {
            got += it.done.get();
            size += it.total;
            row(it.member, it.done.get(), it.total, it.state, it.error);
        }
        boolean fetching = running && s.step.endsWith(":fetch");
        ((View) total.getParent()).setVisibility(pass && (running || PhoneSetup26.work(a).isDirectory()) ? View.VISIBLE : View.GONE);
        if (size > 0 && fetching) {
            total.setText(String.format(Locale.ROOT, "%s of %s from Apple", gb(got), gb(size)));
            totalBar.setProgress((int) (got * 1000 / size));
        } else {
            total.setText(running ? s.text : "Downloads");
            totalBar.setProgress(running && s.pct >= 0 ? s.pct * 10 : 0);
        }
        if (fetching && s.mbps > 0 && size > 0) {
            details.setText(String.format(Locale.ROOT, "%.1f MB/s · %s left · %d connections%s", s.mbps, minutes((size - got) / 1e6 / s.mbps),
                    s.connections, s.servers.isEmpty() ? "" : " · " + s.servers));
        } else if (running && s.waitingForNetwork) {
            details.setText("No internet connection: it goes on by itself when the phone is online again");
        } else if (running && s.waitingForWifi) {
            details.setText("Waiting for Wi-Fi (Download on Wi-Fi only is on)");
        } else if (running && s.held) {
            details.setText(PhoneSetup26.lowBattery(a) ? "Waiting for the charger: the battery is low"
                    : s.paused ? "Paused" : "The phone is hot: the build waits until it cools down, then goes on");
        } else if (running && s.paused) {
            details.setText("Paused: what is done stays, Resume goes on from there");
        } else if (running && !PhoneSetup26.charging(a)) {
            details.setText("Not on the charger: it keeps going, and waits for the charger below 15 %");
        } else {
            details.setText("");
        }

        String sig = (pass ? "p" : "") + PhoneSetup26.groups(a) + want26.isChecked() + wantWatch.isChecked();
        if (!sig.equals(steps.getTag())) {
            steps.setTag(sig);
            steps.removeAllViews();
            stepRows.clear();
            List<String> gs = running || PhoneSetup26.work(a).isDirectory() ? PhoneSetup26.groups(a) : null;
            if (gs == null) {
                gs = new java.util.ArrayList<>();
                gs.add(PhoneSetup26.SHARED);
                if (wantWatch.isChecked()) {
                    gs.add(PhoneSetup26.WATCH);
                }
                if (want26.isChecked()) {
                    gs.add(PhoneSetup26.IOS26);
                }
            }
            for (String g : gs) {
                TextView head = text(steps, PhoneSetup26.SHARED.equals(g) ? "Shared part" : PhoneSetup26.IOS26.equals(g) ? "iOS 26" : "watchOS",
                        13, DIM);
                head.setPadding(0, steps.getChildCount() > 1 ? 24 : 0, 0, 8);
                for (String[] st : PhoneSetup26.steps(g)) {
                    stepRows.put(g + ":" + st[0], text(steps, "○  " + st[1], 15, GRAY));
                }
            }
        }
        steps.setVisibility(pass && !stepRows.isEmpty() ? View.VISIBLE : View.GONE);
        for (Map.Entry<String, TextView> e : stepRows.entrySet()) {
            String[] k = e.getKey().split(":");
            String label = "";
            for (String[] st : PhoneSetup26.steps(k[0])) {
                if (st[0].equals(k[1])) {
                    label = st[1];
                }
            }
            TextView t = e.getValue();
            if (PhoneSetup26.done(a, k[0], k[1])) {
                t.setText("✓  " + label);
                t.setTextColor(GREEN);
            } else if (running && e.getKey().equals(s.step)) {
                t.setText("●  " + label + (s.pct >= 0 && !s.step.endsWith(":fetch") ? " (" + s.pct + "%)" : "")
                        + (s.text.isEmpty() || s.step.endsWith(":fetch") ? "" : "\n    " + s.text));
                t.setTextColor(Color.WHITE);
            } else {
                t.setText("○  " + label);
                t.setTextColor(GRAY);
            }
        }

        errorButtons.setVisibility(mine() && !running && s.error != null && s.record != null ? View.VISIBLE : View.GONE);
        String note = Helper.memoryNote;
        memory.setText(note == null ? "" : note);
        memory.setVisibility(running && note != null ? View.VISIBLE : View.GONE);
        if (mine() && s.error != null) {
            error.setText(s.error);
            error.setTextColor(RED);
        } else if (mine() && s.finished) {
            error.setText("Set up. Choose iOS 26 or watchOS on the home screen and start it.");
            error.setTextColor(GREEN);
        } else if (!running && error.getCurrentTextColor() != RED) {
            error.setText("");
        }
        boolean started = PhoneSetup26.work(a).isDirectory() && !(i26done || wdone) || PhoneSetup26.work(a).isDirectory()
                && (PhoneSetup26.wants(a, PhoneSetup26.IOS26) && !i26done || PhoneSetup26.wants(a, PhoneSetup26.WATCH) && !wdone);
        go.setText(!pass ? "Check the GPU" : running ? (s.paused ? "Resume" : "Pause") : started ? "Resume install" : "Install");
        // a failed check has its own Check again; a pass from another build of the app (an update) needs this button
        go.setVisibility(!pass && gpuResult != null && gpuResult.ran && !gpuResult.pass ? View.GONE
                : running && !s.paused && !SetupService.pausable(s.step) ? View.GONE : View.VISIBLE);
        go.setEnabled(!GpuCheck.running);
        cancel.setVisibility(pass && (running || started) ? View.VISIBLE : View.GONE);
    }

    private void row(String member, long d, long size, String st0, String err) {
        TextView[] r = fileRows.get(member);
        ProgressBar b = fileBars.get(member);
        if (r == null) {
            line(files);
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(a);
            name.setTextSize(15);
            name.setTextColor(Color.WHITE);
            name.setText(NAMES.getOrDefault(member, member.substring(member.lastIndexOf('/') + 1)));
            TextView st = new TextView(a);
            st.setTextSize(13);
            st.setTextColor(GRAY);
            head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(st, new LinearLayout.LayoutParams(-2, -2));
            files.addView(head);
            b = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            b.setMax(1000);
            files.addView(b, new LinearLayout.LayoutParams(-1, 12));
            r = new TextView[] {name, st};
            fileRows.put(member, r);
            fileBars.put(member, b);
        }
        long t = Math.max(1, size);
        String state;
        int color = GRAY;
        switch (st0) {
            case "ok":
                state = "✓ " + gb(size) + " checked";
                color = GREEN;
                break;
            case "verifying":
                state = "Checking…";
                color = Color.WHITE;
                break;
            case "failed":
                state = "✕ " + (err == null ? "failed" : err);
                color = RED;
                break;
            case "fetching":
                state = gb(d) + " of " + gb(size);
                color = Color.WHITE;
                break;
            default:
                state = d > 0 ? gb(d) + " of " + gb(size) : gb(size);
                break;
        }
        r[1].setText(state);
        r[1].setTextColor(color);
        b.setProgress((int) (Math.min(d, t) * 1000 / t));
    }
}
