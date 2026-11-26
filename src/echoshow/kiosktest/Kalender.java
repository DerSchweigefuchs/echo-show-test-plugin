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

/** Read-only calendar view: one calendar.get_events call (return_response) through host.haCall for a day, weekend, week, or marked days. */
final class Kalender {
    static final Pattern TAG = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ENTITY = Pattern.compile("calendar\\.[a-z0-9_]{1,250}");
    static final int[] FARBEN = {0xFF5AB4F0, 0xFFF2875A, 0xFF7BD389, 0xFFA99CF0, 0xFFF0B45A, 0xFFF06A8A, 0xFF5AD2C8};

    static final class Termin {
        String datum, zeit, titel, ort, kalender, sortierung;
        int farbe;
    }

    /** Requested date range and highlighted days (from param: tag, zeitraum, tage). */
    static final class Spanne {
        final Calendar start;
        final int tageAnzahl;
        final Set<String> markiert;
        final String zeitraum;

        Spanne(Calendar start, int tageAnzahl, Set<String> markiert, String zeitraum) {
            this.start = start;
            this.tageAnzahl = tageAnzahl;
            this.markiert = markiert;
            this.zeitraum = zeitraum;
        }
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
        mitternacht(c);
        return c;
    }

    private static void mitternacht(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    static String datumKey(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(c.getTime());
    }

    /** Resolves param {tag, zeitraum: tag|wochenende|woche, tage: [yyyy-MM-dd, ...]} into a concrete range. */
    static Spanne spanne(Map<String, Object> param) {
        String tagStr = param != null && param.get("tag") instanceof String ? ((String) param.get("tag")).trim() : null;
        String zr = param != null && param.get("zeitraum") instanceof String ? ((String) param.get("zeitraum")).trim().toLowerCase(Locale.ROOT) : "tag";
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

        if (!tageListe.isEmpty()) {
            Collections.sort(tageListe);
            Calendar s = tag(tageListe.get(0));
            Calendar e = tag(tageListe.get(tageListe.size() - 1));
            int diff = (int) Math.round((e.getTimeInMillis() - s.getTimeInMillis()) / 86_400_000.0) + 1;
            int n = Math.max(1, Math.min(14, diff));
            if ("woche".equals(zr) && n < 7) n = 7;
            return new Spanne(s, n, markiert, n > 1 ? ("woche".equals(zr) ? "woche" : "tage") : "tag");
        }

        if ("wochenende".equals(zr)) {
            Calendar s = tag(tagStr);
            int dow = s.get(Calendar.DAY_OF_WEEK);
            if (tagStr == null || tagStr.isEmpty()) {
                // If already Saturday -> Sat+Sun; if Sunday -> Sun only? Better: Sat+Sun of current weekend
                if (dow == Calendar.SUNDAY) {
                    s.add(Calendar.DAY_OF_MONTH, -1);
                } else if (dow != Calendar.SATURDAY) {
                    int bisSa = (Calendar.SATURDAY - dow + 7) % 7;
                    s.add(Calendar.DAY_OF_MONTH, bisSa);
                }
            }
            markiert.add(datumKey(s));
            Calendar so = (Calendar) s.clone();
            so.add(Calendar.DAY_OF_MONTH, 1);
            markiert.add(datumKey(so));
            return new Spanne(s, 2, markiert, "wochenende");
        }

        if ("woche".equals(zr)) {
            Calendar s = tag(tagStr);
            if (tagStr != null && TAG.matcher(tagStr).matches()) markiert.add(datumKey(s));
            return new Spanne(s, 7, markiert, "woche");
        }

        Calendar s = tag(tagStr);
        if (tagStr != null && TAG.matcher(tagStr).matches()) markiert.add(datumKey(s));
        return new Spanne(s, 1, markiert, "tag");
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
        return anfrage(entities, start, 1);
    }

    static Map<String, Object> anfrage(String entities, Calendar start, int tageAnzahl) {
        Calendar ende = (Calendar) start.clone();
        ende.add(Calendar.DAY_OF_MONTH, Math.max(1, Math.min(14, tageAnzahl)));
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

    static List<Termin> lesen(Object data, Calendar tag) {
        return lesen(data, tag, 1);
    }

    /** HA's call_service result {context, response: {calendar.x: {events: [...]}}} expanded across the requested days. */
    static List<Termin> lesen(Object data, Calendar start, int tageAnzahl) {
        List<Termin> out = new ArrayList<>();
        if (!(data instanceof Map)) return out;
        Object response = ((Map<?, ?>) data).get("response");
        if (!(response instanceof Map)) return out;
        int nTage = Math.max(1, Math.min(14, tageAnzahl));
        List<String> tageKeys = new ArrayList<>(nTage);
        Calendar cur = (Calendar) start.clone();
        for (int d = 0; d < nTage; d++) {
            tageKeys.add(datumKey(cur));
            cur.add(Calendar.DAY_OF_MONTH, 1);
        }

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
                String sRaw = text(ev.get("start")), eRaw = text(ev.get("end"));
                String titel = text(ev.get("summary"));
                if (titel.isEmpty()) titel = "(ohne Titel)";
                String ort = text(ev.get("location"));
                String kalName = name(entity);
                int farbe = FARBEN[i % FARBEN.length];
                boolean ganztag = sRaw.length() == 10;
                String sDay = sRaw.length() >= 10 ? sRaw.substring(0, 10) : tageKeys.get(0);
                String eDay = eRaw.length() >= 10 ? eRaw.substring(0, 10) : sDay;

                for (String dk : tageKeys) {
                    boolean trifft;
                    if (ganztag) {
                        // All-day end date in HA/iCal is exclusive
                        trifft = dk.compareTo(sDay) >= 0 && (eDay.compareTo(sDay) > 0 ? dk.compareTo(eDay) < 0 : dk.equals(sDay));
                    } else {
                        trifft = dk.compareTo(sDay) >= 0 && dk.compareTo(eDay) <= 0
                            && !(dk.equals(eDay) && eRaw.length() >= 16 && eRaw.substring(11, 16).equals("00:00") && !dk.equals(sDay));
                    }
                    if (!trifft && nTage == 1) trifft = true;   // single-day fallback for ongoing events
                    if (!trifft) continue;

                    Termin t = new Termin();
                    t.datum = dk;
                    t.titel = titel;
                    t.ort = ort;
                    t.kalender = kalName;
                    t.farbe = farbe;
                    if (ganztag) {
                        t.zeit = "ganztägig";
                        t.sortierung = dk + "|0";
                    } else if (sRaw.length() >= 16 && sRaw.startsWith(dk)) {
                        t.zeit = sRaw.substring(11, 16) + (eRaw.length() >= 16 && eRaw.startsWith(dk) ? "–" + eRaw.substring(11, 16) : "");
                        t.sortierung = dk + "|1" + sRaw.substring(11, 16);
                    } else {
                        t.zeit = "läuft";
                        t.sortierung = dk + "|05";
                    }
                    out.add(t);
                }
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
