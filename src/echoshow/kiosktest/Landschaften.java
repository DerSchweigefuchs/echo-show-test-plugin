package echoshow.kiosktest;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Native Canvas renderer for the five non-sea landscapes of the dashboard's weather scene
 * ("see", "lichtung", "tal", "gipfel", "felder"), complementing "meer" in WetterView.
 * All static geometry paths are built once; light- and season-dependent shaders are rebuilt
 * only when the sun altitude or weather changes, keeping per-frame allocations at zero.
 * Every landscape places the figure of the day at (820, 286) in 963 x 362 scene coordinates.
 */
final class Landschaften {
    static final float W = WetterView.W, UNTEN = WetterView.UNTEN;
    static final String[] ORTE = {"see", "lichtung", "tal", "meer", "gipfel", "felder"};

    static String ortFuer(String einstellung, int tagNr) {
        if (einstellung != null) {
            for (String o : ORTE) if (o.equals(einstellung)) return o;
        }
        int idx = ((tagNr * 5 + 1) % ORTE.length + ORTE.length) % ORTE.length;
        return ORTE[idx];
    }

    static String ortName(String ort) {
        if (ort == null) return "Auto";
        switch (ort) {
            case "see": return "See";
            case "lichtung": return "Lichtung";
            case "tal": return "Tal";
            case "meer": return "Meer";
            case "gipfel": return "Gipfel";
            case "felder": return "Felder";
            default: return "Auto";
        }
    }

    /** Season index: 0 = spring (Mar-May), 1 = summer (Jun-Aug), 2 = autumn (Sep-Nov), 3 = winter (Dec-Feb). */
    private static int saison(int monat) {
        if (monat >= 2 && monat <= 4) return 0;
        if (monat >= 5 && monat <= 7) return 1;
        if (monat >= 8 && monat <= 10) return 2;
        return 3;
    }

    // ==== static geometry built once (reused across all frames) =======================================================

    private static final Path SEE_FAR = new Path(), SEE_FAR_SCHNEE = new Path(), SEE_MID = new Path(),
        SEE_TANNEN = new Path(), SEE_TANNEN_SCHNEE = new Path(), SEE_HUETTE = new Path(), SEE_DACH = new Path(),
        SEE_EIS = new Path(), SEE_UFER = new Path(), SEE_UFER2 = new Path(),
        SEE_GRAS_H = new Path(), SEE_GRAS_M = new Path(), SEE_GRAS_V = new Path();
    private static final float[][] SEE_STEINE = {{150, 312, 13}, {178, 315, 7}, {610, 318, 10}, {890, 296, 8}};

    private static final Path LICHT_FAR = new Path(), LICHT_HORIZ = new Path(),
        LICHT_WALD_BASIS = new Path(), LICHT_TANNEN = new Path(), LICHT_TANNEN_SCHNEE = new Path(),
        LICHT_WIESE = new Path(), LICHT_WEG = new Path(), LICHT_KUPPE = new Path(),
        LICHT_GRAS_H = new Path(), LICHT_GRAS_M = new Path(), LICHT_GRAS_V = new Path(), LICHT_WIESENGRAS = new Path(),
        LICHT_ZELT_SEITE = new Path(), LICHT_ZELT_FRONT = new Path(), LICHT_ZELT_SCHATTEN = new Path(),
        LICHT_ZELT_TUER = new Path(), LICHT_ZELT_KLAPPE = new Path(), LICHT_ZELT_LEINEN = new Path(),
        LICHT_ZELT_SCHNEE = new Path(), LICHT_HOLZ = new Path(),
        LICHT_FLAMME1 = new Path(), LICHT_FLAMME2 = new Path(), LICHT_FLAMME3 = new Path();
    private static final float[][] LICHT_LAUB;   // {x, y, r, colorIdx}
    private static final float[][] LICHT_GLUEH;  // {x, y, phase}
    private static final float[][] LICHT_STEINE = {{650, 314, 8}, {905, 300, 7}};

    private static final Path TAL_KAMM = new Path(), TAL_KAMM_SCHATTEN = new Path(),
        TAL_KAMM_SCHNEE_HOCH = new Path(), TAL_KAMM_SCHNEE_ALLE = new Path(),
        TAL_BODEN = new Path(), TAL_STRASSE = new Path(), TAL_FLUSS_UFER = new Path(), TAL_FLUSS = new Path(),
        TAL_SPORN1 = new Path(), TAL_SPORN2 = new Path(), TAL_SPORN2_SCHNEE = new Path(),
        TAL_WALD2 = new Path(), TAL_WALD2_SCHNEE = new Path(),
        TAL_FLANKEN = new Path(), TAL_WALD3 = new Path(), TAL_WALD3_SCHNEE = new Path(),
        TAL_KANTE_GRAS = new Path(), TAL_FELS = new Path(), TAL_FELS_D = new Path(), TAL_FELS_LICHT = new Path(),
        TAL_GRAS_H = new Path(), TAL_GRAS_M = new Path(), TAL_GRAS_V = new Path(),
        TAL_ZELT = new Path(), TAL_ZELT_S = new Path(), TAL_KIRCHE_DACH = new Path();
    private static final float[][][] TAL_FELD_QUADS; // [n][4][2]
    private static final float[][] TAL_FELD_META;    // {v, colIdx, isColor, shadeF}
    private static final float[][] TAL_HECKEN;       // {x0, x1, y, width}
    private static final float[][] TAL_BAEUME;       // {x, y, r, v, colIdx}
    private static final float[][] TAL_FLUSS_GLANZ;  // {x, y, w, h, phase}
    private static final float[][] TAL_HAEUSER = {{478, 284, 1}, {490, 288, 1.05f}, {503, 283, 1}, {514, 290, 1.1f}, {527, 285, 1.05f}, {495, 280, .9f}, {540, 291, 1.1f}};
    private static final float[][] TAL_STEINE = {{700, 300, 8}, {930, 297, 6}};

    private static final Path GIPF_R1 = new Path(), GIPF_R1_SCHATTEN = new Path(), GIPF_R1_SCHNEE = new Path(),
        GIPF_R2 = new Path(), GIPF_R2_SCHATTEN = new Path(), GIPF_R2_SCHNEE = new Path(),
        GIPF_R3 = new Path(), GIPF_R3_SCHATTEN = new Path(), GIPF_R3_SCHNEE = new Path(),
        GIPF_FELS = new Path(), GIPF_FELS_D = new Path(), GIPF_FELS_LICHT = new Path(), GIPF_FELS_SCHNEE = new Path(),
        GIPF_KREUZ_DACH = new Path(), GIPF_GRAS_H = new Path(), GIPF_GRAS_M = new Path(), GIPF_GRAS_V = new Path();
    private static final float[][] GIPF_WOLKEN1, GIPF_WOLKEN2;
    private static final float[][] GIPF_STEINE = {{920, 300, 6}};

    private static final Path FELD_FAR = new Path(), FELD_HUEGEL_A = new Path(), FELD_HUEGEL_B = new Path(),
        FELD_WEG_STRASSE = new Path(), FELD_VORDER = new Path(), FELD_PFAD = new Path(),
        FELD_SCHEUNE_DACH = new Path(), FELD_HAUS_DACH = new Path(), FELD_ZAUN_DRAHT = new Path(),
        FELD_GRAS_H = new Path(), FELD_GRAS_M = new Path(), FELD_GRAS_V = new Path();
    private static final Path[] FELD_A_STREIFEN, FELD_B_STREIFEN;
    private static final Path FELD_A_FURC = new Path(), FELD_B_FURC = new Path();
    private static final int[] FELD_A_IDX, FELD_B_IDX;
    private static final float[][] WINDRAEDER = {{640, 196, 34}, {700, 194, 30}, {756, 200, 26}};
    private static final float[][] FELD_STEINE = {{180, 312, 8}, {905, 300, 7}};

    private static final float[][] BLUMEN_XY = new float[16][3]; // {u, dy, colIdx}
    private static final float[][] HAGEL_XY = new float[40][3];  // {u, dy, r}
    private static final float[][] VOEGEL = {{76, 1.0f, 0f, 34f}, {102, .82f, 12.5f, 40f}, {126, .92f, 24.3f, 37f}};

    private static final Shader WIN_GLOW = new RadialGradient(0, 0, 1,
        new int[] {0xC8FFC86E, 0x55FFB85A, 0x00FFC86E}, new float[] {0f, .45f, 1f}, Shader.TileMode.CLAMP);
    private static final Shader FIRE_GLOW = new RadialGradient(0, 0, 1,
        new int[] {0x99FFAA4A, 0x3DFF8A2A, 0x00FF8A2A}, new float[] {0f, .48f, 1f}, Shader.TileMode.CLAMP);

    /** Tapered organic grass blade (shared aesthetic with Meer). */
    static void addBlade(Path p, float bx, float by, float len, float lean, float bend, float halfW) {
        float tipX = bx + lean, tipY = by - len;
        float cx = bx + lean * .42f + bend, cy = by - len * .56f;
        p.moveTo(bx - halfW, by + 1.2f);
        p.quadTo(cx - halfW * .45f, cy, tipX, tipY);
        p.quadTo(cx + halfW * .45f, cy, bx + halfW, by + 1.2f);
        p.close();
    }

    /** Builds three depth layers of natural tapered grass tufts along a ridge y(x). */
    private interface YFn { float at(float x); }

    private static void buildGrass(Path back, Path mid, Path front, YFn yAt, float x0, float x1, float gapA, float gapB, float scale, int seed) {
        Wolken.Zufall R = new Wolken.Zufall(seed);
        for (float x = x0 + 4; x < x1 - 2; x += (float) (13 + R.next() * 17)) {
            if (x > gapA && x < gapB) continue;
            float by = yAt.at(x) + 1.5f;
            int n = 4 + (int) Math.floor(R.next() * 4);
            for (int k = 0; k < n; k++) {
                float u = (k / (float) Math.max(1, n - 1)) - .5f;
                float bx = x + u * 7 + (float) (R.next() - .5) * 2.5f;
                float len = (float) (7 + R.next() * 9) * scale * (1 - Math.abs(u) * .3f);
                float lean = u * (7 + (float) R.next() * 6) + 2f + (float) (R.next() - .4) * 4;
                addBlade(back, bx, by + (float) R.next() * 2, len, lean, (float) (R.next() - .5) * 3, .8f * scale);
            }
        }
        for (float x = x0 + 2; x < x1 - 2; x += (float) (10 + R.next() * 14)) {
            if (x > gapA + 2 && x < gapB - 2) continue;
            float by = yAt.at(x) + 2.5f;
            int n = 5 + (int) Math.floor(R.next() * 4);
            for (int k = 0; k < n; k++) {
                float u = (k / (float) Math.max(1, n - 1)) - .5f;
                float bx = x + u * 8 + (float) (R.next() - .5) * 2.5f;
                float len = (float) (8 + R.next() * 11) * scale * (1 - Math.abs(u) * .28f);
                float lean = u * (8 + (float) R.next() * 7) + 2.5f + (float) (R.next() - .35) * 5;
                addBlade(mid, bx, by + (float) R.next() * 2.5f, len, lean, (lean > 0 ? 1 : -1) * (1f + (float) R.next() * 2f), .85f * scale);
            }
        }
        for (float x = x0 + 8; x < x1 - 4; x += (float) (17 + R.next() * 22)) {
            if (x > gapA - 2 && x < gapB + 2) continue;
            float by = yAt.at(x) + 4.5f + (float) R.next() * 4f;
            int n = 4 + (int) Math.floor(R.next() * 3);
            for (int k = 0; k < n; k++) {
                float u = (k / (float) Math.max(1, n - 1)) - .5f;
                float bx = x + u * 6.5f + (float) (R.next() - .5) * 2;
                float len = (float) (6.5 + R.next() * 9) * scale;
                float lean = u * 7 + 3f + (float) (R.next() - .35) * 4.5f;
                addBlade(front, bx, by, len, lean, lean > 0 ? 1.2f : -1.2f, .75f * scale);
            }
        }
    }

    private static void addConifer(Path trees, Path snow, float x, float y, float h) {
        trees.moveTo(x, y - h);
        trees.lineTo(x + h * .32f, y);
        trees.lineTo(x - h * .32f, y);
        trees.close();
        if (snow != null) {
            snow.moveTo(x, y - h);
            snow.lineTo(x + h * .14f, y - h * .55f);
            snow.lineTo(x - h * .14f, y - h * .55f);
            snow.close();
        }
    }

    private static void appendBand(Path dst, float[][] cl) {
        int n = cl.length;
        float[][] l = new float[n][2], r = new float[n][2];
        for (int i = 0; i < n; i++) {
            float[] a = cl[Math.max(0, i - 1)], b = cl[Math.min(n - 1, i + 1)];
            float dx = b[0] - a[0], dy = b[1] - a[1];
            float len = (float) Math.hypot(dx, dy);
            if (len < 1e-4f) len = 1;
            float nx = -dy / len * cl[i][2] / 2f, ny = dx / len * cl[i][2] / 2f;
            l[i][0] = cl[i][0] + nx; l[i][1] = cl[i][1] + ny;
            r[n - 1 - i][0] = cl[i][0] - nx; r[n - 1 - i][1] = cl[i][1] - ny;
        }
        dst.moveTo(l[0][0], l[0][1]);
        catmull(dst, l);
        dst.lineTo(r[0][0], r[0][1]);
        catmull(dst, r);
        dst.close();
    }

    private static void catmull(Path p, float[][] pts) {
        for (int i = 0; i < pts.length - 1; i++) {
            float[] a = pts[Math.max(0, i - 1)], b = pts[i], c = pts[i + 1], e = pts[Math.min(pts.length - 1, i + 2)];
            p.cubicTo(b[0] + (c[0] - a[0]) / 6f, b[1] + (c[1] - a[1]) / 6f,
                c[0] - (e[0] - b[0]) / 6f, c[1] - (e[1] - b[1]) / 6f, c[0], c[1]);
        }
    }

    private static float linY(float[][] pts, float x) {
        for (int i = 1; i < pts.length; i++) {
            if (x <= pts[i][0]) {
                float[] a = pts[i - 1], b = pts[i];
                return a[1] + (b[1] - a[1]) * (x - a[0]) / (b[0] - a[0]);
            }
        }
        return pts[pts.length - 1][1];
    }

