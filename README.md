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

## Discord 機器人斜線指令 (Slash Commands)

為確保資料隱私與安全性，以下指令預設僅限擁有 **管理員 (ADMINISTRATOR)** 或 **管理伺服器 (MANAGE_SERVER)** 權限的 Discord 成員在伺服器頻道中使用。

| 指令 | 說明 | 參數 |
| :--- | :--- | :--- |
| `/querydc` | 輸入 Minecraft 玩家名稱，查詢其綁定的 Discord 帳號 | `player` (Minecraft 玩家名稱，必填) |
| `/querymc` | 輸入 Discord 用戶（可直接提及）或 ID，查詢其綁定的 Minecraft 帳號 | `user` (Discord 用戶、提及或 ID，必填) |

## 第三方 HTTP API 介面

本插件內置一個輕量級的 HTTP 伺服器，預設監聽連接埠 `25580`。您可以透過此介面讓第三方程式（如 Python）安全地存取與查詢綁定資料。

### API 金鑰驗證
所有的 GET 請求皆必須在 Header 中附帶 Authorization Bearer 金鑰，或是使用 Query 參數傳送：
* **Header (推薦)**：`Authorization: Bearer <您的API金鑰>`
* **Query**：`?key=<您的API金鑰>`

> [!NOTE]
> 插件首次啟動時，若 `api.key` 設定為 `"GENERATE"`，系統將會**自動生成一組 64 位（Hex）的隨機安全金鑰**並寫入 `config.yml` 中。

### API 介面規格

* **URL**: `http://<IP>:25580/api/query`
* **Method**: `GET`
* **CORS**: 支援 (可直接在前端或跨網域調用)

#### 1. Minecraft 查 Discord
* **請求參數**：
  * `action=query_dc`
  * `target=<Minecraft玩家名稱 或 UUID>`
* **回傳 JSON 範例 (已綁定)**：
  ```json
  {
    "status": "success",
    "bound": true,
    "data": {
      "player": "Steve",
      "uuid": "8667ba71-b85a-4004-af54-4b3a95970922",
      "discord_id": "123456789012345678"
    }
  }
  ```

#### 2. Discord 查 Minecraft
* **請求參數**：
  * `action=query_mc`
  * `target=<Discord使用者ID>`
* **回傳 JSON 範例**：
  ```json
  {
    "status": "success",
    "bound": true,
    "data": [
      {
        "player": "Steve",
        "uuid": "8667ba71-b85a-4004-af54-4b3a95970922"
      }
    ]
  }
  ```

#### 3. 取得所有綁定清單
* **請求參數**：
  * `action=all`
* **回傳 JSON 範例**：
  ```json
  {
    "status": "success",
    "total": 1,
    "data": [
      {
        "player": "Steve",
        "uuid": "8667ba71-b85a-4004-af54-4b3a95970922",
        "discord_id": "123456789012345678"
      }
    ]
  }
  ```

### Python 範例程式碼

您可以使用下方的 Python 腳本來與本插件的 HTTP API 進行互動：

```python
import requests

# 1. 配置設定
API_URL = "http://localhost:25580/api/query"
# 請替換為您 config.yml 中隨機生成的 api.key (64 hex)
API_KEY = "YOUR_GENERATED_64_HEX_API_KEY"

headers = {
    # 支援以 Bearer Token 形式通過身份驗證
    "Authorization": f"Bearer {API_KEY}"
}

def query_dc_by_mc(player_name_or_uuid):
    """
    透過 Minecraft 玩家名稱或 UUID 查詢綁定的 Discord ID
    """
    params = {
        "action": "query_dc",
        "target": player_name_or_uuid
    }
    response = requests.get(API_URL, headers=headers, params=params)
    return response.json()

def query_mc_by_dc(discord_id):
    """
    透過 Discord ID 查詢綁定的 Minecraft 帳號清單
    """
    params = {
        "action": "query_mc",
        "target": discord_id
    }
    response = requests.get(API_URL, headers=headers, params=params)
    return response.json()

def query_all_bindings():
    """
    獲取目前伺服器中所有的綁定關係
    """
    params = {
        "action": "all"
    }
    response = requests.get(API_URL, headers=headers, params=params)
    return response.json()

if __name__ == "__main__":
    # 1. 查詢所有綁定關係
    print("=== 所有綁定關係 ===")
    print(query_all_bindings())
    
    # 2. 用 Minecraft 名稱查詢 Discord
    print("\n=== 用 Minecraft 名稱查詢 Discord ===")
    print(query_dc_by_mc("Steve"))

    # 3. 用 Discord ID 查詢 Minecraft
    print("\n=== 用 Discord ID 查詢 Minecraft ===")
    print(query_mc_by_dc("123456789012345678"))
```