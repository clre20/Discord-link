package clre20.discordLink;

import clre20.discordLink.api.ApiBridge;
import clre20.discordLink.bridge.ChatBridge;
import clre20.discordLink.bridge.ConsoleRelay;
import clre20.discordLink.bridge.Notifier;
import clre20.discordLink.bridge.RoleSync;
import clre20.discordLink.bridge.StatusUpdater;
import clre20.discordLink.render.RenderService;
import clre20.discordLink.showcase.ShowcaseListener;
import clre20.discordLink.showcase.SnapshotMenu;
import clre20.discordLink.command.DiscordLinkCommand;
import clre20.discordLink.config.Messages;
import clre20.discordLink.config.PluginConfig;
import clre20.discordLink.data.LinkStore;
import clre20.discordLink.data.PlayerLookup;
import clre20.discordLink.discord.DiscordBot;
import clre20.discordLink.discord.DiscordListener;
import clre20.discordLink.listener.LockedListener;
import clre20.discordLink.listener.LoginListener;
import clre20.discordLink.update.UpdateChecker;
import clre20.discordLink.update.UpdateNotifier;
import clre20.discordLink.verify.CodeManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.UUID;

public final class DiscordLink extends JavaPlugin {

    private PluginConfig config;
    private Messages messages;
    private LinkStore store;
    private PlayerLookup lookup;
    private CodeManager codes;
    private DiscordBot discord;
    private ApiBridge api;
    private UpdateChecker updates;
    private UpdateNotifier updateNotifier;
    private RenderService render;
    private clre20.discordLink.data.PlayerIndex players;
    private clre20.discordLink.api.DiscordBotServiceImpl botService;
    private ChatBridge chat;
    private ConsoleRelay console;
    private Notifier notifier;
    private StatusUpdater status;
    private RoleSync roles;
    private ShowcaseListener showcase;
    private boolean discordSrv;
    private boolean interactiveChat;

