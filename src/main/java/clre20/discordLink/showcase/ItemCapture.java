package clre20.discordLink.showcase;

import clre20.discordLink.render.ItemView;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.inventory.tooltip.TooltipContext;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.meta.SkullMeta;

import java.net.URL;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** 從 ItemStack 取出繪圖資料。只能在主執行緒呼叫。 */
public final class ItemCapture {

    private ItemCapture() {
    }

    /** 給其他套件用：物品圖示資料（不含說明框），物品是空的時回傳 null。主執行緒呼叫。 */
    public static ItemView publicView(ItemStack item, Player viewer) {
        return view(item, viewer, false, false);
    }

    static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    /** 物品名稱：自訂名稱保留原本顏色，沒有顏色時用稀有度顏色。 */
    static Component name(ItemStack item) {
        if (empty(item)) {
            return Component.translatable("block.minecraft.air");
        }
        Component n = item.effectiveName();
        if (n.color() == null) {
            ItemRarity rarity = item.getData(DataComponentTypes.RARITY);
            if (rarity != null) {
                n = n.color(rarity.color());
            }
        }
        return n;
    }

    /** 空手時的「空氣」說明框。 */
    static ItemView air(boolean advanced) {
        List<Component> lines = advanced
                ? List.of(Component.translatable("block.minecraft.air"), Component.text("minecraft:air", NamedTextColor.DARK_GRAY))
                : List.of(Component.translatable("block.minecraft.air"));
        return new ItemView("air", 1, false, -1, lines);
    }

    /**
     * @param withTooltip 是否計算說明框文字（只有 [item] 需要）
     * @param advanced    說明框是否包含進階資訊（物品 ID、元件數量，同 F3+H）
     */
    static ItemView view(ItemStack item, Player viewer, boolean withTooltip, boolean advanced) {
        if (empty(item)) {
            return null;
        }
        String model = item.getType().getKey().getKey();
        Key custom = item.getData(DataComponentTypes.ITEM_MODEL);
        if (custom != null && custom.namespace().equals(Key.MINECRAFT_NAMESPACE)) {
            model = custom.value();
        }
        Boolean glintOverride = item.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
        boolean glint = glintOverride != null ? glintOverride
                : !item.getEnchantments().isEmpty() || item.getType() == Material.ENCHANTED_BOOK;
        float durability = -1;
        Integer max = item.getData(DataComponentTypes.MAX_DAMAGE);
        Integer dmg = item.getData(DataComponentTypes.DAMAGE);
        if (max != null && max > 0 && dmg != null && dmg > 0) {
            durability = Math.max(0f, (max - dmg) / (float) max);
        }
        List<Component> tooltip = List.of();
        if (withTooltip) {
            try {
                tooltip = List.copyOf(item.computeTooltipLines(
                        TooltipContext.create(advanced, viewer.getGameMode() == GameMode.CREATIVE), viewer));
            } catch (RuntimeException e) {
                tooltip = List.of(item.effectiveName());
            }
        }
        String skin = null;
        if (item.getType() == Material.PLAYER_HEAD && item.getItemMeta() instanceof SkullMeta sm
                && sm.getPlayerProfile() != null && sm.getPlayerProfile().hasTextures()) {
            URL url = sm.getPlayerProfile().getTextures().getSkin();
            skin = url == null ? null : url.toString();
        }
        return new ItemView(model, item.getAmount(), glint, durability, tooltip, skin);
    }

    static ItemView[] views(ItemStack[] items, Player viewer) {
        ItemView[] out = new ItemView[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = view(items[i], viewer, false, false);
        }
        return out;
    }
}
