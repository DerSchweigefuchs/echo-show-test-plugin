package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The presence page drawn on one Canvas, laid out on the dashboard page's own 963 x 584 px grid (the Echo Show's kiosk
 * area) and scaled to the overlay. Positions, sizes and colors follow the dashboard page so both versions draw the same
 * picture. Main thread only.
 */
final class AnwesenheitView extends View {
    static final float W = 963, H = 584;
    private static final float ROBOTO_LH = 1.172f;   // "line-height: normal" of Roboto

    interface Aktionen {
        void zurueck();
        void minute();
    }

    /** Heute (h) or Woche (w); kept across openings like the page's mode. */
    static volatile String modus = "h";

    private final Aktionen aktionen;
    private List<Anwesenheit.Person> personen;
    private Map<String, List<Anwesenheit.Eintrag>> verlauf;
    private Anwesenheit.Seite seite;
    String mess1 = "", mess2 = "";
    String fehler;   // shown instead of "Verlauf wird geladen …"

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final Paint schraffur = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glanz = new Paint();
    private final Typeface normal = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface fett = Typeface.create("sans-serif", Typeface.BOLD);
    private final RectF r = new RectF();
    private final Path pfeil = new Path();
    private final RectF[] schalter = {new RectF(), new RectF()};
    private float skala = 1, dx, dy, downX, downY;
    private final Runnable minute = new Runnable() {
        @Override public void run() {
            TestAnsicht.sicher(() -> {
                aktionen.minute();
                neu();
            });
            postDelayed(this, 60_000);
        }
    };

    AnwesenheitView(Context context, Aktionen aktionen) {
        super(context);
        this.aktionen = aktionen;
        // background: radial-gradient(circle at 15% 20%, rgba(108,196,131,.10), transparent 45%) of the farthest corner
        float cx = W * .15f, cy = H * .2f, rad = (float) Math.hypot(W - cx, H - cy) * .45f;
        glanz.setShader(new RadialGradient(cx, cy, rad, 0x1A6CC483, 0x006CC483, Shader.TileMode.CLAMP));
        // repeating-linear-gradient(135deg, rgba(255,255,255,.035) 0 6px, transparent 6px 12px)
        float d = 12f / (float) Math.sqrt(2);
        schraffur.setShader(new LinearGradient(0, 0, d, d, new int[] {0x09FFFFFF, 0x09FFFFFF, 0, 0},
            new float[] {0, .5f, .5f, 1}, Shader.TileMode.REPEAT));
        // mdi:chevron-left on its 24 px grid
        pfeil.moveTo(15.41f, 16.58f);
        pfeil.lineTo(10.83f, 12f);
        pfeil.lineTo(15.41f, 7.41f);
        pfeil.lineTo(14f, 6f);
        pfeil.lineTo(8f, 12f);
        pfeil.lineTo(14f, 18f);
        pfeil.close();
        setClickable(true);
    }

    /** New people or history (either may be null while loading). */
    void setDaten(List<Anwesenheit.Person> personen, Map<String, List<Anwesenheit.Eintrag>> verlauf) {
        this.personen = personen;
        this.verlauf = verlauf;
        neu();
    }

    private void neu() {
        seite = personen == null ? null : Anwesenheit.rechnen(personen, verlauf, System.currentTimeMillis());
        invalidate();
    }

    void mess(String a, String b) {
        mess1 = a == null ? "" : a;
        mess2 = b == null ? "" : b;
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(minute, 60_000);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(minute);
        super.onDetachedFromWindow();
    }

