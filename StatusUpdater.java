package clre20.discordLink.bridge;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.showcase.ShowcaseListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 機器人狀態（線上人數）與聊天頻道主題（線上人數、TPS）。
 * 主執行緒的定時工作只數人數（很便宜），有變動才交給 JDA 送出；頻道主題至少 10 分鐘更新一次（Discord 限制）。
 */
public final class StatusUpdater {

    private final DiscordLink plugin;
    private BukkitTask presenceTask;
    private BukkitTask topicTask;
    private String lastPresence = "";
    private String lastTopic = "";
    private volatile boolean conflict;

    public StatusUpdater(DiscordLink plugin) {
        this.plugin = plugin;
    }

    public void setConflict(boolean conflict) {
        this.conflict = conflict;
    }

    /** 主執行緒呼叫（啟動與重新載入）。 */
    public void configure() {
        cancel();
        lastPresence = "";
        lastTopic = "";
        if (conflict) {
            return;
        }
        if (plugin.config().presence()) {
            long t = plugin.config().presenceIntervalSeconds() * 20L;
            presenceTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updatePresence, 100L, t);
        }
        if (plugin.config().channelTopic()) {
            long t = plugin.config().topicIntervalMinutes() * 60L * 20L;
            topicTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateTopic, 200L, t);
        }
    }

    public void cancel() {
        if (presenceTask != null) {
            presenceTask.cancel();
            presenceTask = null;
        }
        if (topicTask != null) {
            topicTask.cancel();
            topicTask = null;
        }
    }

    private static int online() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!ShowcaseListener.isVanished(p)) {
                n++;
            }
        }
        return n;
    }

    private void updatePresence() {
        JDA jda = plugin.discord().jda();
        if (jda == null) {
            return;
        }
        String text = plugin.messages().discord("status.presence", "online", online(), "max", Bukkit.getMaxPlayers());
        if (text.isBlank() || text.equals(lastPresence)) {
            return;
        }
        lastPresence = text;
        jda.getPresence().setActivity(Activity.playing(text.length() > 128 ? text.substring(0, 128) : text));
    }

    private void updateTopic() {
        JDA jda = plugin.discord().jda();
        if (jda == null) {
            return;
        }
        String tps = String.format(Locale.ROOT, "%.1f", Math.min(20.0, Bukkit.getTPS()[0]));
        String text = plugin.messages().discord("status.topic", "online", online(), "max", Bukkit.getMaxPlayers(), "tps", tps);
        if (text.equals(lastTopic)) {
            return;
        }
        TextChannel ch = jda.getTextChannelById(plugin.config().chatChannelId());
        if (ch == null) {
            return;
        }
        lastTopic = text;
        ch.getManager().setTopic(text.length() > 1024 ? text.substring(0, 1024) : text).queue(ok -> { }, err -> { });
    }

    /** 關服時把主題改成「已關閉」（主執行緒，最多等 2 秒）。 */
    public void sendOffline() {
        cancel();
        if (conflict || !plugin.config().channelTopic()) {
            return;
        }
        JDA jda = plugin.discord() == null ? null : plugin.discord().jda();
        TextChannel ch = jda == null ? null : jda.getTextChannelById(plugin.config().chatChannelId());
        if (ch == null) {
            return;
        }
        try {
            ch.getManager().setTopic(plugin.messages().discord("status.topic-offline")).submit().get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }
}
