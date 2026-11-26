package echoshow.kiosktest;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Data models and parsers for the native "Mein Tag" and news reading view:
 * - echo_show/news/subscribe live status (briefing chips, active karaoke segment, queue, sources)
 * - hourly forecast strip from input_text.wetter_stunden and input_text.wetter_regen
 * Plain Java without Android UI dependencies so it can be unit-tested and rendered off-device.
 */
final class MorgenDaten {

    static final class Quelle {
        String name = "";
        int farbe = 0xFF8FA3B5;
    }

    static final class Meldung {
        String id = "", q = "", t = "", x = "", l = "", bild = "";
        double z;   // epoch seconds
    }

    static final class Segment {
        String id = "", art = "", text = "";
        double t0, dauer, jetzt;
    }

    static final class MorgenInfo {
        String name = "";
        List<String> termine = Collections.emptyList();
        String muell = "";
        int liste;
        String schirm = "";
        boolean glatt;

        boolean hatInhalt() {
            return !schirm.isEmpty() || glatt || !termine.isEmpty() || !muell.isEmpty() || liste > 0;
        }
    }

    static final class NewsStatus {
        boolean aktiv;
        String art = "";          // "morgen" | "news" | "eil" | ""
        String dash = "";
        List<String> ziel = Collections.emptyList();
        int n, i = -1;
        boolean pause;
        boolean karaoke = true;
        String ansicht = "vollbild";   // "vollbild" | "leiste" | "aus"
        String sprecherName = "";
        long clockOffsetMs;       // serverTimeMs - localTimeMs
        MorgenInfo morgen;
        Segment seg;
        List<Meldung> items = Collections.emptyList();
        Map<String, Quelle> quellen = Collections.emptyMap();
        boolean demo;

        Meldung aktuelleMeldung() {
            if (seg == null || seg.id.isEmpty()) return null;
            for (Meldung m : items) if (seg.id.equals(m.id)) return m;
            return null;
        }

        int aktuelleMeldungIndex() {
            if (seg == null || seg.id.isEmpty()) return -1;
            for (int k = 0; k < items.size(); k++) if (seg.id.equals(items.get(k).id)) return k;
            return -1;
        }

        Meldung naechsteMeldung() {
            if (items.isEmpty()) return null;
            int idx = aktuelleMeldungIndex();
            if (idx >= 0) return idx + 1 < items.size() ? items.get(idx + 1) : null;
            return items.get(0);
        }

        String quelleName(String q) {
            Quelle qu = quellen.get(q);
            return qu != null && !qu.name.isEmpty() ? qu.name : (q == null ? "" : q);
        }

        int quelleFarbe(String q) {
            Quelle qu = quellen.get(q);
            return qu != null ? qu.farbe : 0xFF8FA3B5;
        }

        float fortschritt(long nowMs) {
            if (seg == null || seg.dauer <= 0.01) return 0f;
            double p = (nowMs + clockOffsetMs - seg.t0 * 1000.0) / (seg.dauer * 1000.0);
            if (p < 0) return 0f;
            if (p > 1) return 1f;
            return (float) p;
        }
    }

    static final class Stunde {
        int stunde;
        String cond = "cloudy";
        int temp;
        float mm;
        Integer regenPct;
        boolean nacht;
    }

    static final class HeuteVerlauf {
        List<Stunde> stunden = Collections.emptyList();
        String zusammenfassung = "";
        String nassWort = "";   // word highlighted in blue if present in zusammenfassung
    }

    // ---- HA WebSocket commands -------------------------------------------------------------------------------------

