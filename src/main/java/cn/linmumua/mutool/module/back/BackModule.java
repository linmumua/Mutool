package cn.linmumua.mutool.module.back;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.database.Dao;
import cn.linmumua.mutool.database.entity.BackData;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;
import taboolib.platform.util.FoliaExecutorKt;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Back module — remembers a player's recent locations so {@code /back} can return to them.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_back}. It records two
 * kinds of positions into the {@code mumutools_back} table:</p>
 * <ul>
 *   <li>{@code teleport} — the position <em>before</em> a teleport ({@link org.bukkit.event.player.PlayerTeleportEvent#getFrom()});</li>
 *   <li>{@code death} — the position of death ({@link org.bukkit.event.entity.PlayerDeathEvent}).</li>
 * </ul>
 *
 * <p>The module owns its {@code module/back/config.yml}, {@code module/back/lang.yml} and
 * {@code module/back/menu/back.yml} resources, registers the {@code /back} command through
 * Bukkit's {@link CommandMap} (no plugin.yml command entries) and wires the two listeners
 * itself. Stored records are capped at {@code max} per player (oldest pruned first).</p>
 *
 * <p><b>Folia:</b> teleporting is always scheduled onto the player's own region thread via
 * {@link FoliaExecutorKt#runTask(Player, Runnable)} before calling
 * {@link Player#teleportAsync(Location)}.</p>
 */
public final class BackModule implements MutoolModule {

    /** Permission node required to run {@code /back}. */
    public static final String PERMISSION = "mutool.commands.back";

    /** Record type id for "position before a teleport". */
    public static final String TYPE_TELEPORT = "teleport";
    /** Record type id for "position of death". */
    public static final String TYPE_DEATH = "death";

    /** Mutool is single-server only, so this placeholder is written into every record's server column. */
    private static final String CURRENT_SERVER = "current";

    private static final String CONFIG_RESOURCE = "module/back/config.yml";
    private static final String LANG_RESOURCE = "module/back/lang.yml";
    private static final String MENU_RESOURCE = "module/back/menu/back.yml";

    private Mutool plugin;
    private final BackLang lang = new BackLang();

    private Configuration config;
    private Configuration menuConfig;
    private boolean enabled;
    private PluginCommand command;
    private BackListener listener;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public BackModule() {
    }

    @Override
    public String id() {
        return "back";
    }

    /** Release default resources, load config/lang/menu, then register command + listeners. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);
        File menuFile = IOKt.releaseResourceFile(MENU_RESOURCE, false, MENU_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.menuConfig = Configuration.Companion.loadFromFile(menuFile, Type.YAML, false);
        this.lang.load(langFile);

        this.applyState();
    }

    /** Reload config/lang/menu from disk and re-apply the enable state / (re)register everything. */
    @Override
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
        if (this.menuConfig != null) {
            this.menuConfig.reload();
        }
        this.lang.reload();
        this.applyState();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    /** Unregister the command and listeners, drop state. Called on plugin disable. */
    @Override
    public void unload() {
        this.unregisterCommand();
        this.unregisterListener();
        this.enabled = false;
    }

    // --- internals ---

    private void applyState() {
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterCommand();
        this.unregisterListener();

        if (nowEnabled) {
            this.ensurePermission();
            this.registerCommand();
            this.registerListener();
        }
        this.enabled = nowEnabled && this.command != null;

        if (!nowEnabled) {
            IOKt.info("[Mutool/Back] 返回模块已关闭 (enable: false)");
        }
    }

    /**
     * Register the {@code mutool.commands.back} permission programmatically. The module is not
     * declared in the (immutable) {@code paper-plugin.yml}, so without this the permission node
     * would be unknown and ordinary players would be denied. Idempotent.
     */
    private void ensurePermission() {
        try {
            PluginManager pluginManager = this.plugin.getServer().getPluginManager();
            if (pluginManager.getPermission(PERMISSION) == null) {
                pluginManager.addPermission(new Permission(
                        PERMISSION, "允许使用 /back 返回位置记录", PermissionDefault.TRUE));
            }
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Back] 注册权限节点失败: " + throwable);
        }
    }

    private List<String> resolveNames() {
        List<String> commands = this.config == null ? null : this.config.getStringList("commands");
        List<String> names = new ArrayList<>();
        if (commands != null) {
            for (String value : commands) {
                if (value != null && !value.isBlank()) {
                    names.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (names.isEmpty()) {
            names.add("back");
        }
        return names;
    }

    // permissionMessage(Component) is deprecated in the current Paper API but still the
    // only way to render a themed MiniMessage permission denied message per command.
    @SuppressWarnings("deprecation")
    private void registerCommand() {
        try {
            Constructor<PluginCommand> constructor =
                    PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);

            List<String> names = this.resolveNames();
            PluginCommand cmd = constructor.newInstance(names.get(0), this.plugin);
            if (names.size() > 1) {
                cmd.setAliases(names.subList(1, names.size()));
            }
            cmd.setPermission(PERMISSION);
            cmd.permissionMessage(this.lang.noPermission());
            cmd.setDescription(this.lang.plainDescription());

            BackCommand executor = new BackCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Back] 已注册命令: /" + names.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Back] 注册 /back 命令失败: " + throwable);
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

    private void registerListener() {
        this.listener = new BackListener(this);
        Bukkit.getPluginManager().registerEvents(this.listener, this.plugin);
    }

    private void unregisterListener() {
        if (this.listener == null) {
            return;
        }
        try {
            HandlerList.unregisterAll(this.listener);
        } catch (Throwable ignored) {
        }
        this.listener = null;
    }

    // --- config accessors used by the listener / menu ---

    /** Whether pre-teleport positions should be recorded ({@code save.teleport}, default true). */
    boolean saveTeleport() {
        return this.config != null && this.config.getBoolean("save.teleport", true);
    }

    /** Whether death positions should be recorded ({@code save.death}, default true). */
    boolean saveDeath() {
        return this.config != null && this.config.getBoolean("save.death", true);
    }

    /** Whether to nudge the player with a hint after death ({@code respawn_message}, default true). */
    boolean respawnMessage() {
        return this.config != null && this.config.getBoolean("respawn_message", true);
    }

    /** Per-player stored-record cap ({@code max}, default 10). {@code <= 0} disables pruning. */
    int maxRecords() {
        return this.config == null ? 10 : this.config.getInt("max", 10);
    }

    BackLang lang() {
        return this.lang;
    }

    Configuration menuConfig() {
        return this.menuConfig;
    }

    // --- data access ---

    /** Typed DAO for the back table. Throws if the data layer is unavailable. */
    private Dao<BackData, Integer> dao() {
        if (this.plugin.getDatabaseManager() == null) {
            throw new IllegalStateException("数据库未连接");
        }
        return this.plugin.getDatabaseManager().dao(BackData.class);
    }

    /**
     * Record the position {@code location} for {@code player} under {@code type}, then prune
     * older records so at most {@link #maxRecords()} remain for this player.
     */
    void addRecord(@NotNull Player player, @NotNull String type, @NotNull Location location) {
        if (location.getWorld() == null) {
            return;
        }
        BackData data = new BackData(player.getUniqueId(), type);
        data.setTime(System.currentTimeMillis());
        data.setServer(CURRENT_SERVER);
        data.setWorld(location.getWorld().getName());
        data.setX(location.getX());
        data.setY(location.getY());
        data.setZ(location.getZ());
        data.setYaw(location.getYaw());
        data.setPitch(location.getPitch());
        this.dao().createOrUpdate(data);
        this.prune(player.getUniqueId());
    }

    /** Delete this player's oldest records so no more than {@code max} remain. */
    private void prune(UUID player) {
        int max = this.maxRecords();
        if (max <= 0) {
            return;
        }
        List<BackData> mine = this.dao().getList().stream()
                .filter(data -> player.equals(data.getPlayer()))
                .sorted(Comparator.comparingLong(BackData::getTime).thenComparingInt(BackData::getId))
                .toList();
        int excess = mine.size() - max;
        if (excess <= 0) {
            return;
        }
        Dao<BackData, Integer> dao = this.dao();
        for (int index = 0; index < excess; index++) {
            try {
                dao.delete(mine.get(index));
            } catch (Throwable throwable) {
                IOKt.severe("[Mutool/Back] 清理旧位置记录失败: " + throwable);
            }
        }
    }

    /** All of a player's records, newest first. */
    List<BackData> records(@NotNull UUID player) {
        return this.dao().getList().stream()
                .filter(data -> player.equals(data.getPlayer()))
                .sorted(Comparator.comparingLong(BackData::getTime).reversed()
                        .thenComparing(Comparator.comparingInt(BackData::getId).reversed()))
                .toList();
    }

    /** A player's records of one {@code type}, newest first. */
    List<BackData> records(@NotNull UUID player, @NotNull String type) {
        return this.dao().getList().stream()
                .filter(data -> player.equals(data.getPlayer()))
                .filter(data -> type.equals(data.getType()))
                .sorted(Comparator.comparingLong(BackData::getTime).reversed()
                        .thenComparing(Comparator.comparingInt(BackData::getId).reversed()))
                .toList();
    }

    /** Teleport {@code player} to the stored {@code data} location, Folia-safely. */
    void teleportTo(@NotNull Player player, @NotNull BackData data) {
        World world = Bukkit.getWorld(data.getWorld());
        if (world == null) {
            player.sendMessage(this.lang.worldMissing(data.getWorld()));
            return;
        }
        float yaw = data.getYaw() == null ? 0.0f : data.getYaw();
        float pitch = data.getPitch() == null ? 0.0f : data.getPitch();
        Location target = new Location(world, data.getX(), data.getY(), data.getZ(), yaw, pitch);
        // Folia 安全：投递到玩家区域线程后再执行传送
        FoliaExecutorKt.runTask(player, () -> {
            player.teleportAsync(target);
            player.sendMessage(this.lang.message());
        });
    }

    /** Open the location-record menu for {@code player}. */
    void openMenu(@NotNull Player player) {
        new BackMenu(this, player).open(player);
        player.sendMessage(this.lang.openMenuMessage());
    }
}
