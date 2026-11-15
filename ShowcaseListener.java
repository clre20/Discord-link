package clre20.discordLink.showcase;

import clre20.discordLink.DiscordLink;
import clre20.discordLink.config.Messages;
import clre20.discordLink.config.PluginConfig;
import clre20.discordLink.render.ItemView;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 遊戲內聊天的 [item]／[inv]／[ender] 展示與提及玩家（取代 InteractiveChat）。
 * <ul>
 *   <li>高頻路徑先做便宜的檢查：沒有 '['、沒有提到線上玩家名稱就直接結束。</li>
 *   <li>讀取背包要在主執行緒：聊天事件在背景執行緒時，等主執行緒取快照（最多 2 秒）。</li>
 *   <li>快照存在點擊回呼裡，時間到由伺服器自動清除；冷卻資料在玩家離線時移除。</li>
 * </ul>
 */
public final class ShowcaseListener implements Listener {

    public static final String PERM_ITEM = "discordlink.showcase.item";
    public static final String PERM_INVENTORY = "discordlink.showcase.inventory";
    public static final String PERM_ENDER = "discordlink.showcase.ender";
    public static final String PERM_MENTION = "discordlink.mention";

    private static final int MAX_REPLACE = 3;
    private static final int MAX_MENTIONS = 5;

    private record Capture(ItemStack hand, Component itemName, ItemStack[] itemGui, ItemStack[] inventoryGui,
                           ItemStack[] enderGui, ItemView handView, ItemView[] inventoryViews, ItemView[] enderViews) {
    }

    private record Keywords(List<String> source, Pattern pattern) {
    }

    private final DiscordLink plugin;
    private final SnapshotMenu menu;
    /** 聊天事件 → 展示內容（給同一個事件稍後的 Discord 轉送使用；弱參照，事件結束後自動回收）。 */
    private final Map<AsyncChatEvent, ShowcaseData> pending = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<UUID, Long> showcaseCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> mentionCooldown = new ConcurrentHashMap<>();
    private volatile Keywords item = new Keywords(List.of(), null);
    private volatile Keywords inventory = new Keywords(List.of(), null);
    private volatile Keywords ender = new Keywords(List.of(), null);
    private volatile boolean disabled;

    public ShowcaseListener(DiscordLink plugin, SnapshotMenu menu) {
        this.plugin = plugin;
        this.menu = menu;
    }

