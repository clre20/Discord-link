package clre20.discordLink.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品圖示：依原版的物品定義（items/*.json）與模型（models/*.json）畫出跟背包裡一樣的圖示。
 * 平面物品直接畫材質層；方塊類用簡單的正交投影畫出每個面。
 * 結果依「模型 + 色調」快取（有上限）。只在繪圖執行緒使用。
 */
final class IconRenderer {

    private static final int CACHE = 256;
    private static final int MAX_PARENTS = 16;
    private static final int GRASS = 0x7CBD6B;
    private static final int FOLIAGE = 0x48B518;

    private record Resolved(String model, int[] tints) {
    }

    private record Face(double[][] screen, double depth, BufferedImage tex, double[] uv, int rotation, float shade, int tint) {
    }

    private final AssetPack assets;
    private final Map<String, BufferedImage> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > CACHE;
        }
    };

    IconRenderer(AssetPack assets) {
        this.assets = assets;
    }

    /**
     * @param itemId 物品 ID（例如 diamond_sword，或 item_model 的路徑）
     * @param size   輸出邊長（像素）
     * @return 圖示；找不到模型時回傳 null
     */
    BufferedImage icon(String itemId, int size) {
        String key = itemId + "@" + size;
        BufferedImage cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        BufferedImage img = null;
        try {
            Resolved r = resolveItem(itemId);
            if (r != null) {
                img = renderModel(r, size, itemId.endsWith("shulker_box") || itemId.endsWith("chest"));
            }
        } catch (RuntimeException ignored) {
            img = null;
        }
        if (img != null) {
            cache.put(key, img);
        }
        return img;
    }

    // ---------- 物品定義 ----------

    private Resolved resolveItem(String itemId) {
        if (!itemId.matches("[a-z0-9_./-]{1,128}") || itemId.contains("..")) {
            return null;
        }
        JsonObject def = assets.json("assets/minecraft/items/" + itemId + ".json");
        if (def != null && def.has("model")) {
            Resolved r = resolveNode(def.getAsJsonObject("model"), 0);
            if (r != null) {
                return r;
            }
        }
        // 沒有物品定義時直接找同名模型
        if (assets.json("assets/minecraft/models/item/" + itemId + ".json") != null) {
            return new Resolved("item/" + itemId, new int[0]);
        }
        return null;
    }

    private Resolved resolveNode(JsonObject node, int depth) {
        if (node == null || depth > 8) {
            return null;
        }
        String type = str(node, "type");
        if (type == null) {
            return null;
        }
        type = type.replace("minecraft:", "");
        switch (type) {
            case "model" -> {
                String m = str(node, "model");
                return m == null ? null : new Resolved(strip(m), tints(node.getAsJsonArray("tints")));
            }
            case "special" -> {
                String base = str(node, "base");
                return base == null ? null : new Resolved(strip(base), new int[0]);
            }
            case "condition" -> {
                return resolveNode(obj(node, "on_false"), depth + 1);
            }
            case "select", "range_dispatch" -> {
                JsonObject fb = obj(node, "fallback");
                if (fb != null) {
                    return resolveNode(fb, depth + 1);
                }
                JsonArray list = node.has("cases") ? node.getAsJsonArray("cases") : node.getAsJsonArray("entries");
                if (list != null && !list.isEmpty()) {
                    return resolveNode(obj(list.get(0).getAsJsonObject(), "model"), depth + 1);
                }
                return null;
            }
            case "composite" -> {
                JsonArray models = node.getAsJsonArray("models");
                return models == null || models.isEmpty() ? null : resolveNode(models.get(0).getAsJsonObject(), depth + 1);
            }
            default -> {
                return null;
            }
        }
    }

    private static int[] tints(JsonArray arr) {
        if (arr == null) {
            return new int[0];
        }
        int[] out = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            JsonObject t = arr.get(i).getAsJsonObject();
            String type = String.valueOf(str(t, "type")).replace("minecraft:", "");
            if (t.has("value")) {
                out[i] = t.get("value").getAsInt() & 0xFFFFFF;
            } else if (t.has("default")) {
                out[i] = t.get("default").getAsInt() & 0xFFFFFF;
            } else if (type.equals("grass")) {
                out[i] = GRASS;
            } else {
                out[i] = 0xFFFFFF;
            }
        }
        return out;
    }

    // ---------- 模型 ----------

    /** 箱子、界伏盒這類特殊模型沒有可用的面：用 particle 材質畫成立方體。 */
    private static final JsonArray CUBE = com.google.gson.JsonParser.parseString(
            "[{\"from\":[1,0,1],\"to\":[15,14,15],\"faces\":{"
                    + "\"up\":{\"texture\":\"#particle\"},\"north\":{\"texture\":\"#particle\"},"
                    + "\"south\":{\"texture\":\"#particle\"},\"east\":{\"texture\":\"#particle\"},"
                    + "\"west\":{\"texture\":\"#particle\"},\"down\":{\"texture\":\"#particle\"}}}]").getAsJsonArray();

    private BufferedImage renderModel(Resolved r, int size, boolean cubeFallback) {
        Map<String, String> textures = new HashMap<>();
        JsonArray elements = null;
        JsonObject gui = null;
        boolean generated = false;
        String path = r.model();
        for (int i = 0; i < MAX_PARENTS && path != null; i++) {
            if (path.equals("builtin/generated")) {
                generated = true;
                break;
            }
            JsonObject m = assets.json("assets/minecraft/models/" + path + ".json");
            if (m == null) {
                break;
            }
            JsonObject tex = obj(m, "textures");
            if (tex != null) {
                for (Map.Entry<String, JsonElement> e : tex.entrySet()) {
                    JsonElement v = e.getValue();
                    // 新版可寫成 {"sprite": "...", "force_translucent": true}
                    String ref = v.isJsonPrimitive() ? v.getAsString() : str(v.isJsonObject() ? v.getAsJsonObject() : null, "sprite");
                    if (ref != null) {
                        textures.putIfAbsent(e.getKey(), ref);
                    }
                }
            }
            if (elements == null && m.has("elements")) {
                elements = m.getAsJsonArray("elements");
            }
            JsonObject display = obj(m, "display");
            if (gui == null && display != null) {
                gui = obj(display, "gui");
            }
            String parent = str(m, "parent");
            path = parent == null ? null : strip(parent);
        }

        if (generated || elements == null) {
            List<BufferedImage> layers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String ref = resolveTexture(textures, "layer" + i);
                if (ref == null) {
                    break;
                }
                BufferedImage t = assets.texture(ref);
                if (t != null) {
                    layers.add(solidify(tint(t, i < r.tints().length ? r.tints()[i] : 0xFFFFFF, 1f)));
                }
            }
            if (layers.isEmpty() && cubeFallback && resolveTexture(textures, "particle") != null) {
                return renderElements(CUBE, textures, null, new int[0], size);
            }
            if (layers.isEmpty()) {
                // 特殊模型（盾牌、床等）：用 particle 材質代替
                String ref = resolveTexture(textures, "particle");
                BufferedImage t = ref == null ? null : assets.texture(ref);
                if (t == null) {
                    return null;
                }
                layers.add(t);
            }
            BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (BufferedImage l : layers) {
                g.drawImage(l, 0, 0, size, size, null);
            }
            g.dispose();
            return out;
        }
        return renderElements(elements, textures, gui, r.tints(), size);
    }

    private static String resolveTexture(Map<String, String> textures, String key) {
        String v = textures.get(key);
        for (int i = 0; i < 8 && v != null && v.startsWith("#"); i++) {
            v = textures.get(v.substring(1));
        }
        return v == null || v.startsWith("#") ? null : v;
    }

    private BufferedImage renderElements(JsonArray elements, Map<String, String> textures, JsonObject gui,
                                         int[] tints, int size) {
        double[] rot = vec(gui, "rotation", 30, 225, 0);
        double[] tr = vec(gui, "translation", 0, 0, 0);
        double[] sc = vec(gui, "scale", 0.625, 0.625, 0.625);

        List<Face> faces = new ArrayList<>();
        for (JsonElement ee : elements) {
            JsonObject el = ee.getAsJsonObject();
            double[] from = vec(el, "from", 0, 0, 0);
            double[] to = vec(el, "to", 16, 16, 16);
            JsonObject er = obj(el, "rotation");
            JsonObject fs = obj(el, "faces");
            if (fs == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> fe : fs.entrySet()) {
                String dir = fe.getKey();
                JsonObject f = fe.getValue().getAsJsonObject();
                String texRef = str(f, "texture");
                if (texRef == null) {
                    continue;
                }
                String resolved = texRef.startsWith("#") ? resolveTexture(textures, texRef.substring(1)) : texRef;
                BufferedImage tex = resolved == null ? null : assets.texture(resolved);
                if (tex == null) {
                    continue;
                }
                double[][] corners = corners(dir, from, to);
                if (corners == null) {
                    continue;
                }
                double[] normal = normal(dir);
                if (er != null) {
                    for (int i = 0; i < 4; i++) {
                        corners[i] = elementRotate(corners[i], er);
                    }
                    normal = elementRotateDir(normal, er);
                }
                double[][] screen = new double[4][];
                double depth = 0;
                for (int i = 0; i < 4; i++) {
                    double[] p = display(corners[i], rot, tr, sc);
                    screen[i] = new double[]{size / 2.0 + p[0] * size / 16.0, size / 2.0 - p[1] * size / 16.0};
                    depth += p[2];
                }
                double[] n = rotate(normal, rot);
                if (n[2] < -1e-4) {
                    continue; // 背面
                }
                float shade = n[1] > 0.5 ? 1f : n[1] < -0.5 ? 0.5f : n[0] < 0 ? 0.8f : 0.6f;
                int tintIndex = f.has("tintindex") ? f.get("tintindex").getAsInt() : -1;
                int tint = tintIndex < 0 ? 0xFFFFFF
                        : tintIndex < tints.length ? tints[tintIndex] : (resolved.contains("leaves") ? FOLIAGE : GRASS);
                double[] uv = f.has("uv") ? toArray(f.getAsJsonArray("uv")) : defaultUv(dir, from, to);
                int uvRot = f.has("rotation") ? f.get("rotation").getAsInt() : 0;
                faces.add(new Face(screen, depth / 4, tex, uv, uvRot, shade, tint));
            }
        }
        faces.sort((a, b) -> Double.compare(a.depth, b.depth));

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for (Face f : faces) {
            drawFace(g, f);
        }
        g.dispose();
        return out;
    }

    private static void drawFace(Graphics2D g, Face f) {
        BufferedImage tex = tint(f.tex, f.tint, f.shade);
        double tw = tex.getWidth() / 16.0, th = tex.getHeight() / 16.0;
        double[][] uvc = {
                {f.uv[0] * tw, f.uv[1] * th}, {f.uv[2] * tw, f.uv[1] * th},
                {f.uv[2] * tw, f.uv[3] * th}, {f.uv[0] * tw, f.uv[3] * th}};
        int k = ((f.rotation / 90) % 4 + 4) % 4;
        double[] t0 = uvc[(0 + k) % 4], t1 = uvc[(1 + k) % 4], t3 = uvc[(3 + k) % 4];
        double[] p0 = f.screen[0], p1 = f.screen[1], p3 = f.screen[3];
        double du1 = t1[0] - t0[0], dv1 = t1[1] - t0[1];
        double du3 = t3[0] - t0[0], dv3 = t3[1] - t0[1];
        double det = du1 * dv3 - du3 * dv1;
        if (Math.abs(det) < 1e-9) {
            return;
        }
        double ax = p1[0] - p0[0], ay = p1[1] - p0[1];
        double bx = p3[0] - p0[0], by = p3[1] - p0[1];
        // M * [du dv]^T = [px py]^T
        double m00 = (ax * dv3 - bx * dv1) / det;
        double m01 = (bx * du1 - ax * du3) / det;
        double m10 = (ay * dv3 - by * dv1) / det;
        double m11 = (by * du1 - ay * du3) / det;
        double m02 = p0[0] - m00 * t0[0] - m01 * t0[1];
        double m12 = p0[1] - m10 * t0[0] - m11 * t0[1];
        // 螢幕上面積太小（側面朝向鏡頭）就略過
        double area = Math.abs(ax * by - ay * bx);
        if (area < 0.5) {
            return;
        }
        Polygon poly = new Polygon();
        double cx = 0, cy = 0;
        for (double[] p : f.screen) {
            cx += p[0] / 4;
            cy += p[1] / 4;
        }
        for (double[] p : f.screen) {
            // 往外擴 0.5 像素，避免面與面之間出現縫隙
            double dx = p[0] - cx, dy = p[1] - cy, len = Math.hypot(dx, dy);
            double e = len < 1e-6 ? 0 : 0.5 / len;
            poly.addPoint((int) Math.round(p[0] + dx * e), (int) Math.round(p[1] + dy * e));
        }
        java.awt.Shape oldClip = g.getClip();
        g.setClip(poly);
        g.drawImage(tex, new AffineTransform(m00, m10, m01, m11, m02, m12), null);
        g.setClip(oldClip);
    }

    /** 半透明的平面物品（玻璃片等）在原版介面看起來比較不透明：把半透明像素的 alpha 加倍。 */
    private static BufferedImage solidify(BufferedImage src) {
        BufferedImage out = null;
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                int a = p >>> 24;
                if (a > 0 && a < 255) {
                    if (out == null) {
                        out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
                        out.getGraphics().drawImage(src, 0, 0, null);
                    }
                    out.setRGB(x, y, (Math.min(255, a * 2) << 24) | (p & 0xFFFFFF));
                }
            }
        }
        return out == null ? src : out;
    }

    /** 乘上色調與明暗。 */
    private static BufferedImage tint(BufferedImage src, int rgb, float shade) {
        if (rgb == 0xFFFFFF && shade >= 1f) {
            return src;
        }
        float tr = (rgb >> 16 & 0xFF) / 255f * shade, tg = (rgb >> 8 & 0xFF) / 255f * shade, tb = (rgb & 0xFF) / 255f * shade;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                int a = p >>> 24;
                if (a == 0) {
                    continue;
                }
                int r = (int) ((p >> 16 & 0xFF) * tr), g = (int) ((p >> 8 & 0xFF) * tg), b = (int) ((p & 0xFF) * tb);
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    // ---------- 幾何 ----------

    /** 四個角：左上、右上、右下、左下（從外面看這個面）。 */
    private static double[][] corners(String dir, double[] f, double[] t) {
        return switch (dir) {
            case "north" -> new double[][]{{t[0], t[1], f[2]}, {f[0], t[1], f[2]}, {f[0], f[1], f[2]}, {t[0], f[1], f[2]}};
            case "south" -> new double[][]{{f[0], t[1], t[2]}, {t[0], t[1], t[2]}, {t[0], f[1], t[2]}, {f[0], f[1], t[2]}};
            case "west" -> new double[][]{{f[0], t[1], f[2]}, {f[0], t[1], t[2]}, {f[0], f[1], t[2]}, {f[0], f[1], f[2]}};
            case "east" -> new double[][]{{t[0], t[1], t[2]}, {t[0], t[1], f[2]}, {t[0], f[1], f[2]}, {t[0], f[1], t[2]}};
            case "up" -> new double[][]{{f[0], t[1], f[2]}, {t[0], t[1], f[2]}, {t[0], t[1], t[2]}, {f[0], t[1], t[2]}};
            case "down" -> new double[][]{{f[0], f[1], t[2]}, {t[0], f[1], t[2]}, {t[0], f[1], f[2]}, {f[0], f[1], f[2]}};
            default -> null;
        };
    }

    private static double[] normal(String dir) {
        return switch (dir) {
            case "north" -> new double[]{0, 0, -1};
            case "south" -> new double[]{0, 0, 1};
            case "west" -> new double[]{-1, 0, 0};
            case "east" -> new double[]{1, 0, 0};
            case "up" -> new double[]{0, 1, 0};
            default -> new double[]{0, -1, 0};
        };
    }

    private static double[] defaultUv(String dir, double[] f, double[] t) {
        return switch (dir) {
            case "down" -> new double[]{f[0], 16 - t[2], t[0], 16 - f[2]};
            case "up" -> new double[]{f[0], f[2], t[0], t[2]};
            case "north" -> new double[]{16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
            case "south" -> new double[]{f[0], 16 - t[1], t[0], 16 - f[1]};
            case "west" -> new double[]{f[2], 16 - t[1], t[2], 16 - f[1]};
            default -> new double[]{16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};
        };
    }

    private static double[] elementRotate(double[] p, JsonObject r) {
        double[] o = vec(r, "origin", 8, 8, 8);
        double[] d = {p[0] - o[0], p[1] - o[1], p[2] - o[2]};
        double[] q = elementRotateDir(d, r);
        return new double[]{q[0] + o[0], q[1] + o[1], q[2] + o[2]};
    }

    private static double[] elementRotateDir(double[] d, JsonObject r) {
        double a = Math.toRadians(r.has("angle") ? r.get("angle").getAsDouble() : 0);
        String axis = str(r, "axis");
        if (axis == null || a == 0) {
            return d;
        }
        return switch (axis) {
            case "x" -> rotX(d, a);
            case "y" -> rotY(d, a);
            default -> rotZ(d, a);
        };
    }

    /** 方塊座標（0～16）→ GUI 座標（以中心為原點，y 向上）。 */
    private static double[] display(double[] p, double[] rot, double[] tr, double[] sc) {
        double[] v = {(p[0] - 8) * sc[0], (p[1] - 8) * sc[1], (p[2] - 8) * sc[2]};
        v = rotate(v, rot);
        return new double[]{v[0] + tr[0], v[1] + tr[1], v[2] + tr[2]};
    }

    /** 與原版相同的順序：先 Z、再 Y、最後 X。 */
    private static double[] rotate(double[] v, double[] rot) {
        double[] r = rotZ(v, Math.toRadians(rot[2]));
        r = rotY(r, Math.toRadians(rot[1]));
        return rotX(r, Math.toRadians(rot[0]));
    }

    private static double[] rotX(double[] v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c};
    }

    private static double[] rotY(double[] v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c};
    }

    private static double[] rotZ(double[] v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]};
    }

    // ---------- JSON ----------

    private static String strip(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static double[] vec(JsonObject o, String k, double a, double b, double c) {
        if (o == null || !o.has(k) || !o.get(k).isJsonArray()) {
            return new double[]{a, b, c};
        }
        return toArray(o.getAsJsonArray(k));
    }

    private static double[] toArray(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < a.size(); i++) {
            out[i] = a.get(i).getAsDouble();
        }
        return out;
    }
}
