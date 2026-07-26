# 🔗 DiscordLink

<p align="center">
  <img src="DiscordLink.png" alt="DiscordLink Logo" width="250px" style="border-radius: 15px;"/>
</p>

<p align="center">
  <b>一個基於 Paper API 開發的高效、高客製化 Discord 與 Minecraft 帳號雙向綁定驗證插件。</b>
</p>

---

## 🌟 核心特色

* 🔒 **雙重驗證模式（自由/強制）**：
  * **強制驗證**：未綁定的玩家登入時會被踢出並顯示 5 位數驗證碼。
  * **自由驗證**：未綁定玩家可直接進服，登入時會發送溫和的聊天室綁定提示，玩家可在遊戲內輸入指令進行綁定。
* 🎨 **100% 視覺自訂化（Discord Embeds）**：
  * 所有發送至 Discord 的訊息（驗證成功/失敗/達到上限/玩家私訊/管理員日誌）均採用精美的嵌入式卡片 (Embeds)。
  * 支援在 `config.yml` 中自訂每個卡片的 **標題、內文（支援變數）、側邊欄 Hex 顏色及尾字 (Footer)**。
* 👤 **動態 3D 玩家頭像**：
  * 整合高效能的 Minecraft 皮膚 API，卡片會自動根據綁定玩家的遊戲 ID 渲染出精緻的 **3D 角色頭像** 作為縮圖。
* 📱 **基岩版 (Geyser/Floodgate) 完美相容**：
  * 針對手機與主機板玩家進行防踢與提示介面優化，移除不相容的 Click/Hover 事件，確保小螢幕排版清晰。
* 📝 **管理員日誌與用戶私訊**：
  * 綁定成功後，可設定是否自動私訊 (DM) 玩家，以及是否在指定的管理員 Discord 頻道發送詳細日誌。
* 🔄 **設定檔自動升級與合併**：
  * 升級新版本時，插件會自動合併缺失的新設定欄位至現有的 `config.yml`，**絕對不會覆蓋或刪除**您原先已設定好的 Token、頻道 ID 等自訂值。
* 🚥 **控制台彩色日誌排版**：
  * 以 `[DiscordLink]` 為字首，在伺服器主控台 (Console) 輸出狀態分明的彩色 Log，並支援動態 `/dclink reload` 配置重載報告。
* 🧼 **優雅退場關機機制**：
  * 伺服器關機時，主執行緒會安全阻塞並等待 JDA 背景執行緒完全釋放，100% 避免出現 `zip file closed` 等非同步殘留報錯。

---

## 🛠️ 指令與權限說明

本插件同時支援 `/discordlink` 與簡寫 `/dclink` 進行操作。

| 指令 | 說明 | 權限節點 | 預設權限 |
| :--- | :--- | :--- | :--- |
| `/dclink link` | 產生 Discord 5 位數綁定驗證碼 | `discordlink.use` | 所有人 (`true`) |
| `/dclink help` | 顯示該發送者可使用的指令說明 | `discordlink.use` | 所有人 (`true`) |
| `/dclink reload` | 重新載入設定檔（立即可於控制台看見新報告） | `discordlink.admin` | 管理員 (`op`) |
| `/dclink bypass add <玩家>` | 將特定玩家加入免驗證白名單 | `discordlink.admin` | 管理員 (`op`) |
| `/dclink bypass remove <玩家>` | 將特定玩家移出免驗證白名單 | `discordlink.admin` | 管理員 (`op`) |
| `/dclink admin query <玩家\|DiscordID>` | 查詢指定玩家或 Discord ID 的綁定關係 | `discordlink.admin` | 管理員 (`op`) |
| `/dclink admin unbind <玩家\|DiscordID>` | 解除指定玩家或 Discord ID 的所有連動帳號 | `discordlink.admin` | 管理員 (`op`) |
| `/dclink admin list [頁碼]` | 分頁列出伺服器目前所有綁定資料 (每頁 7 筆) | `discordlink.admin` | 管理員 (`op`) |

---

## ⚙️ 配置文件 (`config.yml`)

您可以隨時修改位於 `plugins/DiscordLink/config.yml` 的檔案，修改後在遊戲中輸入 `/dclink reload` 即可生效。

