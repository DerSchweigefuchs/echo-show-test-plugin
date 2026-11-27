package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Unified native "Mein Tag" v2 & news story player on the 963 x 584 Echo Show grid:
 * - Single cohesive story UI across ALL custom rundown segments (Begrüßung, Lokal, Politik, Wirtschaft,
 *   Sport, Kalender, Müll, Einkaufsliste, Wetter, Abschied, Nachrichten, Eilmeldung).
 * - Unified top segmented progress bar with per-group captions and smooth sub-progress fill.
 * - Unified bottom transport bar (‹ Zurück, Pause/Weiter, Überspringen ›, + Mehr dazu, Stopp) + equalizer & mode.
 * - Live 6-landscape WetterView stays active behind Wetter / Begrüßung / Abschied / Kalender / Müll / Liste,
 *   and is paused & hidden (GONE) during blurred-photo news segments for zero GPU overhead.
 * - Zero setShadowLayer, zero allocations in onDraw, native Android Roboto typefaces (Thin/Light/Regular/Medium).
 */
final class MorgenView extends FrameLayout {
    static final float W = WetterView.W, H = WetterView.H;
    private static final String[] WT = {"Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"};
    private static final String[] MON = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"};

    interface Aktionen {
        void zurueck();
        void steuern(String aktion);
        boolean pausiert();
        void bildBedarf(String bildUrl);
    }

    private final Aktionen aktionen;
    private final WetterView wetterView;
    private final OverlayCanvas overlay;
    private boolean wetterAus;

