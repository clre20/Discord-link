package clre20.discordLink.render;

import net.kyori.adventure.text.Component;

/**
 * 線上玩家清單圖片的一列（在主執行緒取好資料）。
 *
 * @param name    清單上的名稱（含前綴與顏色，同 Tab 清單）
 * @param skinUrl 皮膚網址，沒有時為 null
 * @param ping    延遲（毫秒），負數表示未知
 */
public record PlayerRow(Component name, String skinUrl, int ping) {
}
