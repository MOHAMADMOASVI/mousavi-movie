package app.videotv;

import android.media.tv.TvInputManager;
import android.media.tv.TvView;

/**
 * Tells InputActivity what the input is doing.
 * A top-level class on purpose: build-tools 34's d8 crashes on nested classes compiled by recent JDKs.
 */
final class InputCallback extends TvView.TvInputCallback {
    private final InputActivity activity;

    InputCallback(InputActivity activity) {
        this.activity = activity;
    }

    @Override
    public void onConnectionFailed(String inputId) {
        activity.say("اتصال به این ورودی ممکن نشد");
    }

    @Override
    public void onDisconnected(String inputId) {
        activity.say("ارتباط با این ورودی قطع شد");
    }

    @Override
    public void onVideoAvailable(String inputId) {
        activity.say(null);
    }

    @Override
    public void onVideoUnavailable(String inputId, int reason) {
        boolean waiting = reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
                || reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING;
        activity.say(waiting ? "در حال اتصال…" : "سیگنالی از این ورودی نمی‌آید");
    }
}
