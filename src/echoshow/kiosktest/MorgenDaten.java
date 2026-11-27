package echoshow.kiosktest;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Data models and parsers for the unified native "Mein Tag" v2 and news story player:
 * - echo_show/news/subscribe live status (custom rundown groups, active karaoke segment, queue, sources, enriched morning data)
 * - hourly forecast strip from input_text.wetter_stunden and input_text.wetter_regen
 * Plain Java without Android UI dependencies so it can be unit-tested and rendered off-device.
 */
final class MorgenDaten {

    static final class Quelle {
        String name = "";
        int farbe = 0xFF8FA3B5;
    }

    static final class Gruppe {
        String art = "", label = "", thema = "";
        int count = 1;
        double dauer;
    }

    static final class TerminDetail {
        String zeit = "", bis = "", titel = "", ort = "";
        int farbe = 0xFF6FB3F2;
    }

    static final class Meldung {
        String id = "", q = "", t = "", x = "", l = "", bild = "";
        double z;   // epoch seconds
    }

    static final class Segment {
        String id = "", art = "", text = "", titel = "";
        String label = "", kategorie = "", thema = "", naechstes = "";
        int gruppe, gruppeI, gruppeN = 1;
        double t0, dauer, jetzt, gruppeOffset;
    }

    static final class MorgenInfo {
        String name = "";
        List<String> termine = Collections.emptyList();
        List<TerminDetail> termineDetail = Collections.emptyList();
        String muell = "";
        int liste;
        List<String> listeItems = Collections.emptyList();
        String schirm = "";
        boolean glatt;
        Integer wxTemp, wxHi, wxLo, wxWind;
        String wxZustand = "";

        boolean hatInhalt() {
            return !schirm.isEmpty() || glatt || !termine.isEmpty() || !termineDetail.isEmpty()
                || !muell.isEmpty() || liste > 0;
        }
    }

    static final class NewsStatus {
        boolean aktiv;
        String art = "";          // "morgen" | "news" | "eil" | ""
        String dash = "";
        String modusLabel = "";
        List<String> ziel = Collections.emptyList();
        int n, i = -1;
        boolean pause;
        boolean karaoke = true;
        String ansicht = "vollbild";   // "vollbild" | "leiste" | "aus"
        String sprecherName = "";
        double gesamtDauer;
        long clockOffsetMs;       // serverTimeMs - localTimeMs
        MorgenInfo morgen;
        Segment seg;
        List<Gruppe> gruppen = Collections.emptyList();
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
        String nassWort = "";
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
        s.modusLabel = str(m.get("modus_label"));
        s.n = numInt(m.get("n"), 0);
        s.i = numInt(m.get("i"), -1);
        s.pause = Boolean.TRUE.equals(m.get("pause"));
        s.karaoke = !Boolean.FALSE.equals(m.get("karaoke"));
        String ans = str(m.get("ansicht"));
        s.ansicht = ans.isEmpty() ? "vollbild" : ans;
        s.sprecherName = str(m.get("name"));
        s.gesamtDauer = numDbl(m.get("gesamt_dauer"), 0);
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

