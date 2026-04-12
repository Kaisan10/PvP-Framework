package pvp.framework.arena;

import pvp.framework.PvPFramework;
import pvp.framework.location.LocationResolver;
import pvp.framework.location.ResolvedArena;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * arenas/*.yml を読み込み、arena-ref: で参照できるようにするクラス。
 */
public class ArenaManager {

    private final PvPFramework plugin;
    private final Logger log;
    private final Map<String, ResolvedArena> arenas = new HashMap<>();

    public ArenaManager(PvPFramework plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void load() {
        arenas.clear();

        File arenasDir = new File(plugin.getDataFolder(), "arenas");
        if (!arenasDir.exists()) {
            arenasDir.mkdirs();
            log.info("Created arenas/ directory.");
            return;
        }

        File[] files = arenasDir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            log.info("No arena files found in arenas/.");
            return;
        }

        String defaultWorld = plugin.getConfigManager().getDefaultWorld();
        LocationResolver resolver = new LocationResolver(log, defaultWorld);

        for (File file : files) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            String id = cfg.getString("id");
            if (id == null) {
                log.warning("Arena file '" + file.getName() + "' is missing 'id'. Skipping.");
                continue;
            }

            ResolvedArena arena = resolver.resolveInline(cfg);
            if (arena == null) {
                log.warning("Failed to parse arena: " + file.getName());
                continue;
            }

            arenas.put(id, arena);
            log.info("Loaded arena: " + id + " (" + arena.getSpawns().size() + " spawns)");
        }

        log.info("ArenaManager loaded " + arenas.size() + " arena(s).");
    }

    /**
     * arena-ref: で指定されたIDからアリーナを取得する。
     */
    public ResolvedArena getArena(String id) {
        return arenas.get(id);
    }

    /** ホットリロード：マップをクリアして再読み込みする。 */
    public void reload() {
        log.info("ArenaManager reloading...");
        load();
    }

    public boolean hasArena(String id) {
        return arenas.containsKey(id);
    }

    public Map<String, ResolvedArena> getArenas() {
        return arenas;
    }
}