    private static float seeUferY(float x) {
        return x < 470 ? 302 - (x / 470f) * 6 : (x < 730 ? 304 - ((x - 470) / 260f) * 16 : 288 - ((x - 730) / 190f) * 6 + (x > 920 ? 5 : 0));
    }

    private static float lichtY(float x) {
        return x < 560 ? 304 : 304 - (x - 560) / 260f * 19 + (x > 820 ? (x - 820) * .02f : 0);
    }

    private static float talY(float x) {
        return x < 640 ? 291 + (640 - x) * .1f : 291 - (x - 640) / 180f * 6 + (x > 820 ? (x - 820) / 143f * 3 : 0);
    }

    private static float gipfY(float x) {
        return x < 744 ? 290 : 288 - (x - 744) / 56f * 3 + (x > 880 ? (x - 880) / 40f * 1.5f : 0);
    }

    private static float feldY(float x) {
        return x < 560 ? 300 - x / 560f * 6 : 294 - (x - 560) / 280f * 8 + (x > 840 ? (x - 840) / 123f * 2 : 0);
    }

    static {
        // ---- Shared flower & hail positions ----
        Wolken.Zufall R0 = new Wolken.Zufall(19);
        for (int i = 0; i < BLUMEN_XY.length; i++) BLUMEN_XY[i] = new float[] {(float) R0.next(), (float) (6 + R0.next() * 36), i % 3};
        for (int i = 0; i < HAGEL_XY.length; i++) HAGEL_XY[i] = new float[] {(float) R0.next(), (float) (2 + R0.next() * 48), (float) (1 + R0.next() * 1.2)};

        // ---- 1. SEE ----
        SEE_FAR.moveTo(0, 200);
        SEE_FAR.cubicTo(90, 178, 170, 186, 250, 196);
        SEE_FAR.cubicTo(330, 170, 420, 160, 520, 182);
        SEE_FAR.cubicTo(600, 168, 690, 172, 760, 188);
        SEE_FAR.cubicTo(840, 176, 910, 182, W, 192);
        SEE_FAR.lineTo(W, 240); SEE_FAR.lineTo(0, 240); SEE_FAR.close();
        SEE_FAR_SCHNEE.moveTo(330, 176); SEE_FAR_SCHNEE.cubicTo(380, 164, 420, 160, 470, 170); SEE_FAR_SCHNEE.cubicTo(440, 172, 400, 172, 360, 182); SEE_FAR_SCHNEE.close();
        SEE_FAR_SCHNEE.moveTo(600, 170); SEE_FAR_SCHNEE.cubicTo(640, 166, 680, 170, 720, 180); SEE_FAR_SCHNEE.cubicTo(690, 178, 650, 176, 615, 178); SEE_FAR_SCHNEE.close();
        SEE_MID.moveTo(0, 222);
        SEE_MID.cubicTo(120, 206, 240, 214, 360, 220);
        SEE_MID.cubicTo(470, 206, 600, 210, 700, 218);
        SEE_MID.cubicTo(800, 208, 900, 212, W, 216);
        SEE_MID.lineTo(W, 240); SEE_MID.lineTo(0, 240); SEE_MID.close();
        Wolken.Zufall Rs = new Wolken.Zufall(101);
        for (float x = -4; x < 980; x += (float) (7 + Rs.next() * 9)) {
            float h = (float) (10 + Rs.next() * 16), y = (float) (236 + Rs.next() * 3);
            if (x > 268 && x < 312) continue;
            addConifer(SEE_TANNEN, SEE_TANNEN_SCHNEE, x, y, h);
        }
        SEE_HUETTE.moveTo(276, 238); SEE_HUETTE.lineTo(276, 226); SEE_HUETTE.lineTo(289, 217); SEE_HUETTE.lineTo(302, 226); SEE_HUETTE.lineTo(302, 238); SEE_HUETTE.close();
        SEE_DACH.moveTo(273, 227); SEE_DACH.lineTo(289, 214.5f); SEE_DACH.lineTo(305, 227); SEE_DACH.lineTo(302, 228); SEE_DACH.lineTo(289, 218); SEE_DACH.lineTo(276, 228); SEE_DACH.close();
        SEE_EIS.moveTo(108, 259); SEE_EIS.rLineTo(40, 8); SEE_EIS.rLineTo(30, -4);
        SEE_EIS.moveTo(486, 253); SEE_EIS.rLineTo(26, 10); SEE_EIS.rLineTo(38, 2);
        SEE_EIS.moveTo(657, 273); SEE_EIS.rLineTo(30, -6); SEE_EIS.rLineTo(24, 8);
        SEE_UFER.moveTo(0, 302);
        SEE_UFER.cubicTo(160, 290, 330, 300, 470, 304);
        SEE_UFER.cubicTo(580, 306, 660, 296, 730, 288);
        SEE_UFER.cubicTo(790, 281, 860, 280, 920, 286);
        SEE_UFER.cubicTo(940, 288, 955, 290, W, 291);
        SEE_UFER.lineTo(W, UNTEN); SEE_UFER.lineTo(0, UNTEN); SEE_UFER.close();
        SEE_UFER2.moveTo(0, 318);
        SEE_UFER2.cubicTo(140, 306, 300, 316, 440, 324);
        SEE_UFER2.cubicTo(540, 330, 625, 340, 715, UNTEN);
        SEE_UFER2.lineTo(0, UNTEN); SEE_UFER2.close();
        buildGrass(SEE_GRAS_H, SEE_GRAS_M, SEE_GRAS_V, Landschaften::seeUferY, 0, W, 796, 848, 1.18f, 102);

        // ---- 2. LICHTUNG ----
        LICHT_FAR.moveTo(0, 214);
        LICHT_FAR.cubicTo(120, 202, 230, 208, 330, 214);
        LICHT_FAR.cubicTo(440, 200, 540, 198, 640, 208);
        LICHT_FAR.cubicTo(740, 200, 860, 204, W, 210);
        LICHT_FAR.lineTo(W, 252); LICHT_FAR.lineTo(0, 252); LICHT_FAR.close();
        Wolken.Zufall Rl = new Wolken.Zufall(201);
        LICHT_HORIZ.addRect(170, 234, 810, 244, Path.Direction.CW);
        for (float x = 172; x < 806; x += (float) (5 + Rl.next() * 6)) {
            LICHT_HORIZ.addCircle(x, (float) (235 + Rl.next() * 2), (float) (3 + Rl.next() * 3.5), Path.Direction.CW);
        }
        LICHT_WALD_BASIS.moveTo(-14, 170); LICHT_WALD_BASIS.lineTo(270, 252); LICHT_WALD_BASIS.lineTo(270, 262); LICHT_WALD_BASIS.lineTo(-14, 262); LICHT_WALD_BASIS.close();
        LICHT_WALD_BASIS.moveTo(700, 252); LICHT_WALD_BASIS.lineTo(980, 160); LICHT_WALD_BASIS.lineTo(980, 262); LICHT_WALD_BASIS.lineTo(700, 262); LICHT_WALD_BASIS.close();
        java.util.List<float[]> laubList = new java.util.ArrayList<>();
        float[][] raender = {{-14, 270, 150, 232}, {700, 980, 232, 140}};
        for (float[] rd : raender) {
            int idx = 0;
            for (float x = rd[0]; x <= rd[1]; idx++, x += (float) (7 + Rl.next() * 8)) {
                float y = rd[2] + (rd[3] - rd[2]) * (x - rd[0]) / (rd[1] - rd[0]) + (float) Rl.next() * 16 - 4;
                if (Rl.next() < .45) {
                    float h = (float) (34 + Rl.next() * 26);
                    addConifer(LICHT_TANNEN, LICHT_TANNEN_SCHNEE, x, y + h * .45f, h);
                } else {
                    float rad = (float) (12 + Rl.next() * 11);
                    laubList.add(new float[] {x, y, rad, idx % 4});
                }
            }
        }
        LICHT_LAUB = laubList.toArray(new float[0][]);
        LICHT_WIESE.moveTo(0, 252);
        LICHT_WIESE.cubicTo(220, 242, 420, 240, 600, 242);
        LICHT_WIESE.cubicTo(760, 244, 880, 248, W, 248);
        LICHT_WIESE.lineTo(W, UNTEN); LICHT_WIESE.lineTo(0, UNTEN); LICHT_WIESE.close();
        LICHT_WEG.moveTo(498, 244); LICHT_WEG.lineTo(506, 244);
        LICHT_WEG.cubicTo(560, 260, 620, 282, 668, 310);
        LICHT_WEG.cubicTo(700, 330, 712, 346, 716, UNTEN);
        LICHT_WEG.lineTo(650, UNTEN);
        LICHT_WEG.cubicTo(646, 344, 630, 324, 600, 304);
        LICHT_WEG.cubicTo(560, 280, 524, 262, 498, 244); LICHT_WEG.close();
        LICHT_KUPPE.moveTo(560, 304);
        LICHT_KUPPE.cubicTo(640, 292, 740, 286, 820, 285);
        LICHT_KUPPE.cubicTo(880, 284, 930, 286, W, 288);
        LICHT_KUPPE.lineTo(W, UNTEN); LICHT_KUPPE.lineTo(560, UNTEN); LICHT_KUPPE.close();
        // Tent at (642, 278)
        float zx = 642, zy = 278;
        LICHT_ZELT_SEITE.moveTo(zx, zy - 58); LICHT_ZELT_SEITE.lineTo(zx + 30, zy - 61); LICHT_ZELT_SEITE.lineTo(zx + 62, zy - 6); LICHT_ZELT_SEITE.lineTo(zx + 32, zy); LICHT_ZELT_SEITE.close();
        LICHT_ZELT_FRONT.moveTo(zx - 32, zy); LICHT_ZELT_FRONT.lineTo(zx, zy - 58); LICHT_ZELT_FRONT.lineTo(zx + 32, zy); LICHT_ZELT_FRONT.close();
        LICHT_ZELT_SCHATTEN.moveTo(zx, zy - 58); LICHT_ZELT_SCHATTEN.lineTo(zx + 32, zy); LICHT_ZELT_SCHATTEN.lineTo(zx + 25, zy); LICHT_ZELT_SCHATTEN.close();
        LICHT_ZELT_TUER.moveTo(zx - 9, zy); LICHT_ZELT_TUER.lineTo(zx, zy - 31); LICHT_ZELT_TUER.lineTo(zx + 9, zy); LICHT_ZELT_TUER.close();
        LICHT_ZELT_KLAPPE.moveTo(zx - 9, zy); LICHT_ZELT_KLAPPE.lineTo(zx, zy - 31); LICHT_ZELT_KLAPPE.lineTo(zx - 16, zy); LICHT_ZELT_KLAPPE.close();
        LICHT_ZELT_LEINEN.moveTo(zx, zy - 58); LICHT_ZELT_LEINEN.lineTo(zx, zy - 64);
        LICHT_ZELT_LEINEN.moveTo(zx + 30, zy - 61); LICHT_ZELT_LEINEN.lineTo(zx + 30, zy - 66);
        LICHT_ZELT_LEINEN.moveTo(zx - 32, zy); LICHT_ZELT_LEINEN.lineTo(zx - 41, zy + 4);
        LICHT_ZELT_LEINEN.moveTo(zx + 62, zy - 6); LICHT_ZELT_LEINEN.lineTo(zx + 72, zy - 3);
        LICHT_ZELT_SCHNEE.moveTo(zx, zy - 58); LICHT_ZELT_SCHNEE.lineTo(zx + 30, zy - 61); LICHT_ZELT_SCHNEE.lineTo(zx + 43, zy - 39); LICHT_ZELT_SCHNEE.lineTo(zx + 13, zy - 35); LICHT_ZELT_SCHNEE.close();
        LICHT_ZELT_SCHNEE.moveTo(zx - 7, zy - 45); LICHT_ZELT_SCHNEE.lineTo(zx, zy - 58); LICHT_ZELT_SCHNEE.lineTo(zx + 7, zy - 45); LICHT_ZELT_SCHNEE.lineTo(zx, zy - 49); LICHT_ZELT_SCHNEE.close();
        // Campfire logs and flame silhouettes in local coords around (0, 0) (drawn translated to 716, 300)
        LICHT_HOLZ.moveTo(-13, 1); LICHT_HOLZ.lineTo(1, -18);
        LICHT_HOLZ.moveTo(12, 2); LICHT_HOLZ.lineTo(-1, -18);
        LICHT_HOLZ.moveTo(-3, 3); LICHT_HOLZ.lineTo(0, -19);
        LICHT_FLAMME1.moveTo(-13, 0); LICHT_FLAMME1.quadTo(-15, -12, -9, -25); LICHT_FLAMME1.quadTo(-7, -17, -5, -16);
        LICHT_FLAMME1.quadTo(-4, -28, 0, -37); LICHT_FLAMME1.quadTo(3, -25, 5, -18); LICHT_FLAMME1.quadTo(7, -21, 10, -27);
        LICHT_FLAMME1.quadTo(15, -12, 13, 0); LICHT_FLAMME1.close();
        LICHT_FLAMME2.moveTo(-9, 0); LICHT_FLAMME2.quadTo(-11, -9, -6, -18); LICHT_FLAMME2.quadTo(-4, -12, -3, -11);
        LICHT_FLAMME2.quadTo(-2, -21, 0, -28); LICHT_FLAMME2.quadTo(2, -19, 3, -13); LICHT_FLAMME2.quadTo(5, -15, 7, -19);
        LICHT_FLAMME2.quadTo(10, -9, 9, 0); LICHT_FLAMME2.close();
        LICHT_FLAMME3.moveTo(-5, 0); LICHT_FLAMME3.quadTo(-6, -7, -2, -14); LICHT_FLAMME3.quadTo(0, -9, 1, -17);
        LICHT_FLAMME3.quadTo(4, -8, 5, 0); LICHT_FLAMME3.close();
        LICHT_GLUEH = new float[16][3];
        for (int i = 0; i < 16; i++) LICHT_GLUEH[i] = new float[] {(float) (120 + Rl.next() * 760), (float) (258 + Rl.next() * 70), (float) (Rl.next() * 6.28)};
        // Meadow scattered tufts (tapered organic blades)
        for (int i = 0; i < 64; i++) {
            float y = (float) (254 + Rl.next() * 88), x = (float) (Rl.next() * W);
            if (x > 610 && x < 745 && y > 266 && y < 312) continue;
            float k = .38f + (y - 254) / 88f * .75f, h = (float) (5 + Rl.next() * 6) * k;
            addBlade(LICHT_WIESENGRAS, x, y, h, -2.5f * k, -.5f, .65f * k);
            addBlade(LICHT_WIESENGRAS, x + 2.2f * k, y, h * .85f, 2.2f * k, .5f, .6f * k);
        }
        buildGrass(LICHT_GRAS_H, LICHT_GRAS_M, LICHT_GRAS_V, Landschaften::lichtY, 560, W, 796, 848, 1.2f, 202);

        // ---- 3. TAL ----
        float[][] kamm = {{140, 268}, {196, 232}, {226, 240}, {276, 198}, {306, 212}, {348, 170}, {374, 188}, {408, 176}, {446, 146},
            {480, 178}, {508, 168}, {538, 196}, {568, 158}, {602, 186}, {632, 178}, {670, 214}, {700, 206}, {770, 256}, {820, 268}};
        TAL_KAMM.moveTo(kamm[0][0], kamm[0][1]);
        for (int i = 1; i < kamm.length; i++) TAL_KAMM.lineTo(kamm[i][0], kamm[i][1]);
        TAL_KAMM.close();
        for (int i = 1; i < kamm.length - 1; i++) {
            float[] kp = kamm[i], ka = kamm[i - 1], kb = kamm[i + 1];
            if (kp[1] < ka[1] && kp[1] < kb[1]) {
                TAL_KAMM_SCHATTEN.moveTo(kp[0], kp[1]); TAL_KAMM_SCHATTEN.lineTo(kb[0], kb[1]);
                TAL_KAMM_SCHATTEN.lineTo(kb[0] - 4, 272); TAL_KAMM_SCHATTEN.lineTo(kp[0] + (kb[0] - kp[0]) * .38f + 4, 272); TAL_KAMM_SCHATTEN.close();
                float s = kp[1] < 165 ? .4f : .3f;
                float pax = kp[0] + (ka[0] - kp[0]) * s, pay = kp[1] + (ka[1] - kp[1]) * s;
                float pbx = kp[0] + (kb[0] - kp[0]) * s, pby = kp[1] + (kb[1] - kp[1]) * s;
                Path[] targets = kp[1] < 160 ? new Path[] {TAL_KAMM_SCHNEE_HOCH, TAL_KAMM_SCHNEE_ALLE} : new Path[] {TAL_KAMM_SCHNEE_ALLE};
                for (Path tg : targets) {
                    tg.moveTo(kp[0], kp[1]); tg.lineTo(pbx, pby);
                    tg.lineTo((kp[0] + pbx) / 2f + 1, (kp[1] + pby) / 2f + 4);
                    tg.lineTo(kp[0], kp[1] + (pby - kp[1]) * .55f);
                    tg.lineTo((kp[0] + pax) / 2f, (kp[1] + pay) / 2f + 3);
                    tg.lineTo(pax, pay); tg.close();
                }
            }
        }
        float Y0 = 258;
        TAL_BODEN.moveTo(300, Y0); TAL_BODEN.lineTo(640, Y0); TAL_BODEN.lineTo(940, UNTEN); TAL_BODEN.lineTo(40, UNTEN); TAL_BODEN.close();
        Wolken.Zufall Rt = new Wolken.Zufall(301);
        float VX = 470, VY = 196;
        float[] ys = {Y0, 261, 265, 270, 277, 286, 298, 314, 335, UNTEN};
        java.util.List<Float> xsList = new java.util.ArrayList<>();
        for (float xb = -260; xb < 1240; xb += (float) (70 + Rt.next() * 60)) xsList.add(xb);
        java.util.List<float[][]> qList = new java.util.ArrayList<>();
        java.util.List<float[]> mList = new java.util.ArrayList<>();
        java.util.List<float[]> hList = new java.util.ArrayList<>();
        java.util.List<float[]> bList = new java.util.ArrayList<>();
        for (int row = 0; row < ys.length - 1; row++) {
            float y0 = ys[row], y1 = ys[row + 1], v = Math.max(0, Math.min(1, ((y0 + y1) / 2f - Y0) / 104f));
            for (int col = 0; col < xsList.size() - 1; ) {
                int c1 = col + 1;
                while (c1 < xsList.size() - 1 && Rt.next() < .3) c1++;
                float xA = xsList.get(col), xB = xsList.get(c1);
                float a0 = VX + (xA - VX) * (y0 - VY) / (UNTEN - VY), b0 = VX + (xB - VX) * (y0 - VY) / (UNTEN - VY);
                float a1 = VX + (xA - VX) * (y1 - VY) / (UNTEN - VY), b1 = VX + (xB - VX) * (y1 - VY) / (UNTEN - VY);
                if (b1 > 230 && a1 < 720) {
                    qList.add(new float[][] {{a0, y0}, {b0, y0}, {b1, y1}, {a1, y1}});
                    boolean isCol = Rt.next() < .6;
                    mList.add(new float[] {v, (int) Math.floor(Rt.next() * 4), isCol ? 1 : 0, (float) (.93 + Rt.next() * .12)});
                    if (row > 1 && Rt.next() < .28) {
                        float hw = .4f + v * 1.5f, h0 = a1 + (b1 - a1) * (float) Rt.next() * .4f, h1 = b1 - (b1 - a1) * (float) Rt.next() * .4f;
                        hList.add(new float[] {h0, h1, y1, hw});
                        for (int n = 1 + (int) Math.floor(Rt.next() * 2.6); n > 0; n--) {
                            float bx = h0 + (h1 - h0) * (float) Rt.next();
                            for (int m = 2 + (int) Math.floor(Rt.next() * 4); m > 0; m--) {
                                float rr = (float) ((.7 + v * 2.4) * (.6 + Rt.next() * .7));
                                float yy = y1 - rr * .6f + (float) (Rt.next() - .5) * rr * .6f;
                                bList.add(new float[] {bx, yy, rr, v, (int) Math.floor(Rt.next() * 4)});
                                bx += rr * (1.1f + (float) Rt.next() * .6f);
                            }
                        }
                    }
                }
                col = c1;
            }
        }
        TAL_FELD_QUADS = qList.toArray(new float[0][][]);
        TAL_FELD_META = mList.toArray(new float[0][]);
        TAL_HECKEN = hList.toArray(new float[0][]);
        appendBand(TAL_STRASSE, new float[][] {{484, Y0 + 1, .5f}, {492, 266, .9f}, {504, 276, 1.4f}, {512, 288, 2.2f}, {530, 302, 3.4f}, {556, 322, 5}, {590, 350, 6.5f}, {606, 366, 7.5f}});
        float[][] fl = {{466, Y0 + 1.5f, .5f}, {474, 263, 1}, {463, 268, 1.6f}, {478, 274, 2.4f}, {470, 280, 3}, {446, 289, 4.2f}, {436, 298, 5.4f},
            {450, 309, 6.8f}, {432, 322, 8.6f}, {404, 338, 11}, {398, 352, 14}, {414, 370, 17}};
        float[][] flUfer = new float[fl.length][3];
        for (int i = 0; i < fl.length; i++) {
            float dv = Math.max(0, Math.min(1, (fl[i][1] - Y0) / 104f));
            flUfer[i] = new float[] {fl[i][0], fl[i][1], fl[i][2] + .6f + dv * 2.4f};
        }
        appendBand(TAL_FLUSS_UFER, flUfer);
        appendBand(TAL_FLUSS, fl);
        java.util.List<float[]> fgList = new java.util.ArrayList<>();
        for (int i = 3; i < fl.length - 1; i++) {
            for (int k = 0; k < 2; k++) {
                float u = k * .5f + (float) Rt.next() * .2f;
                float x = fl[i][0] + (fl[i + 1][0] - fl[i][0]) * u, y = fl[i][1] + (fl[i + 1][1] - fl[i][1]) * u;
                float w = (fl[i][2] + (fl[i + 1][2] - fl[i][2]) * u) * (.3f + (float) Rt.next() * .25f);
                float dv = Math.max(0, Math.min(1, (y - Y0) / 104f));
                fgList.add(new float[] {x - w / 2, y, w, .7f + dv * .8f, (float) (Rt.next() * 6.28)});
            }
        }
        TAL_FLUSS_GLANZ = fgList.toArray(new float[0][]);
        for (int i = 2; i < fl.length - 1; i++) {
            for (int k = 0; k < 3; k++) {
                float u = (float) Rt.next();
                if (Rt.next() < .45) continue;
                float x0 = fl[i][0] + (fl[i + 1][0] - fl[i][0]) * u, y = fl[i][1] + (fl[i + 1][1] - fl[i][1]) * u;
                float v = Math.max(0, Math.min(1, (y - Y0) / 104f));
                float ddx = fl[i + 1][0] - fl[i][0], ddy = fl[i + 1][1] - fl[i][1], dn = (float) Math.hypot(ddx, ddy);
                if (dn < 1e-4f) dn = 1;
                float off = (Rt.next() < .5 ? -1 : 1) * ((fl[i][2] + (fl[i + 1][2] - fl[i][2]) * u) / 2f + 1.8f + v * 4 + (float) Rt.next() * 3);
                float bx = x0 - ddy / dn * off, by = y + ddx / dn * off, rr = (float) ((.9 + v * 3) * (.75 + Rt.next() * .5));
                bList.add(new float[] {bx, by - rr * .5f, rr, v, (i + k) % 4});
            }
        }
        TAL_BAEUME = bList.toArray(new float[0][]);
        TAL_KIRCHE_DACH.moveTo(506.2f, 262); TAL_KIRCHE_DACH.lineTo(509.2f, 251); TAL_KIRCHE_DACH.lineTo(512.2f, 262); TAL_KIRCHE_DACH.close();
        TAL_ZELT.moveTo(396, 306); TAL_ZELT.lineTo(404, 296); TAL_ZELT.lineTo(412, 306); TAL_ZELT.close();
        TAL_ZELT_S.moveTo(404, 296); TAL_ZELT_S.lineTo(412, 306); TAL_ZELT_S.lineTo(409, 306); TAL_ZELT_S.close();
        // Spurs & wooded flanks
        TAL_SPORN1.moveTo(-10, 150); TAL_SPORN1.cubicTo(120, 158, 250, 196, 432, 261); TAL_SPORN1.lineTo(372, 306); TAL_SPORN1.lineTo(-10, 306); TAL_SPORN1.close();
        TAL_SPORN1.moveTo(973, 140); TAL_SPORN1.cubicTo(840, 150, 690, 200, 514, 260); TAL_SPORN1.lineTo(586, 306); TAL_SPORN1.lineTo(973, 306); TAL_SPORN1.close();
        TAL_SPORN2.moveTo(-10, 96); TAL_SPORN2.cubicTo(90, 108, 170, 146, 240, 192); TAL_SPORN2.cubicTo(300, 232, 350, 256, 414, 276); TAL_SPORN2.lineTo(340, 334); TAL_SPORN2.lineTo(-10, 334); TAL_SPORN2.close();
        TAL_SPORN2.moveTo(973, 90); TAL_SPORN2.cubicTo(870, 104, 790, 146, 720, 192); TAL_SPORN2.cubicTo(660, 230, 610, 254, 540, 274); TAL_SPORN2.lineTo(646, 334); TAL_SPORN2.lineTo(973, 334); TAL_SPORN2.close();
        float[][] topL = {{-10, 96}, {90, 108}, {170, 146}, {240, 192}, {300, 232}, {350, 256}, {414, 276}};
        float[][] topR = {{540, 274}, {610, 254}, {660, 230}, {720, 192}, {790, 146}, {870, 104}, {973, 90}};
        TAL_SPORN2_SCHNEE.moveTo(topL[0][0], topL[0][1]);
        for (int i = 1; i < 5; i++) TAL_SPORN2_SCHNEE.lineTo(topL[i][0], topL[i][1]);
        for (int i = 4; i >= 0; i--) TAL_SPORN2_SCHNEE.lineTo(topL[i][0] - 6, topL[i][1] + 18);
        TAL_SPORN2_SCHNEE.close();
        TAL_SPORN2_SCHNEE.moveTo(topR[2][0], topR[2][1]);
        for (int i = 3; i < topR.length; i++) TAL_SPORN2_SCHNEE.lineTo(topR[i][0], topR[i][1]);
        for (int i = topR.length - 1; i >= 2; i--) TAL_SPORN2_SCHNEE.lineTo(topR[i][0] + 6, topR[i][1] + 18);
        TAL_SPORN2_SCHNEE.close();
        for (int i = 0; i < 60; i++) {
            boolean lft = i % 2 == 0;
            float x = (float) (lft ? 150 + Rt.next() * 250 : 556 + Rt.next() * 270);
            float top = linY(lft ? topL : topR, x), y = (float) (top + 7 + Rt.next() * 30);
            if (y > 330 || (lft && y > top + (414 - x) * .55f + 10) || (!lft && y > top + (x - 540) * .55f + 10)) continue;
            addConifer(TAL_WALD2, TAL_WALD2_SCHNEE, x, y, (float) (4.5 + Rt.next() * 3.5));
        }
        TAL_FLANKEN.moveTo(0, 168); TAL_FLANKEN.cubicTo(70, 186, 140, 222, 210, 256); TAL_FLANKEN.cubicTo(250, 276, 290, 300, 320, 330); TAL_FLANKEN.lineTo(330, UNTEN); TAL_FLANKEN.lineTo(0, UNTEN); TAL_FLANKEN.close();
        TAL_FLANKEN.moveTo(W, 170); TAL_FLANKEN.cubicTo(900, 186, 830, 214, 760, 244); TAL_FLANKEN.cubicTo(720, 262, 680, 282, 652, 300); TAL_FLANKEN.lineTo(640, UNTEN); TAL_FLANKEN.lineTo(W, UNTEN); TAL_FLANKEN.close();
        for (int i = 0; i < 46; i++) {
            boolean lft = i % 2 == 0;
            float u = (float) Rt.next();
            float x = lft ? 10 + u * 290 : 660 + u * 300;
            float y = (float) (lft ? 182 + u * 120 + Rt.next() * 26 : 270 - u * 90 + Rt.next() * 24);
            addConifer(TAL_WALD3, TAL_WALD3_SCHNEE, x, y, (float) (7 + Rt.next() * 6));
        }
        // Overlook ledge on the right
        TAL_KANTE_GRAS.moveTo(584, UNTEN); TAL_KANTE_GRAS.lineTo(592, 318);
        TAL_KANTE_GRAS.cubicTo(598, 302, 614, 294, 640, 291);
        TAL_KANTE_GRAS.cubicTo(700, 287, 760, 285, 820, 285);
        TAL_KANTE_GRAS.cubicTo(880, 285, 930, 286, W, 288);
        TAL_KANTE_GRAS.lineTo(W, UNTEN); TAL_KANTE_GRAS.close();
        TAL_FELS.moveTo(578, UNTEN); TAL_FELS.lineTo(586, 334); TAL_FELS.lineTo(590, 316);
        TAL_FELS.cubicTo(598, 302, 614, 294, 640, 291);
        TAL_FELS.lineTo(652, 297); TAL_FELS.lineTo(646, 308); TAL_FELS.lineTo(656, 318);
        TAL_FELS.lineTo(646, 332); TAL_FELS.lineTo(652, 346); TAL_FELS.lineTo(640, UNTEN); TAL_FELS.close();
        TAL_FELS_D.moveTo(590, 316); TAL_FELS_D.cubicTo(598, 302, 614, 294, 640, 291); TAL_FELS_D.lineTo(630, 300); TAL_FELS_D.lineTo(612, 304); TAL_FELS_D.lineTo(600, 318); TAL_FELS_D.close();
        TAL_FELS_D.moveTo(586, 334); TAL_FELS_D.lineTo(604, 326); TAL_FELS_D.lineTo(612, 340); TAL_FELS_D.lineTo(596, 350); TAL_FELS_D.close();
        TAL_FELS_D.moveTo(646, 308); TAL_FELS_D.lineTo(656, 318); TAL_FELS_D.lineTo(646, 332); TAL_FELS_D.lineTo(638, 320); TAL_FELS_D.close();
        TAL_FELS_LICHT.moveTo(600, 318); TAL_FELS_LICHT.lineTo(612, 304);
        TAL_FELS_LICHT.moveTo(596, 350); TAL_FELS_LICHT.lineTo(590, UNTEN);
        TAL_FELS_LICHT.moveTo(638, 320); TAL_FELS_LICHT.lineTo(630, 340);
        buildGrass(TAL_GRAS_H, TAL_GRAS_M, TAL_GRAS_V, Landschaften::talY, 636, W, 796, 848, 1.18f, 302);

        // ---- 4. GIPFEL ----
        Wolken.Zufall Rg = new Wolken.Zufall(401);
        buildRange(GIPF_R1, GIPF_R1_SCHATTEN, GIPF_R1_SCHNEE, 196, 46, 34, Rg);
        buildRange(GIPF_R2, GIPF_R2_SCHATTEN, GIPF_R2_SCHNEE, 224, 44, 42, Rg);
        buildRange(GIPF_R3, GIPF_R3_SCHATTEN, GIPF_R3_SCHNEE, 254, 34, 48, Rg);
        GIPF_WOLKEN1 = new float[12][4];
        for (int i = 0; i < 12; i++) GIPF_WOLKEN1[i] = new float[] {(float) (Rg.next() * 980), (float) (222 + Rg.next() * 10), (float) (60 + Rg.next() * 70), (float) (9 + Rg.next() * 7)};
        GIPF_WOLKEN2 = new float[9][4];
        for (int i = 0; i < 9; i++) GIPF_WOLKEN2[i] = new float[] {(float) (Rg.next() * 980), (float) (258 + Rg.next() * 10), (float) (60 + Rg.next() * 70), (float) (9 + Rg.next() * 7)};
        GIPF_FELS.moveTo(548, UNTEN); GIPF_FELS.lineTo(578, 326); GIPF_FELS.lineTo(606, 314); GIPF_FELS.lineTo(636, 300);
        GIPF_FELS.lineTo(668, 295); GIPF_FELS.lineTo(702, 290); GIPF_FELS.lineTo(744, 288); GIPF_FELS.lineTo(800, 285);
        GIPF_FELS.cubicTo(840, 284, 880, 284, 920, 287); GIPF_FELS.lineTo(W, 291); GIPF_FELS.lineTo(W, UNTEN); GIPF_FELS.close();
        GIPF_FELS_D.moveTo(578, 326); GIPF_FELS_D.lineTo(606, 314); GIPF_FELS_D.lineTo(612, 330); GIPF_FELS_D.lineTo(596, 346); GIPF_FELS_D.close();
        GIPF_FELS_D.moveTo(668, 295); GIPF_FELS_D.lineTo(702, 290); GIPF_FELS_D.lineTo(690, 306); GIPF_FELS_D.close();
        GIPF_FELS_D.moveTo(880, 296); GIPF_FELS_D.lineTo(920, 300); GIPF_FELS_D.lineTo(906, 318); GIPF_FELS_D.lineTo(872, 312); GIPF_FELS_D.close();
        GIPF_FELS_D.moveTo(620, 340); GIPF_FELS_D.lineTo(660, 322); GIPF_FELS_D.lineTo(672, 344); GIPF_FELS_D.lineTo(640, 356); GIPF_FELS_D.close();
        GIPF_FELS_LICHT.moveTo(606, 314); GIPF_FELS_LICHT.lineTo(636, 300); GIPF_FELS_LICHT.lineTo(668, 295);
        GIPF_FELS_LICHT.moveTo(744, 288); GIPF_FELS_LICHT.lineTo(800, 285);
        GIPF_FELS_SCHNEE.moveTo(640, 300); GIPF_FELS_SCHNEE.lineTo(668, 295); GIPF_FELS_SCHNEE.lineTo(662, 300); GIPF_FELS_SCHNEE.lineTo(646, 303); GIPF_FELS_SCHNEE.close();
        GIPF_FELS_SCHNEE.moveTo(760, 288); GIPF_FELS_SCHNEE.lineTo(800, 285); GIPF_FELS_SCHNEE.lineTo(792, 290); GIPF_FELS_SCHNEE.lineTo(768, 291); GIPF_FELS_SCHNEE.close();
        GIPF_FELS_SCHNEE.moveTo(900, 287); GIPF_FELS_SCHNEE.lineTo(930, 289); GIPF_FELS_SCHNEE.lineTo(918, 292); GIPF_FELS_SCHNEE.close();
        GIPF_KREUZ_DACH.moveTo(694, 236); GIPF_KREUZ_DACH.lineTo(698.8f, 231); GIPF_KREUZ_DACH.lineTo(703.6f, 236); GIPF_KREUZ_DACH.close();
        buildGrass(GIPF_GRAS_H, GIPF_GRAS_M, GIPF_GRAS_V, Landschaften::gipfY, 742, W, 796, 848, 1.0f, 402);

        // ---- 5. FELDER ----
        FELD_FAR.moveTo(0, 206); FELD_FAR.cubicTo(140, 196, 260, 204, 380, 210);
        FELD_FAR.cubicTo(500, 198, 640, 194, 760, 202); FELD_FAR.cubicTo(860, 196, 920, 200, W, 204);
        FELD_FAR.lineTo(W, 252); FELD_FAR.lineTo(0, 252); FELD_FAR.close();
        FELD_HUEGEL_A.moveTo(0, 232); FELD_HUEGEL_A.cubicTo(160, 220, 320, 226, 480, 230);
        FELD_HUEGEL_A.cubicTo(640, 222, 800, 218, W, 226); FELD_HUEGEL_A.lineTo(W, 300); FELD_HUEGEL_A.lineTo(0, 300); FELD_HUEGEL_A.close();
        FELD_HUEGEL_B.moveTo(0, 262); FELD_HUEGEL_B.cubicTo(200, 248, 400, 254, 600, 250);
        FELD_HUEGEL_B.cubicTo(760, 246, 880, 242, W, 246); FELD_HUEGEL_B.lineTo(W, 330); FELD_HUEGEL_B.lineTo(0, 330); FELD_HUEGEL_B.close();
        Wolken.Zufall Rf = new Wolken.Zufall(501);
        Object[] fa = buildPatchwork(-40, 1000, 218, 7, FELD_A_FURC, Rf);
        FELD_A_STREIFEN = (Path[]) fa[0]; FELD_A_IDX = (int[]) fa[1];
        Object[] fb = buildPatchwork(-40, 1000, 240, 6, FELD_B_FURC, Rf);
        FELD_B_STREIFEN = (Path[]) fb[0]; FELD_B_IDX = (int[]) fb[1];
        FELD_SCHEUNE_DACH.moveTo(288, 216); FELD_SCHEUNE_DACH.lineTo(303, 207); FELD_SCHEUNE_DACH.lineTo(318, 216); FELD_SCHEUNE_DACH.close();
        FELD_HAUS_DACH.moveTo(318.5f, 218); FELD_HAUS_DACH.lineTo(328, 211); FELD_HAUS_DACH.lineTo(337.5f, 218); FELD_HAUS_DACH.close();
        FELD_WEG_STRASSE.moveTo(380, 262); FELD_WEG_STRASSE.cubicTo(440, 256, 520, 252, 600, 247);
        FELD_WEG_STRASSE.lineTo(604, 249); FELD_WEG_STRASSE.cubicTo(524, 255, 446, 260, 386, 266); FELD_WEG_STRASSE.close();
        FELD_VORDER.moveTo(0, 300); FELD_VORDER.cubicTo(200, 292, 400, 296, 560, 294);
        FELD_VORDER.cubicTo(680, 292, 760, 288, 840, 286); FELD_VORDER.cubicTo(900, 285, 940, 287, W, 288);
        FELD_VORDER.lineTo(W, UNTEN); FELD_VORDER.lineTo(0, UNTEN); FELD_VORDER.close();
        FELD_PFAD.moveTo(700, UNTEN); FELD_PFAD.cubicTo(716, 330, 760, 304, 812, 292);
        FELD_PFAD.lineTo(846, 292); FELD_PFAD.cubicTo(800, 306, 770, 330, 760, UNTEN); FELD_PFAD.close();
        FELD_ZAUN_DRAHT.moveTo(560, 284); FELD_ZAUN_DRAHT.lineTo(720, 277.6f);
        FELD_ZAUN_DRAHT.moveTo(560, 290); FELD_ZAUN_DRAHT.lineTo(720, 283.6f);
        buildGrass(FELD_GRAS_H, FELD_GRAS_M, FELD_GRAS_V, Landschaften::feldY, 0, W, 796, 848, 1.18f, 502);
    }

