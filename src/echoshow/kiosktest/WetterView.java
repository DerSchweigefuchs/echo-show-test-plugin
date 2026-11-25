package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live weather scene "Meer" of the dashboard's weather page, drawn natively on a 963 x 584 grid: sky by the sun's real
 * altitude (night, blue hour, evening / morning glow, day, blended continuously), sun and moon on their real paths, stars
 * and constellations at their real place, drifting clouds, sea with a glitter path, lighthouse island, rocking boat,
 * beach and dune, rain / snow / hail / fog as particles.
 *
 * Load: everything static (land and sea, each cloud row in a day and a night version, the moon, the header) is drawn
 * once into bitmaps and only redrawn when the weather or the light changes, on a background thread. A frame draws the
 * sky gradient, those bitmaps and the moving parts. The loop runs at 24 or 30 fps and stops completely while the view
 * is not visible, the screensaver is up or the screen is off. Main thread only, except the bitmap builds.
 */
final class WetterView extends View implements Choreographer.FrameCallback {
    static final float W = 963, H = 584, HZ = Himmel.HORIZONT, UNTEN = 362;
    private static final float WOLKEN_Q = .5f;   // cloud bitmaps at half resolution: cheaper and as soft as the page's blur

    interface Aktionen {
        void zurueck();
        boolean pausiert();
        /** Called once, after the first complete frame with weather data (all layers built). */
        void fertig(long aufbauMs);
    }

    // ---- input ------------------------------------------------------------------------------------------------------
    private final Aktionen aktionen;
    private WetterLage.Daten daten;
    private WetterLage lage = new WetterLage("cloudy");
    private final String modus;        // "live", "zeitraffer" or "fest"
    private final long festZeit;       // fixed moment for "fest"
    private final long rafferStart = System.currentTimeMillis(), rafferUptime = SystemClock.uptimeMillis();
    private final boolean namen;
    private final long intervallNs;
    String mess1 = "", mess2 = "";

    // ---- state of the moment (recomputed per frame) -------------------------------------------------------------------
    private long simZeit;
    private double sonneAlt = 30, sonneAz = 180;
    private boolean steigt;
    private Himmel.Mond mond;
    private boolean koordinaten, mondDa;
    private WetterLage.Licht licht;
    private float sx, sy;              // sun on screen
    private float mx, my;              // moon on screen

    // ---- caches -------------------------------------------------------------------------------------------------------
    private float skala = 1, skalaAlt = -1, dx, dy;
    private Bitmap land, mondBild, kopf;
    private String landKey = "", mondKey = "", kopfKey = "", himmelKey = "";
    private long landGebaut;
    private boolean landBaut, wolkenBaut, mondBaut;
    private List<Wolken.Reihe> reihen = new ArrayList<>();
    private Bitmap[] wolkenTag = new Bitmap[0], wolkenNacht = new Bitmap[0];
    private String wolkenKey = "";
    private LightingColorFilter[] wolkenFilter = new LightingColorFilter[0];
    private Shader himmelShader, sonnenShader, deckTag, deckNacht, deckBlau;
    private Shader[] boeen;
    private ExecutorService bauer;

    /**
     * Built layers survive a closed view for two minutes, so opening it again soon costs no rebuild (like a prebuilt
     * dashboard layer); after that the memory is given back. Main thread only.
     */
    private static final class Lager {
        Bitmap land, mondBild;
        String landKey, mondKey, wolkenKey;
        List<Wolken.Reihe> reihen;
        Bitmap[] wolkenTag, wolkenNacht;
    }

    private static Lager lager;
    private static Handler haupt;
    private static final Runnable FREIGABE = () -> lager = null;
    static final long LAGER_MS = 120_000L;

    /** The plugin stops: drop the kept layers at once. */
    static void freigeben() {
        if (haupt != null) haupt.removeCallbacks(FREIGABE);
        lager = null;
    }

    // ---- particles (seeded, positions follow from the time) ------------------------------------------------------------
    private float[][] tropfen, flocken, hagel, spritzer, ringe, blaetter, glanz, sterne;
    private final List<float[][]> blitze = new ArrayList<>();

    // ---- frame loop and measurement ----------------------------------------------------------------------------------
    private boolean laeuft, angehaengt, fensterSichtbar = true, fertigGemeldet;
    private long letzterFrame, aufbauMs = -1;
    private int bilder, ruckler, bilderSek;
    private long sekStart, letzteZeichnung, maxLuecke, zeichenNs;
    int fps, rucklerGesamt, bilderGesamt;
    float zeichnenMs;

    // ---- paint ------------------------------------------------------------------------------------------------------
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bild = new Paint(Paint.FILTER_BITMAP_FLAG), linie = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface schrift = Typeface.create("sans-serif", Typeface.NORMAL), schriftMittel = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Shader turmGlut = new RadialGradient(112, 173, 10, 0xBFFFC86E, 0x00FFC86E, Shader.TileMode.CLAMP);
    // star positions on screen; live they move a pixel in a few minutes, so they are projected again every 15 s
    private long sternZeit = Long.MIN_VALUE;
    private float[][] sternXY;
    private float[][][] bildXY;
    private final Path pfad = new Path(), mondLoch = new Path();
    private final RectF r = new RectF();
    private float downX, downY;

