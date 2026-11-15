package clre20.discordLink.showcase;

import clre20.discordLink.render.ItemView;
import net.kyori.adventure.text.Component;

/**
 * 一則聊天訊息裡的展示內容（給 Discord 嵌入與圖片用）。沒有展示的欄位為 null。
 *
 * @param itemName  [item] 的名稱（含顏色，空手時為「空氣」）
 * @param itemAmount [item] 的數量
 * @param item      [item] 的說明框與圖示資料
 * @param inventory [inv] 介面的 54 格
 * @param inventoryTitle [inv] 介面標題
 * @param ender     [ender] 介面的 27 格
 * @param enderTitle [ender] 介面標題
 */
public record ShowcaseData(String player, Component itemName, int itemAmount, ItemView item,
                           ItemView[] inventory, Component inventoryTitle,
                           ItemView[] ender, Component enderTitle) {

    public boolean isEmpty() {
        return item == null && inventory == null && ender == null;
    }
}
