package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Native full-screen Shopping List view matching einkaufsliste.py on the 963 x 584 dashboard grid:
 * - Three visual styles: "block" (cream spiral lined notepad with red margin + Caveat font),
 *   "zettel" (rotated yellow sticky note with tape + Caveat font), and "schlicht" (dark green cards).
 * - Multi-list tabs when > 1 list is configured; per-list live todo/item/list fetch via HA WebSocket.
 * - Check/uncheck honoring aufraeumen ("sofort" / "nachts" / "hand") + echo_show/einkauf/merken,
 *   "Abgehakte wegradieren" / "Abgehakte entfernen", real Android EditText + "+" button, and
 *   "Nochmal kaufen" suggestion chips (tap = add, long-press = echo_show/einkauf/vergessen).
 */
final class EinkaufslisteView extends FrameLayout {
    static final float W = 963f, H = 584f;

    interface Aktionen {
        void zurueck();
        void ha(Map<String, Object> msg, Antwort cb);
        interface Antwort {
            void fertig(boolean ok, Object data, String err);
        }
    }

    private static final class ListeInfo {
        final String id;
        final String name;
        ListeInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Eintrag {
        String uid = "";
        String summary = "";
        String status = "needs_action";
        Eintrag(String uid, String summary, String status) {
            this.uid = uid;
            this.summary = summary;
            this.status = status;
        }
        boolean fertig() { return "completed".equals(status); }
    }

    private static final class Hit {
        final String aktion, wert;
        final float l, t, r, b;
        Hit(String aktion, String wert, float l, float t, float r, float b) {
            this.aktion = aktion;
            this.wert = wert;
            this.l = l; this.t = t; this.r = r; this.b = b;
        }
        boolean trifft(float x, float y) { return x >= l && x <= r && y >= t && y <= b; }
    }

    private static final int[] TAB_FARBEN = {0xFFF2D66B, 0xFFBFDCEF, 0xFFD8EDA4, 0xFFF9D9A0};
    private static final int[] CHIP_FARBEN = {0xFFFBE38A, 0xFFF9D9A0, 0xFFD8EDA4};
    private static final float[] CHIP_ROT = {-2.5f, 1.8f, -0.8f};

    private final KsTheme theme;
    private final Aktionen aktionen;
    private final Typeface tfHand, tfHandBold, tfNorm, tfMed;
    private final CanvasView canvasView;
    private final EditText eingabe;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<ListeInfo> listen = new ArrayList<>();
    private final Map<String, List<Eintrag>> itemsByList = new LinkedHashMap<>();
    private final Set<String> ladend = new HashSet<>();
    private final List<String> verlauf = new ArrayList<>();

    private String aktivListeId = "";
    private String initListe = "";
    private String stil = "block";          // "block" | "zettel" | "schlicht"
    private boolean vorschlaege = true;
    private String aufraeumen = "nachts";   // "sofort" | "nachts" | "hand"
    private String fehler;
    private float listScrollY, listMaxScroll;
    private float skala = 1f, dx, dy;

