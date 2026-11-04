package cn.linmumua.mutool.module.back;

import cn.linmumua.mutool.database.entity.BackData;
import cn.linmumua.mutool.menu.Menu;
import cn.linmumua.mutool.menu.MenuItem;
import cn.linmumua.mutool.menu.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import taboolib.library.configuration.ConfigurationSection;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code /back menu} location-record list, built from {@code module/back/menu/back.yml}.
 *
 * <p>The layout is driven entirely by the YAML {@code slots} grid (one string per row, one
 * char per column). Characters equal to {@code keys.location} are filled with the player's
 * records (newest first); every other character maps to a static decoration item under
 * {@code items}. The record icon is described by {@code items.location} with the
 * {@code {year}}/{@code {type}}/... placeholders resolved per record. Clicking a record icon
 * teleports the player back to that location, Folia-safely via
 * {@link BackModule#teleportTo(Player, BackData)}.</p>
 *
 * <p>{@link #build()} runs on the player's region thread (see {@link Menu#open(Player)}), so
 * reading config and constructing {@link ItemStack}s is thread-safe.</p>
 */
final class BackMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final BackModule module;
    private final Player player;

    BackMenu(@NotNull BackModule module, @NotNull Player player) {
        super(title(module), size(module));
        this.module = module;
        this.player = player;
    }

    private static String title(@NotNull BackModule module) {
        String raw = module.menuConfig().getString("title", "");
        return raw == null ? "" : raw;
    }

    private static int size(@NotNull BackModule module) {
        List<String> slots = module.menuConfig().getStringList("slots");
        int rows = slots == null ? 0 : slots.size();
        return Math.max(rows, 1) * 9;
    }

    @Override
    protected void build() {
        List<String> rows = this.module.menuConfig().getStringList("slots");
        if (rows == null) {
            return;
        }
        String locationKey = this.module.menuConfig().getString("keys.location", "L");
        ConfigurationSection items = this.module.menuConfig().getConfigurationSection("items");
        ConfigurationSection locationItem = items == null ? null : items.getConfigurationSection("location");

        List<BackData> records = this.module.records(this.player.getUniqueId());
        int recordIndex = 0;

        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            if (line == null) {
                continue;
            }
            int columns = Math.min(line.length(), 9);
            for (int column = 0; column < columns; column++) {
                char glyph = line.charAt(column);
                if (Character.isWhitespace(glyph)) {
                    continue;
                }
                int slot = row * 9 + column;
                if (slot >= getSize()) {
                    continue;
                }
                String key = String.valueOf(glyph);

                if (key.equals(locationKey)) {
                    if (recordIndex >= records.size()) {
                        continue;
                    }
                    BackData data = records.get(recordIndex++);
                    ItemStack icon = this.buildRecordItem(locationItem, data);
                    if (icon != null && !icon.getType().isAir()) {
                        setItem(slot, new MenuItem(icon,
                                event -> this.module.teleportTo(this.player, data)));
                    }
                    continue;
                }

                ConfigurationSection itemSection = resolveItem(items, key);
                if (itemSection != null) {
                    ItemStack stack = MenuItems.build(itemSection);
                    if (stack != null && !stack.getType().isAir()) {
                        setItem(slot, stack);
                    }
                }
            }
        }
    }

    /** Exact item key first, then the first {@code key_<suffix>} variant (matches the source menu). */
    private static @Nullable ConfigurationSection resolveItem(@Nullable ConfigurationSection items, @NotNull String key) {
        if (items == null) {
            return null;
        }
        ConfigurationSection exact = items.getConfigurationSection(key);
        if (exact != null) {
            return exact;
        }
        // Prefer the neutral "_1" variant (e.g. P_1 / N_1 gray pane) so the decorative
        // corners don't depend on map iteration order.
        ConfigurationSection preferred = items.getConfigurationSection(key + "_1");
        if (preferred != null) {
            return preferred;
        }
        for (String candidate : items.getKeys(false)) {
            if (candidate.startsWith(key + "_")) {
                ConfigurationSection section = items.getConfigurationSection(candidate);
                if (section != null) {
                    return section;
                }
            }
        }
        return null;
    }

    /**
     * Build one record icon from the {@code items.location} template, resolving every
     * placeholder (time parts, {@code type} display name, world / server / coordinates).
     */
    private @NotNull ItemStack buildRecordItem(@Nullable ConfigurationSection section, @NotNull BackData data) {
        Map<String, String> placeholders = this.placeholders(data);

        if (section == null) {
            ItemStack fallback = new ItemStack(Material.RED_BED);
            ItemMeta meta = fallback.getItemMeta();
            if (meta != null) {
                meta.displayName(render("{year}/{month}/{day} {hour}:{minute}:{second}", placeholders));
                fallback.setItemMeta(meta);
            }
            return fallback;
        }

        String materialName = section.getString("material");
        if (materialName == null || materialName.isBlank()) {
            materialName = section.getString("type", "STONE");
        }
        Material material = materialName == null ? null : Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.STONE;
        }
        int amount = Math.max(1, Math.min(64, section.getInt("amount", 1)));
        ItemStack item = new ItemStack(material, amount);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = section.getString("name");
            if (name != null && !name.isEmpty()) {
                meta.displayName(render(name, placeholders));
            }
            List<String> lore = section.getStringList("lore");
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(render(line, placeholders));
                }
                meta.lore(lines);
            }
            if (section.getBoolean("glow", false)) {
                meta.setEnchantmentGlintOverride(true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private Map<String, String> placeholders(@NotNull BackData data) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(data.getTime()), ZoneId.systemDefault());
        Map<String, String> map = new HashMap<>();
        map.put("year", String.valueOf(dateTime.getYear()));
        map.put("month", String.valueOf(dateTime.getMonthValue()));
        map.put("day", String.valueOf(dateTime.getDayOfMonth()));
        map.put("hour", String.valueOf(dateTime.getHour()));
        map.put("minute", String.valueOf(dateTime.getMinute()));
        map.put("second", String.valueOf(dateTime.getSecond()));
        map.put("type", this.module.lang().typeName(data.getType()));
        map.put("server", nullSafe(data.getServer()));
        map.put("world", nullSafe(data.getWorld()));
        map.put("x", String.valueOf(data.getX()));
        map.put("y", String.valueOf(data.getY()));
        map.put("z", String.valueOf(data.getZ()));
        map.put("yaw", String.valueOf(data.getYaw()));
        map.put("pitch", String.valueOf(data.getPitch()));
        return map;
    }

    private static @NotNull Component render(@NotNull String template, @NotNull Map<String, String> placeholders) {
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return MINI.deserialize(result).decoration(TextDecoration.ITALIC, false);
    }

    private static @NotNull String nullSafe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
