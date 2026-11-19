# DiscordLink

DiscordLink 是 Paper 伺服器的 Discord 整合插件：帳號綁定、聊天互通、控制台頻道、各種通知、線上玩家清單，以及聊天中的 `[item]`、`[inv]`、`[ender]` 物品展示（Discord 端附上圖片）。一個插件、一個機器人連線，取代 DiscordSRV、InteractiveChat 與 InteractiveChat DiscordSRV Addon。

## 功能特性

* **帳號綁定**：
  * **強制綁定**：未綁定的玩家連線時被擋下，畫面直接顯示代碼。
  * **自由綁定**：玩家可以進服，用 `/dclink link` 取得代碼。
  * 防止亂猜代碼、免驗證名單、綁定後自動給身分組與同步暱稱。
* **聊天互通**：
  * 遊戲聊天用 Webhook 送到 Discord，顯示玩家頭像與名稱。
  * Discord 訊息在遊戲內顯示為 `[Discord][最高身分組]名稱 >> 訊息`（身分組套用 Discord 的顏色）；網址、圖片、影片、檔案、貼圖都變成可點擊的 `[連結]`、`[圖片]` 等文字，不直接顯示網址。
  * 遊戲內顯示 Discord 上看到的名稱（伺服器暱稱）；所有訊息都不會觸發 `@everyone`。
  * 被禁言插件取消的聊天不會轉送。
