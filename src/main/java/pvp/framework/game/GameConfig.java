package pvp.framework.game;

import pvp.framework.location.GameLocation;
import pvp.framework.location.ResolvedArena;

import java.util.List;
import java.util.Map;

/**
 * games/*.yml をパースして得られる不変のゲーム設定オブジェクト。
 * リロード後も実行中セッションはこのオブジェクトへの参照を直接持つため、
 * 実行中セッションには影響しない。
 */
public class GameConfig {

    private final String gameId;
    private final String displayName;
    private final String mode;

    private final int minPlayers;
    private final int maxPlayers;
    private final int countdown;

    private final GameLocation lobby;
    private final ResolvedArena arena;

    private final String kitId;
    private final String respawnKitId;

    // combat
    private final boolean friendlyFire;
    private final boolean respawnEnabled;
    private final int respawnDelay;
    private final boolean allowBuilding;  // ビルドモード用（デフォルト false）

    // scoreboard
    private final String scoreboardTitle;
    private final List<String> scoreboardLines;
    private final int scoreboardUpdateInterval; // 秒単位

    private final WinConditionConfig winCondition;

    // events: ブロック。キー = "on-kill" 等、値 = アクション文字列リスト
    private final Map<String, List<String>> rawEvents;

    public GameConfig(Builder b) {
        this.gameId        = b.gameId;
        this.displayName   = b.displayName;
        this.mode          = b.mode;
        this.minPlayers    = b.minPlayers;
        this.maxPlayers    = b.maxPlayers;
        this.countdown     = b.countdown;
        this.lobby         = b.lobby;
        this.arena         = b.arena;
        this.kitId         = b.kitId;
        this.respawnKitId  = b.respawnKitId;
        this.friendlyFire  = b.friendlyFire;
        this.respawnEnabled= b.respawnEnabled;
        this.respawnDelay  = b.respawnDelay;
        this.allowBuilding = b.allowBuilding;
        this.scoreboardTitle          = b.scoreboardTitle;
        this.scoreboardLines          = b.scoreboardLines;
        this.scoreboardUpdateInterval = b.scoreboardUpdateInterval;
        this.winCondition  = b.winCondition;
        this.rawEvents     = b.rawEvents;
    }

    // ---- getters ----
    public String getGameId()           { return gameId; }
    public String getDisplayName()      { return displayName; }
    public String getMode()             { return mode; }
    public int    getMinPlayers()       { return minPlayers; }
    public int    getMaxPlayers()       { return maxPlayers; }
    public int    getCountdown()        { return countdown; }
    public GameLocation getLobby()      { return lobby; }
    public ResolvedArena getArena()     { return arena; }
    public String getKitId()            { return kitId; }
    public String getRespawnKitId()     { return respawnKitId; }
    public boolean isFriendlyFire()     { return friendlyFire; }
    public boolean isRespawnEnabled()   { return respawnEnabled; }
    public int    getRespawnDelay()     { return respawnDelay; }
    public boolean isAllowBuilding()    { return allowBuilding; }
    public String       getScoreboardTitle()          { return scoreboardTitle; }
    public List<String> getScoreboardLines()          { return scoreboardLines; }
    public int          getScoreboardUpdateInterval() { return scoreboardUpdateInterval; }
    public WinConditionConfig getWinCondition() { return winCondition; }
    public Map<String, List<String>> getRawEvents() { return rawEvents; }

    // ---- Builder ----
    public static class Builder {
        String gameId = "";
        String displayName = "";
        String mode = "FFA";
        int minPlayers = 2;
        int maxPlayers = 16;
        int countdown  = 30;
        GameLocation lobby = null;
        ResolvedArena arena = null;
        String kitId = null;
        String respawnKitId = null;
        boolean friendlyFire  = false;
        boolean respawnEnabled = true;
        int respawnDelay = 3;
        boolean allowBuilding  = false;
        String scoreboardTitle = "&b&lPvP";
        List<String> scoreboardLines = List.of();
        int scoreboardUpdateInterval = 2; // 秒
        WinConditionConfig winCondition = WinConditionConfig.defaults();
        Map<String, List<String>> rawEvents = Map.of();

        public Builder gameId(String v)        { this.gameId = v; return this; }
        public Builder displayName(String v)   { this.displayName = v; return this; }
        public Builder mode(String v)          { this.mode = v.toUpperCase(); return this; }
        public Builder minPlayers(int v)       { this.minPlayers = v; return this; }
        public Builder maxPlayers(int v)       { this.maxPlayers = v; return this; }
        public Builder countdown(int v)        { this.countdown = v; return this; }
        public Builder lobby(GameLocation v)   { this.lobby = v; return this; }
        public Builder arena(ResolvedArena v)  { this.arena = v; return this; }
        public Builder kitId(String v)         { this.kitId = v; return this; }
        public Builder respawnKitId(String v)  { this.respawnKitId = v; return this; }
        public Builder friendlyFire(boolean v) { this.friendlyFire = v; return this; }
        public Builder respawnEnabled(boolean v){ this.respawnEnabled = v; return this; }
        public Builder respawnDelay(int v)     { this.respawnDelay = v; return this; }
        public Builder allowBuilding(boolean v)          { this.allowBuilding = v; return this; }
        public Builder scoreboardTitle(String v)         { this.scoreboardTitle = v; return this; }
        public Builder scoreboardLines(List<String> v)   { this.scoreboardLines = v; return this; }
        public Builder scoreboardUpdateInterval(int v)   { this.scoreboardUpdateInterval = v; return this; }
        public Builder winCondition(WinConditionConfig v) { this.winCondition = v; return this; }
        public Builder rawEvents(Map<String, List<String>> v) { this.rawEvents = v; return this; }
        public GameConfig build()              { return new GameConfig(this); }
    }
}
