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
 * DiscordLink 對外開放的統一 Bot 與資料服務接口。
 * 允許伺服器上其他插件（如 AetherMart, Cmail）直接共用連線發送私訊、頻道訊息與查詢綁定。
 */
public interface DiscordBotService {

    record PluginDiscordInfo(String pluginName, int dmCount, int channelCount, int queryCount, long lastActiveTime) {
    }

    static DiscordBotService get() {
        return Bukkit.getServicesManager().load(DiscordBotService.class);
    }

    /** 機器人連線目前是否就緒。 */
    boolean isBotReady();

    /** 取得 JDA 實例（未連線時回傳 null）。 */
    JDA getJda(Plugin caller);

    /** 查詢玩家綁定的 Discord ID（記憶體查詢，無 I/O 阻塞）。未綁定時回傳空。 */
    Optional<String> getDiscordId(Plugin caller, UUID playerUuid);

    /** 檢查玩家是否已綁定 Discord。 */
    boolean isLinked(Plugin caller, UUID playerUuid);

    /** 取得所有已綁定玩家的 UUID 集合。 */
    Set<UUID> getLinkedPlayers(Plugin caller);

    /**
     * 透過機器人直接向玩家發送私訊（自動比對玩家綁定的 Discord ID）。
     *
     * @param caller     調用的插件
     * @param playerUuid Minecraft 玩家 UUID
     * @param text       文字訊息（可為 null）
     * @param embed      嵌入卡片（可為 null）
     * @param files      附件檔案清單（如 PDF、圖片等，可為 null 或空）
     * @return 非同步發送結果（true 代表成功送達，false 代表未綁定或發送失敗）
     */
    CompletableFuture<Boolean> sendDirectMessage(Plugin caller, UUID playerUuid, String text, MessageEmbed embed, List<FileUpload> files);

    /**
     * 透過機器人向指定 Discord 頻道發送訊息。
     */
    CompletableFuture<Boolean> sendChannelMessage(Plugin caller, String channelId, String text, MessageEmbed embed, List<FileUpload> files);

    /** 取得所有透過接口連接/調用過的外部插件統計資料。 */
    List<PluginDiscordInfo> getConnectedPlugins();
}
