package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hide and unhide apps on Home. Every launchable app is listed with Shown / Hidden; OK flips it. The same list is
 * filled by "Hide from Home" in the hold-OK menu, and Home rebuilds when this page changes it.
 */
public final class HiddenAppsActivity extends Activity {
    static final String HIDDEN_KEY = "user_hidden_apps";
    private static final int BG = Ui.BG, PANEL = Ui.GLASS, TEXT = Ui.TEXT, DIM = Ui.DIM, ACCENT = Ui.ACCENT, DANGER = Ui.DANGER;
    /** Never offered for hiding: the way back to these controls and to the system settings must stay on Home. */
    private static final Set<String> LOCKED = new HashSet<>(java.util.Arrays.asList(
            "com.hyperlauncher.tv", "com.android.tv.settings", "com.android.settings"));

    private static final class Item {
        String label, pkg;
    }

    private final List<Item> items = new ArrayList<>();
    private Set<String> hidden = new HashSet<>();
    private SharedPreferences choices;
    private ArrayAdapter<Item> adapter;
    private TextView summary;

    static Set<String> hiddenSet(SharedPreferences p) {
        return new HashSet<>(p.getStringSet(HIDDEN_KEY, new HashSet<>()));
    }

    /** Saves the list and moves the settings revision, which makes Home rebuild itself. */
    static void save(SharedPreferences p, Set<String> hidden) {
        p.edit().putStringSet(HIDDEN_KEY, hidden)
                .putLong(Config.SETTINGS_REVISION_KEY, p.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        hidden = hiddenSet(choices);
        loadApps();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(96), dp(36), dp(96), dp(20));
        root.addView(label("Hidden apps", 28, TEXT, true));
        summary = label("", 14, DIM, false);
        summary.setPadding(0, 0, 0, dp(12));
        root.addView(summary);

        Button showAll = new Button(this);
        showAll.setText("Show all apps");
        showAll.setAllCaps(false);
        showAll.setTextColor(TEXT);
        showAll.setFocusable(true);
        showAll.setBackground(Ui.glass(this, 14));
        showAll.setOnClickListener(v -> {
            hidden.clear();
            save(choices, hidden);
            setResult(RESULT_OK);
            refresh();
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMargins(0, 0, 0, dp(10));
        root.addView(showAll, blp);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        adapter = new ArrayAdapter<Item>(this, 0, items) {
            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                Item it = items.get(position);
                LinearLayout row = new LinearLayout(HiddenAppsActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(22), dp(14), dp(22), dp(14));
                row.setBackground(Ui.panel(HiddenAppsActivity.this, 14));
                TextView name = label(it.label, 18, TEXT, false);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
                boolean isHidden = hidden.contains(it.pkg);
                row.addView(label(isHidden ? "Hidden" : "Shown", 18, isHidden ? DIM : ACCENT, true));
                row.setLayoutParams(new ListView.LayoutParams(-1, -2));
                return row;
            }
        };
        list.setAdapter(adapter);
        // A focus ring on the focused row, built here because ListView draws its own selector under the row.
        list.setSelector(Ui.glass(this, 14));
        list.setOnItemClickListener((p, v, position, id) -> {
            Item it = items.get(position);
            if (!hidden.remove(it.pkg)) hidden.add(it.pkg);
            save(choices, hidden);
            setResult(RESULT_OK);
            refresh();
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        refresh();
        list.requestFocus();
    }

    private void refresh() {
        int count = 0;
        for (Item it : items) if (hidden.contains(it.pkg)) count++;
        summary.setText(count == 0 ? "All apps are shown on Home. OK on an app hides it."
                : count + " hidden. OK on an app flips it between Shown and Hidden.");
        adapter.notifyDataSetChanged();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, Item> byPackage = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (LOCKED.contains(pkg) || byPackage.containsKey(pkg)) continue;
                Item it = new Item();
                it.pkg = pkg;
                it.label = String.valueOf(ri.loadLabel(pm));
                byPackage.put(pkg, it);
            }
        }
        // Apps that were hidden and have since been uninstalled stay out of the list (and stay saved).
        items.addAll(byPackage.values());
        Collections.sort(items, (a, b) -> a.label.compareToIgnoreCase(b.label));
    }

    private GradientDrawable rowBg(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? 0x2239404A : PANEL);
        d.setCornerRadius(dp(12));
        if (focused) d.setStroke(dp(3), ACCENT);
        return d;
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        return Ui.text(this, s, sp, color, bold);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
