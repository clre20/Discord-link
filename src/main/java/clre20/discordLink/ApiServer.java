package clre20.discordLink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

public class ApiServer {
    private final DiscordLink plugin;
    private final DatabaseManager dbManager;
    private HttpServer server;
    private final int port;
    private final String apiKey;

    public ApiServer(DiscordLink plugin, DatabaseManager dbManager, int port, String apiKey) {
        this.plugin = plugin;
        this.dbManager = dbManager;
        this.port = port;
        this.apiKey = apiKey;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/api/query", new QueryHandler());
            // 設定 Executor 以便非同步處理 HTTP 請求，不佔用遊戲主線程
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            plugin.logSuccess("HTTP API 伺服器已在連接埠 " + port + " 啟動！");
        } catch (IOException e) {
            plugin.logError("無法啟動 HTTP API 伺服器: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            plugin.logInfo("HTTP API 伺服器已成功關閉。");
        }
    }

    private class QueryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 允許跨網域 (CORS)，方便外部調用
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Authorization, Content-Type");
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

            // 處理 OPTIONS 請求 (CORS 預檢)
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"status\":\"error\",\"message\":\"僅支援 GET 請求\"}");
                return;
            }

            // 驗證 API Key
            String requestKey = getApiKeyFromRequest(exchange);
            if (requestKey == null || !requestKey.equals(apiKey)) {
                sendResponse(exchange, 401, "{\"status\":\"error\",\"message\":\"未授權的請求 (API Key 錯誤)\"}");
                return;
            }

            // 解析 Query 參數
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
            String action = params.get("action");
            String target = params.get("target");

            if (action == null) {
                sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"缺少參數 action (可選值: query_dc, query_mc, all)\"}");
                return;
            }

            String jsonResponse;
            switch (action.toLowerCase()) {
                case "query_dc":
                    if (target == null || target.isEmpty()) {
                        jsonResponse = "{\"status\":\"error\",\"message\":\"缺少 target 參數 (Minecraft 玩家名稱或 UUID)\"}";
                        sendResponse(exchange, 400, jsonResponse);
                        return;
                    }
                    jsonResponse = handleQueryDc(target);
                    break;
                case "query_mc":
                    if (target == null || target.isEmpty()) {
                        jsonResponse = "{\"status\":\"error\",\"message\":\"缺少 target 參數 (Discord ID)\"}";
                        sendResponse(exchange, 400, jsonResponse);
                        return;
                    }
                    jsonResponse = handleQueryMc(target);
                    break;
                case "all":
                    jsonResponse = handleQueryAll();
                    break;
                default:
                    jsonResponse = "{\"status\":\"error\",\"message\":\"未知的 action 參數。請使用: query_dc, query_mc, all\"}";
                    sendResponse(exchange, 400, jsonResponse);
                    return;
            }

            sendResponse(exchange, 200, jsonResponse);
        }

        private String getApiKeyFromRequest(HttpExchange exchange) {
            // 1. 優先從 Header: Authorization: Bearer <key> 讀取
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                return authHeader.substring(7).trim();
            }

            // 2. 備用：從 Query 參數中讀取 key=<key>
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
            return params.get("key");
        }

        private Map<String, String> parseQueryParams(String query) {
            Map<String, String> result = new HashMap<>();
            if (query == null || query.isEmpty()) {
                return result;
            }
            for (String param : query.split("&")) {
                String[] entry = param.split("=");
                if (entry.length > 1) {
                    result.put(entry[0], entry[1]);
                } else if (entry.length == 1) {
                    result.put(entry[0], "");
                }
            }
            return result;
        }

        private String handleQueryDc(String target) {
            try {
                UUID uuid;
                String playerName = target;
                if (target.length() == 36 || target.length() == 32) {
                    String formattedUuid = target.length() == 32 
                        ? target.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5")
                        : target;
                    uuid = UUID.fromString(formattedUuid);
                    OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                    if (op.getName() != null) playerName = op.getName();
                } else {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(target);
                    uuid = op.getUniqueId();
                }

                String discordId = dbManager.getDiscordId(uuid);
                if (discordId == null) {
                    return "{\"status\":\"success\",\"bound\":false,\"message\":\"該玩家尚未綁定 Discord\"}";
                }

                return String.format(
                    "{\"status\":\"success\",\"bound\":true,\"data\":{\"player\":\"%s\",\"uuid\":\"%s\",\"discord_id\":\"%s\"}}",
                    playerName, uuid.toString(), discordId
                );
            } catch (Exception e) {
                return "{\"status\":\"error\",\"message\":\"查詢失敗: " + e.getMessage() + "\"}";
            }
        }

        private String handleQueryMc(String target) {
            if (!target.matches("\\d{17,20}")) {
                return "{\"status\":\"error\",\"message\":\"無效的 Discord ID 格式\"}";
            }

            try {
                List<UUID> uuids = dbManager.getBoundUuids(target);
                if (uuids.isEmpty()) {
                    return "{\"status\":\"success\",\"bound\":false,\"message\":\"該 Discord ID 未綁定任何 Minecraft 帳號\"}";
                }

                StringBuilder sb = new StringBuilder();
                sb.append("{\"status\":\"success\",\"bound\":true,\"data\":[");
                for (int i = 0; i < uuids.size(); i++) {
                    UUID uuid = uuids.get(i);
                    OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                    sb.append(String.format(
                        "{\"player\":\"%s\",\"uuid\":\"%s\"}",
                        op.getName() != null ? op.getName() : "未知玩家",
                        uuid.toString()
                    ));
                    if (i < uuids.size() - 1) {
                        sb.append(",");
                    }
                }
                sb.append("]}");
                return sb.toString();
            } catch (Exception e) {
                return "{\"status\":\"error\",\"message\":\"查詢失敗: " + e.getMessage() + "\"}";
            }
        }

        private String handleQueryAll() {
            try {
                Map<UUID, String> bindings = dbManager.getAllBindings();
                StringBuilder sb = new StringBuilder();
                sb.append("{\"status\":\"success\",\"total\":").append(bindings.size()).append(",\"data\":[");
                
                int index = 0;
                for (Map.Entry<UUID, String> entry : bindings.entrySet()) {
                    UUID uuid = entry.getKey();
                    String dcId = entry.getValue();
                    OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                    
                    sb.append(String.format(
                        "{\"player\":\"%s\",\"uuid\":\"%s\",\"discord_id\":\"%s\"}",
                        op.getName() != null ? op.getName() : "未知玩家",
                        uuid.toString(),
                        dcId
                    ));
                    if (index < bindings.size() - 1) {
                        sb.append(",");
                    }
                    index++;
                }
                sb.append("]}");
                return sb.toString();
            } catch (Exception e) {
                return "{\"status\":\"error\",\"message\":\"查詢失敗: " + e.getMessage() + "\"}";
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
            byte[] responseBytes = responseText.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}
