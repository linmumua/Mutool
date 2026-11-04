package cn.linmumua.mutool.module.warp;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.database.Dao;
import cn.linmumua.mutool.database.entity.WarpData;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
 * Warp module — global named warp points backed by the {@code mumutools_warp} table.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_warp}. It owns the
 * module switch, loads {@code module/warp/config.yml} &amp; {@code module/warp/lang.yml}
 * through TabooLib's {@link Configuration}, and registers the three sub-commands
 * ({@code /warp}, {@code /setwarp}, {@code /delwarp}) directly through Bukkit's
 * {@link CommandMap} (no plugin.yml command entries), each guarded by its own
 * permission node.</p>
 *
 * <p><b>No cross-server support.</b> MumuTools used BungeeCord to carry warps between
 * servers; Mutool is single-server only, so the {@code server} column is always stored
 * as {@code "current"} and the location is rebuilt from the stored world name via
 * {@link Bukkit#getWorld(String)}. Teleportation is Folia-safe: the actual teleport is
 * scheduled onto the player's own region thread via
 * {@link FoliaExecutorKt#runTask(org.bukkit.entity.Player, Runnable)} before calling
 * {@link Player#teleportAsync(Location)}.</p>
 */
public final class WarpModule implements MutoolModule {

    /** Permission node required to run {@code /warp}. */
    public static final String PERM_WARP = "mutool.commands.warp";
    /** Permission node required to run {@code /setwarp}. */
    public static final String PERM_SETWARP = "mutool.commands.setwarp";
    /** Permission node required to run {@code /delwarp}. */
    public static final String PERM_DELWARP = "mutool.commands.delwarp";

    /** Placeholder written into {@link WarpData#getServer()} — Mutool has no cross-server. */
    private static final String CURRENT_SERVER = "current";

    private static final String CONFIG_RESOURCE = "module/warp/config.yml";
    private static final String LANG_RESOURCE = "module/warp/lang.yml";

    private static final String DEFAULT_WARP = "warp";
    private static final String DEFAULT_SETWARP = "setwarp";
    private static final String DEFAULT_DELWARP = "delwarp";

    private Mutool plugin;
    private final WarpLang lang = new WarpLang();

    private Configuration config;
    private boolean enabled;
    private final List<PluginCommand> commands = new ArrayList<>();

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public WarpModule() {
    }

    @Override
    public String id() {
        return "warp";
    }

    /** Release default resources, load config/lang, then register the commands. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);

        this.applyState();
    }

    /** Reload config + lang from disk and re-apply the enable state / (re)register the commands. */
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

    /** Unregister every command and drop state. Called on plugin disable. */
    @Override
    public void unload() {
        this.unregisterCommands();
        this.enabled = false;
    }

    // --- internals ---

    private void applyState() {
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterCommands();
        if (nowEnabled) {
            this.registerCommands();
        }
        this.enabled = nowEnabled && !this.commands.isEmpty();

        if (!nowEnabled) {
            IOKt.info("[Mutool/Warp] 传送点模块已关闭 (enable: false)");
        }
    }

    /**
     * Resolve the three command labels from the config {@code commands} list. The list is
     * positional: {@code [warp, setwarp, delwarp]}. Any missing / blank entry falls back
     * to the bundled default so the module always registers something usable.
     */
    private List<String> resolveNames() {
        List<String> configured = this.config == null ? null : this.config.getStringList("commands");
        List<String> defaults = List.of(DEFAULT_WARP, DEFAULT_SETWARP, DEFAULT_DELWARP);
        List<String> names = new ArrayList<>();
        for (int index = 0; index < defaults.size(); index++) {
            String value = configured != null && configured.size() > index ? configured.get(index) : null;
            if (value == null || value.isBlank()) {
                value = defaults.get(index);
            }
            names.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private void registerCommands() {
        List<String> names = this.resolveNames();
        this.register(names.get(0), PERM_WARP, Sub.WARP);
        this.register(names.get(1), PERM_SETWARP, Sub.SETWARP);
        this.register(names.get(2), PERM_DELWARP, Sub.DELWARP);
    }

    // permissionMessage(Component) is deprecated in the current Paper API but still the
    // only way to render a themed MiniMessage permission denied message per command.
    @SuppressWarnings("deprecation")
    private void register(String name, String permission, Sub sub) {
        try {
            Constructor<PluginCommand> constructor =
                    PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);

            PluginCommand cmd = constructor.newInstance(name, this.plugin);
            cmd.setPermission(permission);
            cmd.permissionMessage(this.lang.noPermission());
            cmd.setDescription(this.lang.plain(sub.descriptionKey));

            WarpExecutor executor = new WarpExecutor(this, sub);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.commands.add(cmd);

            IOKt.info("[Mutool/Warp] 已注册命令: /" + name + " (权限: " + permission + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Warp] 注册命令 /" + name + " 失败: " + throwable);
        }
    }

    private void unregisterCommands() {
        if (this.commands.isEmpty()) {
            return;
        }
        CommandMap commandMap = this.plugin.getServer().getCommandMap();
        for (PluginCommand command : this.commands) {
            try {
                command.unregister(commandMap);
            } catch (Throwable ignored) {
            }
        }
        this.commands.clear();
    }

    /** Typed DAO for the warp table. Throws if the data layer is unavailable. */
    private Dao<WarpData, String> dao() {
        if (this.plugin.getDatabaseManager() == null) {
            throw new IllegalStateException("数据库未连接");
        }
        return this.plugin.getDatabaseManager().dao(WarpData.class);
    }

    /** Names of every existing warp — used for tab completion. Never throws. */
    private List<String> warpNames() {
        try {
            return this.dao().getList().stream().map(WarpData::getWarp).toList();
        } catch (Throwable throwable) {
            return new ArrayList<>();
        }
    }

    // --- sub-command handlers (invoked from the executor) ---

    void handleWarp(Player player, String label, String[] args) {
        if (args.length != 1) {
            player.sendMessage(this.lang.usageError(label, Sub.WARP.usageKey));
            return;
        }
        String name = args[0];
        WarpData data = this.dao().getById(name);
        if (data == null) {
            player.sendMessage(this.lang.warpNoWarp(name));
            return;
        }
        World world = Bukkit.getWorld(data.getWorld());
        if (world == null) {
            player.sendMessage(this.lang.worldMissing(data.getWorld()));
            return;
        }
        Location target = new Location(world,
                data.getX(), data.getY(), data.getZ(), data.getYaw(), data.getPitch());
        // Folia 安全：投递到玩家区域线程后再执行传送
        FoliaExecutorKt.runTask(player, () -> {
            player.teleportAsync(target);
            player.sendMessage(this.lang.warpMessage(name));
        });
    }

    void handleSetWarp(Player player, String label, String[] args) {
        if (args.length != 1) {
            player.sendMessage(this.lang.usageError(label, Sub.SETWARP.usageKey));
            return;
        }
        String name = args[0];
        Location location = player.getLocation();

        WarpData data = new WarpData(name);
        data.setServer(CURRENT_SERVER);
        data.setWorld(location.getWorld().getName());
        data.setX(location.getX());
        data.setY(location.getY());
        data.setZ(location.getZ());
        data.setYaw(location.getYaw());
        data.setPitch(location.getPitch());
        this.dao().createOrUpdate(data);

        player.sendMessage(this.lang.setWarpMessage(name));
    }

    void handleDelWarp(Player player, String label, String[] args) {
        if (args.length != 1) {
            player.sendMessage(this.lang.usageError(label, Sub.DELWARP.usageKey));
            return;
        }
        String name = args[0];
        Dao<WarpData, String> dao = this.dao();
        WarpData data = dao.getById(name);
        if (data == null) {
            player.sendMessage(this.lang.delWarpNoWarp(name));
            return;
        }
        dao.delete(data);
        player.sendMessage(this.lang.delWarpMessage(name));
    }

    /** Which sub-command a given {@link PluginCommand} represents. */
    enum Sub {
        WARP("commands.warp.usage", "commands.warp.description"),
        SETWARP("commands.setwarp.usage", "commands.setwarp.description"),
        DELWARP("commands.delwarp.usage", "commands.delwarp.description");

        private final String usageKey;
        private final String descriptionKey;

        Sub(String usageKey, String descriptionKey) {
            this.usageKey = usageKey;
            this.descriptionKey = descriptionKey;
        }
    }

    /** Executor shared by all three commands; dispatches on the bound {@link Sub}. */
    private static final class WarpExecutor implements TabExecutor {

        private final WarpModule module;
        private final Sub sub;

        private WarpExecutor(WarpModule module, Sub sub) {
            this.module = module;
            this.sub = sub;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(this.module.lang.onlyPlayer());
                return true;
            }
            try {
                switch (this.sub) {
                    case WARP -> this.module.handleWarp(player, label, args);
                    case SETWARP -> this.module.handleSetWarp(player, label, args);
                    case DELWARP -> this.module.handleDelWarp(player, label, args);
                }
            } catch (Throwable throwable) {
                player.sendMessage(this.module.lang.databaseError());
                IOKt.severe("[Mutool/Warp] 执行 /" + label + " 失败: " + throwable);
            }
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (args.length == 1 && (this.sub == Sub.WARP || this.sub == Sub.DELWARP)) {
                return this.module.warpNames();
            }
            return new ArrayList<>();
        }
    }
}
