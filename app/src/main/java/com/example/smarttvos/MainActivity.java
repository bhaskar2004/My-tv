package com.example.smarttvos;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView web;
    private long lastRepeat = 0;
    private long lastLaunch = 0;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);

        web = new WebView(this);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setLongClickable(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                // Only ever show our own bundled page.
                return !url.startsWith("file:///android_asset/");
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                // 1) Smoothness + seamless background (no shadow animation, scale only)
                v.evaluateJavascript(
                    "(function(){var s=document.createElement('style');"
                    + "s.textContent='html,body{background:var(--bg)}"
                    + "*{-webkit-tap-highlight-color:transparent}"
                    + ".fz{transition:transform .18s cubic-bezier(.2,.8,.2,1),"
                    + "background-color .15s,color .15s}';"
                    + "document.head.appendChild(s)})()", null);
                // 2) Turn the prototype's "Opening X..." message into a real launch
                v.evaluateJavascript(
                    "(function(){var t=document.getElementById('toast');"
                    + "if(!t||window.__tvObs)return;window.__tvObs=1;"
                    + "new MutationObserver(function(){"
                    + "var m=/^Opening (.+).$/.exec(t.textContent);"
                    + "if(m)AndroidTV.launch(m[1])"
                    + "}).observe(t,{childList:true})})()", null);
            }

            @TargetApi(Build.VERSION_CODES.O)
            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // WebView process died (often low memory): restart instead of crashing.
                runOnUiThread(() -> MainActivity.this.recreate());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidTV");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
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

    private class Bridge {
        @JavascriptInterface
        public void exit() {
            runOnUiThread(() -> finish());
        }

        @JavascriptInterface
        public void launch(final String name) {
            runOnUiThread(() -> launchApp(name));
        }
    }

    private void launchApp(String name) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 1500) return; // ignore double presses
        lastLaunch = now;

        String[] pkgs;
        switch (name) {
            case "YouTube":
                pkgs = new String[]{"com.google.android.youtube.tv", "com.google.android.youtube"};
                break;
            case "Netflix":
                pkgs = new String[]{"com.netflix.ninja", "com.netflix.mediaclient"};
                break;
            case "Prime Video":
                pkgs = new String[]{"com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"};
                break;
            case "Disney+":
                pkgs = new String[]{"com.disney.disneyplus"};
                break;
            case "Spotify":
                pkgs = new String[]{"com.spotify.tv.android", "com.spotify.music"};
                break;
            case "Browser":
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e) {
                    notInstalled(name);
                }
                return;
            case "Gallery":
                try {
                    startActivity(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_APP_GALLERY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e) {
                    notInstalled(name);
                }
                return;
            default:
                return;
        }
        for (String pkg : pkgs) {
            String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String cat : cats) {
                try {
                    startActivity(new Intent(Intent.ACTION_MAIN)
                        .addCategory(cat)
                        .setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (ActivityNotFoundException ignored) { }
            }
        }
        notInstalled(name);
    }

    private void notInstalled(String name) {
        Toast.makeText(this, name + " isn't installed on this TV", Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String key = null;    // key to send to the page
        String screen = null; // sidebar screen to open
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:    key = "ArrowUp"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:  key = "ArrowDown"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  key = "ArrowLeft"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: key = "ArrowRight"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: key = "Enter"; break;
            case KeyEvent.KEYCODE_BACK:       key = "Escape"; break;
            case KeyEvent.KEYCODE_MENU:       key = "h"; break;
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_ASSIST:     screen = "search"; break;
            case KeyEvent.KEYCODE_GUIDE:      screen = "live"; break;
            case KeyEvent.KEYCODE_TV_INPUT:   screen = "inputs"; break;
            case KeyEvent.KEYCODE_SETTINGS:   screen = "settings"; break;
        }
        if (key == null && screen == null) return super.dispatchKeyEvent(e);

        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) { // held button: don't flood the page
                long t = SystemClock.uptimeMillis();
                if (t - lastRepeat < 70) return true;
                lastRepeat = t;
            }
            if (key != null) sendKey(key); else openScreen(screen);
        }
        return true;
    }

    private void sendKey(String key) {
        web.evaluateJavascript(
            "document.dispatchEvent(new KeyboardEvent('keydown',{key:'" + key
            + "',bubbles:true,cancelable:true}))", null);
    }

    private void openScreen(String id) {
        web.evaluateJavascript(
            "(function(){var e=document.querySelector('[data-s=" + id
            + "]');if(e)e.click()})()", null);
    }

    @Override
    public void onBackPressed() { sendKey("Escape"); }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        preferHighRefreshRate();
        web.onResume();
        web.requestFocus();
    }
    @Override protected void onPause()  { web.onPause(); super.onPause(); }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }
}
