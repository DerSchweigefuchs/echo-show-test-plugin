package echoshow.kiosktest;

import java.util.ArrayList;
import java.util.List;

/**
 * Cloud geometry of the weather page (cumulus clusters of puffs in 6 depth rows, towers when it is cloudier), ported
 * from wetter.js with the same seeded random numbers. Plain Java. Each row is laid out over a band of BREITE px that
 * repeats seamlessly, so the rows can drift across the screen without end.
 */
final class Wolken {
    static final float YH = 235;          // cloud horizon of the weather page
    static final float BREITE = 1240;     // one period of a row (the page lays out x -140 .. 1100)
    static final int REIHEN = 6;

    static final class Haufen {
        float cx, by, w, h, top;
        final List<float[]> puffs = new ArrayList<>();   // {x, y, r}
    }

    static final class Reihe {
        float d;            // 1 far .. 0 near
        float blur;         // page px
        float dauer;        // s for one swing (or one flight when windy)
        final List<Haufen> haufen = new ArrayList<>();
    }

    /** mulberry32 like wRnd in the page. */
    static final class Zufall {
        private int s;

        Zufall(int seed) { s = seed; }

        double next() {
            s += 0x6D2B79F5;
            int t = (s ^ (s >>> 15)) * (1 | s);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }
    }

    static List<Reihe> bauen(WetterLage w, int tagNr) {
        return bauen(w, tagNr, YH);
    }

    static List<Reihe> bauen(WetterLage w, int tagNr, float yh) {
        List<Reihe> out = new ArrayList<>();
        if (w.cover <= 0) return out;
        Zufall R = new Zufall(tagNr * 31 + w.wolken * 7);
        double k = .5 + .5 * yh / 457;
        boolean wolkig = "cloudy".equals(w.cond);
        for (int j = 0; j < REIHEN; j++) {
            Reihe reihe = new Reihe();
            double d = 1 - j / (double) (REIHEN - 1);
            reihe.d = (float) d;
            double by0 = yh * (.16 + .84 * Math.pow(d, .7)) + 6, H0 = (24 + 150 * Math.pow(1 - d, 1.25)) * k;
            for (double x = -140 + R.next() * 80; x < 1100; ) {
                double Hb = H0 * (.7 + R.next() * .6), W = Hb * (wolkig ? 2.6 + R.next() * 2.2 : 2 + R.next() * 1.8), cx = x + W / 2;
                double by = by0 + (R.next() - .5) * H0 * .7 * (1 - d * .6);
                double tf = 1 + (w.tower - 1) * (w.tower > 1 ? .25 + .75 * d : 1), H = Hb * tf * (.8 + R.next() * .5);
                if (R.next() < w.cover + (d > .6 ? .25 : 0)) {
                    Haufen c = new Haufen();
                    int n = (int) Math.round(14 + W / Math.max(8, Hb * .14));
                    for (int i = 0; i < n; i++) {
                        double u = R.next() * 2 - 1, env = Math.pow(Math.max(0, 1 - u * u), .45) * (.7 + R.next() * .4);
                        double r = H * (.13 + R.next() * .15) * (.55 + .45 * env);
                        double py = by - Math.max(r * .2, Math.pow(R.next(), .8) * Math.max(0, H * env - r * 1.1));
                        c.puffs.add(new float[] {(float) (cx + u * W * .46), (float) py, (float) r});
                    }
                    int nT = w.tower > .9 ? 1 + (int) Math.floor(R.next() * (w.tower > 1.2 ? 4 : 3)) : (R.next() < w.tower - .3 ? 1 : 0);
                    for (int t = 0; t < nT; t++) {
                        double u0 = (R.next() - .5) * .9, tw = W * (.12 + R.next() * .14), th = H * (.55 + R.next() * .8) * w.tower * (.35 + .65 * d);
                        double base = by - H * Math.pow(Math.max(0, 1 - u0 * u0), .45) * .75;
                        for (int i = 0; i < 9; i++) {
                            double v = i / 8.0, r = tw * (.55 - .25 * v) * (.8 + R.next() * .4);
                            c.puffs.add(new float[] {(float) (cx + u0 * W * .5 + (R.next() - .5) * tw * .7), (float) (base - v * th), (float) r});
                        }
                    }
                    double top = by - H;
                    for (float[] p : c.puffs) if (p[1] - p[2] < top) top = p[1] - p[2];
                    c.cx = (float) (cx + 140);   // shift into the repeating band 0 .. BREITE
                    for (float[] p : c.puffs) p[0] += 140;
                    c.by = (float) by;
                    c.w = (float) W;
                    c.h = (float) H;
                    c.top = (float) top;
                    reihe.haufen.add(c);
                }
                x += W * (w.cover >= .85 ? .58 : .7 + (1 - w.cover) * (.5 + R.next() * 1.2));
            }
            reihe.blur = (float) ((1.2 + 2.4 * (1 - d)) * k * (w.cover >= 1 ? 1.6 : 1));
            reihe.dauer = (float) ((w.schnell ? 30 : 160) + d * (w.schnell ? 20 : 200) + R.next() * 40);
            if (!reihe.haufen.isEmpty()) out.add(reihe);
        }
        return out;
    }

    /** Cloud colors {hi, bo, sh, hz} of the weather page for day or night. */
    static int[] farben(WetterLage w, boolean nacht, boolean mond) {
        int c = nacht ? w.ccNacht : w.ccTag;
        if (nacht) return new int[] {WetterLage.hex(mond ? "#8f98a9" : "#5f6776"), WetterLage.hex("#464d5a"), WetterLage.hex("#2d323c"), WetterLage.hex("#262c36")};
        if (w.cover < 1) return new int[] {WetterLage.hex("#ffffff"), WetterLage.hex("#eef0f4"), WetterLage.hex("#b6bac9"), WetterLage.hex("#dce2ea")};
        return new int[] {WetterLage.mix(c, 0xFFFFFFFF, .25f), c, WetterLage.shade(c, .84f), WetterLage.mix(c, 0xFFFFFFFF, .12f)};
    }
}
