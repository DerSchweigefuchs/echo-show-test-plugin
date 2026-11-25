package echoshow.kiosktest;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

/**
 * The full-screen test view: a header (title, measured times, frame counter, close button) and one of three bodies.
 * Everything here runs on the Android main thread. Listeners and posted runnables run outside KS's guards, so each
 * one catches its own errors.
 */
final class TestAnsicht implements OverlayFactory {
    private final EchoShowTestPlugin plugin;
    final Messung m;
    /** The live view, main thread only. KS may call create again (Activity restart), then it is replaced. */
    private Ui aktiv;

    TestAnsicht(EchoShowTestPlugin plugin, Messung m) {
        this.plugin = plugin;
        this.m = m;
    }

    @Override public View create(Context context, KsTheme theme) {
        long jetzt = SystemClock.uptimeMillis();
        if (m.tErstellt == 0) m.tErstellt = jetzt;
        if (m.timerEnde == 0) m.timerEnde = jetzt + m.timerSekunden * 1000L;
        Ui ui = new Ui(context, theme);
        aktiv = ui;
        ui.root.setTag(ui);
        return ui.root;
    }

    @Override public void onDestroy(View view) {
        Object tag = view.getTag();
        if (tag instanceof Ui) {
            Ui ui = (Ui) tag;
            ui.zerstoeren();
            if (aktiv == ui) aktiv = null;
        }
    }

    /** Main thread: the calendar answer, or new presence data (people or history), arrived (posted by the plugin). */
    void datenDa() {
        if (aktiv == null) return;
        if (aktiv.anwView != null) aktiv.anwFuellen();
        else aktiv.kalenderFuellen();
    }

    /** Main thread: the RAM reading after opening arrived. */
    void anzeigeAktualisieren() {
        if (aktiv != null) aktiv.zeitText();
    }

