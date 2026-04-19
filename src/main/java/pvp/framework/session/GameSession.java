package pvp.framework.session;

import pvp.framework.PvPFramework;
import pvp.framework.location.GameLocation;
import pvp.framework.location.ResolvedArena;
import pvp.framework.mode.YamlGameSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.*;
import java.util.logging.Logger;

public class GameSession {

    protected final PvPFramework plugin;
    protected final Logger log;
    protected final String sessionId;
    protected final String gameId;

    protected GameState state = GameState.WAITING;
    protected final Set<UUID> players = new LinkedHashSet<>();

    private final Map<UUID, ItemStack[]> savedContents = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor    = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedExtra    = new HashMap<>();

    protected ResolvedArena arena;
    protected GameLocation lobby;

    protected int minPlayers;
    protected int maxPlayers;
    protected int countdownSeconds;

    protected int countdownTask = -1;
    protected int tickTask      = -1;

    protected int timeElapsed = 0;
    protected int timeLimit   = 0;

    private final List<Integer> spawnQueue = new ArrayList<>();

    public GameSession(PvPFramework plugin, String sessionId, String gameId) {
        this.plugin    = plugin;
        this.log       = plugin.getLogger();
        this.sessionId = sessionId;
        this.gameId    = gameId;
    }

    // -------------------------------------------------------
    // ライフサイクル
    // -------------------------------------------------------

    public boolean addPlayer(Player player) {
        if (state == GameState.ACTIVE && isJoinableInActive()) {
            if (players.contains(player.getUniqueId())) {
                if (this instanceof YamlGameSession ys) {
                    Location spawn = nextUniqueSpawn();
                    if (spawn != null) player.teleport(spawn);
                    giveLeaveCompass(player);
                }
                return true;
            }
            if (players.size() >= maxPlayers) return false;
            players.add(player.getUniqueId());
            saveAndClearInventory(player);
            onPlayerJoinActive(player);
            updateAllScoreboards();
            return true;
        }

        if (state != GameState.WAITING && state != GameState.COUNTDOWN) return false;
        if (players.size() >= maxPlayers) return false;
        if (players.contains(player.getUniqueId())) {
            if (lobby != null) {
                Location loc = lobby.toBukkitLocation();
                if (loc != null) player.teleport(loc);
            }
            return true;
        }

        players.add(player.getUniqueId());
        saveAndClearInventory(player);
        onPlayerJoin(player);

        if (lobby != null) {
            Location loc = lobby.toBukkitLocation();
            if (loc != null) player.teleport(loc);
        }

        giveMenuCompass(player);
        updateAllScoreboards();

        if (state == GameState.WAITING) {
            if (isFreeplay()) {
                startGame();
            } else if (players.size() >= minPlayers) {
                startCountdown();
            }
        }

        return true;
    }

    public void removePlayer(Player player) {
        players.remove(player.getUniqueId());
        removeMenuCompass(player);
        if (savedContents.containsKey(player.getUniqueId())) {
            player.getInventory().clear();
            restoreInventory(player);
        }
        onPlayerLeave(player);
        updateAllScoreboards();

        if (state == GameState.COUNTDOWN && players.size() < minPlayers) {
            cancelCountdown();
        }

        if (state == GameState.ACTIVE) {
            if (players.isEmpty() && !isFreeplay()) {
                endGame(plugin.getMessageManager().get("session.all-left"));
            } else if (players.size() == 1 && isEndOnLastPlayer()) {
                UUID lastUUID = players.iterator().next();
                Player last = Bukkit.getPlayer(lastUUID);
                endGame(last != null ? last.getName() : "???");
            }
        }
    }

