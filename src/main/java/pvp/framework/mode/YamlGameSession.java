package pvp.framework.mode;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;
import pvp.framework.script.ActionContext;
import pvp.framework.script.ScriptEngine;
import pvp.framework.session.GameSession;
import pvp.framework.session.GameState;
import pvp.framework.wincondition.WinConditionEvaluator;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * games/*.yml 1ファイルで動くセッション。
 */
public class YamlGameSession extends GameSession {

    private static final String PREFIX = "§b[PvPF] §r";

    private final GameConfig config;
    private final ScriptEngine scriptEngine;
    private final WinConditionEvaluator winEval;

    private final Map<UUID, Integer> kills  = new HashMap<>();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<String, String> variables = new HashMap<>();

    // [Bug①] LAST_STANDING: 死亡後にスペクテイターとなったプレイヤーを管理するセット
    // players セット（生存者）とは別に保持し、getPlayerCount() は生存者のみを返す。
    private final Set<UUID> spectators = new LinkedHashSet<>();

    public YamlGameSession(PvPFramework plugin,
                           String sessionId,
                           GameConfig config,
                           ScriptEngine scriptEngine) {
        super(plugin, sessionId, config.getGameId());

        this.config       = config;
        this.scriptEngine = scriptEngine;
        this.winEval      = new WinConditionEvaluator(config.getWinCondition());

        setArena(config.getArena());
        setLobby(config.getLobby());
        setMinPlayers(config.getMinPlayers());
        setMaxPlayers(config.getMaxPlayers());
        setCountdownSeconds(config.getCountdown());
        setTimeLimit(config.getWinCondition().getTimeLimit());
    }

    // -------------------------------------------------------
    // ライフサイクル
    // -------------------------------------------------------

    @Override
    protected void onPlayerJoin(Player player) {
        kills.put(player.getUniqueId(), 0);
        deaths.put(player.getUniqueId(), 0);
        scores.put(player.getUniqueId(), 0);

        scriptEngine.fire("on-player-join", this, player, buildPlayerCtx(player));
        plugin.getScoreboardManager().update(player, this);
    }

    @Override
    protected void onPlayerLeave(Player player) {
        // [Bug①] スペクテイターが退出した場合もここで除去する
        spectators.remove(player.getUniqueId());

        scriptEngine.fire("on-player-leave", this, player, buildPlayerCtx(player));
        kills.remove(player.getUniqueId());
        deaths.remove(player.getUniqueId());
        scores.remove(player.getUniqueId());
        plugin.getScoreboardManager().remove(player);
    }

