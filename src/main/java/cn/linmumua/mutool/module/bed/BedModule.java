package cn.linmumua.mutool.module.bed;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.Location;
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
 * Bed module — sends a player back to their bed (respawn) location.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_bed}. It owns the
 * module switch, loads {@code module/bed/config.yml} &amp; {@code module/bed/lang.yml}
 * through TabooLib's {@link Configuration}, and registers the {@code /bed} command
 * directly through Bukkit's {@link CommandMap} (no plugin.yml command entries).</p>
 *
 * <p>Teleportation is Folia-safe: the actual teleport is scheduled onto the player's
 * own region thread via {@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)}
 * before calling {@link Player#teleportAsync(Location)}.</p>
 */
public final class BedModule implements MutoolModule {

    /** Permission node required to run {@code /bed}. */
    public static final String PERMISSION = "mutool.commands.bed";

    private static final String CONFIG_RESOURCE = "module/bed/config.yml";
    private static final String LANG_RESOURCE = "module/bed/lang.yml";

    private Mutool plugin;
    private final BedLang lang = new BedLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public BedModule() {
    }

    @Override
    public String id() {
        return "bed";
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
            IOKt.info("[Mutool/Bed] 回床模块已关闭 (enable: false)");
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
            aliases.add("bed");
        }
        return aliases;
    }

    /** Whether {@code /bed} is disabled in the given world (config {@code black_world}). */
    boolean isBlackWorld(String worldName) {
        List<String> blackWorld = this.config == null ? null : this.config.getStringList("black_world");
        return blackWorld != null && blackWorld.contains(worldName);
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

            BedExecutor executor = new BedExecutor(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Bed] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Bed] 注册 /bed 命令失败: " + throwable);
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

    /** Executor for {@code /bed}; teleports the player back to their bed. */
    private static final class BedExecutor implements TabExecutor {

        private final BedModule module;

        private BedExecutor(BedModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(this.module.lang.onlyPlayer());
                return true;
            }
            if (args.length != 0) {
                player.sendMessage(this.module.lang.usageError(label));
                return true;
            }

            // 世界黑名单：配置 black_world 中的世界禁止使用 /bed
            if (this.module.isBlackWorld(player.getWorld().getName())) {
                player.sendMessage(this.module.lang.blackWorld());
                return true;
            }

            Location bed = player.getBedSpawnLocation();
            if (bed == null) {
                player.sendMessage(this.module.lang.noBed());
                return true;
            }

            // Folia 安全：投递到玩家区域线程后再执行传送
            FoliaExecutorKt.runTask(player, () -> {
                player.teleportAsync(bed);
                player.sendMessage(this.module.lang.message());
            });
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            return new ArrayList<>();
        }
    }
}
