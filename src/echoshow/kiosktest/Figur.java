package echoshow.kiosktest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The figure of the weather scene, ported from the dashboard's figuren.js: a person seen from behind, head turned to the
 * left into the distance, dressed for the weather and the season, sometimes with a dog. figurX builds the same small SVG
 * subset the page builds (paths, circles, ellipses, rects, groups with transforms and one clip path); SvgMini draws it.
 * Feet at y = 0, about 140 units tall. Plain Java.
 *
 * The pool of people holds appearance only (no names). Two wishes for the page are kept: hair is cut at the hat's
 * edge, and there is no see-through umbrella (that entry of the umbrella pool is a plain one here).
 */
final class Figur {
    private Figur() {}

    static final Map<String, String[]> SKINS = new HashMap<>();
    static final Map<String, String> HAIRS = new HashMap<>();
    static {
        SKINS.put("hell", new String[] {"#f3cfb3", "#c99a7d"});
        SKINS.put("mittel", new String[] {"#e9b896", "#b5866a"});
        SKINS.put("oliv", new String[] {"#d29a72", "#a06e4f"});
        SKINS.put("braun", new String[] {"#a8714c", "#7a4e33"});
        SKINS.put("dunkel", new String[] {"#6e4330", "#4b2d1f"});
        HAIRS.put("schwarz", "#1f1a17");
        HAIRS.put("dunkelbraun", "#3b2a20");
        HAIRS.put("braun", "#6b4a2f");
        HAIRS.put("kupfer", "#a8492a");
        HAIRS.put("blond", "#d6ae66");
        HAIRS.put("hellblond", "#e6cf96");
        HAIRS.put("grau", "#aaa59e");
        HAIRS.put("weiss", "#dedbd4");
    }

    // ---- configuration (fields of the page's cfg objects) -----------------------------------------------------------

    static final class Oben {
        String type, c, sleeve, print, streifen, c2, c3, band, hoodC, armC, punkte, gurt;
        boolean zopfmuster;
        Boolean kragen;
    }

    static final class Unten {
        String type, c, tights;
        Unten(String type, String c, String tights) { this.type = type; this.c = c; this.tights = tights; }
    }

    static final class Schuh {
        String type, c;
        Schuh(String type, String c) { this.type = type; this.c = c; }
    }

    static final class Hund {
        final String c, c2, collar;
        Hund(String c, String c2, String collar) { this.c = c; this.c2 = c2; this.collar = collar; }
    }

    static final class Schirm {
        final String c, style, c2;
        Schirm(String c, String style, String c2) { this.c = c; this.style = style; this.c2 = c2; }
    }

    static final class Cfg {
        String sex, skin, hair, hairStyle, build, kopftuchC, hat, hatC, scarf, scarf2, mitt, pose, stance, haltung, rucksack, matte, tasche,
            hutband, bommelC, muetzeStreifen, haargummi, shoeAccent, tasse, tasseBand, stockC, leineC, zeitungC;
        boolean bart, brille, ohrring, wind, hoodDown, sonnenbrilleHaar, lookUp, rucksackGross, teleskop;
        Boolean bommel;
        Oben top;
        Unten bottom;
        Schuh shoe;
        Hund dog;
        Schirm umb;
        String[] korbInhalt;

        /** Everything that changes the drawing, for the cache key. */
        String schluessel() {
            StringBuilder sb = new StringBuilder();
            Object[] v = {sex, skin, hair, hairStyle, build, kopftuchC, hat, hatC, scarf, scarf2, mitt, pose, stance, haltung, rucksack, matte, tasche, bommel,
                bommelC, muetzeStreifen, bart, brille, ohrring, wind, hoodDown, sonnenbrilleHaar, lookUp, rucksackGross, teleskop,
                top == null ? null : top.type + top.c + top.sleeve + top.print + top.streifen + top.c2 + top.c3 + top.band + top.hoodC + top.armC + top.punkte + top.gurt + top.zopfmuster + top.kragen,
                bottom == null ? null : bottom.type + bottom.c + bottom.tights, shoe == null ? null : shoe.type + shoe.c,
                dog == null ? null : dog.c + dog.c2 + dog.collar, umb == null ? null : umb.c + umb.style + umb.c2, korbInhalt == null ? null : Arrays.toString(korbInhalt)};
            for (Object o : v) sb.append(o).append('|');
            return sb.toString();
        }
    }

    static final class Person {
        final String sex, skin, hair, hairStyle, build, kopftuchC;
        final String[] frisuren;
        final boolean ohrring, bart, brille, alt;
        final Hund hund;

        Person(String sex, String skin, String hair, String hairStyle, String[] frisuren, boolean ohrring, boolean bart, boolean brille, String build, boolean alt,
               Hund hund, String kopftuchC) {
            this.sex = sex; this.skin = skin; this.hair = hair; this.hairStyle = hairStyle; this.frisuren = frisuren; this.ohrring = ohrring; this.bart = bart;
            this.brille = brille; this.build = build; this.alt = alt; this.hund = hund; this.kopftuchC = kopftuchC;
        }
    }

    private static String[] a(String... s) { return s; }

    /** The pool (13 people, coprime to 7: the page picks PERSONEN[(day * 7) % n]). Appearance only. */
    static final Person[] PERSONEN = {
        new Person("w", "hell", "blond", "zopf", a("zopf", "hoch", "zoepfe", "lang"), true, false, false, null, false, null, null),
        new Person("m", "mittel", "braun", "kurz", a("kurz", "wuschel"), false, false, false, null, false, new Hund("#8a5a36", null, "#c8584c"), null),
        new Person("w", "dunkel", "schwarz", "locken", a("locken", "puff"), true, false, false, null, false, null, null),
        new Person("m", "oliv", "schwarz", "kurz", null, false, true, false, null, false, null, null),
        new Person("w", "hell", "kupfer", "lang", a("lang", "lang", "zopf", "dutt", "hoch"), false, false, false, null, false, null, null),
        new Person("m", "hell", "grau", "glatze", null, false, false, true, "kraeftig", true, new Hund("#c8935a", null, "#3f8f88"), null),
        new Person("w", "mittel", "schwarz", "bob", null, false, false, true, null, false, null, null),
        new Person("m", "braun", "schwarz", "locken", null, false, true, false, null, false, new Hund("#2b2523", "#efe9de", "#e9b93a"), null),
        new Person("w", "oliv", "dunkelbraun", "kopftuch", null, false, false, false, null, false, null, "#8a5a78"),
        new Person("w", "hell", "weiss", "dutt", null, false, false, true, null, true, new Hund("#efe9de", "#c8935a", "#c8584c"), null),
        new Person("m", "braun", "schwarz", "rastas", null, false, false, false, null, false, null, null),
        new Person("w", "hell", "hellblond", "zoepfe", a("zoepfe", "zoepfe", "hoch", "zopf"), false, false, false, null, false, null, null),
        new Person("m", "hell", "dunkelbraun", "dutt", null, false, true, false, "kraeftig", false, null, null),
    };

    static final Map<String, String[]> KPAL = new HashMap<>();
    static {
        KPAL.put("fruehling", a("#9cc5a1", "#e8b4b8", "#a7c7e7", "#f1d38a", "#d8cfc0", "#7fa7c9", "#c9a7d8"));
        KPAL.put("sommer", a("#f3efe6", "#f2c14e", "#7fc8c4", "#e8846b", "#8fb8e0", "#f5d0c5", "#b8d98c"));
        KPAL.put("herbst", a("#b5652b", "#8c6a3f", "#5f6b3a", "#c99a3b", "#7a3b2e", "#4f5d6e", "#9a7b5a"));
        KPAL.put("winter", a("#34507d", "#7a2e3a", "#3d4a3f", "#5b5f6a", "#a33b3b", "#2f3a4f", "#e0ddd6"));
    }
    static final String[] REGEN = {"#e9b93a", "#c94a3c", "#2f4f7a", "#5f7a4a", "#3f8f88", "#d96f8a"};
    /** The page's umbrella pool; its see-through entry (7th) is a plain light blue umbrella here. */
    static final Schirm[] SCHIRM = {new Schirm("#c94a3c", null, null), new Schirm("#2f4f7a", "punkte", null), new Schirm("#1f2a36", "streifen", "#e9b93a"),
        new Schirm("#3b7fc0", null, null), new Schirm("#e9b93a", "punkte", "#f2efe6"), new Schirm("#5f7a4a", "streifen", "#f2efe6"), new Schirm("#7fa7c9", null, null),
        new Schirm("#3f8f88", "streifen", "#f2efe6"), new Schirm("#1f2a36", null, null)};

    // ---- seeded random like the page -----------------------------------------------------------------------------------

    static final class Mulberry {
        private int a;

        Mulberry(int seed) { a = seed; }

        double r() {
            a += 0x6D2B79F5;
            int t = a;
            t = (t ^ (t >>> 15)) * (t | 1);
            t ^= t + ((t ^ (t >>> 7)) * (t | 61));
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }

        String pick(String[] x) { return x[(int) Math.floor(r() * x.length)]; }

        Schirm pick(Schirm[] x) { return x[(int) Math.floor(r() * x.length)]; }
    }

    private static String[] plus(String[] x, String... y) {
        String[] o = Arrays.copyOf(x, x.length + y.length);
        System.arraycopy(y, 0, o, x.length, y.length);
        return o;
    }

    private static boolean in(String v, String... set) {
        for (String s : set) if (s.equals(v)) return true;
        return false;
    }

