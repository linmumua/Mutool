package cn.linmumua.mutool.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import taboolib.library.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Small helper that turns a YAML {@link ConfigurationSection} into an {@link ItemStack}.
 *
 * <p>Recognised keys (all optional except the material):</p>
 * <ul>
 *   <li>{@code material} / {@code type} — Bukkit {@link Material} name (e.g. {@code red_bed}).</li>
 *   <li>{@code amount} — stack size, defaults to 1.</li>
 *   <li>{@code name} — display name, parsed with MiniMessage.</li>
 *   <li>{@code lore} — list of lines, each parsed with MiniMessage.</li>
 *   <li>{@code glow} — when {@code true}, adds an enchantment glint without real enchants.</li>
 * </ul>
 *
 * <p>Placeholders such as {@code {name}} / {@code {world}} / {@code {page}} are <em>not</em>
 * resolved here — the caller must substitute them into the raw template strings before
 * building the section (or build a per-item section per player). This keeps the helper free
 * of any assumption about what data a menu needs.</p>
 */
public final class MenuItems {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private MenuItems() {
    }

    /**
     * Build an {@link ItemStack} from {@code section}.
     *
     * @return the built item; returns an {@link Material#AIR} stack when the section is
     *         {@code null} or names an unknown material so callers never have to null-check
     */
    public static @NotNull ItemStack build(@Nullable ConfigurationSection section) {
        if (section == null) {
            return new ItemStack(Material.AIR);
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
                meta.displayName(render(name));
            }

            List<String> lore = section.getStringList("lore");
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(render(line));
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

    /** Parse a MiniMessage string into a component with the default italic decoration removed. */
    private static @NotNull Component render(@NotNull String miniMessage) {
        return MINI.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }
}