    /** InteractiveChat 存在時停用，避免重複處理。 */
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    /** 取出（並移除）這則訊息的展示內容。 */
    public ShowcaseData take(AsyncChatEvent event) {
        return pending.remove(event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (disabled) {
            return;
        }
        PluginConfig config = plugin.config();
        boolean showcase = config.showcaseEnabled();
        boolean mention = config.mentionEnabled();
        if (!showcase && !mention) {
            return;
        }
        Player player = event.getPlayer();
        Component message = event.message();
        String plain = PlainTextComponentSerializer.plainText().serialize(message);
        boolean changed = false;

        if (mention && player.hasPermission(PERM_MENTION)) {
            Component m = mentions(player, message, plain);
            if (m != message) {
                message = m;
                changed = true;
            }
        }

        if (showcase && plain.indexOf('[') >= 0) {
            boolean wantItem = player.hasPermission(PERM_ITEM) && find(plain, item = keywords(item, config.itemKeywords()));
            boolean wantInv = player.hasPermission(PERM_INVENTORY) && find(plain, inventory = keywords(inventory, config.inventoryKeywords()));
            boolean wantEnder = player.hasPermission(PERM_ENDER) && find(plain, ender = keywords(ender, config.enderKeywords()));
            if ((wantInv || wantEnder) && !cooldownReady(player)) {
                wantInv = false;
                wantEnder = false;
            }
            if (wantItem || wantInv || wantEnder) {
                boolean views = plugin.wantsDiscordImages();
                Capture cap = capture(player, wantItem, wantInv, wantEnder, views);
                if (cap != null) {
                    message = replace(player, message, cap, wantItem, wantInv, wantEnder);
                    changed = true;
                    String owner = player.getName();
                    ShowcaseData data = new ShowcaseData(owner, cap.itemName(),
                            cap.hand() == null ? 1 : cap.hand().getAmount(), cap.handView(),
                            cap.inventoryViews(), menu.title(SnapshotMenu.Kind.INVENTORY, owner),
                            cap.enderViews(), menu.title(SnapshotMenu.Kind.ENDER, owner));
                    if (views && !data.isEmpty()) {
                        pending.put(event, data);
                    }
                }
            }
        }
        if (changed) {
            event.message(message);
        }
    }

    // ---------- 展示 ----------

    private static Keywords keywords(Keywords cached, List<String> list) {
        if (cached.source() == list) {
            return cached;
        }
        if (list.isEmpty()) {
            return new Keywords(list, null);
        }
        StringBuilder sb = new StringBuilder();
        for (String k : list) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            sb.append(Pattern.quote(k));
        }
        return new Keywords(list, Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    private static boolean find(String plain, Keywords k) {
        return k.pattern() != null && k.pattern().matcher(plain).find();
    }

    private boolean cooldownReady(Player player) {
        int seconds = plugin.config().showcaseCooldownSeconds();
        if (seconds <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = showcaseCooldown.get(player.getUniqueId());
        if (last != null && now - last < seconds * 1000L) {
            long left = (seconds * 1000L - (now - last) + 999) / 1000;
            plugin.messages().send(player, "showcase.cooldown", "seconds", left);
            return false;
        }
        showcaseCooldown.put(player.getUniqueId(), now);
        return true;
    }

    private Capture capture(Player player, boolean item, boolean inv, boolean ender, boolean views) {
        boolean advanced = plugin.config().advancedTooltip();
        Callable<Capture> task = () -> {
            if (!player.isOnline()) {
                return null;
            }
            ItemStack hand = null;
            Component name = null;
            ItemStack[] itemGui = null;
            ItemView handView = null;
            if (item) {
                ItemStack h = player.getInventory().getItemInMainHand();
                hand = ItemCapture.empty(h) ? null : h.clone();
                name = ItemCapture.name(hand);
                itemGui = menu.itemLayout(hand);
                if (views) {
                    handView = hand == null ? ItemCapture.air(advanced) : ItemCapture.view(hand, player, true, advanced);
                }
            }
            ItemStack[] invGui = inv ? menu.inventoryLayout(player) : null;
            ItemStack[] enderGui = ender ? menu.enderLayout(player) : null;
            return new Capture(hand, name, itemGui, invGui, enderGui, handView,
                    views && invGui != null ? ItemCapture.views(invGui, player) : null,
                    views && enderGui != null ? ItemCapture.views(enderGui, player) : null);
        };
        try {
            if (Bukkit.isPrimaryThread()) {
                return task.call();
            }
            if (!plugin.isEnabled()) {
                return null;
            }
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private Component replace(Player player, Component message, Capture cap, boolean wantItem, boolean wantInv, boolean wantEnder) {
        Messages msg = plugin.messages();
        Component out = message;
        int minutes = plugin.config().snapshotMinutes();
        String owner = player.getName();
        if (wantItem && cap.itemGui() != null) {
            ItemStack[] snap = cap.itemGui();
            Component amount = cap.hand() != null && cap.hand().getAmount() > 1
                    ? msg.component("showcase.amount", "amount", cap.hand().getAmount()) : Component.empty();
            Component c = msg.componentWith("showcase.item", Map.of("item", cap.itemName(), "amount", amount))
                    .clickEvent(callback(minutes, viewer -> menu.open(viewer, SnapshotMenu.Kind.ITEM, owner, snap)));
            c = cap.hand() != null ? c.hoverEvent(cap.hand().asHoverEvent())
                    : c.hoverEvent(HoverEvent.showText(msg.component("showcase.click-hover", "minutes", minutes)));
            out = out.replaceText(config(item.pattern(), c));
        }
        if (wantInv && cap.inventoryGui() != null) {
            ItemStack[] snap = cap.inventoryGui();
            Component c = msg.component("showcase.inventory", "player", owner)
                    .hoverEvent(HoverEvent.showText(msg.component("showcase.click-hover", "minutes", minutes)))
                    .clickEvent(callback(minutes, viewer -> menu.open(viewer, SnapshotMenu.Kind.INVENTORY, owner, snap)));
            out = out.replaceText(config(inventory.pattern(), c));
        }
        if (wantEnder && cap.enderGui() != null) {
            ItemStack[] snap = cap.enderGui();
            Component c = msg.component("showcase.ender", "player", owner)
                    .hoverEvent(HoverEvent.showText(msg.component("showcase.click-hover", "minutes", minutes)))
                    .clickEvent(callback(minutes, viewer -> menu.open(viewer, SnapshotMenu.Kind.ENDER, owner, snap)));
            out = out.replaceText(config(ender.pattern(), c));
        }
        return out;
    }

    private static TextReplacementConfig config(Pattern p, Component replacement) {
        return TextReplacementConfig.builder().match(p).replacement(replacement).times(MAX_REPLACE).build();
    }

    private ClickEvent callback(int minutes, java.util.function.Consumer<Player> open) {
        return ClickEvent.callback(audience -> {
            if (!(audience instanceof Player viewer)) {
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                open.accept(viewer);
            } else if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (viewer.isOnline()) {
                        open.accept(viewer);
                    }
                });
            }
        }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(minutes)).build());
    }

    // ---------- 提及 ----------

    private Component mentions(Player sender, Component message, String plain) {
        String lower = plain.toLowerCase(Locale.ROOT);
        boolean requireAt = plugin.config().mentionRequireAt();
        if (requireAt && lower.indexOf('@') < 0) {
            return message;
        }
        Map<String, Player> candidates = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == sender || isVanished(p) || !sender.canSee(p)) {
                continue;
            }
            String n = p.getName().toLowerCase(Locale.ROOT);
            if (n.length() >= 3 && lower.contains(n)) {
                candidates.put(n, p);
            }
        }
        if (candidates.isEmpty()) {
            return message;
        }
        StringBuilder names = new StringBuilder();
        for (String n : candidates.keySet()) {
            if (!names.isEmpty()) {
                names.append('|');
            }
            names.append(Pattern.quote(n));
        }
        Pattern pattern = Pattern.compile((requireAt ? "@" : "(?<![A-Za-z0-9_])@?") + "(" + names + ")(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE);
        Set<UUID> mentioned = new LinkedHashSet<>();
        Messages msg = plugin.messages();
        Component out = message.replaceText(TextReplacementConfig.builder()
                .match(pattern)
                .times(MAX_MENTIONS)
                .replacement((match, builder) -> {
                    Player target = candidates.get(match.group(1).toLowerCase(Locale.ROOT));
                    if (target == null) {
                        return builder;
                    }
                    mentioned.add(target.getUniqueId());
                    return msg.component("mention.highlight", "player", target.getName());
                })
                .build());
        if (mentioned.isEmpty()) {
            return message;
        }
        if (mentionCooldownReady(sender)) {
            notifyMentioned(sender.getName(), new ArrayList<>(mentioned));
        }
        return out;
    }

