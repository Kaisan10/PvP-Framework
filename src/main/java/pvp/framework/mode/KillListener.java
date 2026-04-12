package pvp.framework.mode;

import pvp.framework.PvPFramework;
import pvp.framework.session.GameSession;
import pvp.framework.session.GameState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * PlayerDeathEvent を監視して YamlGameSession の onPlayerKill() を呼ぶリスナー。
 *
 * getKiller() ではなく getLastDamageCause() を使用することで、
 * /kill・落下・火などの環境死で他プレイヤーが誤ってキルとみなされるバグを防ぐ。
 */
public class KillListener implements Listener {

    private final PvPFramework plugin;

    public KillListener(PvPFramework plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        GameSession session = plugin.getSessionManager().getSessionByPlayer(victim);
        if (!(session instanceof YamlGameSession ys)) return;

        // ACTIVE中のみキル/デス処理を行う
        if (session.getState() != GameState.ACTIVE) return;

        Player killer = resolveKiller(victim);

        if (killer != null) {
            ys.onPlayerKill(killer, victim);
        } else {
            ys.onPlayerDeath(victim);
        }
    }

    /**
     * 死亡の直接原因からキラーを特定する。
     *
     * getKiller() は「3秒以内に最後にダメージを与えたプレイヤー」を返すため、
     * 落下死・/kill・溶岩などの環境死でも別プレイヤーがキルとみなされてしまう。
     * getLastDamageCause() を使うことで「実際に止めを刺したダメージ」を確認できる。
     *
     * @return キラープレイヤー（環境死・自殺の場合は null）
     */
    private Player resolveKiller(Player victim) {
        EntityDamageEvent lastDamage = victim.getLastDamageCause();
        if (!(lastDamage instanceof EntityDamageByEntityEvent edbe)) {
            // 最後のダメージが EntityDamageByEntityEvent でない = 環境死（落下・溶岩・/kill等）
            return null;
        }

        Entity damager = edbe.getDamager();

        // 直接攻撃
        if (damager instanceof Player p && p != victim) {
            return p;
        }

        // 矢・雪玉などのProjectile
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p && p != victim) {
            return p;
        }

        // それ以外（NPC・モブ・自分自身 等）はキルなし
        return null;
    }
}