    @Override
    protected void onCountdown(int remaining) {
        Map<String, Object> ctx = buildSessionCtx();
        ctx.put("countdown", remaining);
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) scriptEngine.fire("on-countdown", this, p, ctx);
        }
    }

    @Override
    protected void onStart() {
        // キット配布
        if (config.getKitId() != null) {
            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) plugin.getKitManager().give(p, config.getKitId());
            }
        }

        // FREEPLAYは「ゲーム開始」という概念がないため on-game-start を発火しない
        if ("FREEPLAY".equals(config.getMode())) {
            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) GameSession.giveMenuCompass(p);
            }
            updateAllScoreboards();
            return;
        }

        // 通常モード
        scriptEngine.fire("on-game-start", this, null, buildSessionCtx());
        updateAllScoreboards();
    }

    @Override
    protected boolean isJoinableInActive() {
        return "FREEPLAY".equals(config.getMode());
    }

    @Override
    protected boolean isEndOnLastPlayer() {
        return !"FREEPLAY".equals(config.getMode());
    }

    @Override
    protected void onPlayerJoinActive(Player player) {
        kills.put(player.getUniqueId(), 0);
        deaths.put(player.getUniqueId(), 0);
        scores.put(player.getUniqueId(), 0);

        if (isFreeplay()) {
            // FREEPLAY: ロビーへ（アリーナへはメニューコンパスで移動）
            if (lobby != null) {
                Location loc = lobby.toBukkitLocation();
                if (loc != null) player.teleport(loc);
            }
            GameSession.giveMenuCompass(player);
        } else {
            if (arena != null) {
                Location spawn = nextUniqueSpawn();
                if (spawn != null) player.teleport(spawn);
            }
            if (config.getKitId() != null) plugin.getKitManager().give(player, config.getKitId());
            GameSession.giveLeaveCompass(player);
        }

        scriptEngine.fire("on-player-join", this, player, buildPlayerCtx(player));
        plugin.getScoreboardManager().update(player, this);
    }

    @Override
    protected void onTick() {
        int interval = Math.max(1, config.getScoreboardUpdateInterval());
        if (timeElapsed % interval == 0) {
            updateAllScoreboards();
        }
        winEval.check(this).ifPresent(winner -> {
            Map<String, Object> ctx = buildSessionCtx();
            ctx.put("winner", winner);
            endGame(winner);
        });
    }

    @Override
    protected void onEnd(String reason) {
        // [Bug①] スペクテイターのポーションエフェクトも除去する
        for (UUID uuid : spectators) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                new ArrayList<>(p.getActivePotionEffects())
                    .forEach(e -> p.removePotionEffect(e.getType()));
            }
        }

        Map<String, Object> ctx = buildSessionCtx();
        ctx.put("winner", reason);
        players.stream()
            .map(uuid -> Bukkit.getPlayer(uuid))
            .filter(p -> p != null && p.getName().equals(reason))
            .findFirst()
            .ifPresent(p -> {
                ctx.put("kills",  getKills(p.getUniqueId()));
                ctx.put("deaths", getDeaths(p.getUniqueId()));
            });
        ctx.putIfAbsent("kills", 0);
        ctx.putIfAbsent("deaths", 0);

        scriptEngine.fire("on-game-end", this, null, ctx);
        updateAllScoreboards();
    }

    @Override
    protected void onReset() {
        // [Bug①] スペクテイターをゲームモード復元・グローバルロビーへテレポートしてクリーンアップ
        Location globalLobby = plugin.getConfigManager().getGlobalLobby();
        for (UUID uuid : new HashSet<>(spectators)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.setGameMode(GameMode.SURVIVAL);
                p.getInventory().clear();
                restoreInventory(p);  // GameSession.restoreInventory (protected)
                plugin.getScoreboardManager().remove(p);
                if (globalLobby != null) p.teleport(globalLobby);
            }
        }
        spectators.clear();

        kills.clear();
        deaths.clear();
        scores.clear();
        variables.clear();
    }

    // -------------------------------------------------------
    // スコアボード（spectators にも配信）
    // -------------------------------------------------------

    @Override
    protected void updateAllScoreboards() {
        // 生存プレイヤー
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) plugin.getScoreboardManager().update(p, this);
        }
        // スペクテイター（観戦中も残り人数・キル等を表示）
        for (UUID uuid : spectators) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) plugin.getScoreboardManager().update(p, this);
        }
    }

    // -------------------------------------------------------
    // キル・デス処理
    // -------------------------------------------------------
    public void onPlayerKill(Player killer, Player victim) {
        kills.merge(killer.getUniqueId(), 1, Integer::sum);
        deaths.merge(victim.getUniqueId(), 1, Integer::sum);

        Map<String, Object> killerCtx = buildPlayerCtx(killer);
        killerCtx.put("victim", victim.getName());
        scriptEngine.fire("on-kill", this, killer, killerCtx);

        Map<String, Object> victimCtx = buildPlayerCtx(victim);
        victimCtx.put("killer", killer.getName());
        scriptEngine.fire("on-death", this, victim, victimCtx);

        // [Bug①] LAST_STANDING: 死亡者をスペクテイターに移行する
        if ("LAST_STANDING".equals(config.getMode())) {
            makeSpectator(victim);
        }
    }

    public void onPlayerDeath(Player victim) {
        deaths.merge(victim.getUniqueId(), 1, Integer::sum);
        Map<String, Object> victimCtx = buildPlayerCtx(victim);
        victimCtx.put("killer", "none");
        scriptEngine.fire("on-death", this, victim, victimCtx);

        // [Bug①] LAST_STANDING: 死亡者をスペクテイターに移行する
        if ("LAST_STANDING".equals(config.getMode())) {
            makeSpectator(victim);
        }
    }

    /**
     * [Bug①] プレイヤーを生存者から除外しスペクテイターとして扱う。
     * - players から除去（getPlayerCount() が生存者数のみを返すようになる）
     * - spectators に追加
     * - キットをクリア、スペクテイターモードへ変更、アリーナ内にTP（観戦）
     * - 残り1人になった場合は即座に endGame() を呼ぶ
     */
    private void makeSpectator(Player victim) {
        // players から除去 → getPlayerCount() が生存者のみを返す
        players.remove(victim.getUniqueId());
        spectators.add(victim.getUniqueId());

        // キット・エフェクトをクリア
        victim.getInventory().clear();
        new ArrayList<>(victim.getActivePotionEffects())
            .forEach(e -> victim.removePotionEffect(e.getType()));
        GameSession.removeLeaveCompass(victim);

        // スペクテイターモード＆アリーナ内TPで観戦
        victim.setGameMode(GameMode.SPECTATOR);
        Location arenaSpawn = nextUniqueSpawn();
        if (arenaSpawn != null) victim.teleport(arenaSpawn);

        victim.sendMessage("§c§lあなたは脱落しました。スペクテイターとして観戦します。");

        // スコアボードを全員に更新
        updateAllScoreboards();

        // 勝利判定（endGame の二重呼び出しは GameSession 側でガード済み）
        if (getState() == GameState.ACTIVE) {
            if (players.size() == 1) {
                UUID lastUUID = players.iterator().next();
                Player last = Bukkit.getPlayer(lastUUID);
                endGame(last != null ? last.getName() : "???");
            } else if (players.isEmpty()) {
                endGame("引き分け");
            }
        }
    }

    // -------------------------------------------------------
    // アリーナ入場（FREEPLAY用）
    // メニューGUIからアリーナへTPするときに呼ばれる。
    // ここで入場メッセージ・on-arena-enter イベントを発火する。
    // -------------------------------------------------------
    public void teleportToArena(Player player) {
        if (arena != null) {
            Location spawn = nextUniqueSpawn();
            if (spawn != null) player.teleport(spawn);
        }
        // 入場メッセージ（FREEPLAY の「ゲームに参加しました」はここで出す）
        player.sendMessage(PREFIX + "§a§f" + config.getDisplayName() + " §aのアリーナに入場しました！");

        // on-arena-enter スクリプトイベント
        scriptEngine.fire("on-arena-enter", this, player, buildPlayerCtx(player));
    }

    // -------------------------------------------------------
    // 統計
    // -------------------------------------------------------
    public int getKills(UUID uuid)  { return kills.getOrDefault(uuid, 0); }
    public int getDeaths(UUID uuid) { return deaths.getOrDefault(uuid, 0); }
    public int getScore(UUID uuid)  { return scores.getOrDefault(uuid, 0); }

    public void addScore(Player player, int amount) {
        scores.merge(player.getUniqueId(), amount, Integer::sum);
    }

    // -------------------------------------------------------
    // セッション変数
    // -------------------------------------------------------
    public void setVariable(String key, String value) { variables.put(key, value); }
    public String getVariable(String key)              { return variables.getOrDefault(key, ""); }

    // -------------------------------------------------------
    // コンテキスト構築
    // -------------------------------------------------------
    private Map<String, Object> buildPlayerCtx(Player player) {
        Map<String, Object> ctx = new HashMap<>(buildSessionCtx());
        UUID uuid = player.getUniqueId();
        int k = getKills(uuid);
        int d = getDeaths(uuid);
        ctx.put("kills",  k);
        ctx.put("deaths", d);
        ctx.put("kdr",    d == 0 ? (float) k : Math.round((float) k / d * 10) / 10.0f);
        ctx.put("score",  getScore(uuid));
        ctx.put("player", player.getName());
        ctx.putAll(variables);
        return ctx;
    }

    public Map<String, Object> buildCtx(Player player) { return buildPlayerCtx(player); }
    public Map<String, Object> buildCtx()              { return buildSessionCtx(); }

    private Map<String, Object> buildSessionCtx() {
        Map<String, Object> ctx = new HashMap<>();
        int remaining = timeLimit > 0 ? Math.max(0, timeLimit - timeElapsed) : 0;
        ctx.put("time_left",    timeLimit > 0 ? ActionContext.formatTime(remaining) : "∞");
        ctx.put("elapsed",      ActionContext.formatTime(timeElapsed));
        ctx.put("player_count", getPlayerCount());
        return ctx;
    }

    // -------------------------------------------------------
    // getter
    // -------------------------------------------------------
    public GameConfig getConfig()   { return config; }
    public PvPFramework getPlugin() { return plugin; }
}