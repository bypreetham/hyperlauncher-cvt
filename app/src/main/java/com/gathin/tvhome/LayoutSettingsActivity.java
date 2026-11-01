package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Layout choices for Home: apps per row, the space under each row and the space under the top bar. Left and
 * Right change a value (OK steps up and wraps). The built-in spacing is the minimum of both gaps; the picker only
 * adds room. Home rebuilds with the new values when this screen closes (Config.SETTINGS_REVISION_KEY).
 */
public final class LayoutSettingsActivity extends Activity {
    static final String ROW_GAP_KEY = "row_gap_dp", TOP_GAP_KEY = "top_gap_dp", COLOR_EFFECT_KEY = "color_effect", STATIC_SCROLL_KEY = "static_scroll",
            GREETING_KEY = "show_greeting", DATE_FORMAT_KEY = "date_format", BACKGROUND_KEY = "background";
    static final String DATE_LONG = "long", DATE_SHORT = "short";
    static final String STYLE_KEY = "home_style", FOCUS_KEY = "focus_effect", ACCENT_KEY = "accent_mode",
            PACK_KEY = "style_pack";
    private static final String[] FOCUS_IDS = {"standard", "rich"};
    private static final String[] FOCUS_NAMES = {"Standard", "Rich"};
    private static final String[] ACCENT_IDS = {"off", "app", "wallpaper"};
    private static final String[] ACCENT_NAMES = {"Off", "App colour", "Wallpaper"};
    private static final String[] PACK_NAMES = {"None", "Midnight", "Sunset", "Aurora", "Ocean"};
    private static final String[] PACK_WALLPAPERS = {"", "16-glass-navy.jpg", "02-golden-coast.jpg", "15-aurora-glow.jpg",
            "07-mirror-lake.jpg"};
    private static final String[] STYLE_IDS = {"default", "glass", "cinematic", "retro"};
    private static final String[] STYLE_NAMES = {"Default", "Glass", "Cinematic", "Retro"};
    static final String WALLPAPER_MODE_KEY = "wallpaper_mode", MODE_AUTO = "auto", MODE_COLOUR = "colour", MODE_CUSTOM = "custom";
    private static final String[] MODE_IDS = {MODE_AUTO, MODE_COLOUR, MODE_CUSTOM};
    private static final String[] MODE_NAMES = {"Auto", "Colours", "Custom wallpaper"};
    private static final String[] DATE_NAMES = {"SATURDAY 31 OCTOBER", "SAT 31st OCT 2026"};
    private static final String[] BACKGROUND_IDS = {"blue", "grey", "green", "orange"};
    private static final String[] BACKGROUND_NAMES = {"Blue", "Grey", "Green", "Orange"};
    private static final int[] BACKGROUND_COLORS = {0xFF2563EB, 0xFF8A8F98, 0xFF22A55B, 0xFFF08A24};

    /** The fixed Home glow colour for a Background choice; 0 (auto) follows the focused app. */
    static int backgroundColor(String id) {
        for (int i = 0; i < BACKGROUND_IDS.length; i++) if (BACKGROUND_IDS[i].equals(id)) return BACKGROUND_COLORS[i];
        return 0;
    }

    /** Wallpaper mode; an older "Background colour" choice counts as Colours. */
    static String wallpaperMode(SharedPreferences p) {
        String mode = p.getString(WALLPAPER_MODE_KEY, null);
        if (mode != null) return mode;
        return backgroundColor(p.getString(BACKGROUND_KEY, "auto")) != 0 ? MODE_COLOUR : MODE_AUTO;
    }

    /** The fixed glow colour when the wallpaper mode is Colours; 0 otherwise (auto follows the focused app). */
    static int wallpaperColor(SharedPreferences p) {
        if (!MODE_COLOUR.equals(wallpaperMode(p))) return 0;
        int c = backgroundColor(p.getString(BACKGROUND_KEY, "blue"));
        return c != 0 ? c : BACKGROUND_COLORS[0];
    }
    static final int COLUMNS_MIN = 3, COLUMNS_MAX = 8;
    static final int ROW_GAP_MAX = 48, ROW_GAP_STEP = 4;
    static final int TOP_GAP_MAX = 120, TOP_GAP_STEP = 8;

    private static final int BG = Ui.BG, PANEL = Ui.GLASS, TEXT = Ui.TEXT, DIM = Ui.DIM, ACCENT = Ui.ACCENT, DANGER = Ui.DANGER;

