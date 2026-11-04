package cn.linmumua.mutool.module.home;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import taboolib.library.configuration.ConfigurationSection;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed, immutable snapshot of {@code module/home/menu/home.yml}.
 *
 * <p>Reads only the keys the home menu needs: {@code title}, {@code slots}, {@code keys.home}
 * and {@code items.*}. Each item's {@code conditions} (simple numeric comparisons) and
 * {@code actions} (only {@code prev_page} / {@code next_page} are recognised) are parsed so the
 * pagination buttons can be driven from the YAML rather than hard-coded.</p>
 *
 * <p>The parsed object is rebuilt on every {@link #reload()} and shared read-only with
 * {@link HomeMenu} (which renders it on the player's region thread), so all mutable parsing
 * state stays on the module/reload thread.</p>
 */
public final class HomeMenuConfig {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Configuration config;

    private Component title = Component.empty();
    private List<String> slots = new ArrayList<>();
    private String homeKey = "H";
    private List<Integer> homeSlots = new ArrayList<>();
    private Item homeItem;
    private final Map<String, Item> items = new LinkedHashMap<>();

    /** Load (or reload from disk) the given menu file and re-parse it. */
    public void load(File file) {
        this.config = Configuration.Companion.loadFromFile(file, Type.YAML, false);
        this.parse();
    }

    /** Reload the already-loaded menu file and re-parse. No-op when never loaded. */
    public void reload() {
        if (this.config != null) {
            this.config.reload();
            this.parse();
        }
    }

    // --- accessors (read-only) ---

    public Component title() {
        return this.title;
    }

    public List<String> slots() {
        return this.slots;
    }

    public String homeKey() {
        return this.homeKey;
    }

    public List<Integer> homeSlots() {
        return this.homeSlots;
    }

    /** The template item used to render one home (the {@code items.home} entry). */
    public Item homeItem() {
        return this.homeItem;
    }

    /**
     * Resolve the item that should fill a non-home slot keyed {@code key} on the given page.
     *
     * <p>Mirrors the source menu semantics: an exact match is used if present (and only when
     * its conditions pass), otherwise the first {@code key + "_"} entry whose conditions pass
     * is used — that is how {@code P} folds to {@code P_1}/{@code P_2} and {@code N} to
     * {@code N_1}/{@code N_2} depending on the current page.</p>
     */
    public Item resolve(String key, int page, int maxPage) {
        Item exact = this.items.get(key);
        if (exact != null) {
            return exact.matches(page, maxPage) ? exact : null;
        }

        // Prefix variants (e.g. P -> P_1 / P_2). Prefer a state-specific variant whose
        // conditions currently pass (the active prev/next button) over an unconditional
        // filler, regardless of declaration order.
        Item fallback = null;
        for (Map.Entry<String, Item> entry : this.items.entrySet()) {
            if (!entry.getKey().startsWith(key + "_")) {
                continue;
            }
            Item candidate = entry.getValue();
            if (candidate.conditions().isEmpty()) {
                if (fallback == null) {
                    fallback = candidate;
                }
            } else if (candidate.matches(page, maxPage)) {
                return candidate;
            }
        }
        return fallback;
    }

    // --- parsing ---

    private void parse() {
        this.title = MINI.deserialize(this.config.getString("title", ""));

        this.slots = new ArrayList<>(this.config.getStringList("slots"));

        String key = this.config.getString("keys.home", "H");
        this.homeKey = (key == null || key.isBlank()) ? "H" : key;

        this.homeSlots = new ArrayList<>();
        for (int row = 0; row < this.slots.size(); row++) {
            String line = this.slots.get(row);
            for (int column = 0; column < Math.min(line.length(), 9); column++) {
                if (String.valueOf(line.charAt(column)).equals(this.homeKey)) {
                    this.homeSlots.add(row * 9 + column);
                }
            }
        }

        this.items.clear();
        ConfigurationSection section = this.config.getConfigurationSection("items");
        if (section != null) {
            for (String itemKey : section.getKeys(false)) {
                ConfigurationSection itemSection = section.getConfigurationSection(itemKey);
                if (itemSection == null) {
                    continue;
                }
                this.items.put(itemKey, Item.parse(itemSection));
            }
        }
        this.homeItem = this.items.get("home");
    }

    // --- helper: placeholder substitution & rendering ---

    static String sub(String text, Map<String, String> placeholders) {
        if (text == null) {
            return "";
        }
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static Component render(String miniMessage) {
        return MINI.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }

    // --- nested types ---

    /** Behaviour attached to a pagination button when clicked. */
    public enum PageAction {
        NONE, PREV, NEXT
    }

    /** A single parsed menu item template. */
    public record Item(
            String type,
            String name,
            List<String> lore,
            int amount,
            List<Condition> conditions,
            PageAction action
    ) {

        static Item parse(ConfigurationSection section) {
            String type = section.getString("type");
            if (type == null || type.isBlank()) {
                type = section.getString("material");
            }
            String name = section.getString("name");
            List<String> lore = new ArrayList<>(section.getStringList("lore"));
            int amount = section.getInt("amount", 1);

            List<Condition> conditions = new ArrayList<>();
            for (Map<?, ?> raw : section.getMapList("conditions")) {
                conditions.add(new Condition(
                        asString(raw.get("type")),
                        asString(raw.get("value1")),
                        asString(raw.get("value2"))
                ));
            }

            PageAction action = detectAction(section.getConfigurationSection("actions"));
            return new Item(type, name, lore, amount, conditions, action);
        }

        /** Whether every condition passes for the given page / page-count. */
        boolean matches(int page, int maxPage) {
            Map<String, String> placeholders = Map.of(
                    "page", String.valueOf(page),
                    "max_page", String.valueOf(maxPage)
            );
            for (Condition condition : this.conditions) {
                if (!condition.matches(placeholders)) {
                    return false;
                }
            }
            return true;
        }

        /** Build the concrete {@link ItemStack} for this template with placeholders applied. */
        public ItemStack build(Map<String, String> placeholders) {
            String materialName = (this.type == null || this.type.isBlank()) ? "STONE" : this.type;
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                material = Material.STONE;
            }
            int stackAmount = this.amount <= 0 ? 1 : Math.min(64, this.amount);
            ItemStack stack = new ItemStack(material, stackAmount);

            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (this.name != null && !this.name.isEmpty()) {
                    meta.displayName(render(sub(this.name, placeholders)));
                }
                if (this.lore != null && !this.lore.isEmpty()) {
                    List<Component> lines = new ArrayList<>(this.lore.size());
                    for (String line : this.lore) {
                        lines.add(render(sub(line, placeholders)));
                    }
                    meta.lore(lines);
                }
                stack.setItemMeta(meta);
            }
            return stack;
        }

        private static PageAction detectAction(ConfigurationSection actions) {
            if (actions == null) {
                return PageAction.NONE;
            }
            if (containsToken(actions, "prev_page", 0)) {
                return PageAction.PREV;
            }
            if (containsToken(actions, "next_page", 0)) {
                return PageAction.NEXT;
            }
            return PageAction.NONE;
        }

        /**
         * Recursively search a YAML node for a scalar token. Handles both {@link Map}/{@link List}
         * and {@link ConfigurationSection} representations so the detection does not depend on
         * how the config library materialises nested lists/sections.
         */
        private static boolean containsToken(Object node, String token, int depth) {
            if (node == null || depth > 12) {
                return false;
            }
            if (node instanceof String text) {
                return token.equalsIgnoreCase(text.trim());
            }
            if (node instanceof ConfigurationSection section) {
                for (Object child : section.getValues(false).values()) {
                    if (containsToken(child, token, depth + 1)) {
                        return true;
                    }
                }
                return false;
            }
            if (node instanceof Map<?, ?> map) {
                for (Object child : map.values()) {
                    if (containsToken(child, token, depth + 1)) {
                        return true;
                    }
                }
                return false;
            }
            if (node instanceof List<?> list) {
                for (Object child : list) {
                    if (containsToken(child, token, depth + 1)) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }

        private static String asString(Object value) {
            return value == null ? "" : String.valueOf(value);
        }
    }

    /** A single simple numeric/string comparison used by an item's {@code conditions}. */
    public record Condition(String op, String value1, String value2) {

        boolean matches(Map<String, String> placeholders) {
            String left = sub(this.value1, placeholders);
            String right = sub(this.value2, placeholders);
            String operator = this.op == null ? "" : this.op.trim();

            Double leftNumber = asNumber(left);
            Double rightNumber = asNumber(right);
            if (leftNumber != null && rightNumber != null) {
                int comparison = Double.compare(leftNumber, rightNumber);
                return switch (operator) {
                    case ">", "gt" -> comparison > 0;
                    case ">=", "ge" -> comparison >= 0;
                    case "<", "lt" -> comparison < 0;
                    case "<=", "le" -> comparison <= 0;
                    case "==", "=", "eq" -> comparison == 0;
                    case "!=", "ne" -> comparison != 0;
                    default -> true;
                };
            }

            int comparison = left.compareTo(right);
            return switch (operator) {
                case "==", "=", "eq" -> comparison == 0;
                case "!=", "ne" -> comparison != 0;
                case ">", "gt" -> comparison > 0;
                case ">=", "ge" -> comparison >= 0;
                case "<", "lt" -> comparison < 0;
                case "<=", "le" -> comparison <= 0;
                default -> true;
            };
        }

        private static Double asNumber(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
