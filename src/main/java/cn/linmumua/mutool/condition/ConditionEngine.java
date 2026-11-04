package cn.linmumua.mutool.condition;

import org.bukkit.entity.Player;
import taboolib.library.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Stateless entry point of Mutool's minimal condition engine.
 *
 * <p>Evaluates a list of condition nodes, which may be supplied either as TabooLib
 * {@link ConfigurationSection}s (as read from module configs) or as already-parsed
 * {@link Condition} instances (so modules can parse once at config-load time and then
 * cheaply test per event).</p>
 *
 * <p>A {@code null} or empty condition list is treated as {@code true} (no restriction).</p>
 */
public final class ConditionEngine {

    private ConditionEngine() {
    }

    /**
     * Test whether <em>all</em> conditions hold for the player.
     *
     * @param player     the player to test (may be {@code null})
     * @param conditions condition nodes ({@link ConfigurationSection}, {@link Map} or {@link Condition}); {@code null}/empty means {@code true}
     * @param context    extra {@code key -> value} placeholders substituted as {@code {key}} (may be {@code null})
     * @return {@code true} iff every condition holds (or there are none)
     */
    public static boolean testAll(Player player, List<?> conditions, Map<String, String> context) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Object node : conditions) {
            Condition condition = node instanceof Condition existing ? existing : Condition.parse(node);
            if (!condition.test(player, context)) {
                return false;
            }
        }
        return true;
    }

    /** Convenience overload with an empty placeholder context. */
    public static boolean testAll(Player player, List<?> conditions) {
        return testAll(player, conditions, Collections.emptyMap());
    }

    /** Test a single (already parsed) condition. */
    public static boolean test(Player player, Condition condition, Map<String, String> context) {
        return condition != null && condition.test(player, context);
    }
}
