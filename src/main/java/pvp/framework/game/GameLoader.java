package pvp.framework.game;

import pvp.framework.PvPFramework;
import pvp.framework.location.LocationResolver;
import pvp.framework.location.GameLocation;
import pvp.framework.location.ResolvedArena;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * games/*.yml をスキャンして GameConfig にパースし、IDで引けるようにするクラス。
 * reload() を呼ぶとマップをクリアして再スキャンする。
 * 実行中 GameSession は GameConfig への参照を直接持つため影響なし。
 */
public class GameLoader {

    private final PvPFramework plugin;
    private final Logger log;

    // gameId → GameConfig
    private final Map<String, GameConfig> configs = new HashMap<>();

    public GameLoader(PvPFramework plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    // -------------------------------------------------------
    // 読み込み
    // -------------------------------------------------------
    public void load() {
        configs.clear();

        File gamesDir = new File(plugin.getDataFolder(), "games");
        if (!gamesDir.exists()) {
            gamesDir.mkdirs();
            log.info("Created games/ directory.");
            return;
        }

        File[] files = gamesDir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            log.info("No game files found in games/.");
            return;
        }

        String defaultWorld = plugin.getConfigManager().getDefaultWorld();
        LocationResolver resolver = new LocationResolver(log, defaultWorld);

        for (File file : files) {
            try {
                GameConfig cfg = parseFile(file, resolver, defaultWorld);
                if (cfg != null) {
                    configs.put(cfg.getGameId(), cfg);
                    log.info("Loaded game: " + cfg.getGameId()
                            + " (mode=" + cfg.getMode() + ")");
                }
            } catch (Exception e) {
                log.warning("Failed to load game file '" + file.getName() + "': " + e.getMessage());
            }
        }

        log.info("GameLoader loaded " + configs.size() + " game(s).");
    }

    /**
     * reload() は既存マップをクリアして load() を実行する。
     *
     * [Bug②] reload後、古い GameConfig への参照を持つ WAITING セッションが
     * SessionManager に残っていると次の参加で古い設定が使われてしまう。
     * load() 完了後に WAITING セッションを破棄し、次回 joinOrQueue() で
     * 新しい GameConfig を持つセッションが生成されるようにする。
     */
    public void reload() {
        log.info("GameLoader reloading...");
        load();
        // reload後はWAITINGセッション（古いconfigを参照）を破棄する
        plugin.getSessionManager().invalidateWaitingSessions(configs.keySet());
    }

    // -------------------------------------------------------
    // パース
    // -------------------------------------------------------
    private GameConfig parseFile(File file, LocationResolver resolver, String defaultWorld) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        String gameId = cfg.getString("id");
        if (gameId == null || gameId.isBlank()) {
            log.warning("Game file '" + file.getName() + "' is missing 'id'. Skipping.");
            return null;
        }

        String displayName = cfg.getString("display-name", gameId);
        String mode        = cfg.getString("mode", "FFA").toUpperCase();

        int minPlayers = cfg.getInt("min-players", 2);
        int maxPlayers = cfg.getInt("max-players", 16);
        int countdown  = cfg.getInt("countdown", 30);

        // lobby
        GameLocation lobby = null;
        ConfigurationSection lobbySec = cfg.getConfigurationSection("lobby");
        if (lobbySec != null) {
            lobby = resolver.resolveLobby(lobbySec);
        }

        // arena (arena-ref が優先)
        ResolvedArena arena = null;
        String arenaRef = cfg.getString("arena-ref");
        if (arenaRef != null && !arenaRef.isBlank()) {
            arena = plugin.getArenaManager().getArena(arenaRef);
            if (arena == null) {
                log.warning("Game '" + gameId + "' references unknown arena-ref: " + arenaRef);
            }
        } else {
            ConfigurationSection arenaSec = cfg.getConfigurationSection("arena");
            if (arenaSec != null) {
                arena = resolver.resolveInline(arenaSec);
            }
        }

        if (arena == null) {
            log.warning("Game '" + gameId + "' has no valid arena definition. Skipping.");
            return null;
        }

        // kit
        String kitId       = cfg.getString("kit");
        String respawnKitId= cfg.getString("respawn-kit", kitId);