* **控制台頻道**：後台訊息合併後送到 Discord（遮住 IP 與 Token），指定身分組可以在頻道輸入後台指令，並有指令黑名單。
* **通知**：伺服器開啟／關閉（純文字）、加入、離開、首次加入、死亡、成就（附原版成就圖示）（含 [CustomMilestone](https://github.com/clre20/CustomMilestone) 的自訂成就）；每一種都能分別開關、分別送到不同頻道；隱身中的玩家不會通知。
* **線上玩家清單**：在聊天頻道輸入 `playerlist`，或使用斜線指令 `/playerlist`，回覆跟遊戲內 Tab 清單一樣的圖片（頭像、前綴、延遲）。
* **機器人狀態與頻道主題**：顯示線上人數與 TPS。
* **物品展示**（與 InteractiveChat 相同的樣式）：
  * 聊天輸入 `[item]` 變成 `[物品名稱 x數量]`，滑鼠移上去看完整說明，點擊開啟「物品」介面；空手時顯示 `[空氣]`。
  * `[inv]`、`[ender]` 點擊後開啟背包（含頭顱、藥水效果、裝備）／終界箱快照；在介面裡左鍵點物品可以預覽，界伏盒與束口袋會顯示裡面的東西（有「返回」按鈕）；所有介面都是唯讀箱子介面。
  * Discord 端附上嵌入卡片：物品名稱與顏色、右上角物品圖示、與遊戲相同的說明框圖片（含物品 ID 等進階資訊），背包／終界箱則附上介面圖片（第一次使用時自動下載原版材質，約 4.5 MB）。
* **提及玩家**：聊天中提到線上玩家的名稱，對方會聽到音效並看到提示。
* **所有文字可改**：遊戲訊息、斷線畫面、Discord 卡片都在 `messages.yml`，支援 MiniMessage 與 `&` 顏色碼。
* **設定自動補齊**：更新後自動補上新項目並保留原本的值。
* **輕量**：綁定資料在記憶體、資料庫背景寫入；圖片與 Discord 傳送都在背景執行緒，佇列有上限。
* **HTTP API（經 HttpHub）**、**版本檢查**。

## 支援環境

* Paper 26.x（Purpur 等 Paper 分支理論上相容；不支援 Spigot、Folia）
* Java 25 以上
* 選用：[HttpHub](https://github.com/clre20/HttpHub)（要用 HTTP API 時才需要）
* 同時安裝 DiscordSRV 或 InteractiveChat 時，重疊的功能會自動停用並在後台提示；建議移除它們。

## 指令與權限

> 主指令 `/discordlink`，別名 `/dclink`

| 指令 | 說明 | 執行者 | 權限 | 預設 |
| :--- | :--- | :--- | :--- | :--- |
| `/dclink help` | 指令說明 | 玩家／後台 | `discordlink.help` | 所有人 |
| `/dclink link` | 取得綁定代碼 | 玩家 | `discordlink.use` | 所有人 |
| `/dclink version` | 檢查更新 | 玩家／後台 | `discordlink.version` | OP |
| `/dclink admin query <玩家\|DiscordID>` | 查詢綁定 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink admin unbind <玩家\|DiscordID>` | 解除綁定 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink admin list [頁碼]` | 綁定清單 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink bypass add <玩家>` | 加入免驗證名單 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink bypass remove <玩家>` | 移出免驗證名單 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink bypass list` | 免驗證名單 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink reload` | 重新載入設定（Token 有變會重新連線） | 玩家／後台 | `discordlink.admin` | OP |

> 有 `discordlink.version` 的玩家，在有新版本時登入 3 秒後會收到提醒。

### 聊天功能權限

| 功能 | 權限 | 預設 |
| :--- | :--- | :--- |
| `[item]` 展示手上物品 | `discordlink.showcase.item` | 所有人 |
| `[inv]` 展示背包 | `discordlink.showcase.inventory` | 所有人 |
| `[ender]` 展示終界箱 | `discordlink.showcase.ender` | 所有人 |
| 提及玩家 | `discordlink.mention` | 所有人 |

### Discord 斜線指令

| 指令 | 說明 | 可使用 |
| :--- | :--- | :--- |
| `/playerlist` | 線上玩家清單（只有自己看得到） | 所有人 |
| `/querydc` | 用 Minecraft 玩家查詢綁定的 Discord（`player`：玩家名稱或 UUID） | 「管理員」或「管理伺服器」 |
| `/querymc` | 用 Discord 用戶查詢綁定的 Minecraft 帳號（`user`：Discord ID 或提及） | 「管理員」或「管理伺服器」 |

## 使用範例

### 1. 第一次設定

1. 在 [Discord Developer Portal](https://discord.com/developers/applications) 建立機器人，開啟 **Message Content Intent**，邀請進伺服器。機器人需要的權限：讀取／傳送訊息、嵌入連結、附加檔案、新增反應、管理 Webhook；要同步暱稱或身分組時再加「管理暱稱」「管理身分組」，要改頻道主題時加「管理頻道」。
2. 編輯 `plugins/DiscordLink/config.yml`：

```yaml
discord:
  token: "你的機器人 Token"
  verify-channel-id: "驗證頻道 ID"
chat:
  channel-id: "聊天頻道 ID"
console:
  enabled: true
  channel-id: "控制台頻道 ID（只給管理員看）"
# 每種通知可分別指定頻道（留空 = 聊天頻道）
notifications:
  join:
    enabled: true
    channel-id: "加入離開頻道 ID"
  death:
    enabled: true
    channel-id: "死亡頻道 ID"
```

3. 執行 `/dclink reload`（或重啟伺服器）。

### 2. 玩家綁定

```bash
# 自由綁定模式：玩家在遊戲內輸入
/dclink link
# 強制綁定模式：玩家連線時畫面就會顯示代碼
# 接著把 5 位數代碼貼到 Discord 驗證頻道
```

### 3. 物品展示

```text
看我的新劍 [item]
這是我的背包 [inv]，終界箱 [ender]
```

遊戲裡會變成可以懸停、點擊的文字；Discord 聊天頻道會同時收到對應的圖片。

### 4. 從控制台頻道執行指令

在控制台頻道直接輸入指令（不用加 `/`），成功會加上 ✅。只有 `console.command-role-ids` 裡的身分組或「管理員」能用；`console.command-blacklist` 的指令（包含 `minecraft:stop`、`execute … run stop` 這類寫法）一律擋下。

## HTTP API

需要安裝 [HttpHub](https://github.com/clre20/HttpHub)。API 跟其他插件共用 HttpHub 的端口（預設 `52010`），DiscordLink 不再自己開端口。

* **網址**：`http://<伺服器IP>:<HttpHub 端口>/discordlink/api/query`
* **方法**：`GET`
* **金鑰**：第一次啟動自動產生並寫入 `config.yml` 的 `api.key`
  * 標頭（建議）：`Authorization: Bearer <金鑰>`
  * 網址參數：`?key=<金鑰>`（可用 `api.allow-query-key: false` 關閉）

| action | 參數 | 說明 |
| :--- | :--- | :--- |
| `check` | `target`：玩家名稱或 UUID | 這位玩家有沒有綁定 |
| `bound` | — | 所有已綁定的玩家 |
| `unbound` | `scope`：`all`（預設，進過伺服器的所有玩家）或 `online`（目前在線） | 沒有綁定的玩家 |
| `query_dc` | `target`：玩家名稱或 UUID | Minecraft 查 Discord（舊版相容） |
| `query_mc` | `target`：Discord ID | Discord 查 Minecraft |
| `all` | — | 同 `bound`（舊版相容） |

回應範例（`check`）：

```json
{
  "status": "success",
  "known": true,
  "bound": true,
  "player": "Steve",
  "uuid": "8667ba71-b85a-4004-af54-4b3a95970922",
  "online": false,
  "bypass": false,
  "discord_id": "123456789012345678"
}
```

回應範例（`unbound`）：

```json
{
  "status": "success",
  "scope": "all",
  "total": 1,
  "data": [ { "player": "Alex", "uuid": "…", "online": true, "bypass": false } ]
}
```

Python 範例：

```python
import requests

API_URL = "http://localhost:52010/discordlink/api/query"
API_KEY = "config.yml 裡的 api.key"
headers = {"Authorization": f"Bearer {API_KEY}"}

# 這位玩家有沒有綁定
print(requests.get(API_URL, headers=headers, params={"action": "check", "target": "Steve"}).json())
# 有誰綁定
print(requests.get(API_URL, headers=headers, params={"action": "bound"}).json())
# 有誰沒綁定（進過伺服器的所有玩家；只看在線的玩家加 "scope": "online"）
print(requests.get(API_URL, headers=headers, params={"action": "unbound"}).json())
```

> 從 1.6.x 升級：舊的 `api.port`（25580）已移除，請改用上面的網址。

## 設定檔

| 檔案 | 內容 |
| :--- | :--- |
| `config.yml` | Token、頻道、綁定、聊天、控制台、通知、狀態、物品展示、提及、API、版本檢查 |
| `messages.yml` | 所有遊戲訊息、斷線畫面、Discord 卡片、通知與斜線指令文字 |
| `database.db` | 綁定與免驗證名單（SQLite，格式與舊版相同） |
| `assets/` | 物品展示圖片用的原版材質快取（換 Minecraft 版本時自動更新並刪除舊的） |

## 開發者

```bash
mvn clean package
# 產出 target/DiscordLink-<版本>.jar
```

---

# DiscordLink (English)

All-in-one Discord integration for Paper 26.x: account linking, chat bridge, console channel, notifications, player list, and `[item]` / `[inv]` / `[ender]` showcases with rendered images on Discord. Replaces DiscordSRV, InteractiveChat and InteractiveChat DiscordSRV Addon with a single bot connection.

## Features

* **Linking**: forced or free linking with 5-digit codes, guess protection, bypass list, linked role and nickname sync.
* **Chat bridge**: webhook messages with player avatars, replies, attachments; mentions are never pinged.
* **Console channel**: batched log relay (IPs and token masked) and role-restricted commands with a blacklist.
* **Notifications**: start/stop, join/quit/first join, death, advancements (including CustomMilestone).
* **Player list**: type `playerlist` in the chat channel or use `/playerlist`.
* **Showcase**: `[item]` hover, `[inv]` / `[ender]` read-only snapshots, rendered tooltip and inventory images on Discord.
* **Mentions**: mentioned players get a sound and a notice.
* **Editable text**, **auto-filled config**, **HTTP API via HttpHub**, **update checker**.

## Requirements

Paper 26.x, Java 25.

## Commands & Permissions

| Command | Description | Permission | Default |
| :--- | :--- | :--- | :--- |
| `/dclink link` | Get a link code | `discordlink.use` | everyone |
| `/dclink version` | Check for updates | `discordlink.version` | op |
| `/dclink admin <query\|unbind\|list>` | Manage links | `discordlink.admin` | op |
| `/dclink bypass <add\|remove\|list>` | Bypass list | `discordlink.admin` | op |
| `/dclink reload` | Reload config | `discordlink.admin` | op |
| `[item]` / `[inv]` / `[ender]` | Showcase in chat | `discordlink.showcase.item` / `.inventory` / `.ender` | everyone |
| Mentions | Mention players in chat | `discordlink.mention` | everyone |
