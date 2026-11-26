package echoshow.kiosktest;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The forecast sheet of the weather page (wetter.js "card: a sheet with a drag handle"), collapsed: a summary line, the
 * Heute / Woche switch and either the next 8 hours or 6 days. Only data from Home Assistant: the forecast helpers
 * (input_text.wetter_woche, wetter_stunden, wetter_tage, wetter_regen, written by the page's own automation) and the
 * weather entity. The page's own Open-Meteo request is left out; whatever is missing is simply not drawn.
 * Asked-for days are framed in the week (approved mockup). Sheet coordinates: 923 x 262, drawn at page (20, 308).
 */
final class Vorhersage {
    static final float BREITE = 923, HOEHE = 262, X0 = 20, Y0 = 308;
    static final int TXT = 0xFFECEBE8, SUB = 0xFFC9D3DC, BLUE = 0xFF6FB3F2, AMB = 0xFFF2B45C, PROZENT = 0xFF8FC0FF, MM = 0xFF8FBAF0;
    static final String[] DS = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"}, DL = {"Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"};
    static final int RG_MIN = 30;

    private Vorhersage() {}

    // ---- data -----------------------------------------------------------------------------------------------------------

    /** "a|b|c;d|e|f" -> rows; unknown / unavailable / empty -> none (the page's parse). */
    static List<String[]> parse(String s) {
        List<String[]> out = new ArrayList<>();
        if (s == null || s.isEmpty() || "unknown".equals(s) || "unavailable".equals(s)) return out;
        for (String z : s.split(";")) out.add(z.split("\\|", -1));
        return out;
    }

    /** input_text.wetter_regen: YYYYMMDDHH|p,p,... (30 hours)|YYYYMMDD|p,p,... (7 days, daily maximum), local time. */
    static final class Regen {
        long t, d;
        String[] h, dd;
    }

    static Regen regen(String s) {
        if (s == null || s.split("\\|").length < 4) return null;
        String[] a = s.split("\\|", -1);
        try {
            Regen r = new Regen();
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(a[0].substring(0, 4)), Integer.parseInt(a[0].substring(4, 6)) - 1, Integer.parseInt(a[0].substring(6, 8)), Integer.parseInt(a[0].substring(8, 10)), 0, 0);
            r.t = c.getTimeInMillis();
            r.h = a[1].split(",", -1);
            c.clear();
            c.set(Integer.parseInt(a[2].substring(0, 4)), Integer.parseInt(a[2].substring(4, 6)) - 1, Integer.parseInt(a[2].substring(6, 8)), 12, 0, 0);
            r.d = c.getTimeInMillis();
            r.dd = a[3].split(",", -1);
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double zahl(String v) {
        if (v == null || v.trim().isEmpty()) return null;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Probability of the next hour hh of the day from now (hh < 0: the current hour), null when unknown (rgHour). */
    static Double rgHour(Regen rg, int hh, long now) {
        if (rg == null) return null;
        Calendar n = Calendar.getInstance();
        n.setTimeInMillis(now);
        n.set(Calendar.MINUTE, 0);
        n.set(Calendar.SECOND, 0);
        n.set(Calendar.MILLISECOND, 0);
        long h0 = n.getTimeInMillis();
        Calendar c = Calendar.getInstance();
        for (int k = 0; k < rg.h.length; k++) {
            long t = rg.t + k * 3_600_000L;
            if (t < h0) continue;
            c.setTimeInMillis(t);
            if (hh < 0 || c.get(Calendar.HOUR_OF_DAY) == hh) return zahl(rg.h[k]);
        }
        return null;
    }

    /** Daily maximum for weekday wd (0 = Monday) from today on (rgDay). */
    static Double rgDay(Regen rg, int wd, long now) {
        if (rg == null) return null;
        Calendar d0 = Calendar.getInstance();
        d0.setTimeInMillis(now);
        d0.set(Calendar.HOUR_OF_DAY, 0);
        d0.set(Calendar.MINUTE, 0);
        d0.set(Calendar.SECOND, 0);
        d0.set(Calendar.MILLISECOND, 0);
        Calendar c = Calendar.getInstance();
        for (int j = 0; j < rg.dd.length; j++) {
            long t = rg.d + j * 864e5 > 0 ? rg.d + j * 86_400_000L : 0;
            c.setTimeInMillis(t);
            if (t < d0.getTimeInMillis()) continue;
            if (wochentag(c) == wd) return zahl(rg.dd[j]);
        }
        return null;
    }

    static int wochentag(Calendar c) { return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; }   // 0 = Monday

    static boolean nass(String c) { return c != null && (c.contains("rain") || c.contains("pour") || c.contains("snow") || c.contains("hail")); }

    static boolean wahrscheinlich(Double p) { return p == null || p >= RG_MIN; }

    /** The condition as shown: unlikely precipitation (< 30 %) becomes cloudy (rgCond). */
    static String gezeigt(String c, Double p) { return nass(c) && !wahrscheinlich(p) ? "cloudy" : c; }

    static double z(String v) {
        Double d = zahl(v);
        return d == null ? 0 : d;
    }

    static String mmKurz(double v) { return Figur.n(Math.round(v * 10) / 10.0).replace('.', ','); }

    // ---- summaries (the sheet's one line) -------------------------------------------------------------------------------

    /** A piece of the summary; wet words are bold blue like on the page. */
    static final class Teil {
        final String t;
        final boolean blau;

        Teil(String t, boolean blau) {
            this.t = t;
            this.blau = blau;
        }
    }

    private static String wetName(String c) {
        return c.contains("snow") ? "Schnee" : ("hail".equals(c) ? "Hagel" : (c.contains("lightning") ? "Gewitter" : "Regen"));
    }

    static List<Teil> sumW(List<String[]> week, Regen rg, long now) {
        List<Teil> out = new ArrayList<>();
        if (week.isEmpty()) return out;
        List<int[]> rd = new ArrayList<>();
        List<Double> rdMm = new ArrayList<>();
        for (int i = 0; i < Math.min(6, week.size()); i++) {
            int wd = (int) z(week.get(i)[0]);
            double mm = z(week.get(i).length > 4 ? week.get(i)[4] : "");
            if (mm >= 1 && wahrscheinlich(rgDay(rg, wd, now))) {
                rd.add(new int[] {i, wd});
                rdMm.add(mm);
            }
        }
        if (rd.isEmpty()) out.add(new Teil("Die nächsten Tage trocken", false));
        else {
            out.add(new Teil(wetName(week.get(rd.get(0)[0])[1]), true));
            out.add(new Teil(" " + tagText(rd.get(0), true), false));
        }
        if (rd.size() > 1) {
            int mx = 0;
            for (int k = 1; k < rd.size(); k++) if (rdMm.get(k) > rdMm.get(mx)) mx = k;
            if (rdMm.get(mx) >= 5) out.add(new Teil(" · am meisten " + (rd.get(mx)[0] < 2 ? tagText(rd.get(mx), false) : "am " + DS[rd.get(mx)[1]]) + " (" + mmKurz(rdMm.get(mx)) + " mm)", false));
        }
        String[] hot = week.get(0);
        for (int i = 0; i < Math.min(6, week.size()); i++) if (z(week.get(i)[2]) > z(hot[2])) hot = week.get(i);
        if (z(hot[2]) > z(week.get(0)[2])) out.add(new Teil(" · bis " + hot[2] + "° am " + DS[(int) z(hot[0])], false));
        return out;
    }

    private static String tagText(int[] x, boolean ab) {
        return x[0] == 0 ? "heute" : (x[0] == 1 ? (ab ? "ab morgen" : "morgen") : (ab ? "ab " : "am ") + DL[x[1]]);
    }

    static List<Teil> sumH(List<String[]> hours, Regen rg, long now, boolean nowWet, String cond) {
        List<Teil> out = new ArrayList<>();
        if (hours.isEmpty()) return out;
        List<String[]> H8 = hours.subList(0, Math.min(8, hours.size()));
        int n8 = H8.size();
        double lo = 99, hi = -99;
        for (String[] h : H8) {
            lo = Math.min(lo, z(h[2]));
            hi = Math.max(hi, z(h[2]));
        }
        int j = -1;
        if (nowWet) {
            j = erstes(H8, 0, false, rg, now);
            out.add(new Teil("Heute ", false));
            out.add(new Teil(wetName(cond), true));
            out.add(new Teil(j < 0 ? " noch mindestens bis " + nach(H8.get(n8 - 1)) : " noch bis " + uhr(H8.get(j)), false));
        } else {
            int i = erstes(H8, 0, true, rg, now);
            if (i < 0) out.add(new Teil("Heute bleibt es trocken", false));
            else {
                j = erstes(H8, i + 1, false, rg, now);
                out.add(new Teil("Heute ", false));
                out.add(new Teil(wetName(H8.get(i)[1]), true));
                String sp = j < 0 ? "ab " + uhr(H8.get(i)) : ("ab " + (int) z(H8.get(i)[0]) + " bis " + uhr(H8.get(j)));
                out.add(new Teil(" " + sp.replaceFirst("^ab 0 bis", "ab Mitternacht bis"), false));
            }
        }
        if (j >= 0) {
            int k = erstes(H8, j + 1, true, rg, now);
            if (k >= 0) out.add(new Teil(" · ab " + uhr(H8.get(k)) + " wieder", false));
        }
        out.add(new Teil(" · " + (lo == hi ? Figur.n(lo) + "°" : Figur.n(lo) + " bis " + Figur.n(hi) + "°"), false));
        return out;
    }

    private static boolean istNass(String[] h, Regen rg, long now) {
        boolean w = h.length > 3 && h[3] != null && !h[3].isEmpty() ? z(h[3]) >= 0.1 : h[1].matches(".*(rain|pour|snow|hail|lightning).*");
        return w && wahrscheinlich(rgHour(rg, (int) z(h[0]), now));
    }

    private static int erstes(List<String[]> H8, int ab, boolean nassGesucht, Regen rg, long now) {
        for (int k = ab; k < H8.size(); k++) if (istNass(H8.get(k), rg, now) == nassGesucht) return k;
        return -1;
    }

    private static String stunde(int n) { return n == 0 ? "Mitternacht" : n + " Uhr"; }

    private static String uhr(String[] h) { return stunde((int) z(h[0])); }

    private static String nach(String[] h) { return stunde(((int) z(h[0]) + 1) % 24); }

    // ---- weather icons (the page's own SVGs, 32 x 32) -------------------------------------------------------------------

    private static String wolke(String g) { return "<path d=\"M9.5 23.5h14.2a5.8 5.8 0 0 0 .7-11.55A7.6 7.6 0 0 0 9.9 12.9a5.3 5.3 0 0 0-.4 10.6z\" fill=\"url(#" + g + ")\"/>"; }

    private static String tropfen(int n, String col) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double x = 11 + i * (13.0 / (n - 1 == 0 ? 1 : n - 1));
            s.append("<line x1=\"").append(Figur.f1(x + 1.6)).append("\" y1=\"26.5\" x2=\"").append(Figur.f1(x - .6)).append("\" y2=\"30.5\" stroke=\"").append(col == null ? "#4fa0ff" : col)
                .append("\" stroke-width=\"2.2\" stroke-linecap=\"round\"/>");
        }
        return s.toString();
    }

    private static String flocken(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append("<circle cx=\"").append(Figur.f1(11 + i * (13.0 / (n - 1 == 0 ? 1 : n - 1)))).append("\" cy=\"").append(i % 2 == 1 ? "29.5" : "27.5").append("\" r=\"1.7\" fill=\"#eef4fb\"/>");
        return s.toString();
    }

    private static String sonne(double cx, double cy, double r) {
        StringBuilder s = new StringBuilder(Figur.t("<circle cx=\"$\" cy=\"$\" r=\"$\" fill=\"#ffc93c\"/>", cx, cy, r));
        for (int a = 0; a < 360; a += 45) s.append(Figur.t("<line x1=\"$\" y1=\"$\" x2=\"$\" y2=\"$\" stroke=\"#ffc93c\" stroke-width=\"2\" stroke-linecap=\"round\" transform=\"rotate($ $ $)\"/>", cx, cy - r - 4, cx, cy - r - 1.6, a, cx, cy));
        return s.toString();
    }

    private static String mond(double cx, double cy, double r) {
        return "<path d=\"M" + Figur.n(cx + r * .35) + " " + Figur.n(cy - r) + " a" + Figur.n(r) + " " + Figur.n(r) + " 0 1 0 " + Figur.n(r * .65) + " " + Figur.n(r * 1.55)
            + " a" + Figur.n(r * .85) + " " + Figur.n(r * .85) + " 0 0 1 -" + Figur.n(r * .65) + " -" + Figur.n(r * 1.55) + "z\" fill=\"#f3e7b0\"/>";
    }

    private static final String BLITZ = "<path d=\"M17.5 21 l-4 6 h3.2 l-2 5 l5.5 -7 h-3.3 l2 -4 z\" fill=\"#ffd34d\"/>";

    static String icon(String c, boolean nt) {
        String b;
        if ("sunny".equals(c) || "clear-night".equals(c)) b = nt || "clear-night".equals(c) ? mond(14, 15, 9) : sonne(16, 16, 7);
        else if ("partlycloudy".equals(c)) b = (nt ? mond(10, 10, 6.5) : sonne(11, 11, 5.5)) + wolke("wi-cl");
        else if ("cloudy".equals(c) || "fog".equals(c)) b = "<g transform=\"translate(5 -4) scale(.75)\" opacity=\".7\">" + wolke("wi-cd") + "</g>" + wolke("wi-cl")
            + ("fog".equals(c) ? "<line x1=\"7\" y1=\"27\" x2=\"25\" y2=\"27\" stroke=\"#c9d3dc\" stroke-width=\"2\" stroke-linecap=\"round\"/><line x1=\"10\" y1=\"30.5\" x2=\"22\" y2=\"30.5\" stroke=\"#c9d3dc\" stroke-width=\"2\" stroke-linecap=\"round\"/>" : "");
        else if ("pouring".equals(c)) b = wolke("wi-cd") + tropfen(4, null);
        else if ("snowy".equals(c)) b = wolke("wi-cl") + flocken(3);
        else if ("snowy-rainy".equals(c)) b = wolke("wi-cl") + tropfen(2, null) + "<circle cx=\"17.5\" cy=\"29\" r=\"1.7\" fill=\"#eef4fb\"/>";
        else if ("hail".equals(c)) b = wolke("wi-cd") + "<circle cx=\"11\" cy=\"28\" r=\"2\" fill=\"#e8f1fa\"/><circle cx=\"17\" cy=\"30\" r=\"2\" fill=\"#e8f1fa\"/><circle cx=\"23\" cy=\"28\" r=\"2\" fill=\"#e8f1fa\"/>";
        else if ("lightning".equals(c)) b = wolke("wi-cd") + BLITZ;
        else if ("lightning-rainy".equals(c)) b = wolke("wi-cd") + BLITZ
            + "<line x1=\"11.6\" y1=\"26.5\" x2=\"9.4\" y2=\"30.5\" stroke=\"#4fa0ff\" stroke-width=\"2.2\" stroke-linecap=\"round\"/><line x1=\"25.6\" y1=\"26.5\" x2=\"23.4\" y2=\"30.5\" stroke=\"#4fa0ff\" stroke-width=\"2.2\" stroke-linecap=\"round\"/>";
        else if ("windy".equals(c) || "windy-variant".equals(c)) b = ("windy-variant".equals(c) ? "<g opacity=\".8\">" + wolke("wi-cl") + "</g>" : "")
            + "<path d=\"M4 12 h15 a3.5 3.5 0 1 0 -3.5 -3.5 M4 18 h21 a3.5 3.5 0 1 1 -3.5 3.5 M4 24 h11\" stroke=\"#dfe8f2\" stroke-width=\"2.2\" fill=\"none\" stroke-linecap=\"round\"/>";
        else if ("exceptional".equals(c)) b = "<path d=\"M16 4 L29 27 H3 Z\" fill=\"#f2b45c\"/><rect x=\"14.8\" y=\"11\" width=\"2.4\" height=\"9\" rx=\"1\" fill=\"#2a1c00\"/><circle cx=\"16\" cy=\"23.5\" r=\"1.4\" fill=\"#2a1c00\"/>";
        else b = wolke("wi-cl") + tropfen(3, null);   // rainy and unknown
        return b;
    }

    private static final SvgMini.Farbe ROH = (argb, hex) -> argb;

    static void iconZeichnen(Canvas c, String cond, boolean nacht, float x, float y, float groesse) {
        c.save();
        c.translate(x, y);
        c.scale(groesse / 32f, groesse / 32f);
        new SvgMini.Lauf(c, SvgMini.KOERPER, ROH).lauf(SvgMini.parse(icon(cond, nacht)));
        c.restore();
    }

    // ---- the sheet ------------------------------------------------------------------------------------------------------

    /** Everything the sheet shows. */
    static final class Inhalt {
        List<String[]> woche = new ArrayList<>(), stunden = new ArrayList<>();
        Regen regen;
        long jetzt;
        int riseH = 7, setH = 17;
        boolean jetztNass;
        String cond = "cloudy";
        String modus = "h";              // h = Heute, w = Woche
        boolean[] markiert = new boolean[6];

        boolean leer() { return woche.isEmpty() && stunden.isEmpty(); }
    }

    /** Hit areas of the switch in sheet coordinates: {Heute, Woche}. */
    static RectF[] schalter(Paint text) {
        text.setTextSize(14);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        float wH = text.measureText("Heute") + 32, wW = text.measureText("Woche") + 32, h = 14 * 1.172f + 12;
        float rechts = BREITE - 22 - 3, top = 14 + (34 - (h + 6)) / 2 + 3;
        return new RectF[] {new RectF(rechts - wW - wH, top, rechts - wW, top + h), new RectF(rechts - wW, top, rechts, top + h)};
    }

    /** Draws the collapsed sheet (background, handle, summary, switch, the open pane) in sheet coordinates. */
    static void zeichnen(Canvas c, Inhalt in) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface normal = Typeface.create("sans-serif", Typeface.NORMAL), mittel = Typeface.create("sans-serif-medium", Typeface.NORMAL), fett = Typeface.create("sans-serif", Typeface.BOLD);
        p.setColor(0xF2141F2F);   // rgba(20,31,47,.95)
        c.drawRoundRect(new RectF(0, 0, BREITE, HOEHE), 24, 24, p);
        p.setColor(0x61FFFFFF);
        c.drawRoundRect(new RectF(BREITE / 2 - 23, 7, BREITE / 2 + 23, 12), 3, 3, p);
        // summary
        List<Teil> sum = "w".equals(in.modus) ? sumW(in.woche, in.regen, in.jetzt) : sumH(in.stunden, in.regen, in.jetzt, in.jetztNass, in.cond);
        if (sum.isEmpty()) sum.add(new Teil("w".equals(in.modus) ? "Keine Wochenvorhersage" : "Keine Stundenvorhersage", false));
        float x = 24, base = 14 + (34 - 17 * 1.172f) / 2 + .928f * 17, grenze = BREITE - 22 - 180;
        t.setTextSize(17);
        for (Teil teil : sum) {
            t.setTypeface(teil.blau ? fett : mittel);
            t.setColor(teil.blau ? BLUE : TXT);
            float w = t.measureText(teil.t);
            String s = teil.t;
            if (x + w > grenze) {
                while (s.length() > 1 && x + t.measureText(s + "…") > grenze) s = s.substring(0, s.length() - 1);
                s += "…";
            }
            c.drawText(s, x, base, t);
            x += t.measureText(s);
            if (x >= grenze) break;
        }
        // switch
        RectF[] sw = schalter(t);
        p.setColor(0x14FFFFFF);
        c.drawRoundRect(new RectF(sw[0].left - 3, sw[0].top - 3, sw[1].right + 3, sw[1].bottom + 3), 18, 18, p);
        String[] namen = {"Heute", "Woche"};
        for (int i = 0; i < 2; i++) {
            boolean an = (i == 0) == "h".equals(in.modus);
            if (an) {
                p.setColor(TXT);
                c.drawRoundRect(sw[i], 15, 15, p);
            }
            t.setTextSize(14);
            t.setTypeface(an ? mittel : normal);
            t.setColor(an ? 0xFF111111 : SUB);
            t.setTextAlign(Paint.Align.CENTER);
            c.drawText(namen[i], sw[i].centerX(), sw[i].top + 6 + .928f * 14, t);
            t.setTextAlign(Paint.Align.LEFT);
        }
        if ("w".equals(in.modus)) woche(c, in, p, t, normal, mittel, fett);
        else heute(c, in, p, t, normal, mittel);
    }

    /** Text centred at cx in a line box starting at top (CSS "line-height: normal"). */
    private static void mitte(Canvas c, Paint t, String s, float cx, float top, float groesse, int farbe, Typeface tf) {
        t.setTextSize(groesse);
        t.setTypeface(tf);
        t.setColor(farbe);
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, cx, top + .928f * groesse, t);
        t.setTextAlign(Paint.Align.LEFT);
    }

    private static final Path TROPFEN = SvgMini.pfad("M5 0C5 0 0 6 0 8a5 4 0 0 0 10 0C10 6 5 0 5 0z");

    /** Rain probability with the drop in front, centred at cx (ppTxt). */
    private static void prozent(Canvas c, Paint t, Paint p, Double v, float cx, float top, float groesse, Typeface tf) {
        if (v == null) return;
        String s = Math.round(v) + " %";
        t.setTextSize(groesse);
        t.setTypeface(tf);
        float w = 14 + t.measureText(s), x = cx - w / 2, lh = groesse * 1.172f;
        p.setColor(0xFF5AA8FF);
        c.save();
        c.translate(x, top + (lh - 12) / 2);
        c.drawPath(TROPFEN, p);
        c.restore();
        t.setColor(PROZENT);
        c.drawText(s, x + 14, top + .928f * groesse, t);
    }

    private static String glatt(float[][] pts, boolean mv) {
        StringBuilder d = new StringBuilder(mv ? "M" + Figur.f1(pts[0][0]) + " " + Figur.f1(pts[0][1]) : "");
        for (int i = 0; i < pts.length - 1; i++) {
            float[] a = pts[i > 0 ? i - 1 : i], b = pts[i], cc = pts[i + 1], e = pts[i + 2 < pts.length ? i + 2 : i + 1];
            d.append(" C").append(Figur.f1(b[0] + (cc[0] - a[0]) / 6)).append(' ').append(Figur.f1(b[1] + (cc[1] - a[1]) / 6)).append(' ').append(Figur.f1(cc[0] - (e[0] - b[0]) / 6)).append(' ')
                .append(Figur.f1(cc[1] - (e[1] - b[1]) / 6)).append(' ').append(Figur.f1(cc[0])).append(' ').append(Figur.f1(cc[1]));
        }
        return d.toString();
    }

    private static final double[][] T_SKALA = {{-5, 111, 168, 255}, {0, 154, 211, 255}, {10, 212, 226, 122}, {20, 242, 165, 74}, {30, 232, 85, 78}};

    static int tFarbe(double tt) {
        if (tt <= T_SKALA[0][0]) return WetterLage.rgb((int) T_SKALA[0][1], (int) T_SKALA[0][2], (int) T_SKALA[0][3]);
        double[] l = T_SKALA[T_SKALA.length - 1];
        if (tt >= l[0]) return WetterLage.rgb((int) l[1], (int) l[2], (int) l[3]);
        for (int i = 1; i < T_SKALA.length; i++) {
            if (tt <= T_SKALA[i][0]) {
                double[] a = T_SKALA[i - 1], b = T_SKALA[i];
                double k = (tt - a[0]) / (b[0] - a[0]);
                return WetterLage.rgb((int) Math.round(a[1] + (b[1] - a[1]) * k), (int) Math.round(a[2] + (b[2] - a[2]) * k), (int) Math.round(a[3] + (b[3] - a[3]) * k));
            }
        }
        return 0xFFFFFFFF;
    }

    /** Heute: the next 8 hours, temperature line in the colour scale, rain bars with a wavy top, probability, mm. */
    private static void heute(Canvas c, Inhalt in, Paint p, Paint t, Typeface normal, Typeface mittel) {
        if (in.stunden.isEmpty()) return;
        List<String[]> H8 = in.stunden.subList(0, Math.min(8, in.stunden.size()));
        int n = H8.size();
        float cx0 = 76, step = (847 - cx0) / Math.max(1, n - 1);
        double gmin = 99, gmax = -99, mmax = 3.4;
        for (String[] h : H8) {
            gmin = Math.min(gmin, z(h[2]));
            gmax = Math.max(gmax, z(h[2]));
            mmax = Math.max(mmax, h.length > 3 ? z(h[3]) : 0);
        }
        double span = Math.max(4, gmax - gmin);
        float[][] pts = new float[n][];
        for (int i = 0; i < n; i++) pts[i] = new float[] {cx0 + i * step, (float) (162 - (z(H8.get(i)[2]) - gmin) / span * 36)};
        for (int i = 0; i < n; i++) {   // rain bars behind the line
            String[] h = H8.get(i);
            int hh = (int) z(h[0]);
            Double pr = rgHour(in.regen, hh, in.jetzt);
            double mm = wahrscheinlich(pr) && h.length > 3 ? z(h[3]) : 0;
            if (mm >= 0.1) welle(c, pts[i][0] - 22, 44, 230, Math.max(3, Math.round(mm / mmax * 36)));
        }
        if (n > 1) {
            int[] f = new int[n];
            float[] pos = new float[n];
            for (int i = 0; i < n; i++) {
                f[i] = tFarbe(z(H8.get(i)[2]));
                pos[i] = i / (float) (n - 1);
            }
            Paint l = new Paint(Paint.ANTI_ALIAS_FLAG);
            l.setStyle(Paint.Style.STROKE);
            l.setStrokeWidth(2.5f);
            l.setShader(new LinearGradient(pts[0][0], 0, pts[n - 1][0], 0, f, pos, Shader.TileMode.CLAMP));
            c.drawPath(SvgMini.pfad(glatt(pts, true)), l);
        }
        p.setColor(TXT);
        for (float[] q : pts) c.drawCircle(Math.round(q[0]), Math.round(q[1]), 4, p);
        for (int i = 0; i < n; i++) {
            String[] h = H8.get(i);
            int hh = (int) z(h[0]);
            float x = pts[i][0];
            Double pr = rgHour(in.regen, hh, in.jetzt);
            double mm = wahrscheinlich(pr) && h.length > 3 ? z(h[3]) : 0;
            mitte(c, t, hh + " Uhr", x, 52, 14, SUB, normal);
            iconZeichnen(c, gezeigt(h[1], pr), "clear-night".equals(h[1]) || hh < in.riseH || hh >= in.setH, x - 15, 71, 30);
            mitte(c, t, h[2] + "°", x, pts[i][1] - 28, 16, TXT, normal);
            prozent(c, t, p, pr, x, 174, 13, normal);
            if (mm >= 0.1) mitte(c, t, mmKurz(mm) + " mm", x, 232, 12, MM, normal);
        }
    }

    /** Bar with a wavy top fading downwards; the phase comes from the absolute x, so the bars read as one surface. */
    private static void welle(Canvas c, float x, float w, float yb, float h) {
        float yt = yb - h, A = Math.min(3, h / 4);
        Path d = new Path();
        d.moveTo(x, yb);
        d.lineTo(x, (float) (yt + A * Math.sin(x / 14)));
        for (int k = 1; k <= 12; k++) {
            float xx = x + w * k / 12;
            d.lineTo(xx, (float) (yt + A * Math.sin(xx / 14)));
        }
        d.lineTo(x + w, yb);
        d.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, yt - A, 0, yb, new int[] {0xF25AA8FF, 0x733D7FD6, 0x0A2A5FA8}, new float[] {0, .55f, 1}, Shader.TileMode.CLAMP));
        c.drawPath(d, p);
    }

    static float wCx(int i) { return 91 + i * 148.2f; }

    private static String stTxt(double mm) { return mm >= 10 ? "stark" : (mm >= 3 ? "mäßig" : (mm >= 0.1 ? "leicht" : "trocken")); }

    /** Woche: 6 days with icon, high / low curves, probability and amount; asked-for days framed (approved mockup). */
    private static void woche(Canvas c, Inhalt in, Paint p, Paint t, Typeface normal, Typeface mittel, Typeface fett) {
        if (in.woche.isEmpty()) return;
        List<String[]> W6 = in.woche.subList(0, Math.min(6, in.woche.size()));
        int n = W6.size();
        // frames first, behind everything: neighbouring days share one frame
        for (int i = 0; i < n; i++) {
            if (!in.markiert[i] || (i > 0 && in.markiert[i - 1])) continue;
            int j = i;
            while (j + 1 < n && in.markiert[j + 1]) j++;
            RectF r = new RectF(wCx(i) - 72, 44, wCx(j) + 72, 44 + 204);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1A8FC0FF);
            c.drawRoundRect(r, 18, 18, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(0x8C8FC0FF);
            c.drawRoundRect(new RectF(r.left + .75f, r.top + .75f, r.right - .75f, r.bottom - .75f), 17.25f, 17.25f, p);
            p.setStyle(Paint.Style.FILL);
        }
        double gmin = 99, gmax = -99;
        for (String[] d : W6) {
            gmin = Math.min(gmin, z(d[3]));
            gmax = Math.max(gmax, z(d[2]));
        }
        double span = Math.max(4, gmax - gmin);
        float[][] hi = new float[n][], lo = new float[n][];
        for (int i = 0; i < n; i++) {
            hi[i] = new float[] {wCx(i), (float) (110 + (gmax - z(W6.get(i)[2])) / span * 62)};
            lo[i] = new float[] {wCx(i), (float) (110 + (gmax - z(W6.get(i)[3])) / span * 62)};
        }
        if (n > 1) {
            float[][] rueck = new float[n][];
            for (int i = 0; i < n; i++) rueck[i] = lo[n - 1 - i];
            Path band = SvgMini.pfad(glatt(hi, true) + " L" + Figur.f1(lo[n - 1][0]) + " " + Figur.f1(lo[n - 1][1]) + glatt(rueck, false) + " Z");
            p.setColor(0x1AF2B45C);
            c.drawPath(band, p);
            Paint l = new Paint(Paint.ANTI_ALIAS_FLAG);
            l.setStyle(Paint.Style.STROKE);
            l.setStrokeWidth(2.5f);
            l.setColor(AMB);
            c.drawPath(SvgMini.pfad(glatt(hi, true)), l);
            l.setColor(BLUE);
            c.drawPath(SvgMini.pfad(glatt(lo, true)), l);
        }
        p.setColor(AMB);
        for (float[] q : hi) c.drawCircle(Math.round(q[0]), Math.round(q[1]), 4.5f, p);
        p.setColor(BLUE);
        for (float[] q : lo) c.drawCircle(Math.round(q[0]), Math.round(q[1]), 4.5f, p);
        Calendar heute = Calendar.getInstance();
        heute.setTimeInMillis(in.jetzt);
        int todayWd = wochentag(heute);
        for (int i = 0; i < n; i++) {
            String[] d = W6.get(i);
            int wd = (int) z(d[0]);
            float x = wCx(i);
            Double pr = rgDay(in.regen, wd, in.jetzt);
            double mm = wahrscheinlich(pr) && d.length > 4 ? z(d[4]) : 0;
            String name = i == 0 && wd == todayWd ? "Heute" : DS[Math.floorMod(wd, 7)];
            boolean mark = in.markiert[i];
            t.setTextSize(15);
            t.setTypeface(mark ? fett : (i > 0 ? normal : mittel));
            float tw = t.measureText(name), gx = x - (tw + 8 + 28) / 2;
            t.setColor(mark ? 0xFFFFFFFF : (i > 0 ? SUB : TXT));
            c.drawText(name, gx, 52 + (28 - 15 * 1.172f) / 2 + .928f * 15, t);
            iconZeichnen(c, gezeigt(d[1], pr), false, gx + tw + 8, 52, 28);
            mitte(c, t, d[2] + "°", x, hi[i][1] - 26, 16, TXT, normal);
            mitte(c, t, d[3] + "°", x, lo[i][1] + 8, 15, SUB, normal);
            prozent(c, t, p, pr, x, 200, 15, mittel);
            mitte(c, t, (mm >= 0.1 ? mmKurz(mm) + " mm · " : "") + stTxt(mm), x, 222, 13, MM, normal);
        }
    }

    /**
     * Which columns of the week show the asked-for days (yyyy-MM-dd): column i is today + i when the first row is today
     * (one day of slack either way for a helper written around midnight). Returns {marked, not visible}.
     */
    static List<List<String>> markieren(List<String[]> woche, List<String> tage, long jetzt, boolean[] markiert) {
        List<String> ja = new ArrayList<>(), nein = new ArrayList<>();
        Calendar h = Calendar.getInstance();
        h.setTimeInMillis(jetzt);
        h.set(Calendar.HOUR_OF_DAY, 12);
        h.set(Calendar.MINUTE, 0);
        h.set(Calendar.SECOND, 0);
        h.set(Calendar.MILLISECOND, 0);
        int todayWd = wochentag(h), n = Math.min(6, woche.size()), versatz = 0;
        if (n > 0) {
            int w0 = (int) z(woche.get(0)[0]);
            versatz = w0 == todayWd ? 0 : (w0 == (todayWd + 6) % 7 ? -1 : (w0 == (todayWd + 1) % 7 ? 1 : 0));
        }
        for (String tag : tage) {
            if (tag == null || !tag.matches("\\d{4}-\\d{2}-\\d{2}")) continue;
            Calendar d = Calendar.getInstance();
            d.clear();
            d.set(Integer.parseInt(tag.substring(0, 4)), Integer.parseInt(tag.substring(5, 7)) - 1, Integer.parseInt(tag.substring(8, 10)), 12, 0, 0);
            int i = (int) Math.round((d.getTimeInMillis() - h.getTimeInMillis()) / 864e5) - versatz;
            if (i >= 0 && i < n && (int) z(woche.get(i)[0]) == wochentag(d)) {
                markiert[i] = true;
                ja.add(tag);
            } else nein.add(tag);
        }
        List<List<String>> out = new ArrayList<>();
        out.add(ja);
        out.add(nein);
        return out;
    }

    static String datum(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
