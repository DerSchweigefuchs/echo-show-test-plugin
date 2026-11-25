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
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live weather scene "Meer" of the dashboard's weather page, drawn natively on a 963 x 584 grid: sky by the sun's real
 * altitude (night, blue hour, evening / morning glow, day, blended continuously), sun and moon on their real paths, stars
 * and constellations at their real place, drifting clouds, sea with a glitter path, lighthouse island, rocking boat,
 * beach and dune, rain / snow / hail / fog as particles.
 *
 * Load (0.3.1): only the clouds and the moon are bitmaps. Sky, sea, land and header are drawn directly from paths that
 * are built once, so the GPU keeps their geometry and nothing is rebuilt per frame. Each cloud row is one small bitmap
 * in a resolution matched to its softness (the bilinear upscale is the blur), cut to the band the row occupies, in the
 * colours of the current light only. The layers are shared by all open views and given back 30 s after the last one
 * closes. The first frame shows the scene at once; the clouds are built on a background thread and fade in.
 * The loop runs at 24 or 30 fps and stops completely while the view is not visible, the screensaver is up or the screen
 * is off. Main thread only, except the cloud and moon builds.
 */
final class WetterView extends View implements Choreographer.FrameCallback {
    static final float W = 963, H = 584, HZ = Himmel.HORIZONT, UNTEN = 362;
    private static final int FARBSTUFEN = 16;   // cloud colours are rebuilt in 16 steps between night and day

    interface Aktionen {
        void zurueck();
        boolean pausiert();
        /** Called once, after the first frame with weather data and all layers (clouds faded in). */
        void fertig(long aufbauMs);
    }

    // ==== layers shared by all open views (main thread; builds run on one background thread) ===========================

    private static final class Ebenen {
        int nutzer;                         // attached views
        String wolkenKey = "", bautKey;     // built / being built
        List<Wolken.Reihe> reihen = new ArrayList<>();
        Bitmap[] wolken = new Bitmap[0];
        float[] wolkenQ = new float[0], wolkenY0 = new float[0];
        Bitmap[] vorher;                    // the previous set while it fades out
        float[] vorherQ, vorherY0;
        List<Wolken.Reihe> vorherReihen;
        long wechselSeit;                   // uptime of the last swap
        int version;                        // counts swaps, so views rebuild their row filters
        Bitmap mond;
        String mondKey = "";
        boolean mondBaut;
        final List<Object[]> alt = new ArrayList<>();   // {Bitmap[], recycle after this draw count}
    }

    private static final Ebenen E = new Ebenen();
    private static Handler haupt;
    private static ExecutorService bauer;
    private static long zeichnungen;        // onDraw calls of all views, for the deferred recycling
    private static final long FREIGABE_MS = 30_000L;
    private static final Runnable FREIGABE = WetterView::allesFrei;

    private static synchronized ExecutorService bauer() {
        if (bauer == null) bauer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wetter-ebenen");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
        return bauer;
    }

    private static Handler haupt() {
        if (haupt == null) haupt = new Handler(Looper.getMainLooper());
        return haupt;
    }

    /** The plugin stops (any thread): end the build thread now, give the bitmaps back on the main thread. */
    static synchronized void beenden() {
        if (bauer != null) bauer.shutdownNow();
        bauer = null;
        haupt().post(() -> {
            if (E.nutzer == 0) allesFrei();
        });
    }

    private static void allesFrei() {
        if (E.nutzer > 0) return;
        recyceln(E.wolken);
        recyceln(E.vorher);
        for (Object[] a : E.alt) recyceln((Bitmap[]) a[0]);
        E.alt.clear();
        if (E.mond != null) E.mond.recycle();
        E.mond = null;
        E.mondKey = "";
        E.wolken = new Bitmap[0];
        E.wolkenQ = E.wolkenY0 = new float[0];
        E.reihen = new ArrayList<>();
        E.vorher = null;
        E.wolkenKey = "";
        E.bautKey = null;
    }

    private static void recyceln(Bitmap[] b) {
        if (b == null) return;
        for (Bitmap x : b) if (x != null && !x.isRecycled()) x.recycle();
    }

    /** Replaced bitmaps are recycled once every view has drawn a few new frames without them. */
    private static void spaeterRecyceln(Bitmap[] b) {
        if (b != null) E.alt.add(new Object[] {b, zeichnungen + 6});
    }

    static long bitmapBytes() {
        long n = 0;
        for (Bitmap b : E.wolken) if (b != null && !b.isRecycled()) n += b.getAllocationByteCount();
        if (E.vorher != null) for (Bitmap b : E.vorher) if (b != null && !b.isRecycled()) n += b.getAllocationByteCount();
        for (Object[] a : E.alt) for (Bitmap b : (Bitmap[]) a[0]) if (b != null && !b.isRecycled()) n += b.getAllocationByteCount();
        if (E.mond != null && !E.mond.isRecycled()) n += E.mond.getAllocationByteCount();
        return n;
    }

    // ==== this view ===================================================================================================

    private final Aktionen aktionen;
    private WetterLage.Daten daten;
    private WetterLage lage = new WetterLage("cloudy");
    private final String modus;        // "live", "zeitraffer" or "fest"
    private final long festZeit;
    private final long rafferStart = System.currentTimeMillis(), erstellt = SystemClock.uptimeMillis();
    private final boolean namen;
    private final long intervallNs;
    private final int monat = Calendar.getInstance().get(Calendar.MONTH);
    String mess1 = "", mess2 = "";

    // state of the moment
    private long simZeit, momentUm = -1;
    private double sonneAlt = 30, sonneAz = 180;
    private boolean steigt, koordinaten, mondDa;
    private Himmel.Mond mond;
    private WetterLage.Licht licht;
    private float sx, sy, mx, my;

    // colours and shaders of the light (rebuilt when the sun moved by 0.05 deg)
    private String himmelKey = "";
    private Shader himmelShader, sonnenShader, deckTag, deckNacht, deckBlau, meerShader, dunkelShader;
    private LightingColorFilter[] wolkenFilter = new LightingColorFilter[0];
    private int cInsel, cTurm, cRot, cGalerie, cLampe, cSand, cDuene, cGras, cMuschel, cRumpf, cSegel, cFock, cPfuetze;
    private boolean nachtKopf;

    // clouds as this view sees them
    private boolean wolkenSchonDa;     // the shared clouds were there when this view opened (no fade)
    private long wolkenSichtbarSeit = -1;
    private int tagStufe = -1, version = -1;
    private String grund = "";          // cloud key without the colour step: weather / day / moon / scale

    // header texts
    private String tOrt = "", tTemp = "", tZustand = "";
    private List<String> tZeilen = new ArrayList<>();

    // particles (seeded; positions follow from the time)
    private float[][] tropfen, flocken, hagel, spritzer, ringe, blaetter, glanz, sterne;
    private final List<float[][]> blitze = new ArrayList<>();
    private Shader[] boeen, nebelBaender, blitzGlut;
    private Shader dunst;
    private long sternZeit = Long.MIN_VALUE;
    private float[][] sternXY;
    private float[][][] bildXY;

    // loop and measurement
    private boolean laeuft, angehaengt, fensterSichtbar = true, fertigGemeldet;
    private long letzterFrame, aufbauMs = -1, letzteZeichnung, sekStart;
    private int bilderSek, fps, rucklerGesamt, bilderGesamt;
    private long maxLuecke;
    private final long[] dauern = new long[150];   // draw time of the last frames, ns
    private int dauerN;
    private float medianMs, p95Ms;
    private long cpuWand, cpuTicks = -1, cpuHaupt;
    private float cpuProzess = -1, cpuHauptProzent = -1;
    private String szene = "";

