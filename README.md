# DiscordLink

DiscordLink 是專為 Paper 26.x 伺服器設計的一站式 Discord 整合插件：帳號綁定、聊天互通、控制台頻道、各種通知、線上玩家清單，以及聊天中的 `[item]`、`[inv]`、`[ender]` 物品展示（Discord 端附上渲染圖片）。此外，本插件對外開放 **Discord Bot 服務接口（DiscordLinkAPI）**，供其他插件（如 AetherMart、Cmail 等）共用機器人連線發送私訊、收據附件與頻道訊息，一個插件、一個機器人連線，徹底取代 DiscordSRV、InteractiveChat 與 InteractiveChat DiscordSRV Addon。

---

## 功能特性

* **對外開放 Bot 服務接口（DiscordLinkAPI / DiscordBotService）**：
  * 開放 `DiscordLinkAPI` 供伺服器上其他外掛直接共用本插件的機器人連線。
  * 支援非同步發送私訊（可附帶 PDF 帳單、圖片等多個附件檔案）、發送頻道訊息、即時查詢玩家 Discord 綁定狀態與 ID。
  * 其他插件無須自行打包 JDA 或重複建立 WebSocket 連線，大幅減少伺服器負載與線程數（AetherMart 減肥 12MB+，節省 15 條線程）。
* **操作與調用稽核日誌（Audit Logging）**：
  * 外部插件調用 Bot 發送私訊、頻道訊息或查詢綁定時，控制台自動記錄 `[DiscordLink] [Audit]` 稽核日誌，便於追蹤管理。
* **`/dclink status` 狀態面板**：
  * 顯示 Discord Bot 在線狀態、Gateway Ping 延遲、已綁定玩家總數，以及目前調用 Bot 接口的外部插件清單與調用計數。
* **聊天格式細緻優化**：
  * Discord 訊息轉送遊戲內顯示為 `[Discord] [最高身分組] 玩家名稱 >> 訊息`（身分組套用 Discord 顏色），間距標準化、閱讀體驗清晰舒適。
* **帳號綁定**：
  * **強制綁定**：未綁定的玩家連線時被擋下，畫面直接顯示 5 位數驗證碼。
  * **自由綁定**：玩家可以進服，使用 `/dclink link` 取得驗證碼。
  * 防止亂猜代碼、免驗證名單（Bypass）、綁定後自動發放身分組與同步伺服器暱稱。
* **聊天互通**：
  * 遊戲聊天透過 Webhook 送至 Discord，顯示玩家頭像與自訂名稱。
  * 網址、圖片、影片、檔案、貼圖在遊戲內轉化為可點擊之 `[連結]`、`[圖片]` 標籤。
  * 遊戲內顯示 Discord 伺服器暱稱；所有訊息絕不會觸發 `@everyone` 或 `@here`。
  * 被禁言插件取消的發言不會轉送。
