package echoshow.kiosktest;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Data and page model of the presence test view, a port of the dashboard's presence page: who is home since when,
 * today's 0-24 h timeline and the last 7 days. Plain Java, no Android classes.
 *
 * Read-only data: the people come from a render_template subscription over the person domain (one small JSON list,
 * kept live like the dashboard's states), the history from history/history_during_period with the same options as
 * the dashboard page.
 */
final class Anwesenheit {
    static final long TAG = 86_400_000L;
    static final int TXT = 0xFFECEBE8, SUB = 0xFF8FA3B5, GREEN = 0xFF6CC483, TILE = 0xFF16212B, BG = 0xFF0D1720, WEG = 0xFF56606B;
    static final int[] COL = {0xFF6FB3F2, 0xFFF2B45C, 0xFFB49CF2, 0xFF6CC483, 0xFFF28B82, 0xFF7FD6D0, 0xFFE6A1D8, 0xFFC9D36A, 0xFFF0A070, 0xFF8FB0FF};
    static final String[] DS = {"So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"};
    private static final Pattern PERSON = Pattern.compile("person\\.[a-z0-9_]{1,250}");

    private Anwesenheit() {}

    // ---- data from Home Assistant -----------------------------------------------------------------------------------

    static final class Person {
        final String id, name, state;
        final long lc;   // last_changed in ms

        Person(String id, String name, String state, long lc) {
            this.id = id;
            this.name = name;
            this.state = state;
            this.lc = lc;
        }
    }

    /** One history row: state and last_updated in ms. */
    static final class Eintrag {
        final String s;
        final long t;

        Eintrag(String s, long t) {
            this.s = s;
            this.t = t;
        }
    }

    /** The "Personen" setting: empty list = every person entity, otherwise the valid IDs in the given order. */
    static List<String> ids(String setting) {
        List<String> ids = new ArrayList<>();
        if (setting == null) return ids;
        for (String teil : setting.split(",")) {
            String id = teil.trim().toLowerCase(Locale.ROOT);
            if (PERSON.matcher(id).matches() && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    /** render_template message: the people as a JSON list [{id, name, state, lc}], re-rendered by HA on every change. */
    static Map<String, Object> personenAbo(List<String> ids) {
        StringBuilder liste = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) liste.append(i == 0 ? "" : ",").append('\'').append(ids.get(i)).append('\'');
        liste.append(']');
        String tpl = "{%- set ids = " + liste + " -%}{%- set ns = namespace(l=[]) -%}"
            + "{%- for s in states.person -%}{%- if ids | length == 0 or s.entity_id in ids -%}"
            + "{%- set ns.l = ns.l + [{'id': s.entity_id, 'name': s.name, 'state': s.state, 'lc': s.last_changed.timestamp()}] -%}"
            + "{%- endif -%}{%- endfor -%}{{ ns.l | tojson }}";
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "render_template");
        msg.put("template", tpl);
        return msg;
    }

    /**
     * The template result: HA usually sends the parsed list, otherwise the JSON text. jsonText parses text with
     * org.json on the device (passed in so this class stays free of Android classes).
     */
    static List<Person> personen(Object result, List<String> reihenfolge, JsonLeser jsonText) {
        Object liste = result;
        if (result instanceof String) liste = jsonText.lesen((String) result);
        List<Person> out = new ArrayList<>();
        if (!(liste instanceof List)) return out;
        for (Object o : (List<?>) liste) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> p = (Map<?, ?>) o;
            Object id = p.get("id"), lc = p.get("lc");
            if (!(id instanceof String)) continue;
            Object name = p.get("name"), state = p.get("state");
            out.add(new Person((String) id, name == null ? (String) id : String.valueOf(name), state == null ? "unknown" : String.valueOf(state),
                lc instanceof Number ? Math.round(((Number) lc).doubleValue() * 1000) : 0));
        }
        if (!reihenfolge.isEmpty()) {
            Collections.sort(out, (a, b) -> reihenfolge.indexOf(a.id) - reihenfolge.indexOf(b.id));
        }
        return out;
    }

    interface JsonLeser {
        Object lesen(String text);
    }

    /** Key of the current states; the history is reloaded when it changes (as on the dashboard page). */
    static String schluessel(List<Person> ps) {
        StringBuilder sb = new StringBuilder();
        for (Person p : ps) sb.append(p.id).append('=').append(p.state).append('|');
        return sb.toString();
    }

    /** Midnight today, local time. */
    static long heute(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Same options as the dashboard page (minimal_response, no_attributes, significant_changes_only false). */
    static Map<String, Object> verlaufAnfrage(List<Person> ps, long now, boolean nurWechsel) {
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(heute(now) - 6 * TAG);
        List<String> ids = new ArrayList<>();
        for (Person p : ps) ids.add(p.id);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "history/history_during_period");
        msg.put("start_time", Kalender.iso(start));
        msg.put("entity_ids", ids);
        msg.put("minimal_response", true);
        msg.put("no_attributes", true);
        msg.put("significant_changes_only", nurWechsel);
        return msg;
    }

    /** {entity: [{s, lu}, ...]} (compressed) or the long form {state, last_changed} as rows per person. */
    static Map<String, List<Eintrag>> verlauf(Object data) {
        Map<String, List<Eintrag>> out = new LinkedHashMap<>();
        if (!(data instanceof Map)) return out;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) data).entrySet()) {
            if (!(e.getValue() instanceof List)) continue;
            List<Eintrag> rows = new ArrayList<>();
            for (Object o : (List<?>) e.getValue()) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> r = (Map<?, ?>) o;
                Object s = r.containsKey("s") ? r.get("s") : r.get("state");
                Object lu = r.get("lu");
                long t;
                if (lu instanceof Number) t = Math.round(((Number) lu).doubleValue() * 1000);
                else t = zeit(r.get("last_changed") != null ? r.get("last_changed") : r.get("last_updated"));
                rows.add(new Eintrag(s == null ? null : String.valueOf(s), t));
            }
            out.put(String.valueOf(e.getKey()), rows);
        }
        return out;
    }

    /** ISO 8601 timestamp of the long history form, or 0. */
    static long zeit(Object iso) {
        if (!(iso instanceof String)) return 0;
        String s = (String) iso;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            long t = f.parse(s.substring(0, 19)).getTime();
            int tz = Math.max(s.lastIndexOf('+'), s.lastIndexOf('-'));
            if (tz > 18) {
                int sign = s.charAt(tz) == '-' ? -1 : 1;
                String[] hm = s.substring(tz + 1).split(":");
                t -= sign * (Integer.parseInt(hm[0]) * 3_600_000L + (hm.length > 1 ? Integer.parseInt(hm[1]) * 60_000L : 0));
            }
            return t;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- page model (port of the dashboard page's script) -----------------------------------------------------------

    static final class Span {
        long a, b;
        final String s;

        Span(long a, long b, String s) {
            this.a = a;
            this.b = b;
            this.s = s;
        }
    }

    static final class Zeile {
        String id, name, state, initiale;
        int col;
        boolean home;
        long since;
        List<Span> sp;
        String status, dauer, ereignisse;   // card status, "heute 3:03 h zu Hause", "weg 10:34 · heim 13:03"
        long[] wocheMs = new long[7];
    }

    static final class Seite {
        long now, today, start;
        boolean geladen;
        String unterTitel;
        List<Zeile> personen = new ArrayList<>();
    }

    static Seite rechnen(List<Person> ps, Map<String, List<Eintrag>> verlauf, long now) {
        Seite S = new Seite();
        S.now = now;
        S.today = heute(now);
        S.start = S.today - 6 * TAG;
        S.geladen = verlauf != null;
        int home = 0;
        for (int i = 0; i < ps.size(); i++) {
            Person p = ps.get(i);
            Zeile z = new Zeile();
            z.id = p.id;
            z.name = p.name;
            z.state = p.state;
            z.initiale = p.name.isEmpty() ? "?" : p.name.substring(0, Character.charCount(p.name.codePointAt(0))).toUpperCase(Locale.GERMAN);
            z.col = COL[i % COL.length];
            z.home = "home".equals(p.state);
            if (z.home) home++;
            z.sp = spans(verlauf == null ? null : verlauf.get(p.id), p.state, S.start, now);
            z.since = z.sp.isEmpty() ? (p.lc > 0 ? p.lc : now) : z.sp.get(z.sp.size() - 1).a;
            z.status = ort(p.state) + " " + seit(z.since, z.sp, S);
            z.dauer = S.geladen ? "heute " + dauer(homeIn(z.sp, S.today, now)) + " zu Hause" : "";
            List<String> ev = new ArrayList<>();
            for (int k = 1; k < z.sp.size(); k++) {
                Span x = z.sp.get(k);
                if (x.a < S.today) continue;
                String was = "home".equals(x.s) ? "heim " : ("home".equals(z.sp.get(k - 1).s) ? "weg " : "→ " + ort(x.s) + " ");
                ev.add(was + hm(x.a));
            }
            if (!ev.isEmpty()) z.ereignisse = join(ev.subList(Math.max(0, ev.size() - 4), ev.size()), " · ");
            else z.ereignisse = homeIn(z.sp, S.today, now) > 0 ? "den ganzen Tag zu Hause" : "heute noch nicht zu Hause";
            for (int k = 0; k < 7; k++) {
                long a = S.start + k * TAG;
                z.wocheMs[k] = homeIn(z.sp, a, Math.min(a + TAG, now));
            }
            S.personen.add(z);
        }
        int n = ps.size();
        S.unterTitel = home == n ? "Alle zu Hause" : (home == 0 ? "Niemand zu Hause" : home + " von " + n + " zu Hause");
        return S;
    }

    /** Cleaned spans [from, to, state]: unknown/unavailable continue the previous state, GPS flapping under 10 min is merged. */
    static List<Span> spans(List<Eintrag> raw, String aktuell, long start, long now) {
        List<Span> out = new ArrayList<>();
        if (raw != null) {
            for (Eintrag e : raw) {
                if (e.s == null || "unknown".equals(e.s) || "unavailable".equals(e.s)) continue;
                long ts = Math.max(e.t, start);
                if (!out.isEmpty() && out.get(out.size() - 1).s.equals(e.s)) continue;
                if (!out.isEmpty()) out.get(out.size() - 1).b = ts;
                out.add(new Span(ts, now, e.s));
            }
        }
        if (out.isEmpty() && aktuell != null) out.add(new Span(start, now, aktuell));
        for (int k = out.size() - 2; k >= 1; k--) {
            if (k + 1 >= out.size()) continue;   // after a merge near the end (the page script would fail here)
            if (out.get(k).b - out.get(k).a < 600_000L && out.get(k - 1).s.equals(out.get(k + 1).s)) {
                out.get(k - 1).b = out.get(k + 1).b;
                out.remove(k + 1);
                out.remove(k);
            }
        }
        return out;
    }

    static long homeIn(List<Span> sp, long a, long b) {
        long ms = 0;
        for (Span x : sp) {
            if (!"home".equals(x.s)) continue;
            long s = Math.max(a, x.a), e = Math.min(b, x.b);
            if (e > s) ms += e - s;
        }
        return ms;
    }

    static String ort(String s) {
        return "home".equals(s) ? "Zu Hause" : ("not_home".equals(s) ? "Unterwegs" : s);
    }

    static String seit(long t, List<Span> sp, Seite S) {
        if (sp.size() == 1 && sp.get(0).a <= S.start) return "seit über 7 Tagen";
        if (t >= S.today) return "seit " + hm(t);
        if (t >= S.today - TAG) return "seit gestern " + hm(t);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return "seit " + DS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + hm(t);
    }

    static String tagKurz(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return DS[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    static String hm(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    static String dauer(long ms) {
        long m = Math.round(ms / 60000.0);
        return (m / 60) + ":" + String.format(Locale.ROOT, "%02d", m % 60) + " h";
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() == 0 ? "" : sep).append(s);
        return sb.toString();
    }
}