    MorgenView(Context ctx, KsTheme theme, Aktionen aktionen, String ort, boolean sternbildNamen) {
        super(ctx);
        this.aktionen = aktionen;
        setBackgroundColor(0xFF0B1119);
        setClickable(true);

        wetterView = new WetterView(ctx, new WetterView.Aktionen() {
            @Override public void zurueck() {}
            @Override public boolean pausiert() { return wetterAus || aktionen.pausiert(); }
            @Override public void fertig(long aufbauMs) {}
        }, "live", null, sternbildNamen, 20);
        wetterView.setNurSzene(true);
        wetterView.setOrt(ort);
        addView(wetterView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new OverlayCanvas(ctx, theme);
        addView(overlay, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    WetterView wetterView() { return wetterView; }

    void setWetter(WetterLage.Daten wd) {
        wetterView.setDaten(wd);
        overlay.setWetter(wd);
    }

    void setOrt(String ort) { wetterView.setOrt(ort); }

    void setEinstellungsVorschau(boolean aktiv, String ort, String cond, String zeit) {
        wetterView.setEinstellungsVorschau(aktiv, ort, cond, zeit);
    }

    void setNews(MorgenDaten.NewsStatus st) {
        overlay.setNews(st);
        abgleichSichtbarkeit();
    }

    void setArtikelBild(String url, Bitmap scharf, Bitmap weich) {
        overlay.setArtikelBild(url, scharf, weich);
    }

    void pauseGeaendert() {
        wetterView.pauseGeaendert();
        overlay.schleife();
    }

    void zeichneOverlayTest(Canvas c, int w, int h, long nowMs, float animS) {
        overlay.zeichneFuerTest(c, w, h, nowMs, animS);
    }

    private void abgleichSichtbarkeit() {
        boolean neuAus = overlay.istNachrichtVollbild();
        if (neuAus != wetterAus) {
            wetterAus = neuAus;
            wetterView.setVisibility(neuAus ? GONE : VISIBLE);
            wetterView.pauseGeaendert();
        }
    }

    // ---- Pre-laid-out karaoke words (zero allocations in onDraw) ---------------------------------------------------

    private static final class Wort {
        final String text;
        final int start, ende, zeile;
        final float x, y, breite;

        Wort(String text, int start, int ende, int zeile, float x, float y, float breite) {
            this.text = text; this.start = start; this.ende = ende;
            this.zeile = zeile; this.x = x; this.y = y; this.breite = breite;
        }
    }

    private static final class WortLayout {
        String key = "";
        final List<Wort> woerter = new ArrayList<>();
        int zeilen;
        float zeilenHoehe;

        void bauen(String text, Paint pt, float maxW, float lineH) {
            String k = text + "|" + Math.round(maxW) + "|" + Math.round(pt.getTextSize() * 10);
            if (k.equals(key)) return;
            key = k;
            woerter.clear();
            zeilenHoehe = lineH;
            if (text == null || text.isEmpty()) { zeilen = 0; return; }
            float space = pt.measureText(" ");
            int len = text.length(), i = 0, zeile = 0;
            float cx = 0;
            while (i < len) {
                while (i < len && Character.isWhitespace(text.charAt(i))) i++;
                if (i >= len) break;
                int s = i;
                while (i < len && !Character.isWhitespace(text.charAt(i))) i++;
                String w = text.substring(s, i);
                float ww = pt.measureText(w);
                if (cx > 0 && cx + ww > maxW) { zeile++; cx = 0; }
                woerter.add(new Wort(w, s, i, zeile, cx, zeile * lineH, ww));
                cx += ww + space;
            }
            zeilen = woerter.isEmpty() ? 0 : zeile + 1;
        }
    }

    private static final class Hit {
        final String id;
        final float l, t, r, b;
        Hit(String id, float l, float t, float r, float b) {
            this.id = id; this.l = l; this.t = t; this.r = r; this.b = b;
        }
        boolean trifft(float x, float y) { return x >= l && x <= r && y >= t && y <= b; }
    }

    // ---- Unified Story Overlay Canvas ------------------------------------------------------------------------------

    private final class OverlayCanvas extends View implements Choreographer.FrameCallback {
        private final KsTheme theme;
        private final Map<String, Path> iconCache = new HashMap<>();
        private final List<Hit> hits = new ArrayList<>();

        private WetterLage.Daten wetter;
        private WetterLage lage = new WetterLage("cloudy");
        private MorgenDaten.HeuteVerlauf heute = new MorgenDaten.HeuteVerlauf();
        private MorgenDaten.NewsStatus status = new MorgenDaten.NewsStatus();
        private MorgenDaten.MorgenInfo letztesMorgen;
        private boolean mini;
        private float pauseFortschritt;
        private boolean warPause;
        private float karScrollY;
        private String letzterSegKey = "";

        private final Map<String, Bitmap> scharfBilder = new HashMap<>();
        private final Map<String, Bitmap> weichBilder = new HashMap<>();

        // Seamless pre-allocated gradients (all fade cleanly to 0 alpha where needed)
        private final Shader scrimLinks = new LinearGradient(0, 0, 620, 0, 0xCC0A111A, 0x000A111A, Shader.TileMode.CLAMP);
        private final Shader scrimOben = new LinearGradient(0, 0, 0, 100, 0xAA0A111A, 0x000A111A, Shader.TileMode.CLAMP);
        private final Shader scrimUnten = new LinearGradient(0, 310, 0, H, 0x000A111A, 0xEC0A111A, Shader.TileMode.CLAMP);
        private final Shader scrimDim = new LinearGradient(0, 0, 0, H, 0xD40B121C, 0xF20B121C, Shader.TileMode.CLAMP);
        private final Shader scrimNews = new LinearGradient(0, 0, 0, H, 0x470E1118, 0x940C0F15, Shader.TileMode.CLAMP);
        private final Shader fotoCapGrad = new LinearGradient(0, 0, 0, 76, 0x00080A10, 0xDB080A10, Shader.TileMode.CLAMP);
        private final Shader bgNews = new LinearGradient(0, 0, W * .7f, H, 0xFF1A283A, 0xFF0C131C, Shader.TileMode.CLAMP);
        private final Shader bgEil = new LinearGradient(0, 0, W * .7f, H, 0xFF4A1418, 0xFF1A0D10, Shader.TileMode.CLAMP);
        private final Shader regenShader = new LinearGradient(0, 0, 0, 36, 0xB85AAAF5, 0x245AAAF5, Shader.TileMode.CLAMP);
        private final Shader kurveFallback = new LinearGradient(468, 0, 906, 0, 0xFF8FD3F4, 0xFFF2B45C, Shader.TileMode.CLAMP);

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bildP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint bgBildP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint maskP = new Paint();
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint karP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint karActP = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Typeface tfThin, tfLight, tfNorm, tfMed;
        private final RectF rf = new RectF();
        private final Rect srcR = new Rect();
        private final Path clipPath = new Path();
        private final Path kurvePath = new Path();
        private final Path iconTmp = new Path();
        private final WortLayout karLayout = new WortLayout();
        private Shader kurveShader;
        private String kurveKey = "";
        private float maskY, maskH;

        private float skala = 1f, dx, dy, downX, downY;
        private boolean laeuft, angehaengt, sichtbar = true;
        private long startNs, frameNs;
        private int tick;

        OverlayCanvas(Context ctx, KsTheme theme) {
            super(ctx);
            this.theme = theme;
            setClickable(true);
            tfThin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
            tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
            tfNorm = Typeface.create("sans-serif", Typeface.NORMAL);
            tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);

            linie.setStyle(Paint.Style.STROKE);
            linie.setStrokeCap(Paint.Cap.ROUND);
            linie.setStrokeJoin(Paint.Join.ROUND);
            karActP.setShadowLayer(12f, 0f, 0f, 0x8CFFFFFF);

            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.3f);
            ColorMatrix br = new ColorMatrix();
            br.setScale(0.46f, 0.46f, 0.46f, 1f);
            cm.postConcat(br);
            bgBildP.setColorFilter(new ColorMatrixColorFilter(cm));
            maskP.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        void setWetter(WetterLage.Daten wd) {
            wetter = wd;
            lage = new WetterLage(wd == null ? "cloudy" : wd.zustand);
            heute = MorgenDaten.heuteLesen(wd, lage, System.currentTimeMillis());
            invalidate();
        }

        void setNews(MorgenDaten.NewsStatus st) {
            boolean warAktiv = status != null && status.aktiv;
            status = st != null ? st : new MorgenDaten.NewsStatus();
            if ("morgen".equals(status.art) && status.morgen != null) letztesMorgen = status.morgen;
            if (!status.aktiv) {
                mini = false;
                warPause = false;
            } else if (!warAktiv) {
                mini = "leiste".equals(status.ansicht);
            }
            if (status.pause && !warPause) pauseFortschritt = status.fortschritt(System.currentTimeMillis());
            warPause = status.pause;

            String segKey = status.seg == null ? "" : status.i + "|" + status.seg.id + "|" + status.seg.art + "|" + status.seg.t0;
            if (!segKey.equals(letzterSegKey)) {
                letzterSegKey = segKey;
                karScrollY = 0;
            }

            MorgenDaten.Meldung cur = status.aktuelleMeldung();
            MorgenDaten.Meldung nxt = status.naechsteMeldung();
            if (cur != null && !cur.bild.isEmpty() && !scharfBilder.containsKey(cur.bild)) aktionen.bildBedarf(cur.bild);
            if (nxt != null && !nxt.bild.isEmpty() && !scharfBilder.containsKey(nxt.bild)) aktionen.bildBedarf(nxt.bild);

            schleife();
            invalidate();
        }

        void setArtikelBild(String url, Bitmap scharf, Bitmap weich) {
            if (url == null || url.isEmpty() || scharf == null) return;
            scharfBilder.put(url, scharf);
            if (weich != null) weichBilder.put(url, weich);
            invalidate();
        }

        boolean istNachrichtVollbild() {
            if (status == null || !status.aktiv || mini || "aus".equals(status.ansicht)) return false;
            if (status.seg == null) {
                // During startup before seg 0 arrives: only show live weather backdrop if group 0 is wetter/begruessung
                if (!status.gruppen.isEmpty()) {
                    String firstArt = status.gruppen.get(0).art;
                    return !("wetter".equals(firstArt) || "begruessung".equals(firstArt));
                }
                return true;
            }
            String a = status.seg.art;
            return "meldung".equals(a) || "titel".equals(a) || "mehr".equals(a) || "text".equals(a);
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
            if (soll) { tick = 0; ch.postFrameCallback(this); } else ch.removeFrameCallback(this);
        }

        @Override public void doFrame(long ns) {
            if (!laeuft) return;
            if (startNs == 0) startNs = ns;
            int tl = (status != null && status.aktiv && status.seg != null && !status.pause) ? 2 : 30;
            if (tick == 0 || (tick % tl) == 0) { frameNs = ns; invalidate(); }
            tick++;
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onDraw(Canvas c) {
            int gw = getWidth(), gh = getHeight();
            if (gw <= 0 || gh <= 0) return;
            float animS = frameNs > 0 && startNs > 0 ? (float) ((frameNs - startNs) * 1e-9) : SystemClock.uptimeMillis() / 1000f;
            zeichnen(c, gw, gh, System.currentTimeMillis(), animS);
        }

        void zeichneFuerTest(Canvas c, int gw, int gh, long nowMs, float animS) {
            if (wetter != null) heute = MorgenDaten.heuteLesen(wetter, lage, nowMs);
            zeichnen(c, gw, gh, nowMs, animS);
        }

        private void zeichnen(Canvas c, int gw, int gh, long nowMs, float animS) {
            skala = Math.min(gw / W, gh / H);
            if (skala <= 0) return;
            dx = (gw - W * skala) * .5f;
            dy = (gh - H * skala) * .5f;
            hits.clear();

            c.save();
            c.translate(dx, dy);
            c.scale(skala, skala);
            c.clipRect(0, 0, W, H);
            try {
                boolean aktiv = status != null && status.aktiv;
                MorgenDaten.Segment seg = aktiv ? status.seg : null;
                if (mini) {
                    zeichneStandbyWetter(c, nowMs, animS);
                } else if (seg != null) {
                    zeichneEinheitlicheStory(c, seg, nowMs, animS);
                } else if (aktiv) {
                    zeichneVorbereitung(c, nowMs, animS);
                } else {
                    zeichneStandbyWetter(c, nowMs, animS);
                }
            } catch (RuntimeException ignored) {}
            c.restore();
        }

        // ==== LOADING / PREPARATION FRAME (while TTS starts — never flashes old weather!) ===========================

        private void zeichneVorbereitung(Canvas c, long nowMs, float animS) {
            p.setStyle(Paint.Style.FILL);
            if (istNachrichtVollbild()) {
                p.setColor(0xFFFFFFFF);
                p.setShader(bgNews);
                c.drawRect(0, 0, W, H, p);
                p.setShader(null);
            } else {
                p.setColor(0xFFFFFFFF);
                p.setShader(scrimDim);
                c.drawRect(0, 0, W, H, p);
                p.setShader(null);
            }

            // Show the exact rundown top bar already at 0% so the UI feels instant and cohesive
            MorgenDaten.Segment dummy = new MorgenDaten.Segment();
            dummy.gruppe = 0; dummy.gruppeI = 0; dummy.gruppeN = 1;
            zeichneSegmentLeiste(c, dummy, 0f);

            String firstLbl = !status.gruppen.isEmpty() ? status.gruppen.get(0).label : "Mein Tag";
            zeichneKopfZeile(c, 0xFF6FB3F2, firstLbl, "Startet gleich …");

            // Calm centered animated wave + label
            float cx = W * .5f, cy = H * .48f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF7AB8F5);
            for (int k = 0; k < 7; k++) {
                float ph = animS * (3.4f + k * 0.4f) + k * 0.7f;
                float bh = 12f + 26f * (float) (.5 + .5 * Math.sin(ph));
                float bx = cx - 42 + k * 12f;
                rf.set(bx, cy - bh * .5f, bx + 6f, cy + bh * .5f);
                c.drawRoundRect(rf, 3, 3, p);
            }
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(tfLight); txt.setTextSize(26); txt.setColor(0xF0FFFFFF);
            c.drawText("Dein Briefing wird vorbereitet …", cx, cy + 54, txt);
            if (!status.modusLabel.isEmpty()) {
                txt.setTypeface(tfNorm); txt.setTextSize(14); txt.setColor(0x99DCE6F0);
                c.drawText(status.modusLabel, cx, cy + 80, txt);
            }
            txt.setTextAlign(Paint.Align.LEFT);
        }

        // ==== UNIFIED STORY PLAYER ==================================================================================

        private void zeichneEinheitlicheStory(Canvas c, MorgenDaten.Segment seg, long nowMs, float animS) {
            String art = seg.art;
            float prog = aktiverFortschritt(nowMs);
            MorgenDaten.MorgenInfo mi = (status.morgen != null) ? status.morgen : letztesMorgen;
            MorgenDaten.Meldung it = status.aktuelleMeldung();
            boolean isNews = "meldung".equals(art) || "titel".equals(art) || "mehr".equals(art) || "text".equals(art);

            // 1. Backdrop per segment type
            if (isNews) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF13161B);
                c.drawRect(0, 0, W, H, p);
                Bitmap blur = it != null ? weichBilder.get(it.bild) : null;
                if (blur != null && !blur.isRecycled()) {
                    drawBitmapCenterCrop(c, blur, -60f, -60f, W + 120f, H + 120f, 255, bgBildP);
                } else {
                    p.setColor(0xFFFFFFFF);
                    p.setShader("eil".equals(status.art) ? bgEil : bgNews);
                    c.drawRect(0, 0, W, H, p);
                    p.setShader(null);
                }
                p.setColor(0xFFFFFFFF);
                p.setShader(scrimNews);
                c.drawRect(0, 0, W, H, p);
                p.setShader(null);
            } else if ("kalender".equals(art) || "muell".equals(art) || "liste".equals(art)) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                p.setShader(scrimDim);
                c.drawRect(0, 0, W, H, p);
                p.setShader(null);
            } else {
                // Live Wetter / Begrüßung / Abschied: full bright weather scene + seamless edge scrims
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                p.setShader(scrimLinks); c.drawRect(0, 0, 620, H, p);
                p.setShader(scrimOben);  c.drawRect(0, 0, W, 100, p);
                p.setShader(scrimUnten); c.drawRect(0, 310, W, H - 310, p);
                p.setShader(null);
            }

            // 2. Unified Top Segment Progress Bar
            zeichneSegmentLeiste(c, seg, prog);

            // 3. Unified Header Row (Chapter Pill + Counter + Close)
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(nowMs);
            String datumKurz = WT[cal.get(Calendar.DAY_OF_WEEK) - 1] + ", " + cal.get(Calendar.DAY_OF_MONTH) + ". " + MON[cal.get(Calendar.MONTH)];
            String uhrKurz = String.format(Locale.ROOT, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
            int grpN = status.gruppen.isEmpty() ? Math.max(1, status.n) : status.gruppen.size();
            int grpIdx = Math.max(0, Math.min(grpN - 1, seg.gruppe));

            int dotCol = 0xFF6FB3F2;
            String pillTitel = !seg.kategorie.isEmpty() ? seg.kategorie : (!seg.label.isEmpty() ? seg.label : "Mein Tag");
            String pillSub = datumKurz + " · " + uhrKurz;

            if ("wetter".equals(art) || "intro".equals(art)) {
                dotCol = 0xFFF2B45C; pillTitel = "Wetter"; pillSub = "Heute · " + datumKurz;
            } else if ("begruessung".equals(art)) {
                dotCol = 0xFFFFB36B; pillTitel = "Begrüßung"; pillSub = datumKurz + " · " + uhrKurz;
            } else if ("abschied".equals(art)) {
                dotCol = 0xFFB49CF2; pillTitel = "Abschied"; pillSub = datumKurz + " · " + uhrKurz;
            } else if ("kalender".equals(art)) {
                dotCol = 0xFF6FB3F2; pillTitel = "Kalender";
                int tn = mi != null ? Math.max(mi.termine.size(), mi.termineDetail.size()) : 0;
                pillSub = datumKurz + " · " + (tn == 1 ? "1 Termin" : tn + " Termine");
            } else if ("muell".equals(art)) {
                dotCol = 0xFFF2D35C; pillTitel = "Müllabfuhr"; pillSub = datumKurz;
            } else if ("liste".equals(art)) {
                dotCol = 0xFF6CC483; pillTitel = "Einkaufsliste";
                int ln = mi != null ? mi.liste : 0;
                pillSub = ln == 1 ? "1 Eintrag" : ln + " Einträge";
            } else if (isNews) {
                dotCol = it != null ? status.quelleFarbe(it.q) : 0xFF6FB3F2;
                StringBuilder sb = new StringBuilder();
                if (seg.gruppeN > 1) {
                    sb.append(seg.gruppeI + 1).append(" von ").append(seg.gruppeN);
                } else {
                    int mIdx = status.aktuelleMeldungIndex();
                    if (mIdx >= 0 && status.items.size() > 1) {
                        sb.append(mIdx + 1).append(" von ").append(status.items.size());
                    }
                }
                if ("mehr".equals(art)) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append("mehr dazu");
                }
                pillSub = sb.toString();
            }
            zeichneKopfZeile(c, dotCol, pillTitel, pillSub);

            // 4. Center Content tailored to segment type
            if ("wetter".equals(art) || "intro".equals(art)) {
                zeichneWetterInhalt(c, seg, mi, prog);
            } else if ("begruessung".equals(art) || "abschied".equals(art)) {
                zeichneRahmenInhalt(c, seg, mi, cal, prog, "abschied".equals(art));
            } else if ("kalender".equals(art)) {
                zeichneKalenderInhalt(c, seg, mi, prog);
            } else if ("muell".equals(art)) {
                zeichneMuellInhalt(c, seg, mi, prog);
            } else if ("liste".equals(art)) {
                zeichneListeInhalt(c, seg, mi, prog);
            } else {
                zeichneNachrichtInhalt(c, seg, it, prog, nowMs);
            }
        }

