package echoshow.kiosktest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Data models and parsers for the native Music Player view (echo_show/musik/daten & echo_show/musik/liedtext).
 * Plain Java without Android UI dependencies; API 24 compatible.
 */
final class MusikDaten {

    static final class Player {
        String entityId = "";
        String name = "";
        String icon = "mdi:speaker";
        String state = "idle";
        String title = "";
        String artist = "";
        String album = "";
        String picture = "";
        Double volume;
        double position;
        double duration;
        long updatedAtMs;
        boolean canGroup;
        List<String> groupMembers = Collections.emptyList();

        boolean istAktiv() {
            return !"off".equals(state) && !"unavailable".equals(state) && !"unknown".equals(state) && !title.isEmpty();
        }

        boolean istLive() {
            return "playing".equals(state) || "paused".equals(state);
        }

        String objectId() {
            return entityId.startsWith("media_player.") ? entityId.substring(13) : entityId;
        }

        double aktuellePosition(long nowMs) {
            double p = position;
            if ("playing".equals(state) && updatedAtMs > 0) {
                p += Math.max(0.0, (nowMs - updatedAtMs) / 1000.0);
            }
            if (duration > 0 && p > duration) p = duration;
            return Math.max(0.0, p);
        }
    }

    static final class Zustand {
        String aktivPlayer = "";
        String helper = "";
        boolean liedtextAn;
        boolean autoplay;
        boolean crossfade;
        Map<String, Double> vorlauf = new LinkedHashMap<>();
        double aktivVorlauf;
        List<Player> players = Collections.emptyList();
        long empfangenMs;

        Player aktuellerPlayer() {
            if (players.isEmpty()) return null;
            if (!aktivPlayer.isEmpty()) {
                for (Player p : players) if (aktivPlayer.equals(p.entityId)) return p;
            }
            for (Player p : players) if ("playing".equals(p.state)) return p;
            return players.get(0);
        }

        double vorlaufFuer(Player p) {
            if (p == null) return 0.0;
            Double v = vorlauf.get(p.objectId());
            if (v != null) return v;
            if (p.entityId.equals(aktivPlayer)) return aktivVorlauf;
            return 0.0;
        }
    }

    static final class Liedzeile {
        final double t;   // seconds, or -1 for unsynced
        final String s;
        Liedzeile(double t, String s) {
            this.t = t;
            this.s = s == null ? "" : s;
        }
    }

    static final class Liedtext {
        boolean found;
        boolean synced;
        List<Liedzeile> lines = Collections.emptyList();
        String title = "";
        String artist = "";
    }

    private MusikDaten() {}

