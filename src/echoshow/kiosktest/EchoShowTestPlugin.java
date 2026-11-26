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
 * Opened by ks://plugin/echo-show-test/zeigen/<kalender|timer|liste|anwesenheit>[/<param>] or by the echo_show
 * integration's view requests (echo_show/ansicht/abo) for the pages test-kalender, test-timer, test-liste and
 * test-anwesenheit only.
 * Every other page is ignored without an answer, so the real Lovelace pages are never touched. The answers
 * (echo_show/ansicht/melden "bereit" / "gezeigt") carry the times measured on the device.
 *
 * Home Assistant is only read: calendar.get_events with return_response, a render_template over the person domain and
 * history/history_during_period. Lifecycle callbacks run on the plugin worker;
 * the view callbacks (sichtbar, inhaltFertig, beendet, ...) on the main thread, so shared state is synchronized.
 */
public final class EchoShowTestPlugin implements KioskPlugin {
    static final String OVERLAY = "ansicht";
    static final String ABO = "ansicht_abo";
    static final String PERSONEN = "anw_personen";
    static final String WETTER = "wx_daten";
    private static final String[] ANSICHTEN = {"kalender", "timer", "liste", "anwesenheit", "wetter"};
    private static final long VERLAUF_GUELTIG = 300_000L;   // like the dashboard page: reload after 5 minutes

    private volatile PluginHost host;
    private Handler main;   // created on first use

    // settings
    private volatile boolean abo = true;
    private volatile String dashboard = "/echo-show/";
    private volatile String kalender = "all";
    private volatile int timerSekunden = 300;
    private volatile boolean timerFluessig;
    private volatile String uebergang = "keine";
    private volatile String personen = "alle";
    private volatile boolean verlaufCache = true;
    private volatile String wetterEntity = "";
    private volatile boolean sternbildNamen = true;
    private volatile int wetterFps = 30;

    // weather data (live render_template), screensaver / screen for the scene's pause
    private volatile WetterLage.Daten wetterDaten;
    private volatile boolean schonerAktiv, bildschirmAus;
    private volatile boolean schonerEventDa, bildschirmEventDa;
    private boolean zustandAbos;

    // presence data (worker thread writes, views read immutable snapshots)
    private String personenAboFuer;                    // template of the people subscription (to notice setting changes)
    private volatile List<Anwesenheit.Person> personenCache;
    private volatile Map<String, List<Anwesenheit.Eintrag>> verlaufCacheDaten;
    private volatile String verlaufCacheKey;
    private volatile long verlaufCacheZeit;
    private volatile boolean verlaufNurWechsel;        // set once HA's answer was too large for the 32 KB limit