        // ---- Segment 1/7: Wetter Content ---------------------------------------------------------------------------

        private void zeichneWetterInhalt(Canvas c, MorgenDaten.Segment seg, MorgenDaten.MorgenInfo mi, float prog) {
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setTypeface(tfLight); txt.setTextSize(34); txt.setColor(0xFFF2F4F7);
            c.drawText("Dein Wetter heute", 36, 132, txt);

            Integer tVal = (wetter != null && wetter.temp != null) ? (int) Math.round(wetter.temp) : (mi != null ? mi.wxTemp : null);
            String condStr = (wetter != null && wetter.zustand != null && !wetter.zustand.isEmpty()) ? lage.name() : "";
            if (tVal != null) {
                String tStr = tVal + "°";
                txt.setTypeface(tfThin); txt.setTextSize(72); txt.setColor(0xFFFFFFFF);
                c.drawText(tStr, 34, 214, txt);
                float tx = 34 + txt.measureText(tStr) + 14;
                if (!condStr.isEmpty()) {
                    txt.setTypeface(tfMed); txt.setTextSize(21); txt.setColor(0xFFECEBE8);
                    c.drawText(condStr, tx, 184, txt);
                }
                StringBuilder meta = new StringBuilder();
                if (mi != null && mi.wxLo != null) meta.append("Tief ").append(mi.wxLo).append("°");
                if (mi != null && mi.wxHi != null) { if (meta.length() > 0) meta.append(" · "); meta.append("Hoch ").append(mi.wxHi).append("°"); }
                Integer wnd = (wetter != null && wetter.wind != null) ? (int) Math.round(wetter.wind) : (mi != null ? mi.wxWind : null);
                if (wnd != null && wnd > 0) { if (meta.length() > 0) meta.append(" · "); meta.append("Wind ").append(wnd).append(" km/h"); }
                if (meta.length() > 0) {
                    txt.setTypeface(tfNorm); txt.setTextSize(14.5f); txt.setColor(0xCCDCE6F0);
                    c.drawText(meta.toString(), tx, 208, txt);
                }
            }

            // Weather hint pills
            float py = 238;
            if (mi != null && !mi.schirm.isEmpty()) {
                zeichneHinweisPill(c, 36, py, mi.schirm, 0x30F2B45C, 0x8CF2B45C, 0xFFFAD089, true);
                py += 40;
            }
            if (mi != null && mi.glatt) {
                zeichneHinweisPill(c, 36, py, "Glätte möglich – vorsichtig unterwegs", 0x307AB8F5, 0x8C7AB8F5, 0xFFBFE3FF, true);
                py += 40;
            }
            if (py == 238 && !heute.zusammenfassung.isEmpty()) {
                zeichneHinweisPill(c, 36, py, heute.zusammenfassung, 0x1AFFFFFF, 0x38FFFFFF, 0xFFDCE5EE, false);
            }

            // Right glass hourly card (x=446..927, y=94..316)
            zeichneGlasKarte(c, 446, 94, 481, 222, 0xC40E1723, 0x24FFFFFF, 20);
            txt.setTypeface(tfMed); txt.setTextSize(12.5f); txt.setColor(0xFF9CC8EE);
            c.drawText("HEUTE IM VERLAUF", 468, 122, txt);
            if (!heute.zusammenfassung.isEmpty()) {
                txt.setTextAlign(Paint.Align.RIGHT);
                txt.setTypeface(tfNorm); txt.setTextSize(12.5f); txt.setColor(0xB4FFFFFF);
                c.drawText(kuerzen(txt, heute.zusammenfassung, 290), 906, 122, txt);
                txt.setTextAlign(Paint.Align.LEFT);
            }
            zeichneStundenGraphKompakt(c, 468, 136, 438, 162);

            zeichneKaraokeBox(c, 36, 344, 891, 212, seg.text, prog, 21f);
        }

