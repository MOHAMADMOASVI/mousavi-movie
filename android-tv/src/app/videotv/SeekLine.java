package app.videotv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** The player's timeline, drawn like the web page's: a thin rounded track, the played part in the accent colour, and a round knob. */
final class SeekLine extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float rem;
    private float played, buffered;

    SeekLine(Context context, float rem) {
        super(context);
        this.rem = rem;
    }

    /** Both are fractions of the film, 0 to 1. */
    void set(float played, float buffered) {
        this.played = played;
        this.buffered = buffered;
        invalidate();
    }

    private void line(Canvas canvas, float fraction, int color) {
        float half = rem * .2f, mid = getHeight() / 2f;
        rect.set(0, mid - half, getWidth() * fraction, mid + half);
        paint.setColor(color);
        canvas.drawRoundRect(rect, half, half, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        line(canvas, 1, 0x38FFFFFF);
        line(canvas, Math.max(buffered, played), 0x47FFFFFF);
        line(canvas, played, PlayerActivity.ACCENT);
        float halo = rem * .8f, x = Math.max(halo, Math.min(getWidth() - halo, getWidth() * played)), y = getHeight() / 2f;
        paint.setColor(0x38FFFFFF);
        canvas.drawCircle(x, y, halo, paint);
        paint.setColor(PlayerActivity.ACCENT);
        canvas.drawCircle(x, y, rem * .55f, paint);
    }
}
