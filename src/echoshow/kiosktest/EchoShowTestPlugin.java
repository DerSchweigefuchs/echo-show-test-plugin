package echoshow.kiosktest;

import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Prototype: native full-screen test views to compare with the Lovelace layers on the Echo Show.
 *
 * Opened by ks://plugin/echo-show-test/zeigen/<kalender|timer|liste>[/<param>] or by the echo_show integration's view
 * requests (echo_show/ansicht/abo, REGELN-ANSICHTEN.md) for the pages test-kalender, test-timer and test-liste only.
 * Every other page is ignored without an answer, so the real Lovelace pages are never touched. The answers
 * (echo_show/ansicht/melden "bereit" / "gezeigt") carry the times measured on the device.
 *
 * Home Assistant is only read: calendar.get_events with return_response. Lifecycle callbacks run on the plugin worker;
 * the view callbacks (sichtbar, inhaltFertig, beendet, ...) on the main thread, so shared state is synchronized.
 */
public final class EchoShowTestPlugin implements KioskPlugin {
    static final String OVERLAY = "ansicht";
    static final String ABO = "ansicht_abo";
    private static final String[] ANSICHTEN = {"kalender", "timer", "liste"};

    private volatile PluginHost host;
    private Handler main;   // created on first use

    // settings
    private volatile boolean abo = true;
    private volatile String dashboard = "/echo-show/";
    private volatile String kalender = "all";
    private volatile int timerSekunden = 300;
    private volatile boolean timerFluessig;
    private volatile String uebergang = "keine";

    // subscription state (worker thread)
    private String aboFuer;
    private int aboVersuche;
    private volatile String aboStatus = "Abo aus";

    private TestAnsicht aktuell;                        // guarded by this
    private final LinkedList<String> verlauf = new LinkedList<>();   // guarded by this

