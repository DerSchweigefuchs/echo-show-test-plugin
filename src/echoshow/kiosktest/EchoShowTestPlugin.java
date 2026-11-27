package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Native Echo Show views and "Wetterszene" screensaver plugin for Kiosk Satellite.
 *
 * Opened by ks://plugin/echo-show-test/zeigen/<view>[/<param>] or by the echo_show integration's view requests
 * (echo_show/ansicht/abo) for all 11 native views. Lifecycle callbacks run on the plugin worker; the view callbacks
 * (sichtbar, inhaltFertig, beendet, ...) run on the main thread, so shared state is synchronized.
 */
public final class EchoShowTestPlugin implements KioskPlugin {
    static final String OVERLAY = "ansicht";
    static final String SCHONER_KEY = "wetter";
    static final String ABO = "ansicht_abo";
    static final String PERSONEN = "anw_personen";
    static final String WETTER = "wx_daten";
    static final String SCHONER_NOTIF = "schoner_notif";
    static final String SCHONER_INFO = "schoner_info";
    static final String NEWS = "news_abo";
    static final String TIMER = "timer_abo";
    static final String KONFIG_ABO = "echo_show_changed";
    static final String MUSIK_ABO = "musik_live";
    private static final String[] ANSICHTEN = {
        "kalender", "timer", "anwesenheit", "wetter", "schoner", "morgen",
        "player", "waschmaschine", "gaeste-wlan", "einkaufsliste", "muell", "server"
    };
    private static final List<String> NATIV_SEITEN = Collections.unmodifiableList(Arrays.asList(
        "wetter", "kalender", "timer", "anwesenheit", "morgen",
        "player", "waschmaschine", "waschmaschine-verlauf",
        "gaeste-wlan", "einkaufsliste", "muell", "server", "server-dienste"
    ));
    private static final long VERLAUF_GUELTIG = 300_000L;   // like the dashboard page: reload after 5 minutes

    private volatile PluginHost host;
    private Handler main;   // created on first use

    // settings (KS plugin settings + live HA panel config sync)
    private volatile boolean abo = true;
    private volatile String dashboard = "/echo-show/";
    private volatile String uebergang = "keine";
    private volatile boolean verlaufCache = true;
    private volatile String wetterEntity = "";
    private volatile String szeneOrt = "auto";
    private volatile boolean vorschauAktiv;
    private volatile String vorschauOrt = "meer";
    private volatile String vorschauWetter = "cloudy";
    private volatile String vorschauZeit = "tag";
    private volatile boolean sternbildNamen = true;
    private volatile int wetterFps = 30;
    private volatile SchonerDaten.Optionen schonerOpt = new SchonerDaten.Optionen();
    private volatile String schonerMusikAusschluss = "";
    private volatile boolean meinTagAuto = true;
    private volatile boolean vorlesenAuto = true;

    // live HA panel config (echo_show/plugin/konfig + echo_show_changed)
    private volatile Map<?, ?> konfigDaten;
    private volatile String haWetterEntity = "", haWetterName = "", haPersonen = "alle", haMusikAusschluss = "";
    private volatile String haMusikHelper = "input_text.musik_player";
    private volatile List<String> haSzeneOrte;
    private volatile String haSzeneWechsel = "taeglich", haSzeneFest = "";
    private volatile boolean haSzeneSterne = true, haSzeneNamen = true, haSzeneBlitze = true, haSzeneFigur = true, haSzeneTiere = true;
    private volatile Object haEinkaufsKonfig;
    private volatile Typeface caveatTypeface;
    private volatile boolean caveatGeladen;

    // weather data (live render_template), screensaver / screen for the scene's pause
    private volatile WetterLage.Daten wetterDaten;
    private volatile boolean schonerAktiv, bildschirmAus;
    private volatile boolean schonerEventDa, bildschirmEventDa;
    private boolean zustandAbos, schonerRegistriert;
    private final Object schonerLock = new Object();
    private final Map<String, SchonerDaten.Hinweis> schonerNotifs = new LinkedHashMap<>();
    private List<SchonerDaten.Hinweis> schonerWarnungen = Collections.emptyList();
    private List<SchonerDaten.Hinweis> schonerZusatz = Collections.emptyList();
    private SchonerDaten.Musik schonerMusik;
    private volatile SchonerDaten.Zustand schonerZustand = new SchonerDaten.Zustand();
    private volatile String haBasisUrl = "", coverGeladenUrl = "", coverLadeUrl = "";
    private volatile Bitmap coverBitmap;
    private ExecutorService coverLader;
    private WetterView schonerView;   // active KS native screensaver view, guarded by this

    // shared 4-entry caches for article / player cover / guest Wi-Fi images
    private interface BildCallback {
        void fertig(Bitmap scharf, Bitmap weich);
    }
    private static final class BildAnfrage {
        final String url;
        final boolean mitWeich;
        final BildCallback cb;
        BildAnfrage(String url, boolean mitWeich, BildCallback cb) { this.url = url; this.mitWeich = mitWeich; this.cb = cb; }
    }
    private final Object bildLock = new Object();
    private final LinkedHashMap<String, Bitmap[]> bildCache = new LinkedHashMap<>();
    private final Set<String> bildLade = new HashSet<>();
    private final List<BildAnfrage> bildWartend = new ArrayList<>();

    // "Mein Tag" and live news reading (echo_show/news/subscribe)
    private volatile MorgenDaten.NewsStatus newsStatus = new MorgenDaten.NewsStatus();
    private volatile TimerDaten.Zustand timerZustand = new TimerDaten.Zustand();
    private volatile boolean morgenAutoOffen;
    private final Runnable morgenAutoZu = () -> TestAnsicht.sicher(this::morgenAutoSchliessen);

