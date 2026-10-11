package app.videotv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays a film with the TV's own video player instead of inside the web page. Decoding and
 * drawing video in a WebView is the heaviest thing the app could do; a plain VideoView uses the
 * hardware decoder directly, which is what keeps low-memory TVs from stuttering.
 *
 * The controls are drawn to look like the web page's player (static/style.css, "player").
 *
 * Remote: Left/Right seek (held = bigger steps), OK play/pause, Down opens the options row,
 * Back returns to the film list.
 */
public class PlayerActivity extends Activity {
    static final String URL = "url", TITLE = "title", NAME = "name", POSITION = "pos", DONE = "done";
    static final int ACCENT = 0xFFF6B300, ACCENT_INK = 0xFF151000;
    private static final int OVERLAY_MS = 3500, FLASH_MS = 700;
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.75f};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<TextView> pills = new ArrayList<>();
    private SharedPreferences prefs;
    private MediaPlayer media;
    private VideoView video;
    private View top, bottom;
    private SeekLine bar;
    private ProgressBar spinner;
    private TextView time, status, speedPill;
    private Typeface regular, bold, digits, boldDigits; // the page's fonts, from the app's copy of ./static/fonts
    private float rem, buffered;
    private int speedAt, menu = -1; // menu: which option pill is selected, -1 when the row is closed
    private boolean prepared, ended, reported;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        regular = font("alexandria-400", Typeface.DEFAULT);
        bold = font("alexandria-600", Typeface.DEFAULT_BOLD);
        digits = font("digits-400", null);
        boldDigits = font("digits-600", null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        // Same units as the page's stylesheet: 1rem is 1.12% of the screen width, the side gutter 4%.
        int width = getResources().getDisplayMetrics().widthPixels;
        rem = width * .0112f;
        int side = Math.round(width * .04f);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout topbar = new LinearLayout(this);
        topbar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        topbar.setGravity(Gravity.CENTER_VERTICAL);
        topbar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xD9000000, 0}));
        topbar.setPadding(side, px(1.4f), side, px(3.5f));
        TextView title = label(1.3f, Color.WHITE, true);
        put(title, getIntent().getStringExtra(TITLE));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleAt = new LinearLayout.LayoutParams(0, -2, 1);
        titleAt.setMarginStart(px(1.1f));
        topbar.addView(pill("بازگشت"));
        topbar.addView(title, titleAt);
        root.addView(topbar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        top = topbar;

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xE0000000, 0}));
        controls.setPadding(side, px(5), side, px(1.5f));
        bar = new SeekLine(this, rem);
        controls.addView(bar, new LinearLayout.LayoutParams(-1, px(1.6f)));

        LinearLayout row = new LinearLayout(this);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        time = label(1, Color.WHITE, false);
        time.setTextDirection(View.TEXT_DIRECTION_LTR); // a timeline runs left to right
        row.addView(time);
        if (Build.VERSION.SDK_INT >= 23) { // older Android can't change the playback speed
            speedPill = pill("سرعت");
            pills.add(speedPill);
        }
        pills.add(pill("از اول"));
        for (int i = 0; i < pills.size(); i++) {
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2);
            at.setMarginStart(px(i == 0 ? 1.4f : .5f));
            row.addView(pills.get(i), at);
        }
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        TextView hints = label(.8f, 0xFFA3A3A3, false);
        put(hints, "چپ و راست: ۱۰ ثانیه      OK: پخش / مکث      پایین: گزینه‌ها      Back: بازگشت");
        row.addView(hints);
        LinearLayout.LayoutParams rowAt = new LinearLayout.LayoutParams(-1, -2);
        rowAt.topMargin = px(.5f);
        controls.addView(row, rowAt);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        bottom = controls;

        status = label(1.15f, Color.WHITE, true);
        status.setBackground(rounded(0xBF000000));
        status.setPadding(px(1.4f), px(.5f), px(1.4f), px(.5f));
        status.setVisibility(View.INVISIBLE);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);

        speedAt = Math.max(0, Math.min(SPEEDS.length - 1, prefs.getInt("speed", 0)));
        showSpeed();
        video.setOnPreparedListener(player -> {
            prepared = true;
            media = player;
            player.setOnBufferingUpdateListener((p, percent) -> buffered = percent / 100f);
            spinner.setVisibility(View.GONE);
            int start = getIntent().getIntExtra(POSITION, 0) * 1000;
            if (start > 5000 && start < video.getDuration() - 10000) video.seekTo(start); // resume
            video.start();
            if (speedAt != 0) applySpeed();
            showOverlay();
        });
        video.setOnCompletionListener(player -> {
            ended = true;
            close();
        });
        video.setOnErrorListener((player, what, extra) -> {
            spinner.setVisibility(View.GONE);
            say("پخش این فیلم ممکن نشد");
            return true;
        });
        video.setVideoURI(Uri.parse(getIntent().getStringExtra(URL)));
        ui.postDelayed(this::tick, 1000);
    }

    private int px(float rems) {
        return Math.round(rems * rem);
    }

    private TextView label(float rems, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, rems * rem);
        t.setTextColor(color);
        t.setTypeface(bold ? this.bold : regular);
        return t;
    }

    private Typeface font(String name, Typeface otherwise) {
        try {
            return Typeface.createFromAsset(getAssets(), "static/fonts/" + name + ".ttf");
        } catch (RuntimeException e) {
            return otherwise;
        }
    }

    /** Sets a label's text, with its Persian digits in the digits font. */
    private void put(TextView label, String text) {
        label.setText(DigitSpan.mark(text == null ? "" : text, label.getTypeface() == bold ? boldDigits : digits));
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(rem * 99);
        return shape;
    }

    private TextView pill(String text) {
        TextView t = label(.85f, Color.WHITE, false);
        put(t, text);
        t.setBackground(rounded(0x24FFFFFF));
        t.setPadding(px(1), px(.4f), px(1), px(.4f));
        return t;
    }

    private static String fa(String text) {
        StringBuilder out = new StringBuilder(text);
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c >= '0' && c <= '9') out.setCharAt(i, (char) ('۰' + c - '0'));
        }
        return out.toString();
    }

    private static String clock(int ms) {
        int s = Math.max(ms, 0) / 1000;
        return fa(s >= 3600
                ? String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60));
    }

    /** Refreshes the timeline once a second, and only while it is on screen. */
    private void tick() {
        if (prepared && bottom.getVisibility() == View.VISIBLE) drawTimeline();
        if (!isFinishing()) ui.postDelayed(this::tick, 1000);
    }

    private void drawTimeline() {
        int length = video.getDuration(), at = video.getCurrentPosition();
        bar.set(length > 0 ? (float) at / length : 0, buffered);
        put(time, clock(at) + " / " + clock(length));
    }

    private void showOverlay() {
        if (prepared) drawTimeline();
        fade(top, true);
        fade(bottom, true);
        ui.removeCallbacksAndMessages(bottom); // the controls are the token for their own hide timer
        ui.postAtTime(this::hideOverlay, bottom, android.os.SystemClock.uptimeMillis() + OVERLAY_MS);
    }

    private void hideOverlay() {
        if (!video.isPlaying() || menu >= 0) return;
        fade(top, false);
        fade(bottom, false);
    }

    /** Fades a layer in or out, as the page's player does. */
    private static void fade(View layer, boolean show) {
        layer.animate().cancel();
        if (show) {
            if (layer.getVisibility() != View.VISIBLE) {
                layer.setAlpha(0);
                layer.setVisibility(View.VISIBLE);
            }
            layer.animate().alpha(1).setDuration(200).withEndAction(null);
        } else {
            layer.animate().alpha(0).setDuration(350).withEndAction(() -> layer.setVisibility(View.INVISIBLE));
        }
    }

    /** The message in the middle of the picture; it pops in. */
    private void say(String text) {
        put(status, text);
        status.animate().cancel();
        status.setAlpha(1);
        status.setScaleX(.8f);
        status.setScaleY(.8f);
        status.setVisibility(View.VISIBLE);
        status.animate().scaleX(1).scaleY(1).setDuration(280).withEndAction(null);
    }

    private void flash(String text) {
        say(text);
        ui.removeCallbacksAndMessages(status);
        ui.postAtTime(() -> fade(status, false), status, android.os.SystemClock.uptimeMillis() + FLASH_MS);
        showOverlay();
    }

    /** The options row under the timeline: Down selects it, Left/Right move, OK presses, Up or Back leaves. */
    private void selectPill(int index) {
        menu = index;
        for (int i = 0; i < pills.size(); i++) {
            TextView p = pills.get(i);
            boolean on = i == index;
            p.setBackground(rounded(on ? ACCENT : 0x24FFFFFF));
            p.setTextColor(on ? ACCENT_INK : Color.WHITE);
            p.animate().scaleX(on ? 1.1f : 1).scaleY(on ? 1.1f : 1).setDuration(200);
        }
        showOverlay();
    }

    private void pressPill() {
        if (!prepared) return;
        if (pills.get(menu) == speedPill) {
            speedAt = (speedAt + 1) % SPEEDS.length;
            prefs.edit().putInt("speed", speedAt).apply();
            applySpeed();
        } else {
            video.seekTo(0);
            video.start();
            flash("از اول");
        }
    }

    private void showSpeed() {
        if (speedPill == null) return;
        float rate = SPEEDS[speedAt];
        String number = rate == (int) rate ? String.valueOf((int) rate) : String.valueOf(rate);
        put(speedPill, "سرعت " + fa(number).replace('.', '٫') + "×");
    }

    private void applySpeed() {
        showSpeed();
        if (Build.VERSION.SDK_INT < 23 || media == null) return;
        boolean playing = video.isPlaying();
        try {
            media.setPlaybackParams(media.getPlaybackParams().setSpeed(SPEEDS[speedAt]));
            if (!playing) video.pause(); // setting a speed starts playback by itself
        } catch (RuntimeException ignored) { // some TV decoders refuse; the film just stays at normal speed
        }
    }

    private void seekBy(int seconds) {
        if (!prepared) return;
        int target = Math.max(0, Math.min(video.getDuration() - 1000, video.getCurrentPosition() + seconds * 1000));
        video.seekTo(target);
        int n = Math.abs(seconds);
        flash(fa(n >= 60 ? n / 60 + " دقیقه" : n + " ثانیه") + (seconds > 0 ? " جلو" : " عقب"));
    }

    private void toggle() {
        if (!prepared) return;
        if (video.isPlaying()) {
            video.pause();
            flash("مکث");
        } else {
            video.start();
            flash("پخش");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        // holding the button speeds up: 10s, then 30s, then a minute per step
        int held = event.getRepeatCount();
        int step = held > 20 ? 60 : held > 6 ? 30 : 10;
        if (menu >= 0) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: // the row is right-to-left, so Left is the next pill
                    selectPill(Math.min(menu + 1, pills.size() - 1));
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    selectPill(Math.max(menu - 1, 0));
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    selectPill(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (held == 0) pressPill();
                    return true;
                default: // media buttons and Back work as usual
            }
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(step);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-step);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(30);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-30);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (held == 0) toggle();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                selectPill(0);
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                close();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    /** Leaves the position for the film list (MainActivity.onResume), which keeps "continue watching", and closes. */
    private void close() {
        if (reported) return;
        reported = true;
        prefs.edit()
                .putString(NAME, getIntent().getStringExtra(NAME))
                .putInt(POSITION, prepared && !ended ? video.getCurrentPosition() / 1000 : 0)
                .putBoolean(DONE, ended)
                .apply();
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (menu >= 0) selectPill(-1);
        else close();
    }

    @Override
    protected void onPause() {
        super.onPause();
        close(); // leaving with Home also saves the position and frees the decoder; onStop would be too late for the list
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        video.stopPlayback();
        super.onDestroy();
    }
}
