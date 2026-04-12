package pvp.framework.game;

/**
 * games/*.yml の win-condition: セクションを保持するデータクラス。
 */
public class WinConditionConfig {

    public enum Type {
        KILLS, TIME, LAST_STANDING, SCORE, NONE
    }

    private final Type type;
    private final int target;
    private final int timeLimit;

    public WinConditionConfig(Type type, int target, int timeLimit) {
        this.type      = type;
        this.target    = target;
        this.timeLimit = timeLimit;
    }

    public static WinConditionConfig defaults() {
        return new WinConditionConfig(Type.KILLS, 20, 300);
    }

    public Type getType()     { return type; }
    public int  getTarget()   { return target; }
    public int  getTimeLimit(){ return timeLimit; }
}
