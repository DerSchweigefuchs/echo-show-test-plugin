package echoshow.kiosktest;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Screensaver UI overlay (Design A "Himmel"): slim Roboto Thin clock, date, weather line with rain/high-low hint,
 * up to two dark-glass hint cards top-right (with curved inset accent bar and KS Material Design Icons) and a music
 * pill bottom-left when a player is playing. Drawn directly on the scene canvas with zero per-frame allocations.
 */
final class SchonerUi {
    private static final int TXT = 0xFFECEBE8, SUB = 0xFFC3CCD5, SUB_HELL = 0xFFE2E8EE, ACC = 0xFFA99CF0;
    private static final int GLAS = 0x9E0E1620, RAND = 0x18FFFFFF;
    private static final float[][] SHIFT = {{0, 0}, {5, 4}, {0, 8}, {-5, 4}};

    private final KsTheme theme;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint uhrP, sekP, datumP, wxFettP, wxP, kTitelP, kUnterP, mTitelP, mArtP, mehrP;
    private final Shader scrimHell, scrimDunkel;
    private final RectF r = new RectF(), arcR = new RectF();
    private final Path akzent = new Path();
    private final Map<String, Path> iconCache = new HashMap<>();
    private final SimpleDateFormat fmtHm = new SimpleDateFormat("HH:mm", Locale.ROOT);
    private final SimpleDateFormat fmtSec = new SimpleDateFormat(":ss", Locale.ROOT);
    private final SimpleDateFormat fmtDatum = new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN);
    private final Date datumObj = new Date();
    private long letzteSek = -1;
    private String tUhr = "", tSek = "", tDatum = "";

    SchonerUi(KsTheme theme) {
        this.theme = theme;
        linie.setStyle(Paint.Style.STROKE);
        linie.setStrokeWidth(1f);

        Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        Typeface reg = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface med = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        uhrP = textPaint(thin, 152, TXT, true);
        setSpacing(uhrP, -5f / 152f);
        sekP = textPaint(thin, 64, WetterLage.alpha(TXT, .75f), true);
        datumP = textPaint(reg, 26, TXT, true);
        wxFettP = textPaint(med, 19, TXT, true);
        wxP = textPaint(reg, 19, SUB, true);
        kTitelP = textPaint(med, 15.5f, TXT, false);
        kUnterP = textPaint(reg, 13.5f, SUB, false);
        mTitelP = textPaint(med, 17f, TXT, false);
        mArtP = textPaint(reg, 14f, ACC, false);
        mehrP = textPaint(reg, 13.5f, SUB, true);
        mehrP.setTextAlign(Paint.Align.RIGHT);

        scrimHell = new RadialGradient(0, 0, 340,
            new int[] {0x7A080E16, 0x36080E16, 0x00080E16}, new float[] {0f, .52f, 1f}, Shader.TileMode.CLAMP);
        scrimDunkel = new RadialGradient(0, 0, 320,
            new int[] {0x38080E16, 0x14080E16, 0x00080E16}, new float[] {0f, .52f, 1f}, Shader.TileMode.CLAMP);
    }

    private static Paint textPaint(Typeface tf, float size, int color, boolean schatten) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(tf);
        t.setTextSize(size);
        t.setColor(color);
        if (schatten) t.setShadowLayer(10, 0, 2, 0x6B000000);
        return t;
    }

    private static void setSpacing(Paint t, float ls) {
        try { t.setLetterSpacing(ls); } catch (LinkageError ignored) {}
    }

    /** Draws all enabled screensaver elements in 963 x viewH logical coordinates. */
    void zeichnen(Canvas c, float w, float h, long simZeit, float t, float tag, WetterLage.Daten wx, WetterLage lage,
                  SchonerDaten.Zustand zustand, SchonerDaten.Optionen opt) {
        if (opt == null) return;
        // Subtle readability scrim behind top-left clock/date/weather (elliptical, fades cleanly to 0 with no rect edge)
        if (opt.uhr || opt.datum || opt.wetter) {
            c.save();
            c.translate(215, 130);
            c.scale(1.18f, .72f);
            p.setColor(0xFFFFFFFF);
            p.setShader(tag > .35f ? scrimHell : scrimDunkel);
            c.drawCircle(0, 0, 340, p);
            p.setShader(null);
            c.restore();
        }

        // Slow burn-in pixel shift (a few px every 10 minutes, complementing KS's own pixel shift)
        float[] off = SHIFT[(int) ((System.currentTimeMillis() / 600_000L) % SHIFT.length)];
        c.save();
        c.translate(off[0], off[1]);

        long sek = simZeit / 1000L;
        if (sek != letzteSek) {
            letzteSek = sek;
            datumObj.setTime(simZeit);
            tUhr = fmtHm.format(datumObj);
            tSek = fmtSec.format(datumObj);
            tDatum = fmtDatum.format(datumObj);
        }

        // ---- top left: clock, date, weather line ----
        float yDatum, yWetter;
        if (opt.uhr) {
            float sz = opt.uhrGross ? 152f : 96f;
            float xUhr = opt.uhrGross ? 46f : 50f;
            float baseUhr = opt.uhrGross ? 154f : 114f;
            uhrP.setTextSize(sz);
            setSpacing(uhrP, (opt.uhrGross ? -5f : -3f) / sz);
            c.drawText(tUhr, xUhr, baseUhr, uhrP);
            if (opt.sekunden) {
                sekP.setTextSize(sz * .42f);
                c.drawText(tSek, xUhr + uhrP.measureText(tUhr) + 4, baseUhr, sekP);
            }
            yDatum = opt.uhrGross ? 208f : 158f;
            yWetter = opt.datum ? (opt.uhrGross ? 248f : 196f) : (opt.uhrGross ? 208f : 158f);
        } else {
            yDatum = 64f;
            yWetter = opt.datum ? 106f : 64f;
        }

        if (opt.datum) {
            c.drawText(tDatum, 54, yDatum, datumP);
        }

        if (opt.wetter && wx != null) {
            boolean nacht = tag < .5f;
            float x = 54;
            drawIcon(c, WetterLage.wetterIcon(lage.cond, nacht), x, yWetter - 19, 24, TXT);
            x += 32;
            if (wx.temp != null) {
                String tp = Math.round(wx.temp) + "°";
                c.drawText(tp, x, yWetter, wxFettP);
                x += wxFettP.measureText(tp) + 6;
            }
            String hinweis = WetterLage.schonerHinweis(wx, lage);
            String rest = lage.name() + (hinweis.isEmpty() ? "" : ", " + hinweis);
            wxP.setColor(tag > .45f ? SUB_HELL : SUB);
            c.drawText(rest, x, yWetter, wxP);
        }

        // ---- top right: up to 2 hint cards + "+N weitere" ----
        if (opt.hinweise && zustand != null && !zustand.hinweise.isEmpty()) {
            List<SchonerDaten.Hinweis> list = zustand.hinweise;
            float cw = 312, ch = 64, cx = w - 26 - cw, cy = 28;
            long jetzt = System.currentTimeMillis();
            int zeigen = Math.min(2, list.size());
            for (int i = 0; i < zeigen; i++) {
                SchonerDaten.Hinweis hi = list.get(i);
                karteZeichnen(c, cx, cy, cw, ch, hi, jetzt);
                cy += ch + 10;
            }
            if (list.size() > 2) {
                c.drawText("+" + (list.size() - 2) + " weitere", cx + cw - 6, cy + 12, mehrP);
            }
        }

        // ---- bottom left: music pill (only while playing) ----
        if (opt.musik && zustand != null && zustand.musik != null) {
            musikZeichnen(c, 26, h - 24 - 68, t, zustand.musik);
        }

        c.restore();
    }

    private void karteZeichnen(Canvas c, float x, float y, float w, float h, SchonerDaten.Hinweis hi, long jetzt) {
        float rad = 18;
        r.set(x, y, x + w, y + h);
        p.setColor(GLAS);
        c.drawRoundRect(r, rad, rad, p);

        // Curved left inset accent bar (matches CSS box-shadow: inset 4px 0 0 color on border-radius: 18px)
        akzent.reset();
        akzent.moveTo(x + rad, y);
        arcR.set(x, y, x + rad * 2, y + rad * 2);
        akzent.arcTo(arcR, 270, -90, false);
        akzent.lineTo(x, y + h - rad);
        arcR.set(x, y + h - rad * 2, x + rad * 2, y + h);
        akzent.arcTo(arcR, 180, -90, false);
        akzent.lineTo(x + rad + 4, y + h);
        arcR.set(x + 4, y + h - rad * 2, x + 4 + rad * 2, y + h);
        akzent.arcTo(arcR, 90, 90, false);
        akzent.lineTo(x + 4, y + rad);
        arcR.set(x + 4, y, x + 4 + rad * 2, y + rad * 2);
        akzent.arcTo(arcR, 180, 90, false);
        akzent.close();
        p.setColor(hi.farbe);
        c.drawPath(akzent, p);

        // Subtle hairline border
        linie.setColor(RAND);
        c.drawRoundRect(r, rad, rad, linie);

        // Icon badge (38 x 38, radius 12)
        r.set(x + 14, y + 13, x + 52, y + 51);
        p.setColor(WetterLage.alpha(hi.farbe, .18f));
        c.drawRoundRect(r, 12, 12, p);
        drawIcon(c, hi.icon, x + 22, y + 21, 22, hi.farbe);

        // Title and subtitle (ellipsized cleanly)
        float maxW = w - 64 - 14;
        c.drawText(kuerzen(kTitelP, hi.titel, maxW), x + 64, y + 29, kTitelP);
        String sub = !hi.unter.isEmpty() ? hi.unter : SchonerDaten.vorText(hi.zeit, jetzt);
        if (!sub.isEmpty()) c.drawText(kuerzen(kUnterP, sub, maxW), x + 64, y + 48, kUnterP);
    }

    private void musikZeichnen(Canvas c, float x, float y, float t, SchonerDaten.Musik m) {
        float h = 68, maxTextW = 440 - 74 - 46;
        String t1 = kuerzen(mTitelP, m.titel, maxTextW);
        String t2 = kuerzen(mArtP, m.kuenstler, maxTextW);
        float tw = Math.max(mTitelP.measureText(t1), mArtP.measureText(t2));
        float w = Math.min(440, Math.max(210, 74 + tw + 46));
        r.set(x, y, x + w, y + h);
        p.setColor(GLAS);
        c.drawRoundRect(r, 22, 22, p);
        linie.setColor(RAND);
        c.drawRoundRect(r, 22, 22, linie);

        // Icon square (52 x 52, radius 14)
        r.set(x + 8, y + 8, x + 60, y + 60);
        p.setColor(0x14FFFFFF);
        c.drawRoundRect(r, 14, 14, p);
        drawIcon(c, "mdi:music", x + 21, y + 21, 26, ACC);

        c.drawText(t1, x + 74, y + 31, mTitelP);
        if (!t2.isEmpty()) c.drawText(t2, x + 74, y + 50, mArtP);

        // Gently animated equalizer bars
        float ex = x + w - 30, base = y + 43;
        float h1 = 6 + 9 * (float) (.5 + .5 * Math.sin(t * 4.2));
        float h2 = 7 + 11 * (float) (.5 + .5 * Math.sin(t * 5.1 + 1.8));
        float h3 = 5 + 9 * (float) (.5 + .5 * Math.sin(t * 4.7 + 3.4));
        p.setColor(ACC);
        r.set(ex, base - h1, ex + 3, base); c.drawRoundRect(r, 1.5f, 1.5f, p);
        r.set(ex + 6, base - h2, ex + 9, base); c.drawRoundRect(r, 1.5f, 1.5f, p);
        r.set(ex + 12, base - h3, ex + 15, base); c.drawRoundRect(r, 1.5f, 1.5f, p);
    }

    private void drawIcon(Canvas c, String name, float x, float y, float size, int color) {
        Path path = iconPath(name);
        if (path != null) {
            p.setColor(color);
            c.save();
            c.translate(x, y);
            c.scale(size / 24f, size / 24f);
            c.drawPath(path, p);
            c.restore();
            return;
        }
        // Minimal fallback if host has no KsTheme.iconPath (e.g. desktop preview or older KS)
        p.setColor(color);
        c.drawCircle(x + size / 2, y + size / 2, size * .3f, p);
    }

    private Path iconPath(String name) {
        if (theme == null || name == null) return null;
        if (iconCache.containsKey(name)) return iconCache.get(name);
        Path found = null;
        try {
            found = theme.iconPath(name);
        } catch (RuntimeException | LinkageError ignored) {
            // older host without iconPath
        }
        iconCache.put(name, found);
        return found;
    }

    private static String kuerzen(Paint pt, String s, float maxW) {
        if (s == null || s.isEmpty() || pt.measureText(s) <= maxW) return s == null ? "" : s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (pt.measureText(s.substring(0, mid) + "…") <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return lo <= 0 ? "…" : s.substring(0, lo) + "…";
    }
}
