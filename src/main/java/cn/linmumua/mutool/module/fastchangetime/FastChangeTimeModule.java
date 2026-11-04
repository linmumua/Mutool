package cn.linmumua.mutool.module.fastchangetime;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FastChangeTime module — sets the world time through {@code /fastchangetime}.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_fastchangetime}.
 * It owns the module switch, loads {@code module/fastchangetime/config.yml} &amp;
 * {@code module/fastchangetime/lang.yml} through TabooLib's {@link Configuration}, and
 * registers the {@code /fastchangetime} command directly through Bukkit's
 * {@link CommandMap} (no plugin.yml command entries).</p>
 *
 * <p>Usage: {@code /fastchangetime <day|noon|night|midnight|时间值>}. Named presets
 * resolve to standard Minecraft tick values; any number in {@code 0..24000} is also
 * accepted.</p>
 *
 * <p>Time changes are Folia-safe: {@link World#setTime(long)} is dispatched onto each
 * world's owning region thread via
 * {@link FoliaExecutorKt#runTask(org.bukkit.World, double, double, Runnable)}
 * instead of a plain {@code CCScheduler} call.</p>
 */
public final class FastChangeTimeModule implements MutoolModule {

    /** Permission node required to run {@code /fastchangetime}. */
    public static final String PERMISSION = "mutool.commands.fastchangetime";

    private static final String CONFIG_RESOURCE = "module/fastchangetime/config.yml";
    private static final String LANG_RESOURCE = "module/fastchangetime/lang.yml";

    /** Valid Minecraft time range (ticks in a full day cycle). */
    private static final int MIN_TIME = 0;
    private static final int MAX_TIME = 24000;

    /** Built-in named presets (lower-case argument → world time in ticks). */
    private static final Map<String, Integer> PRESETS = createPresets();

    private Mutool plugin;
    private final FastChangeTimeLang lang = new FastChangeTimeLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public FastChangeTimeModule() {
    }

    @Override
    public String id() {
        return "fastchangetime";
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
            IOKt.info("[Mutool/FastChangeTime] 快速改时间模块已关闭 (enable: false)");
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
            aliases.add("fastchangetime");
        }
        return aliases;
    }

    private static Map<String, Integer> createPresets() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("day", 1000);
        map.put("noon", 6000);
        map.put("night", 13000);
        map.put("midnight", 18000);
        return map;
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

            FastChangeTimeExecutor executor = new FastChangeTimeExecutor(this.lang);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/FastChangeTime] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/FastChangeTime] 注册 /fastchangetime 命令失败: " + throwable);
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

    /** Executor for {@code /fastchangetime}; sets the world time to the requested value. */
    private static final class FastChangeTimeExecutor implements TabExecutor {

        private final FastChangeTimeLang lang;

        private FastChangeTimeExecutor(FastChangeTimeLang lang) {
            this.lang = lang;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (args.length != 1) {
                sender.sendMessage(this.lang.usageError(label));
                return true;
            }

            Integer resolved = resolveTime(args[0]);
            if (resolved == null) {
                sender.sendMessage(this.lang.usageError(label));
                return true;
            }

            final int time = resolved;
            // Folia 安全：投递到各世界所属区域线程后再设置时间
            for (World world : Bukkit.getWorlds()) {
                Location spawn = world.getSpawnLocation();
                FoliaExecutorKt.runTask(world, spawn.getX(), spawn.getZ(), () -> world.setTime(time));
            }

            sender.sendMessage(this.lang.message(time));
            return true;
        }

        /** Resolve an argument to a tick value: named preset first, then a raw 0..24000 number. */
        private static Integer resolveTime(String arg) {
            String key = arg.toLowerCase(Locale.ROOT);
            Integer preset = PRESETS.get(key);
            if (preset != null) {
                return preset;
            }
            try {
                int value = Integer.parseInt(arg.trim());
                if (value < MIN_TIME || value > MAX_TIME) {
                    return null;
                }
                return value;
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            List<String> result = new ArrayList<>();
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                for (String preset : PRESETS.keySet()) {
                    if (preset.startsWith(prefix)) {
                        result.add(preset);
                    }
                }
            }
            return result;
        }
    }
}
