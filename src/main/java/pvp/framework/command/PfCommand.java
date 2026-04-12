package pvp.framework.command;

import pvp.framework.PvPFramework;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /pf ショートカットコマンド。
 *
 * /pf j <game-id>  →  /pvpf join <game-id> と同等
 */
public class PfCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "§b[PvPF] §r";
    private final PvPFramework plugin;

    public PfCommand(PvPFramework plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !(args[0].equalsIgnoreCase("j") || args[0].equalsIgnoreCase("join"))) {
            sender.sendMessage(PREFIX + "§7使い方: §f/pf j <ゲームID>");
            showGameList(sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cプレイヤーのみ使用できます。");
            return true;
        }
        if (!player.hasPermission("pvpf.join") && !player.hasPermission("pvpf.admin")) {
            player.sendMessage(PREFIX + "§c権限がありません。");
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(PREFIX + "§7使い方: §f/pf j <ゲームID>");
            showGameList(sender);
            return true;
        }

        String gameId = args[1];
        boolean joined = plugin.getSessionManager().joinOrQueue(player, gameId);
        if (joined) {
            player.sendMessage(PREFIX + "§a§f" + gameId + " §aに参加しました！");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("j").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("j")) {
            return new ArrayList<>(plugin.getGameLoader().getAll().keySet()).stream()
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private void showGameList(CommandSender sender) {
        var games = plugin.getGameLoader().getAll();
        if (games.isEmpty()) {
            sender.sendMessage(PREFIX + "§c参加できるゲームがありません。");
            return;
        }
        sender.sendMessage(PREFIX + "§e参加可能なゲーム:");
        for (var cfg : games.values()) {
            sender.sendMessage("  §7- §f" + cfg.getGameId()
                    + " §7[" + cfg.getMode() + "] " + cfg.getDisplayName());
        }
    }
}