        // ---- Begrüßung / Abschied Content --------------------------------------------------------------------------

        private void zeichneRahmenInhalt(Canvas c, MorgenDaten.Segment seg, MorgenDaten.MorgenInfo mi, Calendar cal, float prog, boolean abschied) {
            int hh = cal.get(Calendar.HOUR_OF_DAY);
            String titel;
            if (abschied) {
                titel = hh < 12 ? "Guten Start in den Tag" : "Einen schönen Tag noch";
            } else {
                titel = hh < 11 ? "Guten Morgen" : (hh < 18 ? "Hallo" : "Guten Abend");
                if (mi != null && !mi.name.isEmpty()) titel += ", " + mi.name;
            }
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setTypeface(tfLight); txt.setTextSize(46); txt.setColor(0xFFFFFFFF);
            c.drawText(titel, 38, 184, txt);

            String datum = WT[cal.get(Calendar.DAY_OF_WEEK) - 1] + ", " + cal.get(Calendar.DAY_OF_MONTH) + ". "
                + MON[cal.get(Calendar.MONTH)] + " · " + String.format(Locale.ROOT, "%02d:%02d Uhr", hh, cal.get(Calendar.MINUTE));
            txt.setTypeface(tfNorm); txt.setTextSize(18); txt.setColor(0xCCDCE6F0);
            c.drawText(datum, 40, 220, txt);

            if (wetter != null && wetter.temp != null) {
                zeichneHinweisPill(c, 38, 248, Math.round(wetter.temp) + "° · " + lage.name(), 0x24FFFFFF, 0x42FFFFFF, 0xFFECEBE8, false);
            }
            zeichneKaraokeBox(c, 36, 344, 891, 212, seg.text, prog, 21.5f);
        }

        // ---- Kalender Content --------------------------------------------------------------------------------------

        private void zeichneKalenderInhalt(Canvas c, MorgenDaten.Segment seg, MorgenDaten.MorgenInfo mi, float prog) {
            zeichneGlasKarte(c, 36, 94, 891, 228, 0xD4141F2E, 0x1EFFFFFF, 20);
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setTypeface(tfMed); txt.setTextSize(12.5f); txt.setColor(0xFF7AB8F5);
            c.drawText("HEUTE IM KALENDER", 58, 124, txt);

            List<MorgenDaten.TerminDetail> list = mi != null ? mi.termineDetail : java.util.Collections.<MorgenDaten.TerminDetail>emptyList();
            if (list.isEmpty()) {
                txt.setTypeface(tfNorm); txt.setTextSize(17); txt.setColor(0xAAFFFFFF);
                c.drawText("Heute stehen keine weiteren Termine an.", 58, 178, txt);
            } else {
                int max = Math.min(4, list.size());
                for (int i = 0; i < max; i++) {
                    MorgenDaten.TerminDetail td = list.get(i);
                    float rx = 58 + (i / 2) * 434;
                    float ry = 142 + (i % 2) * 74;
                    zeichneAgendaZeile(c, rx, ry, 412, td);
                }
            }
            zeichneKaraokeBox(c, 36, 344, 891, 212, seg.text, prog, 21f);
        }

        private void zeichneAgendaZeile(Canvas c, float x, float y, float w, MorgenDaten.TerminDetail td) {
            zeichneGlasKarte(c, x, y, w, 60, 0x0EFFFFFF, 0x18FFFFFF, 14);
            p.setStyle(Paint.Style.FILL);
            p.setColor(td.farbe);
            rf.set(x + 12, y + 12, x + 16, y + 48);
            c.drawRoundRect(rf, 2, 2, p);

            txt.setTypeface(tfMed); txt.setTextSize(15); txt.setColor(td.farbe);
            c.drawText(td.zeit.isEmpty() ? "Heute" : td.zeit, x + 28, y + 36, txt);

            txt.setTypeface(tfMed); txt.setTextSize(16.5f); txt.setColor(0xFFFFFFFF);
            c.drawText(kuerzen(txt, td.titel, w - 102), x + 90, y + (td.ort.isEmpty() && td.bis.isEmpty() ? 36 : 29), txt);

            String sub = td.ort;
            if (!td.bis.isEmpty()) sub = (sub.isEmpty() ? "" : sub + " · ") + td.zeit + "–" + td.bis;
            if (!sub.isEmpty()) {
                txt.setTypeface(tfNorm); txt.setTextSize(13); txt.setColor(0xB8B9C8D8);
                c.drawText(kuerzen(txt, sub, w - 102), x + 90, y + 48, txt);
            }
        }

        // ---- Müll Content ------------------------------------------------------------------------------------------

