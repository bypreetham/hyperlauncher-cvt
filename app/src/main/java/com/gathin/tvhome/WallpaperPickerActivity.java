package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picks the custom wallpaper: the stock pictures that ship with the launcher, any pictures on a USB drive that is
 * plugged in, or Android's own file picker. The chosen picture is copied into the launcher (WallpaperStore), so the
 * drive can be pulled out afterwards.
 */
public final class WallpaperPickerActivity extends Activity {
    private static final int BG = Ui.BG, PANEL = Ui.GLASS, TEXT = Ui.TEXT, DIM = Ui.DIM, ACCENT = Ui.ACCENT, DANGER = Ui.DANGER;
    private static final int PICK_DOCUMENT = 7;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(48);
    private final List<Entry> entries = new ArrayList<>();
    private LinearLayout sources;
    private TextView hint;
    private RecyclerView grid;
    private RecyclerView.Adapter<Holder> adapter;
    private String activeSource = "stock";

    /** One picture in the grid: a stock asset or a file on a drive. */
    private static final class Entry {
        String label;
        String asset;
        File file;
    }

    private final BroadcastReceiver storageChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // A drive appeared or went away: redo the source buttons, and the grid if its drive is gone.
            buildSources();
            if (activeSource.startsWith("usb:") && !usbStillThere(activeSource)) show("stock");
            else if (activeSource.startsWith("usb:")) show(activeSource);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(64), dp(32), dp(64), dp(16));
        root.addView(label("Custom wallpaper", 26, TEXT, true));
        TextView sub = label("Pick a picture. A copy is saved, so it stays after a USB drive is removed.", 13, DIM, false);
        sub.setPadding(0, 0, 0, dp(12));
        root.addView(sub);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        sources = new LinearLayout(this);
        sources.setOrientation(LinearLayout.HORIZONTAL);
        scroller.addView(sources);
        root.addView(scroller);

        hint = label("", 14, DIM, false);
        hint.setPadding(0, dp(10), 0, dp(10));
        root.addView(hint);

        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 4));
        grid.setClipToPadding(false);
        grid.setPadding(dp(6), dp(6), dp(6), dp(6));
        // No scroll bar: a RecyclerView built in code has no scroll bar drawable, and enabling one crashes on draw.
        adapter = new RecyclerView.Adapter<Holder>() {
            @NonNull
            @Override
            public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return makeCell(parent);
            }

            @Override
            public void onBindViewHolder(@NonNull Holder h, int position) {
                bindCell(h, entries.get(position));
            }

            @Override
            public int getItemCount() {
                return entries.size();
            }
        };
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        buildSources();
        show("stock");
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        registerReceiver(storageChanged, f);
        buildSources();
        // Back from the All files access screen: load the drive that was waiting for it.
        if (activeSource.startsWith("usb:") && WallpaperStore.hasAllFilesAccess()) show(activeSource);
    }

    @Override
    protected void onPause() {
        unregisterReceiver(storageChanged);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---- sources ----

    private void buildSources() {
        sources.removeAllViews();
        sources.addView(sourceButton("Stock images", "stock"));
        for (WallpaperStore.Volume v : WallpaperStore.usbVolumes(this)) {
            sources.addView(sourceButton("USB: " + v.label, "usb:" + v.root.getAbsolutePath()));
        }
        Button picker = button("Other (file picker)");
        picker.setOnClickListener(v -> openSystemPicker());
        sources.addView(picker);
        if (WallpaperStore.usbVolumes(this).isEmpty()) {
            TextView none = label("   No USB drive found", 13, DIM, false);
            none.setGravity(Gravity.CENTER_VERTICAL);
            sources.addView(none);
        }
        if (sources.getChildCount() > 0 && !sources.hasFocus() && getCurrentFocus() == null) sources.getChildAt(0).requestFocus();
    }

    private boolean usbStillThere(String source) {
        String path = source.substring(4);
        for (WallpaperStore.Volume v : WallpaperStore.usbVolumes(this)) if (v.root.getAbsolutePath().equals(path)) return true;
        return false;
    }

    private Button sourceButton(String text, String id) {
        Button b = button(text);
        b.setOnClickListener(v -> show(id));
        return b;
    }

    private void show(String source) {
        activeSource = source;
        entries.clear();
        adapter.notifyDataSetChanged();
        if (source.equals("stock")) {
            for (String name : WallpaperStore.stockNames(this)) {
                Entry e = new Entry();
                e.asset = name;
                e.label = WallpaperStore.prettyName(name);
                entries.add(e);
            }
            hint.setText(entries.size() + " stock pictures");
            adapter.notifyDataSetChanged();
            return;
        }
        if (!WallpaperStore.hasAllFilesAccess()) {
            hint.setText("Reading a USB drive needs All files access.");
            askAllFilesAccess();
            return;
        }
        final File root = new File(source.substring(4));
        hint.setText("Looking for pictures on the drive ...");
        worker.execute(() -> {
            final List<File> files = WallpaperStore.scanImages(root);
            main.post(() -> {
                if (!activeSource.equals(source)) return;
                entries.clear();
                for (File f : files) {
                    Entry e = new Entry();
                    e.file = f;
                    e.label = WallpaperStore.prettyName(f.getName());
                    entries.add(e);
                }
                hint.setText(files.isEmpty() ? "No pictures (jpg, png, webp) found on this drive"
                        : files.size() + " pictures on the drive");
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void askAllFilesAccess() {
        new AlertDialog.Builder(this).setTitle("Allow access to USB drives")
                .setMessage("Android needs 'All files access' for HyperLauncher to read pictures from a USB drive. "
                        + "Turn it on in the next screen, then come back. Or use 'Other (file picker)', which needs no permission.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException e) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (RuntimeException e2) {
                            Toast.makeText(this, "This TV has no All files access screen: use Other (file picker)",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("File picker", (d, w) -> openSystemPicker())
                .setNegativeButton("Cancel", null).show();
    }

    private void openSystemPicker() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*"), PICK_DOCUMENT);
        } catch (RuntimeException e) {
            Toast.makeText(this, "This TV has no file picker", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_DOCUMENT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        apply(() -> getContentResolver().openInputStream(uri));
    }

    // ---- grid ----

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView name;

        Holder(LinearLayout cell, ImageView image, TextView name) {
            super(cell);
            this.image = image;
            this.name = name;
        }
    }

    private Holder makeCell(ViewGroup parent) {
        int width = (parent.getWidth() - dp(12)) / 4 - dp(12);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(6), dp(6), dp(6), dp(6));
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setBackground(Ui.glass(this, 12));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(PANEL);
        image.setClipToOutline(true);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(8));
            }
        });
        cell.addView(image, new LinearLayout.LayoutParams(width, width * 9 / 16));
        TextView name = label("", 12, TEXT, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(4), 0, 0);
        cell.addView(name, new LinearLayout.LayoutParams(width, -2));
        cell.setLayoutParams(new RecyclerView.LayoutParams(-2, -2));
        return new Holder(cell, image, name);
    }

    private void bindCell(Holder h, Entry e) {
        h.name.setText(e.label);
        final String key = e.asset != null ? "a:" + e.asset : "f:" + e.file.getAbsolutePath();
        Bitmap cached = thumbs.get(key);
        h.image.setTag(key);
        if (cached != null) {
            h.image.setImageBitmap(cached);
        } else {
            h.image.setImageDrawable(null);
            worker.execute(() -> {
                try {
                    Bitmap b = WallpaperStore.decode(opener(e), 480, 270);
                    thumbs.put(key, b);
                    main.post(() -> {
                        if (key.equals(h.image.getTag())) h.image.setImageBitmap(b);
                    });
                } catch (IOException ignored) {
                }
            });
        }
        h.itemView.setOnClickListener(v -> apply(opener(e)));
    }

    private WallpaperStore.Opener opener(Entry e) {
        return e.asset != null ? WallpaperStore.stockOpener(this, e.asset) : () -> new FileInputStream(e.file);
    }

    private void apply(WallpaperStore.Opener opener) {
        Toast.makeText(this, "Saving wallpaper ...", Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            try {
                WallpaperStore.saveCustom(this, opener);
                main.post(() -> {
                    SharedPreferences p = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
                    p.edit().putString(LayoutSettingsActivity.WALLPAPER_MODE_KEY, LayoutSettingsActivity.MODE_CUSTOM)
                            .putLong(Config.SETTINGS_REVISION_KEY, p.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
                    setResult(RESULT_OK);
                    Toast.makeText(this, "Wallpaper saved", Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (IOException e) {
                main.post(() -> Toast.makeText(this, "Could not use that picture: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ---- views ----

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(15);
        b.setTypeface(Ui.medium(this));
        b.setFocusable(true);
        b.setBackground(Ui.glass(this, 10));
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(10), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        return Ui.text(this, s, sp, color, bold);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
