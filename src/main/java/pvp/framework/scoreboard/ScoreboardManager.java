package pvp.framework.scoreboard;

import pvp.framework.mode.YamlGameSession;
import pvp.framework.script.ActionContext;
import pvp.framework.session.GameState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * sidebar スコアボードをセッション情報で動的更新するユーティリティ。
 */
public class ScoreboardManager {

    private static final String OBJECTIVE_ID = "pvpf";

    public void update(Player player, YamlGameSession session) {
        if (session.getState() == GameState.WAITING
                || session.getState() == GameState.COUNTDOWN) {
            remove(player);
            return;
        }

        List<String> lines = session.getConfig().getScoreboardLines();
        if (lines == null || lines.isEmpty()) return;

        Scoreboard board = getOrCreateBoard(player);
        Objective obj = getOrCreateObjective(board, session.getConfig().getScoreboardTitle());

        for (String entry : new HashSet<>(board.getEntries())) {
            board.resetScores(entry);
        }

        Map<String, Object> ctx = session.buildCtx(player);

        int total = Math.min(lines.size(), 15);
        for (int i = 0; i < total; i++) {
            String raw = ActionContext.expand(lines.get(i), player, ctx);
            Component text = colorize(raw);
            String entry = buildLine(board, i, text);
            obj.getScore(entry).setScore(total - i);
        }
    }

    public void remove(Player player) {
        player.setScoreboard(Objects.requireNonNull(Bukkit.getScoreboardManager()).getMainScoreboard());
    }

    private Scoreboard getOrCreateBoard(Player player) {
        Scoreboard board = player.getScoreboard();
        if (board == Objects.requireNonNull(Bukkit.getScoreboardManager()).getMainScoreboard()) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(board);
        }
        return board;
    }

    private Objective getOrCreateObjective(Scoreboard board, String rawTitle) {
        Component title = colorize(rawTitle);
        Objective obj = board.getObjective(OBJECTIVE_ID);
        if (obj == null) {
            obj = board.registerNewObjective(OBJECTIVE_ID, Criteria.DUMMY, title);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            obj.displayName(title);
        }
        return obj;
    }

    private String buildLine(Scoreboard board, int index, Component text) {
        // 各行を区別するための識別子（不可視の文字列）
        String entry = "§" + Integer.toHexString(index) + "§r";

        String teamId = "pvpf_L" + index;
        Team team = board.getTeam(teamId);
        if (team == null) {
            team = board.registerNewTeam(teamId);
            team.addEntry(entry);
        }

        // 最新のAPIでは prefix に長い Component をそのまま設定可能
        team.prefix(text);
        team.suffix(Component.empty());

        return entry;
    }

    private Component colorize(String s) {
        if (s == null) return Component.empty();
        // & を色の記号として解釈して Component に変換
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
