package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Native full-screen Calendar view matching kalender.py on the 963 x 584 dashboard grid (#121A21):
 * - Top-left back circle button, "Kalender" title, and interactive period navigation (< · period · > · Heute)
 * - Top-right segmented view switcher: Tag · 3 Tage · Woche · Monat · Liste
 * - Time grid (Tag / 3 Tage / Woche): all-day bars, dynamic hour lines, side-by-side overlapping event cards,
 *   live red current-time line, and crisp blue highlight frames around AI-asked days (matching markierung_wetter.png)
 * - Month grid (Monat): 7x6 day cells with color-striped event previews, tap any day to jump to Tag
 * - Agenda list (Liste): vertical touch-scrollable 14-day list with grouped day headers
 * - Event detail card: tap any event in any view to see calendar badge, time/countdown, location & description.
 */
final class KalenderView extends View {
    static final float W = 963, H = 584;
    private static final int BG = 0xFF121A21, TXT = 0xFFECEBE8, SUB = 0xFF8FA3B5, BOX = 0xFF18232D, DETAIL_BG = 0xFF1A2631;
    private static final String[] WT = {"So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"};
    private static final String[] WTL = {"Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"};
    private static final String[] MON = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"};
    private static final String[] TAB_KEYS = {"tag", "3tage", "woche", "monat", "liste"};
    private static final String[] TAB_NAMES = {"Tag", "3 Tage", "Woche", "Monat", "Liste"};

    interface Aktionen {
        void zurueck();
        void bereichLaden(Calendar start, int tageAnzahl);
    }

    private static final class Hit {
        final String id;
        final float l, t, r, b;
        Hit(String id, float l, float t, float r, float b) { this.id = id; this.l = l; this.t = t; this.r = r; this.b = b; }
        boolean trifft(float x, float y) { return x >= l && x <= r && y >= t && y <= b; }
    }

    private final KsTheme theme;
    private final Aktionen aktionen;
    private final Map<String, Path> iconCache = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();

    private String modus = "woche";
    private Calendar basisTag = Kalender.mitternacht(Calendar.getInstance());
    private Set<String> markiert = new LinkedHashSet<>();
    private List<Kalender.Termin> ereignisse;   // null while loading
    private String fehler;
    private String ausgewaehltUid;
    private float listScrollY, listMaxScroll;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface tfNorm = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface tfBold = Typeface.create("sans-serif", Typeface.BOLD);
    private final RectF rf = new RectF();
    private final Path iconTmp = new Path();

    private float skala = 1f, dx, dy, downX, downY, lastY;
    private boolean ziehend;

    KalenderView(Context ctx, KsTheme theme, Aktionen aktionen, Kalender.Spanne init) {
        super(ctx);
        this.theme = theme;
        this.aktionen = aktionen;
        setBackgroundColor(BG);
        setClickable(true);
        linie.setStyle(Paint.Style.STROKE);
        linie.setStrokeCap(Paint.Cap.ROUND);
        if (init != null) {
            modus = init.zeitraum;
            basisTag = (Calendar) init.start.clone();
            markiert = new LinkedHashSet<>(init.markiert);
        }
    }

    void setDaten(List<Kalender.Termin> list, String err) {
        ereignisse = list != null ? list : Collections.emptyList();
        fehler = err;
        invalidate();
    }

    void setAusgewaehlt(String uid) {
        ausgewaehltUid = uid;
        invalidate();
    }

    /** Current visible start date and day count for HA calendar.get_events. */
    Calendar sichtStart() {
        if ("woche".equals(modus)) return Kalender.montag(basisTag);
        if ("monat".equals(modus)) {
            Calendar m = (Calendar) basisTag.clone();
            m.set(Calendar.DAY_OF_MONTH, 1);
            return Kalender.montag(m);
        }
        return (Calendar) basisTag.clone();
    }

    int sichtTage() {
        if ("tag".equals(modus)) return 1;
        if ("3tage".equals(modus)) return 3;
        if ("monat".equals(modus)) return 42;
        if ("liste".equals(modus)) return 14;
        return 7;
    }

    void zeichneFuerTest(Canvas c, int gw, int gh, long nowMs) {
        zeichnen(c, gw, gh, nowMs);
    }

    @Override protected void onDraw(Canvas c) {
        int gw = getWidth(), gh = getHeight();
        if (gw <= 0 || gh <= 0) return;
        zeichnen(c, gw, gh, System.currentTimeMillis());
    }

    private void zeichnen(Canvas c, int gw, int gh, long nowMs) {
        skala = Math.min(gw / W, gh / H);
        if (skala <= 0) return;
        dx = (gw - W * skala) * .5f;
        dy = (gh - H * skala) * .5f;
        hits.clear();

        c.drawColor(BG);
        c.save();
        c.translate(dx, dy);
        c.scale(skala, skala);
        c.clipRect(0, 0, W, H);
        try {
            Calendar t0Cal = Calendar.getInstance();
            t0Cal.setTimeInMillis(nowMs);
            Kalender.mitternacht(t0Cal);
            long t0 = t0Cal.getTimeInMillis();

            Calendar rStart = sichtStart();
            int rN = sichtTage();
            long rA = rStart.getTimeInMillis();
            Calendar rEndeCal = (Calendar) rStart.clone();
            rEndeCal.add(Calendar.DAY_OF_MONTH, rN);
            long rB = rEndeCal.getTimeInMillis();

            // 1. Header: back button, title, period nav, and 5-tab view switcher
            zeichneKopf(c, rStart, rN, t0, rA, rB);

            // 2. Main view body
            if ("tag".equals(modus) || "3tage".equals(modus) || "woche".equals(modus)) {
                zeichneZeitraster(c, rStart, rN, t0, nowMs);
            } else if ("monat".equals(modus)) {
                zeichneMonat(c, rStart, t0);
            } else {
                zeichneListe(c, rStart, rN, t0, nowMs);
            }

            // 3. Selected event detail card
            if (ausgewaehltUid != null && ereignisse != null) {
                Kalender.Termin sel = null;
                for (Kalender.Termin e : ereignisse) if (ausgewaehltUid.equals(e.uid)) { sel = e; break; }
                if (sel != null) zeichneDetail(c, sel, t0, nowMs);
            }
        } catch (RuntimeException ignored) {}
        c.restore();
    }

    private void zeichneKopf(Canvas c, Calendar rStart, int rN, long t0, long rA, long rB) {
        // Back circle button (58, 50)
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x1EFFFFFF);
        c.drawCircle(58, 50, 28, p);
        drawIcon(c, "mdi:chevron-left", 45, 37, 26, TXT);
        hits.add(new Hit("zurueck", 22, 14, 94, 86));

        t.setTextAlign(Paint.Align.LEFT);
        t.setTypeface(tfMed); t.setTextSize(22); t.setColor(TXT);
        c.drawText("Kalender", 102, 42, t);

        // Period navigation row (< [KW 48 · 23. – 29. November] > Heute)
        float ny = 52;
        drawIcon(c, "mdi:chevron-left", 100, ny, 20, SUB);
        hits.add(new Hit("nav:-1", 94, ny - 6, 126, ny + 26));

        String zrTxt = zeitraumLabel(rStart, rN);
        t.setTypeface(tfNorm); t.setTextSize(14);
        float pw = t.measureText(zrTxt) + 24;
        rf.set(126, ny - 2, 126 + pw, ny + 22);
        p.setColor(0x14FFFFFF);
        c.drawRoundRect(rf, 10, 10, p);
        t.setColor(TXT);
        c.drawText(zrTxt, 138, ny + 15, t);
        hits.add(new Hit("heute", 126, ny - 4, 126 + pw, ny + 24));

        float rx = 126 + pw + 4;
        drawIcon(c, "mdi:chevron-right", rx, ny, 20, SUB);
        hits.add(new Hit("nav:1", rx - 4, ny - 6, rx + 26, ny + 26));

        if (rA > t0 || rB <= t0) {
            float hx = rx + 28;
            t.setTypeface(tfMed); t.setTextSize(14); t.setColor(0xFF6FB3F2);
            c.drawText("Heute", hx, ny + 15, t);
            hits.add(new Hit("heute", hx - 6, ny - 4, hx + t.measureText("Heute") + 8, ny + 24));
        }

        // Top-right 5-tab segmented bar
        float totalW = 8;
        float[] tabW = new float[TAB_KEYS.length];
        t.setTextSize(14.5f);
        for (int i = 0; i < TAB_KEYS.length; i++) {
            t.setTypeface(TAB_KEYS[i].equals(modus) ? tfMed : tfNorm);
            tabW[i] = t.measureText(TAB_NAMES[i]) + 26;
            totalW += tabW[i];
        }
        float tx0 = W - 30 - totalW, ty0 = 24, th = 42;
        rf.set(tx0, ty0, tx0 + totalW, ty0 + th);
        p.setColor(0x10FFFFFF);
        c.drawRoundRect(rf, 21, 21, p);
        float cx = tx0 + 4;
        for (int i = 0; i < TAB_KEYS.length; i++) {
            boolean aktiv = TAB_KEYS[i].equals(modus);
            rf.set(cx, ty0 + 4, cx + tabW[i], ty0 + th - 4);
            if (aktiv) {
                p.setColor(TXT);
                c.drawRoundRect(rf, 17, 17, p);
            }
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(aktiv ? tfMed : tfNorm);
            t.setColor(aktiv ? BG : 0xFF9DA0A2);
            c.drawText(TAB_NAMES[i], rf.centerX(), ty0 + 26, t);
            hits.add(new Hit("v:" + TAB_KEYS[i], rf.left, ty0, rf.right, ty0 + th));
            cx += tabW[i];
        }
        t.setTextAlign(Paint.Align.LEFT);
    }

    // ---- Time Grid (Tag / 3 Tage / Woche) --------------------------------------------------------------------------

    private void zeichneZeitraster(Canvas c, Calendar rStart, int rN, long t0, long nowMs) {
        float x0 = 20, y0 = 90, w = W - 40, h = H - 102;
        float lw = 48, cw = (w - lw) / rN;

        long[] dayA = new long[rN], dayB = new long[rN];
        String[] dayKey = new String[rN];
        boolean[] dayMark = new boolean[rN];
        Calendar cur = (Calendar) rStart.clone();
        for (int i = 0; i < rN; i++) {
            dayA[i] = cur.getTimeInMillis();
            dayKey[i] = Kalender.datumKey(cur);
            dayMark[i] = markiert.contains(dayKey[i]);
            cur.add(Calendar.DAY_OF_MONTH, 1);
            dayB[i] = cur.getTimeInMillis();
        }

        // Highlight frames behind marked day columns (neighbouring marked days share ONE crisp blue frame!)
        for (int i = 0; i < rN; i++) {
            if (!dayMark[i] || (i > 0 && dayMark[i - 1])) continue;
            int j = i;
            while (j + 1 < rN && dayMark[j + 1]) j++;
            rf.set(x0 + lw + i * cw + 1, y0, x0 + lw + (j + 1) * cw - 1, y0 + h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1A8FC0FF);
            c.drawRoundRect(rf, 14, 14, p);
            linie.setStrokeWidth(1.5f);
            linie.setColor(0x8C8FC0FF);
            c.drawRoundRect(rf, 14, 14, linie);
        }

        List<Kalender.Termin> evs = ereignisse != null ? ereignisse : Collections.emptyList();
        List<Kalender.Termin> allDay = new ArrayList<>();
        int h0 = 7, h1 = 22;
        for (Kalender.Termin e : evs) {
            if (e.endeMs <= dayA[0] || e.startMs >= dayB[rN - 1]) continue;
            if (e.ganztag) {
                allDay.add(e);
            } else {
                for (int i = 0; i < rN; i++) {
                    if (e.startMs < dayB[i] && e.endeMs > dayA[i]) {
                        long a = Math.max(e.startMs, dayA[i]), b = Math.min(e.endeMs, dayB[i]);
                        Calendar ca = Calendar.getInstance(); ca.setTimeInMillis(a);
                        Calendar cb = Calendar.getInstance(); cb.setTimeInMillis(b);
                        h0 = Math.min(h0, ca.get(Calendar.HOUR_OF_DAY));
                        int bh = cb.get(Calendar.HOUR_OF_DAY) + (cb.get(Calendar.MINUTE) > 0 ? 1 : 0);
                        h1 = Math.max(h1, bh == 0 ? 24 : bh);
                    }
                }
            }
        }
        h1 = Math.min(24, Math.max(h0 + 6, h1));
        float topOff = allDay.isEmpty() ? 32 : 60;
        float ph = (h - topOff - 8) / (float) (h1 - h0);

        // Day column headers + vertical hairlines
        cur = (Calendar) rStart.clone();
        for (int i = 0; i < rN; i++) {
            float colX = x0 + lw + i * cw;
            boolean heute = dayA[i] == t0;
            String lbl = rN == 1 ? datumLang(cur) : WT[cur.get(Calendar.DAY_OF_WEEK) - 1] + " " + cur.get(Calendar.DAY_OF_MONTH);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(13.5f);
            t.setTypeface(heute || dayMark[i] ? tfBold : tfNorm);
            t.setColor(heute || dayMark[i] ? 0xFFFFFFFF : SUB);
            c.drawText(lbl, colX + cw * .5f, y0 + 18, t);
            hits.add(new Hit("tag:" + dayA[i], colX, y0, colX + cw, y0 + 26));

            if (i > 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x0DFFFFFF);
                c.drawRect(colX, y0 + 24, colX + 1, y0 + h, p);
            }
            cur.add(Calendar.DAY_OF_MONTH, 1);
        }

        // Hour grid lines & labels
        int stepH = (h1 - h0) > 12 ? 2 : 1;
        t.setTextAlign(Paint.Align.RIGHT);
        t.setTypeface(tfNorm); t.setTextSize(11); t.setColor(0xFF6C7884);
        for (int hr = h0; hr <= h1; hr += stepH) {
            float gy = y0 + topOff + (hr - h0) * ph;
            c.drawText(hr + ":00", x0 + lw - 8, gy + 4, t);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x0BFFFFFF);
            c.drawRect(x0 + lw, gy, x0 + w, gy + 1, p);
        }
        t.setTextAlign(Paint.Align.LEFT);

        // All-day event bars (up to 2 rows)
        for (int idx = 0; idx < Math.min(2, allDay.size()); idx++) {
            Kalender.Termin e = allDay.get(idx);
            int i0 = 0, i1 = rN;
            for (int i = 0; i < rN; i++) if (dayB[i] > e.startMs) { i0 = i; break; }
            for (int i = rN - 1; i >= 0; i--) if (dayA[i] < e.endeMs) { i1 = i + 1; break; }
            float bx = x0 + lw + i0 * cw + 4, bw = Math.max(20, (i1 - i0) * cw - 8);
            float by = y0 + 28 + idx * 14;
            rf.set(bx, by, bx + bw, by + 22);
            p.setStyle(Paint.Style.FILL);
            p.setColor(WetterLage.alpha(e.farbe, .22f));
            c.drawRoundRect(rf, 7, 7, p);
            t.setTypeface(tfMed); t.setTextSize(12); t.setColor(e.farbe);
            c.drawText(kuerzen(t, e.titel, bw - 14), bx + 7, by + 15, t);
            hits.add(new Hit("ev:" + e.uid, rf.left, rf.top, rf.right, rf.bottom));
        }

        // Timed event cards per column (overlapping events laid out side by side)
        boolean irgendein = !allDay.isEmpty();
        for (int i = 0; i < rN; i++) {
            List<long[]> raw = new ArrayList<>();
            List<Kalender.Termin> colEvs = new ArrayList<>();
            for (Kalender.Termin e : evs) {
                if (!e.ganztag && e.startMs < dayB[i] && e.endeMs > dayA[i]) {
                    colEvs.add(e);
                    raw.add(new long[] {Math.max(e.startMs, dayA[i]), Math.min(e.endeMs, dayB[i]), 0});
                }
            }
            if (colEvs.isEmpty()) continue;
            irgendein = true;
            // Assign sub-columns greedily
            List<Long> spEnde = new ArrayList<>();
            for (int k = 0; k < raw.size(); k++) {
                long a = raw.get(k)[0], b = raw.get(k)[1];
                int sp = 0;
                while (sp < spEnde.size() && spEnde.get(sp) > a) sp++;
                if (sp == spEnde.size()) spEnde.add(b);
                else spEnde.set(sp, b);
                raw.get(k)[2] = sp;
            }
            for (int k = 0; k < raw.size(); k++) {
                long a = raw.get(k)[0], b = raw.get(k)[1];
                int cIdx = (int) raw.get(k)[2], maxC = 1;
                for (int m = 0; m < raw.size(); m++) {
                    if (raw.get(m)[0] < b && raw.get(m)[1] > a) maxC = Math.max(maxC, (int) raw.get(m)[2] + 1);
                }
                float subW = (cw - 8) / maxC;
                float ex = x0 + lw + i * cw + 4 + cIdx * subW;
                float ew = Math.max(18, subW - 2);
                float ey = y0 + topOff + ((a - dayA[i]) / 3_600_000f - h0) * ph;
                float eh = Math.max(22, (b - a) / 3_600_000f * ph - 2);
                Kalender.Termin e = colEvs.get(k);
                rf.set(ex, ey, ex + ew, ey + eh);
                p.setStyle(Paint.Style.FILL);
                p.setColor(WetterLage.alpha(e.farbe, .20f));
                c.drawRoundRect(rf, 8, 8, p);
                // Solid 3 px left accent bar
                p.setColor(e.farbe);
                c.drawRoundRect(ex, ey + 1, ex + 3.5f, ey + eh - 1, 2, 2, p);
                if (e.uid.equals(ausgewaehltUid)) {
                    linie.setStrokeWidth(2f);
                    linie.setColor(e.farbe);
                    c.drawRoundRect(rf, 8, 8, linie);
                }
                float tx = ex + 8, maxTw = Math.max(10, ew - 12);
                t.setTextSize(12);
                if (eh < 34) {
                    t.setTypeface(tfMed); t.setColor(TXT);
                    String line = (ew > 94 ? Kalender.hm(e.startMs) + " " : "") + e.titel;
                    c.drawText(kuerzen(t, line, maxTw), tx, ey + eh * .5f + 4, t);
                } else {
                    t.setTypeface(tfMed); t.setColor(TXT);
                    c.drawText(kuerzen(t, e.titel, maxTw), tx, ey + 15, t);
                    t.setTypeface(tfNorm); t.setColor(0xFFC3CCD5);
                    String sub = Kalender.hm(e.startMs) + (!e.ort.isEmpty() && eh > 52 ? " · " + e.ort : "");
                    c.drawText(kuerzen(t, sub, maxTw), tx, ey + 30, t);
                }
                hits.add(new Hit("ev:" + e.uid, rf.left, rf.top, rf.right, rf.bottom));
            }
        }

        // Live red current-time line on today's column
        for (int i = 0; i < rN; i++) {
            if (dayA[i] == t0) {
                Calendar nc = Calendar.getInstance(); nc.setTimeInMillis(nowMs);
                float ny = y0 + topOff + (nc.get(Calendar.HOUR_OF_DAY) + nc.get(Calendar.MINUTE) / 60f - h0) * ph;
                if (ny >= y0 + topOff && ny <= y0 + h) {
                    float lx = x0 + lw + i * cw;
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xFFF28B82);
                    c.drawRect(lx, ny - 1, lx + cw, ny + 1, p);
                    c.drawCircle(lx, ny, 5, p);
                }
                break;
            }
        }

        // Loading / empty hint
        if (ereignisse == null || fehler != null || !irgendein) {
            String msg = fehler != null ? "Fehler: " + fehler : (ereignisse == null ? "Termine werden geladen …" : "Keine Termine in diesem Zeitraum");
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(tfNorm); t.setTextSize(15.5f); t.setColor(SUB);
            c.drawText(msg, W * .5f, y0 + h * .52f, t);
            t.setTextAlign(Paint.Align.LEFT);
        }
    }

    // ---- Month View (7 x 6 grid) -----------------------------------------------------------------------------------

    private void zeichneMonat(Canvas c, Calendar rStart, long t0) {
        float x0 = 20, y0 = 90, w = W - 40, gap = 4;
        float cw = (w - gap * 6) / 7f, ch = 72;
        String[] hdr = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(tfNorm); t.setTextSize(12); t.setColor(SUB);
        for (int k = 0; k < 7; k++) c.drawText(hdr[k], x0 + k * (cw + gap) + cw * .5f, y0 + 12, t);
        t.setTextAlign(Paint.Align.LEFT);

        int zielMonat = basisTag.get(Calendar.MONTH);
        List<Kalender.Termin> evs = ereignisse != null ? ereignisse : Collections.emptyList();
        Calendar cur = (Calendar) rStart.clone();
        for (int idx = 0; idx < 42; idx++) {
            int row = idx / 7, col = idx % 7;
            float cx = x0 + col * (cw + gap), cy = y0 + 20 + row * (ch + gap);
            long dA = cur.getTimeInMillis();
            Calendar nxt = (Calendar) cur.clone(); nxt.add(Calendar.DAY_OF_MONTH, 1);
            long dB = nxt.getTimeInMillis();
            boolean aus = cur.get(Calendar.MONTH) != zielMonat;
            boolean heute = dA == t0;
            boolean mark = markiert.contains(Kalender.datumKey(cur));

            rf.set(cx, cy, cx + cw, cy + ch);
            p.setStyle(Paint.Style.FILL);
            p.setColor(mark ? 0x228FC0FF : (heute ? 0x1FFFFFFF : 0x08FFFFFF));
            c.drawRoundRect(rf, 10, 10, p);
            if (mark) {
                linie.setStrokeWidth(1.5f); linie.setColor(0x8C8FC0FF);
                c.drawRoundRect(rf, 10, 10, linie);
            }

            float alpha = aus ? .32f : (dA < t0 ? .58f : 1f);
            t.setTypeface(heute || mark ? tfBold : tfNorm);
            t.setTextSize(13);
            t.setColor(WetterLage.alpha(TXT, alpha));
            c.drawText(String.valueOf(cur.get(Calendar.DAY_OF_MONTH)), cx + 7, cy + 17, t);

            int shown = 0, total = 0;
            for (Kalender.Termin e : evs) {
                if (e.startMs < dB && e.endeMs > dA) {
                    total++;
                    if (shown < 2) {
                        float ey = cy + 24 + shown * 16;
                        p.setColor(WetterLage.alpha(e.farbe, alpha));
                        c.drawRect(cx + 6, ey, cx + 9, ey + 12, p);
                        t.setTypeface(tfNorm); t.setTextSize(11);
                        t.setColor(WetterLage.alpha(0xFFC3CCD5, alpha));
                        c.drawText(kuerzen(t, e.titel, cw - 18), cx + 13, ey + 10, t);
                        shown++;
                    }
                }
            }
            if (total > 2) {
                t.setTextSize(10.5f); t.setColor(WetterLage.alpha(SUB, alpha));
                c.drawText("+" + (total - 2), cx + 7, cy + 67, t);
            }
            hits.add(new Hit("tag:" + dA, cx, cy, cx + cw, cy + ch));
            cur = nxt;
        }
    }

    // ---- List / Agenda View (14 days, scrollable left column + detail right) ---------------------------------------

    private void zeichneListe(Canvas c, Calendar rStart, int rN, long t0, long nowMs) {
        float x0 = 30, y0 = 96, w = 460, h = H - y0 - 12;
        List<Kalender.Termin> evs = ereignisse != null ? ereignisse : Collections.emptyList();
        c.save();
        c.clipRect(x0 - 4, y0, x0 + w + 4, y0 + h);
        float y = y0 - listScrollY;
        int drawn = 0;
        Calendar cur = (Calendar) rStart.clone();
        for (int i = 0; i < rN; i++) {
            long dA = cur.getTimeInMillis();
            Calendar nxt = (Calendar) cur.clone(); nxt.add(Calendar.DAY_OF_MONTH, 1);
            long dB = nxt.getTimeInMillis();
            String dk = Kalender.datumKey(cur);
            boolean mark = markiert.contains(dk);
            List<Kalender.Termin> dayEvs = new ArrayList<>();
            for (Kalender.Termin e : evs) {
                if (e.startMs < dB && e.endeMs > dA && (dA >= t0 || e.endeMs > nowMs)) dayEvs.add(e);
            }
            if (dayEvs.isEmpty() && !mark) {
                cur = nxt;
                continue;
            }
            if (drawn > 0) y += 10;
            String hdr = dA == t0 ? "HEUTE" : (dA == t0 + 86_400_000L ? "MORGEN · " + datumKurz(cur).toUpperCase(Locale.GERMAN) : datumLang(cur).toUpperCase(Locale.GERMAN));
            t.setTypeface(mark ? tfBold : tfMed); t.setTextSize(12.5f); t.setColor(mark ? 0xFF9CC8EE : SUB);
            c.drawText(hdr, x0 + 4, y + 14, t);
            y += 22;

            if (dayEvs.isEmpty()) {
                rf.set(x0, y, x0 + w, y + 40);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x148FC0FF);
                c.drawRoundRect(rf, 12, 12, p);
                t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(SUB);
                c.drawText("Keine Termine", x0 + 14, y + 25, t);
                y += 48;
                drawn++;
            } else {
                for (Kalender.Termin e : dayEvs) {
                    float ch = e.ort.isEmpty() ? 46 : 60;
                    rf.set(x0, y, x0 + w, y + ch);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(BOX);
                    c.drawRoundRect(rf, 14, 14, p);
                    if (e.uid.equals(ausgewaehltUid)) {
                        linie.setStrokeWidth(2f); linie.setColor(e.farbe);
                        c.drawRoundRect(rf, 14, 14, linie);
                    }
                    p.setColor(e.farbe);
                    c.drawRoundRect(x0 + 12, y + 9, x0 + 17, y + ch - 9, 2.5f, 2.5f, p);
                    t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(0xFFC3CCD5);
                    c.drawText(e.zeit, x0 + 28, y + (e.ort.isEmpty() ? 28 : 26), t);
                    t.setTypeface(tfMed); t.setTextSize(16); t.setColor(TXT);
                    c.drawText(kuerzen(t, e.titel, w - 142), x0 + 124, y + (e.ort.isEmpty() ? 28 : 25), t);
                    if (!e.ort.isEmpty()) {
                        t.setTypeface(tfNorm); t.setTextSize(12.5f); t.setColor(SUB);
                        c.drawText(kuerzen(t, e.ort, w - 142), x0 + 124, y + 45, t);
                    }
                    if (y + ch > y0 && y < y0 + h) hits.add(new Hit("ev:" + e.uid, x0, Math.max(y0, y), x0 + w, Math.min(y0 + h, y + ch)));
                    y += ch + 7;
                    drawn++;
                }
            }
            cur = nxt;
        }
        listMaxScroll = Math.max(0, (y + listScrollY) - (y0 + h) + 24);
        if (drawn == 0) {
            t.setTypeface(tfNorm); t.setTextSize(16); t.setColor(SUB);
            c.drawText(ereignisse == null ? "Termine werden geladen …" : "Keine Termine in den nächsten " + rN + " Tagen.", x0 + 4, y0 + 36, t);
        }
        c.restore();
    }

    // ---- Event Detail Overlay Card ---------------------------------------------------------------------------------

    private void zeichneDetail(Canvas c, Kalender.Termin e, long t0, long nowMs) {
        float x = "liste".equals(modus) ? 510 : 480, y = 96, w = W - 30 - x, h = 420;
        rf.set(x, y, x + w, y + h);
        p.setStyle(Paint.Style.FILL);
        if (!"liste".equals(modus)) {
            p.setColor(0x66000000);
            c.drawRoundRect(x + 2, y + 10, x + w + 2, y + h + 12, 20, 20, p);
        }
        p.setColor(DETAIL_BG);
        c.drawRoundRect(rf, 20, 20, p);
        linie.setStrokeWidth(1f); linie.setColor(0x1EFFFFFF);
        c.drawRoundRect(rf, 20, 20, linie);

        // Close button
        p.setColor(0x14FFFFFF);
        c.drawCircle(x + w - 34, y + 34, 20, p);
        drawIcon(c, "mdi:close", x + w - 44, y + 24, 20, TXT);
        hits.add(new Hit("zu", x + w - 58, y + 10, x + w - 10, y + 58));

        // Calendar dot & name
        p.setColor(e.farbe);
        c.drawCircle(x + 27, y + 30, 5, p);
        t.setTypeface(tfMed); t.setTextSize(13.5f); t.setColor(e.farbe);
        c.drawText(e.kalender, x + 40, y + 35, t);

        // Title (up to 2 lines)
        t.setTypeface(tfMed); t.setTextSize(25); t.setColor(TXT);
        float ty = zeichneZeilen(c, e.titel, t, x + 22, y + 72, w - 82, 31, 2);

        // Time line
        ty += 26;
        drawIcon(c, "mdi:clock-outline", x + 22, ty - 14, 18, SUB);
        Calendar sc = Calendar.getInstance(); sc.setTimeInMillis(e.startMs);
        String tagStr = e.startMs >= t0 && e.startMs < t0 + 86_400_000L ? "Heute" : datumKurz(sc);
        String zeitInfo = tagStr + ", " + e.zeit;
        long vor = e.ganztag ? 0 : e.startMs - nowMs;
        if (vor > 0 && vor < 86_400_000L) {
            long m = Math.round(vor / 60_000.0);
            zeitInfo += " · in " + (m < 60 ? m + " min" : (m / 60) + ":" + String.format(Locale.ROOT, "%02d", m % 60) + " h");
        }
        t.setTypeface(tfNorm); t.setTextSize(16); t.setColor(0xFFC3CCD5);
        c.drawText(kuerzen(t, zeitInfo, w - 70), x + 48, ty, t);

        // Location
        if (!e.ort.isEmpty()) {
            ty += 30;
            drawIcon(c, "mdi:map-marker-outline", x + 22, ty - 14, 18, SUB);
            c.drawText(kuerzen(t, e.ort, w - 70), x + 48, ty, t);
        }

        // Description
        if (!e.beschreibung.isEmpty()) {
            ty += 32;
            t.setTextSize(14.5f); t.setColor(SUB);
            zeichneZeilen(c, e.beschreibung.replace('\n', ' '), t, x + 22, ty, w - 44, 21, 6);
        }
    }

    // ---- Touch & Navigation ----------------------------------------------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        try {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                downX = e.getX(); downY = e.getY(); lastY = downY; ziehend = false;
            } else if (a == MotionEvent.ACTION_MOVE && "liste".equals(modus) && listMaxScroll > 0) {
                float dyPx = (lastY - e.getY()) / skala;
                if (Math.abs(e.getY() - downY) > 12) ziehend = true;
                if (ziehend) {
                    listScrollY = Math.max(0, Math.min(listMaxScroll, listScrollY + dyPx));
                    lastY = e.getY();
                    invalidate();
                }
            } else if (a == MotionEvent.ACTION_UP && !ziehend && Math.hypot(e.getX() - downX, e.getY() - downY) < 28) {
                float vx = (e.getX() - dx) / skala, vy = (e.getY() - dy) / skala;
                for (int i = hits.size() - 1; i >= 0; i--) {
                    Hit h = hits.get(i);
                    if (h.trifft(vx, vy)) {
                        performClick();
                        ausfuehren(h.id);
                        return true;
                    }
                }
            }
        } catch (RuntimeException ignored) {}
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void ausfuehren(String id) {
        if ("zurueck".equals(id)) { aktionen.zurueck(); return; }
        if ("zu".equals(id)) { ausgewaehltUid = null; invalidate(); return; }
        if (id.startsWith("ev:")) {
            String uid = id.substring(3);
            ausgewaehltUid = uid.equals(ausgewaehltUid) ? null : uid;
            invalidate();
            return;
        }
        if (id.startsWith("v:")) {
            modus = id.substring(2);
            ausgewaehltUid = null;
            listScrollY = 0;
            neuLaden();
            return;
        }
        if ("heute".equals(id)) {
            basisTag = Kalender.mitternacht(Calendar.getInstance());
            ausgewaehltUid = null;
            listScrollY = 0;
            neuLaden();
            return;
        }
        if (id.startsWith("nav:")) {
            int dir = Integer.parseInt(id.substring(4));
            if ("monat".equals(modus)) {
                basisTag.set(Calendar.DAY_OF_MONTH, 1);
                basisTag.add(Calendar.MONTH, dir);
            } else {
                basisTag.add(Calendar.DAY_OF_MONTH, dir * sichtTage());
            }
            ausgewaehltUid = null;
            listScrollY = 0;
            neuLaden();
            return;
        }
        if (id.startsWith("tag:")) {
            long ms = Long.parseLong(id.substring(4));
            basisTag.setTimeInMillis(ms);
            Kalender.mitternacht(basisTag);
            modus = "tag";
            ausgewaehltUid = null;
            neuLaden();
        }
    }

    private void neuLaden() {
        ereignisse = null;
        fehler = null;
        invalidate();
        aktionen.bereichLaden(sichtStart(), sichtTage());
    }

    // ---- Formatting & icons ----------------------------------------------------------------------------------------

    private String zeitraumLabel(Calendar rStart, int rN) {
        if ("monat".equals(modus)) return MON[basisTag.get(Calendar.MONTH)] + " " + basisTag.get(Calendar.YEAR);
        if (rN == 1) return datumLang(rStart);
        Calendar b = (Calendar) rStart.clone();
        b.add(Calendar.DAY_OF_MONTH, rN - 1);
        String kw = "woche".equals(modus) ? "KW " + rStart.get(Calendar.WEEK_OF_YEAR) + " · " : "";
        String aStr = rStart.get(Calendar.DAY_OF_MONTH) + "." + (rStart.get(Calendar.MONTH) != b.get(Calendar.MONTH) ? " " + MON[rStart.get(Calendar.MONTH)] : "");
        return kw + aStr + " – " + b.get(Calendar.DAY_OF_MONTH) + ". " + MON[b.get(Calendar.MONTH)];
    }

    private static String datumLang(Calendar c) {
        return WTL[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH) + ". " + MON[c.get(Calendar.MONTH)];
    }

    private static String datumKurz(Calendar c) {
        return WT[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH) + ". " + MON[c.get(Calendar.MONTH)];
    }

    private float zeichneZeilen(Canvas c, String s, Paint pt, float x, float y, float maxW, float lh, int maxZ) {
        String rest = s == null ? "" : s.trim();
        float cy = y;
        for (int z = 0; z < maxZ && !rest.isEmpty(); z++) {
            if (z == maxZ - 1 || pt.measureText(rest) <= maxW) {
                c.drawText(kuerzen(pt, rest, maxW), x, cy, pt);
                return cy;
            }
            int cut = rest.length();
            while (cut > 0 && pt.measureText(rest.substring(0, cut)) > maxW) cut--;
            int sp = rest.lastIndexOf(' ', cut);
            if (sp > 4) cut = sp;
            c.drawText(rest.substring(0, cut).trim(), x, cy, pt);
            rest = rest.substring(cut).trim();
            cy += lh;
        }
        return cy;
    }

    private void drawIcon(Canvas c, String name, float x, float y, float size, int color) {
        Path path = iconPath(name);
        if (path != null) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.save();
            c.translate(x, y);
            c.scale(size / 24f, size / 24f);
            c.drawPath(path, p);
            c.restore();
            return;
        }
        c.save();
        c.translate(x, y);
        c.scale(size / 24f, size / 24f);
        linie.setColor(color); linie.setStrokeWidth(2.2f);
        iconTmp.reset();
        switch (name) {
            case "mdi:chevron-left":
                iconTmp.moveTo(15, 6); iconTmp.lineTo(9, 12); iconTmp.lineTo(15, 18);
                c.drawPath(iconTmp, linie);
                break;
            case "mdi:chevron-right":
                iconTmp.moveTo(9, 6); iconTmp.lineTo(15, 12); iconTmp.lineTo(9, 18);
                c.drawPath(iconTmp, linie);
                break;
            case "mdi:close":
                c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, linie);
                c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, linie);
                break;
            case "mdi:clock-outline":
                c.drawCircle(12, 12, 8, linie);
                c.drawLine(12, 12, 12, 7.5f, linie);
                c.drawLine(12, 12, 15.5f, 13.5f, linie);
                break;
            default:
                c.drawCircle(12, 10, 4.5f, linie);
                c.drawLine(12, 14.5f, 12, 20, linie);
                break;
        }
        c.restore();
    }

    private Path iconPath(String name) {
        if (theme == null || name == null) return null;
        if (iconCache.containsKey(name)) return iconCache.get(name);
        Path found = null;
        try { found = theme.iconPath(name); } catch (RuntimeException | LinkageError ignored) {}
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
