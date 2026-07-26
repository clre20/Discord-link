package clre20.discordLink;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DiscordLinkCommand implements CommandExecutor, TabCompleter {

    private final DatabaseManager dbManager;
    private final ValidationManager validationManager;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public DiscordLinkCommand(DatabaseManager dbManager, ValidationManager validationManager) {
        this.dbManager = dbManager;
        this.validationManager = validationManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "bypass":
                if (!sender.hasPermission("discordlink.admin")) {
                    sender.sendMessage(mm.deserialize("<red>您沒有權限執行此指令。</red>"));
                    return true;
                }
                handleBypass(sender, args, label);
                break;

            case "link":
                if (!sender.hasPermission("discordlink.use")) {
                    sender.sendMessage(mm.deserialize("<red>您沒有權限執行此指令。</red>"));
                    return true;
                }
                handleLink(sender);
                break;

            case "reload":
                if (!sender.hasPermission("discordlink.admin")) {
                    sender.sendMessage(mm.deserialize("<red>您沒有權限執行此指令。</red>"));
                    return true;
                }
                handleReload(sender);
                break;

            case "admin":
                if (!sender.hasPermission("discordlink.admin")) {
                    sender.sendMessage(mm.deserialize("<red>您沒有權限執行此指令。</red>"));
                    return true;
                }
                handleAdmin(sender, args, label);
                break;

            case "help":
            default:
                sendHelp(sender, label);
                break;
        }

        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§b=== DiscordLink 指令列表 ===");
        if (sender.hasPermission("discordlink.use")) {
            sender.sendMessage("§e/" + label + " link §7- 產生 Discord 驗證碼進行帳號綁定");
        }
        if (sender.hasPermission("discordlink.admin")) {
            sender.sendMessage("§e/" + label + " admin query <玩家|ID> §7- 查詢綁定關係");
            sender.sendMessage("§e/" + label + " admin unbind <玩家|ID> §7- 解除綁定關係");
            sender.sendMessage("§e/" + label + " admin list [頁碼] §7- 列出所有綁定清單");
            sender.sendMessage("§e/" + label + " bypass add <玩家> §7- 將玩家加入免驗證白名單");
            sender.sendMessage("§e/" + label + " bypass remove <玩家> §7- 將玩家移出免驗證白名單");
            sender.sendMessage("§e/" + label + " reload §7- 重新載入設定檔");
        }
    }

    private void handleBypass(CommandSender sender, String[] args, String label) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /" + label + " bypass <add|remove> <玩家名稱>");
            return;
        }

        String action = args[1].toLowerCase();
        String playerName = args[2];

        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);

        if (action.equals("add")) {
            dbManager.addBypass(target.getUniqueId());
            sender.sendMessage("§a成功將 §e" + target.getName() + " §a加入免驗證白名單！");
        } else if (action.equals("remove")) {
            dbManager.removeBypass(target.getUniqueId());
            sender.sendMessage("§c已將 §e" + target.getName() + " §c從免驗證白名單中移除！");
        } else {
            sender.sendMessage("§c無效的參數。請使用 add 或 remove。");
        }
    }

    private void handleLink(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c此指令只能由遊戲內玩家執行！");
            return;
        }

        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        if (dbManager.isBypassed(uuid)) {
            sender.sendMessage("§a您已在免驗證白名單中，無需進行綁定！");
            return;
        }

        if (dbManager.isBound(uuid)) {
            sender.sendMessage("§c您的帳號已經綁定過 Discord 了！");
            return;
        }

        String code = validationManager.generateOrRefreshCode(uuid);
        int expiryMinutes = DiscordLink.getPlugin(DiscordLink.class).getConfig().getInt("verification.code-expiry-minutes", 5);

        player.sendMessage("§a您的 Discord 綁定代碼為: §e§l[" + code + "]§r");
        player.sendMessage("§7請在 Discord 驗證頻道中直接輸入此代碼以完成綁定（有效時間 " + expiryMinutes + " 分鐘）。");
    }

    private void handleReload(CommandSender sender) {
        DiscordLink plugin = DiscordLink.getPlugin(DiscordLink.class);
        plugin.reloadConfig();
        sender.sendMessage("§aDiscordLink 設定檔已成功重新載入！");
        plugin.printConfigReport();
    }

    private void handleAdmin(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: ");
            sender.sendMessage("§c/" + label + " admin query <玩家名稱|Discord ID> - 查詢綁定關係");
            sender.sendMessage("§c/" + label + " admin unbind <玩家名稱|Discord ID> - 解除綁定關係");
            sender.sendMessage("§c/" + label + " admin list [頁碼] - 列出所有綁定清單");
            return;
        }

        String action = args[1].toLowerCase();

        if (action.equals("query")) {
            if (args.length < 3) {
                sender.sendMessage("§c請指定玩家名稱或 Discord ID。");
                return;
            }
            String target = args[2];
            if (target.matches("\\d{17,20}")) {
                List<UUID> uuids = dbManager.getBoundUuids(target);
                if (uuids.isEmpty()) {
                    sender.sendMessage("§c該 Discord ID (" + target + ") 未綁定任何 Minecraft 帳號。");
                } else {
                    sender.sendMessage("§aDiscord ID §e" + target + " §a綁定的帳號有：");
                    for (UUID uuid : uuids) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                        sender.sendMessage("§7- §f" + (op.getName() != null ? op.getName() : "未知玩家") + " §8(" + uuid + ")");
                    }
                }
            } else {
                OfflinePlayer op = Bukkit.getOfflinePlayer(target);
                UUID uuid = op.getUniqueId();
                String dcId = dbManager.getDiscordId(uuid);
                if (dcId == null) {
                    sender.sendMessage("§c玩家 §e" + target + " §c尚未綁定 Discord 帳號。");
                } else {
                    sender.sendMessage("§a玩家 §e" + (op.getName() != null ? op.getName() : target) + " §a已綁定 Discord ID: §e" + dcId);
                }
            }
        } else if (action.equals("unbind")) {
            if (args.length < 3) {
                sender.sendMessage("§c請指定玩家名稱或 Discord ID。");
                return;
            }
            String target = args[2];
            if (target.matches("\\d{17,20}")) {
                List<UUID> uuids = dbManager.getBoundUuids(target);
                if (uuids.isEmpty()) {
                    sender.sendMessage("§c該 Discord ID (" + target + ") 未綁定任何帳號，無需解綁。");
                } else {
                    dbManager.unbindDiscord(target);
                    sender.sendMessage("§a已成功解除 Discord ID §e" + target + " §a所綁定的所有 Minecraft 帳號！");
                }
            } else {
                OfflinePlayer op = Bukkit.getOfflinePlayer(target);
                UUID uuid = op.getUniqueId();
                if (!dbManager.isBound(uuid)) {
                    sender.sendMessage("§c玩家 §e" + target + " §c尚未綁定，無需解綁。");
                } else {
                    dbManager.unbind(uuid);
                    sender.sendMessage("§a已成功解除玩家 §e" + (op.getName() != null ? op.getName() : target) + " §a的 Discord 綁定！");
                }
            }
        } else if (action.equals("list")) {
            java.util.Map<UUID, String> bindings = dbManager.getAllBindings();
            if (bindings.isEmpty()) {
                sender.sendMessage("§c目前伺服器沒有任何綁定資料。");
                return;
            }

            int page = 1;
            if (args.length >= 3) {
                try {
                    page = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c頁碼必須是數字。");
                    return;
                }
            }

            int itemsPerPage = 7;
            List<UUID> keys = new java.util.ArrayList<>(bindings.keySet());
            int totalItems = keys.size();
            int totalPages = (int) Math.ceil((double) totalItems / itemsPerPage);

            if (page < 1 || page > totalPages) {
                sender.sendMessage("§c無效的頁碼，目前總共有 " + totalPages + " 頁。");
                return;
            }

            sender.sendMessage("§b=== Discord 綁定清單 (第 " + page + "/" + totalPages + " 頁) ===");
            int start = (page - 1) * itemsPerPage;
            int end = Math.min(start + itemsPerPage, totalItems);

            for (int i = start; i < end; i++) {
                UUID uuid = keys.get(i);
                String dcId = bindings.get(uuid);
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                sender.sendMessage("§e" + (op.getName() != null ? op.getName() : "未知玩家") + " §8(" + uuid.toString().substring(0, 8) + "...) §7⇄ §a" + dcId);
            }
        } else {
            sender.sendMessage("§c無效的管理指令參數。");
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission("discordlink.use")) {
                completions.add("link");
                completions.add("help");
            }
            if (sender.hasPermission("discordlink.admin")) {
                completions.add("bypass");
                completions.add("reload");
                completions.add("admin");
            }
            return filterCompletions(completions, args[0]);
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("bypass") && sender.hasPermission("discordlink.admin")) {
                completions.add("add");
                completions.add("remove");
                return filterCompletions(completions, args[1]);
            }
            if (args[0].equalsIgnoreCase("admin") && sender.hasPermission("discordlink.admin")) {
                completions.add("query");
                completions.add("unbind");
                completions.add("list");
                return filterCompletions(completions, args[1]);
            }
        }

        if (args.length == 3 && sender.hasPermission("discordlink.admin")) {
            if (args[0].equalsIgnoreCase("bypass")) {
                return null; // 回傳 null 會自動列出線上玩家名單供補全
            }
            if (args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("query") || args[1].equalsIgnoreCase("unbind"))) {
                return null; // 回傳 null 會自動列出線上玩家名單供補全
            }
        }

        return completions;
    }

    private List<String> filterCompletions(List<String> list, String input) {
        List<String> filtered = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(input.toLowerCase())) {
                filtered.add(s);
            }
        }
        return filtered;
    }
}