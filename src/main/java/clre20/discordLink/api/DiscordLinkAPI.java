package clre20.discordLink.api;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 方便其他插件直接以靜態方法調用 DiscordLink Bot 服務的門面類別。
 */
public final class DiscordLinkAPI {

    private DiscordLinkAPI() {
    }

    /** 檢查 DiscordLink 服務是否已啟用且就緒。 */
    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("DiscordLink") && DiscordBotService.get() != null;
    }

    /** 機器人是否已連線完成。 */
    public static boolean isBotReady() {
        DiscordBotService svc = DiscordBotService.get();
        return svc != null && svc.isBotReady();
    }

    /** 取得 JDA。 */
    public static JDA getJda(Plugin caller) {
        DiscordBotService svc = DiscordBotService.get();
        return svc != null ? svc.getJda(caller) : null;
    }

    /** 查詢玩家綁定的 Discord ID。 */
    public static Optional<String> getDiscordId(Plugin caller, UUID playerUuid) {
        DiscordBotService svc = DiscordBotService.get();
        return svc != null ? svc.getDiscordId(caller, playerUuid) : Optional.empty();
    }

    /** 檢查玩家是否已綁定。 */
    public static boolean isLinked(Plugin caller, UUID playerUuid) {
        DiscordBotService svc = DiscordBotService.get();
        return svc != null && svc.isLinked(caller, playerUuid);
    }

    /** 取得所有綁定玩家 UUID。 */
    public static Set<UUID> getLinkedPlayers(Plugin caller) {
        DiscordBotService svc = DiscordBotService.get();
        return svc != null ? svc.getLinkedPlayers(caller) : Set.of();
    }

    /** 發送私訊。 */
    public static CompletableFuture<Boolean> sendDirectMessage(Plugin caller, UUID playerUuid, String text, MessageEmbed embed, List<FileUpload> files) {
        DiscordBotService svc = DiscordBotService.get();
        if (svc == null) {
            return CompletableFuture.completedFuture(false);
        }
        return svc.sendDirectMessage(caller, playerUuid, text, embed, files);
    }

    /** 發送頻道訊息。 */
    public static CompletableFuture<Boolean> sendChannelMessage(Plugin caller, String channelId, String text, MessageEmbed embed, List<FileUpload> files) {
        DiscordBotService svc = DiscordBotService.get();
        if (svc == null) {
            return CompletableFuture.completedFuture(false);
        }
        return svc.sendChannelMessage(caller, channelId, text, embed, files);
    }

    /** 取得連接的外部插件清單。 */
    public static List<DiscordBotService.PluginDiscordInfo> getConnectedPlugins() {
        DiscordBotService svc = DiscordBotService.get();
        return svc != null ? svc.getConnectedPlugins() : List.of();
    }
}
