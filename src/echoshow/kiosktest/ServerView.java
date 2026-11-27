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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Native full-screen Server view matching server.py on the 963 x 584 dashboard grid (#0D1720):
 * - Generic title from config (fallback "Server"), zero hardcoded hostnames or entity IDs
 * - Top-right Übersicht | VMs & Container toggle + Power button with confirmation modal
 * - Übersicht: 3 top stat cards (Prozessor %, Arbeitsspeicher % + GiB, Strom W + kWh/Tag) with smooth 24h gradient sparklines,
 *   Speicher pool bars, and 8-column VMs & Container status dot grid
 * - VMs & Container: 2-column scrollable list with combined CPU | RAM (hatched for VMs) and Disk bars.
 */
final class ServerView extends FrameLayout {
    static final float W = 963, H = 584;
    private static final int BG = 0xFF0D1720, TXT = 0xFFECEBE8, SUB = 0xFF8FA3B5;
    private static final int CPU_COL = 0xFF6FB3F2, RAM_COL = 0xFFA99CF0, DISK_COL = 0xFFF0B45A;
    private static final int GREEN = 0xFF6CC483, OFF_COL = 0xFF4A5866, AMBER = 0xFFE8A35A;

    interface Aktionen {
        void zurueck();
        void ha(Map<String, Object> msg, Antwort cb);
        interface Antwort { void fertig(boolean ok, Object data, String err); }
    }

    private static final class SparkPt {
        final long t;
        final double mean, max;
        SparkPt(long t, double mean, double max) { this.t = t; this.mean = mean; this.max = max; }
    }

    private static final class Storage {
        String name = "";
        Double pct, usedGb, totalGb;
    }

    private static final class Guest {
        String name = "", typ = "LXC", state = "";
        boolean on;
        Double cpu, cores, ramPct, ramMaxGb, diskUsedGb, diskMaxGb;
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

    private String tab = "uebersicht";
    private boolean geladen;
    private boolean aktiv = true;
    private String fehler;
    private String serverName = "Server";
    private String steckdose = "";
    private Boolean steckdoseAn;
    private boolean online;
    private long uptimeMs;

    private Double cpu, cpuThreads, cpuPeak;
    private Double ram, ramMaxGb;
    private Double power, powerPeak, powerAvg, kwhHeute;
    private final List<SparkPt> cpuSpark = new ArrayList<>();
    private final List<SparkPt> ramSpark = new ArrayList<>();
    private final List<SparkPt> powerSpark = new ArrayList<>();
    private final List<Storage> storages = new ArrayList<>();
    private final List<Guest> guests = new ArrayList<>();

    private boolean confirmPower;
    private boolean powerBusy;
    private float diensteScrollY, diensteMaxScroll;
    private final Runnable refreshRun = this::datenNachladen;

    ServerView(Context ctx, KsTheme theme, String initTab, Aktionen aktionen) {
        super(ctx);
        this.theme = theme;
        this.aktionen = aktionen;
        if ("dienste".equals(initTab)) this.tab = "dienste";
        setBackgroundColor(BG);
        setClickable(true);
        canvasView = new CanvasView(ctx);
        addView(canvasView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void setTab(String tab) {
        String neu = "dienste".equals(tab) ? "dienste" : "uebersicht";
        if (!neu.equals(this.tab)) {
            this.tab = neu;
            diensteScrollY = 0;
            canvasView.invalidate();
        }
    }

    void setDaten(Object haDaten, String err) {
        fehler = err;
        if (haDaten instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) haDaten;
            geladen = true;
            aktiv = !Boolean.FALSE.equals(m.get("aktiv"));
            String nm = str(m.get("name"));
            serverName = nm.isEmpty() ? "Server" : nm;
            steckdose = str(m.get("steckdose"));
            steckdoseAn = m.get("steckdose_an") instanceof Boolean ? (Boolean) m.get("steckdose_an") : null;
            online = Boolean.TRUE.equals(m.get("online"));
            uptimeMs = Anwesenheit.zeit(m.get("uptime_iso"));

            cpu = dbl(m.get("cpu"));
            cpuThreads = dbl(m.get("cpu_threads"));
            cpuPeak = dbl(m.get("cpu_peak"));
            ram = dbl(m.get("ram"));
            ramMaxGb = dbl(m.get("ram_max_gb"));
            power = dbl(m.get("power"));
            powerPeak = dbl(m.get("power_peak"));
            powerAvg = dbl(m.get("power_avg"));
            kwhHeute = dbl(m.get("kwh_heute"));

            readSpark(m.get("cpu_spark"), cpuSpark);
            readSpark(m.get("ram_spark"), ramSpark);
            readSpark(m.get("power_spark"), powerSpark);

            storages.clear();
            if (m.get("storages") instanceof List) {
                for (Object o : (List<?>) m.get("storages")) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> sm = (Map<?, ?>) o;
                    Storage s = new Storage();
                    s.name = str(sm.get("name"));
                    s.pct = dbl(sm.get("pct"));
                    s.usedGb = dbl(sm.get("used_gb"));
                    s.totalGb = dbl(sm.get("total_gb"));
                    storages.add(s);
                }
            }

            guests.clear();
            if (m.get("guests") instanceof List) {
                for (Object o : (List<?>) m.get("guests")) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> gm = (Map<?, ?>) o;
                    Guest g = new Guest();
                    g.name = str(gm.get("name"));
                    String typ = str(gm.get("typ")).toUpperCase(Locale.ROOT);
                    g.typ = "VM".equals(typ) ? "VM" : "LXC";
                    g.state = str(gm.get("state"));
                    g.on = Boolean.TRUE.equals(gm.get("on")) || "running".equalsIgnoreCase(g.state);
                    g.cpu = dbl(gm.get("cpu"));
                    g.cores = dbl(gm.get("cores"));
                    g.ramPct = dbl(gm.get("ram_pct"));
                    g.ramMaxGb = dbl(gm.get("ram_max_gb"));
                    g.diskUsedGb = dbl(gm.get("disk_used_gb"));
                    g.diskMaxGb = dbl(gm.get("disk_max_gb"));
                    guests.add(g);
                }
            }
        }
        canvasView.invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(refreshRun);
        super.onDetachedFromWindow();
    }

    private void powerUmschalten() {
        if (aktionen == null || steckdose.isEmpty() || powerBusy) return;
        powerBusy = true;
        confirmPower = false;
        canvasView.invalidate();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "call_service");
        msg.put("domain", "switch");
        msg.put("service", "toggle");
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("entity_id", steckdose);
        msg.put("target", target);
        aktionen.ha(msg, (ok, data, err) -> post(() -> TestAnsicht.sicher(() -> {
            powerBusy = false;
            if (!ok && err != null) fehler = err;
            removeCallbacks(refreshRun);
            postDelayed(refreshRun, 800L);
            canvasView.invalidate();
        })));
    }

    private void datenNachladen() {
        if (aktionen == null) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/server/daten");
        aktionen.ha(msg, (ok, data, err) -> post(() -> TestAnsicht.sicher(() -> {
            if (ok && data != null) setDaten(data, null);
            else if (err != null) setDaten(null, err);
        })));
    }

    private static void readSpark(Object raw, List<SparkPt> out) {
        out.clear();
        if (!(raw instanceof List)) return;
        for (Object o : (List<?>) raw) {
            if (!(o instanceof List)) continue;
            List<?> r = (List<?>) o;
            if (r.size() < 2) continue;
            Double tsD = dbl(r.get(0));
            Double meanD = dbl(r.get(1));
            Double maxD = r.size() > 2 ? dbl(r.get(2)) : meanD;
            if (tsD == null || meanD == null) continue;
            long ts = tsD > 1e12 ? Math.round(tsD) : Math.round(tsD * 1000.0);
            out.add(new SparkPt(ts, meanD, maxD != null ? maxD : meanD));
        }
    }

    private static Double dbl(Object o) {
        if (o instanceof Number) {
            double v = ((Number) o).doubleValue();
            return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
        }
        if (o instanceof String) {
            try {
                double v = Double.parseDouble(((String) o).trim());
                return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String fmt(Double v, int dec) {
        if (v == null) return "–";
        double pow = Math.pow(10, dec);
        double r = Math.round(v * pow) / pow;
        return String.format(Locale.GERMAN, "%." + dec + "f", r);
    }

    private static String fmtGb(Double v) {
        if (v == null) return "–";
        if (v >= 1000.0) return fmt(v / 1024.0, 1) + " TB";
        if (v < 1.0) {
            String s = fmt(v, 2);
            if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
            return s + " GiB";
        }
        String s = fmt(v, v < 10.0 ? 1 : 0);
        if (s.endsWith(",0")) s = s.substring(0, s.length() - 2);
        return s + " GiB";
    }

    private final class CanvasView extends View {
        private final List<Hit> hits = new ArrayList<>();
        private final Map<String, Path> iconCache = new HashMap<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint schraffur = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glanz = new Paint();
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        private final Typeface tfNorm = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final RectF rf = new RectF();
        private final Path sparkLine = new Path();
        private final Path sparkFill = new Path();
        private final Path iconTmp = new Path();
        private float skala = 1f, dx, dy, downX, downY, lastY;
        private boolean ziehend;

        CanvasView(Context ctx) {
            super(ctx);
            setClickable(true);
            linie.setStyle(Paint.Style.STROKE);
            linie.setStrokeCap(Paint.Cap.ROUND);
            linie.setStrokeJoin(Paint.Join.ROUND);

            float cx = W * .18f, cy = H * .30f, rad = (float) Math.hypot(W - cx, H - cy) * .5f;
            glanz.setShader(new RadialGradient(cx, cy, rad, 0x1E6FB3F2, 0x006FB3F2, Shader.TileMode.CLAMP));

            float d = 8f / (float) Math.sqrt(2);
            schraffur.setShader(new LinearGradient(0, 0, d, d,
                new int[] {0x80A99CF0, 0x80A99CF0, 0x24A99CF0, 0x24A99CF0},
                new float[] {0, .5f, .5f, 1}, Shader.TileMode.REPEAT));
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
                c.drawRect(0, 0, W, H, glanz);
                long now = System.currentTimeMillis();
                zeichneKopf(c, now);

                if (geladen && !aktiv) {
                    t.setTextAlign(Paint.Align.CENTER);
                    t.setTypeface(tfNorm); t.setTextSize(18); t.setColor(SUB);
                    c.drawText("Server-Ansicht ist nicht eingerichtet.", W * .5f, H * .52f, t);
                    t.setTextAlign(Paint.Align.LEFT);
                } else if ("dienste".equals(tab)) {
                    zeichneDienste(c);
                } else {
                    zeichneUebersicht(c, now);
                }

                if (confirmPower) zeichneBestaetigung(c);
            } catch (RuntimeException ignored) {}
            c.restore();
        }

        private void zeichneKopf(Canvas c, long now) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1EFFFFFF);
            c.drawCircle(58, 50, 28, p);
            drawIcon(c, "mdi:chevron-left", 45, 37, 26, TXT);
            hits.add(new Hit("zurueck", 22, 14, 94, 86));

            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(tfMed); t.setTextSize(22); t.setColor(TXT);
            c.drawText(kuerzen(t, serverName, 360), 102, 43, t);

            // Status dot + line
            int dotCol = GREEN;
            String statusTxt;
            if (!geladen) {
                statusTxt = fehler != null ? "Fehler: " + fehler : "Daten werden geladen …";
                dotCol = SUB;
            } else if (!steckdose.isEmpty() && steckdoseAn == null && !online) {
                statusTxt = "Steckdose nicht erreichbar";
                dotCol = OFF_COL;
            } else if (Boolean.FALSE.equals(steckdoseAn)) {
                statusTxt = "Steckdose aus";
                dotCol = OFF_COL;
            } else if (!online) {
                statusTxt = "Proxmox nicht erreichbar";
                dotCol = AMBER;
            } else {
                statusTxt = "Proxmox online";
                if (uptimeMs > 0 && now >= uptimeMs) {
                    long min = Math.max(0, Math.round((now - uptimeMs) / 60_000.0));
                    String dStr = min < 60 ? min + " min"
                        : (min < 2880 ? (min / 60) + ":" + String.format(Locale.ROOT, "%02d", min % 60) + " h"
                        : (min / 1440) + " Tagen");
                    statusTxt += " · seit " + dStr;
                }
            }
            p.setColor(dotCol);
            c.drawCircle(107, 60, 5, p);
            t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(SUB);
            c.drawText(kuerzen(t, statusTxt, 380), 119, 65, t);

            // Top-right Power button + segmented toggle
            float pwRight = W - 30;
            if (!steckdose.isEmpty()) {
                float pCx = pwRight - 26, pCy = 48;
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x14FFFFFF);
                c.drawCircle(pCx, pCy, 26, p);
                int icCol = Boolean.FALSE.equals(steckdoseAn) ? GREEN : 0xFFE88A8A;
                drawIcon(c, "mdi:power", pCx - 13, pCy - 13, 26, icCol);
                hits.add(new Hit("power", pCx - 28, pCy - 28, pCx + 28, pCy + 28));
                pwRight -= 66;
            }

            // Segmented toggle: Übersicht | VMs & Container
            t.setTextSize(16);
            t.setTypeface("uebersicht".equals(tab) ? tfMed : tfNorm);
            float w1 = t.measureText("Übersicht") + 38;
            t.setTypeface("dienste".equals(tab) ? tfMed : tfNorm);
            float w2 = t.measureText("VMs & Container") + 38;
            float totW = w1 + w2 + 10;
            float tx0 = pwRight - totW, ty0 = 22, th = 52;
            rf.set(tx0, ty0, tx0 + totW, ty0 + th);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x12FFFFFF);
            c.drawRoundRect(rf, 26, 26, p);

            drawTabPill(c, "uebersicht", "Übersicht", tx0 + 4, ty0 + 4, w1, th - 8);
            drawTabPill(c, "dienste", "VMs & Container", tx0 + 6 + w1, ty0 + 4, w2, th - 8);
        }

        private void drawTabPill(Canvas c, String key, String lbl, float x, float y, float w, float h) {
            boolean on = key.equals(tab);
            rf.set(x, y, x + w, y + h);
            if (on) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(TXT);
                c.drawRoundRect(rf, h * .5f, h * .5f, p);
            }
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(on ? tfMed : tfNorm);
            t.setTextSize(16);
            t.setColor(on ? BG : SUB);
            c.drawText(lbl, rf.centerX(), y + h * .5f + 6, t);
            t.setTextAlign(Paint.Align.LEFT);
            hits.add(new Hit("tab:" + key, x, y - 4, x + w, y + h + 4));
        }

        private void zeichneUebersicht(Canvas c, long now) {
            float x0 = 30, y0 = 96, totalW = W - 60, gap = 26;
            float colW = (totalW - 2 * gap) / 3f;

            // 1. Top 3 stat cards
            double cpuPkVal = peak(cpuSpark, cpu);
            if (cpuPeak != null && cpuPeak > cpuPkVal) cpuPkVal = cpuPeak;
            String cpuFoot = (cpuThreads != null ? fmt(cpuThreads, 0) + " Threads · " : "") + "Spitze " + fmt(cpuPkVal, 0) + " %";
            drawStatCard(c, x0, y0, colW, "Prozessor", fmt(cpu, 0), "%",
                cpuSpark, cpu, Math.max(30.0, cpuPkVal * 1.15), CPU_COL, cpuFoot, now);

            String ramFoot = (ram != null && ramMaxGb != null)
                ? fmt(ram / 100.0 * ramMaxGb, 1) + " von " + fmt(ramMaxGb, 1) + " GiB" : "";
            drawStatCard(c, x0 + colW + gap, y0, colW, "Arbeitsspeicher", fmt(ram, 0), "%",
                ramSpark, ram, 100.0, RAM_COL, ramFoot, now);

            double pwPkVal = peak(powerSpark, power);
            if (powerPeak != null && powerPeak > pwPkVal) pwPkVal = powerPeak;
            Double pwAvgVal = powerAvg != null ? powerAvg : mean(powerSpark);
            String pwFoot = (pwAvgVal != null ? "Ø " + fmt(pwAvgVal, 0) + " W · " : "")
                + fmt(kwhHeute != null ? kwhHeute : 0.0, 2) + " kWh heute";
            drawStatCard(c, x0 + 2 * (colW + gap), y0, colW, "Strom", fmt(power, 0), "W",
                powerSpark, power, Math.max(100.0, pwPkVal * 1.15), GREEN, pwFoot, now);

            // 2. Bottom row: Speicher pools (left) + VMs & Container dots (right)
            float by = 326, leftW = (totalW - 46) * .5f, rightX = x0 + leftW + 46;

            // Left: Speicher
            t.setTypeface(tfMed); t.setTextSize(13); t.setColor(SUB);
            c.drawText("SPEICHER", x0, by + 12, t);
            float sy = by + 28;
            int maxStorages = Math.min(4, storages.size());
            for (int i = 0; i < maxStorages; i++) {
                Storage s = storages.get(i);
                t.setTypeface(tfNorm); t.setTextSize(15); t.setColor(TXT);
                c.drawText(kuerzen(t, s.name, leftW * .48f), x0, sy + 14, t);
                String meta = fmtGb(s.usedGb) + " / " + fmtGb(s.totalGb) + " · " + fmt(s.pct, 0) + " %";
                t.setTextAlign(Paint.Align.RIGHT); t.setColor(SUB);
                c.drawText(meta, x0 + leftW, sy + 14, t);
                t.setTextAlign(Paint.Align.LEFT);

                float barY = sy + 23;
                rf.set(x0, barY, x0 + leftW, barY + 8);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x14FFFFFF);
                c.drawRoundRect(rf, 4, 4, p);
                float pct = s.pct == null ? 0f : Math.max(1f, Math.min(100f, s.pct.floatValue()));
                rf.set(x0, barY, x0 + leftW * (pct / 100f), barY + 8);
                p.setColor(DISK_COL);
                c.drawRoundRect(rf, 4, 4, p);
                sy += 48;
            }
            if (storages.isEmpty() && geladen) {
                t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(SUB);
                c.drawText("Keine Speicherpools gemeldet", x0, sy + 16, t);
            }

            // Right: VMs & Container summary + dot matrix
            int run = 0, vm = 0;
            List<String> offNames = new ArrayList<>();
            for (Guest g : guests) {
                if (g.on) run++; else offNames.add(g.name);
                if ("VM".equals(g.typ)) vm++;
            }
            t.setTypeface(tfLight); t.setTextSize(34); t.setColor(TXT);
            String runStr = String.valueOf(run);
            c.drawText(runStr, rightX, by + 26, t);
            float runW = t.measureText(runStr);
            t.setTypeface(tfNorm); t.setTextSize(16); t.setColor(SUB);
            String subCnt = "von " + guests.size() + " laufen · " + vm + (vm == 1 ? " VM, " : " VMs, ") + (guests.size() - vm) + " Container";
            c.drawText(kuerzen(t, subCnt, leftW - runW - 14), rightX + runW + 10, by + 25, t);

            float gx0 = rightX, gy0 = by + 44, cell = 24, dGap = 10;
            int cols = 8;
            for (int i = 0; i < Math.min(32, guests.size()); i++) {
                Guest g = guests.get(i);
                int r = i / cols, col = i % cols;
                float cx = gx0 + col * (cell + dGap), cy = gy0 + r * (cell + dGap);
                p.setStyle(Paint.Style.FILL);
                p.setColor(g.on ? GREEN : OFF_COL);
                if ("VM".equals(g.typ)) {
                    rf.set(cx, cy, cx + cell, cy + cell);
                    c.drawRoundRect(rf, 7, 7, p);
                } else {
                    c.drawCircle(cx + cell * .5f, cy + cell * .5f, cell * .5f, p);
                }
            }
            int rowsUsed = Math.max(1, (Math.min(32, guests.size()) + cols - 1) / cols);
            float footY = gy0 + rowsUsed * (cell + dGap) + 12;
            String offStr = offNames.isEmpty()
                ? (guests.isEmpty() ? "" : "alle laufen")
                : "aus: " + join(offNames, ", ");
            t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(SUB);
            c.drawText(kuerzen(t, offStr, leftW), rightX, footY, t);
            hits.add(new Hit("tab:dienste", rightX, by, rightX + leftW, H - 20));
        }

        private void drawStatCard(Canvas c, float x, float y, float w, String lbl, String val, String unit,
                                  List<SparkPt> pts, Double cur, double topVal, int col, String foot, long now) {
            t.setTypeface(tfNorm); t.setTextSize(15); t.setColor(SUB);
            c.drawText(lbl, x, y + 16, t);

            t.setTypeface(tfLight); t.setTextSize(48); t.setColor(TXT);
            c.drawText(val, x, y + 66, t);
            float vw = t.measureText(val);
            t.setTypeface(tfNorm); t.setTextSize(20); t.setColor(SUB);
            c.drawText(unit, x + vw + 5, y + 65, t);

            // Smooth 24h gradient sparkline
            float sx = x, sy = y + 78, sw = w, sh = 108;
            long t0 = now - 86_400_000L;
            List<float[]> xy = new ArrayList<>();
            for (SparkPt pt : pts) {
                if (pt.t < t0) continue;
                float px = sx + Math.max(0f, Math.min(1f, (pt.t - t0) / 86_400_000f)) * sw;
                float py = sy + sh - 3 - Math.max(0f, Math.min(1f, (float) (pt.mean / topVal))) * (sh - 6);
                xy.add(new float[] {px, py});
            }
            if (cur != null) {
                float py = sy + sh - 3 - Math.max(0f, Math.min(1f, (float) (cur / topVal))) * (sh - 6);
                xy.add(new float[] {sx + sw, py});
            }
            if (xy.size() >= 2) {
                sparkLine.reset();
                sparkFill.reset();
                float[] first = xy.get(0);
                sparkLine.moveTo(first[0], first[1]);
                sparkFill.moveTo(first[0], sy + sh);
                sparkFill.lineTo(first[0], first[1]);
                for (int i = 1; i < xy.size(); i++) {
                    float[] prev = xy.get(i - 1);
                    float[] curr = xy.get(i);
                    float mx = (prev[0] + curr[0]) * .5f;
                    float my = (prev[1] + curr[1]) * .5f;
                    sparkLine.quadTo(prev[0], prev[1], mx, my);
                    sparkFill.quadTo(prev[0], prev[1], mx, my);
                }
                float[] last = xy.get(xy.size() - 1);
                sparkLine.lineTo(last[0], last[1]);
                sparkFill.lineTo(last[0], last[1]);
                sparkFill.lineTo(last[0], sy + sh);
                sparkFill.close();

                p.setStyle(Paint.Style.FILL);
                p.setShader(new LinearGradient(0, sy, 0, sy + sh,
                    WetterLage.alpha(col, .28f), WetterLage.alpha(col, .0f), Shader.TileMode.CLAMP));
                c.drawPath(sparkFill, p);
                p.setShader(null);

                linie.setStrokeWidth(2.5f);
                linie.setColor(col);
                c.drawPath(sparkLine, linie);

                p.setColor(col);
                c.drawCircle(last[0], last[1], 4.2f, p);
            }

            t.setTypeface(tfNorm); t.setTextSize(13); t.setColor(SUB);
            c.drawText(kuerzen(t, foot, w), x, y + 206, t);
        }

        private void zeichneDienste(Canvas c) {
            float x0 = 30, y0 = 94, totalW = W - 60, colGap = 36, h = H - y0 - 16;
            float colW = (totalW - colGap) * .5f;

            if (guests.isEmpty()) {
                t.setTextAlign(Paint.Align.CENTER);
                t.setTypeface(tfNorm); t.setTextSize(16); t.setColor(SUB);
                c.drawText(geladen ? "Keine VMs oder Container gefunden." : "Daten werden geladen …", W * .5f, H * .52f, t);
                t.setTextAlign(Paint.Align.LEFT);
                return;
            }

            List<Guest> sorted = new ArrayList<>(guests);
            Collections.sort(sorted, (a, b) -> {
                if (a.on != b.on) return a.on ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });

            int rows = (sorted.size() + 1) / 2;
            float rh = Math.max(48f, Math.min(57f, (float) Math.floor(h / Math.max(1, rows))));
            float contentH = rows * rh;
            diensteMaxScroll = Math.max(0f, contentH - h);
            if (diensteScrollY > diensteMaxScroll) diensteScrollY = diensteMaxScroll;

            c.save();
            c.clipRect(x0 - 4, y0, x0 + totalW + 4, y0 + h);
            for (int i = 0; i < sorted.size(); i++) {
                int col = i / rows;
                int row = i % rows;
                float gx = x0 + col * (colW + colGap);
                float gy = y0 + row * rh - diensteScrollY;
                if (gy + rh < y0 || gy > y0 + h) continue;

                Guest g = sorted.get(i);
                float alpha = g.on ? 1f : .45f;

                // Divider hairline
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x0CFFFFFF);
                c.drawRect(gx, gy + rh - 1, gx + colW, gy + rh, p);

                // Status dot
                p.setColor(WetterLage.alpha(g.on ? GREEN : OFF_COL, alpha));
                c.drawCircle(gx + 5, gy + rh * .5f, 5, p);

                float tx = gx + 18, bw = colW - 18;
                float topY = gy + rh * .5f - 5;

                // Right-aligned metrics
                Double ramUsedGb = g.ramPct != null && g.ramMaxGb != null ? g.ramPct / 100.0 * g.ramMaxGb : null;
                String cpuTxt = (g.on && g.cpu != null ? fmt(g.cpu, 1) + "% · " : "")
                    + (g.cores != null ? fmt(g.cores, 0) + (g.cores == 1.0 ? " Kern" : " Kerne") : "");
                String ramTxt = "VM".equals(g.typ) ? fmtGb(g.ramMaxGb) + " res."
                    : (g.on && ramUsedGb != null ? fmt(ramUsedGb, ramUsedGb < 10 ? 1 : 0) + "/" : "") + fmtGb(g.ramMaxGb);
                String diskTxt = (g.diskUsedGb != null ? fmt(g.diskUsedGb, 0) + "/" : "") + fmtGb(g.diskMaxGb);
                String meta = (cpuTxt.isEmpty() ? "" : cpuTxt + " · ") + ramTxt + " · " + diskTxt;

                t.setTypeface(tfNorm); t.setTextSize(11); t.setColor(WetterLage.alpha(0xFF6B7B8A, alpha));
                t.setTextAlign(Paint.Align.RIGHT);
                float metaW = t.measureText(meta);
                c.drawText(meta, gx + colW, topY, t);
                t.setTextAlign(Paint.Align.LEFT);

                // Name + type tag
                t.setTypeface(tfNorm); t.setTextSize(15); t.setColor(WetterLage.alpha(TXT, alpha));
                float maxNameW = Math.max(40f, bw - metaW - 44);
                String nm = kuerzen(t, g.name, maxNameW);
                c.drawText(nm, tx, topY, t);
                float nmW = t.measureText(nm);
                t.setTypeface(tfNorm); t.setTextSize(11); t.setColor(WetterLage.alpha(0xFF56636F, alpha));
                c.drawText(g.typ + (g.on ? "" : " · aus"), tx + nmW + 7, topY, t);

                // Combined CPU | RAM / Disk bars
                float barY = topY + 8;
                float halfW = (bw - 2) * .5f;

                // CPU bar
                p.setColor(WetterLage.alpha(0x12FFFFFF, alpha));
                c.drawRect(tx, barY, tx + halfW, barY + 7, p);
                if (g.on) {
                    float wPct = Math.max(2f, Math.min(100f, g.cpu == null ? 0f : g.cpu.floatValue()));
                    p.setColor(WetterLage.alpha(CPU_COL, alpha));
                    c.drawRect(tx, barY, tx + halfW * (wPct / 100f), barY + 7, p);
                }

                // RAM bar (hatched for VM reserved, solid for LXC)
                float rx = tx + halfW + 2;
                p.setColor(WetterLage.alpha(0x12FFFFFF, alpha));
                c.drawRect(rx, barY, rx + halfW, barY + 7, p);
                if (g.on) {
                    if ("VM".equals(g.typ)) {
                        p.setShader(schraffur.getShader());
                        c.drawRect(rx, barY, rx + halfW, barY + 7, p);
                        p.setShader(null);
                    } else {
                        float rPct = Math.max(2f, Math.min(100f, g.ramPct == null ? 0f : g.ramPct.floatValue()));
                        p.setColor(WetterLage.alpha(RAM_COL, alpha));
                        c.drawRect(rx, barY, rx + halfW * (rPct / 100f), barY + 7, p);
                    }
                }

                // Disk bar (full width below)
                float dyBar = barY + 9;
                p.setColor(WetterLage.alpha(0x12FFFFFF, alpha));
                c.drawRect(tx, dyBar, tx + bw, dyBar + 7, p);
                if (g.diskUsedGb != null && g.diskMaxGb != null && g.diskMaxGb > 0) {
                    float dPct = Math.max(2f, Math.min(100f, (float) (g.diskUsedGb / g.diskMaxGb * 100.0)));
                    p.setColor(WetterLage.alpha(DISK_COL, alpha));
                    c.drawRect(tx, dyBar, tx + bw * (dPct / 100f), dyBar + 7, p);
                }
            }
            c.restore();
        }

        private void zeichneBestaetigung(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xB805090C);
            c.drawRect(0, 0, W, H, p);
            hits.add(new Hit("modal_bg", 0, 0, W, H));

            float mw = 480, mh = 210, mx = (W - mw) * .5f, my = (H - mh) * .5f;
            rf.set(mx, my, mx + mw, my + mh);
            p.setColor(0xFF1A2631);
            c.drawRoundRect(rf, 22, 22, p);
            linie.setStrokeWidth(1f); linie.setColor(0x1EFFFFFF);
            c.drawRoundRect(rf, 22, 22, linie);
            hits.add(new Hit("modal_card", mx, my, mx + mw, my + mh));

            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(tfMed); t.setTextSize(21); t.setColor(TXT);
            c.drawText("Steckdose wirklich umschalten?", mx + 26, my + 48, t);

            String msg = Boolean.FALSE.equals(steckdoseAn)
                ? "Die Server-Steckdose wird eingeschaltet."
                : "Der ganze Server verliert dann sofort den Strom.";
            t.setTypeface(tfNorm); t.setTextSize(15.5f); t.setColor(SUB);
            c.drawText(msg, mx + 26, my + 86, t);

            float by = my + mh - 66;
            t.setTypeface(tfMed); t.setTextSize(16);
            String okTxt = Boolean.FALSE.equals(steckdoseAn) ? "Einschalten" : "Ausschalten";
            float okW = t.measureText(okTxt) + 40;
            float okX = mx + mw - 24 - okW;
            rf.set(okX, by, okX + okW, by + 44);
            p.setColor(Boolean.FALSE.equals(steckdoseAn) ? GREEN : 0xFFE8603C);
            c.drawRoundRect(rf, 16, 16, p);
            t.setColor(Boolean.FALSE.equals(steckdoseAn) ? BG : 0xFFFFFFFF);
            c.drawText(okTxt, okX + 20, by + 28, t);
            hits.add(new Hit("power_ok", rf.left, rf.top, rf.right, rf.bottom));

            float abW = t.measureText("Abbrechen") + 36;
            float abX = okX - 12 - abW;
            rf.set(abX, by, abX + abW, by + 44);
            p.setColor(0x14FFFFFF);
            c.drawRoundRect(rf, 16, 16, p);
            t.setColor(TXT);
            c.drawText("Abbrechen", abX + 18, by + 28, t);
            hits.add(new Hit("power_no", rf.left, rf.top, rf.right, rf.bottom));
        }

        private double peak(List<SparkPt> pts, Double cur) {
            double m = cur != null ? cur : 0;
            for (SparkPt pt : pts) if (pt.max > m) m = pt.max;
            return m;
        }

        private Double mean(List<SparkPt> pts) {
            if (pts.isEmpty()) return null;
            double s = 0;
            for (SparkPt pt : pts) s += pt.mean;
            return s / pts.size();
        }

        private String join(List<String> l, String sep) {
            StringBuilder sb = new StringBuilder();
            for (String s : l) sb.append(sb.length() == 0 ? "" : sep).append(s);
            return sb.toString();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            try {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getX(); downY = e.getY(); lastY = downY; ziehend = false;
                } else if (a == MotionEvent.ACTION_MOVE && "dienste".equals(tab) && !confirmPower && diensteMaxScroll > 0) {
                    float dyPx = (lastY - e.getY()) / skala;
                    if (Math.abs(e.getY() - downY) > 12) ziehend = true;
                    if (ziehend) {
                        diensteScrollY = Math.max(0f, Math.min(diensteMaxScroll, diensteScrollY + dyPx));
                        lastY = e.getY();
                        invalidate();
                    }
                } else if (a == MotionEvent.ACTION_UP && !ziehend && Math.hypot(e.getX() - downX, e.getY() - downY) < 30) {
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
            } else if ("tab:uebersicht".equals(id)) {
                setTab("uebersicht");
            } else if ("tab:dienste".equals(id)) {
                setTab("dienste");
            } else if ("power".equals(id)) {
                confirmPower = true;
                invalidate();
            } else if ("power_no".equals(id) || "modal_bg".equals(id)) {
                confirmPower = false;
                invalidate();
            } else if ("power_ok".equals(id)) {
                powerUmschalten();
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
            } else if ("mdi:power".equals(name)) {
                c.drawLine(12, 3, 12, 11, linie);
                c.drawArc(new RectF(5, 5, 19, 19), 305, 290, false, linie);
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
