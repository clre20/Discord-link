package clre20.discordLink.compatibility;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * 讀取 LuckPerms 的前綴。沒有安裝 LuckPerms 時不會載入任何 LuckPerms 類別。
 * 只讀 LuckPerms 已快取的資料，不會查資料庫；主執行緒呼叫。
 */
public final class LuckPermsHook {

    private LuckPermsHook() {
    }

    /** LuckPerms 是否可用。 */
    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
    }

    /** 玩家的前綴（原始文字，可能含 &／§ 顏色碼或 MiniMessage），沒有時回傳 null。 */
    public static String prefix(Player player) {
        if (!available()) {
            return null;
        }
        try {
            return Impl.prefix(player);
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    /** 分開放，確保沒有 LuckPerms 時不會載入到 LuckPerms 的類別。 */
    private static final class Impl {
        static String prefix(Player player) {
            RegisteredServiceProvider<LuckPerms> rsp = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
            if (rsp == null) {
                return null;
            }
            CachedMetaData meta = rsp.getProvider().getPlayerAdapter(Player.class).getMetaData(player);
            return meta.getPrefix();
        }
    }
}
