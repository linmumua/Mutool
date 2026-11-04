package cn.linmumua.mutool.module.list;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.Bukkit;
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

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * List module — shows the server status panel through {@code /list}.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_list}. It owns the
 * module switch, loads {@code module/list/config.yml} &amp; {@code module/list/lang.yml}
 * through TabooLib's {@link Configuration}, and registers the {@code /list} command
 * directly through Bukkit's {@link CommandMap} (no plugin.yml command entries).</p>
 *
 * <p><strong>Single-server variant:</strong> the online-player list is built from
 * {@link Bukkit#getOnlinePlayers()} only — there is no cross-server (BungeeCord)
 * aggregation. TPS comes from {@link Bukkit#getTPS()} (Paper API) and memory is
 * derived from {@link Runtime}.</p>
 */
public final class ListModule implements MutoolModule {

    /** Permission node required to run {@code /list}. */
    public static final String PERMISSION = "mutool.commands.list";

    private static final String CONFIG_RESOURCE = "module/list/config.yml";
    private static final String LANG_RESOURCE = "module/list/lang.yml";

    private Mutool plugin;
    private final ListLang lang = new ListLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public ListModule() {
    }

    @Override
    public String id() {
        return "list";
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
            IOKt.info("[Mutool/List] 在线列表模块已关闭 (enable: false)");
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
            aliases.add("list");
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

            ListExecutor executor = new ListExecutor(this.lang);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/List] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/List] 注册 /list 命令失败: " + throwable);
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

    /** Executor for {@code /list}; renders the status panel. Console-friendly. */
    private static final class ListExecutor implements TabExecutor {

        private final ListLang lang;

        private ListExecutor(ListLang lang) {
            this.lang = lang;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (args.length != 0) {
                sender.sendMessage(this.lang.usageError(label));
                return true;
            }

            // TPS — Paper API: double[] of 1/5/15-minute averages, [0] = current (1 min).
            double[] tps = Bukkit.getTPS();
            String tpsText = String.format("%.2f", tps.length > 0 ? tps[0] : 0.0d);

            // Memory — used / max, in MB.
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
            long maxMemory = runtime.maxMemory() / 1048576L;

            // Online players — single server only (no cross-server aggregation).
            List<String> playerNames = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                playerNames.add(player.getName());
            }

            sender.sendMessage(this.lang.message(
                    tpsText,
                    usedMemory,
                    maxMemory,
                    playerNames.size(),
                    Bukkit.getMaxPlayers(),
                    playerNames.toString()
            ));
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            return new ArrayList<>();
        }
    }
}
