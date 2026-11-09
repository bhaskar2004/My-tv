package com.example.smarttvos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Smart TV launcher: a native Android TV home screen. */
public class MainActivity extends Activity {

    private static final int COLS = 6;
    private static final int SURF = 0xFF1A1F2A;
    private static final int MUTED = 0xFF8B93A3;

    static class AppItem {
        String label, pkg;
        ResolveInfo ri;
        Intent intent;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Map<String, Boolean> banners = new HashMap<>();
    private final Map<String, AppItem> byPkg = new HashMap<>();
    private final List<AppItem> apps = new ArrayList<>();

    private PackageManager pm;
    private SharedPreferences prefs;
    private ScrollView scroll;
    private LinearLayout content;
    private TextView net;
    private float u; // 1% of the screen width, in pixels: everything scales from this
    private String focusKey;
    private View firstTile;

    private final Runnable refresh = () -> {
        loadApps();
        rebuild();
    };

    private final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            ui.removeCallbacks(refresh);
            ui.postDelayed(refresh, 600);
        }
    };

    private final Runnable netTick = new Runnable() {
        @Override
        public void run() {
            updateNet();
            ui.postDelayed(this, 30000);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        pm = getPackageManager();
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        u = getResources().getDisplayMetrics().widthPixels / 100f;

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF141A2E, 0xFF0B0D12}));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.addView(topBar());

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPadding(0, 0, 0, px(6));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgRx, f);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(pkgRx);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        preferHighRefreshRate();
        loadApps();
        rebuild();
        ui.removeCallbacks(netTick);
        ui.post(netTick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(netTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    /** Home pressed while the launcher is already showing: go back to the top. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        focusKey = null;
        rebuild();
        scroll.scrollTo(0, 0);
    }

    /** A launcher never exits. */
    @Override
    public void onBackPressed() {
    }

    // ---------------------------------------------------------------- data

    private void loadApps() {
        Map<String, AppItem> m = new LinkedHashMap<>();
        collect(m, Intent.CATEGORY_LEANBACK_LAUNCHER);
        collect(m, Intent.CATEGORY_LAUNCHER);
        apps.clear();
        apps.addAll(m.values());
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        byPkg.clear();
        for (AppItem a : apps) byPkg.put(a.pkg, a);
    }

    private void collect(Map<String, AppItem> m, String category) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || m.containsKey(pkg)) continue;
            AppItem a = new AppItem();
            a.pkg = pkg;
            a.ri = ri;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            m.put(pkg, a);
        }
    }

    private List<String> recentPkgs() {
        List<String> l = new ArrayList<>();
        String s = prefs.getString("recent", "");
        if (!s.isEmpty()) Collections.addAll(l, s.split(","));
        return l;
    }

    private void addRecent(String pkg) {
        List<String> l = recentPkgs();
        l.remove(pkg);
        l.add(0, pkg);
        while (l.size() > 10) l.remove(l.size() - 1);
        prefs.edit().putString("recent", TextUtils.join(",", l)).apply();
    }

    // ---------------------------------------------------------------- UI building

    private int px(float units) {
        return Math.round(units * u);
    }

    private TextView text(CharSequence s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(5), px(2.4f), px(5), px(0.4f));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(3f));
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(MUTED);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(1.4f));
        left.addView(time);
        left.addView(date);

        net = text("", 1.4f, MUTED, true);
        bar.addView(left);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(net);
        return bar;
    }

    private TextView title(String s) {
        TextView t = text(s, 1.5f, MUTED, true);
        t.setPadding(px(5), px(1.6f), 0, px(0.2f));
        return t;
    }

    private View hRow(List<View> items, int gap) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setPadding(px(5), px(0.9f), px(5), px(0.9f));
        for (View v : items) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.rightMargin = gap;
            row.addView(v, p);
        }
        hs.addView(row, new ViewGroup.LayoutParams(-2, -2));
        return hs;
    }

    private View chip(String label, final Runnable r, final String key) {
        final TextView c = text(label, 1.5f, Color.WHITE, true);
        c.setPadding(px(2.2f), px(1.1f), px(2.2f), px(1.1f));
        c.setBackground(round(SURF, px(5)));
        c.setFocusable(true);
        c.setClickable(true);
        c.setTag(key);
        c.setOnClickListener(v -> r.run());
        c.setOnFocusChangeListener((v, f) -> {
            if (f) focusKey = key;
            c.setBackground(round(f ? Color.WHITE : SURF, px(5)));
            c.setTextColor(f ? Color.BLACK : Color.WHITE);
            v.animate().scaleX(f ? 1.07f : 1f).scaleY(f ? 1.07f : 1f).setDuration(130).start();
        });
        return c;
    }

    private View tile(final AppItem a, int w) {
        int h = w * 9 / 16;
        int pad = px(0.3f);

        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setTag(a.pkg);
        if (firstTile == null) firstTile = t;

        final GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(px(1.5f));
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(pad, pad, pad, pad);
        frame.setBackground(ring);

        FrameLayout card = new FrameLayout(this);
        card.setBackground(round(SURF, px(1.2f)));
        card.setClipToOutline(true);
        ImageView img = new ImageView(this);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(card, new FrameLayout.LayoutParams(-1, -1));
        t.addView(frame, new LinearLayout.LayoutParams(w, h));

        final TextView lbl = text(a.label, 1.15f, MUTED, false);
        lbl.setSingleLine();
        lbl.setEllipsize(TextUtils.TruncateAt.END);
        lbl.setPadding(pad, px(0.5f), pad, 0);
        t.addView(lbl, new LinearLayout.LayoutParams(w, -2));

        loadIcon(img, a);

        t.setOnClickListener(v -> launch(a));
        t.setOnLongClickListener(v -> {
            options(a);
            return true;
        });
        t.setOnFocusChangeListener((v, f) -> {
            if (f) focusKey = a.pkg;
            ring.setStroke(f ? px(0.3f) : 0, Color.WHITE);
            lbl.setTextColor(f ? Color.WHITE : MUTED);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(130).start();
        });
        return t;
    }

    private void loadIcon(final ImageView img, final AppItem a) {
        Drawable d = icons.get(a.pkg);
        if (d != null) {
            showIcon(img, d, banners.containsKey(a.pkg));
            return;
        }
        io.execute(() -> {
            Drawable b = null, i = null;
            try {
                b = a.ri.activityInfo.loadBanner(pm);
            } catch (Throwable ignored) {
            }
            if (b == null) {
                try {
                    i = a.ri.loadIcon(pm);
                } catch (Throwable ignored) {
                }
            }
            final Drawable r = b != null ? b : i;
            final boolean isBanner = b != null;
            if (r == null) return;
            ui.post(() -> {
                icons.put(a.pkg, r);
                if (isBanner) banners.put(a.pkg, true);
                showIcon(img, r, isBanner);
            });
        });
    }

    private void showIcon(ImageView img, Drawable d, boolean banner) {
        Drawable.ConstantState cs = d.getConstantState();
        img.setImageDrawable(cs != null ? cs.newDrawable() : d);
        if (banner) {
            img.setPadding(0, 0, 0, 0);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            img.setPadding(px(2.2f), px(2.2f), px(2.2f), px(2.2f));
            img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
    }

    private void rebuild() {
        final String key = focusKey;
        content.removeAllViews();
        firstTile = null;

        // Shortcuts: real TV inputs (HDMI etc.) and system settings
        List<View> chips = new ArrayList<>();
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tm != null) {
                for (TvInputInfo i : tm.getTvInputList()) {
                    if (!i.isPassthroughInput()) continue;
                    final String id = i.getId();
                    chips.add(chip(String.valueOf(i.loadLabel(this)), () -> openInput(id), "in:" + id));
                }
            }
        } catch (Throwable ignored) {
        }
        chips.add(chip("Wi-Fi", () -> openSettings(Settings.ACTION_WIFI_SETTINGS), "s:wifi"));
        chips.add(chip("Bluetooth", () -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS), "s:bt"));
        chips.add(chip("Display", () -> openSettings(Settings.ACTION_DISPLAY_SETTINGS), "s:display"));
        chips.add(chip("Sound", () -> openSettings(Settings.ACTION_SOUND_SETTINGS), "s:sound"));
        chips.add(chip("Settings", () -> openSettings(Settings.ACTION_SETTINGS), "s:all"));
        content.addView(hRow(chips, px(1.2f)));

        int gap = px(1.6f);
        int w = (int) ((100 - 10 - (COLS - 1) * 1.6f) / COLS * u);

        List<View> rec = new ArrayList<>();
        for (String p : recentPkgs()) {
            AppItem a = byPkg.get(p);
            if (a != null) rec.add(tile(a, w));
        }
        if (!rec.isEmpty()) {
            content.addView(title("Recent"));
            content.addView(hRow(rec, gap));
        }

        content.addView(title("Apps"));
        List<View> row = new ArrayList<>();
        for (AppItem a : apps) {
            row.add(tile(a, w));
            if (row.size() == COLS) {
                content.addView(hRow(row, gap));
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) content.addView(hRow(row, gap));

        View target = key == null ? null : content.findViewWithTag(key);
        if (target == null) target = firstTile;
        if (target != null) target.requestFocus();
    }

    // ---------------------------------------------------------------- actions

    private void launch(AppItem a) {
        try {
            startActivity(a.intent);
            addRecent(a.pkg);
        } catch (Exception e) {
            toast(a.label + " can't be opened");
        }
    }

    private void options(final AppItem a) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(a.label)
                .setItems(new CharSequence[]{"Open", "App info", "Uninstall"}, (d, which) -> {
                    if (which == 0) launch(a);
                    else if (which == 1) safeStart(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                    else safeStart(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
                })
                .show();
    }

    private void openInput(String id) {
        safeStart(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)));
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            safeStart(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Not available on this TV");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void updateNet() {
        String s = "Offline";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            if (n != null && n.isConnected()) {
                s = n.getType() == ConnectivityManager.TYPE_ETHERNET ? "Ethernet"
                        : n.getType() == ConnectivityManager.TYPE_WIFI ? "Wi-Fi" : "Online";
            }
        } catch (Throwable ignored) {
        }
        net.setText(s);
    }

    /** Ask the display for its highest refresh rate at the current resolution. */
    private void preferHighRefreshRate() {
        if (Build.VERSION.SDK_INT < 23) return;
        Display d = getWindowManager().getDefaultDisplay();
        Display.Mode cur = d.getMode();
        Display.Mode best = cur;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() == cur.getPhysicalWidth()
                    && m.getPhysicalHeight() == cur.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate()) {
                best = m;
            }
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(lp);
    }
}