    private void morgenAutoSchliessen() {
        if (newsStatus != null && newsStatus.aktiv) return;
        boolean istMorgen;
        synchronized (this) {
            istMorgen = aktuell != null && "morgen".equals(aktuell.m.ansicht);
        }
        if (istMorgen && morgenAutoOffen) {
            morgenAutoOffen = false;
            schliessen();
        }
    }

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
    private final Dauerabo aboSchonerNotif = new Dauerabo(SCHONER_NOTIF, zeit, melder);
    private final Dauerabo aboSchonerInfo = new Dauerabo(SCHONER_INFO, zeit, melder);
    private final Dauerabo aboNews = new Dauerabo(NEWS, zeit, melder);
    private final Dauerabo aboTimer = new Dauerabo(TIMER, zeit, melder);
    private final Dauerabo aboChanged = new Dauerabo(KONFIG_ABO, zeit, melder);
    private final Dauerabo aboMusik = new Dauerabo(MUSIK_ABO, zeit, melder);
    private String letzterMusikStand = "";
    private final Runnable musikNachladenDebounced = () -> TestAnsicht.sicher(this::playerDatenNachladen);
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
        uebergang = text(s.get("uebergang"), "keine");
        aboAbgleichen();
        personenAbgleichen();
        wetterEntity = text(s.get("wetterEntity"), "");
        String ortCfg = text(s.get("szeneOrt"), "täglich wechseln").toLowerCase(Locale.ROOT);
        szeneOrt = "täglich wechseln".equals(ortCfg) ? "auto" : ortCfg;
        vorschauAktiv = Boolean.TRUE.equals(s.get("vorschauAktiv"));
        vorschauOrt = text(s.get("vorschauOrt"), "meer").toLowerCase(Locale.ROOT);
        vorschauWetter = wetterKey(text(s.get("vorschauWetter"), "Bewölkt"));
        vorschauZeit = zeitKey(text(s.get("vorschauZeit"), "Tag"));
        sternbildNamen = !Boolean.FALSE.equals(s.get("sternbildNamen"));
        wetterFps = parseInt(s.get("wetterFps"), 30, 15, 60);
        wetterAbgleichen();
        SchonerDaten.Optionen opt = new SchonerDaten.Optionen();
        opt.uhr = !Boolean.FALSE.equals(s.get("schonerUhr"));
        opt.uhrGross = !"kompakt".equals(s.get("schonerUhrGross"));
        opt.sekunden = Boolean.TRUE.equals(s.get("schonerSekunden"));
        opt.datum = !Boolean.FALSE.equals(s.get("schonerDatum"));
        opt.wetter = !Boolean.FALSE.equals(s.get("schonerWetter"));
        opt.hinweise = !Boolean.FALSE.equals(s.get("schonerHinweise"));
        opt.musik = !Boolean.FALSE.equals(s.get("schonerMusik"));
        opt.ruhigFps = parseInt(s.get("schonerRuhigFps"), 15, 12, 60);
        opt.wetterFps = parseInt(s.get("schonerWetterFps"), 60, 20, 60);
        schonerOpt = opt;
        schonerMusikAusschluss = text(s.get("schonerMusikAusschluss"), "");
        meinTagAuto = !Boolean.FALSE.equals(s.get("meinTagAuto"));
        vorlesenAuto = !Boolean.FALSE.equals(s.get("vorlesenAuto"));
        schonerAbgleichen();
        newsAbgleichen();
        timerAbgleichen();
        konfigAbgleichen();
        schonerOptionenMelden(opt);
        konfigPushAnOffene();
        status();
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        List<String> args = args(arguments);
        switch (command) {
            case "zeigen": {
                String raw = args.isEmpty() ? "kalender" : args.get(0);
                String name = ansicht(raw);
                if (name == null) {
                    PluginHost h = host;
                    if (h != null) h.status("Unbekannte Ansicht: " + raw, true);
                    return;
                }
                Map<String, Object> lp = linkParam(name, args.size() > 1 ? args.subList(1, args.size()) : Collections.<String>emptyList());
                aliasParam(raw, lp);
                oeffnen(name, null, false, lp, "Befehl");
                return;
            }
            case "gaestewlan":
                oeffnen("gaeste-wlan", null, false, linkParam("gaeste-wlan", args), "Befehl");
                return;
            case "kalender":
            case "timer":
            case "anwesenheit":
            case "wetter":
            case "schoner":
            case "morgen":
            case "player":
            case "waschmaschine":
            case "einkaufsliste":
            case "muell":
            case "server":
                oeffnen(command, null, false, linkParam(command, args), "Befehl");
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
        else if (("ks.ha.subscription." + SCHONER_NOTIF).equals(event)) schonerNotifEreignis(payload);
        else if (("ks.ha.subscription." + SCHONER_INFO).equals(event)) schonerInfoEreignis(payload);
        else if (("ks.ha.subscription." + NEWS).equals(event)) newsEreignis(payload);
        else if (("ks.ha.subscription." + TIMER).equals(event)) timerEreignis(payload);
        else if (("ks.ha.subscription." + KONFIG_ABO).equals(event)) changedEreignis(payload);
        else if (("ks.ha.subscription." + MUSIK_ABO).equals(event)) musikEreignis(payload);
        else if ("ks.screensaver.state".equals(event) || "ks.screen.state".equals(event)) {
            if ("ks.screensaver.state".equals(event)) {
                schonerEventDa = true;
                schonerAktiv = Boolean.TRUE.equals(payload.get("active"));
                if (schonerAktiv) schonerZusatzLaden();
            } else {
                bildschirmEventDa = true;
                bildschirmAus = Boolean.FALSE.equals(payload.get("on"));
            }
            pauseMelden();
        }
        else if ("overlay.closed".equals(event) && OVERLAY.equals(payload.get("key"))) {
            synchronized (this) { aktuell = null; }   // back gesture; the view's onDestroy records the result
            musikAboAbgleichen();
        }
    }

    @Override public void stop() {
        host = null;   // KS has revoked the host and removes the overlay & screensaver itself
        synchronized (this) {
            aktuell = null;
            schonerView = null;
        }
        aboAnsicht.stop();
        aboPersonen.stop();
        aboWetter.stop();
        aboSchonerNotif.stop();
        aboSchonerInfo.stop();
        aboNews.stop();
        aboTimer.stop();
        aboChanged.stop();
        aboMusik.stop();
        letzterMusikStand = "";
        morgenAutoOffen = false;
        if (main != null) {
            main.removeCallbacks(morgenAutoZu);
            main.removeCallbacks(musikNachladenDebounced);
        }
        synchronized (bildLock) {
            for (Bitmap[] pair : bildCache.values()) {
                if (pair != null) {
                    for (Bitmap b : pair) if (b != null && !b.isRecycled()) b.recycle();
                }
            }
            bildCache.clear();
            bildLade.clear();
            bildWartend.clear();
        }
        ExecutorService cl;
        synchronized (this) {
            cl = coverLader;
            coverLader = null;
        }
        if (cl != null) cl.shutdownNow();
        coverGeladenUrl = coverLadeUrl = "";
        coverBitmap = null;
        caveatTypeface = null;
        caveatGeladen = false;
        konfigDaten = null;
        try {
            WetterView.beenden();   // end the layer build thread, give the weather bitmaps back
        } catch (RuntimeException | LinkageError ignored) {
            // nothing was built
        }
        personenAboFuer = null;
        zustandAbos = false;
        schonerRegistriert = false;
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
            msg.put("nativ_seiten", NATIV_SEITEN);
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
            if (!fehler && abo == aboChanged) {
                konfigLaden();
                schonerZusatzLaden();
            }
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
            Object aktion = e.get("aktion");
            if ("schliessen".equals(aktion)) {
                Object seiteObj = e.get("seite");
                String ziel = seiteObj instanceof String ? ansicht((String) seiteObj) : null;
                boolean passt;
                synchronized (this) {
                    passt = aktuell != null && (ziel == null || ziel.equals(aktuell.m.ansicht));
                }
                if (passt) schliessen();
                return;
            }
            if (!"vorbau".equals(aktion)) return;
            Object seite = e.get("seite"), token = e.get("token");
            // Handle test-* pages and explicit native view requests (nativ: true from echo_show LLM/view router)
            if (!(seite instanceof String) || !(token instanceof String)) return;
            String sName = (String) seite;
            boolean nativ = Boolean.TRUE.equals(e.get("nativ"));
            if (!sName.startsWith("test-") && !nativ) return;
            String ohneTest = sName.startsWith("test-") ? sName.substring(5) : sName;
            String name = ansicht(ohneTest);
            if (name == null) return;
            Map<String, Object> param = new LinkedHashMap<>();
            if (e.get("param") instanceof Map) {
                for (Map.Entry<?, ?> x : ((Map<?, ?>) e.get("param")).entrySet()) param.put(String.valueOf(x.getKey()), x.getValue());
            }
            aliasParam(ohneTest, param);
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
        int sek = 300;
        Object ps = param.get("sekunden");
        if ("timer".equals(ansicht) && ps instanceof Number) sek = Math.max(1, Math.min(86400, ((Number) ps).intValue()));
        Messung m = new Messung(ansicht, token, quelle, kalt, param, t0, sek, false);
        if (h.isHeadless()) {
            melden(m, "bereit", fehler("kein Bildschirm"));
            return;
        }
        TestAnsicht a = new TestAnsicht(this, m);
        synchronized (this) { aktuell = a; }
        ramLesen(m, true, a);
        if ("kalender".equals(ansicht)) kalenderLaden(h, a);
        else if ("anwesenheit".equals(ansicht)) anwesenheitLaden(a);
        else if ("player".equals(ansicht)) ansichtDatenLaden(a, wsMsg("echo_show/musik/daten", "dashboard", dashboard));
        else if ("waschmaschine".equals(ansicht)) ansichtDatenLaden(a, wsMsg("echo_show/waschmaschine/daten"));
        else if ("gaeste-wlan".equals(ansicht)) ansichtDatenLaden(a, wsMsg("echo_show/gaeste_wlan/daten"));
        else if ("einkaufsliste".equals(ansicht)) einkaufslisteLaden(a);
        else if ("muell".equals(ansicht)) ansichtDatenLaden(a, wsMsg("echo_show/muell/daten"));
        else if ("server".equals(ansicht)) ansichtDatenLaden(a, wsMsg("echo_show/server/daten"));
        else if ("wetter".equals(ansicht) || "schoner".equals(ansicht) || "morgen".equals(ansicht)) {
            if ("morgen".equals(ansicht)) {
                if (main != null) main.removeCallbacks(morgenAutoZu);
                if (!"HA-Auto".equals(quelle)) morgenAutoOffen = false;
            }
            if ("schoner".equals(ansicht)) schonerZusatzLaden();
            WetterLage.Daten wd = wetterDaten;
            if (wd != null) {
                m.wetter = wd;
                m.wetterAusCache = true;
                m.tDaten = SystemClock.uptimeMillis();
            }   // otherwise wetterEreignis fills it in as soon as HA sends the first result
        }
        musikAboAbgleichen();
        try {
            if (!"schoner".equals(ansicht) && (schonerAktiv || bildschirmAus)) {
                bildschirmAufwecken(h);
            }
            h.showOverlay(OVERLAY, spec(), a);
            meldenStatus(m, "offen", offenDaten(m));
        } catch (RuntimeException e) {   // older KS, rate limit or stopped session
            synchronized (this) { if (aktuell == a) aktuell = null; }
            melden(m, "bereit", fehler("Overlay: " + e.getMessage()));
            h.status("Overlay nicht möglich: " + e.getMessage(), true);
        }
    }

