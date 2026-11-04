package cn.linmumua.mutool.module.ignore;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.database.Dao;
import cn.linmumua.mutool.database.entity.IgnoreData;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Ignore module — lets a player manage a personal blacklist of players they want to ignore.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_ignore}. It owns the
 * module switch, loads {@code module/ignore/config.yml} &amp; {@code module/ignore/lang.yml}
 * through TabooLib's {@link Configuration}, persists the ignore list through the shared
 * ORMLite data layer ({@link IgnoreData} / {@code mumutools_ignore}), and registers the
 * {@code /ignore} command directly through Bukkit's {@link CommandMap} (no plugin.yml
 * command entries).</p>
 *
 * <p>Supported sub-commands: {@code /ignore add <player>}, {@code /ignore remove <player>},
 * {@code /ignore list}, {@code /ignore help}.</p>
 */
public final class IgnoreModule implements MutoolModule {

    /** Permission node required to run {@code /ignore}. */
    public static final String PERMISSION = "mutool.commands.ignore";

    /** Permission node that bypasses the configured "cannot ignore" blacklist. */
    public static final String BYPASS_PERMISSION = "mutool.bypass.ignore.blacklist";

    private static final String CONFIG_RESOURCE = "module/ignore/config.yml";
    private static final String LANG_RESOURCE = "module/ignore/lang.yml";

    private Mutool plugin;
    private final IgnoreLang lang = new IgnoreLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public IgnoreModule() {
    }

    @Override
    public String id() {
        return "ignore";
    }

