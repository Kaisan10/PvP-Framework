package pvp.framework.session;

import pvp.framework.PvPFramework;
import pvp.framework.mode.YamlGameSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StateValidator implements Listener {

    private final PvPFramework plugin;

    private static final NamespacedKey MENU_COMPASS_KEY =
            new NamespacedKey("pvpframework", "lobby_menu");
    private static final NamespacedKey ARENA_ENTER_KEY =
            new NamespacedKey("pvpframework", "arena_enter");
    private static final NamespacedKey LEAVE_CONFIRM_YES_KEY =
            new NamespacedKey("pvpframework", "leave_confirm_yes");
    private static final NamespacedKey LEAVE_CONFIRM_NO_KEY =
            new NamespacedKey("pvpframework", "leave_confirm_no");

    private final Map<UUID, Integer> pendingLeaveTasks = new HashMap<>();

    public StateValidator(PvPFramework plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.COMPASS) return;
        if (!item.hasItemMeta()) return;

        var pdc = item.getItemMeta().getPersistentDataContainer();

        if (pdc.has(MENU_COMPASS_KEY, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            GameSession session = plugin.getSessionManager().getSessionByPlayer(player);
            if (session == null) return;

            // [FIX] FREEPLAY と通常モード（FFA/TDM/LAST_STANDING）で分岐
            if (session instanceof YamlGameSession ys
                    && "FREEPLAY".equals(ys.getConfig().getMode())) {
                // FREEPLAY: アリーナ入場メニューを開く
                if (session.getState() != GameState.ACTIVE) return;
                openFreeplayMenu(player, ys);
            } else {
                // FFA / TDM / LAST_STANDING: 通常のロビーメニューを開く
                if (session.getState() != GameState.WAITING
                        && session.getState() != GameState.COUNTDOWN) return;
                plugin.getLobbyMenuListener().openMenu(player, session);
            }
            return;
        }

        if (pdc.has(GameSession.LEAVE_COMPASS_KEY, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            if (pendingLeaveTasks.containsKey(player.getUniqueId())) {
                player.sendMessage(colorize("&e[PvPF] 退出カウントダウン中です..."));
                return;
            }
            openLeaveConfirmMenu(player);
        }
    }

    private void openFreeplayMenu(Player player, YamlGameSession session) {
        Component title = colorize("&b&l" + session.getConfig().getDisplayName());
        Inventory gui = Bukkit.createInventory(null, 9, title);

        ItemStack enterItem = new ItemStack(Material.NETHER_STAR);
        ItemMeta enterMeta = enterItem.getItemMeta();
        if (enterMeta != null) {
            enterMeta.displayName(colorize("&a&l" + session.getConfig().getDisplayName()));
            enterMeta.lore(List.of(colorize("&7クリックでアリーナへ入場")));
            enterMeta.getPersistentDataContainer().set(
                    ARENA_ENTER_KEY, PersistentDataType.STRING, session.getSessionId());
            enterItem.setItemMeta(enterMeta);
        }
        gui.setItem(4, enterItem);
        player.openInventory(gui);
    }

    private void openLeaveConfirmMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, colorize("&c&l退出しますか？"));

        ItemStack yesItem = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta yesMeta = yesItem.getItemMeta();
        if (yesMeta != null) {
            yesMeta.displayName(colorize("&a&lはい"));
            yesMeta.lore(List.of(colorize("&75秒後に待機場所へ戻ります")));
            yesMeta.getPersistentDataContainer().set(
                    LEAVE_CONFIRM_YES_KEY, PersistentDataType.BYTE, (byte) 1);
            yesItem.setItemMeta(yesMeta);
        }

        ItemStack noItem = new ItemStack(Material.RED_CONCRETE);
        ItemMeta noMeta = noItem.getItemMeta();
        if (noMeta != null) {
            noMeta.displayName(colorize("&c&lいいえ"));
            noMeta.lore(List.of(colorize("&7キャンセル")));
            noMeta.getPersistentDataContainer().set(
                    LEAVE_CONFIRM_NO_KEY, PersistentDataType.BYTE, (byte) 1);
            noItem.setItemMeta(noMeta);
        }

        gui.setItem(2, yesItem);
        gui.setItem(6, noItem);
        player.openInventory(gui);
    }

    private void startLeaveCountdown(Player player) {
        cancelLeaveCountdown(player.getUniqueId());
        player.sendMessage(colorize("&e[PvPF] &75秒後に待機場所へ戻ります..."));

        final int[] remaining = {5};
        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!player.isOnline() || !plugin.getSessionManager().isInSession(player)) {
                cancelLeaveCountdown(player.getUniqueId());
                return;
            }
            if (remaining[0] <= 0) {
                cancelLeaveCountdown(player.getUniqueId());
                returnToWaitingArea(player);
                return;
            }
            player.sendMessage(colorize("&e" + remaining[0] + "..."));
            remaining[0]--;
        }, 0L, 20L);

        pendingLeaveTasks.put(player.getUniqueId(), taskId);
    }

    private void cancelLeaveCountdown(UUID uuid) {
        Integer taskId = pendingLeaveTasks.remove(uuid);
        if (taskId != null) Bukkit.getScheduler().cancelTask(taskId);
    }

    /**
     * プレイヤーをゲームの待機場所（lobby）へ戻す。
     * セッションからは退出せず、FREEPLAYのロビー待機状態に戻る。
     */
    private void returnToWaitingArea(Player player) {
        GameSession session = plugin.getSessionManager().getSessionByPlayer(player);
        if (session == null) return;
        if (!(session instanceof YamlGameSession ys)) return;

        Location waitingLoc = ys.getConfig().getLobby() != null
                ? ys.getConfig().getLobby().toBukkitLocation()
                : plugin.getConfigManager().getGlobalLobby();
        if (waitingLoc != null) player.teleport(waitingLoc);

        // 退出コンパスを外してメニューコンパスに戻す
        player.getInventory().setItem(8, null);
        GameSession.giveMenuCompass(player);
        player.sendMessage(colorize("&b[PvPF] &a待機場所へ戻りました。"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked != null && clicked.hasItemMeta()) {
            var pdc = clicked.getItemMeta().getPersistentDataContainer();

            if (pdc.has(ARENA_ENTER_KEY, PersistentDataType.STRING)) {
                event.setCancelled(true);
                player.closeInventory();
                String sessionId = pdc.get(ARENA_ENTER_KEY, PersistentDataType.STRING);
                GameSession session = plugin.getSessionManager().getSession(sessionId);
                if (session instanceof YamlGameSession ys
                        && "FREEPLAY".equals(ys.getConfig().getMode())
                        && session.getState() == GameState.ACTIVE) {
                    GameSession.removeMenuCompass(player);
                    ys.teleportToArena(player);
                    GameSession.giveLeaveCompass(player);
                }
                return;
            }

            if (pdc.has(LEAVE_CONFIRM_YES_KEY, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                player.closeInventory();
                startLeaveCountdown(player);
                return;
            }

            if (pdc.has(LEAVE_CONFIRM_NO_KEY, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                player.closeInventory();
                player.sendMessage(colorize("&7[PvPF] 退出をキャンセルしました。"));
                return;
            }
        }

        GameSession session = plugin.getSessionManager().getSessionByPlayer(player);
        if (session != null && session.getState() != GameState.ACTIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        GameSession session = plugin.getSessionManager().getSessionByPlayer(victim);
        if (session == null) return;
        if (session.getState() != GameState.ACTIVE && session.getState() != GameState.ENDING) return;

        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);

        cancelLeaveCountdown(victim.getUniqueId());

        if (!(session instanceof YamlGameSession ys)) return;

        if ("FREEPLAY".equals(ys.getConfig().getMode())) {
            // FREEPLAY: 即リスポーン（PlayerRespawnEvent でlobbyへTP）
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                if (victim.isOnline() && victim.isDead()) victim.spigot().respawn();
            }, 1L);
        } else if (ys.getConfig().isRespawnEnabled()) {
            long delayTicks = Math.max(1L, (long) ys.getConfig().getRespawnDelay() * 20L);
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                if (victim.isOnline() && victim.isDead()) victim.spigot().respawn();
            }, delayTicks);
        }
    }

    /**
     * リスポーン後のテレポート処理。
     * FREEPLAYの場合はゲームの待機場所（lobby）へ、
     * 通常モードの場合はアリーナのスポーンへテレポートする。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        GameSession session = plugin.getSessionManager().getSessionByPlayer(player);
        if (session == null) return;
        if (session.getState() != GameState.ACTIVE) return;
        if (!(session instanceof YamlGameSession ys)) return;

        if ("FREEPLAY".equals(ys.getConfig().getMode())) {
            // FREEPLAYはlobby（待機場所）へ戻す
            Location waitingLoc = ys.getConfig().getLobby() != null
                    ? ys.getConfig().getLobby().toBukkitLocation()
                    : plugin.getConfigManager().getGlobalLobby();
            if (waitingLoc != null) {
                event.setRespawnLocation(waitingLoc);
            }
            // 退出コンパスを外してメニューコンパスに戻す（1tick後に実行）
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                if (player.isOnline()) {
                    player.getInventory().setItem(8, null);
                    GameSession.giveMenuCompass(player);
                }
            }, 1L);
        } else {
            // 通常モードはアリーナのスポーンへ
            Location spawn = session.nextUniqueSpawn();
            if (spawn != null) event.setRespawnLocation(spawn);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        GameSession session = plugin.getSessionManager().getSessionByPlayer(event.getPlayer());
        if (session == null) return;
        boolean allow = (session instanceof YamlGameSession ys) && ys.getConfig().isAllowBuilding();
        if (!allow) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        GameSession session = plugin.getSessionManager().getSessionByPlayer(event.getPlayer());
        if (session == null) return;
        boolean allow = (session instanceof YamlGameSession ys) && ys.getConfig().isAllowBuilding();
        if (!allow) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player)) return;
        GameSession session = plugin.getSessionManager().getSessionByPlayer(victim);
        if (session == null) return;
        if (session.getState() != GameState.ACTIVE) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        GameSession session = plugin.getSessionManager().getSessionByPlayer(event.getPlayer());
        if (session == null) return;
        if (session.getState() != GameState.ACTIVE) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        GameSession session = plugin.getSessionManager().getSessionByPlayer(player);
        if (session == null) return;
        if (session.getState() != GameState.ACTIVE) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
         && event.getFrom().getBlockY() == event.getTo().getBlockY()
         && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        GameSession session = plugin.getSessionManager().getSessionByPlayer(player);
        if (session == null) return;

        if (session.getState() == GameState.ACTIVE && session.getArena() != null) {
            if (!session.getArena().isInBounds(event.getTo())) {
                cancelLeaveCountdown(player.getUniqueId());
                plugin.getSessionManager().leaveSession(player);
                player.sendMessage(colorize("&c[PvPF] アリーナ境界外に出たため、ゲームから除外されました。"));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelLeaveCountdown(event.getPlayer().getUniqueId());
        plugin.getSessionManager().leaveSession(event.getPlayer());
    }

    private static Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
