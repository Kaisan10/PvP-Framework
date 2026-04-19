package pvp.framework.gui;

import pvp.framework.PvPFramework;
import pvp.framework.i18n.MessageManager;
import pvp.framework.session.GameSession;
import pvp.framework.session.GameState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * WAITING / COUNTDOWN 状態のゲームメニュー（メニューコンパス）を管理するリスナー。
 * FREEPLAY の退出コンパス・退出確認 GUI は StateValidator が管理する。
 */
public class LobbyMenuListener implements Listener {

    private static final NamespacedKey COMPASS_KEY =
            new NamespacedKey("pvpframework", "lobby_menu");

    private final PvPFramework plugin;

    public LobbyMenuListener(PvPFramework plugin) {
        this.plugin = plugin;
    }

    private MessageManager mm() { return plugin.getMessageManager(); }

    private Component menuTitle() {
        return colorize(mm().get("gui.game-menu-title"));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!e.getView().title().equals(menuTitle())) return;

        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 9) return;

        if (slot == 0) {
            player.closeInventory();
            plugin.getSessionManager().leaveSession(player);
            player.sendMessage(colorize(mm().getPrefixed("command.leave.left")));
        }
    }

    public void openMenu(Player player, GameSession session) {
        Inventory inv = Bukkit.createInventory(null, 9, menuTitle());

        ItemStack leave = new ItemStack(Material.RED_BED);
        ItemMeta leaveMeta = leave.getItemMeta();
        leaveMeta.displayName(colorize(mm().get("gui.lobby-leave-name")));
        leaveMeta.lore(List.of(colorize(mm().get("gui.lobby-leave-lore"))));
        leave.setItemMeta(leaveMeta);
        inv.setItem(0, leave);

        int waiting = session.getMinPlayers() - session.getPlayerCount();
        String status;
        if (session.getState() == GameState.COUNTDOWN) {
            status = mm().get("gui.lobby-status-countdown");
        } else if (waiting > 0) {
            status = mm().get("gui.lobby-status-waiting", "count", String.valueOf(waiting));
        } else {
            status = mm().get("gui.lobby-status-ready");
        }

        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(colorize("&e&l" + session.getGameId()));
        infoMeta.lore(List.of(
                colorize(mm().get("gui.lobby-info-lore-players",
                        "current", String.valueOf(session.getPlayerCount()),
                        "max",     String.valueOf(session.getMaxPlayers()))),
                colorize(mm().get("gui.lobby-info-lore-state", "status", status)),
                colorize("&8" + session.getSessionId())
        ));
        info.setItemMeta(infoMeta);
        inv.setItem(4, info);

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(colorize("&r"));
        filler.setItemMeta(fillerMeta);
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        player.openInventory(inv);
    }

    private static Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
