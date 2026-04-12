package pvp.framework.location;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * YAMLの lobby: / arena: / arena-ref: セクションを
 * GameLocation / ResolvedArena オブジェクトにパースするクラス。
 */
public class LocationResolver {

    private final Logger log;
    private final String defaultWorld;

    public LocationResolver(Logger log, String defaultWorld) {
        this.log = log;
        this.defaultWorld = defaultWorld;
    }

    // -------------------------------------------------------
    // lobby: セクションをパース
    // -------------------------------------------------------
    public GameLocation resolveLobby(ConfigurationSection section) {
        if (section == null) return null;
        return parseGameLocation(section, defaultWorld);
    }

    // -------------------------------------------------------
    // arena: インラインブロックをパース
    // -------------------------------------------------------
    public ResolvedArena resolveInline(ConfigurationSection arenaSection) {
        if (arenaSection == null) return null;

        String world = arenaSection.getString("world", defaultWorld);

        // spawns
        // NOTE: Bukkit の getList() はインラインマップを LinkedHashMap で返すため
        //       ConfigurationSection への instanceof キャストは常に false になる。
        //       getMapList() で Map<?,?> として受け取るのが正しい。
        List<GameLocation> spawns = new ArrayList<>();
        if (arenaSection.isList("spawns")) {
            for (Map<?, ?> map : arenaSection.getMapList("spawns")) {
                spawns.add(parseSpawnFromMap(map, world));
            }
        }

        if (spawns.isEmpty()) {
            log.warning("arena.spawns is empty or missing.");
        }

        // bounds（省略可）
        GameLocation boundsMin = null;
        GameLocation boundsMax = null;
        ConfigurationSection bounds = arenaSection.getConfigurationSection("bounds");
        if (bounds != null) {
            ConfigurationSection minSec = bounds.getConfigurationSection("min");
            ConfigurationSection maxSec = bounds.getConfigurationSection("max");
            if (minSec != null) boundsMin = parseGameLocation(minSec, world);
            if (maxSec != null) boundsMax = parseGameLocation(maxSec, world);
        }

        return new ResolvedArena(world, spawns, boundsMin, boundsMax);
    }

    // -------------------------------------------------------
    // 共通：ConfigurationSection → GameLocation
    // -------------------------------------------------------
    private GameLocation parseGameLocation(ConfigurationSection sec, String fallbackWorld) {
        String world = sec.getString("world", fallbackWorld);
        double x     = sec.getDouble("x", 0.5);
        double y     = sec.getDouble("y", 64.0);
        double z     = sec.getDouble("z", 0.5);
        float  yaw   = (float) sec.getDouble("yaw", 0.0);
        float  pitch = (float) sec.getDouble("pitch", 0.0);
        return new GameLocation(world, x, y, z, yaw, pitch);
    }

    // spawns リスト内の1エントリをパース（world は親から継承）
    // getMapList() で得た Map<?,?> を使う版
    private GameLocation parseSpawnFromMap(Map<?, ?> map, String inheritedWorld) {
        String world = getStr(map, "world", inheritedWorld);
        double x     = getDouble(map, "x", 0.5);
        double y     = getDouble(map, "y", 64.0);
        double z     = getDouble(map, "z", 0.5);
        float  yaw   = (float) getDouble(map, "yaw", 0.0);
        float  pitch = (float) getDouble(map, "pitch", 0.0);
        return new GameLocation(world, x, y, z, yaw, pitch);
    }

    private static String getStr(Map<?, ?> map, String key, String def) {
        Object v = map.get(key);
        return v instanceof String s ? s : def;
    }

    private static double getDouble(Map<?, ?> map, String key, double def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
