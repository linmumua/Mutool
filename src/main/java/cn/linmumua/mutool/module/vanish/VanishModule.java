package cn.linmumua.mutool.module.vanish;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.database.Dao;
import cn.linmumua.mutool.database.DatabaseManager;
import cn.linmumua.mutool.database.entity.VanishStatus;
import cn.linmumua.mutool.module.MutoolModule;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;
import taboolib.platform.util.FoliaExecutorKt;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Vanish module — hide a player from the other players on the server.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_vanish}. It owns the
 * module switch, loads {@code module/vanish/config.yml} &amp; {@code module/vanish/lang.yml}
 * through TabooLib's {@link Configuration}, registers the {@code /vanish} command directly
 * through Bukkit's {@link CommandMap} (no plugin.yml command entries), and registers
 * {@link VanishJoinListener} to re-apply visibility when a player joins.</p>
 *
 * <p>The vanish flag is persisted in the existing ORMLite table {@code mumutools_vanish}
 * ({@link VanishStatus}) through {@link DatabaseManager}. A vanished player is shown the
 * {@code 隐身中} Adventure {@link BossBar}.</p>
 *
 * <p><b>Folia:</b> {@code hidePlayer}/{@code showPlayer} mutate the <em>viewer's</em>
 * entity-tracking state, so every such call is dispatched onto the viewer's own region
 * thread via {@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)}. The whole
 * toggle (DB read-modify-write, bossbar, feedback) is itself run on the target player's
 * region thread.</p>
 *
 * <p>Commands:</p>
 * <ul>
 *   <li>{@code /vanish} — toggle your own vanish state.</li>
 *   <li>{@code /vanish <player>} — toggle another player's vanish state (requires
 *       {@value #PERMISSION_OTHER}).</li>
 * </ul>
 */
public final class VanishModule implements MutoolModule {

    /** Permission node required to run {@code /vanish}. */
    public static final String PERMISSION = "mutool.commands.vanish";
    /** Permission node required to toggle another player's vanish with {@code /vanish <player>}. */
    public static final String PERMISSION_OTHER = "mutool.commands.vanish.other";

    private static final String CONFIG_RESOURCE = "module/vanish/config.yml";
    private static final String LANG_RESOURCE = "module/vanish/lang.yml";

    private Mutool plugin;
    private final VanishLang lang = new VanishLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;
    private VanishJoinListener listener;

    /** The {@code 隐身中} bossbar shown to vanished players; rebuilt on every enable. */
    private BossBar bossBar;

    /** Typed DAO for the {@code mumutools_vanish} table; {@code null} if the DB is unavailable. */
    private Dao<VanishStatus, UUID> vanishDao;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public VanishModule() {
    }

    @Override
    public String id() {
        return "vanish";
    }

    /** Release default resources, load config/lang, obtain the DAO, then register command + listener. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);

        this.bindDao(plugin);
        this.listener = new VanishJoinListener(this);

        this.applyState();
    }

    /** Reload config + lang from disk and re-apply the enable state / (re)register everything. */
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

    /** Unregister the command + listener and drop state. Called on plugin disable. */
    @Override
    public void unload() {
        this.unregisterCommand();
        this.unregisterListener();
        this.enabled = false;
        this.bossBar = null;
    }

    // --- setup ---

    private void bindDao(Mutool plugin) {
        DatabaseManager database = plugin.getDatabaseManager();
        if (database == null) {
            IOKt.severe("[Mutool/Vanish] 数据库未初始化，隐身状态将不可持久化");
            return;
        }
        try {
            this.vanishDao = database.dao(VanishStatus.class);
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Vanish] 获取隐身数据 DAO 失败: " + throwable);
        }
    }

    private void applyState() {
        this.unregisterCommand();
        this.unregisterListener();

        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);
        if (nowEnabled) {
            this.buildBossBar();
            this.registerCommand(this.resolveAliases());
            this.registerListener();
            this.enabled = this.command != null;
        } else {
            this.enabled = false;
            IOKt.info("[Mutool/Vanish] 隐身模块已关闭 (enable: false)");
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
            aliases.add("vanish");
        }
        return aliases;
    }

    private void buildBossBar() {
        this.bossBar = BossBar.bossBar(
                this.lang.bossBarName(),
                BossBar.MAX_PROGRESS,
                this.readBossBarColor(),
                BossBar.Overlay.PROGRESS
        );
    }

    private BossBar.Color readBossBarColor() {
        String raw = this.config == null ? "WHITE" : this.config.getString("bossbar.vanish.color", "WHITE");
        if (raw == null || raw.isBlank()) {
            raw = "WHITE";
        }
        try {
            return BossBar.Color.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BossBar.Color.WHITE;
        }
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

            VanishExecutor executor = new VanishExecutor(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Vanish] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Vanish] 注册 /vanish 命令失败: " + throwable);
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
        if (this.listener == null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this.listener, this.plugin);
        IOKt.info("[Mutool/Vanish] 已注册进服隐身监听器");
    }

    private void unregisterListener() {
        if (this.listener == null) {
            return;
        }
        HandlerList.unregisterAll(this.listener);
    }

    // --- accessors used by the listener ---

    Mutool plugin() {
        return this.plugin;
    }

    boolean isBossBarEnabled() {
        return this.config != null && this.config.getBoolean("bossbar.vanish.enable", true);
    }

    BossBar bossBar() {
        return this.bossBar;
    }

    /**
     * All currently vanished player ids, read from {@code mumutools_vanish}. Returns an empty
     * set when the data layer is unavailable or the query fails (fail-safe: nobody is treated
     * as vanished rather than crashing the join event).
     */
    Set<UUID> hiddenPlayerIds() {
        Dao<VanishStatus, UUID> dao = this.vanishDao;
        if (dao == null) {
            return Set.of();
        }
        try {
            Set<UUID> ids = new HashSet<>();
            for (VanishStatus status : dao.getList()) {
                if (status.isEnable() && status.getPlayer() != null) {
                    ids.add(status.getPlayer());
                }
            }
            return ids;
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Vanish] 读取隐身玩家列表失败: " + throwable);
            return Set.of();
        }
    }

    // --- /vanish logic (invoked on the target's region thread) ---

    /**
     * Toggle the target's vanish flag and persist it, then apply visibility and feedback.
     *
     * <p>Runs entirely on the target's own region thread. Enabling hides the target from
     * every other online viewer; disabling shows them again. Each {@code hidePlayer}/
     * {@code showPlayer} is dispatched onto the affected viewer's region thread.</p>
     *
     * @param sender           whoever issued the command (may be console)
     * @param target           the player whose vanish is being toggled
     * @param operatingOnOther {@code true} when the command targeted another player; the
     *                         target is then also messaged the result
     */
    void toggle(CommandSender sender, Player target, boolean operatingOnOther) {
        Dao<VanishStatus, UUID> dao = this.vanishDao;
        if (dao == null) {
            sender.sendMessage(this.lang.databaseUnavailable());
            return;
        }

        boolean now;
        try {
            VanishStatus status = dao.getById(target.getUniqueId());
            boolean current = status != null && status.isEnable();
            now = !current;
            if (status == null) {
                status = new VanishStatus(target.getUniqueId(), now);
            } else {
                status.setEnable(now);
            }
            dao.createOrUpdate(status);
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Vanish] 切换玩家隐身状态失败: " + throwable);
            sender.sendMessage(this.lang.databaseUnavailable());
            return;
        }

        this.applyVisibility(target, now);

        if (this.bossBar != null) {
            if (now) {
                if (this.isBossBarEnabled()) {
                    target.showBossBar(this.bossBar);
                }
            } else {
                target.hideBossBar(this.bossBar);
            }
        }

        Component message = this.lang.message(target.getName(), now);
        sender.sendMessage(message);
        if (operatingOnOther && !sender.equals(target)) {
            target.sendMessage(message);
        }
    }

    /**
     * Hide the vanished target from every other online viewer (or show them again). Each call
     * is dispatched onto the affected viewer's region thread, because {@code hidePlayer}/
     * {@code showPlayer} mutate the viewer's client-side entity tracking.
     */
    private void applyVisibility(Player target, boolean vanished) {
        Mutool owner = this.plugin;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) {
                continue;
            }
            if (vanished) {
                FoliaExecutorKt.runTask(viewer, () -> viewer.hidePlayer(owner, target));
            } else {
                FoliaExecutorKt.runTask(viewer, () -> viewer.showPlayer(owner, target));
            }
        }
    }

    // --- command executor ---

    /** Executor for {@code /vanish [player]}. */
    private static final class VanishExecutor implements TabExecutor {

        private final VanishModule module;

        private VanishExecutor(VanishModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            Player target;
            boolean operatingOnOther;

            if (args.length == 0) {
                // 无参：切换自己（仅玩家可用；控制台需显式指定玩家）
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(this.module.lang.usageError(label));
                    return true;
                }
                target = player;
                operatingOnOther = false;
            } else if (args.length == 1) {
                // /vanish <玩家>：切换他人，需要额外权限
                if (!sender.hasPermission(PERMISSION_OTHER)) {
                    sender.sendMessage(this.module.lang.noPermission());
                    return true;
                }
                target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    sender.sendMessage(this.module.lang.playerOffline());
                    return true;
                }
                operatingOnOther = !sender.equals(target);
            } else {
                sender.sendMessage(this.module.lang.usageError(label));
                return true;
            }

            FoliaExecutorKt.runTask(target, () -> this.module.toggle(sender, target, operatingOnOther));
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            List<String> result = new ArrayList<>();
            if (args.length != 1) {
                return result;
            }
            if (!sender.hasPermission(PERMISSION_OTHER)) {
                return result;
            }
            String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(online.getName());
                }
            }
            return result;
        }
    }
}