    // subscriptions that resubscribe themselves with a backoff (after an HA restart HA refuses them for a while)
    Dauerabo.Zeitgeber zeitgeber = new Dauerabo.Zeitgeber() {
        @Override public Object spaeter(Runnable r, long ms) {
            Runnable sicher = () -> TestAnsicht.sicher(r);
            main().postDelayed(sicher, ms);
            return sicher;
        }
        @Override public void abbrechen(Object handle) { main().removeCallbacks((Runnable) handle); }
    };
    private final Dauerabo.Zeitgeber zeit = new Dauerabo.Zeitgeber() {   // follows zeitgeber (tests replace it)
        @Override public Object spaeter(Runnable r, long ms) { return zeitgeber.spaeter(r, ms); }
        @Override public void abbrechen(Object handle) { zeitgeber.abbrechen(handle); }
    };
    private final Dauerabo.Melder melder = (abo, text, fehler) -> aboZustand(abo, text, fehler);
    private final Dauerabo aboAnsicht = new Dauerabo(ABO, zeit, melder);
    private final Dauerabo aboPersonen = new Dauerabo(PERSONEN, zeit, melder);
    private final Dauerabo aboWetter = new Dauerabo(WETTER, zeit, melder);
    private volatile String aboStatus = "Abo aus";
    private volatile boolean aboFehler;

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
        personen = text(s.get("personen"), "alle");
        verlaufCache = !Boolean.FALSE.equals(s.get("verlaufCache"));
        aboAbgleichen();
        personenAbgleichen();
        wetterEntity = text(s.get("wetterEntity"), "");
        sternbildNamen = !Boolean.FALSE.equals(s.get("sternbildNamen"));
        wetterFps = "24".equals(s.get("wetterFps")) ? 24 : 30;
        wetterAbgleichen();
        status();
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        List<String> args = args(arguments);
        switch (command) {
            case "zeigen": {
                String name = args.isEmpty() ? "kalender" : ansicht(args.get(0));
                if (name == null) {
                    PluginHost h = host;
                    if (h != null) h.status("Unbekannte Ansicht. Erlaubt: kalender, timer, liste, anwesenheit, wetter", true);
                    return;
                }
                oeffnen(name, null, false, linkParam(name, args.size() > 1 ? args.get(1) : null), "Befehl");
                return;
            }
            case "kalender":
            case "timer":
            case "liste":
            case "anwesenheit":
            case "wetter":
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
        else if (("ks.ha.subscription." + PERSONEN).equals(event)) personenEreignis(payload);
        else if (("ks.ha.subscription." + WETTER).equals(event)) wetterEreignis(payload);
        else if ("ks.screensaver.state".equals(event) || "ks.screen.state".equals(event)) {
            if ("ks.screensaver.state".equals(event)) {
                schonerEventDa = true;
                schonerAktiv = Boolean.TRUE.equals(payload.get("active"));
            } else {
                bildschirmEventDa = true;
                bildschirmAus = Boolean.FALSE.equals(payload.get("on"));
            }
            pauseMelden();
        }
        else if ("overlay.closed".equals(event) && OVERLAY.equals(payload.get("key"))) {
            synchronized (this) { aktuell = null; }   // back gesture; the view's onDestroy records the result
        }
    }

    @Override public void stop() {
        host = null;   // KS has revoked the host and removes the overlay itself
        synchronized (this) { aktuell = null; }
        aboAnsicht.stop();
        aboPersonen.stop();
        aboWetter.stop();
        try {
            WetterView.beenden();   // end the layer build thread, give the weather bitmaps back
        } catch (RuntimeException | LinkageError ignored) {
            // nothing was built
        }
        personenAboFuer = null;
        zustandAbos = false;
        schonerEventDa = bildschirmEventDa = false;
    }

    // ---- echo_show/ansicht subscription ---------------------------------------------------------------------------

    private void aboAbgleichen() {
        PluginHost h = host;
        if (h == null) return;
        String soll = abo && !h.isHeadless() ? dashboard : null;
        Map<String, Object> msg = null;
        if (soll != null) {
            msg = new LinkedHashMap<>();
            msg.put("type", "echo_show/ansicht/abo");
            msg.put("dashboard", soll);
        }
        if (soll == null) {
            aboStatus = "Abo aus";
            aboFehler = false;
        } else if (!aboAnsicht.aktiv() && !aboFehler) {
            aboStatus = "Abo wird angemeldet (" + soll + ")";
        }
        aboAnsicht.setzen(h, msg, soll);
    }

