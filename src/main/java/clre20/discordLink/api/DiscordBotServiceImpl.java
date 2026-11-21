package clre20.discordLink.api;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.data.LinkStore;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DiscordBotService 的實作。
 */
public final class DiscordBotServiceImpl implements DiscordBotService {

    private static class PluginUsage {
        final String name;
        final AtomicInteger dm = new AtomicInteger();
        final AtomicInteger channel = new AtomicInteger();
        final AtomicInteger query = new AtomicInteger();
        volatile long lastActive = System.currentTimeMillis();

        PluginUsage(String name) {
            this.name = name;
        }
    }

    private final DiscordLink plugin;
    private final Map<String, PluginUsage> usageMap = new ConcurrentHashMap<>();

    public DiscordBotServiceImpl(DiscordLink plugin) {
        this.plugin = plugin;
    }

    private PluginUsage getUsage(Plugin caller) {
        String name = caller != null ? caller.getName() : "Unknown";
        PluginUsage u = usageMap.computeIfAbsent(name, PluginUsage::new);
        u.lastActive = System.currentTimeMillis();
        return u;
    }

    @Override
    public boolean isBotReady() {
        return plugin.bot() != null && plugin.bot().ready();
    }

    @Override
    public JDA getJda(Plugin caller) {
        getUsage(caller);
        return plugin.bot() != null ? plugin.bot().jda() : null;
    }

    @Override
    public Optional<String> getDiscordId(Plugin caller, UUID playerUuid) {
        PluginUsage u = getUsage(caller);
        u.query.incrementAndGet();
        LinkStore store = plugin.store();
        if (store == null) {
            return Optional.empty();
        }
        LinkStore.Binding b = store.binding(playerUuid);
        return b != null ? Optional.of(b.discordId()) : Optional.empty();
    }

    @Override
    public boolean isLinked(Plugin caller, UUID playerUuid) {
        return getDiscordId(caller, playerUuid).isPresent();
    }

    @Override
    public Set<UUID> getLinkedPlayers(Plugin caller) {
        PluginUsage u = getUsage(caller);
        u.query.incrementAndGet();
        LinkStore store = plugin.store();
        if (store == null) {
            return Collections.emptySet();
        }
        Set<UUID> set = new HashSet<>();
        for (LinkStore.Binding b : store.allBindings()) {
            set.add(b.uuid());
        }
        return set;
    }

    @Override
    public CompletableFuture<Boolean> sendDirectMessage(Plugin caller, UUID playerUuid, String text, MessageEmbed embed, List<FileUpload> files) {
        PluginUsage u = getUsage(caller);
        u.dm.incrementAndGet();
        String fileNames = (files != null && !files.isEmpty()) ? " (附件數: " + files.size() + ")" : "";
        plugin.getLogger().info("[Audit] 插件 '" + u.name + "' 透過機器人向玩家 " + playerUuid + " 發送私訊" + fileNames);

        Optional<String> dcIdOpt = getDiscordId(null, playerUuid);
        if (dcIdOpt.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        JDA jda = plugin.bot() != null ? plugin.bot().jda() : null;
        if (jda == null || !isBotReady()) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            jda.retrieveUserById(dcIdOpt.get()).queue(user -> {
                user.openPrivateChannel().queue(ch -> {
                    MessageCreateBuilder mb = new MessageCreateBuilder();
                    if (text != null && !text.isBlank()) mb.setContent(text);
                    if (embed != null) mb.setEmbeds(embed);
                    if (files != null && !files.isEmpty()) mb.setFiles(files);
                    MessageCreateData msg = mb.build();
                    ch.sendMessage(msg).queue(
                            success -> future.complete(true),
                            error -> {
                                plugin.getLogger().warning("[Audit] 插件 '" + u.name + "' 發送私訊失敗: " + error.getMessage());
                                future.complete(false);
                            }
                    );
                }, err -> future.complete(false));
            }, err -> future.complete(false));
        } catch (Exception e) {
            plugin.getLogger().warning("[Audit] 插件 '" + u.name + "' 調用發送私訊拋出例外: " + e.getMessage());
            future.complete(false);
        }
        return future;
    }

    @Override
    public CompletableFuture<Boolean> sendChannelMessage(Plugin caller, String channelId, String text, MessageEmbed embed, List<FileUpload> files) {
        PluginUsage u = getUsage(caller);
        u.channel.incrementAndGet();
        String fileNames = (files != null && !files.isEmpty()) ? " (附件數: " + files.size() + ")" : "";
        plugin.getLogger().info("[Audit] 插件 '" + u.name + "' 透過機器人向頻道 " + channelId + " 發送訊息" + fileNames);

        JDA jda = plugin.bot() != null ? plugin.bot().jda() : null;
        if (jda == null || !isBotReady()) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            GuildMessageChannel ch = jda.getChannelById(GuildMessageChannel.class, channelId);
            if (ch == null) {
                future.complete(false);
                return future;
            }
            MessageCreateBuilder mb = new MessageCreateBuilder();
            if (text != null && !text.isBlank()) mb.setContent(text);
            if (embed != null) mb.setEmbeds(embed);
            if (files != null && !files.isEmpty()) mb.setFiles(files);
            ch.sendMessage(mb.build()).queue(
                    success -> future.complete(true),
                    err -> future.complete(false)
            );
        } catch (Exception e) {
            future.complete(false);
        }
        return future;
    }

    @Override
    public List<PluginDiscordInfo> getConnectedPlugins() {
        List<PluginDiscordInfo> list = new ArrayList<>();
        for (PluginUsage u : usageMap.values()) {
            list.add(new PluginDiscordInfo(u.name, u.dm.get(), u.channel.get(), u.query.get(), u.lastActive));
        }
        return list;
    }
}
