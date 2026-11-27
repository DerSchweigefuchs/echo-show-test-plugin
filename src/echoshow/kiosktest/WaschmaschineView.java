package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
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
 * Native full-screen Washing Machine view matching wasch.py 1:1 on the 963 x 584 dashboard grid (#0D1720):
 * - Top-right Jetzt | Verlauf segmented toggle
 * - Jetzt tab: 230px ring (running % / green checkmark done / idle), remaining/end time, phase,
 *   power/duration metric pills, green "Ausgeräumt" button (button.press), bottom timeline strip
 * - Verlauf tab: < period > + Stunden | Tage | Wochen, stacked category bars, 220px category summary ring with legend.
 */
final class WaschmaschineView extends FrameLayout {
    static final float W = 963f, H = 584f;
    private static final int BG = 0xFF0D1720, TXT = 0xFFECEBE8, SUB = 0xFF8FA3B5, BLUE = 0xFF6FB3F2, GREEN = 0xFF6CC483;
    private static final int[] CAT = {0xFF9FD0F8, 0xFF6FB3F2, 0xFF3D7FC4};
    private static final String[] WD = {"S", "M", "D", "M", "D", "F", "S"};
    private static final String[] WDL = {"So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"};
    private static final String[] MN = {"Jan", "Feb", "März", "Apr", "Mai", "Juni", "Juli", "Aug", "Sep", "Okt", "Nov", "Dez"};

    interface Aktionen {
        void zurueck();
        void ha(Map<String, Object> msg, Antwort cb);
        interface Antwort {
            void fertig(boolean ok, Object data, String err);
        }
    }

    private static final class Lauf {
        long s, e;
        int m;
        double k;
        boolean c;
    }

    private static final class Hit {
        final String id, arg;
        final float l, t, r, b;
        Hit(String id, String arg, float l, float t, float r, float b) {
            this.id = id;
            this.arg = arg;
            this.l = l; this.t = t; this.r = r; this.b = b;
        }
        boolean trifft(float x, float y) { return x >= l && x <= r && y >= t && y <= b; }
    }

    private final KsTheme theme;
    private final Aktionen aktionen;
    private final CanvasView canvasView;

    private String tab = "jetzt";          // "jetzt" | "verlauf"
    private String verlaufModus = "d";     // "h" | "d" | "w"
    private int verlaufSeite;
    private boolean angehaengt;
    private String fehler;

    // Parsed data from echo_show/waschmaschine/daten
    private boolean hatDaten;
    private String zustand = "unavailable";
    private long sinceMs;
    private String phase = "";
    private Double fortschritt;
    private String fertigUm = "";
    private long endMs;
    private Double restzeit;
    private Double verstrichen;
    private String startStr = "";
    private long startMs;
    private Double letzteDauer;
    private Double letzteEnergie;
    private Double leistung;
    private String entladen = "";
    private boolean entladenVerfuegbar;
    private final List<Lauf> runs = new ArrayList<>();

    private final Runnable refreshRun = this::datenNachladen;
    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            if (!angehaengt) return;
            datenNachladen();
            postDelayed(this, 5000L);
        }
    };

    WaschmaschineView(Context ctx, KsTheme theme, String initTab, Aktionen aktionen) {
        super(ctx);
        this.theme = theme;
        this.aktionen = aktionen;
        if ("verlauf".equalsIgnoreCase(initTab)) this.tab = "verlauf";
        setBackgroundColor(BG);
        setClickable(true);
        canvasView = new CanvasView(ctx);
        addView(canvasView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        angehaengt = true;
        removeCallbacks(liveTick);
        postDelayed(liveTick, 5000L);
    }

    @Override protected void onDetachedFromWindow() {
        angehaengt = false;
        removeCallbacks(liveTick);
        removeCallbacks(refreshRun);
        super.onDetachedFromWindow();
    }

    private void datenNachladen() {
        if (aktionen == null || !angehaengt) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/waschmaschine/daten");
        aktionen.ha(msg, (ok, data, err) -> post(() -> TestAnsicht.sicher(() -> {
            if (ok && data != null) setDaten(data, null);
            else if (err != null) setDaten(null, err);
        })));
    }

    void setDaten(Object haDaten, String fehler) {
        this.fehler = fehler;
        if (haDaten instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) haDaten;
            hatDaten = true;
            zustand = str(m.get("zustand"));
            if (zustand.isEmpty()) zustand = "unavailable";
            sinceMs = numLng(m.get("since_ms"), System.currentTimeMillis());
            phase = str(m.get("phase"));
            fortschritt = zahl(m.get("fortschritt"));
            fertigUm = str(m.get("fertig_um"));
            endMs = numLng(m.get("end_ms"), 0L);
            restzeit = zahl(m.get("restzeit"));
            verstrichen = zahl(m.get("verstrichen"));
            startStr = str(m.get("start"));
            startMs = numLng(m.get("start_ms"), 0L);
            letzteDauer = zahl(m.get("letzte_dauer"));
            letzteEnergie = zahl(m.get("letzte_energie"));
            leistung = zahl(m.get("leistung"));
            entladen = str(m.get("entladen"));
            entladenVerfuegbar = Boolean.TRUE.equals(m.get("entladen_verfuegbar"));

            runs.clear();
            if (m.get("runs") instanceof List) {
                for (Object item : (List<?>) m.get("runs")) {
                    if (!(item instanceof Map)) continue;
                    Map<?, ?> rm = (Map<?, ?>) item;
                    Lauf l = new Lauf();
                    l.s = numLng(rm.get("s"), 0L);
                    l.e = numLng(rm.get("e"), 0L);
                    l.m = (int) Math.round(numDbl(rm.get("m"), 0.0));
                    if (l.e <= l.s && l.m > 0) l.e = l.s + l.m * 60_000L;
                    l.k = numDbl(rm.get("k"), 0.0);
                    l.c = Boolean.TRUE.equals(rm.get("c"));
                    if (l.s > 0 && l.m > 0) runs.add(l);
                }
                Collections.sort(runs, (a, b) -> Long.compare(a.s, b.s));
            }
        }
        canvasView.invalidate();
    }

    void setTab(String tab) {
        String neu = "verlauf".equalsIgnoreCase(tab) ? "verlauf" : "jetzt";
        if (!neu.equals(this.tab)) {
            this.tab = neu;
            if ("verlauf".equals(neu)) verlaufSeite = 0;
            datenNachladen();
            canvasView.invalidate();
        }
    }

    private String modeKey() {
        if ("starting".equals(zustand) || "running".equals(zustand) || "paused".equals(zustand)
                || "user_paused".equals(zustand) || "ending".equals(zustand)) return "run";
        if ("finished".equals(zustand) || "clean".equals(zustand) || "anti_wrinkle".equals(zustand)) return "done";
        if ("unavailable".equals(zustand)) return "off";
        return "idle";
    }

    private String modeLabel() {
        switch (zustand) {
            case "starting": return "Startet";
            case "paused":
            case "user_paused": return "Pausiert";
            case "ending": return "Endet gleich";
            default: return "Läuft";
        }
    }

    private Lauf letzterLauf() {
        if (!runs.isEmpty()) return runs.get(runs.size() - 1);
        if (letzteDauer != null && letzteDauer > 0) {
            Lauf l = new Lauf();
            l.s = startMs > 0 ? startMs : (sinceMs > 0 ? sinceMs - Math.round(letzteDauer * 60_000.0) : System.currentTimeMillis());
            l.m = (int) Math.round(letzteDauer);
            l.e = l.s + l.m * 60_000L;
            l.k = letzteEnergie != null ? letzteEnergie : 0.0;
            return l;
        }
        return null;
    }

    private void ausraeumenDruecken() {
        if (aktionen == null || entladen.isEmpty()) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "call_service");
        msg.put("domain", "button");
        msg.put("service", "press");
        Map<String, Object> tgt = new LinkedHashMap<>();
        tgt.put("entity_id", entladen);
        msg.put("target", tgt);
        try {
            aktionen.ha(msg, (ok, data, err) -> {
                if (ok) {
                    post(() -> {
                        removeCallbacks(refreshRun);
                        postDelayed(refreshRun, 600L);
                    });
                }
            });
        } catch (RuntimeException ignored) {}
    }

    private final class CanvasView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        private final Typeface tfNorm = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final RectF rf = new RectF();
        private final Path iconTmp = new Path();
        private final Map<String, Path> iconCache = new HashMap<>();
        private final List<Hit> hits = new ArrayList<>();

        private final Shader glowBlue = new RadialGradient(W * 0.17f, H * 0.52f, 393.3f, 0x296FB3F2, 0x006FB3F2, Shader.TileMode.CLAMP);
        private final Shader glowGreen = new RadialGradient(W * 0.17f, H * 0.52f, 393.3f, 0x296CC483, 0x006CC483, Shader.TileMode.CLAMP);

        private float skala = 1f, dx, dy, downX, downY;

        CanvasView(Context ctx) {
            super(ctx);
            setClickable(true);
            ringP.setStyle(Paint.Style.STROKE);
            ringP.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas c) {
            int gw = getWidth(), gh = getHeight();
            if (gw <= 0 || gh <= 0) return;
            skala = Math.min(gw / W, gh / H);
            if (skala <= 0) return;
            dx = (gw - W * skala) * 0.5f;
            dy = (gh - H * skala) * 0.5f;
            hits.clear();

            c.drawColor(BG);
            c.save();
            c.translate(dx, dy);
            c.scale(skala, skala);
            c.clipRect(0, 0, W, H);
            try {
                long nowMs = System.currentTimeMillis();
                String mk = modeKey();
                p.setStyle(Paint.Style.FILL);
                p.setShader("done".equals(mk) ? glowGreen : glowBlue);
                c.drawRect(0, 0, W, H, p);
                p.setShader(null);

                zeichneKopf(c);

                if (!hatDaten) {
                    tPaint.setTextAlign(Paint.Align.CENTER);
                    tPaint.setTypeface(tfNorm);
                    tPaint.setTextSize(18f);
                    tPaint.setColor(SUB);
                    String msg = fehler != null ? "Fehler: " + fehler : ("verlauf".equals(tab) ? "Verlauf wird geladen …" : "Daten werden geladen …");
                    c.drawText(kuerzen(tPaint, msg, W - 80f), W * 0.5f, H * 0.52f, tPaint);
                    tPaint.setTextAlign(Paint.Align.LEFT);
                } else if ("verlauf".equals(tab)) {
                    zeichneVerlauf(c, nowMs);
                } else {
                    zeichneJetzt(c, mk, nowMs);
                }
            } catch (RuntimeException ignored) {}
            c.restore();
        }

        private void zeichneKopf(Canvas c) {
            // Back circle (58, 50)
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x14FFFFFF);
            c.drawCircle(58f, 50f, 28f, p);
            drawIcon(c, "mdi:chevron-left", 44f, 36f, 28f, TXT);
            hits.add(new Hit("zurueck", "", 22f, 14f, 94f, 86f));

            tPaint.setTextAlign(Paint.Align.LEFT);
            tPaint.setTypeface(tfMed);
            tPaint.setTextSize(22f);
            tPaint.setColor(TXT);
            c.drawText("Waschmaschine", 102f, 57f, tPaint);

            // Top-right toggle: Jetzt | Verlauf
            String[] keys = {"jetzt", "verlauf"};
            String[] names = {"Jetzt", "Verlauf"};
            float[] ws = new float[2];
            float totW = 8f;
            tPaint.setTextSize(17f);
            for (int i = 0; i < 2; i++) {
                tPaint.setTypeface(keys[i].equals(tab) ? tfMed : tfNorm);
                ws[i] = tPaint.measureText(names[i]) + 44f;
                totW += ws[i];
            }
            totW += 2f;
            float rx = W - 30f, lx = rx - totW, ty = 24f, th = 52f;
            p.setColor(0x12FFFFFF);
            rf.set(lx, ty, rx, ty + th);
            c.drawRoundRect(rf, 26f, 26f, p);

            float cx = lx + 4f;
            for (int i = 0; i < 2; i++) {
                boolean on = keys[i].equals(tab);
                rf.set(cx, ty + 4f, cx + ws[i], ty + th - 4f);
                if (on) {
                    p.setColor(TXT);
                    c.drawRoundRect(rf, 22f, 22f, p);
                }
                tPaint.setTextAlign(Paint.Align.CENTER);
                tPaint.setTypeface(on ? tfMed : tfNorm);
                tPaint.setColor(on ? BG : SUB);
                c.drawText(names[i], rf.centerX(), ty + 32f, tPaint);
                hits.add(new Hit("tab", keys[i], rf.left, ty, rf.right, ty + th));
                cx += ws[i] + 2f;
            }
            tPaint.setTextAlign(Paint.Align.LEFT);
        }

        private void zeichneJetzt(Canvas c, String mk, long nowMs) {
            // 230px ring at (36, 110) -> center (151, 225) matching wasch.py
            float cx = 151f, cy = 225f, dia = 230f, sw = 16f;
            double pct = fortschritt != null ? Math.max(0.0, Math.min(100.0, fortschritt)) : 0.0;
            if (fortschritt == null && restzeit != null && verstrichen != null && restzeit + verstrichen > 0) {
                pct = verstrichen / (restzeit + verstrichen) * 100.0;
            }
            int col = "done".equals(mk) ? GREEN : BLUE;
            List<float[]> segs = new ArrayList<>();
            if ("run".equals(mk)) segs.add(new float[] {(float) (pct / 100.0), col});
            else if ("done".equals(mk)) segs.add(new float[] {1f, GREEN});
            int trackCol = ("idle".equals(mk) || "off".equals(mk)) ? 0x12FFFFFF : 0x14FFFFFF;
            zeichneRing(c, cx, cy, dia, sw, segs, trackCol);

            if ("run".equals(mk)) {
                String pTxt = String.valueOf(Math.round(pct));
                tPaint.setTypeface(tfLight);
                tPaint.setTextSize(46f);
                float pw = tPaint.measureText(pTxt);
                tPaint.setTextSize(22f);
                float uw = tPaint.measureText("%");
                float sx = cx - (pw + uw + 2f) * 0.5f;
                tPaint.setTextSize(46f);
                tPaint.setColor(TXT);
                c.drawText(pTxt, sx, cy + 16f, tPaint);
                tPaint.setTextSize(22f);
                tPaint.setColor(SUB);
                c.drawText("%", sx + pw + 2f, cy + 16f, tPaint);
            } else if ("done".equals(mk)) {
                drawIcon(c, "mdi:check", cx - 38f, cy - 38f, 76f, GREEN);
            } else {
                drawIcon(c, "mdi:washing-machine", cx - 42f, cy - 42f, 84f, SUB);
            }

            // Hero text column (x = 310, vertically centered around cy = 225 matching wasch.py)
            Lauf last = letzterLauf();
            List<Lauf> d14 = tage(14, 0);
            int n14 = d14.size();
            double tot14 = 0;
            for (Lauf l : d14) tot14 += l.m;
            double avg14 = n14 > 0 ? tot14 / n14 : 0;

            String small, bigMain, bigUnit = "", line;
            if ("run".equals(mk)) {
                String phLbl = !phase.isEmpty() ? phase : modeLabel();
                small = phLbl + (!startStr.isEmpty() ? " · seit " + startStr : (startMs > 0 ? " · seit " + fmtUhr(startMs) : ""));
                if (restzeit == null) {
                    bigMain = modeLabel();
                } else if (restzeit >= 60.0) {
                    bigMain = fmtHm(restzeit);
                    bigUnit = "h";
                } else {
                    bigMain = String.valueOf(Math.max(0, Math.round(restzeit)));
                    bigUnit = "min";
                }
                line = !fertigUm.isEmpty() ? "fertig um " + fertigUm : (endMs > 0 ? "fertig um " + fmtUhr(endMs) : "");
            } else if ("done".equals(mk)) {
                long m = Math.max(0L, Math.round((nowMs - sinceMs) / 60_000.0));
                small = "Fertig";
                bigMain = m < 60 ? "seit " + m + " min" : "seit " + fmtHm(m) + " h";
                line = last != null
                    ? "Gelaufen " + fmtHm(last.m) + " h" + (last.k > 0.01 ? " · " + fmtKwh(last.k) + " kWh" : "")
                    : "Wäsche rausholen";
            } else {
                small = "off".equals(mk) ? "Offline" : "Bereit";
                bigMain = last != null ? fmtAgo(last.s, nowMs) : "–";
                line = last != null
                    ? weekdayKurz(last.s) + " " + fmtUhr(last.s) + " · " + fmtHm(last.m) + " h" + (last.k > 0.01 ? " · " + fmtKwh(last.k) + " kWh" : "")
                    : "";
            }

            float tx = 310f;
            float maxHeroW = 430f;
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(19f);
            tPaint.setColor(SUB);
            c.drawText(small, tx, "run".equals(mk) ? 156f : 164f, tPaint);

            tPaint.setTypeface(tfLight);
            tPaint.setTextSize("run".equals(mk) ? 104f : 66f);
            tPaint.setColor(TXT);
            float bigBase = "run".equals(mk) ? 256f : 238f;
            float bw = tPaint.measureText(bigMain);
            c.drawText(kuerzen(tPaint, bigMain, maxHeroW - (bigUnit.isEmpty() ? 0f : 66f)), tx, bigBase, tPaint);
            if (!bigUnit.isEmpty()) {
                tPaint.setTextSize(32f);
                tPaint.setColor(SUB);
                c.drawText(bigUnit, tx + Math.min(bw, maxHeroW - 66f) + 10f, bigBase, tPaint);
            }
            if (!line.isEmpty()) {
                tPaint.setTypeface(tfNorm);
                tPaint.setTextSize(21f);
                tPaint.setColor(col);
                c.drawText(line, tx, "run".equals(mk) ? 292f : 276f, tPaint);
            }

            // Right metric pills (top = 124, 180)
            if ("run".equals(mk)) {
                String p1 = (leistung == null ? "–" : Math.round(leistung)) + " W";
                String p2 = verstrichen != null ? fmtHm(verstrichen) + " h gelaufen" : "läuft";
                drawMetricPill(c, "mdi:flash", p1, 933f, 124f);
                drawMetricPill(c, "mdi:timer-outline", p2, 933f, 180f);
            } else {
                drawMetricPill(c, "mdi:washing-machine", n14 + " in 14 Tagen", 933f, 124f);
                drawMetricPill(c, "mdi:clock-outline", "Ø " + (n14 > 0 ? fmtHm(avg14) : "–") + " h", 933f, 180f);
            }

            // Green "✓ Ausgeräumt" button at right 30, top 250
            if ("done".equals(mk) && entladenVerfuegbar && !entladen.isEmpty()) {
                String lbl = "Ausgeräumt";
                tPaint.setTypeface(tfMed);
                tPaint.setTextSize(17f);
                float btnW = 16f + 30f + 10f + tPaint.measureText(lbl) + 22f;
                float btnL = 933f - btnW, btnT = 250f, btnH = 52f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x2E6CC483);
                rf.set(btnL, btnT, 933f, btnT + btnH);
                c.drawRoundRect(rf, 26f, 26f, p);
                drawIcon(c, "mdi:basket-check-outline", btnL + 16f, btnT + 11f, 30f, GREEN);
                tPaint.setColor(GREEN);
                c.drawText(lbl, btnL + 56f, btnT + 32f, tPaint);
                hits.add(new Hit("ausgeraeumt", "", btnL, btnT, 933f, btnT + btnH));
            }

            // Bottom timeline strip (y = 502..558, track center 514) matching wasch.py:262-270
            zeichneTimeline(c, mk, pct, last, col, nowMs, 40f, 502f, W - 80f);
        }

        private void drawMetricPill(Canvas c, String icon, String text, float rightX, float y) {
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(17f);
            float w = 18f + 20f + 9f + tPaint.measureText(text) + 18f;
            float l = rightX - w;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x12FFFFFF);
            rf.set(l, y, rightX, y + 44f);
            c.drawRoundRect(rf, 22f, 22f, p);
            drawIcon(c, icon, l + 18f, y + 12f, 20f, SUB);
            tPaint.setColor(TXT);
            c.drawText(text, l + 47f, y + 28f, tPaint);
        }

        private void zeichneTimeline(Canvas c, String mk, double pct, Lauf last, int col, long nowMs, float x, float y, float w) {
            long t0 = 0, t1 = 0;
            double tp = 1.0;
            String lab = "";
            boolean done = !"run".equals(mk);
            if ("run".equals(mk)) {
                long sEst = startMs > 0 ? startMs : (verstrichen != null ? nowMs - Math.round(verstrichen * 60_000.0) : 0L);
                long eEst = (endMs > sEst) ? endMs : (sEst > 0 && restzeit != null ? nowMs + Math.round(restzeit * 60_000.0) : 0L);
                if (sEst > 0 && eEst > sEst) {
                    t0 = sEst; t1 = eEst; tp = pct / 100.0;
                    lab = !phase.isEmpty() ? phase : "jetzt";
                }
            }
            if ((t0 <= 0 || t1 <= t0) && last != null) {
                t0 = last.s; t1 = last.s + last.m * 60_000L; tp = 1.0;
                lab = "Letzte Wäsche";
                done = true;
            }
            if (t0 <= 0 || t1 <= t0) return;

            float lineY = y + 12f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1AFFFFFF);
            rf.set(x, lineY - 2f, x + w, lineY + 2f);
            c.drawRoundRect(rf, 2f, 2f, p);

            p.setColor(done ? 0x736FB3F2 : col);
            rf.set(x, lineY - 2f, x + w * (float) tp, lineY + 2f);
            c.drawRoundRect(rf, 2f, 2f, p);

            // Start & end dots (20px diameter -> r=10)
            p.setColor(col);
            c.drawCircle(x, lineY, 10f, p);
            p.setColor(done ? col : 0xFF2A3A48);
            c.drawCircle(x + w, lineY, 10f, p);

            // Hour ticks (12px -> r=6)
            Calendar hc = Calendar.getInstance();
            hc.setTimeInMillis(t0);
            hc.set(Calendar.MINUTE, 0); hc.set(Calendar.SECOND, 0); hc.set(Calendar.MILLISECOND, 0);
            for (long h = hc.getTimeInMillis() + 3_600_000L; h < t1; h += 3_600_000L) {
                float f = (h - t0) / (float) (t1 - t0);
                if (f > 0.06f && f < 0.94f) {
                    float hx = x + w * f;
                    p.setColor(f <= tp ? col : 0xFF2A3A48);
                    c.drawCircle(hx, lineY, 6f, p);
                    tPaint.setTextAlign(Paint.Align.CENTER);
                    tPaint.setTypeface(tfNorm);
                    tPaint.setTextSize(14f);
                    tPaint.setColor(SUB);
                    c.drawText(fmtUhr(h), hx, lineY + 34f, tPaint);
                }
            }

            if (done) {
                tPaint.setTextAlign(Paint.Align.CENTER);
                tPaint.setTypeface(tfNorm);
                tPaint.setTextSize(15f);
                tPaint.setColor(SUB);
                c.drawText(lab, x + w * 0.5f, lineY - 20f, tPaint);
            } else {
                float mx = x + w * (float) tp;
                p.setColor(BG);
                c.drawCircle(mx, lineY, 16f, p);
                ringP.setColor(TXT);
                ringP.setStrokeWidth(4f);
                c.drawCircle(mx, lineY, 14f, ringP);
                tPaint.setTextAlign(Paint.Align.CENTER);
                tPaint.setTypeface(tfNorm);
                tPaint.setTextSize(15f);
                tPaint.setColor(TXT);
                c.drawText(lab, mx, lineY - 22f, tPaint);
            }

            tPaint.setTextAlign(Paint.Align.LEFT);
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(15f);
            tPaint.setColor(TXT);
            c.drawText(fmtUhr(t0), x - 10f, lineY + 38f, tPaint);
            tPaint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(fmtUhr(t1), x + w + 10f, lineY + 38f, tPaint);
            tPaint.setTextAlign(Paint.Align.LEFT);
        }

        private void zeichneVerlauf(Canvas c, long nowMs) {
            float leftX = 30f, leftW = 569f;   // 933 - 334 - 30 = 569
            float rightX = 643f, rightW = 290f;

            // Mode switcher (Stunden | Tage | Wochen): height 46, padding 4/18, gap 2
            String[] mKeys = {"h", "d", "w"};
            String[] mNames = {"Stunden", "Tage", "Wochen"};
            float[] mw = new float[3];
            float mTot = 8f + 4f;
            tPaint.setTextSize(16f);
            for (int i = 0; i < 3; i++) {
                tPaint.setTypeface(mKeys[i].equals(verlaufModus) ? tfMed : tfNorm);
                mw[i] = tPaint.measureText(mNames[i]) + 36f;
                mTot += mw[i];
            }
            float mRight = leftX + leftW, mLeft = mRight - mTot, mTop = 89f, mH = 46f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x12FFFFFF);
            rf.set(mLeft, mTop, mRight, mTop + mH);
            c.drawRoundRect(rf, 23f, 23f, p);
            float mx = mLeft + 4f;
            for (int i = 0; i < 3; i++) {
                boolean on = mKeys[i].equals(verlaufModus);
                rf.set(mx, mTop + 4f, mx + mw[i], mTop + mH - 4f);
                if (on) {
                    p.setColor(TXT);
                    c.drawRoundRect(rf, 19f, 19f, p);
                }
                tPaint.setTextAlign(Paint.Align.CENTER);
                tPaint.setTypeface(on ? tfMed : tfNorm);
                tPaint.setColor(on ? BG : SUB);
                c.drawText(mNames[i], rf.centerX(), mTop + 29f, tPaint);
                hits.add(new Hit("mode", mKeys[i], rf.left, mTop, rf.right, mTop + mH));
                mx += mw[i] + 2f;
            }
            tPaint.setTextAlign(Paint.Align.LEFT);

            int maxPg = maxPage(verlaufModus);
            if (verlaufSeite > maxPg) verlaufSeite = maxPg;

            // < period > nav: prev circle center (50, 112), range box x=82..278 (center 180), next circle center (298, 112)
            boolean canPrev = verlaufSeite < maxPg;
            boolean canNext = verlaufSeite > 0;
            p.setColor(WetterLage.alpha(0xFFFFFFFF, canPrev ? 0.08f : 0.08f * 0.3f));
            c.drawCircle(leftX + 20f, 112f, 20f, p);
            drawIcon(c, "mdi:chevron-left", leftX + 9f, 101f, 22f, WetterLage.alpha(TXT, canPrev ? 1f : 0.3f));
            if (canPrev) hits.add(new Hit("page", "1", leftX, 92f, leftX + 44f, 132f));

            p.setColor(WetterLage.alpha(0xFFFFFFFF, canNext ? 0.08f : 0.08f * 0.3f));
            c.drawCircle(298f, 112f, 20f, p);
            drawIcon(c, "mdi:chevron-right", 287f, 101f, 22f, WetterLage.alpha(TXT, canNext ? 1f : 0.3f));
            if (canNext) hits.add(new Hit("page", "-1", 276f, 92f, 320f, 132f));

            // Prepare data for selected mode
            long DAY = 86_400_000L, HOUR = 3_600_000L;
            String rng = "", headNum = "0", headTxt = "", sub = "", note = "";
            String[] rc = {"", "–", ""};
            List<float[]> segs = new ArrayList<>();
            List<Object[]> leg = new ArrayList<>();

            float chartTop = 246f, chartBot = 518f, chartH = chartBot - chartTop;
            p.setColor(0x14FFFFFF);
            c.drawRect(leftX, chartBot + 10f, leftX + leftW, chartBot + 12f, p);

            if ("d".equals(verlaufModus)) {
                Calendar t0 = mitternacht(nowMs);
                t0.add(Calendar.DAY_OF_MONTH, -(13 + verlaufSeite * 7));
                long[] dStart = new long[14];
                List<List<Lauf>> dRuns = new ArrayList<>(14);
                int[] dTot = new int[14];
                boolean[] dCoarse = new boolean[14];
                int n = 0, tot = 0; double kwh = 0; boolean coarse = false;
                int[] cc = {0, 0, 0};
                int maxMin = 150;
                for (int i = 0; i < 14; i++) {
                    long a = t0.getTimeInMillis();
                    Calendar nxt = (Calendar) t0.clone(); nxt.add(Calendar.DAY_OF_MONTH, 1);
                    long b = nxt.getTimeInMillis();
                    dStart[i] = a;
                    List<Lauf> dr = new ArrayList<>();
                    for (Lauf r : runs) {
                        if (r.s >= a && r.s < b) {
                            dr.add(r);
                            dTot[i] += r.m;
                            n++; tot += r.m; kwh += r.k;
                            cc[cat(r.m)]++;
                            if (r.c) { coarse = true; dCoarse[i] = true; }
                        }
                    }
                    dRuns.add(dr);
                    if (dTot[i] > maxMin) maxMin = dTot[i];
                    t0 = nxt;
                }

                float gap = 10f, colW = (leftW - gap * 13f) / 14f;
                for (int i = 0; i < 14; i++) {
                    float bx = leftX + i * (colW + gap);
                    List<Lauf> dr = dRuns.get(i);
                    float yCur = chartBot;
                    if (dr.isEmpty()) {
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0x14FFFFFF);
                        rf.set(bx, chartBot - 8f, bx + colW, chartBot);
                        c.drawRoundRect(rf, 4f, 4f, p);
                    } else {
                        // Stack earliest run at bottom and latest run on top (matching slice().reverse() top-to-bottom)
                        for (int j = 0; j < dr.size(); j++) {
                            Lauf r = dr.get(j);
                            float sh = Math.max(12f, (r.m / (float) maxMin) * (chartH * 0.86f));
                            p.setStyle(Paint.Style.FILL);
                            p.setColor(CAT[cat(r.m)]);
                            rf.set(bx, yCur - sh, bx + colW, yCur);
                            c.drawRoundRect(rf, 10f, 10f, p);
                            yCur -= sh + 4f;
                        }
                        tPaint.setTextAlign(Paint.Align.CENTER);
                        tPaint.setTypeface(tfNorm);
                        tPaint.setTextSize(13f);
                        tPaint.setColor(SUB);
                        c.drawText((dCoarse[i] ? "≈" : "") + fmtHm(dTot[i]), bx + colW * 0.5f, yCur - 2f, tPaint);
                    }
                    boolean today = verlaufSeite == 0 && i == 13;
                    tPaint.setTextAlign(Paint.Align.CENTER);
                    tPaint.setTypeface(today ? tfMed : tfNorm);
                    tPaint.setTextSize(today ? 13f : 15f);
                    tPaint.setColor(today ? TXT : SUB);
                    Calendar dc = Calendar.getInstance(); dc.setTimeInMillis(dStart[i]);
                    c.drawText(today ? "Heute" : WD[dc.get(Calendar.DAY_OF_WEEK) - 1], bx + colW * 0.5f, chartBot + 34f, tPaint);
                }

                rng = fmtDatum(dStart[0]) + " – " + fmtDatum(dStart[13]);
                headNum = String.valueOf(n);
                headTxt = (n == 1 ? "Wäsche" : "Wäschen") + " in 14 Tagen";
                sub = "zusammen " + fmtHm(tot) + " h · " + fmtKwh(kwh) + " kWh" + (coarse ? " · ≈ grob geschätzt" : "");
                if (n > 0) for (int i = 0; i < 3; i++) segs.add(new float[] {cc[i] / (float) n, CAT[i]});
                rc = new String[] {"Ø Dauer", n > 0 ? fmtHm(tot / (double) n) : "–", "Stunden"};
                leg.add(new Object[] {CAT[0], "Kurz · unter 1:15", String.valueOf(cc[0])});
                leg.add(new Object[] {CAT[1], "Normal · bis 2:00", String.valueOf(cc[1])});
                leg.add(new Object[] {CAT[2], "Lang · darüber", String.valueOf(cc[2])});
                note = n > 0 ? "Ø " + fmtKwh(kwh / n) + " kWh pro Wäsche" : "";
            } else if ("w".equals(verlaufModus)) {
                Calendar end = mitternacht(nowMs);
                end.add(Calendar.MONTH, -verlaufSeite);
                Calendar start = (Calendar) end.clone();
                start.add(Calendar.MONTH, -3);
                Calendar m0 = (Calendar) start.clone();
                int dow = (m0.get(Calendar.DAY_OF_WEEK) + 5) % 7;
                m0.add(Calendar.DAY_OF_MONTH, -dow);

                List<long[]> wkBounds = new ArrayList<>();
                List<List<Lauf>> wkRuns = new ArrayList<>();
                for (Calendar s = (Calendar) m0.clone(); s.getTimeInMillis() <= end.getTimeInMillis(); s.add(Calendar.DAY_OF_MONTH, 7)) {
                    long a = s.getTimeInMillis();
                    Calendar e = (Calendar) s.clone(); e.add(Calendar.DAY_OF_MONTH, 7);
                    long eb = e.getTimeInMillis();
                    long ca = Math.max(a, start.getTimeInMillis()), cb = Math.min(eb, end.getTimeInMillis() + DAY);
                    List<Lauf> wr = new ArrayList<>();
                    for (Lauf r : runs) if (r.s >= ca && r.s < cb) wr.add(r);
                    wkBounds.add(new long[] {a, eb});
                    wkRuns.add(wr);
                }
                int cols = Math.max(1, wkRuns.size()), maxN = 5, n = 0;
                int[] cc = {0, 0, 0};
                for (List<Lauf> wr : wkRuns) {
                    if (wr.size() > maxN) maxN = wr.size();
                    for (Lauf r : wr) { n++; cc[cat(r.m)]++; }
                }
                float gap = 8f, colW = (leftW - gap * (cols - 1)) / cols;
                float segH = Math.max(10f, (chartH * 0.86f) / maxN);
                int best = 0; long bt = 0;
                for (int i = 0; i < cols; i++) {
                    float bx = leftX + i * (colW + gap);
                    List<Lauf> wr = wkRuns.get(i);
                    if (wr.size() > best) { best = wr.size(); bt = wkBounds.get(i)[0]; }
                    float yCur = chartBot;
                    if (wr.isEmpty()) {
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0x14FFFFFF);
                        rf.set(bx, chartBot - 8f, bx + colW, chartBot);
                        c.drawRoundRect(rf, 4f, 4f, p);
                    } else {
                        // First run on top, last run at bottom (matching top-to-bottom wr.map())
                        for (int j = wr.size() - 1; j >= 0; j--) {
                            Lauf r = wr.get(j);
                            p.setStyle(Paint.Style.FILL);
                            p.setColor(CAT[cat(r.m)]);
                            rf.set(bx, yCur - segH, bx + colW, yCur);
                            c.drawRoundRect(rf, 9f, 9f, p);
                            yCur -= segH + 4f;
                        }
                        tPaint.setTextAlign(Paint.Align.CENTER);
                        tPaint.setTypeface(tfNorm);
                        tPaint.setTextSize(14f);
                        tPaint.setColor(SUB);
                        c.drawText(String.valueOf(wr.size()), bx + colW * 0.5f, yCur - 2f, tPaint);
                    }
                    Calendar f = Calendar.getInstance();
                    f.setTimeInMillis(wkBounds.get(i)[0]);
                    f.set(Calendar.DAY_OF_MONTH, 1);
                    if (f.getTimeInMillis() < wkBounds.get(i)[0]) f.add(Calendar.MONTH, 1);
                    if (f.getTimeInMillis() < wkBounds.get(i)[1]) {
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0x40FFFFFF);
                        c.drawRect(bx, chartBot + 18f, bx + 2f, chartBot + 36f, p);
                        tPaint.setTextAlign(Paint.Align.LEFT);
                        tPaint.setTypeface(tfNorm);
                        tPaint.setTextSize(15f);
                        tPaint.setColor(TXT);
                        c.drawText(MN[f.get(Calendar.MONTH)], bx + 6f, chartBot + 34f, tPaint);
                    }
                }

                rng = fmtDatum(start.getTimeInMillis()) + " – " + fmtDatum(end.getTimeInMillis());
                headNum = String.valueOf(n);
                headTxt = (n == 1 ? "Wäsche" : "Wäschen") + " in 3 Monaten";
                double nw = Math.max(1.0, (end.getTimeInMillis() - start.getTimeInMillis()) / (7.0 * DAY));
                sub = "Ø " + String.format(Locale.GERMAN, "%.1f", n / nw) + " pro Woche · ein Block = eine Wäsche";
                if (n > 0) for (int i = 0; i < 3; i++) segs.add(new float[] {cc[i] / (float) n, CAT[i]});
                rc = new String[] {"pro Woche", String.format(Locale.GERMAN, "%.1f", n / nw), "Wäschen"};
                leg.add(new Object[] {CAT[0], "Kurz · unter 1:15", String.valueOf(cc[0])});
                leg.add(new Object[] {CAT[1], "Normal · bis 2:00", String.valueOf(cc[1])});
                leg.add(new Object[] {CAT[2], "Lang · darüber", String.valueOf(cc[2])});
                note = best > 0 ? "am meisten: Woche ab " + fmtDatum(bt) + " (" + best + ")" : "";
            } else {
                Calendar nowH = Calendar.getInstance();
                nowH.setTimeInMillis(nowMs);
                nowH.set(Calendar.MINUTE, 0); nowH.set(Calendar.SECOND, 0); nowH.set(Calendar.MILLISECOND, 0);
                long t1 = nowH.getTimeInMillis() + HOUR - verlaufSeite * DAY;
                long t0 = t1 - 24 * HOUR;
                List<Lauf> sel = new ArrayList<>();
                boolean selCoarse = false;
                for (Lauf r : runs) {
                    if (r.s + r.m * 60_000L > t0 && r.s < t1) {
                        sel.add(r);
                        if (r.c) selCoarse = true;
                    }
                }

                double[] hMin = new double[24];
                int[] hCat = new int[24];
                java.util.Arrays.fill(hCat, -1);
                for (Lauf r : sel) {
                    long a = r.s, b = r.s + r.m * 60_000L;
                    for (int i = 0; i < 24; i++) {
                        long hs = t0 + i * HOUR, he = hs + HOUR;
                        long ov = Math.min(b, he) - Math.max(a, hs);
                        if (ov > 0) {
                            hMin[i] += ov / 60_000.0;
                            hCat[i] = cat(r.m);
                        }
                    }
                }
                float gap = 5f, colW = (leftW - gap * 23f) / 24f;
                for (int i = 0; i < 24; i++) {
                    float bx = leftX + i * (colW + gap);
                    Calendar hc = Calendar.getInstance(); hc.setTimeInMillis(t0 + i * HOUR);
                    int hr = hc.get(Calendar.HOUR_OF_DAY);
                    boolean isNow = verlaufSeite == 0 && i == 23;
                    p.setStyle(Paint.Style.FILL);
                    if (hMin[i] > 0 && hCat[i] >= 0) {
                        float bh = Math.max(chartH * 0.06f, (float) (Math.min(60.0, hMin[i]) / 60.0 * (chartH * 0.92f)));
                        p.setColor(CAT[hCat[i]]);
                        rf.set(bx, chartBot - bh, bx + colW, chartBot);
                        c.drawRoundRect(rf, 7f, 7f, p);
                    } else {
                        p.setColor(isNow ? 0x59FFFFFF : 0x14FFFFFF);
                        rf.set(bx, chartBot - 6f, bx + colW, chartBot);
                        c.drawRoundRect(rf, 3f, 3f, p);
                    }
                    tPaint.setTextAlign(Paint.Align.CENTER);
                    tPaint.setTypeface(tfNorm);
                    tPaint.setTextSize(14f);
                    tPaint.setColor(isNow ? TXT : SUB);
                    String lb = isNow ? "jetzt" : (hr % 3 == 0 ? String.valueOf(hr) : "");
                    if (!lb.isEmpty()) c.drawText(lb, bx + colW * 0.5f, chartBot + 34f, tPaint);
                }
                double runM = 0, kwh = 0;
                for (Lauf r : sel) {
                    double ov = Math.max(0L, Math.min(r.s + r.m * 60_000L, t1) - Math.max(r.s, t0)) / 60_000.0;
                    runM += ov; kwh += r.k;
                    if (ov > 0) segs.add(new float[] {(float) (ov / (24.0 * 60.0)), CAT[cat(r.m)]});
                }
                rng = verlaufSeite == 0 ? "letzte 24 Stunden" : (WDL[weekdayIdx(t0)] + " " + fmtUhr(t0) + " – " + WDL[weekdayIdx(t1)] + " " + fmtUhr(t1));
                headNum = String.valueOf(sel.size());
                headTxt = (sel.size() == 1 ? "Wäsche" : "Wäschen") + " in 24 Stunden";
                sub = "Balken = Minuten Laufzeit pro Stunde" + (selCoarse ? " · ≈ grob geschätzt" : "");
                rc = new String[] {"Laufzeit", runM > 0 ? fmtHm(runM) : "–", "Stunden"};
                for (int i = 0; i < Math.min(4, sel.size()); i++) {
                    Lauf r = sel.get(i);
                    leg.add(new Object[] {CAT[cat(r.m)], fmtUhr(r.s) + " – " + fmtUhr(r.s + r.m * 60_000L), fmtHm(r.m) + " h"});
                }
                if (sel.isEmpty()) leg.add(new Object[] {0x26FFFFFF, "Keine Wäsche", ""});
                note = kwh > 0.01 ? fmtKwh(kwh) + " kWh" : "";
            }

            // Period label, headline & sub (period centered at x = 180 matching left: 30+52, width: 196)
            tPaint.setTextAlign(Paint.Align.CENTER);
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(19f);
            tPaint.setColor(TXT);
            c.drawText(kuerzen(tPaint, rng, 192f), 180f, 118f, tPaint);
            tPaint.setTextAlign(Paint.Align.LEFT);

            tPaint.setTypeface(tfLight);
            tPaint.setTextSize(58f);
            tPaint.setColor(TXT);
            float hnw = tPaint.measureText(headNum);
            c.drawText(headNum, leftX, 198f, tPaint);
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(19f);
            tPaint.setColor(SUB);
            c.drawText(kuerzen(tPaint, headTxt, leftW - hnw - 16f), leftX + hnw + 12f, 198f, tPaint);
            tPaint.setTextSize(15f);
            c.drawText(kuerzen(tPaint, sub, leftW), leftX, 224f, tPaint);

            // Right summary 220px ring (cy = 202 matching 92 + 110)
            float rcx = rightX + rightW * 0.5f, rcy = 202f;
            zeichneRing(c, rcx, rcy, 220f, 18f, segs, 0x14FFFFFF);
            tPaint.setTextAlign(Paint.Align.CENTER);
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(15f);
            tPaint.setColor(SUB);
            c.drawText(rc[0], rcx, rcy - 30f, tPaint);
            tPaint.setTypeface(tfLight);
            tPaint.setTextSize(44f);
            tPaint.setColor(TXT);
            c.drawText(rc[1], rcx, rcy + 16f, tPaint);
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(15f);
            tPaint.setColor(SUB);
            c.drawText(rc[2], rcx, rcy + 42f, tPaint);
            tPaint.setTextAlign(Paint.Align.LEFT);

            // Legend rows
            float ly = 351f;
            tPaint.setFontFeatureSettings("tnum");
            for (Object[] row : leg) {
                int dotCol = (Integer) row[0];
                String lbl = (String) row[1];
                String val = (String) row[2];
                p.setStyle(Paint.Style.FILL);
                p.setColor(dotCol);
                c.drawCircle(rightX + 7f, ly - 5f, 7f, p);
                tPaint.setTypeface(tfNorm);
                tPaint.setTextSize(17f);
                tPaint.setColor(TXT);
                c.drawText(kuerzen(tPaint, lbl, rightW - 60f), rightX + 26f, ly, tPaint);
                if (!val.isEmpty()) {
                    tPaint.setTextAlign(Paint.Align.RIGHT);
                    tPaint.setColor(SUB);
                    c.drawText(val, rightX + rightW, ly, tPaint);
                    tPaint.setTextAlign(Paint.Align.LEFT);
                }
                ly += 30f;
            }
            tPaint.setFontFeatureSettings(null);
            if (!note.isEmpty()) {
                tPaint.setTextAlign(Paint.Align.CENTER);
                tPaint.setTypeface(tfNorm);
                tPaint.setTextSize(15f);
                tPaint.setColor(SUB);
                c.drawText(note, rightX + rightW * 0.5f, ly + 8f, tPaint);
                tPaint.setTextAlign(Paint.Align.LEFT);
            }
        }

        private void zeichneRing(Canvas c, float cx, float cy, float dia, float sw, List<float[]> segs, int trackCol) {
            float r = (dia - sw) * 0.5f;
            ringP.setStrokeWidth(sw);
            ringP.setColor(trackCol);
            c.drawCircle(cx, cy, r, ringP);
            if (segs.isEmpty()) return;

            double circum = 2.0 * Math.PI * r;
            double gapPx = segs.size() > 1 ? sw * 1.25 : 0.0;
            double offsetPx = 0.0;
            rf.set(cx - r, cy - r, cx + r, cy + r);
            for (float[] seg : segs) {
                float frac = seg[0];
                int col = (int) seg[1];
                if (frac <= 0f) continue;
                double arcPx = Math.max(0.0, frac * circum - gapPx);
                float startDeg = (float) (-90.0 + (offsetPx + gapPx * 0.5) / circum * 360.0);
                float sweepDeg = (float) (arcPx / circum * 360.0);
                if (sweepDeg > 0.1f) {
                    ringP.setColor(col);
                    c.drawArc(rf, startDeg, sweepDeg, false, ringP);
                }
                offsetPx += frac * circum;
            }
        }

        private int maxPage(String mode) {
            if (runs.isEmpty()) return 0;
            Calendar t0 = mitternacht(System.currentTimeMillis());
            Calendar old = mitternacht(runs.get(0).s);
            int days = (int) Math.max(0, Math.round((t0.getTimeInMillis() - old.getTimeInMillis()) / 86_400_000.0));
            if ("h".equals(mode)) return days;
            if ("w".equals(mode)) return Math.max(0, (t0.get(Calendar.YEAR) - old.get(Calendar.YEAR)) * 12 + t0.get(Calendar.MONTH) - old.get(Calendar.MONTH));
            return days / 7;
        }

        private List<Lauf> tage(int n, int off) {
            Calendar t0 = mitternacht(System.currentTimeMillis());
            t0.add(Calendar.DAY_OF_MONTH, -(n - 1 + off));
            long a = t0.getTimeInMillis();
            t0.add(Calendar.DAY_OF_MONTH, n);
            long b = t0.getTimeInMillis();
            List<Lauf> out = new ArrayList<>();
            for (Lauf r : runs) if (r.s >= a && r.s < b) out.add(r);
            return out;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            try {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getX(); downY = e.getY();
                } else if (a == MotionEvent.ACTION_UP && Math.hypot(e.getX() - downX, e.getY() - downY) < 28f * skala) {
                    float vx = (e.getX() - dx) / skala, vy = (e.getY() - dy) / skala;
                    for (int i = hits.size() - 1; i >= 0; i--) {
                        Hit h = hits.get(i);
                        if (h.trifft(vx, vy)) {
                            performClick();
                            ausfuehren(h);
                            return true;
                        }
                    }
                }
            } catch (RuntimeException ignored) {}
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }

        private void ausfuehren(Hit h) {
            switch (h.id) {
                case "zurueck":
                    if (aktionen != null) aktionen.zurueck();
                    break;
                case "tab":
                    setTab(h.arg);
                    break;
                case "ausgeraeumt":
                    ausraeumenDruecken();
                    break;
                case "mode":
                    if (!verlaufModus.equals(h.arg)) {
                        verlaufModus = h.arg;
                        verlaufSeite = 0;
                        invalidate();
                    }
                    break;
                case "page":
                    verlaufSeite = Math.max(0, Math.min(maxPage(verlaufModus), verlaufSeite + Integer.parseInt(h.arg)));
                    invalidate();
                    break;
            }
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
            ringP.setColor(color); ringP.setStrokeWidth(2.2f);
            iconTmp.reset();
            switch (name) {
                case "mdi:chevron-left":
                    iconTmp.moveTo(15f, 6f); iconTmp.lineTo(9f, 12f); iconTmp.lineTo(15f, 18f);
                    c.drawPath(iconTmp, ringP);
                    break;
                case "mdi:chevron-right":
                    iconTmp.moveTo(9f, 6f); iconTmp.lineTo(15f, 12f); iconTmp.lineTo(9f, 18f);
                    c.drawPath(iconTmp, ringP);
                    break;
                case "mdi:check":
                case "mdi:basket-check-outline":
                    iconTmp.moveTo(5f, 12.5f); iconTmp.lineTo(10f, 17.5f); iconTmp.lineTo(19.5f, 7f);
                    c.drawPath(iconTmp, ringP);
                    break;
                default:
                    c.drawCircle(12f, 12f, 7f, ringP);
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
    }

    private static int cat(int min) { return min < 75 ? 0 : (min < 120 ? 1 : 2); }

    private static Calendar mitternacht(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static int weekdayIdx(long ms) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms);
        return c.get(Calendar.DAY_OF_WEEK) - 1;
    }

    private static String weekdayKurz(long ms) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms);
        return WDL[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    private static String fmtDatum(long ms) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms);
        return c.get(Calendar.DAY_OF_MONTH) + ". " + MN[c.get(Calendar.MONTH)];
    }

    private static String fmtHm(double min) {
        int m = Math.max(0, (int) Math.round(min));
        return (m / 60) + ":" + String.format(Locale.ROOT, "%02d", m % 60);
    }

    private static String fmtUhr(long ms) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private static String fmtKwh(double k) {
        return String.format(Locale.GERMAN, "%.2f", Math.round(k * 100.0) / 100.0);
    }

    private static String fmtAgo(long sMs, long nowMs) {
        long t0 = mitternacht(nowMs).getTimeInMillis();
        long d = mitternacht(sMs).getTimeInMillis();
        int n = (int) Math.round((t0 - d) / 86_400_000.0);
        if (n <= 0) return "Heute";
        if (n == 1) return "Gestern";
        return "Vor " + n + " Tagen";
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

    private static Double zahl(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble(((String) o).trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static double numDbl(Object o, double fb) {
        Double v = zahl(o);
        return v != null ? v : fb;
    }

    private static long numLng(Object o, long fb) {
        if (o instanceof Number) return ((Number) o).longValue();
        return fb;
    }

    private static String str(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o).trim();
        return "null".equals(s) ? "" : s;
    }
}
