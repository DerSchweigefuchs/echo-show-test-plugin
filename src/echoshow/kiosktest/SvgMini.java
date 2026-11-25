package echoshow.kiosktest;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the small SVG subset the figure is built of (figuren.js): path (M L H V Q C Z, upper and lower case), circle,
 * ellipse, rect, g with transform (translate, rotate, scale), opacity and one clip path, the two shading gradients of
 * the page (wf-rd across, wf-hr down) and stroke dashes. Colours pass through a hook so the figure takes the light of
 * the hour. The figure is split into layers by its animated groups: 0 body, 1 head (wf-head), 2 everything drawn after
 * the head (scarf), 3 the dog's tail (wf-tail); each layer can be drawn alone, with all transforms and clips around it.
 */
final class SvgMini {
    static final int KOERPER = 0, KOPF = 1, VORNE = 2, SCHWANZ = 3, SCHICHTEN = 4;

    interface Farbe {
        /** argb of a colour; hex = written as #rrggbb in the SVG (fabric, skin, hair), otherwise rgba (shades). */
        int f(int argb, boolean hex);
    }

    static final class Knoten {
        final String tag;
        final Map<String, String> a = new HashMap<>();
        final List<Knoten> kinder = new ArrayList<>();

        Knoten(String tag) { this.tag = tag; }
    }

    // ---- parsing ------------------------------------------------------------------------------------------------------

    static Knoten parse(String svg) {
        Knoten root = new Knoten("g");
        List<Knoten> stapel = new ArrayList<>();
        stapel.add(root);
        int i = 0, n = svg.length();
        while (i < n) {
            int lt = svg.indexOf('<', i);
            if (lt < 0) break;
            int gt = svg.indexOf('>', lt);
            if (gt < 0) break;
            String inhalt = svg.substring(lt + 1, gt).trim();
            i = gt + 1;
            if (inhalt.startsWith("/")) {
                if (stapel.size() > 1) stapel.remove(stapel.size() - 1);
                continue;
            }
            boolean leer = inhalt.endsWith("/");
            if (leer) inhalt = inhalt.substring(0, inhalt.length() - 1).trim();
            int sp = 0;
            while (sp < inhalt.length() && !Character.isWhitespace(inhalt.charAt(sp))) sp++;
            Knoten k = new Knoten(inhalt.substring(0, sp));
            int p = sp;
            while (p < inhalt.length()) {
                int eq = inhalt.indexOf('=', p);
                if (eq < 0) break;
                String name = inhalt.substring(p, eq).trim();
                int q1 = inhalt.indexOf('"', eq), q2 = q1 < 0 ? -1 : inhalt.indexOf('"', q1 + 1);
                if (q1 < 0 || q2 < 0) break;
                k.a.put(name, inhalt.substring(q1 + 1, q2));
                p = q2 + 1;
            }
            stapel.get(stapel.size() - 1).kinder.add(k);
            if (!leer) stapel.add(k);
        }
        return root;
    }

