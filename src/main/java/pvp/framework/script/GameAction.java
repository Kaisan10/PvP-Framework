package pvp.framework.script;

import org.bukkit.entity.Player;

import java.util.Map;

/**
 * YAMLイベントスクリプトの1アクションを表すインターフェース。
 * Tier 2 プラグインは ScriptEngine.registerAction() でカスタムアクションを追加できる。
 */
@FunctionalInterface
public interface GameAction {
    /**
     * @param session  実行元セッション（YamlGameSession にキャスト可能）
     * @param target   アクション対象プレイヤー（null = セッション全員）
     * @param args     アクション引数文字列（"give-item: GOLDEN_APPLE 1" の "GOLDEN_APPLE 1" 部分）
     * @param ctx      プレースホルダー展開用コンテキスト
     */
    void execute(pvp.framework.mode.YamlGameSession session,
                 Player target,
                 String args,
                 Map<String, Object> ctx);
}
