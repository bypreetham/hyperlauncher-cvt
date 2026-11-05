package com.hyperlauncher.control;

import android.app.Activity;
import com.gathin.tvhome.BuildConfig;
import com.gathin.tvhome.Icons;
import com.gathin.tvhome.Ui;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * HyperLauncher's Control Center: system monitor, app manager, debloater and home/theme shortcuts.
 * Built in code and driven entirely by the D-pad. Everything that needs shell rights goes through
 * {@link AdbClient} (this TV's own adbd on localhost); without it the screen still lists and launches apps.
 */
public final class ControlCenterActivity extends Activity {
    private static final int BG = Ui.BG, PANEL = Ui.GLASS, TEXT = Ui.TEXT, DIM = Ui.DIM, ACCENT = Ui.ACCENT, DANGER = Ui.DANGER;
    /** Packages whose loss can stop Android booting, break Wi-Fi/ADB or lock the TV. Never offered, never run. */
    private static final Pattern PROTECTED = Pattern.compile(
            "^(android|com\\.android\\.(providers\\..*|systemui|shell|phone|settings|tv\\.settings|permissioncontroller"
                    + "|packageinstaller|inputmethod\\..*|inputdevices|bluetooth|tv|wifi.*|networkstack.*|vpndialogs|"
                    + "externalstorage|documentsui|keychain|certinstaller|cellbroadcast.*)"
                    + "|com\\.google\\.android\\.(gms|gsf|permissioncontroller|packageinstaller|ext\\..*|overlay\\..*"
                    + "|modulemetadata|vending|tungsten\\.setupwraith|tv\\.dial|inputmethod\\..*)"
                    + "|com\\.hyperlauncher\\.tv|com\\.gathin\\.tvhome|com\\.cvte\\.tv\\.hkcfac|mediatek\\.factorymenu\\.ui)$");
    /** False in the Lite edition: no ADB client is built in and the page set shrinks to what Android allows an app. */
    private static final boolean ADB = BuildConfig.ADB;
    private static final String PREFS = "control_center";
    private static final String KEY_DISABLED = "disabled_by_us";
    private static final String KEY_UNINSTALLED = "uninstalled_by_us";
    private static final String KEY_COMPONENTS = "components_disabled_by_us";
    /** namespace:key settings the ad tools touch; their original values are saved before the first change. */
    private static final String[] SETTING_KEYS = {"secure:limit_ad_tracking", "global:ad_personalization",
            "global:private_dns_mode", "global:private_dns_specifier"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** The monitor loop never returns, so it gets its own thread; sharing the worker starved connect and every pm call. */
    private final ExecutorService poller = Executors.newSingleThreadExecutor();
    private AdbClient adb;
    private SharedPreferences prefs;
    private FrameLayout content;
    private TextView status;
    private View statusDot;
    private android.widget.ProgressBar busyBar;
    private RailItem[] tabs;
    private int tab = -1;
    private volatile boolean monitoring;
    private long[] lastCpu;

    /** One entry of the side rail: an icon and a name, lit when its page is open. */
    private final class RailItem extends LinearLayout {
        private final String kind;
        private final android.widget.ImageView icon;
        private final TextView label;

        RailItem(String name, String kind, View.OnClickListener click) {
            super(ControlCenterActivity.this);
            this.kind = kind;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setClickable(true);
            setPadding(dp(16), dp(12), dp(16), dp(12));
            icon = new android.widget.ImageView(getContext());
            addView(icon, new LayoutParams(dp(24), dp(24)));
            label = text(name, 17, TEXT, false);
            label.setPadding(dp(14), 0, 0, 0);
            addView(label);
            setOnClickListener(click);
            setActive(false);
        }

        void setActive(boolean on) {
            int color = on ? ACCENT : TEXT;
            icon.setImageDrawable(Icons.of(kind, color));
            label.setTextColor(color);
            label.setTypeface(on ? Ui.medium(getContext()) : Ui.regular(getContext()));
            GradientDrawable focused = new GradientDrawable();
            focused.setColor(Ui.GLASS_FOCUS);
            focused.setCornerRadius(dp(14));
            focused.setStroke(dp(2), Ui.RIM_FOCUS);
            GradientDrawable resting = new GradientDrawable();
            resting.setColor(on ? 0x1A8CCBFF : 0);
            resting.setCornerRadius(dp(14));
            android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
            s.addState(new int[]{android.R.attr.state_focused}, focused);
            s.addState(new int[]{}, resting);
            setBackground(s);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        adb = AdbClient.get(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(40), dp(28), dp(48), dp(24));

        // Side rail: D-pad left from any page lands here, right goes back into the page.
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(12), dp(16), dp(12), dp(12));
        rail.setBackground(Ui.panel(this, 22));
        TextView brand = text("HYPERLAUNCHER", 12, DIM, false);
        brand.setPadding(dp(16), 0, 0, 0);
        rail.addView(brand);
        TextView title = text("Control Center", 22, TEXT, true);
        title.setPadding(dp(16), dp(2), 0, dp(18));
        rail.addView(title);
        String[] names = {"Monitor", "Apps", "Debloat", "Home & Theme", "Settings", "Overview"};
        String[] kinds = {Icons.MONITOR, Icons.APPS, Icons.DEBLOAT, Icons.HOME, Icons.SETTINGS, Icons.DASH};
        tabs = new RailItem[names.length];
        // Logical tab ids: Monitor 0, Apps 1, Debloat 2, Home & Theme 3, Settings 4, Overview 5; this is the order on screen.
        for (int index : new int[]{5, 3, 1, 2, 0, 4}) {
            if (!ADB && index == 2) continue;
            tabs[index] = new RailItem(names[index], kinds[index], v -> show(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, dp(4));
            rail.addView(tabs[index], lp);
        }
        rail.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
        if (ADB) rail.addView(new RailItem("ADB connect", Icons.PLUG, v -> connectAdb(true)), new LinearLayout.LayoutParams(-1, -2));
        root.addView(rail, new LinearLayout.LayoutParams(dp(250), -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(28), 0, 0, 0);

        // Status strip: a coloured dot and the latest message; the thin bar moves while a command runs.
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(18), dp(10), dp(18), dp(10));
        strip.setBackground(Ui.panel(this, 14));
        statusDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Ui.RIM_FOCUS);
        statusDot.setBackground(dot);
        strip.addView(statusDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        status = text("", 14, DIM, false);
        status.setPadding(dp(12), 0, 0, 0);
        status.setMaxLines(2);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        strip.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        column.addView(strip, new LinearLayout.LayoutParams(-1, -2));
        busyBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busyBar.setIndeterminate(true);
        busyBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        busyBar.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(4));
        blp.setMargins(dp(8), dp(4), dp(8), dp(10));
        column.addView(busyBar, blp);

        content = new FrameLayout(this);
        column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(column, new LinearLayout.LayoutParams(0, -1, 1f));
        setContentView(root);
        setStatus("Ready", DIM);
        tabs[5].requestFocus();
        show(5);
        if (ADB) connectAdb(false);
        else setStatus("Lite edition: no ADB and no network access", ACCENT);
        runTestHook(getIntent());
    }

    @Override
    protected void onDestroy() {
        monitoring = false;
        worker.shutdownNow();
        poller.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        monitoring = false;
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tab == 0 || tab == 5) startMonitor();
    }

    @Override
    public void onBackPressed() {
        if (panelOpen && closePanel != null) closePanel.run();
        else super.onBackPressed();
    }

    // ---- ADB ----

    private void connectAdb(boolean prompt) {
        setStatus("ADB: connecting to 127.0.0.1:5555 ... approve the dialog on the TV if it appears (tick Always allow)", DIM);
        worker.execute(() -> {
            try {
                adb.connect();
                String who = adb.shell("id").trim().replaceAll("\\s+groups=.*", "");
                main.post(() -> {
                    setStatus("ADB connected  ·  " + who, ACCENT);
                    if (dashAdb != null) dashAdb.setText("Connected");
                });
            } catch (IOException e) {
                main.post(() -> {
                    setStatus("ADB not connected: " + e.getMessage()
                            + "  ·  Settings > Developer options > turn on USB debugging and Network/ADB debugging, then press ADB connect", DANGER);
                    if (prompt) new AlertDialog.Builder(this)
                            .setTitle("ADB is not reachable")
                            .setMessage("Monitoring details, uninstall and debloat need the TV's own ADB.\n\n"
                                    + "1. Settings > Device Preferences > About: tap Build 7 times.\n"
                                    + "2. Developer options: enable USB debugging and Network debugging "
                                    + "(or run 'adb tcpip 5555' once from a PC).\n"
                                    + "3. Come back and press ADB connect, then accept the TV dialog.\n\n"
                                    + "Android 11+ 'Wireless debugging' uses TLS pairing on a random port, which is not supported yet.")
                            .setPositiveButton("OK", null).show();
                });
            }
        });
    }

    /** Runs a shell command on the worker and hands the result (or the error text) to the main thread. */
    private void shell(String command, java.util.function.Consumer<String> done) {
        worker.execute(() -> {
            String out;
            try {
                if (command.contains("pm disable") || command.contains("pm uninstall") || command.contains("settings put"))
                    ensureSnapshot();
                out = adb.shell(command);
            } catch (IOException e) {
                out = "ERROR: " + e.getMessage();
            }
            final String result = out;
            main.post(() -> {
                if (result.startsWith("ERROR") && !adb.isConnected()) needAdbDialog();
                done.accept(result);
            });
        });
    }

    // ---- no ADB (Developer options off): say so, and offer the way that works without it ----

    private boolean adbDialogShowing;

    /** True when the shell link is up; otherwise explains what to switch on and returns false. */
    private boolean requireAdb() {
        if (adb.isConnected()) return true;
        needAdbDialog();
        return false;
    }

    private void needAdbDialog() {
        if (!ADB) {
            setStatus("Not available in the Lite edition", DIM);
            return;
        }
        if (adbDialogShowing || isFinishing() || isDestroyed()) return;
        adbDialogShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("This needs ADB (Developer options)")
                .setMessage("The monitor details, disabling and uninstalling system apps, debloat and the ad tools run "
                        + "shell commands on this TV, which Android only allows through ADB.\n\n"
                        + "1. Settings > Device Preferences > About: press Build 7 times.\n"
                        + "2. Developer options: turn on USB debugging and Network debugging.\n"
                        + "3. Come back and press Retry, then accept the TV's prompt.\n\n"
                        + "Changes stick after you turn Developer options off again. Everything else here "
                        + "(app list, opening apps, App info, the launcher's own settings) works without it.")
                .setPositiveButton("Open Developer options", (d, w) -> openDeveloperOptions())
                .setNeutralButton("Retry", (d, w) -> connectAdb(true))
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> adbDialogShowing = false)
                .show();
    }

    private void openDeveloperOptions() {
        for (String action : new String[]{Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS,
                Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new Intent(action));
                return;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void setStatus(String s, int color) {
        status.setText(s);
        status.setTextColor(color == DIM ? TEXT : color);
        ((GradientDrawable) statusDot.getBackground()).setColor(color == DIM ? Ui.RIM_FOCUS : color);
        busyBar.setVisibility(color == DIM && s.contains("...") ? View.VISIBLE : View.INVISIBLE);
    }

    // ---- tabs ----

    private void show(int index) {
        monitoring = false;
        tab = index;
        for (int i = 0; i < tabs.length; i++) if (tabs[i] != null) tabs[i].setActive(i == index);
        content.removeAllViews();
        cpuValue = null;
        memRows = null;
        cpuSpark = ramSpark = null;
        dashCpu = dashRam = dashStorage = null;
        dashAdb = null;
        panelOpen = false;
        switch (index) {
            case 5: showDashboard(); break;
            case 0: showMonitor(); break;
            case 1: showApps(); break;
            case 2: showDebloat(); break;
            case 4: showLauncherSettings(); break;
            default: showHome(); break;
        }
    }

    // ---- 1. Monitor ----

    private TextView cpuValue, cpuSub, ramValue, ramSub, storageValue, storageSub, procText;
    private Bar cpuBar, ramBar, storageBar;

    /** A thin rounded progress bar. */
    private final class Bar extends View {
        private float fraction;
        private int color = Ui.ACCENT;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Bar(Context c) {
            super(c);
        }

        void set(float f, int c) {
            fraction = Math.max(0f, Math.min(1f, f));
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float r = getHeight() / 2f;
            paint.setColor(0x26FFFFFF);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, paint);
            if (fraction > 0f) {
                paint.setColor(color);
                canvas.drawRoundRect(0, 0, Math.max(getHeight(), getWidth() * fraction), getHeight(), r, r, paint);
            }
        }
    }

    private static final int HISTORY = 40;
    private final java.util.ArrayList<Float> cpuHist = new java.util.ArrayList<>(), ramHist = new java.util.ArrayList<>();
    private Spark cpuSpark, ramSpark;

    /** A rolling line chart of the last readings (0..1), filled underneath. */
    private final class Spark extends View {
        private java.util.List<Float> data = new java.util.ArrayList<>();
        private int color = Ui.ACCENT;
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Spark(Context c) {
            super(c);
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            line.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        void set(java.util.List<Float> values, int c) {
            data = new java.util.ArrayList<>(values);
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            int w = getWidth(), h = getHeight();
            fill.setColor(0x14FFFFFF);
            canvas.drawRoundRect(0, 0, w, h, dp(8), dp(8), fill);
            if (data.size() < 2) return;
            float pad = dp(4), step = (w - 2 * pad) / (HISTORY - 1f);
            float x0 = w - pad - step * (data.size() - 1);
            android.graphics.Path p = new android.graphics.Path();
            for (int i = 0; i < data.size(); i++) {
                float x = x0 + step * i, y = h - pad - (h - 2 * pad) * Math.max(0f, Math.min(1f, data.get(i)));
                if (i == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
            }
            android.graphics.Path area = new android.graphics.Path(p);
            area.lineTo(x0 + step * (data.size() - 1), h - pad);
            area.lineTo(x0, h - pad);
            area.close();
            fill.setColor((color & 0x00FFFFFF) | 0x33000000);
            canvas.drawPath(area, fill);
            line.setColor(color);
            canvas.drawPath(p, line);
        }
    }

    /** A circular gauge with its value in the middle. */
    private final class Ring extends View {
        private float fraction;
        private int color = Ui.ACCENT;
        private String value = "...";
        private final android.graphics.Paint arc = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint label = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Ring(Context c) {
            super(c);
            arc.setStyle(android.graphics.Paint.Style.STROKE);
            arc.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            arc.setStrokeWidth(dp(10));
            label.setTextAlign(android.graphics.Paint.Align.CENTER);
            label.setTypeface(Ui.medium(c));
            label.setColor(TEXT);
            label.setTextSize(dp(24));
        }

        void set(float f, int c, String text) {
            fraction = Math.max(0f, Math.min(1f, f));
            color = c;
            value = text;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float inset = dp(10);
            android.graphics.RectF box = new android.graphics.RectF(inset, inset, getWidth() - inset, getHeight() - inset);
            arc.setColor(0x26FFFFFF);
            canvas.drawArc(box, 135, 270, false, arc);
            if (fraction > 0f) {
                arc.setColor(color);
                canvas.drawArc(box, 135, 270 * fraction, false, arc);
            }
            canvas.drawText(value, getWidth() / 2f, getHeight() / 2f + dp(8), label);
        }
    }

    /** One metric: a small title, a large value, a line of detail and a bar (or a rolling chart). Views come back through out[]. */
    private LinearLayout metricCard(String title, TextView[] out, Bar[] bar, Spark[] spark) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(18), dp(22), dp(18));
        card.setBackground(Ui.panel(this, 18));
        card.addView(text(title, 13, DIM, false));
        out[0] = text("...", 34, TEXT, true);
        card.addView(out[0]);
        out[1] = text(" ", 13, DIM, false);
        out[1].setPadding(0, 0, 0, dp(10));
        card.addView(out[1]);
        if (spark != null) {
            spark[0] = new Spark(this);
            card.addView(spark[0], new LinearLayout.LayoutParams(-1, dp(64)));
        } else {
            bar[0] = new Bar(this);
            card.addView(bar[0], new LinearLayout.LayoutParams(-1, dp(8)));
        }
        return card;
    }

    private void keepHistory(java.util.ArrayList<Float> list, float v) {
        list.add(v);
        while (list.size() > HISTORY) list.remove(0);
    }

    // ---- 0. Overview ----

    private Ring dashCpu, dashRam, dashStorage;
    private TextView dashCpuSub, dashRamSub, dashStorageSub, dashAdb;

    /** A tappable tile on the Overview page. */
    private LinearLayout tile(String title, View top, TextView sub, View.OnClickListener go) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(Ui.glass(this, 20));
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(go);
        TextView t = text(title, 13, DIM, false);
        card.addView(t);
        top.setPadding(0, dp(6), 0, dp(6));
        card.addView(top);
        card.addView(sub);
        return card;
    }

    private void showDashboard() {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setScrollBarSize(dp(6));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, 0, dp(14), dp(10));
        page.addView(text("Overview", 28, TEXT, true));
        TextView lead = text("Live readings. Press OK on a tile to open its page.", 14, DIM, false);
        lead.setPadding(0, 0, 0, dp(14));
        page.addView(lead);

        LinearLayout gauges = new LinearLayout(this);
        gauges.setOrientation(LinearLayout.HORIZONTAL);
        dashCpu = new Ring(this);
        dashRam = new Ring(this);
        dashStorage = new Ring(this);
        dashCpuSub = text("measuring ...", 13, DIM, false);
        dashRamSub = text(" ", 13, DIM, false);
        dashStorageSub = text(" ", 13, DIM, false);
        Ring[] rings = {dashCpu, dashRam, dashStorage};
        TextView[] subs = {dashCpuSub, dashRamSub, dashStorageSub};
        String[] titles = {"CPU", "MEMORY", "STORAGE"};
        for (int i = 0; i < 3; i++) {
            subs[i].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(0, 0, dp(14), dp(14));
            LinearLayout card = tile(titles[i], rings[i], subs[i], v -> show(0));
            ((LinearLayout) card).removeView(rings[i]);
            card.addView(rings[i], 1, new LinearLayout.LayoutParams(dp(130), dp(130)));
            gauges.addView(card, lp);
        }
        page.addView(gauges);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        dashAdb = text(adb.isConnected() ? "Connected" : "Not connected", 22, TEXT, true);
        TextView adbSub = text(adb.isConnected() ? "Network debugging is on" : (settingOn("adb_enabled")
                ? "Debugging is on but not connected: press to retry" : "Network debugging is off: press for the steps"), 12, DIM, false);
        adbSub.setGravity(Gravity.CENTER);
        int disabled = recorded(KEY_DISABLED).size();
        TextView off = text(String.valueOf(disabled), 22, TEXT, true);
        TextView offSub = text("packages disabled by HyperLauncher", 12, DIM, false);
        offSub.setGravity(Gravity.CENTER);
        boolean ours = currentHome().startsWith(getPackageName() + "/");
        TextView home = text(ours ? "HyperLauncher" : "Stock launcher", 19, TEXT, true);
        TextView homeSub = text("is the TV's Home screen", 12, DIM, false);
        homeSub.setGravity(Gravity.CENTER);
        View[] tops = {dashAdb, off, home};
        TextView[] infoSubs = {adbSub, offSub, homeSub};
        String[] infoTitles = {"ADB LINK", "DEBLOAT", "HOME"};
        View.OnClickListener[] go = {v -> { if (adb.isConnected()) show(4); else connectAdb(true); }, v -> show(2), v -> show(4)};
        if (!ADB) {
            int userApps = 0;
            for (PackageInfo pi : getPackageManager().getInstalledPackages(0))
                if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) userApps++;
            TextView apps = text(String.valueOf(userApps), 22, TEXT, true);
            TextView appsSub = text("apps installed by you", 12, DIM, false);
            appsSub.setGravity(Gravity.CENTER);
            TextView edition = text("Lite", 22, TEXT, true);
            TextView editionSub = text("No ADB, no network access", 12, DIM, false);
            editionSub.setGravity(Gravity.CENTER);
            tops[0] = apps;
            infoSubs[0] = appsSub;
            infoTitles[0] = "APPS";
            go[0] = v -> show(1);
            tops[1] = edition;
            infoSubs[1] = editionSub;
            infoTitles[1] = "EDITION";
            go[1] = v -> show(4);
            dashAdb = null;
        }
        for (int i = 0; i < 3; i++) {
            ((TextView) tops[i]).setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(0, 0, dp(14), 0);
            info.addView(tile(infoTitles[i], tops[i], infoSubs[i], go[i]), lp);
        }
        page.addView(info);
        scroll.addView(page);
        content.addView(scroll);
        lastCpu = null;
        paintHistory();
        startMonitor();
    }

    private void paintHistory() {
        if (cpuSpark != null) cpuSpark.set(cpuHist, Ui.ACCENT);
        if (ramSpark != null) ramSpark.set(ramHist, Ui.ACCENT);
    }

    private void showMonitor() {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setScrollBarSize(dp(6));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] v = new TextView[2];
        Bar[] bar = new Bar[1];
        Spark[] spark = new Spark[1];
        LinearLayout cpu = metricCard("CPU  ·  last minute", v, bar, spark);
        cpuValue = v[0];
        cpuSub = v[1];
        cpuSpark = spark[0];
        cpuBar = null;
        LinearLayout ram = metricCard("MEMORY  ·  last minute", v, bar, spark);
        ramValue = v[0];
        ramSub = v[1];
        ramSpark = spark[0];
        ramBar = null;
        LinearLayout storage = metricCard("STORAGE", v, bar, null);
        storageValue = v[0];
        storageSub = v[1];
        storageBar = bar[0];
        for (LinearLayout c : new LinearLayout[]{cpu, ram, storage}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(0, 0, dp(14), 0);
            cards.addView(c, lp);
        }
        page.addView(cards);

        LinearLayout memTop = new LinearLayout(this);
        memTop.setOrientation(LinearLayout.VERTICAL);
        memTop.setPadding(dp(22), dp(16), dp(22), dp(16));
        memTop.setBackground(Ui.panel(this, 18));
        memTop.addView(text("TOP 5 APPS BY MEMORY", 13, DIM, false));
        memRows = new LinearLayout(this);
        memRows.setOrientation(LinearLayout.VERTICAL);
        memRows.setPadding(0, dp(8), 0, 0);
        memRows.addView(text("Reading ...", 14, DIM, false));
        memTop.addView(memRows);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.setMargins(0, dp(14), dp(14), 0);
        page.addView(memTop, mlp);

        LinearLayout procs = new LinearLayout(this);
        procs.setOrientation(LinearLayout.VERTICAL);
        procs.setPadding(dp(22), dp(16), dp(22), dp(16));
        procs.setBackground(Ui.panel(this, 18));
        procs.addView(text("TOP PROCESSES", 13, DIM, false));
        procText = text("Reading ...", 14, TEXT, false);
        procText.setTypeface(Typeface.MONOSPACE);
        procText.setLineSpacing(0, 1.12f);
        procText.setPadding(0, dp(8), 0, 0);
        procs.addView(procText);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.setMargins(0, dp(14), dp(14), 0);
        page.addView(procs, plp);
        scroll.addView(page);
        content.addView(scroll);
        lastCpu = null;
        paintHistory();
        startMonitor();
    }

    private LinearLayout memRows;

    private static final class Report {
        java.util.List<String[]> memTop = new ArrayList<>();
        boolean shellOk;
        double cpu = -1;
        String cpuNote = "";
        int cores;
        String load = "", temp = "";
        long ramUsed, ramTotal, ramFree;
        boolean lowMemory;
        long storageUsed, storageTotal;
        String procs = "";
    }

    private void startMonitor() {
        if (monitoring) return;
        monitoring = true;
        poller.execute(() -> {
            while (monitoring) {
                final Report report = buildReport();
                main.post(() -> {
                    if (monitoring && (cpuValue != null || dashCpu != null)) showReport(report);
                });
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    private void showReport(Report r) {
        String cpuText = r.cpu >= 0 ? String.format("%.0f %%", r.cpu) : (r.shellOk ? "..." : (ADB ? "ADB" : "n/a"));
        String detail = r.cores + " cores";
        if (!r.cpuNote.isEmpty()) detail = r.cpuNote;
        else if (!r.load.isEmpty()) detail += "  ·  load " + r.load;
        if (!r.temp.isEmpty()) detail += "  ·  " + r.temp;
        int ramPct = (int) (100 * r.ramUsed / Math.max(1, r.ramTotal));
        String ramDetail = mb(r.ramUsed) + " of " + mb(r.ramTotal) + (r.lowMemory ? "  ·  LOW" : "  ·  " + mb(r.ramFree) + " free");
        int stPct = (int) (100 * r.storageUsed / Math.max(1, r.storageTotal));
        String stDetail = mb(r.storageUsed) + " of " + mb(r.storageTotal) + "  ·  " + mb(r.storageTotal - r.storageUsed) + " free";
        int cpuColor = r.cpu > 85 ? Ui.DANGER : Ui.ACCENT;
        int ramColor = r.lowMemory || ramPct > 90 ? Ui.DANGER : Ui.ACCENT;
        int stColor = stPct > 92 ? Ui.DANGER : Ui.ACCENT;

        if (r.cpu >= 0) keepHistory(cpuHist, (float) (r.cpu / 100.0));
        keepHistory(ramHist, ramPct / 100f);

        if (dashCpu != null) {
            dashCpu.set(r.cpu >= 0 ? (float) (r.cpu / 100.0) : 0f, cpuColor, cpuText);
            dashCpuSub.setText(detail);
            dashRam.set(ramPct / 100f, ramColor, ramPct + " %");
            dashRamSub.setText(ramDetail);
            dashStorage.set(stPct / 100f, stColor, stPct + " %");
            dashStorageSub.setText(stDetail);
        }
        if (cpuValue != null) {
            cpuValue.setText(cpuText);
            cpuSub.setText(detail);
            ramValue.setText(ramPct + " %");
            ramSub.setText(ramDetail);
            storageValue.setText(stPct + " %");
            storageSub.setText(stDetail);
            storageBar.set(stPct / 100f, stColor);
            cpuSpark.set(cpuHist, cpuColor);
            ramSpark.set(ramHist, ramColor);
            procText.setText(r.procs);
            if (memRows != null) {
                memRows.removeAllViews();
                if (r.memTop.isEmpty()) {
                    memRows.addView(text(r.shellOk ? "Not available on this firmware." : (ADB ? "Needs ADB: press 'ADB connect'." : "Not available in the Lite edition."), 14, DIM, false));
                } else {
                    long max = 1;
                    for (String[] m : r.memTop) max = Math.max(max, Long.parseLong(m[0]));
                    for (String[] m : r.memTop) {
                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(0, dp(4), 0, dp(4));
                        TextView name = text(m[1], 14, TEXT, false);
                        name.setSingleLine();
                        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                        row.addView(name, new LinearLayout.LayoutParams(0, -2, 5f));
                        Bar b = new Bar(this);
                        b.set(Long.parseLong(m[0]) / (float) max, Ui.ACCENT);
                        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(6), 3f);
                        blp.setMargins(dp(12), 0, dp(12), 0);
                        row.addView(b, blp);
                        TextView size = text(mb(Long.parseLong(m[0]) * 1024), 14, DIM, false);
                        size.setGravity(Gravity.END);
                        row.addView(size, new LinearLayout.LayoutParams(0, -2, 1.4f));
                        memRows.addView(row);
                    }
                }
            }
        }
        paintHistory();
    }

    private Report buildReport() {
        Report r = new Report();
        r.shellOk = adb.isConnected();
        r.cores = Runtime.getRuntime().availableProcessors();
        long[] cpu = parseCpu(r.shellOk ? quiet("cat /proc/stat | head -1") : "");
        if (cpu != null && lastCpu != null) {
            long total = cpu[0] - lastCpu[0], idle = cpu[1] - lastCpu[1];
            if (total > 0) r.cpu = 100.0 * (total - idle) / total;
        } else if (!r.shellOk) {
            r.cpuNote = ADB ? "needs ADB: Android hides CPU use from apps" : "Android hides CPU use from apps";
        } else {
            r.cpuNote = "measuring ...";
        }
        if (cpu != null) lastCpu = cpu;
        if (r.shellOk) {
            String load = quiet("cat /proc/loadavg").trim();
            if (!load.isEmpty()) r.load = load.split("\\s+")[0];
            try {
                double c = Double.parseDouble(quiet("cat /sys/class/thermal/thermal_zone0/temp").trim());
                r.temp = String.format("%.0f °C", c > 1000 ? c / 1000 : c);
            } catch (NumberFormatException ignored) {
            }
        }

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        r.ramTotal = mi.totalMem;
        r.ramFree = mi.availMem;
        r.ramUsed = mi.totalMem - mi.availMem;
        r.lowMemory = mi.lowMemory;

        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        r.storageTotal = fs.getTotalBytes();
        r.storageUsed = r.storageTotal - fs.getAvailableBytes();

        if (r.shellOk) {
            StringBuilder sb = new StringBuilder();
            String top = quiet("top -b -n 1 -m 10 -s 2 -o PID,%CPU,%MEM,RES,ARGS");
            if (top.trim().isEmpty() || top.contains("Unknown option")) top = quiet("top -n 1 -m 10");
            for (String line : top.split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("Tasks:") || t.startsWith("Mem:") || t.startsWith("Swap:")
                        || t.contains("%cpu") || t.startsWith("top ")) continue;
                sb.append(line.length() > 96 ? line.substring(0, 96) : line).append('\n');
            }
            r.procs = sb.toString();
            for (String line : quiet("ps -A -o RSS,NAME --sort=-RSS | head -6").split("\n")) {
                String[] f = line.trim().split("\\s+", 2);
                if (f.length == 2 && f[0].matches("\\d+")) r.memTop.add(f);
            }
        } else {
            r.procs = ADB ? "Per-process use needs ADB: press 'ADB connect'." : "Per-process use is not available in the Lite edition.";
        }
        return r;
    }

    private String quiet(String cmd) {
        try {
            return adb.shell(cmd);
        } catch (IOException e) {
            return "";
        }
    }

    /** {total, idle} jiffies from the aggregate "cpu" line. */
    private static long[] parseCpu(String line) {
        String[] p = line.trim().split("\\s+");
        if (p.length < 5 || !p[0].equals("cpu")) return null;
        long total = 0;
        for (int i = 1; i < p.length; i++) {
            try {
                total += Long.parseLong(p[i]);
            } catch (NumberFormatException e) {
                break;
            }
        }
        return new long[]{total, Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0)};
    }

    private static String mb(long bytes) {
        return bytes >= 1L << 30 ? String.format("%.2f GB", bytes / (double) (1L << 30)) : (bytes >> 20) + " MB";
    }

    // ---- 2. Apps ----

    private boolean showSystem;

    private boolean panelOpen;
    private Runnable closePanel;

    private void showApps() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Button toggle = button(showSystem ? "Showing: all apps (press to show user apps)" : "Showing: user apps (press to show all)", null);
        toggle.setOnClickListener(v -> {
            showSystem = !showSystem;
            show(1);
            tabs[1].requestFocus();
        });
        box.addView(toggle);
        final List<PackageInfo> apps = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (PackageInfo p : pm.getInstalledPackages(0)) {
            boolean system = (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (showSystem || !system) apps.add(p);
        }
        Collections.sort(apps, (a, b) -> pm.getApplicationLabel(a.applicationInfo).toString()
                .compareToIgnoreCase(pm.getApplicationLabel(b.applicationInfo).toString()));

        android.widget.GridView grid = new android.widget.GridView(this);
        grid.setNumColumns(5);
        grid.setVerticalSpacing(dp(8));
        grid.setHorizontalSpacing(dp(8));
        grid.setSelector(Ui.glass(this, 16));
        grid.setScrollbarFadingEnabled(false);
        grid.setScrollBarSize(dp(6));
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(6), dp(10), dp(6));
        grid.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                PackageInfo p = apps.get(position);
                LinearLayout cell = new LinearLayout(ControlCenterActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(8), dp(12), dp(8), dp(10));
                android.widget.ImageView icon = new android.widget.ImageView(ControlCenterActivity.this);
                icon.setImageDrawable(p.applicationInfo.loadIcon(pm));
                cell.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
                TextView name = text(String.valueOf(pm.getApplicationLabel(p.applicationInfo)), 14, TEXT, false);
                name.setSingleLine();
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER);
                name.setPadding(0, dp(8), 0, 0);
                cell.addView(name, new LinearLayout.LayoutParams(-1, -2));
                boolean system = (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                String tag = !p.applicationInfo.enabled ? "Disabled" : (system ? "System" : "Yours");
                TextView t = text(tag, 11, p.applicationInfo.enabled ? DIM : DANGER, false);
                t.setGravity(Gravity.CENTER);
                cell.addView(t, new LinearLayout.LayoutParams(-1, -2));
                return cell;
            }
        });
        box.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView count = text(apps.size() + " apps  ·  press OK on an app for actions", 13, DIM, false);
        box.addView(count);
        row.addView(box, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(16));
        panel.setBackground(Ui.panel(this, 22));
        panel.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(330), -1);
        plp.setMargins(dp(16), 0, 0, 0);
        row.addView(panel, plp);
        closePanel = () -> {
            panel.setVisibility(View.GONE);
            panelOpen = false;
            grid.setNumColumns(5);
            grid.requestFocus();
        };
        grid.setOnItemClickListener((p, v, position, id) -> {
            fillAppPanel(panel, apps.get(position));
            panel.setVisibility(View.VISIBLE);
            grid.setNumColumns(3);
            grid.setSelection(position);
            panelOpen = true;
            if (panel.getChildCount() > 3) panel.getChildAt(3).requestFocus();
        });
        content.addView(row);
    }

    /** The side panel for one app: its icon and name, then what can be done to it. */
    private void fillAppPanel(LinearLayout panel, PackageInfo info) {
        panel.removeAllViews();
        final String pkg = info.packageName;
        PackageManager pm = getPackageManager();
        final CharSequence label = pm.getApplicationLabel(info.applicationInfo);
        boolean system = (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean locked = PROTECTED.matcher(pkg).matches();
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageDrawable(info.applicationInfo.loadIcon(pm));
        panel.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView name = text(String.valueOf(label), 20, TEXT, true);
        name.setPadding(0, dp(10), 0, 0);
        panel.addView(name);
        TextView id = text(pkg + (locked ? "\nProtected: cannot be disabled or removed here" : ""), 12, DIM, false);
        id.setPadding(0, 0, 0, dp(12));
        panel.addView(id);
        List<String> items = new ArrayList<>();
        items.add("Open");
        if (ADB) items.add("Force stop");
        items.add("App info");
        if (!locked) {
            if (ADB) items.add(info.applicationInfo.enabled ? "Disable" : "Enable");
            // Lite: only apps you installed, through Android's own uninstall dialog.
            if (ADB || !system) items.add(system ? "Uninstall for this user" : "Uninstall");
        }
        items.add("Close");
        for (String choice : items) {
            Button b = button(choice, v -> {
                if (choice.equals("Close")) closePanel.run();
                else runAppAction(info, choice);
            });
            if (choice.startsWith("Uninstall")) b.setTextColor(DANGER);
            b.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            ((LinearLayout.LayoutParams) b.getLayoutParams()).setMargins(0, 0, 0, dp(6));
            panel.addView(b);
        }
    }

    private void runAppAction(PackageInfo info, String choice) {
        final String pkg = info.packageName;
        final CharSequence label = getPackageManager().getApplicationLabel(info.applicationInfo);
        boolean system = (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        if (choice.equals("Open")) {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) startActivity(launch);
        } else if (choice.equals("Force stop")) {
            if (requireAdb()) shell("am force-stop " + pkg, r -> setStatus("Stopped " + pkg, ACCENT));
        } else if (choice.equals("App info")) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + pkg)));
        } else if (choice.equals("Disable")) {
            runPm("pm disable-user --user 0 " + pkg, pkg, KEY_DISABLED);
        } else if (choice.equals("Enable")) {
            runPm("pm enable " + pkg, pkg, null);
        } else {
            confirm("Uninstall " + label + "?",
                    system ? "This removes the system app for your user. Restoring it later needs "
                            + "'cmd package install-existing " + pkg + "'." : "This removes the app and its data.",
                    () -> {
                        if (!adb.isConnected() && !system) {
                            // An app you installed: Android's own uninstall dialog, no ADB needed.
                            try {
                                startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)));
                            } catch (RuntimeException e) {
                                needAdbDialog();
                            }
                        } else {
                            runPm("pm uninstall --user 0 " + pkg, pkg, KEY_UNINSTALLED);
                        }
                    });
        }
    }

    private void runPm(String command, String pkg, String rememberIn) {
        if (PROTECTED.matcher(pkg).matches()) {
            setStatus("Refused: " + pkg + " is protected", DANGER);
            return;
        }
        shell(command, r -> {
            boolean ok = r.toLowerCase().contains("success") || r.toLowerCase().contains("new state") || r.trim().isEmpty();
            if (ok && rememberIn != null) remember(rememberIn, pkg);
            if (ok && command.startsWith("pm enable")) forget(KEY_DISABLED, pkg);
            setStatus((ok ? "OK: " : "Failed: ") + command + (ok ? "" : "  ·  " + r.trim()), ok ? ACCENT : DANGER);
            if (tab == 1) show(1);
        });
    }

    // ---- 3. Debloat ----

    private final Set<String> picked = new HashSet<>();

    private static final class Entry {
        String pkg, label, cat;
        int count;
        boolean rec;
    }

    private static final String[] CATS = {"ADS", "TRACK", "BG", "BLOAT"};
    private static final String[] CAT_NAMES = {"Ads and recommendations", "Analytics and telemetry", "Background services", "Bloatware"};

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> collapsed = new HashSet<>();
    private final List<Object> rows = new ArrayList<>();
    private android.widget.BaseAdapter debloatAdapter;
    private TextView pickedCount;
    private Button disableBtn;

    private static String catKey(Entry e) {
        for (String c : CATS) if (c.equalsIgnoreCase(e.cat)) return c;
        return "BLOAT";
    }

    private void rebuildRows() {
        rows.clear();
        for (int i = 0; i < CATS.length; i++) {
            List<Entry> inCat = new ArrayList<>();
            for (Entry e : entries) if (catKey(e).equals(CATS[i])) inCat.add(e);
            if (inCat.isEmpty()) continue;
            rows.add(CATS[i]);
            if (!collapsed.contains(CATS[i])) rows.addAll(inCat);
        }
    }

    private void updatePicked() {
        pickedCount.setText(picked.isEmpty() ? "Nothing selected" : picked.size() + " selected");
        disableBtn.setAlpha(picked.isEmpty() ? 0.5f : 1f);
        if (debloatAdapter != null) debloatAdapter.notifyDataSetChanged();
    }

    private TextView chip(String s, int color) {
        TextView t = text(s, 11, color, true);
        GradientDrawable d = new GradientDrawable();
        d.setColor((color & 0x00FFFFFF) | 0x26000000);
        d.setCornerRadius(dp(8));
        t.setBackground(d);
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        return t;
    }

    private void showDebloat() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView hint = text("From the CleanYourTV catalog (number = TV models listing it). Safe = recommended. "
                + "Disable is reversible. OK on a heading folds it.", 13, DIM, false);
        hint.setPadding(0, 0, 0, dp(6));
        box.addView(hint);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("Select recommended", v -> selectInList(debloatRecommended)));
        actions.addView(button("Select all", v -> selectInList(new HashSet<>(debloatPackages))));
        actions.addView(button("Clear", v -> selectInList(new HashSet<>())));
        Button enableAll = button("Re-enable everything I disabled", v -> { if (requireAdb())
                confirm("Re-enable all?", "Turns back on every package this screen disabled.", this::reenableAll); });
        Button restoreUninstalled = button("Restore uninstalled apps", v -> {
            if (!requireAdb()) return;
            setStatus("Reinstalling ...", DIM);
            worker.execute(() -> {
                final int n = reinstallUninstalled();
                main.post(() -> setStatus("Reinstalled " + n + " app(s)", ACCENT));
            });
        });
        Button undoAds = button("Undo ad changes", v -> { if (requireAdb()) worker.execute(() -> {
            final int n = undoAdChanges();
            main.post(() -> setStatus("Undid " + n + " ad/DNS item(s)", ACCENT));
        }); });
        Button restoreAll = button("RESTORE EVERYTHING", v -> { if (requireAdb()) confirm("Restore everything?",
                "Re-enables disabled packages, reinstalls uninstalled ones and puts ad/DNS settings back as they were.",
                this::restoreEverything); });
        Button ads = button("Ad-block DNS on", v -> setDns(true));
        Button adsOff = button("DNS off", v -> setDns(false));
        Button gms = button("Quiet Google ad services", v -> { if (requireAdb()) quietGmsAds(); });
        actions.addView(enableAll);
        actions.addView(restoreUninstalled);
        actions.addView(undoAds);
        actions.addView(restoreAll);
        actions.addView(gms);
        actions.addView(ads);
        actions.addView(adsOff);
        HorizontalScrollerHolder.wrap(this, box, actions);

        entries.clear();
        loadCatalog(entries);
        debloatPackages = new ArrayList<>();
        debloatRecommended = new HashSet<>();
        for (Entry e : entries) {
            debloatPackages.add(e.pkg);
            if (e.rec) debloatRecommended.add(e.pkg);
        }
        picked.retainAll(debloatPackages);
        rebuildRows();

        ListView list = new ListView(this);
        list.setSelector(Ui.glass(this, 14));
        list.setDivider(null);
        list.setScrollbarFadingEnabled(false);
        list.setScrollBarSize(dp(6));
        debloatAdapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int i) { return rows.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                Object o = rows.get(position);
                LinearLayout row = new LinearLayout(ControlCenterActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setLayoutParams(new ListView.LayoutParams(-1, -2));
                android.widget.ImageView lead = new android.widget.ImageView(ControlCenterActivity.this);
                if (o instanceof String) {
                    String cat = (String) o;
                    int n = 0, sel = 0;
                    for (Entry e : entries) if (catKey(e).equals(cat)) {
                        n++;
                        if (picked.contains(e.pkg)) sel++;
                    }
                    row.setPadding(dp(14), dp(14), dp(14), dp(8));
                    lead.setImageDrawable(Icons.of(collapsed.contains(cat) ? Icons.CHEVRON : Icons.CHEVRON_DOWN, ACCENT));
                    row.addView(lead, new LinearLayout.LayoutParams(dp(20), dp(20)));
                    String name = CAT_NAMES[java.util.Arrays.asList(CATS).indexOf(cat)];
                    TextView head = text(name, 17, ACCENT, true);
                    head.setPadding(dp(10), 0, 0, 0);
                    row.addView(head, new LinearLayout.LayoutParams(0, -2, 1f));
                    row.addView(text(n + " found" + (sel > 0 ? "  ·  " + sel + " selected" : ""), 13, DIM, false));
                    return row;
                }
                Entry e = (Entry) o;
                boolean on = picked.contains(e.pkg);
                row.setPadding(dp(26), dp(10), dp(14), dp(10));
                GradientDrawable boxBg = new GradientDrawable();
                boxBg.setCornerRadius(dp(6));
                boxBg.setColor(on ? ACCENT : 0x14FFFFFF);
                boxBg.setStroke(dp(2), on ? ACCENT : Ui.RIM);
                lead.setBackground(boxBg);
                lead.setPadding(dp(3), dp(3), dp(3), dp(3));
                if (on) lead.setImageDrawable(Icons.of(Icons.CHECK, BG));
                row.addView(lead, new LinearLayout.LayoutParams(dp(24), dp(24)));
                LinearLayout names = new LinearLayout(ControlCenterActivity.this);
                names.setOrientation(LinearLayout.VERTICAL);
                names.setPadding(dp(14), 0, dp(10), 0);
                TextView n = text(e.label, 16, TEXT, false);
                n.setSingleLine();
                n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                names.addView(n);
                TextView p = text(e.pkg, 12, DIM, false);
                p.setSingleLine();
                p.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                names.addView(p);
                row.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
                row.addView(chip(e.count + "/380 TVs", Ui.DIM));
                TextView risk = chip(e.rec ? "Safe" : "Check first", e.rec ? 0xFF7DDC9A : 0xFFFFC27A);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.setMargins(dp(8), 0, 0, 0);
                row.addView(risk, rlp);
                return row;
            }
        };
        list.setAdapter(debloatAdapter);
        list.setOnItemClickListener((p, v, position, id) -> {
            Object o = rows.get(position);
            if (o instanceof String) {
                if (!collapsed.remove(o)) collapsed.add((String) o);
                rebuildRows();
                debloatAdapter.notifyDataSetChanged();
            } else {
                String pkg = ((Entry) o).pkg;
                if (!picked.remove(pkg)) picked.add(pkg);
                updatePicked();
            }
        });
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Sticky bar: always visible whatever the list is scrolled to.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(18), dp(8), dp(10), dp(8));
        bottom.setBackground(Ui.panel(this, 16));
        pickedCount = text("", 16, TEXT, true);
        bottom.addView(pickedCount, new LinearLayout.LayoutParams(0, -2, 1f));
        disableBtn = button("Disable selected", v -> {
            if (!requireAdb()) return;
            if (picked.isEmpty()) {
                setStatus("Nothing selected", DIM);
                return;
            }
            confirm("Disable " + picked.size() + " packages?",
                    "Each runs 'pm disable-user --user 0'. Voice assistant, streaming and live-TV apps are on this list "
                            + "too: untick anything your household uses.", this::disablePicked);
        });
        disableBtn.setTextColor(DANGER);
        bottom.addView(disableBtn);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, dp(8), dp(14), 0);
        box.addView(bottom, bp);
        updatePicked();
        content.addView(box);
    }

    private List<String> debloatPackages = new ArrayList<>();
    private Set<String> debloatRecommended = new HashSet<>();

    /** Ticks exactly {@code which} (and mirrors it in {@link #picked}), leaving the rest unticked. */
    private void selectInList(Set<String> which) {
        picked.clear();
        for (String pkg : debloatPackages) if (which.contains(pkg)) picked.add(pkg);
        updatePicked();
        setStatus(picked.size() + " selected", ACCENT);
    }

    private void loadCatalog(List<Entry> out) {
        PackageManager pm = getPackageManager();
        Set<String> installed = new HashSet<>();
        for (PackageInfo p : pm.getInstalledPackages(0)) if (p.applicationInfo.enabled) installed.add(p.packageName);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("debloat_catalog.txt")))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#") || !line.contains("\t")) continue;
                String[] f = line.split("\t");
                if (!installed.contains(f[0]) || PROTECTED.matcher(f[0]).matches()) continue;
                Entry e = new Entry();
                e.pkg = f[0];
                try {
                    e.label = pm.getApplicationLabel(pm.getApplicationInfo(f[0], 0)).toString();
                } catch (PackageManager.NameNotFoundException ex) {
                    e.label = f[0];
                }
                try {
                    e.count = Integer.parseInt(f[1].trim());
                } catch (NumberFormatException ex) {
                    e.count = 0;
                }
                e.cat = f.length > 2 ? f[2].trim() : "";
                e.rec = f.length > 3 && f[3].equals("1");
                out.add(e);
            }
        } catch (IOException e) {
            setStatus("Catalog missing: " + e.getMessage(), DANGER);
        }
    }

    private void disablePicked() {
        final List<String> todo = new ArrayList<>(picked);
        worker.execute(() -> {
            int ok = 0, failed = 0;
            ensureSnapshot();
            for (String pkg : todo) {
                if (PROTECTED.matcher(pkg).matches()) continue;
                String r;
                try {
                    r = adb.shell("pm disable-user --user 0 " + pkg).toLowerCase();
                } catch (IOException e) {
                    r = "error " + e.getMessage();
                }
                if (r.contains("new state")) {
                    ok++;
                    remember(KEY_DISABLED, pkg);
                    picked.remove(pkg);
                } else {
                    failed++;
                }
                final int o = ok, f = failed;
                main.post(() -> setStatus("Disabling ... " + o + " done, " + f + " refused", DIM));
            }
            final int o = ok, f = failed;
            main.post(() -> {
                setStatus("Disabled " + o + ", refused " + f + (f > 0 ? " (no ADB, or this firmware blocks it)" : ""), f > 0 ? DANGER : ACCENT);
                show(2);
            });
        });
    }

    private void reenableAll() {
        worker.execute(() -> {
            final int ok = reenablePackages();
            main.post(() -> {
                setStatus("Re-enabled " + ok, ACCENT);
                show(2);
            });
        });
    }

    /** GMS ad/analytics services. Component-level disables normally need root; refusals are reported, not hidden. */
    private static final String G = "com.google.android.gms/";
    private static final String[] GMS_COMPONENTS = {
            G + "com.google.android.gms.ads.AdRequestBrokerService",
            G + "com.google.android.gms.ads.identifier.service.AdvertisingIdService",
            G + "com.google.android.gms.ads.measurement.GmpConversionTrackingBrokerService",
            G + "com.google.android.gms.analytics.AnalyticsService",
            G + "com.google.android.gms.analytics.service.AnalyticsService",
            G + "com.google.android.gms.measurement.AppMeasurementService",
    };

    private void quietGmsAds() {
        confirm("Quiet Google ad services?",
                "Opts out of ad personalisation and tries to disable Play Services ad/analytics components. "
                        + "Play Services itself stays installed (the Play Store and Google sign-in depend on it). "
                        + "Component disables often need root and may be refused by your firmware.",
                () -> worker.execute(this::runQuietGmsAds));
    }

    /** Blocking; the confirmed button path and the debug test hook both run this. */
    private void runQuietGmsAds() {
        final String[] components = GMS_COMPONENTS;
        {
            {
                {
                    int ok = 0, refused = 0;
                    ensureSnapshot();
                    try {
                        adb.shell("settings put secure limit_ad_tracking 1");
                        adb.shell("settings put global ad_personalization 0");
                    } catch (IOException ignored) {
                    }
                    for (String c : components) {
                        try {
                            String r = adb.shell("pm disable " + c).toLowerCase();
                            if (r.contains("new state")) {
                                ok++;
                                remember(KEY_COMPONENTS, c);
                            } else {
                                refused++;
                            }
                        } catch (IOException e) {
                            refused++;
                        }
                    }
                    final int o = ok, f = refused;
                    main.post(() -> setStatus("Ad opt-out set. GMS ad components disabled: " + o + ", refused: " + f
                            + (f > 0 ? "  (needs root; Ad-block DNS covers most of it)" : ""), f > 0 ? DIM : ACCENT));
                }
            }
        }
    }

    // ---- debug test hook: adb shell am start -n .../.ControlCenterActivity --es test <action> [--es arg <pkg>] ----

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        runTestHook(intent);
    }

    /** Debuggable builds only. Runs the app's real code without dialogs and logs one line per step to tag HyperTest. */
    private void runTestHook(Intent intent) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        final String test = intent.getStringExtra("test");
        if (test == null) return;
        final String arg = intent.getStringExtra("arg");
        worker.execute(() -> {
            String res;
            try {
                adb.connect();
                switch (test) {
                    case "quiet_ads": runQuietGmsAds(); res = "ran"; break;
                    case "dns_on":
                        ensureSnapshot();
                        adb.shell("settings put global private_dns_specifier dns.adguard-dns.com");
                        adb.shell("settings put global private_dns_mode hostname");
                        res = "dns on"; break;
                    case "disable":
                        ensureSnapshot();
                        res = adb.shell("pm disable-user --user 0 " + arg).trim();
                        if (res.toLowerCase().contains("new state")) remember(KEY_DISABLED, arg);
                        break;
                    case "undo_ads": res = "undone=" + undoAdChanges(); break;
                    case "restore_all":
                        res = "enabled=" + reenablePackages() + " reinstalled=" + reinstallUninstalled() + " ads=" + undoAdChanges();
                        break;
                    case "state":
                        res = "disabled=" + recorded(KEY_DISABLED) + " uninstalled=" + recorded(KEY_UNINSTALLED)
                                + " components=" + recorded(KEY_COMPONENTS);
                        break;
                    default: res = "unknown test";
                }
            } catch (IOException e) {
                res = "ERROR " + e.getMessage();
            }
            android.util.Log.i("HyperTest", test + ": " + res);
        });
    }

    private void setDns(boolean on) {
        String cmd = on
                ? "settings put global private_dns_specifier dns.adguard-dns.com; settings put global private_dns_mode hostname"
                : "settings put global private_dns_mode opportunistic";
        shell(cmd, r -> setStatus(r.startsWith("ERROR") ? r : on ? "Private DNS set to AdGuard (blocks ad domains system-wide)"
                : "Private DNS back to automatic", r.startsWith("ERROR") ? DANGER : ACCENT));
    }

    private void remember(String key, String value) {
        Set<String> s = new HashSet<>(prefs.getStringSet(key, new HashSet<>()));
        s.add(value);
        prefs.edit().putStringSet(key, s).apply();
    }

    private void forget(String key, String value) {
        Set<String> s = new HashSet<>(prefs.getStringSet(key, new HashSet<>()));
        s.remove(value);
        prefs.edit().putStringSet(key, s).apply();
    }

    private Set<String> recorded(String key) {
        return new HashSet<>(prefs.getStringSet(key, new HashSet<>()));
    }

    /**
     * Once, before the first change: writes the package list, disabled list and ad/DNS settings to
     * files/backup/ and keeps the original setting values so "Undo ad changes" can put them back.
     */
    private synchronized void ensureSnapshot() {
        if (prefs.getBoolean("snapshot_done", false)) return;
        try {
            StringBuilder sb = new StringBuilder("# packages\n").append(adb.shell("pm list packages -f"))
                    .append("\n# disabled\n").append(adb.shell("pm list packages -d")).append("\n# settings\n");
            SharedPreferences.Editor e = prefs.edit();
            for (String k : SETTING_KEYS) {
                String[] ns = k.split(":");
                String v = adb.shell("settings get " + ns[0] + " " + ns[1]).trim();
                sb.append(k).append('=').append(v).append('\n');
                e.putString("orig_" + k, v);
            }
            java.io.File dir = new java.io.File(getFilesDir(), "backup");
            dir.mkdirs();
            try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "snapshot-" + System.currentTimeMillis() + ".txt"))) {
                w.write(sb.toString());
            }
            e.putBoolean("snapshot_done", true).apply();
        } catch (IOException ignored) {
            // no ADB: the mutating command that follows fails too, so nothing changes without a snapshot
        }
    }

    // ---- reverts (blocking; call on the worker) ----

    private int reenablePackages() {
        int ok = 0;
        for (String pkg : recorded(KEY_DISABLED)) {
            try {
                if (adb.shell("pm enable " + pkg).toLowerCase().contains("new state")) {
                    ok++;
                    forget(KEY_DISABLED, pkg);
                }
            } catch (IOException ignored) {
            }
        }
        return ok;
    }

    private int reinstallUninstalled() {
        int ok = 0;
        for (String pkg : recorded(KEY_UNINSTALLED)) {
            try {
                String r = adb.shell("cmd package install-existing --user 0 " + pkg).toLowerCase();
                if (r.contains("installed for user")) {
                    ok++;
                    forget(KEY_UNINSTALLED, pkg);
                }
            } catch (IOException ignored) {
            }
        }
        return ok;
    }

    /** Re-enables GMS components and puts the ad/DNS settings back to the values saved in the snapshot. */
    private int undoAdChanges() {
        int ok = 0;
        for (String c : recorded(KEY_COMPONENTS)) {
            try {
                if (adb.shell("pm enable " + c).toLowerCase().contains("new state")) {
                    ok++;
                    forget(KEY_COMPONENTS, c);
                }
            } catch (IOException ignored) {
            }
        }
        for (String k : SETTING_KEYS) {
            String orig = prefs.getString("orig_" + k, null);
            if (orig == null) continue;
            String[] ns = k.split(":");
            try {
                adb.shell(orig.equals("null") ? "settings delete " + ns[0] + " " + ns[1]
                        : "settings put " + ns[0] + " " + ns[1] + " " + orig);
                ok++;
            } catch (IOException ignored) {
            }
        }
        return ok;
    }

    private void restoreEverything() {
        setStatus("Restoring ...", DIM);
        worker.execute(() -> {
            final int a = reenablePackages(), b = reinstallUninstalled(), c = undoAdChanges();
            main.post(() -> {
                setStatus("Restored: " + a + " re-enabled, " + b + " reinstalled, " + c + " ad/DNS items undone", ACCENT);
                show(tab);
            });
        });
    }

    // ---- 5. Settings: start with the TV ----

    private static final String HOME_COMPONENT = "com.hyperlauncher.tv/com.gathin.tvhome.MainActivity";
    /** The stock launchers it is safe to switch off once HyperLauncher is Home (never anything else). */
    private static final Set<String> STOCK_PACKAGES = new HashSet<>(java.util.Arrays.asList(
            "com.google.android.tvlauncher", "com.google.android.leanbacklauncher", "com.google.android.apps.tv.launcherx"));

    /** The component the Home button and the boot sequence open right now, "pkg/class", or "" when none is chosen. */
    private String currentHome() {
        android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null ? "" : ri.activityInfo.packageName + "/" + ri.activityInfo.name;
    }

    private boolean settingOn(String key) {
        try {
            return Settings.Global.getInt(getContentResolver(), key, 0) == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Network debugging at a glance. Android gives apps no way to switch it ON; it can be switched OFF through the ADB link. */
    private View debuggingCard() {
        final boolean linked = adb.isConnected();
        final boolean enabled = settingOn("adb_enabled");
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(18), dp(22), dp(14));
        card.setBackground(Ui.panel(this, 18));
        card.addView(text("NETWORK DEBUGGING", 13, DIM, false));
        TextView state = text(linked ? "ON  ·  connected" : (enabled ? "ON  ·  not connected" : "OFF"), 26, linked ? ACCENT : TEXT, true);
        state.setPadding(0, dp(2), 0, dp(2));
        card.addView(state);
        TextView why = text(linked
                ? "Needed for monitor details, disable and uninstall. You can turn it off when you are done: packages you disabled stay disabled."
                : "Needed for monitor details, disable and uninstall. Android does not let an app switch it on, so it is turned on in Developer options.",
                13, DIM, false);
        why.setPadding(0, 0, 0, dp(10));
        card.addView(why);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (linked) {
            Button off = button("Turn off network debugging", v -> confirm("Turn off network debugging?",
                    "The Control Center loses its ADB link until you switch debugging on again in Developer options "
                            + "(Settings > Device Preferences > Developer options). Your changes stay in place.",
                    () -> shell("settings put global adb_enabled 0", r -> {
                        setStatus(r.startsWith("ERROR") ? "Could not turn it off: " + r : "Network debugging turned off", r.startsWith("ERROR") ? DANGER : ACCENT);
                        main.postDelayed(() -> { if (tab == 4) show(4); }, 1200);
                    })));
            off.setTextColor(DANGER);
            row.addView(off);
        } else {
            row.addView(button("How to turn it on", v -> needAdbDialog()));
            row.addView(button("Open Developer options", v -> openDeveloperOptions()));
        }
        row.addView(button("Reconnect", v -> {
            connectAdb(true);
            main.postDelayed(() -> { if (tab == 4) show(4); }, 2500);
        }));
        HorizontalScrollerHolder.wrap(this, card, row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, dp(14), dp(14));
        card.setLayoutParams(lp);
        return card;
    }

    private void showLauncherSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (ADB) box.addView(debuggingCard());
        final boolean on = currentHome().startsWith(getPackageName() + "/");
        box.addView(button("Start HyperLauncher on start: " + (on ? "ON" : "OFF"), v -> {
            if (!adb.isConnected()) {
                new AlertDialog.Builder(this).setTitle(ADB ? "Needs ADB, or choose Home yourself" : "Choose Home in the TV's settings")
                        .setMessage((ADB ? "Making HyperLauncher the Home and switching off the stock launcher needs ADB "
                                + "(Developer options). Without it, pick" : "This edition has no ADB, so pick")
                                + " HyperLauncher as the default home in the TV's own "
                                + "settings; the round Home button may still open the stock launcher on some TVs.")
                        .setPositiveButton("Open home settings", (d, w) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                            } catch (RuntimeException e) {
                                openDeveloperOptions();
                            }
                        })
                        .setNeutralButton(ADB ? "How to enable ADB" : null, (d, w) -> needAdbDialog())
                        .setNegativeButton("Close", null).show();
                return;
            }
            if (on) confirm("Stop starting HyperLauncher?",
                    "The Home button and TV start go back to the previous home screen.", () -> setLauncherHome(false));
            else confirm("Start HyperLauncher on start?",
                    "HyperLauncher becomes the TV's Home: it opens when the TV starts and the round Home button "
                            + "stays in it instead of opening the stock launcher. The stock launcher stays installed.",
                    () -> setLauncherHome(true));
        }));
        TextView note = text("Current home: " + currentHome()
                        + " | Needs ADB connected. Undo any time here, or with 'Revert to the system home screen' in Home & Theme.",
                13, DIM, false);
        note.setPadding(0, dp(12), 0, 0);
        box.addView(note);
        content.addView(scrolled(box));
    }

    private void setLauncherHome(boolean enable) {
        setStatus("Changing the home screen ...", DIM);
        worker.execute(() -> {
            String result;
            try {
                final String previous = prefs.getString("previous_home", "com.google.android.tvlauncher/.MainActivity");
                if (enable) {
                    String now = currentHome();
                    if (!now.isEmpty() && !now.startsWith(getPackageName() + "/")) prefs.edit().putString("previous_home", now).apply();
                    ensureSnapshot();
                    String r = adb.shell("cmd package set-home-activity --user 0 " + HOME_COMPONENT).trim();
                    // Start HyperLauncher before the stock home is touched, so the TV is never left without one.
                    adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n " + HOME_COMPONENT);
                    Thread.sleep(1500);
                    // Some firmware (Xiaomi) sends the Home button to its own launcher whatever the default is:
                    // switch that known stock launcher off. It is only disabled, and "Restore everything" re-enables it.
                    String stockPkg = previous.split("/")[0];
                    if (STOCK_PACKAGES.contains(stockPkg) && currentHome().startsWith(stockPkg + "/")) {
                        String d = adb.shell("pm disable-user --user 0 " + stockPkg).toLowerCase();
                        if (d.contains("new state")) remember(KEY_DISABLED, stockPkg);
                    } else if (STOCK_PACKAGES.contains(stockPkg)) {
                        String d = adb.shell("pm disable-user --user 0 " + stockPkg).toLowerCase();
                        if (d.contains("new state")) remember(KEY_DISABLED, stockPkg);
                    }
                    result = r.isEmpty() ? "ok" : r;
                } else {
                    String stockPkg = previous.split("/")[0];
                    adb.shell("pm enable " + stockPkg);
                    forget(KEY_DISABLED, stockPkg);
                    String r = adb.shell("cmd package set-home-activity --user 0 " + previous).trim();
                    result = r.isEmpty() ? "ok" : r;
                }
            } catch (IOException | InterruptedException e) {
                result = "ERROR " + e.getMessage();
            }
            final String message = result;
            main.postDelayed(() -> {
                boolean nowOurs = currentHome().startsWith(getPackageName() + "/");
                boolean ok = nowOurs == enable && !message.startsWith("ERROR");
                setStatus(ok ? (enable ? "HyperLauncher is now the TV's Home" : "Previous home screen restored")
                        : "Not changed: " + message, ok ? ACCENT : DANGER);
                if (tab == 4) show(4);
            }, 800);
        });
    }

    // ---- 4. Home & theme ----

    private void showHome() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(button("Customize Launcher", v ->
                startActivity(new Intent().setClassName(getPackageName(), "com.gathin.tvhome.LayoutSettingsActivity"))));
        box.addView(button("Go to HyperLauncher home", v ->
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        box.addView(button("Revert to the system (stock) home screen", v -> confirm("Revert to the stock home?",
                "Re-enables the stock launcher and makes it Home again. HyperLauncher stays installed; "
                        + "come back to it from the app list any time.", this::revertToStock)));
        if (ADB) box.addView(button("Restore everything HyperLauncher changed", v -> { if (requireAdb()) confirm("Restore everything?",
                "Re-enables disabled packages, reinstalls uninstalled ones and puts ad/DNS settings back as they were.",
                this::restoreEverything); }));
        box.addView(button("TV system settings", v -> startActivity(new Intent(Settings.ACTION_SETTINGS))));
        TextView note = text("Revert tries the common stock launchers (Google TV / Android TV / Fire TV / vendor) that are installed on this TV.",
                13, DIM, false);
        note.setPadding(0, dp(12), 0, 0);
        box.addView(note);
        content.addView(scrolled(box));
    }

    private static final String[][] STOCK = {
            {"com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity"},
            {"com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity"},
            {"com.google.android.leanbacklauncher", "com.google.android.leanbacklauncher.MainActivity"},
            {"com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.HomeActivity_vNext"},
    };

    private void revertToStock() {
        if (!adb.isConnected()) {
            setStatus("No ADB: opening the system home settings instead", DIM);
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (RuntimeException e) {
                needAdbDialog();
            }
            return;
        }
        worker.execute(() -> {
            String done = null;
            for (String[] s : STOCK) {
                try {
                    getPackageManager().getPackageInfo(s[0], PackageManager.MATCH_DISABLED_COMPONENTS);
                    adb.shell("pm enable " + s[0]);
                    String r = adb.shell("cmd package set-home-activity " + s[0] + "/" + s[1]);
                    if (!r.toLowerCase().contains("error") && !r.toLowerCase().contains("exception")) {
                        done = s[0];
                        break;
                    }
                } catch (PackageManager.NameNotFoundException | IOException ignored) {
                }
            }
            final String d = done;
            main.post(() -> {
                if (d != null) {
                    setStatus("Home is now " + d, ACCENT);
                    startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                } else {
                    setStatus("No stock launcher found or ADB is off. Opening the system home chooser.", DANGER);
                    startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                }
            });
        });
    }

    // ---- view helpers ----

    /** A page that can scroll, with its bar always showing. */
    private ScrollView scrolled(View page) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(true);
        sv.setScrollbarFadingEnabled(false);
        sv.setScrollBarSize(dp(6));
        sv.addView(page);
        return sv;
    }

    private void confirm(String title, String message, Runnable yes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Yes", (d, w) -> yes.run()).setNegativeButton("Cancel", null).show();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        return Ui.text(this, s, sp, color, bold);
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(15);
        b.setTypeface(Ui.medium(this));
        b.setFocusable(true);
        b.setBackground(focusRing());
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(10), dp(6));
        b.setLayoutParams(lp);
        if (click != null) b.setOnClickListener(click);
        return b;
    }

    private android.graphics.drawable.StateListDrawable focusRing() {
        return Ui.glass(this, 12);
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(10));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Puts a button row into a horizontal scroller so a long row never pushes buttons off a 1080p screen. */
    private static final class HorizontalScrollerHolder {
        static void wrap(Activity a, LinearLayout parent, LinearLayout row) {
            android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(a);
            h.setHorizontalScrollBarEnabled(false);
            h.addView(row);
            parent.addView(h);
        }
    }
}
