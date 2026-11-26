package echoshow.kiosktest;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Read-only calendar data: calendar.get_events (return_response) through host.haCall, supporting Tag / 3 Tage / Woche / Monat / Liste + AI marked days. */
final class Kalender {
    static final Pattern TAG = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ENTITY = Pattern.compile("calendar\\.[a-z0-9_]{1,250}");
    static final int[] FARBEN = {0xFF6FB3F2, 0xFFF2B45C, 0xFF6CC483, 0xFFB49CF2, 0xFFF28B82, 0xFF7FD6D0, 0xFFE6A1D8, 0xFFC9D36A};

    static final class Termin {
        String uid = "", datum = "", zeit = "", titel = "", ort = "", beschreibung = "", kalender = "", sortierung = "";
        long startMs, endeMs;
        boolean ganztag;
        int farbe;
    }

    /** Requested date range, initial view mode, and highlighted days (from param: tag, zeitraum, tage). */
    static final class Spanne {
        final Calendar start;
        final int tageAnzahl;
        final Set<String> markiert;
        final String zeitraum;   // "tag" | "wochenende" | "woche" | "monat" | "liste" | "3tage"

        Spanne(Calendar start, int tageAnzahl, Set<String> markiert, String zeitraum) {
            this.start = start;
            this.tageAnzahl = tageAnzahl;
            this.markiert = markiert;
            this.zeitraum = zeitraum;
        }
    }

    private Kalender() {}

