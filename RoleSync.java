package clre20.discordLink.bridge;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.data.LinkStore;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 綁定身分組與暱稱同步。
 * 綁定時給身分組、改暱稱；解除最後一個綁定時拿掉身分組。
 * 玩家進服時（每人 30 分鐘最多一次）再檢查一次，處理改名。全部用 JDA 的非同步佇列，失敗只記一次警告。
 */
public final class RoleSync implements Listener {

    private static final long JOIN_INTERVAL = 30 * 60 * 1000L;

    private final DiscordLink plugin;
    private final Map<UUID, Long> lastSync = new ConcurrentHashMap<>();
    private volatile boolean warned;

    public RoleSync(DiscordLink plugin) {
        this.plugin = plugin;
    }

    private boolean active() {
        return !plugin.config().linkedRoleId().isEmpty() || plugin.config().nicknameSync();
    }

    /** 綁定的 Discord 伺服器：驗證頻道（沒有時用聊天頻道）所在的伺服器。 */
    private Guild guild(JDA jda) {
        TextChannel ch = jda.getTextChannelById(safe(plugin.config().verifyChannelId()));
        if (ch == null) {
            ch = jda.getTextChannelById(safe(plugin.config().chatChannelId()));
        }
        return ch == null ? null : ch.getGuild();
    }

    private static String safe(String id) {
        return id != null && id.matches("\\d{1,20}") ? id : "0";
    }

    public void onLinked(String discordId, String playerName) {
        JDA jda = plugin.discord() == null ? null : plugin.discord().jda();
        if (jda == null || !active() || discordId == null) {
            return;
        }
        Guild guild = guild(jda);
        if (guild == null) {
            return;
        }
        String roleId = plugin.config().linkedRoleId();
        if (!roleId.isEmpty()) {
            Role role = guild.getRoleById(roleId);
            if (role != null) {
                guild.addRoleToMember(UserSnowflake.fromId(discordId), role).queue(ok -> { }, this::fail);
            }
        }
        if (plugin.config().nicknameSync() && playerName != null && !playerName.isEmpty()) {
            guild.retrieveMemberById(discordId).queue(m -> rename(guild, m, playerName), err -> { });
        }
    }

    private void rename(Guild guild, Member m, String name) {
        if (name.equals(m.getNickname()) || m.isOwner() || !guild.getSelfMember().canInteract(m)) {
            return;
        }
        m.modifyNickname(name.length() > 32 ? name.substring(0, 32) : name).queue(ok -> { }, this::fail);
    }

    /** 解除綁定後呼叫：這個 Discord 帳號沒有其他綁定時拿掉身分組。 */
    public void onUnlinked(String discordId) {
        JDA jda = plugin.discord() == null ? null : plugin.discord().jda();
        String roleId = plugin.config().linkedRoleId();
        LinkStore store = plugin.store();
        if (jda == null || roleId.isEmpty() || discordId == null || store == null || !store.boundTo(discordId).isEmpty()) {
            return;
        }
        Guild guild = guild(jda);
        Role role = guild == null ? null : guild.getRoleById(roleId);
        if (role != null) {
            guild.removeRoleFromMember(UserSnowflake.fromId(discordId), role).queue(ok -> { }, this::fail);
        }
    }

    private void fail(Throwable t) {
        if (!warned) {
            warned = true;
            plugin.getLogger().warning(plugin.messages().console("role-failed", "reason", t.getMessage()));
        }
    }

    public void resetWarning() {
        warned = false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!active() || plugin.store() == null) {
            return;
        }
        Player p = event.getPlayer();
        LinkStore.Binding b = plugin.store().binding(p.getUniqueId());
        if (b == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSync.get(p.getUniqueId());
        if (last != null && now - last < JOIN_INTERVAL) {
            return;
        }
        lastSync.put(p.getUniqueId(), now);
        // 同一個 Discord 有多個帳號時，暱稱用第一個（依名稱排序）
        List<LinkStore.Binding> all = plugin.store().boundTo(b.discordId());
        String name = all.isEmpty() ? p.getName() : all.get(0).name();
        onLinked(b.discordId(), name == null || name.isEmpty() ? p.getName() : name);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // 只清掉已過期的紀錄（留著未過期的，避免重複進出時一直呼叫 Discord）
        long now = System.currentTimeMillis();
        lastSync.values().removeIf(t -> now - t >= JOIN_INTERVAL);
    }

    public void clear() {
        lastSync.clear();
    }
}
