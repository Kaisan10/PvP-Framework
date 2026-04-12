package pvp.framework.npc;

import pvp.framework.PvPFramework;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * FancyNpcs との連携を管理するクラス。
 *
 * NPC は内部ユニークID（pvpf_00001 等）で FancyNpcs に登録し、
 * 表示名（日本語含む）は displayName として別管理する。
 * これにより同じ表示名のNPCを複数作成でき、
 * 同名の場合は作成が古い順（ID昇順）からIDが増えていく。
 *
 * npc-links.yml の構造:
 *   links:
 *     pvpf_00001:
 *       displayName: "アリーナNPC"
 *       gameId: "my-game"
 *     pvpf_00002:
 *       displayName: "アリーナNPC"   # 同名でもOK
 *       gameId: "my-game"
 *
 * 特殊なゲームID "__LOBBY__" はロビーへ戻すNPCを表す。
 *
 * 旧形式（links.<name>: gameId）との後方互換性あり（自動マイグレーション）。
 */
public class FancyNpcManager {

    /** ロビーに戻るNPCに使う予約済みゲームID */
    public static final String LOBBY_NPC_GAME_ID = "__LOBBY__";

    private final PvPFramework plugin;
    private final File linksFile;
    private YamlConfiguration linksConfig;

    /** 内部UID → NpcEntry */
    private final Map<String, NpcEntry> npcEntries = new LinkedHashMap<>();

    /** 次のUID連番（サーバー再起動をまたいで単調増加） */
    private int nextNpcId = 0;

    // -------------------------------------------------------
    // 内部データクラス
    // -------------------------------------------------------

    public static class NpcEntry {
        public final String displayName;
        public final String gameId;

        public NpcEntry(String displayName, String gameId) {
            this.displayName = displayName;
            this.gameId      = gameId;
        }
    }

    // -------------------------------------------------------
    // コンストラクタ
    // -------------------------------------------------------

    public FancyNpcManager(PvPFramework plugin) {
        this.plugin    = plugin;
        this.linksFile = new File(plugin.getDataFolder(), "npc-links.yml");
        load();
    }

    // -------------------------------------------------------
    // ロード / セーブ
    // -------------------------------------------------------

