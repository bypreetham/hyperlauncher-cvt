package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextClock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Home date. A TextClock that, in "short" mode (Customize Launcher > Date format), turns "Sat 31 Oct 2026"
 * into "SAT 31st OCT 2026": capitals, with the day's ordinal suffix in lowercase after it.
 */
public final class DateClock extends TextClock {
    private static final Pattern DAY = Pattern.compile("\\b(\\d{1,2})\\b");
    private boolean ordinal;

    public DateClock(Context context) {
        super(context);
    }

    public DateClock(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DateClock(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Turns the short style on: pattern "EEE d MMM yyyy", capitals and the ordinal handled here. */
    void useShortStyle() {
        ordinal = true;
        setAllCaps(false);
        setFormat12Hour("EEE d MMM yyyy");
        setFormat24Hour("EEE d MMM yyyy");
    }

    static String suffix(int day) {
        if (day % 100 >= 11 && day % 100 <= 13) return "th";
        switch (day % 10) {
            case 1: return "st";
            case 2: return "nd";
            case 3: return "rd";
            default: return "th";
        }
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        if (ordinal && text != null) {
            String t = text.toString();
            Matcher m = DAY.matcher(t);
            if (m.find()) {
                String upper = t.substring(0, m.end(1)).toUpperCase() + suffix(Integer.parseInt(m.group(1)))
                        + t.substring(m.end(1)).toUpperCase();
                text = upper;
            } else {
                text = t.toUpperCase();
            }
        }
        super.setText(text, type);
    }
}