```yaml
# ==========================================================
#                  DiscordLink 設定檔
# ==========================================================

# 1. 資料庫設定
database:
  # SQLite 資料庫的檔案名稱 (儲存於 plugins/DiscordLink/ 目錄下)
  sqlite-file-name: "database.db"

# 2. Discord 機器人設定
discord:
  # Discord 機器人的 Bot Token
  token: "YOUR_DISCORD_BOT_TOKEN"
  # 玩家進行驗證的專屬頻道 ID (Verify Channel ID)
  verify-channel-id: "YOUR_CHANNEL_ID"

# 3. 驗證與綁定設定
verification:
  # 是否強制玩家綁定才能進入伺服器 (Mandatory Verification)
  # - true: 啟用強制驗證。未綁定的玩家登入時會被踢出並顯示驗證碼。
  # - false: 啟用自由驗證。所有玩家皆可直接登入，但可於遊戲內使用指令進行綁定。
  force-binding: true
  # 每個 Discord 帳號上限能綁定的 Minecraft 帳號數量
  max-bindings-per-discord: 1
  # 驗證碼的有效時間 (單位：分鐘，修改後需重啟伺服器)
  code-expiry-minutes: 5

# 4. 遊戲內訊息與提示 (支援 MiniMessage 格式)
messages:
  # 當 force-binding 為 false 時，玩家登入伺服器後顯示的提示訊息 (留空不提示)
  join-reminder: "<yellow>[DiscordLink]</yellow> <gray>您尚未綁定 Discord 帳號，請在遊戲內輸入 <gold>/dclink link</gold> 取得驗證碼進行綁定！</gray>"

# 5. Discord Embed 訊息卡片排版設定 (自訂機器人發送的所有嵌入式卡片內容)
# 顏色設定格式為十六進制 Hex 色碼 (例如 "#2ECC71" 為綠色，"#E74C3C" 為紅色)
embeds:
  # A. 驗證成功卡片 (發送於驗證頻道)
  verify-success:
    title: "✅ 綁定成功！"
    description: "玩家：**{player}** (ID: `{uuid}...`)\n您現在可以登入伺服器了。"
    color: "#2ECC71"
    footer: "極光の幻想鄉 • 驗證系統"

  # B. 驗證失敗卡片 (發送於驗證頻道)
  verify-fail:
    title: "❌ 驗證失敗"
    description: "驗證碼無效或已過期，請重新登入遊戲獲取新代碼。"
    color: "#E74C3C"
    footer: "極光の幻想鄉 • 驗證系統"

  # C. 達到綁定上限卡片 (發送於驗證頻道)
  verify-limit:
    title: "❌ 綁定失敗"
    description: "您的 Discord 帳號已達到綁定上限 ({max} 個帳號)。"
    color: "#E67E22"
    footer: "極光の幻想鄉 • 驗證系統"

  # D. 私訊通知卡片 (當綁定成功時，私訊發送給玩家的 DM)
  private-message:
    enabled: true
    title: "🎉 帳號綁定成功 (Account Linked)"
    description: "您的 Discord 帳號已成功與 Minecraft 帳號 **{player}** 完成綁定！\n現在您可以隨時進入伺服器遊玩囉。"
    color: "#2ECC71"
    footer: "極光の幻想鄉 • 驗證系統"

  # E. 管理員日誌日誌 (當玩家綁定成功時，發送至管理員指定頻道做紀錄)
  admin-log:
    enabled: true
    channel-id: "YOUR_ADMIN_LOG_CHANNEL_ID"
    title: "📥 帳號綁定日誌 (Admin Log)"
    description: "Discord 用戶 <@{discord_id}> (`{discord_id}`) 已成功綁定 Minecraft 帳號 **{player}**\nUUID: `{uuid}`"
    color: "#3498DB"
    footer: "DiscordLink System Log"
```

---

## 🚀 安裝指南

1. 下載並編譯本專案，將產生的 `DiscordLink-x.x.x.jar` 放入伺服器的 `plugins/` 資料夾中。
2. 啟動伺服器以生成預設設定檔，隨後關閉伺服器。
3. 前往 [Discord Developer Portal](https://discord.com/developers/applications) 建立一個 Application，並在 **Bot** 頁面啟用 **`MESSAGE CONTENT INTENT`**（訊息內容意圖，此為讀取驗證碼必備權限）。
4. 複製您的 Bot Token，並貼入 `config.yml` 的 `discord.token` 欄位。
5. 邀請機器人加入您的 Discord 伺服器。
6. 在 Discord 中建立一個獨立的驗證文字頻道（如 `#mc-verify`），複製該頻道 ID 並貼入 `discord.verify-channel-id`。
7. 啟動伺服器，即可在控制台看見精美的顏色日誌以及模式配置狀態報告！

---

## ☕ 聯絡與支援

如有任何建議、回報 Bug 或客製化功能需求，歡迎透過 Github 提交 Issue 或是發起 Pull Request！
