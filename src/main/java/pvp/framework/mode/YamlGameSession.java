package pvp.framework.mode;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;
import pvp.framework.script.ActionContext;
import pvp.framework.script.ScriptEngine;
import pvp.framework.session.GameSession;
import pvp.framework.session.GameState;
import pvp.framework.wincondition.WinConditionEvaluator;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.*;

public class YamlGameSession extends GameSession {

    private static final String PREFIX = "§b[PvPF] §r";

    private final GameConfig config;
    private final ScriptEngine scriptEngine;
    private final WinConditionEvaluator winEval;

    private final Map<UUID, Integer> kills  = new HashMap<>();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<String, String> variables = new HashMap<>();

    // [Fix Bug①] LAST_STANDING: 死亡後スペクテイターになったプレイヤー（players から除去済み）
    private final Set<UUID> spectators = new LinkedHashSet<>();

    // [Fix TDM] チーム割り当て: UUID → "RED" | "BLUE"
    private final Map<UUID, String> teamMap = new HashMap<>();

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
        // スペクテイターが退出した場合もここで除去
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

        if ("FREEPLAY".equals(config.getMode())) {
            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) GameSession.giveMenuCompass(p);
            }
            updateAllScoreboards();
            return;
        }

        // [Fix TDM] チーム割り当てと革ヘルメット配布（キット配布の後に行うことでヘルメットを上書き）
        if ("TDM".equals(config.getMode())) {
            assignTeams();
            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) giveTeamHelmet(p);
            }
        }

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
        // スペクテイターのポーションエフェクトも除去
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
        // スペクテイターをSURVIVALに戻してグローバルロビーへ
        Location globalLobby = plugin.getConfigManager().getGlobalLobby();
        for (UUID uuid : new HashSet<>(spectators)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.setGameMode(GameMode.SURVIVAL);
                p.getInventory().clear();
                restoreInventory(p);
                plugin.getScoreboardManager().remove(p);
                if (globalLobby != null) p.teleport(globalLobby);
            }
        }
        spectators.clear();
        teamMap.clear();

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
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) plugin.getScoreboardManager().update(p, this);
        }
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

        if ("LAST_STANDING".equals(config.getMode())) {
            makeSpectator(victim);
        }
    }

    public void onPlayerDeath(Player victim) {
        deaths.merge(victim.getUniqueId(), 1, Integer::sum);
        Map<String, Object> victimCtx = buildPlayerCtx(victim);
        victimCtx.put("killer", "none");
        scriptEngine.fire("on-death", this, victim, victimCtx);

        if ("LAST_STANDING".equals(config.getMode())) {
            makeSpectator(victim);
        }
    }

    /**
     * [Fix Bug①] プレイヤーを生存者から除外しスペクテイターに移行する。
     * players から除去し、spectators に追加。
     * 強制リスポーン後に StateValidator.onRespawn() で SPECTATOR モードが設定される。
     */
    private void makeSpectator(Player victim) {
        if (!players.contains(victim.getUniqueId())) return; // 二重呼び出し防止

        players.remove(victim.getUniqueId());
        spectators.add(victim.getUniqueId());

        victim.sendMessage(plugin.getMessageManager().get("session.became-spectator"));

        // 死亡画面から強制リスポーン → StateValidator.onRespawn() でSPECTATORモードに切り替え
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            if (victim.isOnline() && victim.isDead()) victim.spigot().respawn();
        }, 1L);

        updateAllScoreboards();

        // 勝利判定
        if (getState() == GameState.ACTIVE) {
            if (players.size() == 1) {
                UUID lastUUID = players.iterator().next();
                Player last = Bukkit.getPlayer(lastUUID);
                endGame(last != null ? last.getName() : "???");
            } else if (players.isEmpty()) {
                endGame(plugin.getMessageManager().get("session.tie"));
            }
        }
    }

    // -------------------------------------------------------
    // アリーナ入場（FREEPLAY用）
    // -------------------------------------------------------
    public void teleportToArena(Player player) {
        if (arena != null) {
            Location spawn = nextUniqueSpawn();
            if (spawn != null) player.teleport(spawn);
        }
        player.sendMessage(plugin.getMessageManager().getPrefixed("session.arena-entered", "displayName", config.getDisplayName()));
        scriptEngine.fire("on-arena-enter", this, player, buildPlayerCtx(player));
    }

    // -------------------------------------------------------
    // [Fix TDM] チーム管理
    // -------------------------------------------------------

    /** ゲーム開始時に全プレイヤーをRED/BLUEに均等割り当て */
    private void assignTeams() {
        List<UUID> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        teamMap.clear();
        for (int i = 0; i < shuffled.size(); i++) {
            teamMap.put(shuffled.get(i), i % 2 == 0 ? "RED" : "BLUE");
        }
    }

    /** チームカラーに染めた革ヘルメットを装備させる */
    public void giveTeamHelmet(Player player) {
        String team = teamMap.getOrDefault(player.getUniqueId(), "RED");
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        LeatherArmorMeta meta = (LeatherArmorMeta) helmet.getItemMeta();
        if (meta != null) {
            // RED: 赤系、BLUE: 青系
            meta.setColor("RED".equals(team)
                    ? Color.fromRGB(255, 85, 85)
                    : Color.fromRGB(85, 85, 255));
            helmet.setItemMeta(meta);
        }
        player.getInventory().setHelmet(helmet);
    }

    /** 攻撃者と被攻撃者が同じチームかどうかを返す（TDMフレンドリーファイア判定用） */
    public boolean isSameTeam(Player p1, Player p2) {
        String t1 = teamMap.get(p1.getUniqueId());
        String t2 = teamMap.get(p2.getUniqueId());
        return t1 != null && t1.equals(t2);
    }

    /** プレイヤーがスペクテイター（LAST_STANDING脱落済み）かどうかを返す */
    public boolean isSpectator(Player player) {
        return spectators.contains(player.getUniqueId());
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

        // [Fix TDM] チーム情報をスコアボードプレースホルダーとして追加
        if ("TDM".equals(config.getMode())) {
            String team = teamMap.getOrDefault(uuid, "");
            ctx.put("team", team);
            // {team_color} → スコアボードで色付き表示するための &c / &9
            ctx.put("team_color", "RED".equals(team) ? "&cREDチーム" : "&9BLUEチーム");
        }

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