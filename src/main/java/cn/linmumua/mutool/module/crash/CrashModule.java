package cn.linmumua.mutool.module.crash;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Crash module — sends crafted packets to a target player's client to reproduce known
 * client-crash packets ({@code explosion}, {@code invalid_teleport}, {@code invalid_particle}).
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_crash}. It owns the
 * module switch, loads {@code module/crash/config.yml} &amp; {@code module/crash/lang.yml}
 * through TabooLib's {@link Configuration}, and registers the {@code /crash} command
 * directly through Bukkit's {@link CommandMap} (no plugin.yml command entries). The
 * actual packet construction lives in {@link CrashService}.</p>
 */
public final class CrashModule implements MutoolModule {

    /** Permission node required to run {@code /crash}. */
    public static final String PERMISSION = "mutool.commands.crash";

    private static final String CONFIG_RESOURCE = "module/crash/config.yml";
    private static final String LANG_RESOURCE = "module/crash/lang.yml";

    private static final List<String> CRASH_TYPES =
            List.of("explosion", "invalid_teleport", "invalid_particle");

    private Mutool plugin;
    private final CrashLang lang = new CrashLang();

    private Configuration config;
    private boolean enabled;
    private String defaultType = "explosion";
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public CrashModule() {
    }

    @Override
    public String id() {
        return "crash";
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
        this.defaultType = this.config == null ? "explosion"
                : this.config.getString("default_type", "explosion");
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterCommand();
        if (nowEnabled) {
            this.ensurePermission();
            this.registerCommand(aliases);
        }
        this.enabled = nowEnabled && this.command != null;

        if (!nowEnabled) {
            IOKt.info("[Mutool/Crash] 崩溃模块已关闭 (enable: false)");
        }
    }

    /**
     * Register the {@code mutool.commands.crash} permission programmatically. The module is not
     * declared in the (immutable) {@code paper-plugin.yml}, so without this the permission node
     * would be unknown and ordinary players would be denied. Defaults to {@link PermissionDefault#OP}
     * because crashing a client is a destructive administrative action. Idempotent.
     */
    private void ensurePermission() {
        try {
            PluginManager pluginManager = this.plugin.getServer().getPluginManager();
            if (pluginManager.getPermission(PERMISSION) == null) {
                pluginManager.addPermission(new Permission(
                        PERMISSION, "允许使用 /crash 崩溃玩家客户端", PermissionDefault.OP));
            }
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Crash] 注册权限节点失败: " + throwable);
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
            aliases.add("crash");
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

            CrashExecutor executor = new CrashExecutor(this.lang, () -> this.defaultType);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Crash] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Crash] 注册 /crash 命令失败: " + throwable);
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

    /** Executor for {@code /crash}; validates args and delegates packet sending to {@link CrashService}. */
    private static final class CrashExecutor implements TabExecutor {

        private final CrashLang lang;
        private final java.util.function.Supplier<String> defaultType;

        private CrashExecutor(CrashLang lang, java.util.function.Supplier<String> defaultType) {
            this.lang = lang;
            this.defaultType = defaultType;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (args.length < 1 || args.length > 2) {
                sender.sendMessage(this.lang.usageError(label));
                return true;
            }

            Player player = Bukkit.getPlayerExact(args[0]);
            if (player == null) {
                sender.sendMessage(this.lang.playerOffline());
                return true;
            }

            String type = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : this.defaultType.get();

            if (type == null || !CrashService.crashPlayerClient(player, type)) {
                sender.sendMessage(this.lang.noType());
                return true;
            }

            sender.sendMessage(this.lang.message(player.getName(), this.lang.typeRaw(type)));
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (args.length == 1) {
                List<String> names = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    names.add(online.getName());
                }
                return names;
            }
            if (args.length == 2) {
                return new ArrayList<>(CRASH_TYPES);
            }
            return new ArrayList<>();
        }
    }
}
