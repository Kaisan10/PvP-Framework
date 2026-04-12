package pvp.framework.session;

/**
 * ゲームセッションの状態を表すEnum。
 *
 * WAITING    → プレイヤー募集中
 * COUNTDOWN  → カウントダウン中
 * ACTIVE     → ゲーム進行中
 * ENDING     → 勝者決定〜後処理中
 * RESETTING  → アリーナリセット中（次のゲームへ）
 */
public enum GameState {
    WAITING,
    COUNTDOWN,
    ACTIVE,
    ENDING,
    RESETTING
}
