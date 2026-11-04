package cn.linmumua.mutool.action;

import cn.linmumua.mutool.placeholder.PlaceholderService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import taboolib.library.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A single action node of Mutool's minimal action engine — the dependency-free replacement
 * for the legacy {@code CC-Action} library used by the future {@code eventaction} /
 * {@code timeaction} modules.
 *
 * <p>A node is a flat configuration map with a {@code type} key plus that type's own
 * parameters. Nodes are parsed from a TabooLib {@link ConfigurationSection} (the Java-visible
 * super-interface of {@code taboolib.module.configuration.Configuration}) or from a nested
 * {@link Map}, so the engine is usable both from YAML configs and from programmatic
 * callers / unit tests — mirroring {@code cn.linmumua.mutool.condition.Condition}.</p>
 *
 * <h2>Supported types</h2>
 * <table>
 *   <tr><th>type (aliases)</th><th>parameters</th></tr>
 *   <tr><td>{@code command} ({@code player_command})</td><td>{@code command}, {@code as_console}[false], {@code op}[false]</td></tr>
 *   <tr><td>{@code console_command}</td><td>{@code command}</td></tr>
 *   <tr><td>{@code message}</td><td>{@code message}</td></tr>
 *   <tr><td>{@code broadcast}</td><td>{@code message}</td></tr>
 *   <tr><td>{@code title}</td><td>{@code title}, {@code subtitle}, {@code in}[20], {@code step}[70], {@code out}[10]</td></tr>
 *   <tr><td>{@code actionbar} ({@code action_bar})</td><td>{@code message}</td></tr>
 *   <tr><td>{@code sound} ({@code play_sound})</td><td>{@code sound}, {@code volume}[1.0], {@code pitch}[1.0]</td></tr>
 *   <tr><td>{@code chat}</td><td>{@code message}</td></tr>
 *   <tr><td>{@code close} ({@code close_inventory})</td><td>—</td></tr>
 *   <tr><td>{@code delay} ({@code wait})</td><td>{@code ticks}[20], optional nested {@code actions}</td></tr>
 * </table>
 *
 * <p>{@code delay} is handled by {@link ActionEngine} (it needs the surrounding list to know
 * what "subsequent" means); this class only exposes {@link #isDelay()},
 * {@link #delayTicks()} and {@link #nestedActions()} for it. Unknown types log a warning and
 * are skipped — never thrown.</p>
 *
 * <p>All string parameters go through {@link PlaceholderService#parse(Player, String)} plus any
 * caller-supplied {@code context} entries (substituted as {@code {key}}), then are rendered to
 * Adventure {@link Component}s by {@link ActionText} (MiniMessage, with legacy {@code &}
 * codes translated for backwards compatibility).</p>
 */
public final class Action {

    private static final Logger LOGGER = Logger.getLogger("Mutool-Action");

    /** Canonical action kinds, resolved from the raw {@code type} via {@link #canonical(String)}. */
    private static final String K_COMMAND = "command";
    private static final String K_CONSOLE_COMMAND = "console_command";
    private static final String K_MESSAGE = "message";
    private static final String K_BROADCAST = "broadcast";
    private static final String K_TITLE = "title";
    private static final String K_ACTIONBAR = "actionbar";
    private static final String K_SOUND = "sound";
    private static final String K_CHAT = "chat";
    private static final String K_CLOSE = "close";
    private static final String K_DELAY = "delay";

    private final String rawType;
    private final String kind; // canonical, or "" when unknown
    private final Map<String, Object> params;
    private final List<Action> nestedActions;

    private Action(String rawType, Map<String, Object> params, List<Action> nestedActions) {
        this.rawType = rawType == null ? "" : rawType.trim();
        this.kind = canonical(this.rawType);
        this.params = params == null ? Collections.emptyMap() : params;
        this.nestedActions = nestedActions == null ? Collections.emptyList() : nestedActions;
    }

    /** The raw {@code type} string as written in config. */
    public String type() {
        return this.rawType;
    }

    /**
     * The canonical action kind after alias resolution (e.g. {@code player_command -> command},
     * {@code action_bar -> actionbar}), or {@code ""} when the type is unsupported.
     */
    public String kind() {
        return this.kind;
    }

    /** Read-only view of this node's parameters (nested sections flattened to maps). */
    public Map<String, Object> params() {
        return Collections.unmodifiableMap(this.params);
    }

    /** Whether this action is the {@code delay} kind. */
    public boolean isDelay() {
        return K_DELAY.equals(this.kind);
    }

    /** The actions nested under a {@code delay} node (empty when there are none). */
    public List<Action> nestedActions() {
        return Collections.unmodifiableList(this.nestedActions);
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parse one action node. Accepts either a TabooLib {@link ConfigurationSection} or a
     * (possibly nested) {@link Map}.
     *
     * @throws IllegalArgumentException if the node is neither a section nor a map
     */
    public static Action parse(Object node) {
        if (node instanceof Action action) {
            return action;
        }
        if (node instanceof ConfigurationSection section) {
            return fromSection(section);
        }
        if (node instanceof Map<?, ?> map) {
            return fromMap(map);
        }
        throw new IllegalArgumentException("Unsupported action node: "
                + (node == null ? "null" : node.getClass().getName()));
    }

    /** Parse an action from a TabooLib configuration section node. */
    public static Action fromSection(ConfigurationSection section) {
        String type = section.getString("type");
        Map<String, Object> params = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if ("type".equals(key)) {
                continue;
            }
            params.put(key, normalize(section.get(key)));
        }
        return build(type, params);
    }

    /** Parse an action from a (possibly nested) map node. */
    public static Action fromMap(Map<?, ?> map) {
        String type = asString(map.get("type"));
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = asString(entry.getKey());
            if (key == null || "type".equals(key)) {
                continue;
            }
            params.put(key, normalize(entry.getValue()));
        }
        return build(type, params);
    }

    /**
     * Build an action, extracting the optional nested {@code actions} list (used by
     * {@code delay}) into parsed {@link Action}s and leaving every other value as a scalar.
     */
    private static Action build(String type, Map<String, Object> params) {
        List<Action> nested = new ArrayList<>();
        Object raw = params.remove("actions");
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                Action child = coerce(element);
                if (child != null) {
                    nested.add(child);
                }
            }
        } else {
            Action child = coerce(raw);
            if (child != null) {
                nested.add(child);
            }
        }
        return new Action(type, params, nested);
    }

    private static Action coerce(Object node) {
        if (node instanceof Action action) {
            return action;
        }
        if (node instanceof ConfigurationSection || node instanceof Map) {
            try {
                return parse(node);
            } catch (Throwable throwable) {
                LOGGER.warning("[Mutool/Action] 解析子动作失败: " + throwable);
                return null;
            }
        }
        return null;
    }

    /**
     * Normalise a raw config value: nested sections/maps become plain maps (so {@code actions}
     * children can be parsed lazily) and lists are mapped element-wise. Scalars are kept as-is.
     */
    private static Object normalize(Object value) {
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                map.put(key, normalize(section.get(key)));
            }
            return map;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = asString(entry.getKey());
                if (key != null) {
                    out.put(key, normalize(entry.getValue()));
                }
            }
            return out;
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

    /** Map a raw {@code type} to a canonical kind, or {@code ""} when unsupported. */
    private static String canonical(String type) {
        if (type == null) {
            return "";
        }
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "command", "player_command", "player-command" -> K_COMMAND;
            case "console_command", "console-command", "console" -> K_CONSOLE_COMMAND;
            case "message", "msg" -> K_MESSAGE;
            case "broadcast", "broadcast_message" -> K_BROADCAST;
            case "title" -> K_TITLE;
            case "actionbar", "action_bar", "action-bar" -> K_ACTIONBAR;
            case "sound", "play_sound", "playsound" -> K_SOUND;
            case "chat" -> K_CHAT;
            case "close", "close_inventory", "closeinventory" -> K_CLOSE;
            case "delay", "wait" -> K_DELAY;
            default -> "";
        };
    }

    // ----------------------------------------------------------------- execution

    /**
     * Execute this action for the given player and placeholder context.
     *
     * <p>Must be invoked on the player's owning region thread (the {@link ActionEngine} wraps
     * the whole batch in {@code FoliaExecutorKt.runTask(player, ...)}). {@code player} may be
     * {@code null} for player-independent actions ({@code broadcast}, {@code console_command});
     * player-bound actions then log and skip.</p>
     */
    public void run(Player player, Map<String, String> context) {
        Map<String, String> ctx = context == null ? Collections.emptyMap() : context;
        try {
            switch (this.kind) {
                case K_COMMAND -> runCommand(player, ctx);
                case K_CONSOLE_COMMAND -> runConsoleCommand(player, ctx);
                case K_MESSAGE -> runMessage(player, ctx);
                case K_BROADCAST -> runBroadcast(player, ctx);
                case K_TITLE -> runTitle(player, ctx);
                case K_ACTIONBAR -> runActionBar(player, ctx);
                case K_SOUND -> runSound(player, ctx);
                case K_CHAT -> runChat(player, ctx);
                case K_CLOSE -> runClose(player);
                case K_DELAY -> LOGGER.fine("[Mutool/Action] delay 由 ActionEngine 统一处理，单次 run() 忽略");
                default -> LOGGER.warning("[Mutool/Action] 未知动作类型: '" + this.rawType + "'，已跳过");
            }
        } catch (Throwable throwable) {
            LOGGER.warning("[Mutool/Action] 动作执行异常 type='" + this.rawType + "': " + throwable);
        }
    }

    private void runCommand(Player player, Map<String, String> ctx) {
        String command = resolve(player, "command", ctx);
        if (command == null || command.isBlank()) {
            LOGGER.warning("[Mutool/Action] command 缺少参数 'command'");
            return;
        }
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        boolean asConsole = getBoolean("as_console", false) || getBoolean("asConsole", false);
        if (asConsole || player == null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            return;
        }
        boolean op = getBoolean("op", false);
        boolean wasOp = player.isOp();
        try {
            if (op && !wasOp) {
                player.setOp(true);
            }
            Bukkit.dispatchCommand(player, command);
        } finally {
            if (op && !wasOp) {
                player.setOp(false);
            }
        }
    }

    private void runConsoleCommand(Player player, Map<String, String> ctx) {
        String command = resolve(player, "command", ctx);
        if (command == null || command.isBlank()) {
            LOGGER.warning("[Mutool/Action] console_command 缺少参数 'command'");
            return;
        }
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private void runMessage(Player player, Map<String, String> ctx) {
        if (player == null) {
            skipNoPlayer(K_MESSAGE);
            return;
        }
        Component component = ActionText.render(resolve(player, "message", ctx));
        player.sendMessage(component);
    }

    private void runBroadcast(Player player, Map<String, String> ctx) {
        Component component = ActionText.render(resolve(player, "message", ctx));
        Bukkit.broadcast(component);
    }

    private void runTitle(Player player, Map<String, String> ctx) {
        if (player == null) {
            skipNoPlayer(K_TITLE);
            return;
        }
        Component title = ActionText.render(resolve(player, "title", ctx));
        Component subtitle = ActionText.render(resolve(player, "subtitle", ctx));
        int in = getInt("in", 20);
        int step = getInt("step", 70);
        int out = getInt("out", 10);
        Title.Times times = Title.Times.times(ticks(in), ticks(step), ticks(out));
        player.showTitle(Title.title(title, subtitle, times));
    }

    private void runActionBar(Player player, Map<String, String> ctx) {
        if (player == null) {
            skipNoPlayer(K_ACTIONBAR);
            return;
        }
        player.sendActionBar(ActionText.render(resolve(player, "message", ctx)));
    }

    private void runSound(Player player, Map<String, String> ctx) {
        if (player == null) {
            skipNoPlayer(K_SOUND);
            return;
        }
        String sound = resolve(player, "sound", ctx);
        if (sound == null || sound.isBlank()) {
            LOGGER.warning("[Mutool/Action] sound 缺少参数 'sound'");
            return;
        }
        float volume = getFloat("volume", 1.0f);
        float pitch = getFloat("pitch", 1.0f);
        Location location = player.getLocation();
        player.playSound(location, sound, volume, pitch);
    }

    private void runChat(Player player, Map<String, String> ctx) {
        if (player == null) {
            skipNoPlayer(K_CHAT);
            return;
        }
        String message = resolve(player, "message", ctx);
        if (message == null || message.isBlank()) {
            LOGGER.warning("[Mutool/Action] chat 缺少参数 'message'");
            return;
        }
        player.chat(message);
    }

    private void runClose(Player player) {
        if (player == null) {
            skipNoPlayer(K_CLOSE);
            return;
        }
        player.closeInventory();
    }

    private static void skipNoPlayer(String type) {
        LOGGER.fine("[Mutool/Action] 动作 '" + type + "' 需要玩家，但当前无玩家上下文，已跳过");
    }

    // -------------------------------------------------------------- delay helpers

    /** Delay in ticks ({@code ticks}/{@code delay}/{@code time}), defaulting to 20 (1s). */
    public long delayTicks() {
        long value = getLong("ticks", Long.MIN_VALUE);
        if (value == Long.MIN_VALUE) {
            value = getLong("delay", Long.MIN_VALUE);
        }
        if (value == Long.MIN_VALUE) {
            value = getLong("time", Long.MIN_VALUE);
        }
        return value == Long.MIN_VALUE ? 20L : Math.max(0L, value);
    }

    // ------------------------------------------------------------------ helpers

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(Math.max(0, ticks) * 50L);
    }

    /** Resolve context placeholders then run through PlaceholderService. */
    private String resolve(Player player, String key, Map<String, String> ctx) {
        return applyPlaceholders(player, asString(this.params.get(key)), ctx);
    }

    private static String applyPlaceholders(Player player, String text, Map<String, String> ctx) {
        if (text == null) {
            return null;
        }
        String result = text;
        if (ctx != null && !ctx.isEmpty()) {
            for (Map.Entry<String, String> entry : ctx.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return PlaceholderService.parse(player, result);
    }

    private boolean getBoolean(String key, boolean def) {
        Object value = this.params.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim();
        return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("1") || s.equalsIgnoreCase("yes");
    }

    private int getInt(String key, int def) {
        return (int) getLong(key, def);
    }

    private long getLong(String key, long def) {
        Object value = this.params.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return def;
        }
    }

    private float getFloat(String key, float def) {
        Object value = this.params.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }
        try {
            return Float.parseFloat(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return def;
        }
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
}
