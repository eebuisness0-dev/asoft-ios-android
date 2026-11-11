package com.asoft.ios;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Logs: what the app kept when something went wrong (CrashReport's records, newest first: when, what, the cause
 * in a line), the app's own log and the last GPU check's report. A tap opens one (this screen again, with
 * EXTRA_ITEM) with its text, scrubbed as it would be sent, and Share, Report on GitHub and, for a record, Delete.
 * Reached from the home screen's Settings and from Set up, which shows its Logs button even on a fresh app.
 * The home screen's style: rounded groups of rows on black.
 */
public class LogsActivity extends Activity {
    /** Which log to show: RECORD + its id, APP_LOG or GPU; none: the list. */
    static final String EXTRA_ITEM = "item";
    static final String RECORD = "record:", APP_LOG = "applog", GPU = "gpucheck";

    private static final int BG = Color.BLACK, LINE = 0xff26262a, TEXT = 0xfff5f5f7, DIM = 0xff8e8e93, FAINT = 0xff636366,
            LINK = 0xff6cb2ff, RED = 0xffff5a50;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    /** The part of the app's log Logs shows and shares: its end (the log is never cut). */
    private static final int APP_LOG_BYTES = 150_000;

    private LinearLayout box;
    private String item;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

    private TextView link(LinearLayout to, String s, int color, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setTextColor(color);
        t.setPadding(dp(14), dp(10), dp(10), dp(10));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff), null, round(Color.WHITE, 14)));
        t.setOnClickListener(l);
        to.addView(t);
        return t;
    }

    /** A rounded group of rows, as the home screen's Settings. */
    private LinearLayout group(int topDp) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable s = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xff19191c, 0xff111113});
        s.setCornerRadius(dp(22));
        s.setStroke(Math.max(1, dp(0.75f)), LINE);
        g.setBackground(s);
        g.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topDp);
        box.addView(g, lp);
        return g;
    }

    /** A row: @title over @sub (none when empty), @value on the right; a hairline above all but the first. */
    private void row(LinearLayout group, String title, String sub, String value, View.OnClickListener l) {
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
        r.setPadding(dp(18), dp(12), dp(16), dp(12));
        r.setMinimumHeight(dp(54));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        r.setBackgroundResource(tv.resourceId);
        r.setOnClickListener(l);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        text(col, title, 15.5f, TEXT);
        if (!sub.isEmpty()) {
            TextView s = text(col, sub, 13, DIM);
            s.setMaxLines(2);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            s.setPadding(0, dp(3), 0, 0);
        }
        if (!value.isEmpty()) {
            TextView v = text(r, value, 14, DIM);
            v.setPadding(dp(12), 0, 0, 0);
        }
        TextView chevron = text(r, "›", 19, FAINT);
        chevron.setPadding(dp(8), 0, 0, dp(2));
        group.addView(r);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        DevNight.apply(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(20), dp(16), dp(28));
        scroll.addView(box);
        setContentView(scroll);
        item = getIntent().getStringExtra(EXTRA_ITEM);
        if (item != null) {
            showItem();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (item == null) {
            showList();         // a log opened from here may have been deleted meanwhile
        }
    }

    private void title(String s) {
        TextView t = text(box, s, 25, TEXT);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    }

    private void open(String what) {
        startActivity(new Intent(this, LogsActivity.class).putExtra(EXTRA_ITEM, what));
    }

    // -- the list

    private void showList() {
        new Thread(() -> {
            List<CrashReport.Record> recs = new ArrayList<>();
            for (File f : CrashReport.files(this)) {
                CrashReport.Record r = CrashReport.parse(f, false);
                if (r != null) {
                    recs.add(r);
                }
            }
            Set<String> unseen = CrashReport.unseen(this);
            long appLog = new File(Guest.runDir(this), "app.txt").length();
            GpuCheck.Result gpu = GpuCheck.dir(this).isDirectory() ? GpuCheck.last(this) : null;
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    list(recs, unseen, appLog, gpu);
                }
            });
        }, "logs-list").start();
    }

    private void list(List<CrashReport.Record> recs, Set<String> unseen, long appLog, GpuCheck.Result gpu) {
        box.removeAllViews();
        title("Logs");
        TextView sub = text(box, "What the app kept when something went wrong, newest first. Nothing is sent by itself: Share"
                + " and Report on GitHub show you the text first; paths inside the app are shortened and nothing personal is"
                + " in it.", 13.5f, DIM);
        sub.setPadding(0, dp(6), 0, dp(4));
        if (recs.isEmpty()) {
            TextView none = text(box, "Nothing went wrong so far: no records.", 15, DIM);
            none.setPadding(dp(4), dp(22), 0, 0);
        } else {
            LinearLayout g = group(18);
            for (CrashReport.Record r : recs) {
                String when = MainActivity.nice(r.time);
                String head = (unseen.contains(r.id) ? "● " : "") + r.summary();
                String cause = CrashText.shortCause(r.cause);
                row(g, head, when + (cause.isEmpty() || r.summary().contains(cause) ? "" : "  ·  " + cause), "", v -> open(RECORD + r.id));
            }
        }
        TextView h = text(box, "The app's own logs", 13, DIM);
        h.setTypeface(MEDIUM);
        h.setPadding(dp(18), dp(28), dp(18), dp(10));
        LinearLayout g = group(0);
        row(g, "App log", "What the app decided and why: starts, saves, setups", appLog > 0 ? size(appLog) : "empty", v -> open(APP_LOG));
        if (gpu != null && gpu.ran) {
            row(g, "GPU check", gpu.summary, gpu.pass ? "passed" : "not passed", v -> open(GPU));
        }
        if (!recs.isEmpty()) {
            LinearLayout actions = new LinearLayout(this);
            actions.setPadding(dp(4), dp(16), 0, 0);
            box.addView(actions);
            link(actions, "Delete all records", RED, v -> new AlertDialog.Builder(this)
                    .setTitle("Delete all " + recs.size() + " records?")
                    .setMessage("They cannot be shared or reported afterwards. The app's log and the GPU check stay.")
                    .setPositiveButton("Delete all", (d, w) -> {
                        CrashReport.deleteAll(this);
                        showList();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }
    }

    private static String size(long b) {
        return b >= 1e6 ? String.format(java.util.Locale.ROOT, "%.1f MB", b / 1e6) : String.format(java.util.Locale.ROOT, "%d KB", Math.max(1, b / 1000));
    }

    // -- one log

    /** What one log shows: its title, a line about it, its text (scrubbed) and the ways to send it on. */
    private static final class Shown {
        String title = "", meta = "", text = "";
        CrashReport.Record record;
        GpuCheck.Result gpu;
    }

    private void showItem() {
        box.removeAllViews();
        title("Logs");
        text(box, "Loading…", 14, DIM).setPadding(0, dp(12), 0, 0);
        new Thread(() -> {
            Shown s = new Shown();
            Map<String, String> pre = CrashReport.prefixes(this);
            if (item.startsWith(RECORD)) {
                s.record = CrashReport.find(this, item.substring(RECORD.length()));
                if (s.record != null) {
                    CrashReport.seen(this, s.record.id);
                    s.title = s.record.summary();
                    s.meta = MainActivity.nice(s.record.time) + "  ·  " + (CrashReport.GUEST.equals(s.record.kind) ? "a guest's end"
                            : CrashReport.SETUP.equals(s.record.kind) ? "a setup that stopped" : "the app's own end");
                    s.text = CrashReport.shareText(this, s.record);
                }
            } else if (APP_LOG.equals(item)) {
                s.title = "App log";
                s.meta = "Its newest part: what the app decided and why (starts, saves, setups, the helper VM).";
                s.text = CrashText.scrub(CrashReport.tailText(new File(Guest.runDir(this), "app.txt"), APP_LOG_BYTES), pre);
            } else if (GPU.equals(item)) {
                s.gpu = GpuCheck.last(this);
                s.title = "GPU check";
                s.meta = s.gpu.summary;
                s.text = CrashText.scrub(s.gpu.report, pre);
            }
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (s.title.isEmpty()) {
                    Toast.makeText(this, "This log is gone", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                showItem(s);
            });
        }, "logs-item").start();
    }

    private void showItem(Shown s) {
        box.removeAllViews();
        TextView t = text(box, s.title, 20, TEXT);
        t.setTypeface(MEDIUM);
        TextView m = text(box, s.meta, 13.5f, DIM);
        m.setPadding(0, dp(6), 0, 0);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(-dp(14), dp(8), 0, dp(4));
        box.addView(actions);
        link(actions, "Share", LINK, v -> {
            if (s.record != null) {
                CrashReport.share(this, s.record);
            } else {
                CrashReport.share(this, s.title, s.text);
            }
        });
        link(actions, "Report on GitHub", LINK, v -> report(s));
        if (s.record != null) {
            link(actions, "Delete", RED, v -> new AlertDialog.Builder(this)
                    .setTitle("Delete this record?")
                    .setMessage("It cannot be shared or reported afterwards.")
                    .setPositiveButton("Delete", (d, w) -> {
                        CrashReport.delete(this, s.record.id);
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }
        TextView body = text(box, s.text, 11, 0xffcfcfcf);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        body.setBackground(round(0xff111113, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        body.setLayoutParams(lp);
    }

    /** The GitHub issue of what @s shows: a record's own report, else the log under the app's and phone's facts. */
    private void report(Shown s) {
        if (s.record != null) {
            CrashReport.openIssue(this, s.record);
            return;
        }
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("App", CrashReport.appVersion(this));
        facts.put("Phone", CrashReport.phone());
        List<String> lines = new ArrayList<>(Arrays.asList(s.text.split("\n")));
        List<CrashText.Block> blocks = new ArrayList<>();
        if (s.gpu != null) {
            blocks.add(new CrashText.Block("The GPU check's report", lines, 1));
            CrashReport.openIssue(this, "GPU check: " + s.gpu.summary, s.gpu.summary,
                    s.gpu.why.isEmpty() ? s.gpu.summary : String.join("; ", s.gpu.why), facts, blocks);
        } else {
            blocks.add(new CrashText.Block("The app's log, its last lines", lines, 1));
            CrashReport.openIssue(this, "App log", "The app's log, from Logs", "(see the log)", facts, blocks);
        }
    }
}
