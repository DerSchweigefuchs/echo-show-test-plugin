package echoshow.kiosktest;

/**
 * Sky geometry for the weather scene: sun and moon positions (low-precision ephemeris after the SunCalc formulas, ported
 * from the dashboard's weather page), the moon's phase and orientation, star positions and the projection of the sky
 * onto the 963 x 584 scene. Plain Java. The observer's coordinates come from Home Assistant at runtime only.
 */
final class Himmel {
    static final double R = Math.PI / 180;
    /** Scene: horizon line of the sea, centre x of the view (looking south), pixels per radian. */
    static final float HORIZONT = 212, MITTE = 472, PX_RAD = 200;

    private Himmel() {}

    static double tage(long ms) { return ms / 864e5 - .5 + 2440588 - 2451545; }

    // ---- sun ----------------------------------------------------------------------------------------------------------

    /** {altitude deg, azimuth deg from north clockwise, declination rad, right ascension rad}. */
    static double[] sonne(long ms, double lat, double lon) {
        double d = tage(ms), e = R * 23.4397;
        double M = R * (357.5291 + .98560028 * d);
        double L = M + R * (1.9148 * Math.sin(M) + .02 * Math.sin(2 * M) + .0003 * Math.sin(3 * M)) + R * 102.9372 + Math.PI;
        double dec = Math.asin(Math.sin(e) * Math.sin(L)), ra = Math.atan2(Math.sin(L) * Math.cos(e), Math.cos(L));
        double phi = R * lat, H = R * (280.16 + 360.9856235 * d) + R * lon - ra;
        double alt = Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(H));
        double az = Math.atan2(Math.sin(H), Math.cos(H) * Math.sin(phi) - Math.tan(dec) * Math.cos(phi));   // from south, westwards
        return new double[] {alt / R, norm360(az / R + 180), dec, ra};
    }

    // ---- moon (port of MOON in the weather page) ----------------------------------------------------------------------

    static final class Mond {
        double f;        // illuminated fraction
        double alt;      // deg
        double az;       // deg from north clockwise
        double lst, phi; // local sidereal angle (rad) and latitude (rad), for the stars
        int limb, face;  // rotation of the lit limb and of the face on screen, deg
        boolean sichtbar;
    }

    static Mond mond(long ms, double lat, double lon) {
        double d = tage(ms), e = R * 23.4397;
        double M = R * (357.5291 + .98560028 * d);
        double Ls = M + R * (1.9148 * Math.sin(M) + .02 * Math.sin(2 * M) + .0003 * Math.sin(3 * M)) + R * 102.9372 + Math.PI;
        double sRa = ra(Ls, 0, e), sDec = de(Ls, 0, e);
        double L = R * (218.316 + 13.176396 * d), Mm = R * (134.963 + 13.064993 * d), F = R * (93.272 + 13.22935 * d);
        double l = L + R * 6.289 * Math.sin(Mm), b = R * 5.128 * Math.sin(F);
        double mRa = ra(l, b, e), mDec = de(l, b, e), dist = 385001 - 20905 * Math.cos(Mm);
        double phi = R * lat, H = R * (280.16 + 360.9856235 * d) + R * lon - mRa;
        double alt = Math.asin(Math.sin(phi) * Math.sin(mDec) + Math.cos(phi) * Math.cos(mDec) * Math.cos(H)) / R;
        double q = Math.atan2(Math.sin(H), Math.tan(phi) * Math.cos(mDec) - Math.sin(mDec) * Math.cos(H));
        double el = Math.acos(Math.sin(sDec) * Math.sin(mDec) + Math.cos(sDec) * Math.cos(mDec) * Math.cos(sRa - mRa));
        double inc = Math.atan2(149598000 * Math.sin(el), dist - 149598000 * Math.cos(el));
        double ang = Math.atan2(Math.cos(sDec) * Math.sin(sRa - mRa), Math.sin(sDec) * Math.cos(mDec) - Math.cos(sDec) * Math.sin(mDec) * Math.cos(sRa - mRa));
        double az = Math.atan2(Math.sin(H), Math.cos(H) * Math.sin(phi) - Math.tan(mDec) * Math.cos(phi));
        Mond m = new Mond();
        m.f = (1 + Math.cos(inc)) / 2;
        m.alt = alt;
        m.az = norm360(az / R + 180);
        m.lst = H + mRa;
        m.phi = phi;
        m.sichtbar = alt > -.5 && m.f > .03;
        m.limb = (int) Math.round(-(ang - q) / R);
        m.face = (int) Math.round(q / R);
        return m;
    }

    private static double ra(double l, double b, double e) { return Math.atan2(Math.sin(l) * Math.cos(e) - Math.tan(b) * Math.sin(e), Math.cos(l)); }

    private static double de(double l, double b, double e) { return Math.asin(Math.sin(b) * Math.cos(e) + Math.cos(b) * Math.sin(e) * Math.sin(l)); }

    /** Star (J2000 RA hours, Dec deg) -> {altitude deg, azimuth deg from north}, using the sidereal angle of mond(). */
    static double[] stern(double raH, double dec, double lst, double phi) {
        double H = lst - raH * 15 * R, dd = dec * R;
        double alt = Math.asin(Math.sin(phi) * Math.sin(dd) + Math.cos(phi) * Math.cos(dd) * Math.cos(H));
        double az = Math.atan2(Math.sin(H), Math.cos(H) * Math.sin(phi) - Math.tan(dd) * Math.cos(phi));
        return new double[] {alt / R, norm360(az / R + 180)};
    }

    // ---- projection ---------------------------------------------------------------------------------------------------

    /**
     * The scene looks south. Mercator around the horizon: the horizon stays the straight sea line, shapes such as the
     * constellations keep their form, about 275 degrees of azimuth fit across the screen, the sky up to about 50 degrees.
     */
    static float x(double azNord) {
        double dz = norm180(azNord - 180);
        return (float) (MITTE + PX_RAD * dz * R);
    }

    static float y(double alt) {
        double a = Math.max(-80, Math.min(80, alt)) * R;
        return (float) (HORIZONT - PX_RAD * Math.log(Math.tan(Math.PI / 4 + a / 2)));
    }

    static double norm360(double a) {
        a %= 360;
        return a < 0 ? a + 360 : a;
    }

    static double norm180(double a) {
        a = norm360(a + 180) - 180;
        return a;
    }

    // ---- constellations (from the weather page: J2000 RA h, Dec deg, magnitude, colour o/r/b; l = polylines) ----------

    static final class Sternbild {
        final String name;
        final double[][] s;
        final char[] farbe;
        final int[][] l;

        Sternbild(String name, double[][] s, char[] farbe, int[][] l) {
            this.name = name;
            this.s = s;
            this.farbe = farbe;
            this.l = l;
        }
    }

    private static Sternbild sb(String n, String sterne, int[][] l) {
        String[] t = sterne.trim().split(";");
        double[][] s = new double[t.length][];
        char[] f = new char[t.length];
        for (int i = 0; i < t.length; i++) {
            String[] v = t[i].trim().split(",");
            s[i] = new double[] {Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2])};
            f[i] = v.length > 3 ? v[3].trim().charAt(0) : ' ';
        }
        return new Sternbild(n, s, f, l);
    }

    static final Sternbild[] STERNBILDER = {
        sb("Großer Wagen", "11.062,61.75,1.8;11.031,56.38,2.4;11.897,53.69,2.4;12.257,57.03,3.3;12.9,55.96,1.8;13.399,54.93,2.2;13.792,49.31,1.9", new int[][] {{0, 1, 2, 3, 0}, {3, 4, 5, 6}}),
        sb("Kleiner Wagen", "2.53,89.26,2;17.537,86.59,4.4;16.766,82.04,4.2;15.734,77.79,4.3;14.845,74.16,2.1,o;15.345,71.83,3;16.292,75.76,5", new int[][] {{0, 1, 2, 3, 4, 5, 6, 3}}),
        sb("Kassiopeia", "0.153,59.15,2.3;0.675,56.54,2.2,o;0.945,60.72,2.2;1.43,60.24,2.7;1.907,63.67,3.4", new int[][] {{0, 1, 2, 3, 4}}),
        sb("Orion", "5.919,7.41,.5,r;5.242,-8.2,.1,b;5.419,6.35,1.6,b;5.533,-.3,2.2;5.603,-1.2,1.7,b;5.679,-1.94,1.8;5.796,-9.67,2.1;5.585,9.93,3.4", new int[][] {{7, 0, 5, 4, 3, 2, 7}, {5, 6}, {3, 1}}),
        sb("Stier", "4.599,16.51,.9,o;5.438,28.61,1.7;5.627,21.14,3;4.33,15.63,3.7;4.477,19.18,3.5", new int[][] {{2, 0, 3, 4, 1}}),
        sb("Plejaden", "3.791,24.11,2.9;3.819,24.05,3.6;3.763,24.37,3.9;3.749,24.11,4.3;3.773,23.95,4.2;3.747,24.47,4.1", new int[][] {}),
        sb("Zwillinge", "7.577,31.89,1.6;7.755,28.03,1.1,o;6.629,16.4,1.9;6.732,25.13,3;7.335,21.98,3.5;6.383,22.51,2.9", new int[][] {{5, 3, 0, 1, 4, 2}}),
        sb("Fuhrmann", "5.278,46,.1,o;5.992,44.95,1.9;5.995,37.21,2.6;5.438,28.61,1.7;4.95,33.17,2.7", new int[][] {{0, 1, 2, 3, 4, 0}}),
        sb("Großer Hund", "6.752,-16.72,-1.5,b;6.378,-17.96,2;6.977,-28.97,1.5;7.14,-26.39,1.8;7.401,-29.3,2.4", new int[][] {{1, 0, 3, 2}, {3, 4}}),
        sb("Kleiner Hund", "7.655,5.22,.4;7.453,8.29,2.9", new int[][] {{0, 1}}),
        sb("Löwe", "10.14,11.97,1.4,b;11.818,14.57,2.1;10.333,19.84,2;11.235,20.52,2.6;11.237,15.43,3.3;10.122,16.76,3.5;10.278,23.42,3.4;9.879,26.01,3.9", new int[][] {{0, 5, 2, 6, 7}, {2, 3, 1, 4, 0}}),
        sb("Bärenhüter", "14.261,19.18,-.1,o;14.75,27.07,2.4;13.911,18.4,2.7;14.535,38.31,3;15.032,40.39,3.5;15.258,33.31,3.5", new int[][] {{2, 0, 1, 5, 4, 3, 0}}),
        sb("Leier", "18.616,38.78,0,b;18.835,33.36,3.5;18.982,32.69,3.3;18.746,37.61,4.3;18.908,36.9,4.3", new int[][] {{0, 3, 1, 2, 4, 3}}),
        sb("Schwan", "20.69,45.28,1.3;20.37,40.26,2.2;20.77,33.97,2.5;19.75,45.13,2.9;19.512,27.96,3.1,o", new int[][] {{0, 1, 4}, {3, 1, 2}}),
        sb("Adler", "19.846,8.87,.8;19.771,10.61,2.7,o;19.922,6.41,3.7;19.425,3.11,3.4;19.104,-4.88,3.4;19.09,13.86,3", new int[][] {{1, 0, 2}, {0, 3, 4}, {3, 5}}),
        sb("Pegasus", "23.079,15.21,2.5;23.063,28.08,2.4,r;0.14,29.09,2.1;0.22,15.18,2.8;22.691,10.83,3.4;21.736,9.88,2.4,o", new int[][] {{0, 1, 2, 3, 0}, {0, 4, 5}}),
        sb("Andromeda", "0.14,29.09,2.1;0.656,30.86,3.3;1.162,35.62,2.1,r;2.065,42.33,2.1,o", new int[][] {{0, 1, 2, 3}}),
        sb("Perseus", "3.405,49.86,1.8;3.136,40.96,2.1;3.715,47.79,3;3.964,40.01,2.9;3.08,53.51,2.9;3.902,31.88,2.9", new int[][] {{4, 0, 2, 3, 5}, {0, 1}}),
        sb("Skorpion", "16.49,-26.43,1,r;16.091,-19.81,2.6;16.006,-22.62,2.3;15.981,-26.11,2.9;16.598,-28.22,2.8;16.836,-34.29,2.3;16.864,-38.05,3;17.622,-43,1.9;17.56,-37.1,1.6", new int[][] {{1, 2, 3}, {2, 0, 4, 5, 6, 7, 8}}),
        sb("Jungfrau", "13.42,-11.16,1,b", new int[][] {}),
    };

    /** Background stars at fixed sky positions (seeded), so they turn with the constellations: {raH, dec, size, brightness}. */
    static double[][] hintergrund(int n) {
        long seed = 7;
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            seed = (seed * 9301 + 49297) % 233280;
            double a = seed / 233280.0;
            seed = (seed * 9301 + 49297) % 233280;
            double b = seed / 233280.0;
            seed = (seed * 9301 + 49297) % 233280;
            double c = seed / 233280.0;
            seed = (seed * 9301 + 49297) % 233280;
            double h = seed / 233280.0;
            out[i] = new double[] {a * 24, Math.asin(2 * b - 1) / R, c < .2 ? 1.4 : .9, .35 + h * .6};
        }
        return out;
    }
}
