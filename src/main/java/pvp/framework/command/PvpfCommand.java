package pvp.framework.command;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;
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

    private static final String PREFIX = "§b[PvPF] §r";
    private final PvPFramework plugin;

    public PvpfCommand(PvPFramework plugin) {
        this.plugin = plugin;
    }

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
            sender.sendMessage(PREFIX + "§c権限がありません。");
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
                sender.sendMessage(PREFIX + "§7コマンドブロック用: /pvpf join <プレイヤー名> <ゲームID>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(PREFIX + "§cプレイヤーが見つかりません: " + args[1]);
                return true;
            }
            plugin.getSessionManager().joinOrQueue(target, args[2]);
            return true;
        }

        // プレイヤー
        Player player = (Player) sender;
        if (!player.hasPermission("pvpf.join") && !player.hasPermission("pvpf.admin")) {
            player.sendMessage(PREFIX + "§c権限がありません。");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(PREFIX + "使い方: /pvpf join <game-id>");
            Map<String, GameConfig> games = plugin.getGameLoader().getAll();
            if (!games.isEmpty()) {
                player.sendMessage(PREFIX + "§e参加可能なゲーム:");
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
            player.sendMessage(PREFIX + "§a§f" + cfg.getDisplayName() + " §aの待機場所に参加しました！");
            player.sendMessage(PREFIX + "§7コンパスを右クリックしてアリーナへ入場できます。");
        } else {
            player.sendMessage(PREFIX + "§a§f" + gameId + " §aに参加しました！");
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
            sender.sendMessage(PREFIX + "§cプレイヤーのみ使用できます。");
            return true;
        }

        GameSession session = plugin.getSessionManager().getSessionByPlayer(player);
        if (session == null) {
            player.sendMessage(PREFIX + "§c現在ゲームに参加していません。");
            return true;
        }

        // LeaveCompass を持っている = アリーナにいる
        if (session.getState() == GameState.ACTIVE && session.isPlayerInArena(player)) {
            // アリーナ → 待機場所へ（セッション維持）
            returnToGameLobby(player, session);
            player.sendMessage(PREFIX + "§a待機場所に戻りました。もう一度 §f/pvpf leave §aでロビーへ退出できます。");
        } else {
            // 待機場所 or WAITING/COUNTDOWN → グローバルロビーへ（セッション退出）
            plugin.getSessionManager().leaveSession(player);
            player.sendMessage(PREFIX + "§aゲームから退出しました。");
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
        sender.sendMessage(PREFIX + "リロード中...");
        plugin.getConfigManager().load();
        plugin.getKitManager().load();
        plugin.getArenaManager().reload();
        plugin.getGameLoader().reload();
        sender.sendMessage(PREFIX + "§aリロード完了。"
                + " kits=" + plugin.getKitManager().getKits().size()
                + " arenas=" + plugin.getArenaManager().getArenas().size()
                + " games=" + plugin.getGameLoader().getAll().size());
        return true;
    }

    // -------------------------------------------------------
    // game
    // -------------------------------------------------------
    private boolean handleGame(CommandSender sender, String[] args) {
        if (!sender.hasPermission("pvpf.admin")) return true;
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "使い方: /pvpf game <list|start|stop|status>");
            return true;
        }
        return switch (args[1].toLowerCase()) {
            case "list"   -> handleGameList(sender);
            case "start"  -> handleGameStart(sender, args);
            case "stop"   -> handleGameStop(sender, args);
            case "status" -> handleGameStatus(sender, args);
            default       -> { sender.sendMessage(PREFIX + "不明なサブコマンド: " + args[1]); yield true; }
        };
    }

    private boolean handleGameList(CommandSender sender) {
        Map<String, GameConfig> games = plugin.getGameLoader().getAll();
        sender.sendMessage(PREFIX + "§e登録済みゲーム (" + games.size() + "):");
        for (GameConfig cfg : games.values()) {
            sender.sendMessage("  §7- §f" + cfg.getGameId()
                    + " §7[" + cfg.getMode() + "] " + cfg.getDisplayName());
        }
        Map<String, GameSession> sessions = plugin.getSessionManager().getSessions();
        sender.sendMessage(PREFIX + "§e実行中セッション (" + sessions.size() + "):");
        for (GameSession s : sessions.values()) {
            sender.sendMessage("  §7- §f" + s.getSessionId()
                    + " §7[" + s.getState() + "] players=" + s.getPlayerCount());
        }
        return true;
    }

    private boolean handleGameStart(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "使い方: /pvpf game start <game-id>");
            return true;
        }
        String gameId = args[2];
        GameConfig cfg = plugin.getGameLoader().getConfig(gameId);
        if (cfg == null) {
            sender.sendMessage(PREFIX + "§cゲームが見つかりません: " + gameId);
            return true;
        }
        boolean alreadyExists = plugin.getSessionManager().getSessions().values().stream()
                .anyMatch(s -> s.getGameId().equals(gameId)
                        && (s.getState() == GameState.WAITING
                            || s.getState() == GameState.COUNTDOWN));
        if (alreadyExists) {
            sender.sendMessage(PREFIX + "§c" + gameId + " の待機中セッションが既に存在します。");
            return true;
        }
        String sessionId = gameId + "-" + (System.currentTimeMillis() % 100000);
        YamlGameSession session = plugin.getModeEngine().createSession(plugin, sessionId, cfg);
        plugin.getSessionManager().registerSession(session);
        sender.sendMessage(PREFIX + "§aセッション開始: §f" + sessionId);
        sender.sendMessage(PREFIX + "§7参加: §f/pvpf join " + gameId);
        return true;
    }

    private boolean handleGameStop(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "使い方: /pvpf game stop <session-id>");
            return true;
        }
        GameSession session = plugin.getSessionManager().getSession(args[2]);
        if (session == null) {
            sender.sendMessage(PREFIX + "§cセッションが見つかりません: " + args[2]);
            return true;
        }
        session.endGame("管理者による強制終了");
        sender.sendMessage(PREFIX + "§aセッションを終了しました: " + args[2]);
        return true;
    }

    private boolean handleGameStatus(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "使い方: /pvpf game status <session-id>");
            return true;
        }
        GameSession session = plugin.getSessionManager().getSession(args[2]);
        if (session == null) {
            sender.sendMessage(PREFIX + "§cセッションが見つかりません: " + args[2]);
            return true;
        }
        sender.sendMessage(PREFIX + "§eセッション: " + args[2]);
        sender.sendMessage("  状態: §f" + session.getState());
        sender.sendMessage("  ゲームID: §f" + session.getGameId());
        sender.sendMessage("  プレイヤー数: §f" + session.getPlayerCount());
        sender.sendMessage("  経過時間: §f" + session.getTimeElapsed() + "秒");
        if (session instanceof YamlGameSession ys) {
            sender.sendMessage("  モード: §f" + ys.getConfig().getMode());
        }
        return true;
    }

    // -------------------------------------------------------
    // template
    // -------------------------------------------------------
    private boolean handleTemplate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("pvpf.admin")) return true;
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "使い方: /pvpf template <list|copy>");
            return true;
        }
        return switch (args[1].toLowerCase()) {
            case "list" -> handleTemplateList(sender);
            case "copy" -> handleTemplateCopy(sender, args);
            default     -> { sender.sendMessage(PREFIX + "不明なサブコマンド: " + args[1]); yield true; }
        };
    }

    private boolean handleTemplateList(CommandSender sender) {
        List<String> templates = plugin.getTemplateManager().listTemplates();
        if (templates.isEmpty()) {
            sender.sendMessage(PREFIX + "§cテンプレートが見つかりません。");
            return true;
        }
        sender.sendMessage(PREFIX + "§eテンプレート一覧 (" + templates.size() + "):");
        for (String name : templates) {
            sender.sendMessage("  §7- §f" + name + " §7→ /pvpf template copy " + name + " <new-id>");
        }
        return true;
    }

    private boolean handleTemplateCopy(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(PREFIX + "使い方: /pvpf template copy <template-name> <new-game-id>");
            return true;
        }
        String newGameId = args[3];
        if (!newGameId.matches("[a-zA-Z0-9_\\-]+")) {
            sender.sendMessage(PREFIX + "§cゲームIDには英数字・_・- のみ使用できます。");
            return true;
        }
        if (plugin.getTemplateManager().gameFileExists(newGameId)) {
            sender.sendMessage(PREFIX + "§cgames/" + newGameId + ".yml はすでに存在します。");
            return true;
        }
        boolean ok = plugin.getTemplateManager().copyTemplate(args[2], newGameId);
        if (ok) {
            sender.sendMessage(PREFIX + "§a作成しました: §fgames/" + newGameId + ".yml");
            sender.sendMessage(PREFIX + "§7編集後 §f/pvpf reload §7で読み込んでください。");
        } else {
            sender.sendMessage(PREFIX + "§cコピーに失敗しました。§f/pvpf template list §cでテンプレート名を確認してください。");
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
            sender.sendMessage(PREFIX + "§cFancyNpcs がサーバーに導入されていません。");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(PREFIX + "使い方: /pvpf npc <create|createlobby|delete|teleport>");
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "create"      -> handleNpcCreate(sender, args);
            case "createlobby" -> handleNpcCreateLobby(sender, args);
            case "delete"      -> handleNpcDelete(sender, args);
            case "teleport"    -> handleNpcTeleport(sender, args);
            default            -> {
                sender.sendMessage(PREFIX + "不明なサブコマンド: " + args[1]);
                sender.sendMessage(PREFIX + "使い方: /pvpf npc <create|createlobby|delete|teleport>");
                yield true;
            }
        };
    }

    private boolean handleNpcCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cプレイヤーのみ使用できます（座標が必要なため）。");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(PREFIX + "使い方: /pvpf npc create <name> <game-id>");
            return true;
        }

        String name   = args[2];
        String gameId = args[3];

        // [要望] NPC名は日本語含む任意の文字列を許可。空文字のみ弾く。
        if (name.isBlank()) {
            sender.sendMessage(PREFIX + "§cNPC名を指定してください。");
            return true;
        }

        boolean ok = plugin.getFancyNpcManager().createNpc(name, gameId, player, null);
        if (ok) {
            sender.sendMessage(PREFIX + "§aNPC §f" + name + " §aを作成しました。");
            sender.sendMessage(PREFIX + "§aゲーム §f" + gameId + " §aにリンク済みです。");
            sender.sendMessage(PREFIX + "§7スキン・装備の変更は §f/npc skin <uid> <name|url> §7を使ってください。");
        }
        return true;
    }

    private boolean handleNpcCreateLobby(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cプレイヤーのみ使用できます（座標が必要なため）。");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "使い方: /pvpf npc createlobby <name>");
            return true;
        }

        String name = args[2];

        // [要望] NPC名は日本語含む任意の文字列を許可。空文字のみ弾く。
        if (name.isBlank()) {
            sender.sendMessage(PREFIX + "§cNPC名を指定してください。");
            return true;
        }

        boolean ok = plugin.getFancyNpcManager().createLobbyNpc(name, player, null);
        if (ok) {
            sender.sendMessage(PREFIX + "§aロビー返還NPC §f" + name + " §aを作成しました。");
            sender.sendMessage(PREFIX + "§7クリックすると §fglobal-lobby §7へテレポートします。");
        }
        return true;
    }

    private boolean handleNpcDelete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "使い方: /pvpf npc delete <name>");
            return true;
        }
        // 名前にスペースが含まれる場合（クォートなし）は args[2] 以降を結合
        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        boolean ok = plugin.getFancyNpcManager().deleteNpc(name);
        if (ok) {
            sender.sendMessage(PREFIX + "§aNPC §f" + name + " §aを削除しました（同名が複数ある場合は最も古いものを削除）。");
        } else {
            sender.sendMessage(PREFIX + "§cNPC §f" + name + " §cが見つかりません。");
        }
        return true;
    }

    private boolean handleNpcTeleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cプレイヤーのみ使用できます。");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "使い方: /pvpf npc teleport <name>");
            return true;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        boolean ok = plugin.getFancyNpcManager().teleportToNpc(name, player);
        if (ok) {
            sender.sendMessage(PREFIX + "§aNPC §f" + name + " §aの場所にテレポートしました。");
        } else {
            sender.sendMessage(PREFIX + "§cNPC §f" + name + " §cが見つかりません。");
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
        sender.sendMessage(PREFIX + "§eコマンド一覧:");
        sender.sendMessage("  §f/pvpf join <game-id> §7- ゲームに参加");
        sender.sendMessage("  §f/pvpf leave §7- ゲームから退出（アリーナ→待機場所→ロビーの2段階）");
        if (sender.hasPermission("pvpf.admin")) {
            sender.sendMessage("  §f/pvpf reload §7- 設定ファイルをリロード");
            sender.sendMessage("  §f/pvpf game list §7- ゲーム・セッション一覧");
            sender.sendMessage("  §f/pvpf game start <id> §7- ゲーム開始");
            sender.sendMessage("  §f/pvpf game stop <session> §7- セッション強制終了");
            sender.sendMessage("  §f/pvpf game status <session> §7- セッション詳細");
            sender.sendMessage("  §f/pvpf template list §7- テンプレート一覧");
            sender.sendMessage("  §f/pvpf template copy <tmpl> <id> §7- テンプレートをコピー");
            sender.sendMessage("  §f/pvpf npc create <name> <game-id> §7- ゲーム参加NPCを作成（日本語名OK、重複OK）");
            sender.sendMessage("  §f/pvpf npc createlobby <name> §7- ロビー返還NPCを作成");
            sender.sendMessage("  §f/pvpf npc delete <name> §7- NPCを削除（同名複数の場合は最古を削除）");
            sender.sendMessage("  §f/pvpf npc teleport <name> §7- NPCの場所へテレポート");
            sender.sendMessage("  §7※スキン等の詳細設定は §f/npc §7コマンドを使用してください。");
        }
    }
}
