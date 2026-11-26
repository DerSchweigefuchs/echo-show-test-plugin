package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Native full-screen "Timer & Wecker" view matching timer_ansicht.py on the 963 x 584 dashboard grid (#0D1720):
 * - Top-left back circle button + "Timer & Wecker" title and live counts ("2 Timer · 1 Wecker")
 * - Left 360 px hero countdown ring (orange active, pulsing amber when ringing, grey paused, purple Echo native)
 *   with white progress knob, tabular remaining time, finish clock, and speaker badge
 * - Right "WEITERE TIMER" mini-rings + "NÄCHSTE WECKER" upcoming alarms within 24h
 * - Bottom-right interactive pill actions (Pause / Weiter, Löschen, Stopp, Wecker aus) wired to HA services.
 */
final class TimerView extends View implements Choreographer.FrameCallback {
    static final float W = 963, H = 584;
    private static final int BG = 0xFF0D1720, TXT = 0xFFECEBE8, SUB = 0xFF8FA3B5, GREY = 0xFF5C6B78;
    private static final int OR = 0xFFF2875A, AMB = 0xFFF0B45A, PUR = 0xFFA99CF0, BLUE = 0xFF6FB3F2;

    interface Aktionen {
        void zurueck();
        boolean pausiert();
        void haDienst(String domain, String service, Map<String, Object> target, Map<String, Object> data);
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

    private TimerDaten.Zustand zustand = new TimerDaten.Zustand();
    private TimerDaten.TimerEintrag fallbackDemo;   // optional local countdown when opened with explicit seconds and no HA timer
    private TimerDaten.TimerEintrag letzterHaupt;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface tfNorm = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final RectF rf = new RectF();
    private final Path iconTmp = new Path();

    private float skala = 1f, dx, dy, downX, downY;
    private boolean laeuft, angehaengt, sichtbar = true;
    private long startNs, frameNs;
    private int tick;

    TimerView(Context ctx, KsTheme theme, Aktionen aktionen, int fallbackSekunden) {
        super(ctx);
        this.theme = theme;
        this.aktionen = aktionen;
        setBackgroundColor(BG);
        setClickable(true);
        ringP.setStyle(Paint.Style.STROKE);
        ringP.setStrokeCap(Paint.Cap.ROUND);
        if (fallbackSekunden > 0) {
            TimerDaten.TimerEintrag fb = new TimerDaten.TimerEintrag();
            fb.nr = -99;
            fb.label = "Timer";
            fb.status = "active";
            fb.dauerMs = fallbackSekunden * 1000L;
            fb.endeMs = System.currentTimeMillis() + fb.dauerMs;
            fallbackDemo = fb;
        }
    }

    void setZustand(TimerDaten.Zustand z) {
        zustand = z != null ? z : new TimerDaten.Zustand();
        schleife();
        invalidate();
    }

    void pauseGeaendert() { schleife(); }

    void zeichneFuerTest(Canvas c, int gw, int gh, long nowMs, float animS) {
        zeichnen(c, gw, gh, nowMs, animS);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); angehaengt = true; schleife(); }
    @Override protected void onDetachedFromWindow() { angehaengt = false; schleife(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); sichtbar = v == VISIBLE; schleife(); }
    @Override public void onVisibilityAggregated(boolean vis) { super.onVisibilityAggregated(vis); schleife(); }

    void schleife() {
        boolean soll = angehaengt && sichtbar && isShown() && !aktionen.pausiert();
        if (soll == laeuft) return;
        laeuft = soll;
        Choreographer ch = Choreographer.getInstance();
        if (soll) {
            tick = 0;
            ch.postFrameCallback(this);
        } else {
            ch.removeFrameCallback(this);
        }
    }

    private boolean hatAnimation() {
        List<TimerDaten.TimerEintrag> l = effektiveTimer();
        for (TimerDaten.TimerEintrag e : l) {
            if ("active".equals(e.status) || "native".equals(e.status) || "ring".equals(e.status)) return true;
        }
        return false;
    }

    @Override public void doFrame(long ns) {
        if (!laeuft) return;
        if (startNs == 0) startNs = ns;
        int tl = hatAnimation() ? 2 : 30;   // 30 fps while counting/ringing, 2 fps when idle
        if (tick == 0 || (tick % tl) == 0) {
            frameNs = ns;
            invalidate();
        }
        tick++;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private List<TimerDaten.TimerEintrag> effektiveTimer() {
        if (zustand != null && !zustand.timer.isEmpty()) return zustand.timer;
        if (fallbackDemo != null) {
            List<TimerDaten.TimerEintrag> l = new ArrayList<>(1);
            l.add(fallbackDemo);
            return l;
        }
        return java.util.Collections.emptyList();
    }

    @Override protected void onDraw(Canvas c) {
        int gw = getWidth(), gh = getHeight();
        if (gw <= 0 || gh <= 0) return;
        float animS = frameNs > 0 && startNs > 0 ? (float) ((frameNs - startNs) * 1e-9) : SystemClock.uptimeMillis() / 1000f;
        zeichnen(c, gw, gh, System.currentTimeMillis(), animS);
    }

    private void zeichnen(Canvas c, int gw, int gh, long nowMs, float animS) {
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
            List<TimerDaten.TimerEintrag> liste = effektiveTimer();
            List<TimerDaten.WeckerEintrag> wecker = zustand != null ? zustand.wecker : java.util.Collections.emptyList();
            TimerDaten.TimerEintrag haupt = liste.isEmpty() ? null : liste.get(0);
            letzterHaupt = haupt;

            // 1. Header: back button (58, 50) + title + live counts
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1EFFFFFF);
            c.drawCircle(58, 50, 28, p);
            drawIcon(c, "mdi:chevron-left", 45, 37, 26, TXT);
            hits.add(new Hit("zurueck", 22, 14, 94, 86));

            int nTimer = 0;
            for (TimerDaten.TimerEintrag e : liste) if (!"wecker".equals(e.art)) nTimer++;
            int nWecker = wecker.size();
            String subTxt;
            if (nTimer > 0 && nWecker > 0) subTxt = (nTimer == 1 ? "1 Timer" : nTimer + " Timer") + " · " + (nWecker == 1 ? "1 Wecker" : nWecker + " Wecker");
            else if (nTimer > 0) subTxt = nTimer == 1 ? "1 Timer" : nTimer + " Timer";
            else if (nWecker > 0) subTxt = nWecker == 1 ? "1 Wecker" : nWecker + " Wecker";
            else subTxt = "Nichts aktiv";

            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(tfMed); t.setTextSize(22); t.setColor(TXT);
            c.drawText("Timer & Wecker", 102, 46, t);
            t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(SUB);
            c.drawText(subTxt, 102, 66, t);

            // 2. Left hero countdown ring (center 232, 324, diameter 360)
            zeichneHauptRing(c, haupt, 232, 326, 360, nowMs, animS);

            // 3. Right list: Weitere Timer + Nächste Wecker
            zeichneRechteListe(c, liste, wecker, 462, 114, W - 34 - 462, nowMs);

            // 4. Bottom-right interactive pills
            zeichneKnoepfe(c, haupt, 462, 492);
        } catch (RuntimeException ignored) {}
        c.restore();
    }

    private void zeichneHauptRing(Canvas c, TimerDaten.TimerEintrag haupt, float cx, float cy, float dia, long nowMs, float animS) {
        float sw = 15f * (dia / 200f) * 0.56f;   // crisp 15 px visual stroke
        float r = dia * .5f - sw - 8f;
        int col = haupt == null ? GREY : ("ring".equals(haupt.status) ? AMB : ("paused".equals(haupt.status) ? GREY : ("native".equals(haupt.status) ? PUR : OR)));
        float frac;
        if (haupt == null) frac = 0f;
        else if ("ring".equals(haupt.status)) frac = 1f;
        else if (haupt.dauerMs > 0) frac = Math.max(0f, Math.min(1f, haupt.restJetzt(nowMs) / (float) haupt.dauerMs));
        else frac = 1f;

        // Pulsing amber ring halo when ringing
        if (haupt != null && "ring".equals(haupt.status)) {
            float puls = .14f + .32f * (float) (.5 + .5 * Math.sin(animS * 4.8));
            ringP.setColor(WetterLage.alpha(AMB, puls));
            ringP.setStrokeWidth(sw + 16f);
            c.drawCircle(cx, cy, r, ringP);
        }

        // Track
        ringP.setColor(0x14FFFFFF);
        ringP.setStrokeWidth(sw);
        c.drawCircle(cx, cy, r, ringP);

        // Progress arc + white knob dot
        if (frac > 0.002f) {
            ringP.setColor(col);
            ringP.setStrokeWidth(sw);
            rf.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(rf, -90f, 360f * frac, false, ringP);
            if (!"ring".equals(haupt.status)) {
                double ang = frac * 2.0 * Math.PI;
                float kx = cx + r * (float) Math.sin(ang), ky = cy - r * (float) Math.cos(ang);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(kx, ky, sw * .58f, p);
            }
        }

        // Center content inside ring
        t.setTextAlign(Paint.Align.CENTER);
        float maxW = dia - 110;
        String top = "", big = "", sub = "", dev = "";
        if (haupt == null) {
            top = "Kein Timer";
            sub = "„Timer auf 10 Minuten“";
            t.setTypeface(tfNorm); t.setTextSize(19); t.setColor(SUB);
            c.drawText(top, cx, cy - 56, t);
            drawIcon(c, "mdi:timer-outline", cx - 32, cy - 34, 64, GREY);
            t.setTypeface(tfNorm); t.setTextSize(15.5f); t.setColor(SUB);
            c.drawText(sub, cx, cy + 56, t);
        } else if ("wecker".equals(haupt.art)) {
            top = "Wecker";
            sub = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12 ? "Guten Morgen!" : "Wecker klingelt";
            dev = haupt.geraet;
            t.setTypeface(tfNorm); t.setTextSize(19); t.setColor(SUB);
            c.drawText(top, cx, cy - 64, t);
            drawIcon(c, "mdi:alarm-bell", cx - 42, cy - 48, 84, AMB);
            t.setTypeface(tfNorm); t.setTextSize(17); t.setColor(SUB);
            c.drawText(sub, cx, cy + 62, t);
        } else {
            top = haupt.label;
            if ("ring".equals(haupt.status)) {
                big = "Fertig!";
                sub = haupt.geraet.isEmpty() ? "klingelt" : "klingelt · " + haupt.geraet;
            } else if ("paused".equals(haupt.status)) {
                big = TimerDaten.fmt(haupt.restMs);
                sub = "pausiert";
                dev = haupt.geraet;
            } else {
                long rem = haupt.restJetzt(nowMs);
                big = rem <= 0 ? "0:00" : TimerDaten.fmt(rem);
                sub = "fertig um " + TimerDaten.uhr(haupt.endeMs);
                dev = haupt.geraet;
            }
            t.setTypeface(tfNorm); t.setTextSize(19); t.setColor(SUB);
            c.drawText(kuerzen(t, top, maxW), cx, cy - 54, t);

            t.setTypeface("ring".equals(haupt.status) ? tfNorm : tfLight);
            t.setTextSize("ring".equals(haupt.status) ? 56 : (big.length() > 5 ? 62 : 76));
            t.setColor("ring".equals(haupt.status) ? AMB : ("paused".equals(haupt.status) ? SUB : TXT));
            c.drawText(big, cx, cy + 22, t);

            t.setTypeface(tfNorm); t.setTextSize(16); t.setColor(SUB);
            c.drawText(kuerzen(t, sub, maxW), cx, cy + 56, t);
        }

        if (!dev.isEmpty()) {
            t.setTypeface(tfNorm); t.setTextSize(13.5f); t.setColor(GREY);
            String dTxt = kuerzen(t, dev, maxW - 22);
            float tw = t.measureText(dTxt);
            drawIcon(c, "mdi:speaker", cx - tw * .5f - 10, cy + 68, 15, GREY);
            t.setTextAlign(Paint.Align.LEFT);
            c.drawText(dTxt, cx - tw * .5f + 9, cy + 80, t);
        }
        t.setTextAlign(Paint.Align.LEFT);
    }

    private void zeichneRechteListe(Canvas c, List<TimerDaten.TimerEintrag> liste, List<TimerDaten.WeckerEintrag> wecker,
                                    float x, float y0, float w, long nowMs) {
        float y = y0;
        int maxAlarms = Math.min(2, wecker.size());
        int maxRest = Math.min(maxAlarms > 0 ? 2 : 3, Math.max(0, liste.size() - 1));

        if (maxRest > 0) {
            t.setTypeface(tfMed); t.setTextSize(12.5f); t.setColor(SUB);
            c.drawText("WEITERE TIMER", x, y + 12, t);
            y += 24;
            for (int k = 0; k < maxRest; k++) {
                TimerDaten.TimerEintrag e = liste.get(k + 1);
                if (k > 0) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x10FFFFFF);
                    c.drawRect(x, y, x + w, y + 1, p);
                }
                y += 10;
                zeichneMiniRing(c, e, x + 27, y + 27, 24, nowMs);

                String timeTxt = "ring".equals(e.status) ? "Fertig" : TimerDaten.fmt(e.restJetzt(nowMs));
                t.setTypeface(tfLight); t.setTextSize(26);
                t.setColor("ring".equals(e.status) ? AMB : ("paused".equals(e.status) ? SUB : TXT));
                t.setTextAlign(Paint.Align.RIGHT);
                c.drawText(timeTxt, x + w, y + 36, t);
                float timeW = t.measureText(timeTxt);
                t.setTextAlign(Paint.Align.LEFT);

                float txtMaxW = Math.max(80, w - 70 - timeW - 14);
                t.setTypeface(tfNorm); t.setTextSize(17); t.setColor(TXT);
                c.drawText(kuerzen(t, e.label, txtMaxW), x + 70, y + 23, t);
                String sub = "paused".equals(e.status) ? "pausiert" : ("ring".equals(e.status) ? "klingelt" : e.geraet);
                if (!sub.isEmpty()) {
                    t.setTextSize(14); t.setColor(SUB);
                    c.drawText(kuerzen(t, sub, txtMaxW), x + 70, y + 43, t);
                }
                y += 64;
            }
        }

        if (maxAlarms > 0) {
            if (maxRest > 0) y += 16;
            t.setTypeface(tfMed); t.setTextSize(12.5f); t.setColor(SUB);
            c.drawText("NÄCHSTE WECKER", x, y + 12, t);
            y += 24;
            for (int k = 0; k < maxAlarms; k++) {
                TimerDaten.WeckerEintrag al = wecker.get(k);
                if (k > 0) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x10FFFFFF);
                    c.drawRect(x, y, x + w, y + 1, p);
                }
                y += 10;
                ringP.setColor(0x736FB3F2);
                ringP.setStrokeWidth(3f);
                c.drawCircle(x + 27, y + 27, 25, ringP);
                drawIcon(c, "mdi:alarm", x + 15, y + 15, 24, BLUE);

                String hm = String.format(Locale.ROOT, "%02d:%02d", al.stunde, al.minute);
                t.setTypeface(tfLight); t.setTextSize(26); t.setColor(BLUE);
                t.setTextAlign(Paint.Align.RIGHT);
                c.drawText(hm, x + w, y + 36, t);
                float hmW = t.measureText(hm);
                t.setTextAlign(Paint.Align.LEFT);

                float txtMaxW = Math.max(80, w - 70 - hmW - 14);
                t.setTypeface(tfNorm); t.setTextSize(17); t.setColor(TXT);
                c.drawText("Wecker " + TimerDaten.tagText(al.tsMs, nowMs), x + 70, y + 23, t);
                String sub = TimerDaten.tageLabel(al.tage) + (al.geraet.isEmpty() ? "" : " · " + al.geraet);
                t.setTextSize(14); t.setColor(SUB);
                c.drawText(kuerzen(t, sub, txtMaxW), x + 70, y + 43, t);
                y += 64;
            }
        }
    }

    private void zeichneMiniRing(Canvas c, TimerDaten.TimerEintrag e, float cx, float cy, float r, long nowMs) {
        int col = "ring".equals(e.status) ? AMB : ("paused".equals(e.status) ? GREY : ("native".equals(e.status) ? PUR : OR));
        float frac = "ring".equals(e.status) ? 1f : (e.dauerMs > 0 ? Math.max(0f, Math.min(1f, e.restJetzt(nowMs) / (float) e.dauerMs)) : 1f);
        ringP.setStrokeWidth(4.5f);
        ringP.setColor(0x16FFFFFF);
        c.drawCircle(cx, cy, r, ringP);
        if (frac > 0.01f) {
            ringP.setColor(col);
            rf.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(rf, -90f, 360f * frac, false, ringP);
        }
    }

    private void zeichneKnoepfe(Canvas c, TimerDaten.TimerEintrag haupt, float x0, float y) {
        float x = x0;
        if (haupt == null) return;
        if ("wecker".equals(haupt.art)) {
            x += pill(c, "stopw", "Wecker aus", "mdi:alarm-off", x, y, AMB, 0xFF2A1D08) + 12;
        } else if ("ring".equals(haupt.status)) {
            pill(c, haupt.nr > 0 ? "stop" : "stop0", "Stopp", "mdi:bell-off-outline", x, y, AMB, 0xFF2A1D08);
        } else if (haupt.nr > 0 || haupt.nr == -10 || haupt.nr == -99) {
            boolean paused = "paused".equals(haupt.status);
            x += pill(c, "pause", paused ? "Weiter" : "Pause", paused ? "mdi:play" : "mdi:pause", x, y, 0x16FFFFFF, TXT) + 12;
            pill(c, "del", "Löschen", "mdi:close", x, y, 0x16FFFFFF, TXT);
        }
    }

    private float pill(Canvas c, String id, String label, String icon, float x, float y, int bg, int fg) {
        t.setTypeface(tfMed); t.setTextSize(17);
        float w = 20 + 26 + 10 + t.measureText(label) + 24;
        float h = 56;
        rf.set(x, y, x + w, y + h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(bg);
        c.drawRoundRect(rf, 28, 28, p);
        drawIcon(c, icon, x + 18, y + 15, 26, fg);
        t.setColor(fg);
        c.drawText(label, x + 52, y + 34, t);
        hits.add(new Hit(id, x, y, x + w, y + h));
        return w;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        try {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                downX = e.getX(); downY = e.getY();
            } else if (a == MotionEvent.ACTION_UP && Math.hypot(e.getX() - downX, e.getY() - downY) < 28) {
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
            aktionen.zurueck();
            return;
        }
        TimerDaten.TimerEintrag h = letzterHaupt;
        if (h == null) return;
        if (h.nr == -99) {   // local fallback timer
            if ("pause".equals(id)) {
                if ("paused".equals(h.status)) {
                    h.status = "active";
                    h.endeMs = System.currentTimeMillis() + h.restMs;
                } else {
                    h.restMs = h.restJetzt(System.currentTimeMillis());
                    h.status = "paused";
                }
            } else if ("del".equals(id)) {
                fallbackDemo = null;
            }
            schleife();
            invalidate();
            return;
        }
        if ("pause".equals(id)) {
            Map<String, Object> tgt = new HashMap<>();
            tgt.put("entity_id", h.entityId.isEmpty() ? "timer.dot_timer_" + h.nr : h.entityId);
            aktionen.haDienst("timer", "paused".equals(h.status) ? "start" : "pause", tgt, null);
        } else if ("del".equals(id) || "stop".equals(id)) {
            if (h.nr > 0) {
                Map<String, Object> data = new HashMap<>();
                data.put("nr", h.nr);
                aktionen.haDienst("script", "echo_timer_entfernen", null, data);
            } else if (!h.entityId.isEmpty()) {
                Map<String, Object> tgt = new HashMap<>();
                tgt.put("entity_id", h.entityId);
                aktionen.haDienst("timer", "cancel", tgt, null);
            }
        } else if ("stop0".equals(id)) {
            Map<String, Object> tgt = new HashMap<>();
            tgt.put("entity_id", "input_boolean.timer_klingelt");
            aktionen.haDienst("input_boolean", "turn_off", tgt, null);
        } else if ("stopw".equals(id)) {
            aktionen.haDienst("script", "wecker_stoppen", null, null);
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
        p.setStyle(Paint.Style.FILL); p.setColor(color);
        ringP.setColor(color); ringP.setStrokeWidth(2.2f);
        iconTmp.reset();
        switch (name) {
            case "mdi:chevron-left":
                iconTmp.moveTo(15, 6); iconTmp.lineTo(9, 12); iconTmp.lineTo(15, 18);
                c.drawPath(iconTmp, ringP);
                break;
            case "mdi:close":
                c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, ringP);
                c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, ringP);
                break;
            case "mdi:play":
                iconTmp.moveTo(8, 5.5f); iconTmp.lineTo(19, 12); iconTmp.lineTo(8, 18.5f); iconTmp.close();
                c.drawPath(iconTmp, p);
                break;
            case "mdi:pause":
                c.drawRoundRect(6.5f, 5.5f, 10.3f, 18.5f, 1.2f, 1.2f, p);
                c.drawRoundRect(13.7f, 5.5f, 17.5f, 18.5f, 1.2f, 1.2f, p);
                break;
            case "mdi:alarm":
            case "mdi:alarm-bell":
            case "mdi:timer-outline":
                c.drawCircle(12, 13, 7.5f, ringP);
                c.drawLine(12, 13, 12, 8.5f, ringP);
                c.drawLine(12, 13, 15.5f, 14.5f, ringP);
                c.drawLine(9, 3, 15, 3, ringP);
                break;
            default:
                c.drawCircle(12, 12, 6, ringP);
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