    // paint (built once)
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bild = new Paint(Paint.FILTER_BITMAP_FLAG), linie = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), tOrtP, tTempP, tZustandP, tZeilenP;
    private final Typeface schrift = Typeface.create("sans-serif", Typeface.NORMAL), schriftMittel = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Shader turmGlut = new RadialGradient(112, 173, 10, 0xBFFFC86E, 0x00FFC86E, Shader.TileMode.CLAMP);
    private final Shader ausblenden = new LinearGradient(0, 292, 0, UNTEN, 0x000D1720, 0xFF0D1720, Shader.TileMode.CLAMP);
    private final RectF r = new RectF();
    private float skala = 1, dx, dy, downX, downY;

    // ==== geometry built once (the GPU keeps static paths; nothing is re-filled per frame) ============================

    private static final Path INSEL = new Path(), TURM = new Path(), TURM_ROT = new Path(), STRAND = new Path(), NASS = new Path(), DUENE = new Path(),
        GRAS = new Path(), GLANZ = new Path(), KAMM = new Path(), MUSCHELN = new Path(), PFUETZEN = new Path(), BRANDUNG1 = new Path(), BRANDUNG2 = new Path(),
        KEGEL_L = new Path(), KEGEL_R = new Path(), RUMPF = new Path(), SEGEL = new Path(), FOCK = new Path(), BLATT = new Path(), PFEIL = new Path();

    static {
        INSEL.moveTo(30, HZ + 1);
        INSEL.cubicTo(60, 205, 110, 199, 150, 205);
        INSEL.cubicTo(175, 209, 190, 211, 204, HZ + 1);
        INSEL.close();
        TURM.moveTo(107.5f, 204); TURM.lineTo(109.5f, 178); TURM.lineTo(114.5f, 178); TURM.lineTo(116.5f, 204); TURM.close();
        TURM_ROT.moveTo(108.4f, 196); TURM_ROT.lineTo(115.6f, 196); TURM_ROT.lineTo(115.2f, 190); TURM_ROT.lineTo(108.8f, 190); TURM_ROT.close();
        TURM_ROT.moveTo(109.1f, 186); TURM_ROT.lineTo(114.9f, 186); TURM_ROT.lineTo(114.6f, 181); TURM_ROT.lineTo(109.4f, 181); TURM_ROT.close();
        TURM_ROT.moveTo(109, 170); TURM_ROT.lineTo(112, 166); TURM_ROT.lineTo(115, 170); TURM_ROT.close();
        STRAND.moveTo(0, 296);
        STRAND.cubicTo(150, 290, 300, 298, 450, 294);
        STRAND.cubicTo(560, 291, 640, 292, 700, 290);
        STRAND.lineTo(W, 290); STRAND.lineTo(W, UNTEN); STRAND.lineTo(0, UNTEN); STRAND.close();
        NASS.moveTo(0, 296);
        NASS.cubicTo(150, 290, 300, 298, 450, 294);
        NASS.cubicTo(560, 291, 640, 292, 700, 290);
        NASS.lineTo(700, 296);
        NASS.cubicTo(620, 298, 540, 297, 450, 300);
        NASS.cubicTo(300, 304, 150, 296, 0, 302);
        NASS.close();
        DUENE.moveTo(590, 310);
        DUENE.cubicTo(650, 290, 720, 282, 790, 283);
        DUENE.cubicTo(850, 284, 910, 280, W, 276);
        DUENE.lineTo(W, UNTEN); DUENE.lineTo(590, UNTEN); DUENE.close();
        Wolken.Zufall R = new Wolken.Zufall(26);
        for (int i = 0; i < 26; i++) {   // still shimmer on the water (waterFx)
            double tt = R.next();
            float y = (float) (HZ + 4 + tt * (300 - HZ - 8)), x = (float) (R.next() * W * .95), ww = (float) ((14 + R.next() * 60) * (.5 + tt * .7));
            GLANZ.addRect(x, y, x + ww, y + 1 + (float) tt * .5f, Path.Direction.CW);
        }
        for (int i = 0; i < 18; i++) {   // wave crests in wind, storm or downpour
            float x = (float) (R.next() * 940), y = (float) (HZ + 10 + R.next() * 70);
            KAMM.moveTo(x, y);
            KAMM.quadTo(x + 4, y - 3, x + 8, y);
        }
        for (float x = 610; x < W; x += (float) (9 + R.next() * 14)) {   // beach grass on the dune (gap for the figure)
            float y = x < 790 ? 310 - (x - 590) / 200 * 27 : 283 - (x - 790) / 173 * 7;
            int n = 3 + (int) Math.floor(R.next() * 3);
            if (x > 790 && x < 852) continue;
            for (int k = 0; k < n; k++) {
                float h = (float) (10 + R.next() * 10), ddx = (k - n / 2f) * 3;
                GRAS.moveTo(x, y + 2);
                GRAS.rQuadTo(ddx * .4f, -h * .6f, ddx, -h);
            }
        }
        float[][] mu = {{300, 318, 3}, {480, 326, 2.4f}, {660, 318, 2.6f}};
        for (float[] m : mu) MUSCHELN.addOval(new RectF(m[0] - m[2], m[1] - m[2] * .6f, m[0] + m[2], m[1] + m[2] * .6f), Path.Direction.CW);
        float[][] pf = {{220, 320, 34}, {500, 324, 26}};
        for (float[] m : pf) PFUETZEN.addOval(new RectF(m[0] - m[2], m[1] - m[2] * .18f, m[0] + m[2], m[1] + m[2] * .18f), Path.Direction.CW);
        BRANDUNG1.moveTo(0, 293);
        BRANDUNG1.cubicTo(120, 287, 250, 296, 380, 291);
        BRANDUNG1.cubicTo(500, 287, 610, 291, 720, 288);
        BRANDUNG2.moveTo(40, 284);
        BRANDUNG2.cubicTo(160, 280, 290, 287, 420, 283);
        BRANDUNG2.cubicTo(540, 279, 640, 283, 740, 281);
        KEGEL_L.moveTo(112, 173); KEGEL_L.lineTo(-40, 160); KEGEL_L.lineTo(-40, 186); KEGEL_L.close();
        KEGEL_R.moveTo(112, 173); KEGEL_R.lineTo(330, 158); KEGEL_R.lineTo(330, 188); KEGEL_R.close();
        RUMPF.moveTo(549, HZ - 1); RUMPF.lineTo(573, HZ - 1); RUMPF.lineTo(569, HZ + 3); RUMPF.lineTo(553, HZ + 3); RUMPF.close();
        SEGEL.moveTo(561, HZ - 2); SEGEL.lineTo(561, HZ - 26); SEGEL.lineTo(572, HZ - 3); SEGEL.close();
        FOCK.moveTo(559.5f, HZ - 2); FOCK.lineTo(559.5f, HZ - 21); FOCK.lineTo(551, HZ - 3); FOCK.close();
        BLATT.moveTo(-7, 4); BLATT.quadTo(-7, -4, 7, -4); BLATT.quadTo(7, 4, -7, 4); BLATT.close();
        PFEIL.moveTo(15.41f, 16.58f); PFEIL.lineTo(10.83f, 12); PFEIL.lineTo(15.41f, 7.41f); PFEIL.lineTo(14, 6); PFEIL.lineTo(8, 12); PFEIL.lineTo(14, 18); PFEIL.close();
    }

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
        tOrtP = kopfText(Typeface.create("sans-serif", Typeface.BOLD), 22);
        tTempP = kopfText(Typeface.create("sans-serif-light", Typeface.NORMAL), 112);
        try {
            tTempP.setLetterSpacing(-3 / 112f);
        } catch (LinkageError ignored) {
            // old Android
        }
        tZustandP = kopfText(Typeface.create("sans-serif", Typeface.BOLD), 28);
        tZeilenP = kopfText(schriftMittel, 16);
        text.setTypeface(schrift);
        setClickable(true);
        partikel();
        wolkenSchonDa = E.wolken.length > 0;
    }

    private static Paint kopfText(Typeface tf, float sp) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(tf);
        t.setTextSize(sp);
        t.setColor(0xFFECEBE8);
        t.setShadowLayer(6, 0, 1, 0x59000000);
        return t;
    }

    /** New data from Home Assistant (main thread). */
    void setDaten(WetterLage.Daten d) {
        boolean neueLage = d == null ? daten != null : !d.zustand.equals(lage.cond);
        daten = d;
        if (neueLage) {
            lage = new WetterLage(d == null ? "cloudy" : d.zustand);
            partikel();
            himmelKey = "";
        }
        if (d != null) {
            tOrt = d.name;
            tTemp = (d.temp == null ? "–" : String.valueOf(Math.round(d.temp))) + "°";
            tZustand = lage.name();
            tZeilen = WetterLage.zeilen(d);
        }
        momentUm = -1;
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
        E.nutzer++;
        haupt().removeCallbacks(FREIGABE);
        schleife();
    }

    @Override protected void onDetachedFromWindow() {
        angehaengt = false;
        schleife();
        E.nutzer = Math.max(0, E.nutzer - 1);
        if (E.nutzer == 0) {
            haupt().removeCallbacks(FREIGABE);
            haupt().postDelayed(FREIGABE, FREIGABE_MS);
        }
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

    // ---- measurement --------------------------------------------------------------------------------------------------

    @Override protected void onDraw(Canvas c) {
        long t0 = System.nanoTime();
        try {
            zeichnen(c);
        } catch (RuntimeException ignored) {
            // a drawing error must not end the app
        }
        zeichnungen++;
        messen(t0);
        try {
            aufraeumen();
        } catch (RuntimeException ignored) {
            // never fail a frame over bookkeeping
        }
    }

    private static void aufraeumen() {
        for (int i = E.alt.size() - 1; i >= 0; i--) {
            Object[] a = E.alt.get(i);
            if ((Long) a[1] <= zeichnungen) {
                recyceln((Bitmap[]) a[0]);
                E.alt.remove(i);
            }
        }
    }

    private void messen(long t0) {
        long jetzt = System.nanoTime();
        dauern[dauerN++ % dauern.length] = jetzt - t0;
        if (letzteZeichnung != 0) {
            long luecke = jetzt - letzteZeichnung;
            bilderGesamt++;
            if (laeuft && luecke > intervallNs * 3 / 2 + 4_000_000L) rucklerGesamt++;
            if (luecke > maxLuecke) maxLuecke = luecke;
        }
        letzteZeichnung = jetzt;
        if (sekStart == 0) sekStart = jetzt;
        bilderSek++;
        if (jetzt - sekStart >= 1_000_000_000L) {
            fps = Math.round(bilderSek * 1e9f / (jetzt - sekStart));
            bilderSek = 0;
            sekStart = jetzt;
            perzentile();
            cpu();
            szene = String.format(Locale.ROOT, "Szene %d fps (Ziel %d) · Frame %.1f / %.1f ms (Median / 95 %%) · Ruckler %d · CPU Prozess %s · Hauptthread %s · Bitmaps %d KB",
                fps, Math.round(1e9f / intervallNs), medianMs, p95Ms, rucklerGesamt, pct(cpuProzess), pct(cpuHauptProzent), bitmapBytes() / 1024);
        }
    }

    private static String pct(float v) { return v < 0 ? "–" : Math.round(v) + " %"; }

    private void perzentile() {
        int n = Math.min(dauerN, dauern.length);
        if (n == 0) return;
        long[] s = Arrays.copyOf(dauern, n);
        Arrays.sort(s);
        medianMs = s[n / 2] / 1e6f;
        p95Ms = s[Math.min(n - 1, (int) Math.ceil(n * .95) - 1)] / 1e6f;
    }

    /**
     * CPU over the last window: the whole process from /proc/self/stat (utime + stime, 100 ticks per second; KS, Flutter
     * and this plugin together, the WebView renders in its own process) and the main thread, which records the frames.
     * In percent of one core.
     */
    private void cpu() {
        long wand = SystemClock.elapsedRealtime(), haupt = Debug.threadCpuTimeNanos(), ticks = prozessTicks();
        if (cpuWand > 0 && wand > cpuWand) {
            float sek = (wand - cpuWand) / 1000f;
            if (ticks >= 0 && cpuTicks >= 0) cpuProzess = (ticks - cpuTicks) / 100f / sek * 100;
            if (haupt > 0 && cpuHaupt > 0) cpuHauptProzent = (haupt - cpuHaupt) / 1e9f / sek * 100;
        }
        cpuWand = wand;
        cpuTicks = ticks;
        cpuHaupt = haupt;
    }

    static long prozessTicks() {
        byte[] b = new byte[1024];
        int n;
        try (FileInputStream in = new FileInputStream("/proc/self/stat")) {
            n = in.read(b);
        } catch (Exception e) {
            return -1;
        }
        if (n <= 0) return -1;
        String s = new String(b, 0, n);
        int z = s.lastIndexOf(')');
        if (z < 0) return -1;
        String[] f = s.substring(z + 2).trim().split(" +");
        try {
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);   // fields 14 and 15: utime, stime
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Figures for "bereit" and the status line. */
    Map<String, Object> kennzahlen() {
        perzentile();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("szene_fps", fps);
        m.put("frame_median_ms", Math.round(medianMs * 10) / 10.0);
        m.put("frame_p95_ms", Math.round(p95Ms * 10) / 10.0);
        m.put("ruckler", rucklerGesamt);
        m.put("cpu_prozess_pct", cpuProzess < 0 ? null : Math.round(cpuProzess));
        m.put("cpu_haupt_pct", cpuHauptProzent < 0 ? null : Math.round(cpuHauptProzent));
        m.put("cpu_kerne", Runtime.getRuntime().availableProcessors());
        m.put("bitmap_kb", bitmapBytes() / 1024);
        return m;
    }

    // ---- frame ---------------------------------------------------------------------------------------------------------

    private void zeichnen(Canvas c) {
        float neu = Math.min(getWidth() / W, getHeight() / H);
        if (neu <= 0) return;
        if (neu != skala) momentUm = -1;
        skala = neu;
        dx = (getWidth() - W * skala) / 2;
        dy = (getHeight() - H * skala) / 2;
        long anim = SystemClock.uptimeMillis();
        float t = anim / 1000f;
        if (cpuWand == 0) cpu();
        moment(anim);
        ebenen(anim);

        c.drawColor(0xFF0D1720);
        c.save();
        c.translate(dx, dy);
        c.scale(skala, skala);
        c.save();
        c.clipRect(0, 0, W, UNTEN);
        mitShader(himmelShader);
        c.drawRect(0, 0, W, UNTEN, p);
        p.setShader(null);
        sonne(c, t);
        sternenhimmel(c, t);
        mondZeichnen(c);
        wolken(c, t, anim);
        blitzWolken(c, t);
        land(c);
        meer(c, t);
        leuchtturm(c, t);
        boot(c, t);
        niederschlag(c, t);
        c.restore();
        kopf(c);
        anzeigen(c);
        c.restore();

        boolean wolkenFertig = lage.cover <= 0 || (E.wolken.length > 0 && E.wolkenKey.startsWith(wolkenGrund()) && wolkenAlpha(anim) >= 1);
        boolean mondFertig = !mondDa || E.mond != null;
        if (!fertigGemeldet && daten != null && wolkenFertig && mondFertig) {
            fertigGemeldet = true;
            aufbauMs = anim - erstellt;
            aktionen.fertig(aufbauMs);
        }
    }

    /** Time, sun, moon and light. Live they change slowly, so they are recomputed four times a second. */
    private void moment(long anim) {
        if ("live".equals(modus) && momentUm >= 0 && anim - momentUm < 250) return;
        if ("fest".equals(modus) && momentUm >= 0) return;
        momentUm = anim;
        if ("zeitraffer".equals(modus)) simZeit = rafferStart + (anim - erstellt) * 1440L % 86_400_000L;
        else if ("fest".equals(modus)) simZeit = festZeit;
        else simZeit = System.currentTimeMillis();
        WetterLage.Daten d = daten;
        koordinaten = d != null && d.lat != null && d.lon != null;
        if (koordinaten) {
            double[] s = Himmel.sonne(simZeit, d.lat, d.lon);
            sonneAlt = s[0];
            sonneAz = s[1];
            // live: anchored to sun.sun; between HA's updates the local ephemeris moves the sun on smoothly
            if ("live".equals(modus) && d.sonneHoehe != null && d.empfangen > 0) {
                double[] h = Himmel.sonne(d.empfangen, d.lat, d.lon);
                sonneAlt += Math.max(-2, Math.min(2, d.sonneHoehe - h[0]));
                if (d.sonneAzimut != null) sonneAz += Math.max(-3, Math.min(3, Himmel.norm180(d.sonneAzimut - h[1])));
            }
            steigt = Himmel.sonne(simZeit + 600_000L, d.lat, d.lon)[0] > s[0];
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
        int tagNr = (int) Math.floor((simZeit + Calendar.getInstance().getTimeZone().getOffset(simZeit)) / 864e5);
        grund = lage.cond + "/" + tagNr + "/" + mondDa + "/" + skala + "/";
    }

    // ---- light-dependent colours and the shared layers -----------------------------------------------------------------

    private String wolkenGrund() { return grund; }

    private void ebenen(long anim) {
        WetterLage.Licht L = licht;
        // clouds first: a new colour step or a new set of rows needs new row filters below: geometry per weather and day, one colour version for the light of now (16 steps night .. day)
        if (lage.cover > 0) {
            int stufe = Math.round(L.tag * FARBSTUFEN);
            String wk = wolkenGrund() + stufe;
            if (!wk.equals(E.wolkenKey) && !wk.equals(E.bautKey) && E.bautKey == null) wolkenBauen(wk, stufe);
            if (stufe != tagStufe || version != E.version) {
                tagStufe = stufe;
                version = E.version;
                himmelKey = "";   // new row filters
            }
        }
        String hk = Math.round(Math.max(-15, Math.min(15, sonneAlt)) * 20) + (steigt ? "s" : "f") + lage.cond + mondDa;
        if (!hk.equals(himmelKey) || himmelShader == null) {
            himmelKey = hk;
            farben(L);
        }
        // moon: phase and turn change slowly
        if (mondDa) {
            String mk = Math.round(mond.f * 50) + "/" + Math.round(mond.limb / 3f) + "/" + Math.round(mond.face / 3f) + "/" + skala;
            if (!mk.equals(E.mondKey)) {
                if (E.mond == null) {   // small: at once, so the first frame has it
                    E.mond = mondBauen(mond, skala);
                    E.mondKey = mk;
                } else if (!E.mondBaut) {
                    E.mondBaut = true;
                    final Himmel.Mond mo = mond;
                    final float sk = skala;
                    try {
                        bauer().execute(() -> {
                            Bitmap b = null;
                            try { b = mondBauen(mo, sk); } catch (RuntimeException ignored) {}
                            final Bitmap fb = b;
                            haupt().post(() -> {
                                E.mondBaut = false;
                                if (fb == null) return;
                                if (E.mond != null) spaeterRecyceln(new Bitmap[] {E.mond});
                                E.mond = fb;
                                E.mondKey = mk;
                            });
                        });
                    } catch (RuntimeException ended) {
                        E.mondBaut = false;
                    }
                }
            }
        }
    }

    private void wolkenBauen(String key, int stufe) {
        final WetterLage lg = lage;
        final boolean md = mondDa;
        final float sk = skala, tag = stufe / (float) FARBSTUFEN;
        final int tagNr = Integer.parseInt(key.split("/")[1]);
        E.bautKey = key;
        try {
            bauer().execute(() -> {
                List<Wolken.Reihe> rh = null;
                Object[] b = null;
                try {
                    rh = Wolken.bauen(lg, tagNr);
                    b = wolkenBilder(rh, lg, md, sk, tag);
                } catch (RuntimeException ignored) {
                    // try again with the next frame
                }
                final List<Wolken.Reihe> frh = rh;
                final Object[] fb = b;
                haupt().post(() -> {
                    if (!key.equals(E.bautKey)) return;
                    E.bautKey = null;
                    if (fb == null) return;
                    if (E.wolken.length > 0) {   // the old set fades out over the new one
                        spaeterRecyceln(E.vorher);
                        E.vorher = E.wolken;
                        E.vorherQ = E.wolkenQ;
                        E.vorherY0 = E.wolkenY0;
                        E.vorherReihen = E.reihen;
                    }
                    E.reihen = frh;
                    E.wolken = (Bitmap[]) fb[0];
                    E.wolkenQ = (float[]) fb[1];
                    E.wolkenY0 = (float[]) fb[2];
                    E.wolkenKey = key;
                    E.wechselSeit = SystemClock.uptimeMillis();
                    E.version++;
                });
            });
        } catch (RuntimeException ended) {
            E.bautKey = null;
        }
    }

    private void farben(WetterLage.Licht L) {
        // sky: the page's gradient (0 % sk0, 30 % sk1, 55 % sk2, 62 % #0d1720) with the overlays of the hour, sampled every 26 px
        int n = 15;
        int[] f = new int[n];
        float[] pos = new float[n];
        for (int i = 0; i < n; i++) {
            float y = UNTEN * i / (n - 1f), q = y / H;
            int c = q <= .3f ? WetterLage.mix(L.himmel[0], L.himmel[1], q / .3f)
                : (q <= .55f ? WetterLage.mix(L.himmel[1], L.himmel[2], (q - .3f) / .25f) : WetterLage.mix(L.himmel[2], 0xFF0D1720, Math.min(1, (q - .55f) / .07f)));
            f[i] = WetterLage.tont(c, y, L);
            pos[i] = i / (n - 1f);
        }
        himmelShader = new LinearGradient(0, 0, 0, UNTEN, f, pos, Shader.TileMode.CLAMP);
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
        // cloud rows take the light of the hour through a colour filter (the bitmaps stay)
        List<Wolken.Reihe> rh = E.reihen;
        wolkenFilter = new LightingColorFilter[rh.size()];
        float stufeTag = Math.round(L.tag * FARBSTUFEN) / (float) FARBSTUFEN;
        for (int i = 0; i < rh.size(); i++) {
            float y = (float) (Wolken.YH * (.16 + .84 * Math.pow(rh.get(i).d, .7)));
            int base = WetterLage.mix(wn[1], wt[1], stufeTag), soll = WetterLage.tont(WetterLage.mix(wn[1], wt[1], L.tag), y, L);
            wolkenFilter[i] = filter(base, soll);
        }
        // land, sea, boat
        WetterLage.Land P = lage.land(L, mondDa, monat);
        float nacht = 1 - L.tag;
        int himmelUnten = WetterLage.tont(WetterLage.mix(L.himmel[1], L.himmel[2], (HZ / H - .3f) / .25f), HZ, L);
        meerShader = new LinearGradient(0, HZ, 0, 300, WetterLage.mix(WetterLage.tont(P.lakeA, HZ + 4, L), himmelUnten, .12f),
            WetterLage.mix(WetterLage.tont(P.lakeB, 300, L), WetterLage.tont(L.himmel[0], 300, L), .05f), Shader.TileMode.CLAMP);
        cInsel = WetterLage.tont(P.mid, 205, L);
        cTurm = WetterLage.tont(WetterLage.mix(0xFFEFECE4, 0xFF4A4E58, nacht), 190, L);
        cRot = WetterLage.tont(WetterLage.mix(0xFFC94A3C, 0xFF4A2A2C, nacht), 190, L);
        cGalerie = WetterLage.mix(0xFF3A3A40, 0xFF22242A, nacht);
        cLampe = WetterLage.mix(WetterLage.mix(0xFFC9D3DC, 0xFF555C66, nacht), 0xFFFFE7A0, L.leuchtturm);
        int sandTag = WetterLage.mix(P.stone, 0xFFD9C08C, "tag".equals(lage.landTag) ? .45f : .25f);
        int sand = lage.schnee ? P.hillA : WetterLage.mix(WetterLage.mix(P.stone, P.hillB, .45f), sandTag, L.tag);
        cSand = WetterLage.tont(sand, 320, L);
        cDuene = WetterLage.tont(lage.schnee ? P.hillA : WetterLage.mix(sand, P.hillB, .18f), 310, L);
        cGras = WetterLage.tont(WetterLage.mix(P.grass, 0xFFC9B98A, .35f), 290, L);
        cMuschel = WetterLage.tont(WetterLage.mix(0xFFEFE9DE, 0xFF6A6C74, nacht), 320, L);
        cPfuetze = WetterLage.alpha(P.lakeB, .6f);
        cRumpf = WetterLage.tont(WetterLage.mix(0xFF22252C, 0xFF3A3F48, L.tag), HZ, L);
        cSegel = WetterLage.tont(WetterLage.mix(0xFF5A6070, 0xFFF4F2EC, L.tag), HZ - 14, L);
        cFock = WetterLage.tont(WetterLage.mix(0xFF50566A, 0xFFE6E2D8, L.tag), HZ - 12, L);
        // dark patch behind the header (day / night / snow and fog)
        boolean nachtJetzt = L.tag < .5f;
        if (dunkelShader == null || nachtJetzt != nachtKopf) {
            nachtKopf = nachtJetzt;
            float dunkel = nachtJetzt ? .25f : (lage.schnee || lage.nebel ? .5f : .38f);
            dunkelShader = new RadialGradient(0, 0, 520, WetterLage.alpha(0xFF080E16, dunkel), 0x00080E16, Shader.TileMode.CLAMP);
        }
    }

    /** Fill with a shader at full opacity (the paint's alpha would otherwise carry over from the last colour). */
    private void mitShader(Shader s) {
        p.setColor(0xFFFFFFFF);
        p.setShader(s);
    }

    /** A colour filter that turns colour a into colour b (multiply where b is darker, add where it is lighter). */
    private static LightingColorFilter filter(int a, int b) {
        int mul = 0, add = 0;
        for (int sh = 16; sh >= 0; sh -= 8) {
            int ca = Math.max(1, (a >> sh) & 255), cb = (b >> sh) & 255;
            mul |= Math.min(255, Math.round(255f * Math.min(cb, ca) / ca)) << sh;
            add |= Math.max(0, cb - ca) << sh;
        }
        return new LightingColorFilter(mul, add);
    }

    // ---- the layers of a frame ------------------------------------------------------------------------------------------

    private void sonne(Canvas c, float t) {
        if (!lage.sonneMoeglich || sonneAlt < -3) return;
        float a = WetterLage.glatt(-3, .5, sonneAlt) * (1 - lage.cover * .85f);
        if (a <= 0 || sx < -220 || sx > W + 220) return;
        float puls = .94f + .06f * (1 + (float) Math.sin(t * 2 * Math.PI / 18));   // sk-pulse 9 s alternate
        c.save();
        c.translate(sx, sy);
        c.scale(puls, puls);
        mitShader(sonnenShader);
        p.setAlpha(Math.round(255 * a));
        c.drawCircle(0, 0, 210, p);
        p.setShader(null);
        p.setAlpha(255);
        c.restore();
    }

    private void sternenhimmel(Canvas c, float t) {
        float a = licht.sterne * lage.sterne / 70f;
        if (a <= .01f || mond == null) return;
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
            if (xy == null || xy[0] < -3 || xy[0] > W + 3 || xy[1] > HZ - 3) continue;
            float tw = .72f + .28f * (float) Math.sin(t * s[4] + s[5]);
            p.setColor(WetterLage.alpha(0xFFFFFFFF, s[3] * tw * a));
            c.drawCircle(xy[0], xy[1], s[2], p);
        }
        // constellations: faint lines, coloured dots, names
        linie.setStrokeWidth(.9f);
        linie.setColor(WetterLage.alpha(0xFFBECDFF, .2f * a));
        text.setTextSize(10);
        text.setTypeface(schrift);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(WetterLage.alpha(0xFFBECDFF, .4f * a));
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
    }

    private void mondZeichnen(Canvas c) {
        if (!mondDa || E.mond == null || E.mond.isRecycled()) return;
        float a = 1 - .93f * licht.tag;   // by day only a pale disc
        if (a <= .02f) return;
        // the dark part of the moon hides the stars and lines behind it: a disc in the sky's own colour
        mitShader(himmelShader);
        p.setAlpha(Math.round(255 * a));
        c.drawCircle(mx, my, 43, p);
        p.setShader(null);
        p.setAlpha(255);
        bild.setAlpha(Math.round(255 * a));
        c.save();
        c.translate(mx - 80, my - 80);
        c.scale(1 / skala, 1 / skala);
        c.drawBitmap(E.mond, 0, 0, bild);
        c.restore();
        bild.setAlpha(255);
    }

    private float wolkenAlpha(long anim) {
        if (E.wolken.length == 0) return 0;
        if (wolkenSchonDa) return 1;
        if (wolkenSichtbarSeit < 0) wolkenSichtbarSeit = anim;
        return Math.min(1, (anim - wolkenSichtbarSeit) / 450f);
    }

    private void wolken(Canvas c, float t, long anim) {
        float tag = licht.tag;
        if (deckBlau != null && tag > 0) {
            mitShader(deckBlau);
            p.setAlpha(Math.round(255 * tag));
            c.drawRect(0, 0, W, Wolken.YH + 60, p);
        }
        if (deckNacht != null) {
            mitShader(deckNacht);
            c.drawRect(0, 0, W, Wolken.YH + 60, p);
            p.setShader(deckTag);
            p.setAlpha(Math.round(255 * tag));
            c.drawRect(0, 0, W, Wolken.YH + 60, p);
        }
        p.setShader(null);
        p.setAlpha(255);
        float ein = wolkenAlpha(anim);
        if (ein <= 0) return;
        float wechsel = Math.min(1, (anim - E.wechselSeit) / 1500f);
        if (E.vorher != null && wechsel < 1) reihenZeichnen(c, t, E.vorherReihen, E.vorher, E.vorherQ, E.vorherY0, ein);
        else if (E.vorher != null) {
            spaeterRecyceln(E.vorher);
            E.vorher = null;
        }
        reihenZeichnen(c, t, E.reihen, E.wolken, E.wolkenQ, E.wolkenY0, E.vorher != null ? ein * wechsel : ein);
    }

    private void reihenZeichnen(Canvas c, float t, List<Wolken.Reihe> rh, Bitmap[] bm, float[] q, float[] y0, float alpha) {
        for (int i = 0; i < rh.size() && i < bm.length; i++) {
            Bitmap b = bm[i];
            if (b == null || b.isRecycled()) continue;
            Wolken.Reihe reihe = rh.get(i);
            float tempo = lage.schnell ? 1700f / reihe.dauer : 600f / reihe.dauer;   // px per s, far rows slower
            float o = (t * tempo) % Wolken.BREITE, k = 1 / (skala * q[i]);
            bild.setColorFilter(i < wolkenFilter.length ? wolkenFilter[i] : null);
            bild.setAlpha(Math.round(255 * alpha));
            for (int kopie = 0; kopie < 2; kopie++) {
                float x0 = o - Wolken.BREITE * kopie;
                if (x0 > W || x0 + Wolken.BREITE < 0) continue;
                c.save();
                c.translate(x0, y0[i]);
                c.scale(k, k);
                c.drawBitmap(b, 0, 0, bild);
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
        for (int i = 0; i < blitze.size(); i++) {
            float[][] e = blitze.get(i);
            float op = blitzWert(e, zyk);
            if (op <= 0) continue;
            float[] k = e[0];   // {x, y, s, a, t0}
            for (int b = 0; b < 3; b++) {
                float bx = k[0] + (b - 1) * 120 * k[2], by = k[1] + (b == 1 ? 0 : 14) * k[2], rx = 160 * k[2] * (b == 1 ? 1 : .55f);
                c.save();
                c.translate(bx, by);
                c.scale(rx, rx);
                mitShader(blitzGlut[0]);
                p.setAlpha(Math.round(255 * op * (b == 1 ? 1 : .7f)));
                c.drawCircle(0, 0, 1, p);
                c.restore();
            }
            p.setShader(null);
            p.setColor(WetterLage.alpha(0xFFDCD6FF, .13f * k[3] * op));
            c.drawRect(0, 0, W, UNTEN, p);
        }
        p.setAlpha(255);
    }

    private static float blitzWert(float[][] e, float zyk) {
        float rel = zyk - e[0][4];
        if (rel < 0 || rel > .6f) return 0;
        for (int i = 2; i < e.length; i++) {
            if (rel <= e[i][0]) {
                float[] a = e[i - 1], b = e[i];
                return a[1] + (b[1] - a[1]) * (rel - a[0]) / Math.max(1e-3f, b[0] - a[0]);
            }
        }
        return 0;
    }

    /** Sea, island with the lighthouse, beach, dune and grass, drawn directly from the paths built once. */
    private void land(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        mitShader(meerShader);
        c.drawRect(0, HZ, W, 300, p);
        p.setShader(null);
        p.setColor(0x2EFFFFFF);
        c.drawRect(0, HZ - 1, W, HZ + 1, p);
        if (!lage.wind) {
            p.setColor(WetterLage.alpha(0xFFFFFFFF, .22f * .45f));
            c.drawPath(GLANZ, p);
        }
        if (lage.wind || lage.stark || lage.blitz) {
            linie.setStrokeWidth(1.2f);
            linie.setColor(0x8CFFFFFF);
            c.drawPath(KAMM, linie);
        }
        p.setColor(cInsel);
        c.drawPath(INSEL, p);
        p.setColor(cTurm);
        c.drawPath(TURM, p);
        p.setColor(cRot);
        c.drawPath(TURM_ROT, p);
        p.setColor(cGalerie);
        c.drawRect(108, 176, 116, 178, p);
        p.setColor(cLampe);
        c.drawRect(109.6f, 170, 114.4f, 176, p);
        p.setColor(cSand);
        c.drawPath(STRAND, p);
        p.setColor(0x1F000000);
        c.drawPath(NASS, p);
        p.setColor(cDuene);
        c.drawPath(DUENE, p);
        linie.setStrokeWidth(1.3f);
        linie.setColor(cGras);
        c.drawPath(GRAS, linie);
        p.setColor(cMuschel);
        c.drawPath(MUSCHELN, p);
        if (lage.stark) {
            p.setColor(cPfuetze);
            c.drawPath(PFUETZEN, p);
        }
        mitShader(ausblenden);
        c.drawRect(0, 292, W, UNTEN, p);
        p.setShader(null);
    }

    /** Glitter path under the sun or moon, surf, rain rings, wind shimmer. */
    private void meer(Canvas c, float t) {
        float sonneG = lage.sonneMoeglich ? WetterLage.glatt(-1, 2, sonneAlt) * (1 - lage.cover * .9f) : 0;
        float mondG = mondDa ? (float) mond.f * (1 - licht.tag) * (1 - lage.cover * .9f) : 0;
        if (sonneG > .02f && sx > -40 && sx < W + 40) glanzpfad(c, t, sx, sonneAlt < 8 ? 0xFFFFE2B0 : 0xFFFFF6E6, .55f * sonneG);
        if (mondG > .02f && mx > -40 && mx < W + 40) glanzpfad(c, t, mx, 0xFFF3EFD9, .55f * mondG);
        for (int i = 0; i < 2; i++) {   // surf (wl-surf: 4 s, the second line 2 s later)
            float ph = (float) ((t + i * 2) % 4 / 4.0), w = (float) (.5 - .5 * Math.cos(ph * 2 * Math.PI));
            linie.setStrokeWidth(i == 0 ? 2 : 1.4f);
            linie.setColor(WetterLage.alpha(0xFFFFFFFF, (i == 0 ? .6f : .35f) * (.9f - .5f * w)));
            c.save();
            c.translate(0, 4 * w);
            c.drawPath(i == 0 ? BRANDUNG1 : BRANDUNG2, linie);
            c.restore();
        }
        linie.setStrokeWidth(1);
        for (float[] g : ringe) {   // rain rings on the water (wl-rip, 1.4 s)
            float ph = ((t + g[2]) % 1.4f) / 1.4f, s = .2f + 1.3f * ph;
            linie.setColor(WetterLage.alpha(0xFFFFFFFF, .45f * (.9f * (1 - ph))));
            r.set(g[0] - 9 * s, g[1] - 2 * s, g[0] + 9 * s, g[1] + 2 * s);
            c.drawOval(r, linie);
        }
        if (lage.wind) {   // shimmer lines running over the sea (wl-wave)
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
        mitShader(turmGlut);
        p.setAlpha(Math.round(255 * an));
        c.drawCircle(112, 173, 10, p);
        p.setShader(null);
        for (int i = 0; i < 2; i++) {   // two beams sweeping out of phase (wl-beam: 8 s, visible 12..20 %)
            float ph = ((t + i * 4) % 8) / 8, op = ph < .12f ? ph / .12f : (ph <= .2f ? 1 : (ph < .32f ? (.32f - ph) / .12f : 0));
            if (op <= 0) continue;
            p.setColor(WetterLage.alpha(0xFFFFECAA, .16f * op * an));
            c.drawPath(i == 0 ? KEGEL_L : KEGEL_R, p);
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
        p.setColor(WetterLage.alpha(cRumpf, tag));
        c.drawPath(RUMPF, p);
        p.setColor(WetterLage.alpha(cSegel, tag));
        c.drawPath(SEGEL, p);
        p.setColor(WetterLage.alpha(cFock, tag));
        c.drawPath(FOCK, p);
        c.restore();
    }

    private void niederschlag(Canvas c, float t) {
        double sl = Math.toRadians(lage.schraeg);
        float vx = (float) -Math.sin(sl), vy = (float) Math.cos(sl);
        linie.setStrokeWidth(2);
        for (float[] d : tropfen) {   // rain: falls 660 px at an angle, bright head and fading tail (sk-drop)
            float ph = ((t + d[3]) % d[2]) / d[2], s = 660 / vy * ph;
            float hx = d[0] + vx * s, hy = -40 + lage.tropfenLaenge + vy * s, len = lage.tropfenLaenge;
            linie.setColor(WetterLage.alpha(0xFFDCEBFF, .95f * d[4] * .35f));
            c.drawLine(hx - vx * len, hy - vy * len, hx - vx * len * .45f, hy - vy * len * .45f, linie);
            linie.setColor(WetterLage.alpha(0xFFDCEBFF, .95f * d[4]));
            c.drawLine(hx - vx * len * .45f, hy - vy * len * .45f, hx, hy, linie);
        }
        linie.setStrokeWidth(1);
        for (float[] g : spritzer) {   // splashes on the ground in a downpour
            float ph = ((t + g[3]) % g[2]) / g[2], s = .2f + 1.4f * ph;
            linie.setColor(WetterLage.alpha(0xFFDCEBFF, .6f * .9f * (1 - ph)));
            r.set(g[0] + 8 - 8 * s, g[1] + 2 - 2 * s, g[0] + 8 + 8 * s, g[1] + 2 + 2 * s);
            c.drawOval(r, linie);
        }
        for (float[] f : flocken) {   // snow: 0 -> (30, 320) -> (-10, 640) (sk-snow)
            float ph = ((t + f[3]) % f[2]) / f[2];
            float x = f[0] + (ph < .5f ? 60 * ph : 30 - 80 * (ph - .5f)), y = -20 + 640 * ph;
            p.setColor(WetterLage.alpha(0xFFFFFFFF, f[4]));
            c.drawCircle(x + f[1] / 2, y + f[1] / 2, f[1] / 2, p);
        }
        for (float[] h : hagel) {   // hail: falls, bounces twice, lies (sk-hail)
            float ph = ((t + h[3]) % h[2]) / h[2];
            float[] xy = hagelBahn(ph);
            p.setColor(WetterLage.alpha(0xFFE6F0FA, ph > .97f ? (1 - ph) / .03f : 1));
            c.drawCircle(h[0] + xy[0] + h[1] / 2, -20 + xy[1] + h[1] / 2, h[1] / 2, p);
        }
        if (nebelBaender != null) {   // fog banks drifting to and fro (sk-fog)
            for (int i = 0; i < 4; i++) {
                float dur = 50 + i * 15, ph = (float) (.5 - .5 * Math.cos(((t + i * 11) % (2 * dur)) / (2 * dur) * 2 * Math.PI));
                float top = 80 + i * 70;
                mitShader(nebelBaender[i]);
                c.save();
                c.translate(-60 + 120 * ph, 0);
                c.drawRect(-100, top - 26, W + 100, top + 116, p);
                c.restore();
            }
            p.setShader(null);
        }
        if (dunst != null) {
            mitShader(dunst);
            c.drawRect(0, 160, W, 340, p);
            p.setShader(null);
        }
        if (boeen != null) {   // wind: gusts and leaves
            for (int i = 0; i < boeen.length; i++) {
                float dur = 2.2f + (i * .37f % 2), ph = ((t + i * .61f) % dur) / dur, breite = 120 + (i * 53 % 220);
                c.save();
                c.translate(-400 + 1500 * ph, 30 + (i * 97 % 300));
                mitShader(boeen[i]);
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
                float kx0 = LAUBX[k], kx1 = LAUBX[k + 1], ky0 = LAUBY[k], ky1 = LAUBY[k + 1];
                c.save();
                c.translate(-40 + kx0 + (kx1 - kx0) * u + 7, b[0] + ky0 + (ky1 - ky0) * u + 4);
                c.rotate(800 * ph);
                p.setColor(laub[i % 4]);
                c.drawPath(BLATT, p);
                c.restore();
            }
        }
    }

    private static final float[] LAUBX = {0, 260, 520, 780, 1060}, LAUBY = {0, -40, 30, -30, 20};
    private static final float[][] HAGEL = {{0, 0, 0}, {.78f, -30, 330}, {.86f, -22, 305}, {.94f, -16, 332}, {.97f, -13, 325}, {1, -11, 334}};
    private final float[] hagelXY = new float[2];

    private float[] hagelBahn(float ph) {
        for (int i = 1; i < HAGEL.length; i++) {
            if (ph <= HAGEL[i][0]) {
                float u = (ph - HAGEL[i - 1][0]) / (HAGEL[i][0] - HAGEL[i - 1][0]);
                if (i == 1) u = u * u * (1.4f - .4f * u);   // accelerating fall
                hagelXY[0] = HAGEL[i - 1][1] + (HAGEL[i][1] - HAGEL[i - 1][1]) * u;
                hagelXY[1] = HAGEL[i - 1][2] + (HAGEL[i][2] - HAGEL[i - 1][2]) * u;
                return hagelXY;
            }
        }
        hagelXY[0] = -11;
        hagelXY[1] = 334;
        return hagelXY;
    }

    /** Header like the weather page, drawn directly: dark patch, back button, place, temperature, condition, details. */
    private void kopf(Canvas c) {
        c.save();
        c.translate(160, 150);
        c.scale(1, .5f);
        mitShader(dunkelShader);
        c.drawCircle(0, 0, 520, p);
        p.setShader(null);
        c.restore();
        p.setColor(0x1FFFFFFF);
        c.drawCircle(58, 50, 28, p);
        p.setColor(0xFFECEBE8);
        c.save();
        c.translate(44, 36);
        c.scale(28 / 24f, 28 / 24f);
        c.drawPath(PFEIL, p);
        c.restore();
        if (daten == null) {
            c.drawText("Wetter wird geladen …", 104, 52, tZeilenP);
            return;
        }
        c.drawText(tOrt, 104, 32 + .928f * 22, tOrtP);
        c.drawText(tTemp, 100, 64 + (112 - 1.172f * 112) / 2 + .928f * 112, tTempP);
        c.drawText(tZustand, 104, 184 + .928f * 28, tZustandP);
        for (int i = 0; i < tZeilen.size(); i++) c.drawText(tZeilen.get(i), 104, 224 + i * 24.8f + (24.8f - 18.75f) / 2 + .928f * 16, tZeilenP);
    }

    /** Mode badge (time lapse / fixed time) and the measured values in the free area below the scene. */
    private void anzeigen(Canvas c) {
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
        c.drawText(szene, W / 2, 572, text);
    }

    // ---- bitmap builds (background thread: no fields of the view are written) -------------------------------------------

    /**
     * Cloud rows, each kept in the resolution its softness allows: the page blurs near rows by up to ~4 px and far rows by
     * ~1 px, so a row keeps 1 / blur bitmap pixels per page pixel (at most half the screen's). Like the page, the finished
     * silhouette is blurred, not each puff: the row is drawn at up to four times that resolution and halved once or twice
     * with bilinear filtering (each halving averages 2 x 2 pixels, a box blur done by Skia, no pixel loops in Java); the
     * large bitmap is given back at once. Only the band of y the row covers is kept. Colours of one moment (tag).
     * Returns {Bitmap[], float[] q (bitmap px per page px), float[] y0 (page y of the band)}.
     */
    static Object[] wolkenBilder(List<Wolken.Reihe> rh, WetterLage lg, boolean mond, float sk, float tag) {
        Bitmap[] out = new Bitmap[rh.size()];
        float[] qs = new float[rh.size()], y0s = new float[rh.size()];
        int[] wt = Wolken.farben(lg, false, mond), wn = Wolken.farben(lg, true, mond);
        int[] wp = new int[4];
        for (int i = 0; i < 4; i++) wp[i] = WetterLage.mix(wn[i], wt[i], tag);
        float hiAlpha = tag < .5f && !mond ? .15f : .35f;
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        PorterDuffXfermode atop = new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP);
        for (int i = 0; i < rh.size(); i++) {
            Wolken.Reihe reihe = rh.get(i);
            float qZiel = Math.max(.2f, Math.min(sk * .5f, 1 / reihe.blur));
            int stufen = 0;   // halvings: as many as fit under 0.75 of the screen's resolution, at most two
            while (stufen < 2 && qZiel * (1 << (stufen + 1)) <= sk * .75f) stufen++;
            float q = qZiel * (1 << stufen);
            float yMin = Float.MAX_VALUE, yMax = 0;
            for (Wolken.Haufen h : reihe.haufen) {
                yMin = Math.min(yMin, Math.min(h.top, h.by - h.h * 1.15f));
                yMax = Math.max(yMax, h.by);
            }
            yMin = Math.max(0, yMin - 2);
            yMax = Math.min(Wolken.YH + 20, yMax + 2);
            if (yMax <= yMin) continue;
            float dd = reihe.d * .6f;
            int hi = WetterLage.mix(wp[0], wp[3], dd), bo = WetterLage.mix(wp[1], wp[3], dd), sh = WetterLage.mix(wp[2], wp[3], dd);
            Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(Wolken.BREITE * q)), Math.max(1, Math.round((yMax - yMin) * q)), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(q, q);
            c.translate(0, -yMin);
            for (int pass = 0; pass < 2; pass++) {
                pp.setXfermode(pass == 1 ? atop : null);
                for (Wolken.Haufen h : reihe.haufen) {
                    float top = Math.min(h.top, h.by - h.h * 1.15f);
                    LinearGradient g = pass == 0 ? new LinearGradient(0, top, 0, h.by, new int[] {hi, hi, bo, sh}, new float[] {0, .1f, .6f, 1}, Shader.TileMode.CLAMP) : null;
                    for (int kopie = -1; kopie <= 1; kopie++) {   // drawn at +-BREITE too, so the row repeats without a seam
                        float ox = kopie * Wolken.BREITE;
                        if (h.cx + h.w + ox < 0 || h.cx - h.w + ox > Wolken.BREITE) continue;
                        c.save();
                        c.clipRect(h.cx - h.w + ox, top - h.h, h.cx + h.w + ox, h.by);
                        if (pass == 0) {
                            pp.setColor(0xFFFFFFFF);   // full opacity; the texture pass left a low alpha
                            pp.setShader(g);
                            for (float[] pf : h.puffs) c.drawCircle(pf[0] + ox, pf[1], pf[2], pp);
                            pp.setShader(null);
                        } else {
                            pp.setColor(WetterLage.alpha(sh, .16f));
                            for (float[] pf : h.puffs) c.drawCircle(pf[0] + ox + pf[2] * .35f, pf[1] + pf[2] * .45f, pf[2] * .9f, pp);
                            pp.setColor(WetterLage.alpha(hi, hiAlpha));
                            float grenze = h.by - (h.by - top) * .45f;
                            for (float[] pf : h.puffs) if (pf[1] < grenze) c.drawCircle(pf[0] + ox - pf[2] * .25f, pf[1] - pf[2] * .3f, pf[2] * .6f, pp);
                        }
                        c.restore();
                    }
                }
            }
            Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
            for (int st = 0; st < stufen; st++) {
                Bitmap h = Bitmap.createBitmap(Math.max(1, (b.getWidth() + 1) / 2), Math.max(1, (b.getHeight() + 1) / 2), Bitmap.Config.ARGB_8888);
                new Canvas(h).drawBitmap(b, null, new Rect(0, 0, h.getWidth(), h.getHeight()), filter);
                b.recycle();
                b = h;
            }
            out[i] = b;
            qs[i] = b.getWidth() / Wolken.BREITE;
            y0s[i] = yMin;
        }
        return new Object[] {out, qs, y0s};
    }

    /** The moon as on the page: glow, the dark disc, the lit part (turned by the real limb), maria on the lit part. */
    static Bitmap mondBauen(Himmel.Mond m, float sk) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(160 * sk)), Math.max(1, Math.round(160 * sk)), Bitmap.Config.ARGB_8888);
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

    // ---- particles (built once per weather) -----------------------------------------------------------------------------

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
        boeen = null;
        if (w.wind) {
            boeen = new Shader[10];
            for (int i = 0; i < 10; i++) boeen[i] = new LinearGradient(0, 0, 120 + (i * 53 % 220), 0, new int[] {0x00FFFFFF, 0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        }
        nebelBaender = null;
        if (w.nebel) {
            nebelBaender = new Shader[4];
            for (int i = 0; i < 4; i++) {
                float top = 80 + i * 70;
                nebelBaender[i] = new LinearGradient(0, top - 26, 0, top + 116, new int[] {0x00FFFFFF, 0x42FFFFFF, 0x42FFFFFF, 0x00FFFFFF}, new float[] {0, .35f, .65f, 1}, Shader.TileMode.CLAMP);
            }
        }
        dunst = w.dunst ? new LinearGradient(0, 160, 0, 340, new int[] {0x00C8D7E6, 0x29C8D7E6, 0x00C8D7E6}, null, Shader.TileMode.CLAMP) : null;
        double[][] hg = Himmel.hintergrund(300);
        sterne = new float[hg.length][];
        for (int i = 0; i < hg.length; i++) {
            sterne[i] = new float[] {(float) hg[i][0], (float) hg[i][1], (float) hg[i][2], (float) hg[i][3], (float) (.6 + rnd.getAsDouble()), (float) (rnd.getAsDouble() * 6.3)};
        }
        sternXY = null;
        // thunderstorm flashes (wetter.js BZ: 4 per 24 s, each with its own flicker); one glow shader, scaled per blob
        blitze.clear();
        blitzGlut = null;
        if (w.blitz) {
            blitzGlut = new Shader[] {new RadialGradient(0, 0, 1, new int[] {0xFFE2DCFF, 0xB39F93E8, 0x006D62B0}, new float[] {0, .5f, 1}, Shader.TileMode.CLAMP)};
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