    /** State reports of the self-healing subscriptions (any thread). */
    private void aboZustand(Dauerabo abo, String text, boolean fehler) {
        if (abo == aboAnsicht) {
            aboStatus = "Abo " + text + " (" + dashboard + ")";
            aboFehler = fehler;
            status();
        } else {
            if (fehler && abo == aboPersonen) personenFehler("Personen nicht lesbar: " + text);
            PluginHost h = host;
            if (h != null) {
                try { h.log(abo.key + ": " + text); } catch (RuntimeException ignored) {}
            }
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
        } else {
            aboAnsicht.ereignis(p);   // ended -> resubscribes with a backoff; resubscribed / disconnected -> state
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
        if ("kalender".equals(ansicht)) kalenderLaden(h, a);
        else if ("anwesenheit".equals(ansicht)) anwesenheitLaden(a);
        else if ("wetter".equals(ansicht)) {
            WetterLage.Daten wd = wetterDaten;
            if (wd != null) {
                m.wetter = wd;
                m.wetterAusCache = true;
                m.tDaten = SystemClock.uptimeMillis();
            }   // otherwise wetterEreignis fills it in as soon as HA sends the first result
        }
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

    // ---- presence: people subscription and history ---------------------------------------------------------------

    /** Keeps one render_template subscription over the person domain, like the dashboard's live states. */
    private void personenAbgleichen() {
        PluginHost h = host;
        if (h == null) return;
        Map<String, Object> msg = h.isHeadless() ? null : Anwesenheit.personenAbo(Anwesenheit.ids(personen));
        String soll = msg == null ? null : (String) msg.get("template");
        if (!(soll == null ? personenAboFuer == null : soll.equals(personenAboFuer))) {
            personenCache = null;   // other people: the cached data no longer fits
            verlaufCacheDaten = null;
        }
        personenAboFuer = soll;
        aboPersonen.setzen(h, msg, soll);
    }

    private void personenEreignis(Map<String, Object> p) {
        Object typ = p.get("type");
        if (!"event".equals(typ)) {
            aboPersonen.ereignis(p);   // ended -> resubscribes with a backoff
            return;
        }
        if (!(p.get("event") instanceof Map)) return;
        Map<?, ?> ev = (Map<?, ?>) p.get("event");
        if (ev.containsKey("error")) {
            personenFehler("Vorlage: " + ev.get("error"));
            return;
        }
        if (!ev.containsKey("result")) return;
        List<Anwesenheit.Person> ps;
        try {
            ps = Anwesenheit.personen(ev.get("result"), Anwesenheit.ids(personen), Json::lesen);
        } catch (RuntimeException e) {
            personenFehler("Personen unlesbar");
            return;
        }
        personenCache = ps;
        TestAnsicht a = anwesenheitOffen();
        if (a == null) return;
        Messung m = a.m;
        m.personen = ps;
        boolean wechsel = !Anwesenheit.schluessel(ps).equals(verlaufCacheKey);
        if (!m.verlaufLaeuft && (m.verlauf == null && m.datenFehler == null || wechsel)) verlaufHolen(a);
        main().post(() -> TestAnsicht.sicher(a::datenDa));
    }

    private void personenFehler(String text) {
        TestAnsicht a = anwesenheitOffen();
        if (a != null && a.m.verlauf == null && a.m.datenFehler == null) {
            a.m.datenFehler = text;
            if (a.m.tDaten == 0) a.m.tDaten = SystemClock.uptimeMillis();
            main().post(() -> TestAnsicht.sicher(a::datenDa));
        }
        PluginHost h = host;
        if (h != null) h.log(text);
    }

    private synchronized TestAnsicht anwesenheitOffen() {
        return aktuell != null && "anwesenheit".equals(aktuell.m.ansicht) ? aktuell : null;
    }

    /** Worker: people from the live subscription (or wait for its first result), then the history. */
    private void anwesenheitLaden(TestAnsicht a) {
        Messung m = a.m;
        List<Anwesenheit.Person> ps = personenCache;
        if (ps == null) return;   // personenEreignis continues once HA sends the people (the subscription retries itself)
        m.personen = ps;
        m.personenAusCache = true;
        verlaufHolen(a);
    }

    /** Any thread: the 7-day history, from the 5-minute cache when the people's states did not change. */
    private void verlaufHolen(TestAnsicht a) {
        Messung m = a.m;
        PluginHost h = host;
        List<Anwesenheit.Person> ps = m.personen;
        if (h == null || ps == null) return;
        String key = Anwesenheit.schluessel(ps);
        long jetzt = System.currentTimeMillis();
        Map<String, List<Anwesenheit.Eintrag>> cache = verlaufCacheDaten;
        if (verlaufCache && cache != null && key.equals(verlaufCacheKey) && jetzt - verlaufCacheZeit < VERLAUF_GUELTIG) {
            if (m.verlauf == null) m.verlaufAusCache = true;
            verlaufFertig(a, cache);
            return;
        }
        if (ps.isEmpty()) {
            verlaufFertig(a, Collections.<String, List<Anwesenheit.Eintrag>>emptyMap());
            return;
        }
        boolean nurWechsel = verlaufNurWechsel;
        m.verlaufLaeuft = true;
        try {
            h.haCall(Anwesenheit.verlaufAnfrage(ps, jetzt, nurWechsel), (ok, data, error) -> {
                m.verlaufLaeuft = false;
                if (!ok && !nurWechsel && error != null && error.startsWith("too_large")) {
                    // more than 32 KB (GPS updates of the person entities): only real state changes, same picture
                    verlaufNurWechsel = true;
                    m.verlaufReduziert = true;
                    verlaufHolen(a);
                    return;
                }
                if (nurWechsel) m.verlaufReduziert = true;
                if (ok) {
                    Map<String, List<Anwesenheit.Eintrag>> v;
                    try {
                        v = Anwesenheit.verlauf(data);
                    } catch (RuntimeException e) {
                        verlaufFehler(a, "Verlauf unlesbar");
                        return;
                    }
                    verlaufCacheDaten = v;
                    verlaufCacheKey = key;
                    verlaufCacheZeit = System.currentTimeMillis();
                    verlaufFertig(a, v);
                } else {
                    verlaufFehler(a, error == null ? "unbekannt" : error);
                }
            });
        } catch (RuntimeException e) {
            m.verlaufLaeuft = false;
            verlaufFehler(a, "haCall: " + e.getMessage());
        }
    }

    private void verlaufFertig(TestAnsicht a, Map<String, List<Anwesenheit.Eintrag>> v) {
        a.m.verlauf = v;
        if (a.m.tDaten == 0) a.m.tDaten = SystemClock.uptimeMillis();
        main().post(() -> TestAnsicht.sicher(a::datenDa));
    }

    private void verlaufFehler(TestAnsicht a, String text) {
        if (a.m.verlauf == null) a.m.datenFehler = text;   // a failed reload keeps the history on screen
        if (a.m.tDaten == 0) a.m.tDaten = SystemClock.uptimeMillis();
        main().post(() -> TestAnsicht.sicher(a::datenDa));
    }

    /** Main thread, once a minute while the page is open: reload an outdated history (as the page does). */
    void anwesenheitMinute(TestAnsicht a) {
        if (!a.m.verlaufLaeuft && System.currentTimeMillis() - verlaufCacheZeit >= VERLAUF_GUELTIG) {
            verlaufCacheDaten = null;
            verlaufHolen(a);
        }
    }

    // ---- weather: live data and the scene's pause ------------------------------------------------------------------

    /** One render_template subscription for the weather entity, sun.sun and zone.home; it stays up while the plugin runs. */
    private void wetterAbgleichen() {
        PluginHost h = host;
        if (h == null) return;
        Map<String, Object> msg = h.isHeadless() ? null : WetterLage.abo(wetterEntity);
        String soll = msg == null ? null : (String) msg.get("template");
        aboWetter.setzen(h, msg, soll);
        if (h.isHeadless() || zustandAbos) return;
        zustandAbos = true;
        try {   // pause the scene under the screensaver and with the screen off (subscribe first, then read initial state)
            h.subscribe("screensaver.state");
            h.subscribe("screen.state");
            h.executeCommand("isScreensaverActive", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                if (ok && !schonerEventDa) {
                    schonerAktiv = Boolean.TRUE.equals(data);
                    pauseMelden();
                }
            });
            h.executeCommand("isScreenOn", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                if (ok && !bildschirmEventDa) {
                    bildschirmAus = Boolean.FALSE.equals(data);
                    pauseMelden();
                }
            });
        } catch (RuntimeException ignored) {
            // without host.read the scene only pauses when its view is hidden
        }
    }

