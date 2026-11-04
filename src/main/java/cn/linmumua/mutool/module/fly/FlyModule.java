package cn.linmumua.mutool.module.fly;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.database.Dao;
import cn.linmumua.mutool.database.DatabaseManager;
import cn.linmumua.mutool.database.entity.FlyStatus;
import cn.linmumua.mutool.module.MutoolModule;
import kotlin.Unit;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.ExecutorKt;
import taboolib.common.platform.function.IOKt;
import taboolib.common.platform.service.PlatformExecutor;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;
import taboolib.platform.util.FoliaExecutorKt;

import java.io.File;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Fly module — toggle flight for players, plus timed ("limited") flight.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_fly}. It owns the
 * module switch, loads {@code module/fly/config.yml} &amp; {@code module/fly/lang.yml}
 * through TabooLib's {@link Configuration}, and registers the {@code /fly} and
 * {@code /flytime} commands directly through Bukkit's {@link CommandMap} (no plugin.yml
 * command entries).</p>
 *
 * <p>Flight state is persisted in {@code mumutools_fly} via ORMLite
 * ({@link FlyStatus}); the module gets its DAO from
 * {@link Mutool#getDatabaseManager()}. A repeating async task (every 20 ticks) checks
 * online players' remaining flight time, plays the {@code fall_messages} title countdown
 * (3 / 2 / 1 / 0) and disables flight when it reaches zero. All Bukkit-API work on a
 * player is dispatched onto that player's own region thread via
 * {@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)} so the module is
 * Folia safe.</p>
 *
 * <p>Commands:</p>
 * <ul>
 *   <li>{@code /fly [player]} — toggle flight.</li>
 *   <li>{@code /flytime <set|add|take> <player> <seconds>} — manage limited flight time.</li>
 * </ul>
 */
public final class FlyModule implements MutoolModule {

    /** Permission node required to run {@code /fly}. */
    public static final String PERMISSION = "mutool.commands.fly";
    /** Permission node required to run {@code /flytime}. */
    public static final String PERMISSION_FLYTIME = "mutool.commands.flytime";
    /** Permission node required to toggle another player's flight with {@code /fly <player>}. */
    public static final String PERMISSION_OTHER = "mutool.commands.fly.other";
    /** Permission node that grants unlimited flight and bypasses the fall countdown. */
    public static final String PERMISSION_INFINITE = "mutool.commands.fly.infinite";

    private static final String CONFIG_RESOURCE = "module/fly/config.yml";
    private static final String LANG_RESOURCE = "module/fly/lang.yml";

    private Mutool plugin;
    private final FlyLang lang = new FlyLang();

    private Configuration config;
    private boolean enabled;

    private PluginCommand flyCommand;
    private PluginCommand flyTimeCommand;

    /** Typed DAO for the {@code mumutools_fly} table; {@code null} if the DB is unavailable. */
    private Dao<FlyStatus, UUID> flyDao;

    /** Handle of the repeating 20-tick async check task; cancelled on unload / reload. */
    private PlatformExecutor.PlatformTask tickTask;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public FlyModule() {
    }

    @Override
    public String id() {
        return "fly";
    }

    /** Release default resources, load config/lang, obtain the DAO, then register commands / start the task. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);

        this.bindDao(plugin);
        this.applyState();
    }

    /** Reload config + lang from disk and re-apply the enable state / (re)register commands / restart the task. */
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

    /** Unregister commands and cancel the repeating task. Called on plugin disable. */
    @Override
    public void unload() {
        this.unregisterCommands();
        this.cancelTask();
        this.enabled = false;
    }

    // --- setup ---

