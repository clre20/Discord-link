package clre20.discordLink.showcase;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.config.Messages;
import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 唯讀的展示介面（與 InteractiveChat 相同的版面）：
 * <ul>
 *   <li>[item]：3×3 發射器介面，物品在正中間。</li>
 *   <li>[inv]：6 列箱子，第 1 列為頭顱、藥水效果、頭盔、胸甲、護腿、靴子、副手；第 2 列分隔；之後是背包與快捷欄。</li>
 *   <li>[ender]：3 列箱子。</li>
 * </ul>
 * 版面在主執行緒建立一次，遊戲內介面與 Discord 圖片共用。所有點擊與拖曳一律取消。
 */
public final class SnapshotMenu implements Listener {

    /** 介面種類。 */
    public enum Kind { ITEM, INVENTORY, ENDER }

    private final DiscordLink plugin;
    private final NamespacedKey guiKey;

    public SnapshotMenu(DiscordLink plugin) {
        this.plugin = plugin;
        this.guiKey = new NamespacedKey(plugin, "gui");
    }

    // ---------- 版面（主執行緒） ----------

    /** [item]：9 格，中間是物品。 */
    ItemStack[] itemLayout(ItemStack hand) {
        ItemStack[] out = new ItemStack[9];
        ItemStack filler = filler();
        for (int i = 0; i < 9; i++) {
            out[i] = filler;
        }
        out[4] = ItemCapture.empty(hand) ? null : hand.clone();
        return out;
    }

    /** [inv]：54 格。 */
    ItemStack[] inventoryLayout(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] out = new ItemStack[54];
        ItemStack filler = filler();
        out[0] = head(p);
        out[1] = effects(p);
        out[2] = filler;
        out[3] = copy(inv.getHelmet());
        out[4] = copy(inv.getChestplate());
        out[5] = copy(inv.getLeggings());
        out[6] = copy(inv.getBoots());
        out[7] = filler;
        out[8] = copy(inv.getItemInOffHand());
        for (int i = 9; i < 18; i++) {
            out[i] = filler;
        }
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 9; i < 36 && i < storage.length; i++) {
            out[18 + (i - 9)] = copy(storage[i]); // 背包 3 列
        }
        for (int i = 0; i < 9 && i < storage.length; i++) {
            out[45 + i] = copy(storage[i]); // 快捷欄
        }
        return out;
    }

    /** [ender]：27 格。 */
    ItemStack[] enderLayout(Player p) {
        ItemStack[] src = p.getEnderChest().getContents();
        ItemStack[] out = new ItemStack[27];
        for (int i = 0; i < 27 && i < src.length; i++) {
            out[i] = copy(src[i]);
        }
        return out;
    }

    private static ItemStack copy(ItemStack it) {
        return ItemCapture.empty(it) ? null : it.clone();
    }

    private ItemStack filler() {
        return button(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), plugin.messages().component("showcase.gui.filler"), List.of());
    }

    private ItemStack head(Player p) {
        Messages msg = plugin.messages();
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        if (it.getItemMeta() instanceof SkullMeta sm) {
            PlayerProfile profile = p.getPlayerProfile();
            sm.setPlayerProfile(profile);
            it.setItemMeta(sm);
        }
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        Object[] vars = {"player", p.getName(),
                "health", String.format(Locale.ROOT, "%.1f", p.getHealth()),
                "max_health", String.format(Locale.ROOT, "%.1f", max == null ? 20.0 : max.getValue()),
                "food", p.getFoodLevel(), "level", p.getLevel()};
        return button(it, msg.component("showcase.gui.head-name", vars), lines("showcase.gui.head-lore", vars));
    }

    private ItemStack effects(Player p) {
        Messages msg = plugin.messages();
        ItemStack it = new ItemStack(Material.POTION);
        if (it.getItemMeta() instanceof PotionMeta pm) {
            pm.setColor(Color.fromRGB(0x9CCC65));
            it.setItemMeta(pm);
        }
        List<Component> lore = new ArrayList<>();
        for (PotionEffect e : p.getActivePotionEffects()) {
            if (lore.size() >= 20) {
                break;
            }
            String time = e.isInfinite() || e.getDuration() < 0 ? "∞"
                    : String.format(Locale.ROOT, "%02d:%02d", e.getDuration() / 20 / 60, e.getDuration() / 20 % 60);
            lore.add(msg.componentWith("showcase.gui.effect-line",
                    Map.of("effect", Component.translatable(e.getType().translationKey())),
                    "level", roman(e.getAmplifier() + 1), "time", time));
        }
        if (lore.isEmpty()) {
            lore.add(msg.component("showcase.gui.effects-none"));
        }
        return button(it, msg.component("showcase.gui.effects-name"), lore);
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    private List<Component> lines(String key, Object... vars) {
        List<Component> out = new ArrayList<>();
        String raw = plugin.messages().raw(key);
        if (raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\n", 20)) {
            out.add(clre20.discordLink.util.Text.component(line, vars));
        }
        return out;
    }

    private ItemStack button(ItemStack it, Component name, List<Component> lore) {
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        List<Component> l = new ArrayList<>(lore.size());
        for (Component c : lore) {
            l.add(c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        meta.lore(l);
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(guiKey, PersistentDataType.STRING, "snapshot");
        it.setItemMeta(meta);
        return it;
    }

    /** 介面標題（遊戲內與 Discord 圖片共用）。 */
    Component title(Kind kind, String owner) {
        return plugin.messages().component(switch (kind) {
            case ITEM -> "showcase.gui.item-title";
            case INVENTORY -> "showcase.gui.inventory-title";
            case ENDER -> "showcase.gui.ender-title";
        }, "player", owner);
    }

    // ---------- 開啟（主執行緒） ----------

    void open(Player viewer, Kind kind, String owner, ItemStack[] layout) {
        SnapshotHolder holder = new SnapshotHolder(owner);
        Component title = title(kind, owner);
        Inventory inv = switch (kind) {
            case ITEM -> Bukkit.createInventory(holder, InventoryType.DROPPER, title);
            case INVENTORY -> Bukkit.createInventory(holder, 54, title);
            case ENDER -> Bukkit.createInventory(holder, 27, title);
        };
        holder.bind(inv);
        for (int i = 0; i < layout.length && i < inv.getSize(); i++) {
            if (layout[i] != null) {
                inv.setItem(i, layout[i]);
            }
        }
        viewer.openInventory(inv);
    }

    // ---------- 防止拿取 ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof SnapshotHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof SnapshotHolder) {
            event.setCancelled(true);
        }
    }
}