    static void sicher(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // The session can end between a frame and this call. An exception here would end the whole app.
        }
    }

    static void zweiFrames(Runnable r) {
        Choreographer.getInstance().postFrameCallback(a -> Choreographer.getInstance().postFrameCallback(b -> sicher(r)));
    }

    private final class Ui {
        final Context ctx;
        final KsTheme th;
        final LinearLayout root;
        TextView zeitLabel, framesLabel;
        LinearLayout kalenderListe;
        AnwesenheitView anwView;
        String messZeit = "", messFrames = "";
        ScrollView scroll;
        ObjectAnimator autoScroll;
        final FrameZaehler zaehler = new FrameZaehler();
        final Handler handler = new Handler(Looper.getMainLooper());
        boolean ersterFrame, sichtbar, gefuellt, zerstoert;
        ViewTreeObserver.OnDrawListener draw;
        final Runnable sekunde = new Runnable() {
            @Override public void run() {
                sicher(() -> framesText());
                if (!zerstoert) handler.postDelayed(this, 1000);
            }
        };

        Ui(Context ctx, KsTheme th) {
            this.ctx = ctx;
            this.th = th;
            root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(th.color("surface"));
            root.setClickable(true);   // touches stay in the test view
            // the presence page brings its own header (back button, title, switch), like the dashboard page it copies
            if (!"anwesenheit".equals(m.ansicht)) root.addView(kopf(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout inhalt = new FrameLayout(ctx);
            root.addView(inhalt, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            if ("anwesenheit".equals(m.ansicht)) anwesenheit(inhalt);
            else if ("timer".equals(m.ansicht)) timer(inhalt);
            else if ("liste".equals(m.ansicht)) liste(inhalt);
            else kalender(inhalt);
            zeitText();
            // A draw listener added before the view is attached sits on a temporary ViewTreeObserver and did not fire on
            // the Echo Show (no "gezeigt"/"bereit" ever sent). So it is added once the view is attached to the overlay
            // window, plus a fallback: the next animation frame after attaching counts as the first frame.
            draw = this::ersterFrame;
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { sicher(() -> angehaengt()); }
                @Override public void onViewDetachedFromWindow(View v) {}
            });
            if (root.isAttachedToWindow()) angehaengt();
            zaehler.start();
            handler.postDelayed(sekunde, 1000);
        }

        private void angehaengt() {
            try { root.getViewTreeObserver().addOnDrawListener(draw); } catch (RuntimeException ignored) {}
            root.postOnAnimation(() -> sicher(this::ersterFrame));
        }

        private void ersterFrame() {
            if (ersterFrame || zerstoert) return;
            ersterFrame = true;
            if (m.tErsterFrame == 0) m.tErsterFrame = SystemClock.uptimeMillis();
            root.post(() -> sicher(() -> root.getViewTreeObserver().removeOnDrawListener(draw)));
            zweiFrames(this::istSichtbar);
        }

        private void istSichtbar() {
            if (zerstoert) return;
            sichtbar = true;
            zaehler.reset();   // jank counts from here on, not the opening itself
            if (m.tSichtbar == 0) m.tSichtbar = SystemClock.uptimeMillis();
            plugin.sichtbar(TestAnsicht.this);
            if (!m.brauchtDaten() || gefuellt) plugin.inhaltFertig(TestAnsicht.this);
            zeitText();
        }

        // ---- header -------------------------------------------------------------------------------------------

        private View kopf() {
            LinearLayout kopf = new LinearLayout(ctx);
            kopf.setOrientation(LinearLayout.HORIZONTAL);
            kopf.setGravity(Gravity.CENTER_VERTICAL);
            kopf.setPadding(th.px(24), th.px(10), th.px(12), th.px(10));
            kopf.setBackgroundColor(th.color("surfaceContainerHigh"));
            TextView titel = new TextView(ctx);
            titel.setText(titel());
            th.styleText(titel, 22, 600, "onSurface");
            titel.setSingleLine(true);
            kopf.addView(titel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout mess = new LinearLayout(ctx);
            mess.setOrientation(LinearLayout.VERTICAL);
            mess.setGravity(Gravity.END);
            mess.setPadding(th.px(16), 0, th.px(12), 0);
            zeitLabel = new TextView(ctx);
            th.styleText(zeitLabel, 15, 500, "onSurface");
            zeitLabel.setGravity(Gravity.END);
            zeitLabel.setSingleLine(true);
            zeitLabel.setEllipsize(TextUtils.TruncateAt.END);
            framesLabel = new TextView(ctx);
            th.styleText(framesLabel, 13, 400, "onSurfaceVariant");
            framesLabel.setGravity(Gravity.END);
            framesLabel.setSingleLine(true);
            framesLabel.setText("Frames: –");
            mess.addView(zeitLabel);
            mess.addView(framesLabel);
            kopf.addView(mess, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if ("liste".equals(m.ansicht)) {
                Button auto = new Button(ctx);
                auto.setText("Auto-Scroll");
                th.stylePill(auto, false);
                auto.setOnClickListener(v -> sicher(this::autoScroll));
                kopf.addView(auto);
            }
            Button zu = new Button(ctx);
            zu.setText("Schließen");
            th.stylePill(zu, true);
            zu.setOnClickListener(v -> sicher(plugin::schliessen));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = th.px(8);
            kopf.addView(zu, lp);
            return kopf;
        }

        private String titel() {
            switch (m.ansicht) {
                case "timer": return "Timer (nativ)";
                case "liste": return "Liste 50 (nativ)";
                case "anwesenheit": return "Anwesenheit (nativ)";
                default: return "Kalender (nativ)";
            }
        }

        void zeitText() {
            if (zeitLabel == null && anwView == null) return;
            StringBuilder sb = new StringBuilder();
            if (m.tInhalt > 0) {
                sb.append("offen in ").append(m.ms(m.tInhalt)).append(" ms");
                if (m.brauchtDaten() && m.tDaten > 0) sb.append(" · Daten ").append(m.ms(m.tDaten));
                if (m.tErsterFrame > 0) sb.append(" · View ").append(m.tErsterFrame - m.tErstellt);
            } else if (m.tSichtbar > 0) {
                sb.append("sichtbar nach ").append(m.ms(m.tSichtbar)).append(" ms · lädt Daten …");
            } else {
                sb.append("öffnet …");
            }
            if (m.ramVorherMb != null && m.ramNachherMb != null) {
                sb.append(" · RAM frei ").append(Math.round(m.ramVorherMb)).append("→").append(Math.round(m.ramNachherMb)).append(" MB");
            }
            sb.append(" · ").append(m.quelle);
            messZeit = sb.toString();
            if (zeitLabel != null) zeitLabel.setText(messZeit);
            if (anwView != null) anwView.mess("nativ · " + messZeit, messFrames);
        }

        private void framesText() {
            if (!sichtbar) return;
            messFrames = String.format(Locale.ROOT, "%d fps · Ruckler %d von %d Frames · max %d ms",
                zaehler.fps, zaehler.ruckler, zaehler.frames, zaehler.maxNs / 1_000_000L);
            if (framesLabel != null) framesLabel.setText(messFrames);
            if (anwView != null) anwView.mess("nativ · " + messZeit, messFrames);
        }

        // ---- (d) presence -------------------------------------------------------------------------------------

        private void anwesenheit(FrameLayout inhalt) {
            anwView = new AnwesenheitView(ctx, new AnwesenheitView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public void minute() { plugin.anwesenheitMinute(TestAnsicht.this); }
            });
            inhalt.addView(anwView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            anwFuellen();
        }

        /** People and / or history changed. The first complete history counts as "content ready". */
        void anwFuellen() {
            if (zerstoert || anwView == null) return;
            anwView.fehler = m.verlauf == null ? m.datenFehler : null;
            anwView.setDaten(m.personen, m.verlauf);
            if (gefuellt || (m.verlauf == null && m.datenFehler == null)) return;
            gefuellt = true;
            if (sichtbar) zweiFrames(() -> { if (!zerstoert) { plugin.inhaltFertig(TestAnsicht.this); zeitText(); } });
        }

        // ---- (a) calendar -------------------------------------------------------------------------------------

        private void kalender(FrameLayout inhalt) {
            scroll = new ScrollView(ctx);
            scroll.setFillViewport(true);
            kalenderListe = new LinearLayout(ctx);
            kalenderListe.setOrientation(LinearLayout.VERTICAL);
            kalenderListe.setPadding(th.px(24), th.px(16), th.px(24), th.px(24));
            scroll.addView(kalenderListe);
            inhalt.addView(scroll);
            if (m.termine != null || m.datenFehler != null) kalenderFuellen();
            else {
                kalenderListe.addView(kopfzeile());
                kalenderListe.addView(hinweis("Lade Termine …"));
            }
        }

        private TextView kopfzeile() {
            TextView t = new TextView(ctx);
            Object tag = m.param == null ? null : m.param.get("tag");
            t.setText(new SimpleDateFormat("EEEE, d. MMMM yyyy", Locale.GERMAN).format(Kalender.tag(tag instanceof String ? (String) tag : null).getTime()));
            th.styleText(t, 20, 500, "onSurfaceVariant");
            t.setPadding(0, 0, 0, th.px(12));
            return t;
        }

        private TextView hinweis(String text) {
            TextView t = new TextView(ctx);
            t.setText(text);
            th.styleText(t, 18, 400, "onSurfaceVariant");
            t.setPadding(0, th.px(24), 0, 0);
            return t;
        }

        void kalenderFuellen() {
            if (zerstoert || kalenderListe == null || gefuellt) return;
            gefuellt = true;
            kalenderListe.removeAllViews();
            kalenderListe.addView(kopfzeile());
            List<Kalender.Termin> termine = m.termine;
            if (m.datenFehler != null) {
                kalenderListe.addView(hinweis("Fehler: " + m.datenFehler));
            } else if (termine == null || termine.isEmpty()) {
                kalenderListe.addView(hinweis("Keine Termine an diesem Tag."));
            } else {
                for (Kalender.Termin t : termine) kalenderListe.addView(termin(t));
            }
            if (sichtbar) zweiFrames(() -> { if (!zerstoert) { plugin.inhaltFertig(TestAnsicht.this); zeitText(); } });
        }

        private View termin(Kalender.Termin t) {
            LinearLayout zeile = new LinearLayout(ctx);
            zeile.setOrientation(LinearLayout.HORIZONTAL);
            zeile.setGravity(Gravity.CENTER_VERTICAL);
            zeile.setPadding(th.px(16), th.px(12), th.px(16), th.px(12));
            zeile.setBackground(karte());
            TextView zeit = new TextView(ctx);
            zeit.setText(t.zeit);
            th.styleText(zeit, 17, 600, "onSurface");
            zeile.addView(zeit, new LinearLayout.LayoutParams(th.px(120), ViewGroup.LayoutParams.WRAP_CONTENT));
            View balken = new View(ctx);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(t.farbe);
            bg.setCornerRadius(th.px(3));
            balken.setBackground(bg);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(th.px(5), th.px(44));
            blp.rightMargin = th.px(16);
            zeile.addView(balken, blp);
            LinearLayout text = new LinearLayout(ctx);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView titel = new TextView(ctx);
            titel.setText(t.titel);
            th.styleText(titel, 18, 500, "onSurface");
            titel.setSingleLine(true);
            titel.setEllipsize(TextUtils.TruncateAt.END);
            TextView unter = new TextView(ctx);
            unter.setText(t.ort.isEmpty() ? t.kalender : t.ort + " · " + t.kalender);
            th.styleText(unter, 14, 400, "onSurfaceVariant");
            unter.setSingleLine(true);
            unter.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(titel);
            text.addView(unter);
            zeile.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            zeile.setLayoutParams(abstand());
            return zeile;
        }

        private GradientDrawable karte() {
            GradientDrawable d = new GradientDrawable();
            d.setColor(th.color("surfaceContainerHigh"));
            d.setCornerRadius(th.px(th.radiusRow()));
            return d;
        }

        private LinearLayout.LayoutParams abstand() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = th.px(8);
            return lp;
        }

        // ---- (b) timer ----------------------------------------------------------------------------------------

        private void timer(FrameLayout inhalt) {
            TimerRing ring = new TimerRing(ctx, m, th.typeface(500), th.color("onSurface"), th.color("onSurfaceVariant"));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(th.px(380), th.px(380), Gravity.CENTER);
            inhalt.addView(ring, lp);
        }

        // ---- (c) list of 50 rows ------------------------------------------------------------------------------

        private final String[] icons = {"mdi:lightbulb", "mdi:thermometer", "mdi:fan", "mdi:door", "mdi:television", "mdi:speaker", "mdi:window-closed", "mdi:power-plug"};
        private final String[] raeume = {"Wohnzimmer", "Küche", "Bad", "Flur", "Büro", "Schlafzimmer", "Keller"};

        private void liste(FrameLayout inhalt) {
            scroll = new ScrollView(ctx);
            LinearLayout spalte = new LinearLayout(ctx);
            spalte.setOrientation(LinearLayout.VERTICAL);
            spalte.setPadding(th.px(24), th.px(16), th.px(24), th.px(24));
            for (int i = 1; i <= 50; i++) spalte.addView(zeile(i));
            scroll.addView(spalte);
            inhalt.addView(scroll);
        }

        private View zeile(int i) {
            LinearLayout z = new LinearLayout(ctx);
            z.setOrientation(LinearLayout.HORIZONTAL);
            z.setGravity(Gravity.CENTER_VERTICAL);
            z.setPadding(th.px(16), th.px(10), th.px(16), th.px(10));
            z.setBackground(karte());
            ImageView icon = new ImageView(ctx);
            Drawable d = null;
            try {
                d = th.icon(icons[i % icons.length], "primary");
            } catch (LinkageError olderHost) {
                // KS without KsTheme.icon: row without icon
            }
            if (d != null) icon.setImageDrawable(d);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(th.px(28), th.px(28));
            ilp.rightMargin = th.px(16);
            z.addView(icon, ilp);
            LinearLayout text = new LinearLayout(ctx);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView titel = new TextView(ctx);
            titel.setText("Eintrag " + i);
            th.styleText(titel, 17, 500, "onSurface");
            TextView unter = new TextView(ctx);
            unter.setText(raeume[i % raeume.length] + " · Testzeile " + i + " von 50");
            th.styleText(unter, 13, 400, "onSurfaceVariant");
            text.addView(titel);
            text.addView(unter);
            z.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView wert = new TextView(ctx);
            wert.setText(String.format(Locale.GERMAN, "%.1f °C", 18 + (i * 7 % 50) / 10.0));
            th.styleText(wert, 17, 600, "onSurface");
            z.addView(wert);
            z.setLayoutParams(abstand());
            return z;
        }

        /** Scrolls to the end and back in 3 s each, so smoothness can be compared run by run. */
        private void autoScroll() {
            if (scroll == null || scroll.getChildCount() == 0) return;
            if (autoScroll != null) autoScroll.cancel();
            int max = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
            zaehler.reset();
            autoScroll = ObjectAnimator.ofInt(scroll, "scrollY", 0, max);
            autoScroll.setDuration(3000);
            autoScroll.setInterpolator(new AccelerateDecelerateInterpolator());
            autoScroll.setRepeatCount(1);
            autoScroll.setRepeatMode(ValueAnimator.REVERSE);
            autoScroll.addListener(new AnimatorListenerAdapter() {
                private boolean abgebrochen;
                @Override public void onAnimationCancel(Animator a) { abgebrochen = true; }
                @Override public void onAnimationEnd(Animator a) {
                    if (abgebrochen || zerstoert) return;
                    sicher(() -> plugin.scrollFertig(TestAnsicht.this, zaehler.frames, zaehler.ruckler, zaehler.maxNs / 1_000_000L));
                }
            });
            autoScroll.start();
        }

        void zerstoeren() {
            if (zerstoert) return;
            zerstoert = true;
            zaehler.stop();
            handler.removeCallbacksAndMessages(null);
            if (autoScroll != null) autoScroll.cancel();
            try { root.getViewTreeObserver().removeOnDrawListener(draw); } catch (RuntimeException ignored) {}
            if (sichtbar) {
                m.frames = zaehler.frames;
                m.ruckler = zaehler.ruckler;
                m.maxFrameMs = zaehler.maxNs / 1_000_000L;
            }
            sicher(() -> plugin.beendet(TestAnsicht.this));
        }
    }
}
