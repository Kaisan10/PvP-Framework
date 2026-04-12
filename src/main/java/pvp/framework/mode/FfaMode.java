package pvp.framework.mode;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;
import pvp.framework.script.ScriptEngine;

/** 個人戦モード。YamlGameSession をそのまま使う。 */
public class FfaMode implements GameMode {
    @Override public String getId() { return "FFA"; }

    @Override
    public YamlGameSession createSession(PvPFramework plugin,
                                          String sessionId,
                                          GameConfig config,
                                          ScriptEngine scriptEngine) {
        return new YamlGameSession(plugin, sessionId, config, scriptEngine);
    }
}
