package cn.linmumua.mutool.module.pvp;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.database.Dao;
import cn.linmumua.mutool.database.DatabaseManager;
import cn.linmumua.mutool.database.entity.PvpStatus;
import cn.linmumua.mutool.module.MutoolModule;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * PVP module — toggles a player's PVP flag and enforces it in combat.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_pvp}. It owns the
 * module switch, loads {@code module/pvp/config.yml} &amp; {@code module/pvp/lang.yml}
 * through TabooLib's {@link Configuration}, registers the {@code /pvp} command directly
 * through Bukkit's {@link CommandMap} (no plugin.yml command entries), and registers
 * {@link PvpDamageListener} to cancel PvP damage unless both sides opted in.</p>
 *
 * <p>The PVP flag is persisted to the existing ORMLite table {@code mumutools_pvp}
 * ({@link PvpStatus}) through {@link DatabaseManager}. The toggle writes Folia-safely:
 * the read-modify-write and the feedback messages are scheduled onto the target
 * player's own region thread via
 * {@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)}.</p>
 */
public final class PvpModule implements MutoolModule {

    /** Permission node required to toggle your own PVP with {@code /pvp}. */
    public static final String PERMISSION = "mutool.commands.pvp";

    /** Permission node required to toggle another player's PVP with {@code /pvp <player>}. */
    public static final String PERMISSION_OTHER = "mutool.commands.pvp.other";

    /**
     * The PVP state assumed for a player with no stored row — matches the original
     * {@code module_pvp} {@code config.yml} {@code default: true}. It is a constant here
     * because the ported {@code config.yml} only exposes {@code enable}/{@code commands}.
     */
    static final boolean DEFAULT_PVP = true;

    private static final String CONFIG_RESOURCE = "module/pvp/config.yml";
    private static final String LANG_RESOURCE = "module/pvp/lang.yml";

    private Mutool plugin;
    private final PvpLang lang = new PvpLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;
    private PvpDamageListener listener;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public PvpModule() {
    }

    @Override
    public String id() {
        return "pvp";
    }

    /** Release default resources, load config/lang, then register the command + listener. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);

        this.listener = new PvpDamageListener(this);

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
    }

    // --- internals ---

    private void applyState() {
        List<String> aliases = this.resolveAliases();
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterCommand();
        this.unregisterListener();
        if (nowEnabled) {
            this.registerCommand(aliases);
            this.registerListener();
        }
        this.enabled = nowEnabled && this.command != null;

        if (!nowEnabled) {
            IOKt.info("[Mutool/Pvp] PVP模块已关闭 (enable: false)");
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
            aliases.add("pvp");
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

            PvpExecutor executor = new PvpExecutor(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Pvp] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Pvp] 注册 /pvp 命令失败: " + throwable);
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
        IOKt.info("[Mutool/Pvp] 已注册 PVP 伤害拦截监听器");
    }

    private void unregisterListener() {
        if (this.listener == null) {
            return;
        }
        HandlerList.unregisterAll(this.listener);
    }

    /** The plugin's data layer, or {@code null} when it failed to start. */
    DatabaseManager databaseManager() {
        return this.plugin == null ? null : this.plugin.getDatabaseManager();
    }

    /**
     * Flip the target's PVP flag and persist it, then send the result message.
     *
     * <p>Runs the whole read-modify-write on the target's own region thread so it is
     * Folia-safe. A player without a stored row is created on first toggle, starting
     * from {@link #DEFAULT_PVP} so the flip is deterministic.</p>
     *
     * @param sender      whoever issued the command (may be console)
     * @param target      the player whose flag is being toggled
     * @param notifyTarget whether to also message the target (only when different from sender)
     */
    void toggle(CommandSender sender, Player target, boolean notifyTarget) {
        DatabaseManager database = this.databaseManager();
        if (database == null) {
            sender.sendMessage(this.lang.databaseUnavailable());
            return;
        }

        FoliaExecutorKt.runTask(target, () -> {
            try {
                Dao<PvpStatus, UUID> dao = database.dao(PvpStatus.class);
                UUID uuid = target.getUniqueId();

                PvpStatus status = dao.getById(uuid);
                boolean current;
                if (status == null) {
                    status = new PvpStatus(uuid);
                    current = DEFAULT_PVP;
                } else {
                    current = status.isEnable();
                }

                boolean nowEnabled = !current;
                status.setEnable(nowEnabled);
                dao.createOrUpdate(status);

                Component message = this.lang.message(target.getName(), nowEnabled);
                if (notifyTarget) {
                    target.sendMessage(message);
                }
                sender.sendMessage(message);
            } catch (Throwable throwable) {
                IOKt.severe("[Mutool/Pvp] 切换玩家 PVP 状态失败: " + throwable);
                sender.sendMessage(this.lang.databaseUnavailable());
            }
        });
    }

    /** Executor for {@code /pvp [player]}. */
    private static final class PvpExecutor implements TabExecutor {

        private final PvpModule module;

        private PvpExecutor(PvpModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            Player target;
            boolean notifyTarget;

            if (args.length == 0) {
                // 无参：切换自己（仅玩家可用）
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(this.module.lang.onlyPlayer());
                    return true;
                }
                target = player;
                notifyTarget = false;
            } else if (args.length == 1) {
                // /pvp <玩家>：切换他人，需要额外权限
                if (!sender.hasPermission(PERMISSION_OTHER)) {
                    sender.sendMessage(this.module.lang.noPermission());
                    return true;
                }
                target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    sender.sendMessage(this.module.lang.playerOffline());
                    return true;
                }
                notifyTarget = !sender.equals(target);
            } else {
                sender.sendMessage(this.module.lang.usageError(label));
                return true;
            }

            this.module.toggle(sender, target, notifyTarget);
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            List<String> result = new ArrayList<>();
            if (args.length != 1) return result;
            if (!sender.hasPermission(PERMISSION_OTHER)) return result;

            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(online.getName());
                }
            }
            return result;
        }
    }
}
