package pvp.framework.location;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * YAMLから読み込んだロケーション情報を保持するデータクラス。
 * ワールドが未ロードでも保持できるよう、worldNameを文字列で持つ。
 */
public class GameLocation {

    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;

    public GameLocation(String worldName, double x, double y, double z, float yaw, float pitch) {
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /**
     * BukkitのLocationに変換する。
     * ワールドが見つからない場合は null を返す（コンソールに警告を出す）。
     */
    public Location toBukkitLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            Bukkit.getLogger().warning(
                "[PvPFramework] World '" + worldName + "' is not loaded. " +
                "Make sure the world exists and is loaded (e.g. via Multiverse-Core).");
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    public String getWorldName() { return worldName; }
    public double getX()        { return x; }
    public double getY()        { return y; }
    public double getZ()        { return z; }
    public float  getYaw()      { return yaw; }
    public float  getPitch()    { return pitch; }

    @Override
    public String toString() {
        return worldName + " (" + x + ", " + y + ", " + z + ")";
    }
}