    private void startCountdown() {
        setState(GameState.COUNTDOWN);
        final int[] remaining = {countdownSeconds};

        countdownTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (remaining[0] <= 0) {
                cancelCountdown();
                startGame();
                return;
            }
            onCountdown(remaining[0]);
            remaining[0]--;
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != -1) {
            Bukkit.getScheduler().cancelTask(countdownTask);
            countdownTask = -1;
        }
        if (state == GameState.COUNTDOWN) {
            setState(GameState.WAITING);
        }
    }

    private void startGame() {
        setState(GameState.ACTIVE);
        rebuildSpawnQueue();

        if (!isFreeplay()) {
            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                removeMenuCompass(p);
                Location spawn = nextUniqueSpawn();
                if (spawn != null) p.teleport(spawn);
                // [Fix] ゲーム開始時に体力・食料・満腹度を完全回復
                healPlayer(p);
            }
        } else {
            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) giveMenuCompass(p);
            }
        }

        onStart();

        timeElapsed = 0;
        tickTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            onTick();
            timeElapsed++;
            if (!isFreeplay() && timeLimit > 0 && timeElapsed >= timeLimit) {
                endGame(plugin.getMessageManager().get("session.time-up"));
            }
        }, 0L, 20L);
    }

    public void endGame(String reason) {
        if (state == GameState.ENDING || state == GameState.RESETTING) return;
        setState(GameState.ENDING);

        if (tickTask != -1) {
            Bukkit.getScheduler().cancelTask(tickTask);
            tickTask = -1;
        }

        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                new ArrayList<>(p.getActivePotionEffects())
                    .forEach(e -> p.removePotionEffect(e.getType()));
            }
        }

        onEnd(reason);

        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            setState(GameState.RESETTING);
            reset();
        }, 60L);
    }

    private void reset() {
        Location globalLobby = plugin.getConfigManager().getGlobalLobby();
        for (UUID uuid : new HashSet<>(players)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.getInventory().clear();
            new ArrayList<>(p.getActivePotionEffects())
                .forEach(e -> p.removePotionEffect(e.getType()));
            restoreInventory(p);
            plugin.getScoreboardManager().remove(p);
            if (globalLobby != null) p.teleport(globalLobby);
        }
        players.clear();
        timeElapsed = 0;
        spawnQueue.clear();

        setState(GameState.WAITING);
        plugin.getSessionManager().onSessionReset(this);
        onReset();
    }

    // -------------------------------------------------------
    // [Fix] 体力・食料・満腹度を最大に回復するユーティリティ
    // GameSession 内外から使えるよう protected static で公開
    // -------------------------------------------------------
    protected static void healPlayer(Player p) {
        var attr = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) p.setHealth(attr.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
    }

    // -------------------------------------------------------
    // スポーン重複防止
    // -------------------------------------------------------

    private void rebuildSpawnQueue() {
        spawnQueue.clear();
        if (arena == null) return;
        int count = arena.getSpawnCount();
        for (int i = 0; i < count; i++) spawnQueue.add(i);
        Collections.shuffle(spawnQueue);
    }

    public Location nextUniqueSpawn() {
        if (arena == null) return null;
        try {
            if (spawnQueue.isEmpty()) rebuildSpawnQueue();
            if (spawnQueue.isEmpty()) return arena.nextSpawn();
            int idx = spawnQueue.remove(0);
            Location loc = arena.getSpawn(idx);
            return loc != null ? loc : arena.nextSpawn();
        } catch (Exception e) {
            return arena.nextSpawn();
        }
    }

    // -------------------------------------------------------
    // リスポーン処理
    // -------------------------------------------------------
    public void respawnPlayer(Player player) {
        if (isFreeplay()) {
            Location lobbyLoc = null;
            if (lobby != null) lobbyLoc = lobby.toBukkitLocation();
            if (lobbyLoc == null) lobbyLoc = plugin.getConfigManager().getGlobalLobby();
            if (lobbyLoc != null) {
                player.teleport(lobbyLoc);
                removeLeaveCompass(player);
                giveMenuCompass(player);
            }
        } else {
            Location spawn = nextUniqueSpawn();
            if (spawn != null) player.teleport(spawn);
        }
    }

    // -------------------------------------------------------
    // インベントリ保存 / 復元
    // -------------------------------------------------------
    private void saveAndClearInventory(Player player) {
        savedContents.put(player.getUniqueId(), player.getInventory().getContents().clone());
        savedArmor.put(player.getUniqueId(),    player.getInventory().getArmorContents().clone());
        savedExtra.put(player.getUniqueId(),    player.getInventory().getExtraContents().clone());
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setExtraContents(new ItemStack[1]);
    }

    // [Fix] YamlGameSession.onReset() からスペクテイターのインベントリを復元できるよう protected に変更
    protected void restoreInventory(Player player) {
        UUID uuid = player.getUniqueId();
        ItemStack[] contents = savedContents.remove(uuid);
        ItemStack[] armor    = savedArmor.remove(uuid);
        ItemStack[] extra    = savedExtra.remove(uuid);
        if (contents != null) player.getInventory().setContents(contents);
        if (armor    != null) player.getInventory().setArmorContents(armor);
        if (extra    != null) player.getInventory().setExtraContents(extra);
    }

    // -------------------------------------------------------
    // サブクラス向けフック
    // -------------------------------------------------------
    protected boolean isJoinableInActive()            { return false; }
    protected boolean isEndOnLastPlayer()             { return true; }
    protected void onPlayerJoinActive(Player player)  {}
    protected void onReset()                          {}

    protected void updateAllScoreboards() {
        if (this instanceof YamlGameSession ys) {
            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) plugin.getScoreboardManager().update(p, ys);
            }
        }
    }

    public boolean isFreeplay() {
        if (this instanceof YamlGameSession ys) {
            return "FREEPLAY".equals(ys.getConfig().getMode());
        }
        return false;
    }

    // -------------------------------------------------------
    // コンパスアイテム
    // -------------------------------------------------------
    public static final NamespacedKey LEAVE_COMPASS_KEY =
            new NamespacedKey("pvpframework", "freeplay_leave");

    public static void giveLeaveCompass(Player player) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            String name = PvPFramework.getInstance() != null
                    ? PvPFramework.getInstance().getMessageManager().get("item.leave-compass-name")
                    : "&c&l退出";
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
            meta.getPersistentDataContainer().set(
                    LEAVE_COMPASS_KEY, PersistentDataType.BYTE, (byte) 1);
            compass.setItemMeta(meta);
        }
        player.getInventory().setItem(8, compass);
    }

    public static void removeLeaveCompass(Player player) {
        ItemStack item = player.getInventory().getItem(8);
        if (item != null && item.hasItemMeta()) {
            if (item.getItemMeta().getPersistentDataContainer()
                    .has(LEAVE_COMPASS_KEY, PersistentDataType.BYTE)) {
                player.getInventory().setItem(8, null);
            }
        }
    }

    public static void giveMenuCompass(Player player) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            String name = PvPFramework.getInstance() != null
                    ? PvPFramework.getInstance().getMessageManager().get("item.menu-compass-name")
                    : "&b&lゲームメニュー";
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
            meta.getPersistentDataContainer().set(
                    new NamespacedKey("pvpframework", "lobby_menu"),
                    PersistentDataType.BYTE, (byte) 1);
            compass.setItemMeta(meta);
        }
        player.getInventory().setItem(4, compass);
    }

    public static void removeMenuCompass(Player player) {
        ItemStack item = player.getInventory().getItem(4);
        if (item != null && item.hasItemMeta()) {
            if (item.getItemMeta().getPersistentDataContainer()
                    .has(new NamespacedKey("pvpframework", "lobby_menu"), PersistentDataType.BYTE)) {
                player.getInventory().setItem(4, null);
            }
        }
    }

    public boolean isPlayerInArena(Player player) {
        ItemStack item = player.getInventory().getItem(8);
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(LEAVE_COMPASS_KEY, PersistentDataType.BYTE);
    }

    // -------------------------------------------------------
    // オーバーライドポイント
    // -------------------------------------------------------
    protected void onPlayerJoin(Player player)  {}
    protected void onPlayerLeave(Player player) {}
    protected void onCountdown(int remaining)   {}
    protected void onStart()                    {}
    protected void onTick()                     {}
    protected void onEnd(String reason)         {}

    protected void setState(GameState newState) {
        log.info("[" + sessionId + "] " + state + " → " + newState);
        this.state = newState;
    }

    public String getSessionId()     { return sessionId; }
    public String getGameId()        { return gameId; }
    public GameState getState()      { return state; }
    public Set<UUID> getPlayers()    { return Collections.unmodifiableSet(players); }
    public int getPlayerCount()      { return players.size(); }
    public int getMinPlayers()       { return minPlayers; }
    public int getMaxPlayers()       { return maxPlayers; }
    public int getTimeElapsed()      { return timeElapsed; }
    public int getTimeLimit()        { return timeLimit; }

    public void setArena(ResolvedArena arena)    { this.arena = arena; }
    public void setLobby(GameLocation lobby)     { this.lobby = lobby; }
    public void setMinPlayers(int min)           { this.minPlayers = min; }
    public void setMaxPlayers(int max)           { this.maxPlayers = max; }
    public void setCountdownSeconds(int seconds) { this.countdownSeconds = seconds; }
    public void setTimeLimit(int seconds)        { this.timeLimit = seconds; }
    public ResolvedArena getArena()              { return arena; }
}