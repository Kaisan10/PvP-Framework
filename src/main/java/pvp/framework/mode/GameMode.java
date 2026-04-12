package pvp.framework.mode;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;

/**
 * モードごとのセッション生成を担うインターフェース。
 * Tier 2 プラグインはこれを実装して ModeEngine.registerMode() で追加できる。
 */
public interface GameMode {
    /** モードID ("FFA" / "TDM" / "LAST_STANDING" 等) */
    String getId();

    /**
     * 新しい YamlGameSession（またはそのサブクラス）を生成して返す。
     * セッションの登録は ModeEngine / PvpfCommand 側で行う。
     */
    YamlGameSession createSession(PvPFramework plugin,
                                  String sessionId,
                                  GameConfig config,
                                  pvp.framework.script.ScriptEngine scriptEngine);
}
