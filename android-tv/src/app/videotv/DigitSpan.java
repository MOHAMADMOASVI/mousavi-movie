package app.videotv;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

/**
 * Draws the Persian digits of a text in their own font, as the web page does: Alexandria's ۴
 * reads as ۳, so the digits come from the Vazirmatn digits file instead.
 */
final class DigitSpan extends MetricAffectingSpan {
    private final Typeface face;

    private DigitSpan(Typeface face) {
        this.face = face;
    }

    static CharSequence mark(String text, Typeface digits) {
        if (digits == null) return text;
        SpannableString out = new SpannableString(text);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '۰' || text.charAt(i) > '۹') continue;
            int end = i;
            while (end < text.length() && text.charAt(end) >= '۰' && text.charAt(end) <= '۹') end++;
            out.setSpan(new DigitSpan(digits), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = end;
        }
        return out;
    }

    @Override
    public void updateMeasureState(TextPaint paint) {
        paint.setTypeface(face);
    }

    @Override
    public void updateDrawState(TextPaint paint) {
        paint.setTypeface(face);
    }
}