    private boolean mentionCooldownReady(Player sender) {
        int seconds = plugin.config().mentionCooldownSeconds();
        long now = System.currentTimeMillis();
        Long last = mentionCooldown.get(sender.getUniqueId());
        if (seconds > 0 && last != null && now - last < seconds * 1000L) {
            return false;
        }
        mentionCooldown.put(sender.getUniqueId(), now);
        return true;
    }

    private void notifyMentioned(String sender, List<UUID> targets) {
        if (!plugin.isEnabled()) {
            return;
        }
        Messages msg = plugin.messages();
        String soundId = plugin.config().mentionSound();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Sound sound = null;
            if (!soundId.isEmpty()) {
                try {
                    sound = Sound.sound(Key.key(soundId), Sound.Source.PLAYER, 1f, 1f);
                } catch (InvalidKeyException ignored) {
                }
            }
            String title = msg.raw("mention.title");
            String subtitle = msg.raw("mention.subtitle");
            String actionbar = msg.raw("mention.actionbar");
            for (UUID id : targets) {
                Player t = Bukkit.getPlayer(id);
                if (t == null) {
                    continue;
                }
                if (sound != null) {
                    t.playSound(sound, Sound.Emitter.self());
                }
                if (!title.isBlank() || !subtitle.isBlank()) {
                    t.showTitle(Title.title(msg.component("mention.title", "sender", sender),
                            msg.component("mention.subtitle", "sender", sender),
                            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))));
                }
                if (!actionbar.isBlank()) {
                    t.sendActionBar(msg.component("mention.actionbar", "sender", sender));
                }
            }
        });
    }

    public static boolean isVanished(Player p) {
        for (MetadataValue v : p.getMetadata("vanished")) {
            if (v.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        showcaseCooldown.remove(event.getPlayer().getUniqueId());
        mentionCooldown.remove(event.getPlayer().getUniqueId());
    }

    public void clear() {
        pending.clear();
        showcaseCooldown.clear();
        mentionCooldown.clear();
    }
}
