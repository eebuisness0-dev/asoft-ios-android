package com.asoft.ios;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Supplier;

/**
 * The notice of a record the user has not seen yet (a setup that failed, the app closed by Android or crashed:
 * CrashReport's setup and app kinds), on the home screen and the setup screens: what went wrong in a line, then
 * "If something went wrong, please share this log" with Share log, Report on GitHub and Dismiss. A tap on the
 * line opens it in Logs. Hidden while there is none; it looks again every 1.5 s on its own, so a record written
 * meanwhile (the setup's thread, the check of the app's last end at its start) shows up by itself.
 */
final class RecordNotice {
    private static final int BG = 0xff2a0f0e, ACCENT = 0xffff6a5f, TEXT = 0xffe5e5ea, DIM = 0xffb0524c;

    private final Activity a;
    private final LinearLayout root;
    private final TextView what, cause, more;
    /** The record a screen shows itself (under a failed step's red line): not again here. */
    private final Supplier<String> except;
    private final Handler h = new Handler(Looper.getMainLooper());
    private String shownFor = "";
    private CrashReport.Record record;
    private volatile boolean looking;

    RecordNotice(Activity a, Supplier<String> except) {
        this.a = a;
        this.except = except;
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(8), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG);
        g.setCornerRadius(dp(18));
        root.setBackground(g);
        root.setVisibility(View.GONE);

        what = new TextView(a);
        what.setTextSize(15);
        what.setTextColor(ACCENT);
        what.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        what.setPadding(0, 0, dp(8), 0);
        what.setOnClickListener(v -> open());
        root.addView(what);
        cause = new TextView(a);
        cause.setTextSize(13);
        cause.setTextColor(0xffd1a19c);
        cause.setMaxLines(3);
        cause.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cause.setPadding(0, dp(4), dp(8), 0);
        cause.setOnClickListener(v -> open());
        root.addView(cause);
        TextView ask = new TextView(a);
        ask.setTextSize(13.5f);
        ask.setTextColor(TEXT);
        ask.setText("If something went wrong, please share this log.");
        ask.setPadding(0, dp(8), dp(8), 0);
        root.addView(ask);
        more = new TextView(a);
        more.setTextSize(12.5f);
        more.setTextColor(DIM);
        more.setPadding(0, dp(2), dp(8), 0);
        more.setVisibility(View.GONE);
        root.addView(more);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(-dp(14), dp(2), 0, 0);
        row.addView(link("Share log", ACCENT, v -> {
            if (record != null) {
                CrashReport.share(a, record);
            }
        }));
        row.addView(link("Report on GitHub", ACCENT, v -> {
            if (record != null) {
                CrashReport.openIssue(a, record);
            }
        }));
        row.addView(link("Dismiss", DIM, v -> {
            // every unseen one: the notice does not come back one by one (Logs keeps them all)
            CrashReport.seenAll(a);
            look();
        }));
        root.addView(row);
        tick();
    }

    /** Its view, for the screen to place. */
    View view() {
        return root;
    }

    private int dp(float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private TextView link(String s, int color, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(color);
        t.setPadding(dp(14), dp(9), dp(8), dp(9));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(14));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff), null, mask));
        t.setOnClickListener(l);
        return t;
    }

    private void open() {
        if (record != null) {
            a.startActivity(new Intent(a, LogsActivity.class).putExtra(LogsActivity.EXTRA_ITEM, LogsActivity.RECORD + record.id));
        }
    }

    private void tick() {
        if (a.isFinishing() || a.isDestroyed()) {
            return;
        }
        look();
        h.postDelayed(this::tick, 1500);
    }

    /** Again when the unseen records or the one the screen shows itself changed (the files are read off the UI thread). */
    void look() {
        String ex = except == null ? null : except.get();
        String key = CrashReport.unseenKey(a) + "|" + ex;
        if (key.equals(shownFor) || looking) {
            return;
        }
        looking = true;
        new Thread(() -> {
            CrashReport.Record r = CrashReport.newestUnseen(a, ex);
            int others = 0;
            for (String id : CrashReport.unseen(a)) {
                others += r != null && !id.equals(r.id) && !id.equals(ex) ? 1 : 0;
            }
            int n = others;
            a.runOnUiThread(() -> {
                looking = false;
                shownFor = key;
                record = r;
                root.setVisibility(r == null ? View.GONE : View.VISIBLE);
                if (r == null) {
                    return;
                }
                what.setText(r.summary());
                String why = causeLine(r);
                cause.setText(why);
                cause.setVisibility(why.isEmpty() ? View.GONE : View.VISIBLE);
                more.setText(n == 1 ? "1 more in Logs" : n + " more in Logs");
                more.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
            });
        }, "record-notice").start();
    }

    /** The cause under the notice's line, when it says more than the line: a setup's error, a crash's exception. */
    private static String causeLine(CrashReport.Record r) {
        String c = r.cause == null ? "" : r.cause.trim();
        if (c.isEmpty() || r.summary().contains(c)) {
            return "";
        }
        return c.length() > 300 ? c.substring(0, 299) + "…" : c;
    }
}
