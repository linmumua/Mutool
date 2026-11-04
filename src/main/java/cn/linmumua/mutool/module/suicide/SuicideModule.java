package cn.linmumua.mutool.module.suicide;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
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
import java.util.List;
import java.util.Locale;

/**
 * Suicide module — lets a player end their own life via {@code /suicide}.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_suicide}. It owns the
 * module switch, loads {@code module/suicide/config.yml} &amp; {@code module/suicide/lang.yml}
 * through TabooLib's {@link Configuration}, and registers the {@code /suicide} command
 * directly through Bukkit's {@link CommandMap} (no plugin.yml command entries).</p>
 *
 * <p>Behaviour: a bare {@code /suicide} sends the themed confirm prompt (whose body is a
 * native MiniMessage {@code <click:run_command:"/suicide confirm">} button);
 * {@code /suicide confirm} actually kills the player. The health change is Folia-safe:
 * it is scheduled onto the player's own region thread via
 * {@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)} before calling
 * {@link Player#setHealth(double)}.</p>
 */
public final class SuicideModule implements MutoolModule {

    /** Permission node required to run {@code /suicide}. */
    public static final String PERMISSION = "mutool.commands.suicide";

    private static final String CONFIG_RESOURCE = "module/suicide/config.yml";
    private static final String LANG_RESOURCE = "module/suicide/lang.yml";

    private Mutool plugin;
    private final SuicideLang lang = new SuicideLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public SuicideModule() {
    }

    @Override
    public String id() {
        return "suicide";
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
            IOKt.info("[Mutool/Suicide] 自杀模块已关闭 (enable: false)");
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
            aliases.add("suicide");
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

            SuicideExecutor executor = new SuicideExecutor(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Suicide] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Suicide] 注册 /suicide 命令失败: " + throwable);
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

    /** Executor for {@code /suicide}; bare call shows the confirm prompt, {@code confirm} kills. */
    private static final class SuicideExecutor implements TabExecutor {

        private final SuicideModule module;

        private SuicideExecutor(SuicideModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(this.module.lang.onlyPlayer());
                return true;
            }

            // 无参：发送确认提示（内含可点击的 /suicide confirm）
            if (args.length == 0) {
                player.sendMessage(this.module.lang.confirm());
                return true;
            }

            // /suicide confirm：执行自杀
            if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
                // Folia 安全：投递到玩家区域线程后再改变生命值
                FoliaExecutorKt.runTask(player, () -> {
                    player.setHealth(0.0);
                    player.sendMessage(this.module.lang.message());
                });
                return true;
            }

            // 其它参数：输出用法
            player.sendMessage(this.module.lang.usageError(label));
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            List<String> result = new ArrayList<>();
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                if ("confirm".startsWith(prefix)) {
                    result.add("confirm");
                }
            }
            return result;
        }
    }
}
