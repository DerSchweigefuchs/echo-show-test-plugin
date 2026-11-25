package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import java.util.Locale;

/**
 * Countdown ring like the Lovelace timer page (timer_ansicht.py: orange ring, big digits). "sekündlich" redraws once
 * per second exactly on the second boundary; "flüssig" redraws every frame so the ring sweeps continuously.
 */
final class TimerRing extends View {
    private static final int OR = 0xFFF2875A, ORT = 0x29F2875A;
    private final Messung m;
    private final Paint spur = new Paint(Paint.ANTI_ALIAS_FLAG), bogen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ziffern = new Paint(Paint.ANTI_ALIAS_FLAG), klein = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float dichte;
    private boolean angehaengt;
    private final Runnable naechste = this::invalidate;

    TimerRing(Context context, Messung m, Typeface schrift, int textFarbe, int textLeise) {
        super(context);
        this.m = m;
        dichte = context.getResources().getDisplayMetrics().density;
        spur.setStyle(Paint.Style.STROKE);
        spur.setColor(ORT);
        spur.setStrokeWidth(15 * dichte);
        bogen.setStyle(Paint.Style.STROKE);
        bogen.setColor(OR);
        bogen.setStrokeWidth(15 * dichte);
        bogen.setStrokeCap(Paint.Cap.ROUND);
        ziffern.setColor(textFarbe);
        ziffern.setTextAlign(Paint.Align.CENTER);
        ziffern.setTypeface(schrift);
        ziffern.setTextSize(84 * dichte);
        klein.setColor(textLeise);
        klein.setTextAlign(Paint.Align.CENTER);
        klein.setTypeface(schrift);
        klein.setTextSize(18 * dichte);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); angehaengt = true; }

    @Override protected void onDetachedFromWindow() {
        angehaengt = false;
        removeCallbacks(naechste);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        try {
            zeichnen(c);
        } catch (RuntimeException ignored) {
            // a drawing error must not end the app
        }
    }

    private void zeichnen(Canvas c) {
        long jetzt = SystemClock.uptimeMillis();
        long gesamt = m.timerSekunden * 1000L;
        long rest = Math.max(0, m.timerEnde - jetzt);
        float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f - 12 * dichte;
        float cx = w / 2f, cy = h / 2f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        c.drawOval(oval, spur);
        long sek = (rest + 999) / 1000;
        // sekündlich: the ring shows the whole seconds left, so it jumps once per second together with the digits
        float anteil = m.timerFluessig ? rest / (float) gesamt : sek * 1000f / gesamt;
        if (rest > 0) c.drawArc(oval, -90, 360f * Math.min(1f, anteil), false, bogen);
        String txt;
        if (rest > 0) {
            long hh = sek / 3600, mm = (sek % 3600) / 60, ss = sek % 60;
            txt = hh > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hh, mm, ss) : String.format(Locale.ROOT, "%d:%02d", mm, ss);
        } else {
            txt = "Fertig";
            ziffern.setAlpha((jetzt / 500) % 2 == 0 ? 255 : 110);
        }
        c.drawText(txt, cx, cy + ziffern.getTextSize() * 0.35f, ziffern);
        c.drawText("Test-Timer · " + (m.timerFluessig ? "flüssig" : "sekündlich"), cx, cy + r * 0.55f, klein);
        if (!angehaengt) return;
        removeCallbacks(naechste);   // one pending redraw at most, also when a layout pass drew in between
        if (rest == 0) postDelayed(naechste, 500);
        else if (m.timerFluessig) postInvalidateOnAnimation();
        else postDelayed(naechste, Math.max(1, rest - (sek - 1) * 1000));   // exactly when the next whole second starts
    }
}
