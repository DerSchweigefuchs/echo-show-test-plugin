package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Native full-screen Guest Wi-Fi view matching gaeste_wlan.py on the 963 x 584 dashboard grid (#0D1720):
 * - Top-left back circle button, "Gäste-WLAN" title, and live status pill top-right
 * - Left 344x344 white card housing a crisp 268x268 QR code (QrCode.encode or router image fallback, dimmed when off)
 * - Right panel: SSID, toggleable password field (never logged), device count & security chips,
 *   duration selector (2 h, 4 h, bis morgen früh, dauerhaft), +2 h, and Einschalten/Ausschalten actions.
 */
final class GaesteWlanView extends FrameLayout {
    static final float W = 963, H = 584;
    private static final int BG = 0xFF0D1720, TXT = 0xFFECEBE8, SUB = 0xFF8FA3B5, AC = 0xFF4DB6E6;
    private static final int[] DAUER_WERTE = {2, 4, -1, 0};
    private static final String[] DAUER_TEXTE = {"2 h", "4 h", "bis morgen früh", "dauerhaft"};

    interface Aktionen {
        void zurueck();
        void ha(Map<String, Object> msg, Antwort cb);
        void bildLaden(String url);
        interface Antwort { void fertig(boolean ok, Object data, String err); }
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

    // Parsed HA state (sensitive strings stay strictly in memory, never logged)
    private boolean geladen;
    private boolean aktiv = true;
    private String fehler;
    private String ssid = "";
    private String passwort = "";
    private String sicherheit = "";
    private String wifiQr = "";
    private boolean[][] qrMatrix;
    private String qrMatrixKey = "";
    private String bildUrl = "";
    private String geladenBildUrl = "";
    private Bitmap fallbackBild;
    private boolean hatSchalter;
    private Boolean an;
    private boolean dauerhaft;
    private long bisMs;
    private int fallbackRestSek = -1;
    private boolean passwortZeigen = true;
    private boolean geraeteZeigen = true;
    private Integer geraeteAnzahl;

    // Interactive UI state
    private int gewaehlteDauer = 4;
    private boolean dauerInitialisiert;
    private boolean passwortVerdeckt;
    private boolean aktionLaeuft;
    private boolean angehaengt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!angehaengt) return;
            canvasView.invalidate();
            if (Boolean.TRUE.equals(an) && !dauerhaft && bisMs > 0) {
                postDelayed(this, 15_000L);
            }
        }
    };

    private final Runnable refreshRun = this::datenNachladen;

    GaesteWlanView(Context ctx, KsTheme theme, Aktionen aktionen) {
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
            ssid = str(m.get("ssid"));
            passwort = str(m.get("passwort"));
            if (passwort.isEmpty()) passwort = str(m.get("pw"));
            sicherheit = str(m.get("sicherheit"));
            if (sicherheit.isEmpty()) sicherheit = str(m.get("typ"));

            String qr = str(m.get("wifi_qr"));
            if (qr.isEmpty()) qr = str(m.get("qr"));
            if (qr.isEmpty()) qr = str(m.get("text"));
            wifiQr = qr;

            if (!wifiQr.isEmpty() && !wifiQr.equals(qrMatrixKey)) {
                try {
                    qrMatrix = QrCode.encode(wifiQr);
                    qrMatrixKey = wifiQr;
                } catch (RuntimeException ignored) {
                    qrMatrix = null;
                    qrMatrixKey = "";
                }
                Map<String, String> parsed = parseWifiQr(wifiQr);
                if (ssid.isEmpty()) ssid = parsed.get("S") != null ? parsed.get("S") : "";
                if (passwort.isEmpty()) passwort = parsed.get("P") != null ? parsed.get("P") : "";
                if (sicherheit.isEmpty()) sicherheit = parsed.get("T") != null ? parsed.get("T") : "";
            } else if (wifiQr.isEmpty()) {
                qrMatrix = null;
                qrMatrixKey = "";
            }

            String bUrl = str(m.get("bild_url"));
            if (bUrl.isEmpty()) bUrl = str(m.get("bild"));
            bildUrl = bUrl;
            if (wifiQr.isEmpty() && !bildUrl.isEmpty() && !bildUrl.equals(geladenBildUrl)) {
                geladenBildUrl = bildUrl;
                if (aktionen != null) aktionen.bildLaden(bildUrl);
            }

            Object anObj = m.get("an");
            if (anObj instanceof Boolean) an = (Boolean) anObj;
            else if (anObj instanceof String) {
                String s = ((String) anObj).trim().toLowerCase(Locale.ROOT);
                an = "on".equals(s) || "true".equals(s) ? Boolean.TRUE
                    : ("off".equals(s) || "false".equals(s) ? Boolean.FALSE : null);
            } else {
                an = null;
            }

            if (m.containsKey("hat_schalter")) {
                hatSchalter = Boolean.TRUE.equals(m.get("hat_schalter"));
            } else if (m.get("schalter") instanceof String && !((String) m.get("schalter")).trim().isEmpty()) {
                hatSchalter = true;
            } else {
                hatSchalter = an != null;
            }

            dauerhaft = Boolean.TRUE.equals(m.get("dauerhaft"));
            bisMs = parseZeitMs(m.get("bis"));
            if (bisMs == 0) bisMs = parseZeitMs(m.get("bis_ts"));
            fallbackRestSek = m.get("rest_s") instanceof Number ? ((Number) m.get("rest_s")).intValue() : -1;
            if (bisMs == 0 && fallbackRestSek > 0) {
                bisMs = System.currentTimeMillis() + fallbackRestSek * 1000L;
            }

            if (!dauerInitialisiert && m.get("dauer_h") instanceof Number) {
                int dh = ((Number) m.get("dauer_h")).intValue();
                for (int w : DAUER_WERTE) {
                    if (w == dh) { gewaehlteDauer = dh; break; }
                }
                dauerInitialisiert = true;
            }
            passwortZeigen = !Boolean.FALSE.equals(m.get("passwort_zeigen"));
            if (m.containsKey("geraete_zeigen")) {
                geraeteZeigen = !Boolean.FALSE.equals(m.get("geraete_zeigen"));
            } else if (m.get("geraete") instanceof Boolean) {
                geraeteZeigen = (Boolean) m.get("geraete");
            } else {
                geraeteZeigen = true;
            }
            Object ga = m.get("geraete") instanceof Number ? m.get("geraete")
                : (m.containsKey("geraete_anzahl") ? m.get("geraete_anzahl") : m.get("n"));
            geraeteAnzahl = ga instanceof Number ? ((Number) ga).intValue() : null;
        }
        tickPlanen();
        canvasView.invalidate();
    }

    void setBild(String url, Bitmap bm) {
        if (url != null && url.equals(bildUrl)) {
            fallbackBild = bm;
            canvasView.invalidate();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        angehaengt = true;
        tickPlanen();
    }

    @Override protected void onDetachedFromWindow() {
        angehaengt = false;
        removeCallbacks(tick);
        removeCallbacks(refreshRun);
        super.onDetachedFromWindow();
    }

    private void tickPlanen() {
        removeCallbacks(tick);
        if (angehaengt && Boolean.TRUE.equals(an) && !dauerhaft && bisMs > 0) {
            postDelayed(tick, 15_000L);
        }
    }

    private void datenNachladen() {
        if (aktionen == null || !angehaengt) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/gaeste_wlan/daten");
        aktionen.ha(msg, (ok, data, err) -> post(() -> TestAnsicht.sicher(() -> {
            aktionLaeuft = false;
            if (ok && data != null) setDaten(data, null);
            else if (err != null) setDaten(null, err);
            else canvasView.invalidate();
        })));
    }

    private void skriptAusfuehren(String aktion, Integer stunden) {
        if (aktionen == null || aktionLaeuft) return;
        aktionLaeuft = true;
        canvasView.invalidate();
        Map<String, Object> sdata = new LinkedHashMap<>();
        sdata.put("aktion", aktion);
        if (stunden != null) sdata.put("stunden", stunden);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "call_service");
        msg.put("domain", "script");
        msg.put("service", "echo_show_gaeste_wlan");
        msg.put("service_data", sdata);
        aktionen.ha(msg, (ok, data, err) -> post(() -> TestAnsicht.sicher(() -> {
            if (!ok) {
                aktionLaeuft = false;
                if (err != null) fehler = err;
                canvasView.invalidate();
                return;
            }
            removeCallbacks(refreshRun);
            postDelayed(refreshRun, 600L);
        })));
    }

    private static Map<String, String> parseWifiQr(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || !raw.regionMatches(true, 0, "WIFI:", 0, 5)) return map;
        String s = raw.substring(5);
        StringBuilder k = new StringBuilder(), v = new StringBuilder();
        boolean inK = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i++;
                if (inK) k.append(s.charAt(i)); else v.append(s.charAt(i));
                continue;
            }
            if (inK && c == ':') { inK = false; continue; }
            if (c == ';') {
                if (k.length() > 0) map.put(k.toString().toUpperCase(Locale.ROOT), v.toString());
                k.setLength(0); v.setLength(0); inK = true; continue;
            }
            if (inK) k.append(c); else v.append(c);
        }
        return map;
    }

    private static long parseZeitMs(Object o) {
        if (o instanceof Number) {
            double v = ((Number) o).doubleValue();
            if (v <= 0) return 0;
            return v > 1e12 ? Math.round(v) : Math.round(v * 1000.0);
        }
        if (o instanceof String) {
            long t = Anwesenheit.zeit(o);
            if (t > 0) return t;
        }
        return 0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private final class CanvasView extends View {
        private final List<Hit> hits = new ArrayList<>();
        private final Map<String, Path> iconCache = new HashMap<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint qrP = new Paint();
        private final Paint glanz = new Paint();
        private final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface tfNorm = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface tfMono = Typeface.MONOSPACE;
        private final RectF rf = new RectF();
        private final Rect dstR = new Rect();
        private final Path iconTmp = new Path();
        private float skala = 1f, dx, dy, downX, downY;

        CanvasView(Context ctx) {
            super(ctx);
            setClickable(true);
            qrP.setAntiAlias(false);
            qrP.setFilterBitmap(false);
            qrP.setStyle(Paint.Style.FILL);
            linie.setStyle(Paint.Style.STROKE);
            linie.setStrokeCap(Paint.Cap.ROUND);
            float cx = W * .22f, cy = H * .50f, rad = (float) Math.hypot(W - cx, H - cy) * .45f;
            glanz.setShader(new RadialGradient(cx, cy, rad, 0x244DB6E6, 0x004DB6E6, Shader.TileMode.CLAMP));
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
                boolean aus = hatSchalter && Boolean.FALSE.equals(an);
                if (!aus) c.drawRect(0, 0, W, H, glanz);
                zeichneKopf(c, aus);
                if (geladen && !aktiv) {
                    t.setTextAlign(Paint.Align.CENTER);
                    t.setTypeface(tfNorm); t.setTextSize(18); t.setColor(SUB);
                    c.drawText("Gäste-WLAN ist nicht eingerichtet.", W * .5f, H * .52f, t);
                    t.setTextAlign(Paint.Align.LEFT);
                } else {
                    zeichneQrKarte(c, aus);
                    zeichneRechts(c, aus);
                }
            } catch (RuntimeException ignored) {}
            c.restore();
        }

        private void zeichneKopf(Canvas c, boolean aus) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1EFFFFFF);
            c.drawCircle(58, 50, 28, p);
            drawIcon(c, "mdi:chevron-left", 45, 37, 26, TXT);
            hits.add(new Hit("zurueck", 22, 14, 94, 86));

            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(tfMed); t.setTextSize(22); t.setColor(TXT);
            c.drawText("Gäste-WLAN", 102, 44, t);

            t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(SUB);
            String sub = !geladen ? (fehler != null ? "Fehler: " + fehler : "Daten werden geladen …")
                : (aus ? ssid : "Handy-Kamera auf den Code halten – fertig");
            c.drawText(kuerzen(t, sub, 460), 102, 65, t);

            // Top-right status pill
            if (hatSchalter) {
                long now = System.currentTimeMillis();
                String restTxt = restText(now);
                String lbl;
                String icName;
                int bgCol, fgCol;
                if (Boolean.TRUE.equals(an)) {
                    lbl = "An" + (restTxt.isEmpty() ? "" : " · " + restTxt);
                    icName = "mdi:check-circle";
                    bgCol = 0x296CC483;
                    fgCol = 0xFF8FDCA3;
                } else if (Boolean.FALSE.equals(an)) {
                    lbl = "Aus";
                    icName = "mdi:wifi-off";
                    bgCol = 0x12FFFFFF;
                    fgCol = 0xFF9DA0A2;
                } else {
                    lbl = "Status unbekannt";
                    icName = "mdi:help-circle-outline";
                    bgCol = 0x12FFFFFF;
                    fgCol = 0xFF9DA0A2;
                }
                t.setTypeface(tfMed); t.setTextSize(16);
                float tw = t.measureText(lbl);
                float pw = 16 + 18 + 8 + tw + 16;
                float px = W - 30 - pw, py = 26, ph = 38;
                rf.set(px, py, px + pw, py + ph);
                p.setStyle(Paint.Style.FILL);
                p.setColor(bgCol);
                c.drawRoundRect(rf, 16, 16, p);
                drawIcon(c, icName, px + 14, py + 10, 18, fgCol);
                t.setColor(fgCol);
                c.drawText(lbl, px + 39, py + 25, t);
            }
        }

        private void zeichneQrKarte(Canvas c, boolean aus) {
            float cardSize = 344, cardX = 56, cardY = 122;
            rf.set(cardX, cardY, cardX + cardSize, cardY + cardSize);
            p.setStyle(Paint.Style.FILL);
            p.setColor(aus ? 0x22FFFFFF : 0xFFFFFFFF);
            c.drawRoundRect(rf, 22, 22, p);

            float qrSize = 268;
            float qx = cardX + (cardSize - qrSize) * .5f;
            float qy = cardY + (cardSize - qrSize) * .5f;

            if (qrMatrix != null) {
                int n = qrMatrix.length;
                float cell = qrSize / n;
                qrP.setColor(aus ? 0x24000000 : 0xFF000000);
                for (int r = 0; r < n; r++) {
                    float top = qy + r * cell;
                    float bot = qy + (r + 1) * cell;
                    int col = 0;
                    while (col < n) {
                        if (!qrMatrix[r][col]) { col++; continue; }
                        int end = col + 1;
                        while (end < n && qrMatrix[r][end]) end++;
                        c.drawRect(qx + col * cell, top, qx + end * cell, bot, qrP);
                        col = end;
                    }
                }
            } else if (fallbackBild != null && !fallbackBild.isRecycled()) {
                dstR.set(Math.round(qx), Math.round(qy), Math.round(qx + qrSize), Math.round(qy + qrSize));
                qrP.setAlpha(aus ? 35 : 255);
                c.drawBitmap(fallbackBild, null, dstR, qrP);
                qrP.setAlpha(255);
            } else if (!aus) {
                drawIcon(c, "mdi:qrcode-scan", cardX + (cardSize - 64) * .5f, cardY + 116, 64, 0xFF5D6A74);
                t.setTextAlign(Paint.Align.CENTER);
                t.setTypeface(tfNorm); t.setTextSize(15); t.setColor(0xFF5D6A74);
                c.drawText(!geladen ? "QR-Code wird geladen …" : "QR-Code nicht verfügbar", cardX + cardSize * .5f, cardY + 204, t);
                t.setTextAlign(Paint.Align.LEFT);
            }

            if (aus) {
                drawIcon(c, "mdi:wifi-off", cardX + (cardSize - 90) * .5f, cardY + (cardSize - 90) * .5f, 90, 0xFF5D6A74);
            }
        }

        private void zeichneRechts(Canvas c, boolean aus) {
            float rx = 456, maxW = W - 36 - rx;
            if (aus) {
                t.setTypeface(tfMed); t.setTextSize(28); t.setColor(TXT);
                c.drawText("Gäste-WLAN ist aus", rx, 158, t);

                String desc = gewaehlteDauer == 0
                    ? "Einschalten – es bleibt an, bis jemand es ausschaltet."
                    : "Einschalten – es geht danach von selbst wieder aus.";
                t.setTypeface(tfNorm); t.setTextSize(16); t.setColor(SUB);
                zeichneZeilen(c, desc, t, rx, 190, maxW, 22, 2);

                t.setTypeface(tfMed); t.setTextSize(13); t.setColor(SUB);
                c.drawText("WIE LANGE?", rx, 260, t);

                float cx = rx, cy = 278;
                t.setTypeface(tfNorm); t.setTextSize(16);
                for (int i = 0; i < DAUER_WERTE.length; i++) {
                    int val = DAUER_WERTE[i];
                    String label = DAUER_TEXTE[i];
                    float cw = t.measureText(label) + 32;
                    if (cx + cw > W - 30) { cx = rx; cy += 48; }
                    boolean sel = gewaehlteDauer == val;
                    rf.set(cx, cy, cx + cw, cy + 40);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(sel ? 0xFF3182B7 : 0x12FFFFFF);
                    c.drawRoundRect(rf, 16, 16, p);
                    t.setColor(sel ? 0xFFFFFFFF : TXT);
                    c.drawText(label, cx + 16, cy + 25, t);
                    hits.add(new Hit("dauer:" + val, rf.left, rf.top, rf.right, rf.bottom));
                    cx += cw + 10;
                }

                float by = cy + 68;
                String btnLbl = aktionLaeuft ? "Wird eingeschaltet …" : "Einschalten";
                t.setTypeface(tfMed); t.setTextSize(18);
                float bw = 24 + 22 + 10 + t.measureText(btnLbl) + 24;
                rf.set(rx, by, rx + bw, by + 54);
                p.setColor(aktionLaeuft ? 0x803182B7 : 0xFF3182B7);
                c.drawRoundRect(rf, 20, 20, p);
                drawIcon(c, "mdi:wifi", rx + 22, by + 16, 22, 0xFFFFFFFF);
                t.setColor(0xFFFFFFFF);
                c.drawText(btnLbl, rx + 54, by + 33, t);
                hits.add(new Hit("act:an", rf.left, rf.top, rf.right, rf.bottom));
                return;
            }

            // Active / readable view
            float y = 138;
            t.setTypeface(tfMed); t.setTextSize(13); t.setColor(SUB);
            c.drawText("NETZWERK", rx, y, t);
            y += 32;
            String netz = ssid.isEmpty() ? "–" : ssid;
            t.setTypeface(tfMed); t.setTextSize(netz.length() > 22 ? 24 : 30); t.setColor(TXT);
            y = zeichneZeilen(c, netz, t, rx, y, maxW, 32, 2);

            if (passwortZeigen && !passwort.isEmpty()) {
                y += 36;
                t.setTypeface(tfMed); t.setTextSize(13); t.setColor(SUB);
                c.drawText("PASSWORT", rx, y, t);
                drawIcon(c, passwortVerdeckt ? "mdi:eye-off-outline" : "mdi:eye-outline", rx + 82, y - 14, 18, SUB);

                float pwTop = y - 18;
                y += 14;
                String shownPw = passwortVerdeckt ? punkte(Math.min(passwort.length(), 14)) : passwort;
                rf.set(rx, y, rx + maxW, y + 54);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x0DFFFFFF);
                c.drawRoundRect(rf, 14, 14, p);
                linie.setStrokeWidth(1f); linie.setColor(0x16FFFFFF);
                c.drawRoundRect(rf, 14, 14, linie);

                t.setTypeface(tfMono); t.setTextSize(passwort.length() > 18 ? 21 : 27); t.setColor(TXT);
                c.drawText(kuerzen(t, shownPw, maxW - 28), rx + 14, y + 36, t);
                hits.add(new Hit("pw_toggle", rx, pwTop, rx + maxW, y + 56));
                y += 74;
            } else {
                y += 34;
            }

            // Chips: connected devices + security
            float cx = rx;
            t.setTypeface(tfNorm); t.setTextSize(16);
            if (geraeteZeigen && geraeteAnzahl != null && !Boolean.FALSE.equals(an)) {
                String gTxt = geraeteAnzahl == 1 ? "1 Gerät verbunden" : geraeteAnzahl + " Geräte verbunden";
                float cw = 16 + 18 + 8 + t.measureText(gTxt) + 16;
                rf.set(cx, y, cx + cw, y + 38);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x12FFFFFF);
                c.drawRoundRect(rf, 16, 16, p);
                drawIcon(c, "mdi:cellphone", cx + 14, y + 10, 18, AC);
                t.setColor(TXT);
                c.drawText(gTxt, cx + 39, y + 25, t);
                cx += cw + 10;
            }
            String secTxt = sicherheitText(sicherheit);
            if (!secTxt.isEmpty()) {
                float cw = 16 + 18 + 8 + t.measureText(secTxt) + 16;
                if (cx + cw > W - 30) { cx = rx; y += 46; }
                rf.set(cx, y, cx + cw, y + 38);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x12FFFFFF);
                c.drawRoundRect(rf, 16, 16, p);
                drawIcon(c, "NOPASS".equalsIgnoreCase(sicherheit) ? "mdi:lock-open-variant-outline" : "mdi:lock-outline",
                    cx + 14, y + 10, 18, SUB);
                t.setColor(TXT);
                c.drawText(secTxt, cx + 39, y + 25, t);
            }

            // Bottom action buttons (+2 h and Ausschalten)
            if (hatSchalter && Boolean.TRUE.equals(an)) {
                float by = 446;
                float bx = rx;
                t.setTypeface(tfMed); t.setTextSize(18);
                if (!dauerhaft) {
                    String pLbl = "+2 h";
                    float bw = 22 + 22 + 10 + t.measureText(pLbl) + 22;
                    rf.set(bx, by, bx + bw, by + 54);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x14FFFFFF);
                    c.drawRoundRect(rf, 20, 20, p);
                    drawIcon(c, "mdi:timer-plus-outline", bx + 20, by + 16, 22, TXT);
                    t.setColor(TXT);
                    c.drawText(pLbl, bx + 52, by + 33, t);
                    hits.add(new Hit("act:plus", rf.left, rf.top, rf.right, rf.bottom));
                    bx += bw + 12;
                }
                String offLbl = aktionLaeuft ? "Wird geschaltet …" : "Ausschalten";
                float bw = 22 + 22 + 10 + t.measureText(offLbl) + 22;
                rf.set(bx, by, bx + bw, by + 54);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x2EE8603C);
                c.drawRoundRect(rf, 20, 20, p);
                drawIcon(c, "mdi:wifi-off", bx + 20, by + 16, 22, 0xFFF29A80);
                t.setColor(0xFFF29A80);
                c.drawText(offLbl, bx + 52, by + 33, t);
                hits.add(new Hit("act:aus", rf.left, rf.top, rf.right, rf.bottom));
            }
        }

        private String restText(long now) {
            if (!Boolean.TRUE.equals(an)) return "";
            if (dauerhaft || (bisMs > now + 30L * 86_400_000L)) return "dauerhaft";
            long rest = bisMs > now ? bisMs - now : 0;
            if (rest <= 0) return "";
            long minTotal = rest / 60_000L;
            return String.format(Locale.ROOT, "noch %d:%02d h", minTotal / 60, minTotal % 60);
        }

        private String sicherheitText(String raw) {
            if (raw == null || raw.isEmpty()) return "";
            String u = raw.toUpperCase(Locale.ROOT);
            if ("NOPASS".equals(u)) return "offen, ohne Passwort";
            if (u.contains("SAE")) return "WPA3";
            if (u.contains("WPA")) return "WPA2/WPA3";
            return u;
        }

        private String punkte(int n) {
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) sb.append('•');
            return sb.toString();
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
            } else if ("pw_toggle".equals(id)) {
                passwortVerdeckt = !passwortVerdeckt;
                invalidate();
            } else if (id.startsWith("dauer:")) {
                gewaehlteDauer = Integer.parseInt(id.substring(6));
                invalidate();
            } else if ("act:an".equals(id)) {
                skriptAusfuehren("an", gewaehlteDauer);
            } else if ("act:plus".equals(id)) {
                skriptAusfuehren("plus", null);
            } else if ("act:aus".equals(id)) {
                skriptAusfuehren("aus", null);
            }
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
            linie.setStrokeWidth(2.1f);
            iconTmp.reset();
            switch (name) {
                case "mdi:chevron-left":
                    iconTmp.moveTo(15, 6); iconTmp.lineTo(9, 12); iconTmp.lineTo(15, 18);
                    c.drawPath(iconTmp, linie);
                    break;
                case "mdi:wifi-off":
                    c.drawArc(new RectF(4, 7, 20, 23), 225, 90, false, linie);
                    c.drawArc(new RectF(8, 11, 16, 19), 225, 90, false, linie);
                    c.drawLine(4, 4, 20, 20, linie);
                    break;
                case "mdi:wifi":
                    c.drawArc(new RectF(3, 6, 21, 24), 225, 90, false, linie);
                    c.drawArc(new RectF(7, 10, 17, 20), 225, 90, false, linie);
                    p.setStyle(Paint.Style.FILL); p.setColor(color);
                    c.drawCircle(12, 18, 1.8f, p);
                    break;
                case "mdi:check-circle":
                    p.setStyle(Paint.Style.FILL); p.setColor(color);
                    c.drawCircle(12, 12, 9, p);
                    linie.setColor(BG);
                    iconTmp.moveTo(8, 12); iconTmp.lineTo(11, 15); iconTmp.lineTo(16.5f, 9.5f);
                    c.drawPath(iconTmp, linie);
                    break;
                default:
                    c.drawCircle(12, 12, 8, linie);
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
}
