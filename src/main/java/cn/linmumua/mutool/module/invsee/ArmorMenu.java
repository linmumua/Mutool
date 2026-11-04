package cn.linmumua.mutool.module.invsee;

import cn.linmumua.mutool.menu.Menu;
import cn.linmumua.mutool.menu.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import taboolib.library.configuration.ConfigurationSection;
import taboolib.module.configuration.Configuration;

import java.util.List;

/**
 * Read-only armour display built from {@code module/invsee/menu/armor.yml}.
 *
 * <p>The layout is driven entirely by the YAML {@code slots} grid (one string per row, one char per
 * column). Every character maps to either an equipment slot ({@code H/C/L/B/O} by default, i.e.
 * helmet / chestplate / leggings / boots / off-hand) or a static decoration item declared under
 * {@code items}. Equipment cells show the target's actual (cloned) item when present; empty
 * equipment cells fall back to the placeholder declared for that character (e.g. a barrier).</p>
 *
 * <p><strong>Folia note:</strong> {@link ArmorSnapshot#capture(PlayerInventory)} must run on the
 * target's region thread. {@link #build()} only reads the detached, cloned snapshot on the viewer's
 * region thread, so no cross-region inventory access happens after capture. Unlike the legacy
 * MumuTools menu this port has <em>no</em> click handling and <em>no</em> write-back — it is a pure
 * read-only view.</p>
 */
final class ArmorMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Configuration config;
    private final ArmorSnapshot snapshot;

    private ArmorMenu(@NotNull Configuration config, @NotNull ArmorSnapshot snapshot, @NotNull String targetName) {
        super(title(config, targetName), size(config));
        this.config = config;
        this.snapshot = snapshot;
    }

    /**
     * Capture the target's equipment on the current (target region) thread and build the menu.
     * Returns a menu whose {@link #open(Player)} will render the snapshot on the viewer's thread.
     */
    static @NotNull ArmorMenu capture(@NotNull Configuration config, @NotNull PlayerInventory inventory,
                                      @NotNull String targetName) {
        return new ArmorMenu(config, ArmorSnapshot.capture(inventory), targetName);
    }

    private static Component title(Configuration config, String player) {
        String raw = config.getString("title", "");
        if (raw == null) {
            raw = "";
        }
        return MINI.deserialize(raw.replace("{player}", player));
    }

    private static int size(Configuration config) {
        List<String> slots = config.getStringList("slots");
        int rows = slots == null ? 0 : slots.size();
        return Math.max(rows, 1) * 9;
    }

    @Override
    protected void build() {
        List<String> rows = config.getStringList("slots");
        if (rows == null) {
            return;
        }

        ConfigurationSection equipment = config.getConfigurationSection("equipment_slots");
        String helmetKey = keyOf(equipment, "helmet", "H");
        String chestplateKey = keyOf(equipment, "chestplate", "C");
        String leggingsKey = keyOf(equipment, "leggings", "L");
        String bootsKey = keyOf(equipment, "boots", "B");
        String offhandKey = keyOf(equipment, "offhand", "O");

        ConfigurationSection items = config.getConfigurationSection("items");

        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            if (line == null) {
                continue;
            }
            int columns = Math.min(line.length(), 9);
            for (int column = 0; column < columns; column++) {
                char ch = line.charAt(column);
                if (Character.isWhitespace(ch)) {
                    continue;
                }
                int slot = row * 9 + column;
                if (slot >= getSize()) {
                    continue;
                }

                String key = String.valueOf(ch);
                ItemStack stack = null;
                if (key.equals(helmetKey)) {
                    stack = snapshot.helmet();
                } else if (key.equals(chestplateKey)) {
                    stack = snapshot.chestplate();
                } else if (key.equals(leggingsKey)) {
                    stack = snapshot.leggings();
                } else if (key.equals(bootsKey)) {
                    stack = snapshot.boots();
                } else if (key.equals(offhandKey)) {
                    stack = snapshot.offhand();
                }

                if (stack == null) {
                    stack = MenuItems.build(items == null ? null : items.getConfigurationSection(key));
                }
                if (stack != null && !stack.getType().isAir()) {
                    setItem(slot, stack);
                }
            }
        }
    }

    private static String keyOf(ConfigurationSection section, String path, String fallback) {
        if (section == null) {
            return fallback;
        }
        String value = section.getString(path);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /**
     * Detached copy of a player's five equipment slots. Every field is an already-cloned
     * {@link ItemStack} (or {@code null} when that slot is empty), captured on the target's region
     * thread so it can be safely read later on the viewer's thread.
     */
    record ArmorSnapshot(ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots, ItemStack offhand) {

        static @NotNull ArmorSnapshot capture(@NotNull PlayerInventory inventory) {
            return new ArmorSnapshot(
                    clone(inventory.getHelmet()),
                    clone(inventory.getChestplate()),
                    clone(inventory.getLeggings()),
                    clone(inventory.getBoots()),
                    clone(inventory.getItemInOffHand())
            );
        }

        private static ItemStack clone(ItemStack stack) {
            return stack == null || stack.getType().isAir() ? null : stack.clone();
        }
    }
}
