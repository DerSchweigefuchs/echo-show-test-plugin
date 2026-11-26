package echoshow.kiosktest;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Data for the native screensaver overlay (Design A "Himmel"): options, notification/warning hint cards
 * (persistent_notification/subscribe + generic HA warning auto-detection) and playing music pill (generic
 * media_player auto-detection with configurable exclusion list). Plain Java, read-only HA queries only.
 */
final class SchonerDaten {

    static final class Optionen {
        boolean uhr = true;
        boolean uhrGross = true;
        boolean sekunden = false;
        boolean datum = true;
        boolean wetter = true;
        boolean hinweise = true;
        boolean musik = true;
        int ruhigFps = 12;
        int wetterFps = 60;

        Optionen kopie() {
            Optionen o = new Optionen();
            o.uhr = uhr; o.uhrGross = uhrGross; o.sekunden = sekunden;
            o.datum = datum; o.wetter = wetter; o.hinweise = hinweise;
            o.musik = musik; o.ruhigFps = ruhigFps; o.wetterFps = wetterFps;
            return o;
        }
    }

    static final class Hinweis {
        String id = "";
        String titel = "";
        String unter = "";
        String icon = "mdi:bell-outline";
        int farbe = 0xFFA99CF0;
        long zeit;       // epoch ms (0 if subtitle is static text)
        boolean warnung; // shown before regular notifications
    }

    static final class Musik {
        String titel = "";
        String kuenstler = "";
    }

    static final class Zustand {
        List<Hinweis> hinweise = Collections.emptyList();
        Musik musik;
    }

    private SchonerDaten() {}

