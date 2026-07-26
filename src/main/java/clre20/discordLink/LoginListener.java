package clre20.discordLink;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

public class LoginListener implements Listener {
    private final DatabaseManager dbManager;
    private final ValidationManager validationManager;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public LoginListener(DatabaseManager dbManager, ValidationManager validationManager) {
        this.dbManager = dbManager;
        this.validationManager = validationManager;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();

        // 檢查是否在白名單 (Bypass) 內，如果是則直接放行
        if (dbManager.isBypassed(uuid)) {
            return;
        }

        DiscordLink plugin = DiscordLink.getPlugin(DiscordLink.class);
        boolean forceBinding = plugin.getConfig().getBoolean("verification.force-binding", true);

        // 如果啟用強制驗證，且玩家未綁定，則進行攔截
        if (forceBinding && !dbManager.isBound(uuid)) {
            String code = validationManager.generateOrRefreshCode(uuid);
            int expiryMinutes = plugin.getConfig().getInt("verification.code-expiry-minutes", 5);

            // 專為相容 Bedrock (Geyser) 調整的精簡版排版
            // 移除 hover/click 互動事件，並減少換行以適應手機等較小的螢幕
            Component kickMsg = mm.deserialize(
                    "<gradient:blue:aqua><bold>極光の幻想鄉</bold></gradient><br>" +
                            "<red>您必須綁定 Discord 帳號才能進入伺服器！</red><br>" +
                            "<gray>請在 Discord 驗證頻道輸入以下代碼：</gray><br>" +
                            "<yellow><bold>[" + code + "]</bold></yellow><br>" +
                            "<dark_gray>期限 " + expiryMinutes + " 分鐘，重新登入可刷新代碼。</dark_gray>"
            );

            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, kickMsg);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 檢查是否在白名單內，如果是則不提示
        if (dbManager.isBypassed(uuid)) {
            return;
        }

        // 如果未綁定，且並非強制驗證狀態（因為強制狀態的玩家根本進不來），則發送提示訊息
        if (!dbManager.isBound(uuid)) {
            DiscordLink plugin = DiscordLink.getPlugin(DiscordLink.class);
            String reminderMsg = plugin.getConfig().getString("messages.join-reminder", "");
            if (reminderMsg != null && !reminderMsg.isEmpty()) {
                player.sendMessage(mm.deserialize(reminderMsg));
            }
        }
    }
}