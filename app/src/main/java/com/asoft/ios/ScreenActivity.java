package com.asoft.ios;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * iOS full screen: the guest's framebuffer drawn every display frame it changes, a finger is
 * the guest's touchscreen, a slim bar holds Home and the side button, the phone's volume keys
 * are the guest's.
 */
public class ScreenActivity extends Activity implements SurfaceHolder.Callback, Choreographer.FrameCallback {
    private SurfaceView view;
    private TextView status;
    private HandlerThread renderThread;
    private Handler render;
    private volatile boolean attached;
    private volatile int surfW, surfH;
    private int fbPid = -1;             // the QEMU whose framebuffer is mapped
    private long lastTry;
    private Button restart;
    /** Under restart when the guest ended by itself: the crash report (CrashReport.show). */
    private Button report;
    /** The pending crash record shown, and what it was read for (the os, its notice, the newest record's time). */
    private CrashReport.Record crash;
    private String crashFor;
    /** watchOS: the picture sits in a drawn watch (WatchCase), whose crown and side button are the watch's. */
    private boolean watchMode;
    private WatchCase watchCase;
    private Object backCallback;
    /** ⏏ was pressed: leave once the saved guest has quit. */
    private boolean closing;
    /** ...and it is a Turn off, not a save. */
    private boolean closingOff;
    /** "Screen off" over the guest while iOS has its display off; a tap wakes it. */
    private TextView cover;
    private java.io.RandomAccessFile leds;
    private int ledsPid = -1;
    /** When this screen first read the running QEMU's LED byte (uptime ms): a loaded save's sleeping display is no Auto-Lock. */
    private long ledsPidSince;
    /**
     * This QEMU loaded a save: once it runs, a display that was asleep in the save is woken. AutoSave saves
     * when iOS has settled, mostly with its display off, so a load showed only "Screen off" and looked as if
     * it had not worked (2026-11-06).
     */
    private boolean wakeAfterLoad;
    private long wakeAt, wakeUntil, wakePowerMs;
    private int wakeTries;
    private long ledsRead;
    private boolean displayOff, displayWasOff, coverArmed;
    private long savingSince, savingShown = -1;
    /** The phone's own screen went off (iOS was locked with it, or slept already): wake iOS when the user is back. */
    private boolean lockedByPhone;
    /**
     * iOS 26's Always On Display (APK-PLAN §6.23) is Guest.aod26: iOS goes there only at the side button (its
     * Auto-Lock and lock screen timeout are Never on the AOD base), so the app knows it without guessing. In
     * AOD iOS takes no touches (backboardd puts the touchscreen in its wakeOnTouch mode, where an iPhone's touch
     * controller wakes it with an event bit QEMU's touchscreen cannot send): the first touch wakes it with Home
     * and goes no further, as on an iPhone. This is that touch: its moves and lift stay away from iOS.
     */
    private boolean swallowTouch;
    /** The bar's touch lock is on: touches on the picture go nowhere. */
    private boolean touchLocked;
    /**
     * The side bar: a handle that opens it and moves it up and down the right edge (where it sits, a share of
     * the height, and whether it is open are kept: prefs "bar-y", "bar-open"); each button says what it does
     * on a long press.
     */
    private LinearLayout bar, barItems;
    private ImageButton handle, touchLockButton, pinButton;
    private float barDownY;
    private int barFrom;
    private long barDownAt;
    private boolean barMoved;
    /** The pointer iOS has as its finger (both guests' touchscreens are single-touch), -1 when none; where it is (0..1). */
    private int finger = -1;
    private float fingerX, fingerY;
    /** ios26.txt touchlog=1 (debug builds): every MotionEvent and what went to iOS, in files/run/touch.txt. */
    private java.io.PrintWriter touchLog;
    private final android.content.BroadcastReceiver phoneScreenOff = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            // the phone's power key never reaches an app; its effect does: iOS locks with the phone, and
            // wakes when the user is back (also when iOS slept first and the phone's timeout followed)
            readLeds();
            if (Guest.running() && !Guest.saving() && !closing) {
                if (!displayOff && !Guest.aod26) {
                    sideButton();
                }
                lockedByPhone = true;
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        DevNight.apply(this);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }       // Android 10: immersive()'s layout flags do the same
        view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        watchMode = Guest.os(this) == Guest.WATCH;
        if (watchMode) {
            watchCase = new WatchCase(this);
            watchCase.addView(view);        // laid out at the watch's screen (render.c and guestPoint fit it exactly)
            root.addView(watchCase, new FrameLayout.LayoutParams(-1, -1));
            android.content.SharedPreferences p = getSharedPreferences("guest", MODE_PRIVATE);
            if (!p.getBoolean("watch-tip", false)) {
                p.edit().putBoolean("watch-tip", true).apply();
                android.widget.Toast.makeText(this, "The Digital Crown is on the watch's right: drag it to turn, tap to press. "
                        + "The volume keys turn it too.", android.widget.Toast.LENGTH_LONG).show();
            }
        } else {
            root.addView(view);
        }
        cover = new TextView(this);
        cover.setText(screenOffText("Screen off", "tap to wake"));
        cover.setTextColor(Color.WHITE);
        cover.setTextSize(18);
        cover.setGravity(Gravity.CENTER);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        cover.setOnClickListener(v -> {
            if (!Guest.saving() && !closing) {
                wake();
            }
        });
        if (watchMode) {
            watchCase.addView(cover);       // the screen-off cover inside the watch's screen
        } else {
            root.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        }
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setPadding(24, 24, 24, 24);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        // clear of the button bar at the screen's edge: a crash's cause is several lines and ran under it
        if (watchMode) {
            slp.leftMargin = dp(54);
        } else {
            slp.rightMargin = dp(54);
        }
        root.addView(status, slp);
        // mid right edge: iOS keeps nothing there on the lock screen, home screen or dock
        // the watch keeps its crown on the right: its bar goes to the left edge
        root.addView(buttonBar(), new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | (watchMode ? Gravity.START : Gravity.END)));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) {
                placeBar();
            }
        });
        restart = new Button(this);
        restart.setText("Restart iOS");
        restart.setVisibility(View.GONE);
        restart.setOnClickListener(v -> {
            restart.setVisibility(View.GONE);
            report.setVisibility(View.GONE);
            if (Qmp.noSpace && Guest.running()) {
                // paused on a full disk: go on (QEMU pauses again, and says so, if there is still no room)
                Qmp.noSpace = false;
                status.setText("");
                Qmp q = Guest.qmp();
                if (q != null) {
                    q.send("{\"execute\":\"cont\"}");
                }
                return;
            }
            status.setText("starting");
            GuestService.start(this);
        });
        root.addView(restart, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        report = new Button(this);
        report.setText("Report this crash");
        report.setVisibility(View.GONE);
        report.setOnClickListener(v -> CrashReport.show(this, crash));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        rlp.topMargin = Math.round(64 * getResources().getDisplayMetrics().density);     // below restart
        root.addView(report, rlp);
        setContentView(root);
        view.setOnTouchListener(this::onTouch);
        renderThread = new HandlerThread("render");
        renderThread.start();
        render = new Handler(renderThread.getLooper());
        if (!Guest.running() && !"preparing".equals(Guest.state)) {     // preparing: a start is on its way
            GuestService.start(this);
        }
        registerReceiver(phoneScreenOff, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
        // Android's back gesture (a swipe in from the left edge) is iOS's back gesture here, not "leave the app"
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback cb = this::guestBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        }
    }

    /** Opened again (launcher, notification) while this screen exists: start iOS if it is not running. */
    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        if (!Guest.running() && !Guest.saving() && !closing && !"preparing".equals(Guest.state)) {
            restart.setVisibility(View.GONE);
            report.setVisibility(View.GONE);
            status.setText("starting");
            GuestService.start(this);
        }
    }

    private void guestBack() {
        if (touchLocked) {
            return;             // Android's back swipe is a touch too
        }
        Guest.input();
        Qmp q = Guest.qmp();
        if (q != null && finger == -1) {
            q.edgeSwipeBack();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        guestBack();
    }

    /** Wakes iOS's display: the side button on iOS 16, Home on iOS 26 (power would sleep it again). */
    private void wake() {
        Guest.button(Guest.ios26Running() ? "home" : "power");
    }

    /** The bar's Home (in AOD it wakes iOS, to its lock screen). */
    private void home() {
        Guest.aod26 = false;
        Guest.button("home");
    }

    /** The bar's side button: iOS locks (AOD on an AOD disk, else display off) or wakes, from AOD too. */
    private void power() {
        if (displayOff || Guest.aod26) {
            Guest.aod26 = false;
            Guest.button("power");
        } else {
            sideButton();
        }
    }

    /**
     * The side button with iOS's display on and not in AOD: on an AOD disk that takes iOS to its AOD, the only
     * way there (iOS's Auto-Lock and lock screen timeout are Never on the AOD base), so the app knows it.
     */
    private void sideButton() {
        if (Guest.ios26Running() && Guest.aodRunning) {
            Guest.aod26 = true;
            AppLog.note(this, "aod: in (the side button)");
        }
        Guest.button("power");
    }

    private View buttonBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xaa000000);
        bg.setCornerRadius(dp(26));
        bar.setBackground(bg);
        handle = barButton(R.drawable.ic_bar_handle, null, null);
        handle.setOnTouchListener(this::onHandleTouch);
        bar.addView(handle);
        barItems = new LinearLayout(this);
        barItems.setOrientation(LinearLayout.VERTICAL);
        if (!watchMode) {       // the watch's crown and side button are drawn on the watch (WatchCase)
            barItems.addView(barButton(R.drawable.ic_bar_home, "Home", this::home));
            barItems.addView(barButton(R.drawable.ic_bar_power, "Side button: lock or wake iOS", this::power));
        }
        touchLockButton = barButton(R.drawable.ic_bar_touch, "Touch lock: touches do not reach iOS", this::toggleTouchLock);
        barItems.addView(touchLockButton);
        pinButton = barButton(R.drawable.ic_bar_pin, "Pin the app: Android's edge swipes stay away from iOS", this::togglePin);
        barItems.addView(pinButton);
        barItems.addView(barButton(R.drawable.ic_bar_save, "Saves", this::openSaves));
        bar.addView(barItems);
        barItems.setVisibility(getSharedPreferences("guest", MODE_PRIVATE).getBoolean("bar-open", false) ? View.VISIBLE : View.GONE);
        this.bar = bar;
        showToggles();
        return bar;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** A round icon button of the bar; @label is its long press's tooltip. */
    private ImageButton barButton(int icon, String label, Runnable r) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        b.setBackground(round(false));
        // never focused: Enter from a physical keyboard would click it (Home!)
        b.setFocusable(false);
        if (label != null) {
            b.setContentDescription(label);
            b.setTooltipText(label);    // a mouse's hover
            // a finger's long press: One UI shows no tooltip for it (the S25 clicked instead), so say it here
            b.setOnLongClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                toast(label);
                return true;
            });
        }
        if (r != null) {
            b.setOnClickListener(v -> r.run());
        }
        return b;
    }

    /** A bar button's background: a round ripple, filled red while its lock is on. */
    private static android.graphics.drawable.Drawable round(boolean on) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        GradientDrawable fill = null;
        if (on) {
            fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(0xffc62828);
        }
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40ffffff), fill, mask);
    }

    /** The handle: a tap opens or closes the bar, a drag moves it, a long press says so. */
    private boolean onHandleTouch(View v, MotionEvent e) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                barDownY = e.getRawY();
                barFrom = lp.topMargin;
                barDownAt = android.os.SystemClock.uptimeMillis();
                barMoved = false;
                v.setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getRawY() - barDownY;
                if (!barMoved && Math.abs(dy) > android.view.ViewConfiguration.get(this).getScaledTouchSlop()) {
                    barMoved = true;
                    v.setPressed(false);
                }
                if (barMoved) {
                    int h = ((View) bar.getParent()).getHeight();
                    lp.topMargin = Math.max(0, Math.min(h - bar.getHeight(), barFrom + Math.round(dy)));
                    bar.setLayoutParams(lp);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                v.setPressed(false);
                if (barMoved) {
                    int h = ((View) bar.getParent()).getHeight();
                    if (h > 0) {
                        getSharedPreferences("guest", MODE_PRIVATE).edit().putFloat("bar-y", (float) lp.topMargin / h).apply();
                    }
                } else if (android.os.SystemClock.uptimeMillis() - barDownAt >= android.view.ViewConfiguration.getLongPressTimeout()) {
                    toast("Tap: show or hide the buttons. Drag: move the bar up or down.");
                } else {
                    boolean open = barItems.getVisibility() != View.VISIBLE;
                    barItems.setVisibility(open ? View.VISIBLE : View.GONE);
                    getSharedPreferences("guest", MODE_PRIVATE).edit().putBoolean("bar-open", open).apply();
                    showToggles();
                    bar.post(this::placeBar);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                v.setPressed(false);
                return true;
            default:
                return false;
        }
    }

    /** Puts the bar where it was left (a share of the height; the middle at first), whole on the screen. */
    private void placeBar() {
        if (bar == null) {
            return;
        }
        View parent = (View) bar.getParent();
        int h = parent.getHeight(), bh = bar.getHeight();
        if (h == 0 || bh == 0) {
            bar.post(this::placeBar);
            return;
        }
        float y = getSharedPreferences("guest", MODE_PRIVATE).getFloat("bar-y", -1f);
        int top = y < 0 ? (h - bh) / 2 : Math.round(y * h);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        int want = Math.max(0, Math.min(h - bh, top));
        if (lp.topMargin != want) {
            lp.topMargin = want;
            bar.setLayoutParams(lp);
        }
    }

    /** The bar's touch lock: touches on the picture stay away from iOS (the bar's own buttons still work). */
    private void toggleTouchLock() {
        touchLocked = !touchLocked;
        if (touchLocked) {
            liftFinger();
            swallowTouch = false;
        }
        touchLockButton.setImageResource(touchLocked ? R.drawable.ic_bar_touch_off : R.drawable.ic_bar_touch);
        showToggles();
        toast(touchLocked ? "Touch lock on: touches do not reach iOS" : "Touch lock off");
        AppLog.note(this, "touch lock " + (touchLocked ? "on" : "off"));
    }

    private boolean pinned() {
        return getSystemService(android.app.ActivityManager.class).getLockTaskModeState()
                != android.app.ActivityManager.LOCK_TASK_MODE_NONE;
    }

    /**
     * The bar's pin: Android's screen pinning (lock task), the only way an app keeps the phone's edge swipes
     * (the notification shade from the top, home from the bottom) from taking touches meant for iOS. Android
     * asks the user once; the pin comes off here again, or with Android's own unpin gesture.
     */
    private void togglePin() {
        try {
            if (pinned()) {
                stopLockTask();
            } else {
                startLockTask();
            }
        } catch (RuntimeException e) {
            toast("Android did not pin the app: " + e.getMessage());
        }
        // the state changes after Android's question: shown when the window gets its focus back, and here
        pinButton.postDelayed(this::showToggles, 1_500);
    }

    /** The touch lock and pin buttons are red while on; the closed bar's handle too, so a lock is never hidden. */
    private void showToggles() {
        if (bar == null) {
            return;
        }
        boolean pin = pinned(), open = barItems.getVisibility() == View.VISIBLE;
        touchLockButton.setBackground(round(touchLocked));
        pinButton.setBackground(round(pin));
        handle.setBackground(round(!open && (touchLocked || pin)));
        bar.setAlpha(open || touchLocked || pin ? 0.9f : 0.55f);
    }

    /** Two lines for the black cover: a title and a smaller, dimmer hint. */
    private static CharSequence screenOffText(String title, String hint) {
        android.text.SpannableString t = new android.text.SpannableString(title + '\n' + hint);
        int at = title.length() + 1;
        t.setSpan(new android.text.style.RelativeSizeSpan(0.7f), at, t.length(), 0);
        t.setSpan(new android.text.style.ForegroundColorSpan(0xff9a9a9a), at, t.length(), 0);
        return t;
    }

    // -- saves: the side bar's Saves lists them; a save is iOS as it was then (RAM and disk), about 3 GB

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    private static String gb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9);
    }

    /** The list comes from QEMU or qemu-img: fetched off the UI thread, then shown. */
    private void openSaves() {
        if (Guest.saving() || closing) {
            return;
        }
        new Thread(() -> {
            java.util.List<Guest.Snap> list = Guest.snapshots(this);
            boolean wayBack = Guest.quickStale(this, Guest.os(this));
            long free = getFilesDir().getUsableSpace();
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    showSaves(list, wayBack, free);
                }
            });
        }, "saves-list").start();
    }

    /** "11-09 14:36" of a save's date. */
    static String when(Guest.Snap s) {
        return s.date.substring(5, 16);
    }

    /** @wayBack: the quick save is the way back kept after a Turn off (Guest.quickStale). */
    private void showSaves(java.util.List<Guest.Snap> list, boolean wayBack, long free) {
        boolean up = Guest.running();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 16, 48, 0);
        TextView info = new TextView(this);
        boolean big = Guest.os(this) == Guest.IOS26 && Guest.ramMiB(this, Guest.IOS26) > Guest.RAM_TIGHT;
        info.setText(gb(free) + " free on the phone; a save takes about " + (big ? 5 : 3) + " GB.\n"
                + (list.isEmpty() ? "No saves yet." : "Tap a save to load or delete it."));
        info.setPadding(0, 0, 0, 16);
        box.addView(info);
        android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        for (Guest.Snap snap : list) {
            Button row = new Button(this);
            row.setAllCaps(false);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            int os = Guest.os(this);
            boolean back = Guest.isQuick(snap.tag) && (wayBack || Guest.otherHardware(this, os, snap.tag));
            row.setText((back ? "Go back to the save of " + when(snap) : snap.label())
                    + "\n" + snap.date + " · " + snap.size + " · " + Guest.hardware(this, os, snap.tag));
            row.setOnClickListener(v -> {
                dialog[0].dismiss();
                if (back) {
                    askGoBack(snap);
                } else {
                    saveActions(snap);
                }
            });
            box.addView(row);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("Saves")
                .setView(scroll)
                .setNegativeButton("Close", null);
        if (up) {
            b.setPositiveButton("Save now", (d, w) -> askSaveName(list, free));
            b.setNeutralButton("Save and close", (d, w) -> saveAndClose());
            TextView off = new TextView(this);
            off.setText("Turn off " + (Guest.watchRunning() ? "watchOS" : "iOS") + " without saving");
            off.setTextSize(16);
            off.setTextColor(0xffff453a);
            off.setPadding(0, 40, 0, 24);
            Guest.Snap quick = Guest.newestQuick(list);
            off.setOnClickListener(v -> {
                dialog[0].dismiss();
                askTurnOff(quick);
            });
            box.addView(off);
        }
        dialog[0] = b.show();
    }

    private void askSaveName(java.util.List<Guest.Snap> list, long free) {
        if (free < 4_000_000_000L) {
            toast("Not enough space: " + gb(free) + " free, a save needs about 3 GB");
            return;
        }
        android.widget.EditText name = new android.widget.EditText(this);
        name.setSingleLine(true);
        name.setText(new java.text.SimpleDateFormat("'Save' d MMM HH.mm", java.util.Locale.ENGLISH)
                .format(new java.util.Date()));
        name.selectAll();
        FrameLayout pad = new FrameLayout(this);
        pad.setPadding(48, 16, 48, 0);
        pad.addView(name);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Name this save")
                .setMessage("iOS stops for up to a minute while it is saved, then goes on.")
                .setView(pad)
                .setPositiveButton("Save", (d, w) -> {
                    String tag = Guest.tagFor(name.getText().toString());
                    if (tag == null) {
                        toast("Use letters, digits, spaces, - or _");
                        return;
                    }
                    boolean exists = false;
                    for (Guest.Snap s : list) {
                        exists |= s.tag.equals(tag);
                    }
                    if (exists) {
                        new android.app.AlertDialog.Builder(this)
                                .setMessage("Replace the save \"" + tag.replace('_', ' ') + "\"?")
                                .setPositiveButton("Replace", (d2, w2) -> saveNamed(tag))
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        saveNamed(tag);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveNamed(String tag) {
        new Thread(() -> {
            String why = Guest.saveNamed(this, tag);
            runOnUiThread(() -> toast(why == null ? "Saved \"" + tag.replace('_', ' ') + "\"" : "Not saved: " + why));
        }, "save-named").start();
    }

    private void saveActions(Guest.Snap snap) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(snap.label())
                .setMessage(snap.date + " · " + snap.size + "\n\n"
                        + (Guest.running() ? "Loading it replaces the iOS running now: what happened since its last save is lost."
                                : "Loading it starts iOS from that moment."))
                .setPositiveButton("Load", (d, w) -> {
                    status.setText("Loading \"" + snap.label() + "\"…");
                    restart.setVisibility(View.GONE);
                    report.setVisibility(View.GONE);
                    GuestService.load(this, snap.tag);
                })
                .setNeutralButton("Delete", (d, w) -> new android.app.AlertDialog.Builder(this)
                        .setMessage("Delete the save \"" + snap.label() + "\"?")
                        .setPositiveButton("Delete", (d2, w2) -> new Thread(() -> {
                            String why = Guest.deleteSnapshot(this, snap.tag);
                            runOnUiThread(() -> toast(why == null ? "Deleted" : "Not deleted: " + why));
                        }, "save-delete").start())
                        .setNegativeButton("Cancel", null)
                        .show())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The way back: the quick save kept after a Turn off. Going back loads it (into the running iOS, or as the
     * start), and from then on it is the normal quick save.
     */
    private void askGoBack(Guest.Snap snap) {
        int os = Guest.os(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Go back to the save of " + when(snap) + "?")
                .setMessage(goBackText(this, os, snap))
                .setPositiveButton("Go back", (d, w) -> {
                    Guest.goBack(this, os, snap.tag);
                    status.setText("Going back to " + when(snap) + "…");
                    restart.setVisibility(View.GONE);
                    report.setVisibility(View.GONE);
                    GuestService.load(this, snap.tag);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The Go back dialog's text: what is undone, and the hardware the save opens with. */
    static String goBackText(android.content.Context c, int os, Guest.Snap snap) {
        String hw = Guest.hardware(c, os, snap.tag);
        return (os == Guest.WATCH ? "watchOS" : "iOS") + " goes back to how it was at " + when(snap)
                + ": what you did after that (settings, apps, files) is undone.\n\n"
                + snap.date + " · " + snap.size + " · opens with " + hw
                + (Guest.otherHardware(c, os, snap.tag) ? " (its own; boots keep the setting, "
                + Guest.ramMiB(c, os) / 1024 + " GB)" : "");
    }

    /** What Turn off says, @quick the quick save (null: none), which stays as a way back. */
    static String turnOffText(int os, Guest.Snap quick) {
        return (os == Guest.WATCH ? "watchOS shuts down as an Apple Watch does" : "iOS shuts down as an iPhone does")
                + ": settings and apps stay, open apps close. The next start boots (a few minutes)."
                + (quick == null ? "" : " The last save (" + when(quick) + ") stays as a way back until the next Save and close.")
                + " Named saves stay."
                + (os == Guest.IOS26 ? "\n\niOS 26 is slow for about 15 minutes after a boot (its own "
                + "start-up work). Save and close keeps it as it is, past that." : "");
    }

    private void askTurnOff(Guest.Snap quick) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(Guest.watchRunning() ? "Turn off watchOS?" : "Turn off iOS?")
                .setMessage(turnOffText(Guest.os(this), quick))
                .setPositiveButton("Turn off", (d, w) -> {
                    if (!Guest.running() || Guest.saving() || closing) {
                        return;
                    }
                    closing = true;
                    closingOff = true;
                    GuestService.powerOff(this);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Where the compatibility mode's stops are collected (asoft-ios-android). */
    static final String COMPAT_ISSUE = "https://github.com/alpersamur3/asoft-ios-android/issues/1";

    static final String COMPAT_TEXT = "This is a known issue on this GPU (Adreno 750 and others without sparse binds from host memory)."
            + " If you want to help fix it, write on Reddit (r/hackios) or open an issue on GitHub.";
    /** How long a stop in the compatibility mode waits for its crash record before the notice shows alone. */
    private static final long COMPAT_WAIT_MS = 6000;
    /** When the guest's end in the compatibility mode was seen (Guest.endedInCompatMode); 0: none waits. */
    private long compatSince;

    /**
     * iOS 26 or watchOS ended by itself in the GPU's compatibility mode (GpuCheck.noSparse: no sparse binds from
     * host memory, so reims' views take a mapping each and a long run can reach Android's limit): once per stop
     * (Guest.endedInCompatMode), where to tell us about it. Only when no crash record came: with one, its report
     * says this (CrashReport.show with COMPAT_TEXT), so that one stop shows one dialog.
     */
    private void compatNotice(String name) {
        AppLog.note(this, "gpu: " + name + " stopped in the compatibility mode: notice shown");
        new android.app.AlertDialog.Builder(this)
                .setTitle(name + " stopped")
                .setMessage(COMPAT_TEXT)
                .setPositiveButton("Open the GitHub issue", (d, w) -> {
                    try {
                        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(COMPAT_ISSUE)));
                    } catch (android.content.ActivityNotFoundException e) {
                        toast(COMPAT_ISSUE);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Quick boot: keep iOS as it is, close; the next start goes on from here. */
    private void saveAndClose() {
        if (!Guest.running()) {
            finishAndRemoveTask();
            return;
        }
        closing = true;
        GuestService.save(this);
    }

    private long thumbAt;

    /**
     * Keeps a small picture of the guest's screen for the home screen's device (Guest.thumbFile): every 20 s
     * while the picture is live, and when this screen is left (@now). Not while iOS's display is off or in its
     * Always On Display, and not a boot's black screen: the picture before stays.
     */
    private void keepThumb(boolean now) {
        long up = android.os.SystemClock.uptimeMillis();
        if (!now && up - thumbAt < 20_000) {
            return;
        }
        if (fbPid == -1 || fbPid != Guest.pid(this) || !attached || displayOff || Guest.aod26 || Guest.saving() || closing
                || view.getWidth() <= 0 || !view.getHolder().getSurface().isValid()) {
            return;
        }
        thumbAt = up;
        // the guest's picture inside the surface (guestPoint's rectangle)
        float vw = view.getWidth(), vh = view.getHeight(), g = (float) Guest.fbW() / Guest.fbH();
        float w = vw, h = vh;
        if (vw / vh > g) {
            w = vh * g;
        } else {
            h = vw / g;
        }
        android.graphics.Rect from = new android.graphics.Rect(Math.round((vw - w) / 2), Math.round((vh - h) / 2),
                Math.round((vw + w) / 2), Math.round((vh + h) / 2));
        int tw = 300, th = Math.max(1, Math.round(300 / g));
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(tw, th, android.graphics.Bitmap.Config.ARGB_8888);
        java.io.File to = Guest.thumbFile(this, Guest.os(this));
        try {
            android.view.PixelCopy.request(view, from, b, result -> {
                if (result == android.view.PixelCopy.SUCCESS) {
                    long sum = 0;
                    for (int y = 4; y < th; y += 16) {
                        for (int x = 4; x < tw; x += 16) {
                            int c = b.getPixel(x, y);
                            sum += (c >> 16 & 0xff) + (c >> 8 & 0xff) + (c & 0xff);
                        }
                    }
                    long n = (long) ((th + 11) / 16) * ((tw + 11) / 16) * 3;
                    if (n > 0 && sum / n >= 14) {       // not a black screen (a boot, a display just off)
                        java.io.File tmp = new java.io.File(to.getPath() + ".new");
                        try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) {
                            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, o);
                        } catch (java.io.IOException e) {
                            tmp.delete();
                        }
                        if (tmp.length() > 0) {
                            tmp.renameTo(to);
                        }
                    }
                }
                b.recycle();
            }, render);
        } catch (IllegalArgumentException e) {
            b.recycle();    // the surface went away meanwhile
        }
    }

    /** The guest's display power from QEMU's LED byte (Guest.ledsFile); at most every 100 ms. */
    private void readLeds() {
        int pid = Guest.pid(this);
        if (pid != ledsPid) {
            ledsPid = pid;
            ledsPidSince = android.os.SystemClock.uptimeMillis();
            wakeAfterLoad = Guest.resumed;
            wakeAt = 0;
            wakeTries = 0;
            displayOff = displayWasOff = false;
            // a loaded state is past the boot's own display off; iOS 26's sleep is only ever a real one
            coverArmed = Guest.resumed || Guest.ios26Running();
            try {
                if (leds != null) {
                    leds.close();
                }
                leds = new java.io.RandomAccessFile(Guest.ledsFile(this), "r");
            } catch (java.io.IOException e) {
                leds = null;
            }
        }
        if (leds == null) {
            return;
        }
        try {
            leds.seek(0);
            int b = leds.read();
            boolean off = b >= 0 && (b & Guest.SCROLL_LOCK_LED) != 0;
            if (off) {
                displayWasOff = true;
            } else if (displayWasOff) {
                coverArmed = true;          // iOS turns the display off once under the Apple logo
            }
            displayOff = off;
        } catch (java.io.IOException ignored) {
            // keep the last state
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lockedByPhone) {
            lockedByPhone = false;
            readLeds();
            // back from the phone's lock screen: wake iOS too (it locked with the phone: display off, or AOD)
            if (Guest.running() && (displayOff || Guest.aod26)) {
                Guest.aod26 = false;
                wake();
            }
        }
        immersive();
        Choreographer.getInstance().postFrameCallback(this);
        String log = Guest.ios26Running() ? Guest.overrides26().get("touchlog") : null;
        if ("1".equals(log) && touchLog == null) {
            try {
                touchLog = new java.io.PrintWriter(new java.io.FileWriter(new java.io.File(Guest.runDir(this), "touch.txt"), true));
            } catch (java.io.IOException ignored) {
                // no log
            }
        }
    }

    @Override
    protected void onPause() {
        keepThumb(true);
        Choreographer.getInstance().removeFrameCallback(this);
        liftFinger();
        Guest.releaseButtons();
        if (touchLog != null) {
            touchLog.close();
            touchLog = null;
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            immersive();
            showToggles();      // after Android's pin question, or its own unpin
        }
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            // Android 10 has no WindowInsetsController: the flags it replaced, for the same full screen with
            // bars that come back on a swipe
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            return;
        }
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    protected void onDestroy() {
        // the guest and its QMP connection stay: they belong to GuestService, not to this screen
        if (backCallback != null && android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) backCallback);
        }
        unregisterReceiver(phoneScreenOff);
        render.post(Render::detach);
        renderThread.quitSafely();
        super.onDestroy();
    }

    // -- the guest: open its framebuffer and QMP once it is up

    /**
     * @os's pending crash record (CrashReport.pendingFor), read again only when its notice or the newest record
     * changed: doFrame asks once a second while the guest is stopped.
     */
    private CrashReport.Record pendingCrash(int os) {
        String key = os + " " + CrashReport.pendingId(this, os) + " " + CrashReport.file(this, 0).lastModified();
        if (!key.equals(crashFor)) {
            crashFor = key;
            crash = CrashReport.pendingFor(this, os);
        }
        return crash;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        Choreographer.getInstance().postFrameCallback(this);
        long now = System.currentTimeMillis();
        if (closing && !Guest.running()) {
            finishAndRemoveTask();
            return;
        }
        // saving takes ~25 s with iOS paused: the whole screen says so, or it looks hung
        if (Guest.saving() || closing && Guest.running()) {
            long s = savingSince == 0 ? 0 : (now - savingSince) / 1000;
            if (savingSince == 0 || s != savingShown) {
                if (savingSince == 0) {
                    savingSince = now;
                }
                savingShown = s;
                boolean off = closingOff || Guest.turningOff();
                cover.setText(screenOffText(off ? "Turning iOS off" : "Saving iOS", s + " s"));
                cover.setVisibility(View.VISIBLE);
                status.setText("");
            }
            return;
        }
        if (savingSince != 0) {
            savingSince = 0;
            cover.setText(screenOffText("Screen off", "tap to wake"));
        }
        if (now - ledsRead >= 100) {
            ledsRead = now;
            readLeds();
            boolean show = displayOff && coverArmed && Guest.running();
            if (show != (cover.getVisibility() == View.VISIBLE)) {
                cover.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            long up = android.os.SystemClock.uptimeMillis();
            // For a minute after a load iOS's display is kept on: a save taken asleep loads asleep, and one
            // taken awake goes to sleep a few seconds after the load (iOS's Auto-Lock counts the time away).
            // A press right at "running" is lost (iOS is busy for a while after a load): presses from 5 s
            // after, 6 s apart, at most six; not after a side button press of the user's.
            if (wakeAfterLoad && "running".equals(Guest.state) && Guest.qmp() != null) {
                if (wakeAt == 0) {
                    wakeAt = up + 5_000;
                    wakeUntil = up + 60_000;
                    wakePowerMs = Guest.lastPowerMs;
                } else if (up >= wakeUntil || wakeTries >= 6
                        || Guest.lastPowerMs != wakePowerMs && up - Guest.lastPowerMs < 10_000) {
                    AppLog.note(this, "wake after a load: display " + (displayOff ? "off" : "on") + " after "
                            + wakeTries + " press(es)");
                    wakeAfterLoad = false;
                } else if (displayOff && up >= wakeAt) {
                    wake();
                    wakePowerMs = Guest.lastPowerMs;    // iOS 16's wake is the side button: ours, not the user's
                    wakeTries++;
                    wakeAt = up + 6_000;
                }
            }
            if (displayOff) {
                Guest.aod26 = false;        // the side button in AOD turns the display off
            }
        }
        if (Qmp.noSpace && Guest.running()) {
            // a disk write found the phone's storage full and QEMU paused the guest (it froze with no word before)
            if (now - lastTry > 1000) {
                lastTry = now;
                status.setText(String.format(java.util.Locale.ROOT, "The phone's storage is full (%.1f GB free): %s is"
                        + " paused, nothing is lost. Free some space, then tap Continue.",
                        getFilesDir().getUsableSpace() / 1e9, Guest.osName(Guest.os(this))));
                restart.setText("Continue");
                restart.setVisibility(View.VISIBLE);
                report.setVisibility(View.GONE);
            }
        } else if ("resuming".equals(Guest.state) || "switching".equals(Guest.state)) {
            status.setText("Loading the saved " + Guest.osName(Guest.os(this)) + "…");
        } else if ("preparing".equals(Guest.state)) {
            // a start's own step before QEMU runs (Guest.fitScreen26): not "stopped", no Start again button
            String what = Guest.preparing;
            status.setText(what == null ? "starting" : what);
            restart.setVisibility(View.GONE);
            report.setVisibility(View.GONE);
        } else if (!closing && status.getText().length() > 0 && fbPid == Guest.pid(this)
                && ("running".equals(Guest.state) || "booting".equals(Guest.state))) {
            status.setText("");
        }
        if (!Guest.running() && !"switching".equals(Guest.state) && !"preparing".equals(Guest.state)) {
            if (now - lastTry > 1000) {
                lastTry = now;
                String name = Guest.osName(Guest.os(this));
                restart.setText("Start " + name + " again");
                String err = Guest.startError;
                if (err != null) {
                    status.setText(name + " did not start: " + err);
                    restart.setVisibility(View.VISIBLE);
                    report.setVisibility(View.GONE);
                    fbPid = -1;
                    return;
                }
                String why = Guest.stopReason(this);
                // QEMU ended by itself: its record (written a moment after the end) says why, and can be reported
                CrashReport.Record cr = pendingCrash(Guest.os(this));
                if (cr != null) {
                    // whole (CrashText.cause keeps 600 characters): the pixel format comes at the end of reims' line
                    String cause = cr.cause;
                    status.setText(name + (cr.crash ? " crashed: " : " stopped by itself: ")
                            + (!why.isEmpty() && !why.equals(cr.cause) ? why + "\n" : "") + cause);
                } else {
                    status.setText(name + " stopped" + (why.isEmpty() ? "" : ": " + why));
                }
                restart.setVisibility(View.VISIBLE);
                report.setVisibility(cr != null ? View.VISIBLE : View.GONE);
                if (Guest.endedInCompatMode()) {
                    compatSince = now;
                }
                // one dialog, not two: the crash's report says the compatibility mode's sentence too. Its record
                // comes a moment after the end (CrashReport.captureAsync); without one in a few seconds, the notice alone
                if (compatSince > 0 && (cr != null || now - compatSince > COMPAT_WAIT_MS)) {
                    compatSince = 0;
                    if (cr != null) {
                        AppLog.note(this, "gpu: " + name + " stopped in the compatibility mode: crash report shown");
                        CrashReport.show(this, cr, COMPAT_TEXT);
                    } else {
                        compatNotice(name);
                    }
                }
                if (fbPid != -1) {
                    render.post(Render::clear);     // not the stopped guest's last frame
                }
                fbPid = -1;
            }
            return;
        }
        int pid = Guest.pid(this);
        if (fbPid != pid && now - lastTry > 1000) {
            lastTry = now;
            String err = Guest.openScreen(pid);
            fbPid = err == null ? pid : -1;
            status.setText(err == null ? "" : "starting: " + err);
            restart.setVisibility(View.GONE);
            report.setVisibility(View.GONE);
        }
        if (fbPid == pid && attached) {
            int w = surfW, h = surfH;
            render.post(() -> Render.frame(w, h));
            if ("running".equals(Guest.state) || "booting".equals(Guest.state)) {
                keepThumb(false);       // (a boot's black screen is not kept)
            }
        }
    }

    // -- the surface

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        render.post(() -> attached = Render.attach(h.getSurface()));
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
        surfW = w;
        surfH = hgt;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        attached = false;
        // the surface goes when this returns: GLES must have let go of it by then
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        render.post(() -> {
            Render.detach();
            done.countDown();
        });
        try {
            done.await(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // -- input

    /** The phone's volume keys are the iPhone's (Guest.button). */
    private static String volumeKey(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_DOWN ? "voldown" : code == KeyEvent.KEYCODE_VOLUME_UP ? "volup" : null;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        String volume = volumeKey(code);
        logKey(e);
        if (volume != null && watchMode) {
            return true;        // the crown turned on the key's downs
        }
        if (volume != null && Guest.qmp() != null) {
            Guest.button(volume, false);
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void logKey(KeyEvent e) {
        if (touchLog != null) {
            touchLog.println(System.nanoTime() + " key " + (e.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up") + " "
                    + KeyEvent.keyCodeToString(e.getKeyCode()) + " t=" + e.getEventTime() + " repeat=" + e.getRepeatCount()
                    + " flags=0x" + Integer.toHexString(e.getFlags()));
            touchLog.flush();
        }
    }

    /** Where the guest screen is inside the view (aspect kept, centred). */
    private float[] guestPoint(float x, float y) {
        float vw = view.getWidth(), vh = view.getHeight();
        float g = (float) Guest.fbW() / Guest.fbH();
        float w = vw, h = vh;
        if (vw / vh > g) {
            w = vh * g;
        } else {
            h = vw / g;
        }
        return new float[] { (x - (vw - w) / 2) / w, (y - (vh - h) / 2) / h };
    }

    /**
     * One finger at a time goes to iOS (its touchscreen is single-touch): the first one down, followed
     * by its pointer id. Before, every event's first pointer went: with a second finger (two thumbs, a
     * finger beside the one that draws) its lift never reached iOS and the touch jumped to the other
     * finger, so iOS drew one line on into the next touch. When iOS's finger lifts while another stays,
     * that one goes down as a new touch; another finger down beside it is not seen.
     */
    private boolean onTouch(View v, MotionEvent e) {
        if (touchLocked) {
            return true;        // the bar's touch lock: not even an input for iOS's idle time
        }
        Guest.input();
        Qmp qmp = Guest.qmp();
        if (qmp == null) {
            finger = -1;
            return true;
        }
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && Guest.aod26 && Guest.ios26Running()) {
            // iOS's touchscreen is off in its AOD: this touch only wakes it (Home), as an iPhone's tap does
            Guest.aod26 = false;
            swallowTouch = true;
            Guest.button("home");
            AppLog.note(this, "aod: a touch woke iOS (Home)");
        }
        if (swallowTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                swallowTouch = false;
            }
            logTouch(e, action);
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (finger != -1) {
                    sendTouch(qmp, false, "up before a new down");      // its up never came
                }
                fingerDown(qmp, e, 0);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (finger == -1) {
                    fingerDown(qmp, e, e.getActionIndex());
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                int i = finger == -1 ? -1 : e.findPointerIndex(finger);
                if (i >= 0) {
                    float[] p = guestPoint(e.getX(i), e.getY(i));
                    if (p[0] != fingerX || p[1] != fingerY) {
                        fingerX = p[0];
                        fingerY = p[1];
                        sendTouch(qmp, null, null);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                if (e.getPointerId(i) == finger) {
                    float[] p = guestPoint(e.getX(i), e.getY(i));
                    fingerX = p[0];
                    fingerY = p[1];
                    sendTouch(qmp, false, null);
                    finger = -1;
                    for (int k = 0; k < e.getPointerCount(); k++) {
                        if (k != i) {
                            fingerDown(qmp, e, k);
                            break;
                        }
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
                if (finger != -1) {
                    int i = e.findPointerIndex(finger);
                    if (i >= 0) {
                        float[] p = guestPoint(e.getX(i), e.getY(i));
                        fingerX = p[0];
                        fingerY = p[1];
                    }
                    sendTouch(qmp, false, null);
                    finger = -1;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (finger != -1) {
                    sendTouch(qmp, false, "cancel");
                    finger = -1;
                }
                break;
            default:
                break;
        }
        logTouch(e, action);
        return true;
    }

    private void fingerDown(Qmp qmp, MotionEvent e, int index) {
        finger = e.getPointerId(index);
        float[] p = guestPoint(e.getX(index), e.getY(index));
        fingerX = p[0];
        fingerY = p[1];
        sendTouch(qmp, true, null);
    }

    private void sendTouch(Qmp qmp, Boolean button, String why) {
        qmp.touch(fingerX, fingerY, button);
        if (touchLog != null) {
            touchLog.printf(java.util.Locale.ROOT, "%d sent %s %.4f %.4f%s%n", System.nanoTime(),
                    button == null ? "move" : button ? "down" : "up", fingerX, fingerY, why == null ? "" : " (" + why + ")");
        }
    }

    /** A finger that is down when the screen goes (another app, the phone's lock) lifts in iOS too. */
    private void liftFinger() {
        Qmp qmp = Guest.qmp();
        if (finger != -1 && qmp != null) {
            sendTouch(qmp, false, "screen left");
        }
        finger = -1;
    }

    private void logTouch(MotionEvent e, int action) {
        if (touchLog == null) {
            return;
        }
        StringBuilder s = new StringBuilder();
        s.append(System.nanoTime()).append(" event ").append(MotionEvent.actionToString(action))
                .append(" t=").append(e.getEventTime()).append(" n=").append(e.getPointerCount());
        for (int k = 0; k < e.getPointerCount(); k++) {
            s.append(" [").append(e.getPointerId(k)).append(' ').append((int) e.getX(k)).append(',')
                    .append((int) e.getY(k)).append(']');
        }
        s.append(" hist=").append(e.getHistorySize()).append(" finger=").append(finger);
        touchLog.println(s);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchLog.flush();
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        String volume = volumeKey(code);
        logKey(e);
        if (volume != null && watchMode && watchCase != null) {
            // the watch has no volume buttons: the phone's turn the crown, and keep turning while held (key repeats)
            if (Guest.qmp() != null) {
                watchCase.turn(volume.equals("volup") ? -WatchCase.CROWN_PER_NOTCH : WatchCase.CROWN_PER_NOTCH);
            }
            return true;
        }
        if (volume != null && Guest.qmp() != null) {
            // down here, up in onKeyUp: holding is iOS's to see; repeats and the release must
            // not reach Android, or the phone's own volume moves too
            if (e.getRepeatCount() == 0) {
                Guest.button(volume, true);
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }
}
