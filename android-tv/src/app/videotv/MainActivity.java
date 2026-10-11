package app.videotv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Android TV shell for the video web app, and the TV's home screen (launcher): shows server.py's
 * pages full-screen in a WebView, starting on its home screen page, /tv. While the server can't
 * be reached it shows the copy of that page built into the app, which still has the apps, the
 * inputs and the clock, and keeps looking for the server.
 * The remote's D-pad and OK reach the page as arrow keys and Enter, which the page already handles.
 */
public class MainActivity extends Activity {
    private static final int PORT = 8000;
    private static final String PING_REPLY = "video-app";
    private static final String HOME_PATH = "/tv", OFFLINE_PAGE = "file:///android_asset/tv.html";
    private static final int RETRY_MS = 60000;
    // Says where Back was pressed: "list" on the film list, "home" on the home screen, else "page",
    // where the page gets the key to close its own overlays.
    private static final String BACK_SCRIPT =
            "(function(){if(location.pathname==='/')return 'list';"
            + "document.dispatchEvent(new KeyboardEvent('keydown',"
            + "{key:'Escape',keyCode:27,bubbles:true,cancelable:true}));"
            + "return location.pathname==='/tv'||location.protocol==='file:'?'home':'page';})()";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WebView web;
    private LinearLayout setup;
    private EditText address;
    private TextView status;
    private final Runnable retry = this::tryServer;
    private boolean offline, goHome;
    private volatile boolean scanning;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(buildWeb(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildSetup(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        String saved = prefs.getString("url", null);
        if (saved != null) {
            connect(saved);
        } else {
            showOffline();
            scan();
        }
    }

    /** Home was pressed while this app was open. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasCategory(Intent.CATEGORY_HOME)) goHome = true;
    }

    private WebView buildWeb() {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // the page keeps "resume where I left off" in localStorage
        s.setMediaPlaybackRequiresUserGesture(false);
        // Tells the server this is the TV app, so it sends the lighter version of its pages.
        s.setUserAgentString(s.getUserAgentString() + " MousaviTV");
        TvBridge bridge = new TvBridge(this);
        web.addJavascriptInterface(bridge, "TV");
        web.setWebViewClient(new PageClient(this, bridge));
        return web;
    }

    private View buildSetup() {
        setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.VERTICAL);
        setup.setGravity(Gravity.CENTER);
        setup.setBackgroundColor(0xFF0B0C0F);
        setup.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        setup.setPadding(dp(96), dp(48), dp(96), dp(48));
        setup.setVisibility(View.GONE);

        TextView title = text("موسوی مووی", 26, 0xFFF4F2EE);
        TextView hint = text("برای اتصال، آدرسی را وارد کنید که هنگام اجرای server.py روی کامپیوتر نشان داده می‌شود. "
                + "تلویزیون و کامپیوتر باید به یک شبکه وصل باشند.", 15, 0xFF9097A3);
        status = text("", 15, 0xFFF6A723);

        address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setHint("192.168.1.4:" + PORT);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setGravity(Gravity.CENTER);
        address.setOnEditorActionListener((v, action, event) -> {
            connect(address.getText().toString());
            return true;
        });

        Button go = new Button(this);
        go.setText("اتصال");
        go.setOnClickListener(v -> connect(address.getText().toString()));
        Button find = new Button(this);
        find.setText("جستجوی خودکار");
        find.setOnClickListener(v -> scan());
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(go);
        buttons.addView(find);

        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(dp(420), -2);
        wide.topMargin = dp(20);
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(-2, -2);
        wrap.topMargin = dp(12);
        setup.addView(title);
        setup.addView(hint, wrap);
        setup.addView(address, wide);
        setup.addView(buttons, wrap);
        setup.addView(status, wrap);
        return setup;
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Accepts "192.168.1.4", "192.168.1.4:8000" or a full http:// URL. */
    private static String normalize(String input) {
        String url = input.trim();
        if (url.isEmpty()) return null;
        if (!url.contains("://")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.indexOf(':', url.indexOf("://") + 3) < 0) url += ":" + PORT;
        return url;
    }

    private void connect(String input) {
        String url = normalize(input);
        if (url == null) return;
        prefs.edit().putString("url", url).apply();
        address.setText(url.replace("http://", ""));
        setup.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.requestFocus();
        offline = false;
        ui.removeCallbacks(retry);
        web.loadUrl(url + HOME_PATH);
    }

    /** The home screen kept inside the app, until the server answers again. */
    private void showOffline() {
        offline = true;
        web.loadUrl(OFFLINE_PAGE);
        ui.removeCallbacks(retry);
        ui.postDelayed(retry, RETRY_MS);
    }

    private void tryServer() {
        if (!offline || isFinishing()) return;
        ui.postDelayed(retry, RETRY_MS);
        if (setup.getVisibility() == View.VISIBLE) return; // an address is being typed
        final String saved = prefs.getString("url", null);
        new Thread(() -> {
            final boolean found = saved != null && ping(saved);
            ui.post(() -> {
                if (!offline || isFinishing()) return;
                if (found) connect(saved);
                else scan(); // the computer may have a new address
            });
        }).start();
    }

    /** A link in the page: films go to the native player, and the offline page's links look for the server. */
    boolean openLink(String url) {
        if (url.startsWith("file:")) {
            showSetup("");
            scan();
            return true;
        }
        return openFilm(url);
    }

    /**
     * Opens a /watch/ link in the native player instead of the page's own, starting from the
     * position the page remembers. Returns false for any other link.
     */
    private boolean openFilm(String url) {
        Uri link = Uri.parse(url);
        String path = link.getPath();
        if (path == null || !path.startsWith("/watch/") || link.getEncodedPath() == null) return false;
        String name = path.substring(7);
        String video = link.getScheme() + "://" + link.getEncodedAuthority() + "/video/" + link.getEncodedPath().substring(7);
        int dot = name.lastIndexOf('.');
        Intent player = new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.URL, video)
                .putExtra(PlayerActivity.NAME, name)
                .putExtra(PlayerActivity.TITLE, dot > 0 ? name.substring(0, dot) : name);
        if ("1".equals(link.getQueryParameter("restart"))) {
            startActivity(player);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            return true;
        }
        web.evaluateJavascript("localStorage.getItem(" + JSONObject.quote("pos:" + name) + ")", saved -> {
            int position = 0;
            try {
                position = (int) Float.parseFloat(saved.replace("\"", ""));
            } catch (RuntimeException ignored) { // nothing saved yet
            }
            startActivity(player.putExtra(PlayerActivity.POSITION, position));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });
        return true;
    }

    void failed() {
        showOffline(); // also replaces WebView's own error page
    }

    private void showSetup(String message) {
        status.setText(message);
        web.setVisibility(View.GONE);
        setup.setVisibility(View.VISIBLE);
        address.requestFocus();
    }

    /** Looks for the server on every address of this device's /24 network. */
    private void scan() {
        if (scanning) return;
        scanning = true;
        status.setText("در حال جستجو روی شبکه…");
        new Thread(() -> {
            final AtomicReference<String> hit = new AtomicReference<>();
            String prefix = localPrefix();
            if (prefix != null) {
                ExecutorService pool = Executors.newFixedThreadPool(48);
                CountDownLatch done = new CountDownLatch(254);
                for (int i = 1; i <= 254; i++) {
                    final String base = "http://" + prefix + i + ":" + PORT;
                    pool.execute(() -> {
                        if (hit.get() == null && ping(base)) hit.compareAndSet(null, base);
                        done.countDown();
                    });
                }
                try {
                    done.await();
                } catch (InterruptedException ignored) {
                }
                pool.shutdownNow();
            }
            ui.post(() -> {
                scanning = false;
                if (isFinishing()) return;
                if (hit.get() != null) {
                    if (offline || setup.getVisibility() == View.VISIBLE) connect(hit.get());
                } else status.setText("سروری پیدا نشد. مطمئن شوید server.py در حال اجراست، یا آدرس را دستی وارد کنید.");
            });
        }).start();
    }

    private static String localPrefix() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        return ip.substring(0, ip.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean ping(String base) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/ping").openConnection();
            c.setConnectTimeout(600);
            c.setReadTimeout(1000);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                return PING_REPLY.equals(r.readLine());
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { // Menu on the remote reopens the server address screen
            showSetup("");
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (setup.getVisibility() == View.VISIBLE) {
            hideSetup();
            return;
        }
        web.evaluateJavascript(BACK_SCRIPT, where -> {
            if (where == null) return;
            if (where.contains("list")) web.loadUrl(prefs.getString("url", "") + HOME_PATH);
            else if (where.contains("home") && !isHomeApp()) moveTaskToBack(true); // as the launcher there is nowhere to go back to
        });
    }

    private void hideSetup() {
        setup.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.requestFocus();
    }

    /** Whether the TV uses this app as its home screen. */
    private boolean isHomeApp() {
        android.content.pm.ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && home.activityInfo != null && getPackageName().equals(home.activityInfo.packageName);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        final boolean home = goHome;
        goHome = false;
        if (home) hideSetup();
        // What the native player left behind goes into the page's storage, which keeps "continue watching".
        final String played = prefs.getString(PlayerActivity.NAME, null);
        prefs.edit().remove(PlayerActivity.NAME).apply();
        if (offline || (played == null && !home)) return;
        String script = "";
        if (played != null) {
            int position = prefs.getInt(PlayerActivity.POSITION, 0);
            String pos = JSONObject.quote("pos:" + played), done = JSONObject.quote("done:" + played);
            script = "localStorage.setItem('last'," + JSONObject.quote(played) + ");";
            if (prefs.getBoolean(PlayerActivity.DONE, false)) {
                script += "localStorage.removeItem(" + pos + ");localStorage.setItem(" + done + ",'1');";
            } else if (position > 5) {
                script += "localStorage.setItem(" + pos + ",'" + position + "');";
            }
        }
        web.evaluateJavascript("try{" + script + "}catch(e){}", ignored -> {
            String at = web.getUrl();
            boolean onHome = at != null && HOME_PATH.equals(Uri.parse(at).getPath());
            if (home && !onHome) web.loadUrl(prefs.getString("url", "") + HOME_PATH);
            else if (played != null) web.reload(); // redraws the list with the new position
        });
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
