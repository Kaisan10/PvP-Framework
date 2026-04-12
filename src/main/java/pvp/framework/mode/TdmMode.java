package pvp.framework.mode;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;
import pvp.framework.script.ScriptEngine;

/**
 * チーム戦モード。
 * 現フェーズでは YamlGameSession をそのまま使う（チームサポートは Phase 4+ で追加）。
 */
public class TdmMode implements GameMode {
    @Override public String getId() { return "TDM"; }

    @Override
    public YamlGameSession createSession(PvPFramework plugin,
                                          String sessionId,
                                          GameConfig config,
                                          ScriptEngine scriptEngine) {
        return new YamlGameSession(plugin, sessionId, config, scriptEngine);
    }
}