    private void pauseMelden() {
        TestAnsicht a;
        synchronized (this) { a = aktuell; }
        if (a != null) main().post(() -> TestAnsicht.sicher(a::pauseGeaendert));
    }

    private void wetterEreignis(Map<String, Object> p) {
        if (!"event".equals(p.get("type"))) {
            aboWetter.ereignis(p);   // ended -> resubscribes with a backoff
            return;
        }
        if (!(p.get("event") instanceof Map)) return;
        Map<?, ?> ev = (Map<?, ?>) p.get("event");
        if (!ev.containsKey("result")) return;
        WetterLage.Daten d;
        try {
            d = WetterLage.lesen(ev.get("result"), Json::lesen);
        } catch (RuntimeException e) {
            return;
        }
        if (d == null) return;
        wetterDaten = d;
        TestAnsicht a;
        synchronized (this) { a = aktuell != null && "wetter".equals(aktuell.m.ansicht) ? aktuell : null; }
        if (a == null) return;
        a.m.wetter = d;
        if (a.m.tDaten == 0) a.m.tDaten = SystemClock.uptimeMillis();
        main().post(() -> TestAnsicht.sicher(a::datenDa));
    }

    boolean wetterPausiert() { return schonerAktiv || bildschirmAus; }

    boolean sternbildNamen() { return sternbildNamen; }

