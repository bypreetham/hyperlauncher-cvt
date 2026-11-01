package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Custom wallpaper: the stock pictures shipped in assets, the pictures on attached USB drives, and the one copy the
 * user picked (files/custom_wallpaper.jpg). The copy is made once, so it keeps working after the drive is pulled out.
 */
final class WallpaperStore {
    private WallpaperStore() {}

    static final String CUSTOM_NAME = "custom_wallpaper.jpg";
    private static final String STOCK_DIR = "stock_wallpapers";
    /** Saved size: a 1080p TV never needs more, and a smaller file decodes quickly on a weak CPU. */
    private static final int MAX_W = 1920, MAX_H = 1080;
    private static final int MAX_SCAN_DEPTH = 5, MAX_SCAN_FILES = 600;

    interface Opener {
        InputStream open() throws IOException;
    }

    static File customFile(Context c) {
        return new File(c.getFilesDir(), CUSTOM_NAME);
    }

    static boolean hasCustom(Context c) {
        File f = customFile(c);
        return f.isFile() && f.length() > 0;
    }

    /** Decodes at the smallest sample size that still covers reqW x reqH. */
    static Bitmap decode(Opener opener, int reqW, int reqH) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = opener.open()) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not a picture");
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = opener.open()) {
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null) throw new IOException("Could not read the picture");
            return bitmap;
        }
    }

    /** Saves a copy of the picture as the custom wallpaper (replacing the previous one). */
    static void saveCustom(Context c, Opener opener) throws IOException {
        Bitmap bitmap = decode(opener, MAX_W, MAX_H);
        if (bitmap.getWidth() > MAX_W || bitmap.getHeight() > MAX_H) {
            float scale = Math.min(MAX_W / (float) bitmap.getWidth(), MAX_H / (float) bitmap.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale),
                    Math.round(bitmap.getHeight() * scale), true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        File tmp = new File(c.getFilesDir(), CUSTOM_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new IOException("Could not save the picture");
        } finally {
            bitmap.recycle();
        }
        if (!tmp.renameTo(customFile(c))) {
            customFile(c).delete();
            if (!tmp.renameTo(customFile(c))) throw new IOException("Could not save the picture");
        }
    }

    /**
     * The picture's main colour for the accent: the average of its pixels weighted towards vivid, bright ones, then
     * lifted to a colour that shows up on dark glass. 0 when the picture is grey or unreadable.
     */
    static int dominantColor(File file) {
        try {
            Bitmap b = decode(() -> new java.io.FileInputStream(file), 64, 36);
            float[] hsv = new float[3];
            double r = 0, g = 0, bl = 0, weight = 0;
            for (int y = 0; y < b.getHeight(); y++) {
                for (int x = 0; x < b.getWidth(); x++) {
                    int px = b.getPixel(x, y);
                    android.graphics.Color.colorToHSV(px, hsv);
                    if (hsv[2] < 0.2f) continue;
                    double w = hsv[1] * hsv[1] * hsv[2];
                    r += android.graphics.Color.red(px) * w;
                    g += android.graphics.Color.green(px) * w;
                    bl += android.graphics.Color.blue(px) * w;
                    weight += w;
                }
            }
            b.recycle();
            if (weight < 1e-3) return 0;
            int avg = android.graphics.Color.rgb((int) (r / weight), (int) (g / weight), (int) (bl / weight));
            android.graphics.Color.colorToHSV(avg, hsv);
            if (hsv[1] < 0.12f) return 0;
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = Math.max(hsv[2], 0.85f);
            return android.graphics.Color.HSVToColor(hsv);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    static List<String> stockNames(Context c) {
        try {
            String[] names = c.getAssets().list(STOCK_DIR);
            if (names == null) return Collections.emptyList();
            Arrays.sort(names);
            return new ArrayList<>(Arrays.asList(names));
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    static Opener stockOpener(Context c, String name) {
        return () -> c.getAssets().open(STOCK_DIR + "/" + name);
    }

    static String prettyName(String file) {
        String n = file.replaceFirst("\\.[A-Za-z0-9]+$", "").replaceFirst("^\\d+[-_ ]*", "").replace('-', ' ').replace('_', ' ');
        return n.isEmpty() ? file : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // ---- USB ----

    static final class Volume {
        final String label;
        final File root;

        Volume(String label, File root) {
            this.label = label;
            this.root = root;
        }
    }

    /** Mounted removable drives (USB sticks, disks, SD cards). */
    static List<Volume> usbVolumes(Context c) {
        List<Volume> found = new ArrayList<>();
        StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) return found;
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (v.isPrimary() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
            File root = directoryOf(v);
            if (root == null) continue;
            String label = v.getDescription(c);
            found.add(new Volume(label == null || label.isEmpty() ? root.getName() : label, root));
        }
        return found;
    }

    private static File directoryOf(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) return v.getDirectory();
        try {
            return new File((String) StorageVolume.class.getMethod("getPath").invoke(v));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Whether the app may read files on a USB drive (All files access on Android 11+). */
    static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    /** Pictures under a drive, folder by folder, skipping system and hidden folders. */
    static List<File> scanImages(File root) {
        List<File> out = new ArrayList<>();
        walk(root, 0, out);
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    private static void walk(File dir, int depth, List<File> out) {
        if (depth > MAX_SCAN_DEPTH || out.size() >= MAX_SCAN_FILES) return;
        File[] children;
        try {
            children = dir.listFiles();
        } catch (SecurityException e) {
            return;
        }
        if (children == null) return;
        for (File child : children) {
            if (out.size() >= MAX_SCAN_FILES) return;
            String name = child.getName();
            if (name.startsWith(".")) continue;
            if (child.isDirectory()) {
                if (name.equals("Android") || name.equals("LOST.DIR") || name.equals("System Volume Information")
                        || name.equalsIgnoreCase("$RECYCLE.BIN")) continue;
                walk(child, depth + 1, out);
            } else {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
                    if (child.length() > 0) out.add(child);
                }
            }
        }
    }
}
