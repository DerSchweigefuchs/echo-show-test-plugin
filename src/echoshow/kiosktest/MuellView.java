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
 * Native full-screen Waste Collection view matching muell.py 1:1 on the 963 x 584 dashboard grid (#121A21):
 * - Top-left back circle button, "Müllabfuhr" title + calendar name & upcoming bin count
 * - Left column: next 6 upcoming pickup days (cutoff 10:00 on pickup day, vorabend highlight, "steht draußen ✓",
 *   40x40 / 22x22 rounded colored icon badges)
 * - Right column: 5-week Mo–So mini calendar (0..+2 pages, month name only) + upcoming bin legend.
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
    private String quelleName = "Müllabfuhr";
    private int vorabendMin = 18 * 60;
    private final List<TagesGruppe> kommende = new ArrayList<>();
    private final Map<String, List<Abfuhr>> proTag = new LinkedHashMap<>();
    private int kalenderOffset;   // 0 .. 2 in 28-day steps
    private boolean angehaengt;

    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            if (!angehaengt) return;
            datenNachladen();
            postDelayed(this, 60_000L);
        }
    };

    MuellView(Context ctx, KsTheme theme, Aktionen aktionen) {
        super(ctx);
        this.theme = theme;
        this.aktionen = aktionen;
        setBackgroundColor(BG);
        setClickable(true);
        canvasView = new CanvasView(ctx);
        addView(canvasView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        angehaengt = true;
        removeCallbacks(liveTick);
        postDelayed(liveTick, 60_000L);
    }

    @Override protected void onDetachedFromWindow() {
        angehaengt = false;
        removeCallbacks(liveTick);
        super.onDetachedFromWindow();
    }

    private void datenNachladen() {
        if (aktionen == null || !angehaengt) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/muell/daten");
        aktionen.ha(msg, (ok, data, err) -> post(() -> TestAnsicht.sicher(() -> {
            if (ok && data != null) setDaten(data, null);
        })));
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

            String va = str(m.get("vorabend"));
            if (va.matches("\\d{1,2}:\\d{2}")) {
                String[] parts = va.split(":");
                vorabendMin = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            } else {
                vorabendMin = 18 * 60;
            }

            boolean globalDraussen = Boolean.TRUE.equals(m.get("draussen"));
            String erledigtDatum = str(m.get("erledigt_datum"));
            proTag.clear();
            List<Abfuhr> alle = new ArrayList<>();
            Calendar nowCal = Calendar.getInstance();
            String heute = Kalender.datumKey(nowCal);
            int nowHour = nowCal.get(Calendar.HOUR_OF_DAY);

            if (m.get("termine") instanceof List) {
                for (Object o : (List<?>) m.get("termine")) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> em = (Map<?, ?>) o;
                    String d = str(em.get("datum"));
                    if (d.length() > 10) d = d.substring(0, 10);
                    if (d.isEmpty()) continue;
                    // Today drops off at 10:00 matching muell.py:81
                    int cmp = d.compareTo(heute);
                    if (cmp < 0 || (cmp == 0 && nowHour >= 10)) continue;

                    String typ = str(em.get("typ"));
                    String name = str(em.get("name"));
                    String titel = str(em.get("titel"));
                    if (name.isEmpty()) name = titel.isEmpty() ? "Sonstiges" : titel;
                    int col = parseFarbe(str(em.get("farbe")), 0xFF8FA3B5);
                    String ic = str(em.get("icon"));
                    if (ic.isEmpty()) ic = "mdi:delete-outline";
                    else if (!ic.startsWith("mdi:")) ic = "mdi:" + ic;
                    boolean erl = Boolean.TRUE.equals(em.get("erledigt")) || (globalDraussen && d.equals(erledigtDatum));
                    Abfuhr a = new Abfuhr(d, typ, name, col, ic, titel, erl);
                    alle.add(a);
                    List<Abfuhr> dayList = proTag.get(d);
                    if (dayList == null) {
                        dayList = new ArrayList<>();
                        proTag.put(d, dayList);
                    }
                    boolean dup = false;
                    for (Abfuhr ex : dayList) {
                        if (ex.typ.equals(a.typ) && ex.name.equals(a.name)) { dup = true; break; }
                    }
                    if (!dup) dayList.add(a);
                }
            }

            Collections.sort(alle, (x, y) -> x.datum.compareTo(y.datum));
            kommende.clear();
            Map<String, TagesGruppe> grpMap = new LinkedHashMap<>();
            for (Abfuhr a : alle) {
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
            Map<String, TonneDef> seen = new LinkedHashMap<>();
            for (TagesGruppe g : kommende) {
                for (Abfuhr a : g.tonnen) {
                    String k = a.typ.isEmpty() ? a.name : a.typ;
                    if (!seen.containsKey(k)) seen.put(k, new TonneDef(k, a.name, a.farbe, a.icon));
                }
            }
            return new ArrayList<>(seen.values());
        }

        private void zeichneKopf(Canvas c, int tonnenAnz) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x14FFFFFF);
            c.drawCircle(58, 50, 28, p);
            drawIcon(c, "mdi:chevron-left", 44, 36, 28, TXT);
            hits.add(new Hit("zurueck", 30, 22, 86, 78));

            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(tfMed); t.setTextSize(22); t.setColor(TXT);
            c.drawText("Müllabfuhr", 102, 44, t);

            t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(SUB);
            String sub = quelleName + (tonnenAnz > 0 ? " · " + tonnenAnz + (tonnenAnz == 1 ? " Tonne" : " Tonnen") : "");
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
            Calendar nowCal = Calendar.getInstance();
            int nowHour = nowCal.get(Calendar.HOUR_OF_DAY);
            int nowMin = nowHour * 60 + nowCal.get(Calendar.MINUTE);

            int count = Math.min(6, kommende.size());
            float rowH = 64, gap = 8;
            for (int i = 0; i < count; i++) {
                TagesGruppe g = kommende.get(i);
                Abfuhr prim = g.tonnen.get(0);
                boolean istHeute = g.datum.equals(heute);
                boolean istMorgen = g.datum.equals(morgen);
                boolean akt = (i == 0) && (istHeute ? nowHour < 10 : (istMorgen && nowMin >= vorabendMin));
                float ry = y + i * (rowH + gap);

                rf.set(x, ry, x + w, ry + rowH);
                p.setStyle(Paint.Style.FILL);
                p.setColor(akt ? WetterLage.alpha(prim.farbe, .20f) : BOX);
                c.drawRoundRect(rf, 16, 16, p);

                // Date column (weekday 22px + sub-date 13px #8FA3B5)
                Calendar dc = Kalender.tag(g.datum);
                String wt = WT[dc.get(Calendar.DAY_OF_WEEK) - 1];
                String subD = istHeute ? "heute" : (istMorgen ? "morgen"
                    : String.format(Locale.ROOT, "%02d.%02d.", dc.get(Calendar.DAY_OF_MONTH), dc.get(Calendar.MONTH) + 1));
                t.setTypeface(tfMed); t.setTextSize(22); t.setColor(TXT);
                c.drawText(wt, x + 16, ry + 31, t);
                t.setTypeface(tfNorm); t.setTextSize(13); t.setColor(SUB);
                c.drawText(subD, x + 16, ry + 49, t);

                // 40x40 rounded square badge matching tonne(t, 40)
                tonneBadge(c, x + 94, ry + 12, 40, 10, prim);

                // Extra bin badges (22x22, 5px gap) + plain green "steht draußen ✓" if akt && draussen
                float rightEdge = x + w - 16;
                if (g.tonnen.size() > 1) {
                    int extra = g.tonnen.size() - 1;
                    float exStart = rightEdge - (extra * 22 + (extra - 1) * 5);
                    for (int k = 1; k < g.tonnen.size(); k++) {
                        tonneBadge(c, exStart + (k - 1) * 27, ry + 21, 22, 6, g.tonnen.get(k));
                    }
                    rightEdge = exStart - 10;
                }
                if (akt && g.istErledigt()) {
                    t.setTypeface(tfNorm); t.setTextSize(13); t.setColor(0xFF8FDCA3);
                    t.setTextAlign(Paint.Align.RIGHT);
                    String drTxt = "steht draußen ✓";
                    c.drawText(drTxt, rightEdge, ry + 36.5f, t);
                    t.setTextAlign(Paint.Align.LEFT);
                    rightEdge -= t.measureText(drTxt) + 10;
                }

                // Bin title (never concatenated with "+")
                t.setTypeface(tfMed); t.setTextSize(19); t.setColor(TXT);
                c.drawText(kuerzen(t, prim.name, Math.max(40, rightEdge - (x + 148))), x + 148, ry + 39, t);
            }
        }

        private void tonneBadge(Canvas c, float x, float y, float sz, float rad, Abfuhr a) {
            rf.set(x, y, x + sz, y + sz);
            p.setStyle(Paint.Style.FILL);
            p.setColor(a.farbe);
            c.drawRoundRect(rf, rad, rad, p);
            float iconSz = Math.round(sz * 0.60f);
            int iconCol = "gelb".equals(a.typ) ? 0xFF3A2E05 : 0xFFFFFFFF;
            drawIcon(c, a.icon, x + (sz - iconSz) * 0.5f, y + (sz - iconSz) * 0.5f, iconSz, iconCol);
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
                ? MON[d0.get(Calendar.MONTH)]
                : MON[d0.get(Calendar.MONTH)] + " / " + MON[d1.get(Calendar.MONTH)];

            // Header (< Monat > — offset 0..2, no year, no Heute button)
            float hy = by + 18;
            drawIcon(c, "mdi:chevron-left", bx + 24, hy, 24, kalenderOffset > 0 ? SUB : 0x4D8FA3B5);
            hits.add(new Hit("cal:-1", bx + 16, hy - 6, bx + 56, hy + 30));

            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(tfMed); t.setTextSize(16); t.setColor(TXT);
            c.drawText(monat, bx + bw * .5f, hy + 18, t);

            drawIcon(c, "mdi:chevron-right", bx + bw - 48, hy, 24, kalenderOffset < 2 ? SUB : 0x4D8FA3B5);
            hits.add(new Hit("cal:1", bx + bw - 56, hy - 6, bx + bw - 16, hy + 30));

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
            float cellsY = gy + 19;
            for (int k = 0; k < 35; k++) {
                int row = k / 7, col = k % 7;
                float cx = gx + col * (cw + gap), cy = cellsY + row * (ch + gap);
                String dk = Kalender.datumKey(cur);
                boolean isHeute = dk.equals(heute);
                boolean alt = dk.compareTo(heute) < 0;
                float alpha = alt ? .40f : 1f;

                rf.set(cx, cy, cx + cw, cy + ch);
                p.setStyle(Paint.Style.FILL);
                p.setColor(isHeute ? 0x21FFFFFF : WetterLage.alpha(0xFFFFFFFF, 0.03f * alpha));
                c.drawRoundRect(rf, 10, 10, p);

                List<Abfuhr> dayBins = proTag.get(dk);
                boolean hatDots = dayBins != null && !dayBins.isEmpty();

                t.setTypeface(isHeute ? tfBold : tfNorm);
                t.setTextSize(14);
                t.setColor(WetterLage.alpha(TXT, alpha));
                c.drawText(String.valueOf(cur.get(Calendar.DAY_OF_MONTH)), cx + cw * .5f, hatDots ? (cy + 23) : (cy + 33), t);

                if (hatDots) {
                    int n = dayBins.size();
                    float dotR = 4.5f, dotGap = 3f;
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

            // Legend at bottom (13px, 6px dot gap, 14px item gap)
            float lx = bx + 16, ly = cellsY + 5 * (ch + gap) + 14;
            t.setTypeface(tfNorm); t.setTextSize(13);
            for (TonneDef td : legende) {
                float tw = 16 + t.measureText(td.name) + 14;
                if (lx + tw > bx + bw - 14) {
                    lx = bx + 16;
                    ly += 20;
                    if (ly > by + bh - 6) break;
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(td.farbe);
                c.drawCircle(lx + 5, ly - 4, 5, p);
                t.setColor(0xFFC3CCD5);
                c.drawText(td.name, lx + 16, ly, t);
                lx += tw;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            try {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getX(); downY = e.getY();
                } else if (a == MotionEvent.ACTION_UP && Math.hypot(e.getX() - downX, e.getY() - downY) <= 14f * skala) {
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
            } else if (id.startsWith("cal:")) {
                int dir = Integer.parseInt(id.substring(4));
                kalenderOffset = Math.max(0, Math.min(2, kalenderOffset + dir));
                invalidate();
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
