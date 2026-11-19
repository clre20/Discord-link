package clre20.discordLink.data;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 進過伺服器的玩家（UUID → 名稱）與目前在線的玩家，給 HTTP API 查詢「誰沒有綁定」。
 * 啟動時在主執行緒讀一次，之後只靠進出服事件更新；API 執行緒只讀記憶體，不呼叫 Bukkit。
 * 只存 UUID 與名稱，不持有 Player 物件。
 */
public final class PlayerIndex implements Listener {

    private final Map<UUID, String> known = new ConcurrentHashMap<>();
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private volatile boolean loaded;

    /** 主執行緒呼叫一次。 */
    public void load() {
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            String name = op.getName();
            if (name != null && !name.isEmpty()) {
                known.put(op.getUniqueId(), name);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            known.put(p.getUniqueId(), p.getName());
            online.add(p.getUniqueId());
        }
        loaded = true;
    }

    public boolean loaded() {
        return loaded;
    }

    /** UUID → 名稱（唯讀快照）。 */
    public Map<UUID, String> known() {
        return Map.copyOf(known);
    }

    public String name(UUID uuid) {
        return known.get(uuid);
    }

    public boolean isOnline(UUID uuid) {
        return online.contains(uuid);
    }

    /** 用名稱找 UUID（不分大小寫），找不到回傳 null。 */
    public UUID findByName(String name) {
        for (Map.Entry<UUID, String> e : known.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                return e.getKey();
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        known.put(p.getUniqueId(), p.getName());
        online.add(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        online.remove(event.getPlayer().getUniqueId());
    }

    public void clear() {
        known.clear();
        online.clear();
        loaded = false;
    }
}