    /**
     * Outfit and pose for the weather class (sommer | mild | kuehl | regen | schirm | winter | winterschirm | wind) and the
     * season (fruehling | sommer | herbst | winter), seeded by the day; ort is the place of the day ("meer" here).
     * Same order of random numbers as the page, so the same day gives the same figure.
     */
    static Cfg kleidung(Person p, String klasse, double T, String saison, int seed, String ort) {
        Mulberry R = new Mulberry(seed * 7919 + 13);
        String[] pal = KPAL.containsKey(saison) ? KPAL.get(saison) : KPAL.get("herbst");
        boolean w = "w".equals(p.sex);
        Cfg c = new Cfg();
        c.sex = p.sex; c.skin = p.skin; c.hair = p.hair; c.hairStyle = p.hairStyle; c.bart = p.bart; c.brille = p.brille; c.ohrring = p.ohrring; c.build = p.build; c.kopftuchC = p.kopftuchC;
        if (p.frisuren != null) c.hairStyle = R.pick(p.frisuren);
        boolean tuch = "kopftuch".equals(p.hairStyle), hatOk = !tuch;
        String[] poses = {"haengen", "taschen", "ruecken"};
        if ("sommer".equals(klasse)) {
            if (w && R.r() < .55) {
                c.top = new Oben();
                c.top.type = "kleid";
                c.top.c = R.pick(pal);
                c.top.sleeve = tuch ? "lang" : (R.r() < .5 ? "ohne" : "kurz");
                if (R.r() < .45) c.top.punkte = "#f7f3ea";
                if (tuch) c.bottom = new Unten("rock", c.top.c, "#d9cfc2");
            } else {
                c.top = new Oben();
                c.top.type = "tshirt";
                c.top.c = R.pick(pal);
                c.top.sleeve = tuch ? "lang" : "kurz";
                if (R.r() < .3) c.top.streifen = "rgba(255,255,255,.55)";
                if (tuch) c.bottom = new Unten("rock", "#4f6a8a", "#d29a72");
                else if (w && R.r() < .35) c.bottom = new Unten("rock", R.pick(a("#3f5a7d", "#c99a3b", "#7a3b2e")), null);
                else c.bottom = new Unten("shorts", R.pick(a("#6f8fb5", "#c9b68e", "#5f6b3a", "#2f3a50")), null);
            }
            if (tuch && c.bottom == null) c.bottom = new Unten("rock", "#4f6a8a", "#d29a72");
            String st = R.r() < .5 ? "sandale" : "sneaker";
            c.shoe = new Schuh(st, R.pick(a("#8c5a3a", "#f1f1f1", "#d9c7a7")));
            if (hatOk) {
                double h = R.r();
                if (h < .4) {
                    c.hat = "sonne";
                    c.hatC = R.pick(a("#e2c27a", "#efe4cc"));
                } else if (h < .6) {
                    c.hat = "kappe";
                    c.hatC = R.pick(plus(pal, "#2f3e52"));
                } else if (h < .85) c.sonnenbrilleHaar = true;
            }
            poses = a("schauen", "haengen", "ruecken", "schauen", "huefte");
        } else if ("mild".equals(klasse)) {
            String t = "herbst".equals(saison) ? R.pick(a("pulli", "jacke", "jeansjacke", "hoodie")) : R.pick(a("jeansjacke", "jacke", "hoodie", "pulli", "trench"));
            c.top = new Oben();
            c.top.type = t;
            c.top.c = "jeansjacke".equals(t) ? "#5d7fa6" : R.pick(pal);
            if ("pulli".equals(t) && R.r() < .5) c.top.zopfmuster = true;
            c.bottom = w && R.r() < .3 ? new Unten("rock", R.pick(pal), "#2b2b33") : jeans(R);
            String st = R.r() < .7 ? "sneaker" : "boots";
            c.shoe = new Schuh(st, R.pick(a("#e6e6e6", "#f1f1f1", "#6a4a33", "#2f3a50")));
            if (hatOk && R.r() < .35) {
                c.hat = R.r() < .5 ? "kappe" : "schieber";
                c.hatC = R.pick(a("#2f3e52", "#5a4a3a", "#6b6f5a"));
            }
            if ("herbst".equals(saison) && R.r() < .4) c.scarf = R.pick(pal);
            poses = a("taschen", "ruecken", "tasse", "haengen", "huefte", "taschenWeit");
        } else if ("kuehl".equals(klasse)) {
            String t = R.pick(w ? a("mantel", "trench", "parka", "mantel") : a("mantel", "parka", "jacke", "mantel"));
            c.top = new Oben();
            c.top.type = t;
            c.top.c = R.pick("fruehling".equals(saison) ? a("#c9b68e", "#7f9a86", "#8a8f9a", "#b5856a") : a("#8a6a52", "#4f5d6e", "#6b5a3f", "#5f6b3a", "#7a3b2e", "#3f4654"));
            c.bottom = w && R.r() < .3 ? new Unten("rock", R.pick(a("#2f3a50", "#4a2f2f")), "#24242a") : (R.r() < .5 ? jeans(R) : hose(R));
            c.shoe = new Schuh("boots", R.pick(a("#5b4030", "#2b2523", "#7a5a3a")));
            c.scarf = R.pick(plus(pal, "#c8584c", "#d9a03a"));
            if (R.r() < .35) c.scarf2 = "#efe9de";
            if (hatOk) {
                double h = R.r();
                if (h < .3) {
                    c.hat = "muetze";
                    c.bommel = false;
                    c.hatC = R.pick(a("#4f5d6e", "#8c6a3f", "#7a3b2e", "#3d4a3f"));
                } else if (h < .5) {
                    c.hat = p.alt || !w ? "schieber" : "stirnband";
                    c.hatC = R.pick(a("#5a4a3a", "#4f5d6e", "#8a5a3a"));
                } else if (h < .62) {
                    c.hat = "hut";
                    c.hatC = "#3b3532";
                }
            }
            poses = a("taschen", "tasse", "ruecken", "verschraenkt", "taschenWeit");
        } else if ("regen".equals(klasse) || "schirm".equals(klasse)) {
            boolean stark = "schirm".equals(klasse), umb = R.r() < (stark ? .6 : .4);
            String t = umb ? R.pick(a("trench", "mantel", "parka", "regenkurz", "regen", "jacke")) : R.pick(a("regen", "regenkurz", "poncho", "parka", "regenkurz", "trench"));
            boolean bunt = in(t, "regen", "regenkurz", "poncho"), kap = bunt || "parka".equals(t);
            c.top = new Oben();
            c.top.type = t;
            c.top.c = bunt ? R.pick(REGEN) : R.pick(a("#c9b68e", "#8a6a52", "#3f4654", "#5f6b3a", "#4f5d6e", "#7a3b2e"));
            if (umb) c.umb = R.pick(SCHIRM);
            if (!umb && R.r() < .4) c.scarf = R.pick(pal);
            if (hatOk) {
                String h = R.pick(umb ? a("", "", "hut", "schieber", "kappe", "muetze", "baske", "fischer") : a("kapuze", "suedwester", "fischer", "kappe", "muetze", "hut", "schieber", "baske", "", "suedwester"));
                c.hat = "kapuze".equals(h) && !kap ? "fischer" : (h.isEmpty() ? null : h);
                if ("baske".equals(c.hat) && !w && !p.alt) c.hat = "schieber";
                if (c.hat != null) {   // the page builds the whole colour table (all random numbers drawn), then takes one
                    String sw = R.r() < .6 ? "#e9b93a" : c.top.c, fi = R.pick(a("#3f4654", "#5f6b3a", "#c9b68e", "#2f4f7a", "#8a6a52")), ka = R.pick(a("#2f3e52", "#1f2a36", "#5f6b3a", "#7a3b2e"));
                    String mu = R.pick(plus(pal, "#2f3e52", "#c8584c")), sch = R.pick(a("#5a4a3a", "#4f5d6e", "#6b6f5a")), ba = R.pick(a("#7a2e3a", "#2f3a4f", "#3d4a3f", "#c8584c"));
                    Map<String, String> m = new HashMap<>();
                    m.put("suedwester", sw); m.put("fischer", fi); m.put("kappe", ka); m.put("muetze", mu); m.put("hut", "#3b3532"); m.put("schieber", sch); m.put("baske", ba); m.put("kapuze", null);
                    c.hatC = m.get(c.hat);
                }
                if ("muetze".equals(c.hat)) c.bommel = R.r() < .4;
            }
            if (kap && !"parka".equals(t) && !"kapuze".equals(c.hat) && R.r() < .7) c.hoodDown = true;
            c.bottom = R.r() < .5 ? jeans(R) : hose(R);
            String st = R.r() < .75 ? "gummi" : "boots";
            c.shoe = new Schuh(st, R.pick(a("#2f6b55", "#c94a3c", "#1f2a36", "#e9b93a", "#4a4a50", "#5b4030")));
            if ("poncho".equals(t)) poses = a("poncho");
            else if (umb) poses = a("schirm", "schirmTaschen", "schirmSchulter");
            else {
                List<String> pl = new java.util.ArrayList<>(Arrays.asList("taschen", "taschenWeit", "huefte", "haengen", "verschraenkt"));
                if (!stark) pl.add("handraus");
                if (c.hat == null && !stark && hatOk) {
                    pl.add("zeitung");
                    pl.add("zeitung");
                }
                if (in(String.valueOf(c.hat), "hut", "suedwester", "fischer", "schieber")) pl.add("hutfest");
                poses = pl.toArray(new String[0]);
            }
        } else if ("winter".equals(klasse) || "winterschirm".equals(klasse)) {
            String t = R.pick(w ? a("daunelang", "daune", "parka") : a("daune", "parka", "daune"));
            c.top = new Oben();
            c.top.type = t;
            c.top.c = R.pick(a("#34507d", "#7a2e3a", "#2f3a4f", "#3d4a3f", "#a33b3b", "#e0ddd6", "#5b5f6a"));
            c.bottom = R.r() < .5 ? hose(R) : jeans(R);
            c.shoe = new Schuh("schnee", R.pick(a("#6a4a33", "#3a3a40", "#8c6a4a")));
            c.scarf = R.pick(a("#c8584c", "#d9a03a", "#efe9de", "#3f8f88", "#7a3b2e"));
            if (R.r() < .4) c.scarf2 = R.pick(a("#efe9de", "#2f3a4f"));
            c.mitt = R.pick(a("#c8584c", "#efe9de", "#3f8f88", "#2f3a4f", c.scarf));
            if (hatOk) {
                double h = R.r();
                if (h < .7 || "winterschirm".equals(klasse)) {
                    c.hat = "muetze";
                    c.hatC = R.r() < .5 ? c.scarf : R.pick(a("#c8584c", "#2f3e52", "#efe9de", "#3d4a3f"));
                    if (R.r() < .35) c.muetzeStreifen = "#efe9de";
                    c.bommelC = R.pick(a("#efe9de", "#c8584c", "#f1e6c8"));
                } else if (w) {
                    c.hat = R.r() < .5 ? "ohren" : "stirnband";
                    c.hatC = R.pick(a("#efe9de", "#c8584c", "#9a7b5a", "#3f8f88"));
                } else {
                    c.hat = "muetze";
                    c.bommel = false;
                    c.hatC = R.pick(a("#2f3e52", "#5b5f6a", "#7a3b2e"));
                }
            }
            if ("winterschirm".equals(klasse)) {
                c.umb = R.pick(SCHIRM);
                poses = a("schirm", "schirmTaschen", "schirmSchulter");
            } else poses = a("taschen", "verschraenkt", "tasse", "haengen", "taschenWeit");
        } else if ("wind".equals(klasse)) {
            c.top = new Oben();
            c.top.type = R.pick(a("wind", "parka", "jacke"));
            c.top.c = R.pick(a("#4f6f96", "#3f8f88", "#c94a3c", "#5f6b3a", "#2f3a4f"));
            c.top.c2 = R.pick(a("#e9b93a", "#e9e4d8", "#7fc8c4"));
            c.bottom = R.r() < .5 ? jeans(R) : hose(R);
            String st = R.r() < .5 ? "sneaker" : "boots";
            c.shoe = new Schuh(st, R.pick(a("#e6e6e6", "#5b4030")));
            c.scarf = R.pick(a("#d9a03a", "#c8584c", "#3f8f88"));
            c.wind = true;
            if (hatOk && R.r() < .5) {
                c.hat = R.pick(a("kappe", "hut", "schieber"));
                c.hatC = R.pick(a("#2f3e52", "#3b3532", "#5a4a3a"));
                poses = a("hutfest");
            } else poses = a("taschen", "verschraenkt");
        }
        if (c.bottom == null) c.bottom = jeans(R);
        if (tuch || "rastas".equals(c.hairStyle)) {
            c.scarf = null;
            c.scarf2 = null;
        }
        if (tuch && c.top != null && in(String.valueOf(c.top.sleeve), "kurz", "ohne")) c.top.sleeve = "lang";
        boolean cape = "poncho".equals(c.top.type);
        if (p.alt && c.umb == null && !cape && R.r() < .5) poses = a("stock");
        if (p.hund != null && R.r() < .6) {
            c.dog = p.hund;
            if (c.umb == null && !cape && !"wind".equals(klasse)) poses = a("leine");
        }
        if (c.umb == null && !"wind".equals(klasse) && !"sommer".equals(klasse) && R.r() < .25) c.rucksack = R.pick(a("#3f5a4a", "#7a3b2e", "#2f3a4f", "#c99a3b"));
        else if (c.umb == null && R.r() < .25) c.tasche = R.pick(a("#8a5a36", "#3b3532", "#c99a3b"));
        boolean cape2 = "poncho".equals(c.top.type);
        if ("meer".equals(ort)) {
            c.rucksack = null;
            c.rucksackGross = false;
            if (c.umb == null && !cape2 && c.dog == null && R.r() < .35) poses = a("fernglas");
        }
        c.pose = R.pick(poses);
        if (c.rucksack != null && "ruecken".equals(c.pose)) c.pose = "taschen";
        double sr = R.r(), hr = R.r();
        boolean nass = in(klasse, "regen", "schirm", "winter", "winterschirm", "wind");
        c.stance = sr < .28 ? "locker" : (sr < .42 && !"wind".equals(klasse) ? "gekreuzt" : (sr < .58 && !"sommer".equals(klasse) ? "breit" : ""));
        if (in(c.pose, "stock", "leine")) c.stance = "";
        if ("gekreuzt".equals(c.stance) && in(c.shoe.type, "gummi", "schnee")) c.stance = "locker";
        c.haltung = nass ? (hr < .3 ? "geduckt" : (hr < .42 ? "aufrecht" : "")) : (hr < .2 ? "aufrecht" : (hr < .34 && "locker".equals(c.stance) ? "schief" : ""));
        return c;
    }

    private static Unten jeans(Mulberry R) { return new Unten("jeans", R.pick(a("#34486a", "#3f5a7d", "#2b3446", "#5b6f8f")), null); }

    private static Unten hose(Mulberry R) { return new Unten("hose", R.pick(a("#2f3a50", "#4a4237", "#5a5f52", "#272e3d", "#7b6a55")), null); }

    // ---- the page's choice for the scene (wetter.js) ----------------------------------------------------------------------

    /**
     * Figure of the day for the weather scene: person PERSONEN[(day * 7) % n], clothes by temperature and weather, by day
     * binoculars, on a clear night the telescope, looking up in a moonlit night, no shading the eyes without sun.
     */
    static Cfg fuerSzene(int tagNr, int monat, String cond, double T, boolean nacht, boolean sonne, boolean mondhell, boolean starkerRegen) {
        boolean snowy = "snowy".equals(cond), windy = in(cond, "windy", "windy-variant");
        String outfit = "snowy-rainy".equals(cond) ? "winterschirm" : (snowy ? "winter"
            : (starkerRegen || in(cond, "hail", "lightning-rainy", "exceptional") ? (T <= 3 ? "winterschirm" : "schirm")
            : (in(cond, "rainy", "lightning") ? (T <= 3 ? "winterschirm" : "regen")
            : (windy ? (T <= 3 ? "winter" : "wind")
            : (T <= 3 ? "winter" : (T >= 22 && !nacht && (sonne || "partlycloudy".equals(cond)) ? "sommer" : (T >= 15 ? "mild" : "kuehl")))))));
        String saison = monat < 2 || monat == 11 ? "winter" : (monat < 5 ? "fruehling" : (monat < 8 ? "sommer" : "herbst"));
        Person p = PERSONEN[Math.floorMod(tagNr * 7, PERSONEN.length)];
        Cfg c = kleidung(p, "mild".equals(outfit) && T >= 21 ? "sommer" : outfit, T, saison, tagNr, "meer");
        if ("schauen".equals(c.pose) && !sonne) c.pose = "ruecken";
        if (nacht && "sonne".equals(c.hat)) c.hat = null;
        if (nacht && mondhell) c.lookUp = true;
        boolean klar = in(cond, "clear-night", "sunny");
        c.teleskop = nacht && klar && c.umb == null;
        if (c.teleskop && in(c.pose, "stoecke", "korb", "zeitung", "fernglas", "schauen")) c.pose = "taschen";
        // binoculars at the sea on sunny days (the page: every other day, as in the approved mockup)
        if ("sunny".equals(cond) && !nacht && c.umb == null && c.dog == null && !"poncho".equals(c.top.type) && Math.floorMod(tagNr, 2) == 0) c.pose = "fernglas";
        return c;
    }

    // ---- drawing: the page's figurX, as SVG ------------------------------------------------------------------------------

