package cn.linmumua.mutool.module.gamemode;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;
import taboolib.platform.util.FoliaExecutorKt;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gamemode module — switch a player's {@link GameMode} with {@code /gamemode}.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_gamemode}. It owns the
 * module switch, loads {@code module/gamemode/config.yml} &amp; {@code module/gamemode/lang.yml}
 * through TabooLib's {@link Configuration}, and registers the {@code /gamemode} command
 * (with the {@code /gm} alias) directly through Bukkit's {@link CommandMap} (no
 * {@code plugin.yml} command entries).</p>
 *
 * <p><strong>Single-server only.</strong> The original module forwarded a
 * {@code GameModeMessage} over Redis so that {@code /gamemode <mode> <player>} could also
 * flip the gamemode of a player on <em>another</em> server, and its own receive-handler
 * re-applied that change on the target server. This port drops that whole cross-server
 * path — only players online on <em>this</em> server are affected, resolved through
 * {@link Bukkit#getPlayerExact(String)}.</p>
 *
 * <p>Commands:</p>
 * <ul>
 *   <li>{@code /gamemode <survival|creative|adventure|spectator>} — change your own gamemode.</li>
 *   <li>{@code /gamemode <mode> <player>} — change another player's gamemode (requires
 *       {@value #PERMISSION_GIVE}).</li>
 * </ul>
 *
 * <p>The gamemode change is dispatched onto the target player's own region thread through
 * {@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)} so the module is
 * Folia safe.</p>
 */
public final class GamemodeModule implements MutoolModule {

    /** Permission node required to run {@code /gamemode} (on yourself). */
    public static final String PERMISSION = "mutool.commands.gamemode";
    /** Permission node required to change another player's gamemode with {@code /gamemode <mode> <player>}. */
    public static final String PERMISSION_GIVE = "mutool.commands.gamemode.give";

    private static final String CONFIG_RESOURCE = "module/gamemode/config.yml";
    private static final String LANG_RESOURCE = "module/gamemode/lang.yml";

    private Mutool plugin;
    private final GamemodeLang lang = new GamemodeLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Lower-cased alias -> gamemode lookup, rebuilt from config on every (re)load. */
    private Map<String, GameMode> aliases = new LinkedHashMap<>();
    /** Tab-completion candidates for the first argument, rebuilt from config on every (re)load. */
    private List<String> tabCompleter = new ArrayList<>();

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public GamemodeModule() {
    }

    @Override
    public String id() {
        return "gamemode";
    }

    /** Release default resources, load config/lang, then register the command. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);

        this.applyState();
    }

    /** Reload config + lang from disk and re-apply the enable state / (re)register the command. */
    @Override
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
        this.lang.reload();
        this.applyState();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    /** Unregister the command and drop state. Called on plugin disable. */
    @Override
    public void unload() {
        this.unregisterCommand();
        this.enabled = false;
    }

    // --- internals ---

    private void applyState() {
        this.unregisterCommand();
        this.rebuildLookups();

        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);
        if (nowEnabled) {
            this.registerCommand(this.resolveAliases());
        }
        this.enabled = nowEnabled && this.command != null;

        if (!nowEnabled) {
            IOKt.info("[Mutool/Gamemode] 游戏模式模块已关闭 (enable: false)");
        }
    }

    /** Rebuild the gamemode alias map and tab-completion list from the config file. */
    private void rebuildLookups() {
        Map<String, GameMode> map = new LinkedHashMap<>();
        List<String> completion = new ArrayList<>();
        if (this.config != null) {
            for (GameMode gameMode : GameMode.values()) {
                List<String> list = this.config.getStringList("gamemode." + gameMode.name().toLowerCase(Locale.ROOT));
                if (list != null) {
                    for (String alias : list) {
                        if (alias != null && !alias.isBlank()) {
                            map.put(alias.trim().toLowerCase(Locale.ROOT), gameMode);
                        }
                    }
                }
                // Always allow the lower-cased enum name as an alias (e.g. /gamemode creative),
                // even if the config list is missing or customised.
                map.putIfAbsent(gameMode.name().toLowerCase(Locale.ROOT), gameMode);
            }

            List<String> configured = this.config.getStringList("tabCompleter");
            if (configured != null) {
                for (String value : configured) {
                    if (value != null && !value.isBlank()) {
                        completion.add(value.trim());
                    }
                }
            }
        }
        if (completion.isEmpty()) {
            completion.add("survival");
            completion.add("creative");
            completion.add("adventure");
            completion.add("spectator");
        }

        this.aliases = map;
        this.tabCompleter = completion;
    }

    private List<String> resolveAliases() {
        List<String> commands = this.config == null ? null : this.config.getStringList("commands");
        List<String> result = new ArrayList<>();
        if (commands != null) {
            for (String value : commands) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (result.isEmpty()) {
            result.add("gamemode");
            result.add("gm");
        }
        return result;
    }

    /** Resolve a raw argument ({@code survival} / {@code 生存} / {@code 0} …) to a {@link GameMode}, or {@code null}. */
    GameMode resolveGameMode(String input) {
        if (input == null) {
            return null;
        }
        GameMode mapped = this.aliases.get(input.trim().toLowerCase(Locale.ROOT));
        if (mapped != null) {
            return mapped;
        }
        // Final fallback: accept the enum name case-insensitively (e.g. "SPECTATOR").
        try {
            return GameMode.valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    List<String> tabCompleter() {
        return new ArrayList<>(this.tabCompleter);
    }

    // permissionMessage(Component) is deprecated in the current Paper API but still the
    // only way to render a themed MiniMessage permission denied message per command.
    @SuppressWarnings("deprecation")
    private void registerCommand(List<String> commandAliases) {
        try {
            Constructor<PluginCommand> constructor =
                    PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);

            PluginCommand cmd = constructor.newInstance(commandAliases.get(0), this.plugin);
            if (commandAliases.size() > 1) {
                cmd.setAliases(commandAliases.subList(1, commandAliases.size()));
            }
            cmd.setPermission(PERMISSION);
            cmd.permissionMessage(this.lang.noPermission());
            cmd.setDescription(this.lang.plainDescription());

            GamemodeExecutor executor = new GamemodeExecutor(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Gamemode] 已注册命令: /" + commandAliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Gamemode] 注册 /gamemode 命令失败: " + throwable);
        }
    }

    private void unregisterCommand() {
        if (this.command == null) {
            return;
        }
        try {
            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            this.command.unregister(commandMap);
        } catch (Throwable ignored) {
        }
        this.command = null;
    }

    /** Executor for {@code /gamemode <mode> [player]}. */
    private static final class GamemodeExecutor implements TabExecutor {

        private final GamemodeModule module;

        private GamemodeExecutor(GamemodeModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (args.length == 0) {
                sender.sendMessage(this.module.lang.usageError(label));
                return true;
            }

            String targetName = null;
            Player target = null;

            // Change the sender's own gamemode.
            if (args.length == 1 && sender instanceof Player player) {
                target = player;
                targetName = player.getName();
            }

            // Change another player's gamemode.
            if (args.length == 2) {
                if (!sender.hasPermission(PERMISSION_GIVE)) {
                    sender.sendMessage(this.module.lang.noPermission());
                    return true;
                }
                targetName = args[1];
                target = Bukkit.getPlayerExact(targetName);
                if (target == null) {
                    sender.sendMessage(this.module.lang.playerOffline());
                    return true;
                }
            }

            // Recognised usage but couldn't resolve a target → show usage.
            if (targetName == null) {
                sender.sendMessage(this.module.lang.usageError(label));
                return true;
            }

            GameMode gameMode = this.module.resolveGameMode(args[0]);
            if (gameMode == null) {
                sender.sendMessage(this.module.lang.noGameMode(args[0]));
                return true;
            }

            final Player finalTarget = target;
            // Folia 安全：投递到目标玩家区域线程后再修改游戏模式
            FoliaExecutorKt.runTask(finalTarget, () -> {
                finalTarget.setGameMode(gameMode);
                Component message = this.module.lang.message(finalTarget.getName(), this.module.lang.gameModeName(gameMode));
                if (!sender.equals(finalTarget)) {
                    sender.sendMessage(message);
                }
                finalTarget.sendMessage(message);
            });
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (args.length == 1) {
                return this.module.tabCompleter();
            }
            if (args.length == 2 && sender.hasPermission(PERMISSION_GIVE)) {
                List<String> names = new ArrayList<>();
                String prefix = args[1] == null ? "" : args[1].toLowerCase(Locale.ROOT);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        names.add(online.getName());
                    }
                }
                return names;
            }
            return new ArrayList<>();
        }
    }
}