    private void bindDao(Mutool plugin) {
        DatabaseManager database = plugin.getDatabaseManager();
        if (database == null) {
            IOKt.severe("[Mutool/Fly] 数据库未初始化，限时飞行数据将不可用");
            return;
        }
        try {
            this.flyDao = database.dao(FlyStatus.class);
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Fly] 获取飞行数据 DAO 失败: " + throwable);
        }
    }

    private void applyState() {
        this.unregisterCommands();
        this.cancelTask();

        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);
        if (nowEnabled) {
            this.registerCommands();
            this.startTask();
            this.enabled = nowEnabled && (this.flyCommand != null || this.flyTimeCommand != null);
        } else {
            this.enabled = false;
            IOKt.info("[Mutool/Fly] 飞行模块已关闭 (enable: false)");
        }
    }

    private void registerCommands() {
        List<String> flyAliases = this.aliases("commands", "fly");
        List<String> timeAliases = this.aliases("time_commands", "flytime");

        this.flyCommand = this.register(flyAliases, PERMISSION,
                this.lang.plainFlyDescription(), new FlyExecutor(this), "fly");
        this.flyTimeCommand = this.register(timeAliases, PERMISSION_FLYTIME,
                this.lang.plainFlyTimeDescription(), new FlyTimeExecutor(this), "flytime");
    }

    private List<String> aliases(String key, String fallback) {
        List<String> result = new ArrayList<>();
        List<String> raw = this.config == null ? null : this.config.getStringList(key);
        if (raw != null) {
            for (String value : raw) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (result.isEmpty()) {
            result.add(fallback);
        }
        return result;
    }

    // permissionMessage(Component) is deprecated in the current Paper API but still the
    // only way to render a themed MiniMessage permission denied message per command.
    @SuppressWarnings("deprecation")
    private PluginCommand register(List<String> aliases, String permission, String description,
                                   TabExecutor executor, String label) {
        try {
            Constructor<PluginCommand> constructor =
                    PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);

            PluginCommand cmd = constructor.newInstance(aliases.get(0), this.plugin);
            if (aliases.size() > 1) {
                cmd.setAliases(aliases.subList(1, aliases.size()));
            }
            cmd.setPermission(permission);
            cmd.permissionMessage(this.lang.noPermission());
            cmd.setDescription(description);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);

            IOKt.info("[Mutool/Fly] 已注册命令: /" + aliases.get(0) + " (权限: " + permission + ")");
            return cmd;
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Fly] 注册 /" + label + " 命令失败: " + throwable);
            return null;
        }
    }

    private void unregisterCommands() {
        this.unregister(this.flyTimeCommand);
        this.flyTimeCommand = null;
        this.unregister(this.flyCommand);
        this.flyCommand = null;
    }

    private void unregister(PluginCommand command) {
        if (command == null) {
            return;
        }
        try {
            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            command.unregister(commandMap);
        } catch (Throwable ignored) {
        }
    }

    // --- repeating task ---

    /**
     * Start the 20-tick repeating check.
     *
     * <p>{@code submitAsync(false, 0L, 20L, task -> ...)} runs on an async thread (the
     * first argument {@code false} maps to "now"=false and the helper forces
     * {@code async=true}), once every 20 ticks with no initial delay. Because touching a
     * player's flight state must happen on its region thread, each player is handed to
     * {@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)}.</p>
     */
    private void startTask() {
        this.tickTask = ExecutorKt.submitAsync(false, 0L, 20L, task -> {
            try {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player == null) {
                        continue;
                    }
                    try {
                        FoliaExecutorKt.runTask(player, () -> this.tick(player));
                    } catch (Throwable ignored) {
                        // The player may have disconnected between iteration and dispatch.
                    }
                }
            } catch (Throwable throwable) {
                IOKt.severe("[Mutool/Fly] 定时检查异常: " + throwable);
            }
            return Unit.INSTANCE;
        });
    }

    private void cancelTask() {
        if (this.tickTask != null) {
            try {
                this.tickTask.cancel();
            } catch (Throwable ignored) {
            }
            this.tickTask = null;
        }
    }

    /** Per-player flight-time check; always invoked on the player's own region thread. */
    private void tick(Player player) {
        try {
            if (!player.isOnline() || !player.getAllowFlight()) {
                return;
            }
            GameMode mode = player.getGameMode();
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
                return;
            }

            FlyStatus status = this.status(player.getUniqueId());
            if (status == null || !status.isEnable()) {
                return;
            }
            if (player.hasPermission(PERMISSION_INFINITE)) {
                return;
            }

            long flyTime = status.getTime();
            if (flyTime <= this.fallTime()) {
                this.sendFallMessage(player, flyTime);
                this.playFallSound(player, flyTime);
            }

            if (flyTime <= 0L) {
                status.setEnable(false);
                if (status.getTime() < 0L) {
                    status.setTime(0L);
                }
                this.save(status);
                player.setAllowFlight(false);
                player.setFlying(false);
                return;
            }

            status.setTime(flyTime - 1L);
            this.save(status);
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Fly] 处理玩家 " + player.getName() + " 的限时飞行失败: " + throwable);
        }
    }

    private void sendFallMessage(Player player, long flyTime) {
        FlyLang.FallMessage message = this.lang.fallMessage(flyTime);
        if (message == null) {
            return;
        }
        player.showTitle(Title.title(
                message.title(),
                message.subtitle(),
                Title.Times.times(
                        Duration.ofMillis(message.in() * 50L),
                        Duration.ofMillis(message.step() * 50L),
                        Duration.ofMillis(message.out() * 50L)
                )
        ));
    }

    private void playFallSound(Player player, long flyTime) {
        String base = "fall_sounds." + flyTime;
        if (this.config == null || !this.config.contains(base + ".sound")) {
            return;
        }
        String sound = this.config.getString(base + ".sound", "");
        if (sound == null || sound.isBlank()) {
            return;
        }
        float volume = (float) this.config.getDouble(base + ".volume", 1.0D);
        float pitch = (float) this.config.getDouble(base + ".pitch", 1.0D);
        player.playSound(Sound.sound(Key.key(sound), Sound.Source.MASTER, volume, pitch));
    }

    private int fallTime() {
        return this.config == null ? 5 : this.config.getInt("fall_time", 5);
    }

    // --- /fly logic (invoked on the target's region thread) ---

    /**
     * Toggle a player's flight.
     *
     * @param operatingOnOther {@code true} when the command targeted another player
     *                         (source's {@code sendToSender}); {@code false} for a self toggle
     */
    void toggleFly(CommandSender sender, Player target, boolean operatingOnOther) {
        FlyStatus status = this.status(target.getUniqueId());
        if (status == null) {
            sender.sendMessage(this.lang.error());
            return;
        }

        if (!operatingOnOther
                && !sender.hasPermission(PERMISSION_INFINITE)
                && status.getTime() <= 0L) {
            sender.sendMessage(this.lang.noPermission());
            return;
        }

        // Use the live flight state (guarded by the persisted enable flag) so a player who
        // already has flight is toggled off, and one who does not is toggled on — this also
        // avoids the "toggle off after a restart resets allowFlight" pitfall.
        boolean currentlyOn = status.isEnable() && target.getAllowFlight();
        if (currentlyOn) {
            if (operatingOnOther) {
                status.setTime(0L);
            }
            status.setEnable(false);
            this.save(status);
            this.applyFlight(target, false);
            this.sendChange(sender, target, false);
            if (operatingOnOther && !sender.equals(target)) {
                this.sendChange(target, target, false);
            }
        } else {
            if (operatingOnOther) {
                status.setTime(Long.MAX_VALUE);
            }
            status.setEnable(true);
            this.save(status);
            this.applyFlight(target, true);
            this.sendChange(sender, target, true);
            if (operatingOnOther && !sender.equals(target)) {
                this.sendChange(target, target, true);
            }
        }
    }

    private void applyFlight(Player player, boolean allow) {
        player.setAllowFlight(allow);
        if (!allow) {
            player.setFlying(false);
        }
    }

    private void sendChange(CommandSender sender, Player target, boolean enable) {
        sender.sendMessage(this.lang.flyMessage(target.getName(), enable));
    }

    // --- /flytime logic (invoked on the target's region thread) ---

    void applyFlyTime(CommandSender sender, Player target, String mode, long input) {
        FlyStatus status = this.status(target.getUniqueId());
        if (status == null) {
            sender.sendMessage(this.lang.error());
            return;
        }

        switch (mode) {
            case "set" -> status.setTime(Math.max(0L, input));
            case "add" -> status.setTime(this.saturatingAdd(status.getTime(), input));
            case "take" -> status.setTime(Math.max(0L, status.getTime() - Math.max(0L, input)));
            default -> {
                return;
            }
        }
        this.save(status);

        long amount = status.getTime();
        String amountText = amount == Long.MAX_VALUE ? "无限" : String.valueOf(amount);
        sender.sendMessage(this.lang.flyTimeMessage(mode, target.getName(), String.valueOf(input), amountText));
    }

    private long saturatingAdd(long base, long delta) {
        long start = Math.max(0L, base);
        if (delta > 0L && start > Long.MAX_VALUE - delta) {
            return Long.MAX_VALUE;
        }
        long result = start + delta;
        return Math.max(0L, result);
    }

    // --- DAO helpers ---

    private FlyStatus status(UUID player) {
        if (this.flyDao == null) {
            return null;
        }
        FlyStatus value = this.flyDao.getById(player);
        if (value == null) {
            value = new FlyStatus(player, false, -1L);
        }
        return value;
    }

    private void save(FlyStatus status) {
        if (this.flyDao != null) {
            this.flyDao.createOrUpdate(status);
        }
    }

    // --- command executors ---

    /** Executor for {@code /fly [player]}. */
    private static final class FlyExecutor implements TabExecutor {

        private final FlyModule module;

        private FlyExecutor(FlyModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (args.length == 0) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(this.module.lang.flyUsageError(label));
                    return true;
                }
                FoliaExecutorKt.runTask(player, () -> this.module.toggleFly(sender, player, false));
                return true;
            }

            if (args.length == 1) {
                if (!sender.hasPermission(PERMISSION_OTHER)) {
                    sender.sendMessage(this.module.lang.noPermission());
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    sender.sendMessage(this.module.lang.playerOffline());
                    return true;
                }
                FoliaExecutorKt.runTask(target, () -> this.module.toggleFly(sender, target, true));
                return true;
            }

            sender.sendMessage(this.module.lang.flyUsageError(label));
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            List<String> names = new ArrayList<>();
            if (args.length == 1 && sender.hasPermission(PERMISSION_OTHER)) {
                String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        names.add(online.getName());
                    }
                }
            }
            return names;
        }
    }

    /** Executor for {@code /flytime <set|add|take> <player> <seconds>}. */
    private static final class FlyTimeExecutor implements TabExecutor {

        private final FlyModule module;

        private FlyTimeExecutor(FlyModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (args.length == 3) {
                String mode = args[0].toLowerCase(Locale.ROOT);
                if (mode.equals("set") || mode.equals("add") || mode.equals("take")) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(this.module.lang.playerNotFound());
                        return true;
                    }

                    long input;
                    try {
                        input = Long.parseLong(args[2]);
                    } catch (NumberFormatException exception) {
                        sender.sendMessage(this.module.lang.timeFormatError());
                        return true;
                    }

                    final long amount = input;
                    FoliaExecutorKt.runTask(target, () -> this.module.applyFlyTime(sender, target, mode, amount));
                    return true;
                }
            }

            sender.sendMessage(this.module.lang.flyTimeHelp(label));
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (args.length == 1) {
                List<String> modes = new ArrayList<>();
                modes.add("help");
                modes.add("set");
                modes.add("add");
                modes.add("take");
                return modes;
            }
            if (args.length == 2) {
                String mode = args[0].toLowerCase(Locale.ROOT);
                if (mode.equals("set") || mode.equals("add") || mode.equals("take")) {
                    List<String> names = new ArrayList<>();
                    String prefix = args[1] == null ? "" : args[1].toLowerCase(Locale.ROOT);
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                            names.add(online.getName());
                        }
                    }
                    return names;
                }
            }
            return new ArrayList<>();
        }
    }
}
