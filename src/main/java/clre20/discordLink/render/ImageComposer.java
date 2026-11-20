package clre20.discordLink.render;

import net.kyori.adventure.text.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 組出 Discord 用的圖片：物品說明框、玩家背包、終界箱。
 * 尺寸以 GUI 單位計算，乘上倍率 scale 輸出。只在繪圖執行緒使用。
 */
public final class ImageComposer {

    private static final int MAX_LINES = 40;
    private static final int SKIN_CACHE = 256;
    private static final int TAB_ROWS = 20;
    private static final int TAB_MAX_PLAYERS = 100;
    private static final int MAX_TOOLTIP_WIDTH = 400;

    private final int s;
    private final GlyphFont font;
    private final IconRenderer icons;
    private final TextFlattener flattener;
    private final AssetPack assets;
    /** 皮膚網址 → 臉部圖（含帽子層）；下載失敗存 null 以免重試。 */
    private final java.util.Map<String, java.util.Optional<BufferedImage>> faces = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, java.util.Optional<BufferedImage>> eldest) {
            return size() > SKIN_CACHE;
        }
    };
    private java.net.http.HttpClient http;
    /** 每張圖最多下載幾個新皮膚（其餘用一般頭顱圖示），避免一次展示很多自訂頭顱拖慢聊天轉送。 */
    private int downloadsLeft;

    public ImageComposer(AssetPack assets, int scale) {
        this.s = Math.max(1, Math.min(4, scale));
        this.assets = assets;
        this.font = new GlyphFont(assets);
        this.icons = new IconRenderer(assets);
        this.flattener = new TextFlattener(assets::translate);
    }

    public TextFlattener flattener() {
        return flattener;
    }

    // ---------- [item] ----------

    /** 物品說明框（只有說明框，給 Discord 嵌入的大圖）。 */
    public byte[] tooltip(ItemView v) throws IOException {
        List<List<TextRun>> lines = new ArrayList<>();
        for (Component c : v.tooltip()) {
            if (lines.size() >= MAX_LINES) {
                break;
            }
            lines.add(flattener.runs(c, 0xFFFFFF));
        }
        if (lines.isEmpty()) {
            lines.add(List.of(new TextRun(v.model(), 0xFFFFFF, false, false, false, false)));
        }
        float w = 0;
        for (List<TextRun> l : lines) {
            w = Math.max(w, font.width(l));
        }
        int tw = (int) Math.ceil(Math.min(w, MAX_TOOLTIP_WIDTH));
        int th = 8 + (lines.size() - 1) * 10 + (lines.size() > 1 ? 2 : 0);
        int x = 4, y = 4;
        BufferedImage img = new BufferedImage((tw + 8) * s, (th + 8) * s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        tooltipBox(g, x, y, tw, th);
        float ly = y;
        for (int i = 0; i < lines.size(); i++) {
            font.draw(g, lines.get(i), x, ly, s, true);
            ly += i == 0 ? 12 : 10;
        }
        g.dispose();
        return png(img);
    }

    /** 物品圖示（含數量），給 Discord 嵌入的縮圖；沒有圖示時回傳 null。 */
    public byte[] icon(ItemView v) throws IOException {
        downloadsLeft = 1;
        if (v.skinUrl() == null && icons.icon(v.model(), 16 * s) == null) {
            return null;
        }
        BufferedImage img = new BufferedImage(18 * s, 18 * s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        stack(g, v, 1, 1);
        g.dispose();
        return png(img);
    }

    // ---------- 成就圖示 ----------

    /**
     * 成就圖示：原版成就畫面的外框（已完成）加上物品圖示。
     *
     * @param frame task、goal 或 challenge
     */
    public byte[] advancement(ItemView icon, String frame) throws IOException {
        String f = switch (frame) {
            case "goal", "challenge" -> frame;
            default -> "task";
        };
        BufferedImage img = new BufferedImage(26 * s, 26 * s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        BufferedImage frameImg = assets.texture("gui/sprites/advancements/" + f + "_frame_obtained");
        if (frameImg != null) {
            g.drawImage(frameImg, 0, 0, 26 * s, 26 * s, null);
        }
        if (icon != null) {
            BufferedImage ic = icons.icon(icon.model(), 16 * s);
            if (ic != null) {
                g.drawImage(icon.glint() ? glint(ic) : ic, 5 * s, 5 * s, null);
            }
        }
        g.dispose();
        return png(img);
    }

    // ---------- 線上玩家清單 ----------

    /** 跟遊戲內 Tab 清單一樣的線上玩家圖片（每欄最多 20 人）。 */
    public byte[] playerList(Component title, List<PlayerRow> players) throws IOException {
        List<PlayerRow> rows = players.size() > TAB_MAX_PLAYERS ? players.subList(0, TAB_MAX_PLAYERS) : players;
        downloadsLeft = 25;
        List<TextRun> titleRuns = flattener.runs(title, 0x55FF55);
        List<List<TextRun>> names = new ArrayList<>(rows.size());
        float nameW = 0;
        for (PlayerRow r : rows) {
            List<TextRun> n = flattener.runs(r.name(), 0xFFFFFF);
            names.add(n);
            nameW = Math.max(nameW, font.width(n));
        }
        int n = rows.size();
        int cols = Math.max(1, (n + TAB_ROWS - 1) / TAB_ROWS);
        int perCol = n == 0 ? 0 : (n + cols - 1) / cols;
        int colW = 9 + (int) Math.ceil(nameW) + 3 + 10;
        int gridW = cols * colW + (cols - 1) * 5;
        int width = Math.max(gridW, (int) Math.ceil(font.width(titleRuns))) + 4;
        int height = 2 + 10 + perCol * 9 + 1;
        BufferedImage img = new BufferedImage(width * s, height * s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        // 標題直接在 Discord 背景上；玩家列在灰色外框裡（與 InteractiveChat DiscordSRV Addon 相同）
        font.draw(g, titleRuns, (width - font.width(titleRuns)) / 2f, 2, s, true);
        g.setColor(new Color(0x444444));
        g.fillRect(0, 11 * s, width * s, (height - 11) * s);
        int left = (width - gridW) / 2;
        for (int i = 0; i < n; i++) {
            int c = i / Math.max(1, perCol);
            int r = i % Math.max(1, perCol);
            int x = left + c * (colW + 5);
            int y = 12 + r * 9;
            g.setColor(new Color(0x606060));
            g.fillRect(x * s, y * s, colW * s, 8 * s);
            PlayerRow row = rows.get(i);
            BufferedImage face = row.skinUrl() == null ? null : face(row.skinUrl());
            if (face != null) {
                g.drawImage(face, x * s, y * s, 8 * s, 8 * s, null);
            } else {
                g.setColor(new Color(0x404040));
                g.fillRect(x * s, y * s, 8 * s, 8 * s);
            }
            font.draw(g, names.get(i), x + 9, y, s, true);
            BufferedImage ping = assets.texture("gui/sprites/icon/" + pingSprite(row.ping()));
            if (ping != null) {
                g.drawImage(ping, (x + colW - 11) * s, y * s, 10 * s, 8 * s, null);
            }
        }
        g.dispose();
        return png(img);
    }

    private static String pingSprite(int ping) {
        if (ping < 0) {
            return "ping_unknown";
        }
        if (ping < 150) {
            return "ping_5";
        }
        if (ping < 300) {
            return "ping_4";
        }
        if (ping < 600) {
            return "ping_3";
        }
        return ping < 1000 ? "ping_2" : "ping_1";
    }

    // ---------- [inv] / [ender] ----------

    /**
     * 箱子樣式的圖片（跟遊戲內的快照介面相同的版面）。
     *
     * @param slots 每格的物品（null = 空格），長度 = rows × 9
     */
    public byte[] inventory(Component title, ItemView[] slots, int rows) throws IOException {
        int r = Math.max(1, Math.min(6, rows));
        downloadsLeft = 3;
        int width = 176;
        int height = 17 + r * 18 + 7;
        BufferedImage img = new BufferedImage(width * s, height * s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        panel(g, width, height);
        font.draw(g, flattener.runs(title, 0x404040), 8, 6, s, false);
        for (int row = 0; row < r; row++) {
            for (int c = 0; c < 9; c++) {
                cell(g, 7 + c * 18, 17 + row * 18, at(slots, row * 9 + c));
            }
        }
        g.dispose();
        return png(img);
    }

    private static ItemView at(ItemView[] slots, int i) {
        return slots != null && i >= 0 && i < slots.length ? slots[i] : null;
    }

    private void cell(Graphics2D g, int x, int y, ItemView v) {
        slot(g, x, y);
        if (v != null) {
            stack(g, v, x + 1, y + 1);
        }
    }

    // ---------- 元件 ----------

    private void panel(Graphics2D g, int w, int h) {
        rect(g, 0, 0, w, h, 0xC6C6C6);
        rect(g, 1, 0, w - 2, 1, 0x000000);
        rect(g, 1, h - 1, w - 2, 1, 0x000000);
        rect(g, 0, 1, 1, h - 2, 0x000000);
        rect(g, w - 1, 1, 1, h - 2, 0x000000);
        rect(g, 1, 1, w - 3, 2, 0xFFFFFF);
        rect(g, 1, 1, 2, h - 3, 0xFFFFFF);
        rect(g, 3, h - 3, w - 4, 2, 0x555555);
        rect(g, w - 3, 3, 2, h - 4, 0x555555);
    }

    private void slot(Graphics2D g, int x, int y) {
        rect(g, x, y, 18, 18, 0x8B8B8B);
        rect(g, x, y, 17, 1, 0x373737);
        rect(g, x, y, 1, 17, 0x373737);
        rect(g, x + 1, y + 17, 17, 1, 0xFFFFFF);
        rect(g, x + 17, y + 1, 1, 17, 0xFFFFFF);
    }

    /** 物品圖示、數量與耐久條（x, y 是 16×16 圖示的左上角）。 */
    private void stack(Graphics2D g, ItemView v, int x, int y) {
        BufferedImage face = v.skinUrl() == null ? null : face(v.skinUrl());
        BufferedImage icon = face != null ? null : icons.icon(v.model(), 16 * s);
        if (face != null) {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(face, (x + 1) * s, (y + 1) * s, 14 * s, 14 * s, null);
        } else if (icon != null) {
            g.drawImage(v.glint() ? glint(icon) : icon, x * s, y * s, null);
        } else {
            font.draw(g, List.of(new TextRun("?", 0xFF55FF, true, false, false, false)), x + 5, y + 4, s, true);
        }
        if (v.durability() >= 0) {
            float f = Math.max(0, Math.min(1, v.durability()));
            int bar = Math.round(13 * f);
            rect(g, x + 2, y + 13, 13, 2, 0x000000);
            rect(g, x + 2, y + 13, bar, 1, Color.HSBtoRGB(f / 3f, 1f, 1f) & 0xFFFFFF);
        }
        if (v.amount() > 1) {
            List<TextRun> n = List.of(new TextRun(String.valueOf(v.amount()), 0xFFFFFF, false, false, false, false));
            font.draw(g, n, x + 17 - font.width(n), y + 9, s, true);
        }
    }

    /** 下載（並快取）玩家皮膚，取出臉與帽子層。只接受 textures.minecraft.net。 */
    private BufferedImage face(String url) {
        java.util.Optional<BufferedImage> cached = faces.get(url);
        if (cached != null) {
            return cached.orElse(null);
        }
        if (downloadsLeft <= 0) {
            return null; // 不快取，下次還可以下載
        }
        downloadsLeft--;
        BufferedImage out = null;
        try {
            java.net.URI uri = java.net.URI.create(url.replaceFirst("^http://", "https://"));
            if ("https".equals(uri.getScheme()) && "textures.minecraft.net".equals(uri.getHost())) {
                if (http == null) {
                    http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
                }
                var res = http.send(java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(3)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                byte[] body = res.body();
                if (res.statusCode() == 200 && body.length < 64 * 1024) {
                    BufferedImage skin = ImageIO.read(new java.io.ByteArrayInputStream(body));
                    if (skin != null && skin.getWidth() == 64 && skin.getHeight() >= 32) {
                        out = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g = out.createGraphics();
                        g.drawImage(skin, 0, 0, 8, 8, 8, 8, 16, 16, null);
                        g.drawImage(skin, 0, 0, 8, 8, 40, 8, 48, 16, null);
                        g.dispose();
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
            out = null;
        }
        if (out == null) {
            // 皮膚伺服器有問題：這張圖不再下載其他皮膚，避免拖慢 Discord 聊天轉送
            downloadsLeft = 0;
            return null; // 不快取失敗，之後還可以再試
        }
        faces.put(url, java.util.Optional.of(out));
        return out;
    }

    /** 關閉時釋放（繪圖執行緒）。 */
    void close() {
        faces.clear();
        if (http != null) {
            http.close();
            http = null;
        }
    }

    private static BufferedImage glint(BufferedImage icon) {
        BufferedImage out = new BufferedImage(icon.getWidth(), icon.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                int p = icon.getRGB(x, y);
                int a = p >>> 24;
                if (a == 0) {
                    continue;
                }
                // 原版的附魔光澤是淡淡的紫色光，只疊加一點點，保留物品原本的顏色
                int r = Math.min(255, (p >> 16 & 0xFF) + 0x0C);
                int gg = Math.min(255, (p >> 8 & 0xFF) + 0x03);
                int b = Math.min(255, (p & 0xFF) + 0x18);
                out.setRGB(x, y, (a << 24) | (r << 16) | (gg << 8) | b);
            }
        }
        return out;
    }

    /** 原版樣式的說明框（x, y 是文字區左上角）。 */
    private void tooltipBox(Graphics2D g, int x, int y, int w, int h) {
        int bg = 0x100010;
        rect(g, x - 3, y - 4, w + 6, 1, bg);
        rect(g, x - 3, y + h + 3, w + 6, 1, bg);
        rect(g, x - 3, y - 3, w + 6, h + 6, bg);
        rect(g, x - 4, y - 3, 1, h + 6, bg);
        rect(g, x + w + 3, y - 3, 1, h + 6, bg);
        Color top = new Color(0x50, 0x00, 0xFF), bottom = new Color(0x28, 0x00, 0x7F);
        g.setPaint(new GradientPaint(0, (y - 3) * s, top, 0, (y + h + 3) * s, bottom));
        g.fillRect((x - 3) * s, (y - 2) * s, s, (h + 4) * s);
        g.fillRect((x + w + 2) * s, (y - 2) * s, s, (h + 4) * s);
        g.setPaint(null);
        rect(g, x - 3, y - 3, w + 6, 1, top.getRGB() & 0xFFFFFF);
        rect(g, x - 3, y + h + 2, w + 6, 1, bottom.getRGB() & 0xFFFFFF);
    }

    private void rect(Graphics2D g, int x, int y, int w, int h, int rgb) {
        if (w <= 0 || h <= 0) {
            return;
        }
        g.setColor(new Color(rgb));
        g.fillRect(x * s, y * s, w * s, h * s);
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
