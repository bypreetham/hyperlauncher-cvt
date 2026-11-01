package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.TextView;

/**
 * The launcher's own look for the screens built in code (Control Center, Customize Launcher, the pickers): the same
 * frosted-glass panels, white focus rim and Outfit font that Home uses, so every screen reads as one app.
 */
public final class Ui {
    private Ui() {}

    /** The theme background Home sits on (#090C14), and the text tones used over it. */
    public static final int BG = 0xFF090C14;
    public static final int TEXT = 0xFFF5F7FA;
    public static final int DIM = 0x99FFFFFF;
    /** Values and highlights: a soft sky blue that stays readable on glass. */
    public static final int ACCENT = 0xFF8CCBFF;
    public static final int DANGER = 0xFFFF7A7A;
    /** Frosted glass: white at 14%, brighter when focused. */
    public static final int GLASS = 0x24FFFFFF;
    public static final int GLASS_FOCUS = 0x40FFFFFF;
    public static final int RIM = 0x38FFFFFF;
    public static final int RIM_FOCUS = 0xE6FFFFFF;

    private static Typeface regular, medium;

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static Typeface regular(Context c) {
        if (regular == null) regular = load(c, R.font.outfit_regular);
        return regular;
    }

    public static Typeface medium(Context c) {
        if (medium == null) medium = load(c, R.font.outfit_medium);
        return medium;
    }

    private static Typeface load(Context c, int font) {
        try {
            Typeface t = c.getResources().getFont(font);
            return t != null ? t : Typeface.DEFAULT;
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    public static TextView text(Context c, String s, int sp, int color, boolean strong) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(strong ? medium(c) : regular(c));
        return t;
    }

    /** A resting glass panel: the card behind a group of content. */
    public static GradientDrawable panel(Context c, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(GLASS);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), RIM);
        return d;
    }

    /** A focusable glass control: resting glass, and a brighter fill with a white rim when focused. */
    public static StateListDrawable glass(Context c, int radiusDp) {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(GLASS_FOCUS);
        focused.setCornerRadius(dp(c, radiusDp));
        focused.setStroke(dp(c, 2), RIM_FOCUS);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, panel(c, radiusDp));
        return s;
    }
}
