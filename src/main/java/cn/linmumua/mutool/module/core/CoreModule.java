package cn.linmumua.mutool.module.core;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
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

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Core module — the {@code /mutool} master command.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_core}. It owns the
 * module switch, loads {@code module/core/config.yml} &amp; {@code module/core/lang.yml}
 * through TabooLib's {@link Configuration}, and registers the {@code /mutool} command
 * (alias {@code /mt}) directly through Bukkit's {@link CommandMap} (no plugin.yml
 * command entries).</p>
 *
 * <p>Sub-commands:</p>
 * <ul>
 *   <li>{@code /mutool help} — command help panel (lists this module's sub-commands)</li>
 *   <li>{@code /mutool info} — plugin version &amp; enabled/total module counts</li>
 *   <li>{@code /mutool modules} — loaded / unloaded module ids (via {@link cn.linmumua.mutool.module.ModuleManager})</li>
 *   <li>{@code /mutool reload} — reload every module ({@link cn.linmumua.mutool.module.ModuleManager#reloadAll()})</li>
 *   <li>{@code /mutool feature [page]} — paginated list of every module (feature)</li>
 * </ul>
 *
 * <p>Unlike MumuTools, the port has no central command registry: each module registers
 * its own commands, so {@code feature} enumerates the modules reported by
 * {@link cn.linmumua.mutool.module.ModuleManager#modules()} instead of a command map.</p>
 */
public final class CoreModule implements MutoolModule {

    /** Permission node required to run {@code /mutool}. */
    public static final String PERMISSION = "mutool.commands.mutool";

    private static final String CONFIG_RESOURCE = "module/core/config.yml";
    private static final String LANG_RESOURCE = "module/core/lang.yml";

    /** Feature entries shown per page (matches the original MumuTools feature panel). */
    private static final int FEATURE_PAGE_SIZE = 4;

    /** Sub-command keys used both for lang lookups and tab completion, in display order. */
    private static final List<String> SUB_COMMANDS = List.of("help", "info", "modules", "reload", "feature");

    private Mutool plugin;
    private final CoreLang lang = new CoreLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public CoreModule() {
    }

    @Override
    public String id() {
        return "core";
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
            IOKt.info("[Mutool/Core] 核心模块已关闭 (enable: false)");
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
            aliases.add("mutool");
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

            CoreExecutor executor = new CoreExecutor(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Core] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Core] 注册 /mutool 命令失败: " + throwable);
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

    // --- module data helpers ---

    /** Every module currently known to the manager (never {@code null}). */
    private List<MutoolModule> modules() {
        if (this.plugin == null || this.plugin.getModuleManager() == null) {
            return List.of();
        }
        return this.plugin.getModuleManager().modules();
    }

    private static String safeId(MutoolModule module) {
        try {
            String id = module.id();
            return id == null ? module.getClass().getSimpleName() : id;
        } catch (Throwable throwable) {
            return module.getClass().getSimpleName();
        }
    }

    private String version() {
        try {
            return this.plugin.getDescription().getVersion();
        } catch (Throwable throwable) {
            return "未知";
        }
    }

    // --- sub-command handlers (invoked from the executor) ---

    void handle(CommandSender sender, String label, String[] args) {
        String sub = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "help" -> this.sendHelp(sender, label);
            case "modules" -> this.sendModules(sender, label);
            case "reload" -> this.doReload(sender, label);
            case "feature" -> this.sendFeature(sender, label, args);
            case "info" -> this.sendInfo(sender, label);
            default -> this.sendInfo(sender, label);
        }
    }

    /** Build one {@code command_info_format} line for the given sub-command key. */
    private String subCommandInfo(String label, String sub) {
        String usage = this.lang.raw("commands.mutool.sub_commands." + sub + ".usage").replace("{command}", label);
        String description = this.lang.raw("commands.mutool.sub_commands." + sub + ".description");
        return this.lang.raw("command_info_format")
                .replace("{usage}", usage)
                .replace("{description}", description);
    }

    private void sendHelp(CommandSender sender, String label) {
        StringBuilder helpList = new StringBuilder();
        for (String sub : SUB_COMMANDS) {
            if (helpList.length() > 0) {
                helpList.append('\n');
            }
            helpList.append(this.subCommandInfo(label, sub));
        }
        sender.sendMessage(this.lang.render("commands.mutool.sub_commands.help.message",
                "{help_list}", helpList.toString(),
                "{command}", label));
    }

    private void sendInfo(CommandSender sender, String label) {
        List<MutoolModule> modules = this.modules();
        int loadCount = 0;
        for (MutoolModule module : modules) {
            if (module.isEnabled()) {
                loadCount++;
            }
        }
        sender.sendMessage(this.lang.render("commands.mutool.sub_commands.info.message",
                "{version}", this.version(),
                "{load_count}", String.valueOf(loadCount),
                "{total_count}", String.valueOf(modules.size()),
                "{command}", label));
    }

    private void sendModules(CommandSender sender, String label) {
        StringBuilder load = new StringBuilder();
        StringBuilder unload = new StringBuilder();
        for (MutoolModule module : this.modules()) {
            String id = safeId(module);
            if (module.isEnabled()) {
                if (load.length() > 0) {
                    load.append(", ");
                }
                load.append(id);
            } else {
                if (unload.length() > 0) {
                    unload.append(", ");
                }
                unload.append(id);
            }
        }
        sender.sendMessage(this.lang.render("commands.mutool.sub_commands.modules.message",
                "{load_modules}", load.length() == 0 ? "无" : load.toString(),
                "{unload_modules}", unload.length() == 0 ? "无" : unload.toString(),
                "{command}", label));
    }

    private void doReload(CommandSender sender, String label) {
        try {
            if (this.plugin.getModuleManager() != null) {
                this.plugin.getModuleManager().reloadAll();
            }
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Core] 重载模块失败: " + throwable);
        }
        sender.sendMessage(this.lang.render("commands.mutool.sub_commands.reload.message",
                "{command}", label));
    }

    private void sendFeature(CommandSender sender, String label, String[] args) {
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }

        List<String> entries = this.featureEntries();
        int total = entries.size();
        int maxPage = Math.max(1, (int) Math.ceil(total / (double) FEATURE_PAGE_SIZE));

        if (page < 1) {
            page = 1;
        }
        if (page > maxPage) {
            page = maxPage;
        }

        int start = (page - 1) * FEATURE_PAGE_SIZE;
        int end = Math.min(total, start + FEATURE_PAGE_SIZE);

        StringBuilder helpList = new StringBuilder();
        for (int index = start; index < end; index++) {
            if (helpList.length() > 0) {
                helpList.append('\n');
            }
            helpList.append(entries.get(index));
        }
        if (helpList.length() == 0) {
            helpList.append("<gray>暂无可用功能");
        }

        sender.sendMessage(this.lang.render("commands.mutool.sub_commands.feature.message",
                "{help_list}", helpList.toString(),
                "{page}", String.valueOf(page),
                "{max_page}", String.valueOf(maxPage),
                "{last_page}", String.valueOf(page - 1),
                "{next_page}", String.valueOf(page + 1),
                "{command}", label));
    }

    /** One {@code command_info_format} line per module, e.g. {@code /warp » 已启用}. */
    private List<String> featureEntries() {
        List<String> entries = new ArrayList<>();
        for (MutoolModule module : this.modules()) {
            String state = module.isEnabled() ? "已启用" : "未启用";
            entries.add(this.lang.raw("command_info_format")
                    .replace("{usage}", "/" + safeId(module))
                    .replace("{description}", state));
        }
        return entries;
    }

    /** Executor for {@code /mutool}; dispatches to the core sub-command handlers. */
    private static final class CoreExecutor implements TabExecutor {

        private final CoreModule module;

        private CoreExecutor(CoreModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            this.module.handle(sender, label, args);
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (args.length == 1) {
                List<String> matches = new ArrayList<>();
                String prefix = args[0].toLowerCase(Locale.ROOT);
                for (String sub : SUB_COMMANDS) {
                    if (sub.startsWith(prefix)) {
                        matches.add(sub);
                    }
                }
                return matches;
            }
            return new ArrayList<>();
        }
    }
}