    /** Subscription for HA persistent notifications (waste reminders, appliance alerts, battery warnings, ...). */
    static Map<String, Object> notifAbo() {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "persistent_notification/subscribe");
        return msg;
    }

    /**
     * One compact render_template subscription for active weather/civil warnings (DWD / NINA / Meteoalarm) and the
     * currently playing media_player (skipping any excluded entity IDs from settings). No hardcoded entity names.
     */
    static Map<String, Object> infoAbo(String ausschlussCsv) {
        StringBuilder ex = new StringBuilder("[");
        if (ausschlussCsv != null && !ausschlussCsv.trim().isEmpty()) {
            boolean first = true;
            for (String raw : ausschlussCsv.split("[,;\\s]+")) {
                String id = raw.trim();
                if (!id.matches("[a-z0-9_]+\\.[a-z0-9_]{1,250}")) continue;
                if (!first) ex.append(',');
                ex.append('\'').append(id).append('\'');
                first = false;
            }
        }
        ex.append(']');
        String tpl = "{%- set excl = " + ex + " -%}"
            + "{%- set ns = namespace(m=none, w=[]) -%}"
            + "{%- for p in states.media_player | selectattr('state', 'eq', 'playing') -%}"
            + "{%- if ns.m is none and p.entity_id not in excl and p.attributes.media_title | default('') -%}"
            + "{%- set ns.m = {'t': p.attributes.media_title, 'a': p.attributes.media_artist | default(p.attributes.media_album_name | default(''))} -%}"
            + "{%- endif -%}{%- endfor -%}"
            + "{%- for s in states.sensor -%}"
            + "{%- set wc = s.attributes.warning_count | default(0) | int(0) -%}"
            + "{%- if wc > 0 and ns.w | length < 2 -%}"
            + "{%- set ns.w = ns.w + [{'t': s.attributes.warning_1_headline | default(s.attributes.warning_1_name | default('Wetterwarnung')),"
            + " 'lv': s.attributes.warning_1_level | default(2) | int(2), 'e': s.attributes.warning_1_end | default('') | string}] -%}"
            + "{%- endif -%}{%- endfor -%}"
            + "{%- for b in states.binary_sensor | selectattr('state', 'eq', 'on') -%}"
            + "{%- if ns.w | length < 2 and b.attributes.headline | default('') and b.attributes.severity | default('') -%}"
            + "{%- set ns.w = ns.w + [{'t': b.attributes.headline, 'sev': b.attributes.severity | string, 'e': b.attributes.expires | default('') | string}] -%}"
            + "{%- endif -%}{%- endfor -%}"
            + "{{ {'m': ns.m, 'w': ns.w} | tojson }}";
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "render_template");
        msg.put("template", tpl);
        return msg;
    }

    /** Updates the mutable notification map from a persistent_notification/subscribe event payload. */
    static void notifEreignis(Map<String, Hinweis> ziel, Map<?, ?> ev) {
        Object typ = ev.get("type");
        Object raw = ev.get("notifications");
        if ("current".equals(typ)) ziel.clear();
        if (!(raw instanceof Map)) return;
        Map<?, ?> ns = (Map<?, ?>) raw;
        if ("removed".equals(typ)) {
            for (Object k : ns.keySet()) ziel.remove(String.valueOf(k));
            return;
        }
        for (Map.Entry<?, ?> e : ns.entrySet()) {
            String id = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) e.getValue();
            Hinweis h = new Hinweis();
            h.id = id;
            String tl = sauberMd(str(m.get("title")));
            String ms = sauberMd(str(m.get("message")));
            h.titel = !tl.isEmpty() ? tl : (!ms.isEmpty() ? ms : "Benachrichtigung");
            h.unter = !tl.isEmpty() && !ms.isEmpty() ? ms : "";
            h.zeit = Anwesenheit.zeit(m.get("created_at"));
            symbolUndFarbe(h, (tl + " " + ms + " " + id).toLowerCase(Locale.ROOT));
            ziel.put(id, h);
        }
    }

    /** Parses the infoAbo render_template result into {List<Hinweis> warnings, Musik or null}. */
    static Object[] infoLesen(Object result, Anwesenheit.JsonLeser json) {
        Object o = result instanceof String ? json.lesen((String) result) : result;
        List<Hinweis> warn = new ArrayList<>();
        Musik musik = null;
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.get("m") instanceof Map) {
                Map<?, ?> pm = (Map<?, ?>) m.get("m");
                String t = str(pm.get("t")).trim();
                if (!t.isEmpty()) {
                    musik = new Musik();
                    musik.titel = t;
                    musik.kuenstler = str(pm.get("a")).trim();
                }
            }
            if (m.get("w") instanceof List) {
                for (Object item : (List<?>) m.get("w")) {
                    if (!(item instanceof Map)) continue;
                    Map<?, ?> wm = (Map<?, ?>) item;
                    String t = sauberMd(str(wm.get("t")));
                    if (t.isEmpty()) continue;
                    int stufe = warnStufe(wm);
                    Hinweis h = new Hinweis();
                    h.warnung = true;
                    h.titel = kurzWarnung(t);
                    String ende = uhrBis(Anwesenheit.zeit(wm.get("e")));
                    h.unter = stufeName(stufe) + (ende.isEmpty() ? "" : " · bis " + ende);
                    h.farbe = stufeFarbe(stufe);
                    h.icon = warnIcon(t.toLowerCase(Locale.ROOT));
                    warn.add(h);
                }
            }
        }
        return new Object[] {warn, musik};
    }

    /** Combines warnings (first) and persistent notifications (newest first) into an immutable snapshot. */
    static Zustand zusammen(List<Hinweis> warnungen, Map<String, Hinweis> notifs, Musik musik) {
        List<Hinweis> alle = new ArrayList<>();
        if (warnungen != null) alle.addAll(warnungen);
        List<Hinweis> rest = new ArrayList<>(notifs.values());
        Collections.sort(rest, new Comparator<Hinweis>() {
            @Override public int compare(Hinweis a, Hinweis b) {
                return Long.compare(b.zeit, a.zeit);
            }
        });
        alle.addAll(rest);
        Zustand z = new Zustand();
        z.hinweise = alle;
        z.musik = musik;
        return z;
    }

    static String vorText(long zeitMs, long jetztMs) {
        if (zeitMs <= 0) return "";
        long m = Math.max(0, (jetztMs - zeitMs) / 60_000L);
        if (m < 1) return "gerade eben";
        if (m < 60) return "vor " + m + " Min.";
        long h = m / 60;
        if (h < 24) return "vor " + h + " Std.";
        return new SimpleDateFormat("d. MMM", Locale.GERMAN).format(new Date(zeitMs));
    }

    private static void symbolUndFarbe(Hinweis h, String t) {
        if (t.contains("bio") && (t.contains("müll") || t.contains("muell") || t.contains("rausstellen") || t.contains("tonne"))) {
            h.icon = "mdi:leaf"; h.farbe = 0xFFD48A4A;
        } else if ((t.contains("gelb") || t.contains("wertstoff")) && (t.contains("müll") || t.contains("muell") || t.contains("rausstellen") || t.contains("sack") || t.contains("tonne"))) {
            h.icon = "mdi:recycle"; h.farbe = 0xFFE8C44A;
        } else if (t.contains("papier") && (t.contains("müll") || t.contains("muell") || t.contains("rausstellen") || t.contains("tonne"))) {
            h.icon = "mdi:newspaper-variant-outline"; h.farbe = 0xFF5A9AE6;
        } else if (t.contains("restmüll") || t.contains("restmuell") || t.contains("muell") || t.contains("rausstellen")) {
            h.icon = "mdi:trash-can-outline"; h.farbe = 0xFF9BA5B0;
        } else if (t.contains("wasch")) {
            h.icon = "mdi:washing-machine"; h.farbe = 0xFF6FB3F2;
        } else if (t.contains("paket") || t.contains("lieferung") || t.contains("dhl")) {
            h.icon = "mdi:package-variant-closed"; h.farbe = 0xFFF0B45A;
        } else if (t.contains("akku") || t.contains("batter")) {
            h.icon = "mdi:battery-alert-variant-outline"; h.farbe = 0xFFF2875A;
        } else if (t.contains("update") || t.contains("aktualisier")) {
            h.icon = "mdi:update"; h.farbe = 0xFF6FB3F2;
        } else if (t.contains("login") || t.contains("anmeld")) {
            h.icon = "mdi:shield-alert-outline"; h.farbe = 0xFFF07A5A;
        } else if (t.contains("fehler") || t.contains("error") || t.contains("ausgefallen") || t.contains("offline")) {
            h.icon = "mdi:alert-circle-outline"; h.farbe = 0xFFF07A5A;
        } else {
            h.icon = "mdi:bell-outline"; h.farbe = 0xFFA99CF0;
        }
    }

    private static int warnStufe(Map<?, ?> wm) {
        Object lv = wm.get("lv");
        if (lv instanceof Number) return Math.max(1, Math.min(4, ((Number) lv).intValue()));
        String sev = str(wm.get("sev")).toLowerCase(Locale.ROOT);
        if (sev.contains("extreme")) return 4;
        if (sev.contains("severe")) return 3;
        if (sev.contains("moderate")) return 2;
        return 1;
    }

    private static int stufeFarbe(int s) {
        switch (s) {
            case 4: return 0xFFB04AD8;
            case 3: return 0xFFE8503C;
            case 2: return 0xFFF2A03C;
            default: return 0xFFF2D65A;
        }
    }

    private static String stufeName(int s) {
        switch (s) {
            case 4: return "Extrem";
            case 3: return "Schwer";
            case 2: return "Mäßig";
            default: return "Gering";
        }
    }

    private static String warnIcon(String t) {
        if (t.contains("wind") || t.contains("sturm") || t.contains("böe") || t.contains("orkan")) return "mdi:weather-windy";
        if (t.contains("gewitter") || t.contains("blitz")) return "mdi:weather-lightning";
        if (t.contains("regen") || t.contains("hochwasser") || t.contains("starkregen")) return "mdi:weather-pouring";
        if (t.contains("schnee") || t.contains("glätte") || t.contains("frost") || t.contains("eis")) return "mdi:snowflake-alert";
        if (t.contains("hitze")) return "mdi:thermometer-alert";
        return "mdi:alert-outline";
    }

    private static String kurzWarnung(String s) {
        String r = s.replaceFirst("(?i)^Amtliche\\s+(UNWETTER)?WARNUNG\\s+vor\\s+", "");
        return r.isEmpty() ? s : r;
    }

    private static String uhrBis(long ms) {
        if (ms <= 0) return "";
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(ms));
    }

    private static String sauberMd(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1").replaceAll("[*_`#>]+", "").replaceAll("\\s+", " ").trim();
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