    private static void buildRange(Path body, Path shade, Path snow, float yb, float amp, float step, Wolken.Zufall R) {
        java.util.List<float[]> pts = new java.util.ArrayList<>();
        body.moveTo(-20, UNTEN);
        for (float x = -20; x < 1010; x += (float) (step * (.6 + R.next() * .8))) {
            float y = (float) (yb - R.next() * amp - (R.next() < .22 ? amp * .7 : 0));
            pts.add(new float[] {x, y});
            body.lineTo(x, y);
        }
        body.lineTo(1010, UNTEN);
        body.close();
        for (int i = 1; i < pts.size() - 1; i++) {
            float[] kp = pts.get(i), ka = pts.get(i - 1), kb = pts.get(i + 1);
            if (kp[1] < ka[1] - 4 && kp[1] < kb[1] - 4) {
                shade.moveTo(kp[0], kp[1]); shade.lineTo(kb[0], kb[1]);
                shade.lineTo(kb[0], UNTEN); shade.lineTo(kp[0] + (kb[0] - kp[0]) * .35f, UNTEN); shade.close();
            }
            if (kp[1] < ka[1] - 6 && kp[1] < kb[1] - 6 && kp[1] < yb - amp * .55f) {
                float f = .32f;
                float pax = kp[0] + (ka[0] - kp[0]) * f, pay = kp[1] + (ka[1] - kp[1]) * f;
                float pbx = kp[0] + (kb[0] - kp[0]) * f, pby = kp[1] + (kb[1] - kp[1]) * f;
                snow.moveTo(kp[0], kp[1]); snow.lineTo(pbx, pby);
                snow.lineTo((kp[0] + pbx) / 2f, (kp[1] + pby) / 2f + 3);
                snow.lineTo(kp[0], kp[1] + (pay - kp[1]) * .7f);
                snow.lineTo((kp[0] + pax) / 2f, (kp[1] + pay) / 2f + 2);
                snow.lineTo(pax, pay); snow.close();
            }
        }
    }

