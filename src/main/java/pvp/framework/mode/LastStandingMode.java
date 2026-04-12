package pvp.framework.mode;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;
import pvp.framework.script.ScriptEngine;

/**
 * リスポーンなし最後の生存者モード。
 * GameConfig 側で respawn=false が強制されるため、YamlGameSession をそのまま使う。
 */
public class LastStandingMode implements GameMode {
    @Override public String getId() { return "LAST_STANDING"; }

    @Override
    public YamlGameSession createSession(PvPFramework plugin,
                                          String sessionId,
                                          GameConfig config,
                                          ScriptEngine scriptEngine) {
        return new YamlGameSession(plugin, sessionId, config, scriptEngine);
    }
}