    static Map<String, Object> aboAnfrage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "echo_show/news/subscribe");
        return m;
    }

    static Map<String, Object> steuernAnfrage(String aktion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "echo_show/news/steuern");
        m.put("aktion", aktion);
        return m;
    }

    // ---- parsing echo_show/news/subscribe event --------------------------------------------------------------------

    static NewsStatus lesen(Object evObj, long nowMs) {
        NewsStatus s = new NewsStatus();
        if (!(evObj instanceof Map)) return s;
        Map<?, ?> m = (Map<?, ?>) evObj;
        s.aktiv = Boolean.TRUE.equals(m.get("aktiv"));
        s.art = str(m.get("art"));
        s.dash = str(m.get("dash"));
        s.n = numInt(m.get("n"), 0);
        s.i = numInt(m.get("i"), -1);
        s.pause = Boolean.TRUE.equals(m.get("pause"));
        s.karaoke = !Boolean.FALSE.equals(m.get("karaoke"));
        String ans = str(m.get("ansicht"));
        s.ansicht = ans.isEmpty() ? "vollbild" : ans;
        s.sprecherName = str(m.get("name"));
        double jetzt = numDbl(m.get("jetzt"), 0);
        if (jetzt > 1e9) s.clockOffsetMs = Math.round(jetzt * 1000.0) - nowMs;

        if (m.get("ziel") instanceof List) {
            List<String> z = new ArrayList<>();
            for (Object o : (List<?>) m.get("ziel")) {
                String t = str(o);
                if (!t.isEmpty()) z.add(t);
            }
            s.ziel = z;
        }

        if (m.get("morgen") instanceof Map) {
            Map<?, ?> mm = (Map<?, ?>) m.get("morgen");
            MorgenInfo mi = new MorgenInfo();
            mi.name = str(mm.get("name"));
            mi.muell = str(mm.get("muell"));
            mi.schirm = str(mm.get("schirm"));
            mi.liste = numInt(mm.get("liste"), 0);
            mi.glatt = Boolean.TRUE.equals(mm.get("glatt"));
            if (mm.get("termine") instanceof List) {
                List<String> ts = new ArrayList<>();
                for (Object o : (List<?>) mm.get("termine")) {
                    String t = str(o);
                    if (!t.isEmpty()) ts.add(t);
                }
                mi.termine = ts;
            }
            s.morgen = mi;
        }

        if (m.get("seg") instanceof Map) {
            Map<?, ?> sm = (Map<?, ?>) m.get("seg");
            Segment seg = new Segment();
            seg.id = str(sm.get("id"));
            seg.art = str(sm.get("art"));
            seg.text = str(sm.get("text"));
            seg.t0 = numDbl(sm.get("t0"), 0);
            seg.dauer = numDbl(sm.get("dauer"), 0);
            seg.jetzt = numDbl(sm.get("jetzt"), 0);
            if (seg.jetzt > 1e9) s.clockOffsetMs = Math.round(seg.jetzt * 1000.0) - nowMs;
            s.seg = seg;
        }

        if (m.get("items") instanceof List) {
            List<Meldung> list = new ArrayList<>();
            for (Object o : (List<?>) m.get("items")) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> im = (Map<?, ?>) o;
                Meldung e = new Meldung();
                e.id = str(im.get("id"));
                e.q = str(im.get("q"));
                e.t = str(im.get("t"));
                e.x = str(im.get("x"));
                e.l = str(im.get("l"));
                e.bild = str(im.get("bild"));
                e.z = numDbl(im.get("z"), 0);
                if (!e.id.isEmpty() || !e.t.isEmpty()) list.add(e);
            }
            s.items = list;
        }

        if (m.get("quellen") instanceof Map) {
            Map<String, Quelle> qm = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : ((Map<?, ?>) m.get("quellen")).entrySet()) {
                if (!(en.getValue() instanceof Map)) continue;
                Map<?, ?> vm = (Map<?, ?>) en.getValue();
                Quelle q = new Quelle();
                q.name = str(vm.get("n"));
                q.farbe = hexFarbe(str(vm.get("c")), 0xFF8FA3B5);
                qm.put(String.valueOf(en.getKey()), q);
            }
            s.quellen = qm;
        }
        return s;
    }

    // ---- hourly forecast parsing (input_text.wetter_stunden & wetter_regen) ----------------------------------------

    static HeuteVerlauf heuteLesen(WetterLage.Daten wd, WetterLage lage, long nowMs) {
        HeuteVerlauf out = new HeuteVerlauf();
        if (wd == null || wd.stunden == null || wd.stunden.isEmpty()) return out;

        Map<Integer, Integer> regenMap = regenStunden(wd.regen, nowMs);
        int aufSt = stundeAusMs(wd.aufgang, 6), untSt = stundeAusMs(wd.untergang, 20);

        List<Stunde> list = new ArrayList<>();
        String[] teile = wd.stunden.split(";");
        for (String t : teile) {
            if (list.size() >= 8) break;
            String[] f = t.trim().split("\\|");
            if (f.length < 3) continue;
            try {
                Stunde h = new Stunde();
                h.stunde = Integer.parseInt(f[0].trim());
                h.cond = f[1].trim().isEmpty() ? "cloudy" : f[1].trim();
                h.temp = Math.round(Float.parseFloat(f[2].trim()));
                h.mm = f.length >= 4 && !f[3].trim().isEmpty() ? Float.parseFloat(f[3].trim()) : 0f;
                h.regenPct = regenMap.get(h.stunde);
                h.nacht = "clear-night".equals(h.cond) || h.stunde < aufSt || h.stunde >= untSt;
                list.add(h);
            } catch (NumberFormatException ignored) {}
        }
        out.stunden = list;
        if (list.isEmpty()) return out;

        // One-line summary identical to wetter.js
        int lo = 99, hi = -99;
        for (Stunde h : list) {
            if (h.temp < lo) lo = h.temp;
            if (h.temp > hi) hi = h.temp;
        }
        boolean nowWet = lage != null && (lage.tropfen > 0 || lage.flocken > 0 || lage.hagel > 0);
        int j = -1;
        String txt;
        if (nowWet) {
            j = ersteTrocken(list, 0);
            String nw = nassName(lage.cond);
            out.nassWort = nw;
            txt = "Heute " + nw + (j < 0 ? " noch mindestens bis " + uhrTxt((list.get(list.size() - 1).stunde + 1) % 24)
                : " noch bis " + uhrTxt(list.get(j).stunde));
        } else {
            int i = ersteNass(list, 0);
            if (i < 0) {
                txt = "Heute bleibt es trocken";
            } else {
                String nw = nassName(list.get(i).cond);
                out.nassWort = nw;
                j = ersteTrocken(list, i + 1);
                String span = j < 0 ? "ab " + uhrTxt(list.get(i).stunde)
                    : ("ab " + (list.get(i).stunde == 0 ? "Mitternacht" : String.valueOf(list.get(i).stunde)) + " bis " + uhrTxt(list.get(j).stunde));
                txt = "Heute " + nw + " " + span;
            }
        }
        if (j >= 0) {
            int k = ersteNass(list, j + 1);
            if (k >= 0) txt += " · ab " + uhrTxt(list.get(k).stunde) + " wieder";
        }
        txt += " · " + (lo == hi ? lo + "°" : lo + " bis " + hi + "°");
        out.zusammenfassung = txt;
        return out;
    }

    static boolean istNass(Stunde h) {
        boolean likely = h.regenPct == null || h.regenPct >= 45;
        if (!likely) return false;
        if (h.mm >= 0.1f) return true;
        String c = h.cond;
        return c.contains("rain") || c.contains("pour") || c.contains("snow") || c.contains("hail") || c.contains("lightning");
    }

    static String effCond(Stunde h) {
        if (h.regenPct != null && h.regenPct < 45 && (h.cond.contains("rain") || h.cond.contains("pour"))) return "cloudy";
        return h.cond;
    }

    private static int ersteNass(List<Stunde> l, int ab) {
        for (int i = ab; i < l.size(); i++) if (istNass(l.get(i))) return i;
        return -1;
    }

    private static int ersteTrocken(List<Stunde> l, int ab) {
        for (int i = ab; i < l.size(); i++) if (!istNass(l.get(i))) return i;
        return -1;
    }

    private static String nassName(String cond) {
        if (cond == null) return "Regen";
        if (cond.contains("snow")) return "Schnee";
        if ("hail".equals(cond)) return "Hagel";
        if (cond.contains("lightning")) return "Gewitter";
        return "Regen";
    }

    private static String uhrTxt(int h) { return h == 0 ? "Mitternacht" : h + " Uhr"; }

    private static int stundeAusMs(long ms, int fallback) {
        if (ms <= 0) return fallback;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    /** Parses input_text.wetter_regen: "YYYYMMDDHH|p0,p1,...|YYYYMMDD|d0,d1,..." -> hour -> pct. */
    private static Map<Integer, Integer> regenStunden(String roh, long nowMs) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        if (roh == null || roh.isEmpty()) return out;
        String[] parts = roh.split("\\|");
        if (parts.length < 2 || parts[0].length() < 10) return out;
        try {
            int y = Integer.parseInt(parts[0].substring(0, 4));
            int mo = Integer.parseInt(parts[0].substring(4, 6)) - 1;
            int d = Integer.parseInt(parts[0].substring(6, 8));
            int hh = Integer.parseInt(parts[0].substring(8, 10));
            Calendar c = Calendar.getInstance();
            c.set(y, mo, d, hh, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            long baseMs = c.getTimeInMillis();
            if (Math.abs(nowMs - baseMs) > 28L * 3600_000L) return out;   // stale
            String[] ps = parts[1].split(",");
            for (int i = 0; i < ps.length; i++) {
                String p = ps[i].trim();
                if (p.isEmpty()) continue;
                long tMs = baseMs + i * 3600_000L;
                if (tMs < nowMs - 3600_000L) continue;
                int hr = (hh + i) % 24;
                if (!out.containsKey(hr)) out.put(hr, Integer.parseInt(p));
            }
        } catch (NumberFormatException ignored) {}
        return out;
    }

    // ---- temperature colour scale (matches wetter.js T_SCALE) ------------------------------------------------------

    private static final int[][] T_SCALE = {
        {-5, 111, 168, 255}, {0, 154, 211, 255}, {10, 212, 226, 122}, {20, 242, 165, 74}, {30, 232, 85, 78}
    };

    static int tempFarbe(int t) {
        if (t <= T_SCALE[0][0]) return WetterLage.rgb(T_SCALE[0][1], T_SCALE[0][2], T_SCALE[0][3]);
        int last = T_SCALE.length - 1;
        if (t >= T_SCALE[last][0]) return WetterLage.rgb(T_SCALE[last][1], T_SCALE[last][2], T_SCALE[last][3]);
        for (int i = 1; i < T_SCALE.length; i++) {
            if (t <= T_SCALE[i][0]) {
                int[] a = T_SCALE[i - 1], b = T_SCALE[i];
                float k = (t - a[0]) / (float) (b[0] - a[0]);
                return WetterLage.rgb(
                    Math.round(a[1] + (b[1] - a[1]) * k),
                    Math.round(a[2] + (b[2] - a[2]) * k),
                    Math.round(a[3] + (b[3] - a[3]) * k));
            }
        }
        return 0xFFFFFFFF;
    }

    static String vorText(double epochSec, long nowMs) {
        if (epochSec <= 0) return "";
        long m = Math.max(0, Math.round((nowMs / 1000.0 - epochSec) / 60.0));
        if (m < 1) return "gerade eben";
        if (m < 60) return "vor " + m + " min";
        if (m < 1440) return "vor " + (m / 60) + " h";
        return "vor " + (m / 1440) + " T.";
    }

    static String mmText(float mm) {
        float v = Math.round(mm * 10f) / 10f;
        if (v == (int) v) return (int) v + " mm";
        return String.format(Locale.GERMANY, "%.1f mm", v);
    }

    // ---- generic privacy-free demo data for local previews & screenshots -------------------------------------------

    static NewsStatus demo(boolean storyPhase, boolean mehrPhase, long nowMs) {
        NewsStatus s = new NewsStatus();
        s.aktiv = true;
        s.demo = true;
        s.art = "morgen";
        s.n = 4;
        s.i = storyPhase ? 1 : 0;
        s.karaoke = true;
        s.sprecherName = "Stimme";

        MorgenInfo mi = new MorgenInfo();
        mi.schirm = "Schirm einpacken – ab 14 Uhr Regen";
        mi.termine = new ArrayList<>();
        mi.termine.add("09:30 Zahnarzt");
        mi.termine.add("15:00 Paket abholen");
        mi.muell = "morgen Papiertonne rausstellen";
        mi.liste = 4;
        s.morgen = mi;

        Map<String, Quelle> qm = new LinkedHashMap<>();
        Quelle q1 = new Quelle(); q1.name = "tagesschau"; q1.farbe = 0xFF6FB3F2; qm.put("tagesschau", q1);
        Quelle q2 = new Quelle(); q2.name = "heise"; q2.farbe = 0xFFF2B45C; qm.put("heise", q2);
        s.quellen = qm;

        List<Meldung> items = new ArrayList<>();
        Meldung m1 = new Meldung();
        m1.id = "demo1"; m1.q = "tagesschau";
        m1.t = "Neue Batterie-Technik verspricht deutlich schnellere Ladezeiten im Alltag";
        m1.x = "Forscher haben eine verbesserte Zellchemie vorgestellt, die auch bei kalten Temperaturen stabil bleibt und nach tausend Ladezyklen kaum an Kapazität verliert. Erste Serienmodelle sollen bereits im kommenden Jahr in Testfahrzeugen eingesetzt werden.";
        m1.z = nowMs / 1000.0 - 18 * 60;
        items.add(m1);

        Meldung m2 = new Meldung();
        m2.id = "demo2"; m2.q = "heise";
        m2.t = "Offener Smart-Home-Standard erhält neues Update für lokale Sprachsteuerung";
        m2.x = "Die neue Spezifikation reduziert Latenzen im Heimnetz spürbar und erlaubt vollständig lokale Abläufe ohne Cloud-Anbindung.";
        m2.z = nowMs / 1000.0 - 47 * 60;
        items.add(m2);
        s.items = items;

        Segment seg = new Segment();
        if (storyPhase) {
            seg.id = "demo1";
            seg.art = mehrPhase ? "mehr" : "titel";
            seg.text = mehrPhase ? m1.x : m1.t;
            seg.dauer = mehrPhase ? 16.0 : 8.0;
            seg.t0 = nowMs / 1000.0 - seg.dauer * 0.44;
        } else {
            seg.id = "";
            seg.art = "intro";
            seg.text = "Draußen sind es 11 Grad, später bis 15. Ab 14 Uhr regnet es, nimm einen Schirm mit. Heute hast du 2 Termine: um 09:30 Zahnarzt und 15:00 Paket abholen.";
            seg.dauer = 14.0;
            seg.t0 = nowMs / 1000.0 - seg.dauer * 0.36;
        }
        s.seg = seg;
        return s;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static int numInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt(((String) o).trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static double numDbl(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble(((String) o).trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static int hexFarbe(String s, int def) {
        if (s == null || s.length() != 7 || s.charAt(0) != '#') return def;
        try {
            return 0xFF000000 | Integer.parseInt(s.substring(1), 16);
        } catch (NumberFormatException ignored) {
            return def;
        }
    }
}
