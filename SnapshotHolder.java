package clre20.discordLink.showcase;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * 背包快照介面的擁有者。其他插件可用 {@code holder instanceof SnapshotHolder}
 * 或 {@link #PLUGIN_ID} 辨識這是 DiscordLink 的介面；按鈕與填充物另有 PDC 標籤 discordlink:gui。
 */
public final class SnapshotHolder implements InventoryHolder {

    /** 插件簽署。 */
    public static final String PLUGIN_ID = "DiscordLink";

    private final String owner;
    private Inventory inventory;

    SnapshotHolder(String owner) {
        this.owner = owner;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    public String pluginId() {
        return PLUGIN_ID;
    }

    /** 快照的主人（玩家名稱）。 */
    public String owner() {
        return owner;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