    /** Release default resources, load config/lang, then register the command. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);

        this.ensurePermissions();
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

    /** Obtain the ignore DAO from the shared data layer, or {@code null} when the DB is unavailable. */
    private Dao<IgnoreData, Integer> dao() {
        if (this.plugin == null || this.plugin.getDatabaseManager() == null) {
            return null;
        }
        try {
            return this.plugin.getDatabaseManager().dao(IgnoreData.class);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private List<String> resolveBlacklist() {
        List<String> blacklist = this.config == null ? null : this.config.getStringList("blacklist");
        return blacklist == null ? new ArrayList<>() : blacklist;
    }

    private void applyState() {
        List<String> aliases = this.resolveAliases();
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterCommand();
        if (nowEnabled) {
            if (this.dao() == null) {
                IOKt.severe("[Mutool/Ignore] 数据层不可用，无法启用屏蔽模块（ignoring enable=true 被跳过）");
            } else {
                this.registerCommand(aliases);
            }
        }
        this.enabled = nowEnabled && this.command != null;

        if (!nowEnabled) {
            IOKt.info("[Mutool/Ignore] 屏蔽模块已关闭 (enable: false)");
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
            aliases.add("ignore");
        }
        return aliases;
    }

    /**
     * Register the module's permission nodes at runtime when they are not already
     * declared (this module may not edit {@code paper-plugin.yml}). Without this the
     * undeclared node would fall back to op-only, which would break the default-true
     * behaviour the legacy {@code /ignore} command had. Idempotent and guarded.
     */
    private void ensurePermissions() {
        try {
            PluginManager manager = this.plugin.getServer().getPluginManager();
            if (manager.getPermission(PERMISSION) == null) {
                manager.addPermission(new Permission(PERMISSION, "允许使用 /ignore 管理屏蔽列表", PermissionDefault.TRUE));
            }
            if (manager.getPermission(BYPASS_PERMISSION) == null) {
                manager.addPermission(new Permission(BYPASS_PERMISSION, "绕过屏蔽禁止屏蔽列表", PermissionDefault.OP));
            }
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Ignore] 注册权限节点失败: " + throwable);
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

            IgnoreExecutor executor = new IgnoreExecutor(this.plugin, this.lang, this.resolveBlacklist());
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Ignore] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Ignore] 注册 /ignore 命令失败: " + throwable);
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

    /** Executor for {@code /ignore}; add / remove / list / help backed by the ignore table. */
    private static final class IgnoreExecutor implements TabExecutor {

        private final Mutool plugin;
        private final IgnoreLang lang;
        private final List<String> blacklist;

        private IgnoreExecutor(Mutool plugin, IgnoreLang lang, List<String> blacklist) {
            this.plugin = plugin;
            this.lang = lang;
            this.blacklist = blacklist == null ? new ArrayList<>() : blacklist;
        }

        private Dao<IgnoreData, Integer> dao() {
            if (this.plugin == null || this.plugin.getDatabaseManager() == null) {
                return null;
            }
            try {
                return this.plugin.getDatabaseManager().dao(IgnoreData.class);
            } catch (Throwable throwable) {
                return null;
            }
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(this.lang.onlyPlayer());
                return true;
            }

            if (args.length == 0) {
                player.sendMessage(this.lang.helpMessage(label));
                return true;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "list" -> handleList(player);
                case "add" -> handleAdd(player, label, args);
                case "remove" -> handleRemove(player, label, args);
                case "help" -> player.sendMessage(this.lang.helpMessage(label));
                default -> player.sendMessage(this.lang.helpMessage(label));
            }
            return true;
        }

        private void handleList(Player player) {
            Dao<IgnoreData, Integer> dao = dao();
            if (dao == null) {
                player.sendMessage(this.lang.databaseUnavailable());
                return;
            }
            UUID me = player.getUniqueId();
            List<String> names = new ArrayList<>();
            for (IgnoreData data : dao.getList()) {
                if (me.equals(data.getPlayer())) {
                    names.add(displayName(data.getIgnore()));
                }
            }
            player.sendMessage(this.lang.listMessage(names.isEmpty() ? "空" : String.join(", ", names)));
        }

        private void handleAdd(Player player, String label, String[] args) {
            if (args.length != 2) {
                player.sendMessage(this.lang.helpMessage(label));
                return;
            }
            String targetName = args[1];

            if (!player.hasPermission(BYPASS_PERMISSION) && containsIgnoreCase(this.blacklist, targetName)) {
                player.sendMessage(this.lang.addBlacklist(targetName));
                return;
            }

            UUID targetUuid = resolveUuid(targetName);
            if (targetUuid == null) {
                player.sendMessage(this.lang.playerNotFound(targetName));
                return;
            }
            if (targetUuid.equals(player.getUniqueId())) {
                player.sendMessage(this.lang.addIgnoreSelf());
                return;
            }

            Dao<IgnoreData, Integer> dao = dao();
            if (dao == null) {
                player.sendMessage(this.lang.databaseUnavailable());
                return;
            }
            if (findRow(dao, player.getUniqueId(), targetUuid) != null) {
                player.sendMessage(this.lang.addHaveIgnore(targetName));
                return;
            }

            dao.createOrUpdate(new IgnoreData(player.getUniqueId(), targetUuid));
            player.sendMessage(this.lang.addMessage(targetName));
        }

        private void handleRemove(Player player, String label, String[] args) {
            if (args.length != 2) {
                player.sendMessage(this.lang.helpMessage(label));
                return;
            }
            String targetName = args[1];

            UUID targetUuid = resolveUuid(targetName);
            if (targetUuid == null) {
                player.sendMessage(this.lang.playerNotFound(targetName));
                return;
            }

            Dao<IgnoreData, Integer> dao = dao();
            if (dao == null) {
                player.sendMessage(this.lang.databaseUnavailable());
                return;
            }
            IgnoreData row = findRow(dao, player.getUniqueId(), targetUuid);
            if (row == null) {
                player.sendMessage(this.lang.removeNoIgnore(targetName));
                return;
            }

            dao.delete(row);
            player.sendMessage(this.lang.removeMessage(targetName));
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                    @NotNull String label, String @NotNull [] args) {
            if (args.length == 1) {
                return filterByPrefix(List.of("help", "list", "add", "remove"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
                // Returning null lets the server suggest online player names.
                return null;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
                if (!(sender instanceof Player player)) {
                    return new ArrayList<>();
                }
                Dao<IgnoreData, Integer> dao = dao();
                if (dao == null) {
                    return new ArrayList<>();
                }
                UUID me = player.getUniqueId();
                List<String> names = new ArrayList<>();
                for (IgnoreData data : dao.getList()) {
                    if (me.equals(data.getPlayer())) {
                        names.add(displayName(data.getIgnore()));
                    }
                }
                return filterByPrefix(names, args[1]);
            }
            return new ArrayList<>();
        }

        private static IgnoreData findRow(Dao<IgnoreData, Integer> dao, UUID owner, UUID target) {
            for (IgnoreData data : dao.getList()) {
                if (owner.equals(data.getPlayer()) && target.equals(data.getIgnore())) {
                    return data;
                }
            }
            return null;
        }

        private static UUID resolveUuid(String name) {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) {
                return online.getUniqueId();
            }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (offline != null && (offline.hasPlayedBefore() || offline.getName() != null)) {
                return offline.getUniqueId();
            }
            return null;
        }

        private static String displayName(UUID uuid) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            String name = offline == null ? null : offline.getName();
            return name == null || name.isBlank() ? uuid.toString() : name;
        }

        private static boolean containsIgnoreCase(List<String> values, String needle) {
            for (String value : values) {
                if (value != null && value.equalsIgnoreCase(needle)) {
                    return true;
                }
            }
            return false;
        }

        private static List<String> filterByPrefix(List<String> values, String prefix) {
            List<String> result = new ArrayList<>();
            String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
            for (String value : values) {
                if (value != null && value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    result.add(value);
                }
            }
            return result;
        }
    }
}
