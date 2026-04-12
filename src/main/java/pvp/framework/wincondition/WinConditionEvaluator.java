package pvp.framework.wincondition;

import pvp.framework.game.WinConditionConfig;
import pvp.framework.mode.YamlGameSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * セッションの現在状態を見て勝者を判定するクラス。
 * GameSession.onTick() から毎秒呼ばれる。
 */
public class WinConditionEvaluator {

    private final WinConditionConfig config;

    public WinConditionEvaluator(WinConditionConfig config) {
        this.config = config;
    }

    /**
     * 勝者が決まった場合は勝者名を返す。まだ決まっていない場合は empty。
     */
    public Optional<String> check(YamlGameSession session) {
        return switch (config.getType()) {
            case KILLS         -> checkKills(session);
            case TIME          -> checkTime(session);
            case LAST_STANDING -> checkLastStanding(session);
            case SCORE         -> checkScore(session);
            case NONE          -> Optional.empty(); // FREEPLAYなど: 自動終了しない
        };
    }

    // -------------------------------------------------------
    // KILLS: target キルに最初に到達したプレイヤーが勝者
    // -------------------------------------------------------
    private Optional<String> checkKills(YamlGameSession session) {
        for (UUID uuid : session.getPlayers()) {
            int kills = session.getKills(uuid);
            if (kills >= config.getTarget()) {
                Player p = Bukkit.getPlayer(uuid);
                return Optional.of(p != null ? p.getName() : uuid.toString());
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------
    // TIME: 時間切れ → 最多キルプレイヤーが勝者
    // -------------------------------------------------------
    private Optional<String> checkTime(YamlGameSession session) {
        if (session.getTimeElapsed() < config.getTimeLimit()) return Optional.empty();

        UUID top = null;
        int  max = -1;
        for (UUID uuid : session.getPlayers()) {
            int kills = session.getKills(uuid);
            if (kills > max) { max = kills; top = uuid; }
        }

        if (top == null) return Optional.of("(引き分け)");
        Player p = Bukkit.getPlayer(top);
        return Optional.of(p != null ? p.getName() : top.toString());
    }

    // -------------------------------------------------------
    // LAST_STANDING: 生存1名（または1名以下）になったら終了
    // -------------------------------------------------------
    private Optional<String> checkLastStanding(YamlGameSession session) {
        // 生存者 = オンラインのプレイヤー（既にonPlayerDeath内でremoveしている前提でもよいが、
        //          ここでは単純に残りプレイヤー数で判断する）
        int count = session.getPlayerCount();
        if (count > 1) return Optional.empty();
        if (count == 0) return Optional.of("(全員脱落)");

        UUID survivor = session.getPlayers().iterator().next();
        Player p = Bukkit.getPlayer(survivor);
        return Optional.of(p != null ? p.getName() : survivor.toString());
    }

    // -------------------------------------------------------
    // SCORE: target スコアに最初に到達したプレイヤーが勝者
    // -------------------------------------------------------
    private Optional<String> checkScore(YamlGameSession session) {
        for (UUID uuid : session.getPlayers()) {
            int score = session.getScore(uuid);
            if (score >= config.getTarget()) {
                Player p = Bukkit.getPlayer(uuid);
                return Optional.of(p != null ? p.getName() : uuid.toString());
            }
        }
        return Optional.empty();
    }
}