    static int rowGapDp(SharedPreferences p) {
        return Math.max(0, Math.min(ROW_GAP_MAX, p.getInt(ROW_GAP_KEY, 0)));
    }

    static int topGapDp(SharedPreferences p) {
        return Math.max(0, Math.min(TOP_GAP_MAX, p.getInt(TOP_GAP_KEY, 0)));
    }

    private SharedPreferences choices;
    private LinearLayout list;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(96), dp(40), dp(96), dp(24));
        root.addView(label("Customize Launcher", 28, TEXT, true));
        TextView sub = label("Left / Right changes a value. Minimum is the built-in look.", 14, DIM, false);
        sub.setPadding(0, 0, 0, dp(20));
        root.addView(sub);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);   // always visible, so a long page shows it can scroll
        scroll.setScrollBarSize(dp(6));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        build(0);
    }

    private int columns() {
        return Math.max(COLUMNS_MIN, Math.min(COLUMNS_MAX, Config.load(this).columns));
    }

    private void build(int focusRow) {
        list.removeAllViews();
        stepper("Apps per row", columns(), COLUMNS_MIN, COLUMNS_MAX, 1, "", "fewest", "most",
                v -> choices.edit().putInt("columns", v).apply());
        stepper("Space between rows", rowGapDp(choices), 0, ROW_GAP_MAX, ROW_GAP_STEP, " dp", "built-in", "max",
                v -> choices.edit().putInt(ROW_GAP_KEY, v).apply());
        stepper("Space under the top bar", topGapDp(choices), 0, TOP_GAP_MAX, TOP_GAP_STEP, " dp", "built-in", "max",
                v -> choices.edit().putInt(TOP_GAP_KEY, v).apply());
        Config now = Config.load(this);
        cycle("Style pack", PACK_NAMES, indexOf(PACK_NAMES, choices.getString(PACK_KEY, "None")), this::applyPack);
        cycle("Style", STYLE_NAMES, indexOf(STYLE_IDS, choices.getString(STYLE_KEY, "glass")),
                i -> choices.edit().putString(STYLE_KEY, STYLE_IDS[i]).apply());
        toggle("Animations", now.animations, true, v -> choices.edit().putBoolean("animations", v).apply());
        toggle("Colour effect", now.ambientGlow, true, v -> choices.edit().putBoolean(COLOR_EFFECT_KEY, v).apply());
        toggle("Greeting", now.showGreeting, true, v -> choices.edit().putBoolean(GREETING_KEY, v).apply());
        cycle("Focus effect", FOCUS_NAMES, indexOf(FOCUS_IDS, choices.getString(FOCUS_KEY, "rich")),
                i -> choices.edit().putString(FOCUS_KEY, FOCUS_IDS[i]).apply());
        cycle("Accent colour", ACCENT_NAMES, indexOf(ACCENT_IDS, choices.getString(ACCENT_KEY, "app")),
                i -> choices.edit().putString(ACCENT_KEY, ACCENT_IDS[i]).apply());
        cycle("Date format", DATE_NAMES, DATE_SHORT.equals(choices.getString(DATE_FORMAT_KEY, DATE_LONG)) ? 1 : 0,
                i -> choices.edit().putString(DATE_FORMAT_KEY, i == 1 ? DATE_SHORT : DATE_LONG).apply());
        final String mode = wallpaperMode(choices);
        cycle("Wallpaper", MODE_NAMES, indexOf(MODE_IDS, mode), i -> {
            SharedPreferences.Editor e = choices.edit().putString(WALLPAPER_MODE_KEY, MODE_IDS[i]);
            if (MODE_IDS[i].equals(MODE_COLOUR) && backgroundColor(choices.getString(BACKGROUND_KEY, "auto")) == 0)
                e.putString(BACKGROUND_KEY, "blue");
            e.apply();
        });
        if (MODE_COLOUR.equals(mode)) {
            cycle("Colour", BACKGROUND_NAMES, Math.max(0, indexOf(BACKGROUND_IDS, choices.getString(BACKGROUND_KEY, "blue"))),
                    i -> choices.edit().putString(BACKGROUND_KEY, BACKGROUND_IDS[i]).apply());
        }
        if (MODE_CUSTOM.equals(mode)) {
            TextView pick = row();
            pick.setText(WallpaperStore.hasCustom(this) ? "Choose a different picture ..." : "Choose a picture ...");
            pick.setOnClickListener(v -> startActivityForResult(new android.content.Intent(this, WallpaperPickerActivity.class), 5));
            list.addView(pick, rowParams());
        }
        toggle("Static scroll", choices.getBoolean(STATIC_SCROLL_KEY, false), true,
                v -> choices.edit().putBoolean(STATIC_SCROLL_KEY, v).apply());
        toggle("System sound", android.provider.Settings.System.getInt(getContentResolver(), "sound_effects_enabled", 1) != 0,
                false, this::setSystemSound);
        TextView hiddenApps = row();
        int hiddenCount = HiddenAppsActivity.hiddenSet(choices).size();
        hiddenApps.setText(hiddenCount == 0 ? "Hidden apps" : "Hidden apps (" + hiddenCount + " hidden)");
        hiddenApps.setOnClickListener(v -> startActivityForResult(
                new android.content.Intent(this, HiddenAppsActivity.class), 6));
        list.addView(hiddenApps, rowParams());
        TextView reset = row();
        reset.setText("Reset to the built-in look");
        reset.setOnClickListener(v -> {
            choices.edit().remove("columns").remove(ROW_GAP_KEY).remove(TOP_GAP_KEY).remove("animations")
                    .remove(COLOR_EFFECT_KEY).remove(STATIC_SCROLL_KEY).remove(GREETING_KEY).remove(DATE_FORMAT_KEY)
                    .remove(BACKGROUND_KEY).remove(WALLPAPER_MODE_KEY).remove(STYLE_KEY).remove(FOCUS_KEY).remove(ACCENT_KEY).remove(PACK_KEY).apply();
            changed();
            build(0);
        });
        list.addView(reset, rowParams());
        View target = list.getChildAt(Math.min(focusRow, list.getChildCount() - 1));
        if (target != null) target.requestFocus();
    }

    private interface Save {
        void put(int value);
    }

    /**
     * A style pack sets a whole look in one press: a base style, focus effect, accent, spacing and a stock wallpaper.
     * Every setting stays editable afterwards; the pack only starts you from a good combination.
     */
    private void applyPack(int index) {
        SharedPreferences.Editor e = choices.edit();
        if (index == 0) {
            e.remove(PACK_KEY).apply();
            return;
        }
        e.putString(PACK_KEY, PACK_NAMES[index]).remove(COLOR_EFFECT_KEY).remove(STATIC_SCROLL_KEY)
                .putString(WALLPAPER_MODE_KEY, MODE_CUSTOM);
        switch (index) {
            case 1:   // Midnight: deep navy glass, rich focus, accent from the picture
                e.putString(STYLE_KEY, "glass").putString(FOCUS_KEY, "rich").putString(ACCENT_KEY, "wallpaper")
                        .putInt(ROW_GAP_KEY, 8).putInt(TOP_GAP_KEY, 16).putString(DATE_FORMAT_KEY, DATE_LONG);
                break;
            case 2:   // Sunset: warm, frosted dock, roomy rows
                e.putString(STYLE_KEY, "cinematic").putString(FOCUS_KEY, "rich").putString(ACCENT_KEY, "wallpaper")
                        .putInt(ROW_GAP_KEY, 12).putInt(TOP_GAP_KEY, 24).putString(DATE_FORMAT_KEY, DATE_SHORT);
                break;
            case 3:   // Aurora: glass with the picture's colours everywhere
                e.putString(STYLE_KEY, "glass").putString(FOCUS_KEY, "rich").putString(ACCENT_KEY, "wallpaper")
                        .putInt(ROW_GAP_KEY, 0).putInt(TOP_GAP_KEY, 0).putString(DATE_FORMAT_KEY, DATE_SHORT);
                break;
            default:  // Ocean: calm default look with the app's own colours
                e.putString(STYLE_KEY, "glass").putString(FOCUS_KEY, "rich").putString(ACCENT_KEY, "app")
                        .putInt(ROW_GAP_KEY, 4).putInt(TOP_GAP_KEY, 8).putString(DATE_FORMAT_KEY, DATE_LONG);
                break;
        }
        e.apply();
        final String asset = PACK_WALLPAPERS[index];
        final int focusRow = list.getFocusedChild() != null ? list.indexOfChild(list.getFocusedChild()) : 0;
        android.widget.Toast.makeText(this, "Applying " + PACK_NAMES[index] + " ...", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                WallpaperStore.saveCustom(this, WallpaperStore.stockOpener(this, asset));
            } catch (java.io.IOException ignored) {
                // no picture: the pack's other settings still apply
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                changed();
                build(focusRow);
            });
        }, "style-pack").start();
    }

    private static int indexOf(String[] ids, String id) {
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return i;
        return 0;
    }

    /** Back from the picker with a saved picture: the page shows the new state, and Home rebuilds. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) setResult(RESULT_OK);
        build(0);
    }

    /** A pick-one row: Left and Right (or OK) step through the options and wrap. */
    private void cycle(String name, String[] options, int current, Save save) {
        final int index = list.getChildCount();
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(24), dp(16), dp(24), dp(16));
        r.setFocusable(true);
        r.setClickable(true);
        r.setBackground(rowBackground());
        r.addView(label(name, 19, TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(label("<   " + options[current] + "   >", 19, ACCENT, true));
        r.setOnClickListener(v -> apply(save, (current + 1) % options.length, index));
        r.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) apply(save, (current + options.length - 1) % options.length, index);
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) apply(save, (current + 1) % options.length, index);
            else return false;
            return true;
        });
        list.addView(r, rowParams());
    }

    private interface SaveFlag {
        void put(boolean value);
    }

    /** An On / Off row: OK, Left or Right flips it. */
    private void toggle(String name, boolean value, boolean affectsHome, SaveFlag save) {
        final int index = list.getChildCount();
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(24), dp(16), dp(24), dp(16));
        r.setFocusable(true);
        r.setClickable(true);
        r.setBackground(rowBackground());
        r.addView(label(name, 19, TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(label(value ? "On" : "Off", 19, value ? ACCENT : DIM, true));
        View.OnClickListener flip = view -> {
            save.put(!value);
            if (affectsHome) changed();
            build(index);
        };
        r.setOnClickListener(flip);
        r.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN
                    || (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT)) return false;
            flip.onClick(view);
            return true;
        });
        list.addView(r, rowParams());
    }

    private void stepper(String name, int value, int min, int max, int step, String unit, String minName, String maxName,
                         Save save) {
        final int index = list.getChildCount();
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(24), dp(16), dp(24), dp(16));
        r.setFocusable(true);
        r.setClickable(true);
        r.setBackground(rowBackground());
        TextView n = label(name, 19, TEXT, false);
        r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        String note = value <= min ? "  (" + minName + ")" : value >= max ? "  (" + maxName + ")" : "";
        TextView v = label("<   " + value + unit + "   >" + note, 19, ACCENT, true);
        r.addView(v);
        r.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int next = value;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) next = Math.max(min, value - step);
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) next = Math.min(max, value + step);
            else return false;
            if (next != value) apply(save, next, index);
            return true;
        });
        r.setOnClickListener(view -> apply(save, value + step > max ? min : value + step, index));
        list.addView(r, rowParams());
    }

    /**
     * The TV's own navigation click sounds (Settings > Sound > System sounds). That switch lives in the system
     * settings table, which an app cannot write, so it goes through the Control Center's ADB link to this TV.
     */
    private void setSystemSound(boolean on) {
        final boolean[] ok = {false};
        Thread t = new Thread(() -> {
            try {
                com.hyperlauncher.control.AdbClient.get(this).shell("settings put system sound_effects_enabled " + (on ? 1 : 0));
                ok[0] = true;
            } catch (java.io.IOException ignored) {
            }
        });
        t.start();
        try {
            t.join(4000);
        } catch (InterruptedException ignored) {
        }
        if (!ok[0]) {
            // No ADB (Developer options off): the TV's own sound settings have the same switch.
            android.widget.Toast.makeText(this,
                    "Needs ADB to change from here. Opening the TV's sound settings instead.", android.widget.Toast.LENGTH_LONG).show();
            try {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS));
            } catch (RuntimeException e) {
                try {
                    startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS));
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void apply(Save save, int value, int focusRow) {
        save.put(value);
        changed();
        build(focusRow);
    }

    private void changed() {
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        setResult(RESULT_OK);
    }

    private TextView row() {
        TextView t = label("", 19, TEXT, false);
        t.setPadding(dp(24), dp(16), dp(24), dp(16));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(rowBackground());
        return t;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(10));
        return lp;
    }

    private StateListDrawable rowBackground() {
        return Ui.glass(this, 14);
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        return Ui.text(this, s, sp, color, bold);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
