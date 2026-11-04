package cn.linmumua.mutool.module.spawn;

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
 * Spawn module — lets a player return to, and set, the spawn point <strong>per world</strong>.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_spawn}. It owns the
 * module switch, loads {@code module/spawn/config.yml} &amp; {@code module/spawn/lang.yml}
 * through TabooLib's {@link Configuration}, keeps the per-world spawn points in
 * {@code module/spawn/spawn.yml} (see {@link SpawnStore}), and registers the two
 * sub-commands ({@code /spawn}, {@code /setspawn}) directly through Bukkit's
 * {@link CommandMap} (no plugin.yml command entries), each guarded by its own
 * permission node.</p>
 *
 * <p><b>Single-server only.</b> MumuTools carried a BungeeCord {@code server} field with
 * its spawn; Mutool is single-server, so the spawn is stored per world
 * ({@code x / y / z / yaw / pitch}) and resolved against the player's current world.
 * Teleportation is Folia-safe: the actual teleport is scheduled onto the player's own
 * region thread via {@link FoliaExecutorKt#runTask(org.bukkit.entity.Player, Runnable)}
 * before calling {@link Player#teleportAsync(Location)}.</p>
 */
public final class SpawnModule implements MutoolModule {

    /** Permission node required to run {@code /spawn}. */
    public static final String PERM_SPAWN = "mutool.commands.spawn";
    /** Permission node required to run {@code /setspawn}. */
    public static final String PERM_SETSPAWN = "mutool.commands.setspawn";

    private static final String CONFIG_RESOURCE = "module/spawn/config.yml";
    private static final String LANG_RESOURCE = "module/spawn/lang.yml";
    private static final String SPAWN_RESOURCE = "module/spawn/spawn.yml";

    private static final String DEFAULT_SPAWN = "spawn";
    private static final String DEFAULT_SETSPAWN = "setspawn";

    private Mutool plugin;
    private final SpawnLang lang = new SpawnLang();
    private final SpawnStore store = new SpawnStore();

    private Configuration config;
    private boolean enabled;
    private final List<PluginCommand> commands = new ArrayList<>();

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public SpawnModule() {
    }

    @Override
    public String id() {
        return "spawn";
    }

    /** Release default resources, load config/lang/spawn-data, then register the commands. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);
        File spawnFile = IOKt.releaseResourceFile(SPAWN_RESOURCE, false, SPAWN_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);
        this.store.load(spawnFile);

        this.applyState();
    }

    /** Reload config + lang + spawn data from disk and re-apply the enable state / (re)register the commands. */
    @Override
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
        this.lang.reload();
        this.store.reload();
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
            IOKt.info("[Mutool/Spawn] 出生点模块已关闭 (enable: false)");
        }
    }

    /**
     * Resolve the two command labels from the config {@code commands} list. The list is
     * positional: {@code [spawn, setspawn]}. Any missing / blank entry falls back to the
     * bundled default so the module always registers something usable.
     */
    private List<String> resolveNames() {
        List<String> configured = this.config == null ? null : this.config.getStringList("commands");
        List<String> defaults = List.of(DEFAULT_SPAWN, DEFAULT_SETSPAWN);
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
        this.register(names.get(0), PERM_SPAWN, Sub.SPAWN);
        this.register(names.get(1), PERM_SETSPAWN, Sub.SETSPAWN);
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

            SpawnExecutor executor = new SpawnExecutor(this, sub);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.commands.add(cmd);

            IOKt.info("[Mutool/Spawn] 已注册命令: /" + name + " (权限: " + permission + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Spawn] 注册命令 /" + name + " 失败: " + throwable);
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

    // --- sub-command handlers (invoked from the executor) ---

    /** {@code /spawn} — teleport the player to the spawn point of their current world. */
    void handleSpawn(Player player, String label, String[] args) {
        if (args.length != 0) {
            player.sendMessage(this.lang.usageError(label, Sub.SPAWN.usageKey));
            return;
        }

        String worldName = player.getWorld().getName();
        SpawnStore.SpawnPoint spawn = this.store.get(worldName);
        if (spawn == null) {
            player.sendMessage(this.lang.spawnNoSpawn(worldName));
            return;
        }
        Location target = spawn.toLocation();
        if (target == null) {
            player.sendMessage(this.lang.spawnWorldMissing(worldName));
            return;
        }
        // Folia 安全：投递到玩家区域线程后再执行传送
        FoliaExecutorKt.runTask(player, () -> {
            player.teleportAsync(target);
            player.sendMessage(this.lang.spawnMessage());
        });
    }

    /** {@code /setspawn} — record the player's current world &amp; position as that world's spawn. */
    void handleSetSpawn(Player player, String label, String[] args) {
        if (args.length != 0) {
            player.sendMessage(this.lang.usageError(label, Sub.SETSPAWN.usageKey));
            return;
        }
        this.store.set(player.getWorld().getName(), player.getLocation());
        player.sendMessage(this.lang.setSpawnMessage());
    }

    /** Which sub-command a given {@link PluginCommand} represents. */
    enum Sub {
        SPAWN("commands.spawn.usage", "commands.spawn.description"),
        SETSPAWN("commands.setspawn.usage", "commands.setspawn.description");

        private final String usageKey;
        private final String descriptionKey;

        Sub(String usageKey, String descriptionKey) {
            this.usageKey = usageKey;
            this.descriptionKey = descriptionKey;
        }
    }

    /** Executor shared by both commands; dispatches on the bound {@link Sub}. */
    private static final class SpawnExecutor implements TabExecutor {

        private final SpawnModule module;
        private final Sub sub;

        private SpawnExecutor(SpawnModule module, Sub sub) {
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
            switch (this.sub) {
                case SPAWN -> this.module.handleSpawn(player, label, args);
                case SETSPAWN -> this.module.handleSetSpawn(player, label, args);
            }
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            return new ArrayList<>();
        }
    }
}
