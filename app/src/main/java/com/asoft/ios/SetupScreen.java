package com.asoft.ios;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Set up's "Install on this phone from Apple": a card per step (the download with a row per file,
 * then the helper VM's steps), the total with speed, time left and connections, Pause / Cancel and
 * Wi-Fi only, in the home screen's style (cards, grouped rows). Reads SetupService.state twice a second.
 */
final class SetupScreen {
    private static final int CARD = 0xff1c1c1e, LINE = 0xff38383a, GRAY = 0xff9a9a9a, DIM = 0xff777777,
            BLUE = 0xff0a84ff, RED = 0xffff453a, GREEN = 0xff30d158, ORANGE = 0xffff9f0a;

    /** What a member is, for people. */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("087-86698-044.dmg", "iOS itself");
        NAMES.put("087-86593-047.dmg", "System libraries (SystemOS)");
        NAMES.put("087-86715-047.dmg", "Built-in apps (AppOS)");
        NAMES.put("BuildManifest.plist", "Build manifest");
        NAMES.put("Firmware/all_flash/DeviceTree.d22ap.im4p", "iPhone X device tree");
    }

    /** iOS 16's steps and what the screen calls them (SetupRecord names the failed one with them too). */
    static final String[][] STEPS = {
            {"ipsw", "Download from Apple"},
            {"payload", "Kernel with the iPhone's device tree"},
            {"disk", "Build the iOS disk"},
            {"pack", "Pack the disk"},
            {"install", "Set it up in the app"},
            {"firststart", "First start of iOS (once, about 40 min)"},
    };

    private final Activity a;
    private final LinearLayout root;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView total, details, error, free, memory;
    /** Share log and Report on GitHub under a failed step's red line (its record: SetupService.state.record). */
    private View errorButtons;
    private ProgressBar totalBar;
    private LinearLayout files;
    private final Map<String, TextView[]> fileRows = new LinkedHashMap<>();
    private final Map<String, ProgressBar> fileBars = new LinkedHashMap<>();
    private final Map<String, TextView> stepRows = new LinkedHashMap<>();
    /** The members asoft-setup.json lists (their bytes in the zip), shown before the download reads the IPSW. */
    private final Map<String, Long> planned = new LinkedHashMap<>();
    private long plannedTotal;
    private Button startPause, cancel;

    SetupScreen(Activity a) {
        this.a = a;
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        build();
        tick();
    }

    /** Its views, for the setup screen to place (again, after it rebuilds its list). */
    View view() {
        if (root.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) root.getParent()).removeView(root);
        }
        return root;
    }

    private LinearLayout card(int top) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(48, 36, 48, 36);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(36);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        root.addView(c, lp);
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

    private ProgressBar bar(LinearLayout to, int height) {
        ProgressBar b = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        b.setMax(1000);
        to.addView(b, new LinearLayout.LayoutParams(-1, height));
        return b;
    }

    private void build() {
        TextView title = text(root, "Install on this phone from Apple", 18, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 8, 0, 8);
        text(root, "iOS 16.7.16 for the iPhone X: about 5.8 GB from Apple's own servers, then the phone builds the iOS disk "
                + "itself, as the PC installer does, and starts iOS once so that your first start is quick. About 1.5 hours "
                + "on a Galaxy S25 Ultra. Keep the phone on its charger; you can use it meanwhile.", 15, GRAY)
                .setPadding(0, 0, 0, 16);
        free = text(root, "", 13, DIM);

        LinearLayout dl = card(24);
        total = text(dl, "", 16, Color.WHITE);
        totalBar = bar(dl, 24);
        details = text(dl, "", 13, GRAY);
        details.setPadding(0, 12, 0, 0);
        files = new LinearLayout(a);
        files.setOrientation(LinearLayout.VERTICAL);
        dl.addView(files);
        try {
            org.json.JSONArray m = PhoneSetup.manifest(a).getJSONObject("os").getJSONObject("ios16")
                    .getJSONObject("sources").getJSONObject("ipsw").getJSONArray("members");
            for (int i = 0; i < m.length(); i++) {
                org.json.JSONObject e = m.getJSONObject(i);
                long zip = e.optLong("zipBytes", e.optLong("bytes"));
                planned.put(e.getString("name"), zip);
                plannedTotal += zip;
            }
        } catch (java.io.IOException | org.json.JSONException ignored) {
            // the list shows once the download has read the IPSW
        }

        LinearLayout steps = card(24);
        for (String[] s : STEPS) {
            if (steps.getChildCount() > 0) {
                line(steps);
            }
            stepRows.put(s[0], text(steps, "○  " + s[1], 15, GRAY));
        }

        error = text(root, "", 15, RED);
        error.setPadding(8, 24, 8, 0);
        errorButtons = logButtons(a, root, () -> SetupService.state.record);
        memory = text(root, "", 13, GRAY);
        memory.setPadding(8, 16, 8, 0);

        LinearLayout opts = card(24);
        Switch wifi = new Switch(a);
        wifi.setText("Download on Wi-Fi only");
        wifi.setTextColor(Color.WHITE);
        wifi.setTextSize(15);
        // iOS's colours: green when on (the dark theme's own switch hardly shows)
        int[][] states = {{android.R.attr.state_checked}, {}};
        wifi.setThumbTintList(new android.content.res.ColorStateList(states, new int[] {Color.WHITE, 0xffd0d0d0}));
        wifi.setTrackTintList(new android.content.res.ColorStateList(states, new int[] {GREEN, 0xff48484a}));
        wifi.setChecked(SetupService.wifiOnly(a));
        wifi.setOnCheckedChangeListener((v, on) -> SetupService.setWifiOnly(a, on));
        opts.addView(wifi);

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, 24, 0, 0);
        startPause = new Button(a);
        startPause.setAllCaps(false);
        startPause.setTextSize(17);
        startPause.setOnClickListener(v -> {
            SetupService.State s = SetupService.state;
            if (s.running && !s.paused) {
                SetupService.pause(a);
            } else if (Guest.running()) {
                s.record = null;
                s.error = "Close iOS first (side bar > Saves > Save and close): the setup needs its memory.";
            } else {
                SetupService.start(a);
            }
        });
        cancel = new Button(a);
        cancel.setAllCaps(false);
        cancel.setText("Cancel");
        cancel.setTextColor(RED);
        cancel.setOnClickListener(v -> new android.app.AlertDialog.Builder(a)
                .setTitle("Cancel the install?")
                .setMessage("What was downloaded and built so far is deleted.")
                .setPositiveButton("Cancel install", (d, w) -> SetupService.cancel(a))
                .setNegativeButton("Keep going", null)
                .show());
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(0, -2, 1);
        buttons.addView(startPause, wide);
        buttons.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
        root.addView(buttons);
    }

    /**
     * Share log and Report on GitHub for the record @id gives (a failed step's), added to @to; hidden until the
     * screen shows it. Setup26Screen's error line has them too.
     */
    static View logButtons(Activity a, LinearLayout to, java.util.function.Supplier<String> id) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 8, 0, 0);
        Button share = new Button(a), report = new Button(a);
        share.setText("Share log");
        report.setText("Report on GitHub");
        for (Button b : new Button[] {share, report}) {
            b.setAllCaps(false);
            row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        }
        share.setOnClickListener(v -> {
            CrashReport.Record r = CrashReport.find(a, id.get());
            if (r != null) {
                CrashReport.share(a, r);
            }
        });
        report.setOnClickListener(v -> {
            CrashReport.Record r = CrashReport.find(a, id.get());
            if (r != null) {
                CrashReport.openIssue(a, r);
            }
        });
        row.setVisibility(View.GONE);
        to.addView(row);
        return row;
    }

    private static String gb(long b) {
        return b >= 1e9 ? String.format(Locale.ROOT, "%.2f GB", b / 1e9)
                : b >= 1e6 ? String.format(Locale.ROOT, "%.0f MB", b / 1e6)
                : String.format(Locale.ROOT, "%.0f KB", b / 1e3);
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
        SetupService.State s = SetupService.state;
        long freeBytes = a.getFilesDir().getUsableSpace();
        long need = PhoneSetup.needBytes(a);
        free.setText(String.format(Locale.ROOT, "%s free on this phone; the install needs about %s more at its height and leaves about 6.5 GB.",
                gb(freeBytes), gb(need)));
        free.setTextColor(freeBytes < need ? ORANGE : DIM);

        boolean mine = !"a26".equals(s.target);
        // (a26's own error line has its buttons: Setup26Screen)
        errorButtons.setVisibility(mine && s.error != null && s.record != null && !s.running ? View.VISIBLE : View.GONE);
        String note = Helper.memoryNote;
        memory.setText(note == null ? "" : note);
        memory.setVisibility(mine && s.running && note != null ? View.VISIBLE : View.GONE);
        if (s.running && "a26".equals(s.target)) {
            // the iOS 26 / watchOS install runs (Setup26Screen shows it); one install at a time
            startPause.setText("iOS 26 / watchOS are being installed");
            startPause.setEnabled(false);
            cancel.setVisibility(View.GONE);
            return;
        }
        List<IpswFetch.Item> items = s.items;
        long got = 0, size = 0;
        for (IpswFetch.Item it : items) {
            got += it.done.get();
            size += it.total;
            row(it.member, it.done.get(), it.total, it.state, it.error);
        }
        boolean downloadedEarlier = PhoneSetup.done(a, "ipsw") || PhoneSetup.done(a, "disk");
        if (items.isEmpty()) {
            for (Map.Entry<String, Long> e : planned.entrySet()) {
                row(e.getKey(), downloadedEarlier ? e.getValue() : 0, e.getValue(), downloadedEarlier ? "ok" : "waiting", null);
            }
        }
        boolean fetching = "ipsw".equals(s.step) && s.running;
        if (size > 0) {
            total.setText(String.format(Locale.ROOT, "%s of %s from Apple", gb(got), gb(size)));
            totalBar.setProgress((int) (got * 1000 / size));
        } else {
            total.setText(downloadedEarlier ? "Downloaded and checked" : plannedTotal > 0 ? gb(plannedTotal) + " from Apple" : "From Apple");
            totalBar.setProgress(downloadedEarlier ? 1000 : 0);
        }
        if (fetching && s.mbps > 0) {
            double left = (size - got) / 1e6 / s.mbps;
            details.setText(String.format(Locale.ROOT, "%.1f MB/s · %s left · %d connections%s", s.mbps, minutes(left), s.connections,
                    s.servers.isEmpty() ? "" : " · " + s.servers));
        } else if (s.waitingForNetwork) {
            details.setText("No internet connection: it goes on by itself when the phone is online again");
        } else if (s.waitingForWifi) {
            details.setText("Waiting for Wi-Fi (Download on Wi-Fi only is on)");
        } else if (s.paused) {
            details.setText("Paused: what came stays, Resume goes on from there");
        } else {
            details.setText(fetching ? "Finding Apple's fastest servers…" : "");
        }

        for (String[] st : STEPS) {
            TextView t = stepRows.get(st[0]);
            // (the download's marker goes when its files are dropped early: a built disk means it was done)
            boolean isDone = PhoneSetup.done(a, st[0]) || ("ipsw".equals(st[0]) && PhoneSetup.done(a, "disk"));
            boolean now = st[0].equals(s.step) && s.running;
            if (isDone) {
                t.setText("✓  " + st[1]);
                t.setTextColor(GREEN);
            } else if (now) {
                t.setText("●  " + st[1] + (s.pct >= 0 && !"ipsw".equals(st[0]) ? " (" + s.pct + "%)" : "")
                        + (s.text.isEmpty() || "ipsw".equals(st[0]) ? "" : "\n    " + s.text));
                t.setTextColor(Color.WHITE);
            } else {
                t.setText("○  " + st[1]);
                t.setTextColor(GRAY);
            }
        }
        boolean all = PhoneSetup.done(a, "firststart");
        error.setText(s.error != null ? s.error : all ? "iOS 16 is set up. Start it from the home screen: it comes up in about 10 s." : "");
        error.setTextColor(s.error == null ? GREEN : RED);
        boolean started = PhoneSetup.work(a).isDirectory();
        startPause.setText(s.running ? (s.paused ? "Resume" : "Pause") : all ? "iOS 16 is set up" : started ? "Resume install" : "Install iOS 16");
        startPause.setEnabled(!all);
        // only the download pauses (a helper VM step or iOS's first start would have to start over)
        startPause.setVisibility(s.running && !s.paused && !"ipsw".equals(s.step) ? View.GONE : View.VISIBLE);
        cancel.setVisibility(!all && (s.running || started) ? View.VISIBLE : View.GONE);
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
            name.setText(NAMES.getOrDefault(member, member));
            TextView st = new TextView(a);
            st.setTextSize(13);
            st.setTextColor(GRAY);
            head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(st, new LinearLayout.LayoutParams(-2, -2));
            files.addView(head);
            b = bar(files, 12);
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