    private static Object[] buildPatchwork(float x0, float x1, float yTop, int n, Path furrows, Wolken.Zufall R) {
        java.util.List<Path> ps = new java.util.ArrayList<>();
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        float x = x0;
        for (int i = 0; x < x1; i++) {
            float w = (float) ((x1 - x0) / n * (.7 + R.next() * .6));
            float sk2 = (float) ((R.next() - .5) * 60);
            Path strip = new Path();
            strip.moveTo(x, yTop); strip.lineTo(x + w, yTop); strip.lineTo(x + w + sk2, UNTEN); strip.lineTo(x + sk2, UNTEN); strip.close();
            ps.add(strip);
            idx.add((i + n) % 4);
            if (i % 2 == 0) {
                for (int k = 1; k < 5; k++) {
                    furrows.moveTo(x + w * k / 5f, yTop);
                    furrows.lineTo(x + w * k / 5f + sk2, UNTEN);
                }
            }
            x += w;
        }
        int[] outIdx = new int[idx.size()];
        for (int i = 0; i < idx.size(); i++) outIdx[i] = idx.get(i);
        return new Object[] {ps.toArray(new Path[0]), outIdx};
    }

    // ==== instance paints & light-dependent shaders (rebuilt only on light/weather/ort change) =======================

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path tmp = new Path();

