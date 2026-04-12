package pvp.framework.mode;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;
import pvp.framework.script.ScriptEngine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * mode: フィールドに応じてセッションを生成するファクトリ。
 */
public class ModeEngine {

    private final Logger log;
    private final ScriptEngine scriptEngine;
    private final Map<String, GameMode> modes = new LinkedHashMap<>();

    public ModeEngine(Logger log, ScriptEngine scriptEngine) {
        this.log          = log;
        this.scriptEngine = scriptEngine;

        registerMode(new FfaMode());
        registerMode(new TdmMode());
        registerMode(new LastStandingMode());
        registerMode(new FreeplayMode()); // FREEPLAY モード追加
    }

    public void registerMode(GameMode mode) {
        modes.put(mode.getId().toUpperCase(), mode);
        log.info("[ModeEngine] Registered mode: " + mode.getId());
    }

    public YamlGameSession createSession(PvPFramework plugin,
                                          String sessionId,
                                          GameConfig config) {
        String modeId = config.getMode().toUpperCase();
        GameMode mode = modes.get(modeId);

        if (mode == null) {
            log.warning("[ModeEngine] Unknown mode '" + modeId + "'. Falling back to FFA.");
            mode = modes.get("FFA");
        }

        return mode.createSession(plugin, sessionId, config, scriptEngine);
    }

    public boolean hasMode(String modeId)        { return modes.containsKey(modeId.toUpperCase()); }
    public Map<String, GameMode> getModes()       { return Map.copyOf(modes); }
    public ScriptEngine getScriptEngine()         { return scriptEngine; }
}
