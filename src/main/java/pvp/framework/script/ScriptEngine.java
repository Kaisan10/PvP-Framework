package pvp.framework.script;

import pvp.framework.mode.YamlGameSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

/**
 * events: ブロックを解析して GameAction リストに変換し、
 * イベント名を指定して発火するエンジン。
 *
 * アクション書式: "action-name: args" または "action-name:" (引数なし)
 */
public class ScriptEngine {

    private final Logger log;

    // actionName → GameAction
    private final Map<String, GameAction> actions = new LinkedHashMap<>();

    public ScriptEngine(Logger log) {
        this.log = log;
        registerDefaults();
    }

    // -------------------------------------------------------
    // 登録 (Tier 2 拡張用)
    // -------------------------------------------------------
    public void registerAction(String name, GameAction action) {
        actions.put(name.toLowerCase(), action);
    }

    // -------------------------------------------------------
    // 発火
    // -------------------------------------------------------
    /**
     * セッションの rawEvents から eventName のアクションリストを取得して実行する。
     *
     * @param eventName イベント名 ("on-kill" 等)
     * @param session   実行元セッション
     * @param target    アクション対象プレイヤー（null 可）
     * @param ctx       プレースホルダー展開コンテキスト
     */
    public void fire(String eventName,
                     YamlGameSession session,
                     Player target,
                     Map<String, Object> ctx) {

        List<String> rawList = session.getConfig().getRawEvents().get(eventName);
        if (rawList == null || rawList.isEmpty()) return;

        for (String raw : rawList) {
            executeRaw(raw, session, target, ctx);
        }
    }

    /**
     * "action-name: args" 形式の文字列を解析して実行する。
     */
    private void executeRaw(String raw,
                             YamlGameSession session,
                             Player target,
                             Map<String, Object> ctx) {
        if (raw == null || raw.isBlank()) return;

        int colonIdx = raw.indexOf(':');
        String name;
        String args;
        if (colonIdx < 0) {
            name = raw.trim().toLowerCase();
            args = "";
        } else {
            name = raw.substring(0, colonIdx).trim().toLowerCase();
            args = raw.substring(colonIdx + 1).trim();
        }

        GameAction action = actions.get(name);
        if (action == null) {
            log.warning("[ScriptEngine] Unknown action: " + name);
            return;
        }

        try {
            action.execute(session, target, args, ctx);
        } catch (Exception e) {
            log.warning("[ScriptEngine] Action '" + name + "' threw: " + e.getMessage());
        }
    }

    // -------------------------------------------------------
    // 標準アクション登録
    // -------------------------------------------------------
    private void registerDefaults() {

        // send-message: "テキスト"
        registerAction("send-message", (session, target, args, ctx) -> {
            if (target == null) return;
            target.sendMessage(ActionContext.expand(args, target, ctx));
        });

        // broadcast: "テキスト"
        registerAction("broadcast", (session, target, args, ctx) -> {
            String msg = ActionContext.expand(args, target, ctx);
            for (UUID uuid : session.getPlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.sendMessage(msg);
            }
        });

        // send-title: "title" "subtitle" fadeIn stay fadeOut
        // 例: send-title: "&aWIN!" "&7good game" 10 60 20
        registerAction("send-title", (session, target, args, ctx) -> {
            String[] parts = splitArgs(args);
            String titleStr    = parts.length > 0 ? ActionContext.expand(parts[0].replace("\"", ""), target, ctx) : "";
            String subtitleStr = parts.length > 1 ? ActionContext.expand(parts[1].replace("\"", ""), target, ctx) : "";
            int fadeIn  = parts.length > 2 ? parseInt(parts[2], 10) : 10;
            int stay    = parts.length > 3 ? parseInt(parts[3], 60) : 60;
            int fadeOut = parts.length > 4 ? parseInt(parts[4], 20) : 20;

            Title title = buildTitle(titleStr, subtitleStr, fadeIn, stay, fadeOut);

            if (target != null) {
                target.showTitle(title);
            } else {
                for (UUID uuid : session.getPlayers()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) p.showTitle(title);
                }
            }
        });

        // give-item: MATERIAL amount
        registerAction("give-item", (session, target, args, ctx) -> {
            if (target == null) return;
            String[] parts = args.trim().split("\\s+");
            Material mat = Material.matchMaterial(parts[0]);
            if (mat == null) { log.warning("give-item: unknown material: " + parts[0]); return; }
            int amount = parts.length > 1 ? parseInt(parts[1], 1) : 1;
            target.getInventory().addItem(new ItemStack(mat, amount));
        });