    public void load() {
        if (!linksFile.exists()) {
            try {
                linksFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("[FancyNpcManager] npc-links.yml の作成に失敗: " + e.getMessage());
            }
        }
        linksConfig = YamlConfiguration.loadConfiguration(linksFile);
        npcEntries.clear();
        int maxId = -1;

        if (linksConfig.isConfigurationSection("links")) {
            for (String key : linksConfig.getConfigurationSection("links").getKeys(false)) {
                Object rawValue = linksConfig.get("links." + key);

                if (rawValue instanceof String gameId) {
                    // 旧形式: links.<name>: "<gameId>"  → 表示名＝キー名でマイグレーション
                    npcEntries.put(key, new NpcEntry(key, gameId));
                    plugin.getLogger().info("[FancyNpcManager] 旧形式エントリをマイグレーション: " + key);
                } else {
                    // 新形式: links.<uid>.displayName / .gameId
                    String displayName = linksConfig.getString("links." + key + ".displayName");
                    String gameId      = linksConfig.getString("links." + key + ".gameId");
                    if (displayName != null && gameId != null) {
                        npcEntries.put(key, new NpcEntry(displayName, gameId));
                    }
                }

                // UID から最大連番を取得（pvpf_00001 形式）
                if (key.startsWith("pvpf_")) {
                    try {
                        int id = Integer.parseInt(key.substring(5));
                        maxId = Math.max(maxId, id);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // 次のUID = 既存最大 + 1
        nextNpcId = maxId + 1;

        plugin.getLogger().info("[FancyNpcManager] " + npcEntries.size() + " 個のNPCリンクを読み込みました。次のUID: pvpf_" + String.format("%05d", nextNpcId));
    }

    private void save() {
        linksConfig.set("links", null);
        for (Map.Entry<String, NpcEntry> entry : npcEntries.entrySet()) {
            String uid = entry.getKey();
            NpcEntry npcEntry = entry.getValue();
            linksConfig.set("links." + uid + ".displayName", npcEntry.displayName);
            linksConfig.set("links." + uid + ".gameId",      npcEntry.gameId);
        }
        try {
            linksConfig.save(linksFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[FancyNpcManager] npc-links.yml の保存に失敗: " + e.getMessage());
        }
    }

    /** 単調増加するユニークIDを生成する */
    private String generateUid() {
        return "pvpf_" + String.format("%05d", nextNpcId++);
    }

    // -------------------------------------------------------
    // NPC 作成（ゲーム参加用）
    // -------------------------------------------------------

    /**
     * NPCを作成してゲームIDとリンクする。
     * displayName は日本語含む任意の文字列を使用可能。
     * 同名NPCを複数作成した場合は作成順にUIDが付与される。
     *
     * @return 成功したら true
     */
    public boolean createNpc(String displayName, String gameId, Player creator, Location location) {
        if (!isFancyNpcsAvailable()) return false;

        if (plugin.getGameLoader().getConfig(gameId) == null) {
            creator.sendMessage("§c[PvPF] ゲームが見つかりません: " + gameId);
            return false;
        }

        return spawnNpc(displayName, gameId, creator, location);
    }

    // -------------------------------------------------------
    // NPC 作成（ロビー返還用）
    // -------------------------------------------------------

    /**
     * ロビーに戻るNPCを作成する。
     * クリックするとglobal lobbyへテレポートする。
     *
     * @return 成功したら true
     */
    public boolean createLobbyNpc(String displayName, Player creator, Location location) {
        if (!isFancyNpcsAvailable()) return false;
        return spawnNpc(displayName, LOBBY_NPC_GAME_ID, creator, location);
    }

    /**
     * FancyNpcs にNPCをスポーンしてリンクを保存する共通処理。
     * FancyNpcs の内部識別子にはユニークUID（pvpf_00001 等）を使い、
     * 表示名（日本語含む）は displayName として設定する。
     */
    private boolean spawnNpc(String displayName, String gameId, Player creator, Location location) {
        String uid      = generateUid();
        Location spawnLoc = location != null ? location : creator.getLocation();

        NpcData data = new NpcData(uid, creator.getUniqueId(), spawnLoc);
        data.setDisplayName("<aqua>" + displayName + "</aqua>");

        Npc npc = FancyNpcsPlugin.get().getNpcAdapter().apply(data);
        FancyNpcsPlugin.get().getNpcManager().registerNpc(npc);
        npc.create();
        npc.spawnForAll();

        npcEntries.put(uid, new NpcEntry(displayName, gameId));
        save();

        plugin.getLogger().info("[FancyNpcManager] NPC '" + displayName + "' (uid=" + uid + ") を作成し '" + gameId + "' にリンク。");
        return true;
    }

    // -------------------------------------------------------
    // NPC 削除
    // -------------------------------------------------------

    /**
     * 指定した表示名のNPCを削除する。
     * 同名NPCが複数存在する場合は最も古いもの（UID昇順で最初）を削除する。
     *
     * @return 成功したら true、NPCが見つからなければ false
     */
    public boolean deleteNpc(String displayName) {
        if (!isFancyNpcsAvailable()) return false;

        // 同名の中で最も古いUID（LinkedHashMap なので挿入順 = 作成順）
        String targetUid = npcEntries.entrySet().stream()
                .filter(e -> e.getValue().displayName.equals(displayName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        if (targetUid == null) return false;

        Npc npc = FancyNpcsPlugin.get().getNpcManager().getNpc(targetUid);
        if (npc != null) {
            FancyNpcsPlugin.get().getNpcManager().removeNpc(npc);
            npc.removeForAll();
        }

        npcEntries.remove(targetUid);
        save();

        plugin.getLogger().info("[FancyNpcManager] NPC '" + displayName + "' (uid=" + targetUid + ") を削除しました。");
        return true;
    }

    // -------------------------------------------------------
    // テレポート
    // -------------------------------------------------------

    /**
     * 指定した表示名のNPCの位置へプレイヤーをテレポートする。
     * 同名NPCが複数存在する場合は最も古いものを使う。
     *
     * @return 成功したら true
     */
    public boolean teleportToNpc(String displayName, Player player) {
        if (!isFancyNpcsAvailable()) return false;

        String targetUid = npcEntries.entrySet().stream()
                .filter(e -> e.getValue().displayName.equals(displayName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        if (targetUid == null) return false;

        Npc npc = FancyNpcsPlugin.get().getNpcManager().getNpc(targetUid);
        if (npc == null) return false;

        player.teleport(npc.getData().getLocation());
        return true;
    }

    // -------------------------------------------------------
    // 参照
    // -------------------------------------------------------

    /**
     * FancyNpcs 内部識別子（UID）からリンクされているゲームIDを返す。
     * NpcInteractEvent の npc.getData().getName() で取得したUIDを渡す。
     * リンクがなければ null。
     * "__LOBBY__" の場合はロビー返還NPC。
     */
    public String getGameId(String uid) {
        NpcEntry entry = npcEntries.get(uid);
        return entry != null ? entry.gameId : null;
    }

    /**
     * 全NPCの表示名の一覧を返す（重複除去済み）。
     * Tab補完や delete / teleport コマンドの候補として使用する。
     */
    public List<String> getDisplayNames() {
        List<String> names = new ArrayList<>();
        for (NpcEntry entry : npcEntries.values()) {
            if (!names.contains(entry.displayName)) {
                names.add(entry.displayName);
            }
        }
        return names;
    }

    /**
     * UID → gameId のマップを返す（後方互換・読み取り専用）。
     * Tab補完では {@link #getDisplayNames()} を使うこと。
     */
    public Map<String, String> getNpcGameLinks() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, NpcEntry> e : npcEntries.entrySet()) {
            result.put(e.getKey(), e.getValue().gameId);
        }
        return Collections.unmodifiableMap(result);
    }

    /** FancyNpcs がサーバーに導入されていて有効かどうか。 */
    public boolean isFancyNpcsAvailable() {
        return plugin.getServer().getPluginManager().getPlugin("FancyNpcs") != null
                && plugin.getServer().getPluginManager().getPlugin("FancyNpcs").isEnabled();
    }
}