    static Path pfad(String d) {
        Path p = new Path();
        double cx = 0, cy = 0, sx = 0, sy = 0;
        char cmd = 'M';
        int i = 0, n = d.length();
        double[] z = new double[6];
        while (i < n) {
            char ch = d.charAt(i);
            if (Character.isLetter(ch)) {
                cmd = ch;
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    p.close();
                    cx = sx;
                    cy = sy;
                }
                continue;
            }
            if (ch == ' ' || ch == ',' || ch == '\n' || ch == '\t') {
                i++;
                continue;
            }
            int anz = "MmLlTt".indexOf(cmd) >= 0 ? 2 : ("HhVv".indexOf(cmd) >= 0 ? 1 : ("QqSs".indexOf(cmd) >= 0 ? 4 : ("Cc".indexOf(cmd) >= 0 ? 6 : 2)));
            for (int k = 0; k < anz; k++) {
                while (i < n && (d.charAt(i) == ' ' || d.charAt(i) == ',')) i++;
                int st = i;
                if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
                boolean punkt = false;
                while (i < n) {
                    char c2 = d.charAt(i);
                    if (Character.isDigit(c2)) i++;
                    else if (c2 == '.' && !punkt) { punkt = true; i++; }
                    else if ((c2 == 'e' || c2 == 'E') && i + 1 < n && (Character.isDigit(d.charAt(i + 1)) || d.charAt(i + 1) == '-')) { i += 2; }
                    else break;
                }
                z[k] = st == i ? 0 : Double.parseDouble(d.substring(st, i));
            }
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M':
                    cx = rel ? cx + z[0] : z[0];
                    cy = rel ? cy + z[1] : z[1];
                    p.moveTo((float) cx, (float) cy);
                    sx = cx;
                    sy = cy;
                    cmd = rel ? 'l' : 'L';   // further pairs are lines
                    break;
                case 'L':
                    cx = rel ? cx + z[0] : z[0];
                    cy = rel ? cy + z[1] : z[1];
                    p.lineTo((float) cx, (float) cy);
                    break;
                case 'H':
                    cx = rel ? cx + z[0] : z[0];
                    p.lineTo((float) cx, (float) cy);
                    break;
                case 'V':
                    cy = rel ? cy + z[0] : z[0];
                    p.lineTo((float) cx, (float) cy);
                    break;
                case 'Q': {
                    double x1 = rel ? cx + z[0] : z[0], y1 = rel ? cy + z[1] : z[1], x = rel ? cx + z[2] : z[2], y = rel ? cy + z[3] : z[3];
                    p.quadTo((float) x1, (float) y1, (float) x, (float) y);
                    cx = x;
                    cy = y;
                    break;
                }
                case 'C': {
                    double x1 = rel ? cx + z[0] : z[0], y1 = rel ? cy + z[1] : z[1], x2 = rel ? cx + z[2] : z[2], y2 = rel ? cy + z[3] : z[3], x = rel ? cx + z[4] : z[4], y = rel ? cy + z[5] : z[5];
                    p.cubicTo((float) x1, (float) y1, (float) x2, (float) y2, (float) x, (float) y);
                    cx = x;
                    cy = y;
                    break;
                }
                default:
                    i++;
            }
        }
        return p;
    }

    static int farbe(String s) {
        if (s == null || s.isEmpty() || "none".equals(s)) return 0;
        if (s.startsWith("#")) return WetterLage.hex(s);
        if (s.startsWith("rgba(") || s.startsWith("rgb(")) {
            String[] t = s.substring(s.indexOf('(') + 1, s.indexOf(')')).split(",");
            int r = (int) Double.parseDouble(t[0].trim()), g = (int) Double.parseDouble(t[1].trim()), b = (int) Double.parseDouble(t[2].trim());
            double a = t.length > 3 ? Double.parseDouble(t[3].trim()) : 1;
            return ((int) Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (r << 16) | (g << 8) | b;
        }
        return 0;
    }

    // ---- drawing / measuring --------------------------------------------------------------------------------------------

    /** One pass over the tree: draws (c != null) or measures (bounds per layer in the root's coordinates). */
    static final class Lauf {
        final Canvas c;
        final int schicht;
        final Farbe farbe;
        final RectF[] grenzen = new RectF[SCHICHTEN];
        final float[] kopfDreh = new float[2], schwanzDreh = new float[2];   // pivots of the head and tail in root coordinates
        private final Map<String, String> clips = new HashMap<>();
        private boolean nachKopf;
        private final List<double[]> matrizen = new ArrayList<>();
        private double[] m = {1, 0, 0, 1, 0, 0};   // a b c d e f: x' = a x + c y + e, y' = b x + d y + f
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Map<String, Path> pfade = new LinkedHashMap<>();

        Lauf(Canvas c, int schicht, Farbe farbe) {
            this.c = c;
            this.schicht = schicht;
            this.farbe = farbe;
        }

        void lauf(Knoten k) { knoten(k, -1, 1); }

        private void knoten(Knoten k, int erzwungen, float op) {
            String tag = k.tag;
            if ("clipPath".equals(tag)) {
                for (Knoten x : k.kinder) if ("path".equals(x.tag)) clips.put(k.a.get("id"), x.a.get("d"));
                return;
            }
            if ("defs".equals(tag)) return;
            String tr = k.a.get("transform");
            float o = op * (k.a.containsKey("opacity") ? Float.parseFloat(k.a.get("opacity")) : 1);
            if ("g".equals(tag)) {
                String cls = k.a.get("class");
                int sch = erzwungen;
                if ("wf-head".equals(cls)) sch = KOPF;
                else if ("wf-tail".equals(cls)) sch = SCHWANZ;
                speichern();
                if (tr != null) transform(tr);
                if ("wf-head".equals(cls)) merke(kopfDreh, 0, -110);
                if ("wf-tail".equals(cls)) merke(schwanzDreh, -37, -3);
                String clip = k.a.get("clip-path");
                if (clip != null && c != null) {
                    String id = clip.substring(clip.indexOf('#') + 1, clip.lastIndexOf(')'));
                    if (clips.containsKey(id)) c.clipPath(pfad(clips.get(id)));
                }
                for (Knoten x : k.kinder) knoten(x, sch, o);
                zurueck();
                if ("wf-head".equals(cls)) nachKopf = true;
                return;
            }
            int sch = erzwungen >= 0 ? erzwungen : (nachKopf ? VORNE : KOERPER);
            speichern();
            if (tr != null) transform(tr);
            blatt(k, sch, o);
            zurueck();
        }

        private void merke(float[] ziel, double x, double y) {
            ziel[0] = (float) (m[0] * x + m[2] * y + m[4]);
            ziel[1] = (float) (m[1] * x + m[3] * y + m[5]);
        }

        private void speichern() {
            matrizen.add(m.clone());
            if (c != null) c.save();
        }

        private void zurueck() {
            m = matrizen.remove(matrizen.size() - 1);
            if (c != null) c.restore();
        }

        private void mult(double a, double b, double cc, double d, double e, double f) {
            double[] o = m;
            m = new double[] {o[0] * a + o[2] * b, o[1] * a + o[3] * b, o[0] * cc + o[2] * d, o[1] * cc + o[3] * d, o[0] * e + o[2] * f + o[4], o[1] * e + o[3] * f + o[5]};
        }

        private void transform(String tr) {
            int i = 0;
            while (i < tr.length()) {
                int auf = tr.indexOf('(', i), zu = tr.indexOf(')', auf);
                if (auf < 0 || zu < 0) break;
                String name = tr.substring(i, auf).trim();
                String[] v = tr.substring(auf + 1, zu).trim().split("[ ,]+");
                double[] z = new double[v.length];
                for (int k = 0; k < v.length; k++) z[k] = Double.parseDouble(v[k]);
                if ("translate".equals(name)) {
                    double ty = z.length > 1 ? z[1] : 0;
                    mult(1, 0, 0, 1, z[0], ty);
                    if (c != null) c.translate((float) z[0], (float) ty);
                } else if ("rotate".equals(name)) {
                    double px = z.length > 2 ? z[1] : 0, py = z.length > 2 ? z[2] : 0, r = Math.toRadians(z[0]);
                    mult(1, 0, 0, 1, px, py);
                    mult(Math.cos(r), Math.sin(r), -Math.sin(r), Math.cos(r), 0, 0);
                    mult(1, 0, 0, 1, -px, -py);
                    if (c != null) {
                        c.translate((float) px, (float) py);
                        c.rotate((float) z[0]);
                        c.translate((float) -px, (float) -py);
                    }
                } else if ("scale".equals(name)) {
                    double sy = z.length > 1 ? z[1] : z[0];
                    mult(z[0], 0, 0, sy, 0, 0);
                    if (c != null) c.scale((float) z[0], (float) sy);
                }
                i = zu + 1;
            }
        }

        private Path form(Knoten k) {
            String tag = k.tag;
            if ("path".equals(tag)) {
                String d = k.a.get("d");
                Path pp = pfade.get(d);
                if (pp == null) {
                    pp = pfad(d);
                    pfade.put(d, pp);
                }
                return pp;
            }
            Path pp = new Path();
            if ("circle".equals(tag)) pp.addCircle(z(k, "cx"), z(k, "cy"), z(k, "r"), Path.Direction.CW);
            else if ("ellipse".equals(tag)) pp.addOval(new RectF(z(k, "cx") - z(k, "rx"), z(k, "cy") - z(k, "ry"), z(k, "cx") + z(k, "rx"), z(k, "cy") + z(k, "ry")), Path.Direction.CW);
            else if ("rect".equals(tag)) {
                float x = z(k, "x"), y = z(k, "y"), w = z(k, "width"), h = z(k, "height"), rx = z(k, "rx");
                if (rx > 0) pp.addRoundRect(new RectF(x, y, x + w, y + h), rx, rx, Path.Direction.CW);
                else pp.addRect(x, y, x + w, y + h, Path.Direction.CW);
            } else return null;
            return pp;
        }

        private static float z(Knoten k, String n) {
            String v = k.a.get(n);
            return v == null ? 0 : Float.parseFloat(v);
        }

        private void blatt(Knoten k, int sch, float op) {
            Path form = form(k);
            if (form == null) return;
            String fill = k.a.containsKey("fill") ? k.a.get("fill") : ("path".equals(k.tag) && k.a.containsKey("stroke") ? "none" : "#000000");
            String stroke = k.a.get("stroke");
            float sw = k.a.containsKey("stroke-width") ? Float.parseFloat(k.a.get("stroke-width")) : 1;
            if (c == null) {   // measure
                RectF b = new RectF();
                form.computeBounds(b, true);
                float halb = stroke != null && !"none".equals(stroke) ? sw / 2 + 1 : 1;
                b.set(b.left - halb, b.top - halb, b.right + halb, b.bottom + halb);
                double[][] ecken = {{b.left, b.top}, {b.right, b.top}, {b.left, b.bottom}, {b.right, b.bottom}};
                RectF g = grenzen[sch] == null ? null : grenzen[sch];
                for (double[] e : ecken) {
                    float x = (float) (m[0] * e[0] + m[2] * e[1] + m[4]), y = (float) (m[1] * e[0] + m[3] * e[1] + m[5]);
                    if (g == null) {
                        g = new RectF(x, y, x, y);
                        grenzen[sch] = g;
                    } else g.union(x, y);
                }
                return;
            }
            if (sch != schicht) return;
            if (fill != null && !"none".equals(fill)) {
                p.reset();
                p.setAntiAlias(true);
                p.setStyle(Paint.Style.FILL);
                if (fill.startsWith("url(")) {
                    RectF b = new RectF();
                    form.computeBounds(b, true);
                    if (fill.contains("wf-hr")) p.setShader(new LinearGradient(0, b.top, 0, b.bottom, new int[] {0x2EFFFFFF, 0x00FFFFFF, 0x38000000}, new float[] {0, .5f, 1}, Shader.TileMode.CLAMP));
                    else p.setShader(new LinearGradient(b.left, 0, b.right, 0, new int[] {0x1FFFFFFF, 0x00FFFFFF, 0x00000000, 0x4D000000}, new float[] {0, .38f, .62f, 1}, Shader.TileMode.CLAMP));
                    p.setAlpha(Math.round(255 * op));
                } else {
                    p.setColor(farbe.f(farbe(fill), fill.startsWith("#")));
                    p.setAlpha(Math.round(((p.getColor() >>> 24) & 255) * op));
                }
                c.drawPath(form, p);
            }
            if (stroke != null && !"none".equals(stroke)) {
                p.reset();
                p.setAntiAlias(true);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw);
                p.setStrokeCap("butt".equals(k.a.get("stroke-linecap")) || !k.a.containsKey("stroke-linecap") ? Paint.Cap.BUTT : Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                String dash = k.a.get("stroke-dasharray");
                if (dash != null) {
                    String[] v = dash.trim().split("[ ,]+");
                    float[] iv = new float[v.length % 2 == 0 ? v.length : v.length * 2];
                    for (int i = 0; i < iv.length; i++) iv[i] = Float.parseFloat(v[i % v.length]);
                    p.setPathEffect(new DashPathEffect(iv, 0));
                }
                p.setColor(farbe.f(farbe(stroke), stroke.startsWith("#")));
                p.setAlpha(Math.round(((p.getColor() >>> 24) & 255) * op));
                c.drawPath(form, p);
            }
        }
    }
}
