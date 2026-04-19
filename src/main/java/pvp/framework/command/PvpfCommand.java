package pvp.framework.command;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;
import pvp.framework.i18n.MessageManager;
import pvp.framework.mode.YamlGameSession;
import pvp.framework.session.GameSession;
import pvp.framework.session.GameState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /pvpf コマンドハンドラ。
 *
 * 権限ノード:
 *   pvpf.admin — reload / game / template / npc サブコマンド
 *   pvpf.join  — join サブコマンド（一般プレイヤー用）
 */
public class PvpfCommand implements CommandExecutor, TabCompleter {

    private final PvPFramework plugin;

    public PvpfCommand(PvPFramework plugin) {
        this.plugin = plugin;
    }

    private MessageManager mm() { return plugin.getMessageManager(); }

    // -------------------------------------------------------
    // onCommand
    // -------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "join"     -> handleJoin(sender, args);
            case "leave"    -> handleLeave(sender);
            case "reload"   -> { requireAdmin(sender); yield handleReload(sender); }
            case "game"     -> { requireAdmin(sender); yield handleGame(sender, args); }
            case "template" -> { requireAdmin(sender); yield handleTemplate(sender, args); }
            case "npc"      -> { requireAdmin(sender); yield handleNpc(sender, args); }
            default         -> { sendHelp(sender); yield true; }
        };
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("pvpf.admin")) {
            sender.sendMessage(mm().getPrefixed("command.no-permission"));
            return false;
        }
        return true;
    }

    // -------------------------------------------------------
    // join / leave
    // -------------------------------------------------------

    private boolean handleJoin(CommandSender sender, String[] args) {

        // コマンドブロック / コンソール
        if (!(sender instanceof Player)) {
            if (args.length < 3) {
                sender.sendMessage(mm().getPrefixed("command.join.cb-usage"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(mm().getPrefixed("command.join.cb-player-not-found", "player", args[1]));
                return true;
            }
            plugin.getSessionManager().joinOrQueue(target, args[2]);
            return true;
        }

        // プレイヤー
        Player player = (Player) sender;
        if (!player.hasPermission("pvpf.join") && !player.hasPermission("pvpf.admin")) {
            player.sendMessage(mm().getPrefixed("command.no-permission"));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(mm().getPrefixed("command.join.usage"));
            Map<String, GameConfig> games = plugin.getGameLoader().getAll();
            if (!games.isEmpty()) {
                player.sendMessage(mm().getPrefixed("command.join.available-header"));
                for (var cfg : games.values()) {
                    player.sendMessage("  §7- §f" + cfg.getGameId()
                            + " §7[" + cfg.getMode() + "] " + cfg.getDisplayName());
                }
            }
            return true;
        }

        String arg = args[1];

        // セッションIDが直接指定された場合
        if (plugin.getSessionManager().getSessions().containsKey(arg)) {
            boolean joined = plugin.getSessionManager().joinSession(player, arg);
            if (joined) sendJoinMessage(player, arg);
            return true;
        }

        // ゲームIDとして自動キュー参加
        boolean joined = plugin.getSessionManager().joinOrQueue(player, arg);
        if (joined) sendJoinMessage(player, arg);
        return true;
    }

    private void sendJoinMessage(Player player, String gameId) {
        GameConfig cfg = plugin.getGameLoader().getConfig(gameId);
        if (cfg != null && "FREEPLAY".equals(cfg.getMode())) {
            player.sendMessage(mm().getPrefixed("command.join.success-freeplay", "displayName", cfg.getDisplayName()));
            player.sendMessage(mm().getPrefixed("command.join.success-freeplay-hint"));
        } else {
            player.sendMessage(mm().getPrefixed("command.join.success", "gameId", gameId));
        }
    }

    /**
     * [Bug②修正] 2段階退出の判定をコンパスの有無で行う。
     *
     * LeaveCompass を持っている（= アリーナにいる）:
     *   → 待機場所へ移動。セッションには残留。
     *   　 もう一度 /pvpf leave でグローバルロビーへ退出できる。
     *
     * LeaveCompass を持っていない（= 待機場所 or WAITING/COUNTDOWN）:
     *   → グローバルロビーへ。セッション退出。
     *
     * これにより ACTIVE 状態でも「アリーナにいるか」「待機場所にいるか」を
     * 正確に区別でき、無限ループを防ぐ。
     */
    private boolean handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(mm().getPrefixed("command.leave.player-only"));
            return true;
        }

        GameSession session = plugin.getSessionManager().getSessionByPlayer(player);
        if (session == null) {
            player.sendMessage(mm().getPrefixed("command.leave.not-in-game"));
            return true;
        }

        // LeaveCompass を持っている = アリーナにいる
        if (session.getState() == GameState.ACTIVE && session.isPlayerInArena(player)) {
            // アリーナ → 待機場所へ（セッション維持）
            returnToGameLobby(player, session);
            player.sendMessage(mm().getPrefixed("command.leave.returned-to-game-lobby"));
        } else {
            // 待機場所 or WAITING/COUNTDOWN → グローバルロビーへ（セッション退出）
            plugin.getSessionManager().leaveSession(player);
            player.sendMessage(mm().getPrefixed("command.leave.left"));
        }
        return true;
    }

    /**
     * プレイヤーをゲームの待機場所（game lobby）へ移動する。
     * セッションからは退出せず、LeaveCompassをMenuCompassに切り替える。
     */
    private void returnToGameLobby(Player player, GameSession session) {
        Location lobbyLoc = null;
        if (session instanceof YamlGameSession ys && ys.getConfig().getLobby() != null) {
            lobbyLoc = ys.getConfig().getLobby().toBukkitLocation();
        }
        if (lobbyLoc == null) {
            lobbyLoc = plugin.getConfigManager().getGlobalLobby();
        }
        if (lobbyLoc != null) player.teleport(lobbyLoc);

        // LeaveCompass → MenuCompass に切り替える（次回 /pvpf leave で待機場所→ロビー判定になる）
        GameSession.removeLeaveCompass(player);
        GameSession.giveMenuCompass(player);
    }

    // -------------------------------------------------------
    // reload
    // -------------------------------------------------------
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("pvpf.admin")) return true;
        sender.sendMessage(mm().getPrefixed("command.reload.start"));
        plugin.getConfigManager().load();
        plugin.getMessageManager().reload();
        plugin.getKitManager().load();
        plugin.getArenaManager().reload();
        plugin.getGameLoader().reload();
        sender.sendMessage(mm().getPrefixed("command.reload.done",
                "kits",   String.valueOf(plugin.getKitManager().getKits().size()),
                "arenas", String.valueOf(plugin.getArenaManager().getArenas().size()),
                "games",  String.valueOf(plugin.getGameLoader().getAll().size())));
        return true;
    }

    // -------------------------------------------------------
    // game
    // -------------------------------------------------------
    private boolean handleGame(CommandSender sender, String[] args) {
        if (!sender.hasPermission("pvpf.admin")) return true;
        if (args.length < 2) {
            sender.sendMessage(mm().getPrefixed("command.game.usage"));
            return true;
        }
        return switch (args[1].toLowerCase()) {
            case "list"   -> handleGameList(sender);
            case "start"  -> handleGameStart(sender, args);
            case "stop"   -> handleGameStop(sender, args);
            case "status" -> handleGameStatus(sender, args);
            default       -> { sender.sendMessage(mm().getPrefixed("command.game.unknown-sub", "sub", args[1])); yield true; }
        };
    }

    private boolean handleGameList(CommandSender sender) {
        Map<String, GameConfig> games = plugin.getGameLoader().getAll();
        sender.sendMessage(mm().getPrefixed("command.game.list.header-games", "count", String.valueOf(games.size())));
        for (GameConfig cfg : games.values()) {
            sender.sendMessage("  §7- §f" + cfg.getGameId()
                    + " §7[" + cfg.getMode() + "] " + cfg.getDisplayName());
        }
        Map<String, GameSession> sessions = plugin.getSessionManager().getSessions();
        sender.sendMessage(mm().getPrefixed("command.game.list.header-sessions", "count", String.valueOf(sessions.size())));
        for (GameSession s : sessions.values()) {
            sender.sendMessage("  §7- §f" + s.getSessionId()
                    + " §7[" + s.getState() + "] players=" + s.getPlayerCount());
        }
        return true;
    }

    private boolean handleGameStart(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(mm().getPrefixed("command.game.start.usage"));
            return true;
        }
        String gameId = args[2];
        GameConfig cfg = plugin.getGameLoader().getConfig(gameId);
        if (cfg == null) {
            sender.sendMessage(mm().getPrefixed("command.game.start.not-found", "gameId", gameId));
            return true;
        }
        boolean alreadyExists = plugin.getSessionManager().getSessions().values().stream()
                .anyMatch(s -> s.getGameId().equals(gameId)
                        && (s.getState() == GameState.WAITING
                            || s.getState() == GameState.COUNTDOWN));
        if (alreadyExists) {
            sender.sendMessage(mm().getPrefixed("command.game.start.already-exists", "gameId", gameId));
            return true;
        }
        String sessionId = gameId + "-" + (System.currentTimeMillis() % 100000);
        YamlGameSession session = plugin.getModeEngine().createSession(plugin, sessionId, cfg);
        plugin.getSessionManager().registerSession(session);
        sender.sendMessage(mm().getPrefixed("command.game.start.success", "sessionId", sessionId));
        sender.sendMessage(mm().getPrefixed("command.game.start.hint", "gameId", gameId));
        return true;
    }

    private boolean handleGameStop(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(mm().getPrefixed("command.game.stop.usage"));
            return true;
        }
        GameSession session = plugin.getSessionManager().getSession(args[2]);
        if (session == null) {
            sender.sendMessage(mm().getPrefixed("command.game.stop.not-found", "sessionId", args[2]));
            return true;
        }
        session.endGame(mm().get("command.game.stop.force-reason"));
        sender.sendMessage(mm().getPrefixed("command.game.stop.success", "sessionId", args[2]));
        return true;
    }

    private boolean handleGameStatus(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(mm().getPrefixed("command.game.status.usage"));
            return true;
        }
        GameSession session = plugin.getSessionManager().getSession(args[2]);
        if (session == null) {
            sender.sendMessage(mm().getPrefixed("command.game.status.not-found", "sessionId", args[2]));
            return true;
        }
        sender.sendMessage(mm().getPrefixed("command.game.status.header", "sessionId", args[2]));
        sender.sendMessage(mm().get("command.game.status.line-state", "state", session.getState().toString()));
        sender.sendMessage(mm().get("command.game.status.line-game-id", "gameId", session.getGameId()));
        sender.sendMessage(mm().get("command.game.status.line-player-count", "count", String.valueOf(session.getPlayerCount())));
        sender.sendMessage(mm().get("command.game.status.line-elapsed", "elapsed", String.valueOf(session.getTimeElapsed())));
        if (session instanceof YamlGameSession ys) {
            sender.sendMessage(mm().get("command.game.status.line-mode", "mode", ys.getConfig().getMode()));
        }
        return true;
    }

    // -------------------------------------------------------
    // template
    // -------------------------------------------------------
    private boolean handleTemplate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("pvpf.admin")) return true;
        if (args.length < 2) {
            sender.sendMessage(mm().getPrefixed("command.template.usage"));
            return true;
        }
        return switch (args[1].toLowerCase()) {
            case "list" -> handleTemplateList(sender);
            case "copy" -> handleTemplateCopy(sender, args);
            default     -> { sender.sendMessage(mm().getPrefixed("command.template.unknown-sub", "sub", args[1])); yield true; }
        };
    }

    private boolean handleTemplateList(CommandSender sender) {
        List<String> templates = plugin.getTemplateManager().listTemplates();
        if (templates.isEmpty()) {
            sender.sendMessage(mm().getPrefixed("command.template.list.empty"));
            return true;
        }
        sender.sendMessage(mm().getPrefixed("command.template.list.header", "count", String.valueOf(templates.size())));
        for (String name : templates) {
            sender.sendMessage("  §7- §f" + name + " §7→ /pvpf template copy " + name + " <new-id>");
        }
        return true;
    }

    private boolean handleTemplateCopy(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(mm().getPrefixed("command.template.copy.usage"));
            return true;
        }
        String newGameId = args[3];
        if (!newGameId.matches("[a-zA-Z0-9_\\-]+")) {
            sender.sendMessage(mm().getPrefixed("command.template.copy.invalid-id"));
            return true;
        }
        if (plugin.getTemplateManager().gameFileExists(newGameId)) {
            sender.sendMessage(mm().getPrefixed("command.template.copy.already-exists", "gameId", newGameId));
            return true;
        }
        boolean ok = plugin.getTemplateManager().copyTemplate(args[2], newGameId);
        if (ok) {
            sender.sendMessage(mm().getPrefixed("command.template.copy.success", "gameId", newGameId));
            sender.sendMessage(mm().getPrefixed("command.template.copy.hint"));
        } else {
            sender.sendMessage(mm().getPrefixed("command.template.copy.failed"));
        }
        return true;
    }

    // -------------------------------------------------------
    // npc
    // -------------------------------------------------------

    /**
     * /pvpf npc <create|createlobby|delete|teleport> ...
     */
    private boolean handleNpc(CommandSender sender, String[] args) {
        if (!sender.hasPermission("pvpf.admin")) return true;

        if (!plugin.getFancyNpcManager().isFancyNpcsAvailable()) {
            sender.sendMessage(mm().getPrefixed("command.npc.no-plugin"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(mm().getPrefixed("command.npc.usage"));
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "create"      -> handleNpcCreate(sender, args);
            case "createlobby" -> handleNpcCreateLobby(sender, args);
            case "delete"      -> handleNpcDelete(sender, args);
            case "teleport"    -> handleNpcTeleport(sender, args);
            default            -> {
                sender.sendMessage(mm().getPrefixed("command.npc.unknown-sub", "sub", args[1]));
                sender.sendMessage(mm().getPrefixed("command.npc.usage"));
                yield true;
            }
        };
    }

    private boolean handleNpcCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(mm().getPrefixed("command.npc.player-only"));
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(mm().getPrefixed("command.npc.create.usage"));
            return true;
        }

        String name   = args[2];
        String gameId = args[3];

        // [要望] NPC名は日本語含む任意の文字列を許可。空文字のみ弾く。
        if (name.isBlank()) {
            sender.sendMessage(mm().getPrefixed("command.npc.name-empty"));
            return true;
        }

        boolean ok = plugin.getFancyNpcManager().createNpc(name, gameId, player, null);
        if (ok) {
            sender.sendMessage(mm().getPrefixed("command.npc.create.success", "name", name));
            sender.sendMessage(mm().getPrefixed("command.npc.create.linked", "gameId", gameId));
            sender.sendMessage(mm().getPrefixed("command.npc.create.hint"));
        }
        return true;
    }

    private boolean handleNpcCreateLobby(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(mm().getPrefixed("command.npc.player-only"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(mm().getPrefixed("command.npc.createlobby.usage"));
            return true;
        }

        String name = args[2];

        // [要望] NPC名は日本語含む任意の文字列を許可。空文字のみ弾く。
        if (name.isBlank()) {
            sender.sendMessage(mm().getPrefixed("command.npc.name-empty"));
            return true;
        }

        boolean ok = plugin.getFancyNpcManager().createLobbyNpc(name, player, null);
        if (ok) {
            sender.sendMessage(mm().getPrefixed("command.npc.createlobby.success", "name", name));
            sender.sendMessage(mm().getPrefixed("command.npc.createlobby.hint"));
        }
        return true;
    }

    private boolean handleNpcDelete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(mm().getPrefixed("command.npc.delete.usage"));
            return true;
        }
        // 名前にスペースが含まれる場合（クォートなし）は args[2] 以降を結合
        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        boolean ok = plugin.getFancyNpcManager().deleteNpc(name);
        if (ok) {
            sender.sendMessage(mm().getPrefixed("command.npc.delete.success", "name", name));
        } else {
            sender.sendMessage(mm().getPrefixed("command.npc.delete.not-found", "name", name));
        }
        return true;
    }

    private boolean handleNpcTeleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(mm().getPrefixed("command.npc.player-only"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(mm().getPrefixed("command.npc.teleport.usage"));
            return true;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        boolean ok = plugin.getFancyNpcManager().teleportToNpc(name, player);
        if (ok) {
            sender.sendMessage(mm().getPrefixed("command.npc.teleport.success", "name", name));
        } else {
            sender.sendMessage(mm().getPrefixed("command.npc.teleport.not-found", "name", name));
        }
        return true;
    }

    // -------------------------------------------------------
    // Tab補完
    // -------------------------------------------------------
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "leave"));
            if (sender.hasPermission("pvpf.admin")) {
                subs.addAll(List.of("reload", "game", "template", "npc"));
            }
            return filter(subs, args[0]);
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("join") && args.length == 2) {
            List<String> candidates = new ArrayList<>(plugin.getGameLoader().getAll().keySet());
            plugin.getSessionManager().getSessions().keySet().stream()
                    .filter(id -> !candidates.contains(id))
                    .forEach(candidates::add);
            return filter(candidates, args[1]);
        }

        if (!sender.hasPermission("pvpf.admin")) return List.of();

        if (sub.equals("game")) {
            if (args.length == 2) return filter(List.of("list", "start", "stop", "status"), args[1]);
            if (args.length == 3) {
                return switch (args[1].toLowerCase()) {
                    case "start" -> filter(new ArrayList<>(plugin.getGameLoader().getAll().keySet()), args[2]);
                    case "stop", "status" ->
                            filter(new ArrayList<>(plugin.getSessionManager().getSessions().keySet()), args[2]);
                    default -> List.of();
                };
            }
        }

        if (sub.equals("template")) {
            if (args.length == 2) return filter(List.of("list", "copy"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("copy")) {
                return filter(plugin.getTemplateManager().listTemplates(), args[2]);
            }
        }

        if (sub.equals("npc")) {
            if (args.length == 2) return filter(List.of("create", "createlobby", "delete", "teleport"), args[1]);
            if (args.length == 3) {
                return switch (args[1].toLowerCase()) {
                    // [変更] getDisplayNames() で表示名リストをTab補完に使用
                    case "delete", "teleport" ->
                            filter(plugin.getFancyNpcManager().getDisplayNames(), args[2]);
                    default -> List.of();
                };
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("create")) {
                return filter(new ArrayList<>(plugin.getGameLoader().getAll().keySet()), args[3]);
            }
        }

        return List.of();
    }

    // -------------------------------------------------------
    // ユーティリティ
    // -------------------------------------------------------
    private List<String> filter(List<String> list, String prefix) {
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(mm().getPrefixed("command.help.header"));
        sender.sendMessage(mm().get("command.help.join"));
        sender.sendMessage(mm().get("command.help.leave"));
        if (sender.hasPermission("pvpf.admin")) {
            sender.sendMessage(mm().get("command.help.reload"));
            sender.sendMessage(mm().get("command.help.game-list"));
            sender.sendMessage(mm().get("command.help.game-start"));
            sender.sendMessage(mm().get("command.help.game-stop"));
            sender.sendMessage(mm().get("command.help.game-status"));
            sender.sendMessage(mm().get("command.help.template-list"));
            sender.sendMessage(mm().get("command.help.template-copy"));
            sender.sendMessage(mm().get("command.help.npc-create"));
            sender.sendMessage(mm().get("command.help.npc-createlobby"));
            sender.sendMessage(mm().get("command.help.npc-delete"));
            sender.sendMessage(mm().get("command.help.npc-teleport"));
            sender.sendMessage(mm().get("command.help.npc-note"));
        }
    }
}
