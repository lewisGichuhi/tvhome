package com.lewis.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // Dock apps after "Live TV". {package name, label}
    private static final String[][] DOCK_APPS = {
            {"com.netflix.mediaclient", "Netflix"},
            {"com.google.android.youtube.tv", "YouTube"},
            {"com.spotify.tv.android", "Spotify"},
            {"com.android.chrome", "Chrome"},
    };

    // The TV's own app for local channels. The first one that opens is used.
    private static final String[] LIVE_TV_PACKAGES = {
            "com.mstar.tv.tvplayer.ui",
            "com.mstar.android.tv.app",
    };

    private static final int ACCENT = 0xFFFF2D6F;
    private static final long SLIDE_MS = 9000;

    static class Movie {
        String title = "";
        String overview = "";
        String year = "";
        String backdrop = "";
    }

    private int sw, sh;
    private ImageView wallpaper, heroFront, heroBack;
    private TextView heroTitle, heroOverview, watchBtn, credit;
    private LinearLayout dock;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Movie> movies = new ArrayList<>();
    private Movie current;
    private int index = -1;
    private int showToken = 0;
    private boolean loading = false;
    private boolean resumed = false;

    private final Runnable slideRunnable = () -> showNext();
    private final Runnable retryRunnable = () -> loadMovies();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        sw = dm.widthPixels;
        sh = dm.heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(false);

        wallpaper = fullImage();
        root.addView(wallpaper, matchParent());

        heroBack = fullImage();
        heroBack.setAlpha(0f);
        root.addView(heroBack, matchParent());
        heroFront = fullImage();
        heroFront.setAlpha(0f);
        root.addView(heroFront, matchParent());

        // Darken the left side (text) and the bottom (tiles)
        View leftScrim = new View(this);
        leftScrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xE6000000, 0x00000000}));
        root.addView(leftScrim, new FrameLayout.LayoutParams(
                (int) (sw * 0.65f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xEE000000}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (sh * 0.45f), Gravity.BOTTOM));

        // Clock, top-left
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(0xFFFFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.042f);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        clockLp.setMargins((int) (sw * 0.05f), (int) (sh * 0.05f), 0, 0);
        root.addView(clock, clockLp);

        // Hero text block
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);

        heroTitle = new TextView(this);
        heroTitle.setTextColor(0xFFFFFFFF);
        heroTitle.setTypeface(Typeface.DEFAULT_BOLD);
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.072f);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(heroTitle);

        heroOverview = new TextView(this);
        heroOverview.setTextColor(0xDDFFFFFF);
        heroOverview.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.03f);
        heroOverview.setMaxLines(3);
        heroOverview.setEllipsize(TextUtils.TruncateAt.END);
        heroOverview.setPadding(0, (int) (sh * 0.015f), 0, 0);
        hero.addView(heroOverview);

        watchBtn = new TextView(this);
        watchBtn.setText("Watch trailer");
        watchBtn.setTextColor(0xFF000000);
        watchBtn.setTypeface(Typeface.DEFAULT_BOLD);
        watchBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.032f);
        watchBtn.setPadding((int) (sw * 0.02f), (int) (sh * 0.015f),
                (int) (sw * 0.02f), (int) (sh * 0.015f));
        watchBtn.setFocusable(true);
        watchBtn.setClickable(true);
        watchBtn.setId(View.generateViewId());
        watchBtn.setVisibility(View.GONE);
        final GradientDrawable btnNormal = rounded(0xFFFFFFFF, sh * 0.01f, 0, 0);
        final GradientDrawable btnFocus = rounded(0xFFFFFFFF, sh * 0.01f, Math.max(3, (int) (sh * 0.006f)), ACCENT);
        watchBtn.setBackground(btnNormal);
        watchBtn.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(hasFocus ? btnFocus : btnNormal);
            v.animate().scaleX(hasFocus ? 1.08f : 1f).scaleY(hasFocus ? 1.08f : 1f)
                    .setDuration(120).start();
        });
        watchBtn.setOnClickListener(v -> openTrailer());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = (int) (sh * 0.035f);
        hero.addView(watchBtn, btnLp);

        FrameLayout.LayoutParams heroLp = new FrameLayout.LayoutParams(
                (int) (sw * 0.42f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        heroLp.setMargins((int) (sw * 0.05f), (int) (sh * 0.27f), 0, 0);
        root.addView(hero, heroLp);

        // Dock
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        dockLp.setMargins(0, 0, 0, (int) (sh * 0.07f));
        root.addView(dock, dockLp);

        // TMDB credit (required by their terms when using their data)
        credit = new TextView(this);
        credit.setText("Movie data: TMDB");
        credit.setTextColor(0x88FFFFFF);
        credit.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.022f);
        credit.setVisibility(View.GONE);
        FrameLayout.LayoutParams creditLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        creditLp.setMargins((int) (sw * 0.05f), 0, 0, (int) (sh * 0.02f));
        root.addView(credit, creditLp);

        setContentView(root);
        buildDock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        loadWallpaper();
        if (movies.isEmpty()) {
            loadMovies();
        } else {
            showNext();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        showToken++;
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while we are already open: go back to the first tile.
        if (dock != null && dock.getChildCount() > 0) {
            dock.getChildAt(0).requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        // A home screen should not close on Back.
    }

    // ---------- Dock ----------

    private void buildDock() {
        dock.removeAllViews();
        PackageManager pm = getPackageManager();

        // Live TV (local channels) first
        Drawable liveIcon = null;
        for (String pkg : LIVE_TV_PACKAGES) {
            try {
                liveIcon = pm.getApplicationIcon(pkg);
                break;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        addTile(liveIcon, "Live TV", v -> launchLiveTv());

        for (String[] app : DOCK_APPS) {
            final String pkg = app[0];
            try {
                Drawable icon = pm.getApplicationIcon(pkg);
                addTile(icon, app[1], v -> launchPackage(pkg));
            } catch (PackageManager.NameNotFoundException e) {
                // Not installed on this TV: skip it.
            }
        }

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

        addTile(null, "All apps", v -> showAllApps());

        if (dock.getChildCount() > 0) {
            dock.getChildAt(0).requestFocus();
        }
    }

    private void addTile(Drawable icon, String label, View.OnClickListener onClick) {
        int tileW = (int) (sw * 0.115f);
        int tileH = (int) (tileW * 0.78f);
        int iconSize = (int) (tileW * 0.40f);

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setId(View.generateViewId());
        tile.setNextFocusUpId(watchBtn.getId());
        tile.setOnClickListener(onClick);

        final GradientDrawable normalBg = rounded(0x44FFFFFF, sh * 0.02f, 0, 0);
        final GradientDrawable focusBg = rounded(0x77FFFFFF, sh * 0.02f,
                Math.max(3, (int) (sh * 0.006f)), ACCENT);
        tile.setBackground(normalBg);

        ImageView iv = new ImageView(this);
        if (icon != null) {
            iv.setImageDrawable(icon);
        }
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(iv, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * 0.028f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, (int) (sh * 0.01f), 0, 0);
        tile.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tile.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(hasFocus ? focusBg : normalBg);
            v.setTranslationZ(hasFocus ? 20f : 0f);
            v.animate().scaleX(hasFocus ? 1.12f : 1f).scaleY(hasFocus ? 1.12f : 1f)
                    .setDuration(120).start();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
        int m = (int) (sw * 0.008f);
        lp.setMargins(m, 0, m, 0);
        dock.addView(tile, lp);
    }

    // ---------- Launching ----------

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

    private void launchLiveTv() {
        PackageManager pm = getPackageManager();
        for (String pkg : LIVE_TV_PACKAGES) {
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) {
                startActivity(i);
                return;
            }
        }
        Toast.makeText(this, "Live TV app could not be opened", Toast.LENGTH_LONG).show();
    }

    private void openTrailer() {
        if (current == null) {
            return;
        }
        String q = current.title + " official trailer";
        Uri uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q));
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        i.setPackage("com.google.android.youtube.tv");
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent j = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(j);
            } catch (Exception e2) {
                Toast.makeText(this, "Cannot open trailer", Toast.LENGTH_SHORT).show();
            }
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

    // ---------- Trending movies (TMDB) ----------

    private void setStatus(String message) {
        heroTitle.setText("");
        heroOverview.setText(message);
        watchBtn.setVisibility(View.GONE);
        credit.setVisibility(View.GONE);
    }

    private String readKey() {
        // Saved on the TV at:
        // /sdcard/Android/data/com.lewis.tvhome/files/tmdb_key.txt
        File f = new File(getExternalFilesDir(null), "tmdb_key.txt");
        if (!f.exists()) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s = r.readLine();
            if (s == null) {
                return null;
            }
            s = s.trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private void loadMovies() {
        if (loading) {
            return;
        }
        final String key = readKey();
        if (key == null) {
            setStatus("Add a TMDB key to show trending movies here.");
            return;
        }
        loading = true;
        setStatus("Loading trending movies...");

        io.execute(() -> {
            List<Movie> list = null;
            String err = null;
            try {
                list = fetchMovies(key);
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final List<Movie> res = list;
            final String error = err;
            handler.post(() -> {
                loading = false;
                if (res != null && !res.isEmpty()) {
                    movies.clear();
                    movies.addAll(res);
                    index = -1;
                    watchBtn.setVisibility(View.VISIBLE);
                    credit.setVisibility(View.VISIBLE);
                    showNext();
                } else {
                    setStatus(error != null ? "Could not load movies (" + error + ")"
                            : "No movies found.");
                    if (resumed) {
                        handler.postDelayed(retryRunnable, 60000);
                    }
                }
            });
        });
    }

    private List<Movie> fetchMovies(String key) throws Exception {
        String json = new String(httpGet(
                "https://api.themoviedb.org/3/trending/movie/week?language=en-US&api_key=" + key),
                "UTF-8");
        JSONArray arr = new JSONObject(json).getJSONArray("results");
        List<Movie> out = new ArrayList<>();
        for (int i = 0; i < arr.length() && out.size() < 12; i++) {
            JSONObject o = arr.getJSONObject(i);
            String bd = o.optString("backdrop_path", "");
            if (bd.isEmpty() || "null".equals(bd)) {
                continue;
            }
            Movie m = new Movie();
            m.title = o.optString("title", "");
            m.overview = o.optString("overview", "");
            String rd = o.optString("release_date", "");
            m.year = rd.length() >= 4 ? rd.substring(0, 4) : "";
            m.backdrop = bd;
            out.add(m);
        }
        return out;
    }

    private byte[] httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private void showNext() {
        handler.removeCallbacks(slideRunnable);
        if (movies.isEmpty() || !resumed) {
            return;
        }
        index = (index + 1) % movies.size();
        final Movie m = movies.get(index);
        final int mine = ++showToken;

        io.execute(() -> {
            Bitmap b = null;
            try {
                byte[] data = httpGet("https://image.tmdb.org/t/p/w1280" + m.backdrop);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } catch (Exception ignored) {
            }
            final Bitmap bmp = b;
            handler.post(() -> {
                if (mine != showToken) {
                    return;
                }
                current = m;
                heroTitle.setText(m.year.isEmpty() ? m.title : m.title + " (" + m.year + ")");
                heroOverview.setText(m.overview);
                if (bmp != null) {
                    crossfadeTo(bmp);
                }
                handler.postDelayed(slideRunnable, SLIDE_MS);
            });
        });
    }

    private void crossfadeTo(Bitmap bmp) {
        ImageView in = heroBack;
        ImageView out = heroFront;
        in.animate().cancel();
        out.animate().cancel();
        in.setImageBitmap(bmp);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(900).start();
        out.animate().alpha(0f).setDuration(900).start();
        heroFront = in;
        heroBack = out;
    }

    // ---------- Wallpaper and helpers ----------

    private void loadWallpaper() {
        // Optional picture shown when no movie is displayed:
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

    private ImageView fullImage() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private GradientDrawable rounded(int color, float radius, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokePx > 0) {
            g.setStroke(strokePx, strokeColor);
        }
        return g;
    }
}
