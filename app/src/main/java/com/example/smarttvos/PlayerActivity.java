package com.example.smarttvos;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.Locale;

/**
 * Minimal full-screen video player.
 * Remote: Left/Right seek, OK play/pause, Up/Down show controls, Back exits.
 * Controls fade away on their own after a few seconds of playback.
 */
public class PlayerActivity extends Activity {

    private static final int HIDE_MS = 4000;

    private VideoView video;
    private View controls;
    private ProgressBar spinner;
    private ProgressView bar;
    private TextView timeL, timeR;
    private Glyph gBack, gPlay, gFwd;
    private float u;
    private boolean ready, ended, resumeAfter = true;
    private int resumePos;
    private long lastSeek;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hide = () -> {
        if (video.isPlaying()) setControls(false);
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            ui.postDelayed(this, 250);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        u = getResources().getDisplayMetrics().widthPixels / 100f;

        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }

        Typeface med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        Typeface reg = Typeface.create("sans-serif", Typeface.NORMAL);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(px(6), px(6), Gravity.CENTER));

        // Bottom overlay: title, three controls, animated progress bar, times
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(6), px(14), px(6), px(4));
        c.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000}));
        controls = c;

        TextView title = new TextView(this);
        title.setText(titleOf(uri));
        title.setTextColor(Color.WHITE);
        title.setTypeface(med);
        title.setLetterSpacing(-0.01f);
        title.setSingleLine();
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(2.8f));
        c.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        gBack = new Glyph(this, Glyph.BACK, med);
        gPlay = new Glyph(this, Glyph.PAUSE, med);
        gFwd = new Glyph(this, Glyph.FWD, med);
        row.addView(gBack, glyphLp(6.5f));
        row.addView(gPlay, glyphLp(8.5f));
        row.addView(gFwd, glyphLp(6.5f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.topMargin = px(1.6f);
        rp.bottomMargin = px(2.2f);
        c.addView(row, rp);

        bar = new ProgressView(this);
        c.addView(bar, new LinearLayout.LayoutParams(-1, px(1.6f)));

        LinearLayout times = new LinearLayout(this);
        timeL = timeText(reg);
        timeR = timeText(reg);
        timeR.setGravity(Gravity.END);
        times.addView(timeL, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(timeR, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = px(0.6f);
        c.addView(times, tp);

        root.addView(c, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        // Player callbacks
        video.setOnPreparedListener(mp -> {
            ready = true;
            spinner.setVisibility(View.GONE);
            if (resumePos > 0) video.seekTo(resumePos);
            if (resumeAfter) video.start();
            syncPlayIcon();
            update();
            show();
        });
        video.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                spinner.setVisibility(View.VISIBLE);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                    || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                spinner.setVisibility(View.GONE);
            }
            return false;
        });
        video.setOnCompletionListener(mp -> {
            ended = true;
            syncPlayIcon();
            show();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Can't play this video", Toast.LENGTH_LONG).show();
            ui.postDelayed(this::finish, 1500);
            return true;
        });
        video.setVideoURI(uri);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
        if (resumeAfter) video.start();
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hide);
        if (video != null) {
            resumePos = video.getCurrentPosition();
            resumeAfter = video.isPlaying() || !ready;
            video.pause();
        }
        super.onPause();
    }

    // ---------------------------------------------------------------- remote

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) seekKey(e, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) seekKey(e, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && e.getRepeatCount() == 0) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down && !video.isPlaying()) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && video.isPlaying()) toggle();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) show();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    /** Short press = 10s; holding the key speeds up to 30s steps. */
    private void seekKey(KeyEvent e, int dir) {
        long now = SystemClock.uptimeMillis();
        int r = e.getRepeatCount();
        if (r > 0 && now - lastSeek < 140) return;
        lastSeek = now;
        seekBy(dir * (r > 6 ? 30000 : 10000));
    }

    // ---------------------------------------------------------------- playback

    private void seekBy(int ms) {
        if (!ready) return;
        int dur = video.getDuration();
        if (dur <= 0) return;
        int p = Math.max(0, Math.min(dur - 500, video.getCurrentPosition() + ms));
        video.seekTo(p);
        ended = false;
        bar.setTarget((float) p / dur, false);
        timeL.setText(fmt(p));
        (ms < 0 ? gBack : gFwd).pulse();
        show();
    }

    private void toggle() {
        if (!ready) return;
        if (ended) {
            ended = false;
            video.seekTo(0);
            video.start();
        } else if (video.isPlaying()) {
            video.pause();
        } else {
            video.start();
        }
        syncPlayIcon();
        gPlay.pulse();
        show();
    }

    private void syncPlayIcon() {
        gPlay.set(video.isPlaying() ? Glyph.PAUSE : Glyph.PLAY);
    }

    private void update() {
        if (!ready) return;
        int dur = video.getDuration();
        if (dur <= 0) return;
        int pos = video.getCurrentPosition();
        bar.setTarget((float) pos / dur, !controlsOn);
        timeL.setText(fmt(pos));
        timeR.setText(fmt(dur));
    }

    // ---------------------------------------------------------------- auto-hide controls

    private boolean controlsOn = true;

    private void show() {
        ui.removeCallbacks(hide);
        setControls(true);
        if (video.isPlaying()) ui.postDelayed(hide, HIDE_MS);
    }

    private void setControls(boolean on) {
        controlsOn = on;
        controls.animate().cancel();
        controls.animate().alpha(on ? 1f : 0f).setDuration(on ? 150 : 350).start();
    }

    // ---------------------------------------------------------------- helpers

    private int px(float units) {
        return Math.round(units * u);
    }

    private LinearLayout.LayoutParams glyphLp(float size) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(px(size), px(size));
        p.rightMargin = px(2.4f);
        return p;
    }

    private TextView timeText(Typeface tf) {
        TextView t = new TextView(this);
        t.setTextColor(0xCCFFFFFF);
        t.setTypeface(tf);
        t.setLetterSpacing(0.03f);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(1.4f));
        t.setText("0:00");
        return t;
    }

    private static String titleOf(Uri uri) {
        String t = uri.getQueryParameter("title");
        return t != null ? t : "";
    }

    private String titleOf2(Uri uri) {
        String t = getIntent().getStringExtra("title");
        if (t == null) t = uri.getLastPathSegment();
        if (t == null) t = "Video";
        int dot = t.lastIndexOf('.');
        if (dot > 0 && t.length() - dot <= 5) t = t.substring(0, dot);
        return t;
    }

    private static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    // ---------------------------------------------------------------- custom views

    /** Smooth progress bar: the played part eases toward the real position every frame. */
    static class ProgressView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float target, shown;
        private boolean animating;

        private final Runnable step = new Runnable() {
            @Override
            public void run() {
                shown += (target - shown) * 0.2f;
                if (Math.abs(target - shown) < 0.0005f) {
                    shown = target;
                    animating = false;
                } else {
                    postOnAnimation(this);
                }
                invalidate();
            }
        };

        ProgressView(Context c) {
            super(c);
        }

        void setTarget(float f, boolean snap) {
            target = Math.max(0f, Math.min(1f, f));
            if (snap) {
                shown = target;
                invalidate();
            } else if (!animating) {
                animating = true;
                postOnAnimation(step);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), th = h * 0.32f, cy = h / 2f;
            p.setColor(0x40FFFFFF);
            r.set(0, cy - th / 2, w, cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, p);
            p.setColor(Color.WHITE);
            r.set(0, cy - th / 2, Math.max(th, w * shown), cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, p);
            c.drawCircle(Math.max(h * 0.28f, Math.min(w - h * 0.28f, w * shown)), cy, h * 0.28f, p);
        }
    }

    /** Hand-drawn play / pause / seek icons, so no icon fonts or images are needed. */
    static class Glyph extends View {
        static final int PLAY = 0, PAUSE = 1, BACK = 2, FWD = 3;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int type;

        Glyph(Context c, int type, Typeface tf) {
            super(c);
            this.type = type;
            p.setTypeface(tf);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setTextAlign(Paint.Align.CENTER);
        }

        void set(int t) {
            type = t;
            invalidate();
        }

        void pulse() {
            animate().cancel();
            setScaleX(1.22f);
            setScaleY(1.22f);
            animate().scaleX(1f).scaleY(1f).setDuration(260).start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x26FFFFFF);
            c.drawCircle(cx, cy, s / 2f, p);
            p.setColor(Color.WHITE);
            if (type == PLAY) {
                path.reset();
                path.moveTo(cx - s * 0.1f, cy - s * 0.19f);
                path.lineTo(cx + s * 0.21f, cy);
                path.lineTo(cx - s * 0.1f, cy + s * 0.19f);
                path.close();
                c.drawPath(path, p);
            } else if (type == PAUSE) {
                float bw = s * 0.1f, bh = s * 0.4f;
                c.drawRoundRect(cx - s * 0.16f, cy - bh / 2, cx - s * 0.16f + bw, cy + bh / 2, bw / 2, bw / 2, p);
                c.drawRoundRect(cx + s * 0.06f, cy - bh / 2, cx + s * 0.06f + bw, cy + bh / 2, bw / 2, bw / 2, p);
            } else {
                float d = type == BACK ? -1f : 1f, hw = s * 0.05f, sp = s * 0.12f, cyy = cy - s * 0.05f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.065f);
                for (int k = 0; k < 2; k++) {
                    float x = cx + d * (k * sp - sp / 2f);
                    path.reset();
                    path.moveTo(x - d * hw, cyy - s * 0.13f);
                    path.lineTo(x + d * hw, cyy);
                    path.lineTo(x - d * hw, cyy + s * 0.13f);
                    c.drawPath(path, p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(s * 0.19f);
                c.drawText("10", cx, cy + s * 0.31f, p);
            }
        }
    }
}
