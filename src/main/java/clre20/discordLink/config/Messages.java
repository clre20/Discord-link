package clre20.discordLink.config;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * messages.yml：所有顯示文字。變數寫法 {name}，呼叫時傳成對的參數：get("key", "name", 值, ...)。
 * yaml 物件整份替換（volatile），Discord 執行緒讀取也安全。
 */
public final class Messages {

    private final DiscordLink plugin;
    private volatile YamlConfiguration yaml = new YamlConfiguration();
    private volatile YamlConfiguration defaults = new YamlConfiguration();
    private int lastFilled;

    public Messages(DiscordLink plugin) {
        this.plugin = plugin;
    }

    /** 1.8.x 的預設文字：使用者沒改過的，升級時換成新版的預設（改過的保留）。 */
    private static final Map<String, String> OLD_DEFAULTS = Map.ofEntries(
            Map.entry("chat.discord-to-minecraft", "<#5865F2>[Discord]</#5865F2> <white>{name}</white><gray>：</gray>{message}"),
            Map.entry("chat.attachment", " <aqua><u>[附件：{file}]</u></aqua>"),
            Map.entry("discord.notify.server-start.text", "✅ 伺服器已啟動"),
            Map.entry("discord.notify.server-stop.text", "🛑 伺服器已關閉"),
            Map.entry("discord.notify.join.text", "{player} 加入了伺服器"),
            Map.entry("discord.notify.join.color", "#2ECC71"),
            Map.entry("discord.notify.quit.text", "{player} 離開了伺服器"),
            Map.entry("discord.notify.quit.color", "#E74C3C"),
            Map.entry("discord.notify.first-join.text", "🎉 {player} 第一次加入伺服器！"),
            Map.entry("discord.notify.first-join.color", "#F1C40F"),
            Map.entry("discord.notify.advancement-task.text", "🏅 {player} 獲得了成就 [{advancement}]"),
            Map.entry("discord.notify.advancement-task.color", "#3498DB"),
            Map.entry("discord.notify.advancement-goal.text", "🎯 {player} 達成了目標 [{advancement}]"),
            Map.entry("discord.notify.advancement-goal.color", "#3498DB"),
            Map.entry("discord.notify.advancement-challenge.text", "🏆 {player} 完成了挑戰 [{advancement}]"),
            Map.entry("discord.notify.advancement-challenge.color", "#9B59B6"),
            Map.entry("http.missing-action", "缺少 action（query_dc、query_mc、all）"),
            Map.entry("http.unknown-action", "未知的 action（query_dc、query_mc、all）")
    );

    private void upgrade(YamlConfiguration user, YamlConfiguration merged) {
        YamlConfiguration fresh = YamlFiles.defaults(plugin, "messages.yml");
        for (Map.Entry<String, String> e : OLD_DEFAULTS.entrySet()) {
            if (e.getValue().equals(user.getString(e.getKey())) && fresh.contains(e.getKey(), true)) {
                merged.set(e.getKey(), fresh.get(e.getKey()));
            }
        }
    }

    /** @param legacy 舊版 config.yml 的文字（只在第一次升級時有內容） */
    public void load(Map<String, Object> legacy) {
        YamlFiles.Loaded loaded = YamlFiles.load(plugin, "messages.yml", "messages-version", this::upgrade);
        YamlConfiguration y = loaded.yaml();
        if (!legacy.isEmpty()) {
            legacy.forEach(y::set);
            try {
                y.save(new File(plugin.getDataFolder(), "messages.yml"));
            } catch (IOException e) {
                plugin.getLogger().warning("messages.yml: " + e.getMessage());
            }
        }
        yaml = y;
        defaults = loaded.defaults();
        lastFilled = loaded.filled();
    }

    public int lastFilled() {
        return lastFilled;
    }

    /** 原始文字（找不到時用內建預設值，再找不到回傳 key）。 */
    public String raw(String key) {
        String v = yaml.getString(key);
        if (v == null) {
            v = defaults.getString(key, key);
        }
        return v;
    }

    public Component component(String key, Object... vars) {
        return Text.component(raw(key), vars);
    }

    public Component componentWith(String key, Map<String, Component> parts, Object... vars) {
        return Text.componentWith(raw(key), parts, vars);
    }

    /** 帶前綴的聊天訊息。 */
    public void send(CommandSender to, String key, Object... vars) {
        to.sendMessage(Text.component(plugin.config().prefix() + raw(key), vars));
    }

    /** 不帶前綴（清單內容等）。 */
    public void line(CommandSender to, String key, Object... vars) {
        to.sendMessage(component(key, vars));
    }

    /** 後台文字（去掉顏色）。 */
    public String console(String key, Object... vars) {
        return Text.plain(raw("console." + key), vars);
    }

    /** Discord 文字：只替換變數。 */
    public String discord(String key, Object... vars) {
        return Text.plainReplace(raw("discord." + key), vars);
    }

    public Color discordColor(String card, Color fallback) {
        String hex = raw("discord." + card + ".color");
        try {
            return Color.decode(hex.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
