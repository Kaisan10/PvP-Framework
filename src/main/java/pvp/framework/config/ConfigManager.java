package pvp.framework.config;

import pvp.framework.PvPFramework;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    private final PvPFramework plugin;

    private String defaultWorld;
    private Location globalLobby;

    public ConfigManager(PvPFramework plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        FileConfiguration cfg = plugin.getConfig();

        defaultWorld = cfg.getString("default-world", "world");

        globalLobby = parseLocation(cfg, "global-lobby", defaultWorld);

        plugin.getLogger().info("ConfigManager loaded. default-world=" + defaultWorld);
    }

    private Location parseLocation(FileConfiguration cfg, String path, String fallbackWorld) {
        String worldName = cfg.getString(path + ".world", fallbackWorld);
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            plugin.getLogger().warning("World '" + worldName + "' not found for '" + path + "'. Using first available world.");
            world = Bukkit.getWorlds().get(0);
        }

        double x     = cfg.getDouble(path + ".x", 0.5);
        double y     = cfg.getDouble(path + ".y", 64.0);
        double z     = cfg.getDouble(path + ".z", 0.5);
        float  yaw   = (float) cfg.getDouble(path + ".yaw", 0.0);
        float  pitch = (float) cfg.getDouble(path + ".pitch", 0.0);

        return new Location(world, x, y, z, yaw, pitch);
    }

    public String getDefaultWorld() {
        return defaultWorld;
    }

    public Location getGlobalLobby() {
        return globalLobby;
    }
}
