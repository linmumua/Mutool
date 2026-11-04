package cn.linmumua.mutool.condition;

import cn.linmumua.mutool.placeholder.PlaceholderService;
import org.bukkit.entity.Player;
import taboolib.library.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A single condition node of Mutool's minimal condition engine.
 *
 * <p>A node is a flat configuration map with a {@code type} key plus the type's own
 * parameters. Nodes are parsed from a TabooLib {@link ConfigurationSection} (the
 * Java-visible super-interface of {@code taboolib.module.configuration.Configuration})
 * or from a nested {@link Map}, which makes the engine usable both from YAML configs
 * and from programmatic callers / unit tests.</p>
 *
 * <p>Supported types (mirroring the joinmessage {@code config.yml} documentation):</p>
 * <ul>
 *   <li>{@code contains_value} — {@code list.contains(value)}</li>
 *   <li>{@code <}, {@code <=}, {@code ==}, {@code >=}, {@code >} — numeric {@code double} comparison of {@code value1} vs {@code value2}</li>
 *   <li>{@code permission} — {@code player.hasPermission(permission)}</li>
 *   <li>{@code contains} — {@code string.contains(value)}</li>
 *   <li>{@code equals} — {@code string.equals(value)}</li>
 *   <li>{@code &&} / {@code ||} — logical AND / OR over the {@code conditionss} sub-condition list</li>
 * </ul>
 *
 * <p>Every string operand is first run through {@link PlaceholderService#parse(Player, String)}
 * (which performs basic {@code {player}}-style replacements and then PlaceholderAPI expansion),
 * and additionally through any caller-supplied {@code context} placeholders expressed as
 * {@code {key}} tokens. This mirrors the reference implementation's string pre-processing.</p>
 *
 * <p>Unknown types log a warning and evaluate to {@code false} — never throw.</p>
 */
public final class Condition {

    private static final Logger LOGGER = Logger.getLogger("Mutool-Condition");

    private final String type;
    private final Map<String, Object> params;

    private Condition(String type, Map<String, Object> params) {
        this.type = type == null ? "" : type.trim();
        this.params = params == null ? Collections.emptyMap() : params;
    }

    public String type() {
        return this.type;
    }

    public Map<String, Object> params() {
        return Collections.unmodifiableMap(this.params);
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parse one condition node. Accepts either a TabooLib {@link ConfigurationSection}
     * or a nested {@link Map}.
     *
     * @throws IllegalArgumentException if the node is neither a section nor a map
     */
    public static Condition parse(Object node) {
        if (node instanceof ConfigurationSection section) {
            return fromSection(section);
        }
        if (node instanceof Map<?, ?> map) {
            return fromMap(map);
        }
        throw new IllegalArgumentException("Unsupported condition node: "
                + (node == null ? "null" : node.getClass().getName()));
    }

    /** Parse a condition from a TabooLib configuration section node. */
    public static Condition fromSection(ConfigurationSection section) {
        String type = section.getString("type");
        Map<String, Object> params = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if ("type".equals(key)) {
                continue;
            }
            params.put(key, normalize(section.get(key)));
        }
        return new Condition(type, params);
    }

    /** Parse a condition from a (possibly nested) map node. */
    public static Condition fromMap(Map<?, ?> map) {
        String type = asString(map.get("type"));
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = asString(entry.getKey());
            if (key == null || "type".equals(key)) {
                continue;
            }
            params.put(key, normalize(entry.getValue()));
        }
        return new Condition(type, params);
    }

    /**
     * Normalise a raw config value into the internal representation: nested sections /
     * maps become {@link Condition}s (used by {@code conditionss}) and lists are mapped
     * element-wise. Scalars are kept as-is.
     */
    private static Object normalize(Object value) {
        if (value instanceof ConfigurationSection section) {
            return fromSection(section);
        }
        if (value instanceof Map<?, ?> map) {
            return fromMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(normalize(element));
            }
            return out;
        }
        return value;
    }

    // ----------------------------------------------------------------- evaluation

    /**
     * Evaluate this condition for the given player and placeholder context.
     *
     * @param player  the player to test (may be {@code null} for player-independent checks)
     * @param context extra {@code key -> value} placeholders substituted as {@code {key}} (may be {@code null})
     * @return {@code true} when the condition holds; {@code false} on failure / unknown type
     */
    public boolean test(Player player, Map<String, String> context) {
        Map<String, String> ctx = context == null ? Collections.emptyMap() : context;
        String op = this.type.toLowerCase(Locale.ROOT);
        try {
            switch (op) {
                case "contains_value":
                    return testContainsValue(player, ctx);
                case "<":
                case "<=":
                case "==":
                case ">=":
                case ">":
                    return testCompare(op, player, ctx);
                case "permission":
                    return testPermission(player, ctx);
                case "contains":
                    return testString(player, ctx, true);
                case "equals":
                    return testString(player, ctx, false);
                case "&&":
                    return testAnd(player, ctx);
                case "||":
                    return testOr(player, ctx);
                default:
                    LOGGER.warning("[Mutool/Condition] 未知条件类型: '" + this.type + "'，条件返回 false");
                    return false;
            }
        } catch (Throwable throwable) {
            LOGGER.warning("[Mutool/Condition] 条件执行异常 type='" + this.type + "': " + throwable);
            return false;
        }
    }

    private boolean testContainsValue(Player player, Map<String, String> ctx) {
        Object rawList = this.params.get("list");
        String value = resolve(player, this.params.get("value"), ctx);
        if (!(rawList instanceof List<?> list)) {
            LOGGER.warning("[Mutool/Condition] contains_value 缺少列表参数 'list'");
            return false;
        }
        if (value == null) {
            LOGGER.warning("[Mutool/Condition] contains_value 缺少参数 'value'");
            return false;
        }
        for (Object element : list) {
            if (value.equals(asString(element))) {
                return true;
            }
        }
        return false;
    }

    private boolean testCompare(String op, Player player, Map<String, String> ctx) {
        Double left = parseDouble(resolve(player, this.params.get("value1"), ctx));
        Double right = parseDouble(resolve(player, this.params.get("value2"), ctx));
        if (left == null || right == null) {
            LOGGER.warning("[Mutool/Condition] 数值条件 '" + op + "' 的参数不是有效数字: value1="
                    + this.params.get("value1") + ", value2=" + this.params.get("value2"));
            return false;
        }
        switch (op) {
            case "<":
                return left < right;
            case "<=":
                return left <= right;
            case "==":
                return left.doubleValue() == right.doubleValue();
            case ">=":
                return left >= right;
            case ">":
                return left > right;
            default:
                return false;
        }
    }

    private boolean testPermission(Player player, Map<String, String> ctx) {
        if (player == null) {
            return false;
        }
        String permission = resolve(player, this.params.get("permission"), ctx);
        if (permission == null || permission.isEmpty()) {
            LOGGER.warning("[Mutool/Condition] permission 缺少参数 'permission'");
            return false;
        }
        return player.hasPermission(permission);
    }

    private boolean testString(Player player, Map<String, String> ctx, boolean contains) {
        String string = resolve(player, this.params.get("string"), ctx);
        String value = resolve(player, this.params.get("value"), ctx);
        if (string == null || value == null) {
            LOGGER.warning("[Mutool/Condition] 字符串条件缺少参数 'string'/'value'");
            return false;
        }
        return contains ? string.contains(value) : string.equals(value);
    }

    private boolean testAnd(Player player, Map<String, String> ctx) {
        for (Condition sub : subConditions()) {
            if (!sub.test(player, ctx)) {
                return false;
            }
        }
        return true;
    }

    private boolean testOr(Player player, Map<String, String> ctx) {
        for (Condition sub : subConditions()) {
            if (sub.test(player, ctx)) {
                return true;
            }
        }
        return false;
    }

    /** Extract the logical sub-conditions of an {@code &&} / {@code ||} node. */
    private List<Condition> subConditions() {
        List<Condition> out = new ArrayList<>();
        Object raw = this.params.get("conditionss");
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                Condition condition = coerce(element);
                if (condition != null) {
                    out.add(condition);
                }
            }
        } else {
            Condition condition = coerce(raw);
            if (condition != null) {
                out.add(condition);
            }
        }
        return out;
    }

    private static Condition coerce(Object node) {
        if (node instanceof Condition condition) {
            return condition;
        }
        if (node instanceof ConfigurationSection || node instanceof Map) {
            try {
                return parse(node);
            } catch (Throwable throwable) {
                LOGGER.warning("[Mutool/Condition] 解析子条件失败: " + throwable);
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static String resolve(Player player, Object value, Map<String, String> ctx) {
        String text = asString(value);
        if (text == null) {
            return null;
        }
        if (!ctx.isEmpty()) {
            for (Map.Entry<String, String> entry : ctx.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                text = text.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return PlaceholderService.parse(player, text);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        return String.valueOf(value);
    }

    private static Double parseDouble(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
