package echoshow.kiosktest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Native full-screen Music Player view matching player.py on the 963 x 584 dashboard grid:
 * - Blurred cover backdrop + dark gradient scrim (or #15171A fallback)
 * - Top bar: back circle (58, 50), Text-Versatz pill/popover, Lyrics toggle, Autoplay & Crossfade toggles,
 *   Group button with count badge, Device button with MDI icon & chevron
 * - 330px (or 224px in lyrics mode) rounded cover, title/artist/album, interactive seekbar with 1s live tick,
 *   transport controls (prev / play-pause / next) and volume - / % / +
 * - Synced Karaoke lyrics panel (echo_show/musik/liedtext, tap line to seek, +-0.25 s Text-Versatz popover)
 * - Wiedergabegeraete popover (script.musik_umziehen) and Gruppenmitglieder popover (media_player.join / unjoin)
 */
final class MusikPlayerView extends FrameLayout {
    static final float W = 963f, H = 584f;
    private static final int BG = 0xFF15171A, TXT = 0xFFFFFFFF, SUB = 0xCCFFFFFF, DIM = 0x8CFFFFFF, ACC = 0xFFA99CF0;

    interface Aktionen {
        void zurueck();
        void ha(Map<String, Object> msg, Antwort cb);
        void coverLaden(String url);
        boolean pausiert();
    }

    interface Antwort {
        void fertig(boolean ok, Object data, String err);
    }

    private static final class Hit {
        final String id, arg;
        final float l, t, r, b;
        Hit(String id, String arg, float l, float t, float r, float b) {
            this.id = id;
            this.arg = arg;
            this.l = l; this.t = t; this.r = r; this.b = b;
        }
        boolean trifft(float x, float y) { return x >= l && x <= r && y >= t && y <= b; }
    }

    private static final class LyrikLayoutZeile {
        final int index;
        final double t;
        final List<String> zeilen;
        final float topY, height;
        LyrikLayoutZeile(int index, double t, List<String> zeilen, float topY, float height) {
            this.index = index;
            this.t = t;
            this.zeilen = zeilen;
            this.topY = topY;
            this.height = height;
        }
    }

    private final KsTheme theme;
    private final Aktionen aktionen;
    private final CanvasView canvasView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MusikDaten.Zustand daten = new MusikDaten.Zustand();
    private String coverUrl = "", coverAngefordert = "";
    private Bitmap coverScharf, coverWeich;

    private boolean lyricsAn;
    private boolean lyricsLaden;
    private String lyricsKey = "";
    private MusikDaten.Liedtext liedtext;
    private float lyricsScrollY, lyricsTargetY, lyricsMaxScroll;
    private int aktivLyrikIdx = -2;

    private String offenPopover = "";   // "" | "dev" | "grp" | "versatz"
    private float popoverScrollY, popoverMaxScroll;

    private boolean ziehendSeek;
    private double seekDragPos;

    private final Runnable tick1s = new Runnable() {
        @Override public void run() {
            TestAnsicht.sicher(() -> {
                if (aktionen == null || !aktionen.pausiert()) {
                    canvasView.invalidate();
                }
            });
            mainHandler.postDelayed(this, 1000L);
        }
    };

    MusikPlayerView(Context ctx, KsTheme theme, Aktionen aktionen) {
        super(ctx);
        this.theme = theme;
        this.aktionen = aktionen;
        setBackgroundColor(BG);
        setClickable(true);
        canvasView = new CanvasView(ctx);
        addView(canvasView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void setDaten(Object haDaten) {
        if (haDaten == null) return;
        MusikDaten.Zustand neu = MusikDaten.lesen(haDaten, System.currentTimeMillis());
        daten = neu;
        lyricsAn = neu.liedtextAn;
        MusikDaten.Player pl = neu.aktuellerPlayer();
        if (pl != null && !pl.picture.isEmpty() && !pl.picture.equals(coverAngefordert)) {
            coverAngefordert = pl.picture;
            if (aktionen != null) aktionen.coverLaden(pl.picture);
        }
        pruefeLiedtext(pl);
        canvasView.schleife();
        canvasView.invalidate();
    }

    void setCover(String url, Bitmap scharf, Bitmap weich) {
        coverUrl = url == null ? "" : url;
        coverScharf = scharf;
        coverWeich = weich != null ? weich : scharf;
        canvasView.invalidate();
    }

    void pauseGeaendert() {
        canvasView.schleife();
        canvasView.invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mainHandler.removeCallbacks(tick1s);
        mainHandler.postDelayed(tick1s, 1000L);
    }

    @Override protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(tick1s);
        super.onDetachedFromWindow();
    }

    private void pruefeLiedtext(MusikDaten.Player pl) {
        if (!lyricsAn || pl == null || !pl.istLive() || pl.title.isEmpty()) return;
        final String key = pl.entityId + "|" + pl.artist + "|" + pl.title;
        if (key.equals(lyricsKey)) return;
        lyricsKey = key;
        liedtext = null;
        lyricsLaden = true;
        lyricsScrollY = 0f;
        lyricsTargetY = 0f;
        aktivLyrikIdx = -2;
        if (aktionen == null) return;

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "echo_show/musik/liedtext");
        msg.put("player", pl.entityId);
        try {
            aktionen.ha(msg, (ok, data, err) -> mainHandler.post(() -> TestAnsicht.sicher(() -> {
                if (!key.equals(lyricsKey)) return;
                lyricsLaden = false;
                liedtext = ok ? MusikDaten.lesenLiedtext(data) : new MusikDaten.Liedtext();
                aktivLyrikIdx = -2;
                canvasView.invalidate();
            })));
        } catch (RuntimeException ignored) {
            lyricsLaden = false;
        }
    }

    private void callService(String domain, String service, String targetEid, Map<String, Object> data) {
        if (aktionen == null) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "call_service");
        msg.put("domain", domain);
        msg.put("service", service);
        if (targetEid != null && !targetEid.isEmpty()) {
            Map<String, Object> tgt = new LinkedHashMap<>();
            tgt.put("entity_id", targetEid);
            msg.put("target", tgt);
        }
        if (data != null && !data.isEmpty()) {
            msg.put("service_data", data);
        }
        try {
            aktionen.ha(msg, (ok, resp, err) -> {});
        } catch (RuntimeException ignored) {}
    }

    private void queueOption(String option) {
        if ("autoplay".equals(option)) daten.autoplay = !daten.autoplay;
        else if ("crossfade".equals(option)) daten.crossfade = !daten.crossfade;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("option", option);
        if (!daten.helper.isEmpty()) data.put("auswahl", daten.helper);
        callService("script", "musik_queue_option", null, data);
        canvasView.invalidate();
    }

    private void verschiebeZu(String zielPid) {
        if (zielPid == null || zielPid.isEmpty()) return;
        daten.aktivPlayer = zielPid;
        offenPopover = "";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ziel", zielPid);
        if (!daten.helper.isEmpty()) data.put("auswahl", daten.helper);
        callService("script", "musik_umziehen", null, data);
        MusikDaten.Player pl = daten.aktuellerPlayer();
        if (pl != null && !pl.picture.isEmpty() && !pl.picture.equals(coverAngefordert)) {
            coverAngefordert = pl.picture;
            if (aktionen != null) aktionen.coverLaden(pl.picture);
        }
        pruefeLiedtext(pl);
        canvasView.invalidate();
    }

    private void gruppeUmschalten(String pid) {
        MusikDaten.Player leader = daten.aktuellerPlayer();
        if (leader == null || pid == null || pid.isEmpty() || pid.equals(leader.entityId)) return;
        boolean inGroup = leader.groupMembers.contains(pid);
        List<String> neu = new ArrayList<>(leader.groupMembers);
        if (inGroup) {
            neu.remove(pid);
            callService("media_player", "unjoin", pid, null);
        } else {
            if (!neu.contains(pid)) neu.add(pid);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("group_members", Collections.singletonList(pid));
            callService("media_player", "join", leader.entityId, data);
        }
        leader.groupMembers = neu;
        canvasView.invalidate();
    }

    private void versatzAendern(double delta, boolean reset) {
        MusikDaten.Player pl = daten.aktuellerPlayer();
        if (pl == null) return;
        double cur = daten.vorlaufFuer(pl);
        double neu = reset ? 0.0 : Math.max(-3.0, Math.min(6.0, Math.round((cur + delta) * 4.0) / 4.0));
        daten.vorlauf.put(pl.objectId(), neu);
        daten.aktivVorlauf = neu;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", MusikDaten.vorlaufJson(daten.vorlauf));
        callService("input_text", "set_value", "input_text.musik_liedtext_vorlauf_geraete", data);
        aktivLyrikIdx = -2;
        canvasView.invalidate();
    }

    private void seekTo(MusikDaten.Player pl, double sec) {
        if (pl == null || pl.duration <= 0) return;
        double s = Math.max(0.0, Math.min(pl.duration, sec));
        pl.position = s;
        pl.updatedAtMs = System.currentTimeMillis();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seek_position", Math.round(s * 10.0) / 10.0);
        callService("media_player", "media_seek", pl.entityId, data);
        aktivLyrikIdx = -2;
        canvasView.invalidate();
    }

    private final class CanvasView extends View implements Choreographer.FrameCallback {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint lPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface tfNorm = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface tfBold = Typeface.create("sans-serif", Typeface.BOLD);
        private final RectF rf = new RectF();
        private final Rect srcR = new Rect();
        private final Path clipP = new Path();
        private final Path iconTmp = new Path();
        private final Map<String, Path> iconCache = new HashMap<>();
        private final List<Hit> hits = new ArrayList<>();
        private final List<LyrikLayoutZeile> lyrikLayout = new ArrayList<>();
        private String lyrikLayoutKey = "";

        private final Shader scrimGrad = new LinearGradient(0, 0, 0, H, 0x26000000, 0x73000000, Shader.TileMode.CLAMP);

        private float skala = 1f, dx, dy, downX, downY, lastY;
        private boolean ziehendScroll, laeuft, angehaengt, sichtbar = true;
        private Hit downHit;
        private final Runnable langCheck = new Runnable() {
            @Override public void run() {
                if (downHit != null && "lyr".equals(downHit.id) && !ziehendScroll) {
                    if (!lyricsAn) {
                        lyricsAn = true;
                        callService("input_boolean", "turn_on", "input_boolean.musik_liedtext", null);
                        pruefeLiedtext(daten.aktuellerPlayer());
                    }
                    offenPopover = "versatz".equals(offenPopover) ? "" : "versatz";
                    schleife();
                    invalidate();
                    downHit = null;
                }
            }
        };

        CanvasView(Context ctx) {
            super(ctx);
            setClickable(true);
            lPaint.setStyle(Paint.Style.STROKE);
            lPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); angehaengt = true; schleife(); }
        @Override protected void onDetachedFromWindow() { angehaengt = false; schleife(); super.onDetachedFromWindow(); }
        @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); sichtbar = v == VISIBLE; schleife(); }
        @Override public void onVisibilityAggregated(boolean vis) { super.onVisibilityAggregated(vis); schleife(); }

        void schleife() {
            MusikDaten.Player pl = daten.aktuellerPlayer();
            boolean soll = angehaengt && sichtbar && isShown() && (aktionen == null || !aktionen.pausiert())
                && ((lyricsAn && pl != null && pl.istLive()) || ziehendSeek);
            if (soll == laeuft) return;
            laeuft = soll;
            Choreographer ch = Choreographer.getInstance();
            if (soll) ch.postFrameCallback(this);
            else ch.removeFrameCallback(this);
        }

        @Override public void doFrame(long ns) {
            if (!laeuft) return;
            if (Math.abs(lyricsTargetY - lyricsScrollY) > 0.5f && (liedtext == null || liedtext.synced)) {
                lyricsScrollY += (lyricsTargetY - lyricsScrollY) * 0.16f;
            }
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onDraw(Canvas c) {
            int gw = getWidth(), gh = getHeight();
            if (gw <= 0 || gh <= 0) return;
            skala = Math.min(gw / W, gh / H);
            if (skala <= 0) return;
            dx = (gw - W * skala) * 0.5f;
            dy = (gh - H * skala) * 0.5f;
            hits.clear();

            c.drawColor(BG);
            c.save();
            c.translate(dx, dy);
            c.scale(skala, skala);
            c.clipRect(0, 0, W, H);
            try {
                long nowMs = System.currentTimeMillis();
                MusikDaten.Player pl = daten.aktuellerPlayer();

                // 1. Blurred cover backdrop + scrim
                zeichneHintergrund(c, pl);

                // 2. Top bar
                zeichneKopf(c, pl);

                // 3. Cover + Meta or Lyrics + Seekbar + Controls
                boolean lyrikModus = lyricsAn && pl != null && pl.istLive();
                float rightX = lyrikModus ? 284f : 384f;
                zeichneCover(c, pl, lyrikModus);
                if (lyrikModus) {
                    zeichneLiedtext(c, pl, rightX, 94f, W - 30f - rightX, 296f, nowMs);
                } else {
                    zeichneMeta(c, pl, rightX, 94f, W - 30f - rightX, 296f);
                }
                zeichneSeekbar(c, pl, rightX, 412f, W - 30f - rightX, nowMs);
                zeichneSteuerung(c, pl, rightX, 472f, W - 30f - rightX);

                // 4. Popovers
                if ("dev".equals(offenPopover)) zeichneGeraetePopover(c, pl);
                else if ("grp".equals(offenPopover)) zeichneGruppenPopover(c, pl);
                else if ("versatz".equals(offenPopover)) zeichneVersatzPopover(c, pl);
            } catch (RuntimeException ignored) {}
            c.restore();
        }

        private void zeichneHintergrund(Canvas c, MusikDaten.Player pl) {
            boolean hatBild = pl != null && !pl.picture.isEmpty() && pl.picture.equals(coverUrl) && coverWeich != null && !coverWeich.isRecycled();
            if (hatBild) {
                drawBitmapCenterCrop(c, coverWeich, -40f, -40f, W + 80f, H + 80f, 165);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x66000000);
                c.drawRect(0, 0, W, H, p);
                p.setShader(scrimGrad);
                c.drawRect(0, 0, W, H, p);
                p.setShader(null);
            } else {
                p.setStyle(Paint.Style.FILL);
                p.setColor(BG);
                c.drawRect(0, 0, W, H, p);
            }
        }

        private void zeichneKopf(Canvas c, MusikDaten.Player pl) {
            // Back circle (58, 50), 52px diameter
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1FFFFFFF);
            c.drawCircle(58f, 50f, 26f, p);
            drawIcon(c, "mdi:chevron-down", 42f, 34f, 32f, TXT);
            hits.add(new Hit("zurueck", "", 24f, 16f, 92f, 84f));

            float rx = W - 30f;
            float cy = 50f;

            // Device button (rightmost)
            String devName = pl != null ? pl.name : "Kein Gerät";
            String devIcon = pl != null ? pl.icon : "mdi:speaker";
            tPaint.setTypeface(tfMed);
            tPaint.setTextSize(15f);
            float nameW = Math.min(200f, tPaint.measureText(devName));
            float devW = 14f + 24f + 10f + nameW + 10f + 22f + 16f;
            float devL = rx - devW;
            p.setColor("dev".equals(offenPopover) ? 0x47FFFFFF : 0x1FFFFFFF);
            rf.set(devL, cy - 26f, rx, cy + 26f);
            c.drawRoundRect(rf, 26f, 26f, p);
            drawIcon(c, devIcon, devL + 14f, cy - 12f, 24f, TXT);
            tPaint.setColor(TXT);
            c.drawText(kuerzen(tPaint, devName, 200f), devL + 48f, cy + 5.5f, tPaint);
            drawIcon(c, "dev".equals(offenPopover) ? "mdi:chevron-up" : "mdi:chevron-down", rx - 36f, cy - 11f, 22f, TXT);
            hits.add(new Hit("pop_dev", "", devL, cy - 26f, rx, cy + 26f));

            // Group button with count badge
            rx = devL - 12f;
            float grpL = rx - 52f;
            p.setColor("grp".equals(offenPopover) ? 0x47FFFFFF : 0x1FFFFFFF);
            c.drawCircle(grpL + 26f, cy, 26f, p);
            drawIcon(c, "mdi:speaker-multiple", grpL + 12f, cy - 14f, 28f, TXT);
            Set<String> grpSet = new LinkedHashSet<>();
            if (pl != null) {
                grpSet.add(pl.entityId);
                grpSet.addAll(pl.groupMembers);
            }
            String badge = String.valueOf(Math.max(1, grpSet.size()));
            tPaint.setTypeface(tfBold);
            tPaint.setTextSize(12f);
            float bw = Math.max(20f, tPaint.measureText(badge) + 10f);
            p.setColor(TXT);
            rf.set(rx - bw + 2f, cy - 24f, rx + 2f, cy - 4f);
            c.drawRoundRect(rf, 10f, 10f, p);
            tPaint.setColor(0xFF17151F);
            tPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(badge, rf.centerX(), cy - 10f, tPaint);
            tPaint.setTextAlign(Paint.Align.LEFT);
            hits.add(new Hit("pop_grp", "", grpL, cy - 26f, rx, cy + 26f));

            // Crossfade toggle
            rx = grpL - 12f;
            float xfL = rx - 52f;
            p.setColor(daten.crossfade ? ACC : 0x1FFFFFFF);
            c.drawCircle(xfL + 26f, cy, 26f, p);
            drawIcon(c, "mdi:set-none", xfL + 13f, cy - 13f, 26f, daten.crossfade ? 0xFF17151F : TXT);
            hits.add(new Hit("xf", "", xfL, cy - 26f, rx, cy + 26f));

            // Autoplay toggle
            rx = xfL - 12f;
            float aplL = rx - 52f;
            p.setColor(daten.autoplay ? ACC : 0x1FFFFFFF);
            c.drawCircle(aplL + 26f, cy, 26f, p);
            drawIcon(c, "mdi:infinity", aplL + 13f, cy - 13f, 26f, daten.autoplay ? 0xFF17151F : TXT);
            hits.add(new Hit("apl", "", aplL, cy - 26f, rx, cy + 26f));

            // Lyrics toggle
            rx = aplL - 12f;
            float lyrL = rx - 52f;
            p.setColor(lyricsAn ? ACC : 0x1FFFFFFF);
            c.drawCircle(lyrL + 26f, cy, 26f, p);
            drawIcon(c, "mdi:microphone-variant", lyrL + 13f, cy - 13f, 26f, lyricsAn ? 0xFF17151F : TXT);
            hits.add(new Hit("lyr", "", lyrL, cy - 26f, rx, cy + 26f));

            // Text-Versatz chip when lyrics mode is active (left of lyrics button)
            if (lyricsAn && pl != null && pl.istLive()) {
                double lead = daten.vorlaufFuer(pl);
                String leadTxt = (lead > 0 ? "+" : "") + String.format(Locale.GERMAN, "%.2f", lead) + " s";
                tPaint.setTypeface(tfMed);
                tPaint.setTextSize(14f);
                float vw = Math.max(96f, tPaint.measureText("Versatz " + leadTxt) + 28f);
                rx = lyrL - 12f;
                float vL = rx - vw;
                p.setColor("versatz".equals(offenPopover) ? 0x47FFFFFF : 0x1FFFFFFF);
                rf.set(vL, cy - 20f, rx, cy + 20f);
                c.drawRoundRect(rf, 20f, 20f, p);
                tPaint.setTextAlign(Paint.Align.CENTER);
                tPaint.setColor(TXT);
                c.drawText("Versatz " + leadTxt, rf.centerX(), cy + 5f, tPaint);
                tPaint.setTextAlign(Paint.Align.LEFT);
                hits.add(new Hit("pop_versatz", "", vL, cy - 24f, rx, cy + 24f));
            }
        }

        private void zeichneCover(Canvas c, MusikDaten.Player pl, boolean lyrikModus) {
            float sz = lyrikModus ? 224f : 330f;
            float cx = 30f;
            float cy = lyrikModus ? 104f : 132f;
            float rad = lyrikModus ? 18f : 22f;

            // Shadow
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x66000000);
            rf.set(cx + 2f, cy + 12f, cx + sz + 2f, cy + sz + 18f);
            c.drawRoundRect(rf, rad, rad, p);

            boolean hatBild = pl != null && !pl.picture.isEmpty() && pl.picture.equals(coverUrl) && coverScharf != null && !coverScharf.isRecycled();
            c.save();
            clipP.reset();
            rf.set(cx, cy, cx + sz, cy + sz);
            clipP.addRoundRect(rf, rad, rad, Path.Direction.CW);
            c.clipPath(clipP);
            if (hatBild) {
                drawBitmapCenterCrop(c, coverScharf, cx, cy, sz, sz, 255);
            } else {
                p.setColor(0x14FFFFFF);
                c.drawRect(rf, p);
                float isz = lyrikModus ? 68f : 96f;
                drawIcon(c, "mdi:music-note", cx + (sz - isz) * 0.5f, cy + (sz - isz) * 0.5f, isz, 0x80FFFFFF);
            }
            c.restore();

            if (lyrikModus && pl != null) {
                float ty = cy + sz + 22f;
                tPaint.setTypeface(tfMed);
                tPaint.setTextSize(22f);
                tPaint.setColor(TXT);
                ty = drawWrapped(c, pl.title.isEmpty() ? "Keine Musik" : pl.title, tPaint, cx, ty, sz, 27f, 2);
                if (!pl.artist.isEmpty()) {
                    tPaint.setTypeface(tfNorm);
                    tPaint.setTextSize(16.5f);
                    tPaint.setColor(SUB);
                    c.drawText(kuerzen(tPaint, MusikDaten.formatArtist(pl.artist), sz), cx, ty + 22f, tPaint);
                }
            }
        }

        private void zeichneMeta(Canvas c, MusikDaten.Player pl, float x, float y, float w, float h) {
            boolean on = pl != null && pl.istAktiv();
            String title = on ? pl.title : "Keine Musik";
            String artist = on ? MusikDaten.formatArtist(pl.artist) : "";
            String album = on ? pl.album : "";

            // Align near bottom of meta area (above seekbar)
            float bottomY = y + h - 18f;
            if (!album.isEmpty()) {
                tPaint.setTypeface(tfNorm);
                tPaint.setTextSize(16f);
                tPaint.setColor(0x8CFFFFFF);
                c.drawText(kuerzen(tPaint, album, w), x, bottomY, tPaint);
                bottomY -= 26f;
            }
            if (!artist.isEmpty()) {
                tPaint.setTypeface(tfNorm);
                tPaint.setTextSize(21f);
                tPaint.setColor(0xD9FFFFFF);
                c.drawText(kuerzen(tPaint, artist, w), x, bottomY, tPaint);
                bottomY -= 34f;
            }
            tPaint.setTypeface(tfMed);
            tPaint.setTextSize(34f);
            tPaint.setColor(TXT);
            List<String> tLines = wrapLines(title, tPaint, w, 2);
            float lineH = 40f;
            float startY = bottomY - (tLines.size() - 1) * lineH;
            for (int i = 0; i < tLines.size(); i++) {
                c.drawText(tLines.get(i), x, startY + i * lineH, tPaint);
            }
        }

        private void zeichneLiedtext(Canvas c, MusikDaten.Player pl, float x, float y, float w, float h, long nowMs) {
            c.save();
            c.clipRect(x, y, x + w, y + h);
            if (lyricsLaden || liedtext == null) {
                tPaint.setTypeface(tfMed);
                tPaint.setTextSize(25f);
                tPaint.setColor(TXT);
                c.drawText("Liedtext wird geladen …", x, y + h * 0.42f, tPaint);
                c.restore();
                return;
            }
            if (liedtext.lines.isEmpty()) {
                tPaint.setTypeface(tfMed);
                tPaint.setTextSize(25f);
                tPaint.setColor(TXT);
                c.drawText("Kein Liedtext gefunden", x, y + h * 0.42f, tPaint);
                c.restore();
                return;
            }

            rebuildLyricLayout(w);
            double pos = ziehendSeek ? seekDragPos : pl.aktuellePosition(nowMs);
            double lead = daten.vorlaufFuer(pl);
            int idx = -1;
            if (liedtext.synced) {
                for (int i = 0; i < liedtext.lines.size(); i++) {
                    if (liedtext.lines.get(i).t <= pos + lead) idx = i;
                    else break;
                }
                if (idx != aktivLyrikIdx) {
                    aktivLyrikIdx = idx;
                    int targetI = Math.max(0, idx);
                    if (targetI < lyrikLayout.size()) {
                        LyrikLayoutZeile lz = lyrikLayout.get(targetI);
                        float center = lz.topY + lz.height * 0.5f;
                        lyricsTargetY = Math.max(0f, center - h * 0.36f);
                        if (aktivLyrikIdx == -2 || Math.abs(lyricsTargetY - lyricsScrollY) > h * 1.5f) {
                            lyricsScrollY = lyricsTargetY;
                        }
                    }
                }
            }

            float sy = liedtext.synced ? lyricsScrollY : Math.max(0f, Math.min(lyricsMaxScroll, lyricsScrollY));
            float lineH = 34f;
            for (LyrikLayoutZeile lz : lyrikLayout) {
                float ry = y + lz.topY - sy + (liedtext.synced ? 0f : 12f);
                if (ry + lz.height < y || ry > y + h) continue;

                // Edge alpha fade (top 12%, bottom 18%)
                float midY = ry + lz.height * 0.5f;
                float edgeFade = 1f;
                if (midY < y + h * 0.14f) edgeFade = Math.max(0.15f, (midY - y) / (h * 0.14f));
                else if (midY > y + h * 0.80f) edgeFade = Math.max(0.15f, (y + h - midY) / (h * 0.20f));

                int baseCol;
                if (!liedtext.synced) baseCol = 0xD9FFFFFF;
                else if (lz.index == idx) baseCol = 0xFFFFFFFF;
                else if (lz.index < idx) baseCol = 0x8CFFFFFF;
                else baseCol = 0x61FFFFFF;

                int alpha = Math.round(((baseCol >>> 24) & 0xFF) * edgeFade);
                tPaint.setTypeface(lz.index == idx || !liedtext.synced ? tfBold : tfMed);
                tPaint.setTextSize(26f);
                tPaint.setColor((alpha << 24) | (baseCol & 0x00FFFFFF));
                for (int k = 0; k < lz.zeilen.size(); k++) {
                    c.drawText(lz.zeilen.get(k), x, ry + 26f + k * lineH, tPaint);
                }
                if (liedtext.synced && lz.t >= 0) {
                    hits.add(new Hit("seek_line", String.valueOf(lz.t), x, Math.max(y, ry), x + w, Math.min(y + h, ry + lz.height)));
                }
            }
            c.restore();
        }

        private void rebuildLyricLayout(float maxW) {
            String k = lyricsKey + "|" + Math.round(maxW) + "|" + (liedtext == null ? 0 : liedtext.lines.size());
            if (k.equals(lyrikLayoutKey)) return;
            lyrikLayoutKey = k;
            lyrikLayout.clear();
            if (liedtext == null) { lyricsMaxScroll = 0f; return; }
            tPaint.setTypeface(tfBold);
            tPaint.setTextSize(26f);
            float y = 0f, lineH = 34f, pad = 10f;
            for (int i = 0; i < liedtext.lines.size(); i++) {
                MusikDaten.Liedzeile lz = liedtext.lines.get(i);
                String txt = lz.s.trim().isEmpty() ? "♪" : lz.s.trim();
                List<String> wrapped = wrapLines(txt, tPaint, maxW - 12f, 4);
                float h = wrapped.size() * lineH + pad;
                lyrikLayout.add(new LyrikLayoutZeile(i, lz.t, wrapped, y, h));
                y += h;
            }
            lyricsMaxScroll = Math.max(0f, y - 240f);
        }

        private void zeichneSeekbar(Canvas c, MusikDaten.Player pl, float x, float y, float w, long nowMs) {
            if (pl == null || pl.duration <= 0) return;
            double pos = ziehendSeek ? seekDragPos : pl.aktuellePosition(nowMs);
            float frac = (float) Math.max(0.0, Math.min(1.0, pos / pl.duration));

            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(14f);
            tPaint.setColor(0xBFFFFFFF);
            tPaint.setTextAlign(Paint.Align.LEFT);
            c.drawText(MusikDaten.fmtZeit(pos), x, y + 19f, tPaint);
            tPaint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(MusikDaten.fmtZeit(pl.duration), x + w, y + 19f, tPaint);
            tPaint.setTextAlign(Paint.Align.LEFT);

            float barL = x + 56f, barR = x + w - 56f, barW = Math.max(20f, barR - barL);
            float cy = y + 14f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x40FFFFFF);
            rf.set(barL, cy - 3f, barR, cy + 3f);
            c.drawRoundRect(rf, 3f, 3f, p);

            float thumbX = barL + barW * frac;
            p.setColor(TXT);
            rf.set(barL, cy - 3f, thumbX, cy + 3f);
            c.drawRoundRect(rf, 3f, 3f, p);

            p.setColor(0x66000000);
            c.drawCircle(thumbX, cy + 2f, 11f, p);
            p.setColor(TXT);
            c.drawCircle(thumbX, cy, 11f, p);

            hits.add(new Hit("seek", barL + "|" + barW, barL - 14f, cy - 22f, barR + 14f, cy + 22f));
        }

        private void zeichneSteuerung(Canvas c, MusikDaten.Player pl, float x, float y, float w) {
            float cy = y + 40f;

            // Prev (64)
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1FFFFFFF);
            c.drawCircle(x + 32f, cy, 32f, p);
            drawIcon(c, "mdi:skip-previous", x + 17f, cy - 15f, 30f, TXT);
            hits.add(new Hit("prev", "", x, cy - 32f, x + 64f, cy + 32f));

            // Play / Pause (80, accent)
            float px = x + 64f + 12f;
            p.setColor(ACC);
            c.drawCircle(px + 40f, cy, 40f, p);
            boolean playing = pl != null && "playing".equals(pl.state);
            drawIcon(c, playing ? "mdi:pause" : "mdi:play", px + 21f, cy - 19f, 38f, 0xFF17151F);
            hits.add(new Hit("play", "", px, cy - 40f, px + 80f, cy + 40f));

            // Next (64)
            float nx = px + 80f + 12f;
            p.setColor(0x1FFFFFFF);
            c.drawCircle(nx + 32f, cy, 32f, p);
            drawIcon(c, "mdi:skip-next", nx + 17f, cy - 15f, 30f, TXT);
            hits.add(new Hit("next", "", nx, cy - 32f, nx + 64f, cy + 32f));

            // Volume right side: [ - ] [ 65% ] [ + ]
            float rx = x + w;
            float vpL = rx - 48f;
            p.setColor(0x1FFFFFFF);
            c.drawCircle(vpL + 24f, cy, 24f, p);
            drawIcon(c, "mdi:volume-plus", vpL + 13f, cy - 11f, 22f, TXT);
            hits.add(new Hit("vol_p", "", vpL, cy - 26f, rx, cy + 26f));

            String volTxt = (pl != null && pl.volume != null) ? Math.round(pl.volume * 100.0) + "%" : "–";
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(15f);
            tPaint.setColor(0xD9FFFFFF);
            tPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(volTxt, vpL - 31f, cy + 5.5f, tPaint);
            tPaint.setTextAlign(Paint.Align.LEFT);

            float vmL = vpL - 62f - 48f;
            p.setColor(0x1FFFFFFF);
            c.drawCircle(vmL + 24f, cy, 24f, p);
            drawIcon(c, "mdi:volume-minus", vmL + 13f, cy - 11f, 22f, TXT);
            hits.add(new Hit("vol_m", "", vmL, cy - 26f, vmL + 48f, cy + 26f));
        }

        private void zeichneGeraetePopover(Canvas c, MusikDaten.Player aktiv) {
            float pw = 360f, px = W - 30f - pw, py = 86f;
            float rowH = 64f, headH = 46f;
            float ph = Math.min(452f, headH + Math.max(1, daten.players.size()) * rowH + 14f);
            drawPopoverShell(c, px, py, pw, ph);

            tPaint.setTypeface(tfMed);
            tPaint.setTextSize(17f);
            tPaint.setColor(0xFFECEBE8);
            c.drawText("Wiedergabegeräte", px + 20f, py + 32f, tPaint);

            c.save();
            c.clipRect(px, py + headH, px + pw, py + ph - 8f);
            float listH = daten.players.size() * rowH;
            popoverMaxScroll = Math.max(0f, listH - (ph - headH - 12f));
            float sy = Math.min(popoverScrollY, popoverMaxScroll);
            float y = py + headH - sy;

            for (MusikDaten.Player pItem : daten.players) {
                boolean isSel = aktiv != null && pItem.entityId.equals(aktiv.entityId);
                boolean inG = !isSel && aktiv != null && aktiv.groupMembers.contains(pItem.entityId);
                boolean off = "unavailable".equals(pItem.state);

                if (isSel) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xFF332C55);
                    rf.set(px + 8f, y + 2f, px + pw - 8f, y + rowH - 2f);
                    c.drawRoundRect(rf, 16f, 16f, p);
                }

                int nc = isSel ? ACC : (off ? 0x73ECEBE8 : 0xFFECEBE8);
                int lc = isSel ? ACC : (off ? 0x738C9094 : 0xFF8C9094);
                drawIcon(c, pItem.icon, px + 20f, y + 19f, 26f, nc);

                float rightW = isSel ? 34f : (inG ? 76f : 12f);
                float txtW = pw - 60f - rightW;
                tPaint.setTypeface(tfMed);
                tPaint.setTextSize(16f);
                tPaint.setColor(nc);
                c.drawText(kuerzen(tPaint, pItem.name, txtW), px + 58f, y + 27f, tPaint);

                tPaint.setTypeface(tfNorm);
                tPaint.setTextSize(13f);
                tPaint.setColor(lc);
                c.drawText(kuerzen(tPaint, playerStatusLabel(pItem), txtW), px + 58f, y + 46f, tPaint);

                if (isSel) {
                    drawIcon(c, "mdi:check-circle", px + pw - 42f, y + 20f, 24f, ACC);
                } else if (inG) {
                    lPaint.setColor(ACC);
                    lPaint.setStrokeWidth(1.2f);
                    rf.set(px + pw - 82f, y + 20f, px + pw - 16f, y + 42f);
                    c.drawRoundRect(rf, 10f, 10f, lPaint);
                    tPaint.setTypeface(tfNorm);
                    tPaint.setTextSize(12f);
                    tPaint.setColor(ACC);
                    tPaint.setTextAlign(Paint.Align.CENTER);
                    c.drawText("in Gruppe", rf.centerX(), y + 35f, tPaint);
                    tPaint.setTextAlign(Paint.Align.LEFT);
                }

                if (y + rowH > py + headH && y < py + ph) {
                    hits.add(new Hit("pick_dev", pItem.entityId, px + 8f, Math.max(py + headH, y), px + pw - 8f, Math.min(py + ph, y + rowH)));
                }
                y += rowH;
            }
            c.restore();
        }

        private void zeichneGruppenPopover(Canvas c, MusikDaten.Player aktiv) {
            float pw = 360f, px = W - 30f - pw, py = 86f;
            float headH = 64f, rowH = 60f;
            List<MusikDaten.Player> inG = new ArrayList<>(), outG = new ArrayList<>();
            for (MusikDaten.Player item : daten.players) {
                boolean isLeader = aktiv != null && item.entityId.equals(aktiv.entityId);
                boolean member = isLeader || (aktiv != null && aktiv.groupMembers.contains(item.entityId));
                if (member) inG.add(item);
                else outG.add(item);
            }
            float contentH = 26f + inG.size() * rowH + (outG.isEmpty() ? 0f : 30f + outG.size() * rowH);
            float ph = Math.min(452f, headH + contentH + 12f);
            drawPopoverShell(c, px, py, pw, ph);

            tPaint.setTypeface(tfMed);
            tPaint.setTextSize(17f);
            tPaint.setColor(0xFFECEBE8);
            c.drawText("Gruppenmitglieder", px + 20f, py + 28f, tPaint);
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(13f);
            tPaint.setColor(0xFF8C9094);
            c.drawText(kuerzen(tPaint, aktiv != null ? aktiv.name : "", pw - 40f), px + 20f, py + 48f, tPaint);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF30353A);
            c.drawRect(px + 12f, py + headH - 2f, px + pw - 12f, py + headH - 1f, p);

            c.save();
            c.clipRect(px, py + headH, px + pw, py + ph - 8f);
            popoverMaxScroll = Math.max(0f, contentH - (ph - headH - 12f));
            float sy = Math.min(popoverScrollY, popoverMaxScroll);
            float y = py + headH - sy;

            tPaint.setTypeface(tfMed);
            tPaint.setTextSize(12f);
            tPaint.setColor(0xFF8C9094);
            c.drawText("IN GRUPPE", px + 20f, y + 18f, tPaint);
            y += 26f;
            for (MusikDaten.Player item : inG) {
                zeichneGruppenZeile(c, aktiv, item, true, px, py + headH, pw, py + ph, y, rowH);
                y += rowH;
            }
            if (!outG.isEmpty()) {
                tPaint.setTypeface(tfMed);
                tPaint.setTextSize(12f);
                tPaint.setColor(0xFF8C9094);
                c.drawText("WEITERE GERÄTE", px + 20f, y + 22f, tPaint);
                y += 30f;
                for (MusikDaten.Player item : outG) {
                    zeichneGruppenZeile(c, aktiv, item, false, px, py + headH, pw, py + ph, y, rowH);
                    y += rowH;
                }
            }
            c.restore();
        }

        private void zeichneGruppenZeile(Canvas c, MusikDaten.Player aktiv, MusikDaten.Player item, boolean inGroup,
                                         float px, float clipT, float pw, float clipB, float y, float rowH) {
            boolean isLeader = aktiv != null && item.entityId.equals(aktiv.entityId);
            boolean canGroup = aktiv != null && !isLeader && aktiv.canGroup && item.canGroup && !"unavailable".equals(item.state);
            float alpha = (isLeader || canGroup) ? 1f : 0.45f;

            if (inGroup) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x24A99CF0);
                rf.set(px + 8f, y + 2f, px + pw - 8f, y + rowH - 2f);
                c.drawRoundRect(rf, 16f, 16f, p);
            }
            int nc = WetterLage.alpha(0xFFECEBE8, alpha);
            int lc = WetterLage.alpha(0xFF8C9094, alpha);
            drawIcon(c, item.icon, px + 20f, y + 17f, 26f, nc);

            float txtW = pw - 108f;
            tPaint.setTypeface(tfMed);
            tPaint.setTextSize(16f);
            tPaint.setColor(nc);
            c.drawText(kuerzen(tPaint, item.name, txtW), px + 58f, y + 25f, tPaint);

            String sub = "unavailable".equals(item.state) ? "Offline"
                : (isLeader ? "Ausgewähltes Gerät" : (inGroup ? "Spielt mit" : (!item.canGroup ? "Kann nicht gruppiert werden" : stateLabel(item.state))));
            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(13f);
            tPaint.setColor(lc);
            c.drawText(kuerzen(tPaint, sub, txtW), px + 58f, y + 44f, tPaint);

            // Checkbox icon
            int cc = WetterLage.alpha(inGroup ? ACC : 0xFFECEBE8, isLeader ? 0.5f : alpha);
            drawIcon(c, inGroup ? "mdi:checkbox-marked" : "mdi:checkbox-blank-outline", px + pw - 44f, y + 16f, 28f, cc);

            if (canGroup && y + rowH > clipT && y < clipB) {
                hits.add(new Hit("pick_grp", item.entityId, px + 8f, Math.max(clipT, y), px + pw - 8f, Math.min(clipB, y + rowH)));
            }
        }

        private void zeichneVersatzPopover(Canvas c, MusikDaten.Player pl) {
            float pw = 240f, ph = 114f;
            float px = Math.min(W - 30f - pw, 470f), py = 86f;
            drawPopoverShell(c, px, py, pw, ph);

            double lead = daten.vorlaufFuer(pl);
            String s = (lead > 0 ? "+" : "") + String.format(Locale.GERMAN, "%.2f", lead) + " s";

            tPaint.setTypeface(tfNorm);
            tPaint.setTextSize(13f);
            tPaint.setColor(0x99FFFFFF);
            tPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText("Text-Versatz", px + pw * 0.5f, py + 26f, tPaint);

            float cy = py + 62f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1FFFFFFF);
            c.drawCircle(px + 42f, cy, 22f, p);
            drawIcon(c, "mdi:minus", px + 31f, cy - 11f, 22f, TXT);
            hits.add(new Hit("lead_m", "", px + 16f, cy - 24f, px + 68f, cy + 24f));

            tPaint.setTypeface(tfBold);
            tPaint.setTextSize(20f);
            tPaint.setColor(TXT);
            c.drawText(s, px + pw * 0.5f, cy + 7f, tPaint);

            p.setColor(0x1FFFFFFF);
            c.drawCircle(px + pw - 42f, cy, 22f, p);
            drawIcon(c, "mdi:plus", px + pw - 53f, cy - 11f, 22f, TXT);
            hits.add(new Hit("lead_p", "", px + pw - 68f, cy - 24f, px + pw - 16f, cy + 24f));

            if (Math.abs(lead) > 0.01) {
                tPaint.setTypeface(tfNorm);
                tPaint.setTextSize(12.5f);
                tPaint.setColor(ACC);
                c.drawText("Zurücksetzen", px + pw * 0.5f, py + ph - 12f, tPaint);
                hits.add(new Hit("lead_0", "", px + 60f, py + ph - 28f, px + pw - 60f, py + ph));
            }
            tPaint.setTextAlign(Paint.Align.LEFT);
            // Swallow background touches on popover card
            hits.add(0, new Hit("pop_bg", "", px, py, px + pw, py + ph));
        }

        private void drawPopoverShell(Canvas c, float x, float y, float w, float h) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x8C000000);
            rf.set(x + 2f, y + 12f, x + w + 2f, y + h + 14f);
            c.drawRoundRect(rf, 22f, 22f, p);
            p.setColor(0xFF24282C);
            rf.set(x, y, x + w, y + h);
            c.drawRoundRect(rf, 22f, 22f, p);
            lPaint.setColor(0xFF30353A);
            lPaint.setStrokeWidth(1.2f);
            c.drawRoundRect(rf, 22f, 22f, lPaint);
        }

        private String playerStatusLabel(MusikDaten.Player pItem) {
            if ("unavailable".equals(pItem.state)) return "Offline";
            if (("playing".equals(pItem.state) || "paused".equals(pItem.state) || "idle".equals(pItem.state)) && !pItem.title.isEmpty()) {
                String pre = "paused".equals(pItem.state) ? "Pausiert · " : ("idle".equals(pItem.state) ? "Gestoppt · " : "");
                return pre + pItem.title + (pItem.artist.isEmpty() ? "" : " · " + MusikDaten.formatArtist(pItem.artist));
            }
            return stateLabel(pItem.state);
        }

        private String stateLabel(String st) {
            switch (st) {
                case "playing": return "Spielt";
                case "paused": return "Pausiert";
                case "idle":
                case "on":
                case "standby": return "Bereit";
                case "off": return "Aus";
                default: return st;
            }
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
                    ziehendScroll = false;
                    ziehendSeek = false;
                    downHit = findeHit(vx, vy);
                    mainHandler.removeCallbacks(langCheck);
                    if (downHit != null && "seek".equals(downHit.id)) {
                        ziehendSeek = true;
                        updateSeekFromX(downHit.arg, vx);
                        schleife();
                    } else if (downHit != null && "lyr".equals(downHit.id)) {
                        mainHandler.postDelayed(langCheck, 550L);
                    }
                } else if (a == MotionEvent.ACTION_MOVE) {
                    if (ziehendSeek && downHit != null) {
                        updateSeekFromX(downHit.arg, vx);
                        invalidate();
                    } else {
                        if (Math.hypot(e.getX() - downX, e.getY() - downY) > 12f * skala) {
                            ziehendScroll = true;
                            mainHandler.removeCallbacks(langCheck);
                        }
                        if (ziehendScroll) {
                            float dyV = (lastY - e.getY()) / skala;
                            lastY = e.getY();
                            if ("dev".equals(offenPopover) || "grp".equals(offenPopover)) {
                                popoverScrollY = Math.max(0f, Math.min(popoverMaxScroll, popoverScrollY + dyV));
                                invalidate();
                            } else if (lyricsAn && liedtext != null && !liedtext.synced && lyricsMaxScroll > 0) {
                                lyricsScrollY = Math.max(0f, Math.min(lyricsMaxScroll, lyricsScrollY + dyV));
                                invalidate();
                            }
                        }
                    }
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    mainHandler.removeCallbacks(langCheck);
                    if (ziehendSeek) {
                        ziehendSeek = false;
                        schleife();
                        if (a == MotionEvent.ACTION_UP) {
                            seekTo(daten.aktuellerPlayer(), seekDragPos);
                        }
                    } else if (a == MotionEvent.ACTION_UP && !ziehendScroll
                            && Math.hypot(e.getX() - downX, e.getY() - downY) < 28f * skala) {
                        Hit h = findeHit(vx, vy);
                        if (h != null) {
                            performClick();
                            ausfuehren(h);
                        } else if (!offenPopover.isEmpty()) {
                            offenPopover = "";
                            invalidate();
                        }
                    }
                    downHit = null;
                }
            } catch (RuntimeException ignored) {}
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }

        private void updateSeekFromX(String arg, float vx) {
            MusikDaten.Player pl = daten.aktuellerPlayer();
            if (pl == null || pl.duration <= 0) return;
            String[] parts = arg.split("\\|");
            if (parts.length < 2) return;
            float barL = Float.parseFloat(parts[0]);
            float barW = Float.parseFloat(parts[1]);
            float frac = Math.max(0f, Math.min(1f, (vx - barL) / barW));
            seekDragPos = frac * pl.duration;
        }

        private Hit findeHit(float vx, float vy) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (h.trifft(vx, vy)) return h;
            }
            return null;
        }

        private void ausfuehren(Hit h) {
            MusikDaten.Player pl = daten.aktuellerPlayer();
            if (!offenPopover.isEmpty() && !"pop_dev".equals(h.id) && !"pop_grp".equals(h.id)
                    && !"pop_versatz".equals(h.id) && !"pick_dev".equals(h.id) && !"pick_grp".equals(h.id)
                    && !h.id.startsWith("lead_") && !"pop_bg".equals(h.id)) {
                offenPopover = "";
            }
            switch (h.id) {
                case "zurueck":
                    if (aktionen != null) aktionen.zurueck();
                    break;
                case "pop_dev":
                    offenPopover = "dev".equals(offenPopover) ? "" : "dev";
                    popoverScrollY = 0f;
                    invalidate();
                    break;
                case "pop_grp":
                    offenPopover = "grp".equals(offenPopover) ? "" : "grp";
                    popoverScrollY = 0f;
                    invalidate();
                    break;
                case "pop_versatz":
                    offenPopover = "versatz".equals(offenPopover) ? "" : "versatz";
                    invalidate();
                    break;
                case "lyr":
                    lyricsAn = !lyricsAn;
                    callService("input_boolean", "toggle", "input_boolean.musik_liedtext", null);
                    if (lyricsAn) pruefeLiedtext(pl);
                    else if ("versatz".equals(offenPopover)) offenPopover = "";
                    schleife();
                    invalidate();
                    break;
                case "apl":
                    queueOption("autoplay");
                    break;
                case "xf":
                    queueOption("crossfade");
                    break;
                case "prev":
                    if (pl != null) callService("media_player", "media_previous_track", pl.entityId, null);
                    break;
                case "play":
                    if (pl != null) {
                        pl.state = "playing".equals(pl.state) ? "paused" : "playing";
                        callService("media_player", "media_play_pause", pl.entityId, null);
                        schleife();
                        invalidate();
                    }
                    break;
                case "next":
                    if (pl != null) callService("media_player", "media_next_track", pl.entityId, null);
                    break;
                case "vol_m":
                    if (pl != null) {
                        double cur = pl.volume != null ? pl.volume : 0.0;
                        double nv = Math.round(Math.max(0.0, Math.min(1.0, cur - 0.05)) * 100.0) / 100.0;
                        pl.volume = nv;
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("volume_level", nv);
                        callService("media_player", "volume_set", pl.entityId, d);
                        invalidate();
                    }
                    break;
                case "vol_p":
                    if (pl != null) {
                        double cur = pl.volume != null ? pl.volume : 0.0;
                        double nv = Math.round(Math.max(0.0, Math.min(1.0, cur + 0.05)) * 100.0) / 100.0;
                        pl.volume = nv;
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("volume_level", nv);
                        callService("media_player", "volume_set", pl.entityId, d);
                        invalidate();
                    }
                    break;
                case "seek_line":
                    try {
                        seekTo(pl, Double.parseDouble(h.arg));
                    } catch (NumberFormatException ignored) {}
                    break;
                case "pick_dev":
                    verschiebeZu(h.arg);
                    break;
                case "pick_grp":
                    gruppeUmschalten(h.arg);
                    break;
                case "lead_m":
                    versatzAendern(-0.25, false);
                    break;
                case "lead_p":
                    versatzAendern(0.25, false);
                    break;
                case "lead_0":
                    versatzAendern(0.0, true);
                    break;
            }
        }

        private void drawBitmapCenterCrop(Canvas c, Bitmap bm, float x, float y, float w, float h, int alpha) {
            int bw = bm.getWidth(), bh = bm.getHeight();
            if (bw <= 0 || bh <= 0) return;
            float sc = Math.max(w / bw, h / bh);
            float sw = w / sc, sh = h / sc;
            int sl = Math.max(0, Math.round((bw - sw) * 0.5f));
            int st = Math.max(0, Math.round((bh - sh) * 0.5f));
            srcR.set(sl, st, Math.min(bw, Math.round(sl + sw)), Math.min(bh, Math.round(st + sh)));
            rf.set(x, y, x + w, y + h);
            bPaint.setAlpha(alpha);
            c.drawBitmap(bm, srcR, rf, bPaint);
            bPaint.setAlpha(255);
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
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            lPaint.setColor(color);
            lPaint.setStrokeWidth(2.2f);
            iconTmp.reset();
            switch (name) {
                case "mdi:chevron-down":
                    iconTmp.moveTo(6f, 9f); iconTmp.lineTo(12f, 15f); iconTmp.lineTo(18f, 9f);
                    c.drawPath(iconTmp, lPaint);
                    break;
                case "mdi:chevron-up":
                    iconTmp.moveTo(6f, 15f); iconTmp.lineTo(12f, 9f); iconTmp.lineTo(18f, 15f);
                    c.drawPath(iconTmp, lPaint);
                    break;
                case "mdi:play":
                    iconTmp.moveTo(8f, 5.5f); iconTmp.lineTo(19f, 12f); iconTmp.lineTo(8f, 18.5f); iconTmp.close();
                    c.drawPath(iconTmp, p);
                    break;
                case "mdi:pause":
                    c.drawRoundRect(6.5f, 5.5f, 10.3f, 18.5f, 1.2f, 1.2f, p);
                    c.drawRoundRect(13.7f, 5.5f, 17.5f, 18.5f, 1.2f, 1.2f, p);
                    break;
                case "mdi:skip-previous":
                    c.drawRect(6f, 6f, 8.2f, 18f, p);
                    iconTmp.moveTo(18f, 6f); iconTmp.lineTo(9.5f, 12f); iconTmp.lineTo(18f, 18f); iconTmp.close();
                    c.drawPath(iconTmp, p);
                    break;
                case "mdi:skip-next":
                    iconTmp.moveTo(6f, 6f); iconTmp.lineTo(14.5f, 12f); iconTmp.lineTo(6f, 18f); iconTmp.close();
                    c.drawPath(iconTmp, p);
                    c.drawRect(15.8f, 6f, 18f, 18f, p);
                    break;
                case "mdi:plus":
                    c.drawLine(12f, 5f, 12f, 19f, lPaint);
                    c.drawLine(5f, 12f, 19f, 12f, lPaint);
                    break;
                case "mdi:minus":
                    c.drawLine(5f, 12f, 19f, 12f, lPaint);
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

    private static List<String> wrapLines(String text, Paint pt, float maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        String rest = text == null ? "" : text.trim();
        if (rest.isEmpty()) {
            out.add("");
            return out;
        }
        for (int z = 0; z < maxLines && !rest.isEmpty(); z++) {
            if (z == maxLines - 1 || pt.measureText(rest) <= maxW) {
                out.add(kuerzen(pt, rest, maxW));
                break;
            }
            int cut = rest.length();
            while (cut > 0 && pt.measureText(rest.substring(0, cut)) > maxW) cut--;
            int sp = rest.lastIndexOf(' ', cut);
            if (sp > 2) cut = sp;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        return out;
    }

    private static float drawWrapped(Canvas c, String text, Paint pt, float x, float y, float maxW, float lineH, int maxLines) {
        List<String> ls = wrapLines(text, pt, maxW, maxLines);
        for (int i = 0; i < ls.size(); i++) {
            c.drawText(ls.get(i), x, y + i * lineH, pt);
        }
        return y + (ls.size() - 1) * lineH;
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
}
