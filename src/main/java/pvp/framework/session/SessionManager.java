package pvp.framework.session;

import pvp.framework.PvPFramework;
import pvp.framework.game.GameConfig;
import pvp.framework.mode.YamlGameSession;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.logging.Logger;

/**
 * 全セッションのライフサイクルと
 * プレイヤー → セッション の対応を管理するクラス。
 */
public class SessionManager {

    private final PvPFramework plugin;
    private final Logger log;

    private final Map<String, GameSession> sessions = new HashMap<>();
    private final Map<UUID, String> playerSession = new HashMap<>();

    public SessionManager(PvPFramework plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    public void registerSession(GameSession session) {
        sessions.put(session.getSessionId(), session);
        log.info("Session registered: " + session.getSessionId());
    }

    public void unregisterSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public boolean joinSession(Player player, String sessionId) {
        if (playerSession.containsKey(player.getUniqueId())) {
            String currentId = playerSession.get(player.getUniqueId());
            if (currentId.equals(sessionId)) {
                GameSession session = sessions.get(sessionId);
                if (session != null) return session.addPlayer(player);
            }
            player.sendMessage(plugin.getMessageManager().get("session.already-in-game"));
            return false;
        }

        GameSession session = sessions.get(sessionId);
        if (session == null) {
            player.sendMessage(plugin.getMessageManager().get("session.session-not-found", "sessionId", sessionId));
            return false;
        }

        boolean joined = session.addPlayer(player);
        if (joined) {
            playerSession.put(player.getUniqueId(), sessionId);
            log.info(player.getName() + " joined session: " + sessionId);
        } else {
            player.sendMessage(plugin.getMessageManager().get("session.join-failed"));
        }
        return joined;
    }

    public void leaveSession(Player player) {
        String sessionId = playerSession.remove(player.getUniqueId());
        if (sessionId == null) return;

        GameSession session = sessions.get(sessionId);

        // [FIX] leaveSession は常に global-lobby へ送る。
        // ゲーム中（ACTIVE）→ 待機場所 の移動は PvpfCommand.handleLeave が担当するため、
        // ここでは game lobby を参照しない。
        org.bukkit.Location dest = plugin.getConfigManager().getGlobalLobby();

        if (session != null) {
            session.removePlayer(player);
            log.info(player.getName() + " left session: " + sessionId);
        }

        // 切断時（isOnline=false）はテレポート不要
        if (player.isOnline() && dest != null) {
            player.teleport(dest);
        }
    }

    public void onSessionReset(GameSession session) {
        playerSession.entrySet().removeIf(e -> e.getValue().equals(session.getSessionId()));
        log.info("Session reset: " + session.getSessionId());
    }

    /**
     * [Bug②] /pvpf reload 後に呼ばれ、指定された gameId の WAITING セッションを破棄する。
     *
     * reload() で GameLoader の configs は新しい GameConfig に更新されるが、
     * SessionManager に残っている WAITING セッションはリロード前の古い GameConfig への
     * 参照を保持したままになる。そのため次の joinOrQueue() で古い設定が使われてしまう。
     * このメソッドでWAITINGセッションを破棄することで、次回の参加時に
     * 新しい GameConfig を持つセッションが自動生成されるようにする。
     *
     * @param gameIds 破棄対象の gameId セット（reload後の configs.keySet()）
     */
    public void invalidateWaitingSessions(Set<String> gameIds) {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, GameSession> entry : sessions.entrySet()) {
            GameSession s = entry.getValue();
            if (s.getState() == GameState.WAITING && gameIds.contains(s.getGameId())) {
                toRemove.add(entry.getKey());
            }
        }
        for (String id : toRemove) {
            // WAITINGセッションにはプレイヤーがいないはずだが念のりprayerSession側もクリア
            playerSession.entrySet().removeIf(e -> e.getValue().equals(id));
            sessions.remove(id);
            log.info("Invalidated WAITING session on reload: " + id);
        }
    }

    public boolean joinOrQueue(Player player, String gameId) {
        if (playerSession.containsKey(player.getUniqueId())) {
            String currentId = playerSession.get(player.getUniqueId());
            GameSession current = sessions.get(currentId);
            if (current != null && current.getGameId().equals(gameId)) {
                return current.addPlayer(player);
            }
            player.sendMessage(plugin.getMessageManager().get("session.already-in-game"));
            return false;
        }

        GameConfig cfg = plugin.getGameLoader().getConfig(gameId);
        if (cfg == null) {
            player.sendMessage(plugin.getMessageManager().get("command.game.start.not-found", "gameId", gameId));
            return false;
        }

        GameSession target = sessions.values().stream()
                .filter(s -> s.getGameId().equals(gameId))
                .filter(s -> s.getState() == GameState.WAITING
                          || s.getState() == GameState.COUNTDOWN
                          || (s.getState() == GameState.ACTIVE && s.isJoinableInActive()))
                .filter(s -> s.getPlayerCount() < cfg.getMaxPlayers())
                .findFirst()
                .orElse(null);

        if (target == null) {
            String sessionId = gameId + "-" + (System.currentTimeMillis() % 100000);
            target = plugin.getModeEngine().createSession(plugin, sessionId, cfg);
            registerSession(target);
            log.info("Auto-created session: " + sessionId + " for game: " + gameId);
        }

        boolean joined = target.addPlayer(player);
        if (joined) {
            playerSession.put(player.getUniqueId(), target.getSessionId());
            log.info(player.getName() + " joined session: " + target.getSessionId()
                    + " (via queue for " + gameId + ")");
        } else {
            player.sendMessage(plugin.getMessageManager().get("session.join-failed"));
        }
        return joined;
    }

    public GameSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public GameSession getSessionByPlayer(Player player) {
        String sessionId = playerSession.get(player.getUniqueId());
        return sessionId != null ? sessions.get(sessionId) : null;
    }

    public boolean isInSession(Player player) {
        return playerSession.containsKey(player.getUniqueId());
    }

    public Map<String, GameSession> getSessions() {
        return Collections.unmodifiableMap(sessions);
    }
}