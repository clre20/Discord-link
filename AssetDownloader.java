package clre20.discordLink.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 從 Mojang 下載繪圖需要的原版資源。
 * <ul>
 *   <li>客戶端 jar 以串流方式讀取，只留下材質、模型、字型（約 2.5 MB），不把整個 jar 存到硬碟。</li>
 *   <li>每個檔案都驗證 SHA-1；先寫暫存檔，完成後才改名，下載中斷不會留下壞檔。</li>
 *   <li>Minecraft 版本變更時刪除舊版本的資料夾，避免磁碟用量累積。</li>
 * </ul>
 * 只在背景執行緒呼叫。
 */
public final class AssetDownloader {

    static final String PACK = "pack.zip";
    static final String UNIFONT_ZIP = "unifont.zip";
    static final String UNIFONT_JSON = "unifont.json";

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCES = "https://resources.download.minecraft.net/";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}");
    private static final String[] KEEP = {
            "assets/minecraft/textures/item/",
            "assets/minecraft/textures/block/",
            "assets/minecraft/textures/font/",
            "assets/minecraft/models/",
            "assets/minecraft/items/",
            "assets/minecraft/font/include/default.json",
            "assets/minecraft/font/include/space.json",
            "assets/minecraft/lang/en_us.json",
    };

    private AssetDownloader() {
    }

    static String langFile(String language) {
        return "lang_" + language + ".json";
    }

    /** 語言代碼只允許 xx_yy 格式。 */
    public static String safeLanguage(String language) {
        String l = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        return l.matches("[a-z]{2,3}_[a-z]{2,4}") ? l : "zh_tw";
    }

    /**
     * 準備好資源並開啟。
     *
     * @param root     插件資料夾下的 assets 資料夾
     * @param version  伺服器的 Minecraft 版本（例如 26.1.2）
     * @param language 語言（例如 zh_tw）
     * @param log      進度訊息
     */
    public static AssetPack prepare(File root, String version, String language, Consumer<String> log) throws Exception {
        if (!SAFE.matcher(version).matches()) {
            throw new IOException("invalid version: " + version);
        }
        String lang = safeLanguage(language);
        File dir = new File(root, version);
        removeOtherVersions(root, version);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File pack = new File(dir, PACK);
        File unifont = new File(dir, UNIFONT_ZIP);
        File unifontJson = new File(dir, UNIFONT_JSON);
        File langOut = new File(dir, langFile(lang));
        boolean needLang = !lang.equals("en_us") && !langOut.isFile();

        if (!pack.isFile() || !unifont.isFile() || !unifontJson.isFile() || needLang) {
            try (HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()) {
                log.accept("download-start");
                JsonObject manifest = getJson(http, MANIFEST);
                String versionUrl = null;
                for (JsonElement e : manifest.getAsJsonArray("versions")) {
                    JsonObject v = e.getAsJsonObject();
                    if (version.equals(v.get("id").getAsString())) {
                        versionUrl = v.get("url").getAsString();
                        break;
                    }
                }
                if (versionUrl == null) {
                    throw new IOException("version " + version + " not found in Mojang manifest");
                }
                JsonObject meta = getJson(http, versionUrl);
                if (!pack.isFile()) {
                    JsonObject client = meta.getAsJsonObject("downloads").getAsJsonObject("client");
                    downloadPack(http, client.get("url").getAsString(), client.get("sha1").getAsString(), pack);
                }
                JsonObject index = getJson(http, meta.getAsJsonObject("assetIndex").get("url").getAsString())
                        .getAsJsonObject("objects");
                if (!unifont.isFile()) {
                    downloadObject(http, index, "minecraft/font/unifont.zip", unifont);
                }
                if (!unifontJson.isFile()) {
                    downloadObject(http, index, "minecraft/font/include/unifont.json", unifontJson);
                }
                if (needLang && index.has("minecraft/lang/" + lang + ".json")) {
                    downloadObject(http, index, "minecraft/lang/" + lang + ".json", langOut);
                }
                log.accept("download-done");
            }
        }
        return new AssetPack(dir, lang);
    }

    private static void removeOtherVersions(File root, String keep) {
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory() && !f.getName().equals(keep)
                    && SAFE.matcher(f.getName()).matches()) {
                deleteTree(f, 0);
            }
        }
    }

    /** 刪除資料夾；不跟隨符號連結，避免刪到 assets 資料夾以外的檔案。 */
    private static void deleteTree(File f, int depth) {
        if (depth > 8) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                if (!Files.isSymbolicLink(c.toPath()) && c.isDirectory()) {
                    deleteTree(c, depth + 1);
                } else {
                    c.delete(); // 符號連結本身會被刪除，但不會刪到它指向的內容
                }
            }
        }
        f.delete();
    }

    private static JsonObject getJson(HttpClient http, String url) throws Exception {
        HttpResponse<String> res = http.send(request(url, Duration.ofSeconds(30)), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + " " + url);
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static HttpRequest request(String url, Duration timeout) {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (!"https".equals(uri.getScheme()) || !(host.endsWith(".mojang.com") || host.endsWith(".minecraft.net"))) {
            throw new IllegalArgumentException("unexpected host: " + host);
        }
        return HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", "DiscordLink").GET().build();
    }

    /** 串流讀取客戶端 jar，只把需要的項目寫進 pack.zip。 */
    private static void downloadPack(HttpClient http, String url, String sha1, File out) throws Exception {
        File tmp = new File(out.getParentFile(), out.getName() + ".part");
        HttpResponse<InputStream> res = http.send(request(url, Duration.ofMinutes(10)), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            throw new IOException("HTTP " + res.statusCode() + " client.jar");
        }
        MessageDigest digest = sha1();
        try (InputStream raw = res.body();
             DigestInputStream din = new DigestInputStream(raw, digest);
             ZipInputStream zin = new ZipInputStream(din);
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(tmp.toPath()))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory() || !keep(e.getName())) {
                    continue;
                }
                zout.putNextEntry(new ZipEntry(e.getName()));
                int n;
                while ((n = zin.read(buf)) > 0) {
                    zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
            // 讀完剩下的資料，才能算出整個檔案的雜湊
            while (din.read(buf) > 0) {
                // drain
            }
        } catch (Exception ex) {
            tmp.delete();
            throw ex;
        }
        if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha1)) {
            tmp.delete();
            throw new IOException("client.jar SHA-1 mismatch");
        }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean keep(String name) {
        if (name.contains("..")) {
            return false;
        }
        for (String k : KEEP) {
            if (name.startsWith(k)) {
                return name.endsWith(".png") || name.endsWith(".json");
            }
        }
        return false;
    }

    private static void downloadObject(HttpClient http, JsonObject index, String key, File out) throws Exception {
        JsonObject o = index.getAsJsonObject(key);
        if (o == null) {
            throw new IOException(key + " not in asset index");
        }
        String hash = o.get("hash").getAsString();
        if (!HASH.matcher(hash).matches()) {
            throw new IOException("bad hash for " + key);
        }
        File tmp = new File(out.getParentFile(), out.getName() + ".part");
        HttpResponse<InputStream> res = http.send(request(RESOURCES + hash.substring(0, 2) + "/" + hash, Duration.ofMinutes(5)),
                HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            throw new IOException("HTTP " + res.statusCode() + " " + key);
        }
        MessageDigest digest = sha1();
        try (InputStream in = new DigestInputStream(res.body(), digest);
             OutputStream os = Files.newOutputStream(tmp.toPath())) {
            in.transferTo(os);
        } catch (Exception ex) {
            tmp.delete();
            throw ex;
        }
        if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(hash)) {
            tmp.delete();
            throw new IOException(key + " SHA-1 mismatch");
        }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
