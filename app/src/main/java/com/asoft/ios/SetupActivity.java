package com.asoft.ios;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Set up: bring iOS 16 in from the PC (pc/Send-ToPhone.cmd makes ASoftware-iOS16.asoft and,
 * with the phone on USB, pushes it to this app's import folder), or from a file picked here.
 */
public class SetupActivity extends Activity {
    private static final int PICK = 1;
    private LinearLayout box;
    private TextView status;
    private ProgressBar bar;
    private volatile boolean busy;
    private SetupScreen fromApple;
    private Setup26Screen from26;
    private View section26;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DevNight.apply(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFitsSystemWindows(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(56, 72, 56, 56);
        scroll.addView(box);
        setContentView(scroll);
        show();
        // the home screen's "Set up iOS 26 or watchOS": open at that part, not at iOS 16's. The parts above it
        // fill in after the first layout (iOS 16's list of Apple's files), so it follows that part's place
        // for 3 s, until the first touch
        if (state == null && getIntent().getBooleanExtra(EXTRA_IOS26, false)) {
            long until = android.os.SystemClock.uptimeMillis() + 3000;
            boolean[] touched = {false};
            scroll.setOnTouchListener((v, e) -> {
                touched[0] = true;
                return false;
            });
            scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (touched[0] || android.os.SystemClock.uptimeMillis() > until) {
                        scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        return;
                    }
                    scroll.scrollTo(0, Math.max(0, section26.getTop() - 24));
                }
            });
        }
    }

    /** Intent extra: open at iOS 26 and watchOS from Apple. */
    static final String EXTRA_IOS26 = "ios26";

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy) {
            show();                     // a file may have arrived over adb meanwhile
        }
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, 0, 0, 28);
        box.addView(t);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        box.addView(b);
        return b;
    }

    /** The notice of a record not seen yet (RecordNotice), but the one a failed step or import shows under its line. */
    private RecordNotice notice;
    /** The record of the last import that failed, shown under its status line. */
    private volatile String importRecord;

    private void show() {
        box.removeAllViews();
        // the title, and Logs beside it: there on a fresh app too, where a failed first setup has nothing else to show
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, 28);
        TextView title = new TextView(this);
        title.setText("Set up");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button logs = new Button(this);
        logs.setText("Logs");
        logs.setAllCaps(false);
        logs.setOnClickListener(v -> startActivity(new Intent(this, LogsActivity.class)));
        head.addView(logs, new LinearLayout.LayoutParams(-2, -2));
        box.addView(head);
        if (notice == null) {
            notice = new RecordNotice(this, () -> {
                SetupService.State s = SetupService.state;
                return s.error != null && s.record != null ? s.record : importRecord;
            });
        }
        View n = notice.view();
        if (n.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) n.getParent()).removeView(n);
        }
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.bottomMargin = 28;
        box.addView(n, nlp);
        if (Installer.installed(this)) {
            text("iOS 16 is set up on this phone. Importing or installing again replaces it: this iPhone's settings, apps and saves go.",
                    15, 0xffbbbbbb);
        }
        // no PC: Apple's files, the PC installer's helper VM (PhoneSetup)
        if (fromApple == null) {
            fromApple = new SetupScreen(this);
        }
        box.addView(fromApple.view());
        // iOS 26 and watchOS from Apple, behind the GPU check (Setup26Screen)
        if (from26 == null) {
            from26 = new Setup26Screen(this);
        }
        section26 = from26.view();      // (view() takes it out of its old parent: call it only here)
        box.addView(section26);
        TextView pc = text("Or bring it from your PC", 18, Color.WHITE);
        pc.setPadding(0, 56, 0, 28);
        text("1. Install ASoftware iOS (iOS 16) on a Windows PC.\n"
                + "2. On the PC, run Send-ToPhone.cmd from the ASoftware iOS folder. It packs that iOS into ASoftware-iOS16.asoft (about 6.5 GB).\n"
                + "3. With this phone on USB and USB debugging on, it sends the file here by itself. "
                + "Otherwise copy the file to the phone (for example to Download) and choose it below.",
                15, 0xffbbbbbb);
        for (File f : Installer.waiting(this)) {
            button(String.format("Import %s (%.1f GB)", f.getName(), f.length() / 1e9), v -> {
                try {
                    run(new FileInputStream(f), f.length(), f);
                } catch (IOException e) {
                    status.setText(e.getMessage());
                }
            });
        }
        button("Choose file…", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i, PICK);
        });
        text(String.format("%.1f GB free on this phone; iOS needs about 6.5 GB, and 4 GB more for a save.",
                getFilesDir().getUsableSpace() / 1e9), 13, 0xff888888).setPadding(0, 28, 0, 28);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        box.addView(bar);
        status = text("", 15, Color.WHITE);
        status.setGravity(Gravity.START);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri u = data.getData();
        long size = -1;
        try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                size = c.getLong(0);
            }
        }
        try {
            run(getContentResolver().openInputStream(u), size, null);
        } catch (IOException e) {
            status.setText(e.getMessage());
        }
    }

    /** Asks first when it replaces something, then imports in the background; @done is the pushed file to delete after. */
    private void run(InputStream in, long total, File done) {
        if (busy) {
            return;
        }
        if (Guest.running()) {
            status.setText("Close iOS first (side bar > Saves > Save and close).");
            return;
        }
        String n = done == null ? "" : done.getName();
        boolean other = n.contains("iOS26") || n.contains("watchOS");
        if (Installer.hasData(this) || other) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(n.contains("iOS26") ? "Bring in iOS 26?" : n.contains("watchOS") ? "Bring in watchOS?" : "Replace iOS on this phone?")
                    .setMessage("What the file replaces goes first: that system's disk with its settings, apps and saves (a user "
                            + "disk of iOS 26 or the watch is kept as .old). Then the new one is copied in.")
                    .setPositiveButton("Replace", (d, w) -> importNow(in, total, done))
                    .setNegativeButton("Cancel", (d, w) -> {
                        try {
                            in.close();
                        } catch (IOException ignored) {
                            // nothing read
                        }
                    })
                    .show();
            return;
        }
        importNow(in, total, done);
    }

    private void importNow(InputStream in, long total, File done) {
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        for (int i = 0; i < box.getChildCount(); i++) {
            if (box.getChildAt(i) instanceof Button) {
                box.getChildAt(i).setEnabled(false);
            }
        }
        bar.setVisibility(View.VISIBLE);
        status.setText("Starting… keep this screen open.");
        importRecord = null;
        long t0 = System.currentTimeMillis();
        long logAt = new java.io.File(Guest.runDir(this), "app.txt").length();
        AppExit.begin(this, AppExit.IMPORT, "an import from the PC was running");
        new Thread(() -> {
            String result;
            try (InputStream s = in) {
                String os = Installer.installAny(this, new java.io.BufferedInputStream(s, 1 << 20), total, (what, at, all) -> runOnUiThread(() -> {
                    bar.setIndeterminate(all <= 0);
                    if (all > 0) {
                        bar.setProgress((int) (at * 1000 / all));
                    }
                    status.setText(all > 0 ? String.format("%s: %.1f of %.1f GB", what, at / 1e9, all / 1e9) : what + "…");
                }));
                if (done != null) {
                    done.delete();
                }
                String notes = "16".equals(os) ? Installer.kernelNotes(this) : "";
                result = String.format("%s is set up (%d s).%s", "16".equals(os) ? "iOS 16" : "26".equals(os) ? "iOS 26" : "watchOS",
                        (System.currentTimeMillis() - t0) / 1000, notes.isEmpty() ? "" : "\n" + notes);
            } catch (IOException | RuntimeException e) {
                result = "Not set up: " + e.getMessage();
                AppLog.note(this, "import stopped: " + e);
                importRecord = SetupRecord.writeImport(this, e.getMessage(), e, total, logAt, t0);
            } finally {
                AppExit.end(this, AppExit.IMPORT);
            }
            String r = result;
            runOnUiThread(() -> finishRun(r));
        }, "setup-import").start();
    }

    private void finishRun(String result) {
        busy = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        show();
        status.setText(result);
        String id = importRecord;
        if (id != null) {
            // under the failed import's line: its log, as under a failed setup step
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button share = new Button(this), report = new Button(this);
            share.setText("Share log");
            report.setText("Report on GitHub");
            for (Button b : new Button[] {share, report}) {
                b.setAllCaps(false);
                row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
            }
            share.setOnClickListener(v -> {
                CrashReport.Record rec = CrashReport.find(this, id);
                if (rec != null) {
                    CrashReport.share(this, rec);
                }
            });
            report.setOnClickListener(v -> {
                CrashReport.Record rec = CrashReport.find(this, id);
                if (rec != null) {
                    CrashReport.openIssue(this, rec);
                }
            });
            box.addView(row);
        }
        if (Installer.installed(this) && result.startsWith("iOS 16 is set up")) {
            Guest.select(this, Guest.IOS16);
            button("Start iOS 16 (the first start takes a few minutes)", v -> {
                startActivity(new Intent(this, ScreenActivity.class));
                finish();
            });
        }
    }
}
