package echoshow.kiosktest;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live timer & alarm data from Home Assistant (one render_template subscription), matching timer_ansicht.py:
 * - Pool timers (timer.dot_timer_1..6 + optional name/target helpers) and any other active/paused HA timer.* entities
 * - Echo native timer sensors (sensor.*_next_timer / *_ringing_timers)
 * - Ringing flags (input_boolean.timer_klingelt, input_boolean.wecker_klingelt)
 * - Active alarms within the next 24h (input_datetime.wecker* + input_boolean.wecker*_aktiv)
 * Plain Java without Android UI dependencies.
 */
final class TimerDaten {

    static final class TimerEintrag {
        String entityId = "";
        int nr;                  // >0 = dot_timer_N, 0 = generic ringing, -1 = native Echo timer, -2 = native ringing, -3 = alarm ringing, -10 = generic HA timer
        String art = "timer";    // "timer" | "wecker"
        String status = "active";// "active" | "paused" | "ring" | "native"
        String label = "Timer";
        String geraet = "";
        long dauerMs;
        long endeMs;             // epoch ms when active/native
        long restMs;             // remaining ms when paused

        long restJetzt(long nowMs) {
            if ("paused".equals(status)) return Math.max(0L, restMs);
            if ("ring".equals(status)) return 0L;
            return Math.max(0L, endeMs - nowMs);
        }
    }

    static final class WeckerEintrag {
        int nr;
        long tsMs;
        int stunde, minute;
        String tage = "";
        String geraet = "";
    }

    static final class Zustand {
        List<TimerEintrag> timer = Collections.emptyList();
        List<WeckerEintrag> wecker = Collections.emptyList();
        long empfangenMs;
    }

    private TimerDaten() {}

