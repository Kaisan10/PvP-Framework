package pvp.framework.kit;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class Kit {

    private final String id;
    private final List<ItemStack> items;
    private final ItemStack[] armor; // [boots, leggings, chestplate, helmet]
    private final List<PotionEffect> effects;

    private Kit(String id, List<ItemStack> items, ItemStack[] armor, List<PotionEffect> effects) {
        this.id = id;
        this.items = items;
        this.armor = armor;
        this.effects = effects;
    }

    public static Kit fromConfig(YamlConfiguration cfg, String fileName, Logger log) {
        String id = cfg.getString("id");
        if (id == null) {
            log.warning("Kit file '" + fileName + "' is missing 'id'. Skipping.");
            return null;
        }

        // Items
        List<ItemStack> items = new ArrayList<>();
        if (cfg.isList("items")) {
            for (String entry : cfg.getStringList("items")) {
                ItemStack stack = parseItemEntry(entry, log);
                if (stack != null) items.add(stack);
            }
        }

        // Armor [boots, leggings, chestplate, helmet]
        ItemStack[] armor = new ItemStack[4];
        ConfigurationSection armorSec = cfg.getConfigurationSection("armor");
        if (armorSec != null) {
            armor[0] = parseMaterial(armorSec.getString("boots"), log);
            armor[1] = parseMaterial(armorSec.getString("leggings"), log);
            armor[2] = parseMaterial(armorSec.getString("chestplate"), log);
            armor[3] = parseMaterial(armorSec.getString("helmet"), log);
        }

        // Effects
        List<PotionEffect> effects = new ArrayList<>();
        if (cfg.isList("effects")) {
            for (String entry : cfg.getStringList("effects")) {
                PotionEffect effect = parseEffect(entry, log);
                if (effect != null) effects.add(effect);
            }
        }

        return new Kit(id, items, armor, effects);
    }

    public void apply(Player player) {
        player.getInventory().clear();
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));

        for (ItemStack item : items) {
            player.getInventory().addItem(item.clone());
        }

        player.getInventory().setBoots(armor[0]);
        player.getInventory().setLeggings(armor[1]);
        player.getInventory().setChestplate(armor[2]);
        player.getInventory().setHelmet(armor[3]);

        for (PotionEffect effect : effects) {
            player.addPotionEffect(effect);
        }
    }

    // "IRON_SWORD 1" or "GOLDEN_APPLE 3"
    private static ItemStack parseItemEntry(String entry, Logger log) {
        String[] parts = entry.trim().split("\\s+");
        Material mat = Material.matchMaterial(parts[0]);
        if (mat == null) {
            log.warning("Unknown material: " + parts[0]);
            return null;
        }
        int amount = parts.length > 1 ? parseInt(parts[1], 1) : 1;
        return new ItemStack(mat, amount);
    }

    private static ItemStack parseMaterial(String name, Logger log) {
        if (name == null || name.isBlank()) return null;
        Material mat = Material.matchMaterial(name);
        if (mat == null) {
            log.warning("Unknown armor material: " + name);
            return null;
        }
        return new ItemStack(mat);
    }

    // "SPEED 600 0" → duration ticks, amplifier
    private static PotionEffect parseEffect(String entry, Logger log) {
        String[] parts = entry.trim().split("\\s+");
        if (parts.length < 1) return null;

        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(parts[0].toLowerCase()));
        if (type == null) {
            log.warning("Unknown potion effect: " + parts[0]);
            return null;
        }
        int duration  = parts.length > 1 ? parseInt(parts[1], 600) : 600;
        int amplifier = parts.length > 2 ? parseInt(parts[2], 0)   : 0;
        return new PotionEffect(type, duration, amplifier);
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return fallback; }
    }

    public String getId() { return id; }
}
