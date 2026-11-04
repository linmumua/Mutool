package cn.linmumua.mutool.action;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import taboolib.platform.BukkitPlugin;
import taboolib.platform.util.FoliaExecutorKt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Stateless entry point of Mutool's minimal action engine — the dependency-free replacement
 * for the legacy {@code CC-Action} library used by the future {@code eventaction} /
 * {@code timeaction} modules.
 *
 * <p>{@link #runAll(Player, List, Map)} executes a list of action nodes, which may be supplied
 * as TabooLib {@code ConfigurationSection}s, plain {@link Map}s, or already-parsed
 * {@link Action} instances. A {@code null}/empty list is a no-op.</p>
 *
 * <h2>Folia safety</h2>
 * <p>The whole batch is dispatched onto the <em>player's owning region thread</em> via
 * {@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)} before anything runs, so
 * every player-state mutation (game mode aside, messages, titles, sounds, inventory, commands)
 * happens on the correct thread. When there is no player (pure console / broadcast actions) the
 * batch runs on the {@code GlobalRegionScheduler}. {@link #runAll} is therefore safe to call
 * from any thread — including async event handlers — and safe to call when already on the
 * region thread (TabooLib's {@code runTask} detects current-region ownership and runs inline).</p>
 *
 * <h2>{@code delay}</h2>
 * <p>A {@code delay} action is handled here (not in {@link Action}) because delaying requires
 * knowing the surrounding list:</p>
 * <ul>
 *   <li>with nested {@code actions}: they run after the delay, and the siblings after the
 *       {@code delay} node continue immediately;</li>
 *   <li>without nested {@code actions}: it delays the <em>remainder</em> of the current list.</li>
 * </ul>
 * <p>Delayed work is scheduled on the player's entity scheduler (or the global scheduler when
 * there is no player), never via a Bukkit scheduler, keeping Folia compatibility.</p>
 */
public final class ActionEngine {

    private ActionEngine() {
    }

    /**
     * Run every action in {@code actions} for {@code player}.
     *
     * @param player  the acting player (may be {@code null} for console / broadcast actions)
     * @param actions action nodes ({@link taboolib.library.configuration.ConfigurationSection},
     *                {@link Map} or {@link Action}); {@code null}/empty is a no-op
     * @param context extra {@code key -> value} placeholders substituted as {@code {key}}
     *                in every string parameter (may be {@code null})
     */
    public static void runAll(Player player, List<?> actions, Map<String, String> context) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        Map<String, String> ctx = context == null ? Collections.emptyMap() : context;
        Runnable body = () -> executeList(player, actions, ctx);
        dispatch(player, body);
    }

    /** Convenience overload with an empty placeholder context. */
    public static void runAll(Player player, List<?> actions) {
        runAll(player, actions, Collections.emptyMap());
    }

    // ---------------------------------------------------------------- execution

    /** The synchronous body: iterate nodes, honouring {@code delay} restart semantics. */
    private static void executeList(Player player, List<?> actions, Map<String, String> ctx) {
        for (int i = 0; i < actions.size(); i++) {
            Action action;
            try {
                action = Action.parse(actions.get(i));
            } catch (Throwable throwable) {
                warn("解析动作失败，已跳过: " + throwable);
                continue;
            }
            if (action == null) {
                continue;
            }

            if (action.isDelay()) {
                long ticks = action.delayTicks();
                List<Action> nested = action.nestedActions();
                if (!nested.isEmpty()) {
                    // Self-contained delay: run the children afterwards, keep going with siblings.
                    List<?> copy = new ArrayList<>(nested);
                    scheduleLater(player, ticks, () -> runAll(player, copy, ctx));
                    continue;
                }
                // Bare delay: delay the remainder of the current list.
                List<?> remainder = new ArrayList<>(actions.subList(i + 1, actions.size()));
                if (!remainder.isEmpty()) {
                    scheduleLater(player, ticks, () -> runAll(player, remainder, ctx));
                }
                return;
            }

            action.run(player, ctx);
        }
    }

    // ---------------------------------------------------------------- scheduling

    /** Dispatch {@code body} onto the right region thread (player region, else global). */
    private static void dispatch(Player player, Runnable body) {
        if (player != null) {
            try {
                FoliaExecutorKt.runTask(player, body);
                return;
            } catch (Throwable throwable) {
                warn("通过玩家区域线程调度失败，回退到全局区域: " + throwable);
            }
        }
        Plugin plugin = plugin();
        if (plugin != null) {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> body.run());
        } else {
            body.run();
        }
    }

    /** Schedule {@code task} to run after {@code ticks} on the player (or global) scheduler. */
    private static void scheduleLater(Player player, long ticks, Runnable task) {
        long delay = Math.max(1L, ticks);
        Plugin plugin = plugin();
        if (plugin == null) {
            task.run();
            return;
        }
        if (player != null) {
            player.getScheduler().runDelayed(plugin, scheduledTask -> guarded(task), null, delay);
        } else {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> guarded(task), delay);
        }
    }

    private static void guarded(Runnable task) {
        try {
            task.run();
        } catch (Throwable throwable) {
            warn("延迟动作执行异常: " + throwable);
        }
    }

    private static Plugin plugin() {
        try {
            BukkitPlugin instance = BukkitPlugin.getInstance();
            if (instance != null) {
                return instance;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            return Bukkit.getPluginManager().getPlugin("Mutool");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void warn(String message) {
        java.util.logging.Logger.getLogger("Mutool-Action").warning("[Mutool/Action] " + message);
    }
}
