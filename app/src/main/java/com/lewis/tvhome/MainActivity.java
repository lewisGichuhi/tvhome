package com.lewis.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    // Edit this list to change the dock. {package name, label}
    private static final String[][] DOCK_APPS = {
            {"com.netflix.mediaclient", "Netflix"},
            {"com.google.android.youtube.tv", "YouTube"},
            {"com.spotify.tv.android", "Spotify"},
            {"com.android.chrome", "Chrome"},
    };

    private ImageView wallpaper;
    private LinearLayout dock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Dark fade at the bottom so the dock is readable on any wallpaper
        View scrim = new View(this);
        GradientDrawable fade = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xE6000000});
        scrim.setBackground(fade);
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280), Gravity.BOTTOM));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(0xFFFFFFFF);
        clock.setTextSize(30);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clockLp.setMargins(0, dp(30), dp(40), 0);
        root.addView(clock, clockLp);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        dockLp.setMargins(0, 0, 0, dp(30));
        root.addView(dock, dockLp);

        setContentView(root);
        buildDock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadWallpaper();
    }

    @Override
    public void onBackPressed() {
        // A home screen should not close on Back.
    }

    private void buildDock() {
        dock.removeAllViews();
        PackageManager pm = getPackageManager();

        for (String[] app : DOCK_APPS) {
            final String pkg = app[0];
            try {
                Drawable icon = pm.getApplicationIcon(pkg);
                addTile(icon, app[1], v -> launchPackage(pkg));
            } catch (PackageManager.NameNotFoundException e) {
                // App not installed on this TV: skip it.
            }
        }

        // TV settings
        Drawable settingsIcon = null;
        try {
            settingsIcon = pm.getApplicationIcon("com.android.tv.settings");
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        addTile(settingsIcon, "Settings", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show();
            }
        });

        // All apps
        GradientDrawable allIcon = new GradientDrawable();
        allIcon.setColor(0x66FFFFFF);
        allIcon.setCornerRadius(dp(14));
        addTile(allIcon, "All apps", v -> showAllApps());

        if (dock.getChildCount() > 0) {
            dock.getChildAt(0).requestFocus();
        }
    }

    private void addTile(Drawable icon, String label, View.OnClickListener onClick) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(14), dp(12), dp(14), dp(12));
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setOnClickListener(onClick);

        ImageView iv = new ImageView(this);
        if (icon != null) {
            iv.setImageDrawable(icon);
        }
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(iv, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(8), 0, 0);
        tile.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(0x44FFFFFF);
        focusBg.setCornerRadius(dp(16));
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.18f : 1f).scaleY(hasFocus ? 1.18f : 1f)
                    .setDuration(120).start();
            v.setBackground(hasFocus ? focusBg : null);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(14), 0, dp(14), 0);
        dock.addView(tile, lp);
    }

    private void launchPackage(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) {
            i = pm.getLaunchIntentForPackage(pkg);
        }
        if (i != null) {
            startActivity(i);
        } else {
            Toast.makeText(this, "Cannot open " + pkg, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAllApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(getPackageName());

        String[] categories = {Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER};
        for (String cat : categories) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (seen.add(ri.activityInfo.packageName)) {
                    found.add(ri);
                }
            }
        }

        final List<String> pkgs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (ResolveInfo ri : found) {
            pkgs.add(ri.activityInfo.packageName);
            names.add(String.valueOf(ri.loadLabel(pm)));
        }

        new AlertDialog.Builder(this)
                .setTitle("All apps")
                .setItems(names.toArray(new String[0]), (d, which) -> launchPackage(pkgs.get(which)))
                .show();
    }

    private void loadWallpaper() {
        // Put your own image at:
        // /sdcard/Android/data/com.lewis.tvhome/files/wallpaper.jpg
        File f = new File(getExternalFilesDir(null), "wallpaper.jpg");
        if (f.exists()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1280 && o.outHeight / (sample * 2) >= 720) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            if (b != null) {
                wallpaper.setBackground(null);
                wallpaper.setImageBitmap(b);
                return;
            }
        }
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1026, 0xFF3A2A6B, 0xFF0B1026});
        wallpaper.setImageDrawable(null);
        wallpaper.setBackground(g);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
