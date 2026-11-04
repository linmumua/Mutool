package cn.linmumua.mutool.module.home;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.database.Dao;
import cn.linmumua.mutool.database.entity.HomeData;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Home module — per-player named home points backed by the {@code mumutools_home} table.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_home}. It owns the module
 * switch, loads {@code module/home/config.yml}, {@code module/home/lang.yml} and
 * {@code module/home/menu/home.yml} through TabooLib's {@link Configuration}, and registers the
 * three sub-commands ({@code /home}, {@code /sethome}, {@code /delhome}) directly through
 * Bukkit's {@link CommandMap} (no plugin.yml command entries), each guarded by its own
 * permission node.</p>
 *
 * <p>{@code /home} with no arguments opens the paginated home list built on the shared
 * {@link cn.linmumua.mutool.menu.Menu} framework; {@code /home <name>} teleports directly.</p>
 *
 * <p><b>No cross-server support.</b> MumuTools carried homes between servers via BungeeCord;
 * Mutool is single-server only, so the {@code server} column is always stored as
 * {@code "current"} and the location is rebuilt from the stored world name via
 * {@link Bukkit#getWorld(String)}. Teleportation is Folia-safe: the actual teleport is
 * scheduled onto the player's own region thread via
 * {@link FoliaExecutorKt#runTask(Player, Runnable)} before calling
 * {@link Player#teleportAsync(Location)}.</p>
 */
public final class HomeModule implements MutoolModule {

    /** Permission node required to run {@code /home}. */
    public static final String PERM_HOME = "mutool.commands.home";
    /** Permission node required to run {@code /sethome}. */
    public static final String PERM_SETHOME = "mutool.commands.sethome";
    /** Permission node required to run {@code /delhome}. */
    public static final String PERM_DELHOME = "mutool.commands.delhome";

    /** Placeholder written into {@link HomeData#getServer()} — Mutool has no cross-server. */
    private static final String CURRENT_SERVER = "current";

    private static final String CONFIG_RESOURCE = "module/home/config.yml";
    private static final String LANG_RESOURCE = "module/home/lang.yml";
    private static final String MENU_RESOURCE = "module/home/menu/home.yml";

    private static final String DEFAULT_HOME = "home";
    private static final String DEFAULT_SETHOME = "sethome";
    private static final String DEFAULT_DELHOME = "delhome";

    private Mutool plugin;
    private final HomeLang lang = new HomeLang();
    private final HomeMenuConfig menuSettings = new HomeMenuConfig();

    private Configuration config;
    private boolean enabled;
    private int maxHomes = 5;
    private String nameRegex;
    private List<String> blackWorld = new ArrayList<>();
    private final List<PluginCommand> commands = new ArrayList<>();

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public HomeModule() {
    }

    @Override
    public String id() {
        return "home";
    }

    /** Release default resources, load config/lang/menu, then register the commands. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);
        File menuFile = IOKt.releaseResourceFile(MENU_RESOURCE, false, MENU_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);
        this.menuSettings.load(menuFile);

        this.applyState();
    }

    /** Reload config + lang + menu from disk and re-apply the enable state / (re)register commands. */
    @Override
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
        this.lang.reload();
        this.menuSettings.reload();
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
        this.refreshConfigValues();

        this.unregisterCommands();
        if (nowEnabled) {
            this.registerCommands();
        }
        this.enabled = nowEnabled && !this.commands.isEmpty();

        if (!nowEnabled) {
            IOKt.info("[Mutool/Home] 家模块已关闭 (enable: false)");
        }
    }

    private void refreshConfigValues() {
        this.maxHomes = Math.max(1, this.config == null ? 5 : this.config.getInt("max", 5));
        this.nameRegex = this.config == null ? null : this.config.getString("regex", null);
        List<String> configured = this.config == null ? null : this.config.getStringList("black_world");
        this.blackWorld = configured == null ? new ArrayList<>() : new ArrayList<>(configured);
    }

    /**
     * Resolve the three command labels from the config {@code commands} list. The list is
     * positional: {@code [home, sethome, delhome]}. Any missing / blank entry falls back to
     * the bundled default so the module always registers something usable.
     */
    private List<String> resolveNames() {
        List<String> configured = this.config == null ? null : this.config.getStringList("commands");
        List<String> defaults = List.of(DEFAULT_HOME, DEFAULT_SETHOME, DEFAULT_DELHOME);
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
        this.register(names.get(0), PERM_HOME, Sub.HOME);
        this.register(names.get(1), PERM_SETHOME, Sub.SETHOME);
        this.register(names.get(2), PERM_DELHOME, Sub.DELHOME);
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

            HomeExecutor executor = new HomeExecutor(this, sub);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.commands.add(cmd);

            IOKt.info("[Mutool/Home] 已注册命令: /" + name + " (权限: " + permission + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Home] 注册命令 /" + name + " 失败: " + throwable);
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

    /** Typed DAO for the home table. Throws if the data layer is unavailable. */
    private Dao<HomeData, Integer> dao() {
        if (this.plugin.getDatabaseManager() == null) {
            throw new IllegalStateException("数据库未连接");
        }
        return this.plugin.getDatabaseManager().dao(HomeData.class);
    }

    /** The parsed menu configuration, shared read-only with {@link HomeMenu}. */
    HomeMenuConfig menuConfig() {
        return this.menuSettings;
    }

    /** All of a player's homes, sorted by home name for stable pagination. Never throws. */
    List<HomeData> homesOf(UUID uuid) {
        try {
            return this.dao().getList().stream()
                    .filter(home -> uuid.equals(home.getPlayer()))
                    .sorted(Comparator.comparing(HomeData::getHome, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (Throwable throwable) {
            return new ArrayList<>();
        }
    }

    private HomeData findHome(UUID uuid, String name) {
        return this.dao().getList().stream()
                .filter(home -> uuid.equals(home.getPlayer())
                        && home.getHome() != null
                        && home.getHome().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private int countHomes(UUID uuid) {
        try {
            return (int) this.dao().getList().stream()
                    .filter(home -> uuid.equals(home.getPlayer()))
                    .count();
        } catch (Throwable throwable) {
            return 0;
        }
    }

    /** Names of the player's homes — used for tab completion. Never throws. */
    private List<String> homeNames(Player player) {
        return this.homesOf(player.getUniqueId()).stream()
                .map(HomeData::getHome)
                .filter(name -> name != null)
                .toList();
    }

    private boolean isValidName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String regex = this.nameRegex;
        if (regex == null || regex.isBlank()) {
            return true;
        }
        try {
            return name.matches(regex);
        } catch (Throwable throwable) {
            return true;
        }
    }

    private boolean isBlackWorld(String world) {
        return this.blackWorld != null && this.blackWorld.contains(world);
    }

    // --- menu + teleport (shared with HomeMenu) ---

    /** Build and open the home list menu at {@code page} (clamped to {@code >= 1}). */
    void openMenu(Player player, int page) {
        new HomeMenu(this, player, Math.max(1, page)).open(player);
    }

    /** Folia-safe teleport to a stored home, notifying the player on completion. */
    void teleport(Player player, HomeData data) {
        World world = Bukkit.getWorld(data.getWorld());
        if (world == null) {
            player.sendMessage(this.lang.homeWorldMissing(data.getWorld()));
            return;
        }
        Location target = new Location(world,
                data.getX(), data.getY(), data.getZ(), data.getYaw(), data.getPitch());
        // Folia 安全：投递到玩家区域线程后再执行传送
        FoliaExecutorKt.runTask(player, () -> {
            player.teleportAsync(target);
            player.sendMessage(this.lang.homeMessage(data.getHome()));
        });
    }

    // --- sub-command handlers (invoked from the executor) ---

    void handleHome(Player player, String label, String[] args) {
        if (this.isBlackWorld(player.getWorld().getName())) {
            player.sendMessage(this.lang.blackWorld());
            return;
        }
        if (args.length == 0) {
            this.openMenu(player, 1);
            player.sendMessage(this.lang.homeOpenMenu());
            return;
        }
        if (args.length != 1) {
            player.sendMessage(this.lang.usageError(label, Sub.HOME.usageKey));
            return;
        }

        String name = args[0];
        if (!this.isValidName(name)) {
            player.sendMessage(this.lang.homeInvalidName());
            return;
        }
        HomeData data = this.findHome(player.getUniqueId(), name);
        if (data == null) {
            player.sendMessage(this.lang.homeNoHome(name));
            return;
        }
        this.teleport(player, data);
    }

    void handleSetHome(Player player, String label, String[] args) {
        if (args.length != 1) {
            player.sendMessage(this.lang.usageError(label, Sub.SETHOME.usageKey));
            return;
        }
        if (this.isBlackWorld(player.getWorld().getName())) {
            player.sendMessage(this.lang.blackWorld());
            return;
        }

        String name = args[0];
        if (!this.isValidName(name)) {
            player.sendMessage(this.lang.homeInvalidName());
            return;
        }

        UUID uuid = player.getUniqueId();
        HomeData existing = this.findHome(uuid, name);
        if (existing == null && this.countHomes(uuid) >= this.maxHomes) {
            player.sendMessage(this.lang.setIsMax(this.maxHomes));
            return;
        }

        Location location = player.getLocation();
        HomeData data = existing != null ? existing : new HomeData(uuid, name);
        data.setServer(CURRENT_SERVER);
        data.setWorld(location.getWorld().getName());
        data.setX(location.getX());
        data.setY(location.getY());
        data.setZ(location.getZ());
        data.setYaw(location.getYaw());
        data.setPitch(location.getPitch());
        this.dao().createOrUpdate(data);

        player.sendMessage(this.lang.setHomeMessage(name));
    }

    void handleDelHome(Player player, String label, String[] args) {
        if (args.length != 1) {
            player.sendMessage(this.lang.usageError(label, Sub.DELHOME.usageKey));
            return;
        }
        if (this.isBlackWorld(player.getWorld().getName())) {
            player.sendMessage(this.lang.blackWorld());
            return;
        }

        String name = args[0];
        if (!this.isValidName(name)) {
            player.sendMessage(this.lang.homeInvalidName());
            return;
        }

        HomeData data = this.findHome(player.getUniqueId(), name);
        if (data == null) {
            player.sendMessage(this.lang.delHomeNoHome(name));
            return;
        }
        this.dao().delete(data);
        player.sendMessage(this.lang.delHomeMessage(name));
    }

    /** Which sub-command a given {@link PluginCommand} represents. */
    enum Sub {
        HOME("commands.home.usage", "commands.home.description"),
        SETHOME("commands.sethome.usage", "commands.sethome.description"),
        DELHOME("commands.delhome.usage", "commands.delhome.description");

        private final String usageKey;
        private final String descriptionKey;

        Sub(String usageKey, String descriptionKey) {
            this.usageKey = usageKey;
            this.descriptionKey = descriptionKey;
        }
    }

    /** Executor shared by all three commands; dispatches on the bound {@link Sub}. */
    private static final class HomeExecutor implements TabExecutor {

        private final HomeModule module;
        private final Sub sub;

        private HomeExecutor(HomeModule module, Sub sub) {
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
                    case HOME -> this.module.handleHome(player, label, args);
                    case SETHOME -> this.module.handleSetHome(player, label, args);
                    case DELHOME -> this.module.handleDelHome(player, label, args);
                }
            } catch (Throwable throwable) {
                player.sendMessage(this.module.lang.databaseError());
                IOKt.severe("[Mutool/Home] 执行 /" + label + " 失败: " + throwable);
            }
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (args.length == 1 && sender instanceof Player player) {
                return this.module.homeNames(player);
            }
            return new ArrayList<>();
        }
    }
}
