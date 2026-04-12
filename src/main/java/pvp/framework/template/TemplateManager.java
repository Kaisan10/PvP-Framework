package pvp.framework.template;

import pvp.framework.PvPFramework;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * game-templates/ フォルダのサンプルYAMLをデータフォルダへ自動展開するクラス。
 *
 * <ul>
 *   <li>プラグイン起動時に setup() を呼ぶと、JAR同梱のテンプレートが
 *       plugins/PvPFramework/game-templates/ にコピーされる（上書きなし）。</li>
 *   <li>/pvpf template list でテンプレート一覧を表示。</li>
 *   <li>/pvpf template copy &lt;template&gt; &lt;new-id&gt; で
 *       games/ フォルダにIDを書き換えてコピー。</li>
 * </ul>
 */
public class TemplateManager {

    /** JAR内のテンプレートリソースパス一覧 */
    private static final List<String> BUNDLED = List.of(
            "game-templates/ffa_example.yml",
            "game-templates/tdm_example.yml",
            "game-templates/last_standing_example.yml"
    );

    private final PvPFramework plugin;
    private final Logger log;

    public TemplateManager(PvPFramework plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
    }

    // -------------------------------------------------------
    // 起動時セットアップ
    // -------------------------------------------------------

    /**
     * JAR同梱テンプレートをデータフォルダにコピーする（ファイルが存在しない場合のみ）。
     */
    public void setup() {
        for (String resourcePath : BUNDLED) {
            File dest = new File(plugin.getDataFolder(), resourcePath);
            if (!dest.exists()) {
                plugin.saveResource(resourcePath, false); // replace=false: 上書きしない
                log.info("Extracted template: " + resourcePath);
            }
        }
    }

    // -------------------------------------------------------
    // テンプレート一覧
    // -------------------------------------------------------

    /**
     * データフォルダ内の game-templates/ に存在するテンプレート名（拡張子なし）を返す。
     */
    public List<String> listTemplates() {
        File dir = new File(plugin.getDataFolder(), "game-templates");
        if (!dir.exists() || !dir.isDirectory()) return List.of();

        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) return List.of();

        List<String> names = new ArrayList<>();
        for (File f : files) {
            names.add(f.getName().replace(".yml", ""));
        }
        Collections.sort(names);
        return names;
    }

    // -------------------------------------------------------
    // テンプレートコピー
    // -------------------------------------------------------

    /**
     * game-templates/&lt;templateName&gt;.yml を読み込み、
     * id を newGameId に書き換えて games/&lt;newGameId&gt;.yml として保存する。
     *
     * @param templateName テンプレート名（拡張子なし）
     * @param newGameId    新しいゲームID（= 出力ファイル名）
     * @return 成功した場合 true
     */
    public boolean copyTemplate(String templateName, String newGameId) {
        File src = new File(plugin.getDataFolder(),
                "game-templates/" + templateName + ".yml");
        if (!src.exists()) {
            log.warning("Template not found: " + templateName);
            return false;
        }

        // games/ ディレクトリを保証
        File gamesDir = new File(plugin.getDataFolder(), "games");
        if (!gamesDir.exists()) gamesDir.mkdirs();

        File dest = new File(gamesDir, newGameId + ".yml");
        if (dest.exists()) {
            log.warning("Destination already exists: games/" + newGameId + ".yml");
            return false;
        }

        // テキストとして読み込み、id: の行だけ書き換える
        try {
            String content = readText(src);
            // "id: ffa_example" → "id: <newGameId>"（最初の出現のみ）
            content = content.replaceFirst(
                    "(?m)^id:.*$",
                    "id: " + newGameId
            );
            writeText(dest, content);
            log.info("Copied template '" + templateName + "' → games/" + newGameId + ".yml");
            return true;
        } catch (IOException e) {
            log.severe("Failed to copy template: " + e.getMessage());
            return false;
        }
    }

    /**
     * コピー先ファイルが既に存在するか確認する。
     */
    public boolean gameFileExists(String gameId) {
        return new File(plugin.getDataFolder(), "games/" + gameId + ".yml").exists();
    }

    // -------------------------------------------------------
    // ユーティリティ
    // -------------------------------------------------------
    private String readText(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private void writeText(File file, String content) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(content);
        }
    }
}
