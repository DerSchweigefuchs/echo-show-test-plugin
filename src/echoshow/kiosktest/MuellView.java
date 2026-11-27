package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Native full-screen Waste Collection view matching muell.py on the 963 x 584 dashboard grid (#121A21):
 * - Top-left back circle button, "Müllabfuhr" title + calendar name & bin count
 * - Left column: next 6 pickup days with custom tapered bin graphic (lid, handle, body, vertical ribs),
 *   Heute/Morgen colored highlight & border, "steht draußen ✓" pill, and tap-to-toggle via echo_show/muell/erledigt_setzen
 * - Right column: 5-week Mo–So mini calendar with ‹ Heute › (±28 days) paging, colored bin dots, and active bin legend.
 */
final class MuellView extends FrameLayout {
    static final float W = 963, H = 584;
    private static final int BG = 0xFF121A21, BOX = 0xFF18232D, TXT = 0xFFECEBE8, SUB = 0xFF8FA3B5;
    private static final String[] WT = {"So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"};
    private static final String[] MON = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"};

    interface Aktionen {
        void zurueck();
        void ha(Map<String, Object> msg, Antwort cb);
        interface Antwort { void fertig(boolean ok, Object data, String err); }
    }

    private static final class TonneDef {
        final String key, name, icon;
        final int farbe;
        TonneDef(String key, String name, int farbe, String icon) {
            this.key = key; this.name = name; this.farbe = farbe; this.icon = icon;
        }
    }

    private static final class Abfuhr {
        final String datum, typ, name, icon, titel;
        final int farbe;
        boolean erledigt;
        Abfuhr(String datum, String typ, String name, int farbe, String icon, String titel, boolean erledigt) {
            this.datum = datum; this.typ = typ; this.name = name;
            this.farbe = farbe; this.icon = icon; this.titel = titel; this.erledigt = erledigt;
        }
    }

    private static final class TagesGruppe {
        final String datum;
        final List<Abfuhr> tonnen = new ArrayList<>();
        TagesGruppe(String datum) { this.datum = datum; }
        boolean istErledigt() {
            for (Abfuhr a : tonnen) if (a.erledigt) return true;
            return false;
        }
        void setErledigt(boolean v) {
            for (Abfuhr a : tonnen) a.erledigt = v;
        }
    }

    private static final class Hit {
        final String id;
        final float l, t, r, b;
        Hit(String id, float l, float t, float r, float b) { this.id = id; this.l = l; this.t = t; this.r = r; this.b = b; }
        boolean trifft(float x, float y) { return x >= l && x <= r && y >= t && y <= b; }
    }

    private final KsTheme theme;
    private final Aktionen aktionen;
    private final CanvasView canvasView;

    private boolean geladen;
    private boolean aktiv = true;
    private String fehler;
    private String quelleName = "Müllkalender";
    private final List<TonneDef> tonnenDefs = new ArrayList<>();
    private final List<TagesGruppe> kommende = new ArrayList<>();
    private final Map<String, List<Abfuhr>> proTag = new LinkedHashMap<>();
    private int kalenderOffset;   // -1 .. +3 in 28-day steps
    private boolean toggleLaeuft;

    MuellView(Context ctx, KsTheme theme, Aktionen aktionen) {
        super(ctx);
        this.theme = theme;
        this.aktionen = aktionen;
        setBackgroundColor(BG);
        setClickable(true);
        canvasView = new CanvasView(ctx);
        addView(canvasView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void setDaten(Object haDaten, String err) {
        fehler = err;
        if (haDaten instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) haDaten;
            geladen = true;
            aktiv = !Boolean.FALSE.equals(m.get("aktiv"));
            String qn = str(m.get("quelle_name"));
            if (qn.isEmpty()) qn = str(m.get("quelle"));
            if (!qn.isEmpty()) quelleName = qn;

            tonnenDefs.clear();
            if (m.get("tonnen") instanceof List) {
                for (Object o : (List<?>) m.get("tonnen")) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> tm = (Map<?, ?>) o;
                    String k = str(tm.get("key"));
                    String n = str(tm.get("name"));
                    int col = parseFarbe(str(tm.get("farbe")), 0xFF8FA3B5);
                    String ic = str(tm.get("icon"));
                    if (!n.isEmpty() || !k.isEmpty()) {
                        tonnenDefs.add(new TonneDef(k.isEmpty() ? n : k, n.isEmpty() ? k : n, col, ic));
                    }
                }
            }

            boolean globalDraussen = Boolean.TRUE.equals(m.get("draussen"));
            String erledigtDatum = str(m.get("erledigt_datum"));
            proTag.clear();
            List<Abfuhr> alle = new ArrayList<>();
            if (m.get("termine") instanceof List) {
                for (Object o : (List<?>) m.get("termine")) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> em = (Map<?, ?>) o;
                    String d = str(em.get("datum"));
                    if (d.length() > 10) d = d.substring(0, 10);
                    if (d.isEmpty()) continue;
                    String typ = str(em.get("typ"));
                    String name = str(em.get("name"));
                    String titel = str(em.get("titel"));
                    if (name.isEmpty()) name = titel.isEmpty() ? "Sonstiges" : titel;
                    int col = parseFarbe(str(em.get("farbe")), 0xFF8FA3B5);
                    String ic = str(em.get("icon"));
                    boolean erl = Boolean.TRUE.equals(em.get("erledigt")) || (globalDraussen && d.equals(erledigtDatum));
                    Abfuhr a = new Abfuhr(d, typ, name, col, ic, titel, erl);
                    alle.add(a);
                    List<Abfuhr> dayList = proTag.get(d);
                    if (dayList == null) {
                        dayList = new ArrayList<>();
                        proTag.put(d, dayList);
                    }
                    // Deduplicate same type on same day
                    boolean dup = false;
                    for (Abfuhr ex : dayList) {
                        if (ex.typ.equals(a.typ) && ex.name.equals(a.name)) { dup = true; break; }
                    }
                    if (!dup) dayList.add(a);
                }
            }

            Collections.sort(alle, (x, y) -> x.datum.compareTo(y.datum));
            String heute = Kalender.datumKey(Calendar.getInstance());
            kommende.clear();
            Map<String, TagesGruppe> grpMap = new LinkedHashMap<>();
            for (Abfuhr a : alle) {
                if (a.datum.compareTo(heute) < 0) continue;
                List<Abfuhr> dayList = proTag.get(a.datum);
                if (dayList == null) continue;
                TagesGruppe g = grpMap.get(a.datum);
                if (g == null) {
                    g = new TagesGruppe(a.datum);
                    g.tonnen.addAll(dayList);
                    grpMap.put(a.datum, g);
                    kommende.add(g);
                }
            }
        }
        canvasView.invalidate();
    }

    private void erledigtUmschalten(TagesGruppe g) {
        if (aktionen == null || toggleLaeuft || g == null) return;
        final boolean neu = !g.istErledigt();
        g.setErledigt(neu);
        toggleLaeuft = true;
        canvasView.invalidate();

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/muell/erledigt_setzen");
        msg.put("datum", g.datum);
        msg.put("erledigt", neu);
        aktionen.ha(msg, (ok, data, err) -> post(() -> TestAnsicht.sicher(() -> {
            toggleLaeuft = false;
            if (!ok) {
                g.setErledigt(!neu);
                if (err != null) fehler = err;
                canvasView.invalidate();
                return;
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("type", "echo_show/muell/daten");
            aktionen.ha(req, (ok2, data2, err2) -> post(() -> TestAnsicht.sicher(() -> {
                if (ok2 && data2 != null) setDaten(data2, null);
            })));
        })));
    }

    private static int parseFarbe(String hex, int fallback) {
        if (hex != null && hex.startsWith("#") && (hex.length() == 7 || hex.length() == 9)) {
            try {
                if (hex.length() == 7) return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
                return (int) Long.parseLong(hex.substring(1), 16);
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private final class CanvasView extends View {
        private final List<Hit> hits = new ArrayList<>();
        private final Map<String, Path> iconCache = new HashMap<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface tfNorm = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface tfBold = Typeface.create("sans-serif", Typeface.BOLD);
        private final RectF rf = new RectF();
        private final Path binPath = new Path();
        private final Path iconTmp = new Path();
        private float skala = 1f, dx, dy, downX, downY;

        CanvasView(Context ctx) {
            super(ctx);
            setClickable(true);
            linie.setStyle(Paint.Style.STROKE);
            linie.setStrokeCap(Paint.Cap.ROUND);
            linie.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas c) {
            int gw = getWidth(), gh = getHeight();
            if (gw <= 0 || gh <= 0) return;
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
                Calendar heuteCal = Kalender.mitternacht(Calendar.getInstance());
                String heute = Kalender.datumKey(heuteCal);
                Calendar morgenCal = (Calendar) heuteCal.clone();
                morgenCal.add(Calendar.DAY_OF_MONTH, 1);
                String morgen = Kalender.datumKey(morgenCal);

                List<TonneDef> legende = aktiveLegende();
                zeichneKopf(c, legende.size());
                if (geladen && !aktiv) {
                    t.setTextAlign(Paint.Align.CENTER);
                    t.setTypeface(tfNorm); t.setTextSize(18); t.setColor(SUB);
                    c.drawText("Müllabfuhr ist nicht eingerichtet.", W * .5f, H * .52f, t);
                    t.setTextAlign(Paint.Align.LEFT);
                } else {
                    zeichneListe(c, heuteCal, heute, morgen);
                    zeichneKalender(c, heuteCal, heute, legende);
                }
            } catch (RuntimeException ignored) {}
            c.restore();
        }

        private List<TonneDef> aktiveLegende() {
            if (!tonnenDefs.isEmpty()) return tonnenDefs;
            Map<String, TonneDef> seen = new LinkedHashMap<>();
            for (List<Abfuhr> l : proTag.values()) {
                for (Abfuhr a : l) {
                    String k = a.typ.isEmpty() ? a.name : a.typ;
                    if (!seen.containsKey(k)) seen.put(k, new TonneDef(k, a.name, a.farbe, a.icon));
                }
            }
            return new ArrayList<>(seen.values());
        }

        private void zeichneKopf(Canvas c, int tonnenAnz) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1EFFFFFF);
            c.drawCircle(58, 50, 28, p);
            drawIcon(c, "mdi:chevron-left", 45, 37, 26, TXT);
            hits.add(new Hit("zurueck", 22, 14, 94, 86));

            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(tfMed); t.setTextSize(22); t.setColor(TXT);
            c.drawText("Müllabfuhr", 102, 44, t);

            t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(SUB);
            String sub = !geladen ? (fehler != null ? "Fehler: " + fehler : "Daten werden geladen …")
                : quelleName + (tonnenAnz > 0 ? " · " + tonnenAnz + (tonnenAnz == 1 ? " Tonne" : " Tonnen") : "");
            c.drawText(kuerzen(t, sub, 600), 102, 65, t);
        }

        private void zeichneListe(Canvas c, Calendar heuteCal, String heute, String morgen) {
            float x = 30, y = 100, w = 420;
            if (kommende.isEmpty()) {
                t.setTypeface(tfNorm); t.setTextSize(16); t.setColor(SUB);
                String msg = !geladen ? (fehler != null ? "Fehler: " + fehler : "lädt …")
                    : "Keine Abfuhrtermine in den nächsten Wochen.";
                c.drawText(msg, x + 8, y + 40, t);
                return;
            }
            int count = Math.min(6, kommende.size());
            float rowH = 68, gap = 8;
            for (int i = 0; i < count; i++) {
                TagesGruppe g = kommende.get(i);
                Abfuhr prim = g.tonnen.get(0);
                boolean istHeute = g.datum.equals(heute);
                boolean istMorgen = g.datum.equals(morgen);
                boolean akt = istHeute || istMorgen;
                float ry = y + i * (rowH + gap);

                rf.set(x, ry, x + w, ry + rowH);
                p.setStyle(Paint.Style.FILL);
                p.setColor(akt ? WetterLage.alpha(prim.farbe, .20f) : BOX);
                c.drawRoundRect(rf, 16, 16, p);
                if (akt) {
                    linie.setStrokeWidth(1.6f);
                    linie.setColor(WetterLage.alpha(prim.farbe, .72f));
                    c.drawRoundRect(rf, 16, 16, linie);
                }

                // Date column (weekday + relative/date)
                Calendar dc = Kalender.tag(g.datum);
                String wt = WT[dc.get(Calendar.DAY_OF_WEEK) - 1];
                String subD = istHeute ? "heute" : (istMorgen ? "morgen"
                    : String.format(Locale.ROOT, "%02d.%02d.", dc.get(Calendar.DAY_OF_MONTH), dc.get(Calendar.MONTH) + 1));
                t.setTypeface(tfMed); t.setTextSize(21); t.setColor(TXT);
                c.drawText(wt, x + 16, ry + 32, t);
                t.setTypeface(akt ? tfMed : tfNorm); t.setTextSize(13);
                t.setColor(akt ? prim.farbe : SUB);
                c.drawText(subD, x + 16, ry + 51, t);

                // Tapered bin graphic
                drawTonne(c, x + 78, ry + 13, 42, prim.farbe);

                // Extra bin mini icons if multiple bins on same day
                float rightStop = x + w - 14;
                if (akt) {
                    boolean dr = g.istErledigt();
                    String pill = dr ? "steht draußen ✓" : "rausgestellt?";
                    t.setTypeface(tfMed); t.setTextSize(12.5f);
                    float pw = t.measureText(pill) + 20;
                    float px = x + w - 12 - pw, py = ry + (rowH - 28) * .5f;
                    rf.set(px, py, px + pw, py + 28);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(dr ? 0x2E6CC483 : 0x12FFFFFF);
                    c.drawRoundRect(rf, 14, 14, p);
                    t.setColor(dr ? 0xFF8FDCA3 : SUB);
                    c.drawText(pill, px + 10, py + 18.5f, t);
                    rightStop = px - 8;
                    hits.add(new Hit("toggle:" + i, x, ry, x + w, ry + rowH));
                } else if (g.tonnen.size() > 1) {
                    int extra = Math.min(3, g.tonnen.size() - 1);
                    float ex = x + w - 14 - extra * 24;
                    for (int k = 1; k <= extra; k++) {
                        drawTonne(c, ex + (k - 1) * 24, ry + 22, 22, g.tonnen.get(k).farbe);
                    }
                    rightStop = ex - 8;
                }

                // Bin title
                String label = prim.name;
                if (akt && g.tonnen.size() > 1) {
                    StringBuilder sb = new StringBuilder(prim.name);
                    for (int k = 1; k < g.tonnen.size(); k++) sb.append(" + ").append(g.tonnen.get(k).name);
                    label = sb.toString();
                }
                t.setTypeface(tfMed); t.setTextSize(18.5f); t.setColor(TXT);
                c.drawText(kuerzen(t, label, Math.max(40, rightStop - (x + 132))), x + 132, ry + 41, t);
            }
        }

        private void zeichneKalender(Canvas c, Calendar heuteCal, String heute, List<TonneDef> legende) {
            float bx = 480, by = 100, bw = W - 30 - bx, bh = H - 30 - by;
            rf.set(bx, by, bx + bw, by + bh);
            p.setStyle(Paint.Style.FILL);
            p.setColor(BOX);
            c.drawRoundRect(rf, 18, 18, p);

            Calendar d0 = Kalender.montag(heuteCal);
            d0.add(Calendar.DAY_OF_MONTH, kalenderOffset * 28);
            Calendar d1 = (Calendar) d0.clone();
            d1.add(Calendar.DAY_OF_MONTH, 34);
            String monat = d0.get(Calendar.MONTH) == d1.get(Calendar.MONTH)
                ? MON[d0.get(Calendar.MONTH)] + " " + d0.get(Calendar.YEAR)
                : MON[d0.get(Calendar.MONTH)] + " / " + MON[d1.get(Calendar.MONTH)] + " " + d1.get(Calendar.YEAR);

            // Header (< Monat > + Heute)
            float hy = by + 16;
            drawIcon(c, "mdi:chevron-left", bx + 16, hy, 24, kalenderOffset > -1 ? SUB : 0x4D8FA3B5);
            hits.add(new Hit("cal:-1", bx + 8, hy - 6, bx + 48, hy + 30));

            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(tfMed); t.setTextSize(16.5f); t.setColor(TXT);
            c.drawText(monat, bx + bw * .5f, hy + 18, t);

            if (kalenderOffset != 0) {
                t.setTypeface(tfMed); t.setTextSize(13); t.setColor(0xFF6FB3F2);
                c.drawText("Heute", bx + bw - 68, hy + 17, t);
                hits.add(new Hit("cal:0", bx + bw - 94, hy - 4, bx + bw - 42, hy + 28));
            }

            drawIcon(c, "mdi:chevron-right", bx + bw - 40, hy, 24, kalenderOffset < 3 ? SUB : 0x4D8FA3B5);
            hits.add(new Hit("cal:1", bx + bw - 48, hy - 6, bx + bw - 8, hy + 30));

            // Weekday row
            float gx = bx + 16, gy = by + 56, gw = bw - 32, gap = 4;
            float cw = (gw - gap * 6) / 7f, ch = 56;
            String[] hdr = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
            t.setTypeface(tfNorm); t.setTextSize(12); t.setColor(SUB);
            for (int k = 0; k < 7; k++) {
                c.drawText(hdr[k], gx + k * (cw + gap) + cw * .5f, gy + 12, t);
            }

            // 5 weeks = 35 cells
            Calendar cur = (Calendar) d0.clone();
            float cellsY = gy + 22;
            for (int k = 0; k < 35; k++) {
                int row = k / 7, col = k % 7;
                float cx = gx + col * (cw + gap), cy = cellsY + row * (ch + gap);
                String dk = Kalender.datumKey(cur);
                boolean isHeute = dk.equals(heute);
                boolean alt = dk.compareTo(heute) < 0;
                float alpha = alt ? .40f : 1f;

                rf.set(cx, cy, cx + cw, cy + ch);
                p.setStyle(Paint.Style.FILL);
                p.setColor(isHeute ? 0x24FFFFFF : 0x08FFFFFF);
                c.drawRoundRect(rf, 10, 10, p);

                t.setTypeface(isHeute ? tfBold : tfNorm);
                t.setTextSize(14);
                t.setColor(WetterLage.alpha(TXT, alpha));
                c.drawText(String.valueOf(cur.get(Calendar.DAY_OF_MONTH)), cx + cw * .5f, cy + 23, t);

                List<Abfuhr> dayBins = proTag.get(dk);
                if (dayBins != null && !dayBins.isEmpty()) {
                    int n = Math.min(4, dayBins.size());
                    float dotR = 4.5f, dotGap = 3.5f;
                    float totalW = n * (dotR * 2) + (n - 1) * dotGap;
                    float sx = cx + (cw - totalW) * .5f + dotR;
                    for (int i = 0; i < n; i++) {
                        p.setColor(WetterLage.alpha(dayBins.get(i).farbe, alpha));
                        c.drawCircle(sx + i * (dotR * 2 + dotGap), cy + 40, dotR, p);
                    }
                }
                cur.add(Calendar.DAY_OF_MONTH, 1);
            }
            t.setTextAlign(Paint.Align.LEFT);

            // Legend at bottom
            float lx = bx + 18, ly = cellsY + 5 * (ch + gap) + 18;
            t.setTypeface(tfNorm); t.setTextSize(12.5f);
            for (TonneDef td : legende) {
                float tw = 16 + t.measureText(td.name) + 14;
                if (lx + tw > bx + bw - 14) {
                    lx = bx + 18;
                    ly += 20;
                    if (ly > by + bh - 6) break;
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(td.farbe);
                c.drawCircle(lx + 5, ly - 4, 5, p);
                t.setColor(0xFFC3CCD5);
                c.drawText(td.name, lx + 15, ly, t);
                lx += tw;
            }
        }

        /** Draws a crisp tapered wheelie bin (handle, overhanging lid, tapered body, vertical ribs) in [x..x+sz, y..y+sz]. */
        private void drawTonne(Canvas c, float x, float y, float sz, int farbe) {
            c.save();
            c.translate(x, y);
            float s = sz / 24f;
            c.scale(s, s);

            // Handle at top
            linie.setColor(farbe);
            linie.setStrokeWidth(1.6f);
            c.drawRoundRect(8.5f, 1.8f, 15.5f, 4.4f, 1.2f, 1.2f, linie);

            // Overhanging lid
            p.setStyle(Paint.Style.FILL);
            p.setColor(farbe);
            c.drawRoundRect(3.2f, 4.2f, 20.8f, 7.2f, 1.5f, 1.5f, p);

            // Tapered body
            binPath.reset();
            binPath.moveTo(4.6f, 7.8f);
            binPath.lineTo(19.4f, 7.8f);
            binPath.lineTo(17.7f, 21.4f);
            binPath.quadTo(17.5f, 22.4f, 16.4f, 22.4f);
            binPath.lineTo(7.6f, 22.4f);
            binPath.quadTo(6.5f, 22.4f, 6.3f, 21.4f);
            binPath.close();
            c.drawPath(binPath, p);

            // Lid shadow separator + vertical ribs
            int ribCol = (farbe == 0xFFE8C23A) ? 0x553A2E05 : 0x55000000;
            linie.setColor(ribCol);
            linie.setStrokeWidth(1.1f);
            c.drawLine(4.8f, 7.9f, 19.2f, 7.9f, linie);
            c.drawLine(9.0f, 10.5f, 9.6f, 19.6f, linie);
            c.drawLine(12.0f, 10.5f, 12.0f, 19.6f, linie);
            c.drawLine(15.0f, 10.5f, 14.4f, 19.6f, linie);
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            try {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getX(); downY = e.getY();
                } else if (a == MotionEvent.ACTION_UP && Math.hypot(e.getX() - downX, e.getY() - downY) < 30) {
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
            if ("zurueck".equals(id)) {
                if (aktionen != null) aktionen.zurueck();
            } else if ("cal:0".equals(id)) {
                kalenderOffset = 0;
                invalidate();
            } else if (id.startsWith("cal:")) {
                int dir = Integer.parseInt(id.substring(4));
                kalenderOffset = Math.max(-1, Math.min(3, kalenderOffset + dir));
                invalidate();
            } else if (id.startsWith("toggle:")) {
                int idx = Integer.parseInt(id.substring(7));
                if (idx >= 0 && idx < kommende.size()) erledigtUmschalten(kommende.get(idx));
            }
        }

        private String kuerzen(Paint pt, String s, float maxW) {
            if (s == null || s.isEmpty() || pt.measureText(s) <= maxW) return s == null ? "" : s;
            int lo = 0, hi = s.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (pt.measureText(s.substring(0, mid) + "…") <= maxW) lo = mid;
                else hi = mid - 1;
            }
            return lo <= 0 ? "…" : s.substring(0, lo) + "…";
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
            linie.setColor(color);
            linie.setStrokeWidth(2.2f);
            iconTmp.reset();
            if ("mdi:chevron-left".equals(name)) {
                iconTmp.moveTo(15, 6); iconTmp.lineTo(9, 12); iconTmp.lineTo(15, 18);
                c.drawPath(iconTmp, linie);
            } else if ("mdi:chevron-right".equals(name)) {
                iconTmp.moveTo(9, 6); iconTmp.lineTo(15, 12); iconTmp.lineTo(9, 18);
                c.drawPath(iconTmp, linie);
            } else {
                c.drawCircle(12, 12, 8, linie);
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
    }
}
