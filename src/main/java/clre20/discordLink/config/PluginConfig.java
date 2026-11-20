package clre20.discordLink.config;

import clre20.discordLink.DiscordLink;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * config.yml。讀取時全部轉成欄位，之後只讀欄位（任何執行緒都可以讀）。
 */
public final class PluginConfig {

    private static final Pattern DB_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}");
    private static final Pattern SNOWFLAKE = Pattern.compile("\\d{17,20}");

    private final DiscordLink plugin;

    private volatile String prefix = "";
    private volatile String dbFile = "database.db";
    private volatile String token = "";
    private volatile String verifyChannelId = "";
    private volatile boolean privateMessage = true;
    private volatile boolean adminLog = true;
    private volatile String adminLogChannelId = "";
    private volatile boolean forceBinding = true;
    private volatile int maxBindings = 1;
    private volatile int codeExpiryMinutes = 5;
    private volatile int maxFailedAttempts = 5;
    private volatile int attemptWindowMinutes = 10;
    private volatile boolean apiEnabled = true;
    private volatile String apiKey = "";
    private volatile boolean allowQueryKey = true;
    private volatile boolean updateCheckOnStartup = true;
    private volatile boolean updateNotifyOnJoin = true;

    private volatile String linkedRoleId = "";
    private volatile boolean nicknameSync;

    private volatile boolean chatEnabled = true;
    private volatile String chatChannelId = "";
    private volatile boolean useWebhook = true;
    private volatile String avatarUrl = "";
    private volatile boolean escapeMarkdown = true;
    private volatile int maxLengthToMinecraft = 256;
    private volatile String playerlistKeyword = "playerlist";
    private volatile int playerlistDeleteSeconds = 30;
    private volatile String playerlistName = "luckperms";

    private volatile boolean consoleEnabled;
    private volatile String consoleChannelId = "";
    private volatile int consoleFlushSeconds = 2;
    private volatile boolean consoleMaskIp = true;
    private volatile List<Pattern> consoleFilters = List.of();
    private volatile boolean consoleAllowCommands = true;
    private volatile List<String> consoleCommandRoles = List.of();
    private volatile List<String> consoleBlacklist = List.of();

    /** 通知種類（config.yml notifications 底下的鍵）。 */
    public static final List<String> NOTIFY_TYPES = List.of(
            "server-start", "server-stop", "join", "quit", "first-join", "death", "advancement");

    /** 一種通知的設定：是否開啟、頻道 ID（沒設定時用聊天頻道）。 */
    public record Notify(boolean enabled, String channelId) {
    }

    private volatile Map<String, Notify> notify = Map.of();

    private volatile boolean presence = true;
    private volatile int presenceIntervalSeconds = 60;
    private volatile boolean channelTopic;
    private volatile int topicIntervalMinutes = 10;

    private volatile boolean showcaseEnabled = true;
    private volatile List<String> itemKeywords = List.of();
    private volatile List<String> inventoryKeywords = List.of();
    private volatile List<String> enderKeywords = List.of();
    private volatile int showcaseCooldownSeconds = 5;
    private volatile int snapshotMinutes = 5;
    private volatile boolean discordImages = true;
    private volatile int imageScale = 2;
    private volatile boolean advancedTooltip = true;
    private volatile String imageLanguage = "zh_tw";

    private volatile boolean mentionEnabled = true;
    private volatile boolean mentionRequireAt;
    private volatile String mentionSound = "";
    private volatile int mentionCooldownSeconds = 3;

    private List<String> badFilters = List.of();

    /** 上次載入的結果（給後台訊息用）。 */
    private int lastFilled;
    private boolean migrated;
    private boolean hadOldApiPort;
    private boolean badDbName;
    private String generatedKey;
    private Map<String, Object> legacyMessages = Map.of();

    public PluginConfig(DiscordLink plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        migrated = false;
        hadOldApiPort = false;
        generatedKey = null;
        legacyMessages = Map.of();
        if (file.exists()) {
            YamlConfiguration raw = new YamlConfiguration();
            try {
                raw.load(file);
                if (!raw.contains("config-version")) {
                    legacyMessages = extractLegacyMessages(raw);
                    migrated = true;
                }
                hadOldApiPort = raw.contains("api.port");
            } catch (Exception ignored) {
                // 格式錯誤由 YamlFiles 備份並提示
            }
        }

        YamlFiles.Loaded loaded = YamlFiles.load(plugin, "config.yml", "config-version", PluginConfig::migrate);
        YamlConfiguration c = loaded.yaml();
        lastFilled = loaded.filled();

        String key = c.getString("api.key", "GENERATE");
        if (key == null || key.isBlank() || key.equalsIgnoreCase("GENERATE")) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            key = HexFormat.of().formatHex(bytes);
            c.set("api.key", key);
            try {
                c.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("config.yml: " + e.getMessage());
            }
            generatedKey = key;
        }

        prefix = c.getString("prefix", "<aqua>[DiscordLink]</aqua> ");
        String db = c.getString("database.sqlite-file-name", "database.db");
        badDbName = db == null || !DB_NAME.matcher(db).matches();
        dbFile = badDbName ? "database.db" : db;
        token = trim(c.getString("discord.token", ""));
        verifyChannelId = trim(c.getString("discord.verify-channel-id", ""));
        privateMessage = c.getBoolean("discord.private-message", true);
        adminLog = c.getBoolean("discord.admin-log.enabled", true);
        adminLogChannelId = trim(c.getString("discord.admin-log.channel-id", ""));
        forceBinding = c.getBoolean("verification.force-binding", true);
        maxBindings = clamp(c.getInt("verification.max-bindings-per-discord", 1), 1, 100);
        codeExpiryMinutes = clamp(c.getInt("verification.code-expiry-minutes", 5), 1, 60);
        maxFailedAttempts = clamp(c.getInt("verification.max-failed-attempts", 5), 1, 100);
        attemptWindowMinutes = clamp(c.getInt("verification.attempt-window-minutes", 10), 1, 1440);
        apiEnabled = c.getBoolean("api.enabled", true);
        apiKey = trim(key);
        allowQueryKey = c.getBoolean("api.allow-query-key", true);
        updateCheckOnStartup = c.getBoolean("update-checker.check-on-startup", true);
        updateNotifyOnJoin = c.getBoolean("update-checker.notify-on-join", true);

        linkedRoleId = trim(c.getString("discord.linked-role-id", ""));
        nicknameSync = c.getBoolean("discord.nickname-sync", false);

        chatEnabled = c.getBoolean("chat.enabled", true);
        chatChannelId = trim(c.getString("chat.channel-id", ""));
        useWebhook = c.getBoolean("chat.use-webhook", true);
        avatarUrl = trim(c.getString("chat.avatar-url", "https://mc-heads.net/avatar/{uuid}/128"));
        escapeMarkdown = c.getBoolean("chat.escape-markdown", true);
        maxLengthToMinecraft = clamp(c.getInt("chat.max-length-to-minecraft", 256), 16, 2000);
        playerlistKeyword = trim(c.getString("chat.playerlist-keyword", "playerlist"));
        playerlistDeleteSeconds = clamp(c.getInt("chat.playerlist-delete-seconds", 30), 0, 3600);
        String pn = trim(c.getString("chat.playerlist-name", "luckperms")).toLowerCase(Locale.ROOT);
        playerlistName = pn.equals("tab") ? "tab" : "luckperms";

        consoleEnabled = c.getBoolean("console.enabled", false);
        consoleChannelId = trim(c.getString("console.channel-id", ""));
        consoleFlushSeconds = clamp(c.getInt("console.flush-seconds", 2), 1, 60);
        consoleMaskIp = c.getBoolean("console.mask-ip", true);
        List<Pattern> filters = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (String f : c.getStringList("console.filters")) {
            try {
                filters.add(Pattern.compile(f));
            } catch (PatternSyntaxException e) {
                bad.add(f);
            }
        }
        consoleFilters = List.copyOf(filters);
        badFilters = List.copyOf(bad);
        consoleAllowCommands = c.getBoolean("console.allow-commands", true);
        consoleCommandRoles = snowflakes(c.getStringList("console.command-role-ids"));
        List<String> black = new ArrayList<>();
        for (String b : c.getStringList("console.command-blacklist")) {
            String v = trim(b).toLowerCase(Locale.ROOT);
            if (v.startsWith("/")) {
                v = v.substring(1);
            }
            if (!v.isEmpty()) {
                black.add(v);
            }
        }
        consoleBlacklist = List.copyOf(black);

        Map<String, Notify> n = new HashMap<>();
        for (String type : NOTIFY_TYPES) {
            String id = trim(c.getString("notifications." + type + ".channel-id", ""));
            if (!isSnowflake(id)) {
                id = isSnowflake(chatChannelId) ? chatChannelId : "";
            }
            n.put(type, new Notify(c.getBoolean("notifications." + type + ".enabled", true), id));
        }
        notify = Map.copyOf(n);

        presence = c.getBoolean("status.presence", true);
        presenceIntervalSeconds = clamp(c.getInt("status.presence-interval-seconds", 60), 30, 3600);
        channelTopic = c.getBoolean("status.channel-topic", false);
        topicIntervalMinutes = clamp(c.getInt("status.topic-interval-minutes", 10), 10, 1440);

        showcaseEnabled = c.getBoolean("showcase.enabled", true);
        itemKeywords = keywords(c.getStringList("showcase.keywords.item"));
        inventoryKeywords = keywords(c.getStringList("showcase.keywords.inventory"));
        enderKeywords = keywords(c.getStringList("showcase.keywords.ender"));
        showcaseCooldownSeconds = clamp(c.getInt("showcase.cooldown-seconds", 5), 0, 3600);
        snapshotMinutes = clamp(c.getInt("showcase.snapshot-minutes", 5), 1, 60);
        discordImages = c.getBoolean("showcase.discord-images", true);
        imageScale = clamp(c.getInt("showcase.image-scale", 2), 1, 4);
        advancedTooltip = c.getBoolean("showcase.advanced-tooltip", true);
        imageLanguage = trim(c.getString("showcase.language", "zh_tw"));

        mentionEnabled = c.getBoolean("mention.enabled", true);
        mentionRequireAt = c.getBoolean("mention.require-at", false);
        mentionSound = trim(c.getString("mention.sound", ""));
        mentionCooldownSeconds = clamp(c.getInt("mention.cooldown-seconds", 3), 0, 3600);
    }

    /** 關鍵字：去空白、最多 32 字，最多 10 個。 */
    private static List<String> keywords(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String k : raw) {
            String v = trim(k);
            if (!v.isEmpty() && v.length() <= 32 && out.size() < 10) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> snowflakes(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            String v = trim(r);
            if (isSnowflake(v)) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    /** 舊版設定（沒有 config-version）搬到新位置。 */
    private static void migrate(YamlConfiguration old, YamlConfiguration merged) {
        move(old, merged, "discord-token", "discord.token");
        move(old, merged, "verify-channel-id", "discord.verify-channel-id");
        move(old, merged, "sqlite-file-name", "database.sqlite-file-name");
        move(old, merged, "max-bindings-per-discord", "verification.max-bindings-per-discord");
        move(old, merged, "notifications.private-message.enabled", "discord.private-message");
        move(old, merged, "embeds.private-message.enabled", "discord.private-message");
        move(old, merged, "notifications.admin-log.enabled", "discord.admin-log.enabled");
        move(old, merged, "notifications.admin-log.channel-id", "discord.admin-log.channel-id");
        move(old, merged, "embeds.admin-log.enabled", "discord.admin-log.enabled");
        move(old, merged, "embeds.admin-log.channel-id", "discord.admin-log.channel-id");
        // 1.8.0：notifications.<種類> 是 true/false，頻道共用 notifications.channel-id
        for (String type : NOTIFY_TYPES) {
            move(old, merged, "notifications." + type, "notifications." + type + ".enabled");
            move(old, merged, "notifications.channel-id", "notifications." + type + ".channel-id");
        }
    }

    private static void move(YamlConfiguration old, YamlConfiguration merged, String from, String to) {
        if (old.contains(from, true) && !old.isConfigurationSection(from)) {
            merged.set(to, old.get(from));
        }
    }

    /** 舊版 config.yml 裡的顯示文字，之後搬到 messages.yml。 */
    private static Map<String, Object> extractLegacyMessages(YamlConfiguration raw) {
        Map<String, Object> out = new HashMap<>();
        copy(raw, out, "messages.join-reminder", "join-reminder");
        // 新版會自動加上 config 的 prefix，去掉舊訊息自帶的 [DiscordLink]
        if (out.get("join-reminder") instanceof String s) {
            out.put("join-reminder", s.replaceFirst("^\\s*(<[^>]+>)?\\[DiscordLink](</[^>]+>)?\\s*", ""));
        }
        for (String card : new String[]{"verify-success", "verify-fail", "verify-limit", "private-message", "admin-log"}) {
            for (String field : new String[]{"title", "description", "color"}) {
                copy(raw, out, "embeds." + card + "." + field, "discord." + card + "." + field);
            }
        }
        copy(raw, out, "notifications.private-message.message", "discord.private-message.description");
        copy(raw, out, "notifications.admin-log.message", "discord.admin-log.description");
        copy(raw, out, "embeds.verify-success.footer", "discord.footer");
        return out;
    }

    private static void copy(YamlConfiguration raw, Map<String, Object> out, String from, String to) {
        String v = raw.getString(from);
        if (v != null && !v.isEmpty()) {
            out.put(to, v);
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static boolean isSnowflake(String s) {
        return s != null && SNOWFLAKE.matcher(s).matches();
    }

    public String prefix() { return prefix; }
    public String dbFile() { return dbFile; }
    public String token() { return token; }
    public boolean hasToken() { return !token.isEmpty() && !token.equalsIgnoreCase("YOUR_DISCORD_BOT_TOKEN"); }
    public String verifyChannelId() { return verifyChannelId; }
    public boolean privateMessage() { return privateMessage; }
    public boolean adminLog() { return adminLog && isSnowflake(adminLogChannelId); }
    public String adminLogChannelId() { return adminLogChannelId; }
    public boolean forceBinding() { return forceBinding; }
    public int maxBindings() { return maxBindings; }
    public int codeExpiryMinutes() { return codeExpiryMinutes; }
    public int maxFailedAttempts() { return maxFailedAttempts; }
    public int attemptWindowMinutes() { return attemptWindowMinutes; }
    public boolean apiEnabled() { return apiEnabled; }
    public String apiKey() { return apiKey; }
    public boolean allowQueryKey() { return allowQueryKey; }
    public boolean updateCheckOnStartup() { return updateCheckOnStartup; }
    public boolean updateNotifyOnJoin() { return updateNotifyOnJoin; }

    public String linkedRoleId() { return isSnowflake(linkedRoleId) ? linkedRoleId : ""; }
    public boolean nicknameSync() { return nicknameSync; }

    public boolean chatEnabled() { return chatEnabled && isSnowflake(chatChannelId); }
    public String chatChannelId() { return chatChannelId; }
    public boolean useWebhook() { return useWebhook; }
    public String avatarUrl() { return avatarUrl; }
    public boolean escapeMarkdown() { return escapeMarkdown; }
    public int maxLengthToMinecraft() { return maxLengthToMinecraft; }
    public String playerlistKeyword() { return playerlistKeyword; }
    public int playerlistDeleteSeconds() { return playerlistDeleteSeconds; }
    public String playerlistName() { return playerlistName; }

    public boolean consoleEnabled() { return consoleEnabled && isSnowflake(consoleChannelId); }
    public String consoleChannelId() { return consoleChannelId; }
    public int consoleFlushSeconds() { return consoleFlushSeconds; }
    public boolean consoleMaskIp() { return consoleMaskIp; }
    public List<Pattern> consoleFilters() { return consoleFilters; }
    public List<String> badFilters() { return badFilters; }
    public boolean consoleAllowCommands() { return consoleAllowCommands; }
    public List<String> consoleCommandRoles() { return consoleCommandRoles; }
    public List<String> consoleBlacklist() { return consoleBlacklist; }

    /** 通知設定；頻道已套用「留空 = 聊天頻道」，都沒有時為空字串。 */
    public Notify notify(String type) {
        Notify v = notify.get(type);
        return v == null ? new Notify(false, "") : v;
    }

    public boolean presence() { return presence; }
    public int presenceIntervalSeconds() { return presenceIntervalSeconds; }
    public boolean channelTopic() { return channelTopic && isSnowflake(chatChannelId); }
    public int topicIntervalMinutes() { return topicIntervalMinutes; }

    public boolean showcaseEnabled() { return showcaseEnabled; }
    public List<String> itemKeywords() { return itemKeywords; }
    public List<String> inventoryKeywords() { return inventoryKeywords; }
    public List<String> enderKeywords() { return enderKeywords; }
    public int showcaseCooldownSeconds() { return showcaseCooldownSeconds; }
    public int snapshotMinutes() { return snapshotMinutes; }
    public boolean discordImages() { return discordImages; }
    public int imageScale() { return imageScale; }
    public boolean advancedTooltip() { return advancedTooltip; }
    public String imageLanguage() { return imageLanguage; }

    public boolean mentionEnabled() { return mentionEnabled; }
    public boolean mentionRequireAt() { return mentionRequireAt; }
    public String mentionSound() { return mentionSound; }
    public int mentionCooldownSeconds() { return mentionCooldownSeconds; }

    public int lastFilled() { return lastFilled; }
    public boolean migrated() { return migrated; }
    public boolean hadOldApiPort() { return hadOldApiPort; }
    public boolean badDbName() { return badDbName; }
    public String generatedKey() { return generatedKey; }
    public Map<String, Object> legacyMessages() { return legacyMessages; }
}
