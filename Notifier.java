package clre20.discordLink.bridge;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.config.Messages;
import clre20.discordLink.config.PluginConfig;
import clre20.discordLink.showcase.ShowcaseListener;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.GameRule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.awt.Color;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Discord 通知：伺服器啟動／關閉、加入、離開、第一次加入、死亡、成就。
 * 原版成就與 CustomMilestone 註冊的自訂成就都走同一個成就事件，遵守 announceAdvancements 遊戲規則。
 * 事件處理只取資料，送出交給 JDA 的非同步佇列。隱身中的玩家不發通知。
 */
public final class Notifier implements Listener {

    private final DiscordLink plugin;
    private volatile boolean conflict;
    private volatile boolean startSent;

    public Notifier(DiscordLink plugin) {
        this.plugin = plugin;
    }

    public void setConflict(boolean conflict) {
        this.conflict = conflict;
    }

    /** 這種通知開啟時回傳它的頻道，否則 null。 */
    private TextChannel channel(String type) {
        PluginConfig.Notify n = plugin.config().notify(type);
        if (conflict || !n.enabled() || n.channelId().isEmpty()) {
            return null;
        }
        JDA jda = plugin.discord() == null ? null : plugin.discord().jda();
        return jda == null ? null : jda.getTextChannelById(n.channelId());
    }

    /** 機器人連上時（Discord 執行緒）：檢查各通知頻道，第一次連上時送出啟動通知。 */
    public void onReady(JDA jda) {
        if (conflict) {
            return;
        }
        java.util.Set<String> warned = new java.util.HashSet<>();
        for (String type : PluginConfig.NOTIFY_TYPES) {
            PluginConfig.Notify n = plugin.config().notify(type);
            if (n.enabled() && !n.channelId().isEmpty() && jda.getTextChannelById(n.channelId()) == null
                    && warned.add(n.channelId())) {
                plugin.getLogger().warning(plugin.messages().console("notify-channel-missing", "id", n.channelId(), "type", type));
            }
        }
        if (startSent) {
            return;
        }
        startSent = true;
        TextChannel ch = channel("server-start");
        if (ch != null) {
            ch.sendMessageEmbeds(server("server-start", Color.GREEN)).queue(ok -> { }, err -> { });
        }
    }

    /** 關服時（主執行緒）：最多等 3 秒送出。 */
    public void sendStop() {
        TextChannel ch = channel("server-stop");
        if (ch == null) {
            return;
        }
        try {
            ch.sendMessageEmbeds(server("server-stop", Color.RED)).submit().get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private MessageEmbed server(String key, Color fallback) {
        Messages msg = plugin.messages();
        return new EmbedBuilder()
                .setAuthor(cut(msg.discord("notify." + key + ".text"), MessageEmbed.AUTHOR_MAX_LENGTH))
                .setColor(msg.discordColor("notify." + key, fallback))
                .build();
    }

    // ---------- 玩家事件 ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        boolean first = !p.hasPlayedBefore() && plugin.config().notify("first-join").enabled();
        if (ShowcaseListener.isVanished(p)) {
            return;
        }
        String type = first ? "first-join" : "join";
        player(type, type, first ? Color.YELLOW : Color.GREEN, p, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (ShowcaseListener.isVanished(event.getPlayer())) {
            return;
        }
        player("quit", "quit", Color.RED, event.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Component death = event.deathMessage();
        if (!plugin.config().notify("death").enabled() || death == null || ShowcaseListener.isVanished(event.getPlayer())) {
            return;
        }
        String text = plugin.render().plain(death);
        if (text.isBlank()) {
            return;
        }
        player("death", "death", Color.DARK_GRAY, event.getPlayer(), null, "message", text);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!plugin.config().notify("advancement").enabled()) {
            return;
        }
        AdvancementDisplay display = event.getAdvancement().getDisplay();
        Player p = event.getPlayer();
        if (display == null || !display.doesAnnounceToChat() || ShowcaseListener.isVanished(p)) {
            return;
        }
        Boolean announce = p.getWorld().getGameRuleValue(GameRule.ANNOUNCE_ADVANCEMENTS);
        if (announce != null && !announce) {
            return;
        }
        String key = switch (display.frame()) {
            case GOAL -> "advancement-goal";
            case CHALLENGE -> "advancement-challenge";
            default -> "advancement-task";
        };
        String title = plugin.render().plain(display.title());
        String description = plugin.render().plain(display.description());
        player("advancement", key, Color.CYAN, p, plugin.messages().discord("notify." + key + ".description",
                "player", p.getName(), "advancement", title, "description", description),
                "advancement", title, "description", description);
    }

    /**
     * @param type 通知種類（決定開關與頻道）
     * @param key  messages.yml 的 discord.notify 底下的鍵
     */
    private void player(String type, String key, Color fallback, Player p, String description, Object... extra) {
        TextChannel ch = channel(type);
        if (ch == null) {
            return;
        }
        Messages msg = plugin.messages();
        Object[] vars = new Object[extra.length + 2];
        vars[0] = "player";
        vars[1] = p.getName();
        System.arraycopy(extra, 0, vars, 2, extra.length);
        EmbedBuilder b = new EmbedBuilder()
                .setAuthor(cut(msg.discord("notify." + key + ".text", vars), MessageEmbed.AUTHOR_MAX_LENGTH),
                        null, avatar(p.getUniqueId(), p.getName()))
                .setColor(msg.discordColor("notify." + key, fallback));
        if (description != null && !description.isBlank()) {
            b.setDescription(cut(description, MessageEmbed.DESCRIPTION_MAX_LENGTH));
        }
        ch.sendMessageEmbeds(b.build()).queue(ok -> { }, err -> { });
    }

    private String avatar(UUID uuid, String name) {
        String url = plugin.config().avatarUrl()
                .replace("{uuid}", uuid.toString().replace("-", ""))
                .replace("{name}", name);
        return url.startsWith("https://") || url.startsWith("http://") ? url : null;
    }

    private static String cut(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