    private Shader seeLakeShader, hillShader, hzGlowShader, talHazeShader, talMistShader, gipfMistPuff, gipfMistBand1, gipfMistBand2;
    private int cFar, cMid, cPine, cHillA, cHillB, cGrass, cGrassH, cGrassV, cStone, cSnowCap, cAst, cPfuetze;
    private int cTalFar, cTalFarS, cTalCap, cTalS1, cTalS1Rim, cTalS2, cTalS2Rim, cTalWc2, cTalNl, cTalFels, cTalFelsD, cTalRoad, cTalUfer;
    private int cGipfR1, cGipfR1S, cGipfR2, cGipfR2S, cGipfR3, cGipfR3S, cGipfFels, cGipfFelsD, cGipfHolz, cGipfBuch, cGipfWolke, cGipfWolkeB;
    private int cHuette, cHuetteDach, cKamin, cWand, cDach, cZelt, cZeltSch, cZeltKlappe, cLeine, cSteinA, cSteinB, cHolzFeuer, cReh, cRehD;
    private int cWindrad, cScheune, cHaus, cScheuneDach, cSilo, cHeu, cZaun, cFeldStrasse, cFeldPfad;
    private final int[] cLaub = new int[4];
    private final int[] cTalFeld = new int[4];
    private final int[] cFeldBunt = new int[4];
    private boolean hatLaub;

    Landschaften() {
        linie.setStyle(Paint.Style.STROKE);
        linie.setStrokeCap(Paint.Cap.ROUND);
    }

    private static final int[][] LAUB_PAL = {
        {0xFF86C45A, 0xFF9CCF6A, 0xFFE9B8CC, 0xFF7AB84E}, // spring
        {0xFF3F7A3A, 0xFF4F8A40, 0xFF356A34, 0xFF47823B}, // summer
        {0xFFC9762F, 0xFFD9A03A, 0xFFA8492A, 0xFF8A8A3A}, // autumn
        null                                               // winter (bare)
    };
    private static final int[][] TAL_PAL = {
        {0xFFA8D46A, 0xFFE8D84A, 0xFF7FB04E, 0xFFB09068},
        {0xFFB9C95A, 0xFFD9B85A, 0xFF7FAE4A, 0xFF9AB85A},
        {0xFFB08A52, 0xFFC9B06A, 0xFF8A9A4A, 0xFF9A6A42},
        {0xFF8F8A66, 0xFFA09A78, 0xFF7A7A58, 0xFFA59A82}
    };
    private static final int[][] FELD_PAL = {
        {0xFF9CC062, 0xFFE8D84A, 0xFF7AA24A, 0xFFA98C62},
        {0xFFD9B85A, 0xFFA8C05A, 0xFFC9A24A, 0xFF7FA24A},
        {0xFF8A6A4A, 0xFFC9B06A, 0xFF7F9A4A, 0xFFA07A52},
        {0xFF8A7A62, 0xFF9AA07A, 0xFF7A6A52, 0xFFA59A82}
    };
    private static final int[] ZELT_FARBEN = {0xFFD9783A, 0xFF5F7A4A, 0xFFC94A3C, 0xFF3F6F9A};

    void farben(String ort, WetterLage lage, WetterLage.Licht L, WetterLage.Land P, int monat, boolean mondDa,
                double temp, float hz, float landY, int dayN) {
        float tag = L.tag, nacht = 1 - tag;
        int sai = saison(monat);
        int sk2 = WetterLage.tont(L.himmel[2], hz, L);

        cFar = WetterLage.tont(P.far, landY + 200, L);
        cMid = WetterLage.tont(P.mid, landY + 225, L);
        cPine = WetterLage.tont(P.pine, landY + 240, L);
        cHillA = WetterLage.tont(P.hillA, landY + 290, L);
        cHillB = WetterLage.tont(P.hillB, landY + 340, L);
        cGrass = WetterLage.tont(P.grass, landY + 295, L);
        cGrassH = WetterLage.shade(cGrass, .68f);
        cGrassV = WetterLage.shade(WetterLage.mix(cGrass, 0xFFE8E2B0, .22f * tag), 1.12f);
        cStone = WetterLage.tont(P.stone, landY + 310, L);
        cSnowCap = WetterLage.tont(WetterLage.mix(0xFF9AA6B8, 0xFFF4F6F8, tag), landY + 180, L);
        cAst = WetterLage.tont(WetterLage.mix(0xFF1D1714, 0xFF5A4334, tag), landY + 240, L);
        cPfuetze = WetterLage.alpha(WetterLage.tont(P.lakeB, landY + 320, L), .72f);

        int lakeA = WetterLage.mix(WetterLage.tont(P.lakeA, landY + 240, L), sk2, .18f);
        int lakeB = WetterLage.tont(P.lakeB, landY + 305, L);
        seeLakeShader = new LinearGradient(0, 236, 0, 316, lakeA, lakeB, Shader.TileMode.CLAMP);
        hillShader = new LinearGradient(0, 244, 0, UNTEN, cHillA, cHillB, Shader.TileMode.CLAMP);
        int glowCol = WetterLage.alpha(WetterLage.mix(0xFF8AA4C8, 0xFFFFFFFF, tag), .24f * tag + .07f);
        hzGlowShader = new LinearGradient(0, 178, 0, 236, new int[] {0x00FFFFFF, glowCol, 0x00FFFFFF}, new float[] {0f, .46f, 1f}, Shader.TileMode.CLAMP);
        talHazeShader = new LinearGradient(0, 196, 0, 274, new int[] {0x00FFFFFF, glowCol, 0x00FFFFFF}, new float[] {0f, .6f, 1f}, Shader.TileMode.CLAMP);

        int[] lp = LAUB_PAL[sai];
        hatLaub = lp != null;
        if (hatLaub) {
            float mP = tag < .5f ? .78f : ("tag".equals(lage.landTag) ? .20f : .45f);
            for (int i = 0; i < 4; i++) cLaub[i] = WetterLage.tont(WetterLage.mix(lp[i], P.pine, mP), landY + 235, L);
        }

        // See
        cHuette = WetterLage.tont(WetterLage.mix(0xFF2C2420, 0xFF7A5A44, tag), landY + 230, L);
        cHuetteDach = lage.schnee ? WetterLage.tont(0xFFEEF3F7, landY + 220, L) : WetterLage.tont(WetterLage.mix(0xFF3A2421, 0xFF8C3F33, tag), landY + 220, L);
        cKamin = WetterLage.tont(WetterLage.mix(0xFF231D1A, 0xFF5D4636, tag), landY + 216, L);

        // Lichtung
        int zBasis = ZELT_FARBEN[(dayN % 4 + 4) % 4];
        cZelt = WetterLage.tont(WetterLage.mix(zBasis, P.hillB, tag < .5f ? .55f : ("tag".equals(lage.landTag) ? .05f : .30f)), landY + 265, L);
        cZeltSch = WetterLage.shade(cZelt, .76f);
        cZeltKlappe = WetterLage.shade(cZelt, 1.18f);
        cLeine = WetterLage.tont(WetterLage.mix(0xFF2A2420, 0xFF6A5A4A, tag), landY + 265, L);
        cSteinA = WetterLage.tont(WetterLage.mix(0xFF3E4048, 0xFF8D8A84, tag), landY + 300, L);
        cSteinB = tag < .5f ? 0xFF8A6448 : WetterLage.tont(0xFFAAA7A1, landY + 300, L);
        cHolzFeuer = WetterLage.tont(WetterLage.mix(0xFF2A1C14, 0xFF5A3B26, tag), landY + 295, L);
        cReh = WetterLage.tont(WetterLage.mix(P.mid, 0xFF8A5634, .6f), landY + 250, L);
        cRehD = WetterLage.shade(cReh, .75f);

        // Tal
        float dunst = lage.nebel ? .7f : (lage.tropfen > 0 || lage.blitz ? .5f : ("cloudy".equals(lage.cond) ? .42f : .30f));
        cTalFar = WetterLage.mix(cFar, sk2, dunst);
        cTalFarS = WetterLage.mix(WetterLage.shade(cTalFar, .84f), sk2, dunst * .3f);
        cTalCap = WetterLage.mix(cSnowCap, cTalFar, dunst * .55f);
        cTalS1 = WetterLage.mix(cFar, sk2, dunst * .7f);
        cTalS1Rim = WetterLage.mix(cTalS1, sk2, .45f);
        cTalS2 = WetterLage.mix(cFar, sk2, dunst * .25f);
        cTalS2Rim = WetterLage.mix(cTalS2, sk2, .35f);
        cTalWc2 = WetterLage.mix(cPine, cTalS2, .35f);
        cTalNl = WetterLage.mix(cMid, cPine, .35f);
        cTalFels = WetterLage.shade(WetterLage.mix(cStone, cFar, .5f), .78f);
        cTalFelsD = WetterLage.shade(cTalFels, .74f);
        cTalRoad = lage.schnee ? WetterLage.mix(cStone, 0xFFF4F6F8, .5f) : WetterLage.mix(cStone, cHillA, .3f);
        cTalUfer = lage.schnee ? WetterLage.tont(0xFFE4EAF0, landY + 300, L) : WetterLage.mix(cStone, cHillA, .45f);
        cWand = WetterLage.tont(WetterLage.mix(0xFF3A3A44, 0xFFE8E0D0, tag), landY + 285, L);
        cDach = lage.schnee ? WetterLage.tont(0xFFEEF3F7, landY + 280, L) : WetterLage.tont(WetterLage.mix(0xFF2A2024, 0xFF8C3F33, tag), landY + 280, L);
        for (int i = 0; i < 4; i++) cTalFeld[i] = TAL_PAL[sai][i];
        int mistRgb = WetterLage.mix(0xFFBEC8DC, 0xFFFFFFFF, tag);
        float mo = (.15f + .21f * tag) * (lage.nebel ? 1f : (lage.tropfen > 0 ? .65f : 0f));
        talMistShader = mo > .01f ? new RadialGradient(0, 0, 1,
            new int[] {WetterLage.alpha(mistRgb, mo), WetterLage.alpha(mistRgb, mo * .72f), 0x00FFFFFF},
            new float[] {0f, .5f, 1f}, Shader.TileMode.CLAMP) : null;

        // Gipfel
        cGipfR1 = WetterLage.mix(cFar, sk2, .45f);
        cGipfR1S = WetterLage.shade(cGipfR1, .86f);
        cGipfR2 = cFar;
        cGipfR2S = WetterLage.shade(cGipfR2, .84f);
        cGipfR3 = cMid;
        cGipfR3S = WetterLage.shade(cGipfR3, .82f);
        cGipfFels = WetterLage.mix(cStone, cFar, .45f);
        cGipfFelsD = WetterLage.shade(cGipfFels, .76f);
        cGipfHolz = WetterLage.tont(WetterLage.mix(0xFF2A1F18, 0xFF6B4A2F, tag), landY + 260, L);
        cGipfBuch = WetterLage.tont(WetterLage.mix(0xFF30343A, 0xFF7A8088, tag), landY + 275, L);
        cGipfWolke = WetterLage.alpha(WetterLage.mix(0xFFAAB4CD, 0xFFFFFFFF, tag), .26f + .32f * tag);
        cGipfWolkeB = WetterLage.alpha(WetterLage.mix(0xFFAAB4CD, 0xFFFFFFFF, tag), .18f + .24f * tag);
        int gipfRgb = WetterLage.mix(0xFFAAB4CD, 0xFFFFFFFF, tag);
        gipfMistPuff = new RadialGradient(0, 0, 1,
            new int[] {WetterLage.alpha(gipfRgb, .28f + .36f * tag), WetterLage.alpha(gipfRgb, .14f + .20f * tag), 0x00FFFFFF},
            new float[] {0f, .52f, 1f}, Shader.TileMode.CLAMP);
        gipfMistBand1 = new LinearGradient(0, 214, 0, 260,
            new int[] {0x00FFFFFF, cGipfWolkeB, cGipfWolkeB, 0x00FFFFFF}, new float[] {0f, .35f, .7f, 1f}, Shader.TileMode.CLAMP);
        gipfMistBand2 = new LinearGradient(0, 250, 0, 302,
            new int[] {0x00FFFFFF, cGipfWolkeB, cGipfWolkeB, 0x00FFFFFF}, new float[] {0f, .35f, .7f, 1f}, Shader.TileMode.CLAMP);

        // Felder
        cWindrad = WetterLage.tont(WetterLage.mix(0xFFEEF1F4, P.far, tag < .5f ? .6f : .25f), landY + 185, L);
        for (int i = 0; i < 4; i++) {
            int b = FELD_PAL[sai][i];
            int col = lage.schnee ? WetterLage.mix(b, 0xFFEEF2F6, .85f) : WetterLage.mix(b, P.hillB, tag < .5f ? .70f : ("tag".equals(lage.landTag) ? .08f : .38f));
            cFeldBunt[i] = WetterLage.tont(col, landY + 255, L);
        }
        cScheune = WetterLage.tont(WetterLage.mix(0xFF3A2224, 0xFF9C3B2E, tag), landY + 220, L);
        cHaus = WetterLage.tont(WetterLage.mix(0xFF3C3E48, 0xFFECE6DA, tag), landY + 220, L);
        cScheuneDach = lage.schnee ? WetterLage.tont(0xFFEEF3F7, landY + 214, L) : WetterLage.tont(WetterLage.mix(0xFF25201E, 0xFF5A4A42, tag), landY + 214, L);
        cSilo = WetterLage.tont(WetterLage.mix(0xFF44484F, 0xFFB8BCC2, tag), landY + 218, L);
        cHeu = WetterLage.tont(WetterLage.mix(0xFF4A4234, 0xFFD9B86A, tag), landY + 270, L);
        cZaun = WetterLage.tont(WetterLage.mix(0xFF2A2018, 0xFF7A5A3A, tag), landY + 286, L);
        cFeldStrasse = WetterLage.alpha(WetterLage.mix(cStone, cHillB, .3f), .7f);
        cFeldPfad = WetterLage.alpha(WetterLage.mix(cHillB, cStone, .45f), .55f);
    }

    /** Draws the active non-sea landscape behind the figure (in landY-shifted 963 x 362 coords). */
    void vorFigur(Canvas c, String ort, WetterLage lage, WetterLage.Licht L, float t, boolean mondDa,
                  float sx, float mx, double sonneAlt, Himmel.Mond mond, double temp, int monat,
                  float[][] ringe, float[][] glanz) {
        p.setStyle(Paint.Style.FILL);
        switch (ort) {
            case "see": drawSee(c, lage, L, t, mondDa, sx, mx, sonneAlt, mond, temp, monat, ringe, glanz); break;
            case "lichtung": drawLichtung(c, lage, L, t, temp, monat); break;
            case "tal": drawTal(c, lage, L, t, temp, monat); break;
            case "gipfel": drawGipfel(c, lage, L, t, monat); break;
            default: drawFelder(c, lage, L, t, temp, monat); break;
        }
    }

