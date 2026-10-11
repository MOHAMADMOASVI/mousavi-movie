package app.videotv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Shows one of the TV's inputs (HDMI, AV, a tuner) full-screen, tuned by this app itself.
 * Handing the input to the TV maker's own TV app instead doesn't work everywhere: on some TVs
 * that app ignores which input was asked for and stays on the one it showed last.
 * Back returns to the home screen.
 */
public class InputActivity extends Activity {
    static final String ID = "id", NAME = "name";

    private TvView tv;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        tv = new TvView(this);
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextSize(20);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);

        String id = getIntent().getStringExtra(ID);
        Uri channel = channelFor(id);
        if (channel == null) { // a tuner whose channels this app may not read: only the TV's own app can show it
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(id))
                        .putExtra(TvInputInfo.EXTRA_INPUT_ID, id));
            } catch (RuntimeException ignored) {
            }
            finish();
            return;
        }
        say(getIntent().getStringExtra(NAME));
        tv.setCallback(new InputCallback(this));
        try {
            tv.tune(id, channel);
        } catch (RuntimeException e) {
            say("این ورودی باز نشد");
        }
    }

    /** What to tune to: the input itself for a socket, or a tuner's first channel. Null when there is none. */
    private Uri channelFor(String id) {
        try {
            TvInputManager manager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            TvInputInfo input = manager == null ? null : manager.getTvInputInfo(id);
            if (input == null || input.isPassthroughInput()) return TvContract.buildChannelUriForPassthroughInput(id);
            try (Cursor rows = getContentResolver().query(TvContract.buildChannelsUriForInput(id),
                    new String[] {TvContract.Channels._ID}, null, null, null)) {
                return rows != null && rows.moveToFirst() ? TvContract.buildChannelUri(rows.getLong(0)) : null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A message over the picture; null clears it. */
    void say(String text) {
        status.setText(text == null ? "" : text);
    }

    @Override
    protected void onDestroy() {
        tv.reset(); // lets go of the input
        super.onDestroy();
    }
}