    int wetterFps() { return wetterFps; }

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
            if (m.datenFehler != null) d.put("fehler", m.datenFehler);
        }
        if ("kalender".equals(m.ansicht)) d.put("termine", m.termine == null ? 0 : m.termine.size());
        if ("wetter".equals(m.ansicht)) {
            Map<String, Object> sz = m.szene;
            if (sz != null) for (Map.Entry<String, Object> e : sz.entrySet()) if (e.getValue() != null) d.put(e.getKey(), e.getValue());
            WetterLage.Daten wd = m.wetter;
            d.put("zustand", wd == null ? "" : wd.zustand);
            d.put("daten_cache", m.wetterAusCache);
            d.put("aufbau_ms", m.aufbauMs);
            Object mo = m.param.get("modus"), uhr = m.param.get("uhr");
            d.put("modus", mo != null ? String.valueOf(mo) : (uhr != null ? "fest " + uhr : "live"));
        }
        if ("anwesenheit".equals(m.ansicht)) {
            List<Anwesenheit.Person> ps = m.personen;
            d.put("personen", ps == null ? 0 : ps.size());
            d.put("personen_cache", m.personenAusCache);
            d.put("verlauf_cache", m.verlaufAusCache);
            d.put("verlauf_nur_wechsel", m.verlaufReduziert);
            d.put("modus", AnwesenheitView.modus);
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

    /** Main thread, 5 s after "bereit": the scene's figures over full one-second windows, into the status. */
    void szeneNachtrag(TestAnsicht a) {
        Map<String, Object> sz = a.m.szene5;
        if (sz == null) return;
        synchronized (this) {
            zeile(String.format(Locale.ROOT, "%s wetter nach 5 s: %s fps, Frame %s / %s ms, CPU Prozess %s %%, Hauptthread %s %%, Bitmaps %s KB (Figur %s KB)%s",
                uhr(), sz.get("szene_fps"), sz.get("frame_median_ms"), sz.get("frame_p95_ms"), sz.get("cpu_prozess_pct"), sz.get("cpu_haupt_pct"), sz.get("bitmap_kb"), sz.get("figur_kb"),
                sz.get("cpu_fehler") != null ? ", " + sz.get("cpu_fehler") : ""));
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
            Map<String, Object> sz = m.szeneEnde;
            if (sz != null) {
                zeile(String.format(Locale.ROOT, "%s wetter Szene: %s fps, Frame %s / %s ms (Median / 95 %%), CPU Prozess %s %%, Hauptthread %s %%, Bitmaps %s KB, Ruckler %s%s",
                    uhr(), sz.get("szene_fps"), sz.get("frame_median_ms"), sz.get("frame_p95_ms"), sz.get("cpu_prozess_pct"), sz.get("cpu_haupt_pct"), sz.get("bitmap_kb"), sz.get("ruckler"),
                    sz.get("cpu_fehler") != null ? ", " + sz.get("cpu_fehler") : ""));
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
            h.status(s, aboFehler);
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
        else if ("wetter".equals(ansicht) && "zeitraffer".equals(a)) p.put("modus", "zeitraffer");
        else if ("wetter".equals(ansicht) && a.matches("([01]?\\d|2[0-3]):[0-5]\\d")) p.put("uhr", a);
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
