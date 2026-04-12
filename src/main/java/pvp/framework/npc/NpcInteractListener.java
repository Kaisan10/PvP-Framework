package pvp.framework.npc;

import pvp.framework.PvPFramework;
import pvp.framework.mode.YamlGameSession;
import pvp.framework.session.GameSession;
import pvp.framework.session.GameState;
import de.oliver.fancynpcs.api.events.NpcInteractEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * FancyNpcs の NpcInteractEvent を受け取り、
 * PvPFramework にリンクされているNPCをクリックしたときの処理を行う。
 *
 * - 通常NPC（gameId = ゲームID） : ゲームへ参加
 * - ロビーNPC（gameId = "__LOBBY__"）: global lobby へテレポート
 *
 * [Bug③修正]
 * FREEPLAY の待機場所にいるプレイヤー（同じゲームIDのセッション参加中かつ
 * MenuCompassを所持している状態）は「ゲーム中」エラーを出さず、
 * アリーナへ入場させる。
 */
public class NpcInteractListener implements Listener {

    private static final String PREFIX = "§b[PvPF] §r";
    private final PvPFramework plugin;

    public NpcInteractListener(PvPFramework plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onNpcInteract(NpcInteractEvent event) {
        Player player = event.getPlayer();

        // FancyNpcs 内部識別子（UID）を取得し、ゲームIDに変換する
        String npcUid = event.getNpc().getData().getName();
        String gameId = plugin.getFancyNpcManager().getGameId(npcUid);
        if (gameId == null) return; // PvPF にリンクされていないNPCは無視

        // ロビー返還NPC
        if (FancyNpcManager.LOBBY_NPC_GAME_ID.equals(gameId)) {
            handleLobbyNpc(player);
            return;
        }

        // ゲーム参加NPC
        GameSession currentSession = plugin.getSessionManager().getSessionByPlayer(player);

        if (currentSession != null) {
            // [Bug③修正]
            // FREEPLAY セッションに参加中 && 同じゲームID && ACTIVE && 待機場所にいる（アリーナにいない）
            // → アリーナへ入場（「ゲーム中」エラーを出さない）
            if (currentSession instanceof YamlGameSession ys
                    && "FREEPLAY".equals(ys.getConfig().getMode())
                    && ys.getGameId().equals(gameId)
                    && currentSession.getState() == GameState.ACTIVE
                    && !currentSession.isPlayerInArena(player)) {
                GameSession.removeMenuCompass(player);
                ys.teleportToArena(player);
                GameSession.giveLeaveCompass(player);
                return;
            }

            // 上記以外（別ゲーム参加中、アリーナにいる、等）はエラー
            player.sendMessage(PREFIX + "§c既にゲームに参加中です。§f/pvpf leave §cで退出してから参加してください。");
            return;
        }

        // 未参加 → 通常の参加処理
        plugin.getSessionManager().joinOrQueue(player, gameId);
    }

    /**
     * プレイヤーをglobal lobbyへテレポートする。
     * セッション参加中の場合はセッションを退出してからテレポートする。
     */
    private void handleLobbyNpc(Player player) {
        if (plugin.getSessionManager().isInSession(player)) {
            plugin.getSessionManager().leaveSession(player);
        }

        Location globalLobby = plugin.getConfigManager().getGlobalLobby();
        if (globalLobby == null) {
            player.sendMessage(PREFIX + "§cグローバルロビーが設定されていません。");
            return;
        }

        player.teleport(globalLobby);
        player.sendMessage(PREFIX + "§aロビーへ戻りました。");
    }
}
