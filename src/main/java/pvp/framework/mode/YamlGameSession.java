package pvp.framework.mode;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;
import pvp.framework.script.ActionContext;
import pvp.framework.script.ScriptEngine;
import pvp.framework.session.GameSession;
import pvp.framework.wincondition.WinConditionEvaluator;
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
            Player p = org.bukkit.Bukkit.getPlayer(uuid);
            if (p != null) scriptEngine.fire("on-countdown", this, p, ctx);
        }
    }

    @Override
    protected void onStart() {
        // キット配布
        if (config.getKitId() != null) {
            for (UUID uuid : players) {
                Player p = org.bukkit.Bukkit.getPlayer(uuid);
                if (p != null) plugin.getKitManager().give(p, config.getKitId());
            }
        }

        // FREEPLAYは「ゲーム開始」という概念がないため on-game-start を発火しない
        if ("FREEPLAY".equals(config.getMode())) {
            for (UUID uuid : players) {
                Player p = org.bukkit.Bukkit.getPlayer(uuid);
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
                org.bukkit.Location loc = lobby.toBukkitLocation();
                if (loc != null) player.teleport(loc);
            }
            GameSession.giveMenuCompass(player);
        } else {
            if (arena != null) {
                org.bukkit.Location spawn = nextUniqueSpawn();
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
        Map<String, Object> ctx = buildSessionCtx();
        ctx.put("winner", reason);
        players.stream()
            .map(uuid -> org.bukkit.Bukkit.getPlayer(uuid))
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
        kills.clear();
        deaths.clear();
        scores.clear();
        variables.clear();
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
    }

    public void onPlayerDeath(Player victim) {
        deaths.merge(victim.getUniqueId(), 1, Integer::sum);
        Map<String, Object> victimCtx = buildPlayerCtx(victim);
        victimCtx.put("killer", "none");
        scriptEngine.fire("on-death", this, victim, victimCtx);
    }

    // -------------------------------------------------------
    // アリーナ入場（FREEPLAY用）
    // メニューGUIからアリーナへTPするときに呼ばれる。
    // ここで入場メッセージ・on-arena-enter イベントを発火する。
    // -------------------------------------------------------
    public void teleportToArena(Player player) {
        if (arena != null) {
            org.bukkit.Location spawn = nextUniqueSpawn();
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