    WetterView(Context ctx, Aktionen aktionen, String modus, String uhr, boolean namen, int zielFps) {
        super(ctx);
        this.aktionen = aktionen;
        this.namen = namen;
        this.intervallNs = 1_000_000_000L / Math.max(10, Math.min(60, zielFps));
        long fest = 0;
        String mo = "live";
        if ("zeitraffer".equals(modus)) mo = "zeitraffer";
        else if (uhr != null && uhr.matches("\\d{1,2}:\\d{2}")) {
            String[] t = uhr.split(":");
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, Math.min(23, Integer.parseInt(t[0])));
            c.set(Calendar.MINUTE, Math.min(59, Integer.parseInt(t[1])));
            c.set(Calendar.SECOND, 0);
            fest = c.getTimeInMillis();
            mo = "fest";
        }
        this.modus = mo;
        this.festZeit = fest;
        linie.setStyle(Paint.Style.STROKE);
        linie.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
        partikel();
        Lager l = lager;
        if (l != null) {   // layers of the last opening
            land = l.land;
            landKey = l.landKey;
            mondBild = l.mondBild;
            mondKey = l.mondKey;
            if (l.reihen != null) {
                reihen = l.reihen;
                wolkenTag = l.wolkenTag;
                wolkenNacht = l.wolkenNacht;
                wolkenKey = l.wolkenKey;
            }
            if (haupt != null) haupt.removeCallbacks(FREIGABE);
        }
    }

    /** New data from Home Assistant (main thread). */
    void setDaten(WetterLage.Daten d) {
        boolean neueLage = d == null ? daten != null : !d.zustand.equals(lage.cond);
        daten = d;
        if (neueLage) {
            lage = new WetterLage(d == null ? "cloudy" : d.zustand);
            partikel();
        }
        kopfKey = "";
        invalidate();
    }

    void mess(String a, String b) {
        mess1 = a == null ? "" : a;
        mess2 = b == null ? "" : b;
    }

    /** The screensaver or the screen changed: start or stop the loop. */
    void pauseGeaendert() { schleife(); }

    // ---- loop ---------------------------------------------------------------------------------------------------------

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        angehaengt = true;
        if (bauer == null) bauer = Executors.newSingleThreadExecutor();
        schleife();
    }

    @Override protected void onDetachedFromWindow() {
        angehaengt = false;
        schleife();
        if (bauer != null) bauer.shutdownNow();
        bauer = null;
        Lager l = new Lager();
        l.land = land;
        l.landKey = landKey;
        l.mondBild = mondBild;
        l.mondKey = mondKey;
        if (wolkenTag.length > 0) {
            l.reihen = reihen;
            l.wolkenTag = wolkenTag;
            l.wolkenNacht = wolkenNacht;
            l.wolkenKey = wolkenKey;
        }
        lager = l;
        if (haupt == null) haupt = new Handler(Looper.getMainLooper());
        haupt.removeCallbacks(FREIGABE);
        haupt.postDelayed(FREIGABE, LAGER_MS);
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        fensterSichtbar = v == VISIBLE;
        schleife();
    }

    @Override public void onVisibilityAggregated(boolean sichtbar) {
        super.onVisibilityAggregated(sichtbar);
        schleife();
    }

    private void schleife() {
        boolean soll = angehaengt && fensterSichtbar && isShown() && !aktionen.pausiert();
        if (soll == laeuft) return;
        laeuft = soll;
        Choreographer ch = Choreographer.getInstance();
        if (soll) {
            letzterFrame = 0;
            letzteZeichnung = 0;
            ch.postFrameCallback(this);
        } else {
            ch.removeFrameCallback(this);
        }
    }

    @Override public void doFrame(long ns) {
        if (!laeuft) return;
        if (letzterFrame == 0 || ns - letzterFrame >= intervallNs - 2_000_000L) {
            letzterFrame = ns;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    // ---- touch: back button ------------------------------------------------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        try {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                downY = e.getY();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                && Math.abs(e.getX() - downX) < 14 * skala && Math.abs(e.getY() - downY) < 14 * skala) {
                float x = (e.getX() - dx) / skala, y = (e.getY() - dy) / skala;
                if (Math.hypot(x - 58, y - 50) <= 32) {
                    performClick();
                    aktionen.zurueck();
                }
            }
        } catch (RuntimeException ignored) {
            // a touch error must not end the app
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    // ---- drawing ------------------------------------------------------------------------------------------------------

    @Override protected void onDraw(Canvas c) {
        long t0 = System.nanoTime();
        try {
            zeichnen(c);
        } catch (RuntimeException ignored) {
            // a drawing error must not end the app
        }
        messen(t0);
    }

    private void messen(long t0) {
        long jetzt = System.nanoTime();
        zeichenNs = zeichenNs == 0 ? jetzt - t0 : (zeichenNs * 7 + (jetzt - t0)) / 8;
        zeichnenMs = zeichenNs / 1e6f;
        if (letzteZeichnung != 0) {
            long luecke = jetzt - letzteZeichnung;
            bilder++;
            bilderGesamt++;
            if (laeuft && luecke > intervallNs * 3 / 2 + 4_000_000L) {
                ruckler++;
                rucklerGesamt++;
            }
            if (luecke > maxLuecke) maxLuecke = luecke;
        }
        letzteZeichnung = jetzt;
        if (sekStart == 0) sekStart = jetzt;
        bilderSek++;
        if (jetzt - sekStart >= 1_000_000_000L) {
            fps = Math.round(bilderSek * 1e9f / (jetzt - sekStart));
            bilderSek = 0;
            sekStart = jetzt;
        }
    }

    /** Scene fps, jank and draw time for the measurement line. */
    String szenenText() {
        return String.format(Locale.ROOT, "Szene %d fps (Ziel %d) · Ruckler %d von %d · max %d ms · Zeichnen %.1f ms",
            fps, Math.round(1e9f / intervallNs), rucklerGesamt, bilderGesamt, maxLuecke / 1_000_000L, zeichnenMs);
    }

    private void zeichnen(Canvas c) {
        float neu = Math.min(getWidth() / W, getHeight() / H);
        if (neu <= 0) return;
        boolean groesse = skalaAlt > 0 && neu != skalaAlt;   // the overlay changed its size: rebuild at once
        skalaAlt = neu;
        skala = neu;
        dx = (getWidth() - W * skala) / 2;
        dy = (getHeight() - H * skala) / 2;
        long anim = SystemClock.uptimeMillis();
        float t = anim / 1000f;
        moment();
        caches(groesse);

        c.drawColor(0xFF0D1720);
        c.save();
        c.translate(dx, dy);
        c.scale(skala, skala);
        c.save();
        c.clipRect(0, 0, W, UNTEN);
        himmel(c);
        sonne(c, t);
        sternenhimmel(c, t);
        mondZeichnen(c);
        wolken(c, t);
        blitzWolken(c, t);
        c.restore();
        if (land != null) {
            c.save();
            c.translate(0, 150);
            c.scale(1 / skala, 1 / skala);
            c.drawBitmap(land, 0, 0, bild);
            c.restore();
        }
        c.save();
        c.clipRect(0, 0, W, UNTEN);
        meer(c, t);
        leuchtturm(c, t);
        boot(c, t);
        niederschlag(c, t);
        c.restore();
        if (kopf != null) {
            c.save();
            c.scale(1 / skala, 1 / skala);
            c.drawBitmap(kopf, 0, 0, bild);
            c.restore();
        }
        anzeigen(c);
        c.restore();

        if (!fertigGemeldet && daten != null && land != null && kopf != null && (lage.cover <= 0 || wolkenTag.length > 0)) {
            fertigGemeldet = true;
            aktionen.fertig(aufbauMs);
        }
    }

    /** Time, sun, moon and light of this frame. */
    private void moment() {
        long jetzt = System.currentTimeMillis();
        if ("zeitraffer".equals(modus)) simZeit = rafferStart + (SystemClock.uptimeMillis() - rafferUptime) * 1440L % 86_400_000L;
        else if ("fest".equals(modus)) simZeit = festZeit;
        else simZeit = jetzt;
        WetterLage.Daten d = daten;
        koordinaten = d != null && d.lat != null && d.lon != null;
        if (koordinaten) {
            double[] s = Himmel.sonne(simZeit, d.lat, d.lon);
            sonneAlt = s[0];
            sonneAz = s[1];
            // live: anchored to sun.sun (refraction, HA's own model); between HA's updates the local ephemeris moves it on smoothly
            if ("live".equals(modus) && d.sonneHoehe != null && d.empfangen > 0) {
                double[] h = Himmel.sonne(d.empfangen, d.lat, d.lon);
                sonneAlt += Math.max(-2, Math.min(2, d.sonneHoehe - h[0]));
                if (d.sonneAzimut != null) sonneAz += Math.max(-3, Math.min(3, Himmel.norm180(d.sonneAzimut - h[1])));
            }
            steigt = Himmel.sonne(simZeit + 600_000L, d.lat, d.lon)[0] > Himmel.sonne(simZeit, d.lat, d.lon)[0];
            mond = Himmel.mond(simZeit, d.lat, d.lon);
        } else if (d != null && d.sonneHoehe != null) {
            sonneAlt = d.sonneHoehe;
            sonneAz = d.sonneAzimut == null ? 180 : d.sonneAzimut;
            steigt = d.steigt != null ? d.steigt : Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12;
            mond = null;
        } else {
            sonneAlt = 30;
            sonneAz = 180;
            mond = null;
        }
        licht = lage.licht(sonneAlt, steigt);
        sx = Himmel.x(sonneAz);
        sy = Math.max(60, Himmel.y(sonneAlt));
        mondDa = mond != null && mond.sichtbar && lage.mondMoeglich;
        if (mondDa) {
            mx = Himmel.x(mond.az);
            my = Math.max(46, Himmel.y(mond.alt));
            if (mx < -80 || mx > W + 80) mondDa = false;
        }
    }

    // ---- caches ---------------------------------------------------------------------------------------------------------

    private void caches(boolean groesse) {
        WetterLage.Licht L = licht;
        int monat = Calendar.getInstance().get(Calendar.MONTH);
        boolean ersterBau = aufbauMs < 0;
        long bau0 = SystemClock.uptimeMillis();
        // sky, sun glow and cloud decks: cheap shaders, rebuilt when the light moves by 0.05 deg
        String hk = Math.round(Math.max(-15, Math.min(15, sonneAlt)) * 20) + (steigt ? "s" : "f") + lage.cond;
        if (!hk.equals(himmelKey) || himmelShader == null) {
            himmelKey = hk;
            himmelShaders(L);
        }
        // land: on the background thread when the light changed by half a degree (at most every 0.4 s)
        double q = Math.max(-14, Math.min(12, sonneAlt));
        String lk = Math.round(q * 2) + (steigt ? "s" : "f") + lage.cond + mondDa + monat + skala;
        if (groesse || !lk.equals(landKey)) {
            if (land == null || groesse || !landKey.endsWith(String.valueOf(skala))) {
                landKey = lk;
                land = landBauen(L, mondDa, monat, skala);
            } else if (!landBaut && SystemClock.uptimeMillis() - landGebaut > 400 && bauer != null) {
                landBaut = true;
                final String key = lk;
                final boolean md = mondDa;
                final float sk = skala;
                bauer.execute(() -> {
                    Bitmap b = null;
                    try { b = landBauen(L, md, monat, sk); } catch (RuntimeException ignored) {}
                    final Bitmap fb = b;
                    post(() -> {
                        landBaut = false;
                        landGebaut = SystemClock.uptimeMillis();
                        if (fb != null) {
                            land = fb;
                            landKey = key;
                        }
                    });
                });
            }
        }
        // clouds: geometry per weather and day, two color versions (day / night, crossfaded)
        int tagNr = (int) Math.floor((simZeit + Calendar.getInstance().getTimeZone().getOffset(simZeit)) / 864e5);
        String wk = lage.cond + tagNr + mondDa + skala;
        if (!wk.equals(wolkenKey)) {
            if (wolkenTag.length == 0 || groesse) {
                // first frame: only the version for the light of now, the other one follows in the background
                wolkenKey = wk;
                int jetzt = L.tag >= .5f ? 0 : 1;
                List<Wolken.Reihe> rh = Wolken.bauen(lage, tagNr);
                Bitmap[][] b = wolkenBilder(rh, lage, mondDa, skala, jetzt);
                reihen = rh;
                wolkenTag = b[0];
                wolkenNacht = b[1];
                himmelKey = "";
                if (bauer != null && !rh.isEmpty()) {
                    wolkenBaut = true;
                    final WetterLage lg = lage;
                    final boolean md = mondDa;
                    final float sk = skala;
                    bauer.execute(() -> {
                        Bitmap[][] rest = null;
                        try { rest = wolkenBilder(rh, lg, md, sk, 1 - jetzt); } catch (RuntimeException ignored) {}
                        final Bitmap[][] fr = rest;
                        post(() -> {
                            wolkenBaut = false;
                            if (fr != null && reihen == rh) {
                                if (jetzt == 0) wolkenNacht = fr[1];
                                else wolkenTag = fr[0];
                            }
                        });
                    });
                }
            } else if (!wolkenBaut && bauer != null) {
                wolkenBaut = true;
                final String key = wk;
                final WetterLage lg = lage;
                final boolean md = mondDa;
                final float sk = skala;
                bauer.execute(() -> {
                    List<Wolken.Reihe> rh = Wolken.bauen(lg, tagNr);
                    Bitmap[][] b = null;
                    try { b = wolkenBilder(rh, lg, md, sk, -1); } catch (RuntimeException ignored) {}
                    final Bitmap[][] fb = b;
                    post(() -> {
                        wolkenBaut = false;
                        if (fb != null && lg == lage) {
                            reihen = rh;
                            wolkenTag = fb[0];
                            wolkenNacht = fb[1];
                            wolkenKey = key;
                            himmelKey = "";   // new row filters
                        }
                    });
                });
            }
        }
        // moon: phase and turn change slowly
        if (mondDa) {
            String mk = Math.round(mond.f * 100) + "/" + mond.limb + "/" + mond.face + "/" + skala;
            if (!mk.equals(mondKey)) {
                if (mondBild == null) {
                    mondKey = mk;
                    mondBild = mondBauen(mond, skala);
                } else if (!mondBaut && bauer != null) {
                    mondBaut = true;
                    final Himmel.Mond mo = mond;
                    final float sk = skala;
                    bauer.execute(() -> {
                        Bitmap b = mondBauen(mo, sk);
                        post(() -> {
                            mondBaut = false;
                            mondBild = b;
                            mondKey = mk;
                        });
                    });
                }
            }
        }
        // header: texts and the dark patch behind them
        WetterLage.Daten d = daten;
        boolean nacht = L.tag < .5f;
        String kk = (d == null ? "-" : d.name + d.temp + d.zustand + d.feuchte + d.wind + d.richtung + d.aufgang + d.untergang) + nacht + skala;
        if (!kk.equals(kopfKey) || kopf == null) {
            kopfKey = kk;
            kopf = kopfBauen(d, nacht, skala);
        }
        if (ersterBau) aufbauMs = SystemClock.uptimeMillis() - bau0;
    }

    private void himmelShaders(WetterLage.Licht L) {
        // sky: the page's gradient (0 % sk0, 30 % sk1, 55 % sk2, 62 % #0d1720) with the overlays of the hour, sampled every 26 px
        int n = 15;
        int[] farben = new int[n];
        float[] pos = new float[n];
        for (int i = 0; i < n; i++) {
            float y = UNTEN * i / (n - 1f), q = y / H;
            int f = q <= .3f ? WetterLage.mix(L.himmel[0], L.himmel[1], q / .3f)
                : (q <= .55f ? WetterLage.mix(L.himmel[1], L.himmel[2], (q - .3f) / .25f) : WetterLage.mix(L.himmel[2], 0xFF0D1720, Math.min(1, (q - .55f) / .07f)));
            farben[i] = WetterLage.tont(f, y, L);
            pos[i] = i / (n - 1f);
        }
        himmelShader = new LinearGradient(0, 0, 0, UNTEN, farben, pos, Shader.TileMode.CLAMP);
        // sun: evening / morning glow when low, the page's yellow sun when high
        float hoch = WetterLage.glatt(3, 12, sonneAlt);
        float[][] tief = steigt
            ? new float[][] {{0, 255, 248, 230, 1}, {40, 255, 215, 160, .95f}, {72, 255, 180, 140, .45f}, {150, 255, 170, 150, .12f}, {210, 255, 170, 150, 0}}
            : new float[][] {{0, 255, 236, 200, 1}, {40, 255, 180, 90, .95f}, {72, 255, 130, 60, .5f}, {150, 255, 110, 60, .15f}, {210, 255, 110, 60, 0}};
        float[][] tag = {{0, 255, 250, 220, 1}, {44, 255, 236, 160, .95f}, {75, 255, 214, 110, .45f}, {150, 255, 200, 90, .12f}, {210, 255, 200, 90, 0}};
        int[] sf = new int[5];
        float[] sp = new float[5];
        for (int i = 0; i < 5; i++) {
            float a = tief[i][4] + (tag[i][4] - tief[i][4]) * hoch;
            sf[i] = WetterLage.alpha(WetterLage.rgb(Math.round(tief[i][1] + (tag[i][1] - tief[i][1]) * hoch),
                Math.round(tief[i][2] + (tag[i][2] - tief[i][2]) * hoch), Math.round(tief[i][3] + (tag[i][3] - tief[i][3]) * hoch)), a);
            sp[i] = (tief[i][0] + (tag[i][0] - tief[i][0]) * hoch) / 210f;
        }
        sonnenShader = new RadialGradient(0, 0, 210, sf, sp, Shader.TileMode.CLAMP);
        // decks behind the clouds: blue sky in the gaps of a cloudy day, the closed grey deck of rain / snow
        int[] wt = Wolken.farben(lage, false, mondDa), wn = Wolken.farben(lage, true, mondDa);
        deckBlau = "cloudy".equals(lage.cond) ? new LinearGradient(0, 0, 0, Wolken.YH + 60,
            new int[] {WetterLage.tont(0xFF76A9DB, 0, L), WetterLage.tont(0xFFA9C7E6, 160, L), WetterLage.tont(0xFFD4DEE9, 295, L)}, new float[] {0, .55f, 1}, Shader.TileMode.CLAMP) : null;
        if (lage.cover >= 1) {
            deckTag = new LinearGradient(0, 0, 0, Wolken.YH + 60, WetterLage.tont(WetterLage.mix(wt[1], wt[2], .45f), 0, L), WetterLage.tont(wt[3], 295, L), Shader.TileMode.CLAMP);
            deckNacht = new LinearGradient(0, 0, 0, Wolken.YH + 60, WetterLage.tont(WetterLage.mix(wn[1], wn[2], .45f), 0, L), WetterLage.tont(wn[3], 295, L), Shader.TileMode.CLAMP);
        } else {
            deckTag = deckNacht = null;
        }
        // cloud rows take the light of the hour through a color filter (the bitmaps stay)
        wolkenFilter = new LightingColorFilter[reihen.size()];
        for (int i = 0; i < reihen.size(); i++) {
            Wolken.Reihe rh = reihen.get(i);
            float y = (float) (Wolken.YH * (.16 + .84 * Math.pow(rh.d, .7)));
            int base = WetterLage.mix(wt[1], wn[1], 1 - L.tag), get = WetterLage.tont(base, y, L);
            wolkenFilter[i] = filter(base, get);
        }
    }

    /** A color filter that turns color a into color b (multiply where b is darker, add where it is lighter). */
    private static LightingColorFilter filter(int a, int b) {
        int mul = 0, add = 0;
        for (int sh = 16; sh >= 0; sh -= 8) {
            int ca = Math.max(1, (a >> sh) & 255), cb = (b >> sh) & 255;
            mul |= Math.min(255, Math.round(255f * Math.min(cb, ca) / ca)) << sh;
            add |= Math.max(0, cb - ca) << sh;
        }
        return new LightingColorFilter(mul, add);
    }

    // ---- the layers of a frame --------------------------------------------------------------------------------------------

    private void himmel(Canvas c) {
        p.setShader(himmelShader);
        p.setAlpha(255);
        c.drawRect(0, 0, W, UNTEN, p);
        p.setShader(null);
    }

    private void sonne(Canvas c, float t) {
        if (!lage.sonneMoeglich || sonneAlt < -3) return;
        float a = WetterLage.glatt(-3, .5, sonneAlt) * (1 - lage.cover * .85f);
        if (a <= 0 || sx < -220 || sx > W + 220) return;
        float puls = .94f + .06f * (1 + (float) Math.sin(t * 2 * Math.PI / 18));   // sk-pulse 9 s alternate
        c.save();
        c.translate(sx, sy);
        c.scale(puls, puls);
        p.setShader(sonnenShader);
        p.setAlpha(Math.round(255 * a));
        c.drawCircle(0, 0, 210, p);
        p.setShader(null);
        p.setAlpha(255);
        c.restore();
    }

    private void sternenhimmel(Canvas c, float t) {
        float a = licht.sterne * lage.sterne / 70f;
        if (a <= .01f || mond == null) return;
        c.save();
        if (mondDa) {
            mondLoch.reset();
            mondLoch.addCircle(mx, my, 45, Path.Direction.CW);
            if (Build.VERSION.SDK_INT >= 26) c.clipOutPath(mondLoch);
            else c.clipPath(mondLoch, Region.Op.DIFFERENCE);
        }
        if (sternXY == null || Math.abs(simZeit - sternZeit) > 15_000L) {
            sternZeit = simZeit;
            sternXY = new float[sterne.length][];
            for (int i = 0; i < sterne.length; i++) {
                double[] h = Himmel.stern(sterne[i][0], sterne[i][1], mond.lst, mond.phi);
                sternXY[i] = h[0] < 1 ? null : new float[] {Himmel.x(h[1]), Himmel.y(h[0])};
            }
            bildXY = new float[Himmel.STERNBILDER.length][][];
            for (int k = 0; k < Himmel.STERNBILDER.length; k++) {
                Himmel.Sternbild sb = Himmel.STERNBILDER[k];
                bildXY[k] = new float[sb.s.length][];
                for (int i = 0; i < sb.s.length; i++) {
                    double[] h = Himmel.stern(sb.s[i][0], sb.s[i][1], mond.lst, mond.phi);
                    bildXY[k][i] = h[0] < -.6 ? null : new float[] {Himmel.x(h[1]), Himmel.y(h[0])};
                }
            }
        }
        // background stars at fixed sky positions, each twinkling on its own
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < sterne.length; i++) {
            float[] s = sterne[i], xy = sternXY[i];
            if (xy == null) continue;
            float x = xy[0], y = xy[1];
            if (x < -3 || x > W + 3 || y > HZ - 3) continue;
            float tw = .72f + .28f * (float) Math.sin(t * s[4] + s[5]);
            p.setColor(WetterLage.alpha(0xFFFFFFFF, s[3] * tw * a));
            c.drawCircle(x, y, s[2], p);
        }
        // constellations: faint lines, coloured dots, names
        linie.setStrokeWidth(.9f);
        linie.setColor(WetterLage.alpha(0xFFBECDFF, .2f * a));
        text.setTextSize(10);
        text.setTypeface(schrift);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(0, 0, 0, 0);
        text.setColor(WetterLage.alpha(0xFFBECDFF, .4f * a));
        try {
            text.setLetterSpacing(.06f);
        } catch (LinkageError ignored) {
            // old Android
        }
        float grenze = HZ + 60;
        for (int k = 0; k < Himmel.STERNBILDER.length; k++) {
            Himmel.Sternbild sb = Himmel.STERNBILDER[k];
            float[][] q = bildXY[k];
            for (int[] pl : sb.l) {
                for (int i = 1; i < pl.length; i++) {
                    float[] u = q[pl[i - 1]], v = q[pl[i]];
                    if (u != null && v != null && Math.hypot(u[0] - v[0], u[1] - v[1]) < 300 && Math.min(u[1], v[1]) < grenze) c.drawLine(u[0], u[1], v[0], v[1], linie);
                }
            }
            float sum = 0, maxY = 0;
            int nv = 0;
            for (int i = 0; i < q.length; i++) {
                float[] u = q[i];
                if (u == null || u[0] < -5 || u[0] > W + 5 || u[1] < -5 || u[1] > grenze) continue;
                double mag = sb.s[i][2];
                int f = sb.farbe[i] == 'o' ? 0xFFFFD6A8 : (sb.farbe[i] == 'r' ? 0xFFFFB89A : (sb.farbe[i] == 'b' ? 0xFFD4E3FF : 0xFFEEF2FF));
                p.setColor(WetterLage.alpha(f, (float) Math.min(1, .55 + .15 * (3 - mag)) * a));
                c.drawCircle(u[0], u[1], (float) Math.max(.7, 1.95 - .42 * mag), p);
                if (u[0] > 30 && u[0] < W - 30 && u[1] > 10 && u[1] < HZ - 30) {
                    sum += u[0];
                    maxY = Math.max(maxY, u[1]);
                    nv++;
                }
            }
            if (namen && sb.s.length > 1 && nv >= Math.min(3, sb.s.length)) c.drawText(sb.name, sum / nv, maxY + 15, text);
        }
        c.restore();
    }

    private void mondZeichnen(Canvas c) {
        if (!mondDa || mondBild == null) return;
        float a = 1 - .93f * licht.tag;   // by day only a pale disc
        if (a <= .02f) return;
        bild.setAlpha(Math.round(255 * a));
        c.save();
        c.translate(mx - 80, my - 80);
        c.scale(1 / skala, 1 / skala);
        c.drawBitmap(mondBild, 0, 0, bild);
        c.restore();
        bild.setAlpha(255);
    }

    private void wolken(Canvas c, float t) {
        float tag = licht.tag;
        if (deckBlau != null && tag > 0) {
            p.setShader(deckBlau);
            p.setAlpha(Math.round(255 * tag));
            c.drawRect(0, 0, W, Wolken.YH + 60, p);
        }
        if (deckNacht != null) {
            p.setShader(deckNacht);
            p.setAlpha(255);
            c.drawRect(0, 0, W, Wolken.YH + 60, p);
            p.setShader(deckTag);
            p.setAlpha(Math.round(255 * tag));
            c.drawRect(0, 0, W, Wolken.YH + 60, p);
        }
        p.setShader(null);
        p.setAlpha(255);
        if (wolkenTag.length == 0) return;
        float k = 1 / (skala * WOLKEN_Q);
        for (int i = 0; i < reihen.size() && i < Math.max(wolkenTag.length, wolkenNacht.length); i++) {
            Wolken.Reihe rh = reihen.get(i);
            float tempo = lage.schnell ? 1700f / rh.dauer : 600f / rh.dauer;   // px per s, far rows slower
            float o = (t * tempo) % Wolken.BREITE;
            bild.setColorFilter(i < wolkenFilter.length ? wolkenFilter[i] : null);
            for (int kopie = 0; kopie < 2; kopie++) {
                float x0 = o - Wolken.BREITE * kopie;
                if (x0 > W || x0 + Wolken.BREITE < 0) continue;
                c.save();
                c.translate(x0, 0);
                c.scale(k, k);
                Bitmap bn = i < wolkenNacht.length ? wolkenNacht[i] : null, bt = i < wolkenTag.length ? wolkenTag[i] : null;
                if (bn == null && bt == null) {
                    c.restore();
                    continue;
                }
                if (bn != null && (tag < .999f || bt == null)) {
                    bild.setAlpha(255);
                    c.drawBitmap(bn, 0, 0, bild);
                }
                if (bt != null && (tag > .001f || bn == null)) {
                    bild.setAlpha(bn == null ? 255 : Math.round(255 * tag));
                    c.drawBitmap(bt, 0, 0, bild);
                }
                c.restore();
            }
        }
        bild.setColorFilter(null);
        bild.setAlpha(255);
    }

    /** Thunderstorm: four flashes per 24 s inside the clouds and a faint brightening of the scene (no strikes). */
    private void blitzWolken(Canvas c, float t) {
        if (!lage.blitz || blitze.isEmpty()) return;
        float zyk = t % 24;
        for (float[][] e : blitze) {
            float op = blitzWert(e, zyk);
            if (op <= 0) continue;
            float[] k = e[0];   // {x, y, s, a}
            for (int b = 0; b < 3; b++) {
                float bx = k[0] + (b - 1) * 120 * k[2], by = k[1] + (b == 1 ? 0 : 14) * k[2], rx = 160 * k[2] * (b == 1 ? 1 : .55f);
                p.setShader(new RadialGradient(bx, by, rx, new int[] {0xFFE2DCFF, 0xB39F93E8, 0x006D62B0}, new float[] {0, .5f, 1}, Shader.TileMode.CLAMP));
                p.setAlpha(Math.round(255 * op * (b == 1 ? 1 : .7f)));
                c.drawCircle(bx, by, rx, p);
            }
            p.setShader(null);
            p.setColor(WetterLage.alpha(0xFFDCD6FF, .13f * k[3] * op));
            c.drawRect(0, 0, W, UNTEN, p);
        }
        p.setAlpha(255);
    }

    private static float blitzWert(float[][] e, float zyk) {
        float t0 = e[0][4];
        float rel = zyk - t0;
        if (rel < 0 || rel > .6f) return 0;
        for (int i = 2; i < e.length; i++) {
            if (rel <= e[i][0]) {
                float[] a = e[i - 1], b = e[i];
                return a[1] + (b[1] - a[1]) * (rel - a[0]) / Math.max(1e-3f, b[0] - a[0]);
            }
        }
        return 0;
    }

    /** Glitter path under the sun or moon, surf, rain rings, wind crests. */
    private void meer(Canvas c, float t) {
        float sonneG = lage.sonneMoeglich ? WetterLage.glatt(-1, 2, sonneAlt) * (1 - lage.cover * .9f) : 0;
        float mondG = mondDa ? (float) mond.f * (1 - licht.tag) * (1 - lage.cover * .9f) : 0;
        if (sonneG > .02f && sx > -40 && sx < W + 40) glanzpfad(c, t, sx, sonneAlt < 8 ? 0xFFFFE2B0 : 0xFFFFF6E6, .55f * sonneG);
        if (mondG > .02f && mx > -40 && mx < W + 40) glanzpfad(c, t, mx, 0xFFF3EFD9, .55f * mondG);
        // surf (wl-surf: 4 s, the second line 2 s later)
        for (int i = 0; i < 2; i++) {
            float ph = (float) ((t + i * 2) % 4 / 4.0), w = (float) (.5 - .5 * Math.cos(ph * 2 * Math.PI));
            linie.setStrokeWidth(i == 0 ? 2 : 1.4f);
            linie.setColor(WetterLage.alpha(0xFFFFFFFF, (i == 0 ? .6f : .35f) * (.9f - .5f * w)));
            pfad.reset();
            if (i == 0) {
                pfad.moveTo(0, 293);
                pfad.cubicTo(120, 287, 250, 296, 380, 291);
                pfad.cubicTo(500, 287, 610, 291, 720, 288);
            } else {
                pfad.moveTo(40, 284);
                pfad.cubicTo(160, 280, 290, 287, 420, 283);
                pfad.cubicTo(540, 279, 640, 283, 740, 281);
            }
            c.save();
            c.translate(0, 4 * w);
            c.drawPath(pfad, linie);
            c.restore();
        }
        // rain rings on the water (wl-rip, 1.4 s)
        linie.setStrokeWidth(1);
        for (float[] g : ringe) {
            float ph = ((t + g[2]) % 1.4f) / 1.4f, s = .2f + 1.3f * ph;
            linie.setColor(WetterLage.alpha(0xFFFFFFFF, .45f * (.9f * (1 - ph))));
            r.set(g[0] - 9 * s, g[1] - 2 * s, g[0] + 9 * s, g[1] + 2 * s);
            c.drawOval(r, linie);
        }
        // wind: shimmer lines running over the sea (wl-wave)
        if (lage.wind) {
            for (float[] g : glanz) {
                float ph = (float) (.5 - .5 * Math.cos(((t + g[4]) % 3.2f) / 3.2f * 2 * Math.PI));
                p.setColor(WetterLage.alpha(0xFFFFFFFF, .22f * (.3f + .6f * ph)));
                c.drawRect(g[0] - 10 + 24 * ph, g[1], g[0] - 10 + 24 * ph + g[2], g[1] + g[3], p);
            }
        }
    }

    private void glanzpfad(Canvas c, float t, float x, int farbe, float a) {
        for (int i = 0; i < 12; i++) {
            float q = i / 11f, y = HZ + 3 + (284 - HZ - 6) * (float) Math.pow(q, 1.5);
            float w = 6 + q * 34, flackern = .45f + .55f * (float) (.5 + .5 * Math.sin(t * (1.7 + i * .23) + i * 2.1));
            float off = (float) Math.sin(t * .8 + i * 1.3) * (2 + q * 5);
            p.setColor(WetterLage.alpha(farbe, a * flackern * (1 - q * .35f)));
            r.set(x - w / 2 + off, y, x + w / 2 + off, y + 1.2f + q * 1.2f);
            c.drawRoundRect(r, 1, 1, p);
        }
    }

    private void leuchtturm(Canvas c, float t) {
        float an = licht.leuchtturm;
        if (an <= .01f) return;
        p.setShader(turmGlut);
        p.setAlpha(Math.round(255 * an));
        c.drawCircle(112, 173, 10, p);
        p.setShader(null);
        // two beams sweeping out of phase (wl-beam: 8 s, visible 12..20 %)
        for (int i = 0; i < 2; i++) {
            float ph = ((t + i * 4) % 8) / 8, op = ph < .12f ? ph / .12f : (ph <= .2f ? 1 : (ph < .32f ? (.32f - ph) / .12f : 0));
            if (op <= 0) continue;
            p.setColor(WetterLage.alpha(0xFFFFECAA, .16f * op * an));
            pfad.reset();
            pfad.moveTo(112, 173);
            if (i == 0) {
                pfad.lineTo(-40, 160);
                pfad.lineTo(-40, 186);
            } else {
                pfad.lineTo(330, 158);
                pfad.lineTo(330, 188);
            }
            pfad.close();
            c.drawPath(pfad, p);
        }
        p.setAlpha(255);
    }

    /** The sailing boat rocks irregularly (three swells); at night only its lamp. Not in a storm or downpour. */
    private void boot(Canvas c, float t) {
        if (lage.blitz || lage.stark) return;
        float tag = licht.tag;
        if (tag < .999f) {
            p.setColor(WetterLage.alpha(0xFFFFD27A, 1 - tag));
            c.drawCircle(560, HZ - 2, 1.3f, p);
        }
        if (tag <= .001f) return;
        float winkel = (float) (2.2 * Math.sin(t * .9) + 1.3 * Math.sin(t * 1.7 + 1) + .6 * Math.sin(t * 3.1 + 2)) * (lage.wind ? 2 : 1);
        float hub = (float) (.8 * Math.sin(t * 1.3) + .4 * Math.sin(t * 2.3 + .5));
        c.save();
        c.translate(561, HZ + 1 + hub);
        c.rotate(winkel);
        c.translate(-561, -(HZ + 1));
        int rumpf = WetterLage.mix(0xFF22252C, 0xFF3A3F48, tag), segel = WetterLage.mix(0xFF5A6070, 0xFFF4F2EC, tag), fock = WetterLage.mix(0xFF50566A, 0xFFE6E2D8, tag);
        p.setColor(WetterLage.alpha(WetterLage.tont(rumpf, HZ, licht), tag));
        pfad.reset();
        pfad.moveTo(549, HZ - 1); pfad.lineTo(573, HZ - 1); pfad.lineTo(569, HZ + 3); pfad.lineTo(553, HZ + 3); pfad.close();
        c.drawPath(pfad, p);
        p.setColor(WetterLage.alpha(WetterLage.tont(segel, HZ - 14, licht), tag));
        pfad.reset();
        pfad.moveTo(561, HZ - 2); pfad.lineTo(561, HZ - 26); pfad.lineTo(572, HZ - 3); pfad.close();
        c.drawPath(pfad, p);
        p.setColor(WetterLage.alpha(WetterLage.tont(fock, HZ - 12, licht), tag));
        pfad.reset();
        pfad.moveTo(559.5f, HZ - 2); pfad.lineTo(559.5f, HZ - 21); pfad.lineTo(551, HZ - 3); pfad.close();
        c.drawPath(pfad, p);
        c.restore();
    }

    private void niederschlag(Canvas c, float t) {
        // rain: falls 660 px at an angle, bright head and fading tail (sk-drop)
        double sl = Math.toRadians(lage.schraeg);
        float vx = (float) -Math.sin(sl), vy = (float) Math.cos(sl);
        linie.setStrokeWidth(2);
        for (float[] d : tropfen) {
            float ph = ((t + d[3]) % d[2]) / d[2], s = 660 / vy * ph;
            float hx = d[0] + vx * s, hy = -40 + lage.tropfenLaenge + vy * s;
            float len = lage.tropfenLaenge;
            linie.setColor(WetterLage.alpha(0xFFDCEBFF, .95f * d[4] * .35f));
            c.drawLine(hx - vx * len, hy - vy * len, hx - vx * len * .45f, hy - vy * len * .45f, linie);
            linie.setColor(WetterLage.alpha(0xFFDCEBFF, .95f * d[4]));
            c.drawLine(hx - vx * len * .45f, hy - vy * len * .45f, hx, hy, linie);
        }
        // splashes on the ground in a downpour
        linie.setStrokeWidth(1);
        for (float[] g : spritzer) {
            float ph = ((t + g[3]) % g[2]) / g[2], s = .2f + 1.4f * ph;
            linie.setColor(WetterLage.alpha(0xFFDCEBFF, .6f * .9f * (1 - ph)));
            r.set(g[0] + 8 - 8 * s, g[1] + 2 - 2 * s, g[0] + 8 + 8 * s, g[1] + 2 + 2 * s);
            c.drawOval(r, linie);
        }
        // snow: 0 -> (30, 320) -> (-10, 640) (sk-snow)
        for (float[] f : flocken) {
            float ph = ((t + f[3]) % f[2]) / f[2];
            float x = f[0] + (ph < .5f ? 60 * ph : 30 - 80 * (ph - .5f)), y = -20 + 640 * ph;
            p.setColor(WetterLage.alpha(0xFFFFFFFF, f[4]));
            c.drawCircle(x + f[1] / 2, y + f[1] / 2, f[1] / 2, p);
        }
        // hail: falls, bounces twice, lies (sk-hail)
        for (float[] h : hagel) {
            float ph = ((t + h[3]) % h[2]) / h[2];
            float[] xy = hagelBahn(ph);
            p.setColor(WetterLage.alpha(0xFFE6F0FA, ph > .97f ? (1 - ph) / .03f : 1));
            c.drawCircle(h[0] + xy[0] + h[1] / 2, -20 + xy[1] + h[1] / 2, h[1] / 2, p);
        }
        // fog banks drifting to and fro (sk-fog)
        if (lage.nebel) {
            for (int i = 0; i < 4; i++) {
                float dur = 50 + i * 15, ph = (float) (.5 - .5 * Math.cos(((t + i * 11) % (2 * dur)) / (2 * dur) * 2 * Math.PI));
                float top = 80 + i * 70;
                p.setShader(new LinearGradient(0, top - 26, 0, top + 116, new int[] {0x00FFFFFF, 0x42FFFFFF, 0x42FFFFFF, 0x00FFFFFF}, new float[] {0, .35f, .65f, 1}, Shader.TileMode.CLAMP));
                c.save();
                c.translate(-60 + 120 * ph, 0);
                c.drawRect(-100, top - 26, W + 100, top + 116, p);
                c.restore();
            }
            p.setShader(null);
        }
        if (lage.dunst) {
            p.setShader(new LinearGradient(0, 160, 0, 340, new int[] {0x00C8D7E6, 0x29C8D7E6, 0x00C8D7E6}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 160, W, 340, p);
            p.setShader(null);
        }
        // wind: gusts and leaves
        if (lage.wind && boeen != null) {
            for (int i = 0; i < boeen.length; i++) {
                float[] g = glanz[i % glanz.length];
                float dur = 2.2f + (i * .37f % 2), ph = ((t + i * .61f) % dur) / dur, breite = 120 + (i * 53 % 220);
                c.save();
                c.translate(-400 + 1500 * ph, 30 + (i * 97 % 300));
                p.setShader(boeen[i]);
                c.drawRect(0, 0, breite, 2, p);
                c.restore();
            }
            p.setShader(null);
            int[] laub = {0xFFD98C3A, 0xFFC9662F, 0xFFE0B34A, 0xFF8FAE4A};
            for (int i = 0; i < blaetter.length; i++) {
                float[] b = blaetter[i];
                float ph = ((t + b[2]) % b[1]) / b[1], seg = ph * 4;
                int k = Math.min(3, (int) seg);
                float u = seg - k;
                float[][] kp = {{0, 0}, {260, -40}, {520, 30}, {780, -30}, {1060, 20}};
                float x = -40 + kp[k][0] + (kp[k + 1][0] - kp[k][0]) * u, y = b[0] + kp[k][1] + (kp[k + 1][1] - kp[k][1]) * u;
                c.save();
                c.translate(x + 7, y + 4);
                c.rotate(800 * ph);
                p.setColor(laub[i % 4]);
                pfad.reset();
                pfad.moveTo(-7, 4); pfad.quadTo(-7, -4, 7, -4); pfad.quadTo(7, 4, -7, 4); pfad.close();
                c.drawPath(pfad, p);
                c.restore();
            }
        }
    }

    private static float[] hagelBahn(float ph) {
        float[][] k = {{0, 0, 0}, {.78f, -30, 330}, {.86f, -22, 305}, {.94f, -16, 332}, {.97f, -13, 325}, {1, -11, 334}};
        for (int i = 1; i < k.length; i++) {
            if (ph <= k[i][0]) {
                float u = (ph - k[i - 1][0]) / (k[i][0] - k[i - 1][0]);
                if (i == 1) u = u * u * (1.4f - .4f * u);   // accelerating fall
                return new float[] {k[i - 1][1] + (k[i][1] - k[i - 1][1]) * u, k[i - 1][2] + (k[i][2] - k[i - 1][2]) * u};
            }
        }
        return new float[] {-11, 334};
    }

    /** Mode badge (time lapse / fixed time) and the measured values in the free area below the scene. */
    private void anzeigen(Canvas c) {
        text.setShadowLayer(0, 0, 0, 0);
        try {
            text.setLetterSpacing(0);
        } catch (LinkageError ignored) {
            // old Android
        }
        if (!"live".equals(modus)) {
            String s = ("zeitraffer".equals(modus) ? "Zeitraffer " : "Uhrzeit ") + WetterLage.uhr(simZeit) + (koordinaten ? "" : " (ohne zone.home)");
            text.setTypeface(schriftMittel);
            text.setTextSize(16);
            text.setTextAlign(Paint.Align.RIGHT);
            float w = text.measureText(s);
            p.setColor(0x73000000);
            r.set(W - 30 - w - 28, 30, W - 30, 64);
            c.drawRoundRect(r, 17, 17, p);
            text.setColor(0xFFECEBE8);
            c.drawText(s, W - 44, 52.5f, text);
        }
        text.setTypeface(schrift);
        text.setTextSize(12);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFF8FA3B5);
        c.drawText(mess1, W / 2, 536, text);
        c.drawText(mess2, W / 2, 554, text);
        c.drawText(szenenText(), W / 2, 572, text);
    }

    // ---- bitmap builds (also on the background thread: no fields written here) ------------------------------------------

    private static Bitmap leer(float w, float h, float sk) {
        return Bitmap.createBitmap(Math.max(1, Math.round(w * sk)), Math.max(1, Math.round(h * sk)), Bitmap.Config.ARGB_8888);
    }

    /** Sea, lighthouse island, beach, dune, beach grass and the fade into the dark area (page y 150 .. 362). */
    private Bitmap landBauen(WetterLage.Licht L, boolean mondSichtbar, int monat, float sk) {
        Bitmap b = leer(W, UNTEN + 4 - 150, sk);   // 4 px of the dark area below, so no seam shows at the edge
        Canvas c = new Canvas(b);
        c.scale(sk, sk);
        c.translate(0, -150);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path pa = new Path();
        WetterLage.Land P = lage.land(L, mondSichtbar, monat);
        int himmelUnten = WetterLage.tont(WetterLage.mix(L.himmel[1], L.himmel[2], (HZ / H - .3f) / .25f), HZ, L);
        // sea: the lake colours of the palette, taking the colour of the sky near the horizon
        int seeOben = WetterLage.mix(WetterLage.tont(P.lakeA, HZ + 4, L), himmelUnten, .12f);
        int seeUnten = WetterLage.mix(WetterLage.tont(P.lakeB, 300, L), WetterLage.tont(L.himmel[0], 300, L), .05f);
        q.setShader(new LinearGradient(0, HZ, 0, 300, seeOben, seeUnten, Shader.TileMode.CLAMP));
        c.drawRect(0, HZ, W, 300, q);
        q.setShader(null);
        q.setColor(0x2EFFFFFF);
        c.drawRect(0, HZ - 1, W, HZ + 1, q);
        // shimmer on the water (waterFx, still)
        Wolken.Zufall R = new Wolken.Zufall(26);
        if (!lage.wind) {
            for (int i = 0; i < 26; i++) {
                double tt = R.next();
                float y = (float) (HZ + 4 + tt * (300 - HZ - 8)), x = (float) (R.next() * W * .95), ww = (float) ((14 + R.next() * 60) * (.5 + tt * .7));
                q.setColor(WetterLage.alpha(0xFFFFFFFF, .22f * .45f));
                c.drawRect(x, y, x + ww, y + 1 + (float) tt * .5f, q);
            }
        }
        if (lage.wind || lage.stark || lage.blitz) {
            q.setStyle(Paint.Style.STROKE);
            q.setStrokeWidth(1.2f);
            q.setColor(0x8CFFFFFF);
            for (int i = 0; i < 18; i++) {
                float x = (float) (R.next() * 940), y = (float) (HZ + 10 + R.next() * 70);
                pa.reset();
                pa.moveTo(x, y);
                pa.quadTo(x + 4, y - 3, x + 8, y);
                c.drawPath(pa, q);
            }
            q.setStyle(Paint.Style.FILL);
        }
        // island with the lighthouse
        float nacht = 1 - L.tag;
        q.setColor(WetterLage.tont(P.mid, 205, L));
        pa.reset();
        pa.moveTo(30, HZ + 1);
        pa.cubicTo(60, 205, 110, 199, 150, 205);
        pa.cubicTo(175, 209, 190, 211, 204, HZ + 1);
        pa.close();
        c.drawPath(pa, q);
        int lw = WetterLage.tont(WetterLage.mix(0xFFEFECE4, 0xFF4A4E58, nacht), 190, L), lr = WetterLage.tont(WetterLage.mix(0xFFC94A3C, 0xFF4A2A2C, nacht), 190, L);
        q.setColor(lw);
        pa.reset();
        pa.moveTo(107.5f, 204); pa.lineTo(109.5f, 178); pa.lineTo(114.5f, 178); pa.lineTo(116.5f, 204); pa.close();
        c.drawPath(pa, q);
        q.setColor(lr);
        pa.reset();
        pa.moveTo(108.4f, 196); pa.lineTo(115.6f, 196); pa.lineTo(115.2f, 190); pa.lineTo(108.8f, 190); pa.close();
        pa.moveTo(109.1f, 186); pa.lineTo(114.9f, 186); pa.lineTo(114.6f, 181); pa.lineTo(109.4f, 181); pa.close();
        pa.moveTo(109, 170); pa.lineTo(112, 166); pa.lineTo(115, 170); pa.close();
        c.drawPath(pa, q);
        q.setColor(WetterLage.mix(0xFF3A3A40, 0xFF22242A, nacht));
        c.drawRect(108, 176, 116, 178, q);
        q.setColor(WetterLage.mix(WetterLage.mix(0xFFC9D3DC, 0xFF555C66, nacht), 0xFFFFE7A0, L.leuchtturm));
        c.drawRect(109.6f, 170, 114.4f, 176, q);
        // beach, wet sand, dune
        int sandTag = WetterLage.mix(P.stone, 0xFFD9C08C, "tag".equals(lage.landTag) ? .45f : .25f);
        int sand = lage.schnee ? P.hillA : WetterLage.mix(WetterLage.mix(P.stone, P.hillB, .45f), sandTag, L.tag);
        q.setColor(WetterLage.tont(sand, 320, L));
        pa.reset();
        pa.moveTo(0, 296);
        pa.cubicTo(150, 290, 300, 298, 450, 294);
        pa.cubicTo(560, 291, 640, 292, 700, 290);
        pa.lineTo(W, 290); pa.lineTo(W, UNTEN); pa.lineTo(0, UNTEN); pa.close();
        c.drawPath(pa, q);
        q.setColor(0x1F000000);
        pa.reset();
        pa.moveTo(0, 296);
        pa.cubicTo(150, 290, 300, 298, 450, 294);
        pa.cubicTo(560, 291, 640, 292, 700, 290);
        pa.lineTo(700, 296);
        pa.cubicTo(620, 298, 540, 297, 450, 300);
        pa.cubicTo(300, 304, 150, 296, 0, 302);
        pa.close();
        c.drawPath(pa, q);
        int duene = lage.schnee ? P.hillA : WetterLage.mix(sand, P.hillB, .18f);
        q.setColor(WetterLage.tont(duene, 310, L));
        pa.reset();
        pa.moveTo(590, 310);
        pa.cubicTo(650, 290, 720, 282, 790, 283);
        pa.cubicTo(850, 284, 910, 280, W, 276);
        pa.lineTo(W, UNTEN); pa.lineTo(590, UNTEN); pa.close();
        c.drawPath(pa, q);
        // beach grass on the dune (gap where the figure will stand)
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(1.3f);
        q.setStrokeCap(Paint.Cap.ROUND);
        q.setColor(WetterLage.tont(WetterLage.mix(P.grass, 0xFFC9B98A, .35f), 290, L));
        for (float x = 610; x < W; x += (float) (9 + R.next() * 14)) {
            float y = x < 790 ? 310 - (x - 590) / 200 * 27 : 283 - (x - 790) / 173 * 7;
            int n = 3 + (int) Math.floor(R.next() * 3);
            if (x > 790 && x < 852) continue;
            for (int k = 0; k < n; k++) {
                float h = (float) (10 + R.next() * 10), ddx = (k - n / 2f) * 3;
                pa.reset();
                pa.moveTo(x, y + 2);
                pa.rQuadTo(ddx * .4f, -h * .6f, ddx, -h);
                c.drawPath(pa, q);
            }
        }
        q.setStyle(Paint.Style.FILL);
        // shells, puddles in a downpour
        q.setColor(WetterLage.tont(WetterLage.mix(0xFFEFE9DE, 0xFF6A6C74, nacht), 320, L));
        float[][] mu = {{300, 318, 3}, {480, 326, 2.4f}, {660, 318, 2.6f}};
        for (float[] m : mu) {
            RectF o = new RectF(m[0] - m[2], m[1] - m[2] * .6f, m[0] + m[2], m[1] + m[2] * .6f);
            c.drawOval(o, q);
        }
        if (lage.stark) {
            q.setColor(WetterLage.alpha(P.lakeB, .6f));
            float[][] pf = {{220, 320, 34}, {500, 324, 26}};
            for (float[] m : pf) c.drawOval(new RectF(m[0] - m[2], m[1] - m[2] * .18f, m[0] + m[2], m[1] + m[2] * .18f), q);
        }
        // fade into the dark lower area (wl-fd)
        q.setShader(new LinearGradient(0, 292, 0, UNTEN, 0x000D1720, 0xFF0D1720, Shader.TileMode.CLAMP));
        c.drawRect(0, 292, W, UNTEN, q);
        q.setShader(null);
        q.setColor(0xFF0D1720);
        c.drawRect(0, UNTEN - .5f, W, UNTEN + 4, q);
        return b;
    }

    /** One bitmap per row and day / night: clusters of puffs filled from the lit top to the grey base, puff texture, blur. */
    private static Bitmap[][] wolkenBilder(List<Wolken.Reihe> rh, WetterLage lg, boolean mond, float sk, int nur) {
        Bitmap[][] out = new Bitmap[2][rh.size()];
        float q = sk * WOLKEN_Q;
        for (int v = 0; v < 2; v++) {
            if (nur >= 0 && v != nur) continue;
            boolean nacht = v == 1;
            int[] wp = Wolken.farben(lg, nacht, mond);
            for (int i = 0; i < rh.size(); i++) {
                Wolken.Reihe reihe = rh.get(i);
                float dd = reihe.d * .6f;
                int hi = WetterLage.mix(wp[0], wp[3], dd), bo = WetterLage.mix(wp[1], wp[3], dd), sh = WetterLage.mix(wp[2], wp[3], dd);
                Bitmap b = leer(Wolken.BREITE, Wolken.YH + 20, q);
                Canvas c = new Canvas(b);
                c.scale(q, q);
                Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (int pass = 0; pass < 2; pass++) {
                    if (pass == 1) pp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
                    for (Wolken.Haufen h : reihe.haufen) {
                        for (int kopie = -1; kopie <= 1; kopie++) {
                            float ox = kopie * Wolken.BREITE;
                            if (h.cx + h.w + ox < 0 || h.cx - h.w + ox > Wolken.BREITE) continue;
                            float top = Math.min(h.top, h.by - h.h * 1.15f);
                            c.save();
                            c.clipRect(h.cx - h.w + ox, top - h.h, h.cx + h.w + ox, h.by);
                            if (pass == 0) {
                                pp.setShader(new LinearGradient(0, top, 0, h.by, new int[] {hi, hi, bo, sh}, new float[] {0, .1f, .6f, 1}, Shader.TileMode.CLAMP));
                                for (float[] pf : h.puffs) c.drawCircle(pf[0] + ox, pf[1], pf[2], pp);
                                pp.setShader(null);
                            } else {
                                pp.setColor(WetterLage.alpha(sh, .16f));
                                for (float[] pf : h.puffs) c.drawCircle(pf[0] + ox + pf[2] * .35f, pf[1] + pf[2] * .45f, pf[2] * .9f, pp);
                                pp.setColor(WetterLage.alpha(hi, nacht && !mond ? .15f : .35f));
                                float grenze = h.by - (h.by - top) * .45f;
                                for (float[] pf : h.puffs) if (pf[1] < grenze) c.drawCircle(pf[0] + ox - pf[2] * .25f, pf[1] - pf[2] * .3f, pf[2] * .6f, pp);
                            }
                            c.restore();
                        }
                    }
                }
                weich(b, Math.max(1, Math.round(reihe.blur * q * .8f)));
                out[v][i] = b;
            }
        }
        return out;
    }

    /** Two box blurs on premultiplied pixels (close to the page's Gaussian blur), wrapping horizontally like the row. */
    static void weich(Bitmap b, int rad) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[][] ch = new int[4][w * h];
        for (int i = 0; i < px.length; i++) {
            int a = px[i] >>> 24;
            ch[0][i] = a;
            ch[1][i] = ((px[i] >> 16) & 255) * a / 255;
            ch[2][i] = ((px[i] >> 8) & 255) * a / 255;
            ch[3][i] = (px[i] & 255) * a / 255;
        }
        int[] tmp = new int[Math.max(w, h)];
        for (int lauf = 0; lauf < 2; lauf++) {
            for (int[] k : ch) {
                for (int y = 0; y < h; y++) boxZeile(k, y * w, 1, w, rad, tmp, true);
                for (int x = 0; x < w; x++) boxZeile(k, x, w, h, rad, tmp, false);
            }
        }
        for (int i = 0; i < px.length; i++) {
            int a = Math.min(255, ch[0][i]);
            if (a == 0) {
                px[i] = 0;
                continue;
            }
            int rr = Math.min(255, ch[1][i] * 255 / a), gg = Math.min(255, ch[2][i] * 255 / a), bb = Math.min(255, ch[3][i] * 255 / a);
            px[i] = (a << 24) | (rr << 16) | (gg << 8) | bb;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxZeile(int[] k, int start, int schritt, int n, int rad, int[] tmp, boolean rund) {
        int sum = 0, fenster = 2 * rad + 1;
        for (int i = -rad; i <= rad; i++) sum += k[start + idx(i, n, rund) * schritt];
        for (int i = 0; i < n; i++) {
            tmp[i] = sum / fenster;
            sum += k[start + idx(i + rad + 1, n, rund) * schritt] - k[start + idx(i - rad, n, rund) * schritt];
        }
        for (int i = 0; i < n; i++) k[start + i * schritt] = tmp[i];
    }

    private static int idx(int i, int n, boolean rund) {
        if (rund) return ((i % n) + n) % n;
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }

    /** The moon as on the page: glow, the dark disc, the lit part (turned by the real limb), maria on the lit part. */
    private static Bitmap mondBauen(Himmel.Mond m, float sk) {
        Bitmap b = leer(160, 160, sk);
        Canvas c = new Canvas(b);
        c.scale(sk, sk);
        c.translate(80, 80);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        q.setShader(new RadialGradient(0, 0, 80, new int[] {0x00F3EFD9, 0x00F3EFD9, WetterLage.alpha(0xFFF3EFD9, (float) (.06 + .14 * m.f)), 0x00F3EFD9},
            new float[] {0, .5f, .54f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, 80, q);
        q.setShader(null);
        float rr = 42, k = (float) (rr * Math.abs(2 * m.f - 1));
        q.setColor(0x0BBEC8E1);
        c.drawCircle(0, 0, rr, q);
        Path lit = new Path();
        lit.arcTo(new RectF(-rr, -rr, rr, rr), 180, 180, true);
        lit.arcTo(new RectF(-rr, -k, rr, k), 0, m.f > .5 ? 180 : -180, false);
        lit.close();
        int ebene = c.saveLayer(-80, -80, 80, 80, null);
        c.save();
        c.rotate(m.limb);
        q.setColor(0xFFF3EFD9);
        c.drawPath(lit, q);
        c.restore();
        q.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        c.save();
        c.rotate(m.face);
        float[][] mar = {{-.58f, -.02f, .22f, .38f, .9f}, {-.3f, -.36f, .25f, .19f, 1}, {.17f, -.38f, .15f, .14f, 1}, {.32f, -.07f, .19f, .14f, .95f}, {.69f, -.3f, .09f, .075f, 1},
            {.55f, .2f, .1f, .15f, .8f}, {.34f, .31f, .07f, .07f, .7f}, {-.18f, .37f, .16f, .11f, .7f}, {-.47f, .39f, .07f, .07f, .7f}, {-.04f, -.66f, .38f, .06f, .5f}, {-.12f, -.12f, .12f, .1f, .5f}};
        for (float[] a : mar) {
            q.setColor(WetterLage.alpha(0xFF76705C, .3f * a[4]));
            c.drawOval(new RectF((a[0] - a[2]) * rr, (a[1] - a[3]) * rr, (a[0] + a[2]) * rr, (a[1] + a[3]) * rr), q);
        }
        c.restore();
        c.restoreToCount(ebene);
        return b;
    }

    /** Header like the weather page: back button, place, big temperature, condition, details; dark patch behind them. */
    private Bitmap kopfBauen(WetterLage.Daten d, boolean nacht, float sk) {
        Bitmap b = leer(W, 330, sk);
        Canvas c = new Canvas(b);
        c.scale(sk, sk);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        float dunkel = nacht ? .25f : (lage.schnee || lage.nebel ? .5f : .38f);
        c.save();
        c.translate(160, 150);
        c.scale(1, .5f);
        q.setShader(new RadialGradient(0, 0, 520, WetterLage.alpha(0xFF080E16, dunkel), 0x00080E16, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, 520, q);
        q.setShader(null);
        c.restore();
        q.setColor(0x1FFFFFFF);
        c.drawCircle(58, 50, 28, q);
        q.setColor(0xFFECEBE8);
        Path pf = new Path();
        pf.moveTo(15.41f, 16.58f); pf.lineTo(10.83f, 12); pf.lineTo(15.41f, 7.41f); pf.lineTo(14, 6); pf.lineTo(8, 12); pf.lineTo(14, 18); pf.close();
        c.save();
        c.translate(44, 36);
        c.scale(28 / 24f, 28 / 24f);
        c.drawPath(pf, q);
        c.restore();
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFECEBE8);
        t.setShadowLayer(6, 0, 1, 0x59000000);
        if (d == null) {
            t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            t.setTextSize(18);
            c.drawText("Wetter wird geladen …", 104, 52, t);
            return b;
        }
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setTextSize(22);
        c.drawText(d.name, 104, 32 + .928f * 22, t);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setTextSize(112);
        try {
            t.setLetterSpacing(-3 / 112f);
        } catch (LinkageError ignored) {
            // old Android
        }
        c.drawText((d.temp == null ? "–" : String.valueOf(Math.round(d.temp))) + "°", 100, 64 + (112 - 1.172f * 112) / 2 + .928f * 112, t);
        try {
            t.setLetterSpacing(0);
        } catch (LinkageError ignored) {
            // old Android
        }
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setTextSize(28);
        c.drawText(lage.name(), 104, 184 + .928f * 28, t);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextSize(16);
        List<String> z = WetterLage.zeilen(d);
        for (int i = 0; i < z.size(); i++) c.drawText(z.get(i), 104, 224 + i * 24.8f + (24.8f - 18.75f) / 2 + .928f * 16, t);
        return b;
    }

    // ---- particles ----------------------------------------------------------------------------------------------------

    private void partikel() {
        WetterLage w = lage;
        long[] seed = {7};
        java.util.function.DoubleSupplier rnd = () -> {
            seed[0] = (seed[0] * 9301 + 49297) % 233280;
            return seed[0] / 233280.0;
        };
        tropfen = new float[w.tropfen][];
        for (int i = 0; i < w.tropfen; i++) {
            float x = (float) (Math.round(rnd.getAsDouble() * 1100) - 40), dur = (float) (w.tropfenDauer + rnd.getAsDouble() * .3);
            tropfen[i] = new float[] {x, 0, dur, (float) rnd.getAsDouble(), (float) (.25 + rnd.getAsDouble() * .45)};
        }
        spritzer = new float[w.spritzer][];
        for (int i = 0; i < w.spritzer; i++) {
            spritzer[i] = new float[] {(float) Math.round(rnd.getAsDouble() * 940), (float) Math.round(306 + rnd.getAsDouble() * 30), (float) (.5 + rnd.getAsDouble() * .4), (float) rnd.getAsDouble()};
        }
        flocken = new float[w.flocken][];
        for (int i = 0; i < w.flocken; i++) {
            float s = (float) (3 + rnd.getAsDouble() * 5);
            flocken[i] = new float[] {(float) Math.round(rnd.getAsDouble() * 980), s, (float) (7 + rnd.getAsDouble() * 7), (float) (rnd.getAsDouble() * 14), (float) (.5 + rnd.getAsDouble() * .5)};
        }
        hagel = new float[w.hagel][];
        for (int i = 0; i < w.hagel; i++) {
            float s = (float) (5 + rnd.getAsDouble() * 5);
            hagel[i] = new float[] {(float) Math.round(rnd.getAsDouble() * 960), s, (float) (.8 + rnd.getAsDouble() * .5), (float) (rnd.getAsDouble() * 1.3)};
        }
        int nRinge = w.tropfen > 0 || w.hagel > 0 ? (w.stark ? 26 : 14) : 0;
        ringe = new float[nRinge][];
        for (int i = 0; i < nRinge; i++) {
            ringe[i] = new float[] {(float) (rnd.getAsDouble() * W * .97), (float) (HZ + 6 + rnd.getAsDouble() * (300 - HZ - 16)), (float) (rnd.getAsDouble() * 1.4)};
        }
        glanz = new float[26][];
        for (int i = 0; i < 26; i++) {
            double tt = rnd.getAsDouble();
            glanz[i] = new float[] {(float) (rnd.getAsDouble() * W * .95), (float) (HZ + 4 + tt * (300 - HZ - 8)), (float) ((14 + rnd.getAsDouble() * 60) * (.5 + tt * .7)), (float) (1 + tt * .5), (float) (rnd.getAsDouble() * 4)};
        }
        blaetter = new float[w.blaetter][];
        for (int i = 0; i < w.blaetter; i++) {
            blaetter[i] = new float[] {(float) Math.round(60 + rnd.getAsDouble() * 260), (float) (3.5 + rnd.getAsDouble() * 3), (float) (rnd.getAsDouble() * 6)};
        }
        if (w.wind) {
            boeen = new Shader[10];
            for (int i = 0; i < 10; i++) {
                float breite = 120 + (i * 53 % 220);
                boeen[i] = new LinearGradient(0, 0, breite, 0, new int[] {0x00FFFFFF, 0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
            }
        } else {
            boeen = null;
        }
        // stars: fixed sky positions with their own twinkle
        double[][] hg = Himmel.hintergrund(300);
        sterne = new float[hg.length][];
        for (int i = 0; i < hg.length; i++) {
            sterne[i] = new float[] {(float) hg[i][0], (float) hg[i][1], (float) hg[i][2], (float) hg[i][3], (float) (.6 + rnd.getAsDouble()), (float) (rnd.getAsDouble() * 6.3)};
        }
        // thunderstorm flashes (wetter.js BZ: 4 per 24 s, each with its own flicker)
        blitze.clear();
        if (w.blitz) {
            Wolken.Zufall R = new Wolken.Zufall(tagNummer() * 13 + 5);
            float[] zeiten = {1.6f + (float) R.next() * 2, 7 + (float) R.next() * 2.5f, 12.5f + (float) R.next() * 2.5f, 18.5f + (float) R.next() * 3};
            for (int i = 0; i < 4; i++) {
                float a = .55f + (float) R.next() * .45f;
                List<float[]> pat = new ArrayList<>();
                pat.add(new float[] {0, 0});
                pat.add(new float[] {.03f, a});
                pat.add(new float[] {.1f, a * .9f});
                pat.add(new float[] {.16f, a * .15f});
                pat.add(new float[] {.23f, a * (.6f + (float) R.next() * .4f)});
                pat.add(new float[] {.31f, a * .25f});
                if (R.next() < .5) pat.add(new float[] {.38f, a * .7f});
                pat.add(new float[] {pat.get(pat.size() - 1)[0] + .12f, 0});
                float[][] e = new float[pat.size() + 1][];
                float x = 130 + (i * 190 + (float) R.next() * 330) % 700, y = Wolken.YH * (.2f + (float) R.next() * .3f), s = .75f + (float) R.next() * .5f;
                e[0] = new float[] {x, y, s * .76f, a, zeiten[i]};
                for (int j = 0; j < pat.size(); j++) e[j + 1] = pat.get(j);
                blitze.add(e);
            }
        }
    }

    private static int tagNummer() {
        long ms = System.currentTimeMillis();
        return (int) Math.floor((ms + Calendar.getInstance().getTimeZone().getOffset(ms)) / 864e5);
    }
}
