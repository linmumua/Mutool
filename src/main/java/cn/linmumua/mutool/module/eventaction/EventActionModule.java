package cn.linmumua.mutool.module.eventaction;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.action.ActionEngine;
import cn.linmumua.mutool.condition.ConditionEngine;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import taboolib.common.platform.function.IOKt;
import taboolib.library.configuration.ConfigurationSection;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EventAction module — runs configured {@code conditions} + {@code actions} in response to
 * Bukkit events.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_eventaction}. It owns the
 * module switch, loads {@code module/eventaction/config.yml} through TabooLib's
 * {@link Configuration}, and — while enabled — registers a single generic
 * {@link EventActionListener} (see there for the covered event types).</p>
 *
 * <h2>Config shape</h2>
 * <pre>
 * enable: true
 * list:
 *   player_join:                     # 自定义命名（仅用于日志/排错）
 *     event: "玩家加入服务器"          # 监听的事件类型（见各事件对应的中文名）
 *     actions:
 *       -                            # 一个“条件 + 操作”块
 *         conditions:                # 可选；缺省/为空 = 无条件
 *           - type: "permission"
 *             permission: "..."
 *         actions:                   # 条件满足时执行的操作（ActionEngine）
 *           - type: "message"
 *             message: "&e..."
 *       - actions:                   # 无条件块
 *           - type: "console_command"
 *             command: "say hi"
 * </pre>
 *
 * <p>Event dispatch mirrors the original loop: for every block whose {@code event} equals the
 * fired event's Chinese name, evaluate the block's {@code conditions} via
 * {@link ConditionEngine#testAll}; when they all pass, run the block's {@code actions} via
 * {@link ActionEngine#runAll}. {@link ActionEngine#runAll} dispatches onto the player's owning
 * region thread, so this is safe to call from async event handlers and Folia.</p>
 */
public final class EventActionModule implements MutoolModule {

    private static final String CONFIG_RESOURCE = "module/eventaction/config.yml";

    private Mutool plugin;
    private Configuration config;

    private boolean enabled;
    private List<Rule> rules = List.of();
    private EventActionListener listener;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public EventActionModule() {
    }

    @Override
    public String id() {
        return "eventaction";
    }

    /** Release the default resource, load config, then (re)apply the enable state + listener. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);

        this.listener = new EventActionListener(this);
        this.applyState();
    }

    /** Reload config from disk and re-apply the enable state / listener registration. */
    @Override
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
        this.applyState();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    /** Unregister the listener and drop state. Called on plugin disable. */
    @Override
    public void unload() {
        this.unregisterListener();
        this.enabled = false;
        this.rules = List.of();
    }

    // --- internals ---

    private void applyState() {
        this.rules = this.parseRules();

        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        // Always drop the old registration first so a reload never double-registers.
        this.unregisterListener();
        if (nowEnabled) {
            this.registerListener();
        }
        this.enabled = nowEnabled;

        if (!nowEnabled) {
            IOKt.info("[Mutool/EventAction] 事件动作模块已关闭 (enable: false)");
        }
    }

    /**
     * Flatten the {@code list} section into an ordered list of {@link Rule}s. Every element of a
     * named block's {@code actions} list becomes one rule carrying that block's {@code event}
     * name plus its own optional {@code conditions} and required {@code actions} nodes (kept as
     * raw maps so they can be fed straight into {@link ConditionEngine} / {@link ActionEngine}).
     */
    private List<Rule> parseRules() {
        List<Rule> result = new ArrayList<>();
        if (this.config == null) {
            return result;
        }
        ConfigurationSection list = this.config.getConfigurationSection("list");
        if (list == null) {
            return result;
        }
        for (String key : list.getKeys(false)) {
            ConfigurationSection section = list.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            String event = section.getString("event", "");
            if (event == null) {
                event = "";
            }
            List<Map<?, ?>> blocks = section.getMapList("actions");
            if (blocks == null) {
                continue;
            }
            for (Map<?, ?> block : blocks) {
                if (block == null) {
                    continue;
                }
                List<?> conditions = asNodeList(block.get("conditions"));
                List<?> actions = asNodeList(block.get("actions"));
                result.add(new Rule(key, event, conditions, actions));
            }
        }
        return result;
    }

    private static List<?> asNodeList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private void registerListener() {
        if (this.listener == null) {
            return;
        }
        this.plugin.getServer().getPluginManager().registerEvents(this.listener, this.plugin);
        IOKt.info("[Mutool/EventAction] 已注册事件动作监听器");
    }

    private void unregisterListener() {
        if (this.listener == null) {
            return;
        }
        HandlerList.unregisterAll(this.listener);
    }

    // --- dispatch (called by the listener) ---

    /**
     * Run every rule whose configured {@code event} name equals {@code eventName}: evaluate the
     * rule's conditions for {@code player} and, when they hold, hand its actions to the action
     * engine.
     *
     * @param player    the involved player (never {@code null} for the supported events)
     * @param eventName the Chinese event name the fired Bukkit event maps to
     */
    void runAction(Player player, String eventName) {
        if (!this.enabled || eventName == null) {
            return;
        }
        for (Rule rule : this.rules) {
            if (!eventName.equals(rule.event())) {
                continue;
            }
            if (!ConditionEngine.testAll(player, rule.conditions(), new HashMap<>())) {
                continue;
            }
            ActionEngine.runAll(player, rule.actions(), new HashMap<>());
        }
    }

    /**
     * One "conditions + actions" block.
     *
     * @param id         the config key the block lives under (kept for logging/debug)
     * @param event      the Chinese event name this block reacts to
     * @param conditions raw condition nodes passed to {@link ConditionEngine#testAll} (empty = unconditional)
     * @param actions    raw action nodes passed to {@link ActionEngine#runAll}
     */
    public record Rule(String id, String event, List<?> conditions, List<?> actions) {
    }
}
