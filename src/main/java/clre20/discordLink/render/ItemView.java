package clre20.discordLink.render;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * 繪圖用的物品資料（在主執行緒從 ItemStack 取出，之後只在繪圖執行緒讀取）。
 *
 * @param model    物品模型 ID（例如 diamond_sword）
 * @param amount   數量
 * @param glint    是否有附魔光澤
 * @param durability 剩餘耐久比例 0～1；沒有耐久或沒損耗時為 -1
 * @param tooltip  說明框文字（只有 [item] 需要，背包格子為空清單）
 * @param skinUrl  玩家頭顱的皮膚網址（textures.minecraft.net），沒有時為 null
 */
public record ItemView(String model, int amount, boolean glint, float durability, List<Component> tooltip, String skinUrl) {

    public ItemView(String model, int amount, boolean glint, float durability, List<Component> tooltip) {
        this(model, amount, glint, durability, tooltip, null);
    }
}
