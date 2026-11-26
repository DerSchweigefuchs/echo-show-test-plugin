package echoshow.kiosktest;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Weather of the scene: the data from Home Assistant, the scene parameters per condition (ported from the dashboard's
 * weather page: clouds, rain, snow, fog, ...), the sky and land palettes and the light of the hour (night, blue hour,
 * evening / morning glow, day) blended continuously by the sun's altitude. Plain Java, colors as ARGB ints.
 */
final class WetterLage {

    // ---- data from Home Assistant (one render_template subscription) -------------------------------------------------

    static final class Daten {
        String name = "", zustand = "cloudy", einheit = "°C", windEinheit = "km/h";
        Double temp, feuchte, wind, richtung;
        Double sonneHoehe, sonneAzimut;   // sun.sun elevation / azimuth (deg)
        Boolean steigt;                   // sun.sun rising
        long aufgang, untergang;          // next_rising / next_setting, ms (0 = unknown)
        Double lat, lon;                  // zone.home, only kept in memory
        long empfangen;                   // System.currentTimeMillis() of this snapshot
        String stunden = "", woche = "", regen = "";   // input_text.wetter_stunden / woche / regen (for screensaver hint)
    }

    /** The template: weather entity (setting, or the first weather.*), sun.sun, zone.home and forecast helpers. */
    static Map<String, Object> abo(String entity) {
        String id = entity != null && entity.matches("weather\\.[a-z0-9_]{1,250}") ? entity : "";
        String d = " | default(none)";
        String tpl = "{%- set ws = states.weather | list -%}"
            + "{%- set w = (ws | selectattr('entity_id', 'eq', '" + id + "') | list | first) if '" + id + "' else (ws | first) -%}"
            + "{%- set s = states.sun | selectattr('entity_id', 'eq', 'sun.sun') | list | first -%}"
            + "{%- set z = states.zone | selectattr('entity_id', 'eq', 'zone.home') | list | first -%}"
            + "{{ {'w': ({'name': w.name, 'state': w.state, 't': w.attributes.temperature" + d + ", 'unit': w.attributes.temperature_unit" + d
            + ", 'hum': w.attributes.humidity" + d + ", 'ws': w.attributes.wind_speed" + d + ", 'wsu': w.attributes.wind_speed_unit" + d
            + ", 'wb': w.attributes.wind_bearing" + d + "} if w else none),"
            + " 'sun': ({'el': s.attributes.elevation" + d + ", 'az': s.attributes.azimuth" + d + ", 'rising': s.attributes.rising" + d
            + ", 'nr': s.attributes.next_rising" + d + ", 'ns': s.attributes.next_setting" + d + "} if s else none),"
            + " 'home': ({'lat': z.attributes.latitude" + d + ", 'lon': z.attributes.longitude" + d + "} if z else none),"
            + " 'st': states('input_text.wetter_stunden'), 'wo': states('input_text.wetter_woche'), 'rg': states('input_text.wetter_regen')} | tojson }}";
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "render_template");
        msg.put("template", tpl);
        return msg;
    }

    static Daten lesen(Object result, Anwesenheit.JsonLeser json) {
        Object o = result instanceof String ? json.lesen((String) result) : result;
        if (!(o instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) o;
        Daten d = new Daten();
        d.empfangen = System.currentTimeMillis();
        if (m.get("w") instanceof Map) {
            Map<?, ?> w = (Map<?, ?>) m.get("w");
            d.name = w.get("name") == null ? "" : String.valueOf(w.get("name"));
            d.zustand = w.get("state") == null ? "cloudy" : String.valueOf(w.get("state"));
            d.temp = zahl(w.get("t"));
            if (w.get("unit") instanceof String) d.einheit = (String) w.get("unit");
            d.feuchte = zahl(w.get("hum"));
            d.wind = zahl(w.get("ws"));
            if (w.get("wsu") instanceof String) d.windEinheit = (String) w.get("wsu");
            d.richtung = zahl(w.get("wb"));
        }
        if (m.get("sun") instanceof Map) {
            Map<?, ?> s = (Map<?, ?>) m.get("sun");
            d.sonneHoehe = zahl(s.get("el"));
            d.sonneAzimut = zahl(s.get("az"));
            d.steigt = s.get("rising") instanceof Boolean ? (Boolean) s.get("rising") : null;
            d.aufgang = Anwesenheit.zeit(s.get("nr"));
            d.untergang = Anwesenheit.zeit(s.get("ns"));
        }
        if (m.get("home") instanceof Map) {
            Map<?, ?> h = (Map<?, ?>) m.get("home");
            d.lat = zahl(h.get("lat"));
            d.lon = zahl(h.get("lon"));
        }
        d.stunden = sauberText(m.get("st"));
        d.woche = sauberText(m.get("wo"));
        d.regen = sauberText(m.get("rg"));
        return d;
    }

    private static String sauberText(Object o) {
        if (!(o instanceof String)) return "";
        String s = ((String) o).trim();
        return "unknown".equals(s) || "unavailable".equals(s) ? "" : s;
    }

    private static Double zahl(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try {
                return Double.parseDouble((String) o);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ---- scene parameters per condition (wetter.js) ------------------------------------------------------------------

    final String cond;
    int[] skyTag, skyNacht;
    int wolken;
    int ccTag = 0xFFFFFFFF, ccNacht = 0xFFFFFFFF;
    int sterne;                 // stars at night (of 70)
    int tropfen, flocken, hagel, spritzer, blaetter;
    float tropfenLaenge = 26, tropfenDauer = .55f, schraeg = 12;
    boolean sonneMoeglich, mondMoeglich, nebel, blitz, wind, schnell, dunst, stark, schnee;
    float cover, tower;
    String landTag, landNachtMond, landNachtDunkel;
    float wetterAnteil;         // how much the weather's own sky palette replaces the clear-sky light (0 clear .. .85 overcast)

    static final Map<String, int[]> SKY = new LinkedHashMap<>();
    static {
        sky("sunny", "#1b64c8", "#3f97ea", "#9fd2f6");
        sky("night", "#040817", "#0b1838", "#1b2d58");
        sky("partly", "#2f6fc4", "#629ed8", "#b3d1ea");
        sky("partlyN", "#070d22", "#13224a", "#26375f");
        sky("cloudy", "#4f5f70", "#7d8c9b", "#a7b3bf");
        sky("cloudyN", "#151b24", "#232d39", "#36424f");
        sky("rain", "#323d49", "#4d5a68", "#6e7b88");
        sky("rainN", "#0e1318", "#1b242d", "#2b3540");
        sky("pour", "#1f272f", "#333e49", "#4c5762");
        sky("pourN", "#090d11", "#131a21", "#1f2830");
        sky("hail", "#3a4452", "#5d6a7a", "#8794a3");
        sky("storm", "#17141f", "#2c2740", "#4a4361");
        sky("snow", "#7f93a8", "#a9b9c9", "#dbe3eb");
        sky("snowN", "#1f2a3d", "#34435c", "#55657e");
        sky("sleet", "#5f7083", "#8495a7", "#b3c0cc");
        sky("fog", "#7d8990", "#a2acb2", "#c6cdd1");
        sky("fogN", "#22282d", "#363e45", "#4d565d");
        sky("wind", "#3d76b8", "#6fa2d2", "#aecbe3");
        // the approved day / night mockup
        sky("abend", "#22305e", "#b8577a", "#f7a256");
        sky("blau", "#0c1a45", "#34498a", "#c98572");
        sky("morgen", "#3a5a98", "#e2939e", "#ffd49a");
    }

    private static void sky(String k, String a, String b, String c) { SKY.put(k, new int[] {hex(a), hex(b), hex(c)}); }

    WetterLage(String c) {
        cond = c == null ? "cloudy" : c;
        switch (cond) {
            case "sunny":
            case "clear-night":
                p("sunny", "night");
                sterne = 70;
                sonneMoeglich = mondMoeglich = true;
                break;
            case "partlycloudy":
                p("partly", "partlyN");
                wolken = 4;
                sterne = 40;
                ccNacht = rgb(150, 160, 180);
                sonneMoeglich = mondMoeglich = true;
                wetterAnteil = .45f;
                break;
            case "cloudy":
                p("cloudy", "cloudyN");
                wolken = 8;
                sterne = 30;
                ccNacht = rgb(90, 100, 115);
                ccTag = rgb(235, 239, 243);
                break;
            case "rainy":
                p("rain", "rainN");
                wolken = 8;
                ccNacht = rgb(70, 80, 92);
                ccTag = rgb(150, 160, 172);
                tropfen = 55;
                break;
            case "pouring":
                p("pour", "pourN");
                wolken = 9;
                ccNacht = rgb(55, 63, 74);
                ccTag = rgb(110, 120, 132);
                tropfen = 140;
                tropfenLaenge = 36;
                tropfenDauer = .42f;
                schraeg = 16;
                spritzer = 26;
                dunst = true;
                stark = true;
                break;
            case "hail":
                p("hail", "hail");
                wolken = 8;
                ccTag = ccNacht = rgb(120, 130, 145);
                hagel = 55;
                tropfen = 25;
                break;
            case "lightning":
            case "lightning-rainy":
            case "exceptional":
                p("storm", "storm");
                wolken = 8;
                ccTag = ccNacht = rgb(75, 70, 95);
                blitz = true;
                tropfen = "lightning".equals(cond) ? 0 : 80;
                break;
            case "snowy":
                p("snow", "snowN");
                wolken = 6;
                ccNacht = rgb(120, 130, 150);
                ccTag = rgb(240, 244, 248);
                flocken = 70;
                schnee = true;
                break;
            case "snowy-rainy":
                p("sleet", "snowN");
                wolken = 7;
                ccNacht = rgb(110, 120, 138);
                ccTag = rgb(205, 213, 222);
                flocken = 40;
                tropfen = 35;
                tropfenLaenge = 20;
                break;
            case "fog":
                p("fog", "fogN");
                nebel = true;
                break;
            case "windy":
            case "windy-variant":
                p("wind", "partlyN");
                wolken = "windy-variant".equals(cond) ? 7 : 4;
                wind = schnell = true;
                blaetter = 12;
                schraeg = 30;
                ccNacht = rgb(150, 160, 180);
                sonneMoeglich = mondMoeglich = true;
                wetterAnteil = .45f;
                break;
            default:
                p("cloudy", "cloudyN");
                wolken = 6;
        }
        if (!"sunny".equals(cond) && !"clear-night".equals(cond) && wetterAnteil == 0) wetterAnteil = .85f;
        cover = wolken == 0 ? 0 : (wolken < 6 ? .42f : ("cloudy".equals(cond) ? .9f : 1));
        tower = wolken == 0 ? 0 : (wolken < 6 ? .55f : ("cloudy".equals(cond) ? 1.15f : (blitz || stark ? 1.5f : 1.05f)));
        if (schnee) {
            landTag = "schnee";
            landNachtMond = landNachtDunkel = "schneeN";
        } else if (blitz) {
            landTag = landNachtMond = landNachtDunkel = "sturm";
        } else if (nebel) {
            landTag = "nebel";
            landNachtMond = landNachtDunkel = "nachtTrueb";
        } else if (stark || "hail".equals(cond)) {
            landTag = "regen";
            landNachtMond = landNachtDunkel = "nachtTrueb";
        } else {
            landTag = sonneMoeglich ? "tag" : "trueb";
            landNachtMond = mondMoeglich ? "nacht" : "nachtTrueb";
            landNachtDunkel = "nachtTrueb";
        }
    }

    private void p(String tag, String nacht) {
        skyTag = SKY.get(tag);
        skyNacht = SKY.get(nacht);
    }

    static final Map<String, String> NAME = new LinkedHashMap<>();
    static {
        String[] n = {"clear-night", "Klar", "cloudy", "Bewölkt", "exceptional", "Unwetter", "fog", "Nebel", "hail", "Hagel", "lightning", "Gewitter",
            "lightning-rainy", "Gewitter mit Regen", "partlycloudy", "Leicht bewölkt", "pouring", "Starkregen", "rainy", "Regen", "snowy", "Schnee",
            "snowy-rainy", "Schneeregen", "sunny", "Sonnig", "windy", "Windig", "windy-variant", "Windig und bewölkt"};
        for (int i = 0; i < n.length; i += 2) NAME.put(n[i], n[i + 1]);
    }

    String name() { return NAME.containsKey(cond) ? NAME.get(cond) : cond; }

    // ---- land palettes (wetter.js PAL) -------------------------------------------------------------------------------

    static final class Land {
        int far, mid, pine, lakeA, lakeB, hillA, hillB, grass, stone;

        Land(String... c) {
            far = hex(c[0]); mid = hex(c[1]); pine = hex(c[2]); lakeA = hex(c[3]); lakeB = hex(c[4]);
            hillA = hex(c[5]); hillB = hex(c[6]); grass = hex(c[7]); stone = hex(c[8]);
        }

        Land(Land a) {
            far = a.far; mid = a.mid; pine = a.pine; lakeA = a.lakeA; lakeB = a.lakeB; hillA = a.hillA; hillB = a.hillB; grass = a.grass; stone = a.stone;
        }

        static Land mix(Land a, Land b, float t) {
            Land o = new Land(a);
            o.far = WetterLage.mix(a.far, b.far, t); o.mid = WetterLage.mix(a.mid, b.mid, t); o.pine = WetterLage.mix(a.pine, b.pine, t);
            o.lakeA = WetterLage.mix(a.lakeA, b.lakeA, t); o.lakeB = WetterLage.mix(a.lakeB, b.lakeB, t);
            o.hillA = WetterLage.mix(a.hillA, b.hillA, t); o.hillB = WetterLage.mix(a.hillB, b.hillB, t);
            o.grass = WetterLage.mix(a.grass, b.grass, t); o.stone = WetterLage.mix(a.stone, b.stone, t);
            return o;
        }
    }

    static final Map<String, Land> PAL = new LinkedHashMap<>();
    static {
        PAL.put("tag", new Land("#86a6cf", "#5f84b3", "#2e5547", "#a9d0f2", "#4f86bf", "#93c064", "#47783a", "#6ea248", "#d2cdc0"));
        PAL.put("trueb", new Land("#8a96a3", "#66737f", "#34473f", "#a5b2bf", "#5b6d7f", "#819a66", "#46593b", "#64794d", "#adaaa0"));
        PAL.put("regen", new Land("#6c7682", "#525c67", "#28362f", "#7e8c99", "#43525f", "#677f52", "#36472f", "#52663f", "#8e8b84"));
        PAL.put("sturm", new Land("#4a4560", "#3a3650", "#1d2228", "#5b5873", "#2a2a3d", "#4f6447", "#26331f", "#40553a", "#77737f"));
        PAL.put("nebel", new Land("#b3bcc2", "#9aa4ab", "#6f7d7a", "#b7c0c6", "#8c989f", "#8d9c80", "#5a6a50", "#77876a", "#aaa8a1"));
        PAL.put("nacht", new Land("#24335a", "#1a2747", "#0f1a28", "#34558a", "#132544", "#2e4d4a", "#13252a", "#2d4c3c", "#5d6a80"));
        PAL.put("nachtTrueb", new Land("#2a323d", "#1e252e", "#11171d", "#3d4957", "#1b232c", "#2c3d37", "#151f1d", "#2b3c32", "#4f575f"));
        PAL.put("schnee", new Land("#c9d4df", "#a9b8c7", "#4a5d63", "#d5dfe7", "#a7b6c4", "#f1f5f8", "#c5d1dc", "#dbe4ec", "#9ca6af"));
        PAL.put("schneeN", new Land("#42516b", "#34425b", "#1a2533", "#4c5d78", "#2c3a50", "#a3b4ca", "#5c6d86", "#8a9bb2", "#56637a"));
    }

    /** Ground in the season when no snow lies (fresh in spring, yellowed in autumn, dull in a winter without snow). */
    static void saison(Land P, int monat, boolean nacht) {
        String[] gt = monat < 2 || monat == 11 ? new String[] {"#9a9468", "#55573f", ".5"} : (monat < 5 ? new String[] {"#a8d46a", "#4f8a3a", ".22"}
            : (monat < 8 ? null : new String[] {"#b8a24a", "#6a6a32", ".25"}));
        if (gt == null) return;
        float k = Float.parseFloat(gt[2]) * (nacht ? .45f : 1);
        P.hillA = mix(P.hillA, hex(gt[0]), k);
        P.hillB = mix(P.hillB, hex(gt[1]), k);
        P.grass = mix(P.grass, hex(gt[0]), k);
    }

    // ---- the light of the hour -------------------------------------------------------------------------------------

    /** Everything that depends on the sun's altitude, for one moment. */
    static final class Licht {
        double hoehe;          // sun altitude, deg
        boolean steigt;
        float tag;             // 0 night .. 1 day (land, clouds): smoothstep(-6, 0)
        float sterne;          // star visibility 0..1 (fade in from -4 deg)
        float warm, kuehl, blau;   // strength of the evening / morning / blue-hour overlays
        int[] himmel;          // 3 sky colors
        float leuchtturm;      // lighthouse lamp 0..1
    }

    Licht licht(double h, boolean steigt) {
        Licht L = new Licht();
        L.hoehe = h;
        L.steigt = steigt;
        L.tag = glatt(-6, 0, h);
        L.sterne = (float) Math.max(0, Math.min(1, (-4 - h) / 6));
        float wenigerWetter = 1 - .7f * wetterAnteil;
        float glut = (float) (h >= 0 && h <= 3 ? 1 : (h > -4 && h < 0 ? (h + 4) / 4 : (h > 3 && h < 10 ? (10 - h) / 7 : 0)));
        L.warm = steigt ? 0 : glut * wenigerWetter;
        L.kuehl = steigt ? glut * wenigerWetter : 0;
        L.blau = (float) (h >= -12 && h <= -6 ? (h + 12) / 6 : (h > -6 && h < -1 ? (-1 - h) / 5 : 0)) * wenigerWetter;
        int[] klar = klar(h, steigt);
        float wt = glatt(-8, 4, h);
        int[] wetter = {mix(skyNacht[0], skyTag[0], wt), mix(skyNacht[1], skyTag[1], wt), mix(skyNacht[2], skyTag[2], wt)};
        L.himmel = new int[] {mix(klar[0], wetter[0], wetterAnteil), mix(klar[1], wetter[1], wetterAnteil), mix(klar[2], wetter[2], wetterAnteil)};
        L.leuchtturm = glatt(-1, -5, h);
        return L;
    }

    /** Clear sky by the sun's altitude: night < -12, blue hour -6, glow 0..3 (evening or morning), day > 10. */
    static int[] klar(double h, boolean steigt) {
        int[] N = SKY.get("night"), B = SKY.get("blau"), G = SKY.get(steigt ? "morgen" : "abend"), T = SKY.get("sunny");
        if (h <= -12) return N;
        if (h < -6) return mix3(N, B, (float) ((h + 12) / 6));
        if (h < 0) return mix3(B, G, (float) ((h + 6) / 6));
        if (h <= 3) return G;
        if (h < 10) return mix3(G, T, (float) ((h - 3) / 7));
        return T;
    }

    /** Land colors for the moment: day and night palettes mixed, season, then the overlays of the hour at y. */
    Land land(Licht L, boolean mondSichtbar, int monat) {
        Land tag = new Land(PAL.get(landTag)), nacht = new Land(PAL.get(mondSichtbar ? landNachtMond : landNachtDunkel));
        if (!schnee) {
            saison(tag, monat, false);
            saison(nacht, monat, true);
        }
        return Land.mix(nacht, tag, L.tag);
    }

    // ---- overlays of the approved mockup (soft-light gradients over the scene) ---------------------------------------

    // {position (0..1 of 584 px), r, g, b, alpha}
    static final float[][] WARM = {{.20f, 255, 150, 80, 0}, {.45f, 255, 130, 70, .55f}, {1, 120, 60, 120, .5f}};
    static final float[][] KUEHL = {{.15f, 255, 190, 170, 0}, {.42f, 255, 180, 150, .45f}, {1, 140, 170, 220, .45f}};
    static final float[][] BLAU = {{.30f, 60, 80, 160, 0}, {1, 80, 90, 170, .5f}};

    /** color at scene y (0..584) with the evening / morning / blue overlays of the hour applied (soft light). */
    static int tont(int c, float y, Licht L) {
        if (L.warm > 0) c = ueber(c, WARM, y / 584f, L.warm);
        if (L.kuehl > 0) c = ueber(c, KUEHL, y / 584f, L.kuehl);
        if (L.blau > 0) c = ueber(c, BLAU, y / 584f, L.blau);
        return c;
    }

    private static int ueber(int c, float[][] g, float p, float staerke) {
        float r, gg, b, a;
        if (p <= g[0][0]) {
            r = g[0][1]; gg = g[0][2]; b = g[0][3]; a = g[0][4];
        } else if (p >= g[g.length - 1][0]) {
            float[] s = g[g.length - 1];
            r = s[1]; gg = s[2]; b = s[3]; a = s[4];
        } else {
            int i = 1;
            while (p > g[i][0]) i++;
            float[] s0 = g[i - 1], s1 = g[i];
            float t = (p - s0[0]) / (s1[0] - s0[0]);
            r = s0[1] + (s1[1] - s0[1]) * t; gg = s0[2] + (s1[2] - s0[2]) * t; b = s0[3] + (s1[3] - s0[3]) * t; a = s0[4] + (s1[4] - s0[4]) * t;
        }
        a *= staerke;
        if (a <= 0) return c;
        int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
        return (c & 0xFF000000) | (kanal(cr, r, a) << 16) | (kanal(cg, gg, a) << 8) | kanal(cb, b, a);
    }

    /** W3C soft-light of source s over backdrop b (0..255), mixed in with alpha a. */
    private static int kanal(int bi, float si, float a) {
        float b = bi / 255f, s = si / 255f, out;
        if (s <= .5f) out = b - (1 - 2 * s) * b * (1 - b);
        else {
            float D = b <= .25f ? ((16 * b - 12) * b + 4) * b : (float) Math.sqrt(b);
            out = b + (2 * s - 1) * (D - b);
        }
        return Math.round(255 * Math.max(0, Math.min(1, b + (out - b) * a)));
    }

    // ---- small helpers -----------------------------------------------------------------------------------------------

    static float glatt(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return (float) (t * t * (3 - 2 * t));
    }

    static int hex(String h) { return 0xFF000000 | Integer.parseInt(h.substring(1), 16); }

    static int rgb(int r, int g, int b) { return 0xFF000000 | (r << 16) | (g << 8) | b; }

    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        int al = Math.round(((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        return (al << 24) | (r << 16) | (g << 8) | bl;
    }

    static int[] mix3(int[] a, int[] b, float t) { return new int[] {mix(a[0], b[0], t), mix(a[1], b[1], t), mix(a[2], b[2], t)}; }

    /** f < 1 darker, f > 1 lighter towards white (figuren.js shade). */
    static int shade(int c, float f) {
        int[] ch = {(c >> 16) & 255, (c >> 8) & 255, c & 255};
        for (int i = 0; i < 3; i++) {
            float v = f <= 1 ? ch[i] * f : ch[i] + (255 - ch[i]) * (f - 1);
            ch[i] = Math.round(Math.max(0, Math.min(255, v)));
        }
        return (c & 0xFF000000) | (ch[0] << 16) | (ch[1] << 8) | ch[2];
    }

    static int alpha(int c, float a) { return (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (c & 0xFFFFFF); }

    // ---- header text -------------------------------------------------------------------------------------------------

    static String richtung(Double grad) {
        if (grad == null) return "";
        return new String[] {"N", "NO", "O", "SO", "S", "SW", "W", "NW"}[(int) (Math.round(grad / 45) % 8)];
    }

    static String uhr(long ms) {
        if (ms <= 0) return "–";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** The detail lines under the condition, like the weather page (without the forecast's high / low). */
    static List<String> zeilen(Daten d) {
        List<String> l = new java.util.ArrayList<>();
        StringBuilder a = new StringBuilder();
        if (d.feuchte != null) a.append("Luftfeuchte ").append(Math.round(d.feuchte)).append(" %");
        if (d.wind != null) {
            if (a.length() > 0) a.append(" · ");
            a.append("Wind ").append(Math.round(d.wind)).append(' ').append(d.windEinheit);
            String r = richtung(d.richtung);
            if (!r.isEmpty()) a.append(" aus ").append(r);
        }
        if (a.length() > 0) l.add(a.toString());
        if (d.aufgang > 0 || d.untergang > 0) l.add("Sonne ↑ " + uhr(d.aufgang) + " · ↓ " + uhr(d.untergang));
        return l;
    }

    /** Material Design Icon name for a weather condition (day or night), matching the screensaver. */
    static String wetterIcon(String cond, boolean nacht) {
        if (cond == null) return "mdi:weather-cloudy";
        switch (cond) {
            case "clear-night": return "mdi:weather-night";
            case "sunny": return nacht ? "mdi:weather-night" : "mdi:weather-sunny";
            case "partlycloudy": return nacht ? "mdi:weather-night-partly-cloudy" : "mdi:weather-partly-cloudy";
            case "cloudy": return "mdi:weather-cloudy";
            case "exceptional": return "mdi:alert-circle-outline";
            case "fog": return "mdi:weather-fog";
            case "hail": return "mdi:weather-hail";
            case "lightning": return "mdi:weather-lightning";
            case "lightning-rainy": return "mdi:weather-lightning-rainy";
            case "pouring": return "mdi:weather-pouring";
            case "rainy": return "mdi:weather-rainy";
            case "snowy": return "mdi:weather-snowy";
            case "snowy-rainy": return "mdi:weather-snowy-rainy";
            case "windy": return "mdi:weather-windy";
            case "windy-variant": return "mdi:weather-windy-variant";
            default: return "mdi:weather-cloudy";
        }
    }

    /** Short screensaver hint: "ab X Uhr trocken" / "Regen ab X Uhr" from wetter_stunden, or today's "↑H° ↓L°" from wetter_woche. */
    static String schonerHinweis(Daten d, WetterLage lage) {
        if (d == null) return "";
        boolean nassJetzt = istNassCond(lage.cond);
        String hint = "";
        if (!d.stunden.isEmpty()) {
            String[] eintraege = d.stunden.split(";");
            int[] regenProb = regenStunden(d.regen);
            if (nassJetzt) {
                int k = -1;
                for (int i = 0; i < eintraege.length; i++) {
                    String[] f = eintraege[i].split("\\|", -1);
                    if (f.length >= 2 && !stundeNass(f, regenProb)) { k = i; break; }
                }
                if (k < 0) hint = "noch länger";
                else {
                    String[] f = eintraege[k].split("\\|", -1);
                    hint = "ab " + stundeText(f[0]) + " trocken";
                }
            } else {
                for (int i = 0; i < eintraege.length; i++) {
                    String[] f = eintraege[i].split("\\|", -1);
                    if (f.length >= 2 && stundeNass(f, regenProb)) {
                        hint = nassName(f[1]) + " ab " + stundeText(f[0]);
                        break;
                    }
                }
            }
        }
        if (hint.isEmpty() && !d.woche.isEmpty()) {
            String[] tage = d.woche.split(";");
            if (tage.length > 0) {
                String[] f = tage[0].split("\\|", -1);
                int heuteWd = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7;
                try {
                    if (f.length >= 4 && Integer.parseInt(f[0].trim()) == heuteWd) {
                        hint = "↑" + Math.round(Double.parseDouble(f[2].trim())) + "° ↓" + Math.round(Double.parseDouble(f[3].trim())) + "°";
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return hint;
    }

    private static boolean istNassCond(String c) {
        return c != null && (c.contains("rain") || c.contains("pour") || c.contains("snow") || c.contains("hail") || c.contains("lightning"));
    }

    private static String nassName(String c) {
        if (c == null) return "Regen";
        if (c.contains("snow")) return "Schnee";
        if ("hail".equals(c)) return "Hagel";
        if (c.contains("lightning")) return "Gewitter";
        return "Regen";
    }

    private static String stundeText(String s) {
        try {
            int h = Integer.parseInt(s.trim());
            return h == 0 ? "Mitternacht" : h + " Uhr";
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private static boolean stundeNass(String[] f, int[] prob) {
        boolean nass;
        if (f.length >= 4 && !f[3].trim().isEmpty()) {
            try { nass = Double.parseDouble(f[3].trim()) >= .1; } catch (NumberFormatException e) { nass = istNassCond(f[1]); }
        } else {
            nass = istNassCond(f[1]);
        }
        if (!nass) return false;
        if (prob == null) return true;
        try {
            int h = Integer.parseInt(f[0].trim()), jetztH = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            int idx = (h - jetztH + 24) % 24;
            if (idx < prob.length && prob[idx] >= 0) return prob[idx] >= 35;
        } catch (NumberFormatException ignored) {}
        return true;
    }

    /** Parses hourly rain probabilities from input_text.wetter_regen ("YYYYMMDDHH|p0,p1,...|..."). */
    private static int[] regenStunden(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] teile = s.split("\\|", -1);
        if (teile.length < 2 || teile[0].length() < 10) return null;
        try {
            int startH = Integer.parseInt(teile[0].substring(8, 10));
            String[] vs = teile[1].split(",");
            int[] out = new int[24];
            java.util.Arrays.fill(out, -1);
            int jetztH = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            int off = (jetztH - startH + 24) % 24;
            for (int i = 0; i < 24 && off + i < vs.length; i++) {
                String v = vs[off + i].trim();
                if (!v.isEmpty()) out[i] = Integer.parseInt(v);
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