    /** Draws front grass blades in front of the figure's boots. */
    void nachFigur(Canvas c, String ort) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(cGrassV);
        switch (ort) {
            case "see": c.drawPath(SEE_GRAS_V, p); break;
            case "lichtung": c.drawPath(LICHT_GRAS_V, p); break;
            case "tal": c.drawPath(TAL_GRAS_V, p); break;
            case "gipfel": c.drawPath(GIPF_GRAS_V, p); break;
            default: c.drawPath(FELD_GRAS_V, p); break;
        }
    }

    /** Fair-weather daytime birds gliding gently across the sky (drawn in screen/scene coords). */
    void voegel(Canvas c, WetterLage lage, WetterLage.Licht L, float t, float landY) {
        if (L.tag < .55f || (!lage.sonneMoeglich && !"partlycloudy".equals(lage.cond)) || lage.tropfen > 0 || lage.nebel) return;
        float alpha = Math.min(1, (L.tag - .55f) / .35f) * .62f;
        linie.setStrokeWidth(1.5f);
        linie.setColor(WetterLage.alpha(0xFF1E2837, alpha));
        for (float[] v : VOEGEL) {
            float ph = ((t + v[2]) % v[3]) / v[3];
            float x = -30 + ph * (W + 60);
            float y = v[0] + landY * .45f + (float) Math.sin(t * .7 + v[2]) * 6f;
            float flap = (float) Math.sin(t * 7.2 + v[2]) * 3.6f * v[1];
            float s = v[1];
            tmp.reset();
            tmp.moveTo(x - 8 * s, y + flap * .5f);
            tmp.quadTo(x - 4 * s, y - 4 * s - flap, x, y);
            tmp.quadTo(x + 4 * s, y - 4 * s - flap, x + 8 * s, y + flap * .5f);
            c.drawPath(tmp, linie);
        }
    }

    // ---- 1. SEE ------------------------------------------------------------------------------------------------------

    private void drawSee(Canvas c, WetterLage lage, WetterLage.Licht L, float t, boolean mondDa,
                         float sx, float mx, double sonneAlt, Himmel.Mond mond, double temp, int monat,
                         float[][] ringe, float[][] glanz) {
        float nacht = 1 - L.tag;
        boolean frozen = temp <= -1;
        p.setColor(cFar); c.drawPath(SEE_FAR, p);
        if (lage.schnee) { p.setColor(WetterLage.alpha(0xFFFFFFFF, .55f)); c.drawPath(SEE_FAR_SCHNEE, p); }
        fillShader(hzGlowShader); c.drawRect(0, 178, W, 236, p); p.setShader(null);
        p.setColor(cMid); c.drawPath(SEE_MID, p);
        p.setColor(cPine); c.drawPath(SEE_TANNEN, p);
        if (lage.schnee) { p.setColor(WetterLage.alpha(0xFFFFFFFF, .85f)); c.drawPath(SEE_TANNEN_SCHNEE, p); }
        // Lakeside cabin + chimney + warm window at night + rising smoke when cold
        p.setColor(cHuette); c.drawPath(SEE_HUETTE, p);
        p.setColor(cHuetteDach); c.drawPath(SEE_DACH, p);
        p.setColor(cKamin); c.drawRect(296, 214, 299.5f, 222, p);
        drawFenster(c, 282.5f, 228.5f, 5, 5, nacht);
        if (temp < 10 && !lage.wind) drawRauch(c, 298, 212, 4, 1f, t);
        // Lake
        fillShader(seeLakeShader); c.drawRect(0, 236, W, 316, p); p.setShader(null);
        if (frozen) {
            linie.setStrokeWidth(1f); linie.setColor(WetterLage.alpha(0xFFFFFFFF, .48f));
            c.drawPath(SEE_EIS, linie);
        } else {
            // Subtle warm window reflection in the lake at night
            if (nacht > .25f) {
                for (int i = 0; i < 6; i++) {
                    float q = i / 5f, gy = 240 + q * 24, gw = 4 + q * 8;
                    float fl = .65f + .35f * (float) (.5 + .5 * Math.sin(t * (2.1 + i * .4) + i));
                    p.setColor(WetterLage.alpha(0xFFFFD27A, .36f * nacht * fl * (1 - q * .5f)));
                    r.set(285 - gw / 2, gy, 285 + gw / 2, gy + 1.2f);
                    c.drawRoundRect(r, 1, 1, p);
                }
            }
            // Sun / moon glitter path on the lake
            float sonneG = lage.sonneMoeglich ? WetterLage.glatt(-1, 2, sonneAlt) * (1 - lage.cover * .9f) : 0;
            float mondG = mondDa && mond != null ? (float) mond.f * nacht * (1 - lage.cover * .75f) : 0;
            if (sonneG > .02f && sx > -40 && sx < W + 40) lakeGlanz(c, t, sx, sonneAlt < 8 ? 0xFFFFE2B0 : 0xFFFFF6E6, .5f * sonneG);
            if (mondG > .02f && mx > -40 && mx < W + 40) lakeGlanz(c, t, mx, 0xFFF3EFD9, .48f * mondG);
            for (int i = 0; i < Math.min(12, glanz.length); i++) {
                float[] g = glanz[i];
                float gy = 242 + (g[1] - WetterView.HZ) * .68f;
                if (gy > 296) continue;
                float off = lage.wind ? (-10 + 24 * (float) (.5 - .5 * Math.cos(((t + g[4]) % 3.2f) / 3.2f * 2 * Math.PI))) : 0;
                p.setColor(WetterLage.alpha(0xFFFFFFFF, .14f));
                c.drawRect(g[0] + off, gy, g[0] + off + g[2] * .8f, gy + g[3], p);
            }
            linie.setStrokeWidth(1f);
            for (float[] g : ringe) {
                float gy = 242 + (g[1] - WetterView.HZ) * .65f;
                if (gy > 295) continue;
                float ph = ((t + g[2]) % 1.4f) / 1.4f, s = .2f + 1.3f * ph;
                linie.setColor(WetterLage.alpha(0xFFFFFFFF, .42f * (1 - ph)));
                r.set(g[0] - 9 * s, gy - 2 * s, g[0] + 9 * s, gy + 2 * s);
                c.drawOval(r, linie);
            }
        }
        // Foreground shore, stones, puddles, flowers, back/mid grass
        fillShader(hillShader); c.drawPath(SEE_UFER, p); p.setShader(null);
        p.setColor(WetterLage.alpha(cHillB, .42f)); c.drawPath(SEE_UFER2, p);
        drawVordergrundExtras(c, lage, L, temp, monat, Landschaften::seeUferY, 0, W, SEE_STEINE, false);
        p.setColor(cGrassH); c.drawPath(SEE_GRAS_H, p);
        p.setColor(cGrass); c.drawPath(SEE_GRAS_M, p);
    }

    private void lakeGlanz(Canvas c, float t, float x, int col, float a) {
        for (int i = 0; i < 9; i++) {
            float q = i / 8f, gy = 240 + 52 * (float) Math.pow(q, 1.4);
            float gw = 6 + q * 28, fl = .5f + .5f * (float) (.5 + .5 * Math.sin(t * (1.8 + i * .25) + i * 2));
            p.setColor(WetterLage.alpha(col, a * fl * (1 - q * .35f)));
            r.set(x - gw / 2, gy, x + gw / 2, gy + 1.2f + q);
            c.drawRoundRect(r, 1, 1, p);
        }
    }

    // ---- 2. LICHTUNG -------------------------------------------------------------------------------------------------

    private void drawLichtung(Canvas c, WetterLage lage, WetterLage.Licht L, float t, double temp, int monat) {
        float tag = L.tag, nacht = 1 - tag;
        int sai = saison(monat);
        p.setColor(cFar); c.drawPath(LICHT_FAR, p);
        fillShader(hzGlowShader); c.drawRect(0, 178, W, 236, p); p.setShader(null);
        p.setColor(cMid); c.drawPath(LICHT_HORIZ, p);
        // Forest edges left & right (conifers + seasonal deciduous trees)
        p.setColor(cPine); c.drawPath(LICHT_WALD_BASIS, p); c.drawPath(LICHT_TANNEN, p);
        if (lage.schnee) { p.setColor(WetterLage.alpha(0xFFFFFFFF, .85f)); c.drawPath(LICHT_TANNEN_SCHNEE, p); }
        for (float[] lt : LICHT_LAUB) {
            if (!hatLaub) {
                drawKahl(c, lt[0], lt[1] + lt[2], lt[2] * 2.4f, lage.schnee);
            } else {
                p.setColor(cLaub[(int) lt[3]]); c.drawCircle(lt[0], lt[1], lt[2], p);
                p.setColor(WetterLage.alpha(0xFFFFFFFF, .06f)); c.drawCircle(lt[0] - lt[2] * .3f, lt[1] - lt[2] * .3f, lt[2] * .5f, p);
                if (lage.schnee) {
                    p.setColor(WetterLage.alpha(0xFFFFFFFF, .8f));
                    r.set(lt[0] - lt[2] * .7f, lt[1] - lt[2] * .95f, lt[0] + lt[2] * .7f, lt[1] - lt[2] * .45f);
                    c.drawOval(r, p);
                }
            }
        }
        // Meadow & winding trail
        fillShader(hillShader); c.drawPath(LICHT_WIESE, p); p.setShader(null);
        p.setColor(cFeldPfad); c.drawPath(LICHT_WEG, p);
        // Lone tree at (410, 256)
        p.setColor(0x26000000); r.set(388, 253, 432, 259); c.drawOval(r, p);
        if (!hatLaub) {
            drawKahl(c, 410, 256, 62, lage.schnee);
        } else {
            int cc = cLaub[sai == 2 ? 0 : 1];
            p.setColor(cAst); c.drawRect(407.5f, 234, 412.5f, 256, p);
            p.setColor(cc); c.drawCircle(410, 218, 20, p); c.drawCircle(396, 228, 14, p); c.drawCircle(425, 227, 15, p);
            p.setColor(WetterLage.alpha(0xFFFFFFFF, .08f)); c.drawCircle(404, 212, 9, p);
            if (lage.schnee) {
                p.setColor(WetterLage.alpha(0xFFFFFFFF, .85f));
                r.set(395, 198, 425, 206); c.drawOval(r, p);
                r.set(385, 214, 403, 220); c.drawOval(r, p);
                r.set(417, 212, 437, 218); c.drawOval(r, p);
            }
        }
        // Two distant deer by the left forest edge in fair daytime weather
        if (tag > .45f && lage.tropfen == 0 && !lage.nebel && !lage.blitz) {
            drawReh(c, 300, 252, 1, false);
            drawReh(c, 324, 254, -1, true);
        }
        // Ridge tent at (642, 278)
        p.setColor(0x2E000000); r.set(604, 272.5f, 712, 281.5f); c.drawOval(r, p);
        p.setColor(cZeltSch); c.drawPath(LICHT_ZELT_SEITE, p);
        p.setColor(cZelt); c.drawPath(LICHT_ZELT_FRONT, p);
        p.setColor(0x1F000000); c.drawPath(LICHT_ZELT_SCHATTEN, p);
        p.setColor(nacht > .45f ? WetterLage.alpha(0xFFFFBE64, .82f) : 0x80000000); c.drawPath(LICHT_ZELT_TUER, p);
        p.setColor(cZeltKlappe); c.drawPath(LICHT_ZELT_KLAPPE, p);
        linie.setStrokeWidth(1f); linie.setColor(cLeine); c.drawPath(LICHT_ZELT_LEINEN, linie);
        if (lage.schnee) { p.setColor(WetterLage.alpha(0xFFFFFFFF, .78f)); c.drawPath(LICHT_ZELT_SCHNEE, p); }
        if (nacht > .25f) drawGlow(c, 642, 264, 36, 36, WIN_GLOW, .68f * nacht);

        // Campfire at (716, 300)
        float fx = 716, fy = 300;
        boolean feuer = lage.tropfen == 0 && !lage.blitz && (nacht > .4f || temp < 14);
        if (feuer) {
            float fl = .88f + .12f * (float) Math.sin(t * 9.5 + .7 * Math.sin(t * 17));
            drawGlow(c, fx, fy, (42 + 44 * nacht) * fl, (10 + 11 * nacht) * fl, FIRE_GLOW, .75f);
            drawGlow(c, fx, fy - 16, (34 + 42 * nacht) * fl, (34 + 42 * nacht) * fl, FIRE_GLOW, .72f);
        }
        drawSteinring(c, fx, fy, true);
        p.setColor(WetterLage.mix(0xFF1E1A18, 0xFF4A4440, tag)); r.set(fx - 15, fy - 4, fx + 15, fy + 4); c.drawOval(r, p);
        c.save(); c.translate(fx, fy);
        linie.setStrokeWidth(3f); linie.setColor(cHolzFeuer); c.drawPath(LICHT_HOLZ, linie);
        if (feuer) {
            float s1 = .92f + .12f * (float) Math.sin(t * 10.5), sk1 = (float) Math.sin(t * 7.3) * .08f;
            float s2 = .92f + .14f * (float) Math.sin(t * 13.1 + 1.4), sk2 = (float) Math.sin(t * 9.1 + 2) * .09f;
            c.save(); c.skew(sk1, 0); c.scale(1, s1); p.setColor(0xFFFF8A2A); c.drawPath(LICHT_FLAMME1, p); c.restore();
            c.save(); c.skew(sk2, 0); c.scale(1, s2); p.setColor(0xFFFFB347); c.drawPath(LICHT_FLAMME2, p);
            p.setColor(0xFFFFE9A0); c.drawPath(LICHT_FLAMME3, p); c.restore();
            p.setColor(0xD9FF7828); r.set(-12, -3, 12, 3); c.drawOval(r, p);
            if (nacht > .3f) {
                float[] spX = {-3, 4, -1, 6};
                for (int i = 0; i < 4; i++) {
                    float ph = ((t + i * .45f) % 1.8f) / 1.8f;
                    p.setColor(WetterLage.alpha(0xFFFFD27A, (1 - ph) * nacht));
                    c.drawCircle(spX[i] + (float) Math.sin(ph * 6 + i) * 4, -22 - ph * 34, 1.2f * (1 - ph * .4f), p);
                }
            }
        }
        c.restore();
        drawSteinring(c, fx, fy, false);
        if (!feuer && lage.tropfen == 0) drawRauch(c, fx, fy - 8, 3, .9f, t);

        // Fireflies on summer nights
        if (nacht > .45f && sai == 1 && lage.tropfen == 0 && !lage.wind) {
            for (float[] g : LICHT_GLUEH) {
                float a = (float) (.5 + .5 * Math.sin(t * 3.1 + g[2])) * nacht;
                p.setColor(WetterLage.alpha(0xFFE8F59A, a));
                c.drawCircle(g[0] + (float) Math.sin(t * .9 + g[2]) * 5, g[1] + (float) Math.cos(t * 1.1 + g[2]) * 3, 1.5f, p);
            }
        }
        // Meadow grass, knoll under the figure, extras & back/mid grass
        p.setColor(WetterLage.alpha(cGrass, .8f)); c.drawPath(LICHT_WIESENGRAS, p);
        p.setColor(WetterLage.alpha(cHillB, .38f)); c.drawPath(LICHT_KUPPE, p);
        drawVordergrundExtras(c, lage, L, temp, monat, Landschaften::lichtY, 560, W, LICHT_STEINE, false);
        p.setColor(cGrassH); c.drawPath(LICHT_GRAS_H, p);
        p.setColor(cGrass); c.drawPath(LICHT_GRAS_M, p);
    }

    private void drawReh(Canvas c, float x, float y, float flip, boolean alert) {
        c.save(); c.translate(x, y); c.scale(flip, 1);
        linie.setStrokeWidth(.8f); linie.setColor(cRehD);
        c.drawLine(-2.9f, -5, -3.1f, 0, linie); c.drawLine(3, -5.2f, 3.8f, -2.6f, linie); c.drawLine(3.8f, -2.6f, 3.4f, 0, linie);
        p.setColor(cReh);
        r.set(-6, -8.5f, 6.8f, -4.2f); c.drawRoundRect(r, 2.2f, 2.2f, p);
        linie.setColor(cReh);
        c.drawLine(-4.4f, -5, -4.6f, 0, linie); c.drawLine(5, -5.2f, 5.9f, -2.6f, linie); c.drawLine(5.9f, -2.6f, 5.5f, 0, linie);
        tmp.reset();
        if (alert) {
            tmp.moveTo(-5.4f, -7); tmp.lineTo(-7.4f, -11.6f); tmp.lineTo(-5.9f, -12.3f); tmp.lineTo(-3.6f, -7.6f); tmp.close();
            tmp.moveTo(-6.2f, -13.1f); tmp.lineTo(-9.9f, -12.1f); tmp.lineTo(-9.8f, -11.4f); tmp.lineTo(-6.6f, -11.3f); tmp.close();
        } else {
            tmp.moveTo(-5.3f, -7.1f); tmp.lineTo(-7.8f, -2.6f); tmp.lineTo(-6.4f, -2); tmp.lineTo(-3.8f, -6.2f); tmp.close();
            tmp.moveTo(-8.4f, -3.2f); tmp.lineTo(-9.4f, -.2f); tmp.lineTo(-8.5f, .1f); tmp.lineTo(-6.6f, -2.4f); tmp.close();
        }
        c.drawPath(tmp, p);
        p.setColor(0x73FFFAF0); r.set(5.4f, -7.3f, 6.8f, -5.1f); c.drawOval(r, p);
        c.restore();
    }

    private void drawSteinring(Canvas c, float fx, float fy, boolean back) {
        for (int i = 0; i < 10; i++) {
            double a = (i / 10.0) * 2 * Math.PI + .3;
            if ((Math.sin(a) < 0) != back) continue;
            float sx = fx + (float) Math.cos(a) * 19, sy = fy + (float) Math.sin(a) * 5.5f;
            p.setColor(cSteinA); r.set(sx - 4.6f, sy - 3, sx + 4.6f, sy + 3); c.drawOval(r, p);
            p.setColor(cSteinB); r.set(sx - 3.4f, sy - 2.5f, sx + 2.2f, sy + .1f); c.drawOval(r, p);
        }
    }

    // ---- 3. TAL ------------------------------------------------------------------------------------------------------

    private void drawTal(Canvas c, WetterLage lage, WetterLage.Licht L, float t, double temp, int monat) {
        float tag = L.tag, nacht = 1 - tag;
        int sai = saison(monat);
        boolean frozen = temp <= -1;
        // Far snowy alpine range + haze
        p.setColor(cTalFar); c.drawPath(TAL_KAMM, p);
        p.setColor(cTalFarS); c.drawPath(TAL_KAMM_SCHATTEN, p);
        p.setColor(cTalCap);
        c.drawPath(lage.schnee || sai != 1 ? TAL_KAMM_SCHNEE_ALLE : TAL_KAMM_SCHNEE_HOCH, p);
        fillShader(talHazeShader); c.drawRect(130, 196, 830, 274, p); p.setShader(null);
        // Perspective valley floor
        fillShader(hillShader); c.drawPath(TAL_BODEN, p); p.setShader(null);
        float mixK = tag < .5f ? .16f : ("tag".equals(lage.landTag) ? .58f : .36f);
        for (int i = 0; i < TAL_FELD_QUADS.length; i++) {
            float[] m = TAL_FELD_META[i];
            float v = m[0];
            int base = WetterLage.mix(cHillA, cHillB, v * .8f);
            int col = lage.schnee ? WetterLage.shade(base, .96f + (m[3] - .93f) * .5f)
                : (m[2] > .5f ? WetterLage.mix(base, cTalFeld[(int) m[1]], mixK) : WetterLage.shade(base, m[3]));
            col = WetterLage.mix(col, cTalFar, (1 - v) * .45f);
            p.setColor(col);
            float[][] q = TAL_FELD_QUADS[i];
            tmp.reset(); tmp.moveTo(q[0][0], q[0][1]); tmp.lineTo(q[1][0], q[1][1]); tmp.lineTo(q[2][0], q[2][1]); tmp.lineTo(q[3][0], q[3][1]); tmp.close();
            c.drawPath(tmp, p);
        }
        int hc = WetterLage.alpha(WetterLage.mix(cPine, cHillB, .3f), .55f);
        linie.setColor(hc);
        for (float[] h : TAL_HECKEN) { linie.setStrokeWidth(h[3]); c.drawLine(h[0], h[2], h[1], h[2], linie); }
        // Winding road & river
        p.setColor(WetterLage.alpha(cTalRoad, .55f + .30f * tag)); c.drawPath(TAL_STRASSE, p);
        p.setColor(WetterLage.alpha(cTalUfer, .8f)); c.drawPath(TAL_FLUSS_UFER, p);
        if (frozen) {
            p.setColor(WetterLage.mix(cFar, 0xFFEEF3F7, .55f)); c.drawPath(TAL_FLUSS, p);
        } else {
            fillShader(seeLakeShader); c.drawPath(TAL_FLUSS, p); p.setShader(null);
            for (float[] fg : TAL_FLUSS_GLANZ) {
                float fl = .5f + .5f * (float) Math.sin(t * 1.9 + fg[4]);
                p.setColor(WetterLage.alpha(0xFFFFFFFF, .38f * fl));
                c.drawRect(fg[0], fg[1], fg[0] + fg[2], fg[1] + fg[3], p);
            }
        }
        // Hedgerow & riverbank trees
        for (float[] b : TAL_BAEUME) {
            float v = b[3], rr = b[2];
            int col = hatLaub ? WetterLage.mix(cLaub[(int) b[4]], cTalFar, (1 - v) * .4f)
                : WetterLage.alpha(WetterLage.mix(cAst, cHillB, .45f), .55f);
            p.setColor(col); c.drawCircle(b[0], b[1], rr, p);
            if (lage.schnee) {
                p.setColor(WetterLage.alpha(0xFFFFFFFF, .82f));
                r.set(b[0] - rr * .75f, b[1] - rr * .8f, b[0] + rr * .75f, b[1] - rr * .1f);
                c.drawOval(r, p);
            }
        }
        // Alpine village + church steeple + warm glowing windows at night + chimney smoke
        if (nacht > .25f) drawGlow(c, 508, 284, 24, 24, WIN_GLOW, .55f * nacht);
        for (float[] h : TAL_HAEUSER) {
            float hx = h[0], hy = h[1], s = h[2] * 1.25f, hw = 7 * s, hh = 4.6f * s;
            p.setColor(cWand); c.drawRect(hx, hy - hh, hx + hw, hy, p);
            p.setColor(0x1A000000); c.drawRect(hx + hw * .6f, hy - hh, hx + hw, hy, p);
            p.setColor(cDach);
            tmp.reset(); tmp.moveTo(hx - .8f, hy - hh); tmp.lineTo(hx + hw / 2, hy - hh - 3.4f * s); tmp.lineTo(hx + hw + .8f, hy - hh); tmp.close();
            c.drawPath(tmp, p);
            if (nacht > .25f) {
                p.setColor(WetterLage.alpha(0xFFFFD27A, nacht));
                c.drawRect(hx + hw * .3f, hy - hh * .65f, hx + hw * .3f + 1.6f * s, hy - hh * .65f + 1.6f * s, p);
            }
        }
        p.setColor(cWand); c.drawRect(507, 262, 511.4f, 279, p);
        p.setColor(0x1A000000); c.drawRect(509.2f, 262, 511.4f, 279, p);
        p.setColor(cDach); c.drawPath(TAL_KIRCHE_DACH, p);
        if (temp < 10 && !lage.wind) drawRauch(c, 492, 276, 3, .55f, t);
        // Meadow tent + tiny campfire
        p.setColor(WetterLage.mix(0xFFD9783A, cHillB, tag < .5f ? .55f : .1f)); c.drawPath(TAL_ZELT, p);
        p.setColor(0x33000000); c.drawPath(TAL_ZELT_S, p);
        if (nacht > .3f && lage.tropfen == 0) {
            drawGlow(c, 418, 305, 9, 9, FIRE_GLOW, nacht);
            p.setColor(WetterLage.alpha(0xFFFFB347, nacht)); c.drawCircle(418, 305, 1.5f, p);
        }
        // Overlapping spurs with sunlit rims, slope forests, optional mist
        drawSporn(c, TAL_SPORN1, cTalS1, cTalS1Rim);
        if (talMistShader != null) drawTalMist(c, 261, 320, 640, 15, 1f);
        drawSporn(c, TAL_SPORN2, cTalS2, cTalS2Rim);
        if (lage.schnee) { p.setColor(WetterLage.alpha(0xFFFFFFFF, .58f)); c.drawPath(TAL_SPORN2_SCHNEE, p); }
        p.setColor(cTalWc2); c.drawPath(TAL_WALD2, p);
        if (lage.schnee) { p.setColor(WetterLage.alpha(0xFFFFFFFF, .82f)); c.drawPath(TAL_WALD2_SCHNEE, p); }
        if (talMistShader != null && lage.nebel) drawTalMist(c, 298, 260, 720, 16, .9f);
        p.setColor(cTalNl); c.drawPath(TAL_FLANKEN, p);
        p.setColor(cPine); c.drawPath(TAL_WALD3, p);
        if (lage.schnee) { p.setColor(WetterLage.alpha(0xFFFFFFFF, .82f)); c.drawPath(TAL_WALD3_SCHNEE, p); }
        // Right overlook ledge: meadow grass + rocky cliff face + tapered grass tufts
        fillShader(hillShader); c.drawPath(TAL_KANTE_GRAS, p); p.setShader(null);
        p.setColor(cTalFels); c.drawPath(TAL_FELS, p);
        p.setColor(cTalFelsD); c.drawPath(TAL_FELS_D, p);
        linie.setStrokeWidth(1f); linie.setColor(0x24FFFFFF); c.drawPath(TAL_FELS_LICHT, linie);
        drawVordergrundExtras(c, lage, L, temp, monat, Landschaften::talY, 640, W, TAL_STEINE, false);
        p.setColor(cGrassH); c.drawPath(TAL_GRAS_H, p);
        p.setColor(cGrass); c.drawPath(TAL_GRAS_M, p);
    }

    private void drawSporn(Canvas c, Path path, int col, int rim) {
        p.setColor(rim); c.drawPath(path, p);
        c.save(); c.translate(2.5f, 3.5f); p.setColor(col); c.drawPath(path, p); c.restore();
    }

    private void drawTalMist(Canvas c, float y, float x0, float x1, float h, float alpha) {
        float m = (x0 + x1) / 2f, w = x1 - x0;
        float[][] es = {{0, 0, .5f, 1}, {-.22f, .3f, .28f, .7f}, {.24f, -.05f, .26f, .65f}};
        for (float[] e : es) drawGlow(c, m + e[0] * w, y + e[1] * h, e[2] * w, e[3] * h, talMistShader, alpha);
    }

    // ---- 4. GIPFEL ---------------------------------------------------------------------------------------------------

    private void drawGipfel(Canvas c, WetterLage lage, WetterLage.Licht L, float t, int monat) {
        int sai = saison(monat);
        boolean wolken = lage.nebel || "cloudy".equals(lage.cond) || "partlycloudy".equals(lage.cond) || (lage.sonneMoeglich && L.tag > .5f && sai >= 2);
        p.setColor(cGipfR1); c.drawPath(GIPF_R1, p);
        p.setColor(cGipfR1S); c.drawPath(GIPF_R1_SCHATTEN, p);
        p.setColor(cSnowCap); c.drawPath(GIPF_R1_SCHNEE, p);
        fillShader(hzGlowShader); c.drawRect(0, 178, W, 236, p); p.setShader(null);
        p.setColor(cGipfR2); c.drawPath(GIPF_R2, p);
        p.setColor(cGipfR2S); c.drawPath(GIPF_R2_SCHATTEN, p);
        p.setColor(cSnowCap); c.drawPath(GIPF_R2_SCHNEE, p);
        if (wolken) drawWolkenmeer(c, GIPF_WOLKEN1, gipfMistBand1, 214, 260, t * 4f);
        p.setColor(cGipfR3); c.drawPath(GIPF_R3, p);
        p.setColor(cGipfR3S); c.drawPath(GIPF_R3_SCHATTEN, p);
        if (lage.schnee || sai == 3) { p.setColor(cSnowCap); c.drawPath(GIPF_R3_SCHNEE, p); }
        if (wolken) drawWolkenmeer(c, GIPF_WOLKEN2, gipfMistBand2, 250, 302, t * 6.5f);
        // Summit rock foreground
        p.setColor(cGipfFels); c.drawPath(GIPF_FELS, p);
        p.setColor(cGipfFelsD); c.drawPath(GIPF_FELS_D, p);
        linie.setStrokeWidth(1.4f); linie.setColor(0x2EFFFFFF); c.drawPath(GIPF_FELS_LICHT, linie);
        if (lage.schnee || sai == 3) { p.setColor(cSnowCap); c.drawPath(GIPF_FELS_SCHNEE, p); }
        // Wooden summit cross & summit book box
        p.setColor(cGipfHolz);
        c.drawRect(697, 236, 700.6f, 292, p);
        c.drawRect(684, 249, 714, 252.4f, p);
        c.drawPath(GIPF_KREUZ_DACH, p);
        p.setColor(0x40000000); c.drawRect(699.6f, 236, 700.6f, 292, p);
        p.setColor(cGipfBuch); r.set(694, 272, 703, 278); c.drawRoundRect(r, 1, 1, p);
        drawVordergrundExtras(c, lage, L, 5, monat, Landschaften::gipfY, 740, W, GIPF_STEINE, true);
        p.setColor(cGrassH); c.drawPath(GIPF_GRAS_H, p);
        p.setColor(cGrass); c.drawPath(GIPF_GRAS_M, p);
    }

    private void drawWolkenmeer(Canvas c, float[][] ell, Shader band, float y0, float y1, float drift) {
        fillShader(band);
        c.drawRect(0, y0, W, y1, p);
        p.setShader(null);
        for (float[] e : ell) {
            float cx = (e[0] + drift) % (W + 180) - 90;
            drawGlow(c, cx, e[1], e[2] * 1.25f, e[3] * 1.65f, gipfMistPuff, 1f);
        }
    }

    // ---- 5. FELDER ---------------------------------------------------------------------------------------------------

    private void drawFelder(Canvas c, WetterLage lage, WetterLage.Licht L, float t, double temp, int monat) {
        float nacht = 1 - L.tag;
        int sai = saison(monat);
        p.setColor(cFar); c.drawPath(FELD_FAR, p);
        // Three animated rotating wind turbines on the far ridge
        float dur = lage.wind ? 2f : 4.2f;
        for (int i = 0; i < WINDRAEDER.length; i++) {
            float wx = WINDRAEDER[i][0], wy = WINDRAEDER[i][1], wh = WINDRAEDER[i][2], hy = wy - wh;
            linie.setStrokeWidth(1.6f); linie.setColor(cWindrad);
            c.drawLine(wx, wy, wx, hy, linie);
            float ang = ((t + i * 1.3f) / dur) * 360f;
            c.save(); c.translate(wx, hy); c.rotate(ang);
            linie.setStrokeWidth(1.3f);
            for (int b = 0; b < 3; b++) {
                double rad = Math.toRadians(b * 120);
                c.drawLine(0, 0, (float) Math.sin(rad) * wh * .55f, (float) -Math.cos(rad) * wh * .55f, linie);
            }
            c.restore();
            p.setColor(cWindrad); c.drawCircle(wx, hy, 1.4f, p);
            if (nacht > .35f) {
                float blink = ((t + i * .7f) % 2.2f) < 1.1f ? 1f : .25f;
                p.setColor(WetterLage.alpha(0xFFFF5A4A, nacht * blink));
                c.drawCircle(wx, hy - 1.2f, 1.15f, p);
            }
        }
        fillShader(hzGlowShader); c.drawRect(0, 178, W, 236, p); p.setShader(null);
        // First patchwork hill
        drawPatchwork(c, FELD_HUEGEL_A, FELD_A_STREIFEN, FELD_A_IDX, FELD_A_FURC);
        // Farmstead (barn, farmhouse, silo, farm tree, chimney smoke)
        p.setColor(cScheune); c.drawRect(290, 216, 316, 228, p);
        p.setColor(cScheuneDach); c.drawPath(FELD_SCHEUNE_DACH, p);
        p.setColor(0x4D000000); c.drawRect(300, 221, 306, 228, p);
        p.setColor(cHaus); c.drawRect(320, 218, 336, 228, p);
        p.setColor(lage.schnee ? cScheuneDach : cScheune); c.drawPath(FELD_HAUS_DACH, p);
        drawFenster(c, 324, 221, 3, 3, nacht);
        drawFenster(c, 330, 221, 3, 3, nacht);
        p.setColor(cSilo); r.set(276, 208, 284, 228); c.drawRoundRect(r, 3.5f, 3.5f, p);
        if (hatLaub) {
            p.setColor(cAst); c.drawRect(345, 222, 347, 228, p);
            p.setColor(cLaub[0]); c.drawCircle(346, 218, 8, p);
        } else {
            drawKahl(c, 346, 228, 20, lage.schnee);
        }
        if (temp < 10 && !lage.wind) drawRauch(c, 334, 210, 4, .8f, t);
        // Second patchwork hill
        drawPatchwork(c, FELD_HUEGEL_B, FELD_B_STREIFEN, FELD_B_IDX, FELD_B_FURC);
        // Country road + row of 9 poplars
        p.setColor(cFeldStrasse); c.drawPath(FELD_WEG_STRASSE, p);
        for (int i = 0; i < 9; i++) {
            float px = 400 + i * 24, py = 260 - i * 1.6f, ph = 30 - i * 2.2f;
            if (!hatLaub) {
                p.setColor(WetterLage.alpha(cAst, .2f)); r.set(px - ph * .14f, py - ph, px + ph * .14f, py - ph * .1f); c.drawOval(r, p);
                linie.setStrokeWidth(1f); linie.setColor(cAst);
                c.drawLine(px, py, px, py - ph, linie);
                c.drawLine(px, py - ph * .25f, px - ph * .1f, py - ph * .85f, linie);
                c.drawLine(px, py - ph * .3f, px + ph * .1f, py - ph * .88f, linie);
            } else {
                p.setColor(cLaub[1]); r.set(px - ph * .17f, py - ph, px + ph * .17f, py); c.drawOval(r, p);
                if (lage.schnee) {
                    p.setColor(WetterLage.alpha(0xFFFFFFFF, .8f));
                    r.set(px - ph * .1f, py - ph * .97f, px + ph * .1f, py - ph * .73f); c.drawOval(r, p);
                }
            }
        }
        // Hay bales in summer / autumn when not snowy
        if ((sai == 1 || sai == 2) && !lage.schnee) {
            float[][] b = {{520, 270}, {548, 273}, {584, 268}};
            for (float[] hb : b) {
                p.setColor(cHeu); c.drawCircle(hb[0], hb[1], 6, p);
                linie.setStrokeWidth(1f); linie.setColor(0x26000000); c.drawCircle(hb[0], hb[1], 3.2f, linie);
            }
        }
        // Foreground meadow, field path, wooden fence, extras & grass
        fillShader(hillShader); c.drawPath(FELD_VORDER, p); p.setShader(null);
        p.setColor(cFeldPfad); c.drawPath(FELD_PFAD, p);
        p.setColor(cZaun);
        for (int i = 0; i < 5; i++) {
            float fx = 560 + i * 40, fy = 296 - i * 1.6f;
            c.drawRect(fx - 1.5f, fy - 16, fx + 1.5f, fy + 2, p);
        }
        linie.setStrokeWidth(.8f); linie.setColor(cZaun); c.drawPath(FELD_ZAUN_DRAHT, linie);
        drawVordergrundExtras(c, lage, L, temp, monat, Landschaften::feldY, 0, W, FELD_STEINE, false);
        p.setColor(cGrassH); c.drawPath(FELD_GRAS_H, p);
        p.setColor(cGrass); c.drawPath(FELD_GRAS_M, p);
    }

    private void drawPatchwork(Canvas c, Path hillClip, Path[] strips, int[] colIdx, Path furrows) {
        c.save();
        c.clipPath(hillClip);
        p.setColor(cFeldBunt[0]); c.drawPath(hillClip, p);
        for (int i = 0; i < strips.length; i++) {
            p.setColor(cFeldBunt[colIdx[i]]);
            c.drawPath(strips[i], p);
        }
        linie.setStrokeWidth(1f); linie.setColor(0x14000000);
        c.drawPath(furrows, linie);
        c.restore();
    }

    // ---- shared helpers ----------------------------------------------------------------------------------------------

    private void fillShader(Shader s) {
        p.setColor(0xFFFFFFFF);
        p.setShader(s);
    }

    private void drawGlow(Canvas c, float cx, float cy, float rx, float ry, Shader sh, float alpha) {
        c.save();
        c.translate(cx, cy);
        c.scale(rx, ry);
        fillShader(sh);
        p.setAlpha(Math.round(255 * Math.max(0, Math.min(1, alpha))));
        c.drawCircle(0, 0, 1, p);
        p.setShader(null);
        p.setAlpha(255);
        c.restore();
    }

    private void drawFenster(Canvas c, float x, float y, float w, float h, float nacht) {
        if (nacht > .25f) {
            drawGlow(c, x + w / 2, y + h / 2, w * 2.6f, w * 2.6f, WIN_GLOW, nacht);
            p.setColor(WetterLage.alpha(0xFFFFD27A, .35f + .65f * nacht));
        } else {
            p.setColor(0x59000000);
        }
        c.drawRect(x, y, x + w, y + h, p);
    }

    private void drawRauch(Canvas c, float x, float y, int n, float scale, float t) {
        for (int i = 0; i < n; i++) {
            float ph = ((t + i * (6f / n)) % 6f) / 6f;
            float rx = x + ph * 18 * scale + (float) Math.sin(ph * 5 + i) * 3 * scale;
            float ry = y - ph * 34 * scale;
            float rad = (2.6f + ph * 5.2f) * scale;
            p.setColor(WetterLage.alpha(0xFFEBEFF4, .45f * (1 - ph)));
            c.drawCircle(rx, ry, rad, p);
        }
    }

    private void drawKahl(Canvas c, float x, float y, float h, boolean snowy) {
        p.setColor(WetterLage.alpha(cAst, .16f));
        r.set(x - h * .38f, y - h * 1.04f, x + h * .38f, y - h * .36f);
        c.drawOval(r, p);
        linie.setColor(cAst);
        linie.setStrokeWidth(Math.max(1f, h * .05f));
        c.drawLine(x, y, x, y - h * .45f, linie);
        c.drawLine(x, y - h * .3f, x - h * .28f, y - h * .72f, linie);
        c.drawLine(x, y - h * .38f, x + h * .26f, y - h * .8f, linie);
        c.drawLine(x, y - h * .45f, x + h * .04f, y - h, linie);
        c.drawLine(x - h * .14f, y - h * .52f, x - h * .34f, y - h * .6f, linie);
        c.drawLine(x + h * .13f, y - h * .6f, x + h * .34f, y - h * .66f, linie);
        if (snowy) {
            linie.setColor(0xCCFFFFFF);
            linie.setStrokeWidth(Math.max(.7f, h * .025f));
            c.drawLine(x, y - h * .3f - .9f, x - h * .28f, y - h * .72f - .9f, linie);
            c.drawLine(x, y - h * .38f - .9f, x + h * .26f, y - h * .8f - .9f, linie);
        }
    }

    private void drawVordergrundExtras(Canvas c, WetterLage lage, WetterLage.Licht L, double temp, int monat,
                                       YFn yAt, float x0, float x1, float[][] steine, boolean keineBlumen) {
        if ("snowy-rainy".equals(lage.cond)) {
            float[][] sl = {{.08f, 22, 40}, {.3f, 14, 28}, {.6f, 18, 34}, {.93f, 8, 30}};
            p.setColor(0xBFFFFFFF);
            for (float[] s : sl) {
                float sx = x0 + (x1 - x0) * s[0], sy = yAt.at(sx) + s[1];
                r.set(sx - s[2], sy - s[2] * .16f, sx + s[2], sy + s[2] * .16f);
                c.drawOval(r, p);
            }
        }
        if (lage.stark) {
            float[][] pf = {{.22f, 26, 34}, {.52f, 22, 26}, {.73f, 20, 22}};
            p.setColor(cPfuetze);
            for (float[] s : pf) {
                float sx = x0 + (x1 - x0) * s[0], sy = yAt.at(sx) + s[1];
                r.set(sx - s[2], sy - s[2] * .18f, sx + s[2], sy + s[2] * .18f);
                c.drawOval(r, p);
            }
        }
        for (float[] st : steine) {
            p.setColor(0x28000000);
            r.set(st[0] - st[2], st[1] - st[2] * .25f + 1.5f, st[0] + st[2], st[1] + st[2] * .48f + 1.5f);
            c.drawOval(r, p);
            p.setColor(cStone);
            r.set(st[0] - st[2], st[1] - st[2] * .45f, st[0] + st[2], st[1] + st[2] * .45f);
            c.drawOval(r, p);
            if (lage.schnee) {
                p.setColor(0xFFF4F7FA);
                r.set(st[0] - st[2] * .8f, st[1] - st[2] * .45f, st[0] + st[2] * .8f, st[1] - st[2] * .05f);
                c.drawOval(r, p);
            }
        }
        int sai = saison(monat);
        if (!keineBlumen && "tag".equals(lage.landTag) && L.tag > .5f && temp >= 8 && (sai == 0 || sai == 1)) {
            int[] bc = {0xFFF4E27A, 0xFFF2F2F2, 0xFFE88FB0};
            for (float[] b : BLUMEN_XY) {
                float bx = x0 + b[0] * (x1 - x0), by = yAt.at(bx) + b[1];
                p.setColor(bc[(int) b[2]]);
                c.drawCircle(bx, by, 1.7f, p);
            }
        }
        if ("hail".equals(lage.cond)) {
            p.setColor(0xE6F0F6FC);
            for (float[] h : HAGEL_XY) {
                float hx = x0 + h[0] * (x1 - x0), hy = yAt.at(hx) + h[1];
                c.drawCircle(hx, hy, h[2], p);
            }
        }
    }
}