    /** Single read-only render_template subscription covering all HA timers, Echo timers and alarms dynamically. */
    static Map<String, Object> abo() {
        String tpl = "{%- set out_t = namespace(l=[]) -%}"
            + "{%- set kl = is_state('input_boolean.timer_klingelt', 'on') -%}"
            + "{%- set wkl = is_state('input_boolean.wecker_klingelt', 'on') -%}"
            + "{%- set wger = states('input_text.wecker_klingelt_geraet') -%}"
            + "{%- for s in states.timer -%}"
            + "{%- set obj = s.object_id -%}"
            + "{%- set ist_dot = obj.startswith('dot_timer_') and obj[10:].isdigit() -%}"
            + "{%- set nr = (obj[10:] | int) if ist_dot else -10 -%}"
            + "{%- set nm = states('input_text.' ~ obj ~ '_name') if ist_dot else '' -%}"
            + "{%- set zl = states('input_text.' ~ obj ~ '_ziel') if ist_dot else '' -%}"
            + "{%- set zname = state_attr(zl, 'friendly_name') if (zl and zl not in ['unknown', 'unavailable', '']) else '' -%}"
            + "{%- if s.state in ['active', 'paused'] or (ist_dot and kl and s.state == 'idle' and nm not in ['', 'unknown', 'unavailable'] and s.attributes.get('last_transition') == 'finished') -%}"
            + "{%- set out_t.l = out_t.l + [{'eid': s.entity_id, 'nr': nr, 'st': s.state, 'fn': s.name, 'nm': '' if nm in ['unknown','unavailable'] else nm, "
            + "'dur': s.attributes.duration | default('0:00:00'), 'fin': s.attributes.finishes_at | default(''), 'rem': s.attributes.remaining | default('0:00:00'), 'dev': zname or ''}] -%}"
            + "{%- endif -%}"
            + "{%- endfor -%}"
            + "{%- set out_nt = namespace(l=[]) -%}"
            + "{%- for s in states.sensor if s.object_id.endswith('_next_timer') and s.state not in ['unknown', 'unavailable', ''] -%}"
            + "{%- set pref = s.object_id[:-11] -%}"
            + "{%- set nn = states('sensor.' ~ pref ~ '_next_timer_name') -%}"
            + "{%- set ln = states('sensor.' ~ pref ~ '_next_timer_length') -%}"
            + "{%- set out_nt.l = out_nt.l + [{'fin': s.state, 'nm': '' if nn in ['unknown','unavailable'] else nn, 'len': '' if ln in ['unknown','unavailable'] else ln}] -%}"
            + "{%- endfor -%}"
            + "{%- set out_rn = namespace(l=[]) -%}"
            + "{%- for s in states.sensor if s.object_id.endswith('_ringing_timers') and s.state not in ['unknown', 'unavailable', '', 'none', '0'] -%}"
            + "{%- set out_rn.l = out_rn.l + [s.state] -%}"
            + "{%- endfor -%}"
            + "{%- set out_w = namespace(l=[]) -%}"
            + "{%- for n in range(1, 7) -%}"
            + "{%- set dt_id = 'input_datetime.wecker' if n == 1 else 'input_datetime.wecker_' ~ n -%}"
            + "{%- set ak_id = 'input_boolean.wecker_aktiv' if n == 1 else 'input_boolean.wecker_' ~ n ~ '_aktiv' -%}"
            + "{%- if is_state(ak_id, 'on') and states[dt_id] is defined -%}"
            + "{%- set dt = states[dt_id] -%}"
            + "{%- set tg = states('input_text.wecker_' ~ n ~ '_tage') -%}"
            + "{%- set zl = states('input_text.wecker_' ~ n ~ '_ziel') -%}"
            + "{%- set zname = state_attr(zl, 'friendly_name') if (zl and zl not in ['unknown', 'unavailable', '']) else '' -%}"
            + "{%- set out_w.l = out_w.l + [{'nr': n, 'ts': dt.attributes.timestamp | default(0), 'h': dt.attributes.hour | default(0), 'm': dt.attributes.minute | default(0), "
            + "'tg': '' if tg in ['unknown','unavailable'] else tg, 'dev': zname or ''}] -%}"
            + "{%- endif -%}"
            + "{%- endfor -%}"
            + "{{ {'t': out_t.l, 'nt': out_nt.l, 'rn': out_rn.l, 'kl': kl, 'wkl': wkl, 'wdev': (state_attr(wger, 'friendly_name') or '') if wger not in ['', 'unknown', 'unavailable'] else '', 'w': out_w.l} | tojson }}";
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "render_template");
        msg.put("template", tpl);
        return msg;
    }

    static Zustand lesen(Object result, Anwesenheit.JsonLeser json, long nowMs) {
        Object o = result instanceof String ? json.lesen((String) result) : result;
        Zustand z = new Zustand();
        z.empfangenMs = nowMs;
        if (!(o instanceof Map)) return z;
        Map<?, ?> m = (Map<?, ?>) o;
        boolean kl = Boolean.TRUE.equals(m.get("kl"));
        boolean wkl = Boolean.TRUE.equals(m.get("wkl"));
        boolean ringFound = false;

        List<TimerEintrag> liste = new ArrayList<>();
        if (m.get("t") instanceof List) {
            for (Object item : (List<?>) m.get("t")) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> tm = (Map<?, ?>) item;
                String st = str(tm.get("st"));
                int nr = numInt(tm.get("nr"), -10);
                String nm = str(tm.get("nm"));
                String fn = str(tm.get("fn"));
                TimerEintrag e = new TimerEintrag();
                e.entityId = str(tm.get("eid"));
                e.nr = nr;
                if (nr > 0) e.label = !nm.isEmpty() ? capitalize(nm) + "-Timer" : "Timer";
                else e.label = !fn.isEmpty() ? fn : "Timer";
                e.dauerMs = hms(str(tm.get("dur")));
                e.geraet = str(tm.get("dev"));
                if ("active".equals(st)) {
                    long fin = Anwesenheit.zeit(tm.get("fin"));
                    if (fin > 0) {
                        e.status = "active";
                        e.endeMs = fin;
                        liste.add(e);
                    }
                } else if ("paused".equals(st)) {
                    e.status = "paused";
                    e.restMs = hms(str(tm.get("rem")));
                    liste.add(e);
                } else if ("idle".equals(st) && kl) {
                    e.status = "ring";
                    ringFound = true;
                    liste.add(e);
                }
            }
        }
        if (kl && !ringFound) {
            TimerEintrag r = new TimerEintrag();
            r.nr = 0; r.label = "Timer"; r.status = "ring";
            liste.add(r);
        }
        if (m.get("nt") instanceof List) {
            for (Object item : (List<?>) m.get("nt")) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> nm = (Map<?, ?>) item;
                long fin = Anwesenheit.zeit(nm.get("fin"));
                if (fin <= nowMs) continue;
                TimerEintrag e = new TimerEintrag();
                e.nr = -1;
                e.status = "native";
                String name = str(nm.get("nm"));
                e.label = name.isEmpty() ? "Timer" : name;
                e.endeMs = fin;
                e.dauerMs = Math.round(numDbl(nm.get("len"), 0) * 1000.0);
                e.geraet = "am Echo Show";
                liste.add(e);
            }
        }
        if (wkl) {
            TimerEintrag w = new TimerEintrag();
            w.nr = -3; w.art = "wecker"; w.label = "Wecker"; w.status = "ring";
            w.geraet = str(m.get("wdev"));
            liste.add(w);
        }
        if (m.get("rn") instanceof List) {
            boolean hatRing = false;
            for (TimerEintrag e : liste) if ("ring".equals(e.status)) hatRing = true;
            if (!hatRing) {
                for (Object ro : (List<?>) m.get("rn")) {
                    String s = str(ro);
                    if (s.isEmpty()) continue;
                    TimerEintrag e = new TimerEintrag();
                    e.nr = -2; e.label = s; e.status = "ring"; e.geraet = "am Echo Show";
                    liste.add(e);
                    break;
                }
            }
        }
        Collections.sort(liste, (a, b) -> {
            int ra = "ring".equals(a.status) ? 0 : 1, rb = "ring".equals(b.status) ? 0 : 1;
            if (ra != rb) return ra - rb;
            return Long.compare(a.restJetzt(nowMs), b.restJetzt(nowMs));
        });
        z.timer = liste;

        List<WeckerEintrag> wecker = new ArrayList<>();
        if (m.get("w") instanceof List) {
            for (Object item : (List<?>) m.get("w")) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> wm = (Map<?, ?>) item;
                long ts = Math.round(numDbl(wm.get("ts"), 0) * 1000.0);
                if (ts <= nowMs || ts - nowMs > 24L * 3600_000L) continue;
                WeckerEintrag w = new WeckerEintrag();
                w.nr = numInt(wm.get("nr"), 1);
                w.tsMs = ts;
                w.stunde = numInt(wm.get("h"), 0);
                w.minute = numInt(wm.get("m"), 0);
                w.tage = str(wm.get("tg"));
                w.geraet = str(wm.get("dev"));
                wecker.add(w);
            }
        }
        Collections.sort(wecker, (a, b) -> Long.compare(a.tsMs, b.tsMs));
        z.wecker = wecker;
        return z;
    }

    /** Formats remaining ms matching timer_ansicht.py ("9:42" or "1:05:12"). */
    static String fmt(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        long h = s / 3600; s -= h * 3600;
        long m = s / 60; s -= m * 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    static String uhr(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    static String tagText(long tsMs, long nowMs) {
        Calendar a = Calendar.getInstance(); a.setTimeInMillis(tsMs);
        Calendar b = Calendar.getInstance(); b.setTimeInMillis(nowMs);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) ? "heute" : "morgen";
    }

    static String tageLabel(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "einmalig";
        String norm = raw.trim().toLowerCase(Locale.ROOT);
        if ("mo,di,mi,do,fr,sa,so".equals(norm)) return "täglich";
        if ("mo,di,mi,do,fr".equals(norm)) return "Mo–Fr";
        if ("sa,so".equals(norm)) return "Wochenende";
        String[] keys = {"mo", "di", "mi", "do", "fr", "sa", "so"};
        String[] nm = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
        StringBuilder sb = new StringBuilder();
        for (String p : norm.split(",")) {
            String k = p.trim();
            for (int i = 0; i < keys.length; i++) {
                if (keys[i].equals(k)) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(nm[i]);
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : "einmalig";
    }

    /** Generic demo state for offline visual previews (zero personal data). */
    static Zustand demo(long nowMs) {
        Zustand z = new Zustand();
        z.empfangenMs = nowMs;
        List<TimerEintrag> ts = new ArrayList<>();
        TimerEintrag t1 = new TimerEintrag();
        t1.nr = 1; t1.entityId = "timer.dot_timer_1"; t1.label = "Nudeln-Timer"; t1.status = "active";
        t1.dauerMs = 10 * 60_000L; t1.endeMs = nowMs + 6 * 60_000L + 18_000L; t1.geraet = "Küche";
        ts.add(t1);
        TimerEintrag t2 = new TimerEintrag();
        t2.nr = 2; t2.entityId = "timer.dot_timer_2"; t2.label = "Ofen-Timer"; t2.status = "paused";
        t2.dauerMs = 25 * 60_000L; t2.restMs = 14 * 60_000L + 30_000L; t2.geraet = "Küche";
        ts.add(t2);
        z.timer = ts;

        List<WeckerEintrag> ws = new ArrayList<>();
        WeckerEintrag w1 = new WeckerEintrag();
        w1.nr = 1; w1.stunde = 6; w1.minute = 30; w1.tsMs = nowMs + 12L * 3600_000L; w1.tage = "mo,di,mi,do,fr"; w1.geraet = "Schlafzimmer";
        ws.add(w1);
        z.wecker = ws;
        return z;
    }

    private static long hms(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] p = s.split(":");
        try {
            long h = p.length > 0 ? Long.parseLong(p[0].trim()) : 0;
            long m = p.length > 1 ? Long.parseLong(p[1].trim()) : 0;
            long sec = p.length > 2 ? Math.round(Double.parseDouble(p[2].trim())) : 0;
            return (h * 3600 + m * 60 + sec) * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static int numInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return o == null ? def : Integer.parseInt(String.valueOf(o).trim()); } catch (NumberFormatException e) { return def; }
    }

    private static double numDbl(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return o == null ? def : Double.parseDouble(String.valueOf(o).trim()); } catch (NumberFormatException e) { return def; }
    }
}
