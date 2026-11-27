package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

/**
 * The full-screen native view host.
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

    /** Main thread: the view's HA data arrived (posted by the plugin). */
    void datenDa() {
        if (aktiv == null) return;
        if (aktiv.anwView != null) aktiv.anwFuellen();
        else if (aktiv.morgenView != null) aktiv.morgenWetterFuellen();
        else if (aktiv.wetterView != null) aktiv.wetterFuellen();
        else if (aktiv.musikView != null) aktiv.musikFuellen();
        else if (aktiv.waschView != null) aktiv.waschFuellen();
        else if (aktiv.gaesteView != null) aktiv.gaesteFuellen();
        else if (aktiv.einkaufView != null) aktiv.einkaufFuellen();
        else if (aktiv.muellView != null) aktiv.muellFuellen();
        else if (aktiv.serverView != null) aktiv.serverFuellen();
        else aktiv.kalenderFuellen();
    }

    /** Main thread: downloaded album cover + blurred backdrop arrived for MusikPlayerView. */
    void playerCoverSetzen(String url, Bitmap scharf, Bitmap weich) {
        if (aktiv != null && aktiv.musikView != null) aktiv.musikView.setCover(url, scharf, weich);
    }

    /** Main thread: downloaded router QR fallback image arrived for GaesteWlanView. */
    void gaesteBildSetzen(String url, Bitmap bmp) {
        if (aktiv != null && aktiv.gaesteView != null) aktiv.gaesteView.setBild(url, bmp);
    }

    /** Main thread: news / "Mein Tag" reading state updated. */
    void morgenNewsFuellen(MorgenDaten.NewsStatus st) {
        if (aktiv != null && aktiv.morgenView != null) aktiv.morgenView.setNews(st);
    }

    /** Main thread: live timers & alarms updated. */
    void timerFuellen(TimerDaten.Zustand z) {
        if (aktiv != null && aktiv.timerView != null) aktiv.timerView.setZustand(z);
    }

    /** Main thread: downloaded article thumbnail + blurred backdrop arrived. */
    void morgenBildSetzen(String url, Bitmap scharf, Bitmap weich) {
        if (aktiv != null && aktiv.morgenView != null) aktiv.morgenView.setArtikelBild(url, scharf, weich);
    }

    /** Main thread: screensaver hints / music updated. */
    void schonerFuellen(SchonerDaten.Zustand z) {
        if (aktiv != null && aktiv.wetterView != null) aktiv.wetterView.setSchoner(z);
    }

    /** Main thread: screensaver settings updated. */
    void schonerOptionen(SchonerDaten.Optionen opt) {
        if (aktiv != null && aktiv.wetterView != null) aktiv.wetterView.setSchonerOptionen(opt);
    }

    /** Main thread: landscape setting updated. */
    void szeneOrtSetzen(String ort) {
        if (aktiv != null && aktiv.wetterView != null) aktiv.wetterView.setOrt(ort);
        if (aktiv != null && aktiv.morgenView != null) aktiv.morgenView.setOrt(ort);
    }

    /** Main thread: HA panel config (wetter_name, szene flags) updated. */
    void konfigAnwenden() {
        if (aktiv == null) return;
        if (aktiv.wetterView != null) plugin.konfigAnwenden(aktiv.wetterView);
        if (aktiv.morgenView != null) plugin.konfigAnwenden(aktiv.morgenView.wetterView());
        if (aktiv.einkaufView != null && plugin.einkaufsKonfig() != null) {
            m.ansichtDaten = plugin.einkaufsKonfig();
            aktiv.einkaufFuellen();
        }
    }

    /** Main thread: settings-driven weather/time/landscape preview toggled or changed. */
    void vorschauSetzen(boolean aktivV, String ort, String cond, String zeit) {
        if (aktiv != null && aktiv.wetterView != null) {
            aktiv.wetterView.setOrt(ort);
            aktiv.wetterView.setEinstellungsVorschau(aktivV, ort, cond, zeit);
        }
        if (aktiv != null && aktiv.morgenView != null) {
            aktiv.morgenView.setOrt(ort);
            aktiv.morgenView.setEinstellungsVorschau(aktivV, ort, cond, zeit);
        }
    }

    /** Main thread: screensaver or screen changed (the weather scene pauses). */
    void pauseGeaendert() {
        if (aktiv != null) aktiv.wetterPause();
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
        AnwesenheitView anwView;
        WetterView wetterView;
        MorgenView morgenView;
        KalenderView kalenderView;
        TimerView timerView;
        MusikPlayerView musikView;
        WaschmaschineView waschView;
        GaesteWlanView gaesteView;
        EinkaufslisteView einkaufView;
        MuellView muellView;
        ServerView serverView;
        String messZeit = "", messFrames = "";
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
            root.setClickable(true);   // touches stay in the view
            FrameLayout inhalt = new FrameLayout(ctx);
            root.addView(inhalt, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            if ("anwesenheit".equals(m.ansicht)) anwesenheit(inhalt);
            else if ("wetter".equals(m.ansicht)) wetter(inhalt);
            else if ("schoner".equals(m.ansicht)) schoner(inhalt);
            else if ("morgen".equals(m.ansicht)) morgen(inhalt);
            else if ("timer".equals(m.ansicht)) timer(inhalt);
            else if ("player".equals(m.ansicht)) player(inhalt);
            else if ("waschmaschine".equals(m.ansicht)) waschmaschine(inhalt);
            else if ("gaeste-wlan".equals(m.ansicht)) gaesteWlan(inhalt);
            else if ("einkaufsliste".equals(m.ansicht)) einkaufsliste(inhalt);
            else if ("muell".equals(m.ansicht)) muell(inhalt);
            else if ("server".equals(m.ansicht)) server(inhalt);
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
            pauseAbgleichen();
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
            if (!m.brauchtDaten() || gefuellt) {
                szeneMessen();
                plugin.inhaltFertig(TestAnsicht.this);
            }
            zeitText();
        }

        void zeitText() {
            if (anwView == null && wetterView == null) return;
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
            if (anwView != null) anwView.mess("nativ · " + messZeit, messFrames);
            if (wetterView != null) wetterView.mess("nativ · " + messZeit, "Hauptthread " + messFrames);
        }

        private void framesText() {
            if (!sichtbar) return;
            messFrames = String.format(Locale.ROOT, "%d fps · Ruckler %d von %d Frames · max %d ms",
                zaehler.fps, zaehler.ruckler, zaehler.frames, zaehler.maxNs / 1_000_000L);
            if (anwView != null) anwView.mess("nativ · " + messZeit, messFrames);
            if (wetterView != null) wetterView.mess("nativ · " + messZeit, "Hauptthread " + messFrames);
        }

        // ---- (e) weather scene ---------------------------------------------------------------------------------

        private void wetter(FrameLayout inhalt) {
            Object mo = m.param == null ? null : m.param.get("modus"), uhr = m.param == null ? null : m.param.get("uhr");
            wetterView = new WetterView(ctx, new WetterView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public boolean pausiert() { return plugin.wetterPausiert(); }
                @Override public void fertig(long aufbauMs) {
                    m.aufbauMs = aufbauMs;
                    if (gefuellt) return;
                    gefuellt = true;
                    if (sichtbar) zweiFrames(() -> { if (!zerstoert) { szeneMessen(); plugin.inhaltFertig(TestAnsicht.this); zeitText(); } });
                }
            }, mo instanceof String ? (String) mo : "live", uhr instanceof String ? (String) uhr : null, plugin.sternbildNamen(), plugin.wetterFps());
            plugin.konfigAnwenden(wetterView);
            if (m.param != null) {
                Object ans = m.param.get("ansicht");
                Object rawTage = m.param.get("tage");
                List<String> tage = new ArrayList<>();
                if (rawTage instanceof List) {
                    for (Object o : (List<?>) rawTage) if (o instanceof String) tage.add((String) o);
                } else if (rawTage instanceof String) {
                    for (String s : ((String) rawTage).split(",")) if (!s.trim().isEmpty()) tage.add(s.trim());
                }
                if (ans instanceof String || !tage.isEmpty()) {
                    wetterView.setVorhersageParam(ans instanceof String ? (String) ans : null, tage);
                }
            }
            inhalt.addView(wetterView.mitVordergrund(ctx), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            wetterFuellen();
        }

        private void schoner(FrameLayout inhalt) {
            Object mo = m.param == null ? null : m.param.get("modus"), uhr = m.param == null ? null : m.param.get("uhr");
            final String initModus = mo instanceof String ? (String) mo : "live";
            final String initUhr = uhr instanceof String ? (String) uhr : null;
            wetterView = new WetterView(ctx, new WetterView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public boolean pausiert() { return plugin.wetterPausiert(); }
                @Override public void fertig(long aufbauMs) {
                    m.aufbauMs = aufbauMs;
                    if (gefuellt) return;
                    gefuellt = true;
                    if (sichtbar) zweiFrames(() -> { if (!zerstoert) { szeneMessen(); plugin.inhaltFertig(TestAnsicht.this); zeitText(); } });
                }
            }, th, plugin.schonerOptionen(), initModus, initUhr, plugin.sternbildNamen(), true);
            plugin.konfigAnwenden(wetterView);
            wetterView.setSchoner(plugin.schonerZustand());
            inhalt.addView(wetterView.mitVordergrund(ctx), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View panel = vorschauLeiste(initModus, initUhr);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            int mg = th.px(16);
            plp.setMargins(mg, mg, mg, mg);
            inhalt.addView(panel, plp);
            wetterView.setTapAktion(() -> panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            wetterFuellen();
        }

        /** Interactive floating glass control panel for the screensaver preview (landscape, time & weather). */
        private View vorschauLeiste(String initModus, String initUhr) {
            final String[] ortKeys = {"auto", "meer", "tal", "see", "lichtung", "gipfel", "felder"};
            final String[] ortLabels = {"Auto", "Meer", "Tal", "See", "Lichtung", "Gipfel", "Felder"};
            final String[] wxKeys = {"live", "sunny", "partlycloudy", "cloudy", "rainy", "pouring", "lightning-rainy", "snowy", "snowy-rainy", "hail", "fog", "windy", "clear-night"};
            final String[] wxLabels = {"Live", "Sonnig", "Leicht bew.", "Bewölkt", "Regen", "Starkregen", "Gewitter", "Schnee", "Schneeregen", "Hagel", "Nebel", "Windig", "Klar"};
            final String[] zeitKeys = {"live", "06:30", "13:00", "18:30", "23:00", "zeitraffer"};
            final String[] zeitLabels = {"Live", "Morgen", "Tag", "Abend", "Nacht", "Zeitraffer"};

            final String[] cur = {initModus, initUhr, plugin.szeneOrt(), "live"}; // {modus, uhr, ort, cond}
            final TextView[] ortBtns = new TextView[ortKeys.length];
            final TextView[] zeitBtns = new TextView[zeitKeys.length];
            final TextView[] wxBtns = new TextView[wxKeys.length];

            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setClickable(true);
            box.setPadding(th.px(14), th.px(12), th.px(14), th.px(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xD90E1620);
            bg.setCornerRadius(th.px(18));
            bg.setStroke(Math.max(1, th.px(1)), 0x2AFFFFFF);
            box.setBackground(bg);

            final TextView uhrWert = new TextView(ctx);
            th.styleText(uhrWert, 14, 600, "onSurface");
            uhrWert.setText("live".equals(initModus) ? "Live" : ("zeitraffer".equals(initModus) ? "24h" : (initUhr != null ? initUhr : "Live")));

            final Runnable anwenden = () -> {
                if (wetterView != null) wetterView.setVorschau(cur[0], cur[1], cur[2], cur[3]);
                for (int i = 0; i < ortKeys.length; i++) pillStil(ortBtns[i], ortKeys[i].equals(cur[2]));
                for (int i = 0; i < wxKeys.length; i++) pillStil(wxBtns[i], wxKeys[i].equals(cur[3]));
                for (int i = 0; i < zeitKeys.length; i++) {
                    boolean ak = ("live".equals(zeitKeys[i]) && "live".equals(cur[0]))
                        || ("zeitraffer".equals(zeitKeys[i]) && "zeitraffer".equals(cur[0]))
                        || ("fest".equals(cur[0]) && zeitKeys[i].equals(cur[1]));
                    pillStil(zeitBtns[i], ak);
                }
                uhrWert.setText("live".equals(cur[0]) ? "Live" : ("zeitraffer".equals(cur[0]) ? "24h" : (cur[1] != null ? cur[1] : "Live")));
            };

            // Row 1: Landscapes + Close button
            LinearLayout r1 = new LinearLayout(ctx);
            r1.setOrientation(LinearLayout.HORIZONTAL);
            r1.setGravity(Gravity.CENTER_VERTICAL);
            TextView l1 = zeilenLabel("Ort");
            r1.addView(l1);
            HorizontalScrollView sOrt = new HorizontalScrollView(ctx);
            sOrt.setHorizontalScrollBarEnabled(false);
            LinearLayout pOrt = new LinearLayout(ctx);
            pOrt.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < ortKeys.length; i++) {
                final String k = ortKeys[i];
                TextView b = pill(ortLabels[i]);
                b.setOnClickListener(v -> sicher(() -> { cur[2] = k; anwenden.run(); }));
                ortBtns[i] = b;
                pOrt.addView(b, pillLp());
            }
            sOrt.addView(pOrt);
            r1.addView(sOrt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView zu = pill("Schließen");
            pillStil(zu, true);
            zu.setOnClickListener(v -> sicher(plugin::schliessen));
            LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            zlp.leftMargin = th.px(8);
            r1.addView(zu, zlp);
            box.addView(r1);

            // Row 2: Time presets + 00:00..23:55 SeekBar
            LinearLayout r2 = new LinearLayout(ctx);
            r2.setOrientation(LinearLayout.HORIZONTAL);
            r2.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            r2lp.topMargin = th.px(8);
            r2.addView(zeilenLabel("Zeit"));
            SeekBar seek = new SeekBar(ctx);
            seek.setMax(287);   // 5-minute steps: 00:00 .. 23:55
            seek.setProgress(12 * 12);
            for (int i = 0; i < zeitKeys.length; i++) {
                final String k = zeitKeys[i];
                TextView b = pill(zeitLabels[i]);
                b.setOnClickListener(v -> sicher(() -> {
                    if ("live".equals(k)) { cur[0] = "live"; cur[1] = null; }
                    else if ("zeitraffer".equals(k)) { cur[0] = "zeitraffer"; cur[1] = null; }
                    else {
                        cur[0] = "fest"; cur[1] = k;
                        String[] hp = k.split(":");
                        seek.setProgress(Integer.parseInt(hp[0]) * 12 + Integer.parseInt(hp[1]) / 5);
                    }
                    anwenden.run();
                }));
                zeitBtns[i] = b;
                r2.addView(b, pillLp());
            }
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                    if (!fromUser) return;
                    sicher(() -> {
                        int min = prog * 5;
                        cur[0] = "fest";
                        cur[1] = String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60);
                        anwenden.run();
                    });
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            slp.leftMargin = th.px(6);
            slp.rightMargin = th.px(8);
            r2.addView(seek, slp);
            r2.addView(uhrWert);
            box.addView(r2, r2lp);

            // Row 3: Weather conditions
            LinearLayout r3 = new LinearLayout(ctx);
            r3.setOrientation(LinearLayout.HORIZONTAL);
            r3.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams r3lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            r3lp.topMargin = th.px(8);
            r3.addView(zeilenLabel("Wetter"));
            HorizontalScrollView sWx = new HorizontalScrollView(ctx);
            sWx.setHorizontalScrollBarEnabled(false);
            LinearLayout pWx = new LinearLayout(ctx);
            pWx.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < wxKeys.length; i++) {
                final String k = wxKeys[i];
                TextView b = pill(wxLabels[i]);
                b.setOnClickListener(v -> sicher(() -> { cur[3] = k; anwenden.run(); }));
                wxBtns[i] = b;
                pWx.addView(b, pillLp());
            }
            sWx.addView(pWx);
            r3.addView(sWx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(r3, r3lp);

            anwenden.run();
            return box;
        }

        private TextView zeilenLabel(String s) {
            TextView t = new TextView(ctx);
            t.setText(s);
            t.setTextSize(13);
            t.setTextColor(0xFFA8B4C2);
            t.setMinWidth(th.px(54));
            return t;
        }

        private TextView pill(String label) {
            TextView b = new TextView(ctx);
            b.setText(label);
            b.setTextSize(13);
            b.setSingleLine(true);
            b.setPadding(th.px(11), th.px(5), th.px(11), th.px(5));
            pillStil(b, false);
            return b;
        }

        private void pillStil(TextView b, boolean aktiv) {
            if (b == null) return;
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(th.px(14));
            d.setColor(aktiv ? 0xFF4D6FA8 : 0x26FFFFFF);
            b.setBackground(d);
            b.setTextColor(aktiv ? 0xFFFFFFFF : 0xFFE2E8EE);
        }

        private LinearLayout.LayoutParams pillLp() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = th.px(6);
            return lp;
        }

        void wetterFuellen() {
            if (zerstoert || wetterView == null) return;
            if (m.wetter != null) wetterView.setDaten(m.wetter);
        }

        private void morgen(FrameLayout inhalt) {
            morgenView = new MorgenView(ctx, th, new MorgenView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public void steuern(String aktion) { plugin.newsSteuern(aktion); }
                @Override public boolean pausiert() { return plugin.wetterPausiert(); }
                @Override public void bildBedarf(String bildUrl) { plugin.artikelBildLaden(bildUrl); }
            }, plugin.szeneOrt(), plugin.sternbildNamen());
            plugin.konfigAnwenden(morgenView.wetterView());
            inhalt.addView(morgenView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            morgenWetterFuellen();
            Object mo = m.param == null ? null : m.param.get("modus");
            long now = System.currentTimeMillis();
            if ("demo".equals(mo)) morgenView.setNews(MorgenDaten.demo(false, false, now));
            else if ("story".equals(mo)) morgenView.setNews(MorgenDaten.demo(true, false, now));
            else if ("mehr".equals(mo)) morgenView.setNews(MorgenDaten.demo(true, true, now));
            else morgenView.setNews(plugin.newsStatus());
        }

        void morgenWetterFuellen() {
            if (zerstoert || morgenView == null) return;
            if (m.wetter != null) morgenView.setWetter(m.wetter);
        }

        /** Main thread: screensaver or screen changed. Pause both the weather scene and the frame counter. */
        void wetterPause() {
            pauseAbgleichen();
        }

        private void pauseAbgleichen() {
            if (zerstoert) return;
            if (plugin.wetterPausiert()) {
                zaehler.stop();
                handler.removeCallbacks(sekunde);
            } else {
                zaehler.start();
                handler.removeCallbacks(sekunde);
                handler.postDelayed(sekunde, 1000);
            }
            if (wetterView != null) wetterView.pauseGeaendert();
            if (morgenView != null) morgenView.pauseGeaendert();
            if (timerView != null) timerView.pauseGeaendert();
            if (musikView != null) musikView.pauseGeaendert();
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

        // ---- (a) calendar (full-screen native port of kalender.py) --------------------------------------------

        private void kalender(FrameLayout inhalt) {
            Kalender.Spanne sp = m.kalenderSpanne != null ? m.kalenderSpanne : Kalender.spanne(m.param);
            kalenderView = new KalenderView(ctx, th, new KalenderView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public void bereichLaden(Calendar start, int tageAnzahl) {
                    plugin.kalenderBereichLaden(TestAnsicht.this, start, tageAnzahl);
                }
                @Override public void erinnern(String titel, String umIso, String beginn) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("titel", titel);
                    data.put("um", umIso);
                    data.put("beginn", beginn);
                    plugin.haDienst("script", "echo_show_kalender_erinnern", null, data);
                }
                @Override public void erstellen(Map<String, Object> callMsg) {
                    plugin.haCallWeiterleiten(callMsg, (ok, data, err) -> {
                        if (ok) plugin.kalenderBereichLaden(TestAnsicht.this, kalenderView.sichtStart(), kalenderView.sichtTage());
                    });
                }
                @Override public void muellErledigt(String datum, boolean erledigt) {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("type", "echo_show/muell/erledigt_setzen");
                    msg.put("datum", datum);
                    msg.put("erledigt", erledigt);
                    plugin.haCallWeiterleiten(msg, (ok, data, err) -> {
                        if (ok) plugin.kalenderBereichLaden(TestAnsicht.this, kalenderView.sichtStart(), kalenderView.sichtTage());
                    });
                }
            }, sp);
            inhalt.addView(kalenderView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (m.kalenderErgebnis != null || m.termine != null || m.datenFehler != null) kalenderFuellen();
        }

        void kalenderFuellen() {
            if (zerstoert || kalenderView == null) return;
            if (m.kalenderErgebnis != null) kalenderView.setDaten(m.kalenderErgebnis, m.datenFehler);
            else kalenderView.setDaten(m.termine, m.datenFehler);
            if (!gefuellt) {
                gefuellt = true;
                if (sichtbar) zweiFrames(() -> { if (!zerstoert) { plugin.inhaltFertig(TestAnsicht.this); zeitText(); } });
            }
        }

        // ---- (b) timer & alarms (full-screen native port of timer_ansicht.py) ---------------------------------

        private void timer(FrameLayout inhalt) {
            int fbSek = m.param != null && m.param.get("sekunden") instanceof Number ? ((Number) m.param.get("sekunden")).intValue() : 0;
            timerView = new TimerView(ctx, th, new TimerView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public boolean pausiert() { return plugin.wetterPausiert(); }
                @Override public void haDienst(String domain, String service, java.util.Map<String, Object> target, java.util.Map<String, Object> data) {
                    plugin.haDienst(domain, service, target, data);
                }
            }, fbSek);
            timerView.setZustand(plugin.timerZustand());
            inhalt.addView(timerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // ---- (c) music player ---------------------------------------------------------------------------------

        private void player(FrameLayout inhalt) {
            musikView = new MusikPlayerView(ctx, th, new MusikPlayerView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public void ha(Map<String, Object> msg, MusikPlayerView.Antwort cb) {
                    plugin.haCallWeiterleiten(msg, cb::fertig);
                }
                @Override public void coverLaden(String url) {
                    plugin.playerCoverLaden(url);
                }
                @Override public void nachladen() {
                    plugin.playerDatenNachladen();
                }
                @Override public boolean pausiert() { return plugin.wetterPausiert(); }
            });
            inhalt.addView(musikView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (m.ansichtDaten != null) musikFuellen();
        }

        void musikFuellen() {
            if (zerstoert || musikView == null) return;
            musikView.setDaten(m.ansichtDaten);
            if (!gefuellt && (m.ansichtDaten != null || m.datenFehler != null)) {
                gefuellt = true;
                if (sichtbar) zweiFrames(() -> { if (!zerstoert) { plugin.inhaltFertig(TestAnsicht.this); zeitText(); } });
            }
        }

        // ---- (f) washing machine ------------------------------------------------------------------------------

        private void waschmaschine(FrameLayout inhalt) {
            String initTab = m.param != null && m.param.get("tab") instanceof String ? (String) m.param.get("tab") : "";
            waschView = new WaschmaschineView(ctx, th, initTab, new WaschmaschineView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public void ha(Map<String, Object> msg, WaschmaschineView.Aktionen.Antwort cb) {
                    plugin.haCallWeiterleiten(msg, cb::fertig);
                }
            });
            inhalt.addView(waschView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (m.ansichtDaten != null || m.datenFehler != null) waschFuellen();
        }

        void waschFuellen() {
            if (zerstoert || waschView == null) return;
            waschView.setDaten(m.ansichtDaten, m.datenFehler);
            if (!gefuellt && (m.ansichtDaten != null || m.datenFehler != null)) {
                gefuellt = true;
                if (sichtbar) zweiFrames(() -> { if (!zerstoert) { plugin.inhaltFertig(TestAnsicht.this); zeitText(); } });
            }
        }

        // ---- (g) guest Wi-Fi ----------------------------------------------------------------------------------

        private void gaesteWlan(FrameLayout inhalt) {
            gaesteView = new GaesteWlanView(ctx, th, new GaesteWlanView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public void ha(Map<String, Object> msg, GaesteWlanView.Aktionen.Antwort cb) {
                    plugin.haCallWeiterleiten(msg, cb::fertig);
                }
                @Override public void bildLaden(String url) {
                    plugin.gaesteBildLaden(url);
                }
            });
            inhalt.addView(gaesteView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (m.ansichtDaten != null || m.datenFehler != null) gaesteFuellen();
        }

        void gaesteFuellen() {
            if (zerstoert || gaesteView == null) return;
            gaesteView.setDaten(m.ansichtDaten, m.datenFehler);
            if (!gefuellt && (m.ansichtDaten != null || m.datenFehler != null)) {
                gefuellt = true;
                if (sichtbar) zweiFrames(() -> { if (!zerstoert) { plugin.inhaltFertig(TestAnsicht.this); zeitText(); } });
            }
        }

        // ---- (h) shopping list --------------------------------------------------------------------------------

        private void einkaufsliste(FrameLayout inhalt) {
            String initListe = m.param != null && m.param.get("liste") instanceof String ? (String) m.param.get("liste") : "";
            einkaufView = new EinkaufslisteView(ctx, th, initListe, plugin.caveatTf(), new EinkaufslisteView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public void ha(Map<String, Object> msg, EinkaufslisteView.Aktionen.Antwort cb) {
                    plugin.haCallWeiterleiten(msg, cb::fertig);
                }
            });
            inhalt.addView(einkaufView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (m.ansichtDaten != null || m.ansichtZusatz != null || m.datenFehler != null) einkaufFuellen();
        }

        void einkaufFuellen() {
            if (zerstoert || einkaufView == null) return;
            einkaufView.setDaten(m.ansichtDaten, m.ansichtZusatz, m.datenFehler);
            if (!gefuellt && (m.ansichtDaten != null || m.datenFehler != null)) {
                gefuellt = true;
                if (sichtbar) zweiFrames(() -> { if (!zerstoert) { plugin.inhaltFertig(TestAnsicht.this); zeitText(); } });
            }
        }

        // ---- (i) waste collection -----------------------------------------------------------------------------

        private void muell(FrameLayout inhalt) {
            muellView = new MuellView(ctx, th, new MuellView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public void ha(Map<String, Object> msg, MuellView.Aktionen.Antwort cb) {
                    plugin.haCallWeiterleiten(msg, cb::fertig);
                }
            });
            inhalt.addView(muellView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (m.ansichtDaten != null || m.datenFehler != null) muellFuellen();
        }

        void muellFuellen() {
            if (zerstoert || muellView == null) return;
            muellView.setDaten(m.ansichtDaten, m.datenFehler);
            if (!gefuellt && (m.ansichtDaten != null || m.datenFehler != null)) {
                gefuellt = true;
                if (sichtbar) zweiFrames(() -> { if (!zerstoert) { plugin.inhaltFertig(TestAnsicht.this); zeitText(); } });
            }
        }

        // ---- (j) server status --------------------------------------------------------------------------------

        private void server(FrameLayout inhalt) {
            String initTab = m.param != null && m.param.get("tab") instanceof String ? (String) m.param.get("tab") : "";
            serverView = new ServerView(ctx, th, initTab, new ServerView.Aktionen() {
                @Override public void zurueck() { plugin.schliessen(); }
                @Override public void ha(Map<String, Object> msg, ServerView.Aktionen.Antwort cb) {
                    plugin.haCallWeiterleiten(msg, cb::fertig);
                }
            });
            inhalt.addView(serverView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (m.ansichtDaten != null || m.datenFehler != null) serverFuellen();
        }

        void serverFuellen() {
            if (zerstoert || serverView == null) return;
            serverView.setDaten(m.ansichtDaten, m.datenFehler);
            if (!gefuellt && (m.ansichtDaten != null || m.datenFehler != null)) {
                gefuellt = true;
                if (sichtbar) zweiFrames(() -> { if (!zerstoert) { plugin.inhaltFertig(TestAnsicht.this); zeitText(); } });
            }
        }

        /** The weather scene's own figures (frame times, CPU, bitmap memory) for "bereit" and the status. */
        void szeneMessen() {
            if (wetterView == null) return;
            m.szene = wetterView.kennzahlen();
            // "bereit" comes within the first second; the figures over full one-second windows follow 5 s later in the status
            handler.postDelayed(() -> sicher(() -> {
                if (zerstoert || wetterView == null) return;
                m.szene5 = wetterView.kennzahlen();
                plugin.szeneNachtrag(TestAnsicht.this);
            }), 5000);
        }

        void zerstoeren() {
            if (wetterView != null && m.szeneEnde == null) {
                try { m.szeneEnde = wetterView.kennzahlen(); } catch (RuntimeException ignored) {}
            }
            if (zerstoert) return;
            zerstoert = true;
            zaehler.stop();
            handler.removeCallbacksAndMessages(null);
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
