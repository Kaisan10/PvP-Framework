package pvp.framework.i18n;

import pvp.framework.PvPFramework;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * 多言語対応メッセージマネージャー。
 *
 * <p>config.yml の {@code locale} キー（例: {@code ja_JP}）に基づき、
 * {@code plugins/PvPFramework/messages/<locale>.yml} を読み込む。
 * ファイルが存在しない場合は JAR 内の同名リソースから自動生成する。
 *
 * <p>使い方:
 * <pre>{@code
 * // プレースホルダーなし
 * player.sendMessage(plugin.getMessageManager().get("command.no-permission"));
 *
 * // プレースホルダーあり（キー・値の対で可変長引数）
 * player.sendMessage(plugin.getMessageManager().get("command.join.success", "gameId", gameId));
 * }</pre>
 */
public class MessageManager {

    private final PvPFramework plugin;
    private final Logger log;

    /** 現在読み込んでいるロケール（例: ja_JP） */
    private String locale;

    /** 現在アクティブなメッセージ設定 */
    private YamlConfiguration messages;

    /** JAR 内に同梱されたフォールバック用デフォルトメッセージ */
    private YamlConfiguration defaults;

    public MessageManager(PvPFramework plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    // -------------------------------------------------------
    // ロード / リロード
    // -------------------------------------------------------

    /**
     * config.yml の locale を読み取り、対応するメッセージファイルをロードする。
     * ConfigManager#load() から呼び出すこと。
     */
    public void reload() {
        this.locale = plugin.getConfig().getString("locale", "ja_JP");

        // デフォルト（JAR 内）を先に読む
        defaults = loadFromJar(locale);

        // plugins/PvPFramework/messages/<locale>.yml を確保してから読む
        File msgFile = getMessageFile(locale);
        saveDefaultIfAbsent(locale, msgFile);
        messages = YamlConfiguration.loadConfiguration(msgFile);

        // JAR内デフォルトをフォールバックとしてセット
        if (defaults != null) {
            messages.setDefaults(defaults);
        }

        log.info("MessageManager loaded locale: " + locale + " (" + msgFile.getPath() + ")");
    }

    // -------------------------------------------------------
    // メッセージ取得
    // -------------------------------------------------------

    /**
     * 指定キーのメッセージを取得し、プレースホルダーを置換して返す。
     *
     * @param key          ドット区切りメッセージキー（例: "command.join.success"）
     * @param replacements キー・値の交互可変長引数（例: "gameId", "my-game"）
     * @return 色コード {@code §} 適用済みの文字列。キーが存在しない場合は {@code "<missing: key>"}。
     */
    public String get(String key, String... replacements) {
        String raw = null;

        if (messages != null) {
            raw = messages.getString(key);
        }
        if (raw == null && defaults != null) {
            raw = defaults.getString(key);
        }
        if (raw == null) {
            log.warning("Missing message key: " + key);
            return "<missing: " + key + ">";
        }

        // プレースホルダー置換
        if (replacements.length >= 2) {
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                raw = raw.replace("{" + replacements[i] + "}", replacements[i + 1]);
            }
        }

        return raw;
    }

    /**
     * prefix + メッセージ を結合して返す。
     *
     * @param key          メッセージキー
     * @param replacements プレースホルダー（キー・値の対）
     * @return prefix付きメッセージ
     */
    public String getPrefixed(String key, String... replacements) {
        return get("prefix") + get(key, replacements);
    }

    // -------------------------------------------------------
    // ファイル管理
    // -------------------------------------------------------

    private File getMessageFile(String locale) {
        File dir = new File(plugin.getDataFolder(), "messages");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, locale + ".yml");
    }

    /** JAR 内のリソースから File にコピーし、なければ何もしない */
    private void saveDefaultIfAbsent(String locale, File dest) {
        if (dest.exists()) return;
        String resourcePath = "messages/" + locale + ".yml";
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                // JAR内に存在しない場合は空ファイルを作成しておく
                log.warning("No bundled message file found for locale: " + locale);
                try {
                    //noinspection ResultOfMethodCallIgnored
                    dest.createNewFile();
                } catch (IOException e) {
                    log.severe("Failed to create message file: " + dest.getPath());
                }
                return;
            }
            plugin.saveResource(resourcePath, false);
        } catch (IOException e) {
            log.warning("Failed to close resource stream for: " + resourcePath);
        }
    }

    /** JAR 内のリソースを YamlConfiguration として読み込む（外部ファイル不使用）*/
    private YamlConfiguration loadFromJar(String locale) {
        String resourcePath = "messages/" + locale + ".yml";
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warning("Failed to load bundled messages for: " + locale);
            return null;
        }
    }

    // -------------------------------------------------------
    // getter
    // -------------------------------------------------------

    public String getLocale() { return locale; }
}