        if (m.get("gruppen") instanceof List) {
            List<Gruppe> gs = new ArrayList<>();
            for (Object o : (List<?>) m.get("gruppen")) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> gm = (Map<?, ?>) o;
                Gruppe g = new Gruppe();
                g.art = str(gm.get("art"));
                g.label = str(gm.get("label"));
                g.thema = str(gm.get("thema"));
                g.count = Math.max(1, numInt(gm.get("count"), 1));
                g.dauer = numDbl(gm.get("dauer"), 0);
                gs.add(g);
            }
            s.gruppen = gs;
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
            if (mm.get("termine_detail") instanceof List) {
                List<TerminDetail> td = new ArrayList<>();
                for (Object o : (List<?>) mm.get("termine_detail")) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> dm = (Map<?, ?>) o;
                    TerminDetail d = new TerminDetail();
                    d.zeit = str(dm.get("zeit"));
                    d.bis = str(dm.get("bis"));
                    d.titel = str(dm.get("titel"));
                    d.ort = str(dm.get("ort"));
                    d.farbe = hexFarbe(str(dm.get("farbe")), 0xFF6FB3F2);
                    if (!d.titel.isEmpty()) td.add(d);
                }
                mi.termineDetail = td;
            } else if (!mi.termine.isEmpty()) {
                List<TerminDetail> td = new ArrayList<>();
                int[] pal = {0xFF6FB3F2, 0xFF6CC483, 0xFFF2B45C, 0xFFB49CF2};
                for (int k = 0; k < mi.termine.size(); k++) {
                    String raw = mi.termine.get(k);
                    TerminDetail d = new TerminDetail();
                    if (raw.length() > 6 && raw.charAt(2) == ':' && raw.charAt(5) == ' ') {
                        d.zeit = raw.substring(0, 5);
                        d.titel = raw.substring(6).trim();
                    } else {
                        d.zeit = "Heute";
                        d.titel = raw;
                    }
                    d.farbe = pal[k % pal.length];
                    td.add(d);
                }
                mi.termineDetail = td;
            }
            if (mm.get("liste_items") instanceof List) {
                List<String> li = new ArrayList<>();
                for (Object o : (List<?>) mm.get("liste_items")) {
                    String t = str(o);
                    if (!t.isEmpty()) li.add(t);
                }
                mi.listeItems = li;
            }
            if (mm.get("wetter") instanceof Map) {
                Map<?, ?> wm = (Map<?, ?>) mm.get("wetter");
                mi.wxTemp = optInt(wm.get("temp"));
                mi.wxHi = optInt(wm.get("hi"));
                mi.wxLo = optInt(wm.get("lo"));
                mi.wxWind = optInt(wm.get("wind"));
                mi.wxZustand = str(wm.get("zustand"));
            }
            s.morgen = mi;
        }

        if (m.get("seg") instanceof Map) {
            Map<?, ?> sm = (Map<?, ?>) m.get("seg");
            Segment seg = new Segment();
            seg.id = str(sm.get("id"));
            seg.art = str(sm.get("art"));
            seg.text = str(sm.get("text"));
            seg.titel = str(sm.get("titel"));
            seg.label = str(sm.get("label"));
            seg.kategorie = str(sm.get("kategorie"));
            seg.thema = str(sm.get("thema"));
            seg.naechstes = str(sm.get("naechstes"));
            seg.gruppe = numInt(sm.get("gruppe"), Math.max(0, s.i));
            seg.gruppeI = numInt(sm.get("gruppe_i"), 0);
            seg.gruppeN = Math.max(1, numInt(sm.get("gruppe_n"), 1));
            seg.t0 = numDbl(sm.get("t0"), 0);
            seg.dauer = numDbl(sm.get("dauer"), 0);
            seg.gruppeOffset = numDbl(sm.get("gruppe_offset"), 0);
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
            if (Math.abs(nowMs - baseMs) > 28L * 3600_000L) return out;
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
        return demoArt(storyPhase ? (mehrPhase ? "mehr" : "meldung") : "wetter", nowMs);
    }

    static NewsStatus demoArt(String phase, long nowMs) {
        NewsStatus s = new NewsStatus();
        s.aktiv = true;
        s.demo = true;
        s.art = "morgen";
        s.karaoke = true;
        s.sprecherName = "Stimme";
        s.modusLabel = "KI-Moderation · locker";

        String[][] grp = {
            {"begruessung", "Begrüßung", ""}, {"meldung", "Lokal", "ort"}, {"meldung", "Politik", "politik"},
            {"meldung", "Wirtschaft", "wirtschaft"}, {"meldung", "Sport", "sport"},
            {"kalender", "Kalender", ""}, {"wetter", "Wetter", ""}, {"abschied", "Abschied", ""}
        };
        List<Gruppe> gs = new ArrayList<>();
        double[] durs = {8, 34, 18, 18, 16, 14, 15, 7};
        for (int gi = 0; gi < grp.length; gi++) {
            String[] g = grp[gi];
            Gruppe gr = new Gruppe();
            gr.art = g[0]; gr.label = g[1]; gr.thema = g[2];
            gr.count = "ort".equals(g[2]) ? 2 : 1;
            gr.dauer = durs[gi];
            gs.add(gr);
        }
        s.gruppen = gs;
        s.gesamtDauer = 130.0;
        s.n = 9;

        MorgenInfo mi = new MorgenInfo();
        mi.schirm = "Ab 14 Uhr Regen – Schirm mitnehmen";
        mi.muell = "morgen Papiertonne rausstellen";
        mi.liste = 4;
        mi.listeItems = new ArrayList<>();
        mi.listeItems.add("Hafermilch"); mi.listeItems.add("Tomaten");
        mi.listeItems.add("Sauerteigbrot"); mi.listeItems.add("Kaffeebohnen");
        mi.wxTemp = 11; mi.wxLo = 7; mi.wxHi = 16; mi.wxWind = 14; mi.wxZustand = "partlycloudy";

        mi.termine = new ArrayList<>();
        mi.termine.add("09:30 Zahnarzt"); mi.termine.add("15:00 Paket abholen"); mi.termine.add("18:30 Sport");
        List<TerminDetail> td = new ArrayList<>();
        TerminDetail d1 = new TerminDetail(); d1.zeit = "09:30"; d1.bis = "10:15"; d1.titel = "Zahnarzt"; d1.ort = "Praxis am Markt"; d1.farbe = 0xFF6FB3F2; td.add(d1);
        TerminDetail d2 = new TerminDetail(); d2.zeit = "15:00"; d2.bis = ""; d2.titel = "Paket abholen"; d2.ort = "Packstation"; d2.farbe = 0xFF6CC483; td.add(d2);
        TerminDetail d3 = new TerminDetail(); d3.zeit = "18:30"; d3.bis = "19:45"; d3.titel = "Sport"; d3.ort = "Fitnessstudio"; d3.farbe = 0xFFF2B45C; td.add(d3);
        mi.termineDetail = td;
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
        if ("kalender".equals(phase)) {
            s.i = 6; seg.art = "kalender"; seg.gruppe = 5; seg.label = "Kalender"; seg.kategorie = "Kalender";
            seg.naechstes = "Wetter";
            seg.text = "Im Kalender stehen heute drei Termine: um halb zehn der Zahnarzt, nachmittags um fünfzehn Uhr ein Paket abholen und heute Abend um halb sieben geht es zum Sport.";
            seg.dauer = 14.0; seg.t0 = nowMs / 1000.0 - seg.dauer * 0.48;
        } else if ("meldung".equals(phase) || "mehr".equals(phase)) {
            s.i = 1; seg.id = "demo1"; seg.art = "mehr".equals(phase) ? "mehr" : "meldung";
            seg.gruppe = 1; seg.gruppeI = 0; seg.gruppeN = 2; seg.label = "Lokal"; seg.kategorie = "Lokalnachrichten";
            seg.naechstes = "Politik";
            seg.text = m1.x;
            seg.dauer = 15.0; seg.t0 = nowMs / 1000.0 - seg.dauer * 0.46;
        } else {
            s.i = 7; seg.art = "wetter"; seg.gruppe = 6; seg.label = "Wetter"; seg.kategorie = "Wetter";
            seg.naechstes = "Abschied";
            seg.text = "Zum Schluss das Wetter: Aktuell sind es elf Grad und leicht bewölkt. Bis Mittag steigt die Temperatur auf sechzehn Grad, ab vierzehn Uhr zieht Regen auf – pack also einen Schirm ein.";
            seg.dauer = 14.0; seg.t0 = nowMs / 1000.0 - seg.dauer * 0.52;
        }
        s.seg = seg;
        return s;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static int numInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt(((String) o).trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static Integer optInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String && !((String) o).trim().isEmpty()) {
            try { return Integer.parseInt(((String) o).trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
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