    /** Ask HA echo_show integration to wake the screen and exit KS screensaver before showing any view. */
    private void bildschirmAufwecken(PluginHost h) {
        if (h == null) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/ansicht/aufwecken");
        msg.put("dashboard", dashboard);
        try {
            h.haCall(msg, (ok, data, err) -> {});
        } catch (RuntimeException ignored) {}
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
        Kalender.Spanne sp = Kalender.spanne(a.m.param);
        a.m.kalenderSpanne = sp;
        kalenderBereichLaden(a, sp.start, sp.tageAnzahl);
    }

    /** Loads any calendar date range (initial open or when user switches Tag / 3 Tage / Woche / Monat / Liste). */
    void kalenderBereichLaden(TestAnsicht a, Calendar start, int tageAnzahl) {
        PluginHost h = host;
        if (h == null) return;
        Messung m = a.m;
        try {
            h.haCall(Kalender.anfrageEcho(start, tageAnzahl), (ok, data, error) -> {
                if (m.tDaten == 0) m.tDaten = SystemClock.uptimeMillis();
                if (ok) {
                    try {
                        m.kalenderErgebnis = Kalender.lesenEchoDaten(data);
                        m.termine = m.kalenderErgebnis != null ? m.kalenderErgebnis.termine : Collections.<Kalender.Termin>emptyList();
                        m.datenFehler = null;
                    } catch (RuntimeException e) {
                        m.datenFehler = "Antwort unlesbar";
                    }
                } else {
                    m.datenFehler = error == null ? "unbekannt" : error;
                }
                main().post(() -> TestAnsicht.sicher(a::datenDa));
            });
        } catch (RuntimeException e) {
            if (m.tDaten == 0) m.tDaten = SystemClock.uptimeMillis();
            m.datenFehler = "haCall: " + e.getMessage();
            main().post(() -> TestAnsicht.sicher(a::datenDa));
        }
    }

    /** Loads JSON data for one of the native HA-WS views (player, waschmaschine, gaeste-wlan, muell, server). */
    private void ansichtDatenLaden(TestAnsicht a, Map<String, Object> msg) {
        PluginHost h = host;
        if (h == null) return;
        Messung m = a.m;
        try {
            h.haCall(msg, (ok, data, error) -> {
                if (m.tDaten == 0) m.tDaten = SystemClock.uptimeMillis();
                if (ok) {
                    m.ansichtDaten = data;
                    m.datenFehler = null;
                } else {
                    m.datenFehler = error == null ? "unbekannt" : error;
                }
                main().post(() -> TestAnsicht.sicher(a::datenDa));
            });
        } catch (RuntimeException e) {
            if (m.tDaten == 0) m.tDaten = SystemClock.uptimeMillis();
            m.datenFehler = "haCall: " + e.getMessage();
            main().post(() -> TestAnsicht.sicher(a::datenDa));
        }
    }

    /** Reloads Music Player data if the player view is currently open. */
    void playerDatenNachladen() {
        TestAnsicht a;
        synchronized (this) { a = aktuell != null && "player".equals(aktuell.m.ansicht) ? aktuell : null; }
        if (a != null) ansichtDatenLaden(a, wsMsg("echo_show/musik/daten", "dashboard", dashboard));
    }

    /** Subscribes to live media_player + helper changes only while the native Music Player view is open. */
    private void musikAboAbgleichen() {
        PluginHost h = host;
        if (h == null) return;
        boolean offen;
        synchronized (this) { offen = !h.isHeadless() && aktuell != null && "player".equals(aktuell.m.ansicht); }
        if (!offen) {
            letzterMusikStand = "";
            if (main != null) main.removeCallbacks(musikNachladenDebounced);
            aboMusik.setzen(h, null, null);
            return;
        }
        String excl = schonerMusikAusschluss;
        if (!haMusikAusschluss.isEmpty()) excl = excl.isEmpty() ? haMusikAusschluss : excl + "," + haMusikAusschluss;
        Map<String, Object> msg = MusikDaten.liveAbo(excl, haMusikHelper);
        aboMusik.setzen(h, msg, (String) msg.get("template"));
    }

    private void musikEreignis(Map<String, Object> p) {
        if (!"event".equals(p.get("type"))) {
            aboMusik.ereignis(p);
            return;
        }
        Object evObj = p.get("event");
        if (!(evObj instanceof Map)) return;
        Map<?, ?> ev = (Map<?, ?>) evObj;
        if (!ev.containsKey("result")) return;
        Object raw = ev.get("result");
        String stand = raw == null ? "" : String.valueOf(raw);
        if (stand.equals(letzterMusikStand)) return;
        letzterMusikStand = stand;
        main().removeCallbacks(musikNachladenDebounced);
        main().postDelayed(musikNachladenDebounced, 180L);
    }

    /** Loads shopping list config (from cached plugin/konfig or HA) + purchase history (echo_show/einkauf/verlauf). */
    private void einkaufslisteLaden(TestAnsicht a) {
        PluginHost h = host;
        if (h == null) return;
        Messung m = a.m;
        Object ek = haEinkaufsKonfig != null ? haEinkaufsKonfig : konfigDaten;
        if (ek != null) {
            m.ansichtDaten = ek;
        } else {
            try {
                h.haCall(wsMsg("echo_show/plugin/konfig", "dashboard", dashboard), (ok, data, error) -> {
                    if (ok && data instanceof Map) {
                        konfigAnwenden((Map<?, ?>) data);
                        m.ansichtDaten = haEinkaufsKonfig != null ? haEinkaufsKonfig : data;
                    } else if (!ok && m.ansichtDaten == null) {
                        m.datenFehler = error == null ? "unbekannt" : error;
                    }
                    if (m.tDaten == 0) m.tDaten = SystemClock.uptimeMillis();
                    main().post(() -> TestAnsicht.sicher(a::datenDa));
                });
            } catch (RuntimeException ignored) {}
        }
        try {
            h.haCall(wsMsg("echo_show/einkauf/verlauf"), (ok, data, error) -> {
                if (m.tDaten == 0) m.tDaten = SystemClock.uptimeMillis();
                if (ok) m.ansichtZusatz = data;
                main().post(() -> TestAnsicht.sicher(a::datenDa));
            });
        } catch (RuntimeException ignored) {
            if (m.tDaten == 0) m.tDaten = SystemClock.uptimeMillis();
            main().post(() -> TestAnsicht.sicher(a::datenDa));
        }
    }

