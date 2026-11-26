package echoshow.kiosktest;

import java.util.List;
import java.util.Map;

/**
 * One opening of a test view and its timings. Times are SystemClock.uptimeMillis(), 0 = not reached yet.
 * Written from the plugin worker and the main thread, so every field is volatile.
 */
final class Messung {
    final String ansicht;      // kalender | timer | liste | anwesenheit | wetter
    final String token;        // echo_show/ansicht token, null when opened by a command
    final String quelle;       // "HA" or "Befehl"
    final boolean kalt;
    final Map<String, Object> param;
    final long t0;             // command / event received by the plugin
    final int timerSekunden;
    final boolean timerFluessig;

    volatile long tErstellt;   // OverlayFactory.create ran
    volatile long tErsterFrame;// first draw of the view
    volatile long tSichtbar;   // two frames after the first draw (like requestAnimationFrame x2 in ANSICHT_JS)
    volatile long tDaten;      // calendar answer arrived (kalender only)
    volatile long tInhalt;     // content complete and drawn
    volatile long timerEnde;   // uptime at which the countdown reaches 0, set on first create

    volatile List<Kalender.Termin> termine;
    volatile Kalender.Spanne kalenderSpanne;
    volatile String datenFehler;

    // anwesenheit: people (render_template) and 7-day history; null while loading
    volatile List<Anwesenheit.Person> personen;
    volatile Map<String, List<Anwesenheit.Eintrag>> verlauf;
    volatile boolean personenAusCache, verlaufAusCache, verlaufReduziert, verlaufLaeuft;

    // wetter: weather, sun and home from the live render_template subscription; build time of the cached layers
    volatile WetterLage.Daten wetter;
    volatile boolean wetterAusCache;
    volatile long aufbauMs = -1;
    volatile java.util.Map<String, Object> szene, szene5, szeneEnde;   // the scene's own figures at "bereit" and when it closes

    volatile Double ramVorherMb, ramNachherMb;
    volatile long heapMb = -1, nativeHeapMb = -1;

    volatile int frames, ruckler;
    volatile long maxFrameMs;

    volatile boolean gezeigtGemeldet, bereitGemeldet, beendet;
    volatile String verlaufZeile;   // this opening's line in the plugin status, replaced when the RAM reading arrives

    Messung(String ansicht, String token, String quelle, boolean kalt, Map<String, Object> param, long t0,
            int timerSekunden, boolean timerFluessig) {
        this.ansicht = ansicht;
        this.token = token;
        this.quelle = quelle;
        this.kalt = kalt;
        this.param = param;
        this.t0 = t0;
        this.timerSekunden = timerSekunden;
        this.timerFluessig = timerFluessig;
    }

    long ms(long t) { return t == 0 ? -1 : t - t0; }

    boolean brauchtDaten() { return "kalender".equals(ansicht) || "anwesenheit".equals(ansicht) || "wetter".equals(ansicht) || "schoner".equals(ansicht); }
}
