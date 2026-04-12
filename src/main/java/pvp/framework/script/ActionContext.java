package pvp.framework.script;

import org.bukkit.entity.Player;

import java.util.Map;

/**
 * プレースホルダー展開ユーティリティ。
 * {kills}, {time_left} 等を context map から文字列に置換する。
 */
public final class ActionContext {

    private ActionContext() {}

    /**
     * テキスト内の {key} を ctx の値に置換する。
     */
    public static String expand(String text, Player player, Map<String, Object> ctx) {
        if (text == null) return "";
        String result = text;

        // player 固有
        if (player != null) {
            result = result.replace("{player}", player.getName());
        }

        // ctx から展開
        for (Map.Entry<String, Object> entry : ctx.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}",
                    entry.getValue() != null ? entry.getValue().toString() : "");
        }

        // カラーコード変換 (&x → §x)
        result = result.replace('&', '§');

        return result;
    }

    /**
     * 残り時間を MM:SS 形式にフォーマットする。
     */
    public static String formatTime(int totalSeconds) {
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        return String.format("%02d:%02d", m, s);
    }
}
