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
import android.util.Log;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "TVHOME";
    private static final String TAG_IMG = "TMDBIMG";
    private static final String CACHE_JSON = "trending_cache.json";
    private static final String CACHE_TIME = "trending_cache_time.txt";
    private static final String CACHE_DIR = "backdrops";
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000; // 24 hours
    private static final String CREDIT_TEXT = "Movie data: TMDB";

    private static final String[][] DOCK_APPS = {
            {"com.netflix.mediaclient", "Netflix"},
            {"com.google.android.youtube.tv", "YouTube"},
            {"com.spotify.tv.android", "Spotify"},
            {"com.android.chrome", "Chrome"},
    };

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

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        dockLp.setMargins(0, 0, 0, (int) (sh * 0.07f));
        root.addView(dock, dockLp);

        credit = new TextView(this);
        credit.setText(CREDIT_TEXT);
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
            if (!cacheIsFresh()) {
                loadMovies(); // refresh in the background when the saved list is old
            }
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
