package clre20.discordLink;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;

public final class DiscordLink extends JavaPlugin {

    private JDA jda;
    private DatabaseManager dbManager;
    private ApiServer apiServer;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // 1. 帶顏色控制台 log 輔助方法
    public void logInfo(String message) {
        getServer().getConsoleSender().sendMessage(mm.deserialize("<aqua>[DiscordLink]</aqua> <gray>" + message + "</gray>"));
    }

    public void logSuccess(String message) {
        getServer().getConsoleSender().sendMessage(mm.deserialize("<aqua>[DiscordLink]</aqua> <green>" + message + "</green>"));
    }

    public void logWarning(String message) {
        getServer().getConsoleSender().sendMessage(mm.deserialize("<aqua>[DiscordLink]</aqua> <yellow>[警告] " + message + "</yellow>"));
    }

    public void logError(String message) {
        getServer().getConsoleSender().sendMessage(mm.deserialize("<aqua>[DiscordLink]</aqua> <red>[錯誤] " + message + "</red>"));
    }

    public void printConfigReport() {
        boolean forceBinding = getConfig().getBoolean("verification.force-binding", true);
        boolean pmEnabled = getConfig().getBoolean("embeds.private-message.enabled", 
                getConfig().getBoolean("notifications.private-message.enabled", false));
        boolean logEnabled = getConfig().getBoolean("embeds.admin-log.enabled", 
                getConfig().getBoolean("notifications.admin-log.enabled", false));
        String adminChannelId = getConfig().getString("embeds.admin-log.channel-id", 
                getConfig().getString("notifications.admin-log.channel-id", "未設定"));

        logInfo("==================================================");
        logInfo("當前運行模式配置：");
        logInfo("  ➤ 強制驗證模式: " + (forceBinding ? "<red>強制啟用 (未綁定無法登入)</red>" : "<green>自由模式 (未綁定可登入，使用 /dclink link 綁定)</green>"));
        logInfo("  ➤ 綁定成功私訊: " + (pmEnabled ? "<green>已啟用</green>" : "<red>已停用</red>"));
        logInfo("  ➤ 管理員日誌記錄: " + (logEnabled ? "<green>已啟用 (頻道 ID: " + adminChannelId + ")</green>" : "<red>已停用</red>"));
        logInfo("==================================================");
    }

    @Override
    public void onEnable() {
        // 初始化設定檔
        saveDefaultConfig();

        // 自動生成 API Key 邏輯
        if (!getConfig().contains("api.key") || "GENERATE".equalsIgnoreCase(getConfig().getString("api.key"))) {
            java.security.SecureRandom random = new java.security.SecureRandom();
            byte[] bytes = new byte[32]; // 32 bytes = 256 bits
            random.nextBytes(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            String randomKey = sb.toString(); // 64 字元的 Hex 字串

            getConfig().set("api.enabled", getConfig().getBoolean("api.enabled", true));
            getConfig().set("api.port", getConfig().getInt("api.port", 25580));
            getConfig().set("api.key", randomKey);
            saveConfig();
            String maskedKey = randomKey.substring(0, 4) + "********************************************************" + randomKey.substring(60);
            logInfo("已自動為 HTTP API 生成隨機安全金鑰 (64 hex): " + maskedKey + " 並儲存至 config.yml 中！");
        }

        // 檢查是否需要補齊新版設定項目 (保留玩家現有設定值)
        if (!getConfig().contains("embeds.verify-success.title")) {
            getConfig().options().copyDefaults(true);
            saveConfig();
            logInfo("偵測到設定檔版本較舊，已自動補齊缺失的卡片設定欄位 (Embeds)，並完整保留您原先設定的 Token 與頻道 ID！");
        }

        String token = getConfig().getString("discord.token", getConfig().getString("discord-token"));
        String channelId = getConfig().getString("discord.verify-channel-id", getConfig().getString("verify-channel-id"));
        String dbName = getConfig().getString("database.sqlite-file-name", getConfig().getString("sqlite-file-name", "database.db"));
        int expiryMinutes = getConfig().getInt("verification.code-expiry-minutes", 5);

        // 預先檢查 Token 是否為空或預設預留字串，若是則直接停用插件，避免拋出非同步執行緒錯誤
        if (token == null || token.isEmpty() || token.equalsIgnoreCase("YOUR_DISCORD_BOT_TOKEN")) {
            logError("==================================================");
            logError("  無法啟動 Discord 機器人！");
            logError("  請在 config.yml 中將 'discord.token' 設定為正確的 Bot Token！");
            logError("==================================================");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 初始化管理器
        ValidationManager validationManager = new ValidationManager(expiryMinutes);
        dbManager = new DatabaseManager(getDataFolder(), dbName);

        // 註冊 Minecraft 監聽器
        getServer().getPluginManager().registerEvents(new LoginListener(dbManager, validationManager), this);

        DiscordLinkCommand commandExecutor = new DiscordLinkCommand(dbManager, validationManager);
        getCommand("discordlink").setExecutor(commandExecutor);
        getCommand("discordlink").setTabCompleter(commandExecutor);

        // 啟動 API 伺服器
        if (getConfig().getBoolean("api.enabled", true)) {
            int apiPort = getConfig().getInt("api.port", 25580);
            String apiKey = getConfig().getString("api.key");
            apiServer = new ApiServer(this, dbManager, apiPort, apiKey);
            apiServer.start();
        }

        // 初始化 JDA
        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new DiscordListener(this, validationManager, dbManager, getServer()))
                    .build();
            jda.awaitReady();
            logSuccess("Discord 機器人已成功啟動並連線！");

            // 註冊 Slash 指令
            jda.updateCommands().addCommands(
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("querydc", "輸入 Minecraft 玩家名稱查詢綁定的 Discord 帳號")
                            .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "player", "Minecraft 玩家名稱", true),
                    net.dv8tion.jda.api.interactions.commands.build.Commands.slash("querymc", "輸入 Discord 用戶或 ID 查詢綁定的 Minecraft 帳號")
                            .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "user", "Discord 用戶 ID、提及(Mention)或名稱", true)
            ).queue(
                    success -> logInfo("已成功向 Discord 註冊斜線指令 (querydc / querymc)"),
                    error -> logError("註冊斜線指令失敗: " + error.getMessage())
            );

            printConfigReport();
        } catch (Exception e) {
            logError("無法啟動 Discord 機器人，請檢查 Token 是否正確。");
            logError("詳細錯誤簡述: " + e.getMessage());
            
            // 立即強制關閉非同步執行緒，防範 classloader 關閉後背景連線丟出 zip file closed 警告
            if (jda != null) {
                try {
                    jda.shutdownNow();
                    jda.awaitShutdown(java.time.Duration.ofSeconds(5));
                } catch (Exception ignored) {}
                jda = null;
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (apiServer != null) {
            apiServer.stop();
        }
        if (jda != null) {
            try {
                // 優先使用優雅關閉
                jda.shutdown();
                // 阻塞等待最多 5 秒讓 JDA 內部連線與背景執行緒完全退場，防止 classloader 關閉後丟出 zip file closed 錯誤
                if (!jda.awaitShutdown(java.time.Duration.ofSeconds(5))) {
                    jda.shutdownNow();
                    jda.awaitShutdown(java.time.Duration.ofSeconds(2));
                }
            } catch (InterruptedException e) {
                try {
                    jda.shutdownNow();
                } catch (Exception ignored) {}
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
            } finally {
                jda = null;
            }
        }
        // Sqlite Connection pool 如果未來有使用 HikariCP，須在此關閉
    }
}