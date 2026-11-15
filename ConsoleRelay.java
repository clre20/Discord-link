package clre20.discordLink.bridge;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.config.PluginConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 控制台頻道：後台訊息送到 Discord、Discord 輸入後台指令。
 * <ul>
 *   <li>後台訊息先放進有上限的佇列（滿了丟最舊的），每隔幾秒合併成少數幾則訊息送出。</li>
 *   <li>送出前去掉顏色碼、遮住 IP 與機器人 Token，並略過 Discord 函式庫自己的訊息（避免無限循環）。</li>
 *   <li>指令只接受指定身分組（或管理員），並檢查黑名單（包含 minecraft:stop、execute … run stop）。</li>
 * </ul>
 */
public final class ConsoleRelay extends ListenerAdapter {

    private static final int QUEUE = 2000;
    private static final int MAX_MESSAGES_PER_FLUSH = 3;
    private static final int MAX_COMMAND = 256;
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*[A-Za-z]");
    private static final Pattern SECTION = Pattern.compile("§[0-9A-FK-ORXa-fk-orx]");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DiscordLink plugin;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE);
    private ScheduledExecutorService flusher;
    private ScheduledFuture<?> task;
    private Appender appender;
    private volatile boolean conflict;

    public ConsoleRelay(DiscordLink plugin) {
        this.plugin = plugin;
    }

    public void setConflict(boolean conflict) {
        this.conflict = conflict;
    }

    private boolean active() {
        return !conflict && plugin.config().consoleEnabled();
    }

    /** 依設定啟動或停止（主執行緒呼叫；重新載入時也呼叫）。 */
    public synchronized void configure() {
        stop();
        if (!active()) {
            return;
        }
        appender = new Appender();
        appender.start();
        ((Logger) LogManager.getRootLogger()).addAppender(appender);
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DiscordLink-Console");
            t.setDaemon(true);
            return t;
        });
        int s = plugin.config().consoleFlushSeconds();
        task = flusher.scheduleWithFixedDelay(this::flush, s, s, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (appender != null) {
            ((Logger) LogManager.getRootLogger()).removeAppender(appender);
            appender.stop();
            appender = null;
        }
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (flusher != null) {
            flusher.shutdownNow();
            flusher = null;
        }
        queue.clear();
    }

    /** 後台訊息 → 佇列。 */
    private final class Appender extends AbstractAppender {

        Appender() {
            super("DiscordLink-Console", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
            if (logger.startsWith("net.dv8tion")) {
                return;
            }
            String text = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            if (event.getThrown() != null) {
                StringBuilder sb = new StringBuilder(text).append('\n').append(event.getThrown());
                StackTraceElement[] st = event.getThrown().getStackTrace();
                for (int i = 0; i < Math.min(5, st.length); i++) {
                    sb.append("\n    at ").append(st[i]);
                }
                text = sb.toString();
            }
            String prefix = "[" + TIME.format(Instant.ofEpochMilli(event.getTimeMillis())) + " "
                    + event.getLevel().name() + "]";
            if (!logger.isEmpty() && logger.indexOf('.') < 0 && !logger.equals("Minecraft")) {
                prefix += " [" + logger + "]";
            }
            offer(prefix + " " + text);
        }
    }

    private void offer(String line) {
        String clean = SECTION.matcher(ANSI.matcher(line).replaceAll("")).replaceAll("");
        PluginConfig config = plugin.config();
        for (Pattern p : config.consoleFilters()) {
            if (p.matcher(clean).find()) {
                return;
            }
        }
        if (config.consoleMaskIp()) {
            clean = IPV4.matcher(clean).replaceAll("*.*.*.*");
        }
        String token = config.token();
        if (token.length() > 8) {
            clean = clean.replace(token, "***");
        }
        String key = config.apiKey();
        if (key.length() >= 16) {
            clean = clean.replace(key, "***");
        }
        clean = clean.replace("```", "`​``");
        if (clean.length() > 1800) {
            clean = clean.substring(0, 1800) + "…";
        }
        while (!queue.offer(clean)) {
            queue.poll(); // 滿了：丟掉最舊的
        }
    }

    /** 在控制台執行緒執行。 */
    private void flush() {
        try {
            JDA jda = plugin.discord().jda();
            if (jda == null || queue.isEmpty()) {
                return;
            }
            TextChannel ch = jda.getTextChannelById(plugin.config().consoleChannelId());
            if (ch == null) {
                queue.clear();
                return;
            }
            for (int m = 0; m < MAX_MESSAGES_PER_FLUSH && !queue.isEmpty(); m++) {
                StringBuilder sb = new StringBuilder("```\n");
                String line;
                while ((line = queue.peek()) != null && sb.length() + line.length() + 5 < Message.MAX_CONTENT_LENGTH) {
                    sb.append(queue.poll()).append('\n');
                }
                sb.append("```");
                ch.sendMessage(sb.toString()).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                        .queue(ok -> { }, err -> { });
            }
        } catch (RuntimeException ignored) {
            // 不讓例外終止排程
        }
    }

    // ---------- Discord → 後台指令 ----------

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!active() || !event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }
        PluginConfig config = plugin.config();
        if (!event.getChannel().getId().equals(config.consoleChannelId()) || !config.consoleAllowCommands()) {
            return;
        }
        Message m = event.getMessage();
        String raw = m.getContentRaw().trim();
        if (raw.isEmpty() || raw.contains("\n") || raw.contains("\r")) {
            return;
        }
        Member member = event.getMember();
        if (member == null || !allowed(member, config.consoleCommandRoles())) {
            m.reply(plugin.messages().discord("console.no-permission")).queue(ok -> { }, err -> { });
            return;
        }
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        if (command.length() > MAX_COMMAND || command.isBlank()) {
            return;
        }
        String user = event.getAuthor().getName();
        String id = event.getAuthor().getId();
        if (blocked(command, config.consoleBlacklist())) {
            plugin.getLogger().warning(plugin.messages().console("console-command-blocked", "user", user, "id", id, "command", command));
            m.reply(plugin.messages().discord("console.blocked")).queue(ok -> { }, err -> { });
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info(plugin.messages().console("console-command", "user", user, "id", id, "command", command));
            boolean ok;
            try {
                ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (RuntimeException e) {
                ok = false;
            }
            m.addReaction(Emoji.fromUnicode(ok ? "✅" : "❌")).queue(a -> { }, err -> { });
        });
    }

    private static boolean allowed(Member member, List<String> roles) {
        if (member.hasPermission(Permission.ADMINISTRATOR)) {
            return true;
        }
        for (Role r : member.getRoles()) {
            if (roles.contains(r.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 黑名單比對：去掉命名空間（minecraft:stop），並展開 execute … run 之後的指令。
     */
    static boolean blocked(String command, List<String> blacklist) {
        String cmd = command.trim().toLowerCase(Locale.ROOT);
        for (int depth = 0; depth < 8; depth++) {
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            String[] parts = cmd.split("\\s+", 2);
            String label = parts[0];
            int colon = label.indexOf(':');
            if (colon >= 0) {
                label = label.substring(colon + 1);
            }
            String normalized = parts.length > 1 ? label + " " + parts[1] : label;
            for (String b : blacklist) {
                if (normalized.equals(b) || normalized.startsWith(b + " ")) {
                    return true;
                }
            }
            if (!label.equals("execute") || parts.length < 2) {
                return false;
            }
            int run = (" " + parts[1] + " ").indexOf(" run ");
            if (run < 0) {
                return false;
            }
            cmd = (" " + parts[1] + " ").substring(run + 5).trim();
        }
        return true;
    }
}
