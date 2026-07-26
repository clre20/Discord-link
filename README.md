# Discord Link

DiscordLink 是一個專為 Paper (Minecraft 1.21.x) 伺服器設計的 Discord 帳號綁定驗證插件。本插件透過本地 SQLite 資料庫紀錄玩家 UUID 與 Discord 帳號的連結關係，並支援強制驗證與自由綁定兩種運作模式，以便伺服器管理員管理玩家社群。

## 功能特性

* **雙重驗證機制**：
  * **強制驗證模式**：未綁定的玩家在登入時會被拒絕連線，並顯示隨機生成的 5 位數驗證碼，玩家需於 Discord 指定頻道輸入驗證碼方可進服。
  * **自由綁定模式**：玩家可直接進入伺服器，系統會在登入時提示玩家使用 `/discordlink link` 指令獲取驗證碼自行綁定。
* **自訂 Discord Embeds**：支援在設定檔中自訂所有發送至 Discord 的卡片訊息（標題、描述、Hex 顏色與頁尾文字）。
* **玩家頭像渲染**：自動整合 `mc-heads.net` API，於 Discord 訊息卡片中顯示綁定玩家的 3D 遊戲頭像，支援正版與離線版 UUID。
* **Geyser/Floodgate 相容**：針對基岩版（Bedrock）玩家的介面與字數限制進行優化，防範踢出訊息版面錯亂。
* **設定檔自動升級**：更新插件時，自動補齊新版設定項目，並完整保留原有的自訂值。
* **管理員日誌與通知**：綁定成功時可選擇是否向玩家發送私訊通知，以及在特定的管理員頻道輸出日誌。
* **優雅關閉設計**：插件停用時會阻塞主執行緒等待 Discord 連線執行緒完全釋放，避免關機時產生 `zip file closed` 警告。

## 指令與權限

本插件主指令為 `/discordlink`，別名為 `/dclink`。

| 指令 | 說明 | 權限節點 | 預設權限 |
| :--- | :--- | :--- | :--- |
| `/dclink link` | 獲取 5 位數綁定驗證碼 | `discordlink.use` | 所有人 (`true`) |
| `/dclink help` | 顯示指令說明清單 | `discordlink.use` | 所有人 (`true`) |
| `/dclink reload` | 重新載入設定檔 | `discordlink.admin` | 管理員 (`op`) |
| `/dclink bypass add <玩家>` | 將玩家加入免驗證白名單 | `discordlink.admin` | 管理員 (`op`) |
| `/dclink bypass remove <玩家>` | 將玩家移出免驗證白名單 | `discordlink.admin` | 管理員 (`op`) |
| `/dclink admin query <玩家\|DiscordID>` | 查詢特定帳號的綁定關係 | `discordlink.admin` | 管理員 (`op`) |
| `/dclink admin unbind <玩家\|DiscordID>` | 解除特定帳號的綁定關係 | `discordlink.admin` | 管理員 (`op`) |
| `/dclink admin list [頁碼]` | 分頁檢視所有已綁定的玩家清單 | `discordlink.admin` | 管理員 (`op`) |