    /**
     * Compact render_template subscription watching all non-excluded media_player entities and active music helpers
     * while the native Music Player view is open, so track/state/volume/group/lyrics changes trigger an immediate reload.
     */
    static Map<String, Object> liveAbo(String ausschlussCsv, String helperEntity) {
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
        String selHelper = (helperEntity != null && helperEntity.trim().matches("[a-z0-9_]+\\.[a-z0-9_]{1,250}"))
            ? helperEntity.trim() : "input_text.musik_player";
        String tpl = "{%- set excl = " + ex + " -%}"
            + "{%- set ns = namespace(l=['versatz=' ~ states('input_boolean.musik_liedtext_versatz'),"
            + "states('" + selHelper + "'),"
            + "states('input_boolean.musik_liedtext'),"
            + "states('input_boolean.musik_autoplay'),"
            + "states('input_boolean.musik_crossfade'),"
            + "states('input_text.musik_liedtext_vorlauf_geraete'),"
            + "states('input_text.musik_geraete'),"
            + "states('input_text.musik_geraete_icons')]) -%}"
            + "{%- for p in states.media_player -%}"
            + "{%- if p.entity_id not in excl and p.state != 'unavailable' -%}"
            + "{%- set ns.l = ns.l + [p.entity_id ~ ':' ~ p.state ~ ':' ~ (p.attributes.media_title | default('')) ~ ':'"
            + " ~ (p.attributes.media_artist | default('')) ~ ':' ~ (p.attributes.media_album_name | default('')) ~ ':'"
            + " ~ (p.attributes.entity_picture | default(p.attributes.entity_picture_local | default(''))) ~ ':'"
            + " ~ (p.attributes.volume_level | default('')) ~ ':' ~ (p.attributes.media_position | default('')) ~ ':'"
            + " ~ (p.attributes.media_duration | default('')) ~ ':' ~ (p.attributes.media_position_updated_at | default('')) ~ ':'"
            + " ~ (p.attributes.supported_features | default('')) ~ ':' ~ (p.attributes.group_members | default([]) | join(','))] -%}"
            + "{%- endif -%}{%- endfor -%}"
            + "{{ ns.l | join('|') }}";
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "render_template");
        msg.put("template", tpl);
        return msg;
    }

    static Zustand lesen(Object data, long nowMs) {
        Zustand z = new Zustand();
        z.empfangenMs = nowMs;
        if (!(data instanceof Map)) return z;
        Map<?, ?> m = (Map<?, ?>) data;
        z.aktivPlayer = str(m.get("aktiv_player"));
        z.helper = str(m.get("helper"));
        z.liedtextAn = Boolean.TRUE.equals(m.get("liedtext_an"));
        z.autoplay = Boolean.TRUE.equals(m.get("autoplay"));
        z.crossfade = Boolean.TRUE.equals(m.get("crossfade"));
        z.aktivVorlauf = numDbl(m.get("aktiv_vorlauf"), 0.0);

        if (m.get("vorlauf") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("vorlauf")).entrySet()) {
                String k = str(e.getKey());
                Double v = zahl(e.getValue());
                if (!k.isEmpty() && v != null) z.vorlauf.put(k, v);
            }
        }

        if (m.get("players") instanceof List) {
            List<Player> list = new ArrayList<>();
            for (Object item : (List<?>) m.get("players")) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> pm = (Map<?, ?>) item;
                Player p = new Player();
                p.entityId = str(pm.get("entity_id"));
                if (p.entityId.isEmpty()) continue;
                p.name = str(pm.get("name"));
                if (p.name.isEmpty()) p.name = p.objectId();
                String ic = str(pm.get("icon"));
                p.icon = ic.isEmpty() ? "mdi:speaker" : (ic.startsWith("mdi:") ? ic : "mdi:" + ic);
                p.state = str(pm.get("state"));
                if (p.state.isEmpty()) p.state = "idle";
                p.title = str(pm.get("title"));
                p.artist = str(pm.get("artist"));
                p.album = str(pm.get("album"));
                p.picture = str(pm.get("picture"));
                p.volume = zahl(pm.get("volume"));
                p.position = numDbl(pm.get("position"), 0.0);
                p.duration = numDbl(pm.get("duration"), 0.0);
                p.updatedAtMs = zeit(pm.get("updated_at"));
                p.canGroup = Boolean.TRUE.equals(pm.get("can_group"));
                if (pm.get("group_members") instanceof List) {
                    List<String> gms = new ArrayList<>();
                    for (Object g : (List<?>) pm.get("group_members")) {
                        String gs = str(g);
                        if (!gs.isEmpty()) gms.add(gs);
                    }
                    p.groupMembers = gms;
                }
                list.add(p);
            }
            z.players = list;
        }
        return z;
    }

    static Liedtext lesenLiedtext(Object data) {
        Liedtext lt = new Liedtext();
        if (!(data instanceof Map)) return lt;
        Map<?, ?> m = (Map<?, ?>) data;
        lt.found = Boolean.TRUE.equals(m.get("found"));
        lt.synced = Boolean.TRUE.equals(m.get("synced"));
        if (m.get("track") instanceof Map) {
            Map<?, ?> tm = (Map<?, ?>) m.get("track");
            lt.title = str(tm.get("title"));
            lt.artist = str(tm.get("artist"));
        }
        if (m.get("lines") instanceof List) {
            List<Liedzeile> out = new ArrayList<>();
            for (Object item : (List<?>) m.get("lines")) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> lm = (Map<?, ?>) item;
                double t = numDbl(lm.get("t"), -1.0);
                String s = strRaw(lm.get("s"));
                out.add(new Liedzeile(t, s));
            }
            lt.lines = out;
        }
        return lt;
    }

    static String fmtZeit(double sec) {
        int s = Math.max(0, (int) Math.round(sec));
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    static String formatArtist(String artist) {
        if (artist == null || artist.isEmpty()) return "";
        return artist.replace("/", ", ");
    }

    static String vorlaufJson(Map<String, Double> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Double> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append("\":");
            double v = Math.round(e.getValue() * 100.0) / 100.0;
            sb.append(Double.toString(v));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static long zeit(Object val) {
        if (val instanceof Number) {
            double d = ((Number) val).doubleValue();
            return d > 1e11 ? Math.round(d) : Math.round(d * 1000.0);
        }
        if (val instanceof String) {
            return Anwesenheit.zeit(val);
        }
        return 0L;
    }

    private static Double zahl(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble(((String) o).trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static double numDbl(Object o, double fb) {
        Double v = zahl(o);
        return v != null ? v : fb;
    }

    private static String str(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o).trim();
        return "null".equals(s) ? "" : s;
    }

    private static String strRaw(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        return "null".equals(s.trim()) ? "" : s;
    }
}
