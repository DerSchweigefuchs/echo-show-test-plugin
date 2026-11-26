package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
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
import me.jxl.kiosk.plugins.KsTheme;

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
        int aktiv;                          // running (visible, unpaused) views
        String wolkenKey = "", bautKey;     // built / being built
        long wolkenFehlerBis, figurFehlerBis;   // backoff after a failed background build
        List<Wolken.Reihe> reihen = new ArrayList<>();
        Bitmap[] wolken = new Bitmap[0];
        BitmapShader[] wolkenShader = new BitmapShader[0];
        float[] wolkenY0 = new float[0], wolkenY1 = new float[0];
        Bitmap[] vorher;                    // the previous set while it fades out
        BitmapShader[] vorherShader;
        float[] vorherY0, vorherY1;
        List<Wolken.Reihe> vorherReihen;
        long wechselSeit;                   // uptime of the last swap
        int version;                        // counts swaps, so views rebuild their row filters
        Bitmap mond;
        Bitmap[] figur;                     // layers: body, head, front, tail (SvgMini)
        float[][] figurLage;                // per layer {left, top} in figure units, and pivots of head / tail
        float[] figurDreh;                  // {head x, y, tail x, y} in figure units
        String figurKey = "", figurBaut;
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
            if (E.aktiv == 0) allesFrei();
        });
    }

    private static void allesFrei() {
        if (E.aktiv > 0) return;
        recyceln(E.wolken);
        recyceln(E.vorher);
        for (Object[] a : E.alt) recyceln((Bitmap[]) a[0]);
        E.alt.clear();
        if (E.mond != null) E.mond.recycle();
        E.mond = null;
        recyceln(E.figur);
        E.figur = null;
        E.figurKey = "";
        E.figurBaut = null;
        E.mondKey = "";
        E.wolken = new Bitmap[0];
        E.wolkenShader = new BitmapShader[0];
        E.wolkenY0 = E.wolkenY1 = new float[0];
        E.reihen = new ArrayList<>();
        E.vorher = null;
        E.vorherShader = null;
        E.vorherY0 = E.vorherY1 = null;
        E.vorherReihen = null;
        E.wolkenKey = "";
        E.bautKey = null;
        E.wolkenFehlerBis = E.figurFehlerBis = 0;
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
        n += figurBytes();
        return n;
    }

    static long figurBytes() {
        long n = 0;
        if (E.figur != null) for (Bitmap b : E.figur) if (b != null && !b.isRecycled()) n += b.getAllocationByteCount();
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
    private final boolean schoner, schonerVorschau;
    private final SchonerUi schonerUi;
    private volatile SchonerDaten.Optionen schonerOpt;
    private volatile SchonerDaten.Zustand schonerZustand;
    private int monat = Calendar.getInstance().get(Calendar.MONTH);
    String mess1 = "", mess2 = "";

    // state of the moment
    private long simZeit, momentUm = -1;
    private double sonneAlt = 30, sonneAz = 180;
    private boolean steigt, koordinaten, mondDa;
    private Himmel.Mond mond;
    private WetterLage.Licht licht;
    private float sx, sy, mx, my;
    private float viewH = H, landY, hz = HZ, wolkenYh = Wolken.YH;

    // colours and shaders of the light (rebuilt when the sun moved by 0.05 deg)
    private String himmelKey = "";
    private Shader himmelShader, sonnenShader, deckTag, deckNacht, deckBlau, meerShader, dunkelShader, dunstHorizont;
    private Shader inselShader, turmShader, rotShader, treibShader, strandShader, nassShader, welleShader, bankLShader, dueneShader;
    private final Shader[] holzShader = new Shader[5];
    private LightingColorFilter[] wolkenFilter = new LightingColorFilter[0];
    private int cInsel, cTurm, cRot, cGalerie, cLampe, cSand, cSandDunkel, cDuene, cGras, cGrasHinten, cGrasVorne, cAehren;
    private int cHolzSch, cStein1, cStein2, cMuschel, cRumpf, cSegel, cFock, cPfuetze;
    private boolean nachtKopf;

    // the figure of the day
    private Figur.Cfg figurCfg;
    private String figurCfgKey = "", figurKey = "";
    private boolean figurSchonDa;
    private long figurSichtbarSeit = -1;
    static final float FIGUR_X = 820, FIGUR_Y = 286, FIGUR_S = 1.12f;   // feet on the dune, as on the page

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
    private float sternHz = -1;
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
    private long cpuStartWand, cpuStartTicks = -1, cpuStartHaupt, ersteZeichnung;
    private String szene = "";

    // paint (built once)
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bild = new Paint(Paint.FILTER_BITMAP_FLAG), linie = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), tOrtP, tTempP, tZustandP, tZeilenP;
    private final Typeface schrift = Typeface.create("sans-serif", Typeface.NORMAL), schriftMittel = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Shader ausblenden = new LinearGradient(0, 292, 0, UNTEN, 0x000D1720, 0xFF0D1720, Shader.TileMode.CLAMP);
    private final RectF r = new RectF();
    private final Matrix wolkenMatrix = new Matrix();
    private float skala = 1, dx, dy, downX, downY;

    // ==== geometry built once (the GPU keeps static paths; nothing is re-filled per frame) ============================

    private static final float TURM_LX = 112f, TURM_LY = 172.3f;
    private static final float[][] PFAHL = {{352, 285, 19, 4.0f}, {366, 287, 17, 3.8f}, {380, 289, 15, 3.6f}, {394, 291, 13, 3.3f}, {408, 293, 11, 3.0f}};
    private static final Path INSEL_SCHATTEN = new Path(), INSEL = new Path(), TURM = new Path(), TURM_ROT = new Path(), KUPPEL = new Path(),
        STRAND = new Path(), NASS = new Path(), WELLE = new Path(), RIPPEL_S = new Path(), RIPPEL_L = new Path(), BANK_L = new Path(),
        TREIB_SCHATTEN = new Path(), TREIBHOLZ = new Path(), SPUREN = new Path(),
        STEINE_SCHATTEN = new Path(), STEINE_0 = new Path(), STEINE_1 = new Path(), STEINE_2 = new Path(),
        DUENE = new Path(), DUENE_FALTE = new Path(), DUENE_KAMM = new Path(),
        GRAS_HINTEN = new Path(), GRAS_MITTE = new Path(), GRAS_VORNE = new Path(), GRAS_AEHREN = new Path(),
        GLANZ = new Path(), KAMM = new Path(), PFUETZEN = new Path(), BRANDUNG1 = new Path(), BRANDUNG2 = new Path(),
        RUMPF = new Path(), SEGEL = new Path(), FOCK = new Path(), BLATT = new Path(), PFEIL = new Path();
    private static final Path[] KEGEL_R = new Path[4], KEGEL_L = new Path[3];
    private static final Shader[] KEGEL_R_SHADER = new Shader[4], KEGEL_L_SHADER = new Shader[3];
    private static final Shader TURM_HALO_A = new RadialGradient(TURM_LX, TURM_LY, 46,
        new int[] {WetterLage.alpha(0xFFFFE29A, .20f), WetterLage.alpha(0xFFFFD87A, .06f), 0x00FFD87A}, new float[] {0f, .45f, 1f}, Shader.TileMode.CLAMP);
    private static final Shader TURM_HALO_B = new RadialGradient(TURM_LX, TURM_LY, 16,
        new int[] {WetterLage.alpha(0xFFFFF6D2, .65f), WetterLage.alpha(0xFFFFDC88, .22f), 0x00FFDC88}, new float[] {0f, .45f, 1f}, Shader.TileMode.CLAMP);
    private static final Shader TURM_HALO_C = new RadialGradient(TURM_LX, TURM_LY, 5.5f,
        new int[] {WetterLage.alpha(0xFFFFFFFF, .95f), WetterLage.alpha(0xFFFFF0B8, .45f), 0x00FFF0B8}, new float[] {0f, .55f, 1f}, Shader.TileMode.CLAMP);

    /** Smooth dune crest Y at x in 460..963 (blends seamlessly into the beach). */
    private static float dueneY(float x) {
        if (x <= 460) return 326;
        if (x < 755) {
            float t = (x - 460) / (755 - 460);
            float s = t * t * (3 - 2 * t);
            return 326 - s * (326 - 283);
        }
        return 283 - (x - 755) / (W - 755) * 8;
    }

    /** Foreground left sand mound ridge Y at x in 0..300. */
    private static float bankLY(float x) {
        float t = Math.max(0, Math.min(1, x / 300f));
        return 329 + t * t * 32;
    }

    /** Tapered organic marram-grass blade (wider at base, curved to a fine tip). */
    private static void addBlade(Path p, float bx, float by, float len, float lean, float bend, float halfW) {
        float tipX = bx + lean, tipY = by - len;
        float cx = bx + lean * .42f + bend, cy = by - len * .56f;
        p.moveTo(bx - halfW, by + 1.2f);
        p.quadTo(cx - halfW * .45f, cy, tipX, tipY);
        p.quadTo(cx + halfW * .45f, cy, bx + halfW, by + 1.2f);
        p.close();
    }

    /** Delicate marram-grass seed head (plume/ear) near the tip of a tall stem. */
    private static void addEar(Path p, float tipX, float tipY, float leanDir) {
        float ex = tipX + leanDir * 2.2f, ey = tipY - 6.5f;
        p.moveTo(tipX, tipY + 1.5f);
        p.quadTo(tipX + leanDir * .5f - 1.5f, tipY - 2.5f, ex, ey);
        p.quadTo(tipX + leanDir * 1.8f + 1.5f, tipY - 2.2f, tipX + .6f, tipY + 1.5f);
        p.close();
    }

    static {
        INSEL_SCHATTEN.moveTo(32, HZ + 1);
        INSEL_SCHATTEN.cubicTo(85, HZ + 6, 150, HZ + 6, 202, HZ + 1);
        INSEL_SCHATTEN.close();
        INSEL.moveTo(28, HZ + 1);
        INSEL.cubicTo(58, 204, 108, 198, 152, 204);
        INSEL.cubicTo(176, 208, 192, 210, 206, HZ + 1);
        INSEL.close();
        TURM.moveTo(107.0f, 203); TURM.lineTo(109.3f, 177); TURM.lineTo(114.7f, 177); TURM.lineTo(117.0f, 203); TURM.close();
        TURM_ROT.moveTo(107.9f, 195.5f); TURM_ROT.lineTo(116.1f, 195.5f); TURM_ROT.lineTo(115.6f, 189.5f); TURM_ROT.lineTo(108.4f, 189.5f); TURM_ROT.close();
        TURM_ROT.moveTo(108.7f, 185.5f); TURM_ROT.lineTo(115.3f, 185.5f); TURM_ROT.lineTo(114.9f, 180.5f); TURM_ROT.lineTo(109.1f, 180.5f); TURM_ROT.close();
        KUPPEL.moveTo(108.8f, 169.8f); KUPPEL.cubicTo(109.0f, 165.8f, 115.0f, 165.8f, 115.2f, 169.8f); KUPPEL.close();

        // Concentric volumetric lighthouse beam cones (right main beam + left counter-beam)
        float[][] cR = {{460f, 76f, .055f, .022f, .006f}, {410f, 46f, .085f, .036f, .010f}, {350f, 24f, .135f, .055f, .014f}, {270f, 11f, .220f, .080f, .015f}};
        for (int i = 0; i < cR.length; i++) {
            float len = cR[i][0], hh = cR[i][1];
            Path k = new Path();
            k.moveTo(TURM_LX, TURM_LY - 1f); k.lineTo(TURM_LX + len, TURM_LY - hh + 3); k.lineTo(TURM_LX + len, TURM_LY + hh + 3); k.lineTo(TURM_LX, TURM_LY + 1f); k.close();
            KEGEL_R[i] = k;
            KEGEL_R_SHADER[i] = new LinearGradient(TURM_LX, TURM_LY, TURM_LX + len, TURM_LY + 3,
                new int[] {WetterLage.alpha(0xFFFFF4CC, cR[i][2]), WetterLage.alpha(0xFFFFE6A0, cR[i][3]), WetterLage.alpha(0xFFFFDC88, cR[i][4]), 0x00FFDC88},
                new float[] {0f, .30f, .65f, 1f}, Shader.TileMode.CLAMP);
        }
        float[][] cL = {{190f, 36f, .040f, .015f}, {155f, 20f, .065f, .022f}, {120f, 9f, .100f, .030f}};
        for (int i = 0; i < cL.length; i++) {
            float len = cL[i][0], hh = cL[i][1];
            Path k = new Path();
            k.moveTo(TURM_LX, TURM_LY - .8f); k.lineTo(TURM_LX - len, TURM_LY - hh + 2); k.lineTo(TURM_LX - len, TURM_LY + hh + 2); k.lineTo(TURM_LX, TURM_LY + .8f); k.close();
            KEGEL_L[i] = k;
            KEGEL_L_SHADER[i] = new LinearGradient(TURM_LX, TURM_LY, TURM_LX - len, TURM_LY + 2,
                new int[] {WetterLage.alpha(0xFFFFF0C0, cL[i][2]), WetterLage.alpha(0xFFFFE49E, cL[i][3]), 0x00FFE49E},
                new float[] {0f, .42f, 1f}, Shader.TileMode.CLAMP);
        }

        // Main beach, wet tidal swash, receding translucent wave sheet, two surf foam crests
        STRAND.moveTo(0, 296);
        STRAND.cubicTo(150, 289, 310, 298, 465, 293);
        STRAND.cubicTo(575, 290, 650, 292, 715, 289);
        STRAND.lineTo(W, 289); STRAND.lineTo(W, UNTEN); STRAND.lineTo(0, UNTEN); STRAND.close();
        NASS.moveTo(0, 296);
        NASS.cubicTo(150, 289, 310, 298, 465, 293);
        NASS.cubicTo(575, 290, 650, 292, 715, 289);
        NASS.lineTo(715, 303);
        NASS.cubicTo(620, 306, 540, 305, 455, 309);
        NASS.cubicTo(300, 315, 150, 306, 0, 312);
        NASS.close();
        WELLE.moveTo(0, 294);
        WELLE.cubicTo(120, 287, 250, 297, 390, 291);
        WELLE.cubicTo(510, 287, 615, 292, 725, 288);
        WELLE.lineTo(725, 296);
        WELLE.cubicTo(615, 300, 505, 295, 385, 300);
        WELLE.cubicTo(245, 305, 120, 296, 0, 301);
        WELLE.close();
        BRANDUNG1.moveTo(0, 295); BRANDUNG1.cubicTo(120, 288, 250, 298, 390, 292); BRANDUNG1.cubicTo(510, 288, 615, 293, 725, 289);
        BRANDUNG2.moveTo(35, 284); BRANDUNG2.cubicTo(160, 279, 290, 287, 425, 282); BRANDUNG2.cubicTo(540, 278, 640, 283, 740, 280);

        // Wind-sculpted sand ripples and left foreground sand mound
        float[][] rips = {{60, 326, 210, 320, 360, 327}, {150, 340, 290, 333, 440, 339}, {310, 318, 415, 313, 515, 318}, {20, 348, 140, 343, 275, 349}, {380, 336, 465, 331, 550, 335}};
        for (float[] rp : rips) {
            RIPPEL_S.moveTo(rp[0], rp[1]); RIPPEL_S.quadTo(rp[2], rp[3], rp[4], rp[5]);
            RIPPEL_L.moveTo(rp[0], rp[1] - 1.5f); RIPPEL_L.quadTo(rp[2], rp[3] - 1.5f, rp[4], rp[5] - 1.5f);
        }
        BANK_L.moveTo(0, 329); BANK_L.cubicTo(95, 325, 195, 336, 300, UNTEN); BANK_L.lineTo(0, UNTEN); BANK_L.close();

        // Weathered driftwood branch + grounded shadow
        TREIB_SCHATTEN.addOval(new RectF(420, 323, 492, 330), Path.Direction.CW);
        TREIBHOLZ.moveTo(422, 325); TREIBHOLZ.cubicTo(440, 320, 464, 322, 490, 318);
        TREIBHOLZ.lineTo(491, 322); TREIBHOLZ.cubicTo(468, 326, 444, 326, 423, 328); TREIBHOLZ.close();
        TREIBHOLZ.moveTo(462, 321); TREIBHOLZ.lineTo(472, 314); TREIBHOLZ.lineTo(474, 315.5f); TREIBHOLZ.lineTo(465, 322); TREIBHOLZ.close();

        // Footprints, pebbles and shells (grouped into static paths)
        float[][] spuren = {{355, 334}, {372, 331}, {388, 334}, {404, 332}, {421, 335}, {438, 332}};
        for (float[] sp : spuren) SPUREN.addOval(new RectF(sp[0] - 3.2f, sp[1] - 1.1f, sp[0] + 3.2f, sp[1] + 1.1f), Path.Direction.CW);
        float[][] steine = {
            {186, 318, 4.2f, 2.0f, 0}, {193, 320, 2.8f, 1.4f, 1}, {181, 321, 2.2f, 1.1f, 2},
            {322, 315, 3.6f, 1.7f, 1}, {329, 317, 2.4f, 1.2f, 2}, {336, 314, 1.8f, 1.0f, 0},
            {485, 324, 3.2f, 1.5f, 2}, {492, 326, 2.2f, 1.1f, 0},
            {552, 318, 3.8f, 1.8f, 1}, {560, 321, 2.5f, 1.2f, 2},
            {650, 322, 3.4f, 1.6f, 0}, {659, 325, 2.3f, 1.2f, 2},
            {722, 312, 3.0f, 1.4f, 1},
        };
        for (float[] st : steine) {
            STEINE_SCHATTEN.addOval(new RectF(st[0] - st[2], st[1] - st[3] * .5f + 1.2f, st[0] + st[2], st[1] + st[3] + 1.2f), Path.Direction.CW);
            Path ziel = st[4] == 0 ? STEINE_0 : (st[4] == 1 ? STEINE_1 : STEINE_2);
            ziel.addOval(new RectF(st[0] - st[2], st[1] - st[3], st[0] + st[2], st[1] + st[3]), Path.Direction.CW);
        }

        // Seamless organic dune (no vertical seam) + fold + sunlit crest rim
        DUENE.moveTo(440, UNTEN);
        DUENE.cubicTo(510, 326, 615, 290, 755, 283);
        DUENE.cubicTo(835, 279, 905, 278, W, 275);
        DUENE.lineTo(W, UNTEN); DUENE.close();
        DUENE_FALTE.moveTo(560, 336); DUENE_FALTE.cubicTo(655, 314, 765, 306, W, 304);
        DUENE_KAMM.moveTo(500, 330); DUENE_KAMM.cubicTo(565, 305, 645, 288, 755, 283); DUENE_KAMM.cubicTo(835, 279, 905, 278, W, 275);

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

        // 3-layer organic marram grass on the right dune (gap around figure at 794..850) + foreground grass on bottom-left mound
        Wolken.Zufall Rg = new Wolken.Zufall(42);
        for (float x = 565; x < W - 4; x += (float) (15 + Rg.next() * 19)) {
            if (x > 794 && x < 850) continue;
            float baseY = dueneY(x) + 1.5f;
            int blades = 5 + (int) Math.floor(Rg.next() * 4);
            for (int k = 0; k < blades; k++) {
                float u = (k / (float) Math.max(1, blades - 1)) - .5f;
                float bx = x + u * 8 + (float) (Rg.next() - .5) * 3;
                float len = (float) (11 + Rg.next() * 15) * (1 - Math.abs(u) * .35f);
                float lean = u * (10 + (float) Rg.next() * 9) + 3.5f + (float) (Rg.next() - .35) * 6;
                addBlade(GRAS_HINTEN, bx, baseY + (float) Rg.next() * 2, len, lean, (float) (Rg.next() - .5) * 4, .95f + (float) Rg.next() * .4f);
            }
        }
        for (float x = 535; x < W - 2; x += (float) (12 + Rg.next() * 16)) {
            if (x > 796 && x < 848) continue;
            float baseY = dueneY(x) + 2.5f + (x < 610 ? 2 : 0);
            int blades = 6 + (int) Math.floor(Rg.next() * 5);
            for (int k = 0; k < blades; k++) {
                float u = (k / (float) Math.max(1, blades - 1)) - .5f;
                float bx = x + u * 9 + (float) (Rg.next() - .5) * 2.5f;
                float len = (float) (12 + Rg.next() * 18) * (1 - Math.abs(u) * .3f);
                float lean = u * (11 + (float) Rg.next() * 10) + 4f + (float) (Rg.next() - .3) * 7;
                addBlade(GRAS_MITTE, bx, baseY + (float) Rg.next() * 2.5f, len, lean, (lean > 0 ? 1 : -1) * (1.2f + (float) Rg.next() * 2.5f), .9f + (float) Rg.next() * .45f);
            }
        }
        for (float x = 550; x < W - 4; x += (float) (18 + Rg.next() * 24)) {
            if (x > 792 && x < 852) continue;
            float baseY = dueneY(x) + 4f + (float) Rg.next() * 5f;
            int blades = 4 + (int) Math.floor(Rg.next() * 4);
            for (int k = 0; k < blades; k++) {
                float u = (k / (float) Math.max(1, blades - 1)) - .5f;
                float bx = x + u * 7 + (float) (Rg.next() - .5) * 2;
                float len = (float) (9 + Rg.next() * 14);
                float lean = u * 9 + 4.5f + (float) (Rg.next() - .3) * 6;
                addBlade(GRAS_VORNE, bx, baseY, len, lean, lean > 0 ? 1.5f : -1.5f, .8f + (float) Rg.next() * .35f);
                if (k == blades / 2 && Rg.next() < .45) addEar(GRAS_AEHREN, bx + lean, baseY - len, lean >= 0 ? 1 : -1);
            }
        }
        // Bottom-left foreground sand mound grass (lush on the far left, tapering naturally into the open beach)
        Wolken.Zufall Rl = new Wolken.Zufall(73);
        for (float x = 4; x < 268; x += (float) (11 + Rl.next() * 14 + x * .045f)) {
            float fade = 1f - Math.max(0, (x - 95) / 190f) * .48f;
            float baseY = bankLY(x) + 1.5f;
            int blades = 6 + (int) Math.floor(Rl.next() * 4);
            for (int k = 0; k < blades; k++) {
                float u = (k / (float) Math.max(1, blades - 1)) - .5f;
                float bx = x + u * 9 + (float) (Rl.next() - .5) * 3;
                float len = (float) (14 + Rl.next() * 17) * fade * (1 - Math.abs(u) * .28f);
                float lean = u * (10 + (float) Rl.next() * 9) + 4f + (float) (Rl.next() - .35) * 6;
                addBlade(GRAS_HINTEN, bx, baseY + (float) Rl.next() * 2.5f, len, lean, (float) (Rl.next() - .5) * 4f, .95f + (float) Rl.next() * .4f);
            }
        }
        for (float x = 2; x < 282; x += (float) (9 + Rl.next() * 13 + x * .045f)) {
            float fade = 1f - Math.max(0, (x - 105) / 200f) * .48f;
            float baseY = bankLY(x) + 3.5f + (float) Rl.next() * 4f;
            int blades = 6 + (int) Math.floor(Rl.next() * 5);
            for (int k = 0; k < blades; k++) {
                float u = (k / (float) Math.max(1, blades - 1)) - .5f;
                float bx = x + u * 9.5f + (float) (Rl.next() - .5) * 2.5f;
                float len = (float) (14 + Rl.next() * 20) * fade * (1 - Math.abs(u) * .26f);
                float lean = u * (11 + (float) Rl.next() * 10) + 4.5f + (float) (Rl.next() - .3) * 6.5f;
                addBlade(GRAS_MITTE, bx, baseY + (float) Rl.next() * 3f, len, lean, (lean > 0 ? 1 : -1) * (1.3f + (float) Rl.next() * 2.4f), .95f + (float) Rl.next() * .45f);
            }
        }
        for (float x = 6; x < 248; x += (float) (14 + Rl.next() * 18 + x * .04f)) {
            float fade = 1f - Math.max(0, (x - 85) / 185f) * .42f;
            float baseY = bankLY(x) + 6f + (float) Rl.next() * 6f;
            int blades = 5 + (int) Math.floor(Rl.next() * 4);
            for (int k = 0; k < blades; k++) {
                float u = (k / (float) Math.max(1, blades - 1)) - .5f;
                float bx = x + u * 8 + (float) (Rl.next() - .5) * 2;
                float len = (float) (11 + Rl.next() * 16) * fade;
                float lean = u * 9 + 5f + (float) (Rl.next() - .3) * 5.5f;
                addBlade(GRAS_VORNE, bx, baseY, len, lean, lean > 0 ? 1.6f : -1.6f, .85f + (float) Rl.next() * .35f);
                if (k == blades / 2 && Rl.next() < .48) addEar(GRAS_AEHREN, bx + lean, baseY - len, lean >= 0 ? 1 : -1);
            }
        }

        float[][] pf = {{220, 320, 34}, {500, 324, 26}};
        for (float[] m : pf) PFUETZEN.addOval(new RectF(m[0] - m[2], m[1] - m[2] * .18f, m[0] + m[2], m[1] + m[2] * .18f), Path.Direction.CW);
        RUMPF.moveTo(549, HZ - 1); RUMPF.lineTo(573, HZ - 1); RUMPF.lineTo(569, HZ + 3); RUMPF.lineTo(553, HZ + 3); RUMPF.close();
        SEGEL.moveTo(561, HZ - 2); SEGEL.lineTo(561, HZ - 26); SEGEL.lineTo(572, HZ - 3); SEGEL.close();
        FOCK.moveTo(559.5f, HZ - 2); FOCK.lineTo(559.5f, HZ - 21); FOCK.lineTo(551, HZ - 3); FOCK.close();
        BLATT.moveTo(-7, 4); BLATT.quadTo(-7, -4, 7, -4); BLATT.quadTo(7, 4, -7, 4); BLATT.close();
        PFEIL.moveTo(15.41f, 16.58f); PFEIL.lineTo(10.83f, 12); PFEIL.lineTo(15.41f, 7.41f); PFEIL.lineTo(14, 6); PFEIL.lineTo(8, 12); PFEIL.lineTo(14, 18); PFEIL.close();
    }

    WetterView(Context ctx, Aktionen aktionen, String modus, String uhr, boolean namen, int zielFps) {
        this(ctx, aktionen, null, null, modus, uhr, namen, zielFps, false, false);
    }

    WetterView(Context ctx, Aktionen aktionen, KsTheme theme, SchonerDaten.Optionen opt, String modus, String uhr, boolean namen, boolean vorschau) {
        this(ctx, aktionen, theme, opt, modus, uhr, namen, 24, true, vorschau);
    }

    private WetterView(Context ctx, Aktionen aktionen, KsTheme theme, SchonerDaten.Optionen opt, String modus, String uhr,
                       boolean namen, int zielFps, boolean schoner, boolean vorschau) {
        super(ctx);
        this.aktionen = aktionen;
        this.namen = namen;
        this.schoner = schoner;
        this.schonerVorschau = vorschau;
        this.schonerOpt = opt != null ? opt : new SchonerDaten.Optionen();
        this.schonerUi = schoner ? new SchonerUi(theme) : null;
        this.intervallNs = 1_000_000_000L / Math.max(10, Math.min(60, zielFps));
        long fest = 0;
        String mo = "live";
        if ("zeitraffer".equals(modus)) mo = "zeitraffer";
        else if (uhr != null && uhr.matches("\\d{4}-\\d{2}-\\d{2}T\\d{1,2}:\\d{2}")) {   // a whole moment (previews and tests)
            String[] dt = uhr.split("[-T:]");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(dt[0]), Integer.parseInt(dt[1]) - 1, Integer.parseInt(dt[2]), Integer.parseInt(dt[3]), Integer.parseInt(dt[4]), 0);
            c.set(Calendar.MILLISECOND, 0);
            fest = c.getTimeInMillis();
            mo = "fest";
        } else if (uhr != null && uhr.matches("\\d{1,2}:\\d{2}")) {
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
        setClickable(!schoner || vorschau);
        partikel();
        wolkenSchonDa = E.wolken.length > 0;
        figurSchonDa = E.figur != null;
    }

    /** New screensaver hints / music snapshot (main thread). */
    void setSchoner(SchonerDaten.Zustand z) {
        schonerZustand = z;
        invalidate();
    }

    /** Updated screensaver settings (main thread). */
    void setSchonerOptionen(SchonerDaten.Optionen o) {
        schonerOpt = o != null ? o : new SchonerDaten.Optionen();
        invalidate();
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
        schleife();
    }

    @Override protected void onDetachedFromWindow() {
        angehaengt = false;
        schleife();
        if (E.aktiv == 0) {
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
            E.aktiv++;
            haupt().removeCallbacks(FREIGABE);
            letzterFrame = 0;
            letzteZeichnung = 0;
            ch.postFrameCallback(this);
        } else {
            ch.removeFrameCallback(this);
            E.aktiv = Math.max(0, E.aktiv - 1);
            if (E.aktiv == 0) {
                haupt().removeCallbacks(FREIGABE);
                haupt().postDelayed(FREIGABE, FREIGABE_MS);
            }
        }
    }

    private long zielIntervallNs() {
        if (!schoner) return intervallNs;
        boolean bewegt = lage.tropfen > 0 || lage.flocken > 0 || lage.hagel > 0 || lage.wind || lage.blitz;
        SchonerDaten.Optionen o = schonerOpt;
        int ziel = bewegt ? 24 : Math.max(6, Math.min(30, o != null ? o.ruhigFps : 12));
        return 1_000_000_000L / ziel;
    }

    @Override public void doFrame(long ns) {
        if (!laeuft) return;
        if (letzterFrame == 0 || ns - letzterFrame >= zielIntervallNs() - 2_000_000L) {
            letzterFrame = ns;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    // ---- touch: back button ------------------------------------------------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (schoner && !schonerVorschau) return super.onTouchEvent(e);   // KS owns touch dismissal for native screensavers
        try {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                downY = e.getY();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                && Math.abs(e.getX() - downX) < 14 * skala && Math.abs(e.getY() - downY) < 14 * skala) {
                if (schonerVorschau) {
                    performClick();
                    aktionen.zurueck();
                    return true;
                }
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
            if (laeuft && luecke > zielIntervallNs() * 3 / 2 + 4_000_000L) rucklerGesamt++;
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
                fps, Math.round(1e9f / zielIntervallNs()), medianMs, p95Ms, rucklerGesamt, pct(cpuProzess), pct(cpuHauptProzent), bitmapBytes() / 1024);
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

    /** Why /proc/self/stat gave no value (null while it works). */
    static volatile String cpuFehler;

    static long prozessTicks() {
        byte[] b = new byte[1024];
        int n;
        try (FileInputStream in = new FileInputStream("/proc/self/stat")) {
            n = in.read(b);
        } catch (Exception e) {
            cpuFehler = "/proc/self/stat nicht lesbar: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
            return -1;
        }
        if (n <= 0) {
            cpuFehler = "/proc/self/stat leer";
            return -1;
        }
        String s = new String(b, 0, n);
        int z = s.lastIndexOf(')');
        String[] f = z < 0 ? new String[0] : s.substring(z + 2).trim().split(" +");
        try {
            long v = Long.parseLong(f[11]) + Long.parseLong(f[12]);   // fields 14 and 15: utime, stime
            cpuFehler = null;
            return v;
        } catch (RuntimeException e) {
            cpuFehler = "/proc/self/stat unerwartet aufgebaut (" + f.length + " Felder)";
            return -1;
        }
    }

    /** CPU since the first frame, for figures asked for before the first one-second window is complete. */
    private float[] cpuSeitStart() {
        long wand = SystemClock.elapsedRealtime(), ticks = prozessTicks(), haupt = Debug.threadCpuTimeNanos();
        float sek = (wand - cpuStartWand) / 1000f;
        if (cpuStartWand == 0 || sek < .2f) return new float[] {-1, -1};
        return new float[] {ticks >= 0 && cpuStartTicks >= 0 ? (ticks - cpuStartTicks) / 100f / sek * 100 : -1,
            haupt > 0 && cpuStartHaupt > 0 ? (haupt - cpuStartHaupt) / 1e9f / sek * 100 : -1};
    }

    /** Figures for "bereit" and the status line. */
    Map<String, Object> kennzahlen() {
        perzentile();
        Map<String, Object> m = new LinkedHashMap<>();
        float[] start = cpuProzess < 0 || cpuHauptProzent < 0 ? cpuSeitStart() : null;
        float cp = cpuProzess >= 0 ? cpuProzess : start[0], ch = cpuHauptProzent >= 0 ? cpuHauptProzent : start[1];
        long seit = letzteZeichnung - ersteZeichnung;
        m.put("szene_fps", fps > 0 ? fps : (seit > 0 && bilderGesamt > 0 ? Math.round(bilderGesamt * 1e9f / seit) : 0));
        m.put("messfenster", fps > 0 && cpuProzess >= 0 ? "1 s" : "seit erstem Bild");
        m.put("frame_median_ms", Math.round(medianMs * 10) / 10.0);
        m.put("frame_p95_ms", Math.round(p95Ms * 10) / 10.0);
        m.put("ruckler", rucklerGesamt);
        m.put("cpu_prozess_pct", cp < 0 ? null : Math.round(cp));
        m.put("cpu_haupt_pct", ch < 0 ? null : Math.round(ch));
        if (cpuFehler != null) m.put("cpu_fehler", cpuFehler);
        m.put("figur_kb", figurBytes() / 1024);
        m.put("cpu_kerne", Runtime.getRuntime().availableProcessors());
        m.put("bitmap_kb", bitmapBytes() / 1024);
        return m;
    }

    // ---- frame ---------------------------------------------------------------------------------------------------------

    private void zeichnen(Canvas c) {
        int gw = getWidth(), gh = getHeight();
        if (gw <= 0 || gh <= 0) return;
        float neu = schoner ? gw / W : Math.min(gw / W, gh / H);
        if (neu <= 0) return;
        float neuViewH = schoner ? gh / neu : H;
        if (neu != skala || Math.abs(neuViewH - viewH) > .5f) {
            momentUm = -1;
            himmelKey = "";
        }
        skala = neu;
        viewH = neuViewH;
        if (schoner) {
            dx = 0;
            dy = 0;
            landY = Math.max(0, viewH - UNTEN);
            hz = HZ + landY;
            wolkenYh = Wolken.YH + Math.round(landY / 10f) * 10f;
        } else {
            dx = (gw - W * skala) / 2;
            dy = (gh - H * skala) / 2;
            landY = 0;
            hz = HZ;
            wolkenYh = Wolken.YH;
        }
        long anim = SystemClock.uptimeMillis();
        float t = anim / 1000f;
        if (cpuWand == 0) {
            cpu();
            cpuStartWand = cpuWand;
            cpuStartTicks = cpuTicks;
            cpuStartHaupt = cpuHaupt;
            ersteZeichnung = System.nanoTime();
        }
        moment(anim);
        ebenen(anim);

        float szeneH = schoner ? viewH : UNTEN;
        c.drawColor(0xFF0D1720);
        c.save();
        c.translate(dx, dy);
        c.scale(skala, skala);
        c.save();
        c.clipRect(0, 0, W, szeneH);
        mitShader(himmelShader);
        c.drawRect(0, 0, W, szeneH, p);
        p.setShader(null);
        sonne(c, t);
        sternenhimmel(c, t);
        mondZeichnen(c);
        wolken(c, t, anim);
        blitzWolken(c, t);
        // Subtle atmospheric horizon haze right above the sea
        if (dunstHorizont != null) {
            mitShader(dunstHorizont);
            c.drawRect(0, hz - 26, W, hz + 2, p);
            p.setShader(null);
        }
        c.save();
        c.translate(0, landY);
        see(c);
        meerWasser(c, t);
        inselUndTurm(c);
        leuchtturm(c, t);
        boot(c, t);
        strandUndDuene(c, t);
        figur(c, t);
        grasVorne(c);
        if (!schoner) {
            mitShader(ausblenden);   // the fade into the dark area stays above the figure's feet (page: wl-fd after the figure)
            c.drawRect(0, 292, W, UNTEN, p);
            p.setShader(null);
        }
        c.restore();
        niederschlag(c, t);
        c.restore();
        if (schoner && schonerUi != null) {
            schonerUi.zeichnen(c, W, viewH, simZeit, t, licht.tag, daten, lage, schonerZustand, schonerOpt);
        } else {
            kopf(c);
            anzeigen(c);
        }
        c.restore();

        boolean wolkenFertig = lage.cover <= 0 || (E.wolken.length > 0 && E.wolkenKey.startsWith(wolkenGrund()) && wolkenAlpha(anim) >= 1);
        boolean mondFertig = !mondDa || E.mond != null;
        boolean figurFertig = figurKey.isEmpty() || figurKey.equals(E.figurKey);
        if (!fertigGemeldet && daten != null && wolkenFertig && mondFertig && figurFertig) {
            fertigGemeldet = true;
            aufbauMs = anim - erstellt;
            aktionen.fertig(aufbauMs);
        }
    }

    private float himmelY(double alt) {
        if (!schoner) return Himmel.y(alt);
        return (float) (hz - 280 * Math.log(Math.tan(Math.PI / 4 + Math.max(-80, Math.min(80, alt)) * Himmel.R / 2)));
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
        sy = Math.max(60, himmelY(sonneAlt));
        mondDa = mond != null && mond.sichtbar && lage.mondMoeglich;
        if (mondDa) {
            mx = Himmel.x(mond.az);
            my = Math.max(schoner ? 70 : 46, himmelY(mond.alt));
            if (mx < -80 || mx > W + 80) mondDa = false;
        }
        int tagNr = (int) Math.floor((simZeit + Calendar.getInstance().getTimeZone().getOffset(simZeit)) / 864e5);
        Calendar mc = Calendar.getInstance();
        mc.setTimeInMillis(simZeit);
        monat = mc.get(Calendar.MONTH);
        grund = lage.cond + "/" + tagNr + "/" + mondDa + "/" + skala + "/" + Math.round(wolkenYh) + "/";
        // figure of the day: person, clothes and pose follow day, weather, temperature and night (like the page)
        boolean nacht = licht.tag < .5f, sonneJetzt = lage.sonneMoeglich && !nacht, mondhell = mondDa && nacht;
        double T = d != null && d.temp != null ? Math.round(d.temp) : 10;
        String ck = tagNr + "/" + lage.cond + "/" + T + "/" + nacht + "/" + sonneJetzt + "/" + mondhell + "/" + lage.stark + "/" + monat;
        if (!ck.equals(figurCfgKey)) {
            figurCfgKey = ck;
            figurCfg = Figur.fuerSzene(tagNr, monat, lage.cond, T, nacht, sonneJetzt, mondhell, lage.stark);
        }
    }

    // ---- light-dependent colours and the shared layers -----------------------------------------------------------------

    private String wolkenGrund() { return grund; }

    private void ebenen(long anim) {
        WetterLage.Licht L = licht;
        // clouds first: a new colour step or a new set of rows needs new row filters below: geometry per weather and day, one colour version for the light of now (16 steps night .. day)
        if (lage.cover > 0) {
            int stufe = Math.round(L.tag * FARBSTUFEN);
            String wk = wolkenGrund() + stufe;
            if (!wk.equals(E.wolkenKey) && !wk.equals(E.bautKey) && E.bautKey == null && anim >= E.wolkenFehlerBis) wolkenBauen(wk, stufe);
            if (stufe != tagStufe || version != E.version) {
                tagStufe = stufe;
                version = E.version;
                himmelKey = "";   // new row filters
            }
        }
        // figure: one bitmap per layer, in the light of the hour in steps (8 between night and day, overlays in quarters)
        if (figurCfg != null) {
            WetterLage.Licht fl = figurLicht(L);
            figurKey = figurCfg.schluessel() + fl.tag + "/" + fl.warm + "/" + fl.kuehl + "/" + fl.blau + "/" + skala;
            if (!figurKey.equals(E.figurKey) && E.figurBaut == null && anim >= E.figurFehlerBis) figurBauen(figurKey, figurCfg, fl, skala);
        }
        String hk = Math.round(Math.max(-15, Math.min(15, sonneAlt)) * 20) + (steigt ? "s" : "f") + lage.cond + mondDa + (schoner ? "S" + Math.round(viewH) : "");
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

    /** The light of the hour in coarse steps, so the figure is redrawn only a few times through dusk and dawn. */
    private static WetterLage.Licht figurLicht(WetterLage.Licht L) {
        WetterLage.Licht q = new WetterLage.Licht();
        q.tag = Math.round(L.tag * 8) / 8f;
        q.warm = Math.round(L.warm * 4) / 4f;
        q.kuehl = Math.round(L.kuehl * 4) / 4f;
        q.blau = Math.round(L.blau * 4) / 4f;
        q.himmel = L.himmel;
        return q;
    }

    private void figurBauen(String key, Figur.Cfg cfg, WetterLage.Licht fl, float sk) {
        E.figurBaut = key;
        try {
            bauer().execute(() -> {
                Object[] r = null;
                try {
                    r = figurBilder(cfg, fl, sk);
                } catch (RuntimeException ignored) {
                    // try again with the next frame
                }
                final Object[] fr = r;
                haupt().post(() -> {
                    if (!key.equals(E.figurBaut)) return;
                    E.figurBaut = null;
                    if (fr == null) {
                        E.figurFehlerBis = SystemClock.uptimeMillis() + 5_000L;
                        return;
                    }
                    E.figurFehlerBis = 0;
                    if (E.figur != null) spaeterRecyceln(E.figur);
                    E.figur = (Bitmap[]) fr[0];
                    E.figurLage = (float[][]) fr[1];
                    E.figurDreh = (float[]) fr[2];
                    E.figurKey = key;
                });
            });
        } catch (RuntimeException ended) {
            E.figurBaut = null;
        }
    }

    /**
     * The figure in its layers (body, head, scarf in front, dog's tail), each cut to its own size, in screen resolution.
     * Colours take the light: at night darker and cool (the page's night factors .58 / .6 / .7), at dusk the same
     * overlays as the landscape. Returns {Bitmap[4], float[4][2] top-left in figure units, float[4] pivots}.
     */
    static Object[] figurBilder(Figur.Cfg cfg, WetterLage.Licht fl, float sk) {
        SvgMini.Knoten root = SvgMini.parse(Figur.figurX(cfg));
        SvgMini.Lauf mass = new SvgMini.Lauf(null, -1, null);
        mass.lauf(root);
        final float nacht = 1 - fl.tag;
        SvgMini.Farbe farbe = (argb, hex) -> {
            if (hex) {
                int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
                int dunkel = WetterLage.rgb(Math.round(r * .58f), Math.round(g * .6f), Math.round(b * .7f));
                return WetterLage.tont(WetterLage.mix(argb, dunkel, nacht), 245, fl);
            }
            if ((argb & 0xFFFFFF) == 0xFFE8CD) return WetterLage.alpha(argb, .45f - .2f * nacht);   // rim light, fainter at night
            return argb;
        };
        float k = sk * FIGUR_S;
        Bitmap[] bm = new Bitmap[SvgMini.SCHICHTEN];
        float[][] lage = new float[SvgMini.SCHICHTEN][];
        for (int s = 0; s < SvgMini.SCHICHTEN; s++) {
            RectF g = mass.grenzen[s];
            if (g == null) continue;
            Bitmap b = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(g.width() * k) + 2), Math.max(1, (int) Math.ceil(g.height() * k) + 2), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.translate(1, 1);
            c.scale(k, k);
            c.translate(-g.left, -g.top);
            new SvgMini.Lauf(c, s, farbe).lauf(root);
            bm[s] = b;
            lage[s] = new float[] {g.left - 1 / k, g.top - 1 / k};
        }
        return new Object[] {bm, lage, new float[] {mass.kopfDreh[0], mass.kopfDreh[1], mass.schwanzDreh[0], mass.schwanzDreh[1]}};
    }

    private void wolkenBauen(String key, int stufe) {
        final WetterLage lg = lage;
        final boolean md = mondDa;
        final float sk = skala, tag = stufe / (float) FARBSTUFEN;
        final float wyh = wolkenYh, wMaxY = schoner ? viewH : UNTEN;
        final int tagNr = Integer.parseInt(key.split("/")[1]);
        E.bautKey = key;
        try {
            bauer().execute(() -> {
                List<Wolken.Reihe> rh = null;
                Object[] b = null;
                try {
                    rh = Wolken.bauen(lg, tagNr, wyh);
                    b = wolkenBilder(rh, lg, md, sk, tag, wMaxY);
                } catch (RuntimeException ignored) {
                    // try again with the next frame
                }
                final List<Wolken.Reihe> frh = rh;
                final Object[] fb = b;
                haupt().post(() -> {
                    if (!key.equals(E.bautKey)) return;
                    E.bautKey = null;
                    if (fb == null) {
                        E.wolkenFehlerBis = SystemClock.uptimeMillis() + 5_000L;
                        return;
                    }
                    E.wolkenFehlerBis = 0;
                    if (E.wolken.length > 0) {   // the old set fades out over the new one
                        spaeterRecyceln(E.vorher);
                        E.vorher = E.wolken;
                        E.vorherShader = E.wolkenShader;
                        E.vorherY0 = E.wolkenY0;
                        E.vorherY1 = E.wolkenY1;
                        E.vorherReihen = E.reihen;
                    }
                    E.reihen = frh;
                    Bitmap[] neu = (Bitmap[]) fb[0];
                    BitmapShader[] sh = new BitmapShader[neu.length];
                    for (int i = 0; i < neu.length; i++) {
                        if (neu[i] != null) sh[i] = new BitmapShader(neu[i], Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
                    }
                    E.wolken = neu;
                    E.wolkenShader = sh;
                    E.wolkenY0 = (float[]) fb[1];
                    E.wolkenY1 = (float[]) fb[2];
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
        // sky gradient: 0..UNTEN on the weather page, full 0..viewH in screensaver mode
        int n = 16;
        int[] f = new int[n];
        float[] pos = new float[n];
        float skyH = schoner ? viewH : UNTEN;
        for (int i = 0; i < n; i++) {
            float y = skyH * i / (n - 1f);
            int c;
            if (schoner) {
                float q = y / viewH;
                c = q <= .46f ? WetterLage.mix(L.himmel[0], L.himmel[1], q / .46f)
                    : (q <= .80f ? WetterLage.mix(L.himmel[1], L.himmel[2], (q - .46f) / .34f)
                    : WetterLage.mix(L.himmel[2], 0xFF0D1720, Math.min(1, (q - .80f) / .20f)));
            } else {
                float q = y / H;
                c = q <= .3f ? WetterLage.mix(L.himmel[0], L.himmel[1], q / .3f)
                    : (q <= .55f ? WetterLage.mix(L.himmel[1], L.himmel[2], (q - .3f) / .25f)
                    : WetterLage.mix(L.himmel[2], 0xFF0D1720, Math.min(1, (q - .55f) / .07f)));
            }
            f[i] = WetterLage.tont(c, y, L);
            pos[i] = i / (n - 1f);
        }
        himmelShader = new LinearGradient(0, 0, 0, skyH, f, pos, Shader.TileMode.CLAMP);
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
        float deckH = schoner ? hz + 20 : Wolken.YH + 60;
        deckBlau = "cloudy".equals(lage.cond) ? new LinearGradient(0, 0, 0, deckH,
            new int[] {WetterLage.tont(0xFF76A9DB, 0, L), WetterLage.tont(0xFFA9C7E6, deckH * .55f, L), WetterLage.tont(0xFFD4DEE9, deckH, L)}, new float[] {0, .55f, 1}, Shader.TileMode.CLAMP) : null;
        if (lage.cover >= 1) {
            deckTag = new LinearGradient(0, 0, 0, deckH, WetterLage.tont(WetterLage.mix(wt[1], wt[2], .45f), 0, L), WetterLage.tont(wt[3], deckH, L), Shader.TileMode.CLAMP);
            deckNacht = new LinearGradient(0, 0, 0, deckH, WetterLage.tont(WetterLage.mix(wn[1], wn[2], .45f), 0, L), WetterLage.tont(wn[3], deckH, L), Shader.TileMode.CLAMP);
        } else {
            deckTag = deckNacht = null;
        }
        // subtle horizon atmospheric haze right above the sea
        int hazeCol = WetterLage.tont(L.himmel[2], hz, L);
        dunstHorizont = new LinearGradient(0, hz - 26, 0, hz + 2, WetterLage.alpha(hazeCol, 0), WetterLage.alpha(hazeCol, .24f), Shader.TileMode.CLAMP);
        // cloud rows take the light of the hour through a colour filter (the bitmaps stay)
        List<Wolken.Reihe> rh = E.reihen;
        wolkenFilter = new LightingColorFilter[rh.size()];
        float stufeTag = Math.round(L.tag * FARBSTUFEN) / (float) FARBSTUFEN;
        for (int i = 0; i < rh.size(); i++) {
            float y = (float) (wolkenYh * (.16 + .84 * Math.pow(rh.get(i).d, .7)));
            int base = WetterLage.mix(wn[1], wt[1], stufeTag), soll = WetterLage.tont(WetterLage.mix(wn[1], wt[1], L.tag), y, L);
            wolkenFilter[i] = filter(base, soll);
        }
        // land, sea, island, lighthouse, boat, beach, dune, grass
        WetterLage.Land P = lage.land(L, mondDa, monat);
        float nacht = 1 - L.tag;
        float tY = landY;
        int himmelUnten = WetterLage.tont(WetterLage.mix(L.himmel[1], L.himmel[2], schoner ? .8f : (HZ / H - .3f) / .25f), hz, L);
        int meerOben = WetterLage.mix(WetterLage.tont(P.lakeA, hz + 4, L), himmelUnten, .16f);
        int meerUnten = WetterLage.mix(WetterLage.tont(P.lakeB, tY + 300, L), WetterLage.tont(L.himmel[0], tY + 300, L), .06f);
        meerShader = new LinearGradient(0, HZ, 0, 300, meerOben, meerUnten, Shader.TileMode.CLAMP);

        cInsel = WetterLage.tont(P.mid, hz - 7, L);
        inselShader = new LinearGradient(0, 198, 0, HZ + 1, WetterLage.shade(cInsel, 1.1f), WetterLage.shade(cInsel, .78f), Shader.TileMode.CLAMP);
        cTurm = WetterLage.tont(WetterLage.mix(0xFFF2EFE8, 0xFF4D525E, nacht), hz - 22, L);
        turmShader = new LinearGradient(107, 0, 117, 0, cTurm, WetterLage.shade(cTurm, .80f), Shader.TileMode.CLAMP);
        cRot = WetterLage.tont(WetterLage.mix(0xFFC94A3C, 0xFF542C30, nacht), hz - 22, L);
        rotShader = new LinearGradient(108, 0, 116, 0, cRot, WetterLage.shade(cRot, .78f), Shader.TileMode.CLAMP);
        cGalerie = WetterLage.mix(0xFF3A3C44, 0xFF1F222A, nacht);
        cLampe = WetterLage.mix(WetterLage.mix(0xFFC9D3DC, 0xFF555C66, nacht), 0xFFFFF2B8, L.leuchtturm);

        int cHolz = WetterLage.tont(WetterLage.mix(0xFF6B5A49, 0xFF282B33, nacht), hz + 70, L);
        cHolzSch = WetterLage.shade(cHolz, .68f);
        int cHolzHell = WetterLage.shade(cHolz, 1.12f);
        for (int i = 0; i < PFAHL.length; i++) {
            float px = PFAHL[i][0], pw = PFAHL[i][3];
            holzShader[i] = new LinearGradient(px - pw / 2, 0, px + pw / 2, 0, cHolzHell, cHolzSch, Shader.TileMode.CLAMP);
        }

        int sandTag = WetterLage.mix(P.stone, 0xFFDCC38E, "tag".equals(lage.landTag) ? .52f : .28f);
        int sand = lage.schnee ? P.hillA : WetterLage.mix(WetterLage.mix(P.stone, P.hillB, .42f), sandTag, L.tag);
        cSand = WetterLage.tont(sand, tY + 320, L);
        int cSandHell = WetterLage.shade(cSand, 1.08f);
        cSandDunkel = WetterLage.shade(cSand, .76f);
        strandShader = new LinearGradient(0, 289, 0, UNTEN,
            new int[] {WetterLage.shade(cSand, .84f), cSandHell, cSand, WetterLage.shade(cSand, .86f)}, new float[] {0f, .24f, .62f, 1f}, Shader.TileMode.CLAMP);
        nassShader = new LinearGradient(0, 289, 0, 315,
            WetterLage.alpha(WetterLage.mix(cSandDunkel, meerOben, .38f), .58f), WetterLage.alpha(cSandDunkel, 0f), Shader.TileMode.CLAMP);
        welleShader = new LinearGradient(0, 287, 0, 304,
            WetterLage.alpha(0xFFFFFFFF, .22f * L.tag + .08f), WetterLage.alpha(0xFFFFFFFF, .02f), Shader.TileMode.CLAMP);
        bankLShader = new LinearGradient(0, 325, 0, UNTEN,
            WetterLage.alpha(cSandHell, .48f), WetterLage.alpha(cSandDunkel, .26f), Shader.TileMode.CLAMP);

        int cTreib = WetterLage.tont(WetterLage.mix(0xFF9E8D78, 0xFF3E424D, nacht), tY + 320, L);
        treibShader = new LinearGradient(0, 314, 0, 328, WetterLage.shade(cTreib, 1.15f), WetterLage.shade(cTreib, .72f), Shader.TileMode.CLAMP);
        cStein1 = WetterLage.tont(WetterLage.mix(0xFF8A857C, 0xFF383C46, nacht), tY + 320, L);
        cStein2 = WetterLage.tont(WetterLage.mix(0xFFC8BCA8, 0xFF525662, nacht), tY + 320, L);
        cMuschel = WetterLage.tont(WetterLage.mix(0xFFF0E5D2, 0xFF686B76, nacht), tY + 320, L);

        cDuene = WetterLage.tont(lage.schnee ? P.hillA : WetterLage.mix(sand, P.hillB, .20f), tY + 310, L);
        dueneShader = new LinearGradient(0, 275, 0, UNTEN,
            new int[] {WetterLage.shade(cDuene, 1.10f), cDuene, WetterLage.shade(cDuene, .80f)}, new float[] {0f, .45f, 1f}, Shader.TileMode.CLAMP);
        cGras = WetterLage.tont(WetterLage.mix(P.grass, 0xFFC4B684, .32f), tY + 290, L);
        cGrasHinten = WetterLage.shade(cGras, .68f);
        cGrasVorne = WetterLage.shade(WetterLage.mix(cGras, 0xFFE2D49C, .30f), 1.12f);
        cAehren = WetterLage.tont(WetterLage.mix(0xFFDCC998, 0xFF5A5D66, nacht), tY + 290, L);
        cPfuetze = WetterLage.alpha(P.lakeB, .6f);
        cRumpf = WetterLage.tont(WetterLage.mix(0xFF22252C, 0xFF3A3F48, L.tag), hz, L);
        cSegel = WetterLage.tont(WetterLage.mix(0xFF5A6070, 0xFFF4F2EC, L.tag), hz - 14, L);
        cFock = WetterLage.tont(WetterLage.mix(0xFF50566A, 0xFFE6E2D8, L.tag), hz - 12, L);
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
        if (sternXY == null || Math.abs(simZeit - sternZeit) > 15_000L || Math.abs(hz - sternHz) > .5f) {
            sternZeit = simZeit;
            sternHz = hz;
            sternXY = new float[sterne.length][];
            for (int i = 0; i < sterne.length; i++) {
                double[] h = Himmel.stern(sterne[i][0], sterne[i][1], mond.lst, mond.phi);
                sternXY[i] = h[0] < 1 ? null : new float[] {Himmel.x(h[1]), himmelY(h[0])};
            }
            bildXY = new float[Himmel.STERNBILDER.length][][];
            for (int k = 0; k < Himmel.STERNBILDER.length; k++) {
                Himmel.Sternbild sb = Himmel.STERNBILDER[k];
                bildXY[k] = new float[sb.s.length][];
                for (int i = 0; i < sb.s.length; i++) {
                    double[] h = Himmel.stern(sb.s[i][0], sb.s[i][1], mond.lst, mond.phi);
                    bildXY[k][i] = h[0] < -.6 ? null : new float[] {Himmel.x(h[1]), himmelY(h[0])};
                }
            }
        }
        // background stars at fixed sky positions, each twinkling on its own
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < sterne.length; i++) {
            float[] s = sterne[i], xy = sternXY[i];
            if (xy == null || xy[0] < -3 || xy[0] > W + 3 || xy[1] < 0 || xy[1] > hz - 3) continue;
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
        float grenze = hz + (schoner ? 20 : 60);
        for (int k = 0; k < Himmel.STERNBILDER.length; k++) {
            Himmel.Sternbild sb = Himmel.STERNBILDER[k];
            float[][] q = bildXY[k];
            for (int[] pl : sb.l) {
                for (int i = 1; i < pl.length; i++) {
                    float[] u = q[pl[i - 1]], v = q[pl[i]];
                    if (u != null && v != null && Math.hypot(u[0] - v[0], u[1] - v[1]) < 340 && Math.min(u[1], v[1]) < grenze) c.drawLine(u[0], u[1], v[0], v[1], linie);
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
                if (u[0] > 30 && u[0] < W - 30 && u[1] > 10 && u[1] < hz - 30) {
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
        if (lage.cover <= 0) return;
        float tag = licht.tag;
        float deckH = schoner ? hz + 20 : Wolken.YH + 60;
        if (deckBlau != null && tag > 0) {
            mitShader(deckBlau);
            p.setAlpha(Math.round(255 * tag));
            c.drawRect(0, 0, W, deckH, p);
        }
        if (deckNacht != null) {
            mitShader(deckNacht);
            c.drawRect(0, 0, W, deckH, p);
            p.setShader(deckTag);
            p.setAlpha(Math.round(255 * tag));
            c.drawRect(0, 0, W, deckH, p);
        }
        p.setShader(null);
        p.setAlpha(255);
        float ein = wolkenAlpha(anim);
        if (ein <= 0) return;
        float wechsel = Math.min(1, (anim - E.wechselSeit) / 1500f);
        if (E.vorher != null && wechsel < 1) reihenZeichnen(c, anim, E.vorherReihen, E.vorher, E.vorherShader, E.vorherY0, E.vorherY1, ein);
        else if (E.vorher != null) {
            spaeterRecyceln(E.vorher);
            E.vorher = null;
            E.vorherShader = null;
            E.vorherY0 = E.vorherY1 = null;
            E.vorherReihen = null;
        }
        reihenZeichnen(c, anim, E.reihen, E.wolken, E.wolkenShader, E.wolkenY0, E.wolkenY1, E.vorher != null ? ein * wechsel : ein);
    }

    private void reihenZeichnen(Canvas c, long anim, List<Wolken.Reihe> rh, Bitmap[] bm, BitmapShader[] sh, float[] y0, float[] y1, float alpha) {
        bild.setColor(0xFFFFFFFF);
        bild.setAlpha(Math.round(255 * alpha));
        for (int i = 0; i < rh.size() && i < bm.length; i++) {
            Bitmap b = bm[i];
            BitmapShader s = sh != null && i < sh.length ? sh[i] : null;
            if (b == null || b.isRecycled() || s == null) continue;
            Wolken.Reihe reihe = rh.get(i);
            float tempo = lage.schnell ? 1700f / reihe.dauer : 600f / reihe.dauer;   // px per s, far rows slower
            float o = (float) ((anim * .001 * tempo) % Wolken.BREITE);
            wolkenMatrix.setScale(Wolken.BREITE / b.getWidth(), (y1[i] - y0[i]) / b.getHeight());
            wolkenMatrix.postTranslate(o, y0[i]);
            s.setLocalMatrix(wolkenMatrix);
            bild.setShader(s);
            bild.setColorFilter(i < wolkenFilter.length ? wolkenFilter[i] : null);
            c.drawRect(0, y0[i], W, y1[i], bild);
        }
        bild.setShader(null);
        bild.setColorFilter(null);
        bild.setAlpha(255);
    }

    /** Thunderstorm: four flashes per 24 s inside the clouds and a faint brightening of the scene (no strikes). */
    private void blitzWolken(Canvas c, float t) {
        if (!lage.blitz || blitze.isEmpty()) return;
        float zyk = t % 24, ySkala = wolkenYh / Wolken.YH, h = schoner ? viewH : UNTEN;
        for (int i = 0; i < blitze.size(); i++) {
            float[][] e = blitze.get(i);
            float op = blitzWert(e, zyk);
            if (op <= 0) continue;
            float[] k = e[0];   // {x, y, s, a, t0}
            for (int b = 0; b < 3; b++) {
                float bx = k[0] + (b - 1) * 120 * k[2], by = k[1] * ySkala + (b == 1 ? 0 : 14) * k[2], rx = 160 * k[2] * (b == 1 ? 1 : .55f);
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
            c.drawRect(0, 0, W, h, p);
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

    /** Sea gradient, horizon highlight, calm shimmer and wave crests (drawn in landY-shifted coords). */
    private void see(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        mitShader(meerShader);
        c.drawRect(0, HZ, W, 300, p);
        p.setShader(null);
        p.setColor(WetterLage.alpha(0xFFFFFFFF, .16f));
        c.drawRect(0, HZ - .8f, W, HZ + .8f, p);
        if (!lage.wind) {
            p.setColor(WetterLage.alpha(0xFFFFFFFF, .09f));
            c.drawPath(GLANZ, p);
        }
        if (lage.wind || lage.stark || lage.blitz) {
            linie.setStrokeWidth(1.2f);
            linie.setColor(0x8CFFFFFF);
            c.drawPath(KAMM, linie);
        }
    }

    /** Shaded island, water reflection shadow and 3D-shaded lighthouse tower, gallery, lantern & dome. */
    private void inselUndTurm(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(WetterLage.alpha(0xFF0A121C, .22f));
        c.drawPath(INSEL_SCHATTEN, p);
        mitShader(inselShader);
        c.drawPath(INSEL, p);
        mitShader(turmShader);
        c.drawPath(TURM, p);
        mitShader(rotShader);
        c.drawPath(TURM_ROT, p);
        p.setShader(null);
        // Gallery balcony & fine railing
        p.setColor(cGalerie);
        r.set(107.2f, 175.5f, 116.8f, 177.8f);
        c.drawRoundRect(r, 1, 1, p);
        linie.setStrokeWidth(.7f);
        linie.setColor(cGalerie);
        c.drawLine(107.8f, 173.8f, 116.2f, 173.8f, linie);
        c.drawLine(108.0f, 173.8f, 108.0f, 175.8f, linie);
        c.drawLine(116.0f, 173.8f, 116.0f, 175.8f, linie);
        // Lantern room glass + mullions
        p.setColor(cLampe);
        c.drawRect(109.5f, 169.5f, 114.5f, 175.5f, p);
        linie.setStrokeWidth(.6f);
        linie.setColor(WetterLage.alpha(cGalerie, .75f));
        c.drawLine(111.2f, 169.5f, 111.2f, 175.5f, linie);
        c.drawLine(112.8f, 169.5f, 112.8f, 175.5f, linie);
        // Rounded red dome + pinnacle
        p.setColor(cRot);
        c.drawPath(KUPPEL, p);
        linie.setStrokeWidth(.9f);
        linie.setColor(cGalerie);
        c.drawLine(112, 166.5f, 112, 164.2f, linie);
        // Warm lit tower window at night
        if (licht.leuchtturm > .05f) {
            p.setColor(WetterLage.alpha(0xFFFFD98A, .85f * licht.leuchtturm));
            r.set(111.2f, 186.5f, 112.8f, 189.0f);
            c.drawRoundRect(r, .6f, .6f, p);
        }
    }

    /** Wooden groynes, multi-stop beach, glossy wet swash, surf crests, ripples, driftwood, pebbles, seamless dune & back/mid grass. */
    private void strandUndDuene(Canvas c, float t) {
        p.setStyle(Paint.Style.FILL);
        // North-Sea wooden groyne posts (Buhnen) standing in the shallow surf
        for (int i = 0; i < PFAHL.length; i++) {
            float px = PFAHL[i][0], py = PFAHL[i][1], ph = PFAHL[i][2], pw = PFAHL[i][3];
            p.setColor(WetterLage.alpha(cHolzSch, .26f));
            r.set(px - pw * .45f, py, px + pw * .45f, py + ph * .48f);
            c.drawRoundRect(r, 1, 1, p);
            mitShader(holzShader[i]);
            r.set(px - pw / 2, py - ph, px + pw / 2, py + 1.5f);
            c.drawRoundRect(r, 1.2f, 1.2f, p);
            p.setShader(null);
            p.setColor(WetterLage.alpha(0xFFFFFFFF, .28f));
            r.set(px - pw * 1.1f, py - .8f, px + pw * 1.1f, py + 1.4f);
            c.drawOval(r, p);
        }
        // Main beach body, glossy tidal swash, translucent receding wave sheet
        mitShader(strandShader);
        c.drawPath(STRAND, p);
        mitShader(nassShader);
        c.drawPath(NASS, p);
        mitShader(welleShader);
        c.drawPath(WELLE, p);
        p.setShader(null);
        // Animated surf foam lines (two layered crests)
        for (int i = 0; i < 2; i++) {
            float ph = (float) ((t + i * 2) % 4 / 4.0), w = (float) (.5 - .5 * Math.cos(ph * 2 * Math.PI));
            linie.setStrokeWidth(i == 0 ? 2.2f : 1.2f);
            linie.setColor(WetterLage.alpha(0xFFFFFFFF, (i == 0 ? .58f : .32f) * (.9f - .45f * w)));
            c.save();
            c.translate(0, 3.5f * w);
            c.drawPath(i == 0 ? BRANDUNG1 : BRANDUNG2, linie);
            c.restore();
        }
        // Wind-sculpted sand ripples and left foreground sand mound
        linie.setStrokeWidth(3.2f);
        linie.setColor(WetterLage.alpha(cSandDunkel, .20f));
        c.drawPath(RIPPEL_S, linie);
        linie.setStrokeWidth(1.6f);
        linie.setColor(WetterLage.alpha(0xFFFFF6E0, .14f * licht.tag + .03f));
        c.drawPath(RIPPEL_L, linie);
        mitShader(bankLShader);
        c.drawPath(BANK_L, p);
        p.setShader(null);
        // Weathered driftwood branch + grounded shadow
        p.setColor(WetterLage.alpha(0xFF0A1018, .22f));
        c.drawPath(TREIB_SCHATTEN, p);
        mitShader(treibShader);
        c.drawPath(TREIBHOLZ, p);
        p.setShader(null);
        // Footprints, pebbles and shells
        p.setColor(WetterLage.alpha(cSandDunkel, .24f));
        c.drawPath(SPUREN, p);
        p.setColor(WetterLage.alpha(0xFF0A1018, .22f));
        c.drawPath(STEINE_SCHATTEN, p);
        p.setColor(cStein1); c.drawPath(STEINE_0, p);
        p.setColor(cStein2); c.drawPath(STEINE_1, p);
        p.setColor(cMuschel); c.drawPath(STEINE_2, p);
        if (lage.stark) {
            p.setColor(cPfuetze);
            c.drawPath(PFUETZEN, p);
        }
        // Seamless organic dune + sculptural fold + sunlit crest rim
        mitShader(dueneShader);
        c.drawPath(DUENE, p);
        p.setShader(null);
        linie.setStrokeWidth(4.5f);
        linie.setColor(WetterLage.alpha(cSandDunkel, .15f));
        c.drawPath(DUENE_FALTE, linie);
        linie.setStrokeWidth(1.2f);
        linie.setColor(WetterLage.alpha(0xFFFFF5DC, .18f * licht.tag + .04f));
        c.drawPath(DUENE_KAMM, linie);
        // Back and mid marram grass layers (behind the figure)
        p.setColor(cGrasHinten); c.drawPath(GRAS_HINTEN, p);
        p.setColor(cGras); c.drawPath(GRAS_MITTE, p);
    }

    /** Front highlight marram grass blades & seed heads (in front of the figure's boots and bottom-left mound). */
    private void grasVorne(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(cGrasVorne); c.drawPath(GRAS_VORNE, p);
        p.setColor(cAehren); c.drawPath(GRAS_AEHREN, p);
    }

    /** The figure: tail (wagging), body, head (looking around a little), scarf; small layer bitmaps, only transformed. */
    private void figur(Canvas c, float t) {
        Bitmap[] bm = E.figur;
        if (bm == null) return;
        long anim = SystemClock.uptimeMillis();
        float alpha = 1;
        if (!figurSchonDa) {
            if (figurSichtbarSeit < 0) figurSichtbarSeit = anim;
            alpha = Math.min(1, (anim - figurSichtbarSeit) / 450f);
        }
        float k = 1 / (skala * FIGUR_S);
        float[] dreh = E.figurDreh;
        // wf-head: 9 s alternate, -1.5 deg for the first 30 %, 2 deg from 70 %; wf-tail: 0.5 s alternate, -10 .. 14 deg
        float u = (t % 18) / 9;
        if (u > 1) u = 2 - u;
        float kopf = u < .3f ? -1.5f : (u > .7f ? 2 : -1.5f + 3.5f * WetterLage.glatt(.3, .7, u));
        float v = (t % 1) / .5f;
        if (v > 1) v = 2 - v;
        float schwanz = -10 + 24 * WetterLage.glatt(0, 1, v);
        int[] reihe = {SvgMini.SCHWANZ, SvgMini.KOERPER, SvgMini.KOPF, SvgMini.VORNE};
        bild.setAlpha(Math.round(255 * alpha));
        c.save();
        c.translate(FIGUR_X, FIGUR_Y);
        c.scale(FIGUR_S, FIGUR_S);
        for (int s : reihe) {
            Bitmap b = s < bm.length ? bm[s] : null;
            if (b == null || b.isRecycled()) continue;
            float[] l = E.figurLage[s];
            c.save();
            if (s == SvgMini.KOPF) c.rotate(kopf, dreh[0], dreh[1]);
            if (s == SvgMini.SCHWANZ) c.rotate(schwanz, dreh[2], dreh[3]);
            c.translate(l[0], l[1]);
            c.scale(k, k);
            c.drawBitmap(b, 0, 0, bild);
            c.restore();
        }
        c.restore();
        bild.setAlpha(255);
    }

    /** Glitter path under the sun or moon, rain rings and wind shimmer on the water. */
    private void meerWasser(Canvas c, float t) {
        float sonneG = lage.sonneMoeglich ? WetterLage.glatt(-1, 2, sonneAlt) * (1 - lage.cover * .9f) : 0;
        float mondG = mondDa ? (float) mond.f * (1 - licht.tag) * (1 - lage.cover * .75f) : 0;
        if (sonneG > .02f && sx > -40 && sx < W + 40) glanzpfad(c, t, sx, sonneAlt < 8 ? 0xFFFFE2B0 : 0xFFFFF6E6, .55f * sonneG);
        if (mondG > .02f && mx > -40 && mx < W + 40) glanzpfad(c, t, mx, 0xFFF3EFD9, .50f * mondG);
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

    /** Volumetric rotating lighthouse beam (concentric soft cones), 3-layer Fresnel halo and water reflection shimmer. */
    private void leuchtturm(Canvas c, float t) {
        float an = licht.leuchtturm;
        if (an <= .01f) return;
        // 1. Subtle golden shimmer reflected on the water below the lighthouse
        for (int i = 0; i < 8; i++) {
            float q = i / 7f, gy = HZ + 4 + 34 * (float) Math.pow(q, 1.35);
            float gw = 4 + q * 16, fl = .72f + .28f * (float) (.5 + .5 * Math.sin(t * (1.8 + i * .31) + i * 1.7));
            p.setColor(WetterLage.alpha(0xFFFFDF8E, .28f * an * fl * (1 - q * .55f)));
            r.set(TURM_LX - gw / 2, gy, TURM_LX + gw / 2, gy + 1.1f + q * .6f);
            c.drawRoundRect(r, 1, 1, p);
        }
        // 2. Smoothly rotating double Fresnel optic (10 s full turn; beams breathe softly instead of popping off)
        double ang = t * 2 * Math.PI / 10.0;
        float opR = (.16f + .84f * (float) Math.pow(.5 + .5 * Math.cos(ang), 1.8)) * an;
        float opL = (.14f + .76f * (float) Math.pow(.5 - .5 * Math.cos(ang), 1.8)) * an;
        float flash = (.80f + .20f * (float) Math.pow(Math.abs(Math.sin(ang)), 4)) * an;
        int aR = Math.round(255 * opR);
        if (aR > 2) {
            for (int i = 0; i < KEGEL_R.length; i++) {
                mitShader(KEGEL_R_SHADER[i]);
                p.setAlpha(aR);
                c.drawPath(KEGEL_R[i], p);
            }
        }
        int aL = Math.round(255 * opL);
        if (aL > 2) {
            for (int i = 0; i < KEGEL_L.length; i++) {
                mitShader(KEGEL_L_SHADER[i]);
                p.setAlpha(aL);
                c.drawPath(KEGEL_L[i], p);
            }
        }
        // 3. Multi-layer Fresnel lantern halo (outer mist + warm mid glow + pinpoint core)
        int aF = Math.round(255 * flash);
        mitShader(TURM_HALO_A); p.setAlpha(aF); c.drawCircle(TURM_LX, TURM_LY, 46, p);
        mitShader(TURM_HALO_B); p.setAlpha(aF); c.drawCircle(TURM_LX, TURM_LY, 16, p);
        mitShader(TURM_HALO_C); p.setAlpha(aF); c.drawCircle(TURM_LX, TURM_LY, 5.5f, p);
        p.setShader(null);
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
        float regenH = Math.max(660, viewH + 80), schneeH = Math.max(640, viewH + 60);
        linie.setStrokeWidth(2);
        for (float[] d : tropfen) {   // rain: falls across the scene at an angle, bright head and fading tail (sk-drop)
            float ph = ((t + d[3]) % d[2]) / d[2], s = regenH / vy * ph;
            float hx = d[0] + vx * s, hy = -40 + lage.tropfenLaenge + vy * s, len = lage.tropfenLaenge;
            linie.setColor(WetterLage.alpha(0xFFDCEBFF, .95f * d[4] * .35f));
            c.drawLine(hx - vx * len, hy - vy * len, hx - vx * len * .45f, hy - vy * len * .45f, linie);
            linie.setColor(WetterLage.alpha(0xFFDCEBFF, .95f * d[4]));
            c.drawLine(hx - vx * len * .45f, hy - vy * len * .45f, hx, hy, linie);
        }
        linie.setStrokeWidth(1);
        for (float[] g : spritzer) {   // splashes on the beach/ground in a downpour
            float ph = ((t + g[3]) % g[2]) / g[2], s = .2f + 1.4f * ph;
            float gy = g[1] + landY;
            linie.setColor(WetterLage.alpha(0xFFDCEBFF, .6f * .9f * (1 - ph)));
            r.set(g[0] + 8 - 8 * s, gy + 2 - 2 * s, g[0] + 8 + 8 * s, gy + 2 + 2 * s);
            c.drawOval(r, linie);
        }
        for (float[] f : flocken) {   // snow: 0 -> (30, half) -> (-10, full) (sk-snow)
            float ph = ((t + f[3]) % f[2]) / f[2];
            float x = f[0] + (ph < .5f ? 60 * ph : 30 - 80 * (ph - .5f)), y = -20 + schneeH * ph;
            p.setColor(WetterLage.alpha(0xFFFFFFFF, f[4]));
            c.drawCircle(x + f[1] / 2, y + f[1] / 2, f[1] / 2, p);
        }
        float hagelSkala = (334 + landY) / 334f;
        for (float[] h : hagel) {   // hail: falls, bounces twice on the beach, lies (sk-hail)
            float ph = ((t + h[3]) % h[2]) / h[2];
            float[] xy = hagelBahn(ph);
            p.setColor(WetterLage.alpha(0xFFE6F0FA, ph > .97f ? (1 - ph) / .03f : 1));
            c.drawCircle(h[0] + xy[0] + h[1] / 2, -20 + xy[1] * hagelSkala + h[1] / 2, h[1] / 2, p);
        }
        if (nebelBaender != null) {   // fog banks drifting to and fro (sk-fog)
            for (int i = 0; i < 4; i++) {
                float dur = 50 + i * 15, ph = (float) (.5 - .5 * Math.cos(((t + i * 11) % (2 * dur)) / (2 * dur) * 2 * Math.PI));
                float top = 80 + i * 70;
                mitShader(nebelBaender[i]);
                c.save();
                c.translate(-60 + 120 * ph, landY * (.25f + .2f * i));
                c.drawRect(-100, top - 26, W + 100, top + 116, p);
                c.restore();
            }
            p.setShader(null);
        }
        if (dunst != null) {
            c.save();
            c.translate(0, landY);
            mitShader(dunst);
            c.drawRect(0, 160, W, 340, p);
            p.setShader(null);
            c.restore();
        }
        if (boeen != null) {   // wind: gusts and leaves
            float windBand = schoner ? viewH - 60 : 300;
            for (int i = 0; i < boeen.length; i++) {
                float dur = 2.2f + (i * .37f % 2), ph = ((t + i * .61f) % dur) / dur, breite = 120 + (i * 53 % 220);
                c.save();
                c.translate(-400 + 1500 * ph, 30 + (i * 97 % windBand));
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
                c.translate(-40 + kx0 + (kx1 - kx0) * u + 7, b[0] + landY * .75f + ky0 + (ky1 - ky0) * u + 4);
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
     * large bitmap is given back at once. Only the band of y the row covers is kept (with enough transparent margin for
     * downsampling and bilinear upscaling). Colours of one moment (tag).
     * Returns {Bitmap[], float[] y0 (top page y of the band), float[] y1 (bottom page y of the band)}.
     */
    static Object[] wolkenBilder(List<Wolken.Reihe> rh, WetterLage lg, boolean mond, float sk, float tag) {
        return wolkenBilder(rh, lg, mond, sk, tag, UNTEN);
    }

    static Object[] wolkenBilder(List<Wolken.Reihe> rh, WetterLage lg, boolean mond, float sk, float tag, float maxY) {
        Bitmap[] out = new Bitmap[rh.size()];
        float[] y0s = new float[rh.size()], y1s = new float[rh.size()];
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
            float yMin = Float.MAX_VALUE, yMax = 0;
            for (Wolken.Haufen h : reihe.haufen) {
                yMin = Math.min(yMin, Math.min(h.top, h.by - h.h * 1.15f));
                yMax = Math.max(yMax, h.by);
            }
            float rand = Math.max(4f, 2f / qZiel);   // at least two final bitmap pixels so blur / bilinear upscale never clips
            yMin = Math.max(0, yMin - rand);
            yMax = Math.min(maxY, yMax + rand);
            if (yMax <= yMin) continue;
            float dd = reihe.d * .6f;
            int hi = WetterLage.mix(wp[0], wp[3], dd), bo = WetterLage.mix(wp[1], wp[3], dd), sh = WetterLage.mix(wp[2], wp[3], dd);
            int wEnd = Math.max(1, Math.round(Wolken.BREITE * qZiel)), hEnd = Math.max(1, Math.round((yMax - yMin) * qZiel));
            int w0 = wEnd << stufen, h0 = hEnd << stufen;   // exact power-of-two multiples so every halving is a true 2 x 2 box average
            Bitmap b = Bitmap.createBitmap(w0, h0, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(w0 / Wolken.BREITE, h0 / (yMax - yMin));
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
                Bitmap h = Bitmap.createBitmap(b.getWidth() / 2, b.getHeight() / 2, Bitmap.Config.ARGB_8888);
                new Canvas(h).drawBitmap(b, null, new Rect(0, 0, h.getWidth(), h.getHeight()), filter);
                b.recycle();
                b = h;
            }
            out[i] = b;
            y0s[i] = yMin;
            y1s[i] = yMax;
        }
        return new Object[] {out, y0s, y1s};
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
