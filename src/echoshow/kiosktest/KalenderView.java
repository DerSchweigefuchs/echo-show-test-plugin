package echoshow.kiosktest;

import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Native full-screen Calendar view matching kalender.py on the 963 x 584 dashboard grid (#121A21):
 * - Top-left back circle button, "Kalender" title, and interactive period navigation (< · period pill · > · Heute)
 * - Top-right segmented view switcher honoring enabled views (Tag · 3 Tage · Woche · Monat · Liste)
 * - Time grid (Tag / 3 Tage / Woche): holidays (#FFE0B48C), all-day & waste pickup bars with mini bin icon + ✓,
 *   dynamic hour lines, side-by-side overlapping event cards, live red current-time line, and AI highlight frames
 * - Month grid (Monat): 7x6 day cells with holiday label, event/waste previews, tap any day to jump to Tag
 * - Agenda list (Liste): vertical touch-scrollable list with grouped day headers and holidays
 * - Event detail card: calendar/bin badge, time/countdown, location, description, "15 min vorher erinnern",
 *   and "Steht draußen ✓" toggle for waste pickups
 * - Period Date Picker modal (‹ Month ›, KW highlight, quick jumps)
 * - "+ Termin" modal with real Android EditText child when konfig.schreiben is configured.
 */
final class KalenderView extends FrameLayout {
    static final float W = 963, H = 584;
    private static final int BG = 0xFF121A21, TXT = 0xFFECEBE8, SUB = 0xFF8FA3B5, BOX = 0xFF18232D, DETAIL_BG = 0xFF1A2631, HOL_COL = 0xFFE0B48C;
    private static final String[] WT = {"So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"};
    private static final String[] WTL = {"Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"};
    private static final String[] MON = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"};
    private static final String[] ALL_TAB_KEYS = {"tag", "3tage", "woche", "monat", "liste"};

    interface Aktionen {
        void zurueck();
        void bereichLaden(Calendar start, int tageAnzahl);
        default void erinnern(String titel, String umIso, String beginn) {}
        default void erstellen(Map<String, Object> callMsg) {}
        default void muellErledigt(String datum, boolean erledigt) {}
    }

    private static final class Hit {
        final String id;
        final float l, t, r, b;
        Hit(String id, float l, float t, float r, float b) { this.id = id; this.l = l; this.t = t; this.r = r; this.b = b; }
        boolean trifft(float x, float y) { return x >= l && x <= r && y >= t && y <= b; }
    }

    private final KsTheme theme;
    private final Aktionen aktionen;
    private final CanvasView canvasView;
    private final EditText titelInput;

    private volatile String modus = "woche";
    private volatile Calendar basisTag = Kalender.mitternacht(Calendar.getInstance());
    private Set<String> markiert = new LinkedHashSet<>();
    private final boolean initExplizit;
    private boolean konfigAngewendet;

    private volatile Kalender.Konfig konfig = new Kalender.Konfig();
    private List<Kalender.Termin> ereignisse;   // null while loading
    private Map<String, String> feiertage = new LinkedHashMap<>();
    private String fehler;
    private String ausgewaehltUid;
    private final Set<String> erinnertUids = new HashSet<>();

    // Date picker modal state
    private boolean pickOffen;
    private Calendar pickMonat = Kalender.mitternacht(Calendar.getInstance());

    // "+ Termin" modal state
    private boolean neuOffen;
    private Calendar neuDatum = Kalender.mitternacht(Calendar.getInstance());
    private int neuStunde = 10, neuMinute = 0;
    private int neuDauerMin = 60;   // 0 = all-day, 30, 60, 120

    KalenderView(Context ctx, KsTheme theme, Aktionen aktionen, Kalender.Spanne init) {
        super(ctx);
        this.theme = theme;
        this.aktionen = aktionen;
        setBackgroundColor(BG);
        setClickable(true);

        if (init != null) {
            modus = init.zeitraum;
            basisTag = (Calendar) init.start.clone();
            markiert = new LinkedHashSet<>(init.markiert);
            initExplizit = init.explizit;
        } else {
            initExplizit = false;
        }

        canvasView = new CanvasView(ctx);
        addView(canvasView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        titelInput = new EditText(ctx);
        titelInput.setHint("Was?");
        titelInput.setHintTextColor(0x88ECEBE8);
        titelInput.setTextColor(TXT);
        titelInput.setSingleLine(true);
        titelInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titelInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        titelInput.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(14f);
        titelInput.setBackground(bg);
        titelInput.setVisibility(GONE);
        addView(titelInput, new LayoutParams(400, 48));
    }

    void setDaten(List<Kalender.Termin> list, String err) {
        ereignisse = list != null ? list : Collections.emptyList();
        feiertage = Collections.emptyMap();
        fehler = err;
        canvasView.invalidate();
    }

    void setDaten(Kalender.Ergebnis erg, String err) {
        fehler = err;
        if (erg != null) {
            ereignisse = erg.termine != null ? erg.termine : Collections.emptyList();
            feiertage = erg.feiertage != null ? erg.feiertage : Collections.emptyMap();
            if (erg.konfig != null) {
                konfig = erg.konfig;
                if (!konfig.ansichten.contains(modus) && !konfig.ansichten.isEmpty()) {
                    modus = konfig.ansichten.get(0);
                }
                if (!initExplizit && !konfigAngewendet) {
                    konfigAngewendet = true;
                    Calendar prevStart = sichtStart();
                    int prevTage = sichtTage();
                    if (!konfig.start.isEmpty() && konfig.ansichten.contains(konfig.start)) {
                        modus = konfig.start;
                    }
                    if ("woche".equals(modus) && "ab_heute".equals(konfig.woche)) {
                        basisTag = Kalender.mitternacht(Calendar.getInstance());
                    }
                    Calendar newStart = sichtStart();
                    int newTage = sichtTage();
                    if (!Kalender.datumKey(prevStart).equals(Kalender.datumKey(newStart)) || prevTage != newTage) {
                        neuLaden();
                        return;
                    }
                } else {
                    konfigAngewendet = true;
                }
            }
        } else if (ereignisse == null) {
            ereignisse = Collections.emptyList();
        }
        canvasView.invalidate();
    }

    void setAusgewaehlt(String uid) {
        ausgewaehltUid = uid;
        canvasView.invalidate();
    }

    /** Current visible start date and day count for HA calendar queries. */
    Calendar sichtStart() {
        if ("woche".equals(modus)) {
            if ("ab_heute".equals(konfig.woche)) return (Calendar) basisTag.clone();
            return Kalender.montag(basisTag);
        }
        if ("monat".equals(modus)) {
            Calendar m = (Calendar) basisTag.clone();
            m.set(Calendar.DAY_OF_MONTH, 1);
            return Kalender.montag(m);
        }
        return (Calendar) basisTag.clone();
    }

    int sichtTage() {
        if ("tag".equals(modus)) return 1;
        if ("3tage".equals(modus)) return 3;
        if ("monat".equals(modus)) return 42;
        if ("liste".equals(modus)) return Math.max(1, Math.min(45, konfig.listeTage));
        if ("woche".equals(modus) && "mo-fr".equals(konfig.woche)) return 5;
        return 7;
    }

    void zeichneFuerTest(Canvas c, int gw, int gh, long nowMs) {
        canvasView.zeichneFuerTest(c, gw, gh, nowMs);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        layoutInputOverlay();
    }

    @Override protected void onDetachedFromWindow() {
        tastaturAus();
        super.onDetachedFromWindow();
    }

    private void layoutInputOverlay() {
        if (!neuOffen) {
            if (titelInput.getVisibility() != GONE) titelInput.setVisibility(GONE);
            return;
        }
        int gw = getWidth(), gh = getHeight();
        if (gw <= 0 || gh <= 0) return;
        float s = Math.min(gw / W, gh / H);
        float dx = (gw - W * s) * .5f;
        float dy = (gh - H * s) * .5f;
        int ix = Math.round(dx + 153f * s);
        int iy = Math.round(dy + 114f * s);
        int iw = Math.max(40, Math.round(656f * s));
        int ih = Math.max(24, Math.round(52f * s));

        LayoutParams lp = (LayoutParams) titelInput.getLayoutParams();
        if (lp.leftMargin != ix || lp.topMargin != iy || lp.width != iw || lp.height != ih) {
            lp.leftMargin = ix;
            lp.topMargin = iy;
            lp.width = iw;
            lp.height = ih;
            titelInput.setLayoutParams(lp);
        }
        titelInput.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * s);
        int pad = Math.max(6, Math.round(16f * s));
        titelInput.setPadding(pad, 0, pad, 0);
        if (titelInput.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) titelInput.getBackground()).setCornerRadius(14f * s);
        }
        if (titelInput.getVisibility() != VISIBLE) {
            titelInput.setVisibility(VISIBLE);
        }
    }

    private void neuModalOeffnen() {
        Calendar heute = Kalender.mitternacht(Calendar.getInstance());
        neuDatum = basisTag.getTimeInMillis() < heute.getTimeInMillis() ? heute : (Calendar) basisTag.clone();
        Calendar now = Calendar.getInstance();
        neuStunde = Math.min(22, (now.get(Calendar.HOUR_OF_DAY) + 1) % 24);
        neuMinute = 0;
        neuDauerMin = 60;
        titelInput.setText("");
        neuOffen = true;
        pickOffen = false;
        layoutInputOverlay();
        canvasView.invalidate();
    }

    private void neuModalSchliessen() {
        neuOffen = false;
        tastaturAus();
        layoutInputOverlay();
        canvasView.invalidate();
    }

    private void neuTerminEintragen() {
        String titel = titelInput.getText() == null ? "" : titelInput.getText().toString().trim();
        if (titel.isEmpty() || konfig.schreiben.isEmpty()) return;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", titel);
        String ds = Kalender.datumKey(neuDatum);
        if (neuDauerMin <= 0) {
            Calendar endCal = (Calendar) neuDatum.clone();
            endCal.add(Calendar.DAY_OF_MONTH, 1);
            data.put("start_date", ds);
            data.put("end_date", Kalender.datumKey(endCal));
        } else {
            Calendar sCal = (Calendar) neuDatum.clone();
            sCal.set(Calendar.HOUR_OF_DAY, neuStunde);
            sCal.set(Calendar.MINUTE, neuMinute);
            sCal.set(Calendar.SECOND, 0);
            Calendar eCal = (Calendar) sCal.clone();
            eCal.add(Calendar.MINUTE, neuDauerMin);
            data.put("start_date_time", Kalender.datumKey(sCal) + " " + Kalender.hm(sCal.getTimeInMillis()) + ":00");
            data.put("end_date_time", Kalender.datumKey(eCal) + " " + Kalender.hm(eCal.getTimeInMillis()) + ":00");
        }

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("entity_id", konfig.schreiben);

        Map<String, Object> callMsg = new LinkedHashMap<>();
        callMsg.put("type", "call_service");
        callMsg.put("domain", "calendar");
        callMsg.put("service", "create_event");
        callMsg.put("target", target);
        callMsg.put("service_data", data);

        basisTag = (Calendar) neuDatum.clone();
        neuModalSchliessen();
        aktionen.erstellen(callMsg);
    }

    private void tastaturAus() {
        try {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(titelInput.getWindowToken(), 0);
        } catch (RuntimeException ignored) {}
    }

    private void neuLaden() {
        ereignisse = null;
        fehler = null;
        canvasView.invalidate();
        aktionen.bereichLaden(sichtStart(), sichtTage());
    }

    private static String tabName(String key) {
        switch (key) {
            case "tag": return "Tag";
            case "3tage": return "3 Tage";
            case "woche": return "Woche";
            case "monat": return "Monat";
            case "liste": return "Liste";
            default: return key;
        }
    }

    private final class CanvasView extends View {
        private final Map<String, Path> iconCache = new HashMap<>();
        private final List<Hit> hits = new ArrayList<>();
        private float listScrollY, listMaxScroll;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface tfNorm = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface tfBold = Typeface.create("sans-serif", Typeface.BOLD);
        private final RectF rf = new RectF();
        private final Path binPath = new Path();
        private final Path iconTmp = new Path();

        private float skala = 1f, dx, dy, downX, downY, lastY;
        private boolean ziehend;

        CanvasView(Context ctx) {
            super(ctx);
            setClickable(true);
            linie.setStyle(Paint.Style.STROKE);
            linie.setStrokeCap(Paint.Cap.ROUND);
        }

        void zeichneFuerTest(Canvas c, int gw, int gh, long nowMs) {
            zeichnen(c, gw, gh, nowMs);
        }

        @Override protected void onDraw(Canvas c) {
            int gw = getWidth(), gh = getHeight();
            if (gw <= 0 || gh <= 0) return;
            zeichnen(c, gw, gh, System.currentTimeMillis());
        }

        private void zeichnen(Canvas c, int gw, int gh, long nowMs) {
            skala = Math.min(gw / W, gh / H);
            if (skala <= 0) return;
            dx = (gw - W * skala) * .5f;
            dy = (gh - H * skala) * .5f;
            hits.clear();

            c.drawColor(BG);
            c.save();
            c.translate(dx, dy);
            c.scale(skala, skala);
            c.clipRect(0, 0, W, H);
            try {
                Calendar t0Cal = Calendar.getInstance();
                t0Cal.setTimeInMillis(nowMs);
                Kalender.mitternacht(t0Cal);
                long t0 = t0Cal.getTimeInMillis();

                Calendar rStart = sichtStart();
                int rN = sichtTage();
                long rA = rStart.getTimeInMillis();
                Calendar rEndeCal = (Calendar) rStart.clone();
                rEndeCal.add(Calendar.DAY_OF_MONTH, rN);
                long rB = rEndeCal.getTimeInMillis();

                // 1. Header: back button, title, period nav + picker toggle, and enabled tabs
                zeichneKopf(c, rStart, rN, t0, rA, rB);

                // 2. Main view body
                if ("tag".equals(modus) || "3tage".equals(modus) || "woche".equals(modus)) {
                    zeichneZeitraster(c, rStart, rN, t0, nowMs);
                } else if ("monat".equals(modus)) {
                    zeichneMonat(c, rStart, t0);
                } else {
                    zeichneListe(c, rStart, rN, t0, nowMs);
                }

                // 3. Selected event detail card
                if (ausgewaehltUid != null && ereignisse != null) {
                    Kalender.Termin sel = null;
                    for (Kalender.Termin e : ereignisse) if (ausgewaehltUid.equals(e.uid)) { sel = e; break; }
                    if (sel != null) zeichneDetail(c, sel, t0, nowMs);
                }

                // 4. "+ Termin" FAB (if writable calendar configured)
                if (!konfig.schreiben.isEmpty()) {
                    t.setTypeface(tfMed); t.setTextSize(15);
                    String lbl = "Termin";
                    float bw = 18 + 19 + 8 + t.measureText(lbl) + 18;
                    float bx = W - 30 - bw, by = H - 16 - 42;
                    rf.set(bx, by, bx + bw, by + 42);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x66000000);
                    c.drawRoundRect(bx + 1, by + 4, bx + bw + 1, by + 46, 18, 18, p);
                    p.setColor(0xFF3182B7);
                    c.drawRoundRect(rf, 18, 18, p);
                    drawIcon(c, "mdi:plus", bx + 16, by + 11.5f, 19, 0xFFFFFFFF);
                    t.setColor(0xFFFFFFFF);
                    c.drawText(lbl, bx + 42, by + 26.5f, t);
                    hits.add(new Hit("plus", rf.left, rf.top, rf.right, rf.bottom));
                }

                // 5. Period Date Picker Modal
                if (pickOffen) zeichnePicker(c, rA, rB, t0);

                // 6. "+ Termin" Modal
                if (neuOffen) zeichneNeuModal(c, t0);
            } catch (RuntimeException ignored) {}
            c.restore();
        }

        private void zeichneKopf(Canvas c, Calendar rStart, int rN, long t0, long rA, long rB) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1EFFFFFF);
            c.drawCircle(58, 50, 28, p);
            drawIcon(c, "mdi:chevron-left", 45, 37, 26, TXT);
            hits.add(new Hit("zurueck", 22, 14, 94, 86));

            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(tfMed); t.setTextSize(22); t.setColor(TXT);
            c.drawText("Kalender", 102, 42, t);

            // Period navigation row (< [period v] > Heute)
            float ny = 52;
            drawIcon(c, "mdi:chevron-left", 100, ny, 20, SUB);
            hits.add(new Hit("nav:-1", 94, ny - 6, 126, ny + 26));

            String zrTxt = zeitraumLabel(rStart, rN);
            t.setTypeface(tfNorm); t.setTextSize(14);
            float pw = t.measureText(zrTxt) + 38;
            rf.set(126, ny - 2, 126 + pw, ny + 22);
            p.setColor(pickOffen ? 0xFF3182B7 : 0x14FFFFFF);
            c.drawRoundRect(rf, 10, 10, p);
            t.setColor(pickOffen ? 0xFFFFFFFF : TXT);
            c.drawText(zrTxt, 136, ny + 15, t);
            drawIcon(c, pickOffen ? "mdi:menu-up" : "mdi:menu-down", 126 + pw - 22, ny + 2, 16, pickOffen ? 0xFFFFFFFF : SUB);
            hits.add(new Hit("pick", 126, ny - 4, 126 + pw, ny + 24));

            float rx = 126 + pw + 4;
            drawIcon(c, "mdi:chevron-right", rx, ny, 20, SUB);
            hits.add(new Hit("nav:1", rx - 4, ny - 6, rx + 26, ny + 26));

            if (rA > t0 || rB <= t0) {
                float hx = rx + 28;
                t.setTypeface(tfMed); t.setTextSize(14); t.setColor(0xFF6FB3F2);
                c.drawText("Heute", hx, ny + 15, t);
                hits.add(new Hit("heute", hx - 6, ny - 4, hx + t.measureText("Heute") + 8, ny + 24));
            }

            // Top-right enabled view tabs
            List<String> tabs = konfig.ansichten != null && !konfig.ansichten.isEmpty()
                ? konfig.ansichten : java.util.Arrays.asList(ALL_TAB_KEYS);
            float totalW = 8;
            float[] tabW = new float[tabs.size()];
            t.setTextSize(14.5f);
            for (int i = 0; i < tabs.size(); i++) {
                String k = tabs.get(i);
                t.setTypeface(k.equals(modus) ? tfMed : tfNorm);
                tabW[i] = t.measureText(tabName(k)) + 26;
                totalW += tabW[i];
            }
            float tx0 = W - 30 - totalW, ty0 = 24, th = 42;
            rf.set(tx0, ty0, tx0 + totalW, ty0 + th);
            p.setColor(0x10FFFFFF);
            c.drawRoundRect(rf, 21, 21, p);
            float cx = tx0 + 4;
            for (int i = 0; i < tabs.size(); i++) {
                String k = tabs.get(i);
                boolean aktiv = k.equals(modus);
                rf.set(cx, ty0 + 4, cx + tabW[i], ty0 + th - 4);
                if (aktiv) {
                    p.setColor(TXT);
                    c.drawRoundRect(rf, 17, 17, p);
                }
                t.setTextAlign(Paint.Align.CENTER);
                t.setTypeface(aktiv ? tfMed : tfNorm);
                t.setColor(aktiv ? BG : 0xFF9DA0A2);
                c.drawText(tabName(k), rf.centerX(), ty0 + 26, t);
                hits.add(new Hit("v:" + k, rf.left, ty0, rf.right, ty0 + th));
                cx += tabW[i];
            }
            t.setTextAlign(Paint.Align.LEFT);
        }

        // ---- Time Grid (Tag / 3 Tage / Woche) ----------------------------------------------------------------------

        private void zeichneZeitraster(Canvas c, Calendar rStart, int rN, long t0, long nowMs) {
            float x0 = 20, y0 = 90, w = W - 40, h = H - 102;
            float lw = 48, cw = (w - lw) / rN;

            long[] dayA = new long[rN], dayB = new long[rN];
            String[] dayKey = new String[rN];
            boolean[] dayMark = new boolean[rN];
            String[] dayHol = new String[rN];
            boolean anyHol = false;
            Calendar cur = (Calendar) rStart.clone();
            for (int i = 0; i < rN; i++) {
                dayA[i] = cur.getTimeInMillis();
                dayKey[i] = Kalender.datumKey(cur);
                dayMark[i] = markiert.contains(dayKey[i]);
                dayHol[i] = konfig.mitFeiertage ? feiertage.get(dayKey[i]) : null;
                if (dayHol[i] != null && !dayHol[i].isEmpty()) anyHol = true;
                cur.add(Calendar.DAY_OF_MONTH, 1);
                dayB[i] = cur.getTimeInMillis();
            }

            // Highlight frames behind marked day columns
            for (int i = 0; i < rN; i++) {
                if (!dayMark[i] || (i > 0 && dayMark[i - 1])) continue;
                int j = i;
                while (j + 1 < rN && dayMark[j + 1]) j++;
                rf.set(x0 + lw + i * cw + 1, y0, x0 + lw + (j + 1) * cw - 1, y0 + h);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x1A8FC0FF);
                c.drawRoundRect(rf, 14, 14, p);
                linie.setStrokeWidth(1.5f);
                linie.setColor(0x8C8FC0FF);
                c.drawRoundRect(rf, 14, 14, linie);
            }

            List<Kalender.Termin> evs = ereignisse != null ? ereignisse : Collections.emptyList();
            List<Kalender.Termin> allDay = new ArrayList<>();
            int h0 = 7, h1 = 22;
            for (Kalender.Termin e : evs) {
                if (e.endeMs <= dayA[0] || e.startMs >= dayB[rN - 1]) continue;
                if (e.ganztag) {
                    allDay.add(e);
                } else {
                    for (int i = 0; i < rN; i++) {
                        if (e.startMs < dayB[i] && e.endeMs > dayA[i]) {
                            long a = Math.max(e.startMs, dayA[i]), b = Math.min(e.endeMs, dayB[i]);
                            Calendar ca = Calendar.getInstance(); ca.setTimeInMillis(a);
                            Calendar cb = Calendar.getInstance(); cb.setTimeInMillis(b);
                            h0 = Math.min(h0, ca.get(Calendar.HOUR_OF_DAY));
                            int bh = cb.get(Calendar.HOUR_OF_DAY) + (cb.get(Calendar.MINUTE) > 0 ? 1 : 0);
                            h1 = Math.max(h1, bh == 0 ? 24 : bh);
                        }
                    }
                }
            }
            h1 = Math.min(24, Math.max(h0 + 6, h1));
            float hdrH = anyHol ? 38 : 26;
            float topOff = hdrH + (allDay.isEmpty() ? 6 : (allDay.size() > 1 ? 48 : 28));
            float ph = (h - topOff - 8) / (float) (h1 - h0);

            // Day column headers + holiday label + vertical hairlines
            cur = (Calendar) rStart.clone();
            for (int i = 0; i < rN; i++) {
                float colX = x0 + lw + i * cw;
                boolean heute = dayA[i] == t0;
                String lbl = rN == 1 ? datumLang(cur) : WT[cur.get(Calendar.DAY_OF_WEEK) - 1] + " " + cur.get(Calendar.DAY_OF_MONTH);
                t.setTextAlign(Paint.Align.CENTER);
                t.setTextSize(13.5f);
                t.setTypeface(heute || dayMark[i] ? tfBold : tfNorm);
                t.setColor(heute || dayMark[i] ? 0xFFFFFFFF : SUB);
                c.drawText(lbl, colX + cw * .5f, y0 + 17, t);

                if (dayHol[i] != null && !dayHol[i].isEmpty()) {
                    t.setTypeface(tfNorm); t.setTextSize(10.5f); t.setColor(HOL_COL);
                    c.drawText(kuerzen(t, dayHol[i], cw - 8), colX + cw * .5f, y0 + 31, t);
                }
                hits.add(new Hit("tag:" + dayA[i], colX, y0, colX + cw, y0 + hdrH));

                if (i > 0) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x0DFFFFFF);
                    c.drawRect(colX, y0 + 24, colX + 1, y0 + h, p);
                }
                cur.add(Calendar.DAY_OF_MONTH, 1);
            }

            // Hour grid lines & labels
            int stepH = (h1 - h0) > 12 ? 2 : 1;
            t.setTextAlign(Paint.Align.RIGHT);
            t.setTypeface(tfNorm); t.setTextSize(11); t.setColor(0xFF6C7884);
            for (int hr = h0; hr <= h1; hr += stepH) {
                float gy = y0 + topOff + (hr - h0) * ph;
                c.drawText(hr + ":00", x0 + lw - 8, gy + 4, t);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x0BFFFFFF);
                c.drawRect(x0 + lw, gy, x0 + w, gy + 1, p);
            }
            t.setTextAlign(Paint.Align.LEFT);

            // All-day event & waste bars (up to 2 rows)
            for (int idx = 0; idx < Math.min(2, allDay.size()); idx++) {
                Kalender.Termin e = allDay.get(idx);
                int i0 = 0, i1 = rN;
                for (int i = 0; i < rN; i++) if (dayB[i] > e.startMs) { i0 = i; break; }
                for (int i = rN - 1; i >= 0; i--) if (dayA[i] < e.endeMs) { i1 = i + 1; break; }
                float bx = x0 + lw + i0 * cw + 4, bw = Math.max(20, (i1 - i0) * cw - 8);
                float by = y0 + hdrH + idx * 24;
                rf.set(bx, by, bx + bw, by + 21);
                p.setStyle(Paint.Style.FILL);
                p.setColor(WetterLage.alpha(e.farbe, .24f));
                c.drawRoundRect(rf, 7, 7, p);
                if (e.uid.equals(ausgewaehltUid)) {
                    linie.setStrokeWidth(1.5f); linie.setColor(e.farbe);
                    c.drawRoundRect(rf, 7, 7, linie);
                }
                float tx = bx + 6;
                if (e.muell) {
                    drawMiniTonne(c, tx, by + 3.5f, 14, e.farbe);
                    tx += 18;
                }
                String lbl = e.titel + (e.muell && e.erledigt ? " ✓" : "");
                t.setTypeface(tfMed); t.setTextSize(11.5f); t.setColor(e.farbe);
                c.drawText(kuerzen(t, lbl, Math.max(12, bx + bw - 6 - tx)), tx, by + 14.5f, t);
                hits.add(new Hit("ev:" + e.uid, rf.left, rf.top, rf.right, rf.bottom));
            }

            // Timed event cards per column
            boolean irgendein = !allDay.isEmpty();
            for (int i = 0; i < rN; i++) {
                List<long[]> raw = new ArrayList<>();
                List<Kalender.Termin> colEvs = new ArrayList<>();
                for (Kalender.Termin e : evs) {
                    if (!e.ganztag && e.startMs < dayB[i] && e.endeMs > dayA[i]) {
                        colEvs.add(e);
                        raw.add(new long[] {Math.max(e.startMs, dayA[i]), Math.min(e.endeMs, dayB[i]), 0});
                    }
                }
                if (colEvs.isEmpty()) continue;
                irgendein = true;
                List<Long> spEnde = new ArrayList<>();
                for (int k = 0; k < raw.size(); k++) {
                    long a = raw.get(k)[0], b = raw.get(k)[1];
                    int sp = 0;
                    while (sp < spEnde.size() && spEnde.get(sp) > a) sp++;
                    if (sp == spEnde.size()) spEnde.add(b);
                    else spEnde.set(sp, b);
                    raw.get(k)[2] = sp;
                }
                for (int k = 0; k < raw.size(); k++) {
                    long a = raw.get(k)[0], b = raw.get(k)[1];
                    int cIdx = (int) raw.get(k)[2], maxC = 1;
                    for (int m = 0; m < raw.size(); m++) {
                        if (raw.get(m)[0] < b && raw.get(m)[1] > a) maxC = Math.max(maxC, (int) raw.get(m)[2] + 1);
                    }
                    float subW = (cw - 8) / maxC;
                    float ex = x0 + lw + i * cw + 4 + cIdx * subW;
                    float ew = Math.max(18, subW - 2);
                    float ey = y0 + topOff + ((a - dayA[i]) / 3_600_000f - h0) * ph;
                    float eh = Math.max(22, (b - a) / 3_600_000f * ph - 2);
                    Kalender.Termin e = colEvs.get(k);
                    rf.set(ex, ey, ex + ew, ey + eh);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(WetterLage.alpha(e.farbe, .20f));
                    c.drawRoundRect(rf, 8, 8, p);
                    p.setColor(e.farbe);
                    c.drawRoundRect(ex, ey + 1, ex + 3.5f, ey + eh - 1, 2, 2, p);
                    if (e.uid.equals(ausgewaehltUid)) {
                        linie.setStrokeWidth(2f);
                        linie.setColor(e.farbe);
                        c.drawRoundRect(rf, 8, 8, linie);
                    }
                    float tx = ex + 8, maxTw = Math.max(10, ew - 12);
                    t.setTextSize(12);
                    if (eh < 34) {
                        t.setTypeface(tfMed); t.setColor(TXT);
                        String line = (ew > 94 ? Kalender.hm(e.startMs) + " " : "") + e.titel;
                        c.drawText(kuerzen(t, line, maxTw), tx, ey + eh * .5f + 4, t);
                    } else {
                        t.setTypeface(tfMed); t.setColor(TXT);
                        c.drawText(kuerzen(t, e.titel, maxTw), tx, ey + 15, t);
                        t.setTypeface(tfNorm); t.setColor(0xFFC3CCD5);
                        String sub = Kalender.hm(e.startMs) + (!e.ort.isEmpty() && eh > 52 ? " · " + e.ort : "");
                        c.drawText(kuerzen(t, sub, maxTw), tx, ey + 30, t);
                    }
                    hits.add(new Hit("ev:" + e.uid, rf.left, rf.top, rf.right, rf.bottom));
                }
            }

            // Live red current-time line on today's column
            for (int i = 0; i < rN; i++) {
                if (dayA[i] == t0) {
                    Calendar nc = Calendar.getInstance(); nc.setTimeInMillis(nowMs);
                    float ny = y0 + topOff + (nc.get(Calendar.HOUR_OF_DAY) + nc.get(Calendar.MINUTE) / 60f - h0) * ph;
                    if (ny >= y0 + topOff && ny <= y0 + h) {
                        float lx = x0 + lw + i * cw;
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0xFFF28B82);
                        c.drawRect(lx, ny - 1, lx + cw, ny + 1, p);
                        c.drawCircle(lx, ny, 5, p);
                    }
                    break;
                }
            }

            if (ereignisse == null || fehler != null || !irgendein) {
                String msg = fehler != null ? "Fehler: " + fehler : (ereignisse == null ? "Termine werden geladen …" : "Keine Termine in diesem Zeitraum");
                t.setTextAlign(Paint.Align.CENTER);
                t.setTypeface(tfNorm); t.setTextSize(15.5f); t.setColor(SUB);
                c.drawText(msg, W * .5f, y0 + h * .52f, t);
                t.setTextAlign(Paint.Align.LEFT);
            }
        }

        // ---- Month View (7 x 6 grid) -------------------------------------------------------------------------------

        private void zeichneMonat(Canvas c, Calendar rStart, long t0) {
            float x0 = 20, y0 = 90, w = W - 40, gap = 4;
            float cw = (w - gap * 6) / 7f, ch = 72;
            String[] hdr = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(tfNorm); t.setTextSize(12); t.setColor(SUB);
            for (int k = 0; k < 7; k++) c.drawText(hdr[k], x0 + k * (cw + gap) + cw * .5f, y0 + 12, t);
            t.setTextAlign(Paint.Align.LEFT);

            int zielMonat = basisTag.get(Calendar.MONTH);
            List<Kalender.Termin> evs = ereignisse != null ? ereignisse : Collections.emptyList();
            Calendar cur = (Calendar) rStart.clone();
            for (int idx = 0; idx < 42; idx++) {
                int row = idx / 7, col = idx % 7;
                float cx = x0 + col * (cw + gap), cy = y0 + 20 + row * (ch + gap);
                long dA = cur.getTimeInMillis();
                Calendar nxt = (Calendar) cur.clone(); nxt.add(Calendar.DAY_OF_MONTH, 1);
                long dB = nxt.getTimeInMillis();
                String dk = Kalender.datumKey(cur);
                boolean aus = cur.get(Calendar.MONTH) != zielMonat;
                boolean heute = dA == t0;
                boolean mark = markiert.contains(dk);

                rf.set(cx, cy, cx + cw, cy + ch);
                p.setStyle(Paint.Style.FILL);
                p.setColor(mark ? 0x228FC0FF : (heute ? 0x1FFFFFFF : 0x08FFFFFF));
                c.drawRoundRect(rf, 10, 10, p);
                if (mark) {
                    linie.setStrokeWidth(1.5f); linie.setColor(0x8C8FC0FF);
                    c.drawRoundRect(rf, 10, 10, linie);
                }

                float alpha = aus ? .32f : (dA < t0 ? .58f : 1f);
                String num = String.valueOf(cur.get(Calendar.DAY_OF_MONTH));
                t.setTypeface(heute || mark ? tfBold : tfNorm);
                t.setTextSize(13);
                t.setColor(WetterLage.alpha(TXT, alpha));
                c.drawText(num, cx + 7, cy + 17, t);

                String hol = konfig.mitFeiertage ? feiertage.get(dk) : null;
                if (hol != null && !hol.isEmpty()) {
                    float nw = t.measureText(num);
                    t.setTypeface(tfNorm); t.setTextSize(9.5f);
                    t.setColor(WetterLage.alpha(HOL_COL, alpha));
                    c.drawText(kuerzen(t, hol, cw - nw - 16), cx + 11 + nw, cy + 16, t);
                }

                int shown = 0, total = 0;
                for (Kalender.Termin e : evs) {
                    if (e.startMs < dB && e.endeMs > dA) {
                        total++;
                        if (shown < 2) {
                            float ey = cy + 24 + shown * 16;
                            float tx = cx + 13;
                            if (e.muell) {
                                drawMiniTonne(c, cx + 6, ey + 1, 10, WetterLage.alpha(e.farbe, alpha));
                                tx = cx + 19;
                            } else {
                                p.setColor(WetterLage.alpha(e.farbe, alpha));
                                c.drawRect(cx + 6, ey, cx + 9, ey + 12, p);
                            }
                            String lbl = e.titel + (e.muell && e.erledigt ? " ✓" : "");
                            t.setTypeface(tfNorm); t.setTextSize(11);
                            t.setColor(WetterLage.alpha(e.muell ? e.farbe : 0xFFC3CCD5, alpha));
                            c.drawText(kuerzen(t, lbl, cw - (tx - cx) - 5), tx, ey + 10, t);
                            shown++;
                        }
                    }
                }
                if (total > 2) {
                    t.setTextSize(10.5f); t.setColor(WetterLage.alpha(SUB, alpha));
                    c.drawText("+" + (total - 2), cx + 7, cy + 67, t);
                }
                hits.add(new Hit("tag:" + dA, cx, cy, cx + cw, cy + ch));
                cur = nxt;
            }
        }

        // ---- List / Agenda View (scrollable left column + detail right) --------------------------------------------

        private void zeichneListe(Canvas c, Calendar rStart, int rN, long t0, long nowMs) {
            float x0 = 30, y0 = 96, w = 460, h = H - y0 - 12;
            List<Kalender.Termin> evs = ereignisse != null ? ereignisse : Collections.emptyList();
            c.save();
            c.clipRect(x0 - 4, y0, x0 + w + 4, y0 + h);
            float y = y0 - listScrollY;
            int drawn = 0;
            Calendar cur = (Calendar) rStart.clone();
            for (int i = 0; i < rN; i++) {
                long dA = cur.getTimeInMillis();
                Calendar nxt = (Calendar) cur.clone(); nxt.add(Calendar.DAY_OF_MONTH, 1);
                long dB = nxt.getTimeInMillis();
                String dk = Kalender.datumKey(cur);
                boolean mark = markiert.contains(dk);
                String hol = konfig.mitFeiertage ? feiertage.get(dk) : null;
                List<Kalender.Termin> dayEvs = new ArrayList<>();
                for (Kalender.Termin e : evs) {
                    if (e.startMs < dB && e.endeMs > dA && (dA >= t0 || e.endeMs > nowMs)) dayEvs.add(e);
                }
                if (dayEvs.isEmpty() && !mark && (hol == null || hol.isEmpty())) {
                    cur = nxt;
                    continue;
                }
                if (drawn > 0) y += 10;
                String hdr = dA == t0 ? "HEUTE"
                    : (dA == t0 + 86_400_000L ? "MORGEN · " + datumKurz(cur).toUpperCase(Locale.GERMAN)
                    : datumLang(cur).toUpperCase(Locale.GERMAN));
                t.setTypeface(mark ? tfBold : tfMed); t.setTextSize(12.5f); t.setColor(mark ? 0xFF9CC8EE : SUB);
                c.drawText(hdr, x0 + 4, y + 14, t);
                if (hol != null && !hol.isEmpty()) {
                    float hw = t.measureText(hdr);
                    t.setTypeface(tfNorm); t.setColor(HOL_COL);
                    c.drawText(kuerzen(t, " · " + hol, w - hw - 16), x0 + 4 + hw, y + 14, t);
                }
                y += 22;

                if (dayEvs.isEmpty()) {
                    rf.set(x0, y, x0 + w, y + 40);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(mark ? 0x148FC0FF : 0x0AFFFFFF);
                    c.drawRoundRect(rf, 12, 12, p);
                    t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(SUB);
                    c.drawText("Keine Termine", x0 + 14, y + 25, t);
                    y += 48;
                    drawn++;
                } else {
                    for (Kalender.Termin e : dayEvs) {
                        float ch = e.ort.isEmpty() ? 46 : 60;
                        rf.set(x0, y, x0 + w, y + ch);
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(e.muell ? WetterLage.alpha(e.farbe, .16f) : BOX);
                        c.drawRoundRect(rf, 14, 14, p);
                        if (e.uid.equals(ausgewaehltUid)) {
                            linie.setStrokeWidth(2f); linie.setColor(e.farbe);
                            c.drawRoundRect(rf, 14, 14, linie);
                        }
                        if (e.muell) {
                            drawMiniTonne(c, x0 + 14, y + (ch - 22) * .5f, 22, e.farbe);
                            t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(e.farbe);
                            c.drawText("Müllabfuhr", x0 + 44, y + 28, t);
                        } else {
                            p.setColor(e.farbe);
                            c.drawRoundRect(x0 + 12, y + 9, x0 + 17, y + ch - 9, 2.5f, 2.5f, p);
                            t.setTypeface(tfNorm); t.setTextSize(14); t.setColor(0xFFC3CCD5);
                            c.drawText(e.zeit, x0 + 28, y + (e.ort.isEmpty() ? 28 : 26), t);
                        }
                        float maxTitW = w - 142;
                        if (e.muell && e.erledigt) {
                            t.setTypeface(tfMed); t.setTextSize(13); t.setColor(0xFF8FDCA3);
                            t.setTextAlign(Paint.Align.RIGHT);
                            c.drawText("✓", x0 + w - 14, y + 28, t);
                            t.setTextAlign(Paint.Align.LEFT);
                            maxTitW -= 24;
                        }
                        t.setTypeface(tfMed); t.setTextSize(16); t.setColor(TXT);
                        c.drawText(kuerzen(t, e.titel, maxTitW), x0 + 124, y + (e.ort.isEmpty() ? 28 : 25), t);
                        if (!e.ort.isEmpty()) {
                            t.setTypeface(tfNorm); t.setTextSize(12.5f); t.setColor(SUB);
                            c.drawText(kuerzen(t, e.ort, w - 142), x0 + 124, y + 45, t);
                        }
                        if (y + ch > y0 && y < y0 + h) hits.add(new Hit("ev:" + e.uid, x0, Math.max(y0, y), x0 + w, Math.min(y0 + h, y + ch)));
                        y += ch + 7;
                        drawn++;
                    }
                }
                cur = nxt;
            }
            listMaxScroll = Math.max(0, (y + listScrollY) - (y0 + h) + 24);
            if (drawn == 0) {
                t.setTypeface(tfNorm); t.setTextSize(16); t.setColor(SUB);
                c.drawText(ereignisse == null ? "Termine werden geladen …" : "Keine Termine in den nächsten " + rN + " Tagen.", x0 + 4, y0 + 36, t);
            }
            c.restore();
        }

        // ---- Event Detail Overlay Card -----------------------------------------------------------------------------

        private void zeichneDetail(Canvas c, Kalender.Termin e, long t0, long nowMs) {
            float x = "liste".equals(modus) ? 510 : 480, y = 96, w = W - 30 - x, h = 420;
            rf.set(x, y, x + w, y + h);
            p.setStyle(Paint.Style.FILL);
            if (!"liste".equals(modus)) {
                p.setColor(0x66000000);
                c.drawRoundRect(x + 2, y + 10, x + w + 2, y + h + 12, 20, 20, p);
            }
            p.setColor(DETAIL_BG);
            c.drawRoundRect(rf, 20, 20, p);
            linie.setStrokeWidth(1f); linie.setColor(0x1EFFFFFF);
            c.drawRoundRect(rf, 20, 20, linie);

            // Close button
            p.setColor(0x14FFFFFF);
            c.drawCircle(x + w - 34, y + 34, 20, p);
            drawIcon(c, "mdi:close", x + w - 44, y + 24, 20, TXT);
            hits.add(new Hit("zu", x + w - 58, y + 10, x + w - 10, y + 58));

            // Calendar / waste bin dot & name
            if (e.muell) {
                drawMiniTonne(c, x + 22, y + 20, 18, e.farbe);
                t.setTypeface(tfMed); t.setTextSize(13.5f); t.setColor(e.farbe);
                c.drawText(e.kalender.isEmpty() ? "Müllabfuhr" : e.kalender, x + 46, y + 34, t);
            } else {
                p.setColor(e.farbe);
                c.drawCircle(x + 27, y + 30, 5, p);
                t.setTypeface(tfMed); t.setTextSize(13.5f); t.setColor(e.farbe);
                c.drawText(e.kalender, x + 40, y + 35, t);
            }

            // Title (up to 2 lines)
            t.setTypeface(tfMed); t.setTextSize(25); t.setColor(TXT);
            float ty = zeichneZeilen(c, e.titel, t, x + 22, y + 72, w - 82, 31, 2);

            // Time line
            ty += 26;
            drawIcon(c, "mdi:clock-outline", x + 22, ty - 14, 18, SUB);
            Calendar sc = Calendar.getInstance(); sc.setTimeInMillis(e.startMs);
            String tagStr = e.startMs >= t0 && e.startMs < t0 + 86_400_000L ? "Heute"
                : (e.startMs >= t0 + 86_400_000L && e.startMs < t0 + 172_800_000L ? "Morgen" : datumKurz(sc));
            String zeitInfo = tagStr + ", " + e.zeit;
            long vor = e.ganztag ? 0 : e.startMs - nowMs;
            if (vor > 0 && vor < 86_400_000L) {
                long m = Math.round(vor / 60_000.0);
                zeitInfo += " · in " + (m < 60 ? m + " min" : (m / 60) + ":" + String.format(Locale.ROOT, "%02d", m % 60) + " h");
            }
            t.setTypeface(tfNorm); t.setTextSize(16); t.setColor(0xFFC3CCD5);
            c.drawText(kuerzen(t, zeitInfo, w - 70), x + 48, ty, t);

            // Location
            if (!e.ort.isEmpty()) {
                ty += 30;
                drawIcon(c, "mdi:map-marker-outline", x + 22, ty - 14, 18, SUB);
                c.drawText(kuerzen(t, e.ort, w - 70), x + 48, ty, t);
            }

            // Description
            if (!e.beschreibung.isEmpty()) {
                ty += 32;
                t.setTextSize(14.5f); t.setColor(SUB);
                zeichneZeilen(c, e.beschreibung.replace('\n', ' '), t, x + 22, ty, w - 44, 21, 5);
            }

            // Bottom action pill: "Steht draußen ✓" for waste pickups OR "15 min vorher erinnern"
            if (e.muell) {
                float py = y + h - 58, px = x + 22;
                String lbl = e.erledigt ? "Steht draußen ✓" : "Als draußen markieren";
                t.setTypeface(tfMed); t.setTextSize(14.5f);
                float pw = 16 + 19 + 8 + t.measureText(lbl) + 16;
                rf.set(px, py, px + pw, py + 40);
                p.setStyle(Paint.Style.FILL);
                p.setColor(e.erledigt ? 0x2E6CC483 : 0x12FFFFFF);
                c.drawRoundRect(rf, 16, 16, p);
                drawIcon(c, e.erledigt ? "mdi:check-circle" : "mdi:check", px + 14, py + 10.5f, 19, e.erledigt ? 0xFF8FDCA3 : e.farbe);
                t.setColor(e.erledigt ? 0xFF8FDCA3 : TXT);
                c.drawText(lbl, px + 41, py + 25, t);
                hits.add(new Hit("muell_erl", rf.left, rf.top, rf.right, rf.bottom));
            } else if (vor > 16 * 60_000L) {
                boolean er = erinnertUids.contains(e.uid);
                float py = y + h - 58, px = x + 22;
                String lbl = er ? ("Erinnerung um " + Kalender.hm(e.startMs - 900_000L)) : "15 min vorher erinnern";
                t.setTypeface(tfMed); t.setTextSize(14.5f);
                float pw = 16 + 19 + 8 + t.measureText(lbl) + 16;
                rf.set(px, py, px + pw, py + 40);
                p.setStyle(Paint.Style.FILL);
                p.setColor(er ? 0x2E6CC483 : 0x12FFFFFF);
                c.drawRoundRect(rf, 16, 16, p);
                drawIcon(c, er ? "mdi:bell-check-outline" : "mdi:bell-outline", px + 14, py + 10.5f, 19, er ? 0xFF8FDCA3 : 0xFFF2B45C);
                t.setColor(TXT);
                c.drawText(lbl, px + 41, py + 25, t);
                hits.add(new Hit("erin", rf.left, rf.top, rf.right, rf.bottom));
            }
        }

        // ---- Date Picker Modal -------------------------------------------------------------------------------------

        private void zeichnePicker(Canvas c, long rA, long rB, long t0) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x8C05090C);
            c.drawRect(0, 84, W, H, p);
            hits.add(new Hit("pickzu", 0, 84, W, H));

            float mx = 102, my = 86, mw = 470, mh = 398;
            rf.set(mx, my, mx + mw, my + mh);
            p.setColor(0xFF1F2A35);
            c.drawRoundRect(rf, 20, 20, p);
            linie.setStrokeWidth(1f); linie.setColor(0x1EFFFFFF);
            c.drawRoundRect(rf, 20, 20, linie);
            hits.add(new Hit("pick_card", mx, my, mx + mw, my + mh));

            Calendar m1 = (Calendar) pickMonat.clone();
            m1.set(Calendar.DAY_OF_MONTH, 1);
            Kalender.mitternacht(m1);
            int targetMonth = m1.get(Calendar.MONTH);
            Calendar a = Kalender.montag(m1);

            // Header: < Month Year >
            float hy = my + 16;
            drawIcon(c, "mdi:chevron-left", mx + 16, hy, 24, SUB);
            hits.add(new Hit("pm:-1", mx + 8, hy - 6, mx + 48, hy + 30));
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(tfMed); t.setTextSize(17); t.setColor(TXT);
            c.drawText(MON[targetMonth] + " " + m1.get(Calendar.YEAR), mx + mw * .5f, hy + 18, t);
            drawIcon(c, "mdi:chevron-right", mx + mw - 40, hy, 24, SUB);
            hits.add(new Hit("pm:1", mx + mw - 48, hy - 6, mx + mw - 8, hy + 30));

            // Grid header: KW Mo Di Mi Do Fr Sa So
            float gx = mx + 18, gy = my + 56, kwW = 42, gap = 3;
            float cw = (mw - 36 - kwW - gap * 7) / 7f, ch = 39;
            t.setTypeface(tfNorm); t.setTextSize(12); t.setColor(SUB);
            c.drawText("KW", gx + kwW * .5f, gy + 12, t);
            String[] hdr = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
            for (int k = 0; k < 7; k++) {
                c.drawText(hdr[k], gx + kwW + gap + k * (cw + gap) + cw * .5f, gy + 12, t);
            }

            List<Kalender.Termin> evs = ereignisse != null ? ereignisse : Collections.emptyList();
            Calendar cur = (Calendar) a.clone();
            float ry0 = gy + 20;
            for (int w = 0; w < 6; w++) {
                long ws = cur.getTimeInMillis();
                Calendar weCal = (Calendar) cur.clone(); weCal.add(Calendar.DAY_OF_MONTH, 7);
                long we = weCal.getTimeInMillis();
                float ry = ry0 + w * (ch + gap);
                boolean drin = rA < we && rB > ws && !"monat".equals(modus) && !"liste".equals(modus);
                if (drin) {
                    rf.set(gx, ry, mx + mw - 18, ry + ch);
                    p.setColor(0x593182B7);
                    c.drawRoundRect(rf, 10, 10, p);
                }

                // KW cell
                t.setTypeface(tfMed); t.setTextSize(12);
                t.setColor(drin ? 0xFFFFFFFF : 0xFF6FB3F2);
                c.drawText(String.valueOf(cur.get(Calendar.WEEK_OF_YEAR)), gx + kwW * .5f, ry + 24, t);
                hits.add(new Hit("woche:" + ws, gx, ry, gx + kwW, ry + ch));

                for (int k = 0; k < 7; k++) {
                    long dA = cur.getTimeInMillis();
                    Calendar nxt = (Calendar) cur.clone(); nxt.add(Calendar.DAY_OF_MONTH, 1);
                    long dB = nxt.getTimeInMillis();
                    float cx = gx + kwW + gap + k * (cw + gap);
                    float alpha = cur.get(Calendar.MONTH) != targetMonth ? .35f : 1f;
                    if (dA == t0) {
                        rf.set(cx + 1, ry + 1, cx + cw - 1, ry + ch - 1);
                        linie.setStrokeWidth(2f); linie.setColor(TXT);
                        c.drawRoundRect(rf, 9, 9, linie);
                    }
                    t.setTypeface(dA == t0 ? tfBold : tfNorm); t.setTextSize(14);
                    t.setColor(WetterLage.alpha(TXT, alpha));
                    c.drawText(String.valueOf(cur.get(Calendar.DAY_OF_MONTH)), cx + cw * .5f, ry + 21, t);

                    // Up to 3 dots
                    int dots = 0;
                    for (Kalender.Termin e : evs) {
                        if (e.startMs < dB && e.endeMs > dA) {
                            if (dots < 3) {
                                p.setColor(WetterLage.alpha(e.farbe, alpha));
                                c.drawCircle(cx + cw * .5f + (dots - 1) * 7f, ry + 31, 2.5f, p);
                                dots++;
                            } else break;
                        }
                    }
                    hits.add(new Hit("ptag:" + dA, cx, ry, cx + cw, ry + ch));
                    cur = nxt;
                }
            }
            t.setTextAlign(Paint.Align.LEFT);

            // Quick jump pills
            String[] qk = {"heute", "diese", "naechste", "monat"};
            String[] ql = {"Heute", "Diese Woche", "Nächste Woche", "Nächster Monat"};
            float qx = mx + 18, qy = my + mh - 46;
            t.setTypeface(tfNorm); t.setTextSize(13);
            for (int i = 0; i < qk.length; i++) {
                float pw = t.measureText(ql[i]) + 22;
                rf.set(qx, qy, qx + pw, qy + 30);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF2A3133);
                c.drawRoundRect(rf, 9, 9, p);
                t.setColor(0xFFC3CCD5);
                c.drawText(ql[i], qx + 11, qy + 20, t);
                hits.add(new Hit("schnell:" + qk[i], rf.left, rf.top, rf.right, rf.bottom));
                qx += pw + 8;
            }
        }

        // ---- "+ Termin" Modal --------------------------------------------------------------------------------------

        private void zeichneNeuModal(Canvas c, long t0) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xB805090C);
            c.drawRect(0, 0, W, H, p);
            hits.add(new Hit("neuzu", 0, 0, W, H));

            float mx = 131, my = 60, mw = 700, mh = 340;
            rf.set(mx, my, mx + mw, my + mh);
            p.setColor(DETAIL_BG);
            c.drawRoundRect(rf, 22, 22, p);
            linie.setStrokeWidth(1f); linie.setColor(0x1EFFFFFF);
            c.drawRoundRect(rf, 22, 22, linie);
            hits.add(new Hit("neu_card", mx, my, mx + mw, my + mh));

            t.setTextAlign(Paint.Align.LEFT);
            t.setTypeface(tfMed); t.setTextSize(18); t.setColor(TXT);
            String hdr = "Neuer Termin" + (konfig.schreibenName.isEmpty() ? "" : " · " + konfig.schreibenName);
            c.drawText(kuerzen(t, hdr, mw - 44), mx + 22, my + 38, t);

            // Row 1 (y = 114..166) is the real Android EditText overlay!

            // Row 2: Date quick chips + stepper
            float dyRow = 182;
            t.setTypeface(tfNorm); t.setTextSize(15);
            String[] dLabels = {"Heute", "Morgen", "Übermorgen"};
            long neuMs = neuDatum.getTimeInMillis();
            float cx = mx + 22;
            for (int i = 0; i < 3; i++) {
                long candMs = t0 + i * 86_400_000L;
                boolean sel = Math.abs(neuMs - candMs) < 43_200_000L;
                float pw = t.measureText(dLabels[i]) + 26;
                rf.set(cx, dyRow, cx + pw, dyRow + 40);
                p.setColor(sel ? 0xFF3182B7 : 0x12FFFFFF);
                c.drawRoundRect(rf, 12, 12, p);
                t.setColor(sel ? 0xFFFFFFFF : TXT);
                c.drawText(dLabels[i], cx + 13, dyRow + 25, t);
                hits.add(new Hit("nd:" + candMs, rf.left, rf.top, rf.right, rf.bottom));
                cx += pw + 10;
            }
            // Date stepper (< dd.MM.yyyy >)
            rf.set(cx, dyRow, cx + 190, dyRow + 40);
            p.setColor(0x12FFFFFF);
            c.drawRoundRect(rf, 12, 12, p);
            drawIcon(c, "mdi:chevron-left", cx + 8, dyRow + 10, 20, SUB);
            hits.add(new Hit("nd_step:-1", cx, dyRow, cx + 36, dyRow + 40));
            String dStr = String.format(Locale.ROOT, "%02d.%02d.%04d",
                neuDatum.get(Calendar.DAY_OF_MONTH), neuDatum.get(Calendar.MONTH) + 1, neuDatum.get(Calendar.YEAR));
            t.setTextAlign(Paint.Align.CENTER); t.setColor(TXT);
            c.drawText(dStr, cx + 95, dyRow + 25, t);
            t.setTextAlign(Paint.Align.LEFT);
            drawIcon(c, "mdi:chevron-right", cx + 162, dyRow + 10, 20, SUB);
            hits.add(new Hit("nd_step:1", cx + 154, dyRow, cx + 190, dyRow + 40));

            // Row 3: Time stepper + Duration chips
            float zyRow = 236;
            cx = mx + 22;
            if (neuDauerMin > 0) {
                rf.set(cx, zyRow, cx + 146, zyRow + 40);
                p.setColor(0x12FFFFFF);
                c.drawRoundRect(rf, 12, 12, p);
                drawIcon(c, "mdi:chevron-left", cx + 6, zyRow + 10, 20, SUB);
                hits.add(new Hit("nt_step:-15", cx, zyRow, cx + 36, zyRow + 40));
                String zStr = String.format(Locale.ROOT, "%02d:%02d", neuStunde, neuMinute);
                t.setTextAlign(Paint.Align.CENTER); t.setTypeface(tfMed); t.setColor(TXT);
                c.drawText(zStr, cx + 73, zyRow + 25, t);
                t.setTextAlign(Paint.Align.LEFT); t.setTypeface(tfNorm);
                hits.add(new Hit("nt_pick", cx + 36, zyRow, cx + 110, zyRow + 40));
                drawIcon(c, "mdi:chevron-right", cx + 120, zyRow + 10, 20, SUB);
                hits.add(new Hit("nt_step:15", cx + 110, zyRow, cx + 146, zyRow + 40));
                cx += 158;
            }
            int[] durs = {0, 30, 60, 120};
            String[] durLbls = {"ganztägig", "30 min", "1 h", "2 h"};
            for (int i = 0; i < durs.length; i++) {
                boolean sel = neuDauerMin == durs[i];
                float pw = t.measureText(durLbls[i]) + 26;
                rf.set(cx, zyRow, cx + pw, zyRow + 40);
                p.setColor(sel ? 0xFF3182B7 : 0x12FFFFFF);
                c.drawRoundRect(rf, 12, 12, p);
                t.setColor(sel ? 0xFFFFFFFF : TXT);
                c.drawText(durLbls[i], cx + 13, zyRow + 25, t);
                hits.add(new Hit("nl:" + durs[i], rf.left, rf.top, rf.right, rf.bottom));
                cx += pw + 10;
            }

            // Bottom buttons: Abbrechen + Eintragen
            float by = my + mh - 62;
            t.setTypeface(tfMed); t.setTextSize(16);
            String okTxt = "Eintragen";
            float okW = 20 + 18 + 8 + t.measureText(okTxt) + 20;
            float okX = mx + mw - 22 - okW;
            rf.set(okX, by, okX + okW, by + 44);
            p.setColor(0xFF3182B7);
            c.drawRoundRect(rf, 16, 16, p);
            drawIcon(c, "mdi:check", okX + 18, by + 13, 18, 0xFFFFFFFF);
            t.setColor(0xFFFFFFFF);
            c.drawText(okTxt, okX + 44, by + 28, t);
            hits.add(new Hit("neuok", rf.left, rf.top, rf.right, rf.bottom));

            float abW = t.measureText("Abbrechen") + 36;
            float abX = okX - 12 - abW;
            rf.set(abX, by, abX + abW, by + 44);
            p.setColor(0x12FFFFFF);
            c.drawRoundRect(rf, 16, 16, p);
            t.setColor(TXT);
            c.drawText("Abbrechen", abX + 18, by + 28, t);
            hits.add(new Hit("neuzu", rf.left, rf.top, rf.right, rf.bottom));
        }

        // ---- Touch & Navigation ------------------------------------------------------------------------------------

        @Override public boolean onTouchEvent(MotionEvent e) {
            try {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getX(); downY = e.getY(); lastY = downY; ziehend = false;
                } else if (a == MotionEvent.ACTION_MOVE && "liste".equals(modus) && !pickOffen && !neuOffen && listMaxScroll > 0) {
                    float dyPx = (lastY - e.getY()) / skala;
                    if (Math.abs(e.getY() - downY) > 12) ziehend = true;
                    if (ziehend) {
                        listScrollY = Math.max(0, Math.min(listMaxScroll, listScrollY + dyPx));
                        lastY = e.getY();
                        invalidate();
                    }
                } else if (a == MotionEvent.ACTION_UP && !ziehend && Math.hypot(e.getX() - downX, e.getY() - downY) < 28) {
                    float vx = (e.getX() - dx) / skala, vy = (e.getY() - dy) / skala;
                    for (int i = hits.size() - 1; i >= 0; i--) {
                        Hit h = hits.get(i);
                        if (h.trifft(vx, vy)) {
                            performClick();
                            ausfuehren(h.id);
                            return true;
                        }
                    }
                }
            } catch (RuntimeException ignored) {}
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }

        private void ausfuehren(String id) {
            if ("zurueck".equals(id)) { aktionen.zurueck(); return; }
            if ("zu".equals(id)) { ausgewaehltUid = null; invalidate(); return; }
            if ("pick_card".equals(id) || "neu_card".equals(id)) return;
            if ("pick".equals(id)) {
                pickOffen = !pickOffen;
                pickMonat = (Calendar) ("monat".equals(modus) ? basisTag : sichtStart()).clone();
                invalidate();
                return;
            }
            if ("pickzu".equals(id)) { pickOffen = false; invalidate(); return; }
            if (id.startsWith("pm:")) {
                int dir = Integer.parseInt(id.substring(3));
                pickMonat.set(Calendar.DAY_OF_MONTH, 1);
                pickMonat.add(Calendar.MONTH, dir);
                invalidate();
                return;
            }
            if (id.startsWith("woche:")) {
                basisTag.setTimeInMillis(Long.parseLong(id.substring(6)));
                Kalender.mitternacht(basisTag);
                if (konfig.ansichten.contains("woche")) modus = "woche";
                pickOffen = false;
                ausgewaehltUid = null;
                listScrollY = 0;
                neuLaden();
                return;
            }
            if (id.startsWith("ptag:")) {
                basisTag.setTimeInMillis(Long.parseLong(id.substring(5)));
                Kalender.mitternacht(basisTag);
                if (konfig.ansichten.contains("tag")) modus = "tag";
                pickOffen = false;
                ausgewaehltUid = null;
                listScrollY = 0;
                neuLaden();
                return;
            }
            if (id.startsWith("schnell:")) {
                String q = id.substring(8);
                Calendar c = Kalender.mitternacht(Calendar.getInstance());
                if ("naechste".equals(q)) {
                    c = Kalender.montag(c);
                    c.add(Calendar.DAY_OF_MONTH, 7);
                    if (konfig.ansichten.contains("woche")) modus = "woche";
                } else if ("monat".equals(q)) {
                    c.set(Calendar.DAY_OF_MONTH, 1);
                    c.add(Calendar.MONTH, 1);
                    if (konfig.ansichten.contains("monat")) modus = "monat";
                } else if ("diese".equals(q)) {
                    if (konfig.ansichten.contains("woche")) modus = "woche";
                }
                basisTag = c;
                pickOffen = false;
                ausgewaehltUid = null;
                listScrollY = 0;
                neuLaden();
                return;
            }
            if ("plus".equals(id)) { neuModalOeffnen(); return; }
            if ("neuzu".equals(id)) { neuModalSchliessen(); return; }
            if ("neuok".equals(id)) { neuTerminEintragen(); return; }
            if (id.startsWith("nd:")) {
                neuDatum.setTimeInMillis(Long.parseLong(id.substring(3)));
                Kalender.mitternacht(neuDatum);
                invalidate();
                return;
            }
            if (id.startsWith("nd_step:")) {
                neuDatum.add(Calendar.DAY_OF_MONTH, Integer.parseInt(id.substring(8)));
                invalidate();
                return;
            }
            if (id.startsWith("nt_step:")) {
                int delta = Integer.parseInt(id.substring(8));
                int tot = ((neuStunde * 60 + neuMinute + delta) % 1440 + 1440) % 1440;
                neuStunde = tot / 60;
                neuMinute = tot % 60;
                invalidate();
                return;
            }
            if ("nt_pick".equals(id)) {
                try {
                    new TimePickerDialog(getContext(), (view, hourOfDay, minute) -> {
                        neuStunde = hourOfDay;
                        neuMinute = minute;
                        invalidate();
                    }, neuStunde, neuMinute, true).show();
                } catch (RuntimeException ignored) {}
                return;
            }
            if (id.startsWith("nl:")) {
                neuDauerMin = Integer.parseInt(id.substring(3));
                invalidate();
                return;
            }
            if ("erin".equals(id) && ausgewaehltUid != null && ereignisse != null) {
                for (Kalender.Termin e : ereignisse) {
                    if (ausgewaehltUid.equals(e.uid)) {
                        erinnertUids.add(e.uid);
                        Calendar at = Calendar.getInstance();
                        at.setTimeInMillis(e.startMs - 900_000L);
                        aktionen.erinnern(e.titel, Kalender.iso(at), Kalender.hm(e.startMs));
                        invalidate();
                        return;
                    }
                }
                return;
            }
            if ("muell_erl".equals(id) && ausgewaehltUid != null && ereignisse != null) {
                for (Kalender.Termin e : ereignisse) {
                    if (ausgewaehltUid.equals(e.uid) && e.muell) {
                        boolean neu = !e.erledigt;
                        String dk = e.datumKey.isEmpty() ? e.datum : e.datumKey;
                        for (Kalender.Termin m : ereignisse) {
                            if (m.muell && dk.equals(m.datumKey.isEmpty() ? m.datum : m.datumKey)) {
                                m.erledigt = neu;
                            }
                        }
                        aktionen.muellErledigt(dk, neu);
                        invalidate();
                        return;
                    }
                }
                return;
            }
            if (id.startsWith("ev:")) {
                String uid = id.substring(3);
                ausgewaehltUid = uid.equals(ausgewaehltUid) ? null : uid;
                invalidate();
                return;
            }
            if (id.startsWith("v:")) {
                modus = id.substring(2);
                ausgewaehltUid = null;
                listScrollY = 0;
                neuLaden();
                return;
            }
            if ("heute".equals(id)) {
                basisTag = Kalender.mitternacht(Calendar.getInstance());
                ausgewaehltUid = null;
                listScrollY = 0;
                neuLaden();
                return;
            }
            if (id.startsWith("nav:")) {
                int dir = Integer.parseInt(id.substring(4));
                if ("monat".equals(modus)) {
                    basisTag.set(Calendar.DAY_OF_MONTH, 1);
                    basisTag.add(Calendar.MONTH, dir);
                } else {
                    basisTag.add(Calendar.DAY_OF_MONTH, dir * sichtTage());
                }
                ausgewaehltUid = null;
                listScrollY = 0;
                neuLaden();
                return;
            }
            if (id.startsWith("tag:")) {
                long ms = Long.parseLong(id.substring(4));
                basisTag.setTimeInMillis(ms);
                Kalender.mitternacht(basisTag);
                if (konfig.ansichten.contains("tag")) modus = "tag";
                ausgewaehltUid = null;
                neuLaden();
            }
        }

        // ---- Formatting & icons ------------------------------------------------------------------------------------

        private void drawMiniTonne(Canvas c, float x, float y, float sz, int farbe) {
            c.save();
            c.translate(x, y);
            c.scale(sz / 24f, sz / 24f);
            linie.setColor(farbe);
            linie.setStrokeWidth(1.8f);
            c.drawRoundRect(8.5f, 1.8f, 15.5f, 4.4f, 1.2f, 1.2f, linie);
            p.setStyle(Paint.Style.FILL);
            p.setColor(farbe);
            c.drawRoundRect(3.2f, 4.2f, 20.8f, 7.2f, 1.5f, 1.5f, p);
            binPath.reset();
            binPath.moveTo(4.6f, 7.8f);
            binPath.lineTo(19.4f, 7.8f);
            binPath.lineTo(17.7f, 21.4f);
            binPath.quadTo(17.5f, 22.4f, 16.4f, 22.4f);
            binPath.lineTo(7.6f, 22.4f);
            binPath.quadTo(6.5f, 22.4f, 6.3f, 21.4f);
            binPath.close();
            c.drawPath(binPath, p);
            c.restore();
        }

        private String zeitraumLabel(Calendar rStart, int rN) {
            if ("monat".equals(modus)) return MON[basisTag.get(Calendar.MONTH)] + " " + basisTag.get(Calendar.YEAR);
            if (rN == 1) return datumLang(rStart);
            Calendar b = (Calendar) rStart.clone();
            b.add(Calendar.DAY_OF_MONTH, rN - 1);
            String kw = "woche".equals(modus) ? "KW " + rStart.get(Calendar.WEEK_OF_YEAR) + " · " : "";
            String aStr = rStart.get(Calendar.DAY_OF_MONTH) + "." + (rStart.get(Calendar.MONTH) != b.get(Calendar.MONTH) ? " " + MON[rStart.get(Calendar.MONTH)] : "");
            return kw + aStr + " – " + b.get(Calendar.DAY_OF_MONTH) + ". " + MON[b.get(Calendar.MONTH)];
        }

        private String datumLang(Calendar c) {
            return WTL[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH) + ". " + MON[c.get(Calendar.MONTH)];
        }

        private String datumKurz(Calendar c) {
            return WT[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH) + ". " + MON[c.get(Calendar.MONTH)];
        }

        private float zeichneZeilen(Canvas c, String s, Paint pt, float x, float y, float maxW, float lh, int maxZ) {
            String rest = s == null ? "" : s.trim();
            float cy = y;
            for (int z = 0; z < maxZ && !rest.isEmpty(); z++) {
                if (z == maxZ - 1 || pt.measureText(rest) <= maxW) {
                    c.drawText(kuerzen(pt, rest, maxW), x, cy, pt);
                    return cy;
                }
                int cut = rest.length();
                while (cut > 0 && pt.measureText(rest.substring(0, cut)) > maxW) cut--;
                int sp = rest.lastIndexOf(' ', cut);
                if (sp > 4) cut = sp;
                c.drawText(rest.substring(0, cut).trim(), x, cy, pt);
                rest = rest.substring(cut).trim();
                cy += lh;
            }
            return cy;
        }

        private void drawIcon(Canvas c, String name, float x, float y, float size, int color) {
            Path path = iconPath(name);
            if (path != null) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(color);
                c.save();
                c.translate(x, y);
                c.scale(size / 24f, size / 24f);
                c.drawPath(path, p);
                c.restore();
                return;
            }
            c.save();
            c.translate(x, y);
            c.scale(size / 24f, size / 24f);
            linie.setColor(color); linie.setStrokeWidth(2.2f);
            iconTmp.reset();
            switch (name) {
                case "mdi:chevron-left":
                    iconTmp.moveTo(15, 6); iconTmp.lineTo(9, 12); iconTmp.lineTo(15, 18);
                    c.drawPath(iconTmp, linie);
                    break;
                case "mdi:chevron-right":
                    iconTmp.moveTo(9, 6); iconTmp.lineTo(15, 12); iconTmp.lineTo(9, 18);
                    c.drawPath(iconTmp, linie);
                    break;
                case "mdi:menu-down":
                    p.setStyle(Paint.Style.FILL); p.setColor(color);
                    iconTmp.moveTo(7, 10); iconTmp.lineTo(12, 15); iconTmp.lineTo(17, 10); iconTmp.close();
                    c.drawPath(iconTmp, p);
                    break;
                case "mdi:menu-up":
                    p.setStyle(Paint.Style.FILL); p.setColor(color);
                    iconTmp.moveTo(7, 14); iconTmp.lineTo(12, 9); iconTmp.lineTo(17, 14); iconTmp.close();
                    c.drawPath(iconTmp, p);
                    break;
                case "mdi:plus":
                    c.drawLine(12, 5, 12, 19, linie);
                    c.drawLine(5, 12, 19, 12, linie);
                    break;
                case "mdi:check":
                    iconTmp.moveTo(5, 12.5f); iconTmp.lineTo(9.5f, 17); iconTmp.lineTo(19, 7.5f);
                    c.drawPath(iconTmp, linie);
                    break;
                case "mdi:close":
                    c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, linie);
                    c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, linie);
                    break;
                case "mdi:clock-outline":
                    c.drawCircle(12, 12, 8, linie);
                    c.drawLine(12, 12, 12, 7.5f, linie);
                    c.drawLine(12, 12, 15.5f, 13.5f, linie);
                    break;
                default:
                    c.drawCircle(12, 10, 4.5f, linie);
                    c.drawLine(12, 14.5f, 12, 20, linie);
                    break;
            }
            c.restore();
        }

        private Path iconPath(String name) {
            if (theme == null || name == null) return null;
            if (iconCache.containsKey(name)) return iconCache.get(name);
            Path found = null;
            try { found = theme.iconPath(name); } catch (RuntimeException | LinkageError ignored) {}
            iconCache.put(name, found);
            return found;
        }

        private String kuerzen(Paint pt, String s, float maxW) {
            if (s == null || s.isEmpty() || pt.measureText(s) <= maxW) return s == null ? "" : s;
            int lo = 0, hi = s.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (pt.measureText(s.substring(0, mid) + "…") <= maxW) lo = mid;
                else hi = mid - 1;
            }
            return lo <= 0 ? "…" : s.substring(0, lo) + "…";
        }
    }
}