        // give-kit: kit-id
        registerAction("give-kit", (session, target, args, ctx) -> {
            if (target == null) return;
            session.getPlugin().getKitManager().give(target, args.trim());
        });

        // give-effect: EFFECT duration amplifier
        registerAction("give-effect", (session, target, args, ctx) -> {
            if (target == null) return;
            String[] parts = args.trim().split("\\s+");
            PotionEffectType type = Registry.EFFECT.get(
                    NamespacedKey.minecraft(parts[0].toLowerCase()));
            if (type == null) { log.warning("give-effect: unknown effect: " + parts[0]); return; }
            int duration  = parts.length > 1 ? parseInt(parts[1], 200) : 200;
            int amplifier = parts.length > 2 ? parseInt(parts[2], 0)   : 0;
            target.addPotionEffect(new PotionEffect(type, duration, amplifier));
        });

        // play-sound: SOUND volume pitch
        registerAction("play-sound", (session, target, args, ctx) -> {
            String[] parts = args.trim().split("\\s+");
            Sound sound;
            try {
                sound = Sound.valueOf(parts[0].toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warning("play-sound: unknown sound: " + parts[0]); return;
            }
            float volume = parts.length > 1 ? parseFloat(parts[1], 1.0f) : 1.0f;
            float pitch  = parts.length > 2 ? parseFloat(parts[2], 1.0f) : 1.0f;

            if (target != null) {
                target.playSound(target.getLocation(), sound, volume, pitch);
            } else {
                for (UUID uuid : session.getPlayers()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) p.playSound(p.getLocation(), sound, volume, pitch);
                }
            }
        });

        // teleport-to-spawn:
        registerAction("teleport-to-spawn", (session, target, args, ctx) -> {
            if (target == null) return;
            Location spawn = session.getArena().nextSpawn();
            if (spawn != null) target.teleport(spawn);
        });

        // teleport-to-lobby:
        registerAction("teleport-to-lobby", (session, target, args, ctx) -> {
            if (target == null) return;
            if (session.getConfig().getLobby() != null) {
                Location loc = session.getConfig().getLobby().toBukkitLocation();
                if (loc != null) target.teleport(loc);
            }
        });

        // respawn:
        // FREEPLAYの場合はロビー（待機場所）へ、通常モードはアリーナのスポーンへテレポートする。
        registerAction("respawn", (session, target, args, ctx) -> {
            if (target == null) return;
            session.respawnPlayer(target);
        });

        // end-game:
        registerAction("end-game", (session, target, args, ctx) -> {
            session.endGame("スクリプトによる強制終了");
        });

        // set-variable: varName value
        registerAction("set-variable", (session, target, args, ctx) -> {
            String[] parts = args.trim().split("\\s+", 2);
            if (parts.length < 2) return;
            session.setVariable(parts[0], parts[1]);
        });

        // add-score: amount
        registerAction("add-score", (session, target, args, ctx) -> {
            if (target == null) return;
            int amount = parseInt(args.trim(), 1);
            session.addScore(target, amount);
        });
    }

    // -------------------------------------------------------
    // ユーティリティ
    // -------------------------------------------------------

    /**
     * &カラーコードを含む文字列を Adventure Component に変換する。
     */
    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    /**
     * Adventure Title を組み立てる。
     * タイク数（ticks）を Duration に変換して Times に渡す。
     */
    private static Title buildTitle(String titleStr, String subtitleStr,
                                    int fadeInTicks, int stayTicks, int fadeOutTicks) {
        Title.Times times = Title.Times.times(
                Duration.ofMillis(fadeInTicks  * 50L),
                Duration.ofMillis(stayTicks    * 50L),
                Duration.ofMillis(fadeOutTicks * 50L)
        );
        return Title.title(legacy(titleStr), legacy(subtitleStr), times);
    }

    private static String[] splitArgs(String args) {
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (char c : args.toCharArray()) {
            if (c == '"') { inQuote = !inQuote; cur.append(c); }
            else if (c == ' ' && !inQuote) {
                if (!cur.isEmpty()) { result.add(cur.toString()); cur.setLength(0); }
            } else { cur.append(c); }
        }
        if (!cur.isEmpty()) result.add(cur.toString());
        return result.toArray(new String[0]);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static float parseFloat(String s, float def) {
        try { return Float.parseFloat(s); } catch (NumberFormatException e) { return def; }
    }
}