    /** Compact number like JavaScript prints it. */
    static String n(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return String.valueOf((long) v);
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** Number.prototype.toFixed(1): rounds the exact binary value, half away from zero (Java's formatter would differ at x.x5). */
    static String f1(double v) {
        String r = new java.math.BigDecimal(v).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
        return "-0.0".equals(r) ? "0.0" : r;
    }

    /** Template: each $ takes the next number. */
    static String t(String fmt, double... v) {
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (int i = 0; i < fmt.length(); i++) {
            char ch = fmt.charAt(i);
            if (ch == '$') sb.append(n(v[k++]));
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static String P(double[] pt) { return f1(pt[0]) + " " + f1(pt[1]); }

    private static String L(String d, String col, double sw) { return L(d, col, sw, ""); }

    private static String L(String d, String col, double sw, String ex) {
        return "<path d=\"" + d + "\" stroke=\"" + col + "\" stroke-width=\"" + n(sw) + "\" stroke-linecap=\"" + (ex.contains("dasharray") ? "butt" : "round")
            + "\" stroke-linejoin=\"round\" fill=\"none\"" + ex + "/>";
    }

    private static String F(String d, String col) { return "<path d=\"" + d + "\" fill=\"" + col + "\"/>"; }

    private static String circ(double cx, double cy, double r, String fill) { return "<circle cx=\"" + n(cx) + "\" cy=\"" + n(cy) + "\" r=\"" + n(r) + "\" fill=\"" + fill + "\"/>"; }

    static String shade(String hex, double f) { return String.format(Locale.ROOT, "#%06x", WetterLage.shade(WetterLage.hex(hex), (float) f) & 0xFFFFFF); }

    private static double[] pt(double x, double y) { return new double[] {x, y}; }

    static String figurX(Cfg c) {
        String[] SK = SKINS.containsKey(c.skin) ? SKINS.get(c.skin) : SKINS.get("mittel");
        String SKIN = SK[0], SH = SK[1];
        String HC = HAIRS.containsKey(c.hair) ? HAIRS.get(c.hair) : (c.hair != null ? c.hair : HAIRS.get("dunkelbraun")), HD = shade(HC, .72), HL = "rgba(255,255,255,.16)";
        String RIM = "rgba(255,232,205,.45)", DARK = "rgba(0,0,0,.2)";
        boolean W = "w".equals(c.sex), wind = c.wind;
        String pose = c.pose != null ? c.pose : "haengen";
        Oben top = c.top;
        Unten bot = c.bottom != null ? c.bottom : new Unten("jeans", "#34486a", null);
        Schuh shoe = c.shoe != null ? c.shoe : new Schuh("sneaker", "#e6e6e6");
        double fat = "kraeftig".equals(c.build) ? 2 : 0;
        double[] Bsh = W ? new double[] {-10, 11.5} : new double[] {-12, 13.5}, Bwa = W ? new double[] {-8.5, 10} : new double[] {-11 - fat, 12 + fat};
        double[] Bhi = W ? new double[] {-12, 13} : new double[] {-11 - fat * .5, 12 + fat * .5}, Bleg = W ? new double[] {9.5, 7.6} : new double[] {10, 8.6};
        double Barm = W ? 6.2 : 7, BsY = -101;
        boolean puffy = in(top.type, "daune", "daunelang");
        double px = puffy ? 2.5 : (in(top.type, "parka", "mantel", "trench") ? 1 : 0);
        StringBuilder s = new StringBuilder();
        s.append("<ellipse cx=\"").append(c.dog != null ? -14 : 0).append("\" cy=\"1\" rx=\"").append(c.dog != null ? 40 : 22).append("\" ry=\"4\" fill=\"rgba(0,0,0,.28)\"/>");

        // ---- dog (sits left of the figure, looks into the distance too)
        double[] leashTo = null;
        if (c.dog != null) {
            String dc = c.dog.c != null ? c.dog.c : "#8a5a36", dd = shade(dc, .7), dl = c.dog.c2 != null ? c.dog.c2 : shade(dc, 1.35);
            double x = -44;
            s.append("<g class=\"wf-tail\">").append(L(t("M$ -3 Q$ -2 $ -9", x + 7, x + 17, x + 19), dc, 3.4)).append("</g>");
            s.append(F(t("M$ 0 Q$ -12 $ -19 Q$ -24 $ -18 Q$ -10 $ 0 Z", x - 8, x - 11, x - 6, x, x + 5, x + 10, x + 9), dc))
                .append(F(t("M$ -21 Q$ -10 $ 0 L$ 0 Q$ -10 $ -21Z", x + 2, x + 10, x + 9, x + 3, x + 6, x + 2), "rgba(0,0,0,.18)"));
            s.append(F(t("M$ 0 Q$ -6 $ -8 L$ 0Z", x - 7, x - 8, x - 4, x - 3), dl));
            s.append(F(t("M$ -24 Q$ -31 $ -31 Q$ -31 $ -25 Q$ -19 $ -19 Q$ -19 $ -24Z", x - 9, x - 9, x - 3, x + 3, x + 3, x + 2, x - 4, x - 9, x - 9), dc));
            s.append(F(t("M$ -25 L$ -23.5 Q$ -21 $ -20.5 L$ -20Z", x - 8, x - 15, x - 16, x - 13, x - 6), dc)).append(circ(x - 15.2, -23, 1.2, "#1d1715"));
            s.append(F(t("M$ -30 Q$ -30 $ -24 Q$ -21 $ -23 Q$ -26 $ -30Z", x - 1, x + 4, x + 4, x + 2, x, x - 2, x - 1), dd));
            s.append(L(t("M$ -19.5 Q$ -17.5 $ -20", x - 8, x - 3, x + 2), c.dog.collar != null ? c.dog.collar : "#c8584c", 2));
            leashTo = pt(x - 3, -18.5);
        }

        // ---- legs
        double[] nH = pt(-4, -62), nK = pt(-5, -33), nA = pt(-6, -5), fH = pt(5, -62), fK = pt(5.5, -33), fA = pt(6, -5);
        double nTilt = 0;
        if ("locker".equals(c.stance)) { nK = pt(-7, -34); nA = pt(-8.5, -6.5); nTilt = -7; }
        if ("breit".equals(c.stance) || (wind && !"locker".equals(c.stance))) { nK = pt(-8, -33); nA = pt(-11, -5); fK = pt(8.5, -33); fA = pt(12, -5); }
        if ("gekreuzt".equals(c.stance) && !wind) { nK = pt(-1.5, -33); nA = pt(8, -5); fK = pt(3.5, -33); fA = pt(-.5, -5); nTilt = 4; }
        boolean legSkin = in(bot.type, "shorts", "rock") || "kleid".equals(top.type);
        s.append(bein(fH, fK, fA, true, legSkin, bot, SKIN, Bleg, DARK)).append(schuh(fA, true, 0, shoe, SKIN, c));
        s.append(bein(nH, nK, nA, false, legSkin, bot, SKIN, Bleg, DARK)).append(schuh(nA, false, nTilt, shoe, SKIN, c));
        int up0 = s.length();   // everything from here on is the upper body (posture: c.haltung)
        if ("rock".equals(bot.type) && !"kleid".equals(top.type))
            s.append(F(t("M-11 -64 L13 -64 L$ -40 Q2 -37 -14 -40 Z", 16 + (wind ? 4 : 0)), bot.c)).append(F("M4 -64 L13 -64 L16 -40 L6 -38.5Z", "rgba(0,0,0,.14)"))
                .append(L("M-2 -62 L-4 -40 M7 -62 L9 -39", "rgba(0,0,0,.12)", 1));

        // ---- arms: shoulder -> elbow -> wrist, per pose
        double[] nS = pt(Bsh[0] + 2.5, BsY), fS = pt(Bsh[1] - 2.5, BsY + 1);
        double[][] ARM = arme(pose);
        double dx = W ? 1.5 : 0;
        boolean ruecken = "ruecken".equals(pose);
        double[] nE = pt(ARM[0][0] + dx, ARM[0][1]), nW = pt(ARM[1][0] + (ruecken ? 0 : dx), ARM[1][1]), fE = pt(ARM[2][0] - dx, ARM[2][1]), fW = pt(ARM[3][0] - (ruecken ? 0 : dx), ARM[3][1]);
        String sleeve = top.sleeve != null ? top.sleeve : "lang";
        double aw = puffy ? Barm + 2.4 : (px != 0 ? Barm + .8 : Barm);
        String hand = c.mitt != null ? c.mitt : SKIN, armC = top.armC != null ? top.armC : top.c;
        int[] hidden = versteckt(pose);
        boolean poncho = "poncho".equals(top.type);
        Arm arm = new Arm(sleeve, armC, aw, SKIN, Barm, hand, c.mitt != null, DARK);

        // umbrella (far hand): canopy rim just above the head / hat, flat dome
        if (c.umb != null) {
            double x = 13;
            String uc = c.umb.c, st = c.umb.style != null ? c.umb.style : "uni", SC = "#3e444c";
            double R = "muetze".equals(c.hat) && (c.bommel == null || c.bommel) ? -150 : -146, H = 27, W2 = 42, Tp = R - H, sc = 4.5;
            StringBuilder u = new StringBuilder();
            u.append(L(t("M$ -92 L$ $", x, x, Tp + 3), SC, 2.2)).append(L(t("M$ -96 Q$ -88 $ -88", x, x, x + 4), SC, 2.4));
            String rimBack = " Q" + f1(x + W2 * .75) + " " + f1(R - sc) + " " + f1(x + W2 * .5) + " " + f1(R) + " Q" + f1(x + W2 * .25) + " " + f1(R - sc) + " " + n(x) + " " + f1(R)
                + " Q" + f1(x - W2 * .25) + " " + f1(R - sc) + " " + f1(x - W2 * .5) + " " + f1(R) + " Q" + f1(x - W2 * .75) + " " + f1(R - sc) + " " + f1(x - W2) + " " + f1(R);
            u.append("<path d=\"M").append(f1(x - W2)).append(' ').append(f1(R)).append(" Q").append(f1(x - W2 * .87)).append(' ').append(f1(Tp + 2)).append(' ').append(n(x)).append(' ').append(n(Tp))
                .append(" Q").append(f1(x + W2 * .87)).append(' ').append(f1(Tp + 2)).append(' ').append(f1(x + W2)).append(' ').append(f1(R)).append(rimBack).append("Z\" fill=\"").append(uc).append("\"/>");
            if ("streifen".equals(st)) u.append(F("M" + f1(x - W2 * .5) + " " + f1(R) + " Q" + f1(x - W2 * .25) + " " + f1(R - sc) + " " + n(x) + " " + f1(R) + " L" + n(x) + " " + n(Tp) + " Q"
                + f1(x - W2 * .39) + " " + f1(Tp + 5) + " " + f1(x - W2 * .5) + " " + f1(R) + "Z M" + f1(x + W2 * .5) + " " + f1(R) + " Q" + f1(x + W2 * .75) + " " + f1(R - sc) + " "
                + f1(x + W2) + " " + f1(R) + " Q" + f1(x + W2 * .87) + " " + f1(Tp + 5) + " " + n(x) + " " + n(Tp) + " Q" + f1(x + W2 * .43) + " " + f1(Tp + 7) + " " + f1(x + W2 * .5) + " " + f1(R) + "Z",
                c.umb.c2 != null ? c.umb.c2 : "#f2efe6"));
            if ("punkte".equals(st)) {
                double[][] rows = {{.3, 2.3}, {.6, 2.1}, {.84, 1.7}};
                double[][] ks = {{-.75, -.25, .25, .75}, {-.6, 0, .6}, {-.42, .42}};
                for (int ri = 0; ri < 3; ri++) {
                    double hh = rows[ri][0], wv = wAt(hh, R, Tp, H) * .9;
                    for (double k : ks[ri]) u.append(circ(Double.parseDouble(f1(x + W2 * k * wv)), Double.parseDouble(f1(R - H * hh)), rows[ri][1], c.umb.c2 != null ? c.umb.c2 : "#f2efe6"));
                }
            }
            u.append(F("M" + n(x) + " " + n(Tp) + " Q" + f1(x + W2 * .87) + " " + f1(Tp + 2) + " " + f1(x + W2) + " " + f1(R) + " Q" + f1(x + W2 * .75) + " " + f1(R - sc) + " "
                + f1(x + W2 * .5) + " " + f1(R) + " Q" + f1(x + W2 * .48) + " " + f1(Tp + 7) + " " + n(x) + " " + n(Tp) + "Z", "rgba(0,0,0,.16)"));
            u.append(L("M" + n(x) + " " + n(Tp) + " Q" + f1(x - W2 * .43) + " " + f1(Tp + 7) + " " + f1(x - W2 * .5) + " " + f1(R) + " M" + n(x) + " " + n(Tp) + " Q" + f1(x + W2 * .43) + " "
                + f1(Tp + 7) + " " + f1(x + W2 * .5) + " " + f1(R), "rgba(0,0,0,.18)", 1.1));
            u.append(L("M" + f1(x - W2 * .83) + " " + f1(R - H * .4) + " Q" + f1(x - W2 * .57) + " " + f1(R - H * .86) + " " + f1(x - W2 * .17) + " " + f1(R - H * .93), "rgba(255,255,255,.22)", 2))
                .append(circ(x, Double.parseDouble(f1(Tp - 1.4)), 1.8, SC));
            s.append("schirmSchulter".equals(pose) ? "<g transform=\"translate(-10 14) rotate(30 13 -96)\">" + u + "</g>" : "<g class=\"wf-umb\">" + u + "</g>");
        }

        // far arm (behind the body)
        String POLE = "#59606a";
        if ("stoecke".equals(pose)) s.append(L(t("M$ $ L$ -1", fW[0] + .5, fW[1] + 2, fW[0] + 6), POLE, 1.5)).append(L(t("M$ $ L$ -1", fW[0] + .5, fW[1] + 2, fW[0] + 6), DARK, 1.5))
            .append(t("<ellipse cx=\"$\" cy=\"-4\" rx=\"2.6\" ry=\".8\" fill=\"#2b2b2b\"/>", fW[0] + 5.6));
        if (!poncho) s.append(arm.svg(fS, fE, fW, true, hidden[1] == 1 || ruecken, in(pose, "verschraenkt", "fernglas")));
        if (c.umb != null && !"schirmSchulter".equals(pose)) s.append(circ(13, -96, c.mitt != null ? 3.9 : 3.3, hand)).append(circ(13, -96, 3.4, DARK));

        // ---- torso / garment
        double sL = Bsh[0] - px, sR = Bsh[1] + px, wL = Bwa[0] - px, wR = Bwa[1] + px, hL = Bhi[0] - px, hR = Bhi[1] + px;
        String T = top.type, tc = top.c;
        double hem = saum(T), flare = weite(T, W), flW = wind ? 4 : 0, hemBot = hem;
        String body = "kleid".equals(T)
            ? t("M$ -105 Q1 -109 $ -104 Q$ -98 $ -94 L$ -80 L$ $ Q$ $ $ $ L$ -80 L$ -94 Q$ -98 $ -105Z", sL + 1, sR - 1, sR, sR - 2, wR, hR + flare + flW, hemBot, (hR + hL) / 2, hemBot + 3,
                hL - flare, hemBot, wL, sL + 2, sL, sL + 1)
            : t("M$ -105 Q1 -110 $ -104 Q$ -98 $ -94 L$ -80 Q$ -70 $ -62 L$ $", sL, sR, sR + 1.5, sR - .5, wR, hR + .5, hR, hR + flare + flW, hemBot)
                + (flW != 0 ? t(" L$ $", hR + flare - 2, hemBot - 3) : "")
                + t(" Q1 $ $ $ L$ -62 Q$ -70 $ -80 L$ -94 Q$ -98 $ -105Z", hemBot + 3, hL - flare, hemBot, hL, wL - .5, wL, sL + .5, sL - 1.5, sL);
        if (poncho) {
            String pb = t("M-7 -108 Q1 -111.5 10 -108 Q$ -104 $ -96 Q$ -76 $ $ Q$ $ $ $ Q$ $ $ $ Q$ -76 $ -96 Q$ -104 -7 -108Z", sR + 4, sR + 6, sR + 13, sR + 15, hemBot, sR + 6, hemBot + 4,
                (sR + sL) / 2 + 4, hemBot + 1, sL, hemBot + 4, sL - 14, hemBot, sL - 12, sL - 5, sL - 3);
            s.append(F(pb, tc)).append(F(pb, "url(#wf-rd)")).append(L(t("M1 -104 L2 $", hemBot + 1), "rgba(0,0,0,.14)", 1))
                .append(L(t("M$ -94 Q$ -76 $ $ M$ -94 Q$ -76 $ $", sR + 5, sR + 10, sR + 11, hemBot + 1, sL - 4, sL - 9, sL - 10, hemBot + 1), "rgba(0,0,0,.12)", 1.6))
                .append(L(t("M$ -97 Q$ -78 $ $", sL - 2, sL - 8.5, sL - 10.5, hemBot - 2), "rgba(255,255,255,.28)", 1.6))
                .append(L(t("M$ $ Q1 $ $ $", sL - 13, hemBot - 3, hemBot - 1, sR + 14, hemBot - 3), top.band != null ? top.band : "#d9dcd8", 2));
        } else if (puffy) {
            StringBuilder q = new StringBuilder(t("M$ -105 Q1 -110 $ -104", sL, sR));
            java.util.List<Double> yy = new java.util.ArrayList<>();
            for (double y = -96; y <= hemBot - 1; y += 8) yy.add(y);
            for (double y : yy) q.append(t(" Q$ $ $ $", sR + 2.5, y - 4, sR - .5, y));
            q.append(t(" L$ $ Q1 $ $ $", sR + flare - .5, hemBot, hemBot + 3, sL - flare + .5, hemBot));
            for (int i = yy.size() - 1; i >= 0; i--) { double y = yy.get(i); q.append(t(" L$ $ Q$ $ $ $", sL + .5, y, sL - 2.5, y - 4, sL, y - 8)); }
            q.append(" Z");
            s.append(F(q.toString(), tc)).append(F(q.toString(), "url(#wf-rd)"));
            for (double y : yy) s.append(L(t("M$ $ Q1 $ $ $", sL + 1, y, y + 2.5, sR - 1, y), "rgba(0,0,0,.2)", 1.3)).append(L(t("M$ $ Q1 $ $ $", sL + 2, y - 4, y - 1.5, sR - 2, y - 4), "rgba(255,255,255,.1)", 2.2));
        } else s.append(F(body, tc)).append(F(body, "url(#wf-rd)"));
        s.append(L(t("M$ -101 Q$ -96 $ -80", sL + .5, sL - 1.5, wL), RIM, .9));
        // garment details
        if (in(T, "tshirt", "tank")) s.append(L(t("M$ $ Q1 $ $ $", hL + 1, hemBot - 1.2, hemBot + 1.5, hR - 1, hemBot - 1.2), "rgba(0,0,0,.14)", 1)).append(L("M-2 -96 Q2 -84 0 -66", "rgba(0,0,0,.08)", 2.5));
        if ("tshirt".equals(T) && top.print != null) s.append(circ(2, -90, 5.5, top.print)).append(L("M-3 -82 L7 -82", top.print, 1.4));
        if ("tshirt".equals(T) && top.streifen != null) for (double y = -100; y < hemBot - 2; y += 6) s.append(L(t("M$ $ Q1 $ $ $", sL + 1.5, y, y + 1.5, sR - 1, y), top.streifen, 2.2));
        if (in(T, "pulli", "hoodie")) {
            s.append("<path d=\"").append(t("M$ $ Q1 $ $ $ L$ $ Q1 $ $ $Z", hL, hemBot - 4, hemBot - 1, hR, hemBot - 4, hR, hemBot, hemBot + 3, hL, hemBot)).append("\" fill=\"").append(shade(tc, .82)).append("\"/>");
            for (double x = hL + 2; x < hR; x += 2.2) s.append(L("M" + f1(x) + " " + n(hemBot - 3.2) + " L" + f1(x) + " " + n(hemBot - .4), "rgba(0,0,0,.15)", .7));
            if ("pulli".equals(T) && top.zopfmuster) for (double x : new double[] {-4, 6}) {
                StringBuilder d = new StringBuilder(t("M$ -100", x)), d2 = new StringBuilder(t("M$ -100", x));
                for (double y = -100; y < hemBot - 6; y += 5) { d.append(t(" Q$ $ $ $", x + 2.5, y + 2.5, x, y + 5)); d2.append(t(" Q$ $ $ $", x + 2.5 - 5, y + 2.5, x, y + 5)); }
                s.append(L(d.toString(), "rgba(0,0,0,.16)", 1.6)).append(L(d2.toString(), "rgba(0,0,0,.16)", 1.6));
            }
        }
        if ("hoodie".equals(T)) s.append(F("M-7 -106 Q2 -101 11 -106 Q15 -97 3 -93 Q-9 -96 -7 -106Z", shade(tc, .9))).append(L("M-5 -103 Q2 -98 9 -103", "rgba(0,0,0,.25)", 1.2));
        if (in(T, "jacke", "jeansjacke", "wind", "parka")) {
            s.append(L(t("M$ -94 Q1 -91 $ -94", sL + 2, sR - 2), "rgba(0,0,0,.17)", 1));
            if (in(T, "jacke", "wind")) s.append("<path d=\"").append(t("M$ $ Q1 $ $ $ L$ $ Q1 $ $ $Z", hL, hemBot - 4, hemBot - 1, hR, hemBot - 4, hR, hemBot, hemBot + 3, hL, hemBot)).append("\" fill=\"").append(shade(tc, .8)).append("\"/>");
            if ("jeansjacke".equals(T)) s.append(L(t("M-4 -94 L-5 $ M6 -94 L7 $", hemBot, hemBot), "rgba(255,255,255,.18)", .8, " stroke-dasharray=\"1.4 1.2\""))
                .append(t("<rect x=\"$\" y=\"$\" width=\"$\" height=\"5\" fill=\"", hL, hemBot - 5, hR - hL)).append(shade(tc, .85)).append("\"/>")
                .append(L(t("M$ $ L$ $", hL, hemBot - 5, hR, hemBot - 5), "rgba(255,255,255,.2)", .7, " stroke-dasharray=\"1.4 1.2\""));
            if ("wind".equals(T)) s.append(F(t("M$ -105 Q1 -110 $ -104 Q$ -98 $ -94 Q1 -88 $ -94 Q$ -98 $ -105Z", sL, sR, sR + 1.5, sR - .5, sL + .5, sL - 1.5, sL), top.c2 != null ? top.c2 : shade(tc, 1.4)))
                .append(L(t("M$ -93 Q1 -87 $ -93", sL + .5, sR - .5), top.c3 != null ? top.c3 : "#e9e4d8", 1.4));
            if ("parka".equals(T)) {
                s.append(L(t("M$ -70 Q1 -67 $ -70", hL - 1, hR + 1), "rgba(0,0,0,.2)", 1.1, " stroke-dasharray=\"2 1.5\"")).append("<rect x=\"-14\" y=\"-76\" width=\"9\" height=\"7\" rx=\"1.5\" fill=\"rgba(0,0,0,.12)\"/>");
                s.append(F("M-8 -107 Q2 -100 12 -107 Q16 -97 3 -92 Q-10 -95 -8 -107Z", shade(tc, .9)));
                for (int aa = 0; aa <= 10; aa++) s.append(circ(Double.parseDouble(f1(-9 + aa * 2.2)), Double.parseDouble(f1(-107 + Math.sin(aa / 10.0 * Math.PI) * 4)), 2.6, aa % 2 == 1 ? "#d9cdb8" : "#c7b8a0"));
            }
        }
        if (in(T, "mantel", "trench")) {
            s.append(L(t("M1 $ L1 $", hemBot + 2, hemBot - 14), "rgba(0,0,0,.25)", 1.1)).append(L(t("M-1 $ Q1 $ 1 $", hemBot + 1, hemBot - 4, hemBot - 14), "rgba(0,0,0,.1)", 2));
            s.append(F("M-8 -108 Q1 -112 11 -108 L13 -102 Q1 -99 -10 -102Z", shade(tc, .88))).append(L("M-10 -102 Q1 -99 13 -102", "rgba(0,0,0,.22)", 1));
            if ("trench".equals(T)) {
                s.append("<path d=\"").append(t("M$ -78 Q1 -75 $ -78 L$ -73.5 Q1 -70.5 $ -73.5Z", wL - .5, wR + .5, wR + .5, wL - .5)).append("\" fill=\"").append(shade(tc, .85))
                    .append("\"/><rect x=\"4\" y=\"-78\" width=\"5\" height=\"5\" rx=\"1\" fill=\"none\" stroke=\"#3b3128\" stroke-width=\"1\"/>");
                s.append(F(t("M$ -103 L$ -104 L$ -101 L$ -100Z M$ -103 L$ -102 L$ -99 L$ -100Z", sL + 1, sL + 8, sL + 8, sL + 1, sR - 8, sR - 1, sR - 1, sR - 8), shade(tc, .8)))
                    .append(F("M-6 -100 Q1 -95 9 -100 L9 -88 Q1 -86 -6 -88Z", "rgba(0,0,0,.08)"));
            } else s.append("<rect x=\"-2\" y=\"-79\" width=\"12\" height=\"4\" rx=\"1.5\" fill=\"rgba(0,0,0,.15)\"/>").append(circ(-.5, -77, 1.1, "rgba(255,255,255,.25)")).append(circ(8.5, -77, 1.1, "rgba(255,255,255,.25)"));
        }
        if (in(T, "regen", "regenkurz")) {
            double by = "regen".equals(T) ? -60 : hemBot - 4;
            s.append(L(t("M$ $ Q1 $ $ $", hL - 2, by, by + 3, hR + 2, by), top.band != null ? top.band : "#d9dcd8", 2.2))
                .append(L(t("M$ -99 Q$ -88 $ -80 Q$ -68 $ -62 L$ $", sL + 2, wL + 1, wL + 1.4, hL + .8, hL + 1.4, hL - flare + 2.4, hemBot - 2), "rgba(255,255,255,.28)", 1.6))
                .append(L("M-5 -95 Q1 -93 8 -95", "rgba(0,0,0,.15)", 1)).append(F("M-6 -95 Q1 -93 8 -95 L8 -90 Q1 -88 -6 -90Z", "rgba(0,0,0,.07)"));
        }
        if ("kleid".equals(T)) {
            s.append(L(t("M$ -80 Q1 -78 $ -80", wL, wR), top.gurt != null ? top.gurt : "rgba(0,0,0,.2)", top.gurt != null ? 2 : 1));
            for (double x : new double[] {-6, 0, 6}) s.append(L(t("M$ -79 L$ $", x + 1, x * 1.5 + 1, hemBot), "rgba(0,0,0,.09)", 1.4));
            if (top.punkte != null) for (int i = 0; i < 26; i++) {
                double y = -100 + (i % 13) * 5, x = (i < 13 ? -4 : 6) + ((i * 7) % 5) - 2 + (y > -80 ? (y + 80) * .12 * (i < 13 ? -1 : 1) : 0);
                if (y < hemBot - 2) s.append(circ(Double.parseDouble(f1(x)), y, 1.1, top.punkte));
            }
            if ("ohne".equals(top.sleeve)) s.append(L("M-4 -105 Q2 -101 8 -105", "rgba(0,0,0,.18)", 1));
        }
        if (in(T, "daune", "daunelang") && (top.kragen == null || top.kragen)) s.append(F("M-8 -110 Q1 -113 11 -110 L12 -103 Q1 -100 -9 -103Z", shade(tc, .9)));
        if (c.hoodDown && !"kapuze".equals(c.hat)) {
            String hc = top.hoodC != null ? top.hoodC : shade(tc, .93), hp = "M-7.5 -108 Q2 -113 13 -107.5 Q16.5 -99 10 -93 Q2 -90.5 -4.5 -93.5 Q-9.5 -100 -7.5 -108Z";
            s.append(F(hp, hc)).append(F(hp, "url(#wf-rd)")).append(L("M-4 -104 Q2 -99 10 -104", "rgba(0,0,0,.22)", 1.2)).append(L("M-1 -97 Q3 -95 8 -97", "rgba(0,0,0,.14)", 1))
                .append(L("M-6.5 -106 Q-8 -100 -4.5 -95", "rgba(255,255,255,.22)", 1));
        }
        if (ruecken) s.append(arm.svg(fS, fE, fW, false, false, false)).append(L("M" + P(fS) + " L" + P(fE), DARK, aw));
        if (c.rucksack != null && !poncho) {   // small backpack (the big hiking pack belongs to the mountains, not the sea)
            String rc = c.rucksack;
            double x0 = -6, y0 = -101;
            s.append(L(t("M$ -104 Q$ -96 $ $", sL + 4, sL + 3, x0 + 1, y0 + 6), shade(rc, .7), 2.2)).append(L(t("M$ -103 Q$ -96 $ $", sR - 3, sR - 2, x0 + 17, y0 + 6), shade(rc, .7), 2.2));
            s.append(t("<rect x=\"$\" y=\"$\" width=\"19\" height=\"29\" rx=\"5\" fill=\"", x0, y0)).append(rc).append(t("\"/><rect x=\"$\" y=\"$\" width=\"19\" height=\"29\" rx=\"5\" fill=\"url(#wf-rd)\"/>", x0, y0));
            s.append(F(t("M$ $ Q$ $ $ $ Q$ $ $ $ Q$ $ $ $Z", x0, y0 + 9, x0, y0 - .5, x0 + 9.5, y0 - .5, x0 + 19, y0 - .5, x0 + 19, y0 + 9, x0 + 9.5, y0 + 12, x0, y0 + 9), shade(rc, .85)));
            s.append(t("<rect x=\"$\" y=\"$\" width=\"12\" height=\"10\" rx=\"3\" fill=\"", x0 + 3.5, y0 + 15)).append(shade(rc, .9)).append("\"/>")
                .append(L(t("M$ $ L$ $", x0 + 4.5, y0 + 16.5, x0 + 14.5, y0 + 16.5), "rgba(0,0,0,.3)", .8)).append(L(t("M$ $ L$ $", x0 + 9.5, y0 + 1, x0 + 9.5, y0 - 3), "#3a3a3a", 1.4))
                .append(t("<rect x=\"$\" y=\"$\" width=\"1.6\" height=\"4\" fill=\"#d9c27a\"/>", x0 + 13, y0 + 15));
        }
        if (c.tasche != null && !poncho) {
            String bc = c.tasche;
            s.append(L(t("M$ -104 L$ -68", sR - 3, hL + 1), shade(bc, .8), 1.8)).append(t("<rect x=\"$\" y=\"-71\" width=\"12\" height=\"10\" rx=\"2\" fill=\"", hL - 5)).append(bc)
                .append(t("\"/><rect x=\"$\" y=\"-71\" width=\"12\" height=\"4.5\" rx=\"2\" fill=\"", hL - 5)).append(shade(bc, .85)).append("\"/>").append(circ(hL + 1, -66.5, .9, "#d9c27a"));
        }

        // near arm (+ prop)
        if (!poncho) {
            if (in(pose, "verschraenkt", "zeitung", "fernglas")) s.append(arm.svg(nS, nE, nW, false, true, true));
            else if (!ruecken) s.append(arm.svg(nS, nE, nW, false, hidden[0] == 1, false));
            else s.append(arm.svg(nS, nE, nW, false, false, false));
        }
        String handR = n(c.mitt != null ? 3.9 : 3.3);
        if ("tasse".equals(pose)) {
            double hx = nW[0] - .5, hy = nW[1] - 1.5;
            String cc = c.tasse != null ? c.tasse : "#f1ede4";
            s.append(t("<path d=\"M$ $ L$ $ L$ $ L$ $Z\" fill=\"", hx - 6, hy - 6, hx + 1, hy - 6, hx, hy + 3, hx - 5, hy + 3)).append(cc).append("\"/>")
                .append(t("<rect x=\"$\" y=\"$\" width=\"7.8\" height=\"1.8\" rx=\".8\" fill=\"", hx - 6.4, hy - 7)).append(shade(cc, .6)).append("\"/>")
                .append(t("<path d=\"M$ $ L$ $ L$ $ L$ $Z\" fill=\"", hx - 5.6, hy - 2, hx + .5, hy - 2, hx + .3, hy + .5, hx - 5.2, hy + .5)).append(c.tasseBand != null ? c.tasseBand : "#b06a3a").append("\"/>")
                .append("<circle cx=\"").append(n(hx)).append("\" cy=\"").append(n(hy)).append("\" r=\"").append(handR).append("\" fill=\"").append(hand).append("\"/>");
            for (int i = 0; i < 3; i++) s.append(t("<path d=\"M$ $ q-2.4 -4 0 -7 q2.4 -3 0 -7\" stroke=\"rgba(255,255,255,.5)\" stroke-width=\"1.2\" fill=\"none\" stroke-linecap=\"round\" opacity=\".45\"/>", hx - 3 + i * 1.6, hy - 9));
        }
        if ("stoecke".equals(pose)) s.append(L(t("M$ $ L$ 1", nW[0] - .5, nW[1] + 2, nW[0] - 6), POLE, 1.6)).append(t("<ellipse cx=\"$\" cy=\"-2\" rx=\"2.8\" ry=\".9\" fill=\"#2b2b2b\"/>", nW[0] - 5.6))
            .append(t("<rect x=\"$\" y=\"$\" width=\"3.4\" height=\"7\" rx=\"1.2\" transform=\"rotate(12 $ $)\" fill=\"#2b2b2b\"/>", nW[0] - 2.2, nW[1] - 2, nW[0], nW[1])).append(circ(nW[0], nW[1], c.mitt != null ? 3.9 : 3.3, hand));
        if ("korb".equals(pose)) {
            double bx = nW[0] - 1, by = nW[1] + 3;
            String kc = "#b98a52";
            s.append(L(t("M$ $ Q$ $ $ $", bx - 7, by + 5, bx, by - 6, bx + 7, by + 5), shade(kc, .7), 1.4));
            if (c.korbInhalt != null) for (int i = 0; i < c.korbInhalt.length; i++) s.append(t("<ellipse cx=\"$\" cy=\"$\" rx=\"2.4\" ry=\"1.6\" fill=\"", bx - 5 + i * 3.4, by + 4)).append(c.korbInhalt[i]).append("\"/>");
            s.append(F(t("M$ $ L$ $ L$ $ Q$ $ $ $Z", bx - 9, by + 5, bx + 9, by + 5, bx + 6.5, by + 15, bx, by + 16.5, bx - 6.5, by + 15), kc))
                .append(L(t("M$ $ L$ $ M$ $ L$ $ M$ $ L$ $", bx - 8.4, by + 8, bx + 8.4, by + 8, bx - 7.6, by + 11, bx + 7.6, by + 11, bx - 7, by + 14, bx + 7, by + 14), shade(kc, .72), .8))
                .append(L(t("M$ $ L$ $", bx - 9, by + 5, bx + 9, by + 5), shade(kc, .6), 1.6)).append(circ(nW[0], nW[1], c.mitt != null ? 3.9 : 3.3, hand));
        }
        if ("handraus".equals(pose) && !poncho) s.append(t("<ellipse cx=\"$\" cy=\"$\" rx=\"4.6\" ry=\"1.9\" fill=\"", nW[0] - 2.2, nW[1] - 1.6)).append(hand).append("\"/>")
            .append(L(t("M$ $ L$ $", nW[0] - 6, nW[1] - 2.4, nW[0] + 1, nW[1] - 2.6), "rgba(0,0,0,.12)", .7));
        if ("schauen".equals(pose)) s.append(t("<ellipse cx=\"$\" cy=\"$\" rx=\"5.2\" ry=\"2.6\" transform=\"rotate(-18 $ $)\" fill=\"", nW[0] - 1, nW[1] - 1, nW[0] - 1, nW[1] - 1)).append(hand).append("\"/>");
        if ("stock".equals(pose)) s.append(L(t("M$ $ L$ 0", nW[0] - .5, nW[1] + 2, nW[0] - 5), c.stockC != null ? c.stockC : "#5a3b26", 2.2))
            .append(L(t("M$ $ Q$ $ $ $", nW[0] - .5, nW[1] + 2, nW[0] + 1, nW[1] - 4, nW[0] + 5, nW[1] - 1), c.stockC != null ? c.stockC : "#5a3b26", 2.4)).append(circ(nW[0], nW[1], 3.3, hand));
        if ("leine".equals(pose) && leashTo != null) s.append(L(t("M$ $ Q$ $ ", nW[0], nW[1] + 2, (nW[0] + leashTo[0]) / 2, (nW[1] + leashTo[1]) / 2 + 12) + P(leashTo), c.leineC != null ? c.leineC : "#c8584c", 1.2));

        // ---- neck + head (turned left, profile in shadow)
        s.append("<rect x=\"-4.5\" y=\"-114\" width=\"7.5\" height=\"10\" fill=\"").append(SH).append("\"/>");
        if (!"ohne".equals(top.sleeve) && !in(T, "tank", "kleid", "tshirt")) s.append(F("M-6 -107 Q1 -110 9 -107 L9 -103 Q1 -105 -6 -103Z", shade(tc, .85)));
        else s.append(L("M-5 -105.5 Q1 -108 8 -105.5", "rgba(0,0,0,.15)", 1.4));
        String PROF = "M-5.5 -129.5 Q-9.4 -127.5 -9.6 -123.4 L-12 -119.6 L-9.7 -118.7 Q-10.2 -116.8 -9.5 -116 Q-9.9 -114.2 -8.5 -112.8 Q-6 -110.6 -2 -111 L-2 -129 Z";
        String RIMP = "M-9.6 -123.4 L-12 -119.6 L-9.7 -118.7 Q-10.2 -116.8 -9.5 -116 Q-9.9 -114.2 -8.5 -112.8";
        String hat = c.hat, st = c.hairStyle != null ? c.hairStyle : "kurz", hatC = c.hatC != null ? c.hatC : "#2f3e52";
        boolean hood = "kapuze".equals(hat);
        StringBuilder hd = new StringBuilder(), back = new StringBuilder();
        String ear = "<ellipse cx=\"-2.3\" cy=\"-119\" rx=\"1.9\" ry=\"3\" fill=\"" + SH + "\"/><path d=\"M-2.6 -121 Q-1.4 -119 -2.6 -117\" stroke=\"rgba(0,0,0,.2)\" stroke-width=\".6\" fill=\"none\"/>";
        // long hair hanging on the back (below the head, above the coat)
        if (!hood && "lang".equals(st)) {
            if (wind) back.append("<g class=\"wf-hairw\">").append(F("M-1 -129 Q12 -133 14 -122 Q24 -124 38 -118 Q30 -114 22 -113 Q30 -108 34 -102 Q20 -104 12 -108 Q6 -104 2 -108 Q-2 -116 -1 -129Z", HC))
                .append(L("M14 -120 Q24 -119 32 -116 M13 -112 Q22 -109 29 -104", HD, .8)).append("</g>");
            else {
                String lh = "M-3 -127 Q12 -134 12.5 -119 Q12.5 -108 15 -95 Q13 -91 9 -92.5 Q6 -90 2 -92.5 Q-2 -90.5 -4 -93 Q-2.5 -102 -3.2 -112Z";
                back.append(F(lh, HC)).append(F(lh, "url(#wf-hr)")).append(L("M3 -120 Q4 -106 2 -94 M8 -122 Q10 -108 9 -94 M-.5 -114 Q0 -104 -2 -94", HD, .8)).append(L("M6 -126 Q11 -122 11 -112", HL, 1));
            }
        }
        if (!hood && "zopf".equals(st)) back.append("<g class=\"").append(wind ? "wf-hairw" : "wf-zopf").append("\">").append(wind
            ? F("M8 -127 Q20 -128 30 -122 Q38 -118 44 -121 Q38 -113 28 -115 Q18 -117 9 -119Z", HC) + L("M14 -123 Q26 -121 38 -118", HD, .8)
            : F("M8 -127 Q15.5 -125 14.5 -114 Q13.5 -104 10.5 -96 Q9 -101 9 -108 Q8.5 -116 7.5 -121Z", HC) + L("M11 -120 Q12.5 -110 10.6 -100", HD, .8) + L("M10 -124 Q13 -120 12.5 -114", HL, .9)).append("</g>");
        if (!hood && "bob".equals(st)) {
            String bp = "M-3.5 -128 Q12 -135 12.5 -120 Q12.8 -112 12 -108.5 Q6 -106.5 -1.5 -108.5 Q-3.5 -112 -3 -116Z";
            back.append(F(bp, HC)).append(F(bp, "url(#wf-hr)")).append(L("M4 -124 Q6 -116 4.5 -109 M8.5 -125 Q10.5 -116 9 -109", HD, .7));
        }
        if (!hood && "hoch".equals(st)) back.append("<g class=\"wf-zopf\">").append(F("M6 -134 Q15 -135.5 16.5 -126 Q17 -116 13.5 -106 Q11.5 -111 11.5 -118 Q11 -126 7 -129Z", HC))
            .append(L("M10 -131 Q14.5 -124 13.5 -112", HD, .8)).append(L("M9 -133 Q13 -131 15 -126", HL, .9)).append("</g>");
        if (!hood && "zoepfe".equals(st)) back.append("<g class=\"wf-zopf\">").append(zopf(10, -119, 12.5, -101, HC, HD, c)).append("</g>").append(zopf(-1.5, -114, -3.5, -100, HC, HD, c));
        if (!hood && "rastas".equals(st)) {
            StringBuilder rs = new StringBuilder();
            double[][] dd = {{-1, -118, -3, -99}, {2, -124, 1, -96}, {5, -127, 6, -95}, {8, -127, 10, -96}, {11, -123, 14, -98}, {12.5, -117, 16.5, -103}};
            for (int i = 0; i < dd.length; i++) {
                double[] d = dd[i];
                double mx = (d[0] + d[2]) / 2 + (i % 2 == 1 ? 1.2 : -1.2), my = (d[1] + d[3]) / 2;
                rs.append(L(t("M$ $ Q$ $ $ $", d[0], d[1], mx, my, d[2], d[3]), HC, 3.4)).append(L(t("M$ $ Q$ $ $ $", d[0] + .5, d[1] + 3, mx + .5, my, d[2] + .3, d[3] - 1), HD, .7, " stroke-dasharray=\"1.2 1.6\""));
            }
            back.append("<g class=\"wf-zopf\">").append(rs).append("</g>");
        }
        hd.append(circ(1, -121, 9.5, SKIN)).append(F(PROF, SH)).append(L(RIMP, RIM, .8));
        if (c.bart) hd.append(F("M-9.6 -117.6 Q-10.2 -116.8 -9.5 -116 Q-9.9 -114.2 -8.5 -112.8 Q-6 -110.6 -2 -111 L-1.6 -117 Q-3 -114 -5.5 -115 Q-7.5 -116 -9.6 -117.6Z", HD)).append(F("M-1.6 -117 L3 -117 Q4 -112 1 -110.5 L-2 -111Z", HD));
        Double hatLine = hutKante(hat);
        int hd0 = hd.length();
        boolean unterHut = hatLine != null;
        if (!hood) {
            if (in(st, "kurz", "zopf", "dutt", "lang", "bob", "hoch", "zoepfe", "rastas", "puff", "wuschel") || ("locken".equals(st) && unterHut)) {
                boolean tight = !in(st, "kurz", "wuschel");
                hd.append(F(tight ? "M-7 -129.4 Q-2 -133.8 3 -132.8 Q11.6 -130.6 11 -121 Q10.6 -112.5 5 -109.6 L-1 -110.5 Q-3.2 -114 -2.8 -117.6 Q-4.8 -120.6 -4.4 -124.4 Q-5.6 -127 -7 -129.4Z"
                    : "M-6.8 -129.2 Q-2 -133.5 3 -132.5 Q11.2 -130.5 10.6 -121 Q10.2 -113 5 -110 L-1 -110.5 Q-3.2 -114 -2.6 -117.4 Q-4.6 -120.5 -4.2 -124.2 Q-5.4 -127 -6.8 -129.2Z", HC));
                if ("wuschel".equals(st) && !unterHut) hd.append(F("M-6.8 -129 L-5.6 -134.2 L-3.2 -131 L-1.4 -136 L1.2 -132 L3.6 -136.6 L5.6 -132.4 L8.6 -135.2 L8.9 -130.6 L12 -131.4 L10.8 -126.5 Q3 -131 -6.8 -129Z", HC))
                    .append(L("M-1.4 -135 L-.6 -131 M3.6 -135.5 L4 -131.5", HD, .6));
                hd.append(L("M-3 -131 Q4 -133 8 -129", HL, .8)).append(L("M1 -128 Q6 -122 5 -112 M-2 -125 Q2 -118 1 -111", "rgba(0,0,0,.18)", .7));
                String gummi = c.haargummi != null ? c.haargummi : "#c8584c";
                if ("hoch".equals(st) && !unterHut) hd.append("<rect x=\"6.4\" y=\"-134\" width=\"3.4\" height=\"4.6\" rx=\"1.3\" transform=\"rotate(35 8 -131.5)\" fill=\"").append(gummi).append("\"/>");
                if ("zoepfe".equals(st)) hd.append(L("M-1.5 -133 Q5 -127 9.5 -119", "rgba(0,0,0,.28)", .7));
                if ("rastas".equals(st)) hd.append(L("M-3 -130 Q3 -126 4 -117 M1 -132.5 Q7 -128 8 -118 M5 -133 Q10 -129 10.5 -120", HD, 1.1));
                if ("locken".equals(st)) {
                    for (int i = 0; i < 6; i++) hd.append("<path d=\"M").append(f1(2 + (i % 3) * 2.6)).append(' ').append(f1(-121 + (i / 3) * 4.5)).append(" q1.3 -1.5 2.6 0\" stroke=\"").append(HD)
                        .append("\" stroke-width=\".8\" fill=\"none\" stroke-linecap=\"round\"/>");
                    for (double[] q : new double[][] {{10.6, -118.5}, {10.2, -114.5}, {8.4, -111.2}, {5.6, -110.2}}) hd.append(circ(q[0], q[1], 1.9, HC));
                }
                if ("puff".equals(st) && !unterHut) {
                    hd.append(circ(5.5, -134.5, 8.4, HC)).append(circ(5.5, -134.5, 8.4, "url(#wf-hr)"));
                    for (int i = 0; i < 10; i++) {
                        double aa = i * 36 * Math.PI / 180, r2 = 3 + (i % 3) * 1.8;
                        hd.append("<path d=\"M").append(f1(5.5 + r2 * Math.cos(aa))).append(' ').append(f1(-134.5 + r2 * Math.sin(aa))).append(" q1.3 -1.5 2.6 0\" stroke=\"").append(HD)
                            .append("\" stroke-width=\".8\" fill=\"none\" stroke-linecap=\"round\"/>");
                    }
                    hd.append("<rect x=\"1\" y=\"-128.5\" width=\"8.5\" height=\"2.2\" rx=\"1\" transform=\"rotate(-8 5 -127.5)\" fill=\"").append(c.haargummi != null ? c.haargummi : "#e2c46a").append("\"/>");
                }
                if ("kurz".equals(st) && "grau".equals(c.hair)) hd.append(F("M-4.6 -127 Q-2 -131 3 -131 Q8 -130 9 -127 Q3 -128 -4.6 -127Z", "rgba(0,0,0,.08)"));
                if ("zopf".equals(st) && !unterHut) hd.append("<rect x=\"7\" y=\"-128.2\" width=\"3\" height=\"4.4\" rx=\"1.2\" transform=\"rotate(20 8.5 -126)\" fill=\"").append(gummi).append("\"/>");
                if ("dutt".equals(st) && !unterHut) hd.append(circ(5.2, -132.4, 5, HC)).append(circ(5.2, -132.4, 5, "url(#wf-hr)")).append(L("M2 -134 Q5 -137 8.5 -133.5 M2.6 -131 Q5.5 -133 8.6 -130", HD, .7))
                    .append("<rect x=\"2.5\" y=\"-129\" width=\"5.5\" height=\"2\" rx=\"1\" transform=\"rotate(-12 5 -128)\" fill=\"").append(c.haargummi != null ? c.haargummi : shade(HC, .6)).append("\"/>");
                hd.append(ear);
            } else if ("locken".equals(st)) {
                StringBuilder d = new StringBuilder();
                double cx = 1.6, cy = -123.2, R = 11.4;
                for (int i = 0; i <= 14; i++) {
                    double aa = (205 + i * (205 / 14.0)) * Math.PI / 180, a2 = (205 + (i + .5) * (205 / 14.0)) * Math.PI / 180;
                    double[] p1 = pt(cx + R * Math.cos(aa), cy + R * Math.sin(aa)), q = pt(cx + (R + 3) * Math.cos(a2), cy + (R + 3) * Math.sin(a2));
                    d.append(i > 0 ? " Q" + P(q) + " " : "M").append(P(p1));
                }
                d.append(" Q4 -107 -.5 -110.2 Q-3 -114 -2.6 -117.6 Q-5 -120.5 -4.6 -124.4 Q-6 -127.5 ").append(P(pt(cx + R * Math.cos(205 * Math.PI / 180), cy + R * Math.sin(205 * Math.PI / 180)))).append("Z");
                hd.append(F(d.toString(), HC)).append(F(d.toString(), "url(#wf-hr)"));
                for (int i = 0; i < 9; i++) {
                    double aa = (230 + i * 18) * Math.PI / 180, r2 = 6 + (i % 3) * 1.6;
                    hd.append("<path d=\"M").append(f1(cx + r2 * Math.cos(aa))).append(' ').append(f1(cy + r2 * Math.sin(aa))).append(" q1.4 -1.6 2.8 0\" stroke=\"").append(HD).append("\" stroke-width=\".9\" fill=\"none\" stroke-linecap=\"round\"/>");
                }
                hd.append("<ellipse cx=\"-2.6\" cy=\"-118.4\" rx=\"1.7\" ry=\"2.6\" fill=\"").append(SH).append("\"/>");
            } else if ("glatze".equals(st)) {
                hd.append(F("M-3.2 -118 Q-3.6 -124 1 -126.5 Q9 -127 10.6 -119 Q10.3 -112 5 -110 L-1 -110.5 Q-3 -114 -3.2 -118Z", HC)).append(F("M-1 -122 Q4 -126 9 -123 Q9.5 -120 9 -118 Q4 -121 -1 -119Z", "rgba(0,0,0,.08)"))
                    .append(ear).append(L("M-1 -130 Q4 -131.5 8 -128", "rgba(255,255,255,.35)", 1.2));
            } else if ("kopftuch".equals(st)) {
                String kc = c.kopftuchC != null ? c.kopftuchC : "#7a5a8c", kp = "M-7.2 -129.6 Q-1 -135.6 5.5 -134 Q13.6 -131 13 -119 Q13 -109 18 -101 Q8 -97 -8 -101 Q-6.4 -106 -4.3 -110.8 Q-3 -117 -4.6 -123.8 Q-5.8 -127.2 -7.2 -129.6Z";
                hd.append(F(kp, kc)).append(F(kp, "url(#wf-rd)")).append(L("M-4.2 -126 Q-2 -117 -3.5 -110 Q-5 -105 -7 -101.5", "rgba(0,0,0,.2)", 1.1))
                    .append(L("M4 -133 Q10 -124 9 -112 Q10 -106 14 -101.5 M0 -132 Q5 -120 3 -105", "rgba(0,0,0,.1)", 1.4));
            }
        }
        // hair under a hat ends a little above the hat's lower edge (the hat covers the cut); the beret sits tilted
        if (hatLine != null) {
            String cut = "baske".equals(hat) ? "M-60 -118 L60 -139 L60 90 L-60 90Z" : t("M-60 $ L60 $ L60 90 L-60 90Z", hatLine, hatLine);
            String cp = "<clipPath id=\"wf-hc\"><path d=\"" + cut + "\"/></clipPath>";
            String rest = hd.substring(hd0);
            hd.setLength(hd0);
            hd.append(cp).append("<g clip-path=\"url(#wf-hc)\">").append(rest).append("</g>");
            if (back.length() > 0) {
                String b = back.toString();
                back.setLength(0);
                back.append(cp).append("<g clip-path=\"url(#wf-hc)\">").append(b).append("</g>");
            }
        }
        if (c.brille) hd.append(L("M-2.3 -121.8 L-10 -122.6", "#2a2522", .9));
        if (c.ohrring && !hood && !"kopftuch".equals(st)) hd.append(circ(-2.4, -115.5, 1, "#e2c46a"));
        if (c.sonnenbrilleHaar && hat == null) hd.append(F("M-6.5 -131 Q-3 -133.5 1 -132.8 L.6 -130.6 Q-3 -131.2 -6 -129.4Z", "#1c1f24")).append(L("M1 -132 Q6 -132.5 9.5 -129", "#1c1f24", .9));
        // hats
        if (hood) {
            String hc = top.hoodC != null ? top.hoodC : tc, hp = "M-7.5 -104 Q-11.5 -134 2 -135.5 Q15.5 -134 13.5 -104 Z";
            hd.setLength(0);
            hd.append(F(PROF, SH)).append(L(RIMP, RIM, .8)).append(F(hp, hc)).append(F(hp, "url(#wf-rd)"))
                .append(L("M-2.2 -132.4 Q-7.4 -128.5 -6.6 -118 Q-6.2 -110 -6.6 -105.5", "rgba(0,0,0,.2)", 1.6)).append(L("M2 -135.5 Q7 -121 4 -105", "rgba(0,0,0,.14)", 1))
                .append(L("M-6.4 -125.5 Q-7.6 -118 -6.9 -108.5", "rgba(255,255,255,.2)", 1));
            if (in(T, "regen", "regenkurz", "poncho")) hd.append(L("M-6.5 -106 L-7.5 -97 M-4.8 -106 L-5.2 -96.5", "#f0efe8", .8)).append(circ(-7.5, -96.5, .9, "#3a3a3a")).append(circ(-5.2, -96, .9, "#3a3a3a"));
        }
        if ("sonne".equals(hat)) {
            String sc = c.hatC != null ? c.hatC : "#e2c27a";
            hd.append(F("M-8 -127 Q-8 -139 1 -139 Q9 -139 9 -127Z", sc)).append("<rect x=\"-8\" y=\"-130\" width=\"17\" height=\"2.6\" fill=\"").append(c.hutband != null ? c.hutband : "#9c5a3c").append("\"/>")
                .append("<ellipse cx=\"0\" cy=\"-127\" rx=\"").append(W ? "20.5" : "18.5").append("\" ry=\"").append(W ? "4.6" : "3.8").append("\" fill=\"").append(sc).append("\" transform=\"rotate(-6 0 -127)\"/>")
                .append("<ellipse cx=\"0\" cy=\"-126\" rx=\"").append(W ? "20.5" : "18.5").append("\" ry=\"2.6\" fill=\"rgba(0,0,0,.12)\" transform=\"rotate(-6 0 -127)\"/>")
                .append(L("M-14 -128 Q0 -131 16 -129", "rgba(0,0,0,.08)", .7)).append(L("M-4 -137 Q1 -138 6 -136", "rgba(255,255,255,.3)", 1));
        }
        if ("hut".equals(hat)) hd.append(F("M-8 -127 Q-8 -140 1 -140 Q10 -140 9.5 -127Z", hatC)).append(L("M-3 -139.5 Q1 -137 5 -139.5", "rgba(0,0,0,.3)", 1))
            .append("<rect x=\"-8\" y=\"-130\" width=\"17.5\" height=\"2.6\" fill=\"#1f1b19\"/><ellipse cx=\"0.5\" cy=\"-127\" rx=\"14.5\" ry=\"3\" fill=\"").append(hatC).append("\" transform=\"rotate(-8 0 -127)\"/>");
        if ("kappe".equals(hat)) {
            String kp = "M-9.5 -122.5 Q-10.5 -135.5 1 -135.5 Q11.5 -135 10.8 -122Z", sch = "M-8.5 -124.5 Q-15 -125 -18 -122 Q-12 -121 -6.5 -122.3Z";
            hd.append(F(kp, hatC)).append(F(kp, "url(#wf-rd)")).append(F(sch, hatC)).append(F(sch, "rgba(0,0,0,.2)")).append(circ(1, -135, 1.2, "rgba(0,0,0,.3)"))
                .append(in(st, "zopf", "lang") ? F("M4 -124 Q7 -126 10 -124 L10 -122 Q7 -123 4 -122Z", "rgba(0,0,0,.35)") : "").append(L("M1 -135 Q-4 -129 -6 -123 M1 -135 Q7 -129 8 -123", "rgba(0,0,0,.14)", .8));
        }
        if ("suedwester".equals(hat)) {
            String br = "M-16 -127.5 Q-6 -131.5 4 -129.5 Q12.5 -127.5 16.5 -118.5 Q18 -112.5 14 -111.5 Q11 -118.5 3.5 -124 Q-6 -126.5 -15.5 -125Z", kr = "M-8.2 -126 Q-8.6 -138.5 1.5 -139 Q11 -138.5 10.6 -125.5Z";
            hd.append(F(kr, hatC)).append(F(kr, "url(#wf-rd)")).append(L("M-5.5 -134 Q1.5 -137 8.5 -133.5 M-7 -130 Q1.5 -133 9.8 -129.5", "rgba(0,0,0,.16)", .7, " stroke-dasharray=\"1.2 1\""))
                .append(F(br, shade(hatC, .92))).append(F(br, "url(#wf-rd)")).append(L("M-15.5 -125.5 Q-6 -127.5 3.5 -125 Q11 -120 14 -112", "rgba(0,0,0,.25)", 1))
                .append(L("M-14 -128 Q-5 -131 4 -129.5 Q11 -127.5 15 -120", "rgba(255,255,255,.3)", .9));
        }
        if ("fischer".equals(hat)) {
            String kr = "M-8.6 -125.5 Q-8.4 -137.5 1 -138 Q10.4 -137.5 10.2 -125Z";
            hd.append(F(kr, hatC)).append(F(kr, "url(#wf-rd)")).append(F("M-15 -122 Q-11 -127.5 1 -127.5 Q13 -127.5 17 -121.5 Q16 -119 14.5 -119.5 Q8 -124 1 -124 Q-7 -124 -13 -119Z", shade(hatC, .9)))
                .append(L("M-13.5 -121.5 Q-7 -125.5 1 -125.5 Q9 -125.5 15.5 -121", "rgba(0,0,0,.2)", .7, " stroke-dasharray=\"1.2 1\"")).append(L("M-8 -128.5 Q1 -131 10 -128", "rgba(0,0,0,.22)", 1.6))
                .append(L("M-6 -135 Q1 -137 6 -135.5", "rgba(255,255,255,.22)", 1));
        }
        if ("baske".equals(hat)) hd.append("<ellipse cx=\"2.5\" cy=\"-130.8\" rx=\"11.6\" ry=\"4.6\" transform=\"rotate(-10 2.5 -130.8)\" fill=\"").append(hatC)
            .append("\"/><ellipse cx=\"2.5\" cy=\"-130.8\" rx=\"11.6\" ry=\"4.6\" transform=\"rotate(-10 2.5 -130.8)\" fill=\"url(#wf-rd)\"/>")
            .append(F("M-8 -128 Q1.5 -125.5 11.5 -129.5 L11.4 -127.5 Q1.5 -123.5 -7.8 -126.2Z", shade(hatC, .75))).append(L("M3 -135.2 L3.6 -137.6", shade(hatC, .7), 1.4));
        if ("schieber".equals(hat)) {
            String kr = "M-10 -123 Q-10 -134 2 -134 Q11.5 -133 11.2 -124 Q1 -121 -10 -123Z";
            hd.append(F(kr, hatC)).append(F(kr, "url(#wf-rd)")).append(F("M-9.5 -125 Q-15 -125.5 -16 -123 Q-12 -122 -8 -123Z", shade(hatC, .8)))
                .append(L("M-8 -128 Q2 -131 10 -127", "rgba(255,255,255,.14)", .8, " stroke-dasharray=\"1 1\"")).append(L("M-6 -125 Q3 -128 10 -125", "rgba(0,0,0,.14)", .8, " stroke-dasharray=\"1 1\""));
        }
        if ("muetze".equals(hat)) {
            String hc = hatC, kr = "M-10.5 -117 Q-11.5 -137 0.5 -137 Q12 -137 11.2 -116Z";
            hd.append(F(kr, hc)).append(F(kr, "url(#wf-rd)"));
            for (double x = -8; x <= 9; x += 2.4) hd.append(L(t("M$ -131 Q$ -126 $ -122", x, x + .3, x + .2), "rgba(0,0,0,.12)", .8));
            hd.append(F("M-11 -122 Q0.5 -125 11.5 -121 L11.4 -115.5 Q0.5 -119 -10.8 -116.5Z", shade(hc, .82)));
            if (c.muetzeStreifen != null) hd.append(L("M-10.6 -126 Q0.5 -129 11.4 -125.5", c.muetzeStreifen, 2.2));
            if (c.bommel == null || c.bommel) hd.append(circ(1, -138.5, 4.4, c.bommelC != null ? c.bommelC : "#efe9de")).append(circ(2, -137.6, 2.6, "rgba(0,0,0,.08)"));
            if (in(st, "lang", "bob")) hd.append(F("M5 -116 Q10 -116.5 11.5 -115.5 L12.5 -108 Q8 -106 4 -108Z", HC));
            if ("zopf".equals(st)) hd.append(F("M4 -117 Q8 -118 11 -117 L10 -113 Q7 -112 4.5 -113Z", HC));
        }
        if ("stirnband".equals(hat)) {
            String sb = "M-7.4 -126.8 Q1 -132 10.9 -125.6 L11.2 -120.6 Q1 -126 -6.2 -121.8 Q-4.4 -120.4 -3.6 -116.6 Q-2.4 -114.8 -1 -115.4 L-.6 -123 Z";
            hd.append(F(sb, hatC)).append(F(sb, "url(#wf-rd)"));
            for (double x = -5; x <= 10; x += 1.8) hd.append(L("M" + n(x) + " " + f1(-127.6 + Math.abs(x - 1.5) * .12) + " L" + n(x + .2) + " " + f1(-123 + Math.abs(x - 1.5) * .1), "rgba(0,0,0,.13)", .6));
        }
        if ("ohren".equals(hat)) hd.append(L("M-2.2 -121 Q-1 -138 9 -133 Q12 -129 11.6 -121", "#3a3a3e", 1.8)).append(circ(-2.4, -119, 4.3, hatC)).append(circ(-1.6, -118.3, 2.6, "rgba(0,0,0,.1)"))
            .append(circ(11.6, -119.5, 3, shade(hatC, .7)));
        if ("hutfest".equals(pose)) hd.append(L("M" + P(nE) + " L" + P(nW), armC, aw)).append(circ(nW[0] + 1, nW[1] - 1, c.mitt != null ? 3.9 : 3.4, hand));
        if ("fernglas".equals(pose) && !poncho) hd.append(L("M" + P(nE) + " L" + P(nW), armC, aw))
            .append("<rect x=\"-19.5\" y=\"-125.5\" width=\"9\" height=\"6.4\" rx=\"1.8\" fill=\"#2a2d33\"/><rect x=\"-21\" y=\"-125\" width=\"2.4\" height=\"5.4\" rx=\"1\" fill=\"#41464f\"/>")
            .append(L("M-18.5 -124.2 L-12.5 -124.2", "rgba(255,255,255,.25)", .8)).append(circ(nW[0], nW[1], c.mitt != null ? 3.9 : 3.3, hand));
        if ("zeitung".equals(pose) && !poncho) {
            String pc = c.zeitungC != null ? c.zeitungC : "#ebe7dc";
            hd.append(L("M" + P(nE) + " L" + P(nW), armC, aw)).append(circ(nW[0] + .5, nW[1] + .5, 3.3, hand))
                .append(F("M-27 -136.5 Q-4 -145 23 -140 L24 -135.5 Q-3 -140.5 -26 -132.5Z", pc)).append(F("M-27 -136.5 L-26 -132.5 L-28.5 -127 L-29 -131Z", shade(pc, .8)))
                .append(F("M23 -140 L24 -135.5 L26 -129.5 L25 -134Z", shade(pc, .8))).append(L("M-20 -137 Q-6 -141 8 -139.4 M-16 -135 Q-4 -138.6 10 -137.2", "rgba(0,0,0,.2)", .7));
        }
        String hdS = hd.toString();
        if (c.lookUp) hdS = "<g transform=\"rotate(9 0 -112)\">" + hdS + "</g>";
        String headAll = back + "<g class=\"wf-head\">" + hdS + "</g>";
        if ("geduckt".equals(c.haltung) && !c.lookUp) headAll = "<g transform=\"translate(-.6 1.6) rotate(-6 0 -112)\">" + headAll + "</g>";
        else if ("aufrecht".equals(c.haltung) && !c.lookUp) headAll = "<g transform=\"rotate(4 0 -112)\">" + headAll + "</g>";
        s.append(headAll);
        // scarf (and its loose end)
        if (c.scarf != null) {
            String sc = c.scarf, sc2 = c.scarf2;
            s.append(F("M-8 -113.5 Q1 -110 10.5 -113.5 L10.5 -105.5 Q1 -102.5 -8 -105.5Z", sc)).append(F("M3 -112 Q8 -112 10.5 -113.5 L10.5 -105.5 Q7 -104 3 -104.6Z", "rgba(0,0,0,.15)"))
                .append(L("M-7 -109.5 Q1 -106.5 10 -109.5", "rgba(0,0,0,.15)", .8));
            String end = wind ? "M8 -110 Q20 -115 28 -109 Q36 -103 44 -111" : "M-6 -108 Q-12 -103 -10 -91";
            StringBuilder eS = new StringBuilder(L(end, sc, 5.2));
            if (sc2 != null) eS.append(L(end, sc2, 5.2, " stroke-dasharray=\"2.4 3.2\""));
            if (!wind) eS.append(L("M-12.4 -92 L-12.6 -89 M-10.4 -91.5 L-10.4 -88.5 M-8.4 -92 L-8.2 -89", sc, .9));
            s.append("<g class=\"").append(wind ? "wf-scarfw" : "wf-scarf").append("\">").append(eS).append("</g>");
            if (sc2 != null) s.append(L("M-7 -112 Q1 -108.6 10 -112", sc2, 1.6, " stroke-dasharray=\"2.4 3.2\""));
        }
        // posture of the upper body (hips stay where the legs are)
        String HT = "geduckt".equals(c.haltung) ? "translate(0 -62) rotate(-3) scale(1.02 .965) translate(0 62)"
            : ("aufrecht".equals(c.haltung) ? "translate(0 -62) scale(.99 1.03) translate(0 62)" : ("schief".equals(c.haltung) ? "rotate(3.5 0 -62)" : null));
        String out = s.toString();
        if (HT != null) out = out.substring(0, up0) + "<g transform=\"" + HT + "\">" + out.substring(up0) + "</g>";
        if (wind) out = "<g transform=\"rotate(-4)\">" + out + "</g>";
        if (c.teleskop) out = teleskop() + out;
        return out;
    }

    private static double wAt(double h, double R, double Tp, double H) {
        double b = 1, d = 1e9;
        for (double tt = 0; tt <= 1.0001; tt += .02) {
            double yy = (1 - tt) * (1 - tt) * R + 2 * tt * (1 - tt) * (Tp + 2) + tt * tt * Tp, e = Math.abs(yy - (R - H * h));
            if (e < d) { d = e; b = (1 - tt) * (1 - tt) + 2 * tt * (1 - tt) * .87; }
        }
        return b;
    }

    private static String zopf(double x0, double y0, double x1, double y1, String HC, String HD, Cfg c) {
        StringBuilder r = new StringBuilder();
        for (int k = 0; k < 6; k++) {
            double tt = k / 5.0, x = x0 + (x1 - x0) * tt, y = y0 + (y1 - y0) * tt;
            r.append("<ellipse cx=\"").append(f1(x)).append("\" cy=\"").append(f1(y)).append("\" rx=\"2.6\" ry=\"2.3\" fill=\"").append(HC).append("\"/>")
                .append(L("M" + f1(x - 2) + " " + f1(y - .5) + " Q" + f1(x) + " " + f1(y + 1) + " " + f1(x + 2) + " " + f1(y - .5), HD, .6));
        }
        return r + t("<rect x=\"$\" y=\"$\" width=\"4\" height=\"2\" rx=\"1\" fill=\"", x1 - 2, y1 + 1.6) + (c.haargummi != null ? c.haargummi : "#c8584c") + "\"/>"
            + F(t("M$ $ L$ $ L$ $ L$ $Z", x1 - 1.8, y1 + 3.4, x1 + 1.8, y1 + 3.4, x1 + 1, y1 + 7, x1 - 1, y1 + 7), HC);
    }

    private static Double hutKante(String hat) {
        if (hat == null) return null;
        switch (hat) {
            case "muetze": return -123.0;
            case "kappe": return -124.5;
            case "hut": return -129.0;
            case "schieber": return -125.5;
            case "sonne": return -129.0;
            case "suedwester": return -128.0;
            case "fischer": return -127.0;
            case "baske": return -129.5;
            default: return null;
        }
    }

    private static double saum(String T) {
        switch (T) {
            case "tshirt": case "tank": return -59;
            case "pulli": case "hoodie": return -57;
            case "hemd": return -58;
            case "jacke": return -56;
            case "jeansjacke": return -58;
            case "wind": case "daune": return -55;
            case "parka": return -45;
            case "regen": return -40;
            case "regenkurz": return -54;
            case "poncho": return -48;
            case "mantel": return -32;
            case "trench": return -34;
            case "daunelang": return -31;
            case "kleid": return -36;
            default: return -57;
        }
    }

    private static double weite(String T, boolean W) {
        switch (T) {
            case "mantel": return 3.5;
            case "trench": return 5;
            case "regen": return 4;
            case "regenkurz": return 1.5;
            case "parka": return 2;
            case "daunelang": return 3;
            case "kleid": return W ? 7 : 4;
            default: return 0;
        }
    }

    private static double[][] arme(String pose) {
        switch (pose) {
            case "taschen": return new double[][] {{-14.5, -83}, {-8.5, -71}, {17.5, -83}, {11.5, -71}};
            case "ruecken": return new double[][] {{-13.5, -80}, {-.5, -69.5}, {16.5, -80}, {4, -70.5}};
            case "tasse": return new double[][] {{-13, -84}, {-15, -95}, {15, -82}, {14.5, -65}};
            case "schauen": return new double[][] {{-19, -106}, {-12, -122}, {15, -82}, {14.5, -65}};
            case "hutfest": return new double[][] {{-17, -113}, {-6, -131}, {17.5, -83}, {11.5, -71}};
            case "stock": return new double[][] {{-12.5, -82}, {-14, -64}, {15, -82}, {14.5, -65}};
            case "leine": return new double[][] {{-12.5, -82}, {-14, -66}, {15, -82}, {14.5, -65}};
            case "verschraenkt": return new double[][] {{-15.5, -86}, {-8, -84}, {18.5, -86}, {10, -84}};
            case "schirm": return new double[][] {{-11, -82}, {-9, -64}, {20, -89}, {13, -98}};
            case "schirmTaschen": return new double[][] {{-14.5, -83}, {-8.5, -71}, {20, -89}, {13, -98}};
            case "schirmSchulter": return new double[][] {{-14.5, -83}, {-8.5, -71}, {17, -86}, {4, -82}};
            case "huefte": return new double[][] {{-22, -88}, {-12.5, -75}, {24.5, -88}, {15, -75}};
            case "taschenWeit": return new double[][] {{-20, -85}, {-11.5, -70.5}, {23, -85}, {14, -70.5}};
            case "handraus": return new double[][] {{-20, -85}, {-31, -83}, {15, -82}, {14.5, -65}};
            case "zeitung": return new double[][] {{-18, -112}, {-9, -137}, {17.5, -83}, {11.5, -71}};
            case "stoecke": return new double[][] {{-15, -82}, {-18, -67}, {18, -82}, {20, -67}};
            case "korb": return new double[][] {{-12.5, -82}, {-12, -66}, {15, -82}, {14.5, -65}};
            case "fernglas": return new double[][] {{-19, -99}, {-13, -117}, {16.5, -99}, {-1, -116}};
            default: return new double[][] {{-11, -82}, {-9, -64}, {15, -82}, {14.5, -65}};
        }
    }

    private static int[] versteckt(String pose) {
        switch (pose) {
            case "taschen": case "verschraenkt": case "schirmSchulter": case "taschenWeit": case "fernglas": return new int[] {1, 1};
            case "hutfest": case "zeitung": return new int[] {0, 1};
            case "schirmTaschen": return new int[] {1, 0};
            default: return new int[] {0, 0};
        }
    }

    private static String bein(double[] H, double[] K, double[] A, boolean far, boolean legSkin, Unten bot, String SKIN, double[] Bleg, String DARK) {
        StringBuilder r = new StringBuilder();
        if (legSkin) {
            String lc = bot.tights != null ? bot.tights : SKIN;
            r.append(L("M" + P(H) + " L" + P(K), lc, Bleg[0] - 1.5)).append(L("M" + P(K) + " L" + P(A), lc, Bleg[1] - 1.2));
            if ("shorts".equals(bot.type)) {
                double[] m = pt(H[0] + (K[0] - H[0]) * .45, H[1] + (K[1] - H[1]) * .45);
                r.append(L("M" + P(H) + " L" + P(m), bot.c, Bleg[0] + 1.6)).append(L("M" + P(pt(m[0] - 5, m[1] + 1)) + " L" + P(pt(m[0] + 5, m[1] + 1)), "rgba(0,0,0,.18)", 1));
            }
        } else {
            r.append(L("M" + P(H) + " L" + P(K), bot.c, Bleg[0])).append(L("M" + P(K) + " L" + P(A), bot.c, Bleg[1]));
            if ("jeans".equals(bot.type)) r.append(L("M" + P(pt(H[0] + 2, H[1] + 4)) + " L" + P(pt(K[0] + 2, K[1])) + " L" + P(pt(A[0] + 2, A[1] - 2)), "rgba(255,255,255,.13)", 1, " stroke-dasharray=\"1.6 1.4\""));
            r.append(L("M" + P(pt(K[0] - 2.5, K[1] + 1)) + " Q" + P(pt(K[0], K[1] + 3)) + " " + P(pt(K[0] + 2.5, K[1] + 1)), "rgba(0,0,0,.18)", .9));
        }
        if (far) r.append(L("M" + P(H) + " L" + P(K) + " L" + P(A), DARK, Bleg[0]));
        else if (!legSkin) r.append(L("M" + P(pt(H[0] - 4, H[1] + 6)) + " L" + P(pt(K[0] - 3.6, K[1])) + " L" + P(pt(A[0] - 3, A[1] - 3)), "rgba(255,255,255,.1)", 1.4));
        return r.toString();
    }

    private static String schuh(double[] A, boolean far, double tilt, Schuh shoe, String SKIN, Cfg c) {
        double x = A[0], y = A[1] + 5;
        String tt = shoe.type, sc = shoe.c;
        StringBuilder r = new StringBuilder();
        if ("sandale".equals(tt)) r.append(F(t("M$ $ Q$ $ $ $ L$ $ Q$ $ $ $ Z", x - 8, y, x - 9, y - 3, x - 4, y - 4, x + 3, y - 5, x + 5, y - 4, x + 4.5, y), SKIN))
            .append(L(t("M$ $ L$ $", x - 6, y - 3, x + 4, y - 4), sc, 1.6)).append(L(t("M$ $ L$ $", x - 8, y + .4, x + 4.5, y + .4), shade(sc, .6), 1.4));
        else if ("gummi".equals(tt)) r.append(F(t("M$ $ L$ $ L$ $ L$ $ Q$ $ $ $ Z", x - 5.5, y - 22, x + 5.5, y - 22, x + 5.5, y, x - 10, y, x - 11, y - 4, x - 5.5, y - 6), sc))
            .append(L(t("M$ $ L$ $", x - 3, y - 20, x - 3, y - 8), "rgba(255,255,255,.35)", 1.4)).append(t("<rect x=\"$\" y=\"$\" width=\"12\" height=\"2.6\" rx=\"1\" fill=\"", x - 6, y - 23)).append(shade(sc, .75)).append("\"/>")
            .append(L(t("M$ $ L$ $", x - 10, y + .3, x + 5.5, y + .3), "rgba(0,0,0,.35)", 1.4));
        else if ("schnee".equals(tt)) r.append(F(t("M$ $ L$ $ L$ $ L$ $ Q$ $ $ $ Z", x - 6, y - 17, x + 6, y - 17, x + 6.5, y, x - 11, y, x - 12, y - 5, x - 6, y - 7), sc))
            .append(t("<ellipse cx=\"$\" cy=\"$\" rx=\"7.4\" ry=\"2.8\" fill=\"#efe7da\"/>", x, y - 17)).append(L(t("M$ $ L$ $ M$ $ L$ $", x - 4.5, y - 16, x - 4.5, y - 9, x - 1.5, y - 16, x - 1.5, y - 9), "rgba(0,0,0,.18)", .8))
            .append(L(t("M$ $ L$ $", x - 11, y + .3, x + 6.5, y + .3), "#3a2f28", 2));
        else if ("boots".equals(tt)) r.append(F(t("M$ $ L$ $ L$ $ L$ $ Q$ $ $ $ Z", x - 5, y - 13, x + 5, y - 13, x + 5.5, y, x - 10, y, x - 11, y - 4, x - 5, y - 6), sc))
            .append(F(t("M$ $ L$ $ L$ $ L$ $Z", x - 5, y - 12, x - 2, y - 12, x - 2.5, y - 6, x - 5, y - 6), "rgba(0,0,0,.25)")).append(L(t("M$ $ L$ $", x - 10, y + .3, x + 5.5, y + .3), "rgba(0,0,0,.45)", 1.4))
            .append(t("<rect x=\"$\" y=\"$\" width=\"4.5\" height=\"3\" fill=\"rgba(0,0,0,.25)\"/>", x + 1, y - 3));
        else r.append(F(t("M$ $ Q$ $ $ $ L$ $ Q$ $ $ $ L$ $ Z", x - 8.5, y, x - 9.5, y - 4, x - 4.5, y - 5, x + 2.5, y - 6.2, x + 6, y - 6.2, x + 5.5, y - 1, x + 5, y), sc))
            .append(L(t("M$ $ L$ $", x - 9, y - .6, x + 5.5, y - .6), "sneaker".equals(tt) ? "#f4f4f2" : shade(sc, .55), 1.5))
            .append("sneaker".equals(tt) ? L(t("M$ $ L$ $", x - 3, y - 4.5, x + 2, y - 3.2), c.shoeAccent != null ? c.shoeAccent : "rgba(0,0,0,.25)", 1.2) : "");
        String out = r.toString();
        if (far) out = "<g opacity=\"1\">" + out + "</g>" + ("sandale".equals(tt) ? "" : F(t("M$ $ L$ $ L$ $Z", x - 4, y - 1, x + 6, y - 1, x + 6, y - 30), "rgba(0,0,0,0)"));
        if (tilt != 0) out = "<g transform=\"rotate(" + n(tilt) + " " + n(x) + " " + n(y - 4) + ")\">" + out + "</g>";
        return out;
    }

    /** Arm drawing of the page (armSvg). */
    private static final class Arm {
        final String sleeve, armC, SKIN, hand, DARK;
        final double aw, Barm;
        final boolean mitt;

        Arm(String sleeve, String armC, double aw, String SKIN, double Barm, String hand, boolean mitt, String DARK) {
            this.sleeve = sleeve; this.armC = armC; this.aw = aw; this.SKIN = SKIN; this.Barm = Barm; this.hand = hand; this.mitt = mitt; this.DARK = DARK;
        }

        String svg(double[] S, double[] E, double[] Wr, boolean far, boolean hideHand, boolean upperOnly) {
            StringBuilder r = new StringBuilder();
            String seg = upperOnly ? "M" + P(S) + " L" + P(E) : "M" + P(S) + " L" + P(E) + " L" + P(Wr);
            if ("lang".equals(sleeve)) {
                r.append(L(seg, armC, aw));
                if (!upperOnly && !hideHand) r.append(L("M" + P(pt(E[0] + (Wr[0] - E[0]) * .82, E[1] + (Wr[1] - E[1]) * .82)) + " L" + P(Wr), shade(armC, .8), aw + .3));
                r.append(L("M" + P(pt(E[0] - 1.5, E[1] - 1)) + " L" + P(pt(E[0] + 1.5, E[1] + 1.5)), "rgba(0,0,0,.18)", .9));
            } else {
                r.append(L(seg, SKIN, Barm - 1.2));
                if ("kurz".equals(sleeve)) r.append(L("M" + P(S) + " L" + P(pt(S[0] + (E[0] - S[0]) * .5, S[1] + (E[1] - S[1]) * .5)), armC, aw + 2.2));
            }
            if (!hideHand && !upperOnly) {
                double hx = Wr[0] + (Wr[0] - E[0]) * .12, hy = Wr[1] + (Wr[1] - E[1]) * .12;
                r.append(circ(Double.parseDouble(f1(hx)), Double.parseDouble(f1(hy)), mitt ? 3.9 : 3.3, hand));
                if (mitt) r.append("<ellipse cx=\"").append(f1(hx - 2.6)).append("\" cy=\"").append(f1(hy - 1)).append("\" rx=\"1.5\" ry=\"2.2\" fill=\"").append(hand).append("\"/>");
            }
            if (far) r.append(L(seg, DARK, aw)).append(hideHand || upperOnly ? "" : circ(Double.parseDouble(f1(Wr[0] + (Wr[0] - E[0]) * .12)), Double.parseDouble(f1(Wr[1] + (Wr[1] - E[1]) * .12)), 3.4, DARK));
            return r.toString();
        }
    }

    /** Telescope on a tripod right of the figure, pointing up into the sky (wetter.js teleskop). */
    static String teleskop() {
        String[] d = {"M40 -84 L27 0", "M40 -84 L55 0", "M40 -84 L43 -5"};
        StringBuilder s = new StringBuilder("<g>");
        for (int i = 0; i < 3; i++) s.append("<path d=\"").append(d[i]).append("\" stroke=\"").append(i == 2 ? "#2c2f36" : "#3d414a").append("\" stroke-width=\"1.7\" stroke-linecap=\"round\"/>");
        s.append("<path d=\"M31 -36 L50 -36\" stroke=\"#3d414a\" stroke-width=\"1.2\"/><rect x=\"36.5\" y=\"-88\" width=\"7\" height=\"6\" rx=\"1.2\" fill=\"#2a2d33\"/>")
            .append("<g transform=\"translate(40 -88) rotate(-34)\"><rect x=\"-19\" y=\"-1.1\" width=\"4\" height=\"2.2\" fill=\"#41464f\"/><rect x=\"-15.5\" y=\"-2\" width=\"5\" height=\"4\" fill=\"#2a2d33\"/>")
            .append("<rect x=\"-11\" y=\"-3.2\" width=\"32\" height=\"6.4\" rx=\"1.6\" fill=\"#e6e7ea\"/><rect x=\"-11\" y=\"-3.2\" width=\"32\" height=\"1.5\" rx=\".7\" fill=\"#f7f7f9\"/><rect x=\"-11\" y=\"1.6\" width=\"32\" height=\"1.6\" fill=\"#b9bcc4\"/>")
            .append("<rect x=\"19\" y=\"-4\" width=\"12\" height=\"8\" rx=\"1\" fill=\"#d6d8de\"/><rect x=\"30\" y=\"-4\" width=\"1.4\" height=\"8\" fill=\"#2a2d33\"/><rect x=\"-6\" y=\"-3.6\" width=\"3\" height=\"7.2\" fill=\"#2a2d33\"/>")
            .append("<rect x=\"0\" y=\"-7.4\" width=\"10\" height=\"2.2\" rx=\".9\" fill=\"#c9cbd2\"/><path d=\"M3 -5.2 L3 -3.2 M8 -5.2 L8 -3.2\" stroke=\"#2a2d33\" stroke-width=\".9\"/></g></g>");
        return s.toString();
    }
}
