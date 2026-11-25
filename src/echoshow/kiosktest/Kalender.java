package echoshow.kiosktest;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Read-only day list: one calendar.get_events call (return_response) through host.haCall. */
final class Kalender {
    static final Pattern TAG = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ENTITY = Pattern.compile("calendar\\.[a-z0-9_]{1,250}");
    static final int[] FARBEN = {0xFF5AB4F0, 0xFFF2875A, 0xFF7BD389, 0xFFA99CF0, 0xFFF0B45A, 0xFFF06A8A, 0xFF5AD2C8};

    static final class Termin {
        String zeit, titel, ort, kalender, sortierung;
        int farbe;
    }

    private Kalender() {}

    /** Midnight of the requested day (yyyy-MM-dd) or today. */
    static Calendar tag(String tag) {
        Calendar c = Calendar.getInstance();
        if (tag != null && TAG.matcher(tag).matches()) {
            try {
                c.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(tag));
            } catch (java.text.ParseException ignored) {
                c = Calendar.getInstance();
            }
        }
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
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

    /** The websocket message (without id) for host.haCall. */
    static Map<String, Object> anfrage(String entities, Calendar start) {
        Calendar ende = (Calendar) start.clone();
        ende.add(Calendar.DAY_OF_MONTH, 1);
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

    /** "all" or a list of valid calendar entity IDs from the setting. */
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

    /** HA's call_service result {context, response: {calendar.x: {events: [...]}}} as sorted rows. */
    static List<Termin> lesen(Object data, Calendar tag) {
        List<Termin> out = new ArrayList<>();
        if (!(data instanceof Map)) return out;
        Object response = ((Map<?, ?>) data).get("response");
        if (!(response instanceof Map)) return out;
        String heute = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(tag.getTime());
        List<String> kalender = new ArrayList<>();
        for (Object k : ((Map<?, ?>) response).keySet()) kalender.add(String.valueOf(k));
        Collections.sort(kalender);
        for (int i = 0; i < kalender.size(); i++) {
            String entity = kalender.get(i);
            Object eintrag = ((Map<?, ?>) response).get(entity);
            Object events = eintrag instanceof Map ? ((Map<?, ?>) eintrag).get("events") : null;
            if (!(events instanceof List)) continue;
            for (Object e : (List<?>) events) {
                if (!(e instanceof Map)) continue;
                Map<?, ?> ev = (Map<?, ?>) e;
                String start = text(ev.get("start")), ende = text(ev.get("end"));
                Termin t = new Termin();
                t.titel = text(ev.get("summary"));
                if (t.titel.isEmpty()) t.titel = "(ohne Titel)";
                t.ort = text(ev.get("location"));
                t.kalender = name(entity);
                t.farbe = FARBEN[i % FARBEN.length];
                boolean ganztag = start.length() == 10;
                if (ganztag) {
                    t.zeit = "ganztägig";
                    t.sortierung = "0";
                } else if (start.length() >= 16 && start.startsWith(heute)) {
                    t.zeit = start.substring(11, 16) + (ende.length() >= 16 && ende.startsWith(heute) ? "–" + ende.substring(11, 16) : "");
                    t.sortierung = "1" + start.substring(11, 16);
                } else {
                    t.zeit = "läuft";
                    t.sortierung = "05";
                }
                out.add(t);
            }
        }
        Collections.sort(out, (a, b) -> a.sortierung.compareTo(b.sortierung));
        return out;
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
