package clre20.discordLink;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.UUID;

public class DiscordListener extends ListenerAdapter {
    private final DiscordLink plugin;
    private final ValidationManager validationManager;
    private final DatabaseManager dbManager;
    private final Server server;

    public DiscordListener(DiscordLink plugin, ValidationManager validationManager, DatabaseManager dbManager, Server server) {
        this.plugin = plugin;
        this.validationManager = validationManager;
        this.dbManager = dbManager;
        this.server = server;
    }

    private Color parseColor(String hex, Color defaultColor) {
        if (hex == null || hex.isEmpty()) return defaultColor;
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return defaultColor;
        }
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        String verifyChannelId = plugin.getConfig().getString("discord.verify-channel-id", plugin.getConfig().getString("verify-channel-id"));
        if (!event.getChannel().getId().equals(verifyChannelId)) return;

        String msg = event.getMessage().getContentRaw().trim();

        // 檢查是否為 5 位純數字
        if (msg.matches("\\d{5}")) {
            UUID uuid = validationManager.getUuidFromCode(msg);

            if (uuid != null) {
                // 讀取設定檔的上限值 (相容新舊設定檔欄位)
                int maxBindings = plugin.getConfig().getInt("verification.max-bindings-per-discord", 
                        plugin.getConfig().getInt("max-bindings-per-discord", 1));

                // 檢查目前已綁定數量
                int currentBindings = dbManager.getBindingCount(event.getAuthor().getId());

                if (currentBindings >= maxBindings) {
                    String limitTitle = plugin.getConfig().getString("embeds.verify-limit.title", "❌ 綁定失敗");
                    String limitDescTemplate = plugin.getConfig().getString("embeds.verify-limit.description", "您的 Discord 帳號已達到綁定上限 ({max} 個帳號)。");
                    String limitColorHex = plugin.getConfig().getString("embeds.verify-limit.color", "#E67E22");
                    String limitFooter = plugin.getConfig().getString("embeds.verify-limit.footer", "極光の幻想鄉 • 驗證系統");

                    String limitDesc = limitDescTemplate.replace("{max}", String.valueOf(maxBindings));

                    EmbedBuilder limitEmbed = new EmbedBuilder()
                            .setTitle(limitTitle)
                            .setDescription(limitDesc)
                            .setColor(parseColor(limitColorHex, Color.ORANGE))
                            .setFooter(limitFooter, null)
                            .setTimestamp(java.time.Instant.now());
                    event.getChannel().sendMessageEmbeds(limitEmbed.build()).queue();
                    return;
                }

                // 執行綁定邏輯..
                dbManager.bind(uuid, event.getAuthor().getId());
                validationManager.invalidateCode(msg, uuid);

                // 嘗試取得玩家名稱，處理離線狀態
                OfflinePlayer player = server.getOfflinePlayer(uuid);
                String playerName = player.getName() != null ? player.getName() : "未知玩家";
                String shortUuid = uuid.toString().substring(0, 8);

                // 驗證成功卡片
                String successTitle = plugin.getConfig().getString("embeds.verify-success.title", "✅ 綁定成功！");
                String successDescTemplate = plugin.getConfig().getString("embeds.verify-success.description", "玩家：**{player}** (ID: `{uuid}...`)\n您現在可以登入伺服器了。");
                String successColorHex = plugin.getConfig().getString("embeds.verify-success.color", "#2ECC71");
                String successFooter = plugin.getConfig().getString("embeds.verify-success.footer", "極光の幻想鄉 • 驗證系統");

                String successDesc = successDescTemplate
                        .replace("{player}", playerName)
                        .replace("{uuid}", shortUuid);

                EmbedBuilder successEmbed = new EmbedBuilder()
                        .setTitle(successTitle)
                        .setDescription(successDesc)
                        .setThumbnail("https://mc-heads.net/avatar/" + playerName + "/128")
                        .setColor(parseColor(successColorHex, Color.GREEN))
                        .setFooter(successFooter, null)
                        .setTimestamp(java.time.Instant.now());

                event.getChannel().sendMessageEmbeds(successEmbed.build()).queue();

                // 1. 私訊通知
                boolean pmEnabled = plugin.getConfig().getBoolean("embeds.private-message.enabled",
                        plugin.getConfig().getBoolean("notifications.private-message.enabled", false));
                if (pmEnabled) {
                    final String finalPlayerName = playerName;
                    event.getAuthor().openPrivateChannel().queue(privateChannel -> {
                        String dmTitle = plugin.getConfig().getString("embeds.private-message.title", "🎉 帳號綁定成功 (Account Linked)");
                        String dmTemplate = plugin.getConfig().getString("embeds.private-message.description",
                                plugin.getConfig().getString("notifications.private-message.message", ""));
                        String dmColorHex = plugin.getConfig().getString("embeds.private-message.color", "#2ECC71");
                        String dmFooter = plugin.getConfig().getString("embeds.private-message.footer", "極光の幻想鄉 • 驗證系統");

                        if (dmTemplate != null && !dmTemplate.isEmpty()) {
                            String dmMsg = dmTemplate.replace("{player}", finalPlayerName);
                            EmbedBuilder dmEmbed = new EmbedBuilder()
                                    .setTitle(dmTitle)
                                    .setDescription(dmMsg)
                                    .setThumbnail("https://mc-heads.net/avatar/" + finalPlayerName + "/128")
                                    .setColor(parseColor(dmColorHex, Color.GREEN))
                                    .setFooter(dmFooter, null)
                                    .setTimestamp(java.time.Instant.now());
                            privateChannel.sendMessageEmbeds(dmEmbed.build()).queue(
                                    success -> {},
                                    throwable -> plugin.logWarning("無法私訊用戶 " + event.getAuthor().getName() + "，對方的私訊功能可能關閉了。")
                            );
                        }
                    });
                }

                // 2. 管理員日誌
                boolean logEnabled = plugin.getConfig().getBoolean("embeds.admin-log.enabled",
                        plugin.getConfig().getBoolean("notifications.admin-log.enabled", false));
                if (logEnabled) {
                    String adminChannelId = plugin.getConfig().getString("embeds.admin-log.channel-id",
                            plugin.getConfig().getString("notifications.admin-log.channel-id", ""));
                    if (adminChannelId != null && !adminChannelId.isEmpty()) {
                        TextChannel adminChannel = event.getJDA().getTextChannelById(adminChannelId);
                        if (adminChannel != null) {
                            String logTitle = plugin.getConfig().getString("embeds.admin-log.title", "📥 帳號綁定日誌 (Admin Log)");
                            String logTemplate = plugin.getConfig().getString("embeds.admin-log.description",
                                    plugin.getConfig().getString("notifications.admin-log.message", ""));
                            String logColorHex = plugin.getConfig().getString("embeds.admin-log.color", "#3498DB");
                            String logFooter = plugin.getConfig().getString("embeds.admin-log.footer", "DiscordLink System Log");

                            if (logTemplate != null && !logTemplate.isEmpty()) {
                                String logMsg = logTemplate
                                        .replace("{discord_id}", event.getAuthor().getId())
                                        .replace("{player}", playerName)
                                        .replace("{uuid}", uuid.toString());
                                EmbedBuilder logEmbed = new EmbedBuilder()
                                        .setTitle(logTitle)
                                        .setDescription(logMsg)
                                        .setThumbnail("https://mc-heads.net/avatar/" + playerName + "/128")
                                        .setColor(parseColor(logColorHex, new Color(52, 152, 219)))
                                        .setFooter(logFooter, null)
                                        .setTimestamp(java.time.Instant.now());
                                adminChannel.sendMessageEmbeds(logEmbed.build()).queue();
                            }
                        } else {
                            plugin.logWarning("找不到指定的管理員日誌頻道，請確認 ID: " + adminChannelId + " 是否正確。");
                        }
                    }
                }
            } else {
                String failTitle = plugin.getConfig().getString("embeds.verify-fail.title", "❌ 驗證失敗");
                String failDesc = plugin.getConfig().getString("embeds.verify-fail.description", "驗證碼無效或已過期，請重新登入遊戲獲取新代碼。");
                String failColorHex = plugin.getConfig().getString("embeds.verify-fail.color", "#E74C3C");
                String failFooter = plugin.getConfig().getString("embeds.verify-fail.footer", "極光の幻想鄉 • 驗證系統");

                EmbedBuilder failEmbed = new EmbedBuilder()
                        .setTitle(failTitle)
                        .setDescription(failDesc)
                        .setColor(parseColor(failColorHex, Color.RED))
                        .setFooter(failFooter, null)
                        .setTimestamp(java.time.Instant.now());

                event.getChannel().sendMessageEmbeds(failEmbed.build()).queue();
            }
        }
    }
}