    // ---- touch: back button and the Heute / Woche switch ------------------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        try {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                downY = e.getY();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                if (Math.abs(e.getX() - downX) < 14 * skala && Math.abs(e.getY() - downY) < 14 * skala) tippen((e.getX() - dx) / skala, (e.getY() - dy) / skala);
            }
        } catch (RuntimeException ignored) {
            // a touch error must not end the app
        }
        return true;
    }

    private void tippen(float x, float y) {
        if (Math.hypot(x - 58, y - 50) <= 32) {
            performClick();
            aktionen.zurueck();
            return;
        }
        String[] k = {"h", "w"};
        for (int i = 0; i < 2; i++) {
            if (schalter[i].contains(x, y) && !k[i].equals(modus)) {
                modus = k[i];
                invalidate();
            }
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    // ---- drawing ----------------------------------------------------------------------------------------------------

    @Override protected void onDraw(Canvas c) {
        try {
            zeichnen(c);
        } catch (RuntimeException ignored) {
            // a drawing error must not end the app
        }
    }

    private void zeichnen(Canvas c) {
        skala = Math.min(getWidth() / W, getHeight() / H);
        dx = (getWidth() - W * skala) / 2;
        dy = (getHeight() - H * skala) / 2;
        c.drawColor(Anwesenheit.BG);
        c.save();
        c.translate(dx, dy);
        c.scale(skala, skala);
        c.drawRect(0, 0, W, H, glanz);
        kopf(c);
        if (seite != null) karten(c);
        fuell(Anwesenheit.TILE);
        r.set(20, 222, W - 20, H - 14);
        c.drawRoundRect(r, 22, 22, p);
        c.save();
        c.translate(20, 222);
        if (seite == null || !seite.geladen) {
            txt(c, fehler != null ? "Fehler: " + fehler : "Verlauf wird geladen …", 923 / 2f, 140, 17, 0, Anwesenheit.SUB, 400, Paint.Align.CENTER, 860);
        } else if ("w".equals(modus)) {
            if (seite.personen.size() >= 4) wocheMatrix(c);
            else woche(c);
        } else {
            heute(c);
        }
        c.restore();
        c.restore();
    }

    private void kopf(Canvas c) {
        fuell(0x14FFFFFF);
        c.drawCircle(58, 50, 28, p);
        c.save();
        c.translate(44, 36);
        c.scale(28 / 24f, 28 / 24f);
        fuell(Anwesenheit.TXT);
        c.drawPath(pfeil, p);
        c.restore();
        txt(c, "Anwesenheit", 102, 24, 22, 22 * 1.2f, Anwesenheit.TXT, 500, Paint.Align.LEFT, 0);
        if (seite != null) txt(c, seite.unterTitel, 102, 24 + 26.4f, 14, 14 * 1.2f, Anwesenheit.SUB, 400, Paint.Align.LEFT, 0);
        // the measured times, in the free space between title and switch
        txt(c, mess1, 490, 26, 12, 0, Anwesenheit.SUB, 400, Paint.Align.CENTER, 470);
        txt(c, mess2, 490, 43, 12, 0, Anwesenheit.SUB, 400, Paint.Align.CENTER, 470);
        // switch: padding 4, segments 10 px 22 px, 16 px text
        float segH = 16 * ROBOTO_LH + 20;
        String[] t = {"Heute", "Woche"};
        String[] k = {"h", "w"};
        float[] w = new float[2];
        for (int i = 0; i < 2; i++) {
            tp.setTextSize(16);
            tp.setTypeface(k[i].equals(modus) ? medium : normal);
            w[i] = tp.measureText(t[i]) + 44;
        }
        float rechts = W - 30, links = rechts - w[0] - w[1] - 8;
        fuell(0x12FFFFFF);
        r.set(links, 22, rechts, 22 + segH + 8);
        c.drawRoundRect(r, 26, 26, p);
        float x = links + 4;
        for (int i = 0; i < 2; i++) {
            boolean an = k[i].equals(modus);
            schalter[i].set(x, 26, x + w[i], 26 + segH);
            if (an) {
                fuell(Anwesenheit.TXT);
                c.drawRoundRect(schalter[i], 22, 22, p);
            }
            txt(c, t[i], x + w[i] / 2, 36, 16, 0, an ? Anwesenheit.BG : Anwesenheit.SUB, an ? 500 : 400, Paint.Align.CENTER, 0);
            x += w[i];
        }
    }

    private void karten(Canvas c) {
        List<Anwesenheit.Zeile> ps = seite.personen;
        int n = ps.size();
        if (n == 0) return;
        if (n <= 3) {
            float tw = (923 - (n - 1) * 12f) / n;
            for (int i = 0; i < n; i++) {
                Anwesenheit.Zeile z = ps.get(i);
                float x = Math.round(20 + i * (tw + 12)), w = Math.round(tw);
                fuell(Anwesenheit.TILE);
                r.set(x, 92, x + w, 208);
                c.drawRoundRect(r, 20, 20, p);
                avatar(c, z, x + 18, 92 + 29, 58);
                float tx = x + 18 + 58 + 16, max = w - 18 - 58 - 16 - 18;
                float top = 92 + (116 - (19 * 1.3f + 15 * 1.3f + 2 + 13 * 1.3f)) / 2;
                txt(c, z.name, tx, top, 19, 19 * 1.3f, Anwesenheit.TXT, 600, Paint.Align.LEFT, max);
                txt(c, z.status, tx, top + 19 * 1.3f, 15, 15 * 1.3f, z.home ? Anwesenheit.GREEN : Anwesenheit.SUB, 400, Paint.Align.LEFT, max);
                if (seite.geladen) txt(c, z.dauer, tx, top + 19 * 1.3f + 15 * 1.3f + 2, 13, 13 * 1.3f, Anwesenheit.SUB, 400, Paint.Align.LEFT, max);
            }
            return;
        }
        // 4+ people: "Zu Hause" / "Unterwegs" boxes with one chip per person
        List<Anwesenheit.Zeile> da = new ArrayList<>(), weg = new ArrayList<>();
        for (Anwesenheit.Zeile z : ps) (z.home ? da : weg).add(z);
        float wa = !da.isEmpty() && !weg.isEmpty() ? Math.round(923 * Math.max(.34f, Math.min(.66f, da.size() / (float) n))) - 6 : 923;
        float wb = 923 - wa - 12;
        if (!da.isEmpty()) gruppe(c, 20, wa, "Zu Hause", da, Anwesenheit.GREEN);
        if (!weg.isEmpty()) gruppe(c, da.isEmpty() ? 20 : 20 + wa + 12, da.isEmpty() ? 923 : wb, "Unterwegs", weg, Anwesenheit.SUB);
    }

    private void gruppe(Canvas c, float x, float w, String titel, List<Anwesenheit.Zeile> L, int col) {
        fuell(Anwesenheit.TILE);
        r.set(x, 92, x + w, 208);
        c.drawRoundRect(r, 20, 20, p);
        txt(c, titel + " · " + L.size(), x + 14, 104, 13, 0, col, 500, Paint.Align.LEFT, w - 28);
        int per = Math.max(1, (int) Math.floor((w - 28 + 8) / 136)), max = per * 2;
        int zeigen = L.size() > max ? max - 1 : L.size();
        float top0 = 104 + 13 * ROBOTO_LH + 7;
        for (int i = 0; i <= zeigen; i++) {
            float cx = x + 14 + (i % per) * 136, cy = top0 + (i / per) * 36;
            if (i == zeigen) {
                if (L.size() > max) txt(c, "+" + (L.size() - max + 1) + " weitere", cx, cy, 13.5f, 30, Anwesenheit.SUB, 400, Paint.Align.LEFT, 0);
                break;
            }
            Anwesenheit.Zeile z = L.get(i);
            avatar(c, z, cx, cy + 2, 26);
            float tt = cy + (30 - (14 * 1.15f + 11.5f * 1.15f)) / 2;
            txt(c, z.name, cx + 33, tt, 14, 14 * 1.15f, Anwesenheit.TXT, 600, Paint.Align.LEFT, 95);
            txt(c, Anwesenheit.seit(z.since, z.sp, seite), cx + 33, tt + 14 * 1.15f, 11.5f, 11.5f * 1.15f, z.home ? Anwesenheit.GREEN : Anwesenheit.SUB, 400, Paint.Align.LEFT, 95);
        }
    }

    /** Today: 0-24 h bar per person, home spans in the person's color, the future hatched, a "jetzt" line. */
    private void heute(Canvas c) {
        List<Anwesenheit.Zeile> ps = seite.personen;
        int n = Math.max(1, ps.size());
        float L = 150, Wd = 923 - 40 - L - 24, top0 = 54, rh = Math.min(92, (320 - top0) / n);
        for (int h = 0; h <= 24; h += 3) {
            float x = L + Wd * h / 24;
            txt(c, String.format(java.util.Locale.ROOT, "%02d:00", h % 24), x, 18, 12, 0, Anwesenheit.SUB, 400, Paint.Align.CENTER, 0);
            fuell(0x0FFFFFFF);
            c.drawRect(Math.round(x), 38, Math.round(x) + 1, 348 - 14, p);
        }
        boolean dicht = rh < 44;
        float bh = dicht ? Math.max(10, Math.min(16, rh - 8)) : 22, av = dicht ? 24 : 30;
        float xNow = L + Wd * (seite.now - seite.today) / Anwesenheit.TAG;
        for (int i = 0; i < ps.size(); i++) {
            Anwesenheit.Zeile z = ps.get(i);
            float y = Math.round(top0 + i * rh), zeile = Math.round(top0 + i * rh + bh / 2 - av / 2);
            avatar(c, z, 20, zeile, av);
            txt(c, z.name, 20 + av + 10, zeile, dicht ? 14 : 16, av, Anwesenheit.TXT, 500, Paint.Align.LEFT, L - 30 - av - 10);
            balken(c, z, L, y, Wd, bh, seite.today, seite.today + Anwesenheit.TAG);
            c.save();
            c.clipRect(Math.max(L, xNow), y, L + Wd, y + bh);
            r.set(L, y, L + Wd, y + bh);
            c.drawRoundRect(r, bh / 2, bh / 2, schraffur);
            c.restore();
            if (!dicht) txt(c, z.ereignisse, L, y + bh + 6, 13, 0, Anwesenheit.SUB, 400, Paint.Align.LEFT, Wd);
        }
        fuell(0xBFECEBE8);
        r.set(Math.round(xNow - 1), 40, Math.round(xNow - 1) + 2, 40 + ps.size() * rh + 6);
        c.drawRoundRect(r, 1, 1, p);
        txt(c, "jetzt", xNow, 46 + ps.size() * rh, 12, 0, Anwesenheit.TXT, 400, Paint.Align.CENTER, 0);
    }

    /** Track and the home spans between a and b (history ends now). */
    private void balken(Canvas c, Anwesenheit.Zeile z, float x, float y, float w, float h, long a, long b) {
        fuell(0x0FFFFFFF);
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, h / 2, h / 2, p);
        fuell(z.col);
        for (Anwesenheit.Span s : z.sp) {
            if (!"home".equals(s.s) || s.b <= a || s.a >= b) continue;
            long von = Math.max(a, s.a), bis = Math.min(Math.min(b, seite.now), s.b);
            if (bis <= von) continue;
            float l = x + w * (von - a) / (float) (b - a), sw = Math.max(.006f * w, w * (bis - von) / (float) (b - a));
            r.set(l, y, Math.min(x + w, l + sw), y + h);
            c.drawRoundRect(r, h / 2, h / 2, p);
        }
    }

    /** Week, 1-3 people: one column per person with 7 day bars and hours at home. */
    private void woche(Canvas c) {
        List<Anwesenheit.Zeile> ps = seite.personen;
        int n = Math.max(1, ps.size());
        float cw = (923 - 40 - (n - 1) * 24f) / n;
        for (int i = 0; i < ps.size(); i++) {
            Anwesenheit.Zeile z = ps.get(i);
            float x0 = Math.round(20 + i * (cw + 24));
            avatar(c, z, x0, 16, 26);
            txt(c, z.name, x0 + 36, 16, 16, 26, Anwesenheit.TXT, 500, Paint.Align.LEFT, cw - 36);
            for (int k = 0; k < 7; k++) {
                long a = seite.start + k * Anwesenheit.TAG;
                float y = 58 + k * 36;
                boolean isT = k == 6;
                txt(c, isT ? "Heute" : Anwesenheit.tagKurz(a), x0, y - 4, 13, 0, isT ? Anwesenheit.TXT : Anwesenheit.SUB, isT ? 500 : 400, Paint.Align.LEFT, 42);
                balken(c, z, x0 + 46, y, Math.round(cw - 46 - 48), 10, a, a + Anwesenheit.TAG);
                txt(c, Math.round(z.wocheMs[k] / 36e5) + " h", x0 + cw, y - 4, 13, 0, Anwesenheit.SUB, 400, Paint.Align.RIGHT, 0);
            }
        }
    }

    /** Week, 4+ people: one row per person, one cell per day, opacity by hours at home. */
    private void wocheMatrix(Canvas c) {
        List<Anwesenheit.Zeile> ps = seite.personen;
        int n = ps.size();
        float L2 = 150, cw2 = (923 - 40 - L2 - 50) / 7f, rh2 = Math.min(46, (320 - 56f) / n), dch = Math.min(26, rh2 - 6);
        for (int k = 0; k < 7; k++) {
            long a = seite.start + k * Anwesenheit.TAG;
            boolean isT = k == 6;
            txt(c, isT ? "Heute" : Anwesenheit.tagKurz(a), 20 + L2 + k * cw2 + cw2 / 2, 18, 13, 0, isT ? Anwesenheit.TXT : Anwesenheit.SUB, isT ? 500 : 400, Paint.Align.CENTER, 0);
        }
        txt(c, "Ø", 903, 18, 13, 0, Anwesenheit.SUB, 400, Paint.Align.RIGHT, 0);
        for (int i = 0; i < n; i++) {
            Anwesenheit.Zeile z = ps.get(i);
            float y = 48 + i * rh2;
            avatar(c, z, 20, Math.round(y + dch / 2 - 12), 24);
            txt(c, z.name, 54, Math.round(y + dch / 2 - 12), 14, 24, Anwesenheit.TXT, 500, Paint.Align.LEFT, L2 - 44);
            double sum = 0;
            for (int k = 0; k < 7; k++) {
                double h = z.wocheMs[k] / 36e5;
                if (k < 6) sum += h;
                int alpha = Math.round(255 * (float) (.12 + .78 * Math.min(1, h / 24)));
                fuell((z.col & 0x00FFFFFF) | (alpha << 24));
                r.set(20 + L2 + k * cw2 + 3, y, 20 + L2 + (k + 1) * cw2 - 3, y + dch);
                c.drawRoundRect(r, 7, 7, p);
                if (dch >= 16) txt(c, Math.round(h) + " h", r.centerX(), y, 12, dch, Anwesenheit.BG, 600, Paint.Align.CENTER, 0);
            }
            txt(c, Math.round(sum / 6) + " h", 903, y + dch / 2 - 8, 13, 0, Anwesenheit.SUB, 400, Paint.Align.RIGHT, 0);
        }
    }

    /** Letter avatar (pictures are not loaded) with the home / away dot in the lower right. */
    private void avatar(Canvas c, Anwesenheit.Zeile z, float x, float y, float s) {
        fuell((z.col & 0x00FFFFFF) | 0x33000000);
        c.drawCircle(x + s / 2, y + s / 2, s / 2, p);
        txt(c, z.initiale, x + s / 2, y, Math.round(s * .42f), s, z.col, 600, Paint.Align.CENTER, 0);
        float d = Math.round(s * .3f), l = x + s + 1 - d, t = y + s + 1 - d;
        fuell(Anwesenheit.TILE);
        c.drawCircle(l + d / 2, t + d / 2, d / 2, p);
        fuell(z.home ? Anwesenheit.GREEN : Anwesenheit.WEG);
        c.drawCircle(l + d / 2, t + d / 2, d / 2 - 3, p);
    }

    private void fuell(int farbe) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(farbe);
    }

    /**
     * Text in a CSS line box: top edge, font size and line height in page px (0 = "normal"), weight 400/500/600+,
     * cut with an ellipsis at maxW (0 = no limit).
     */
    private void txt(Canvas c, String t, float x, float top, float size, float lh, int farbe, int weight, Paint.Align al, float maxW) {
        if (t == null || t.isEmpty()) return;
        tp.setTextSize(size);
        tp.setTypeface(weight >= 600 ? fett : weight >= 500 ? medium : normal);
        tp.setColor(farbe);
        tp.setTextAlign(al);
        tp.getFontMetrics(fm);
        float h = fm.descent - fm.ascent;
        if (lh <= 0) lh = size * ROBOTO_LH;
        float base = top + (lh - h) / 2 - fm.ascent;
        CharSequence s = maxW > 0 ? TextUtils.ellipsize(t, tp, maxW, TextUtils.TruncateAt.END) : t;
        c.drawText(s, 0, s.length(), x, base, tp);
    }
}