    EinkaufslisteView(Context ctx, KsTheme theme, String initListe, Typeface handschrift, Aktionen aktionen) {
        super(ctx);
        this.theme = theme;
        this.aktionen = aktionen;
        this.initListe = initListe == null ? "" : initListe.trim();
        Typeface baseHand = handschrift != null ? handschrift : Typeface.create(Typeface.SERIF, Typeface.ITALIC);
        this.tfHand = baseHand;
        this.tfHandBold = Typeface.create(baseHand, Typeface.BOLD);
        this.tfNorm = Typeface.create("sans-serif", Typeface.NORMAL);
        this.tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        setBackgroundColor(0xFF121617);
        setClickable(true);

        canvasView = new CanvasView(ctx);
        addView(canvasView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        eingabe = new EditText(ctx);
        eingabe.setBackground(null);
        eingabe.setPadding(0, 0, 0, 0);
        eingabe.setSingleLine(true);
        eingabe.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        eingabe.setImeOptions(EditorInfo.IME_ACTION_DONE);
        eingabe.setHint("Was fehlt?");
        eingabe.setTextColor(0xFFECEBE8);
        eingabe.setHintTextColor(0xFF8C9094);
        eingabe.setTypeface(tfNorm);
        eingabe.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        eingabe.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND || enter) {
                absendenEingabe();
                return true;
            }
            return false;
        });
        addView(eingabe, new LayoutParams(200, 48));
    }

    void setDaten(Object haKonfig, Object haVerlauf, String fehler) {
        this.fehler = fehler;
        if (haKonfig instanceof Map) {
            Map<?, ?> top = (Map<?, ?>) haKonfig;
            Object ekObj = top.containsKey("einkaufsliste") ? top.get("einkaufsliste") : top;
            if (ekObj instanceof Map) {
                Map<?, ?> ek = (Map<?, ?>) ekObj;
                String st = str(ek.get("stil"));
                if ("block".equals(st) || "zettel".equals(st) || "schlicht".equals(st)) {
                    stil = st;
                }
                if (ek.get("vorschlaege") instanceof Boolean) {
                    vorschlaege = (Boolean) ek.get("vorschlaege");
                }
                String auf = str(ek.get("aufraeumen"));
                if ("sofort".equals(auf) || "nachts".equals(auf) || "hand".equals(auf)) {
                    aufraeumen = auf;
                }
                listen.clear();
                Object rawListen = ek.get("listen");
                if (rawListen instanceof List) {
                    for (Object o : (List<?>) rawListen) {
                        if (o instanceof Map) {
                            Map<?, ?> lm = (Map<?, ?>) o;
                            String id = str(lm.get("id"));
                            if (id.isEmpty()) id = str(lm.get("entity_id"));
                            String name = str(lm.get("name"));
                            if (name.isEmpty()) name = str(lm.get("friendly_name"));
                            if (!id.isEmpty()) listen.add(new ListeInfo(id, name.isEmpty() ? fallbackName(id) : name));
                        } else if (o instanceof String) {
                            String id = ((String) o).trim();
                            if (!id.isEmpty()) listen.add(new ListeInfo(id, fallbackName(id)));
                        }
                    }
                }
            }
        }
        if (listen.isEmpty() && !initListe.isEmpty()) {
            listen.add(new ListeInfo(initListe, fallbackName(initListe)));
        }
        if (haVerlauf != null) {
            verlaufLesen(haVerlauf);
        }
        if (!initListe.isEmpty() && findeListe(initListe) != null) {
            aktivListeId = initListe;
            initListe = "";
        } else if (aktivListeId.isEmpty() || findeListe(aktivListeId) == null) {
            aktivListeId = listen.isEmpty() ? "" : listen.get(0).id;
        }
        for (ListeInfo l : listen) {
            if (!itemsByList.containsKey(l.id)) itemsLaden(l.id);
        }
        layoutEingabe();
        canvasView.invalidate();
    }

    void setListe(String todoEntity) {
        if (todoEntity == null || todoEntity.trim().isEmpty()) return;
        String id = todoEntity.trim();
        if (findeListe(id) == null) {
            listen.add(new ListeInfo(id, fallbackName(id)));
        }
        aktivListeId = id;
        listScrollY = 0f;
        itemsLaden(id);
        canvasView.invalidate();
    }

    private ListeInfo findeListe(String id) {
        for (ListeInfo l : listen) if (l.id.equals(id)) return l;
        return null;
    }

    private static String fallbackName(String eid) {
        String s = eid.startsWith("todo.") ? eid.substring(5) : eid;
        s = s.replace('_', ' ').trim();
        if (s.isEmpty()) return "Einkaufsliste";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void verlaufLesen(Object haVerlauf) {
        verlauf.clear();
        Object raw = haVerlauf;
        if (haVerlauf instanceof Map) {
            raw = ((Map<?, ?>) haVerlauf).get("items");
        }
        if (!(raw instanceof List)) return;
        for (Object o : (List<?>) raw) {
            String n = "";
            if (o instanceof Map) n = str(((Map<?, ?>) o).get("n"));
            else if (o instanceof String) n = ((String) o).trim();
            if (!n.isEmpty()) verlauf.add(n);
        }
    }

    private void itemsLaden(final String eid) {
        if (eid == null || eid.isEmpty() || ladend.contains(eid) || aktionen == null) return;
        ladend.add(eid);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "todo/item/list");
        msg.put("entity_id", eid);
        try {
            aktionen.ha(msg, (ok, data, err) -> mainHandler.post(() -> TestAnsicht.sicher(() -> {
                ladend.remove(eid);
                if (!ok) {
                    if (!itemsByList.containsKey(eid) && err != null && !err.isEmpty()) fehler = err;
                    canvasView.invalidate();
                    return;
                }
                List<Eintrag> list = new ArrayList<>();
                Object rawItems = data instanceof Map ? ((Map<?, ?>) data).get("items") : (data instanceof List ? data : null);
                if (rawItems instanceof List) {
                    for (Object o : (List<?>) rawItems) {
                        if (!(o instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) o;
                        String summary = str(m.get("summary"));
                        String uid = str(m.get("uid"));
                        if (uid.isEmpty()) uid = summary;
                        String status = str(m.get("status"));
                        if (status.isEmpty()) status = "needs_action";
                        if (!summary.isEmpty()) list.add(new Eintrag(uid, summary, status));
                    }
                }
                itemsByList.put(eid, list);
                fehler = null;
                canvasView.invalidate();
            })));
        } catch (RuntimeException ignored) {
            ladend.remove(eid);
        }
    }

    private void haCall(Map<String, Object> msg, final Runnable nachher) {
        if (aktionen == null) return;
        try {
            aktionen.ha(msg, (ok, data, err) -> {
                if (nachher != null) mainHandler.post(() -> TestAnsicht.sicher(nachher));
            });
        } catch (RuntimeException ignored) {}
    }

    private void todoService(String service, Map<String, Object> data, String eid, Runnable nachher) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "call_service");
        msg.put("domain", "todo");
        msg.put("service", service);
        Map<String, Object> tgt = new LinkedHashMap<>();
        tgt.put("entity_id", eid);
        msg.put("target", tgt);
        if (data != null && !data.isEmpty()) msg.put("service_data", data);
        haCall(msg, nachher);
    }

    private void merken(List<String> namen) {
        if (namen == null || namen.isEmpty()) return;
        for (int i = namen.size() - 1; i >= 0; i--) {
            String n = namen.get(i).trim();
            if (n.isEmpty()) continue;
            String lk = n.toLowerCase(Locale.ROOT);
            for (int j = verlauf.size() - 1; j >= 0; j--) {
                if (verlauf.get(j).trim().toLowerCase(Locale.ROOT).equals(lk)) verlauf.remove(j);
            }
            verlauf.add(0, n);
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/einkauf/merken");
        msg.put("namen", namen);
        haCall(msg, null);
    }

    private void vergessen(String name) {
        if (name == null || name.isEmpty()) return;
        String lk = name.trim().toLowerCase(Locale.ROOT);
        for (int i = verlauf.size() - 1; i >= 0; i--) {
            if (verlauf.get(i).trim().toLowerCase(Locale.ROOT).equals(lk)) verlauf.remove(i);
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/einkauf/vergessen");
        msg.put("name", name);
        haCall(msg, null);
        canvasView.invalidate();
    }

    private void umschalten(String uid) {
        if (aktivListeId.isEmpty() || uid == null || uid.isEmpty()) return;
        List<Eintrag> list = itemsByList.get(aktivListeId);
        if (list == null) return;
        Eintrag target = null;
        for (Eintrag e : list) if (uid.equals(e.uid)) { target = e; break; }
        if (target == null) return;
        if (target.fertig()) {
            target.status = "needs_action";
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("item", target.uid);
            data.put("status", "needs_action");
            todoService("update_item", data, aktivListeId, () -> itemsLaden(aktivListeId));
        } else if ("sofort".equals(aufraeumen)) {
            list.remove(target);
            merken(Collections.singletonList(target.summary));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("item", Collections.singletonList(target.uid));
            todoService("remove_item", data, aktivListeId, () -> itemsLaden(aktivListeId));
        } else {
            target.status = "completed";
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("item", target.uid);
            data.put("status", "completed");
            todoService("update_item", data, aktivListeId, () -> itemsLaden(aktivListeId));
        }
        canvasView.invalidate();
    }

    private void neu(String rawTxt) {
        if (aktivListeId.isEmpty() || rawTxt == null) return;
        String t = rawTxt.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return;
        List<Eintrag> list = itemsByList.get(aktivListeId);
        if (list == null) {
            list = new ArrayList<>();
            itemsByList.put(aktivListeId, list);
        }
        String lk = t.toLowerCase(Locale.ROOT);
        Eintrag match = null;
        for (Eintrag e : list) {
            if (e.summary.trim().toLowerCase(Locale.ROOT).equals(lk)) { match = e; break; }
        }
        if (match != null && match.fertig()) {
            match.status = "needs_action";
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("item", match.uid);
            data.put("status", "needs_action");
            todoService("update_item", data, aktivListeId, () -> itemsLaden(aktivListeId));
        } else if (match == null) {
            list.add(new Eintrag("neu" + System.currentTimeMillis(), t, "needs_action"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("item", t);
            todoService("add_item", data, aktivListeId, () -> itemsLaden(aktivListeId));
        }
        canvasView.invalidate();
    }

    private void radieren() {
        if (aktivListeId.isEmpty()) return;
        List<Eintrag> list = itemsByList.get(aktivListeId);
        if (list == null) return;
        List<String> namen = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            Eintrag e = list.get(i);
            if (e.fertig()) {
                namen.add(0, e.summary);
                list.remove(i);
            }
        }
        if (namen.isEmpty()) return;
        merken(namen);
        todoService("remove_completed_items", Collections.emptyMap(), aktivListeId, () -> itemsLaden(aktivListeId));
        canvasView.invalidate();
    }

    private void absendenEingabe() {
        String txt = eingabe.getText() == null ? "" : eingabe.getText().toString();
        eingabe.setText("");
        eingabe.clearFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(eingabe.getWindowToken(), 0);
        neu(txt);
    }

    private List<String> vorschlaegeListe(int max) {
        if (!vorschlaege || aktivListeId.isEmpty()) return Collections.emptyList();
        List<Eintrag> it = itemsByList.get(aktivListeId);
        Set<String> weg = new HashSet<>();
        List<String> kandidaten = new ArrayList<>(verlauf);
        if (it != null) {
            for (Eintrag e : it) {
                if (!e.fertig()) weg.add(e.summary.trim().toLowerCase(Locale.ROOT));
                else kandidaten.add(e.summary);
            }
        }
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String v : kandidaten) {
            String k = v.trim().toLowerCase(Locale.ROOT);
            if (k.isEmpty() || weg.contains(k) || seen.contains(k)) continue;
            seen.add(k);
            out.add(v.trim());
            if (out.size() >= max) break;
        }
        return out;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) post(this::layoutEingabe);
    }

    private float rechtsX() { return "block".equals(stil) ? 675f : ("zettel".equals(stil) ? 640f : 615f); }
    private float rechtsY() { return "block".equals(stil) ? 40f : 100f; }
    private float rechtsR() { return "schlicht".equals(stil) ? 933f : 937f; }

    private void layoutEingabe() {
        int gw = getWidth(), gh = getHeight();
        if (gw <= 0 || gh <= 0) return;
        skala = Math.min(gw / W, gh / H);
        dx = (gw - W * skala) * 0.5f;
        dy = (gh - H * skala) * 0.5f;

        float rx = rechtsX(), ry = rechtsY(), rr = rechtsR();
        float vx = rx + 48f;
        float vy = ry + 6f;
        float vw = Math.max(40f, (rr - 62f) - vx);
        float vh = 46f;

        LayoutParams lp = (LayoutParams) eingabe.getLayoutParams();
        int l = Math.round(dx + vx * skala);
        int t = Math.round(dy + vy * skala);
        int w = Math.max(20, Math.round(vw * skala));
        int h = Math.max(20, Math.round(vh * skala));
        if (lp.leftMargin != l || lp.topMargin != t || lp.width != w || lp.height != h) {
            lp.leftMargin = l;
            lp.topMargin = t;
            lp.width = w;
            lp.height = h;
            eingabe.setLayoutParams(lp);
        }
        eingabe.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(10f, 19f * skala));
    }

    private final class CanvasView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final Path iconTmp = new Path();
        private final Map<String, Path> iconCache = new HashMap<>();
        private final List<Hit> hits = new ArrayList<>();

        private final Shader bgHand = new RadialGradient(W * 0.30f, H * 0.40f, W * 0.72f, 0xFF22292B, 0xFF121617, Shader.TileMode.CLAMP);
        private final Shader bgSchlichtGlow = new RadialGradient(W * 0.25f, H * 0.40f, W * 0.50f, 0x1A8BC34A, 0x008BC34A, Shader.TileMode.CLAMP);
        private final Shader zettelBg = new LinearGradient(0, 0, 120, 500, new int[] {0xFFFFF3A6, 0xFFFDE77C, 0xFFF5E27A}, new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        private final Shader spiralGrad = new LinearGradient(0, 0, 12, 0, new int[] {0xFF7D8488, 0xFFD9DDE0, 0xFF8B9296}, new float[] {0f, 0.45f, 1f}, Shader.TileMode.REPEAT);

        private float downX, downY, lastY;
        private boolean ziehend, langAusgeloest;
        private Hit downHit;
        private final Runnable langCheck = new Runnable() {
            @Override public void run() {
                if (downHit != null && "chip".equals(downHit.aktion) && !ziehend) {
                    langAusgeloest = true;
                    vergessen(downHit.wert);
                }
            }
        };

        CanvasView(Context ctx) {
            super(ctx);
            setClickable(true);
            lPaint.setStyle(Paint.Style.STROKE);
            lPaint.setStrokeCap(Paint.Cap.ROUND);
            lPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas c) {
            int gw = getWidth(), gh = getHeight();
            if (gw <= 0 || gh <= 0) return;
            skala = Math.min(gw / W, gh / H);
            if (skala <= 0) return;
            dx = (gw - W * skala) * 0.5f;
            dy = (gh - H * skala) * 0.5f;
            hits.clear();

            c.drawColor(0xFF121617);
            c.save();
            c.translate(dx, dy);
            c.scale(skala, skala);
            c.clipRect(0, 0, W, H);
            try {
                boolean hand = !"schlicht".equals(stil);
                p.setStyle(Paint.Style.FILL);
                if (hand) {
                    p.setShader(bgHand);
                    c.drawRect(0, 0, W, H, p);
                    p.setShader(null);
                } else {
                    c.drawColor(0xFF111710);
                    p.setShader(bgSchlichtGlow);
                    c.drawRect(0, 0, W, H, p);
                    p.setShader(null);
                }

                // Back circle (58, 50)
                p.setColor(0x1EFFFFFF);
                c.drawCircle(58f, 50f, 28f, p);
                drawIcon(c, "mdi:chevron-left", 45f, 37f, 26f, 0xFFECEBE8);
                hits.add(new Hit("zurueck", "", 22f, 14f, 94f, 86f));

                ListeInfo aktiv = findeListe(aktivListeId);
                if (aktiv == null && !listen.isEmpty()) aktiv = listen.get(0);
                List<Eintrag> alle = aktiv != null ? itemsByList.get(aktiv.id) : null;
                List<Eintrag> offen = alle == null ? null : new ArrayList<>();
                List<Eintrag> fertig = new ArrayList<>();
                if (alle != null) {
                    for (Eintrag e : alle) {
                        if (e.fertig()) fertig.add(e);
                        else offen.add(e);
                    }
                    if (fertig.size() > 8) fertig = new ArrayList<>(fertig.subList(fertig.size() - 8, fertig.size()));
                }

                if ("block".equals(stil)) {
                    zeichneBlock(c, aktiv, offen, fertig);
                } else if ("zettel".equals(stil)) {
                    zeichneZettel(c, aktiv, offen, fertig);
                } else {
                    zeichneSchlicht(c, aktiv, offen, fertig);
                }

                zeichneRechts(c, aktiv);
            } catch (RuntimeException ignored) {}
            c.restore();
        }

        private void zeichneBlock(Canvas c, ListeInfo aktiv, List<Eintrag> offen, List<Eintrag> fertig) {
            c.save();
            c.rotate(-1.2f, 350f, 321.5f);

            // Tabs sticking out on the right edge of the notepad (x = 590..652, y = 70..)
            if (listen.size() > 1) {
                float ty = 70f;
                for (int k = 0; k < listen.size(); k++) {
                    ListeInfo l = listen.get(k);
                    boolean on = aktiv != null && l.id.equals(aktiv.id);
                    int m = offeneAnzahl(l.id);
                    String lbl = l.name + (m > 0 ? " · " + m : "");
                    tPaint.setTypeface(tfHandBold);
                    tPaint.setTextSize(19f);
                    float th = Math.max(120f, tPaint.measureText(lbl) + 32f);
                    float tw = on ? 62f : 58f;
                    float tx = 590f;
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x55000000);
                    rf.set(tx + 3f, ty + 3f, tx + tw + 3f, ty + th + 3f);
                    c.drawRoundRect(rf, 10f, 10f, p);
                    p.setColor(WetterLage.alpha(TAB_FARBEN[k % 4], on ? 1f : 0.75f));
                    rf.set(tx - 10f, ty, tx + tw, ty + th);
                    c.drawRoundRect(rf, 10f, 10f, p);

                    c.save();
                    c.rotate(90f, tx + tw * 0.5f, ty + th * 0.5f);
                    tPaint.setTextAlign(Paint.Align.CENTER);
                    tPaint.setColor(WetterLage.alpha(0xFF3A3020, on ? 1f : 0.8f));
                    c.drawText(kuerzen(tPaint, lbl, th - 16f), tx + tw * 0.5f, ty + th * 0.5f + 6f, tPaint);
                    c.restore();
                    tPaint.setTextAlign(Paint.Align.LEFT);

                    hits.add(new Hit("tab", l.id, tx, ty, tx + tw, ty + th));
                    ty += th + 6f;
                }
            }

            // Paper stack shadow + bottom edge layers
            float bx = 100f, by = 34f, bw = 500f, bh = 575f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x66000000);
            rf.set(bx + 4f, by + 10f, bx + bw + 4f, by + bh + 12f);
            c.drawRoundRect(rf, 6f, 6f, p);
            p.setColor(0xFFD6CFBC);
            rf.set(bx, by + 4f, bx + bw, by + bh + 4f);
            c.drawRoundRect(rf, 6f, 6f, p);
            p.setColor(0xFFE2DCCB);
            rf.set(bx, by + 2f, bx + bw, by + bh + 2f);
            c.drawRoundRect(rf, 6f, 6f, p);

            // Cream paper sheet (#FFF6F0DF)
            p.setColor(0xFFF6F0DF);
            rf.set(bx, by, bx + bw, by + bh);
            c.drawRoundRect(rf, 6f, 6f, p);

            c.save();
            c.clipRect(bx, by, bx + bw, by + bh);

            // Vertical red margin line at x = bx + 66..68
            p.setColor(0xFFE58C8C);
            c.drawRect(bx + 66f, by, bx + 68f, by + bh, p);

            // Scrollable ruled lines + content
            float padTop = 22f, rowH = 49f;
            float contentTop = by + padTop;
            float visibleH = bh - padTop - 10f;

            // Blue ruled lines scrolling with content
            float firstLineOffset = ((rowH - (listScrollY % rowH)) % rowH);
            p.setColor(0xFFB9CDE3);
            for (float ly = contentTop + 47f - (listScrollY % rowH); ly < by + bh; ly += rowH) {
                if (ly >= by + 16f) c.drawRect(bx, ly, bx + bw, ly + 2f, p);
            }
            // Also draw any ruled lines if scroll=0 above first item? No, first line is at contentTop + 47.

            float y = contentTop - listScrollY;
            String listTitle = aktiv != null ? aktiv.name : "Einkaufsliste";
            int nOffen = offen == null ? 0 : offen.size();

            // Header row
            tPaint.setTypeface(tfHandBold);
            tPaint.setTextSize(34f);
            tPaint.setColor(0xFF1F3A68);
            String cntTxt = offen != null && nOffen > 0 ? nOffen + " offen" : "";
            float cntW = cntTxt.isEmpty() ? 0f : 80f;
            c.drawText(kuerzen(tPaint, listTitle, bw - 108f - cntW), bx + 84f, y + rowH - 10f, tPaint);
            if (!cntTxt.isEmpty()) {
                tPaint.setTypeface(tfHand);
                tPaint.setTextSize(21f);
                tPaint.setColor(0xFF7C8AA3);
                tPaint.setTextAlign(Paint.Align.RIGHT);
                c.drawText(cntTxt, bx + bw - 24f, y + rowH - 11f, tPaint);
                tPaint.setTextAlign(Paint.Align.LEFT);
            }
            y += rowH;

            if (offen == null) {
                tPaint.setTypeface(tfHand);
                tPaint.setTextSize(27f);
                tPaint.setColor(0xFF8A93A6);
                String msg = fehler != null ? "Fehler: " + fehler : "Liste wird geladen …";
                c.drawText(kuerzen(tPaint, msg, bw - 108f), bx + 84f, y + rowH - 10f, tPaint);
                y += rowH;
            } else {
                for (Eintrag e : offen) {
                    zeichneHandZeile(c, e, false, bx + 84f, y, bw - 108f, rowH, bx + 32f, 0xFF1F3A68, 0xFF2E8B3D, 0xFFC0392B, -3f, -1.5f, 30f, 26f, by, by + bh);
                    y += rowH;
                }
                if (nOffen == 0) {
                    tPaint.setTypeface(tfHand);
                    tPaint.setTextSize(27f);
                    tPaint.setColor(0xFF8A93A6);
                    String emptyTxt = "Nichts mehr zu kaufen";
                    float tw = tPaint.measureText(emptyTxt);
                    c.drawText(emptyTxt, bx + 84f, y + rowH - 10f, tPaint);
                    drawCheckmark(c, bx + 84f + tw + 10f, y + rowH - 25f, 16f, 0xFF8A93A6);
                    y += rowH;
                }
                if (!fertig.isEmpty()) {
                    for (Eintrag e : fertig) {
                        zeichneHandZeile(c, e, true, bx + 84f, y, bw - 108f, rowH, bx + 32f, 0xFF8A93A6, 0xFF2E8B3D, 0xFFC0392B, -3f, -1.5f, 30f, 26f, by, by + bh);
                        y += rowH;
                    }
                    if (!"sofort".equals(aufraeumen)) {
                        float ry = y;
                        drawIcon(c, "mdi:eraser", bx + 84f, ry + 26f, 20f, 0xFF9AA3B3);
                        tPaint.setTypeface(tfHand);
                        tPaint.setTextSize(21f);
                        tPaint.setColor(0xFF9AA3B3);
                        c.drawText("Abgehakte wegradieren", bx + 112f, ry + 43f, tPaint);
                        if (ry + 56f > by && ry < by + bh) {
                            hits.add(new Hit("rad", "", bx + 70f, Math.max(by, ry), bx + bw - 20f, Math.min(by + bh, ry + 56f)));
                        }
                        y += 56f;
                    }
                }
            }
            listMaxScroll = Math.max(0f, (y + listScrollY) - (by + bh - 28f));
            c.restore();

            // 17 metallic spiral rings at top edge
            float sx0 = bx + 24f;
            float sStep = (bw - 48f - 12f) / 16f;
            for (int i = 0; i < 17; i++) {
                float sx = sx0 + i * sStep;
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x66000000);
                rf.set(sx, by - 13f, sx + 12f, by + 17f);
                c.drawRoundRect(rf, 6f, 6f, p);
                p.setShader(spiralGrad);
                rf.set(sx, by - 14f, sx + 12f, by + 16f);
                c.drawRoundRect(rf, 6f, 6f, p);
                p.setShader(null);
            }
            c.restore();
        }

        private void zeichneZettel(Canvas c, ListeInfo aktiv, List<Eintrag> offen, List<Eintrag> fertig) {
            // Tabs in top-right (left 640..937, top 30)
            if (listen.size() > 1) {
                float tx = 640f, ty = 26f;
                for (int k = 0; k < listen.size(); k++) {
                    ListeInfo l = listen.get(k);
                    boolean on = aktiv != null && l.id.equals(aktiv.id);
                    int m = offeneAnzahl(l.id);
                    String lbl = l.name + (m > 0 ? " · " + m : "");
                    tPaint.setTypeface(tfHandBold);
                    tPaint.setTextSize(21f);
                    float pw = tPaint.measureText(lbl) + 26f;
                    float ph = 34f;
                    if (tx + pw > 937f && tx > 640f) { tx = 640f; ty += 40f; }
                    c.save();
                    c.rotate(k % 2 == 0 ? -2f : 2f, tx + pw * 0.5f, ty + ph * 0.5f);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x73000000);
                    rf.set(tx + 2f, ty + 3f, tx + pw + 2f, ty + ph + 3f);
                    c.drawRoundRect(rf, 3f, 3f, p);
                    p.setColor(WetterLage.alpha(TAB_FARBEN[k % 4], on ? 1f : 0.72f));
                    rf.set(tx, ty, tx + pw, ty + ph);
                    c.drawRoundRect(rf, 3f, 3f, p);
                    tPaint.setColor(0xFF3B3214);
                    c.drawText(lbl, tx + 13f, ty + 24f, tPaint);
                    c.restore();
                    hits.add(new Hit("tab", l.id, tx, ty, tx + pw, ty + ph));
                    tx += pw + 10f;
                }
            }

            c.save();
            c.rotate(-1.8f, 355f, 294f);
            float bx = 120f, by = 44f, bw = 470f, bh = 500f;

            // Shadow + yellow gradient sheet (#FFF5E27A)
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x88000000);
            c.drawRect(bx + 4f, by + 12f, bx + bw + 4f, by + bh + 14f, p);
            c.save();
            c.translate(bx, by);
            p.setShader(zettelBg);
            c.drawRect(0, 0, bw, bh, p);
            p.setShader(null);
            c.restore();

            c.save();
            c.clipRect(bx, by, bx + bw, by + bh);
            float y = by + 34f - listScrollY;
            String listTitle = aktiv != null ? aktiv.name : "Einkaufsliste";
            int nOffen = offen == null ? 0 : offen.size();

            // Header with underline
            tPaint.setTypeface(tfHandBold);
            tPaint.setTextSize(39f);
            tPaint.setColor(0xFF2B2A26);
            String cntTxt = offen != null && nOffen > 0 ? nOffen + " offen" : "";
            float cntW = cntTxt.isEmpty() ? 0f : 80f;
            c.drawText(kuerzen(tPaint, listTitle, bw - 80f - cntW), bx + 44f, y + 32f, tPaint);
            if (!cntTxt.isEmpty()) {
                tPaint.setTypeface(tfHand);
                tPaint.setTextSize(22f);
                tPaint.setColor(0xFF8A7A3A);
                tPaint.setTextAlign(Paint.Align.RIGHT);
                c.drawText(cntTxt, bx + bw - 36f, y + 32f, tPaint);
                tPaint.setTextAlign(Paint.Align.LEFT);
            }
            p.setColor(0x992B2A26);
            c.drawRect(bx + 44f, y + 40f, bx + bw - 36f, y + 43f, p);
            y += 55f;

            float rowH = 42f;
            if (offen == null) {
                tPaint.setTypeface(tfHand);
                tPaint.setTextSize(27f);
                tPaint.setColor(0xFF9B8F55);
                String msg = fehler != null ? "Fehler: " + fehler : "Liste wird geladen …";
                c.drawText(kuerzen(tPaint, msg, bw - 80f), bx + 44f, y + 29f, tPaint);
                y += rowH;
            } else {
                for (Eintrag e : offen) {
                    zeichneHandZeile(c, e, false, bx + 79f, y, bw - 115f, rowH, bx + 44f, 0xFF2B2A26, 0xFF2E7D32, 0xCC5A4A1A, 2f, 1.2f, 32f, 27f, by, by + bh);
                    y += rowH;
                }
                if (nOffen == 0) {
                    tPaint.setTypeface(tfHand);
                    tPaint.setTextSize(27f);
                    tPaint.setColor(0xFF9B8F55);
                    String emptyTxt = "Nichts mehr zu kaufen";
                    float tw = tPaint.measureText(emptyTxt);
                    c.drawText(emptyTxt, bx + 44f, y + 29f, tPaint);
                    drawCheckmark(c, bx + 44f + tw + 10f, y + 14f, 16f, 0xFF9B8F55);
                    y += rowH;
                }
                if (!fertig.isEmpty()) {
                    for (Eintrag e : fertig) {
                        zeichneHandZeile(c, e, true, bx + 79f, y, bw - 115f, rowH, bx + 44f, 0xFF9B8F55, 0xFF2E7D32, 0xCC5A4A1A, 2f, 1.2f, 32f, 27f, by, by + bh);
                        y += rowH;
                    }
                    if (!"sofort".equals(aufraeumen)) {
                        float ry = y + 10f;
                        drawIcon(c, "mdi:eraser", bx + 44f, ry + 11f, 20f, 0xFF8A7A3A);
                        tPaint.setTypeface(tfHand);
                        tPaint.setTextSize(21f);
                        tPaint.setColor(0xFF8A7A3A);
                        c.drawText("Abgehakte wegradieren", bx + 72f, ry + 28f, tPaint);
                        if (ry + 42f > by && ry < by + bh) {
                            hits.add(new Hit("rad", "", bx + 40f, Math.max(by, ry), bx + bw - 30f, Math.min(by + bh, ry + 42f)));
                        }
                        y += 52f;
                    }
                }
            }
            listMaxScroll = Math.max(0f, (y + listScrollY) - (by + bh - 24f));
            c.restore();

            // Masking tape at top center
            c.save();
            c.rotate(2f, bx + 235f, by + 1f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x33000000);
            c.drawRect(bx + 160f, by - 17f, bx + 310f, by + 21f, p);
            p.setColor(0x8CEBEBDC);
            c.drawRect(bx + 160f, by - 18f, bx + 310f, by + 20f, p);
            c.restore();

            c.restore();
        }

        private void zeichneHandZeile(Canvas c, Eintrag e, boolean done, float tx, float y, float maxW, float rowH,
                                      float boxX, int txtCol, int checkCol, int strikeCol, float boxRot, float strikeRot,
                                      float sizeNorm, float sizeLong, float clipTop, float clipBot) {
            float cy = y + rowH * 0.5f;
            float boxY = cy - 10.5f;

            // Checkbox
            c.save();
            c.rotate(boxRot, boxX + 10.5f, boxY + 10.5f);
            lPaint.setColor(txtCol);
            lPaint.setStrokeWidth(2.4f);
            rf.set(boxX, boxY, boxX + 21f, boxY + 21f);
            c.drawRoundRect(rf, 4f, 4f, lPaint);
            c.restore();

            if (done) {
                drawCheckmark(c, boxX + 2f, boxY - 4f, 20f, checkCol);
            }

            boolean lang = e.summary.length() > 20;
            tPaint.setTypeface(tfHandBold);
            tPaint.setTextSize(lang ? sizeLong : sizeNorm);
            tPaint.setColor(txtCol);
            String shown = kuerzen(tPaint, e.summary, maxW);
            float baseY = y + rowH - (rowH > 45f ? 10f : 9f);
            c.drawText(shown, tx, baseY, tPaint);

            if (done) {
                float tw = tPaint.measureText(shown);
                float sy = baseY - (lang ? 8f : 10f);
                c.save();
                c.rotate(strikeRot, tx + tw * 0.5f, sy);
                p.setStyle(Paint.Style.FILL);
                p.setColor(strikeCol);
                rf.set(tx - 4f, sy - 1.5f, tx + tw + 6f, sy + 1.5f);
                c.drawRoundRect(rf, 1.5f, 1.5f, p);
                c.restore();
            }

            if (y + rowH > clipTop && y < clipBot) {
                hits.add(new Hit("toggle", e.uid, boxX - 8f, Math.max(clipTop, y), tx + maxW + 8f, Math.min(clipBot, y + rowH)));
            }
        }

        private void zeichneSchlicht(Canvas c, ListeInfo aktiv, List<Eintrag> offen, List<Eintrag> fertig) {
            String listTitle = aktiv != null ? aktiv.name : "Einkaufsliste";
            int nOffen = offen == null ? 0 : offen.size();

            tPaint.setTypeface(tfMed);
            tPaint.setTextSize(22f);
            tPaint.setColor(0xFFECEBE8);
            c.drawText(kuerzen(tPaint, listTitle, 320f), 102f, 44f, tPaint);
            String sub = offen == null ? "" : ((nOffen > 0 ? nOffen + " offen" : "Alles erledigt")
                + (fertig.isEmpty() ? "" : " · " + fertig.size() + " im Wagen"));
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(14f);
            tPaint.setColor(0xFFA9B59A);
            c.drawText(sub, 102f, 64f, tPaint);

            // Tabs top-right
            if (listen.size() > 1) {
                float totalW = 0f;
                float[] ws = new float[listen.size()];
                String[] lbls = new String[listen.size()];
                for (int k = 0; k < listen.size(); k++) {
                    ListeInfo l = listen.get(k);
                    int m = offeneAnzahl(l.id);
                    lbls[k] = l.name + (m > 0 ? " · " + m : "");
                    boolean on = aktiv != null && l.id.equals(aktiv.id);
                    tPaint.setTypeface(on ? tfMed : tfNorm);
                    tPaint.setTextSize(15f);
                    ws[k] = tPaint.measureText(lbls[k]) + 32f;
                    totalW += ws[k] + (k > 0 ? 8f : 0f);
                }
                float tx = W - 30f - totalW;
                float ty = 30f;
                for (int k = 0; k < listen.size(); k++) {
                    ListeInfo l = listen.get(k);
                    boolean on = aktiv != null && l.id.equals(aktiv.id);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(on ? 0xFF8BC34A : 0x0FFFFFFF);
                    rf.set(tx, ty, tx + ws[k], ty + 38f);
                    c.drawRoundRect(rf, 14f, 14f, p);
                    tPaint.setTypeface(on ? tfMed : tfNorm);
                    tPaint.setTextSize(15f);
                    tPaint.setColor(on ? 0xFF132008 : 0xFF9DA0A2);
                    tPaint.setTextAlign(Paint.Align.CENTER);
                    c.drawText(lbls[k], rf.centerX(), ty + 24f, tPaint);
                    tPaint.setTextAlign(Paint.Align.LEFT);
                    hits.add(new Hit("tab", l.id, tx, ty, tx + ws[k], ty + 38f));
                    tx += ws[k] + 8f;
                }
            }

            float lx = 30f, ly0 = 100f, lw = 555f, lh = H - ly0;
            c.save();
            c.clipRect(lx, ly0, lx + lw, ly0 + lh);
            float y = ly0 - listScrollY;

            if (offen == null) {
                tPaint.setTypeface(tfNorm);
                tPaint.setTextSize(16f);
                tPaint.setColor(0xFFA9B59A);
                tPaint.setTextAlign(Paint.Align.CENTER);
                String msg = fehler != null ? "Fehler: " + fehler : "Liste wird geladen …";
                c.drawText(kuerzen(tPaint, msg, lw - 32f), lx + lw * 0.5f, y + 60f, tPaint);
                tPaint.setTextAlign(Paint.Align.LEFT);
                y += 80f;
            } else {
                for (Eintrag e : offen) {
                    float rh = 60f;
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x0DFFFFFF);
                    rf.set(lx, y, lx + lw, y + rh);
                    c.drawRoundRect(rf, 16f, 16f, p);

                    lPaint.setColor(0xFF8BC34A);
                    lPaint.setStrokeWidth(2.5f);
                    c.drawCircle(lx + 33f, y + rh * 0.5f, 15f, lPaint);

                    tPaint.setTypeface(tfNorm);
                    tPaint.setTextSize(23f);
                    tPaint.setColor(0xFFECEBE8);
                    c.drawText(kuerzen(tPaint, e.summary, lw - 82f), lx + 64f, y + 38f, tPaint);
                    if (y + rh > ly0 && y < ly0 + lh) {
                        hits.add(new Hit("toggle", e.uid, lx, Math.max(ly0, y), lx + lw, Math.min(ly0 + lh, y + rh)));
                    }
                    y += rh + 8f;
                }
                if (nOffen == 0) {
                    drawIcon(c, "mdi:cart-check", lx + lw * 0.5f - 48f, y + 20f, 96f, 0xFF4C6236);
                    tPaint.setTextAlign(Paint.Align.CENTER);
                    tPaint.setTypeface(tfMed);
                    tPaint.setTextSize(28f);
                    tPaint.setColor(0xFFECEBE8);
                    c.drawText("Nichts mehr zu kaufen", lx + lw * 0.5f, y + 148f, tPaint);
                    tPaint.setTypeface(tfNorm);
                    tPaint.setTextSize(16f);
                    tPaint.setColor(0xFFA9B59A);
                    c.drawText("Neues rechts eintippen, antippen oder per Sprache", lx + lw * 0.5f, y + 178f, tPaint);
                    tPaint.setTextAlign(Paint.Align.LEFT);
                    y += 200f;
                }
                if (!fertig.isEmpty()) {
                    y += 14f;
                    tPaint.setTypeface(tfMed);
                    tPaint.setTextSize(13f);
                    tPaint.setColor(0xFFA9B59A);
                    c.drawText("IM WAGEN", lx + 4f, y + 14f, tPaint);
                    if (!"sofort".equals(aufraeumen)) {
                        String radTxt = "Abgehakte entfernen";
                        tPaint.setTypeface(tfNorm);
                        tPaint.setTextSize(14f);
                        float rw = tPaint.measureText(radTxt) + 22f;
                        drawIcon(c, "mdi:delete-sweep-outline", lx + lw - rw - 4f, y, 16f, 0xFF9DA0A2);
                        tPaint.setColor(0xFF9DA0A2);
                        tPaint.setTextAlign(Paint.Align.RIGHT);
                        c.drawText(radTxt, lx + lw - 4f, y + 14f, tPaint);
                        tPaint.setTextAlign(Paint.Align.LEFT);
                        if (y + 24f > ly0 && y < ly0 + lh) {
                            hits.add(new Hit("rad", "", lx + lw - rw - 10f, Math.max(ly0, y - 6f), lx + lw, Math.min(ly0 + lh, y + 22f)));
                        }
                    }
                    y += 26f;
                    for (Eintrag e : fertig) {
                        float rh = 46f;
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0xFF8BC34A);
                        c.drawCircle(lx + 33f, y + rh * 0.5f, 15f, p);
                        drawCheckmark(c, lx + 24f, y + rh * 0.5f - 8f, 16f, 0xFF132008);

                        tPaint.setTypeface(tfNorm);
                        tPaint.setTextSize(19f);
                        tPaint.setColor(0xFF6F7A6A);
                        String shown = kuerzen(tPaint, e.summary, lw - 82f);
                        float tw = tPaint.measureText(shown);
                        c.drawText(shown, lx + 64f, y + 30f, tPaint);
                        p.setColor(0xFF6F7A6A);
                        c.drawRect(lx + 64f, y + 23f, lx + 64f + tw, y + 24.5f, p);

                        if (y + rh > ly0 && y < ly0 + lh) {
                            hits.add(new Hit("toggle", e.uid, lx, Math.max(ly0, y), lx + lw, Math.min(ly0 + lh, y + rh)));
                        }
                        y += rh + 2f;
                    }
                }
            }
            listMaxScroll = Math.max(0f, (y + listScrollY) - (ly0 + lh - 20f));
            c.restore();
        }

        private void zeichneRechts(Canvas c, ListeInfo aktiv) {
            boolean hand = !"schlicht".equals(stil);
            int ac = hand ? 0xFFE9C95A : 0xFF8BC34A;
            float rx = rechtsX(), ry = rechtsY(), rr = rechtsR(), rw = rr - rx;

            // Input bar
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x14FFFFFF);
            rf.set(rx, ry, rr, ry + 58f);
            c.drawRoundRect(rf, 18f, 18f, p);
            drawIcon(c, "mdi:pencil-outline", rx + 18f, ry + 19f, 20f, 0xFF8C9094);
            hits.add(new Hit("eingabe", "", rx, ry, rr - 56f, ry + 58f));

            // "+" button
            p.setColor(ac);
            rf.set(rr - 52f, ry + 7f, rr - 8f, ry + 51f);
            c.drawRoundRect(rf, 14f, 14f, p);
            drawIcon(c, "mdi:plus", rr - 43f, ry + 16f, 26f, hand ? 0xFF3A2D06 : 0xFF132008);
            hits.add(new Hit("plus", "", rr - 56f, ry, rr, ry + 58f));

            // "Nochmal kaufen" chips
            List<String> vor = vorschlaegeListe("schlicht".equals(stil) ? 8 : 9);
            if (!vor.isEmpty()) {
                float cy = ry + 58f + 28f;
                tPaint.setTypeface(tfMed);
                tPaint.setTextSize(13f);
                tPaint.setColor(0xFF9DA0A2);
                c.drawText("NOCHMAL KAUFEN", rx, cy + 12f, tPaint);

                float cx = rx + 2f, chipY = cy + 28f;
                float maxChipY = "block".equals(stil) ? chipY + 250f : chipY + 200f;
                for (int k = 0; k < vor.size(); k++) {
                    String item = vor.get(k);
                    tPaint.setTypeface(hand ? tfHandBold : tfNorm);
                    tPaint.setTextSize(hand ? 21f : 16f);
                    float padL = hand ? 30f : 36f, padR = hand ? 13f : 15f;
                    float ch = hand ? 36f : 38f;
                    float cw = Math.min(rw - 4f, padL + tPaint.measureText(item) + padR);
                    if (cx + cw > rr - 2f && cx > rx + 4f) {
                        cx = rx + 2f;
                        chipY += ch + 12f;
                    }
                    if (chipY + ch > maxChipY) break;

                    c.save();
                    if (hand) {
                        c.rotate(CHIP_ROT[k % 3], cx + cw * 0.5f, chipY + ch * 0.5f);
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0x73000000);
                        rf.set(cx + 2f, chipY + 3f, cx + cw + 2f, chipY + ch + 3f);
                        c.drawRoundRect(rf, 2f, 2f, p);
                        p.setColor(CHIP_FARBEN[k % 3]);
                        rf.set(cx, chipY, cx + cw, chipY + ch);
                        c.drawRoundRect(rf, 2f, 2f, p);
                        drawIcon(c, "mdi:plus", cx + 10f, chipY + 10.5f, 15f, 0xFFA3862B);
                        tPaint.setColor(0xFF3B3214);
                        c.drawText(kuerzen(tPaint, item, cw - padL - padR), cx + padL, chipY + 25f, tPaint);
                    } else {
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0x12FFFFFF);
                        rf.set(cx, chipY, cx + cw, chipY + ch);
                        c.drawRoundRect(rf, 16f, 16f, p);
                        drawIcon(c, "mdi:plus", cx + 13f, chipY + 11f, 16f, 0xFF8BC34A);
                        tPaint.setColor(0xFFECEBE8);
                        c.drawText(kuerzen(tPaint, item, cw - padL - padR), cx + padL, chipY + 25f, tPaint);
                    }
                    c.restore();

                    hits.add(new Hit("chip", item, cx, chipY, cx + cw, chipY + ch));
                    cx += cw + 10f;
                }
            }

            // Bottom voice hint
            String lName = aktiv != null ? aktiv.name : "Einkaufsliste";
            String bsp = lName.toLowerCase(Locale.ROOT).endsWith("liste")
                ? "„Setz Milch auf die " + lName + "“"
                : "„Setz Zahnpasta auf die Liste " + lName + "“";
            float vy = H - 56f;
            drawIcon(c, "mdi:microphone", rx, vy, 22f, ac);
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(14f);
            tPaint.setColor(0xFF9DA0A2);
            c.drawText("Oder einfach sagen:", rx + 32f, vy + 14f, tPaint);
            tPaint.setColor(0xFFECEBE8);
            c.drawText(kuerzen(tPaint, bsp, rw - 32f), rx + 32f, vy + 34f, tPaint);
        }

        private int offeneAnzahl(String id) {
            List<Eintrag> l = itemsByList.get(id);
            if (l == null) return 0;
            int n = 0;
            for (Eintrag e : l) if (!e.fertig()) n++;
            return n;
        }

        private void drawCheckmark(Canvas c, float x, float y, float size, int color) {
            lPaint.setColor(color);
            lPaint.setStrokeWidth(Math.max(2.2f, size * 0.16f));
            iconTmp.reset();
            iconTmp.moveTo(x + size * 0.15f, y + size * 0.55f);
            iconTmp.lineTo(x + size * 0.42f, y + size * 0.85f);
            iconTmp.lineTo(x + size * 0.90f, y + size * 0.18f);
            c.drawPath(iconTmp, lPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            try {
                int a = e.getActionMasked();
                float vx = (e.getX() - dx) / skala;
                float vy = (e.getY() - dy) / skala;
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                    downY = e.getY();
                    lastY = downY;
                    ziehend = false;
                    langAusgeloest = false;
                    downHit = findeHit(vx, vy);
                    mainHandler.removeCallbacks(langCheck);
                    if (downHit != null && "chip".equals(downHit.aktion)) {
                        mainHandler.postDelayed(langCheck, 550L);
                    }
                } else if (a == MotionEvent.ACTION_MOVE) {
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > 14f * skala) {
                        ziehend = true;
                        mainHandler.removeCallbacks(langCheck);
                    }
                    if (ziehend && listMaxScroll > 0 && vx < rechtsX() - 10f) {
                        float dyV = (lastY - e.getY()) / skala;
                        listScrollY = Math.max(0f, Math.min(listMaxScroll, listScrollY + dyV));
                        lastY = e.getY();
                        invalidate();
                    }
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    mainHandler.removeCallbacks(langCheck);
                    if (a == MotionEvent.ACTION_UP && !ziehend && !langAusgeloest
                            && Math.hypot(e.getX() - downX, e.getY() - downY) < 28f * skala) {
                        Hit h = findeHit(vx, vy);
                        if (h != null) {
                            performClick();
                            ausfuehren(h);
                        }
                    }
                    downHit = null;
                }
            } catch (RuntimeException ignored) {}
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }

        private Hit findeHit(float vx, float vy) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (h.trifft(vx, vy)) return h;
            }
            return null;
        }

        private void ausfuehren(Hit h) {
            switch (h.aktion) {
                case "zurueck":
                    if (aktionen != null) aktionen.zurueck();
                    break;
                case "tab":
                    setListe(h.wert);
                    break;
                case "toggle":
                    umschalten(h.wert);
                    break;
                case "rad":
                    radieren();
                    break;
                case "plus":
                    absendenEingabe();
                    break;
                case "chip":
                    neu(h.wert);
                    break;
                case "eingabe":
                    eingabe.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(eingabe, InputMethodManager.SHOW_IMPLICIT);
                    break;
            }
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
            lPaint.setColor(color);
            lPaint.setStrokeWidth(2.2f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            iconTmp.reset();
            switch (name) {
                case "mdi:chevron-left":
                    iconTmp.moveTo(15f, 6f); iconTmp.lineTo(9f, 12f); iconTmp.lineTo(15f, 18f);
                    c.drawPath(iconTmp, lPaint);
                    break;
                case "mdi:plus":
                    c.drawLine(12f, 5f, 12f, 19f, lPaint);
                    c.drawLine(5f, 12f, 19f, 12f, lPaint);
                    break;
                case "mdi:pencil-outline":
                    c.drawLine(5f, 19f, 18f, 6f, lPaint);
                    c.drawLine(4f, 19f, 8f, 19f, lPaint);
                    break;
                case "mdi:microphone":
                    rf.set(9f, 4f, 15f, 13f);
                    c.drawRoundRect(rf, 3f, 3f, p);
                    rf.set(6.5f, 8f, 17.5f, 16f);
                    c.drawArc(rf, 0f, 180f, false, lPaint);
                    c.drawLine(12f, 16f, 12f, 20f, lPaint);
                    c.drawLine(8.5f, 20f, 15.5f, 20f, lPaint);
                    break;
                default:
                    c.drawCircle(12f, 12f, 6f, lPaint);
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
    }

    private static String kuerzen(Paint pt, String s, float maxW) {
        if (s == null || s.isEmpty() || pt.measureText(s) <= maxW) return s == null ? "" : s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (pt.measureText(s.substring(0, mid) + "…") <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return lo <= 0 ? "…" : s.substring(0, lo) + "…";
    }

    private static String str(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o).trim();
        return "null".equals(s) ? "" : s;
    }
}