    static Calendar tag(String tag) {
        Calendar c = Calendar.getInstance();
        if (tag != null && TAG.matcher(tag).matches()) {
            try {
                c.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(tag));
            } catch (java.text.ParseException ignored) {
                c = Calendar.getInstance();
            }
        }
        mitternacht(c);
        return c;
    }

    static Calendar mitternacht(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    static Calendar montag(Calendar c) {
        Calendar m = (Calendar) c.clone();
        mitternacht(m);
        int wd = (m.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // 0 = Monday .. 6 = Sunday
        m.add(Calendar.DAY_OF_MONTH, -wd);
        return m;
    }

    static String datumKey(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(c.getTime());
    }

    static String datumKey(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return datumKey(c);
    }

    /** Resolves param {tag, zeitraum, tage} into initial view mode + range + highlighted dates. */
    static Spanne spanne(Map<String, Object> param) {
        String tagStr = param != null && param.get("tag") instanceof String ? ((String) param.get("tag")).trim() : null;
        String zr = param != null && param.get("zeitraum") instanceof String ? ((String) param.get("zeitraum")).trim().toLowerCase(Locale.ROOT) : "";
        List<String> tageListe = new ArrayList<>();
        Object rawTage = param == null ? null : param.get("tage");
        if (rawTage instanceof List) {
            for (Object o : (List<?>) rawTage) {
                if (o instanceof String && TAG.matcher(((String) o).trim()).matches()) tageListe.add(((String) o).trim());
            }
        } else if (rawTage instanceof String) {
            for (String s : ((String) rawTage).split(",")) {
                if (TAG.matcher(s.trim()).matches()) tageListe.add(s.trim());
            }
        }
        Set<String> markiert = new LinkedHashSet<>(tageListe);

        if ("wochenende".equals(zr)) {
            Calendar sa = tag(tagStr);
            if (tagStr == null || tagStr.isEmpty()) {
                int dow = sa.get(Calendar.DAY_OF_WEEK);
                if (dow == Calendar.SUNDAY) sa.add(Calendar.DAY_OF_MONTH, -1);
                else if (dow != Calendar.SATURDAY) sa.add(Calendar.DAY_OF_MONTH, (Calendar.SATURDAY - dow + 7) % 7);
            }
            markiert.add(datumKey(sa));
            Calendar so = (Calendar) sa.clone();
            so.add(Calendar.DAY_OF_MONTH, 1);
            markiert.add(datumKey(so));
            return new Spanne(montag(sa), 7, markiert, "woche");
        }

        if (!tageListe.isEmpty()) {
            Collections.sort(tageListe);
            Calendar d0 = tag(tageListe.get(0));
            if (tageListe.size() == 1 && "tag".equals(zr)) {
                return new Spanne(d0, 1, markiert, "tag");
            }
            return new Spanne(montag(d0), 7, markiert, "woche");
        }

        if ("tag".equals(zr)) {
            Calendar s = tag(tagStr);
            if (tagStr != null && TAG.matcher(tagStr).matches()) markiert.add(datumKey(s));
            return new Spanne(s, 1, markiert, "tag");
        }
        if ("3tage".equals(zr)) {
            Calendar s = tag(tagStr);
            if (tagStr != null && TAG.matcher(tagStr).matches()) markiert.add(datumKey(s));
            return new Spanne(s, 3, markiert, "3tage");
        }
        if ("monat".equals(zr)) {
            Calendar s = tag(tagStr);
            s.set(Calendar.DAY_OF_MONTH, 1);
            return new Spanne(montag(s), 42, markiert, "monat");
        }
        if ("liste".equals(zr)) {
            return new Spanne(tag(tagStr), 14, markiert, "liste");
        }

        // Default matching kalender.py: Woche (Mo–So)
        Calendar s = tag(tagStr);
        if (tagStr != null && TAG.matcher(tagStr).matches()) markiert.add(datumKey(s));
        return new Spanne(montag(s), 7, markiert, "woche");
    }

    /** ISO 8601 with the device's offset, e.g. 2026-11-25T00:00:00+01:00. */
    static String iso(Calendar c) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        f.setTimeZone(c.getTimeZone());
        int off = c.getTimeZone().getOffset(c.getTimeInMillis()) / 60000;
        char sign = off < 0 ? '-' : '+';
        off = Math.abs(off);
        return f.format(c.getTime()) + String.format(Locale.ROOT, "%c%02d:%02d", sign, off / 60, off % 60);
    }

    static Map<String, Object> anfrage(String entities, Calendar start, int tageAnzahl) {
        Calendar ende = (Calendar) start.clone();
        ende.add(Calendar.DAY_OF_MONTH, Math.max(1, Math.min(45, tageAnzahl)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("start_date_time", iso(start));
        data.put("end_date_time", iso(ende));
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("entity_id", ziele(entities));
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "call_service");
        msg.put("domain", "calendar");
        msg.put("service", "get_events");
        msg.put("service_data", data);
        msg.put("target", target);
        msg.put("return_response", true);
        return msg;
    }

    static Object ziele(String entities) {
        List<String> ids = new ArrayList<>();
        if (entities != null) {
            for (String teil : entities.split(",")) {
                String id = teil.trim().toLowerCase(Locale.ROOT);
                if (ENTITY.matcher(id).matches() && !ids.contains(id)) ids.add(id);
            }
        }
        return ids.isEmpty() ? "all" : ids;
    }

    /** Parses HA's calendar.get_events response into rich normalized Termin objects with startMs/endeMs/ganztag. */
    static List<Termin> lesenEreignisse(Object data) {
        List<Termin> out = new ArrayList<>();
        if (!(data instanceof Map)) return out;
        Object response = ((Map<?, ?>) data).get("response");
        if (!(response instanceof Map)) return out;
        List<String> kalender = new ArrayList<>();
        for (Object k : ((Map<?, ?>) response).keySet()) kalender.add(String.valueOf(k));
        Collections.sort(kalender);

        for (int i = 0; i < kalender.size(); i++) {
            String entity = kalender.get(i);
            Object eintrag = ((Map<?, ?>) response).get(entity);
            Object events = eintrag instanceof Map ? ((Map<?, ?>) eintrag).get("events") : null;
            if (!(events instanceof List)) continue;
            String kalName = name(entity);
            int farbe = FARBEN[i % FARBEN.length];
            int idx = 0;
            for (Object e : (List<?>) events) {
                if (!(e instanceof Map)) continue;
                Map<?, ?> ev = (Map<?, ?>) e;
                String sRaw = text(ev.get("start")), eRaw = text(ev.get("end"));
                Termin t = new Termin();
                t.titel = text(ev.get("summary"));
                if (t.titel.isEmpty()) t.titel = "(ohne Titel)";
                t.ort = text(ev.get("location"));
                t.beschreibung = text(ev.get("description"));
                t.kalender = kalName;
                t.farbe = farbe;
                t.ganztag = sRaw.length() == 10;
                if (t.ganztag) {
                    Calendar cs = tag(sRaw);
                    Calendar ce = eRaw.length() == 10 ? tag(eRaw) : (Calendar) cs.clone();
                    if (ce.getTimeInMillis() <= cs.getTimeInMillis()) ce.add(Calendar.DAY_OF_MONTH, 1);
                    t.startMs = cs.getTimeInMillis();
                    t.endeMs = ce.getTimeInMillis();
                    t.datum = sRaw;
                    t.zeit = "ganztägig";
                } else {
                    t.startMs = parseIso(sRaw);
                    t.endeMs = parseIso(eRaw);
                    if (t.endeMs <= t.startMs) t.endeMs = t.startMs + 3_600_000L;
                    t.datum = sRaw.length() >= 10 ? sRaw.substring(0, 10) : datumKey(t.startMs);
                    t.zeit = hm(t.startMs) + "–" + hm(t.endeMs);
                }
                String uid = text(ev.get("uid"));
                t.uid = (uid.isEmpty() ? entity + "#" + idx : uid) + "|" + t.startMs;
                idx++;
                out.add(t);
            }
        }
        Collections.sort(out, (a, b) -> {
            if (a.ganztag != b.ganztag) return a.ganztag ? -1 : 1;
            return Long.compare(a.startMs, b.startMs);
        });
        return out;
    }

    private static long parseIso(String s) {
        long t = Anwesenheit.zeit(s);
        if (t > 0) return t;
        if (s != null && s.length() >= 16) {
            try {
                Calendar c = tag(s.substring(0, 10));
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(s.substring(11, 13)));
                c.set(Calendar.MINUTE, Integer.parseInt(s.substring(14, 16)));
                return c.getTimeInMillis();
            } catch (NumberFormatException ignored) {}
        }
        return System.currentTimeMillis();
    }

    static String hm(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** Generic privacy-free demo events for visual verification. */
    static List<Termin> demoEreignisse(Calendar wochenStart) {
        List<Termin> out = new ArrayList<>();
        out.add(demoEv("d1", wochenStart, 0, 9, 0, 10, 30, "Team-Abstimmung", "Büro", "Arbeit", FARBEN[0], false));
        out.add(demoEv("d2", wochenStart, 1, 14, 0, 15, 0, "Zahnarzt", "Innenstadt", "Privat", FARBEN[1], false));
        out.add(demoEv("d3", wochenStart, 2, 18, 0, 19, 30, "Sport", "Halle Nord", "Privat", FARBEN[2], false));
        out.add(demoEv("d4", wochenStart, 3, 10, 0, 11, 30, "Werkstatt-Termin", "", "Privat", FARBEN[1], false));
        out.add(demoEv("d5", wochenStart, 3, 10, 30, 12, 0, "Paket-Lieferung", "", "Familie", FARBEN[3], false));
        out.add(demoEv("d6", wochenStart, 4, 0, 0, 0, 0, "Urlaubstag", "", "Privat", FARBEN[2], true));
        out.add(demoEv("d7", wochenStart, 5, 11, 0, 13, 0, "Wochenmarkt & Brunch", "Marktplatz", "Familie", FARBEN[0], false));
        out.add(demoEv("d8", wochenStart, 6, 15, 0, 17, 30, "Ausflug in den Park", "Stadtpark", "Familie", FARBEN[2], false));
        return out;
    }

    private static Termin demoEv(String uid, Calendar base, int plusTage, int sh, int sm, int eh, int em,
                                 String titel, String ort, String kal, int col, boolean ganz) {
        Calendar s = (Calendar) base.clone();
        s.add(Calendar.DAY_OF_MONTH, plusTage);
        mitternacht(s);
        Calendar e = (Calendar) s.clone();
        if (ganz) {
            e.add(Calendar.DAY_OF_MONTH, 1);
        } else {
            s.set(Calendar.HOUR_OF_DAY, sh); s.set(Calendar.MINUTE, sm);
            e.set(Calendar.HOUR_OF_DAY, eh); e.set(Calendar.MINUTE, em);
        }
        Termin t = new Termin();
        t.uid = uid; t.titel = titel; t.ort = ort; t.kalender = kal; t.farbe = col; t.ganztag = ganz;
        t.startMs = s.getTimeInMillis(); t.endeMs = e.getTimeInMillis();
        t.datum = datumKey(s); t.zeit = ganz ? "ganztägig" : hm(t.startMs) + "–" + hm(t.endeMs);
        return t;
    }

    private static String text(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    /** calendar.familien_kalender -> Familien Kalender (display only, stays on the device). */
    private static String name(String entity) {
        String n = entity.startsWith("calendar.") ? entity.substring(9) : entity;
        StringBuilder sb = new StringBuilder();
        for (String w : n.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
