package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
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
 * Native "Mein Tag" and news reading view on the 963 x 584 dashboard grid:
 * - Phase 1 (morning overview / briefing / minimized bar): live 6-landscape WetterView in the background,
 *   greeting + current weather + info chips (umbrella, ice, appointments, bins, shopping list),
 *   today's 8-hour forecast card (smooth temperature curve + wavy rain bars), and the floating karaoke pill bar.
 * - Phase 2 (news story / breaking news / "Mehr dazu"): full-screen story layout with hardware-blurred article
 *   backdrop, segmented progress bar, sharp article photo, source chip, live word-by-word karaoke highlighting,
 *   animated voice orb + waveform, playback controls, and "Als Nächstes" preview card.
 *
 * Performance: zero allocations in onDraw, no setShadowLayer (2-pass offset shadows), pre-laid-out karaoke words,
 * and WetterView is hidden & paused automatically whenever Phase 2 covers the screen.
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
    private boolean storyVollbild;

    MorgenView(Context ctx, KsTheme theme, Aktionen aktionen, String ort, boolean sternbildNamen) {
        super(ctx);
        this.aktionen = aktionen;
        setBackgroundColor(0xFF0D1720);
        setClickable(true);

        wetterView = new WetterView(ctx, new WetterView.Aktionen() {
            @Override public void zurueck() {}
            @Override public boolean pausiert() { return storyVollbild || aktionen.pausiert(); }
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

    /** Desktop render-test hook: draws the overlay directly at a given time. */
    void zeichneOverlayTest(Canvas c, int w, int h, long nowMs, float animS) {
        overlay.zeichneFuerTest(c, w, h, nowMs, animS);
    }

    private void abgleichSichtbarkeit() {
        boolean neu = overlay.istStoryVollbild();
        if (neu != storyVollbild) {
            storyVollbild = neu;
            wetterView.setVisibility(neu ? GONE : VISIBLE);
            wetterView.pauseGeaendert();
        }
    }

    // ---- word layout for zero-allocation karaoke rendering ---------------------------------------------------------

    private static final class Wort {
        final String text;
        final int start, ende, zeile;
        final float x, y, breite;

        Wort(String text, int start, int ende, int zeile, float x, float y, float breite) {
            this.text = text;
            this.start = start;
            this.ende = ende;
            this.zeile = zeile;
            this.x = x;
            this.y = y;
            this.breite = breite;
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
            if (text == null || text.isEmpty()) {
                zeilen = 0;
                return;
            }
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
                if (cx > 0 && cx + ww > maxW) {
                    zeile++;
                    cx = 0;
                }
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
            this.id = id;
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
        }

        boolean trifft(float x, float y) { return x >= l && x <= r && y >= t && y <= b; }
    }

    // ---- Canvas overlay view ---------------------------------------------------------------------------------------

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
        private float barScrollY, mehrScrollY;
        private String letzterSegKey = "";

        // Article images (active + next), tiny 48x27 blurred backdrop + sharp photo
        private final Map<String, Bitmap> scharfBilder = new HashMap<>();
        private final Map<String, Bitmap> weichBilder = new HashMap<>();

        // Cached shaders & paths
        private final Shader linksScrim = new LinearGradient(0, 0, 640, 0,
            new int[] {0xE60D141C, 0xBF0D141C, 0x000D141C}, new float[] {0f, .52f, 1f}, Shader.TileMode.CLAMP);
        private final Shader storyMorgenBg = new LinearGradient(0, 0, W * .7f, H,
            new int[] {0xFF2B3D5E, 0xFF5A4A6E, 0xFFC2786A}, new float[] {0f, .46f, 1f}, Shader.TileMode.CLAMP);
        private final Shader storyEilBg = new LinearGradient(0, 0, W * .7f, H, 0xFF4A1418, 0xFF1A0D10, Shader.TileMode.CLAMP);
        private final Shader storyNewsBg = new LinearGradient(0, 0, W * .7f, H, 0xFF1C2A3A, 0xFF0D141C, Shader.TileMode.CLAMP);
        private final Shader storyScrimTitel = new LinearGradient(0, 0, W, 0,
            new int[] {0x8C080B0F, 0xD4080B0F, 0xDC080B0F}, new float[] {0f, .55f, 1f}, Shader.TileMode.CLAMP);
        private final Shader progShader = new LinearGradient(0, 0, W, 0, 0xFF6F8CFF, 0xFF9AD0FF, Shader.TileMode.CLAMP);
        private final Shader welleShader = new LinearGradient(0, -16, 0, 16, 0xFF9AD0FF, 0xFF6F8CFF, Shader.TileMode.CLAMP);
        private final Shader regenBarShader = new LinearGradient(0, 0, 0, 40,
            new int[] {0xF25AA8FF, 0x733D7FD6, 0x0A2A5FA8}, new float[] {0f, .55f, 1f}, Shader.TileMode.CLAMP);
        private final Shader orbWarm = new RadialGradient(-7, -9, 36,
            new int[] {0xFFFFF1D6, 0xFFFFB36B, 0xFFD0603F}, new float[] {0f, .55f, 1f}, Shader.TileMode.CLAMP);
        private final Shader orbKalt = new RadialGradient(-7, -9, 36,
            new int[] {0xFFBFE3FF, 0xFF5B7CFF, 0xFF3B2FB8}, new float[] {0f, .55f, 1f}, Shader.TileMode.CLAMP);
        private final Shader orbGlowWarm = new RadialGradient(0, 0, 44,
            new int[] {0x78FFAA6E, 0x24FFAA6E, 0x00FFAA6E}, new float[] {0f, .55f, 1f}, Shader.TileMode.CLAMP);
        private final Shader orbGlowKalt = new RadialGradient(0, 0, 44,
            new int[] {0x786FA0FF, 0x246FA0FF, 0x006FA0FF}, new float[] {0f, .55f, 1f}, Shader.TileMode.CLAMP);

        // Pre-allocated paints & geometry
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bildP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint datumP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grussP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tempP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint zustP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chipP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sumP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint kleinP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mittelP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint boldP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint karaokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint karaokeAktivP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final Rect srcR = new Rect();
        private final Path clipPath = new Path();
        private final Path kurvePath = new Path();
        private final Path wavePath = new Path();
        private final Path iconTmp = new Path();
        private final WortLayout barLayout = new WortLayout();
        private final WortLayout storyLayout = new WortLayout();
        private Shader kurveShader;
        private String kurveKey = "";

        private float skala = 1f, dx, dy, downX, downY;
        private boolean laeuft, angehaengt, sichtbar = true;
        private long startNs, frameNs;
        private int tick;

        OverlayCanvas(Context ctx, KsTheme theme) {
            super(ctx);
            this.theme = theme;
            setClickable(true);

            Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
            Typeface norm = Typeface.create("sans-serif", Typeface.NORMAL);
            Typeface med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);

            linie.setStyle(Paint.Style.STROKE);
            linie.setStrokeCap(Paint.Cap.ROUND);
            linie.setStrokeJoin(Paint.Join.ROUND);

            datumP.setTypeface(norm); datumP.setTextSize(15); datumP.setColor(0xC8FFFFFF);
            grussP.setTypeface(light); grussP.setTextSize(38); grussP.setColor(0xFFECEBE8);
            tempP.setTypeface(light); tempP.setTextSize(54); tempP.setColor(0xFFECEBE8);
            zustP.setTypeface(med); zustP.setTextSize(21); zustP.setColor(0xFFECEBE8);
            chipP.setTypeface(norm); chipP.setTextSize(14); chipP.setColor(0xFFECEBE8);
            sumP.setTypeface(med); sumP.setTextSize(16.5f); sumP.setColor(0xFFECEBE8);
            kleinP.setTypeface(norm); kleinP.setTextSize(13); kleinP.setColor(0xFF9FB0C4);
            mittelP.setTypeface(med); mittelP.setTextSize(15); mittelP.setColor(0xFFECEBE8);
            boldP.setTypeface(bold); boldP.setTextSize(13); boldP.setColor(0xFF9AD0FF);
            karaokeP.setTypeface(norm); karaokeP.setTextSize(15.5f);
            karaokeAktivP.setTypeface(med); karaokeAktivP.setTextSize(15.5f);
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
            if (status.pause && !warPause) {
                pauseFortschritt = status.fortschritt(System.currentTimeMillis());
            }
            warPause = status.pause;

            String segKey = status.seg == null ? "" : status.i + "|" + status.seg.id + "|" + status.seg.art + "|" + status.seg.t0;
            if (!segKey.equals(letzterSegKey)) {
                letzterSegKey = segKey;
                barScrollY = 0;
                mehrScrollY = 0;
            }

            // Prefetch current and next article thumbnails
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

        boolean istStoryVollbild() {
            if (status == null || !status.aktiv || status.seg == null || "aus".equals(status.ansicht)) return false;
            if (mini) return false;
            boolean morgenIntro = "morgen".equals(status.art) && status.seg.id.isEmpty();
            return !morgenIntro;
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            angehaengt = true;
            schleife();
        }

        @Override protected void onDetachedFromWindow() {
            angehaengt = false;
            schleife();
            super.onDetachedFromWindow();
        }

        @Override protected void onWindowVisibilityChanged(int v) {
            super.onWindowVisibilityChanged(v);
            sichtbar = v == VISIBLE;
            schleife();
        }

        @Override public void onVisibilityAggregated(boolean vis) {
            super.onVisibilityAggregated(vis);
            schleife();
        }

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

        private int teiler() {
            if (status != null && status.aktiv && status.seg != null && !status.pause) return 2;   // 30 fps for silky karaoke & waveform
            return 30;                                                                              // 2 fps when idle/paused
        }

        @Override public void doFrame(long ns) {
            if (!laeuft) return;
            if (startNs == 0) startNs = ns;
            int tl = teiler();
            if (tick == 0 || (tick % tl) == 0) {
                frameNs = ns;
                invalidate();
            }
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
                if (istStoryVollbild()) {
                    zeichneStory(c, nowMs, animS);
                } else {
                    zeichneMorgenUebersicht(c, nowMs, animS);
                }
            } catch (RuntimeException ignored) {
                // never crash the host app on a drawing error
            }
            c.restore();
        }

        // ==== PHASE 1: "Mein Tag" overview + hourly card + briefing bar =============================================

        private void zeichneMorgenUebersicht(Canvas c, long nowMs, float animS) {
            boolean hatLeiste = status != null && status.aktiv && status.seg != null && !"aus".equals(status.ansicht);

            // Soft left scrim for effortless text legibility on bright skies/snow
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setShader(linksScrim);
            c.drawRect(0, 0, 640, 290, p);
            p.setShader(null);

            // Back button (58, 50) radius 28
            kreisKnopf(c, "zurueck", 58, 50, 28, false, "mdi:chevron-left", 26);

            // Date & time
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(nowMs);
            int hh = cal.get(Calendar.HOUR_OF_DAY), mm = cal.get(Calendar.MINUTE);
            String datum = WT[cal.get(Calendar.DAY_OF_WEEK) - 1] + ", " + cal.get(Calendar.DAY_OF_MONTH) + ". "
                + MON[cal.get(Calendar.MONTH)] + " · " + String.format(Locale.ROOT, "%02d:%02d", hh, mm);
            datumP.setColor(0xC8FFFFFF);
            c.drawText(datum, 102, 37, datumP);

            // Greeting
            MorgenDaten.MorgenInfo mi = (status != null && "morgen".equals(status.art) && status.morgen != null) ? status.morgen : letztesMorgen;
            String gruss = hh < 11 ? "Guten Morgen" : (hh < 18 ? "Hallo" : "Guten Abend");
            if (mi != null && !mi.name.isEmpty()) gruss += ", " + mi.name;
            schattenText(c, gruss, 102, 79, grussP);

            // Current temperature + condition
            if (wetter != null && wetter.temp != null) {
                String tStr = Math.round(wetter.temp) + "°";
                schattenText(c, tStr, 100, 134, tempP);
                float tw = tempP.measureText(tStr);
                schattenText(c, lage.name(), 100 + tw + 14, 126, zustP);
            }

            // Info chips (umbrella, ice, appointments, bins, shopping list)
            zeichneChips(c, mi, 102, 150, 600);

            // Hourly forecast sheet ("Heute")
            float cardTop = hatLeiste ? 268 : 298;
            float cardH = hatLeiste ? 214 : 262;
            zeichneHeuteKarte(c, 20, cardTop, W - 40, cardH);

            // Bottom floating reading / karaoke pill bar
            if (hatLeiste) {
                zeichneLeiste(c, nowMs, animS);
            }
        }

        private void zeichneChips(Canvas c, MorgenDaten.MorgenInfo mi, float x0, float y0, float maxW) {
            if (mi == null || !mi.hatInhalt()) return;
            List<Object[]> chips = new ArrayList<>();
            if (!mi.schirm.isEmpty()) chips.add(new Object[] {"mdi:umbrella", 0xFFF2C94C, mi.schirm});
            if (mi.glatt) chips.add(new Object[] {"mdi:snowflake-alert", 0xFFBFE3FF, "Glätte möglich"});
            if (!mi.termine.isEmpty()) {
                String t = mi.termine.get(0);
                if (mi.termine.size() > 1) t += " · " + (mi.termine.size() - 1) + " weitere";
                chips.add(new Object[] {"mdi:calendar-today", 0xFF9CC8EE, t});
            }
            if (!mi.muell.isEmpty()) chips.add(new Object[] {"mdi:trash-can-outline", 0xFFF2D35C, mi.muell});
            if (mi.liste > 0) {
                chips.add(new Object[] {"mdi:cart-outline", 0xFF6CC483, mi.liste + (mi.liste == 1 ? " Sache" : " Sachen") + " auf der Liste"});
            }

            float cx = x0, cy = y0, h = 32, gap = 8;
            int zeile = 0;
            for (Object[] ch : chips) {
                String ic = (String) ch[0];
                int col = (Integer) ch[1];
                String txt = (String) ch[2];
                float tw = chipP.measureText(txt);
                float maxChipW = maxW;
                if (tw + 50 > maxChipW) {
                    txt = kuerzen(chipP, txt, maxChipW - 50);
                    tw = chipP.measureText(txt);
                }
                float cw = tw + 48;
                if (cx > x0 && cx + cw > x0 + maxW) {
                    zeile++;
                    if (zeile >= 2) break;
                    cx = x0;
                    cy += h + gap;
                }
                rf.set(cx, cy, cx + cw, cy + h);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xA60D141C);
                c.drawRoundRect(rf, 14, 14, p);
                linie.setStrokeWidth(1f);
                linie.setColor(0x22FFFFFF);
                c.drawRoundRect(rf, 14, 14, linie);

                drawIcon(c, ic, cx + 10, cy + 7, 18, col);
                chipP.setColor(0xFFECEBE8);
                c.drawText(txt, cx + 33, cy + 21, chipP);
                cx += cw + gap;
            }
        }

        private void zeichneHeuteKarte(Canvas c, float x, float y, float w, float h) {
            rf.set(x, y, x + w, y + h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xE8141F2F);
            c.drawRoundRect(rf, 24, 24, p);
            linie.setStrokeWidth(1f);
            linie.setColor(0x1AFFFFFF);
            c.drawRoundRect(rf, 24, 24, linie);

            List<MorgenDaten.Stunde> hs = heute.stunden;
            if (hs == null || hs.isEmpty()) {
                kleinP.setTextSize(15);
                kleinP.setTextAlign(Paint.Align.LEFT);
                kleinP.setColor(0xFF9FB0C4);
                c.drawText("Stundenvorhersage wird geladen …", x + 24, y + 36, kleinP);
                return;
            }

            // Header summary line with highlighted precipitation word
            String sum = heute.zusammenfassung;
            String nw = heute.nassWort;
            float hx = x + 24, hy = y + 30;
            if (!nw.isEmpty() && sum.contains(nw)) {
                int idx = sum.indexOf(nw);
                String vor = sum.substring(0, idx);
                String nach = sum.substring(idx + nw.length());
                sumP.setColor(0xFFECEBE8);
                c.drawText(vor, hx, hy, sumP);
                hx += sumP.measureText(vor);
                sumP.setColor(0xFF6FB3F2);
                c.drawText(nw, hx, hy, sumP);
                hx += sumP.measureText(nw);
                sumP.setColor(0xFFECEBE8);
                c.drawText(nach, hx, hy, sumP);
            } else {
                sumP.setColor(0xFFECEBE8);
                c.drawText(sum, hx, hy, sumP);
            }

            // 8 hourly columns
            int n = Math.min(8, hs.size());
            float cx0 = x + 64, step = n > 1 ? (w - 128) / (n - 1f) : 0;
            int gmin = 99, gmax = -99;
            float mmax = 3.4f;
            for (int i = 0; i < n; i++) {
                MorgenDaten.Stunde st = hs.get(i);
                if (st.temp < gmin) gmin = st.temp;
                if (st.temp > gmax) gmax = st.temp;
                if (st.mm > mmax) mmax = st.mm;
            }
            float span = Math.max(4f, gmax - gmin);
            float curveBot = y + h - 52;
            float curveSpan = Math.max(26f, Math.min(42f, h - 168));
            float barBase = y + h - 20;

            float[] px = new float[n], py = new float[n];
            int[] cols = new int[n];
            float[] stops = new float[n];
            for (int i = 0; i < n; i++) {
                MorgenDaten.Stunde st = hs.get(i);
                px[i] = cx0 + i * step;
                py[i] = curveBot - (st.temp - gmin) / span * curveSpan;
                cols[i] = MorgenDaten.tempFarbe(st.temp);
                stops[i] = n > 1 ? i / (float) (n - 1) : 0f;
            }

            // Wavy rain bars behind curve
            for (int i = 0; i < n; i++) {
                MorgenDaten.Stunde st = hs.get(i);
                boolean nass = MorgenDaten.istNass(st);
                float mm = nass ? st.mm : 0f;
                if (mm >= 0.1f) {
                    float bh = Math.max(4f, Math.min(34f, mm / mmax * 34f));
                    zeichneWellenBalken(c, px[i] - 22, 44, barBase, bh);
                }
            }

            // Smooth cubic temperature curve
            if (n >= 2) {
                kurvePath.reset();
                kurvePath.moveTo(px[0], py[0]);
                for (int i = 0; i < n - 1; i++) {
                    int i0 = Math.max(0, i - 1), i2 = i + 1, i3 = Math.min(n - 1, i + 2);
                    float c1x = px[i] + (px[i2] - px[i0]) / 6f;
                    float c1y = py[i] + (py[i2] - py[i0]) / 6f;
                    float c2x = px[i2] - (px[i3] - px[i]) / 6f;
                    float c2y = py[i2] - (py[i3] - py[i]) / 6f;
                    kurvePath.cubicTo(c1x, c1y, c2x, c2y, px[i2], py[i2]);
                }
                String kKey = Math.round(px[0]) + "|" + Math.round(px[n - 1]) + "|" + cols[0] + "|" + cols[n - 1] + "|" + n;
                if (kurveShader == null || !kKey.equals(kurveKey)) {
                    kurveKey = kKey;
                    kurveShader = new LinearGradient(px[0], 0, px[n - 1], 0, cols, stops, Shader.TileMode.CLAMP);
                }
                linie.setColor(0xFFFFFFFF);
                linie.setStrokeWidth(2.8f);
                linie.setShader(kurveShader);
                c.drawPath(kurvePath, linie);
                linie.setShader(null);
            }

            // Dots + column texts & colourful weather icons
            kleinP.setTextAlign(Paint.Align.CENTER);
            mittelP.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < n; i++) {
                MorgenDaten.Stunde st = hs.get(i);
                float cx = px[i];

                // Hour label
                kleinP.setTextSize(13.5f);
                kleinP.setColor(0xFF9FB0C4);
                c.drawText(st.stunde + " Uhr", cx, y + 56, kleinP);

                // Weather vector icon (28 x 28)
                zeichneWetterIcon(c, MorgenDaten.effCond(st), st.nacht, cx - 14, y + 65, 28);

                // Curve dot & temperature label
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFECEBE8);
                c.drawCircle(cx, py[i], 4f, p);
                mittelP.setTextSize(15.5f);
                mittelP.setColor(0xFFECEBE8);
                c.drawText(st.temp + "°", cx, py[i] - 11, mittelP);

            // Rain probability (%) and mm
            float infoY = curveBot + 20;
                if (st.regenPct != null) {
                    String ptTxt = st.regenPct + " %";
                    kleinP.setTextSize(12.5f);
                    float tw = kleinP.measureText(ptTxt);
                    drawDrop(c, cx - tw * .5f - 6, infoY - 9, 9, 0xFF5AA8FF);
                    kleinP.setColor(0xFF8FC0FF);
                    c.drawText(ptTxt, cx + 5, infoY, kleinP);
                }
                if (MorgenDaten.istNass(st) && st.mm >= 0.1f) {
                    kleinP.setTextSize(11.5f);
                    kleinP.setColor(0xFF8FBAF0);
                    c.drawText(MorgenDaten.mmText(st.mm), cx, y + h - 6, kleinP);
                }
            }
            kleinP.setTextAlign(Paint.Align.LEFT);
            mittelP.setTextAlign(Paint.Align.LEFT);
        }

        private void zeichneWellenBalken(Canvas c, float x, float w, float yb, float h) {
            float yt = yb - h, amp = Math.min(2.8f, h * .25f);
            wavePath.reset();
            wavePath.moveTo(x, h);
            wavePath.lineTo(x, amp * (float) Math.sin(x / 14f));
            for (int k = 1; k <= 10; k++) {
                float xx = x + w * k / 10f;
                wavePath.lineTo(xx, amp * (float) Math.sin(xx / 14f));
            }
            wavePath.lineTo(x + w, h);
            wavePath.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setShader(regenBarShader);
            c.save();
            c.translate(0, yt);
            c.drawPath(wavePath, p);
            c.restore();
            p.setShader(null);
        }

        private void zeichneLeiste(Canvas c, long nowMs, float animS) {
            boolean mrg = "morgen".equals(status.art) && status.seg.id.isEmpty();
            float bx = mrg ? 20 : 120, bw = W - 2 * bx, by = H - 88, bh = 74, rad = 37;
            rf.set(bx, by, bx + bw, by + bh);

            // Soft drop shadow (solid dark offset pill, zero blur cost)
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x55000000);
            c.drawRoundRect(bx, by + 5, bx + bw, by + bh + 6, rad, rad, p);

            // Pill background
            p.setColor(0xEB18202A);
            c.drawRoundRect(rf, rad, rad, p);
            linie.setStrokeWidth(1f);
            linie.setColor(0x1EFFFFFF);
            c.drawRoundRect(rf, rad, rad, linie);

            // Warm or cool pulsing orb
            float cy = by + bh * .5f;
            zeichneOrb(c, bx + 37, cy, 26, "morgen".equals(status.art), animS, status.pause);

            // Right control buttons
            float stopX = bx + bw - 34;
            kreisKnopf(c, "stopp", stopX, cy, 22, false, "mdi:close", 21);
            float pauseX;
            if (mrg) {
                float nextX = stopX - 54;
                kreisKnopf(c, "naechste", nextX, cy, 22, false, "mdi:skip-next", 22);
                pauseX = nextX - 54;
            } else {
                pauseX = stopX - 54;
            }
            kreisKnopf(c, "pause", pauseX, cy, 22, false, status.pause ? "mdi:play" : "mdi:pause", 22);

            float textL = bx + 76;
            float textR = pauseX - 34;

            if (mrg) {
                // 2-line karaoke briefing text with smooth auto-scroll
                float fs = 15.5f, lh = 21f;
                karaokeP.setTextSize(fs);
                karaokeAktivP.setTextSize(fs);
                barLayout.bauen(status.seg.text, karaokeP, Math.max(120, textR - textL), lh);
                float prog = aktiverFortschritt(nowMs);
                float boxTop = by + 13, boxH = 46;
                barScrollY = zeichneKaraoke(c, barLayout, status.seg.text, prog, status.karaoke, textL, boxTop, textR - textL, boxH, barScrollY);
            } else {
                // Minimized news bar: tap body to re-expand
                hits.add(new Hit("gross", bx, by, pauseX - 28, by + bh));
                MorgenDaten.Meldung it = status.aktuelleMeldung();
                Bitmap bm = it != null ? scharfBilder.get(it.bild) : null;
                if (bm != null && !bm.isRecycled()) {
                    zeichneBildGerundet(c, bm, textL, cy - 22, 76, 44, 10);
                    textL += 88;
                }
                float welleW = 80;
                float titleW = Math.max(80, textR - textL - welleW - 12);
                String name = status.sprecherName.isEmpty() ? "Wird vorgelesen" : status.sprecherName + " liest vor";
                kleinP.setTextSize(12);
                kleinP.setColor(0xFF9AD0FF);
                c.drawText(name + " · " + (status.i + 1) + " von " + Math.max(1, status.n), textL, by + 29, kleinP);
                mittelP.setTextSize(14.5f);
                mittelP.setColor(0xFFECEBE8);
                String titel = it != null ? it.t : status.seg.text;
                c.drawText(kuerzen(mittelP, titel, titleW), textL, by + 50, mittelP);
                zeichneWelle(c, textR - welleW, cy, 10, 26, animS, status.pause);
            }

            // Bottom edge progress bar clipped inside pill
            float segProg = status.n > 0 ? Math.max(0f, Math.min(1f, (status.i + aktiverFortschritt(nowMs)) / (float) status.n)) : 0f;
            clipPath.reset();
            clipPath.addRoundRect(rf, rad, rad, Path.Direction.CW);
            c.save();
            c.clipPath(clipPath);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setShader(progShader);
            c.drawRect(bx, by + bh - 3.5f, bx + bw * segProg, by + bh, p);
            p.setShader(null);
            c.restore();
        }

        // ==== PHASE 2: Full-screen News Story / Karaoke View ========================================================

        private void zeichneStory(Canvas c, long nowMs, float animS) {
            MorgenDaten.Segment seg = status.seg;
            MorgenDaten.Meldung it = status.aktuelleMeldung();
            MorgenDaten.Meldung nach = status.naechsteMeldung();
            boolean mehr = "mehr".equals(seg.art);
            boolean nurText = "intro".equals(seg.art) || "text".equals(seg.art) || it == null;
            float prog = aktiverFortschritt(nowMs);

            // 1. Backdrop: dark base + hardware-blurred article photo OR mood gradient
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0B0F13);
            c.drawRect(0, 0, W, H, p);

            Bitmap blur = it != null ? weichBilder.get(it.bild) : null;
            if (blur != null && !blur.isRecycled()) {
                bildP.setAlpha(142);
                srcR.set(0, 0, blur.getWidth(), blur.getHeight());
                rf.set(-24, -24, W + 24, H + 24);
                c.drawBitmap(blur, srcR, rf, bildP);
                bildP.setAlpha(255);
            } else {
                p.setColor(0xFFFFFFFF);
                p.setShader("morgen".equals(status.art) ? storyMorgenBg : ("eil".equals(status.art) ? storyEilBg : storyNewsBg));
                c.drawRect(0, 0, W, H, p);
                p.setShader(null);
            }

            // Readability scrim
            if (mehr) {
                p.setColor(0xB8080B0F);
                c.drawRect(0, 0, W, H, p);
            } else {
                p.setColor(0xFFFFFFFF);
                p.setShader(storyScrimTitel);
                c.drawRect(0, 0, W, H, p);
                p.setShader(null);
            }

            // 2. Top segmented progress bar
            int segN = Math.max(1, status.n);
            float segGap = 5f, totalW = W - 60;
            float segW = (totalW - segGap * (segN - 1)) / segN;
            for (int k = 0; k < segN; k++) {
                float sx = 30 + k * (segW + segGap);
                p.setColor(0x38FFFFFF);
                rf.set(sx, 18, sx + segW, 22);
                c.drawRoundRect(rf, 2, 2, p);
                float fill = k < status.i ? 1f : (k == status.i ? prog : 0f);
                if (fill > 0.005f) {
                    p.setColor(0xFFFFFFFF);
                    rf.set(sx, 18, sx + segW * fill, 22);
                    c.drawRoundRect(rf, 2, 2, p);
                }
            }

            // 3. Top header row (minimize, category, close/stop)
            kreisKnopf(c, "klein", 52, 56, 22, false, "mdi:chevron-down", 22);
            drawIcon(c, "eil".equals(status.art) ? "mdi:alert-decagram" : "mdi:newspaper-variant-outline", 86, 47, 18, 0xCCFFFFFF);
            String kopf = "eil".equals(status.art) ? "Eilmeldung" : ("morgen".equals(status.art) ? "Mein Tag" : "Nachrichten");
            if (it != null) {
                int idx = status.aktuelleMeldungIndex();
                if (idx >= 0) kopf += " · Meldung " + (idx + 1) + " von " + status.items.size();
            }
            if (mehr) kopf += " · mehr dazu";
            datumP.setColor(0xCCFFFFFF);
            c.drawText(kopf, 111, 61, datumP);
            kreisKnopf(c, "stopp", W - 56, 56, 25, false, "mdi:close", 23);

            // 4. Main body (3 modes)
            if (nurText) {
                float fs = seg.text.length() > 160 ? 26f : 32f;
                karaokeP.setTextSize(fs);
                karaokeAktivP.setTextSize(fs);
                storyLayout.bauen(seg.text, karaokeP, W - 120, fs * 1.38f);
                mehrScrollY = zeichneKaraoke(c, storyLayout, seg.text, prog, status.karaoke, 60, 146, W - 120, 320, mehrScrollY);
            } else if (mehr) {
                Bitmap foto = scharfBilder.get(it.bild);
                float tx = 30;
                if (foto != null && !foto.isRecycled()) {
                    zeichneBildGerundet(c, foto, 30, 100, 170, 96, 14);
                    tx = 218;
                }
                zeichneQuellenZeile(c, it, tx, 102, nowMs);
                // 2-line headline above body
                mittelP.setTextSize(19);
                mittelP.setColor(0xFFECEBE8);
                zeichneMehrzeilig(c, it.t, mittelP, tx, 148, W - 30 - tx, 26, 2);

                // Karaoke body text
                float fs = 23f;
                karaokeP.setTextSize(fs);
                karaokeAktivP.setTextSize(fs);
                storyLayout.bauen(seg.text, karaokeP, W - 90, fs * 1.44f);
                mehrScrollY = zeichneKaraoke(c, storyLayout, seg.text, prog, status.karaoke, 30, 222, W - 90, 246, mehrScrollY);
            } else {
                // Headline view: big article photo left + karaoke headline right
                Bitmap foto = scharfBilder.get(it.bild);
                boolean mitFoto = foto != null && !foto.isRecycled();
                boolean hatBildUrl = !it.bild.isEmpty();
                if (mitFoto) {
                    p.setColor(0x50000000);
                    c.drawRoundRect(30, 112, 440, 343, 22, 22, p);
                    zeichneBildGerundet(c, foto, 30, 104, 410, 231, 22);
                } else if (hatBildUrl) {
                    // Clean glass placeholder while thumbnail downloads
                    rf.set(30, 104, 440, 335);
                    p.setColor(0x18FFFFFF);
                    c.drawRoundRect(rf, 22, 22, p);
                    drawIcon(c, "mdi:newspaper-variant-outline", 215, 200, 40, 0x44FFFFFF);
                }
                boolean linksBox = mitFoto || hatBildUrl;
                zeichneQuellenZeile(c, it, 30, linksBox ? 350 : 108, nowMs);

                float rx = linksBox ? 468 : 30;
                float ry = linksBox ? 106 : 148;
                float rw = W - 34 - rx;
                boldP.setColor("eil".equals(status.art) ? 0xFFFF8A80 : 0xFF9AD0FF);
                c.drawText("eil".equals(status.art) ? "EILMELDUNG" : "SCHLAGZEILE", rx, ry + 12, boldP);

                float fs = seg.text.length() > 140 ? 22f : (seg.text.length() > 95 ? 25.5f : 29.5f);
                karaokeP.setTextSize(fs);
                karaokeAktivP.setTextSize(fs);
                storyLayout.bauen(seg.text, karaokeP, rw, fs * 1.32f);
                mehrScrollY = zeichneKaraoke(c, storyLayout, seg.text, prog, status.karaoke, rx, ry + 26, rw, 330, mehrScrollY);
            }

            // 5. Bottom dock: Orb + speaker/waveform + transport controls + "Mehr dazu" + "Als Nächstes"
            float dyC = H - 54;
            zeichneOrb(c, 60, dyC, 30, "morgen".equals(status.art), animS, status.pause);
            String spName = status.sprecherName.isEmpty() ? "Wird vorgelesen" : status.sprecherName + " liest vor";
            mittelP.setTextSize(15);
            mittelP.setColor(0xFFECEBE8);
            c.drawText(spName, 104, dyC - 8, mittelP);
            zeichneWelle(c, 104, dyC + 14, 18, 26, animS, status.pause);

            // Right-to-left layout of dock items so nothing ever overlaps
            float curR = W - 30;
            if (nach != null && !mehr) {
                float cardW = 212, cardH = 54, cx = curR - cardW, cy = dyC - cardH * .5f;
                rf.set(cx, cy, cx + cardW, cy + cardH);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x18FFFFFF);
                c.drawRoundRect(rf, 14, 14, p);
                hits.add(new Hit("naechste", cx, cy, cx + cardW, cy + cardH));

                float nxTextX = cx + 10;
                Bitmap nb = scharfBilder.get(nach.bild);
                if (nb != null && !nb.isRecycled()) {
                    zeichneBildGerundet(c, nb, cx + 8, cy + 9, 60, 36, 7);
                    nxTextX = cx + 76;
                }
                kleinP.setTextSize(11);
                kleinP.setColor(0x90FFFFFF);
                c.drawText("Als Nächstes", nxTextX, cy + 22, kleinP);
                mittelP.setTextSize(12.5f);
                mittelP.setColor(0xFFECEBE8);
                c.drawText(kuerzen(mittelP, nach.t, cx + cardW - nxTextX - 10), nxTextX, cy + 40, mittelP);
                curR = cx - 14;
            }

            if (it != null && !mehr && !it.x.isEmpty()) {
                float pw = 128, ph = 44, px = curR - pw, py = dyC - ph * .5f;
                rf.set(px, py, px + pw, py + ph);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x22FFFFFF);
                c.drawRoundRect(rf, 18, 18, p);
                linie.setStrokeWidth(1f);
                linie.setColor(0x26FFFFFF);
                c.drawRoundRect(rf, 18, 18, linie);
                drawIcon(c, "mdi:text-box-outline", px + 14, py + 13, 18, 0xFFECEBE8);
                mittelP.setTextSize(14.5f);
                mittelP.setColor(0xFFECEBE8);
                c.drawText("Mehr dazu", px + 38, py + 27, mittelP);
                hits.add(new Hit("mehr", px, py, px + pw, py + ph));
                curR = px - 14;
            }

            // Skip-next, Play/Pause (white highlight), Skip-previous
            float nextCx = curR - 25;
            kreisKnopf(c, "naechste", nextCx, dyC, 25, false, "mdi:skip-next", 24);
            float pauseCx = nextCx - 66;
            kreisKnopf(c, "pause", pauseCx, dyC, 30, true, status.pause ? "mdi:play" : "mdi:pause", 28);
            float prevCx = pauseCx - 66;
            kreisKnopf(c, "zurueck_seg", prevCx, dyC, 25, false, "mdi:skip-previous", 24);
        }

        private void zeichneQuellenZeile(Canvas c, MorgenDaten.Meldung it, float x, float y, long nowMs) {
            String qn = status.quelleName(it.q);
            int qc = status.quelleFarbe(it.q);
            boldP.setTextSize(12);
            float qw = boldP.measureText(qn) + 16;
            rf.set(x, y, x + qw, y + 22);
            p.setStyle(Paint.Style.FILL);
            p.setColor(WetterLage.alpha(qc, .20f));
            c.drawRoundRect(rf, 6, 6, p);
            boldP.setColor(qc);
            c.drawText(qn, x + 8, y + 15, boldP);

            String vor = MorgenDaten.vorText(it.z, nowMs);
            if (!vor.isEmpty()) {
                kleinP.setTextSize(13);
                kleinP.setColor(0xB3FFFFFF);
                c.drawText(vor, x + qw + 10, y + 15, kleinP);
            }
        }

        private float aktiverFortschritt(long nowMs) {
            if (status == null) return 0f;
            if (status.pause) return pauseFortschritt;
            float p = status.fortschritt(nowMs);
            pauseFortschritt = p;
            return p;
        }

        /** Draws pre-laid-out karaoke words with spoken/active/upcoming tint and gentle vertical auto-scroll. */
        private float zeichneKaraoke(Canvas c, WortLayout layout, String text, float prog, boolean karaoke,
                                     float x, float y, float w, float h, float scrollY) {
            if (layout.woerter.isEmpty()) return 0;
            float curChar = prog * (text == null ? 0 : text.length());
            int aktivIdx = -1;
            int aktiveZeile = 0;
            if (karaoke && prog > 0 && prog < 0.995f) {
                for (int i = 0; i < layout.woerter.size(); i++) {
                    Wort wo = layout.woerter.get(i);
                    if (wo.start <= curChar) {
                        aktivIdx = i;
                        aktiveZeile = wo.zeile;
                    } else break;
                }
            } else if (karaoke && prog >= 0.995f) {
                aktiveZeile = Math.max(0, layout.zeilen - 1);
            }
            float totalH = layout.zeilen * layout.zeilenHoehe;
            float maxScroll = Math.max(0, totalH - h);
            float zielScroll = 0;
            if (maxScroll > 0) {
                float activeY = aktiveZeile * layout.zeilenHoehe;
                zielScroll = Math.max(0, Math.min(maxScroll, activeY - h * .38f));
            }
            float neuScroll = scrollY + (zielScroll - scrollY) * .22f;
            if (Math.abs(zielScroll - neuScroll) < .5f) neuScroll = zielScroll;

            c.save();
            c.clipRect(x - 4, y - 4, x + w + 4, y + h + 4);
            float baseAscent = -karaokeP.ascent();
            for (int i = 0; i < layout.woerter.size(); i++) {
                Wort wo = layout.woerter.get(i);
                float wy = y + wo.y - neuScroll + baseAscent;
                if (wy < y - layout.zeilenHoehe || wy > y + h + layout.zeilenHoehe) continue;
                if (!karaoke) {
                    karaokeP.setColor(0xEBFFFFFF);
                    c.drawText(wo.text, x + wo.x, wy, karaokeP);
                } else if (i == aktivIdx) {
                    // Subtle luminous pill behind the spoken word (zero GPU blur cost)
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x1EFFFFFF);
                    rf.set(x + wo.x - 3, y + wo.y - neuScroll, x + wo.x + wo.breite + 3, y + wo.y - neuScroll + layout.zeilenHoehe - 2);
                    c.drawRoundRect(rf, 6, 6, p);
                    karaokeAktivP.setColor(0xFFFFFFFF);
                    c.drawText(wo.text, x + wo.x, wy, karaokeAktivP);
                } else if (wo.ende <= curChar) {
                    karaokeP.setColor(0xDEFFFFFF);
                    c.drawText(wo.text, x + wo.x, wy, karaokeP);
                } else {
                    karaokeP.setColor(0x5CFFFFFF);
                    c.drawText(wo.text, x + wo.x, wy, karaokeP);
                }
            }
            c.restore();
            return neuScroll;
        }

        // ==== decorative & interactive primitives ===================================================================

        private void zeichneOrb(Canvas c, float cx, float cy, float r, boolean warm, float t, boolean paused) {
            float puls = paused ? 1f : (.95f + .055f * (float) Math.sin(t * 2.1));
            c.save();
            c.translate(cx, cy);
            c.scale(puls, puls);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setShader(warm ? orbGlowWarm : orbGlowKalt);
            c.drawCircle(0, 0, r * 1.55f, p);
            p.setShader(warm ? orbWarm : orbKalt);
            c.drawCircle(0, 0, r, p);
            p.setShader(null);
            c.restore();
        }

        private void zeichneWelle(Canvas c, float x, float cy, int n, float maxH, float t, boolean paused) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            c.save();
            c.translate(0, cy);
            p.setShader(welleShader);
            for (int k = 0; k < n; k++) {
                float ph = paused ? (k * 0.7f) : (t * (2.6f + ((k * 37) % 17) * 0.14f) + k * 0.65f);
                float h = maxH * (.24f + .76f * (float) (.5 + .5 * Math.sin(ph)));
                float bx = x + k * 7f;
                rf.set(bx, -h * .5f, bx + 4f, h * .5f);
                c.drawRoundRect(rf, 2, 2, p);
            }
            p.setShader(null);
            c.restore();
        }

        private void kreisKnopf(Canvas c, String id, float cx, float cy, float rad, boolean hell, String icon, float icSize) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(hell ? 0xFFFFFFFF : 0x22FFFFFF);
            c.drawCircle(cx, cy, rad, p);
            if (!hell) {
                linie.setStrokeWidth(1f);
                linie.setColor(0x1EFFFFFF);
                c.drawCircle(cx, cy, rad, linie);
            }
            drawIcon(c, icon, cx - icSize * .5f, cy - icSize * .5f, icSize, hell ? 0xFF11161E : 0xFFECEBE8);
            float pad = Math.max(rad, 28f);   // generous touch target
            hits.add(new Hit(id, cx - pad, cy - pad, cx + pad, cy + pad));
        }

        private void zeichneBildGerundet(Canvas c, Bitmap bm, float x, float y, float w, float h, float rad) {
            int bw = bm.getWidth(), bh = bm.getHeight();
            if (bw <= 0 || bh <= 0) return;
            float targetRatio = w / h, bmRatio = bw / (float) bh;
            int sx = 0, sy = 0, sw = bw, sh = bh;
            if (bmRatio > targetRatio) {
                sw = Math.round(bh * targetRatio);
                sx = (bw - sw) / 2;
            } else {
                sh = Math.round(bw / targetRatio);
                sy = (bh - sh) / 2;
            }
            srcR.set(sx, sy, sx + sw, sy + sh);
            rf.set(x, y, x + w, y + h);
            clipPath.reset();
            clipPath.addRoundRect(rf, rad, rad, Path.Direction.CW);
            c.save();
            c.clipPath(clipPath);
            c.drawBitmap(bm, srcR, rf, bildP);
            c.restore();
            linie.setStrokeWidth(1f);
            linie.setColor(0x22FFFFFF);
            c.drawRoundRect(rf, rad, rad, linie);
        }

        private void schattenText(Canvas c, String s, float x, float y, Paint pt) {
            int orig = pt.getColor();
            pt.setColor(0x70000000);
            c.drawText(s, x, y + 2, pt);
            pt.setColor(orig);
            c.drawText(s, x, y, pt);
        }

        private void zeichneMehrzeilig(Canvas c, String s, Paint pt, float x, float y, float maxW, float lh, int maxZ) {
            if (s == null || s.isEmpty()) return;
            String rest = s.trim();
            for (int z = 0; z < maxZ && !rest.isEmpty(); z++) {
                if (z == maxZ - 1 || pt.measureText(rest) <= maxW) {
                    c.drawText(kuerzen(pt, rest, maxW), x, y + z * lh, pt);
                    return;
                }
                int cut = rest.length();
                while (cut > 0 && pt.measureText(rest.substring(0, cut)) > maxW) cut--;
                int sp = rest.lastIndexOf(' ', cut);
                if (sp > 4) cut = sp;
                c.drawText(rest.substring(0, cut).trim(), x, y + z * lh, pt);
                rest = rest.substring(cut).trim();
            }
        }

        // ---- crisp vector icons (KsTheme.iconPath first, custom vector fallback) -----------------------------------

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
            drawFallbackIcon(c, name, x, y, size, color);
        }

        private Path iconPath(String name) {
            if (theme == null || name == null) return null;
            if (iconCache.containsKey(name)) return iconCache.get(name);
            Path found = null;
            try { found = theme.iconPath(name); } catch (RuntimeException | LinkageError ignored) {}
            iconCache.put(name, found);
            return found;
        }

        private void drawFallbackIcon(Canvas c, String name, float x, float y, float s, int col) {
            c.save();
            c.translate(x, y);
            c.scale(s / 24f, s / 24f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            linie.setColor(col);
            linie.setStrokeWidth(2.2f);
            iconTmp.reset();
            switch (name) {
                case "mdi:chevron-left":
                    iconTmp.moveTo(15, 6); iconTmp.lineTo(9, 12); iconTmp.lineTo(15, 18);
                    c.drawPath(iconTmp, linie);
                    break;
                case "mdi:chevron-down":
                    iconTmp.moveTo(6, 9); iconTmp.lineTo(12, 15); iconTmp.lineTo(18, 9);
                    c.drawPath(iconTmp, linie);
                    break;
                case "mdi:close":
                    c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, linie);
                    c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, linie);
                    break;
                case "mdi:play":
                    iconTmp.moveTo(8, 5.5f); iconTmp.lineTo(19, 12); iconTmp.lineTo(8, 18.5f); iconTmp.close();
                    c.drawPath(iconTmp, p);
                    break;
                case "mdi:pause":
                    c.drawRoundRect(6.5f, 5.5f, 10.3f, 18.5f, 1.2f, 1.2f, p);
                    c.drawRoundRect(13.7f, 5.5f, 17.5f, 18.5f, 1.2f, 1.2f, p);
                    break;
                case "mdi:skip-next":
                    iconTmp.moveTo(6, 6); iconTmp.lineTo(14.5f, 12); iconTmp.lineTo(6, 18); iconTmp.close();
                    c.drawPath(iconTmp, p);
                    c.drawRoundRect(16, 6, 18.5f, 18, 1, 1, p);
                    break;
                case "mdi:skip-previous":
                    iconTmp.moveTo(18, 6); iconTmp.lineTo(9.5f, 12); iconTmp.lineTo(18, 18); iconTmp.close();
                    c.drawPath(iconTmp, p);
                    c.drawRoundRect(5.5f, 6, 8, 18, 1, 1, p);
                    break;
                case "mdi:umbrella":
                    iconTmp.moveTo(3, 13); iconTmp.cubicTo(4, 5, 20, 5, 21, 13); iconTmp.close();
                    c.drawPath(iconTmp, p);
                    c.drawLine(12, 13, 12, 19, linie);
                    c.drawLine(12, 19, 9.5f, 19, linie);
                    break;
                case "mdi:calendar-today":
                    linie.setStrokeWidth(1.8f);
                    c.drawRoundRect(4, 5, 20, 20, 2.5f, 2.5f, linie);
                    c.drawLine(4, 9.5f, 20, 9.5f, linie);
                    c.drawLine(8, 3, 8, 6.5f, linie);
                    c.drawLine(16, 3, 16, 6.5f, linie);
                    break;
                case "mdi:trash-can-outline":
                    linie.setStrokeWidth(1.8f);
                    c.drawLine(5, 6.5f, 19, 6.5f, linie);
                    c.drawRoundRect(7, 6.5f, 17, 20, 2, 2, linie);
                    c.drawLine(10, 4, 14, 4, linie);
                    break;
                case "mdi:cart-outline":
                    linie.setStrokeWidth(1.8f);
                    iconTmp.moveTo(3, 5); iconTmp.lineTo(6, 5); iconTmp.lineTo(8.5f, 15); iconTmp.lineTo(18.5f, 15); iconTmp.lineTo(20.5f, 8); iconTmp.lineTo(7, 8);
                    c.drawPath(iconTmp, linie);
                    c.drawCircle(9.5f, 18.5f, 1.6f, p);
                    c.drawCircle(17.5f, 18.5f, 1.6f, p);
                    break;
                default:
                    linie.setStrokeWidth(1.8f);
                    c.drawRoundRect(4, 5, 20, 19, 2.5f, 2.5f, linie);
                    c.drawLine(7.5f, 9.5f, 16.5f, 9.5f, linie);
                    c.drawLine(7.5f, 13.5f, 14, 13.5f, linie);
                    break;
            }
            c.restore();
        }

        /** Colourful miniature weather icons for the 8-hour strip (matches wetter.js wIcon). */
        private void zeichneWetterIcon(Canvas c, String cond, boolean nacht, float x, float y, float sz) {
            c.save();
            c.translate(x, y);
            c.scale(sz / 32f, sz / 32f);
            p.setStyle(Paint.Style.FILL);
            if ("sunny".equals(cond) || "clear-night".equals(cond)) {
                if (nacht || "clear-night".equals(cond)) drawMoon(c, 16, 16, 8.5f);
                else drawSun(c, 16, 16, 6.8f);
            } else if ("partlycloudy".equals(cond)) {
                if (nacht) drawMoon(c, 11, 11, 6.2f);
                else drawSun(c, 11.5f, 11, 5.2f);
                drawCloud(c, 0, 0, 1f, 0xFFEAF0F7);
            } else if ("cloudy".equals(cond) || "fog".equals(cond)) {
                drawCloud(c, 5, -4, .75f, 0xB3BAC7D6);
                drawCloud(c, 0, 0, 1f, 0xFFEAF0F7);
                if ("fog".equals(cond)) {
                    linie.setStrokeWidth(2.1f); linie.setColor(0xFFC9D3DC);
                    c.drawLine(7, 27, 25, 27, linie);
                    c.drawLine(10, 30.5f, 22, 30.5f, linie);
                }
            } else if ("pouring".equals(cond)) {
                drawCloud(c, 0, 0, 1f, 0xFFB8C5D4);
                drawRainLines(c, 4, 0xFF4FA0FF);
            } else if ("snowy".equals(cond)) {
                drawCloud(c, 0, 0, 1f, 0xFFEAF0F7);
                p.setColor(0xFFEEF4FB);
                c.drawCircle(11, 27.5f, 1.8f, p); c.drawCircle(17.5f, 29.5f, 1.8f, p); c.drawCircle(24, 27.5f, 1.8f, p);
            } else if ("snowy-rainy".equals(cond)) {
                drawCloud(c, 0, 0, 1f, 0xFFEAF0F7);
                drawRainLines(c, 2, 0xFF4FA0FF);
                p.setColor(0xFFEEF4FB); c.drawCircle(17.5f, 29, 1.8f, p);
            } else if ("hail".equals(cond)) {
                drawCloud(c, 0, 0, 1f, 0xFFB8C5D4);
                p.setColor(0xFFE8F1FA);
                c.drawCircle(11, 28, 2.1f, p); c.drawCircle(17, 30, 2.1f, p); c.drawCircle(23, 28, 2.1f, p);
            } else if ("lightning".equals(cond) || "lightning-rainy".equals(cond)) {
                drawCloud(c, 0, 0, 1f, 0xFFB8C5D4);
                iconTmp.reset();
                iconTmp.moveTo(17.5f, 21); iconTmp.lineTo(13.5f, 27); iconTmp.lineTo(16.7f, 27);
                iconTmp.lineTo(14.7f, 32); iconTmp.lineTo(20.2f, 25); iconTmp.lineTo(16.9f, 25); iconTmp.close();
                p.setColor(0xFFFFD34D);
                c.drawPath(iconTmp, p);
                if ("lightning-rainy".equals(cond)) {
                    linie.setStrokeWidth(2.1f); linie.setColor(0xFF4FA0FF);
                    c.drawLine(11.6f, 26.5f, 9.4f, 30.5f, linie);
                    c.drawLine(25.6f, 26.5f, 23.4f, 30.5f, linie);
                }
            } else if ("windy".equals(cond) || "windy-variant".equals(cond)) {
                if ("windy-variant".equals(cond)) drawCloud(c, 0, -2, .9f, 0xCCEAF0F7);
                linie.setStrokeWidth(2.2f); linie.setColor(0xFFDFE8F2);
                c.drawLine(4, 13, 19, 13, linie);
                c.drawLine(4, 18.5f, 24, 18.5f, linie);
                c.drawLine(4, 24, 15, 24, linie);
            } else {
                drawCloud(c, 0, 0, 1f, 0xFFEAF0F7);
                drawRainLines(c, 3, 0xFF4FA0FF);
            }
            c.restore();
        }

        private void drawSun(Canvas c, float cx, float cy, float r) {
            p.setColor(0xFFFFC93C);
            c.drawCircle(cx, cy, r, p);
            linie.setStrokeWidth(2f); linie.setColor(0xFFFFC93C);
            for (int a = 0; a < 360; a += 45) {
                double rad = a * Math.PI / 180.0;
                float co = (float) Math.cos(rad), si = (float) Math.sin(rad);
                c.drawLine(cx + co * (r + 1.8f), cy + si * (r + 1.8f), cx + co * (r + 4.2f), cy + si * (r + 4.2f), linie);
            }
        }

        private void drawMoon(Canvas c, float cx, float cy, float r) {
            p.setColor(0xFFF3E7B0);
            c.drawCircle(cx, cy, r, p);
            p.setColor(0xFF141F2F);
            c.drawCircle(cx + r * .44f, cy - r * .24f, r * .82f, p);
        }

        private void drawCloud(Canvas c, float ox, float oy, float s, int col) {
            c.save();
            c.translate(ox, oy);
            c.scale(s, s);
            p.setColor(col);
            c.drawCircle(10.5f, 18.2f, 5.3f, p);
            c.drawCircle(16.2f, 15.2f, 6.8f, p);
            c.drawCircle(23.5f, 17.8f, 5.6f, p);
            c.drawRect(10.5f, 18.2f, 23.5f, 23.5f, p);
            c.restore();
        }

        private void drawRainLines(Canvas c, int n, int col) {
            linie.setStrokeWidth(2.2f); linie.setColor(col);
            for (int i = 0; i < n; i++) {
                float lx = 11 + i * (13f / Math.max(1, n - 1));
                c.drawLine(lx + 1.4f, 26.5f, lx - .6f, 30.5f, linie);
            }
        }

        private void drawDrop(Canvas c, float cx, float y, float h, int col) {
            iconTmp.reset();
            float r = h * .34f;
            iconTmp.moveTo(cx, y);
            iconTmp.cubicTo(cx - r * 1.4f, y + h * .55f, cx - r, y + h, cx, y + h);
            iconTmp.cubicTo(cx + r, y + h, cx + r * 1.4f, y + h * .55f, cx, y);
            iconTmp.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            c.drawPath(iconTmp, p);
        }

        // ---- touch handling ----------------------------------------------------------------------------------------

        @Override public boolean onTouchEvent(MotionEvent e) {
            try {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                    downY = e.getY();
                } else if (a == MotionEvent.ACTION_UP && Math.hypot(e.getX() - downX, e.getY() - downY) < 28) {
                    float vx = (e.getX() - dx) / skala;
                    float vy = (e.getY() - dy) / skala;
                    // Walk in reverse so topmost interactive button wins
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
            switch (id) {
                case "zurueck":
                    aktionen.zurueck();
                    break;
                case "klein":
                    mini = true;
                    abgleichSichtbarkeit();
                    invalidate();
                    break;
                case "gross":
                    mini = false;
                    abgleichSichtbarkeit();
                    invalidate();
                    break;
                case "pause":
                    if (status != null && status.demo) {
                        status.pause = !status.pause;
                        if (status.pause) pauseFortschritt = status.fortschritt(System.currentTimeMillis());
                        else if (status.seg != null) status.seg.t0 = System.currentTimeMillis() / 1000.0 - pauseFortschritt * status.seg.dauer;
                        schleife();
                        invalidate();
                    } else {
                        aktionen.steuern(status != null && status.pause ? "weiter" : "pause");
                    }
                    break;
                case "stopp":
                    if (status != null && status.demo) aktionen.zurueck();
                    else {
                        aktionen.steuern("stopp");
                        aktionen.zurueck();
                    }
                    break;
                case "naechste":
                case "zurueck_seg":
                case "mehr":
                    if (status != null && status.demo) {
                        demoWeiter(id);
                    } else {
                        aktionen.steuern("zurueck_seg".equals(id) ? "zurueck" : id);
                    }
                    break;
                default:
                    if ("zurueck".equals(id)) aktionen.zurueck();
                    break;
            }
        }

        private void demoWeiter(String id) {
            long now = System.currentTimeMillis();
            if ("mehr".equals(id)) {
                setNews(MorgenDaten.demo(true, true, now));
            } else if ("naechste".equals(id)) {
                boolean warStory = istStoryVollbild();
                setNews(MorgenDaten.demo(!warStory, false, now));
            } else {
                setNews(MorgenDaten.demo(false, false, now));
            }
            abgleichSichtbarkeit();
        }
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
