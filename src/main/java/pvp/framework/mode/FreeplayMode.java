package pvp.framework.mode;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;
import pvp.framework.script.ScriptEngine;

/**
 * FREEPLAY モード。
 * カウントダウンなし・参加人数制限なし・ゲーム終了条件なし。
 * YamlGameSession の isJoinableInActive() / isEndOnLastPlayer() / isFreeplay() で制御される。
 */
public class FreeplayMode implements GameMode {

    @Override
    public String getId() {
        return "FREEPLAY";
    }

    @Override
    public YamlGameSession createSession(PvPFramework plugin,
                                          String sessionId,
                                          GameConfig config,
                                          ScriptEngine scriptEngine) {
        return new YamlGameSession(plugin, sessionId, config, scriptEngine);
    }
}
