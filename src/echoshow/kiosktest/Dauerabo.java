package echoshow.kiosktest;

import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * One Home Assistant subscription that stays up: when HA refuses it (for example right after an HA restart, before the
 * integration that offers the command is loaded) or KS reports it "ended", it subscribes again after 5 s, 10 s, 30 s
 * and then every 60 s until HA accepts it. All methods are synchronized; the host calls return at once.
 */
final class Dauerabo {
    static final long[] WARTEN_MS = {5_000, 10_000, 30_000, 60_000};

    interface Zeitgeber {
        /** Run r after ms on some thread; returns a handle for abbrechen. */
        Object spaeter(Runnable r, long ms);
        void abbrechen(Object handle);
    }

    interface Melder {
        void zustand(Dauerabo abo, String text, boolean fehler);
    }

    final String key;
    private final Zeitgeber zeit;
    private final Melder melder;
    private PluginHost host;
    private Map<String, Object> msg;     // what should be subscribed, null = nothing
    private String kennung;              // identity of msg, to notice changes
    private int versuche;                // failed attempts in a row
    private int generation;              // increases with every (re)subscribe, so stale callbacks are ignored
    private Object wiederholung;         // pending retry
    private boolean aktiv;               // HA accepted the current subscription

    Dauerabo(String key, Zeitgeber zeit, Melder melder) {
        this.key = key;
        this.zeit = zeit;
        this.melder = melder;
    }

    /** Subscribe msg (or end the subscription with null). Unchanged messages keep the running subscription. */
    synchronized void setzen(PluginHost host, Map<String, Object> msg, String kennung) {
        this.host = host;
        boolean gleich = kennung == null ? this.kennung == null : kennung.equals(this.kennung);
        if (gleich) return;   // running, waiting for HA's answer or retrying: the retries take care of it
        beenden();
        this.msg = msg;
        this.kennung = kennung;
        versuche = 0;
        if (msg != null) anmelden();
    }

    synchronized boolean aktiv() { return aktiv; }

    synchronized int versuche() { return versuche; }

    /** KS events for this key: "ended" starts the retries; "resubscribed" means KS itself restored it. */
    synchronized void ereignis(Map<String, Object> payload) {
        Object typ = payload.get("type");
        if ("ended".equals(typ)) {
            aktiv = false;
            Object e = payload.get("error");
            fehlgeschlagen("beendet" + (e instanceof Map ? ": " + ((Map<?, ?>) e).get("message") : ""));
        } else if ("resubscribed".equals(typ)) {
            aktiv = true;
            versuche = 0;
            melder.zustand(this, "aktiv (neu verbunden)", false);
        } else if ("disconnected".equals(typ)) {
            melder.zustand(this, "Verbindung getrennt, KS verbindet neu", false);
        }
    }

    /** The plugin stops: no more retries, KS ends the subscription itself. */
    synchronized void stop() {
        if (wiederholung != null) zeit.abbrechen(wiederholung);
        wiederholung = null;
        host = null;
        msg = null;
        kennung = null;
        aktiv = false;
        generation++;
    }

    private void beenden() {
        if (wiederholung != null) zeit.abbrechen(wiederholung);
        wiederholung = null;
        generation++;
        if (aktiv || msg != null) {
            try {
                if (host != null) host.haUnsubscribe(key);
            } catch (RuntimeException ignored) {
                // session ended
            }
        }
        aktiv = false;
    }

    private void anmelden() {
        PluginHost h = host;
        if (h == null || msg == null) return;
        final int gen = ++generation;
        try {
            h.haUnsubscribe(key);   // a key KS still holds would refuse the new subscription
        } catch (RuntimeException ignored) {
            // unknown keys are ignored anyway
        }
        try {
            h.haSubscribe(key, msg, (ok, data, error) -> antwort(gen, ok, error));
        } catch (RuntimeException e) {
            fehlgeschlagen(e.getMessage());
        }
    }

    private synchronized void antwort(int gen, boolean ok, String error) {
        if (gen != generation) return;
        if (ok) {
            aktiv = true;
            versuche = 0;
            melder.zustand(this, "aktiv", false);
        } else {
            aktiv = false;
            fehlgeschlagen(error);
        }
    }

    private void fehlgeschlagen(String grund) {
        if (msg == null || host == null) return;
        if (wiederholung != null) zeit.abbrechen(wiederholung);
        long ms = WARTEN_MS[Math.min(versuche, WARTEN_MS.length - 1)];
        versuche++;
        final int gen = ++generation;
        wiederholung = zeit.spaeter(() -> {
            synchronized (Dauerabo.this) {
                if (gen != generation) return;
                wiederholung = null;
                anmelden();
            }
        }, ms);
        melder.zustand(this, (grund == null ? "abgelehnt" : grund) + " – neuer Versuch in " + (ms / 1000) + " s", true);
    }
}