        private void zeichneMuellInhalt(Canvas c, MorgenDaten.Segment seg, MorgenDaten.MorgenInfo mi, float prog) {
            zeichneGlasKarte(c, 36, 94, 891, 228, 0xD4141F2E, 0x1EFFFFFF, 20);
            txt.setTypeface(tfMed); txt.setTextSize(12.5f); txt.setColor(0xFFF2D35C);
            c.drawText("MÜLLABFUHR", 58, 124, txt);

            String mu = mi != null ? mi.muell : "";
            int col = mu.toLowerCase(Locale.ROOT).contains("papier") ? 0xFF6FB3F2
                : (mu.toLowerCase(Locale.ROOT).contains("gelb") ? 0xFFE8C23A
                : (mu.toLowerCase(Locale.ROOT).contains("bio") ? 0xFFA06E46 : 0xFF8FA3B5));
            zeichneGlasKarte(c, 58, 152, 74, 74, WetterLage.alpha(col, .24f), WetterLage.alpha(col, .52f), 18);
            drawIcon(c, "mdi:trash-can-outline", 77, 171, 36, col);

            txt.setTypeface(tfMed); txt.setTextSize(28); txt.setColor(0xFFFFFFFF);
            c.drawText(mu.isEmpty() ? "Keine Abholung geplant" : kuerzen(txt, mu, 730), 154, 192, txt);
            txt.setTypeface(tfNorm); txt.setTextSize(15.5f); txt.setColor(0xB8DCE6F0);
            c.drawText("Rechtzeitig an die Straße stellen", 154, 222, txt);

            zeichneKaraokeBox(c, 36, 344, 891, 212, seg.text, prog, 21f);
        }

        // ---- Einkaufsliste Content ---------------------------------------------------------------------------------

        private void zeichneListeInhalt(Canvas c, MorgenDaten.Segment seg, MorgenDaten.MorgenInfo mi, float prog) {
            zeichneGlasKarte(c, 36, 94, 891, 228, 0xD4141F2E, 0x1EFFFFFF, 20);
            txt.setTypeface(tfMed); txt.setTextSize(12.5f); txt.setColor(0xFF6CC483);
            c.drawText("EINKAUFSLISTE", 58, 124, txt);

            List<String> itms = mi != null ? mi.listeItems : java.util.Collections.<String>emptyList();
            if (itms.isEmpty()) {
                int cnt = mi != null ? mi.liste : 0;
                txt.setTypeface(tfLight); txt.setTextSize(26); txt.setColor(0xFFFFFFFF);
                c.drawText(cnt == 1 ? "1 offener Eintrag" : cnt + " offene Einträge", 58, 186, txt);
            } else {
                int max = Math.min(8, itms.size());
                for (int i = 0; i < max; i++) {
                    float ix = 58 + (i / 4) * 430;
                    float iy = 160 + (i % 4) * 38;
                    linie.setStrokeWidth(1.8f); linie.setColor(0xB46CC483);
                    c.drawCircle(ix + 7, iy - 5, 6, linie);
                    txt.setTypeface(tfNorm); txt.setTextSize(17); txt.setColor(0xFFECEBE8);
                    c.drawText(kuerzen(txt, itms.get(i), 380), ix + 24, iy, txt);
                }
            }
            zeichneKaraokeBox(c, 36, 344, 891, 212, seg.text, prog, 21f);
        }

        // ---- News Segment Content (Variante H: Großes 50/50 Split ohne Footer/Danach) -------------------------------

        private void zeichneNachrichtInhalt(Canvas c, MorgenDaten.Segment seg, MorgenDaten.Meldung it, float prog, long nowMs) {
            String artTitel = (it != null && !it.t.isEmpty()) ? it.t : (seg.titel != null ? seg.titel : "");
            boolean hatBild = it != null && !it.bild.isEmpty();

            float rightX = hatBild ? 508f : 36f;
            float rightW = hatBild ? (W - 28f - rightX) : (W - 72f);

            if (hatBild) {
                float px = 28f, py = 96f, pw = 452f, ph = 460f, rad = 24f;
                drawSoftShadow(c, px, py, pw, ph, rad, 14f, 26f, 0.48f);

                rf.set(px, py, px + pw, py + ph);
                clipPath.reset();
                clipPath.addRoundRect(rf, rad, rad, Path.Direction.CW);
                c.save();
                c.clipPath(clipPath);

                Bitmap foto = scharfBilder.get(it.bild);
                if (foto != null && !foto.isRecycled()) {
                    drawBitmapCenterCrop(c, foto, px, py, pw, ph, 255, bildP);
                } else {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xFF1A2332);
                    c.drawRect(px, py, px + pw, py + ph, p);
                    drawIcon(c, "mdi:newspaper-variant-outline", px + pw * 0.5f - 28f, py + ph * 0.44f - 28f, 56f, 0x48FFFFFF);
                }

                // Bottom caption inside rounded photo
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                p.setShader(fotoCapGrad);
                c.save();
                c.translate(0f, py + ph - 76f);
                c.drawRect(px, 0f, px + pw, 76f, p);
                c.restore();
                p.setShader(null);

                int qc = status.quelleFarbe(it.q);
                String qName = status.quelleName(it.q);
                String vor = MorgenDaten.vorText(it.z, nowMs);
                float cy = py + ph - 22f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(qc);
                c.drawCircle(px + 22f, cy - 4.5f, 4.5f, p);
                txt.setTextAlign(Paint.Align.LEFT);
                txt.setTypeface(tfMed); txt.setTextSize(14.5f); txt.setColor(0xFFFFFFFF);
                c.drawText(qName, px + 35f, cy, txt);
                if (!vor.isEmpty()) {
                    float qw = txt.measureText(qName);
                    txt.setTypeface(tfNorm); txt.setTextSize(14f); txt.setColor(0xCCFFFFFF);
                    c.drawText("  ·  " + vor, px + 35f + qw, cy, txt);
                }
                c.restore();
            }

            float textY = hatBild ? 124f : 128f;
            if (!artTitel.isEmpty()) {
                txt.setTextAlign(Paint.Align.LEFT);
                txt.setTypeface(tfMed); txt.setTextSize(hatBild ? 27f : 28.5f);
                txt.setColor("eil".equals(status.art) ? 0xFFFF9A92 : 0xFFFFFFFF);
                float endY = zeichneMehrzeilig(c, artTitel, txt, rightX, textY, rightW, hatBild ? 33f : 35f, 3);
                textY = endY + 24f;
            }

