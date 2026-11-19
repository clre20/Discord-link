package clre20.discordLink.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 已下載的原版資源（材質、模型、字型、語言）。
 * 只在繪圖執行緒使用；材質與模型用有上限的 LRU 快取，避免占用過多記憶體。
 */
public final class AssetPack implements AutoCloseable {

    private static final int TEXTURE_CACHE = 256;
    private static final int JSON_CACHE = 512;

    private final ZipFile pack;
    private final File unifontZip;
    private final File unifontJson;
    private final Map<String, String> lang;

    private final Map<String, BufferedImage> textures = lru(TEXTURE_CACHE);
    private final Map<String, JsonObject> jsons = lru(JSON_CACHE);
    /** 找不到的資源（避免重複讀 zip）。 */
    private final Map<String, Boolean> missing = lru(JSON_CACHE);

    AssetPack(File dir, String language) throws IOException {
        this.pack = new ZipFile(new File(dir, AssetDownloader.PACK));
        this.unifontZip = new File(dir, AssetDownloader.UNIFONT_ZIP);
        this.unifontJson = new File(dir, AssetDownloader.UNIFONT_JSON);
        Map<String, String> l = new HashMap<>(readLang(pack, "assets/minecraft/lang/en_us.json"));
        File extra = new File(dir, AssetDownloader.langFile(language));
        if (extra.isFile()) {
            try (InputStream in = Files.newInputStream(extra.toPath())) {
                l.putAll(parseLang(in));
            }
        }
        this.lang = Map.copyOf(l);
    }

    private static <K, V> Map<K, V> lru(int max) {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    private static Map<String, String> readLang(ZipFile zip, String path) throws IOException {
        ZipEntry e = zip.getEntry(path);
        if (e == null) {
            return Map.of();
        }
        try (InputStream in = zip.getInputStream(e)) {
            return parseLang(in);
        }
    }

    private static Map<String, String> parseLang(InputStream in) {
        Map<String, String> out = new HashMap<>();
        JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return out;
    }

    /** 翻譯鍵，找不到回傳 null。 */
    public String translate(String key) {
        return lang.get(key);
    }

    File unifontZip() {
        return unifontZip;
    }

    File unifontJson() {
        return unifontJson;
    }

    /** 讀取 zip 內的 JSON（例如 assets/minecraft/models/item/stick.json），找不到回傳 null。 */
    synchronized JsonObject json(String path) {
        JsonObject cached = jsons.get(path);
        if (cached != null || missing.containsKey(path)) {
            return cached;
        }
        ZipEntry e = pack.getEntry(path);
        if (e == null) {
            missing.put(path, Boolean.TRUE);
            return null;
        }
        try (InputStream in = pack.getInputStream(e)) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            jsons.put(path, o);
            return o;
        } catch (Exception ex) {
            missing.put(path, Boolean.TRUE);
            return null;
        }
    }

    /** 讀取 zip 內的圖片（不快取，給字型等只讀一次的資源用）。 */
    synchronized BufferedImage rawImage(String path) {
        ZipEntry e = pack.getEntry(path);
        if (e == null) {
            return null;
        }
        try (InputStream in = pack.getInputStream(e)) {
            return ImageIO.read(in);
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * 材質：例如 minecraft:item/diamond_sword、block/stone。
     * 動畫材質只取第一格；回傳 ARGB 圖片，找不到回傳 null。
     */
    synchronized BufferedImage texture(String ref) {
        String id = ref.startsWith("minecraft:") ? ref.substring(10) : ref;
        if (id.indexOf(':') >= 0 || id.contains("..")) {
            return null; // 只有原版命名空間
        }
        BufferedImage cached = textures.get(id);
        if (cached != null || missing.containsKey("tex:" + id)) {
            return cached;
        }
        BufferedImage img = rawImage("assets/minecraft/textures/" + id + ".png");
        if (img == null) {
            missing.put("tex:" + id, Boolean.TRUE);
            return null;
        }
        int w = img.getWidth();
        int h = Math.min(img.getHeight(), w); // 動畫材質：直向排列，只取第一格
        BufferedImage argb = toArgb(img, w, h);
        textures.put(id, argb);
        return argb;
    }

    /**
     * 轉成 ARGB。灰階 PNG（玻璃、樹葉、草地頂部等）直接複製原始數值：
     * Java 會把灰階當成線性色彩空間轉換，顏色會變得太亮。
     */
    static BufferedImage toArgb(BufferedImage img, int w, int h) {
        BufferedImage argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.image.ColorModel cm = img.getColorModel();
        if (cm.getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY && !(cm instanceof java.awt.image.IndexColorModel)) {
            java.awt.image.Raster r = img.getRaster();
            int bands = r.getNumBands();
            int max = (1 << cm.getComponentSize(0)) - 1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = r.getSample(x, y, 0) * 255 / max;
                    int a = bands > 1 ? r.getSample(x, y, 1) * 255 / max : 255;
                    argb.setRGB(x, y, (a << 24) | (v << 16) | (v << 8) | v);
                }
            }
            return argb;
        }
        argb.getGraphics().drawImage(img, 0, 0, w, h, 0, 0, w, h, null);
        return argb;
    }

    @Override
    public synchronized void close() {
        try {
            pack.close();
        } catch (IOException ignored) {
        }
        textures.clear();
        jsons.clear();
        missing.clear();
    }
}