    /** Forwards any HA WebSocket call (service call or integration WS command) from interactive native views. */
    void haCallWeiterleiten(Map<String, Object> msg, HaAntwort cb) {
        PluginHost h = host;
        if (h == null || msg == null) {
            if (cb != null) main().post(() -> TestAnsicht.sicher(() -> cb.fertig(false, null, "nicht verbunden")));
            return;
        }
        try {
            h.haCall(msg, (ok, data, err) -> {
                if (cb != null) main().post(() -> TestAnsicht.sicher(() -> cb.fertig(ok, data, err)));
            });
        } catch (RuntimeException e) {
            if (cb != null) main().post(() -> TestAnsicht.sicher(() -> cb.fertig(false, null, e.getMessage())));
        }
    }

    interface HaAntwort {
        void fertig(boolean ok, Object data, String err);
    }

    // ---- presence: people subscription and history ---------------------------------------------------------------

    /** Keeps one render_template subscription over the person domain, like the dashboard's live states. */
    private void personenAbgleichen() {
        PluginHost h = host;
        if (h == null) return;
        String pList = haPersonen;
        Map<String, Object> msg = h.isHeadless() ? null : Anwesenheit.personenAbo(Anwesenheit.ids(pList));
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
            ps = Anwesenheit.personen(ev.get("result"), Anwesenheit.ids(haPersonen), Json::lesen);
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
        String effEntity = !wetterEntity.isEmpty() ? wetterEntity : haWetterEntity;
        Map<String, Object> msg = h.isHeadless() ? null : WetterLage.abo(effEntity);
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
            h.executeCommand("getDashboardState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                if (ok && data instanceof Map) {
                    Object u = ((Map<?, ?>) data).get("homeAssistantUrl");
                    if (u instanceof String && !((String) u).trim().isEmpty()) {
                        haBasisUrl = ((String) u).trim().replaceAll("/+$", "");
                        coverNachladen();
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // without host.read the scene only pauses when its view is hidden
        }
    }

    private void pauseMelden() {
        TestAnsicht a;
        WetterView sv;
        synchronized (this) {
            a = aktuell;
            sv = schonerView;
        }
        if (a != null) main().post(() -> TestAnsicht.sicher(a::pauseGeaendert));
        if (sv != null) main().post(() -> TestAnsicht.sicher(sv::pauseGeaendert));
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
        WetterView.vorwaermen(d, effektiverOrt(), haSzeneFigur, haSzeneTiere);
        TestAnsicht a;
        WetterView sv;
        synchronized (this) {
            a = aktuell != null && ("wetter".equals(aktuell.m.ansicht) || "schoner".equals(aktuell.m.ansicht) || "morgen".equals(aktuell.m.ansicht)) ? aktuell : null;
            sv = schonerView;
        }
        if (sv != null) main().post(() -> TestAnsicht.sicher(() -> sv.setDaten(d)));
        if (a == null) return;
        a.m.wetter = d;
        if (a.m.tDaten == 0) a.m.tDaten = SystemClock.uptimeMillis();
        main().post(() -> TestAnsicht.sicher(a::datenDa));
    }

    // ---- live HA panel config sync (echo_show/plugin/konfig + echo_show_changed + schoner/zusatz) ------------------

    private void konfigAbgleichen() {
        PluginHost h = host;
        if (h == null) return;
        if (h.isHeadless()) {
            aboChanged.setzen(h, null, null);
            return;
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "subscribe_events");
        msg.put("event_type", "echo_show_changed");
        aboChanged.setzen(h, msg, "echo_show_changed");
        konfigLaden();
        schonerZusatzLaden();
    }

    private void changedEreignis(Map<String, Object> p) {
        if (!"event".equals(p.get("type"))) {
            aboChanged.ereignis(p);
            return;
        }
        konfigLaden();
        schonerZusatzLaden();
        TestAnsicht a;
        synchronized (this) { a = aktuell; }
        if (a != null) {
            String ans = a.m.ansicht;
            if ("player".equals(ans)) ansichtDatenLaden(a, wsMsg("echo_show/musik/daten", "dashboard", dashboard));
            else if ("waschmaschine".equals(ans)) ansichtDatenLaden(a, wsMsg("echo_show/waschmaschine/daten"));
            else if ("gaeste-wlan".equals(ans)) ansichtDatenLaden(a, wsMsg("echo_show/gaeste_wlan/daten"));
            else if ("einkaufsliste".equals(ans)) einkaufslisteLaden(a);
            else if ("muell".equals(ans)) ansichtDatenLaden(a, wsMsg("echo_show/muell/daten"));
            else if ("server".equals(ans)) ansichtDatenLaden(a, wsMsg("echo_show/server/daten"));
            else if ("kalender".equals(ans)) {
                Calendar st = a.m.kalenderSpanne != null ? a.m.kalenderSpanne.start : Kalender.montag(Calendar.getInstance());
                int tg = a.m.kalenderSpanne != null ? a.m.kalenderSpanne.tageAnzahl : 7;
                kalenderBereichLaden(a, st, tg);
            }
        }
    }

    private void konfigLaden() {
        PluginHost h = host;
        if (h == null || h.isHeadless()) return;
        try {
            h.haCall(wsMsg("echo_show/plugin/konfig", "dashboard", dashboard), (ok, data, error) -> {
                if (ok && data instanceof Map) konfigAnwenden((Map<?, ?>) data);
            });
        } catch (RuntimeException ignored) {}
    }

    private void konfigAnwenden(Map<?, ?> cfg) {
        konfigDaten = cfg;
        String neuWetter = cfg.get("wetter") instanceof String ? ((String) cfg.get("wetter")).trim() : "";
        String neuWetterName = cfg.get("wetter_name") instanceof String ? ((String) cfg.get("wetter_name")).trim() : "";
        boolean wetterGeaendert = !neuWetter.equals(haWetterEntity);
        haWetterEntity = neuWetter;
        haWetterName = neuWetterName;

        if (cfg.get("szene") instanceof Map) {
            Map<?, ?> sz = (Map<?, ?>) cfg.get("szene");
            if (sz.get("orte") instanceof List) {
                List<String> ol = new ArrayList<>();
                for (Object o : (List<?>) sz.get("orte")) if (o instanceof String) ol.add(((String) o).trim().toLowerCase(Locale.ROOT));
                haSzeneOrte = ol;
            }
            if (sz.get("wechsel") instanceof String) haSzeneWechsel = ((String) sz.get("wechsel")).trim().toLowerCase(Locale.ROOT);
            if (sz.get("fest") instanceof String) haSzeneFest = ((String) sz.get("fest")).trim().toLowerCase(Locale.ROOT);
            if (sz.containsKey("sterne")) haSzeneSterne = !Boolean.FALSE.equals(sz.get("sterne"));
            if (sz.containsKey("namen")) haSzeneNamen = !Boolean.FALSE.equals(sz.get("namen"));
            if (sz.containsKey("blitze")) haSzeneBlitze = !Boolean.FALSE.equals(sz.get("blitze"));
            if (sz.containsKey("figur")) haSzeneFigur = !Boolean.FALSE.equals(sz.get("figur"));
            if (sz.containsKey("tiere")) haSzeneTiere = !Boolean.FALSE.equals(sz.get("tiere"));
        }

        if (cfg.get("einkaufsliste") != null) haEinkaufsKonfig = cfg.get("einkaufsliste");

        String neuPersonen = joinCsv(cfg.get("personen"));
        if (neuPersonen.isEmpty()) neuPersonen = "alle";
        boolean personenGeaendert = !neuPersonen.equals(haPersonen);
        haPersonen = neuPersonen;

        String neuMusikExcl = "";
        String neuMusikHelper = "input_text.musik_player";
        if (cfg.get("musik") instanceof Map) {
            Map<?, ?> mu = (Map<?, ?>) cfg.get("musik");
            neuMusikExcl = joinCsv(mu.get("nie_anbieten"));
            if (mu.get("helper") instanceof String && !((String) mu.get("helper")).trim().isEmpty()) {
                neuMusikHelper = ((String) mu.get("helper")).trim();
            }
        }
        boolean musikExclGeaendert = !neuMusikExcl.equals(haMusikAusschluss);
        boolean musikHelperGeaendert = !neuMusikHelper.equals(haMusikHelper);
        haMusikAusschluss = neuMusikExcl;
        haMusikHelper = neuMusikHelper;

        if (wetterGeaendert && wetterEntity.isEmpty()) wetterAbgleichen();
        if (personenGeaendert) personenAbgleichen();
        if (musikExclGeaendert) schonerAbgleichen();
        if (musikExclGeaendert || musikHelperGeaendert) musikAboAbgleichen();
        WetterLage.Daten wd = wetterDaten;
        if (wd != null) WetterView.vorwaermen(wd, effektiverOrt(), haSzeneFigur, haSzeneTiere);
        konfigPushAnOffene();
    }

    private static String joinCsv(Object raw) {
        StringBuilder sb = new StringBuilder();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof String && !((String) o).trim().isEmpty()) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(((String) o).trim());
                }
            }
        } else if (raw instanceof String) {
            sb.append(((String) raw).trim());
        }
        return sb.toString();
    }

    private void konfigPushAnOffene() {
        WetterView sv;
        TestAnsicht a;
        synchronized (this) {
            sv = schonerView;
            a = aktuell;
        }
        if (sv != null) main().post(() -> TestAnsicht.sicher(() -> konfigAnwenden(sv)));
        if (a != null) main().post(() -> TestAnsicht.sicher(a::konfigAnwenden));
    }

    private void schonerZusatzLaden() {
        PluginHost h = host;
        if (h == null || h.isHeadless()) return;
        try {
            h.haCall(wsMsg("echo_show/schoner/zusatz"), (ok, data, error) -> {
                if (!ok) return;
                List<SchonerDaten.Hinweis> neu = SchonerDaten.zusatzLesen(data);
                SchonerDaten.Zustand z;
                synchronized (schonerLock) {
                    schonerZusatz = neu;
                    z = SchonerDaten.zusammen(schonerWarnungen, schonerZusatz, schonerNotifs, schonerMusik);
                }
                schonerZustandMelden(z);
            });
        } catch (RuntimeException ignored) {}
    }

    private String effektiverOrt() {
        if (vorschauAktiv) return vorschauOrt;
        if (!"auto".equals(szeneOrt)) return szeneOrt;
        if ("fest".equals(haSzeneWechsel) && !haSzeneFest.isEmpty()) return haSzeneFest;
        return "auto";
    }

    void konfigAnwenden(WetterView v) {
        if (v == null) return;
        v.setWetterName(haWetterName);
        v.setSzeneOptionen(haSzeneOrte, haSzeneSterne, sternbildNamen && haSzeneNamen, haSzeneBlitze, haSzeneFigur, haSzeneTiere);
        v.setOrt(effektiverOrt());
        if (vorschauAktiv) v.setEinstellungsVorschau(true, vorschauOrt, vorschauWetter, vorschauZeit);
        else v.setEinstellungsVorschau(false, null, null, null);
    }

    Object einkaufsKonfig() { return haEinkaufsKonfig != null ? haEinkaufsKonfig : konfigDaten; }

    Typeface caveatTf() {
        if (caveatGeladen) return caveatTypeface;
        caveatGeladen = true;
        PluginHost h = host;
        if (h != null) {
            try {
                String p = h.assetPath("caveat.ttf");
                if (p != null && !p.isEmpty()) caveatTypeface = Typeface.createFromFile(p);
            } catch (RuntimeException | LinkageError ignored) {}
        }
        return caveatTypeface;
    }

    // ---- native KS screensaver ("Wetterszene") ---------------------------------------------------------------------

    private final OverlayFactory schonerFactory = new OverlayFactory() {
        @Override public View create(Context ctx, KsTheme theme) {
            WetterView v = new WetterView(ctx, new WetterView.Aktionen() {
                @Override public void zurueck() {}
                @Override public boolean pausiert() { return schonerPausiert(); }
                @Override public void fertig(long aufbauMs) {}
            }, theme, schonerOpt, "live", null, sternbildNamen, false);
            WetterLage.Daten wd = wetterDaten;
            if (wd != null) v.setDaten(wd);
            konfigAnwenden(v);
            v.setSchoner(schonerZustand);
            synchronized (EchoShowTestPlugin.this) { schonerView = v; }
            return v.mitVordergrund(ctx);
        }

        @Override public void onDestroy(View view) {
            synchronized (EchoShowTestPlugin.this) {
                if (schonerView != null && (schonerView == view || schonerView == view.getTag())) schonerView = null;
            }
        }
    };

    private void schonerAbgleichen() {
        PluginHost h = host;
        if (h == null) return;
        if (!h.isHeadless() && !schonerRegistriert) {
            try {
                h.publishScreensaverView(SCHONER_KEY, "Wetterszene", schonerFactory);
                schonerRegistriert = true;
            } catch (RuntimeException | LinkageError ignored) {
                // older KS without native screensaver support
            }
        }
        SchonerDaten.Optionen opt = schonerOpt;
        boolean brauchtNotif = !h.isHeadless() && opt.hinweise;
        boolean brauchtInfo = !h.isHeadless() && (opt.hinweise || opt.musik);
        aboSchonerNotif.setzen(h, brauchtNotif ? SchonerDaten.notifAbo() : null, brauchtNotif ? "notif" : null);
        String excl = schonerMusikAusschluss;
        if (!haMusikAusschluss.isEmpty()) excl = excl.isEmpty() ? haMusikAusschluss : excl + "," + haMusikAusschluss;
        Map<String, Object> infoMsg = brauchtInfo ? SchonerDaten.infoAbo(excl) : null;
        aboSchonerInfo.setzen(h, infoMsg, infoMsg == null ? null : (String) infoMsg.get("template"));
    }

    private void schonerNotifEreignis(Map<String, Object> p) {
        if (!"event".equals(p.get("type"))) {
            aboSchonerNotif.ereignis(p);
            return;
        }
        if (!(p.get("event") instanceof Map)) return;
        SchonerDaten.Zustand z;
        synchronized (schonerLock) {
            SchonerDaten.notifEreignis(schonerNotifs, (Map<?, ?>) p.get("event"));
            z = SchonerDaten.zusammen(schonerWarnungen, schonerZusatz, schonerNotifs, schonerMusik);
        }
        schonerZustandMelden(z);
    }

    private void schonerInfoEreignis(Map<String, Object> p) {
        if (!"event".equals(p.get("type"))) {
            aboSchonerInfo.ereignis(p);
            return;
        }
        if (!(p.get("event") instanceof Map)) return;
        Map<?, ?> ev = (Map<?, ?>) p.get("event");
        if (!ev.containsKey("result")) return;
        Object[] res;
        try {
            res = SchonerDaten.infoLesen(ev.get("result"), Json::lesen);
        } catch (RuntimeException e) {
            return;
        }
        SchonerDaten.Zustand z;
        String neuerCoverUrl = "";
        synchronized (schonerLock) {
            @SuppressWarnings("unchecked")
            List<SchonerDaten.Hinweis> w = (List<SchonerDaten.Hinweis>) res[0];
            schonerWarnungen = w;
            SchonerDaten.Musik m = (SchonerDaten.Musik) res[1];
            if (m != null && !m.bildUrl.isEmpty()) {
                if (m.bildUrl.equals(coverGeladenUrl) && coverBitmap != null && !coverBitmap.isRecycled()) {
                    m.cover = coverBitmap;
                } else {
                    neuerCoverUrl = m.bildUrl;
                }
            }
            schonerMusik = m;
            z = SchonerDaten.zusammen(schonerWarnungen, schonerZusatz, schonerNotifs, schonerMusik);
        }
        schonerZustandMelden(z);
        if (!neuerCoverUrl.isEmpty()) coverStarten(neuerCoverUrl);
    }

    private void coverNachladen() {
        String url;
        synchronized (schonerLock) {
            url = schonerMusik != null && !schonerMusik.bildUrl.isEmpty() && schonerMusik.cover == null ? schonerMusik.bildUrl : "";
        }
        if (!url.isEmpty()) coverStarten(url);
        wartendeBilderNachladen();
    }

    private synchronized ExecutorService coverLader() {
        if (coverLader == null) coverLader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "schoner-cover");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
        return coverLader;
    }

    /** Loads the playing track's album cover (HTTP/HTTPS or HA signed path) downsampled to ~128 px on a background thread. */
    private void coverStarten(final String rohUrl) {
        if (rohUrl == null || rohUrl.isEmpty() || rohUrl.equals(coverLadeUrl)) return;
        if (rohUrl.startsWith("http://") || rohUrl.startsWith("https://")) {
            coverLadeUrl = rohUrl;
            coverHolen(rohUrl, rohUrl);
            return;
        }
        if (!rohUrl.startsWith("/")) return;
        final String basis = haBasisUrl;
        PluginHost h = host;
        if (basis.isEmpty()) {
            if (h == null) return;
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "get_config");
            try {
                h.haCall(msg, (ok, data, error) -> {
                    if (ok && data instanceof Map) {
                        Map<?, ?> cfg = (Map<?, ?>) data;
                        Object iu = cfg.get("internal_url"), eu = cfg.get("external_url");
                        String b = iu instanceof String && !((String) iu).trim().isEmpty() ? (String) iu
                            : (eu instanceof String && !((String) eu).trim().isEmpty() ? (String) eu : "");
                        if (!b.isEmpty()) {
                            haBasisUrl = b.trim().replaceAll("/+$", "");
                            coverNachladen();
                        }
                    }
                });
            } catch (RuntimeException ignored) {}
            return;
        }
        coverLadeUrl = rohUrl;
        if (rohUrl.contains("token=") || rohUrl.contains("authSig=") || rohUrl.startsWith("/local/")) {
            coverHolen(rohUrl, basis + rohUrl);
            return;
        }
        if (h == null) return;
        Map<String, Object> sign = new LinkedHashMap<>();
        sign.put("type", "auth/sign_path");
        sign.put("path", rohUrl);
        try {
            h.haCall(sign, (ok, data, error) -> {
                String pfad = ok && data instanceof Map && ((Map<?, ?>) data).get("path") instanceof String
                    ? (String) ((Map<?, ?>) data).get("path") : rohUrl;
                coverHolen(rohUrl, basis + pfad);
            });
        } catch (RuntimeException ignored) {
            coverHolen(rohUrl, basis + rohUrl);
        }
    }

    private void coverHolen(final String rohUrl, final String vollUrl) {
        try {
            coverLader().execute(() -> {
                Bitmap bm = coverDownload(vollUrl);
                if (bm == null) {
                    if (rohUrl.equals(coverLadeUrl)) coverLadeUrl = "";
                    return;
                }
                SchonerDaten.Zustand z = null;
                synchronized (schonerLock) {
                    if (!rohUrl.equals(coverLadeUrl)) {
                        bm.recycle();
                        return;
                    }
                    coverGeladenUrl = rohUrl;
                    coverBitmap = bm;
                    if (schonerMusik != null && rohUrl.equals(schonerMusik.bildUrl)) {
                        SchonerDaten.Musik neu = new SchonerDaten.Musik();
                        neu.titel = schonerMusik.titel;
                        neu.kuenstler = schonerMusik.kuenstler;
                        neu.bildUrl = schonerMusik.bildUrl;
                        neu.cover = bm;
                        schonerMusik = neu;
                        z = SchonerDaten.zusammen(schonerWarnungen, schonerZusatz, schonerNotifs, schonerMusik);
                    }
                }
                if (z != null) schonerZustandMelden(z);
            });
        } catch (RuntimeException ignored) {
            if (rohUrl.equals(coverLadeUrl)) coverLadeUrl = "";
        }
    }

    private static Bitmap coverDownload(String vollUrl) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(vollUrl).openConnection();
            con.setConnectTimeout(4000);
            con.setReadTimeout(5000);
            con.setInstanceFollowRedirects(true);
            if (con.getResponseCode() != 200) return null;
            byte[] bytes;
            try (InputStream in = con.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n, sum = 0;
                while ((n = in.read(buf)) > 0) {
                    sum += n;
                    if (sum > 2_000_000) return null;   // guard RAM on Echo Show
                    out.write(buf, 0, n);
                }
                bytes = out.toByteArray();
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            int sample = 1, maxDim = Math.max(opts.outWidth, opts.outHeight);
            while (maxDim / (sample * 2) >= 128) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            Bitmap raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (raw == null) return null;
            if (raw.getWidth() > 160 || raw.getHeight() > 160) {
                int s = Math.min(raw.getWidth(), raw.getHeight());
                Bitmap sq = Bitmap.createBitmap(raw, (raw.getWidth() - s) / 2, (raw.getHeight() - s) / 2, s, s);
                Bitmap scaled = Bitmap.createScaledBitmap(sq, 128, 128, true);
                if (sq != raw) sq.recycle();
                raw.recycle();
                return scaled;
            }
            return raw;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private void schonerZustandMelden(SchonerDaten.Zustand z) {
        schonerZustand = z;
        WetterView sv;
        TestAnsicht a;
        synchronized (this) {
            sv = schonerView;
            a = aktuell != null && "schoner".equals(aktuell.m.ansicht) ? aktuell : null;
        }
        if (sv != null) main().post(() -> TestAnsicht.sicher(() -> sv.setSchoner(z)));
        if (a != null) main().post(() -> TestAnsicht.sicher(() -> a.schonerFuellen(z)));
    }

    private void schonerOptionenMelden(SchonerDaten.Optionen opt) {
        WetterView sv;
        TestAnsicht a;
        synchronized (this) {
            sv = schonerView;
            a = aktuell != null && "schoner".equals(aktuell.m.ansicht) ? aktuell : null;
        }
        if (sv != null) main().post(() -> TestAnsicht.sicher(() -> sv.setSchonerOptionen(opt)));
        if (a != null) main().post(() -> TestAnsicht.sicher(() -> a.schonerOptionen(opt)));
    }

    private static String wetterKey(String label) {
        switch (label.toLowerCase(Locale.ROOT)) {
            case "sonnig": return "sunny";
            case "leicht bewölkt": return "partlycloudy";
            case "bewölkt": return "cloudy";
            case "regen": return "rainy";
            case "starkregen": return "pouring";
            case "gewitter": return "lightning-rainy";
            case "schnee": return "snowy";
            case "schneeregen": return "snowy-rainy";
            case "hagel": return "hail";
            case "nebel": return "fog";
            case "windig": return "windy";
            case "klar": return "clear-night";
            default: return "cloudy";
        }
    }

    private static String zeitKey(String label) {
        switch (label.toLowerCase(Locale.ROOT)) {
            case "morgendämmerung": return "morgen";
            case "tag": return "tag";
            case "abenddämmerung": return "abend";
            case "blaue stunde": return "blau";
            case "nacht": return "nacht";
            case "zeitraffer (24h)": return "zeitraffer";
            default: return "tag";
        }
    }

    boolean wetterPausiert() { return schonerAktiv || bildschirmAus; }

    boolean schonerPausiert() { return bildschirmAus; }

    String szeneOrt() { return szeneOrt; }

    SchonerDaten.Optionen schonerOptionen() { return schonerOpt; }

    SchonerDaten.Zustand schonerZustand() { return schonerZustand; }

    boolean sternbildNamen() { return sternbildNamen; }

    int wetterFps() { return wetterFps; }

    MorgenDaten.NewsStatus newsStatus() { return newsStatus; }

    TimerDaten.Zustand timerZustand() { return timerZustand; }

    // ---- live timers & alarms (render_template subscription) -------------------------------------------------------

    private void timerAbgleichen() {
        PluginHost h = host;
        if (h == null) return;
        Map<String, Object> msg = h.isHeadless() ? null : TimerDaten.abo();
        aboTimer.setzen(h, msg, msg == null ? null : "timer");
    }

    private void timerEreignis(Map<String, Object> p) {
        if (!"event".equals(p.get("type"))) {
            aboTimer.ereignis(p);
            return;
        }
        if (!(p.get("event") instanceof Map)) return;
        Map<?, ?> ev = (Map<?, ?>) p.get("event");
        if (!ev.containsKey("result")) return;
        TimerDaten.Zustand z;
        try {
            z = TimerDaten.lesen(ev.get("result"), Json::lesen, System.currentTimeMillis());
        } catch (RuntimeException e) {
            return;
        }
        timerZustand = z;
        TestAnsicht a;
        synchronized (this) {
            a = aktuell != null && "timer".equals(aktuell.m.ansicht) ? aktuell : null;
        }
        if (a != null) main().post(() -> TestAnsicht.sicher(() -> a.timerFuellen(z)));
    }

    /** Generic HA call_service helper for interactive view actions (Timer Pause/Weiter/Löschen/Stopp). */
    void haDienst(String domain, String service, Map<String, Object> target, Map<String, Object> data) {
        PluginHost h = host;
        if (h == null) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "call_service");
        msg.put("domain", domain);
        msg.put("service", service);
        if (target != null && !target.isEmpty()) msg.put("target", target);
        if (data != null && !data.isEmpty()) msg.put("service_data", data);
        try {
            h.haCall(msg, (ok, resp, err) -> {
                PluginHost hh = host;
                if (!ok && hh != null) hh.log("dienst " + domain + "." + service + ": " + err);
            });
        } catch (RuntimeException ignored) {}
    }

    // ---- "Mein Tag" and live news reading (echo_show/news/subscribe) -----------------------------------------------

    private void newsAbgleichen() {
        PluginHost h = host;
        if (h == null) return;
        boolean braucht = !h.isHeadless() && (meinTagAuto || vorlesenAuto);
        aboNews.setzen(h, braucht ? MorgenDaten.aboAnfrage() : null, braucht ? "news" : null);
    }

    private void newsEreignis(Map<String, Object> p) {
        if (!"event".equals(p.get("type"))) {
            aboNews.ereignis(p);
            return;
        }
        MorgenDaten.NewsStatus alt = newsStatus;
        boolean warAktiv = alt != null && alt.aktiv;
        MorgenDaten.NewsStatus neu = MorgenDaten.lesen(p.get("event"), System.currentTimeMillis());
        newsStatus = neu;

        TestAnsicht offenMorgen;
        synchronized (this) {
            offenMorgen = aktuell != null && "morgen".equals(aktuell.m.ansicht) ? aktuell : null;
        }
        if (offenMorgen != null) {
            main().post(() -> TestAnsicht.sicher(() -> offenMorgen.morgenNewsFuellen(neu)));
        }

        boolean fuerMich = neu.dash.isEmpty() || norm(neu.dash).equals(dashboard);
        if (neu.aktiv && fuerMich && !"aus".equals(neu.ansicht)) {
            boolean sollAuto = ("morgen".equals(neu.art) && meinTagAuto) || (!"morgen".equals(neu.art) && vorlesenAuto);
            if (sollAuto) {
                if (main != null) main.removeCallbacks(morgenAutoZu);
                if (offenMorgen == null && (!warAktiv || neu.seg != null)) {
                    morgenAutoOffen = true;
                    oeffnen("morgen", null, false, Collections.<String, Object>emptyMap(), "HA-Auto");
                }
            }
        } else if (!neu.aktiv && warAktiv && morgenAutoOffen) {
            main().removeCallbacks(morgenAutoZu);
            main().postDelayed(morgenAutoZu, 2500L);
        }
    }

    /** Sends a playback control command (stopp, pause, weiter, zurueck, naechste, mehr) to HA echo_show/news/steuern. */
    void newsSteuern(String aktion) {
        PluginHost h = host;
        if (h == null || aktion == null || aktion.isEmpty()) return;
        try {
            h.haCall(MorgenDaten.steuernAnfrage(aktion), (ok, data, error) -> {
                PluginHost hh = host;
                if (!ok && hh != null) hh.log("news steuern " + aktion + ": " + error);
            });
        } catch (RuntimeException ignored) {}
    }

    /** Downloads an article thumbnail (/api/echo_show/news/bild/<key>) into a crisp 512 px bitmap + 20x12 blurred backdrop. */
    void artikelBildLaden(final String rohUrl) {
        bildLaden(rohUrl, true, (s, w) -> {
            TestAnsicht a;
            synchronized (this) { a = aktuell != null && "morgen".equals(aktuell.m.ansicht) ? aktuell : null; }
            if (a != null) main().post(() -> TestAnsicht.sicher(() -> a.morgenBildSetzen(rohUrl, s, w)));
        });
    }

    /** Downloads a player album cover into a crisp 512 px bitmap + 20x12 blurred backdrop. */
    void playerCoverLaden(final String rohUrl) {
        bildLaden(rohUrl, true, (s, w) -> {
            TestAnsicht a;
            synchronized (this) { a = aktuell != null && "player".equals(aktuell.m.ansicht) ? aktuell : null; }
            if (a != null) main().post(() -> TestAnsicht.sicher(() -> a.playerCoverSetzen(rohUrl, s, w)));
        });
    }

    /** Downloads a guest Wi-Fi QR image fallback (single bitmap). */
    void gaesteBildLaden(final String rohUrl) {
        bildLaden(rohUrl, false, (s, w) -> {
            TestAnsicht a;
            synchronized (this) { a = aktuell != null && "gaeste-wlan".equals(aktuell.m.ansicht) ? aktuell : null; }
            if (a != null) main().post(() -> TestAnsicht.sicher(() -> a.gaesteBildSetzen(rohUrl, s)));
        });
    }

    private void bildLaden(final String rohUrl, final boolean mitWeich, final BildCallback cb) {
        if (rohUrl == null || rohUrl.isEmpty() || cb == null) return;
        final String cacheKey = (mitWeich ? "2:" : "1:") + rohUrl;
        Bitmap[] cached;
        synchronized (bildLock) {
            cached = bildCache.get(cacheKey);
            if (cached != null && cached[0] != null && !cached[0].isRecycled()) {
                cb.fertig(cached[0], cached[1]);
                return;
            }
            if (bildLade.contains(cacheKey)) return;
        }
        String vollUrl;
        if (rohUrl.startsWith("http://") || rohUrl.startsWith("https://")) {
            vollUrl = rohUrl;
        } else if (rohUrl.startsWith("/")) {
            String basis = haBasisUrl;
            if (basis.isEmpty()) {
                synchronized (bildLock) { bildWartend.add(new BildAnfrage(rohUrl, mitWeich, cb)); }
                PluginHost h = host;
                if (h != null) {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("type", "get_config");
                    try {
                        h.haCall(msg, (ok, data, error) -> {
                            if (ok && data instanceof Map) {
                                Map<?, ?> cfg = (Map<?, ?>) data;
                                Object iu = cfg.get("internal_url"), eu = cfg.get("external_url");
                                String b = iu instanceof String && !((String) iu).trim().isEmpty() ? (String) iu
                                    : (eu instanceof String && !((String) eu).trim().isEmpty() ? (String) eu : "");
                                if (!b.isEmpty()) {
                                    haBasisUrl = b.trim().replaceAll("/+$", "");
                                    coverNachladen();
                                }
                            }
                        });
                    } catch (RuntimeException ignored) {}
                }
                return;
            }
            vollUrl = basis + rohUrl;
        } else {
            return;
        }

        synchronized (bildLock) {
            if (bildLade.contains(cacheKey)) return;
            bildLade.add(cacheKey);
        }
        final String target = vollUrl;
        try {
            coverLader().execute(() -> {
                Bitmap[] pair = bildDownload(target, mitWeich);
                synchronized (bildLock) {
                    bildLade.remove(cacheKey);
                    if (pair == null) return;
                    bildCache.put(cacheKey, pair);
                    while (bildCache.size() > 6) {
                        Iterator<Map.Entry<String, Bitmap[]>> it = bildCache.entrySet().iterator();
                        if (!it.hasNext()) break;
                        it.next();
                        it.remove();
                    }
                }
                cb.fertig(pair[0], pair[1]);
            });
        } catch (RuntimeException ignored) {
            synchronized (bildLock) { bildLade.remove(cacheKey); }
        }
    }

    private void wartendeBilderNachladen() {
        List<BildAnfrage> todo;
        synchronized (bildLock) {
            if (bildWartend.isEmpty()) return;
            todo = new LinkedList<>(bildWartend);
            bildWartend.clear();
        }
        for (BildAnfrage b : todo) bildLaden(b.url, b.mitWeich, b.cb);
    }

    private static Bitmap[] bildDownload(String vollUrl, boolean mitWeich) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(vollUrl).openConnection();
            con.setConnectTimeout(4000);
            con.setReadTimeout(5000);
            con.setInstanceFollowRedirects(true);
            if (con.getResponseCode() != 200) return null;
            byte[] bytes;
            try (InputStream in = con.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n, sum = 0;
                while ((n = in.read(buf)) > 0) {
                    sum += n;
                    if (sum > 2_000_000) return null;
                    out.write(buf, 0, n);
                }
                bytes = out.toByteArray();
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            int sample = 1, maxDim = Math.max(opts.outWidth, opts.outHeight);
            while (maxDim / (sample * 2) >= 512) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            Bitmap scharf = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (scharf == null) return null;
            // Tiny 20x12 copy: GPU bilinear upscaling produces a silky zero-CPU-cost Gaussian blur backdrop
            Bitmap weich = mitWeich ? Bitmap.createScaledBitmap(scharf, 20, 12, true) : null;
            return new Bitmap[] {scharf, weich};
        } catch (Exception ignored) {
            return null;
        } finally {
            if (con != null) con.disconnect();
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
            if (m.datenFehler != null) d.put("fehler", m.datenFehler);
        }
        if ("kalender".equals(m.ansicht)) d.put("termine", m.termine == null ? 0 : m.termine.size());
        if ("wetter".equals(m.ansicht) || "schoner".equals(m.ansicht)) {
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
        musikAboAbgleichen();
        if (!m.geschlossenGemeldet) {
            m.geschlossenGemeldet = true;
            meldenStatus(m, "geschlossen", geschlossenDaten(m));
        }
        status();
    }

    /** Main thread (button) or worker (command). */
    void schliessen() {
        PluginHost h = host;
        TestAnsicht a;
        synchronized (this) {
            a = aktuell;
            aktuell = null;
        }
        musikAboAbgleichen();
        if (a != null && !a.m.geschlossenGemeldet) {
            a.m.geschlossenGemeldet = true;
            meldenStatus(a.m, "geschlossen", geschlossenDaten(a.m));
        }
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
        if (m.token == null) return;
        meldenStatus(m, art, daten);
    }

    /** echo_show/ansicht/melden for lifecycle reports ("offen" / "geschlossen" / token replies). Any thread. */
    private void meldenStatus(Messung m, String art, Map<String, Object> daten) {
        PluginHost h = host;
        if (h == null) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/ansicht/melden");
        msg.put("token", m.token == null ? "" : m.token);
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

    private Map<String, Object> offenDaten(Messung m) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("dashboard", dashboard);
        d.put("seite", m.ansicht);
        d.put("nativ", true);
        d.put("param", m.param != null ? m.param : Collections.<String, Object>emptyMap());
        return d;
    }

    private Map<String, Object> geschlossenDaten(Messung m) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("dashboard", dashboard);
        d.put("seite", m.ansicht);
        return d;
    }

    private static Map<String, Object> wsMsg(String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
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

    /** Canonical native view name (with test- prefix and aliases), or null. */
    static String ansicht(String roh) {
        if (roh == null) return null;
        String n = roh.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("test-")) n = n.substring(5);
        if ("gaestewlan".equals(n)) return "gaeste-wlan";
        if ("musik".equals(n)) return "player";
        if ("waschmaschine-verlauf".equals(n)) return "waschmaschine";
        if ("server-dienste".equals(n)) return "server";
        for (String a : ANSICHTEN) if (a.equals(n)) return a;
        return null;
    }

    private static void aliasParam(String roh, Map<String, Object> p) {
        if (roh == null || p == null || p.containsKey("tab")) return;
        String n = roh.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("test-")) n = n.substring(5);
        if ("waschmaschine-verlauf".equals(n)) p.put("tab", "verlauf");
        else if ("server-dienste".equals(n)) p.put("tab", "dienste");
    }

    /** Link arguments: e.g. timer/90, kalender/wochenende, einkaufsliste/todo.id, waschmaschine/verlauf, server/dienste. */
    static Map<String, Object> linkParam(String ansicht, List<String> rest) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (rest == null || rest.isEmpty()) return p;
        String a = rest.get(0) == null ? "" : rest.get(0).trim();
        String b = rest.size() > 1 && rest.get(1) != null ? rest.get(1).trim() : "";
        if ("timer".equals(ansicht) && a.matches("\\d{1,5}")) {
            p.put("sekunden", Integer.parseInt(a));
        } else if ("kalender".equals(ansicht)) {
            if ("wochenende".equals(a) || "woche".equals(a) || "tag".equals(a) || "3tage".equals(a) || "monat".equals(a) || "liste".equals(a)) p.put("zeitraum", a);
            else if (Kalender.TAG.matcher(a).matches()) p.put("tag", a);
            else if (a.contains(",") && a.matches("\\d{4}-\\d{2}-\\d{2}(,\\d{4}-\\d{2}-\\d{2})+")) p.put("tage", a);
            if (!b.isEmpty()) {
                if (Kalender.TAG.matcher(b).matches()) p.put("tag", b);
                else if (b.matches("\\d{4}-\\d{2}-\\d{2}(,\\d{4}-\\d{2}-\\d{2})*")) p.put("tage", b);
            }
        } else if ("wetter".equals(ansicht) || "schoner".equals(ansicht)) {
            if ("zeitraffer".equals(a)) p.put("modus", "zeitraffer");
            else if (a.matches("([01]?\\d|2[0-3]):[0-5]\\d")) p.put("uhr", a);
            else if ("heute".equals(a) || "woche".equals(a)) p.put("ansicht", a);
            else if (a.matches("\\d{4}-\\d{2}-\\d{2}(,\\d{4}-\\d{2}-\\d{2})*")) {
                p.put("ansicht", "woche");
                p.put("tage", a);
            }
            if (!b.isEmpty() && b.matches("\\d{4}-\\d{2}-\\d{2}(,\\d{4}-\\d{2}-\\d{2})*")) p.put("tage", b);
        } else if ("morgen".equals(ansicht) && ("demo".equals(a) || "story".equals(a) || "mehr".equals(a))) {
            p.put("modus", a);
        } else if ("einkaufsliste".equals(ansicht) && !a.isEmpty()) {
            p.put("liste", a);
        } else if ("waschmaschine".equals(ansicht) && ("verlauf".equalsIgnoreCase(a) || "jetzt".equalsIgnoreCase(a))) {
            p.put("tab", a.toLowerCase(Locale.ROOT));
        } else if ("server".equals(ansicht) && ("dienste".equalsIgnoreCase(a) || "uebersicht".equalsIgnoreCase(a))) {
            p.put("tab", a.toLowerCase(Locale.ROOT));
        }
        return p;
    }

    private static int parseInt(Object o, int standard, int min, int max) {
        int v = standard;
        if (o instanceof Number) v = ((Number) o).intValue();
        else if (o instanceof String) {
            try { v = Integer.parseInt(((String) o).trim()); } catch (NumberFormatException ignored) {}
        }
        return Math.max(min, Math.min(max, v));
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
