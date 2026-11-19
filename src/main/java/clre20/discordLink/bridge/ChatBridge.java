package clre20.discordLink.bridge;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.config.Messages;
import clre20.discordLink.config.PluginConfig;
import clre20.discordLink.render.ImageComposer;
import clre20.discordLink.render.PlayerRow;
import clre20.discordLink.showcase.ShowcaseData;
import clre20.discordLink.showcase.ShowcaseListener;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.sticker.StickerItem;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 聊天互通（取代 DiscordSRV 的聊天功能）與線上玩家清單。
 * <ul>
 *   <li>遊戲 → Discord：在聊天事件最後（MONITOR）取純文字，交給繪圖執行緒依序送出（Webhook 顯示玩家頭像）。</li>
 *   <li>Discord → 遊戲：訊息內容以「不解析」方式放進格式，玩家無法插入顏色或點擊事件。</li>
 *   <li>所有 Discord 訊息都關閉提及（不會 @everyone）。</li>
 * </ul>
 */
public final class ChatBridge extends ListenerAdapter implements Listener {

    /** Webhook 名稱：Discord 不允許名稱含 discord、clyde，也不能是 everyone、here。 */
    private static final String WEBHOOK_NAME = "DcLink Chat";
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Pattern RESERVED = Pattern.compile("(?i)discord|clyde");
    private static final int MAX_ATTACHMENTS = 5;

    private final DiscordLink plugin;
    private volatile Webhook webhook;
    private volatile ShowcaseListener showcase;
    private volatile boolean conflict;

    public ChatBridge(DiscordLink plugin) {
        this.plugin = plugin;
    }

    public void setShowcase(ShowcaseListener showcase) {
        this.showcase = showcase;
    }

    /** DiscordSRV 存在時停用。 */
    public void setConflict(boolean conflict) {
        this.conflict = conflict;
    }

    private boolean active() {
        return !conflict && plugin.config().chatEnabled();
    }

    // ---------- 連線就緒／重新載入 ----------

    /** 在 Discord 執行緒呼叫：檢查頻道並準備 Webhook。 */
    public void onReady(JDA jda) {
        webhook = null;
        if (!active()) {
            return;
        }
        PluginConfig config = plugin.config();
        TextChannel ch = jda.getTextChannelById(config.chatChannelId());
        if (ch == null) {
            plugin.getLogger().warning(plugin.messages().console("chat-channel-missing", "id", config.chatChannelId()));
            return;
        }
        if (!config.useWebhook()) {
            return;
        }
        long self = jda.getSelfUser().getIdLong();
        ch.retrieveWebhooks().queue(list -> {
            for (Webhook w : list) {
                User owner = w.getOwnerAsUser();
                if (owner != null && owner.getIdLong() == self && WEBHOOK_NAME.equals(w.getName()) && w.getToken() != null) {
                    webhook = w;
                    plugin.getLogger().info(plugin.messages().console("webhook-ready"));
                    return;
                }
            }
            ch.createWebhook(WEBHOOK_NAME).queue(w -> {
                webhook = w;
                plugin.getLogger().info(plugin.messages().console("webhook-ready"));
            }, this::webhookFailed);
        }, this::webhookFailed);
    }

    private void webhookFailed(Throwable t) {
        webhook = null;
        plugin.getLogger().warning(plugin.messages().console("webhook-failed", "reason", t.getMessage()));
    }

