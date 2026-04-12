package pvp.framework.kit;

import pvp.framework.PvPFramework;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class KitManager {

    private final PvPFramework plugin;
    private final Logger log;
    private final Map<String, Kit> kits = new HashMap<>();

    public KitManager(PvPFramework plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void load() {
        kits.clear();

        File kitsDir = new File(plugin.getDataFolder(), "kits");
        if (!kitsDir.exists()) {
            kitsDir.mkdirs();
            plugin.saveResource("kits/warrior.yml", false);
        }

        File[] files = kitsDir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            Kit kit = Kit.fromConfig(cfg, file.getName(), log);
            if (kit != null) {
                kits.put(kit.getId(), kit);
                log.info("Loaded kit: " + kit.getId());
            }
        }

        log.info("KitManager loaded " + kits.size() + " kit(s).");
    }

    public boolean give(Player player, String kitId) {
        Kit kit = kits.get(kitId);
        if (kit == null) {
            log.warning("Kit not found: " + kitId);
            return false;
        }
        kit.apply(player);
        return true;
    }

    public boolean hasKit(String kitId) {
        return kits.containsKey(kitId);
    }

    public Map<String, Kit> getKits() {
        return kits;
    }
}