    @Override
    public void onEnable() {
        config = new PluginConfig(this);
        messages = new Messages(this);
        loadFiles();

        store = new LinkStore(new File(getDataFolder(), config.dbFile()),
                reason -> getLogger().warning(messages.console("db-error", "reason", reason)));
        try {
            store.load();
        } catch (Exception e) {
            // 資料庫打不開：讀不到綁定資料就無法判斷誰能進服。強制綁定模式下只讓 OP 進服（寧可擋住，不放行）
            getLogger().severe(messages.console("db-error", "reason", e.getMessage()));
            getLogger().severe(messages.console("db-locked"));
            store.close();
            store = null;
            if (config.forceBinding()) {
                getServer().getPluginManager().registerEvents(new LockedListener(this), this);
            }
            return;
        }
        fillMissingNames();
        getLogger().info(messages.console("db-loaded", "bindings", store.bindingCount(), "bypass", store.bypassCount()));

        lookup = new PlayerLookup(this, store);
        codes = new CodeManager(config.codeExpiryMinutes(), config.attemptWindowMinutes());
        updates = new UpdateChecker(getPluginMeta().getVersion());
        updateNotifier = new UpdateNotifier(this);
        api = new ApiBridge(this);
        // 與舊插件同時存在時停用重疊的功能
        discordSrv = getServer().getPluginManager().isPluginEnabled("DiscordSRV");
        interactiveChat = getServer().getPluginManager().isPluginEnabled("InteractiveChat");
        if (discordSrv) {
            getLogger().warning(messages.console("conflict-discordsrv"));
        }
        if (interactiveChat) {
            getLogger().warning(messages.console("conflict-interactivechat"));
        }

        render = new RenderService(this);
        roles = new RoleSync(this);
        chat = new ChatBridge(this);
        chat.setConflict(discordSrv);
        console = new ConsoleRelay(this);
        console.setConflict(discordSrv);
        notifier = new Notifier(this);
        notifier.setConflict(discordSrv);
        status = new StatusUpdater(this);
        status.setConflict(discordSrv);
        SnapshotMenu menu = new SnapshotMenu(this);
        showcase = new ShowcaseListener(this, menu);
        showcase.setDisabled(interactiveChat);
        chat.setShowcase(showcase);

        discord = new DiscordBot(this, jda -> {
            chat.onReady(jda);
            notifier.onReady(jda);
        }, new DiscordListener(this), chat, console);

        botService = new clre20.discordLink.api.DiscordBotServiceImpl(this);
        getServer().getServicesManager().register(clre20.discordLink.api.DiscordBotService.class, botService, this, org.bukkit.plugin.ServicePriority.Normal);

        players = new clre20.discordLink.data.PlayerIndex();
        var pm = getServer().getPluginManager();
        pm.registerEvents(players, this);
        // 進過伺服器的玩家名單：伺服器啟動完成後在主執行緒讀一次
        getServer().getScheduler().runTask(this, players::load);
        pm.registerEvents(menu, this);
        pm.registerEvents(showcase, this);
        pm.registerEvents(chat, this);
        pm.registerEvents(notifier, this);
        pm.registerEvents(roles, this);
        getServer().getPluginManager().registerEvents(new LoginListener(this), this);
        getServer().getPluginManager().registerEvents(updateNotifier, this);
        getServer().getPluginManager().registerEvents(api, this);

        PluginCommand command = getCommand("discordlink");
        if (command != null) {
            DiscordLinkCommand executor = new DiscordLinkCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        printMode();
        api.refresh(true);
        render.configure();
        console.configure();
        status.configure();
        startDiscord();
        if (getServer().getPluginManager().isPluginEnabled("HttpHub") && clre20.httpHub.api.HttpHubAPI.isAvailable()) {
            clre20.httpHub.api.HttpHubAPI.registerUpdateCheck(this, "clre20/Discord-link-Minecraft-Plugin", getPluginMeta().getVersion(), "discordlink.version", null);
        } else {
            updateNotifier.checkOnStartup();
        }
    }

    @Override
    public void onDisable() {
        if (api != null) {
            api.stop();
        }
        if (status != null) {
            status.sendOffline();
        }
        if (notifier != null) {
            notifier.sendStop();
        }
        if (console != null) {
            console.stop();
        }
        if (discord != null) {
            discord.shutdown();
        }
        if (render != null) {
            render.shutdown();
        }
        if (showcase != null) {
            showcase.clear();
        }
        if (roles != null) {
            roles.clear();
        }
        if (players != null) {
            players.clear();
        }
        if (lookup != null) {
            lookup.shutdown();
        }
        if (updates != null) {
            updates.shutdown();
        }
        if (codes != null) {
            codes.clear();
        }
        if (store != null) {
            store.close();
        }
    }

    /** 讀取（並補齊）config.yml 與 messages.yml。 */
    private void loadFiles() {
        config.load();
        messages.load(config.legacyMessages());
        if (config.migrated()) {
            getLogger().info(messages.console("config-migrated"));
        }
        if (config.hadOldApiPort()) {
            getLogger().info(messages.console("old-api-port"));
        }
        if (config.lastFilled() > 0) {
            getLogger().info(messages.console("config-filled", "count", config.lastFilled()));
        }
        if (messages.lastFilled() > 0) {
            getLogger().info(messages.console("messages-filled", "count", messages.lastFilled()));
        }
        for (String bad : config.badFilters()) {
            getLogger().warning("console.filters: invalid regex " + bad);
        }
        if (config.badDbName()) {
            getLogger().warning(messages.console("bad-db-name"));
        }
        String key = config.generatedKey();
        if (key != null) {
            getLogger().info(messages.console("api-key-generated", "masked", key.substring(0, 4) + "…" + key.substring(key.length() - 4)));
        } else if (config.apiKey().length() < 16) {
            getLogger().warning(messages.console("api-key-short"));
        }
    }

    /** 舊資料沒有名稱欄位：從伺服器的玩家快取補上（只讀本機快取，不連網）。 */
    private void fillMissingNames() {
        for (UUID uuid : store.unnamed()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (op.getName() != null) {
                store.updateName(uuid, op.getName());
            }
        }
    }

    private void startDiscord() {
        if (!config.hasToken()) {
            getLogger().warning(messages.console("token-missing"));
            return;
        }
        discord.connect(config.token());
    }

    private void printMode() {
        getLogger().info(messages.console(config.forceBinding() ? "mode-force" : "mode-free"));
    }

    /** /dclink reload。 */
    public void reload(CommandSender sender) {
        String oldToken = discord.token();
        loadFiles();
        codes.configure(config.codeExpiryMinutes(), config.attemptWindowMinutes());
        api.refresh(true);
        render.configure();
        console.configure();
        status.configure();
        roles.resetWarning();
        printMode();
        messages.send(sender, "reload.done");
        if (!config.hasToken()) {
            if (!oldToken.isEmpty()) {
                discord.disconnectAsync();
            }
            getLogger().warning(messages.console("token-missing"));
        } else if (!config.token().equals(oldToken) || (!discord.online() && !discord.connecting())) {
            messages.send(sender, "reload.discord-restart");
            discord.connect(config.token());
        } else if (discord.jda() != null) {
            // 頻道可能變了：重新檢查並準備 Webhook
            chat.onReady(discord.jda());
        }
    }

    /** 這則聊天是否需要為 Discord 準備圖片資料。 */
    public boolean wantsDiscordImages() {
        return !discordSrv && config.chatEnabled() && config.discordImages() && render.ready() && discord.online();
    }

    public RenderService render() {
        return render;
    }

    public clre20.discordLink.data.PlayerIndex players() {
        return players;
    }

    public RoleSync roles() {
        return roles;
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public LinkStore store() {
        return store;
    }

    public PlayerLookup lookup() {
        return lookup;
    }

    public CodeManager codes() {
        return codes;
    }

    public DiscordBot discord() {
        return discord;
    }

    public DiscordBot bot() {
        return discord;
    }

    public clre20.discordLink.api.DiscordBotServiceImpl botService() {
        return botService;
    }

    public UpdateChecker updates() {
        return updates;
    }

    public UpdateNotifier updateNotifier() {
        return updateNotifier;
    }
}