* **控制台頻道**：後台日誌合併分批轉送至 Discord（自動遮蔽 IP 與 Token），指定身分組可在頻道輸入指令，並具備完整指令黑名單。
* **伺服器事件通知**：開關服（純文字）、加入、離開、首次加入、死亡、成就進度（附原版圖示，含 [CustomMilestone](https://github.com/clre20/CustomMilestone) 自訂成就）；皆可獨立開關與分派不同頻道。
* **線上玩家清單**：在聊天頻道輸入 `playerlist`，或使用斜線指令 `/playerlist`，回覆與遊戲內 Tab 清單相同之渲染圖片（含頭像、延遲、前後綴）。
* **物品展示（Showcase）**：
  * 聊天輸入 `[item]` 產生懸浮預覽，點擊開啟物品介面。
  * `[inv]`、`[ender]` 點擊開啟背包／終界箱快照介面；支援潛影盒與束口袋內容物預覽。
  * Discord 端附帶精緻嵌入卡片與渲染圖片。
* **版本檢查與 HttpHub 整合**：
  * 若伺服器安裝 [HttpHub](https://github.com/clre20/HttpHub)，自動向 HttpHub 登記集中更新檢查，免開額外連線。

---

## 支援環境

* **伺服器核心**：Paper 26.3（標準穩定版）及更高版本（Java 25）
* **相容核心**：Purpur 等標準 Paper 分支（不支援 Spigot、Folia）

---

## 指令與權限

本插件主指令為 `/dclink`。

| 指令 | 說明 | 執行端 | 權限節點 | 預設 |
| :--- | :--- | :--- | :--- | :--- |
| `/dclink link` | 取得帳號綁定代碼（自由綁定模式） | 玩家 | `discordlink.use` | 所有人 |
| `/dclink status` | 查看機器人連線狀態、Ping、綁定數與外部插件調用統計 | 遊戲內 / 控制台 | `discordlink.admin` | OP |
| `/dclink version` | 查看版本並檢查更新 | 遊戲內 / 控制台 | `discordlink.version` | OP |
| `/dclink admin query <玩家>` | 查詢指定玩家的 Discord 綁定資訊 | 遊戲內 / 控制台 | `discordlink.admin` | OP |
| `/dclink admin unbind <玩家>` | 解除指定玩家的 Discord 綁定 | 遊戲內 / 控制台 | `discordlink.admin` | OP |
| `/dclink admin list [頁數]` | 查看所有已綁定玩家清單 | 遊戲內 / 控制台 | `discordlink.admin` | OP |
| `/dclink bypass add/remove/list` | 管理免綁定名單 | 遊戲內 / 控制台 | `discordlink.admin` | OP |
| `/dclink reload` | 重新載入設定檔 | 遊戲內 / 控制台 | `discordlink.admin` | OP |

### 聊天功能與展示權限

| 功能 | 說明 | 權限節點 | 預設 |
| :--- | :--- | :--- | :--- |
| `[item]` | 在聊天室展示手持物品 | `discordlink.showcase.item` | 所有人 |
| `[inv]` | 在聊天室展示個人背包快照 | `discordlink.showcase.inventory` | 所有人 |
| `[ender]` | 在聊天室展示個人終界箱快照 | `discordlink.showcase.ender` | 所有人 |
| 提及玩家 | 在聊天室輸入玩家名稱發出提示音與通知 | `discordlink.mention` | 所有人 |

---

## 開發者 API（供其他插件調用）

DiscordLink 開放統一的 Bot 接口，讓其他插件（例如商城、郵件、抽獎插件）能夠直接利用現有的 Discord 機器人連線，向玩家發送私訊明細、推播頻道公告，或查詢玩家的綁定資料，無須自行打包 JDA 或開啟額外連線。

### 1. Maven 依賴配置

在你的插件 `pom.xml` 中加入：

```xml
<dependency>
    <groupId>clre20</groupId>
    <artifactId>DiscordLink</artifactId>
    <version>1.9.4</version>
    <scope>provided</scope>
</dependency>
```

### 2. plugin.yml 配置

加入軟依賴（Softdepend），確保 DiscordLink 優先載入：

```yaml
softdepend: [ DiscordLink ]
```

### 3. Java 程式碼調用範例

```java
import clre20.discordLink.api.DiscordLinkAPI;
import clre20.discordLink.api.DiscordBotService;
import java.io.File;
import java.util.List;
import java.util.UUID;

public class MyPluginService {

    public void sendPlayerReceipt(UUID playerUuid, File pdfReceipt) {
        // 1. 檢查服務是否可用
        if (!DiscordLinkAPI.isAvailable()) {
            return;
        }

        // 2. 檢查玩家是否已綁定 Discord
        if (!DiscordLinkAPI.isLinked(playerUuid)) {
            return;
        }

        // 3. 發送私訊 (支援純文字或附加 PDF / 圖片檔案)
        DiscordLinkAPI.sendDirectMessage(myPluginInstance, playerUuid, 
            "感謝您的購買！交易明細已隨信附上。", 
            List.of(pdfReceipt)
        ).thenAccept(success -> {
            if (success) {
                myPluginInstance.getLogger().info("收據已成功發送至玩家 Discord 私訊！");
            }
        });
    }

    public void announceToDiscordChannel(String channelId, String announcement) {
        if (DiscordLinkAPI.isAvailable()) {
            // 發送公佈訊息至指定頻道
            DiscordLinkAPI.sendChannelMessage(myPluginInstance, channelId, announcement);
        }
    }

    public void checkLinkedAccounts() {
        if (DiscordLinkAPI.isAvailable()) {
            // 取得所有已綁定玩家 (UUID -> Discord ID)
            Map<UUID, String> linked = DiscordLinkAPI.getLinkedPlayers();
            // 查詢單一玩家 Discord ID
            String discordId = DiscordLinkAPI.getDiscordId(playerUuid);
        }
    }
}
```

> **安全與稽核日誌**：任何外部插件透過 `DiscordLinkAPI` 呼叫發送訊息或查詢時，DiscordLink 控制台均會輸出 `[DiscordLink] [Audit]` 日誌，記錄調用來源插件名稱、目標使用者或頻道與附件數量，確保安全透明。

---

# DiscordLink (English)

DiscordLink is a comprehensive, all-in-one Discord integration plugin engineered for Paper 26.x servers: account linking, two-way chat bridge, console relay, server event notifications, live player list rendering, and in-game `[item]`, `[inv]`, and `[ender]` showcases with rendered image previews on Discord.

Furthermore, DiscordLink provides a centralized **Developer Bot Service API (`DiscordLinkAPI`)**, allowing external plugins (such as AetherMart, Cmail, etc.) to share this single Discord Bot connection for sending direct messages (with PDF/image attachments), channel announcements, and querying link statuses. One plugin, one single Bot connection, replacing DiscordSRV, InteractiveChat, and InteractiveChat DiscordSRV Addon.

---

## Features

* **Centralized Bot Service API (`DiscordLinkAPI` / `DiscordBotService`)**:
  * External plugins can directly reuse DiscordLink's active bot instance.
  * Send direct messages asynchronously (supporting PDF bills, image receipts, and text), send channel messages, and query linked accounts in memory.
  * Eliminates the need for multiple plugins to bundle JDA or open redundant WebSocket connections (saving 12MB+ jar size and 15+ background threads in plugins like AetherMart).
* **Audit Logging**:
  * Whenever an external plugin invokes bot actions, clear `[DiscordLink] [Audit]` logs are printed to the console for tracking and security.
* **`/dclink status` Dashboard**:
  * Displays Bot connection health, Gateway Ping, total linked players, and connected external plugins with usage counts.
* **Refined Chat Formatting**:
  * In-game messages bridged from Discord are formatted as `[Discord] [HighestRole] PlayerName >> Message`, with clean spacing and Discord role colors applied.
* **Account Linking**:
  * **Forced Mode**: Unlinked players are blocked at connection, displaying a 5-digit verification code.
  * **Free Mode**: Players can join and generate link codes using `/dclink link`.
  * Guess prevention, bypass list, automatic role assignment, and nickname synchronization.
* **Two-Way Chat Bridge**:
  * Webhook relay with player heads and nicknames.
  * Media links, images, videos, and stickers are converted into clean, clickable `[Link]`, `[Image]` tags in Minecraft.
  * `@everyone` and `@here` are strictly sanitized.
* **Console Channel**:
  * Batched log relay with automatic IP and Token masking.
  * Configurable command execution for authorized Discord roles with strict command blacklist.
* **Server Event Notifications**:
  * Server start/stop (plain text), player join, quit, first-join, death, advancements (with original icons, including [CustomMilestone](https://github.com/clre20/CustomMilestone)).
* **Showcase**:
  * `[item]`, `[inv]`, `[ender]` snapshots with rendered Discord card images and container item previews.
* **Update Checker & HttpHub Integration**:
  * Automatically registers centralized update checking with [HttpHub](https://github.com/clre20/HttpHub) if present.

---

## Requirements

* **Server Core**: Paper 26.3 (Stable) or higher (Java 25).
* **Compatible Forks**: Purpur and other standard Paper forks (Spigot and Folia are not supported).

---

## Commands & Permissions

Primary command: `/dclink`.

| Command | Description | Sender | Permission | Default |
| :--- | :--- | :--- | :--- | :--- |
| `/dclink link` | Get an account linking code (free link mode) | Player | `discordlink.use` | Everyone |
| `/dclink status` | View Bot connection status, Ping, link counts, and connected plugins | Player / Console | `discordlink.admin` | OP |
| `/dclink version` | View version and check for updates | Player / Console | `discordlink.version` | OP |
| `/dclink admin query <player>` | Query a player's Discord link info | Player / Console | `discordlink.admin` | OP |
| `/dclink admin unbind <player>` | Unbind a player's Discord account | Player / Console | `discordlink.admin` | OP |
| `/dclink admin list [page]` | View list of linked players | Player / Console | `discordlink.admin` | OP |
| `/dclink bypass add/remove/list` | Manage link bypass list | Player / Console | `discordlink.admin` | OP |
| `/dclink reload` | Reload configuration files | Player / Console | `discordlink.admin` | OP |

---

## Developer API (For External Plugins)

DiscordLink provides a clean API allowing other plugins to leverage the existing Discord Bot without bundling JDA or opening secondary connections.

### 1. Maven Dependency

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>clre20</groupId>
    <artifactId>DiscordLink</artifactId>
    <version>1.9.4</version>
    <scope>provided</scope>
</dependency>
```

### 2. plugin.yml Configuration

Add `DiscordLink` as a soft dependency:

```yaml
softdepend: [ DiscordLink ]
```

### 3. Java Usage Example

```java
import clre20.discordLink.api.DiscordLinkAPI;
import java.io.File;
import java.util.List;
import java.util.UUID;

public class MyPluginService {

    public void sendInvoice(UUID playerUuid, File invoicePdf) {
        // 1. Verify service availability
        if (!DiscordLinkAPI.isAvailable()) {
            return;
        }

        // 2. Check if player has linked their Discord account
        if (!DiscordLinkAPI.isLinked(playerUuid)) {
            return;
        }

        // 3. Send Direct Message with attachments (PDF, images, etc.)
        DiscordLinkAPI.sendDirectMessage(myPluginInstance, playerUuid, 
            "Thank you for your purchase! Your invoice is attached.", 
            List.of(invoicePdf)
        ).thenAccept(success -> {
            if (success) {
                myPluginInstance.getLogger().info("Invoice sent to Discord DM!");
            }
        });
    }

    public void announce(String channelId, String message) {
        if (DiscordLinkAPI.isAvailable()) {
            DiscordLinkAPI.sendChannelMessage(myPluginInstance, channelId, message);
        }
    }
}
```
