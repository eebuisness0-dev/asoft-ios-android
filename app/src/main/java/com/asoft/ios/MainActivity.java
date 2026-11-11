package com.asoft.ios;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The home screen: the devices on this phone. Each system (iOS 26, iOS 16, watchOS) is its device, drawn with
 * what its screen last showed (DeviceView), its state in a line, and the one thing to do next as its button;
 * what is rarely needed (a boot without the save, its screen and memory, erasing or removing it) is behind its "more"
 * corner. Under the devices the few settings of the app itself, and the engine test for comparing phones.
 */
public class MainActivity extends Activity {
    private static final int BG = Color.BLACK, LINE = 0xff26262a, TEXT = 0xfff5f5f7, DIM = 0xff8e8e93, FAINT = 0xff636366,
            LINK = 0xff6cb2ff, RED = 0xffff5a50, GREEN = 0xff32d74b, ORANGE = 0xffff9f0a;

    /** One system's panel. */
    private final class Panel {
        final int os;
        LinearLayout root;
        DeviceView device;
        View dot;
        TextView status, detail, primary, secondary, back, menu;
        /** The picture on the device, and the file's time it was read at. */
        Bitmap shot;
        long shotTime;

        Panel(int os) {
            this.os = os;
        }
    }

    private LinearLayout box;
    private final Panel p26 = new Panel(Guest.IOS26), p16 = new Panel(Guest.IOS16), pWatch = new Panel(Guest.WATCH);
    private TextView powerWarn, freeRamValue, wadbValue, updateValue, footer, engineLog;
    private LinearLayout updateBar, advanced;
    private TextView updateText, updateNote, updateGet, updateDismiss;
    /**
     * The clock check again every 10 s while this screen shows: at onResume the phone has just
     * lifted its CPU cap for the app's launch (A71: 2.21 GHz then, 1.56 a few seconds later).
     */
    private final Runnable powerRecheck = new Runnable() {
        @Override
        public void run() {
            showPowerWarning();
            powerWarn.postDelayed(this, 10_000);
        }
    };
    /** Power saving switched while this screen shows: the warning follows it. */
    private final android.content.BroadcastReceiver powerChanged = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            showPowerWarning();
        }
    };
    private final Runnable engineRefresh = new Runnable() {
        @Override
        public void run() {
            File f = new File(getExternalFilesDir(null), EngineService.LOG);
            try {
                if (f.exists() && engineLog.getVisibility() == View.VISIBLE && advanced.getVisibility() == View.VISIBLE) {
                    String[] lines = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n");
                    int from = Math.max(0, lines.length - 30);
                    engineLog.setText(String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length)));
                }
            } catch (IOException e) {
                engineLog.setText(e.toString());
            }
            engineLog.postDelayed(this, 1000);
        }
    };
    /**
     * While this screen shows: the panels follow what runs (a start, a turn off, a setup), and the settings rows
     * the pairing (WadbPairService). Only a change draws the panels again: their saves come from qemu-img.
     */
    private String shown = "";
    private final Runnable follow = new Runnable() {
        @Override
        public void run() {
            String now = Guest.running() + " " + Guest.state + " " + Guest.os(MainActivity.this) + " " + Guest.saving() + " "
                    + SetupService.busy() + " " + Guest.startError;
            if (!now.equals(shown)) {
                shown = now;
                refresh();
            } else {
                showSettings();
            }
            footer.postDelayed(this, 1500);
        }
    };

    // -- the pieces the screen is built from

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** The surface of a panel or a group: a little lighter at its top, a hairline around it. */
    private GradientDrawable surface(float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xff19191c, 0xff111113});
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(Math.max(1, dp(0.75f)), LINE);
        return g;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(LinearLayout to, String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        to.addView(t);
        return t;
    }

    private static LinearLayout.LayoutParams top(LinearLayout.LayoutParams lp, int px) {
        lp.topMargin = px;
        return lp;
    }

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** A panel's one main action: a light capsule, or (@outline) only its edge, for what is not there yet. */
    private TextView capsule(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setTypeface(MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(38));
        t.setMinWidth(dp(92));
        t.setPadding(dp(20), dp(8), dp(20), dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private void fill(TextView capsule, boolean outline) {
        GradientDrawable g = round(outline ? Color.TRANSPARENT : TEXT, 19);
        if (outline) {
            g.setStroke(dp(1.2f), 0xff6e6e73);
        }
        capsule.setTextColor(outline ? TEXT : 0xff0b0b0d);
        capsule.setBackground(new RippleDrawable(ColorStateList.valueOf(outline ? 0x30ffffff : 0x30000000), g, round(Color.WHITE, 19)));
    }

    private TextView link(String s, int color, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14.5f);
        t.setTextColor(color);
        t.setPadding(dp(14), dp(9), dp(8), dp(9));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff), null, round(Color.WHITE, 14)));
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout buildPanel(Panel c, String name, int kind, int[] tint) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(18), dp(18), dp(12), dp(18));
        root.setBackground(surface(26));
        c.root = root;

        c.device = new DeviceView(this, kind, tint);
        c.device.setOnClickListener(v -> primaryAction(c.os));
        root.addView(c.device, new LinearLayout.LayoutParams(dp(80), dp(kind == DeviceView.WATCH ? 132 : 164)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        clp.leftMargin = dp(18);
        root.addView(col, clp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(head, name, 21, TEXT);
        title.setTypeface(MEDIUM);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        c.menu = new TextView(this);
        c.menu.setText("⋯");        // the "more" corner
        c.menu.setTextSize(22);
        c.menu.setTextColor(DIM);
        c.menu.setGravity(Gravity.CENTER);
        c.menu.setContentDescription("More for " + name);
        c.menu.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), null, round(Color.WHITE, 20)));
        c.menu.setOnClickListener(v -> showMenu(c));
        head.addView(c.menu, new LinearLayout.LayoutParams(dp(40), dp(40)));
        col.addView(head);

        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        c.dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.rightMargin = dp(8);
        st.addView(c.dot, dlp);
        c.status = text(st, "", 14.5f, DIM);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-1, -2);
        stlp.rightMargin = dp(8);
        col.addView(st, stlp);

        c.detail = text(col, "", 12.5f, DIM);
        c.detail.setLineSpacing(dp(1.5f), 1f);
        LinearLayout.LayoutParams dtl = top(new LinearLayout.LayoutParams(-1, -2), dp(8));
        dtl.rightMargin = dp(8);
        c.detail.setLayoutParams(dtl);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        c.primary = capsule("", v -> primaryAction(c.os));
        actions.addView(c.primary, new LinearLayout.LayoutParams(-2, -2));
        c.secondary = link("Turn off", RED, v -> askTurnOff());
        c.secondary.setVisibility(View.GONE);
        actions.addView(c.secondary, new LinearLayout.LayoutParams(-2, -2));
        col.addView(actions, top(new LinearLayout.LayoutParams(-1, -2), dp(14)));

        c.back = link("", LINK, v -> goBack(c.os, (Guest.Snap) v.getTag()));
        c.back.setTextSize(13.5f);
        c.back.setPadding(0, dp(10), dp(8), dp(2));
        c.back.setVisibility(View.GONE);
        col.addView(c.back, new LinearLayout.LayoutParams(-2, -2));
        return root;
    }

    /** A rounded group of rows. */
    private LinearLayout group(LinearLayout to, int topPx) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(surface(22));
        g.setClipToOutline(true);
        to.addView(g, top(new LinearLayout.LayoutParams(-1, -2), topPx));
        return g;
    }

    /** A row in a group: its name on the left, its value (the returned view) on the right; a thin line above all but the first. */
    private TextView row(LinearLayout group, String label, View.OnClickListener l) {
        if (group.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(LINE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.75f)));
            lp.leftMargin = dp(18);
            group.addView(line, lp);
        }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), dp(14), dp(16), dp(14));
        r.setMinimumHeight(dp(54));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        r.setBackgroundResource(tv.resourceId);
        r.setOnClickListener(l);
        TextView name = text(r, label, 16, TEXT);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView value = text(r, "", 15, DIM);
        value.setGravity(Gravity.END);
        value.setMaxWidth(dp(170));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
        vlp.leftMargin = dp(12);
        value.setLayoutParams(vlp);
        TextView chevron = text(r, "›", 19, FAINT);
        chevron.setPadding(dp(8), 0, 0, dp(2));
        group.addView(r);
        return value;
    }

    private void heading(LinearLayout to, String s) {
        TextView t = text(to, s, 13, DIM);
        t.setTypeface(MEDIUM);
        t.setPadding(dp(18), dp(30), dp(18), dp(10));
    }

    private TextView banner(int bg, int color) {
        TextView t = new TextView(this);
        t.setTextSize(14);
        t.setTextColor(color);
        t.setLineSpacing(dp(1.5f), 1f);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setBackground(round(bg, 18));
        return t;
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        DevNight.apply(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        scroll.setVerticalScrollBarEnabled(false);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(20), dp(16), dp(28));
        scroll.addView(box);
        setContentView(scroll);

        // the logo's iPhone and the name
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(names, "ASoftware", 25, TEXT);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLetterSpacing(-0.01f);
        text(names, "iPhone and Apple Watch software, on this phone", 13, DIM);
        brand.addView(names);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.leftMargin = -dp(8);
        blp.bottomMargin = dp(18);
        box.addView(brand, blp);

        addUpdateBar();
        // power saving slows iOS several times over: say so above everything else
        powerWarn = banner(0xff241b0b, ORANGE);
        powerWarn.setOnClickListener(v -> PowerCheck.openSettings(this));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.bottomMargin = dp(12);
        box.addView(powerWarn, wlp);
        powerWarn.setVisibility(View.GONE);

        box.addView(buildPanel(p26, "iOS 26", DeviceView.ISLAND, new int[] {0xff5a9bff, 0xff4a5df0, 0xff5b36cf}),
                new LinearLayout.LayoutParams(-1, -2));
        box.addView(buildPanel(p16, "iOS 16", DeviceView.HOME, new int[] {0xff5ad1c8, 0xff2f8fe0, 0xff3a56c9}),
                top(new LinearLayout.LayoutParams(-1, -2), dp(12)));
        box.addView(buildPanel(pWatch, "watchOS", DeviceView.WATCH, null), top(new LinearLayout.LayoutParams(-1, -2), dp(12)));

        heading(box, "Settings");
        LinearLayout settings = group(box, 0);
        freeRamValue = row(settings, "Free memory for iOS", v -> toggleFreeRam());
        wadbValue = row(settings, "Wireless debugging", v -> wirelessDebugging());
        ((View) wadbValue.getParent()).setVisibility(WirelessAdb.supported() ? View.VISIBLE : View.GONE);
        updateValue = row(settings, "Check for updates", v -> {
            UpdateCheck.setEnabled(this, !UpdateCheck.enabled(this));
            showUpdate();
            UpdateCheck.check(this, this::showUpdate);
        });
        row(settings, "Set up or add a system", v -> startActivity(new Intent(this, SetupActivity.class)));
        row(settings, "Engine test", v -> advanced.setVisibility(advanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        advanced.setPadding(dp(4), dp(12), dp(4), 0);
        box.addView(advanced);
        text(advanced, "Runs iOS-style code on this phone's CPU the way iOS 16 does here, and reports its speed: "
                + "useful when trying another phone.", 13, DIM).setPadding(dp(14), 0, dp(14), dp(12));
        TextView run = capsule("Run the engine test", v -> {
            EngineService.start(this, 2, 2, 120);
            engineLog.setVisibility(View.VISIBLE);
        });
        fill(run, true);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.leftMargin = dp(14);
        advanced.addView(run, rlp);
        engineLog = text(advanced, "", 11, 0xffcfcfcf);
        engineLog.setTypeface(Typeface.MONOSPACE);
        engineLog.setTextIsSelectable(true);
        engineLog.setPadding(dp(12), dp(10), dp(12), dp(10));
        engineLog.setBackground(round(0xff111113, 12));
        engineLog.setLayoutParams(top(new LinearLayout.LayoutParams(-1, -2), dp(12)));
        engineLog.setVisibility(new File(getExternalFilesDir(null), EngineService.LOG).exists() ? View.VISIBLE : View.GONE);
        engineLog.post(engineRefresh);

        footer = text(box, "", 12.5f, FAINT);
        footer.setGravity(Gravity.CENTER);
        footer.setLayoutParams(top(new LinearLayout.LayoutParams(-1, -2), dp(26)));

        if (saved == null && !Guest.available(this) && !Guest.available26(this)) {
            startActivity(new Intent(this, SetupActivity.class));   // first start: set up first
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.content.IntentFilter f = new android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerChanged, f, RECEIVER_NOT_EXPORTED);   // a system broadcast still comes
        } else {
            registerReceiver(powerChanged, f);
        }
        shown = "";
        follow.run();
        showUpdate();
        UpdateCheck.check(this, this::showUpdate);
        powerWarn.postDelayed(powerRecheck, 3_000);
    }

    @Override
    protected void onPause() {
        unregisterReceiver(powerChanged);
        powerWarn.removeCallbacks(powerRecheck);
        footer.removeCallbacks(follow);
        super.onPause();
    }

    /**
     * The notice that a newer release is out, hidden until the check says so: the version line with
     * its two links, and under it the text the release notes may carry for the app.
     */
    private void addUpdateBar() {
        updateBar = new LinearLayout(this);
        updateBar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(16), dp(6), dp(6), dp(6));
        updateText = text(line, "", 14, 0xff8cc4ff);
        updateText.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        updateGet = link("Get it", 0xff8cc4ff, v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(UpdateCheck.RELEASE_PAGE)));
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, UpdateCheck.RELEASE_PAGE, Toast.LENGTH_LONG).show();
            }
        });
        line.addView(updateGet);
        updateDismiss = link("Dismiss", 0xff5f84ad, v -> {
            UpdateCheck.Notice n = UpdateCheck.toShow(this);
            if (n != null) {
                UpdateCheck.dismiss(this, n.tag);
            }
            showUpdate();
        });
        line.addView(updateDismiss);
        updateBar.addView(line);
        // full width under the line, not squeezed beside the links: it can run to a few lines
        updateNote = text(updateBar, "", 13.5f, 0xffd1d1d6);
        updateNote.setPadding(dp(16), 0, dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        box.addView(updateBar, lp);
        updateBar.setVisibility(View.GONE);
    }

    private void showUpdate() {
        UpdateCheck.Notice n = UpdateCheck.toShow(this);
        updateBar.setVisibility(n == null ? View.GONE : View.VISIBLE);
        if (n != null) {
            // background, accent, the dimmer Dismiss: the blue it always had, the power warning's
            // amber, or red for a release that fixes something that hurts
            int bg = 0xff0f2136, accent = 0xff8cc4ff, dim = 0xff5f84ad;
            if (n.level == UpdateCheck.CRITICAL) {
                bg = 0xff2a0f0e;
                accent = RED;
                dim = 0xffb0524c;
            } else if (n.level == UpdateCheck.IMPORTANT) {
                bg = 0xff241b0b;
                accent = ORANGE;
                dim = 0xffa87a32;
            }
            updateBar.setBackground(round(bg, 18));
            updateText.setTextColor(accent);
            updateGet.setTextColor(accent);
            updateDismiss.setTextColor(dim);
            updateText.setText(n.tag + " is out (this is " + UpdateCheck.version(this) + ")");
            updateNote.setText(n.text == null ? "" : n.text);
            updateNote.setVisibility(n.text == null ? View.GONE : View.VISIBLE);
        }
        updateValue.setText(UpdateCheck.enabled(this) ? "On" : "Off");
    }

    private void showPowerWarning() {
        String w = PowerCheck.warning(this);
        powerWarn.setText(w == null ? "" : w);
        powerWarn.setVisibility(w == null ? View.GONE : View.VISIBLE);
    }

    /**
     * Runs @go, first asking when power saving is on and iOS is not running yet (a boot or a
     * load then takes several times longer).
     */
    private void whenPowerOk(Runnable go) {
        if (Guest.running() || !PowerCheck.saverOn(this)) {
            go.run();
            return;
        }
        String cap = PowerCheck.capped();
        new AlertDialog.Builder(this)
                .setTitle("Power saving is on")
                .setMessage("It slows the CPU" + (cap != null ? " (now " + cap + ")" : "") + ", and iOS runs on "
                        + "the CPU: a boot can take several times longer and apps open slowly. Turn power saving "
                        + "off while you use iOS.")
                .setPositiveButton("Open the setting", (d, w) -> PowerCheck.openSettings(this))
                .setNeutralButton("Start anyway", (d, w) -> go.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -- a panel's button and its "more" corner

    private static String systemName(int os) {
        return os == Guest.WATCH ? "watchOS" : os == Guest.IOS26 ? "iOS 26" : "iOS 16";
    }

    private boolean installed(int os) {
        return os == Guest.WATCH ? WatchProfile.available(this) : os == Guest.IOS26 ? Guest.available26(this) : Guest.available(this);
    }

    private void primaryAction(int os) {
        if (os == Guest.IOS16 && (!Guest.available(this) || SetupService.busy())) {
            startActivity(new Intent(this, SetupActivity.class));
        } else if (os == Guest.IOS26 && !Guest.available26(this)) {
            startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_IOS26, true));  // Setup26Screen
        } else if (installed(os)) {
            open(os);
        }
    }

    /** What is rarely needed for one system: a boot without its save, its screen and memory, erasing or removing it. */
    private void showMenu(Panel c) {
        int os = c.os;
        if (!installed(os)) {
            return;
        }
        PopupMenu m = new PopupMenu(this, c.menu, Gravity.END);
        boolean up = Guest.running() && Guest.os(this) == os;
        if (!up) {
            m.getMenu().add(0, 1, 0, "Boot, not from the save");
        }
        if (os == Guest.IOS26) {
            m.getMenu().add(0, 2, 0, "Screen: " + (Guest.scale26(this) == 2 ? "2x (faster)" : "3x (sharp)"));
        }
        if (os != Guest.IOS16) {
            m.getMenu().add(0, 3, 0, "Memory: " + Guest.ramMiB(this, os) / 1024 + " GB");
        }
        m.getMenu().add(0, 4, 0, os == Guest.WATCH ? "Erase the watch…" : "Erase the iPhone…");
        m.getMenu().add(0, 5, 0, "Remove from this phone…");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: bootCold(os); break;
                case 2: chooseScale26(); break;
                case 3: chooseRam(os); break;
                case 4: askErase(os); break;
                default: askRemove(os);
            }
            return true;
        });
        m.show();
    }

    private void bootCold(int os) {
        if (Guest.running() && Guest.os(this) != os) {
            open(os);       // says to close the other one first
            return;
        }
        whenPowerOk(() -> {
            if (!Guest.running()) {
                Guest.select(this, os);
                GuestService.startCold(this);
            }
            startActivity(new Intent(this, ScreenActivity.class));
        });
    }

    private void askErase(int os) {
        boolean watch = os == Guest.WATCH;
        String what = watch ? "watchOS's watch" : os == Guest.IOS26 ? "iOS 26's iPhone" : "iOS 16's iPhone";
        new AlertDialog.Builder(this)
                .setTitle("Erase " + what + "?")
                .setMessage(watch ? "The watch's settings, faces and saves go; it starts as it came. watchOS itself stays."
                        : "Its settings, installed apps and saves go; iOS starts as new. " + systemName(os) + " itself stays.")
                .setPositiveButton("Erase", (d, w) -> {
                    if (Guest.running()) {
                        Toast.makeText(this, "Close " + Guest.osName(Guest.os(this)) + " first (side bar > Saves > Save and close)",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Guest.select(this, os);
                        Guest.erase(this, os);
                        shown = "";
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "12.3 GB", "450 MB": what a removal gives back. */
    private static String size(long bytes) {
        return bytes >= 1e9 ? String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
                : String.format(java.util.Locale.ROOT, "%.0f MB", Math.max(1, bytes / 1e6));
    }

    /**
     * Remove from this phone: unlike Erase, @os itself goes, with everything of it (Installer.removal), and its space
     * comes back. The size is measured first (a walk of its files, off the UI thread), then asked.
     */
    private void askRemove(int os) {
        String busy = Installer.cannotRemove(this);
        if (busy != null) {
            Toast.makeText(this, busy, Toast.LENGTH_LONG).show();
            return;
        }
        String name = systemName(os);
        new Thread(() -> {
            Installer.Removal r = Installer.removal(this, os);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                String what = os == Guest.WATCH ? "watchOS itself goes from this phone, with the watch's settings, faces and saves"
                        : name + " itself goes from this phone, with its settings, installed apps and saves";
                new AlertDialog.Builder(this)
                        .setTitle("Remove " + name + " from this phone?")
                        .setMessage(what + (r.shared ? ", and the files it shared with " + (os == Guest.WATCH ? "iOS 26" : "watchOS")
                                + ", which nothing else uses now" : "") + ". That frees about " + size(r.bytes) + ".\n\n"
                                + "To have it again, Set up downloads it from Apple again" + (os == Guest.IOS16 ? " (or your PC sends it)." : "."))
                        .setPositiveButton("Remove", (d, w) -> removeNow(os))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }, "remove-size").start();
    }

    private void removeNow(int os) {
        String busy = Installer.cannotRemove(this);
        if (busy != null) {
            Toast.makeText(this, busy, Toast.LENGTH_LONG).show();
            return;
        }
        String name = systemName(os);
        // in front until the files are gone: nothing starts on them meanwhile
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("Removing " + name + "…").setCancelable(false).show();
        new Thread(() -> {
            String said;
            boolean done = false;
            try {
                said = name + " is removed: " + size(Installer.remove(this, os)) + " free again";
                done = true;
            } catch (IOException e) {
                said = e.getMessage();
            }
            String text = said;
            boolean removed = done;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                wait.dismiss();
                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                shown = "";
                refresh();
                if (removed && !Guest.available(this) && !Guest.available26(this) && !WatchProfile.available(this)) {
                    startActivity(new Intent(this, SetupActivity.class));   // nothing left: set up first, as on a fresh app
                }
            });
        }, "remove").start();
    }

    private void askTurnOff() {
        new Thread(() -> {
            // the quick save's time for the text (from QEMU: off the UI thread)
            Guest.Snap quick = Guest.newestQuick(Guest.snapshots(this));
            runOnUiThread(() -> {
                if (isFinishing() || !Guest.running()) {
                    return;
                }
                int os = Guest.os(this);
                new AlertDialog.Builder(this)
                        .setTitle("Turn off " + (os == Guest.WATCH ? "watchOS" : "iOS") + "?")
                        .setMessage(ScreenActivity.turnOffText(os, quick))
                        .setPositiveButton("Turn off", (d, w) -> {
                            if (Guest.running()) {
                                GuestService.powerOff(this);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }, "turn-off-ask").start();
    }

    /** "today 23:00", "yesterday 23:00" or "9 Nov, 23:00" of a save's "2026-11-09 23:00:51". */
    static String nice(String date) {
        try {
            java.time.LocalDateTime t = java.time.LocalDateTime.parse(date.substring(0, 16).replace(' ', 'T'));
            java.time.LocalDate today = java.time.LocalDate.now();
            String time = String.format(java.util.Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());
            if (t.toLocalDate().equals(today)) {
                return "today " + time;
            }
            if (t.toLocalDate().equals(today.minusDays(1))) {
                return "yesterday " + time;
            }
            return t.getDayOfMonth() + " " + t.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + ", " + time;
        } catch (RuntimeException e) {
            return date.length() >= 16 ? date.substring(5, 16) : date;
        }
    }

    /** Shows @c's way back @snap (null: hides it). */
    private static void showBack(Panel c, Guest.Snap snap) {
        c.back.setTag(snap);
        c.back.setVisibility(snap == null ? View.GONE : View.VISIBLE);
        if (snap != null) {
            c.back.setText("Go back to the save of " + nice(snap.date));
        }
    }

    /** The way back after a Turn off: the quick save it kept loads, into the running @os or as its start. */
    private void goBack(int os, Guest.Snap snap) {
        if (snap == null) {
            return;
        }
        if (Guest.running() && Guest.os(this) != os) {
            open(os);       // says to close the other one first
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Go back to the save of " + ScreenActivity.when(snap) + "?")
                .setMessage(ScreenActivity.goBackText(this, os, snap))
                .setPositiveButton("Go back", (d, w) -> whenPowerOk(() -> {
                    Guest.select(this, os);
                    Guest.goBack(this, os, snap.tag);
                    if (Guest.running()) {
                        GuestService.load(this, snap.tag);
                    }
                    startActivity(new Intent(this, ScreenActivity.class));
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Opens iOS @os: its screen starts it (quick boot when it was saved). One QEMU at a time. */
    private void open(int os) {
        if (Guest.running() && Guest.os(this) != os) {
            String other = Guest.osName(Guest.os(this));
            new AlertDialog.Builder(this)
                    .setTitle(other + " is running")
                    .setMessage("One at a time: close " + other + " first (side bar > Saves > Save and close, or Turn off).")
                    .setPositiveButton("Open " + other, (d, w) -> startActivity(new Intent(this, ScreenActivity.class)))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        whenPowerOk(() -> {
            Guest.select(this, os);
            startActivity(new Intent(this, ScreenActivity.class));
        });
    }

    /**
     * iOS 26's screen, 3x or 2x (Guest.scale26). 2x: the same layout with 2.25 times fewer pixels, so
     * this phone's CPU, which draws much of iOS, is done sooner after a boot. Each scale is its own
     * iPhone (Guest.diskName), so switching loses nothing.
     */
    private void chooseScale26() {
        if (Guest.running() && Guest.os(this) == Guest.IOS26) {
            Toast.makeText(this, "Close iOS 26 first (side bar > Saves > Save and close)", Toast.LENGTH_SHORT).show();
            return;
        }
        int now = Guest.scale26(this);
        int other = now == 2 ? 3 : 2;
        new AlertDialog.Builder(this)
                .setTitle("iOS 26's screen")
                .setMessage("Now " + (now == 2 ? "2x (faster)" : "3x (sharp, as on an iPhone)") + ".\n\n"
                        + "2x draws 2.25 times fewer pixels: after a boot the home screen and apps come up "
                        + "sooner on this phone; text is a little softer. Its first boot takes longer while "
                        + "iOS makes its caches for 2x.\n\n"
                        + "Each setting is its own iPhone, with its own settings, apps and saves; switching "
                        + "back finds the other one as you left it.")
                .setPositiveButton(other == 2 ? "Use 2x" : "Use 3x", (d, w) -> {
                    Guest.setScale26(this, other);
                    shown = "";
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The guest's RAM (Guest.ramMiB): less leaves Android more, so it closes iOS less often on a phone with
     * less RAM; more keeps more apps alive inside iOS. A save loads with the size it was made with, so a new
     * size takes effect at the next full start.
     */
    private void chooseRam(int os) {
        int[] ch = os == Guest.WATCH ? Guest.RAM_CHOICES_WATCH : Guest.RAM_CHOICES_26;
        int now = Guest.ramMiB(this, os), def = Guest.ramDefault(this, os);
        String[] names = new String[ch.length];
        int pick = 0;
        for (int i = 0; i < ch.length; i++) {
            names[i] = ch[i] / 1024 + " GB" + (ch[i] == def ? " (default for this phone)"
                    : ch[i] > Guest.RAM_TIGHT ? " (Android may close iOS)" : "");
            if (ch[i] == now) {
                pick = i;
            }
        }
        int[] chosen = {pick};
        new AlertDialog.Builder(this)
                // (a message would hide the list: the explanation is in the title and the toast)
                .setTitle(String.format(java.util.Locale.ROOT, "%s's memory (this phone: %.1f GB; less leaves Android more)",
                        os == Guest.WATCH ? "watchOS" : "iOS 26", GpuCheck.ram(this) / 1e9))
                .setSingleChoiceItems(names, pick, (d, w) -> chosen[0] = w)
                .setPositiveButton("Use", (d, w) -> {
                    Runnable use = () -> {
                        Guest.setRamMiB(this, os, ch[chosen[0]]);
                        shown = "";
                        refresh();
                        if (ch[chosen[0]] != now) {
                            noteRamChange(os);
                        }
                    };
                    if (ch[chosen[0]] > Guest.RAM_TIGHT && ch[chosen[0]] != now) {
                        warnTightRam(ch[chosen[0]], use);
                    } else {
                        use.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The guest's RAM is only part of what it costs: QEMU and the GPU's memory come on top. */
    private void warnTightRam(int mib, Runnable use) {
        new AlertDialog.Builder(this)
                .setTitle("Use " + mib / 1024 + " GB?")
                .setMessage(String.format(java.util.Locale.ROOT, "This phone has %.1f GB, and iOS needs more than the %d GB you give it, "
                        + "for its graphics and the emulator itself. That leaves Android very little: it may close iOS while it runs, "
                        + "and whatever was not saved is lost. If that happens, go back to a smaller size.",
                        GpuCheck.ram(this) / 1e9, mib / 1024))
                .setPositiveButton("Use " + mib / 1024 + " GB", (d, w) -> use.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * After the memory of @os changed: it applies from the next start, a boot; a quick save made with the other
     * size stays as a way back (Guest.otherHardware), nothing is deleted. The snapshot list comes from qemu-img or
     * QEMU: off the UI thread.
     */
    private void noteRamChange(int os) {
        new Thread(() -> {
            boolean up = Guest.running() && Guest.os(this) == os;
            Guest.Snap quick = up || Guest.userDisk(this, os).exists() ? Guest.newestQuick(Guest.snapshots(this, os)) : null;
            String name = os == Guest.WATCH ? "watchOS" : "iOS 26";
            boolean other = quick != null && Guest.otherHardware(this, os, quick.tag);
            String text = quick != null && !other && !up
                    ? "Your save of " + ScreenActivity.when(quick) + " has this size: the next start goes on from it ("
                    + Guest.hardware(this, os, quick.tag) + "). Boots use the new size."
                    : "This applies from the next start, a boot" + (up ? " (after this run's Save and close or Turn off)" : "")
                    + "." + (other ? " Your save of " + ScreenActivity.when(quick) + " stays as a way back; it opens with "
                    + Guest.hardware(this, os, quick.tag) + "." : "");
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    new AlertDialog.Builder(this)
                            .setTitle(name + "'s memory: " + Guest.ramMiB(this, os) / 1024 + " GB")
                            .setMessage(text)
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }, "ram-change-note").start();
    }

    // -- the panels' states

    private static final int OFF = 0, READY = 1, STARTING = 2, RUNNING = 3;

    /** The panel's line: @how colours its dot (none for a system that is not there). */
    private void state(Panel c, int how, String s) {
        c.status.setText(s);
        c.status.setTextColor(how == RUNNING ? GREEN : how == STARTING ? ORANGE : DIM);
        c.dot.setVisibility(how == OFF ? View.GONE : View.VISIBLE);
        c.dot.setBackground(round(how == RUNNING ? GREEN : how == STARTING ? ORANGE : 0xff48484a, 4));
    }

    private static void detail(Panel c, String s) {
        c.detail.setText(s);
        c.detail.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The panel's button: @primary, filled for a system that is there; "Turn off" beside it while it is @up. */
    private void buttons(Panel c, String primary, boolean up) {
        boolean there = installed(c.os);
        c.primary.setText(primary);
        fill(c.primary, !there);
        c.secondary.setVisibility(up ? View.VISIBLE : View.GONE);
        c.menu.setVisibility(there ? View.VISIBLE : View.INVISIBLE);
    }

    /** The device's picture: what its screen last showed, read again when the file changed. Not on the UI thread. */
    private Bitmap shot(Panel c) {
        File f = Guest.thumbFile(this, c.os);
        long t = f.lastModified();
        if (t == 0) {
            c.shotTime = 0;
            return null;
        }
        if (t != c.shotTime || c.shot == null) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) {
                c.shot = b;
                c.shotTime = t;
            }
        }
        return c.shot;
    }

    private static Guest.Snap quickOf(List<Guest.Snap> list) {
        Guest.Snap quick = null;
        for (Guest.Snap s : list) {
            if (Guest.isQuick(s.tag)) {
                quick = s;
            }
        }
        return quick;
    }

    private String error(int os, boolean up) {
        String err = Guest.startError;
        return !up && err != null && Guest.selected(this) == os ? "Did not start: " + err + "\n" : "";
    }

    /** @os is on its way up: the step before its QEMU runs (Guest.prepare), or QEMU loads its save. */
    private boolean starting(int os) {
        return "preparing".equals(Guest.state) && Guest.selected(this) == os
                || Guest.running() && Guest.os(this) == os && "resuming".equals(Guest.state);
    }

    /** iOS 26's panel: shown without its files too (its button leads to Set up, which installs it from Apple). */
    private void refresh26() {
        Panel c = p26;
        boolean debug = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String missing = Guest.missing26(this);
        boolean twoX = Guest.scale26(this) == 2;
        boolean up = Guest.running() && Guest.os(this) == Guest.IOS26;
        if (missing != null) {
            boolean busy = SetupService.busy() && "a26".equals(SetupService.state.target);
            state(c, busy ? STARTING : OFF, busy ? "Being set up" : "Not installed");
            detail(c, busy ? SetupService.state.text : "Downloads about 10 GB from Apple and builds it on this phone."
                    + (debug ? " (No " + new File(Guest.dir26(this), missing).getName() + ".)" : ""));
            buttons(c, busy ? "Show" : "Set up", false);
            showBack(c, null);
            c.device.show(null, false, false);
            return;
        }
        if ("preparing".equals(Guest.state) && Guest.selected(this) == Guest.IOS26) {
            String what = Guest.preparing;
            state(c, STARTING, "Starting…");
            detail(c, what == null ? "" : what);
            buttons(c, "Open", false);
            showBack(c, null);
            return;
        }
        boolean starting = starting(Guest.IOS26);
        state(c, starting ? STARTING : up ? RUNNING : READY, starting ? "Starting…" : up ? "Running" : "Ready");
        buttons(c, up ? "Open" : "Start", up);
        new Thread(() -> {
            List<Guest.Snap> list = up || Guest.userDisk(this, Guest.IOS26).exists()
                    ? Guest.snapshots(this, Guest.IOS26) : java.util.Collections.emptyList();
            Guest.Snap quick = quickOf(list);
            Guest.Snap back = !up && Guest.isWayBack(this, Guest.IOS26, quick) ? quick : null;
            String st, info;
            if (up) {
                st = starting ? "Starting…" : "Running";
                info = "";
            } else if (back != null) {
                st = "Turned off";
                info = (Guest.otherHardware(this, Guest.IOS26, back.tag) ? "Boots with " + Guest.ramMiB(this, Guest.IOS26) / 1024
                        + " GB next time" : "Boots next time") + ": about 3 min, then slow for about 15 min while iOS finishes starting.";
            } else if (quick != null) {
                // the last close, or an automatic save (AutoSave: settled after a boot, or before a crash)
                st = "Ready";
                info = "Continues from " + nice(quick.date) + ".";
            } else {
                // measured 2026-11-05 (APK-PLAN §6.20): every vCPU busy for ~13 min after a boot, idle after ~15
                st = "Ready";
                info = "Starts with a boot: about 3 min, then slow for about 15 min while iOS finishes starting.";
            }
            String text = (error(Guest.IOS26, up) + info).trim();
            String statusText = st + (twoX && !up ? "  ·  2x screen" : "");
            Bitmap shot = shot(c);
            runOnUiThread(() -> {
                state(c, starting ? STARTING : up ? RUNNING : READY, statusText);
                detail(c, text);
                showBack(c, back);
                c.device.show(shot, true, up);
            });
        }, "home-refresh26").start();
    }

    /** watchOS's panel: only when its files are on the phone (WatchProfile.dir), in every build. */
    private void refreshWatch() {
        Panel c = pWatch;
        boolean have = WatchProfile.available(this);
        c.root.setVisibility(have ? View.VISIBLE : View.GONE);
        if (!have) {
            return;
        }
        boolean up = Guest.running() && Guest.os(this) == Guest.WATCH;
        boolean starting = starting(Guest.WATCH);
        state(c, starting ? STARTING : up ? RUNNING : READY, starting ? "Starting…" : up ? "Running" : "Ready");
        buttons(c, up ? "Open" : "Start", up);
        new Thread(() -> {
            List<Guest.Snap> list = up || Guest.userDisk(this, Guest.WATCH).exists()
                    ? Guest.snapshots(this, Guest.WATCH) : java.util.Collections.emptyList();
            Guest.Snap quick = quickOf(list);
            Guest.Snap back = !up && Guest.isWayBack(this, Guest.WATCH, quick) ? quick : null;
            String st, info;
            if (up) {
                st = starting ? "Starting…" : "Running";
                info = "";
            } else if (back != null) {
                st = "Turned off";
                info = (Guest.otherHardware(this, Guest.WATCH, back.tag) ? "Boots with " + Guest.ramMiB(this, Guest.WATCH) / 1024
                        + " GB next time" : "Boots next time") + ": 5 to 7 min to the watch face.";
            } else if (quick != null) {
                st = "Ready";
                info = "Continues from " + nice(quick.date) + ".";
            } else {
                // measured 2026-11-07 (WATCHOS.md §2k): the face 5-7 minutes after a boot on our phone
                st = "Ready";
                info = "Starts with a boot: 5 to 7 min to the watch face.";
            }
            String text = (error(Guest.WATCH, up) + info).trim();
            Bitmap shot = shot(c);
            runOnUiThread(() -> {
                state(c, starting ? STARTING : up ? RUNNING : READY, st);
                detail(c, text);
                showBack(c, back);
                c.device.show(shot, true, up);
            });
        }, "home-refresh-watch").start();
    }

    private void refresh16() {
        Panel c = p16;
        boolean up = Guest.running() && Guest.os(this) == Guest.IOS16;
        // the setup from Apple runs (its last step starts iOS once, in the background): the panel follows it
        if (SetupService.busy() && !"a26".equals(SetupService.state.target)) {
            state(c, STARTING, "Being installed");
            detail(c, SetupService.state.text);
            buttons(c, "Show", false);
            c.menu.setVisibility(View.INVISIBLE);
            showBack(c, null);
            c.device.show(null, false, false);
            return;
        }
        if (!Guest.available(this)) {
            state(c, OFF, "Not installed");
            detail(c, "Installs from Apple's servers on this phone (about 1.5 hours), or comes from your PC.");
            buttons(c, "Set up", false);
            showBack(c, null);
            c.device.show(null, false, false);
            return;
        }
        boolean starting = starting(Guest.IOS16);
        state(c, starting ? STARTING : up ? RUNNING : READY, starting ? "Starting…" : up ? "Running" : "Ready");
        buttons(c, up ? "Open" : "Start", up);
        new Thread(() -> {
            List<Guest.Snap> list = up || Guest.userDisk(this, Guest.IOS16).exists()
                    ? Guest.snapshots(this, Guest.IOS16) : java.util.Collections.emptyList();
            Guest.Snap quick = quickOf(list);
            Guest.Snap back = !up && Guest.isWayBack(this, Guest.IOS16, quick) ? quick : null;
            String st, info;
            if (up) {
                st = starting ? "Starting…" : "Running";
                info = "";
            } else if (back != null) {
                st = "Turned off";
                info = "Boots next time: 2 to 4 min.";
            } else if (quick != null) {
                st = "Ready";
                info = "Continues from " + nice(quick.date) + ".";
            } else {
                st = "Ready";
                info = "Starts with a boot: 2 to 4 min.";
            }
            int named = list.size() - (quick != null ? 1 : 0);
            if (!up && named > 0) {
                info += " " + named + " named save" + (named == 1 ? "" : "s") + ".";
            }
            if (!Guest.nativeMissing().isEmpty()) {
                info += " This phone's CPU runs iOS in emulation only, much slower (it lacks ARMv8.2's " + Guest.nativeMissing() + ").";
            }
            String text = (error(Guest.IOS16, up) + info).trim();
            Bitmap shot = shot(c);
            runOnUiThread(() -> {
                state(c, starting ? STARTING : up ? RUNNING : READY, st);
                detail(c, text);
                showBack(c, back);
                c.device.show(shot, true, up);
            });
        }, "home-refresh").start();
    }

    // -- the app's settings

    private void showSettings() {
        boolean can = MemoryCleaner.supported(this);
        freeRamValue.setText(can ? MemoryCleaner.enabled(this) ? "On" : "Off" : "Pair first");
        String pairing = WadbPairService.state;
        wadbValue.setText(pairing != null ? "Pairing…" : !WirelessAdb.paired(this) ? "Not paired"
                : WirelessAdb.refused(this) ? "Pair again" : WirelessAdb.connected() ? "Connected"
                : WirelessAdb.switchedOn(this) == 0 ? "Paired, off now" : "Paired");
    }

    private void toggleFreeRam() {
        if (!MemoryCleaner.supported(this)) {
            wirelessDebugging();    // Android 14 and later: only through a pairing
            return;
        }
        if (MemoryCleaner.enabled(this)) {
            MemoryCleaner.setEnabled(this, false);
            showSettings();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Free memory while iOS runs?")
                .setMessage("While iOS runs, and only then, this app ends the background processes of your other apps "
                        + "every 90 s, as Android does itself when memory runs short. iOS needs 4 to 8 GB, and a save "
                        + "3 GB more; without the room Android may close this app, iOS with it.\n\n"
                        + "Your other apps are not force-stopped: their alarms and notifications stay. But an app "
                        + "you left in the background starts again from its first screen when you open it, and "
                        + "music or a call keeps running only if it shows a notification.")
                .setPositiveButton("Turn on", (d, w) -> {
                    MemoryCleaner.setEnabled(this, true);
                    showSettings();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The pairing with this phone's Wireless debugging (WirelessAdb): what it is for and how it goes, or, once
     * paired, how it stands, a test and the way out.
     */
    private void wirelessDebugging() {
        if (!WirelessAdb.supported()) {
            return;
        }
        if (WadbPairService.running()) {
            new AlertDialog.Builder(this)
                    .setTitle("Pairing")
                    .setMessage(WadbPairService.state)
                    .setPositiveButton("Open Developer options", (d, w) -> openDeveloperOptions())
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        if (WirelessAdb.paired(this) && !WirelessAdb.refused(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Wireless debugging")
                    .setMessage(WirelessAdb.status(this) + ".\n\nWhile iOS runs, the app asks Android through it to end the "
                            + "background processes of other apps (Free memory while iOS runs).\n\nAfter a restart of the phone "
                            + "Wireless debugging is off until you turn it on again (Developer options, on Wi-Fi); the pairing stays.")
                    .setPositiveButton("Test now", (d, w) -> testWirelessAdb())
                    .setNeutralButton("Unpair", (d, w) -> {
                        WirelessAdb.unpair(this);
                        showSettings();
                        Toast.makeText(this, "Unpaired. Settings still lists the key under Wireless debugging > Paired devices: "
                                + "remove it there too.", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Pair with Wireless debugging")
                .setMessage("Android 14 and later let an app end only its own background processes. Paired with this phone's "
                        + "Wireless debugging, the app can ask Android to end those of your other apps while iOS runs, so "
                        + "Android does not close iOS for lack of memory.\n\n"
                        + "1. Tap Pair: Developer options open.\n"
                        + "2. Wireless debugging (needs Wi-Fi): on, then \"Pair device with pairing code\".\n"
                        + "3. A notification of this app asks for the six digits: type them there, without leaving that screen.\n\n"
                        + "Only this app, on this phone, uses the pairing. After a restart of the phone Wireless debugging is "
                        + "off until you turn it on again.")
                .setPositiveButton("Pair", (d, w) -> startPairing())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startPairing() {
        // the code goes into a notification: Android 13 and later ask the user for those first
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 7);
            return;
        }
        WadbPairService.start(this);
        showSettings();
        openDeveloperOptions();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != 7) {
            return;
        }
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startPairing();
        } else {
            Toast.makeText(this, "The pairing code is typed into a notification of this app: allow its notifications first.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Developer options are off: Settings > About phone > Software information, tap Build number "
                    + "seven times, then open Developer options.", Toast.LENGTH_LONG).show();
        }
    }

    /** One "am kill-all" through the pairing, with the memory before and after. */
    private void testWirelessAdb() {
        Toast.makeText(this, "Asking Android…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long before = mi.availMem >> 20;
            String said;
            try {
                WirelessAdb.shell(this, "am kill-all", 30_000);
                Thread.sleep(1500);
                am.getMemoryInfo(mi);
                said = "It works: available memory " + before + " MB before, " + (mi.availMem >> 20) + " MB after.";
            } catch (IOException e) {
                said = "It did not work: " + (WirelessAdb.lastError != null ? WirelessAdb.lastError : e.getMessage()) + ".";
            } catch (InterruptedException e) {
                return;
            }
            AppLog.note(this, "wireless adb test: " + said);
            String text = said;
            runOnUiThread(() -> {
                showSettings();
                if (!isFinishing()) {
                    new AlertDialog.Builder(this).setTitle("Wireless debugging").setMessage(text).setPositiveButton("OK", null).show();
                }
            });
        }, "wadb-test").start();
    }

    /** What each system is doing, and the saves (those come from qemu-img or QEMU: off the UI thread). */
    private void refresh() {
        refresh26();
        refreshWatch();
        refresh16();
        if (Guest.turningOff()) {
            Panel c = Guest.os(this) == Guest.WATCH ? pWatch : Guest.os(this) == Guest.IOS26 ? p26 : p16;
            state(c, STARTING, "Turning off…");
        }
        showSettings();
        showPowerWarning();
        footer.setText(String.format(java.util.Locale.ROOT, "ASoftware iOS %s  ·  %.1f GB free on this phone",
                UpdateCheck.version(this), getFilesDir().getUsableSpace() / 1e9));
    }
}