            float fs = hatBild ? 23.5f : 25f;
            karP.setTypeface(tfNorm); karP.setTextSize(fs);
            karActP.setTypeface(tfMed); karActP.setTextSize(fs);
            karLayout.bauen(seg.text, karP, rightW, fs * 1.44f);
            karScrollY = zeichneKaraoke(c, karLayout, seg.text, prog, status.karaoke, rightX, textY, rightW, 556f - textY, karScrollY);
        }

        // ---- Standby (when opened without active playback or minimized) --------------------------------------------

        private void zeichneStandbyWetter(Canvas c, long nowMs, float animS) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setShader(scrimLinks); c.drawRect(0, 0, 620, H, p);
            p.setShader(scrimUnten); c.drawRect(0, 310, W, H - 310, p);
            p.setShader(null);

            // Back circle
            p.setColor(0x24FFFFFF); c.drawCircle(54, 52, 24, p);
            drawIcon(c, "mdi:chevron-left", 42, 40, 24, 0xFFECEBE8);
            hits.add(new Hit("zurueck", 24, 22, 84, 82));

            Calendar cal = Calendar.getInstance(); cal.setTimeInMillis(nowMs);
            int hh = cal.get(Calendar.HOUR_OF_DAY);
            String gruss = hh < 11 ? "Guten Morgen" : (hh < 18 ? "Hallo" : "Guten Abend");
            txt.setTypeface(tfLight); txt.setTextSize(40); txt.setColor(0xFFFFFFFF);
            c.drawText(gruss, 96, 68, txt);

            if (wetter != null && wetter.temp != null) {
                String tStr = Math.round(wetter.temp) + "°";
                txt.setTypeface(tfThin); txt.setTextSize(68); txt.setColor(0xFFFFFFFF);
                c.drawText(tStr, 36, 168, txt);
                txt.setTypeface(tfMed); txt.setTextSize(21); txt.setColor(0xFFECEBE8);
                c.drawText(lage.name(), 36 + txt.measureText(tStr) + 16, 152, txt);
            }

            zeichneGlasKarte(c, 36, 210, 891, 240, 0xC80E1723, 0x20FFFFFF, 22);
            txt.setTypeface(tfMed); txt.setTextSize(13); txt.setColor(0xFF9CC8EE);
            c.drawText("HEUTE IM VERLAUF", 58, 240, txt);
            zeichneStundenGraphKompakt(c, 58, 254, 846, 178);

            if (status != null && status.aktiv && status.seg != null) {
                txt.setTextAlign(Paint.Align.LEFT);
                zeichneGlasKarte(c, 36, 496, 360, 48, 0xCC0D141C, 0x26FFFFFF, 24);
                p.setStyle(Paint.Style.FILL); p.setColor(0xFF7AB8F5);
                c.drawCircle(58, 520, 4.5f, p);
                txt.setTypeface(tfMed); txt.setTextSize(14); txt.setColor(0xFFFFFFFF);
                c.drawText(kuerzen(txt, status.modusLabel.isEmpty() ? "Wird vorgelesen · Antippen" : status.modusLabel, 300), 72, 525, txt);
                hits.add(new Hit("gross", 36, 494, 396, 548));
            }
        }

        // ---- Shared Unified Components -----------------------------------------------------------------------------

        private void zeichneSegmentLeiste(Canvas c, MorgenDaten.Segment seg, float prog) {
            int n = status.gruppen.isEmpty() ? Math.max(1, status.n) : status.gruppen.size();
            int actG = Math.max(0, Math.min(n - 1, seg.gruppe));

            double gesamt = status.gesamtDauer;
            if (gesamt <= 0.5 && !status.gruppen.isEmpty()) {
                for (MorgenDaten.Gruppe g : status.gruppen) gesamt += g.dauer;
            }
            String gesamtTxt = gesamt > 0.5 ? fmtDauer(gesamt) : "";
            float durW = 0f;
            if (!gesamtTxt.isEmpty()) {
                txt.setFontFeatureSettings("tnum");
                txt.setTypeface(tfMed); txt.setTextSize(12.5f);
                durW = txt.measureText(gesamtTxt);
                txt.setTextAlign(Paint.Align.RIGHT);
                txt.setColor(0xCCFFFFFF);
                c.drawText(gesamtTxt, W - 28f, 20.5f, txt);
                txt.setTextAlign(Paint.Align.LEFT);
                txt.setFontFeatureSettings(null);
            }
            float durSpace = durW > 0f ? durW + 14f : 0f;

            float x0 = 28f, gap = n > 7 ? 5f : 7f;
            float usableW = Math.max(40f, W - 56f - durSpace - gap * (n - 1));

            float[] weights = new float[n];
            double sumW = 0.0;
            for (int k = 0; k < n; k++) {
                float wt;
                if (!status.gruppen.isEmpty() && k < status.gruppen.size() && status.gruppen.get(k).dauer > 0.5) {
                    wt = (float) status.gruppen.get(k).dauer;
                } else {
                    int cnt = (!status.gruppen.isEmpty() && k < status.gruppen.size()) ? status.gruppen.get(k).count : 1;
                    wt = Math.max(1f, cnt * 12f);
                }
                weights[k] = wt;
                sumW += wt;
            }
            float minW = Math.min(24f, usableW / n);
            float remW = Math.max(0f, usableW - minW * n);

            p.setStyle(Paint.Style.FILL);
            txt.setTextAlign(Paint.Align.LEFT);
            float sx = x0;
            for (int k = 0; k < n; k++) {
                float segW = minW + (sumW > 0 ? remW * (weights[k] / (float) sumW) : (remW / n));
                p.setColor(0x38FFFFFF);
                rf.set(sx, 14f, sx + segW, 18.5f);
                c.drawRoundRect(rf, 2.5f, 2.5f, p);

                float fill = 0f;
                if (k < actG) {
                    fill = 1f;
                } else if (k == actG) {
                    MorgenDaten.Gruppe actGrp = (!status.gruppen.isEmpty() && k < status.gruppen.size()) ? status.gruppen.get(k) : null;
                    if (actGrp != null && actGrp.dauer > 0.5 && seg.dauer > 0.1) {
                        fill = (float) Math.max(0.03, Math.min(1.0, (seg.gruppeOffset + prog * seg.dauer) / actGrp.dauer));
                    } else {
                        fill = Math.max(0.05f, Math.min(1f, (seg.gruppeI + prog) / Math.max(1f, seg.gruppeN)));
                    }
                }
                if (fill > 0.005f) {
                    p.setColor(k == actG ? 0xFFFFFFFF : 0xDCFFFFFF);
                    rf.set(sx, 14f, sx + segW * fill, 18.5f);
                    c.drawRoundRect(rf, 2.5f, 2.5f, p);
                }

                if (!status.gruppen.isEmpty() && k < status.gruppen.size() && segW >= 22f) {
                    String lbl = status.gruppen.get(k).label;
                    txt.setTypeface(k == actG ? tfMed : tfNorm);
                    txt.setTextSize(10.5f);
                    txt.setColor(k == actG ? 0xF5FFFFFF : (k < actG ? 0x9CFFFFFF : 0x5EFFFFFF));
                    c.drawText(kuerzen(txt, lbl, segW - 2f), sx + 1f, 32f, txt);
                }
                sx += segW + gap;
            }
        }

        private String fmtDauer(double sec) {
            int s = Math.max(1, (int) Math.round(sec));
            return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
        }

        private void zeichneKopfZeile(Canvas c, int dotCol, String titel, String sub) {
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setTypeface(tfMed); txt.setTextSize(14f);
            float tw = txt.measureText(titel);
            String subTxt = sub.isEmpty() ? "" : ("  ·  " + sub);
            txt.setTypeface(tfNorm); txt.setTextSize(13.5f);
            float sw = txt.measureText(subTxt);
            float pw = Math.min(540f, 44f + tw + sw);

            zeichneGlasKarte(c, 28f, 45f, pw, 36f, 0x8F0F121A, 0x24FFFFFF, 18f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(dotCol);
            c.drawCircle(46f, 63f, 4.5f, p);

            txt.setTypeface(tfMed); txt.setTextSize(14f); txt.setColor(0xFFFFFFFF);
            c.drawText(titel, 58f, 68f, txt);
            if (!subTxt.isEmpty()) {
                txt.setTypeface(tfNorm); txt.setTextSize(13.5f); txt.setColor(0xCCFFFFFF);
                c.drawText(kuerzen(txt, subTxt, pw - 44f - tw), 58f + tw, 68f, txt);
            }

            // Close circle (top right)
            zeichneGlasKarte(c, 893f, 42f, 42f, 42f, 0x850F121A, 0x29FFFFFF, 21f);
            drawIcon(c, "mdi:close", 904f, 53f, 20f, 0xFFFFFFFF);
            hits.add(new Hit("stopp", 886f, 36f, 944f, 92f));
        }

        private void zeichneKaraokeBox(Canvas c, float x, float y, float w, float h, String text, float prog, float fs) {
            zeichneGlasKarte(c, x, y, w, h, 0xB80C131C, 0x22FFFFFF, 20);
            karP.setTypeface(tfNorm); karP.setTextSize(fs);
            karActP.setTypeface(tfMed); karActP.setTextSize(fs);
            karLayout.bauen(text, karP, w - 48, fs * 1.48f);
            karScrollY = zeichneKaraoke(c, karLayout, text, prog, status.karaoke, x + 24, y + 18, w - 48, h - 32, karScrollY);
        }

        private float zeichneKaraoke(Canvas c, WortLayout layout, String text, float prog, boolean karaoke,
                                     float x, float y, float w, float h, float scrollY) {
            if (layout.woerter.isEmpty() || h <= 4f) return 0;
            float curChar = prog * (text == null ? 0 : text.length());
            int aktivIdx = -1, aktiveZeile = 0;
            if (karaoke && prog > 0 && prog < 0.995f) {
                for (int i = 0; i < layout.woerter.size(); i++) {
                    Wort wo = layout.woerter.get(i);
                    if (wo.start <= curChar) { aktivIdx = i; aktiveZeile = wo.zeile; } else break;
                }
            } else if (karaoke && prog >= 0.995f) {
                aktiveZeile = Math.max(0, layout.zeilen - 1);
            }
            float maxScroll = Math.max(0, layout.zeilen * layout.zeilenHoehe - h);
            float zielScroll = maxScroll > 0 ? Math.max(0, Math.min(maxScroll, aktiveZeile * layout.zeilenHoehe - h * .38f)) : 0;
            float neuScroll = scrollY + (zielScroll - scrollY) * .22f;
            if (Math.abs(zielScroll - neuScroll) < .5f) neuScroll = zielScroll;

            if (maskP.getShader() == null || maskY != y || maskH != h) {
                maskY = y; maskH = h;
                maskP.setShader(new LinearGradient(
                    0f, y, 0f, y + h,
                    new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                    new float[]{0f, 0.08f, 0.85f, 1f},
                    Shader.TileMode.CLAMP
                ));
            }
            int layer = c.saveLayer(x - 8f, y - 4f, x + w + 8f, y + h + 4f, null);
            float baseAscent = -karP.ascent();
            for (int i = 0; i < layout.woerter.size(); i++) {
                Wort wo = layout.woerter.get(i);
                float wy = y + wo.y - neuScroll + baseAscent;
                if (wy < y - layout.zeilenHoehe || wy > y + h + layout.zeilenHoehe) continue;
                if (!karaoke) {
                    karP.setColor(0xEEFFFFFF);
                    c.drawText(wo.text, x + wo.x, wy, karP);
                } else if (i == aktivIdx) {
                    karActP.setColor(0xFFFFFFFF);
                    c.drawText(wo.text, x + wo.x, wy, karActP);
                } else if (wo.ende <= curChar) {
                    karP.setColor(0xE0FFFFFF);
                    c.drawText(wo.text, x + wo.x, wy, karP);
                } else {
                    karP.setColor(0x5EFFFFFF);
                    c.drawText(wo.text, x + wo.x, wy, karP);
                }
            }
            c.drawRect(x - 8f, y - 4f, x + w + 8f, y + h + 4f, maskP);
            c.restoreToCount(layer);
            return neuScroll;
        }

        private void zeichneStundenGraphKompakt(Canvas c, float x, float y, float w, float h) {
            List<MorgenDaten.Stunde> hs = heute.stunden;
            if (hs == null || hs.isEmpty()) {
                txt.setTypeface(tfNorm); txt.setTextSize(14); txt.setColor(0xFF9FB0C4);
                c.drawText("Stundenvorhersage wird geladen …", x, y + 36, txt);
                return;
            }
            int n = Math.min(8, hs.size());
            float colW = w / n;
            int gmin = 99, gmax = -99;
            for (int i = 0; i < n; i++) {
                MorgenDaten.Stunde st = hs.get(i);
                if (st.temp < gmin) gmin = st.temp;
                if (st.temp > gmax) gmax = st.temp;
            }
            float span = Math.max(4f, gmax - gmin);
            float[] px = new float[n], py = new float[n];
            int[] cols = new int[n];
            float[] stops = new float[n];
            for (int i = 0; i < n; i++) {
                MorgenDaten.Stunde st = hs.get(i);
                px[i] = x + (i + .5f) * colW;
                py[i] = y + 84 - (st.temp - gmin) / span * 42f;
                cols[i] = MorgenDaten.tempFarbe(st.temp);
                stops[i] = n > 1 ? i / (float) (n - 1) : 0f;
            }

            // Rain bars at bottom
            txt.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < n; i++) {
                MorgenDaten.Stunde st = hs.get(i);
                int pct = st.regenPct != null ? st.regenPct : (MorgenDaten.istNass(st) ? 70 : 0);
                if (pct >= 35) {
                    float bh = Math.max(8f, (pct / 100f) * 34f);
                    float by = y + h - 22 - bh;
                    p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); p.setShader(regenShader);
                    c.save(); c.translate(0, by);
                    rf.set(px[i] - 12, 0, px[i] + 12, bh);
                    c.drawRoundRect(rf, 6, 6, p);
                    c.restore(); p.setShader(null);
                    txt.setTypeface(tfMed); txt.setTextSize(10.5f); txt.setColor(0xFF8CC8FF);
                    c.drawText(pct + "%", px[i], by - 4, txt);
                }
            }

            // Smooth temperature curve
            if (n >= 2) {
                kurvePath.reset();
                kurvePath.moveTo(px[0], py[0]);
                for (int i = 0; i < n - 1; i++) {
                    float mx = (px[i] + px[i + 1]) * .5f;
                    kurvePath.cubicTo(mx, py[i], mx, py[i + 1], px[i + 1], py[i + 1]);
                }
                String kKey = Math.round(px[0]) + "|" + Math.round(px[n - 1]) + "|" + cols[0] + "|" + cols[n - 1];
                if (kurveShader == null || !kKey.equals(kurveKey)) {
                    kurveKey = kKey;
                    kurveShader = new LinearGradient(px[0], 0, px[n - 1], 0, cols, stops, Shader.TileMode.CLAMP);
                }
                linie.setColor(0xFFFFFFFF); linie.setStrokeWidth(3f);
                linie.setShader(kurveShader != null ? kurveShader : kurveFallback);
                c.drawPath(kurvePath, linie);
                linie.setShader(null);
            }

            for (int i = 0; i < n; i++) {
                MorgenDaten.Stunde st = hs.get(i);
                txt.setTypeface(tfMed); txt.setTextSize(13.5f); txt.setColor(0xFFFFFFFF);
                c.drawText(st.temp + "°", px[i], py[i] - 11, txt);
                p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF);
                c.drawCircle(px[i], py[i], 3.2f, p);
                txt.setTypeface(tfNorm); txt.setTextSize(12); txt.setColor(0xC4CDDAE8);
                c.drawText(st.stunde + " Uhr", px[i], y + h - 4, txt);
            }
            txt.setTextAlign(Paint.Align.LEFT);
        }

        private void zeichneHinweisPill(Canvas c, float x, float y, String text, int bg, int border, int fg, boolean filledDot) {
            txt.setTypeface(tfMed); txt.setTextSize(13.5f);
            float maxW = 392;
            String s = kuerzen(txt, text, maxW - 42);
            float w = txt.measureText(s) + 42;
            zeichneGlasKarte(c, x, y, w, 32, bg, border, 16);
            if (filledDot) { p.setStyle(Paint.Style.FILL); p.setColor(fg); c.drawCircle(x + 18, y + 16, 4.5f, p); }
            else { linie.setStrokeWidth(1.6f); linie.setColor(fg); c.drawCircle(x + 18, y + 16, 4.5f, linie); }
            txt.setColor(fg);
            c.drawText(s, x + 30, y + 21, txt);
        }

        private void zeichneGlasKarte(Canvas c, float x, float y, float w, float h, int bg, int border, float r) {
            rf.set(x, y, x + w, y + h);
            p.setStyle(Paint.Style.FILL); p.setColor(bg);
            c.drawRoundRect(rf, r, r, p);
            if ((border >>> 24) > 0) {
                linie.setStrokeWidth(1f); linie.setColor(border);
                c.drawRoundRect(rf, r, r, linie);
            }
        }

        private void drawSoftShadow(Canvas c, float x, float y, float w, float h, float rad, float dyOff, float blur, float totalAlpha) {
            final int steps = 12;
            p.setStyle(Paint.Style.FILL);
            float sumW = 0f;
            for (int i = 0; i < steps; i++) {
                float t = (i + 0.5f) / steps;
                sumW += 0.16f + 0.84f * t * t;
            }
            float targetSum = (float) -Math.log(Math.max(0.05, 1.0 - totalAlpha));
            for (int i = 0; i < steps; i++) {
                float t = (i + 0.5f) / steps;
                float exp = blur * (1f - t);
                float oy = dyOff * (0.45f + 0.55f * (1f - t));
                float wStep = 0.16f + 0.84f * t * t;
                int a = Math.max(2, Math.min(255, Math.round(255f * (targetSum * wStep / sumW))));
                p.setColor(a << 24);
                rf.set(x - exp, y + oy - exp * 0.65f, x + w + exp, y + h + oy + exp);
                c.drawRoundRect(rf, rad + exp * 0.55f, rad + exp * 0.55f, p);
            }
        }

        private void drawBitmapCenterCrop(Canvas c, Bitmap bm, float x, float y, float w, float h, int alpha, Paint targetPaint) {
            int bw = bm.getWidth(), bh = bm.getHeight();
            if (bw <= 0 || bh <= 0) return;
            float sc = Math.max(w / bw, h / bh);
            float sw = w / sc, sh = h / sc;
            int sl = Math.max(0, Math.round((bw - sw) * 0.5f));
            int st = Math.max(0, Math.round((bh - sh) * 0.5f));
            srcR.set(sl, st, Math.min(bw, Math.round(sl + sw)), Math.min(bh, Math.round(st + sh)));
            rf.set(x, y, x + w, y + h);
            targetPaint.setAlpha(alpha);
            c.drawBitmap(bm, srcR, rf, targetPaint);
            targetPaint.setAlpha(255);
        }

        private float zeichneMehrzeilig(Canvas c, String s, Paint pt, float x, float y, float maxW, float lh, int maxZ) {
            if (s == null || s.isEmpty()) return y;
            String rest = s.trim();
            float lastY = y;
            for (int z = 0; z < maxZ && !rest.isEmpty(); z++) {
                lastY = y + z * lh;
                if (z == maxZ - 1 || pt.measureText(rest) <= maxW) {
                    c.drawText(kuerzen(pt, rest, maxW), x, lastY, pt);
                    return lastY;
                }
                int cut = rest.length();
                while (cut > 0 && pt.measureText(rest.substring(0, cut)) > maxW) cut--;
                int sp = rest.lastIndexOf(' ', cut);
                if (sp > 4) cut = sp;
                c.drawText(rest.substring(0, cut).trim(), x, lastY, pt);
                rest = rest.substring(cut).trim();
            }
            return lastY;
        }

        private float aktiverFortschritt(long nowMs) {
            if (status == null) return 0f;
            if (status.pause) return pauseFortschritt;
            float pr = status.fortschritt(nowMs);
            pauseFortschritt = pr;
            return pr;
        }

        private void drawIcon(Canvas c, String name, float x, float y, float size, int color) {
            Path path = iconPath(name);
            if (path != null) {
                p.setStyle(Paint.Style.FILL); p.setColor(color);
                c.save(); c.translate(x, y); c.scale(size / 24f, size / 24f);
                c.drawPath(path, p); c.restore();
                return;
            }
            c.save(); c.translate(x, y); c.scale(size / 24f, size / 24f);
            linie.setColor(color); linie.setStrokeWidth(2.1f);
            iconTmp.reset();
            if ("mdi:close".equals(name)) {
                c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, linie);
                c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, linie);
            } else if ("mdi:chevron-left".equals(name)) {
                iconTmp.moveTo(15, 6); iconTmp.lineTo(9, 12); iconTmp.lineTo(15, 18);
                c.drawPath(iconTmp, linie);
            } else if ("mdi:trash-can-outline".equals(name)) {
                linie.setStrokeWidth(1.8f);
                c.drawLine(5, 6.5f, 19, 6.5f, linie);
                c.drawRoundRect(7, 6.5f, 17, 20, 2, 2, linie);
                c.drawLine(10, 4, 14, 4, linie);
            } else {
                linie.setStrokeWidth(1.8f);
                c.drawRoundRect(4, 5, 20, 19, 2.5f, 2.5f, linie);
                c.drawLine(7.5f, 9.5f, 16.5f, 9.5f, linie);
                c.drawLine(7.5f, 13.5f, 14, 13.5f, linie);
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

        // ---- Touch Handling ----------------------------------------------------------------------------------------

        @Override public boolean onTouchEvent(MotionEvent e) {
            try {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getX(); downY = e.getY();
                } else if (a == MotionEvent.ACTION_UP) {
                    float dxTap = e.getX() - downX, dyTap = e.getY() - downY;
                    if (Math.abs(dxTap) > 70f * skala && Math.abs(dyTap) < 55f * skala) {
                        performClick();
                        ausfuehren(dxTap < 0 ? "naechste" : "zurueck_seg");
                        return true;
                    }
                    if (Math.hypot(dxTap, dyTap) < 34f * skala) {
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
                }
            } catch (RuntimeException ignored) {}
            return true;
        }

        private void ausfuehren(String id) {
            switch (id) {
                case "zurueck": aktionen.zurueck(); break;
                case "stopp":
                    if (status != null && status.demo) aktionen.zurueck();
                    else { aktionen.steuern("stopp"); aktionen.zurueck(); }
                    break;
                case "pause":
                    if (status != null && status.demo) { status.pause = !status.pause; invalidate(); }
                    else aktionen.steuern(status != null && status.pause ? "weiter" : "pause");
                    break;
                case "naechste":
                    if (status != null && status.demo) {
                        String cur = status.seg != null ? status.seg.art : "wetter";
                        String nxt = "meldung".equals(cur) ? "kalender" : ("kalender".equals(cur) ? "wetter" : "meldung");
                        setNews(MorgenDaten.demoArt(nxt, System.currentTimeMillis()));
                        abgleichSichtbarkeit();
                    } else aktionen.steuern("naechste");
                    break;
                case "zurueck_seg":
                    if (status != null && status.demo) {
                        String cur = status.seg != null ? status.seg.art : "wetter";
                        String prv = "wetter".equals(cur) ? "kalender" : ("kalender".equals(cur) ? "meldung" : "wetter");
                        setNews(MorgenDaten.demoArt(prv, System.currentTimeMillis()));
                        abgleichSichtbarkeit();
                    } else aktionen.steuern("zurueck");
                    break;
                case "mehr":
                    if (status != null && status.demo) {
                        setNews(MorgenDaten.demoArt("mehr", System.currentTimeMillis()));
                        abgleichSichtbarkeit();
                    } else aktionen.steuern("mehr");
                    break;
                case "klein": mini = true; abgleichSichtbarkeit(); invalidate(); break;
                case "gross": mini = false; abgleichSichtbarkeit(); invalidate(); break;
                default: break;
            }
        }

        private String kuerzen(Paint pt, String s, float maxW) {
            if (s == null || pt.measureText(s) <= maxW) return s == null ? "" : s;
            int n = s.length();
            while (n > 1 && pt.measureText(s.substring(0, n) + "…") > maxW) n--;
            return s.substring(0, n) + "…";
        }
    }
}
