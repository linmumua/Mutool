package cn.linmumua.mutool.module.timeaction;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.action.Action;
import cn.linmumua.mutool.action.ActionEngine;
import cn.linmumua.mutool.condition.ConditionEngine;
import cn.linmumua.mutool.module.MutoolModule;
import kotlin.Unit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import taboolib.common.platform.function.ExecutorKt;
import taboolib.common.platform.function.IOKt;
import taboolib.common.platform.service.PlatformExecutor;
import taboolib.library.configuration.ConfigurationSection;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TimeAction module — runs configured actions on a timer / at a fixed wall-clock time.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_timeaction}. It owns the
 * module switch, loads {@code module/timeaction/config.yml} through TabooLib's
 * {@link Configuration}, and — while enabled — starts a single repeating task that checks the
 * configured triggers and hands the matching actions to {@link ActionEngine#runAll}.</p>
 *
 * <h2>Config shape</h2>
 * <pre>
 * enable: true
 * period: 20                        # 检查周期（tick），20 tick = 1 秒
 * list:
 *   lunch:                          # 自定义命名（仅用于日志/排错）
 *     type: "定点操作"               # 定时操作 | 定点操作
 *     time: "12:0:0"                # 时:分:秒
 *     actions:                      # 操作块列表（沿用源实现的“- actions:”包裹结构）
 *       - actions:
 *           - type: "broadcast"
 *             message: "&e到点了该吃饭了"
 * </pre>
 *
 * <h2>Trigger semantics</h2>
 * <ul>
 *   <li>{@code 定时操作} — {@code time}（时:分:秒）换算成“总秒数”，每隔这么多<b>真实运行时间</b>
 *       触发一次（内部按累计 tick 折算，与 {@code period} 无关，始终等价于 N 秒一次）。</li>
 *   <li>{@code 定点操作} — 当系统时钟（{@link LocalTime#now()}）的时:分:秒恰好等于 {@code time}
 *       时触发一次（每天一次），并带同秒去重，避免 {@code period} 小于 1 秒时重复触发。</li>
 * </ul>
 *
 * <h2>执行范围（与原实现的差异）</h2>
 * <p>源实现每次都以 {@code null} 玩家执行“全局”动作。本移植在触发时按动作类型自动判定执行范围：</p>
 * <ul>
 *   <li>若某个条目的所有操作都是<b>全局动作</b>（{@code broadcast} / {@code console_command}，
 *       以及 {@code as_console: true} 的 {@code command}）且不带条件，则只<b>全局执行一次</b>
 *       （{@code runAll(null, ...)}），与源实现一致，不会因在线人数而重复广播。</li>
 *   <li>否则按<b>在线玩家逐个执行</b>（{@code runAll(player, ...)}，涉及玩家状态的动作如
 *       {@code message/title/actionbar/sound/chat/command/close} 均按玩家生效）。</li>
 * </ul>
 *
 * <h2>Folia 安全</h2>
 * <p>周期任务通过 {@code ExecutorKt.submitAsync(false, 0L, period, ...)} 以异步方式调度；
 * 真正的动作执行交给 {@link ActionEngine#runAll} —— 它会把整批动作投递到玩家的区域线程
 * （全局动作投递到 {@code GlobalRegionScheduler}），因此本模块从异步线程调用是安全的。</p>
 */
public final class TimeActionModule implements MutoolModule {

    private static final String CONFIG_RESOURCE = "module/timeaction/config.yml";
    private static final int DEFAULT_PERIOD = 20;

    /** Mutable placeholder context reused by every dispatch (never modified by the engines). */
    private static final Map<String, String> EMPTY_CONTEXT = Collections.emptyMap();

    private Mutool plugin;
    private Configuration config;

    private boolean enabled;
    private volatile int period = DEFAULT_PERIOD;
    private volatile List<Entry> entries = List.of();

    /** Accumulated ticks per interval-triggered entry id. */
    private final Map<String, Long> intervalTicks = new ConcurrentHashMap<>();
    /** Last epoch-second a fixed-time entry fired, to de-duplicate within the same second. */
    private final Map<String, Long> fixedFired = new ConcurrentHashMap<>();

    private PlatformExecutor.PlatformTask tickTask;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public TimeActionModule() {
    }

    @Override
    public String id() {
        return "timeaction";
    }

    /** Release the default resource, load config, then (re)apply the enable state / task. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);

        this.applyState();
    }

    /** Reload config from disk and re-apply the enable state / restart the task. */
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

    /** Cancel the repeating task and drop state. Called on plugin disable. */
    @Override
    public void unload() {
        this.cancelTask();
        this.enabled = false;
        this.entries = List.of();
        this.intervalTicks.clear();
        this.fixedFired.clear();
    }

    // --- setup ---

    private void applyState() {
        this.cancelTask();
        this.entries = this.parseEntries();
        this.intervalTicks.clear();
        this.fixedFired.clear();

        this.period = this.config == null
                ? DEFAULT_PERIOD
                : Math.max(1, this.config.getInt("period", DEFAULT_PERIOD));

        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);
        if (nowEnabled) {
            this.startTask();
            this.enabled = true;
        } else {
            this.enabled = false;
            IOKt.info("[Mutool/TimeAction] 定时动作模块已关闭 (enable: false)");
        }
    }

    /**
     * Flatten the {@code list} section into an ordered list of {@link Entry}s. Each named entry
     * carries its {@code type} + {@code time} plus a list of {@link Block}s (each block is one
     * "条件 + 操作" element of the entry's {@code actions} list, mirroring the source
     * {@code ConditionAction} structure). Node lists are copied out by TabooLib's {@code getMapList},
     * so they stay valid across config reloads.
     */
    private List<Entry> parseEntries() {
        List<Entry> result = new ArrayList<>();
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
            String type = section.getString("type");
            String time = section.getString("time");
            if (type == null || time == null || type.isBlank() || time.isBlank()) {
                continue;
            }
            List<Block> blocks = this.parseBlocks(section);
            if (blocks.isEmpty()) {
                continue;
            }
            result.add(new Entry(key, type.trim(), time.trim(), blocks, computeGlobal(blocks)));
        }
        return result;
    }

    private List<Block> parseBlocks(ConfigurationSection section) {
        List<Block> blocks = new ArrayList<>();
        List<Map<?, ?>> rawBlocks = section.getMapList("actions");
        if (rawBlocks == null) {
            return blocks;
        }
        for (Map<?, ?> rawBlock : rawBlocks) {
            if (rawBlock == null) {
                continue;
            }
            List<?> conditions = asNodeList(rawBlock.get("conditions"));
            List<?> actions = asNodeList(rawBlock.get("actions"));
            if (actions.isEmpty()) {
                continue;
            }
            blocks.add(new Block(conditions, actions));
        }
        return blocks;
    }

    private static List<?> asNodeList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    // --- repeating task ---

    private void startTask() {
        final int taskPeriod = this.period;
        this.tickTask = ExecutorKt.submitAsync(false, 0L, (long) taskPeriod, task -> {
            try {
                this.tick();
            } catch (Throwable throwable) {
                this.warn("定时检查异常: " + throwable);
            }
            return Unit.INSTANCE;
        });
        IOKt.info("[Mutool/TimeAction] 已启动定时动作任务 (period=" + taskPeriod + " ticks)");
    }

    private void cancelTask() {
        if (this.tickTask != null) {
            try {
                this.tickTask.cancel();
            } catch (Throwable ignored) {
            }
            this.tickTask = null;
        }
    }

    /** One scheduler pass: evaluate every entry's trigger, then dispatch the ones that fire. */
    private void tick() {
        if (!this.enabled) {
            return;
        }
        for (Entry entry : this.entries) {
            boolean fire;
            try {
                fire = this.shouldFire(entry);
            } catch (Throwable throwable) {
                this.warn("触发判断异常 (id=" + entry.id() + "): " + throwable);
                continue;
            }
            if (!fire) {
                continue;
            }
            try {
                this.dispatch(entry);
            } catch (Throwable throwable) {
                this.warn("动作执行异常 (id=" + entry.id() + "): " + throwable);
            }
        }
    }

    private boolean shouldFire(Entry entry) {
        return switch (entry.type()) {
            case "定时操作" -> this.intervalDue(entry);
            case "定点操作" -> this.fixedDue(entry);
            default -> false;
        };
    }

    // --- trigger evaluation ---

    /** {@code 定时操作}: fire once every {@code time} seconds of real time, independent of tick rate. */
    private boolean intervalDue(Entry entry) {
        long targetSeconds = parseHms(entry.time());
        if (targetSeconds <= 0L) {
            return false; // invalid / zero interval — skip rather than fire every tick
        }
        long targetTicks = targetSeconds * 20L;
        long acc = this.intervalTicks.getOrDefault(entry.id(), 0L) + this.period;
        if (acc >= targetTicks) {
            this.intervalTicks.put(entry.id(), 0L);
            return true;
        }
        this.intervalTicks.put(entry.id(), acc);
        return false;
    }

    /** {@code 定点操作}: fire when the wall clock's H:M:S exactly matches {@code time} (once per day). */
    private boolean fixedDue(Entry entry) {
        int[] hms = parseHmsParts(entry.time());
        if (hms == null) {
            return false;
        }
        LocalTime now = LocalTime.now();
        if (now.getHour() != hms[0] || now.getMinute() != hms[1] || now.getSecond() != hms[2]) {
            return false;
        }
        long epochSecond = System.currentTimeMillis() / 1000L;
        Long last = this.fixedFired.get(entry.id());
        if (last != null && last == epochSecond) {
            return false; // already fired within this same second
        }
        this.fixedFired.put(entry.id(), epochSecond);
        return true;
    }

    // --- dispatch ---

    private void dispatch(Entry entry) {
        if (entry.global()) {
            // Global actions (broadcast / console / as_console command) with no conditions:
            // run once with a null player, matching the original implementation.
            for (Block block : entry.blocks()) {
                try {
                    if (!ConditionEngine.testAll(null, block.conditions(), EMPTY_CONTEXT)) {
                        continue;
                    }
                    ActionEngine.runAll(null, block.actions(), EMPTY_CONTEXT);
                } catch (Throwable throwable) {
                    this.warn("全局动作执行异常 (id=" + entry.id() + "): " + throwable);
                }
            }
            return;
        }

        // Player-bound actions: run for every online player.
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            for (Block block : entry.blocks()) {
                try {
                    if (!ConditionEngine.testAll(player, block.conditions(), EMPTY_CONTEXT)) {
                        continue;
                    }
                    ActionEngine.runAll(player, block.actions(), EMPTY_CONTEXT);
                } catch (Throwable throwable) {
                    this.warn("玩家动作执行异常 (id=" + entry.id() + "): " + throwable);
                }
            }
        }
    }

    // --- global / player-bound classification ---

    private static boolean computeGlobal(List<Block> blocks) {
        for (Block block : blocks) {
            if (!block.conditions().isEmpty()) {
                return false; // conditions may depend on a player — evaluate per player
            }
            for (Object node : block.actions()) {
                if (!nodePlayerIndependent(node)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean nodePlayerIndependent(Object node) {
        Action action;
        try {
            action = Action.parse(node);
        } catch (Throwable throwable) {
            return true; // unparseable — run once (it will be skipped/logged by the engine)
        }
        if (action.isDelay()) {
            for (Action child : action.nestedActions()) {
                if (!nodePlayerIndependent(child)) {
                    return false;
                }
            }
            return true;
        }
        return switch (action.kind()) {
            case "broadcast", "console_command" -> true;
            case "command" -> isAsConsole(action);
            case "" -> true; // unknown type — run once to avoid per-player log spam
            default -> false;
        };
    }

    private static boolean isAsConsole(Action action) {
        Object value = action.params().get("as_console");
        if (value == null) {
            value = action.params().get("asConsole");
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    // --- helpers ---

    /** Parse {@code 时:分:秒} to total seconds; returns {@code -1} on malformed input. */
    private static long parseHms(String time) {
        int[] parts = parseHmsParts(time);
        return parts == null ? -1L : (long) parts[0] * 3600L + (long) parts[1] * 60L + parts[2];
    }

    /** Split {@code 时:分:秒} into {@code [hour, minute, second]}; {@code null} on malformed input. */
    private static int[] parseHmsParts(String time) {
        if (time == null) {
            return null;
        }
        String[] data = time.trim().split(":");
        if (data.length < 3) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(data[0].trim()),
                    Integer.parseInt(data[1].trim()),
                    Integer.parseInt(data[2].trim())
            };
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void warn(String message) {
        if (this.plugin != null) {
            this.plugin.getLogger().warning("[Mutool/TimeAction] " + message);
        }
    }

    /**
     * One configured timer entry.
     *
     * @param id     the config key (kept for logging / state tracking)
     * @param type   {@code 定时操作} or {@code 定点操作}
     * @param time   {@code 时:分:秒}
     * @param blocks one or more "条件 + 操作" blocks
     * @param global whether the entry is entirely global (no player-bound actions / conditions)
     */
    private record Entry(String id, String type, String time, List<Block> blocks, boolean global) {
    }

    /**
     * One "conditions + actions" block (mirrors the source {@code ConditionAction}).
     *
     * @param conditions raw condition nodes for {@link ConditionEngine#testAll} (empty = unconditional)
     * @param actions    raw action nodes for {@link ActionEngine#runAll}
     */
    private record Block(List<?> conditions, List<?> actions) {
    }
}
