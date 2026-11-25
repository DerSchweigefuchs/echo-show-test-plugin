package echoshow.kiosktest;

import android.view.Choreographer;

/**
 * Counts main-thread frames while a test view is open: frames per second, frames slower than 25 ms ("Ruckler",
 * 1.5 vsyncs at 60 Hz) and the longest frame gap. Runs on the main thread only. It keeps a frame callback posted while
 * running, which wakes the main thread on every vsync; that small cost is the same for every test view.
 */
final class FrameZaehler implements Choreographer.FrameCallback {
    static final long RUCKLER_NS = 25_000_000L;

    private boolean laeuft;
    private long letzter;
    // since reset()
    int frames, ruckler;
    long maxNs;
    // current second
    private int framesSek;
    private long sekStart;
    int fps;

    void start() {
        if (laeuft) return;
        laeuft = true;
        letzter = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        laeuft = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    /** Start counting afresh, e.g. once the view is visible, so the opening itself is not counted as jank. */
    void reset() {
        frames = 0;
        ruckler = 0;
        maxNs = 0;
    }

    @Override public void doFrame(long nanos) {
        if (!laeuft) return;
        try {
            if (letzter != 0) {
                long d = nanos - letzter;
                frames++;
                if (d > RUCKLER_NS) ruckler++;
                if (d > maxNs) maxNs = d;
            }
            letzter = nanos;
            if (sekStart == 0) sekStart = nanos;
            framesSek++;
            if (nanos - sekStart >= 1_000_000_000L) {
                fps = Math.round(framesSek * 1e9f / (nanos - sekStart));
                framesSek = 0;
                sekStart = nanos;
            }
        } catch (RuntimeException ignored) {
            // never let a main-thread callback take the app down
        }
        if (laeuft) Choreographer.getInstance().postFrameCallback(this);
    }
}