    // ---- lifecycle ------------------------------------------------------------------------------------------------

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        configure(settings);
    }

    @Override public void configure(Map<String, Object> s) {
        abo = !Boolean.FALSE.equals(s.get("abo"));
        dashboard = norm(text(s.get("dashboard"), "/echo-show/"));
        kalender = text(s.get("kalender"), "all");
        Object sek = s.get("timerSekunden");
        timerSekunden = sek instanceof Number ? Math.max(5, Math.min(3600, ((Number) sek).intValue())) : 300;
        timerFluessig = "flüssig".equals(s.get("timerModus"));
        uebergang = text(s.get("uebergang"), "keine");
        aboAbgleichen();
        status();
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        List<String> args = args(arguments);
        switch (command) {
            case "zeigen": {
                String name = args.isEmpty() ? "kalender" : ansicht(args.get(0));
                if (name == null) {
                    PluginHost h = host;
                    if (h != null) h.status("Unbekannte Ansicht. Erlaubt: kalender, timer, liste", true);
                    return;
                }
                oeffnen(name, null, false, linkParam(name, args.size() > 1 ? args.get(1) : null), "Befehl");
                return;
            }
            case "kalender":
            case "timer":
            case "liste":
                oeffnen(command, null, false, Collections.<String, Object>emptyMap(), "Befehl");
                return;
            case "schliessen":
                schliessen();
                return;
            default:
                throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        if (("ks.ha.subscription." + ABO).equals(event)) aboEreignis(payload);
        else if ("overlay.closed".equals(event) && OVERLAY.equals(payload.get("key"))) {
            synchronized (this) { aktuell = null; }   // back gesture; the view's onDestroy records the result
        }
    }

    @Override public void stop() {
        host = null;   // KS has revoked the host and removes the overlay itself
        synchronized (this) { aktuell = null; }
        aboFuer = null;
    }

    // ---- echo_show/ansicht subscription ---------------------------------------------------------------------------

    private void aboAbgleichen() {
        PluginHost h = host;
        if (h == null) return;
        String soll = abo && !h.isHeadless() ? dashboard : null;
        if (soll == null ? aboFuer == null : soll.equals(aboFuer)) return;
        try { h.haUnsubscribe(ABO); } catch (RuntimeException ignored) {}
        aboFuer = null;
        aboStatus = "Abo aus";
        if (soll != null) abonnieren(h, soll);
    }

    private void abonnieren(PluginHost h, String dash) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/ansicht/abo");
        msg.put("dashboard", dash);
        aboFuer = dash;
        aboStatus = "Abo wird angemeldet (" + dash + ")";
        try {
            h.haSubscribe(ABO, msg, (ok, data, error) -> {
                if (!dash.equals(aboFuer)) return;
                if (ok) {
                    aboVersuche = 0;
                    aboStatus = "Abo aktiv (" + dash + ")";
                } else {
                    aboFuer = null;
                    aboStatus = "Abo abgelehnt: " + error;
                }
                status();
            });
        } catch (RuntimeException e) {
            aboFuer = null;
            aboStatus = "Abo nicht möglich: " + e.getMessage();
        }
    }

    private void aboEreignis(Map<String, Object> p) {
        Object typ = p.get("type");
        if ("event".equals(typ)) {
            Object ev = p.get("event");
            if (!(ev instanceof Map)) return;
            Map<?, ?> e = (Map<?, ?>) ev;
            if (!"vorbau".equals(e.get("aktion"))) return;
            Object seite = e.get("seite"), token = e.get("token");
            // only our own test pages; everything else belongs to the Lovelace dashboard
            if (!(seite instanceof String) || !((String) seite).startsWith("test-") || !(token instanceof String)) return;
            String name = ansicht(((String) seite).substring(5));
            if (name == null) return;
            Map<String, Object> param = new LinkedHashMap<>();
            if (e.get("param") instanceof Map) {
                for (Map.Entry<?, ?> x : ((Map<?, ?>) e.get("param")).entrySet()) param.put(String.valueOf(x.getKey()), x.getValue());
            }
            oeffnen(name, (String) token, Boolean.TRUE.equals(e.get("kalt")), param, "HA");
        } else if ("disconnected".equals(typ)) {
            aboStatus = "Abo: Verbindung getrennt, KS verbindet neu";
            status();
        } else if ("resubscribed".equals(typ)) {
            aboStatus = "Abo aktiv (" + aboFuer + ", neu verbunden)";
            status();
        } else if ("ended".equals(typ)) {
            aboFuer = null;
            aboStatus = "Abo beendet: " + p.get("error");
            if (aboVersuche++ < 3) aboAbgleichen();
            status();
        }
    }

    // ---- opening and measuring ------------------------------------------------------------------------------------

    private void oeffnen(String ansicht, String token, boolean kalt, Map<String, Object> param, String quelle) {
        long t0 = SystemClock.uptimeMillis();
        PluginHost h = host;
        if (h == null) return;
        int sek = timerSekunden;
        Object ps = param.get("sekunden");
        if ("timer".equals(ansicht) && ps instanceof Number) sek = Math.max(1, Math.min(86400, ((Number) ps).intValue()));
        Messung m = new Messung(ansicht, token, quelle, kalt, param, t0, sek, timerFluessig);
        if (h.isHeadless()) {
            melden(m, "bereit", fehler("kein Bildschirm"));
            return;
        }
        TestAnsicht a = new TestAnsicht(this, m);
        synchronized (this) { aktuell = a; }
        ramLesen(m, true, a);
        if (m.brauchtDaten()) kalenderLaden(h, a);
        try {
            h.showOverlay(OVERLAY, spec(), a);
        } catch (RuntimeException e) {   // older KS, rate limit or stopped session
            synchronized (this) { if (aktuell == a) aktuell = null; }
            melden(m, "bereit", fehler("Overlay: " + e.getMessage()));
            h.status("Overlay nicht möglich: " + e.getMessage(), true);
        }
    }

    private OverlaySpec spec() {
        OverlaySpec s = OverlaySpec.fullScreen().closeOnBack(true);
        try {
            s.layer(OverlaySpec.LAYER_DASHBOARD);
            switch (uebergang) {
                case "fade": s.enter(OverlaySpec.FADE).exit(OverlaySpec.FADE).transitionDuration(200); break;
                case "slide": s.enter(OverlaySpec.SLIDE).exit(OverlaySpec.SLIDE).transitionDuration(250); break;
                case "scale": s.enter(OverlaySpec.SCALE).exit(OverlaySpec.FADE).transitionDuration(200); break;
                default: break;
            }
        } catch (LinkageError olderHost) {
            // KS without layers or transitions: plain full screen overlay
        }
        return s;
    }

    private void kalenderLaden(PluginHost h, TestAnsicht a) {
        Messung m = a.m;
        Object tagParam = m.param.get("tag");
        Calendar tag = Kalender.tag(tagParam instanceof String ? (String) tagParam : null);
        try {
            h.haCall(Kalender.anfrage(kalender, tag), (ok, data, error) -> {
                m.tDaten = SystemClock.uptimeMillis();
                if (ok) {
                    try {
                        m.termine = Kalender.lesen(data, tag);
                    } catch (RuntimeException e) {
                        m.datenFehler = "Antwort unlesbar";
                    }
                } else {
                    m.datenFehler = error == null ? "unbekannt" : error;
                }
                main().post(() -> TestAnsicht.sicher(a::datenDa));
            });
        } catch (RuntimeException e) {
            m.tDaten = SystemClock.uptimeMillis();
            m.datenFehler = "haCall: " + e.getMessage();
        }
    }

    /** Main thread: the view is on screen (first draw + two frames). */
    void sichtbar(TestAnsicht a) {
        Messung m = a.m;
        if (m.gezeigtGemeldet) return;
        m.gezeigtGemeldet = true;
        Map<String, Object> d = basis(m);
        d.put("ms", m.ms(m.tSichtbar));
        melden(m, "gezeigt", d);
    }

    /** Main thread: content complete (calendar rows drawn) and visible. */
    void inhaltFertig(TestAnsicht a) {
        Messung m = a.m;
        if (m.bereitGemeldet) return;
        m.tInhalt = SystemClock.uptimeMillis();
        m.bereitGemeldet = true;
        Runtime rt = Runtime.getRuntime();
        m.heapMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        m.nativeHeapMb = Debug.getNativeHeapAllocatedSize() / 1048576L;
        Map<String, Object> d = basis(m);
        d.put("ms", m.ms(m.tInhalt));
        if (m.brauchtDaten()) {
            d.put("daten_ms", m.ms(m.tDaten));
            d.put("termine", m.termine == null ? 0 : m.termine.size());
            if (m.datenFehler != null) d.put("fehler", m.datenFehler);
        }
        d.put("heap", m.heapMb);
        d.put("native_heap", m.nativeHeapMb);
        if (m.ramVorherMb != null) d.put("ram_frei_vorher", Math.round(m.ramVorherMb));
        melden(m, "bereit", d);
        verlaufEintrag(m);
        // free RAM once things have settled
        main().postDelayed(() -> TestAnsicht.sicher(() -> ramLesen(m, false, a)), 2000);
    }

    /** Main thread: the list's auto scroll ran down and up once. */
    void scrollFertig(TestAnsicht a, int frames, int ruckler, long maxMs) {
        synchronized (this) {
            zeile(String.format(Locale.ROOT, "%s Auto-Scroll: Ruckler %d von %d Frames, max %d ms", uhr(), ruckler, frames, maxMs));
        }
        status();
    }

    /** Main thread: the view was removed (close button, back, replaced or session end). */
    void beendet(TestAnsicht a) {
        Messung m = a.m;
        if (m.beendet) return;
        m.beendet = true;
        synchronized (this) {
            if (aktuell == a) aktuell = null;
            if (m.tSichtbar > 0) {
                zeile(String.format(Locale.ROOT, "%s %s zu: Ruckler %d von %d Frames, max %d ms", uhr(), m.ansicht, m.ruckler, m.frames, m.maxFrameMs));
            }
        }
        status();
    }

    /** Main thread (button) or worker (command). */
    void schliessen() {
        PluginHost h = host;
        synchronized (this) { aktuell = null; }
        if (h != null) {
            try { h.hideOverlay(OVERLAY); } catch (RuntimeException ignored) {}
        }
    }

    private Map<String, Object> basis(Messung m) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("nativ", true);
        d.put("ansicht", m.ansicht);
        d.put("kalt", m.kalt);
        d.put("start_ms", m.ms(m.tErstellt));                 // showOverlay -> create (KS overhead)
        d.put("view_ms", m.tErsterFrame > 0 && m.tErstellt > 0 ? m.tErsterFrame - m.tErstellt : -1);   // create -> first draw
        d.put("erster_frame_ms", m.ms(m.tErsterFrame));
        d.put("sichtbar_ms", m.ms(m.tSichtbar));
        return d;
    }

    private static Map<String, Object> fehler(String text) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("fehler", text);
        d.put("nativ", true);
        return d;
    }

    /** echo_show/ansicht/melden, only for requests that came with a token. Any thread. */
    private void melden(Messung m, String art, Map<String, Object> daten) {
        PluginHost h = host;
        if (m.token == null || h == null) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/ansicht/melden");
        msg.put("token", m.token);
        msg.put("art", art);
        msg.put("daten", daten);
        try {
            h.haCall(msg, (ok, data, error) -> {
                PluginHost hh = host;
                if (!ok && hh != null) hh.log("melden " + art + ": " + error);
            });
        } catch (RuntimeException ignored) {
            // session ended or rate limit; the integration times out after 4 s
        }
    }

    /** Free system RAM through host.read getDeviceInfo (the same reading KS shows in its device details). */
    private void ramLesen(Messung m, boolean vorher, TestAnsicht a) {
        PluginHost h = host;
        if (h == null) return;
        try {
            h.executeCommand("getDeviceInfo", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                if (!ok || !(data instanceof Map)) return;
                Object frei = ((Map<?, ?>) data).get("ramFree");
                if (!(frei instanceof Number)) return;
                double mb = ((Number) frei).doubleValue() / 1048576.0;
                if (vorher) m.ramVorherMb = mb;
                else {
                    m.ramNachherMb = mb;
                    verlaufEintrag(m);
                    main().post(() -> TestAnsicht.sicher(a::anzeigeAktualisieren));
                }
            });
        } catch (RuntimeException ignored) {
            // host.read missing or rate limited: no RAM figures
        }
    }

    // ---- status ---------------------------------------------------------------------------------------------------

    /** One line per opening, updated in place once the RAM reading arrives. */
    private void verlaufEintrag(Messung m) {
        if (m.tInhalt == 0) return;
        StringBuilder sb = new StringBuilder();
        sb.append(uhr()).append(' ').append(m.ansicht).append(" (").append(m.quelle).append(m.kalt ? ", kalt" : "").append("): offen ")
            .append(m.ms(m.tInhalt)).append(" ms, sichtbar ").append(m.ms(m.tSichtbar)).append(" ms");
        if (m.brauchtDaten()) sb.append(", Daten ").append(m.ms(m.tDaten)).append(" ms");
        if (m.ramVorherMb != null && m.ramNachherMb != null) {
            sb.append(", RAM frei ").append(Math.round(m.ramVorherMb)).append("→").append(Math.round(m.ramNachherMb)).append(" MB");
        }
        if (m.datenFehler != null) sb.append(", Fehler: ").append(m.datenFehler);
        String neu = sb.length() > 200 ? sb.substring(0, 200) : sb.toString();
        synchronized (this) {
            int i = m.verlaufZeile == null ? -1 : verlauf.indexOf(m.verlaufZeile);
            if (i >= 0) verlauf.set(i, neu);
            else zeile(neu);
            m.verlaufZeile = neu;
        }
        status();
    }

    private void zeile(String text) {
        verlauf.addLast(text.length() > 200 ? text.substring(0, 200) : text);
        while (verlauf.size() > 6) verlauf.removeFirst();
    }

    private void status() {
        PluginHost h = host;
        if (h == null) return;
        StringBuilder sb = new StringBuilder(aboStatus);
        synchronized (this) {
            for (String z : verlauf) sb.append('\n').append(z);
        }
        String s = sb.length() > 1000 ? sb.substring(0, 1000) : sb.toString();
        try {
            h.status(s, aboStatus.startsWith("Abo abgelehnt") || aboStatus.startsWith("Abo nicht"));
        } catch (RuntimeException ignored) {
            // older KS or ended session
        }
    }

    private synchronized Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** kalender | timer | liste (also with a test- prefix), or null. */
    static String ansicht(String roh) {
        if (roh == null) return null;
        String n = roh.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("test-")) n = n.substring(5);
        for (String a : ANSICHTEN) if (a.equals(n)) return a;
        return null;
    }

    /** Second link argument: seconds for the timer, yyyy-MM-dd for the calendar. Anything else is ignored. */
    static Map<String, Object> linkParam(String ansicht, String arg) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (arg == null) return p;
        String a = arg.trim();
        if ("timer".equals(ansicht) && a.matches("\\d{1,5}")) p.put("sekunden", Integer.parseInt(a));
        else if ("kalender".equals(ansicht) && Kalender.TAG.matcher(a).matches()) p.put("tag", a);
        return p;
    }

    static List<String> args(Map<String, Object> arguments) {
        List<String> out = new LinkedList<>();
        Object a = arguments == null ? null : arguments.get("args");
        if (a instanceof List) for (Object x : (List<?>) a) if (x instanceof String) out.add((String) x);
        return out;
    }

    static String norm(String pfad) {
        String p = pfad.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return "/" + p + "/";
    }

    private static String text(Object o, String standard) {
        return o instanceof String && !((String) o).trim().isEmpty() ? ((String) o).trim() : standard;
    }

    private static String uhr() {
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
    }
}
