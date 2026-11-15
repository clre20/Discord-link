package clre20.discordLink.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 原版字型（點陣圖 ascii／accented／nonlatin_european ＋ unifont）。
 * <p>
 * 座標單位是「GUI 單位」：一個 ascii 字高 8 單位；畫圖時乘上縮放倍率。
 * unifont 只載入基本多文種平面（U+0000～U+FFFF），用陣列存放，約 2.5 MB，第一次需要時才讀取。
 */
final class GlyphFont {

    /** 點陣圖字：alpha 遮罩。 */
    private record Bitmap(byte[] mask, int w, int h, float scale, int ascent, int width) {
        float advance() {
            return (int) (0.5f + width * scale) + 1;
        }
    }

    private record Override(int from, int to, int left, int right) {
    }

    private final Map<Integer, Float> spaces = new HashMap<>();
    private final Map<Integer, Bitmap> bitmaps = new HashMap<>();
    private final AssetPack assets;

    private boolean unifontLoaded;
    private int[] uniOffset;
    private byte[] uniWidth;
    private byte[] uniBlob;
    private final List<Override> overrides = new ArrayList<>();

    GlyphFont(AssetPack assets) {
        this.assets = assets;
        spaces.put((int) ' ', 4f);
        spaces.put(0x200c, 0f);
        JsonObject def = assets.json("assets/minecraft/font/include/default.json");
        if (def != null) {
            for (JsonElement e : def.getAsJsonArray("providers")) {
                JsonObject p = e.getAsJsonObject();
                if ("bitmap".equals(str(p, "type"))) {
                    loadBitmap(p);
                }
            }
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsString() : null;
    }

    private void loadBitmap(JsonObject p) {
        String file = str(p, "file");
        if (file == null) {
            return;
        }
        String path = "assets/minecraft/textures/" + file.replace("minecraft:", "");
        BufferedImage img = assets.rawImage(path);
        JsonArray rows = p.getAsJsonArray("chars");
        if (img == null || rows == null || rows.isEmpty()) {
            return;
        }
        int height = p.has("height") ? p.get("height").getAsInt() : 8;
        int ascent = p.has("ascent") ? p.get("ascent").getAsInt() : 7;
        int[][] cps = new int[rows.size()][];
        int cols = 0;
        for (int r = 0; r < rows.size(); r++) {
            cps[r] = rows.get(r).getAsString().codePoints().toArray();
            cols = Math.max(cols, cps[r].length);
        }
        int cw = img.getWidth() / cols;
        int ch = img.getHeight() / rows.size();
        float scale = height / (float) ch;
        for (int r = 0; r < cps.length; r++) {
            for (int c = 0; c < cps[r].length; c++) {
                int cp = cps[r][c];
                if (cp == 0 || bitmaps.containsKey(cp)) {
                    continue;
                }
                byte[] mask = new byte[cw * ch];
                int right = -1;
                for (int y = 0; y < ch; y++) {
                    for (int x = 0; x < cw; x++) {
                        int a = (img.getRGB(c * cw + x, r * ch + y) >>> 24);
                        if (a > 0) {
                            mask[y * cw + x] = (byte) a;
                            right = Math.max(right, x);
                        }
                    }
                }
                bitmaps.put(cp, new Bitmap(mask, cw, ch, scale, ascent, right + 1));
            }
        }
    }

    // ---------- unifont ----------

    private void ensureUnifont() {
        if (unifontLoaded) {
            return;
        }
        unifontLoaded = true;
        uniOffset = new int[65536];
        Arrays.fill(uniOffset, -1);
        uniWidth = new byte[65536];
        try {
            if (assets.unifontJson().isFile()) {
                JsonObject o = JsonParser.parseString(Files.readString(assets.unifontJson().toPath())).getAsJsonObject();
                for (JsonElement e : o.getAsJsonArray("providers")) {
                    JsonObject p = e.getAsJsonObject();
                    if (p.has("filter") || !"minecraft:font/unifont.zip".equals(str(p, "hex_file"))) {
                        continue;
                    }
                    JsonArray arr = p.getAsJsonArray("size_overrides");
                    if (arr != null) {
                        for (JsonElement s : arr) {
                            JsonObject so = s.getAsJsonObject();
                            overrides.add(new Override(str(so, "from").codePointAt(0), str(so, "to").codePointAt(0),
                                    so.get("left").getAsInt(), so.get("right").getAsInt()));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (!assets.unifontZip().isFile()) {
            return;
        }
        java.io.ByteArrayOutputStream blob = new java.io.ByteArrayOutputStream(2_600_000);
        try (ZipFile zip = new ZipFile(assets.unifontZip())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".hex") || e.getName().contains("/")) {
                    continue;
                }
                try (BufferedReader r = new BufferedReader(new InputStreamReader(zip.getInputStream(e), StandardCharsets.US_ASCII))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int colon = line.indexOf(':');
                        if (colon < 4 || colon > 6) {
                            continue;
                        }
                        int cp = Integer.parseInt(line, 0, colon, 16);
                        if (cp > 0xFFFF || uniOffset[cp] >= 0) {
                            continue;
                        }
                        int hexLen = line.length() - colon - 1;
                        int w = hexLen * 4 / 16; // 每列位元數
                        if (w != 8 && w != 16) {
                            continue;
                        }
                        uniOffset[cp] = blob.size();
                        uniWidth[cp] = (byte) w;
                        for (int i = colon + 1; i + 1 < line.length(); i += 2) {
                            blob.write(Integer.parseInt(line, i, i + 2, 16));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        uniBlob = blob.toByteArray();
    }

    private boolean uniHas(int cp) {
        if (cp > 0xFFFF) {
            return false;
        }
        ensureUnifont();
        return uniOffset[cp] >= 0;
    }

    private boolean uniBit(int cp, int x, int y) {
        int w = uniWidth[cp];
        int bytesPerRow = w / 8;
        int b = uniBlob[uniOffset[cp] + y * bytesPerRow + x / 8] & 0xFF;
        return (b & (0x80 >> (x % 8))) != 0;
    }

    private int[] uniBounds(int cp) {
        for (Override o : overrides) {
            if (cp >= o.from && cp <= o.to) {
                return new int[]{o.left, o.right};
            }
        }
        int w = uniWidth[cp];
        int left = w, right = -1;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < w; x++) {
                if (uniBit(cp, x, y)) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        return right < 0 ? new int[]{0, -1} : new int[]{left, right};
    }

    // ---------- 量測與繪製 ----------

    /** 字元寬度（GUI 單位，不含粗體）。 */
    float advance(int cp) {
        Float s = spaces.get(cp);
        if (s != null) {
            return s;
        }
        Bitmap b = bitmaps.get(cp);
        if (b != null) {
            return b.advance();
        }
        if (uniHas(cp)) {
            int[] lr = uniBounds(cp);
            return lr[1] < 0 ? 4 : (lr[1] - lr[0] + 1) / 2f + 1;
        }
        return 4;
    }

    float width(List<TextRun> runs) {
        float w = 0;
        for (TextRun r : runs) {
            for (int cp : r.text().codePoints().toArray()) {
                w += advance(cp) + (r.bold() ? 1 : 0);
            }
        }
        return w;
    }

    /**
     * 畫一行文字。
     *
     * @param x     起點（GUI 單位）
     * @param top   行的頂端（GUI 單位）
     * @param s     縮放倍率（1 GUI 單位 = s 像素）
     * @param shadow 是否畫陰影
     * @return 結束的 x
     */
    float draw(Graphics2D g, List<TextRun> runs, float x, float top, int s, boolean shadow) {
        if (shadow) {
            float sx = x;
            for (TextRun r : runs) {
                sx = drawRun(g, r, sx + 1, top + 1, s, shadowColor(r.rgb())) - 1;
            }
        }
        for (TextRun r : runs) {
            x = drawRun(g, r, x, top, s, r.rgb());
        }
        return x;
    }

    private static int shadowColor(int rgb) {
        int r = (rgb >> 16 & 0xFF) / 4, gg = (rgb >> 8 & 0xFF) / 4, b = (rgb & 0xFF) / 4;
        return (r << 16) | (gg << 8) | b;
    }

    private float drawRun(Graphics2D g, TextRun run, float x, float top, int s, int rgb) {
        float start = x;
        for (int cp : run.text().codePoints().toArray()) {
            float adv = advance(cp);
            drawGlyph(g, cp, x, top, s, rgb, run.italic());
            if (run.bold()) {
                drawGlyph(g, cp, x + 1, top, s, rgb, run.italic());
                adv += 1;
            }
            x += adv;
        }
        g.setColor(new java.awt.Color(rgb));
        if (run.underline()) {
            g.fillRect(Math.round(start * s), Math.round((top + 8) * s), Math.round((x - start) * s), s);
        }
        if (run.strikethrough()) {
            g.fillRect(Math.round(start * s), Math.round((top + 3.5f) * s), Math.round((x - start) * s), s);
        }
        return x;
    }

    private void drawGlyph(Graphics2D g, int cp, float x, float top, int s, int rgb, boolean italic) {
        if (spaces.containsKey(cp)) {
            return;
        }
        Bitmap b = bitmaps.get(cp);
        if (b != null) {
            float px = b.scale * s;
            float y0 = top + (7 - b.ascent);
            for (int yy = 0; yy < b.h; yy++) {
                float shear = italic ? (b.h - yy) * b.scale * 0.25f : 0;
                for (int xx = 0; xx < b.w; xx++) {
                    int a = b.mask[yy * b.w + xx] & 0xFF;
                    if (a == 0) {
                        continue;
                    }
                    g.setColor(new java.awt.Color((a << 24) | rgb, true));
                    fill(g, (x + shear) * s + xx * px, y0 * s + yy * px, px);
                }
            }
            return;
        }
        if (!uniHas(cp)) {
            return;
        }
        int[] lr = uniBounds(cp);
        if (lr[1] < 0) {
            return;
        }
        g.setColor(new java.awt.Color(rgb));
        float px = s / 2f;
        for (int yy = 0; yy < 16; yy++) {
            float shear = italic ? (16 - yy) * 0.125f : 0;
            for (int xx = lr[0]; xx <= lr[1]; xx++) {
                if (uniBit(cp, xx, yy)) {
                    fill(g, (x + shear) * s + (xx - lr[0]) * px, top * s + yy * px, px);
                }
            }
        }
    }

    private static void fill(Graphics2D g, float x, float y, float size) {
        int x0 = Math.round(x), y0 = Math.round(y);
        int x1 = Math.round(x + size), y1 = Math.round(y + size);
        g.fillRect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
    }
}
