package pvp.framework.location;

import org.bukkit.Location;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * パース済みのアリーナ情報。
 * スポーンリストと境界（省略可）を持つ。
 */
public class ResolvedArena {

    private final String worldName;
    private final List<GameLocation> spawns;
    private final GameLocation boundsMin;
    private final GameLocation boundsMax;

    private int spawnIndex = 0;

    public ResolvedArena(String worldName, List<GameLocation> spawns,
                         GameLocation boundsMin, GameLocation boundsMax) {
        this.worldName = worldName;
        this.spawns = spawns;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
    }

    /**
     * ランダムなスポーンポイントを返す。
     */
    public Location nextSpawn() {
        if (spawns.isEmpty()) return null;
        int index = ThreadLocalRandom.current().nextInt(spawns.size());
        GameLocation gl = spawns.get(index);
        return gl.toBukkitLocation();
    }

    /**
     * スポーン数を返す。
     * GameSession の重複スポーン防止ロジックで使用される。
     */
    public int getSpawnCount() {
        return spawns.size();
    }

    /**
     * 指定インデックスのスポーン地点を返す。
     * インデックスが範囲外の場合は null を返す。
     * GameSession の重複スポーン防止ロジックで使用される。
     */
    public Location getSpawn(int index) {
        if (index < 0 || index >= spawns.size()) return null;
        return spawns.get(index).toBukkitLocation();
    }

    /**
     * 指定した座標がアリーナ境界内かチェックする。
     * bounds が設定されていない場合は常に true を返す。
     */
    public boolean isInBounds(Location loc) {
        if (boundsMin == null || boundsMax == null) return true;
        if (!loc.getWorld().getName().equals(worldName)) return false;

        return loc.getX() >= boundsMin.getX() && loc.getX() <= boundsMax.getX()
            && loc.getY() >= boundsMin.getY() && loc.getY() <= boundsMax.getY()
            && loc.getZ() >= boundsMin.getZ() && loc.getZ() <= boundsMax.getZ();
    }

    public void resetSpawnIndex() { spawnIndex = 0; }

    public String getWorldName()          { return worldName; }
    public List<GameLocation> getSpawns() { return spawns; }
    public GameLocation getBoundsMin()    { return boundsMin; }
    public GameLocation getBoundsMax()    { return boundsMax; }
    public boolean hasBounds()            { return boundsMin != null && boundsMax != null; }
}
