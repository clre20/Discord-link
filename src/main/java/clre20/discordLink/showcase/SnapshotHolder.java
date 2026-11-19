package clre20.discordLink.showcase;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 展示介面的擁有者。其他插件可用 {@code holder instanceof SnapshotHolder}
 * 或 {@link #PLUGIN_ID} 辨識這是 DiscordLink 的介面；按鈕與填充物另有 PDC 標籤 discordlink:gui。
 * 介面關閉後沒有任何地方再參照它，會被自動回收。
 */
public final class SnapshotHolder implements InventoryHolder {

    /** 插件簽署。 */
    public static final String PLUGIN_ID = "DiscordLink";

    private final String owner;
    private final SnapshotMenu.Kind kind;
    private final ItemStack[] layout;
    private final net.kyori.adventure.text.Component title;
    private final SnapshotHolder parent;
    private Inventory inventory;

    SnapshotHolder(String owner, SnapshotMenu.Kind kind, ItemStack[] layout,
                   net.kyori.adventure.text.Component title, SnapshotHolder parent) {
        this.owner = owner;
        this.kind = kind;
        this.layout = layout;
        this.title = title;
        this.parent = parent;
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

    SnapshotMenu.Kind kind() {
        return kind;
    }

    ItemStack[] layout() {
        return layout;
    }

    net.kyori.adventure.text.Component title() {
        return title;
    }

    /** 上一層介面（按「返回」回去），沒有時為 null。 */
    SnapshotHolder parent() {
        return parent;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