        // combat
        ConfigurationSection combat = cfg.getConfigurationSection("combat");
        boolean friendlyFire   = combat != null && combat.getBoolean("friendly-fire", false);
        boolean respawnEnabled = combat == null || combat.getBoolean("respawn", true);
        int respawnDelay       = combat != null ? combat.getInt("respawn-delay", 3) : 3;
        boolean allowBuilding  = combat != null && combat.getBoolean("allow-building", false);

        // LAST_STANDING/FREEPLAY は respawn 強制 false
        if ("LAST_STANDING".equals(mode) || "FREEPLAY".equals(mode)) {
            respawnEnabled = false;
        }

        // win-condition
        WinConditionConfig winCond = parseWinCondition(cfg, mode);

        // scoreboard
        ConfigurationSection sbSec = cfg.getConfigurationSection("scoreboard");
        String scoreboardTitle = sbSec != null ? sbSec.getString("title", "&b&lPvP") : "&b&lPvP";
        List<String> scoreboardLines = sbSec != null ? sbSec.getStringList("lines") : List.of();
        int scoreboardUpdateInterval = sbSec != null ? sbSec.getInt("update-interval", 2) : 2;

        // events
        Map<String, List<String>> rawEvents = parseRawEvents(cfg);

        return new GameConfig.Builder()
                .gameId(gameId)
                .displayName(displayName)
                .mode(mode)
                .minPlayers(minPlayers)
                .maxPlayers(maxPlayers)
                .countdown(countdown)
                .lobby(lobby)
                .arena(arena)
                .kitId(kitId)
                .respawnKitId(respawnKitId)
                .friendlyFire(friendlyFire)
                .respawnEnabled(respawnEnabled)
                .respawnDelay(respawnDelay)
                .allowBuilding(allowBuilding)
                .winCondition(winCond)
                .scoreboardTitle(scoreboardTitle)
                .scoreboardLines(scoreboardLines)
                .scoreboardUpdateInterval(scoreboardUpdateInterval)
                .rawEvents(rawEvents)
                .build();
    }

    private WinConditionConfig parseWinCondition(YamlConfiguration cfg, String mode) {
        ConfigurationSection sec = cfg.getConfigurationSection("win-condition");
        if (sec == null) return WinConditionConfig.defaults();

        String typeStr = sec.getString("type", "KILLS").toUpperCase();
        WinConditionConfig.Type type;
        try {
            type = WinConditionConfig.Type.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            log.warning("Unknown win-condition type: " + typeStr + ". Defaulting to KILLS.");
            type = WinConditionConfig.Type.KILLS;
        }

        // LAST_STANDING は強制
        if ("LAST_STANDING".equals(mode)) {
            type = WinConditionConfig.Type.LAST_STANDING;
        }
        // FREEPLAY は常に NONE
        if ("FREEPLAY".equals(mode)) {
            type = WinConditionConfig.Type.NONE;
        }

        int target    = sec.getInt("target", 20);
        int timeLimit = sec.getInt("time-limit", 300);
        return new WinConditionConfig(type, target, timeLimit);
    }

    /**
     * events: セクションを { "on-kill": ["give-item: ...", "send-message: ..."], ... } に変換。
     * アクションは YAML リストの文字列エントリまたは "key: value" 形式で記述される。
     */
    private Map<String, List<String>> parseRawEvents(YamlConfiguration cfg) {
        ConfigurationSection eventsSec = cfg.getConfigurationSection("events");
        if (eventsSec == null) return Map.of();

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String key : eventsSec.getKeys(false)) {
            List<String> actions = new ArrayList<>();
            List<?> list = eventsSec.getList(key);
            if (list != null) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        actions.add(s);
                    } else if (item instanceof Map<?, ?> m) {
                        // マップエントリ形式: { give-item: "GOLDEN_APPLE 1" } 等
                        m.forEach((k, v) -> actions.add(k + ": " + v));
                    }
                }
            }
            result.put(key, Collections.unmodifiableList(actions));
        }
        return Collections.unmodifiableMap(result);
    }

    // -------------------------------------------------------
    // 参照
    // -------------------------------------------------------
    public GameConfig getConfig(String gameId) {
        return configs.get(gameId);
    }

    public Map<String, GameConfig> getAll() {
        return Collections.unmodifiableMap(configs);
    }

    public boolean hasGame(String gameId) {
        return configs.containsKey(gameId);
    }
}