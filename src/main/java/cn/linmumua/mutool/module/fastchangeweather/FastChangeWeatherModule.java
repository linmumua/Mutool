package cn.linmumua.mutool.module.fastchangeweather;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;
import taboolib.platform.util.FoliaExecutorKt;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FastChangeWeather module — {@code /fastchangeweather <sun|rain|storm>} switches
 * the weather of every world.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_fastchangeweather}.
 * It owns the module switch, loads {@code module/fastchangeweather/config.yml} &amp;
 * {@code module/fastchangeweather/lang.yml} through TabooLib's {@link Configuration},
 * and registers the {@code /fastchangeweather} command directly through Bukkit's
 * {@link CommandMap} (no plugin.yml command entries).</p>
 *
 * <p>Weather is changed Folia-safely: the {@link World#setStorm(boolean)} /
 * {@link World#setThundering(boolean)} calls are dispatched onto the target world's
 * region thread via {@link FoliaExecutorKt#runTask(World, double, double, Runnable)}
 * (anchored at the world spawn) instead of touching world state from the command
 * thread.</p>
 */
public final class FastChangeWeatherModule implements MutoolModule {

    /** Permission node required to run {@code /fastchangeweather}. */
    public static final String PERMISSION = "mutool.commands.fastchangeweather";

    private static final String CONFIG_RESOURCE = "module/fastchangeweather/config.yml";
    private static final String LANG_RESOURCE = "module/fastchangeweather/lang.yml";

    /** Accepted arguments, in tab-completion order. */
    private static final String[] WEATHER_ARGS = {"sun", "rain", "storm"};

    private Mutool plugin;
    private final FastChangeWeatherLang lang = new FastChangeWeatherLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public FastChangeWeatherModule() {
    }

    @Override
    public String id() {
        return "fastchangeweather";
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
        List<String> aliases = this.resolveAliases();
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterCommand();
        if (nowEnabled) {
            this.registerCommand(aliases);
        }
        this.enabled = nowEnabled && this.command != null;

        if (!nowEnabled) {
            IOKt.info("[Mutool/FastChangeWeather] 快速调节天气模块已关闭 (enable: false)");
        }
    }

    private List<String> resolveAliases() {
        List<String> commands = this.config == null ? null : this.config.getStringList("commands");
        List<String> aliases = new ArrayList<>();
        if (commands != null) {
            for (String value : commands) {
                if (value != null && !value.isBlank()) {
                    aliases.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (aliases.isEmpty()) {
            aliases.add("fastchangeweather");
        }
        return aliases;
    }

    // permissionMessage(Component) is deprecated in the current Paper API but still the
    // only way to render a themed MiniMessage permission denied message per command.
    @SuppressWarnings("deprecation")
    private void registerCommand(List<String> aliases) {
        try {
            Constructor<PluginCommand> constructor =
                    PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);

            PluginCommand cmd = constructor.newInstance(aliases.get(0), this.plugin);
            if (aliases.size() > 1) {
                cmd.setAliases(aliases.subList(1, aliases.size()));
            }
            cmd.setPermission(PERMISSION);
            cmd.permissionMessage(this.lang.noPermission());
            cmd.setDescription(this.lang.plainDescription());

            FastChangeWeatherExecutor executor = new FastChangeWeatherExecutor(this.lang);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/FastChangeWeather] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/FastChangeWeather] 注册 /fastchangeweather 命令失败: " + throwable);
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

    /**
     * Apply the given storm / thunder state to every world, Folia-safely.
     *
     * <p>For each world the mutation is scheduled onto that world's region thread via
     * {@link FoliaExecutorKt#runTask(World, double, double, Runnable)}, anchored at the
     * world spawn, so {@link World#setStorm(boolean)} / {@link World#setThundering(boolean)}
     * never run on the command / global thread.</p>
     */
    static void applyWeather(boolean storm, boolean thunder) {
        for (World world : Bukkit.getWorlds()) {
            Location anchor = world.getSpawnLocation();
            FoliaExecutorKt.runTask(world, anchor.getX(), anchor.getZ(), () -> {
                world.setStorm(storm);
                world.setThundering(thunder);
            });
        }
    }

    /** Executor for {@code /fastchangeweather <sun|rain|storm>}; switches every world's weather. */
    private static final class FastChangeWeatherExecutor implements TabExecutor {

        private final FastChangeWeatherLang lang;

        private FastChangeWeatherExecutor(FastChangeWeatherLang lang) {
            this.lang = lang;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (args.length != 1) {
                sender.sendMessage(this.lang.usageError(label));
                return true;
            }

            Boolean[] state = resolveState(args[0]);
            if (state == null) {
                sender.sendMessage(this.lang.usageError(label));
                return true;
            }
            boolean storm = state[0];
            boolean thunder = state[1];

            applyWeather(storm, thunder);
            sender.sendMessage(this.lang.message(storm, thunder));
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            List<String> result = new ArrayList<>();
            if (args.length == 1) {
                String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
                for (String option : WEATHER_ARGS) {
                    if (option.startsWith(prefix)) {
                        result.add(option);
                    }
                }
            }
            return result;
        }

        /**
         * Map an argument to its {@code [storm, thunder]} state.
         *
         * @return a two-element array, or {@code null} when the argument is unknown
         */
        private static Boolean[] resolveState(String arg) {
            if (arg == null) {
                return null;
            }
            switch (arg.toLowerCase(Locale.ROOT)) {
                case "sun":
                    return new Boolean[]{false, false};
                case "rain":
                    return new Boolean[]{true, false};
                case "storm":
                    return new Boolean[]{true, true};
                default:
                    return null;
            }
        }
    }
}