    // ---------- 遊戲 → Discord ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        ShowcaseListener sl = showcase;
        ShowcaseData data = sl == null ? null : sl.take(event);
        if (!active()) {
            return;
        }
        JDA jda = plugin.discord().jda();
        if (jda == null) {
            return;
        }
        Player player = event.getPlayer();
        String name = player.getName();
        UUID uuid = player.getUniqueId();
        String text = plugin.render().plain(event.message());
        if (text.isBlank()) {
            return;
        }
        if (!plugin.render().submit(() -> send(jda, name, uuid, text, data))) {
            send(jda, name, uuid, text, null); // 佇列已滿：不畫圖，直接送文字
        }
    }

    private void send(JDA jda, String name, UUID uuid, String text, ShowcaseData data) {
        PluginConfig config = plugin.config();
        Messages msg = plugin.messages();
        TextChannel ch = jda.getTextChannelById(config.chatChannelId());
        if (ch == null) {
            return;
        }
        String body = config.escapeMarkdown() ? MarkdownSanitizer.escape(text) : text;
        Attachments att = attachments(name, data);
        Webhook w = webhook;
        if (w != null) {
            String content = cut(msg.discord("chat.webhook-message", "player", name, "message", body), Message.MAX_CONTENT_LENGTH);
            String user = webhookUsername(msg.discord("chat.webhook-name", "player", name), name);
            var action = w.sendMessage(content.isBlank() ? "​" : content)
                    .setUsername(user)
                    .setAvatarUrl(avatar(uuid, name))
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
            if (!att.embeds().isEmpty()) {
                action = action.addEmbeds(att.embeds()).addFiles(att.files());
            }
            action.queue(ok -> { }, err -> {
                // Webhook 被刪除等：之後改用機器人發送，下次重新載入時再建立
                webhook = null;
                sendAsBot(ch, name, body, Attachments.NONE);
            });
            return;
        }
        sendAsBot(ch, name, body, att);
    }

    /** 每則訊息的 Webhook 顯示名稱：去掉 Discord 不允許的字，長度 1～80。 */
    static String webhookUsername(String raw, String fallback) {
        String u = RESERVED.matcher(raw == null ? "" : raw).replaceAll("Dc").replace("```", "").trim();
        if (u.isEmpty()) {
            u = RESERVED.matcher(fallback).replaceAll("Dc");
        }
        if (u.equalsIgnoreCase("everyone") || u.equalsIgnoreCase("here")) {
            u = u + "_";
        }
        return cut(u, 80);
    }

    private void sendAsBot(TextChannel ch, String name, String body, Attachments att) {
        String content = cut(plugin.messages().discord("chat.bot-message", "player", MarkdownSanitizer.escape(name), "message", body),
                Message.MAX_CONTENT_LENGTH);
        var action = ch.sendMessage(content.isBlank() ? "​" : content)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
        if (!att.embeds().isEmpty()) {
            action = action.addEmbeds(att.embeds()).addFiles(att.files());
        }
        action.queue(ok -> { }, err -> { });
    }

    private record Attachments(List<MessageEmbed> embeds, List<FileUpload> files) {
        static final Attachments NONE = new Attachments(List.of(), List.of());
    }

    /** 物品展示的嵌入卡片與圖片（與 InteractiveChat DiscordSRV Addon 相同的樣式）。在繪圖執行緒呼叫。 */
    private Attachments attachments(String name, ShowcaseData data) {
        ImageComposer c = plugin.render().composer();
        if (c == null || data == null) {
            return Attachments.NONE;
        }
        Messages msg = plugin.messages();
        List<MessageEmbed> embeds = new ArrayList<>(3);
        List<FileUpload> files = new ArrayList<>(4);
        try {
            if (data.item() != null) {
                String itemName = MarkdownSanitizer.escape(c.flattener().plain(data.itemName()));
                String title = data.itemAmount() > 1
                        ? msg.discord("showcase.item-title-amount", "player", name, "item", itemName, "amount", data.itemAmount())
                        : msg.discord("showcase.item-title", "player", name, "item", itemName);
                // 色條用名稱實際顯示的顏色（第一段有文字的顏色）
                int rgb = 0xFFFFFF;
                for (clre20.discordLink.render.TextRun r : c.flattener().runs(data.itemName(), 0xFFFFFF)) {
                    if (!r.text().isBlank()) {
                        rgb = r.rgb();
                        break;
                    }
                }
                EmbedBuilder b = new EmbedBuilder()
                        .setAuthor(cut(msg.discord("showcase.item-author", "player", name), MessageEmbed.AUTHOR_MAX_LENGTH))
                        .setTitle(cut(title, MessageEmbed.TITLE_MAX_LENGTH))
                        .setColor(rgb)
                        .setImage("attachment://item.png");
                files.add(FileUpload.fromData(c.tooltip(data.item()), "item.png"));
                byte[] icon = c.icon(data.item());
                if (icon != null) {
                    files.add(FileUpload.fromData(icon, "icon.png"));
                    b.setThumbnail("attachment://icon.png");
                }
                embeds.add(b.build());
            }
            if (data.inventory() != null) {
                files.add(FileUpload.fromData(c.inventory(data.inventoryTitle(), data.inventory(), 6), "inventory.png"));
                embeds.add(new EmbedBuilder()
                        .setAuthor(cut(msg.discord("showcase.inventory-author", "player", name), MessageEmbed.AUTHOR_MAX_LENGTH))
                        .setColor(msg.discordColor("showcase.inventory", java.awt.Color.LIGHT_GRAY))
                        .setImage("attachment://inventory.png")
                        .build());
            }
            if (data.ender() != null) {
                files.add(FileUpload.fromData(c.inventory(data.enderTitle(), data.ender(), 3), "ender.png"));
                embeds.add(new EmbedBuilder()
                        .setAuthor(cut(msg.discord("showcase.ender-author", "player", name), MessageEmbed.AUTHOR_MAX_LENGTH))
                        .setColor(msg.discordColor("showcase.ender", java.awt.Color.MAGENTA))
                        .setImage("attachment://ender.png")
                        .build());
            }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().fine("render failed: " + e);
            return Attachments.NONE;
        }
        return new Attachments(embeds, files);
    }

    private String avatar(UUID uuid, String name) {
        String url = plugin.config().avatarUrl()
                .replace("{uuid}", uuid.toString().replace("-", ""))
                .replace("{name}", name);
        return url.startsWith("https://") || url.startsWith("http://") ? url : null;
    }

    // ---------- Discord → 遊戲 ----------

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!active() || !event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }
        PluginConfig config = plugin.config();
        if (!event.getChannel().getId().equals(config.chatChannelId())) {
            return;
        }
        Message m = event.getMessage();
        String keyword = config.playerlistKeyword();
        if (!keyword.isEmpty() && m.getContentRaw().trim().equalsIgnoreCase(keyword)) {
            playerlistInChannel(event);
            return;
        }
        String text = CONTROL.matcher(m.getContentDisplay().replaceAll("[\\r\\n\\t]+", " ")).replaceAll("").trim();
        if (text.length() > config.maxLengthToMinecraft()) {
            text = text.substring(0, config.maxLengthToMinecraft()) + "…";
        }
        List<Message.Attachment> attachments = m.getAttachments();
        List<StickerItem> stickers = m.getStickers();
        if (text.isEmpty() && attachments.isEmpty() && stickers.isEmpty()) {
            return;
        }
        Messages msg = plugin.messages();

        // 訊息內容：網址換成可點擊的 [連結]／[圖片]，附件與貼圖接在後面
        Component body = linkify(text);
        int n = 0;
        for (Message.Attachment a : attachments) {
            if (n++ >= MAX_ATTACHMENTS || !a.getUrl().startsWith("https://")) {
                continue;
            }
            String key = a.isImage() ? "chat.image" : a.isVideo() ? "chat.video" : "chat.attachment";
            body = body.append(clickable(msg.component(key, "file", cut(a.getFileName(), 40)), a.getUrl(), a.getFileName()));
        }
        for (StickerItem st : stickers) {
            if (n++ >= MAX_ATTACHMENTS) {
                break;
            }
            body = body.append(clickable(msg.component("chat.sticker", "name", cut(st.getName(), 40)), st.getIconUrl(), st.getName()));
        }

        Component reply = Component.empty();
        Message ref = m.getReferencedMessage();
        if (ref != null) {
            reply = msg.component("chat.reply", "name", nameOf(ref.getAuthor(), ref.getMember(), ref.isWebhookMessage()));
        }
        Component out = msg.componentWith("chat.discord-to-minecraft",
                java.util.Map.of("role", role(event.getMember()), "reply", reply, "message", body),
                "name", nameOf(event.getAuthor(), event.getMember(), false));
        runSync(() -> Bukkit.broadcast(out));
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]{1,500}", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_URL = Pattern.compile(
            "(?i)(\\.(png|jpe?g|gif|webp|bmp)(\\?.*)?$)|(^https://(media|cdn)\\.discordapp\\.(net|com)/attachments/)|(^https://(media\\.)?tenor\\.com/)");

    /** 文字中的網址換成可點擊的 [連結]／[圖片]（遊戲內不直接顯示網址）。 */
    private Component linkify(String text) {
        Messages msg = plugin.messages();
        java.util.regex.Matcher mt = URL.matcher(text);
        Component out = Component.empty();
        int last = 0;
        int links = 0;
        while (mt.find() && links < MAX_ATTACHMENTS) {
            if (mt.start() > last) {
                out = out.append(Component.text(text.substring(last, mt.start())));
            }
            String url = mt.group();
            String key = IMAGE_URL.matcher(url).find() ? "chat.image-link" : "chat.link";
            out = out.append(clickable(msg.component(key), url, url));
            last = mt.end();
            links++;
        }
        if (last < text.length()) {
            out = out.append(Component.text(text.substring(last)));
        }
        return out;
    }

    /** 點擊開啟網址；滑鼠移上去顯示名稱與提示。只接受 http(s)。 */
    private Component clickable(Component label, String url, String hoverName) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return label;
        }
        return label.clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(plugin.messages().component("chat.link-hover", "name", cut(hoverName, 60))));
    }

    /** 最高的身分組（有顏色時套用身分組顏色），沒有身分組時為空。 */
    private Component role(Member member) {
        if (member == null || member.getRoles().isEmpty()) {
            return Component.empty();
        }
        Role top = member.getRoles().get(0);
        Component c = plugin.messages().component("chat.role", "role", cut(CONTROL.matcher(top.getName()).replaceAll(""), 32));
        int raw = top.getColorRaw();
        if (raw != Role.DEFAULT_COLOR_RAW) {
            c = c.colorIfAbsent(net.kyori.adventure.text.format.TextColor.color(raw & 0xFFFFFF));
        }
        return c;
    }

    /** 顯示 Discord 上看到的名稱（伺服器暱稱，沒有時為顯示名稱），不對應 Minecraft 帳號。 */
    private String nameOf(User user, Member member, boolean webhook) {
        String n = member != null ? member.getEffectiveName() : user.getEffectiveName();
        return cut(CONTROL.matcher(n).replaceAll(""), 32);
    }

    // ---------- 線上玩家清單 ----------

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals("playerlist")) {
            return;
        }
        if (conflict || !plugin.isEnabled()) {
            event.reply(plugin.messages().discord("playerlist.offline")).setEphemeral(true).queue();
            return;
        }
        event.deferReply().queue(hook -> runSync(() -> {
            PlayerListData data = playerListData();
            renderOrText(data, (text, png) -> {
                if (png != null) {
                    hook.editOriginal("").setFiles(FileUpload.fromData(png, "playerlist.png")).queue(ok -> { }, err -> { });
                } else {
                    hook.editOriginal(text).queue(ok -> { }, err -> { });
                }
            });
        }));
    }

    private void playerlistInChannel(MessageReceivedEvent event) {
        int delete = plugin.config().playerlistDeleteSeconds();
        Message request = event.getMessage();
        TextChannel ch = event.getChannel().asTextChannel();
        runSync(() -> {
            PlayerListData data = playerListData();
            renderOrText(data, (text, png) -> {
                var action = png != null
                        ? ch.sendFiles(FileUpload.fromData(png, "playerlist.png"))
                        : ch.sendMessage(text).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
                action.queue(sent -> {
                    if (delete > 0) {
                        sent.delete().queueAfter(delete, TimeUnit.SECONDS, ok -> { }, err -> { });
                        request.delete().queueAfter(delete, TimeUnit.SECONDS, ok -> { }, err -> { });
                    }
                }, err -> { });
            });
        });
    }

    /** 線上玩家清單資料（主執行緒取得）。 */
    private record PlayerListData(String text, Component title, List<PlayerRow> rows) {
    }

    /** 主執行緒呼叫。 */
    private PlayerListData playerListData() {
        Messages msg = plugin.messages();
        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!ShowcaseListener.isVanished(p)) {
                players.add(p);
            }
        }
        players.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()));
        int max = Bukkit.getMaxPlayers();
        String header = msg.discord("playerlist.header", "online", players.size(), "max", max);
        String text;
        if (players.isEmpty()) {
            text = cut(header + "\n" + msg.discord("playerlist.empty"), Message.MAX_CONTENT_LENGTH);
        } else {
            List<String> escaped = new ArrayList<>(players.size());
            for (Player p : players) {
                escaped.add(MarkdownSanitizer.escape(p.getName()));
            }
            text = cut(header + "\n" + msg.discord("playerlist.body", "players",
                    String.join(msg.discord("playerlist.separator"), escaped)), Message.MAX_CONTENT_LENGTH);
        }
        List<PlayerRow> rows = new ArrayList<>(players.size());
        if (plugin.render().ready()) {
            for (Player p : players) {
                String skin = null;
                try {
                    var tex = p.getPlayerProfile().getTextures();
                    skin = tex.getSkin() == null ? null : tex.getSkin().toString();
                } catch (RuntimeException ignored) {
                }
                rows.add(new PlayerRow(p.playerListName(), skin, p.getPing()));
            }
        }
        return new PlayerListData(text, msg.component("playerlist.image-title", "online", players.size(), "max", max), rows);
    }

    /** 最近一次畫好的線上玩家圖片（5 秒內重複查詢直接用，避免被洗頻時一直重畫）。 */
    private volatile byte[] cachedList;
    private volatile long cachedListAt;

    /** 有圖片功能時在繪圖執行緒畫圖，否則直接用文字。 */
    private void renderOrText(PlayerListData data, java.util.function.BiConsumer<String, byte[]> send) {
        ImageComposer c = plugin.render().composer();
        byte[] recent = cachedList;
        if (recent != null && System.currentTimeMillis() - cachedListAt < 5000) {
            send.accept(data.text(), recent);
            return;
        }
        if (c == null || data.rows().isEmpty() || !plugin.render().submit(() -> {
            byte[] png = null;
            try {
                ImageComposer cc = plugin.render().composer();
                png = cc == null ? null : cc.playerList(data.title(), data.rows());
            } catch (Exception | LinkageError e) {
                png = null;
            }
            if (png != null) {
                cachedList = png;
                cachedListAt = System.currentTimeMillis();
            }
            send.accept(data.text(), png);
        })) {
            send.accept(data.text(), null);
        }
    }

    private void runSync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
