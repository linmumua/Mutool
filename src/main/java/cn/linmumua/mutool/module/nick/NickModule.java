package cn.linmumua.mutool.module.nick;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.database.Dao;
import cn.linmumua.mutool.database.DatabaseManager;
import cn.linmumua.mutool.database.entity.NickData;
import cn.linmumua.mutool.module.MutoolModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Nick (nickname) module — lets a player set a nickname for themselves, or an operator
 * set one for another online player.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_nick}. It owns the
 * module switch, loads {@code module/nick/config.yml} &amp; {@code module/nick/lang.yml}
 * through TabooLib's {@link Configuration}, registers the {@code /nick} command through
 * Bukkit's {@link CommandMap} (no plugin.yml command entries), and persists each
 * nickname to the shared ORMLite table {@code mumutools_nick} via
 * {@link DatabaseManager#dao(Class)}.</p>
 *
 * <p><b>Scope note:</b> this port only persists the nickname and prints a success
 * message. Actually rendering the nickname (tab list / chat / display name) is not wired
 * up in this batch — the original relied on PAPI placeholders and the
 * {@code MumuToolsPlayer} display-name replacement, which are out of scope here.</p>
 */
public final class NickModule implements MutoolModule {

    /** Base permission required to run {@code /nick}. */
    public static final String PERMISSION = "mutool.commands.nick";

    /** Extra permission required to set a nickname for <em>another</em> player. */
    public static final String GIVE_PERMISSION = "mutool.commands.nick.give";

    /** Special nickname argument that clears the target's nickname. */
    private static final String OFF = "off";

    private static final String CONFIG_RESOURCE = "module/nick/config.yml";
    private static final String LANG_RESOURCE = "module/nick/lang.yml";

    private Mutool plugin;
    private final NickLang lang = new NickLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public NickModule() {
    }

    @Override
    public String id() {
        return "nick";
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
            IOKt.info("[Mutool/Nick] 昵称模块已关闭 (enable: false)");
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
            aliases.add("nick");
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

            NickExecutor executor = new NickExecutor(this.plugin, this.lang);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Nick] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Nick] 注册 /nick 命令失败: " + throwable);
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

    /** Executor for {@code /nick}; persists the target's nickname into {@code mumutools_nick}. */
    private static final class NickExecutor implements TabExecutor {

        private final Mutool plugin;
        private final NickLang lang;

        private NickExecutor(Mutool plugin, NickLang lang) {
            this.plugin = plugin;
            this.lang = lang;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            Player target = null;
            boolean sendToSender = true;

            // /nick <名称> — the sender renames themselves.
            if (args.length == 1 && sender instanceof Player player) {
                target = player;
                sendToSender = false;
            }

            // /nick <名称> <玩家> — an operator renames another online player.
            if (args.length == 2) {
                if (!sender.hasPermission(GIVE_PERMISSION)) {
                    sender.sendMessage(this.lang.noPermission());
                    return true;
                }
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(this.lang.playerOffline());
                    return true;
                }
            }

            if (target == null) {
                sender.sendMessage(this.lang.usageError(label));
                return true;
            }

            Dao<NickData, UUID> dao = this.dao();
            if (dao == null) {
                // Data layer unavailable (init failed). Surface a themed error, keep server stable.
                sender.sendMessage(this.lang.noPermission());
                return true;
            }

            UUID uuid = target.getUniqueId();
            String nick = args[0];
            try {
                if (OFF.equalsIgnoreCase(nick)) {
                    NickData existing = dao.getById(uuid);
                    if (existing != null) {
                        dao.delete(existing);
                    }
                } else {
                    dao.createOrUpdate(new NickData(uuid, nick));
                }
            } catch (RuntimeException exception) {
                IOKt.severe("[Mutool/Nick] 保存昵称失败 (" + uuid + "): " + exception.getMessage());
                sender.sendMessage(this.lang.noPermission());
                return true;
            }

            Component message = this.lang.message(target.getName(), nick);
            if (sendToSender && !sender.equals(target)) {
                target.sendMessage(message);
            }
            sender.sendMessage(message);
            return true;
        }

        private Dao<NickData, UUID> dao() {
            DatabaseManager databaseManager = this.plugin.getDatabaseManager();
            return databaseManager == null ? null : databaseManager.dao(NickData.class);
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (args.length == 1) {
                return List.of(OFF);
            }
            if (args.length == 2 && sender.hasPermission(GIVE_PERMISSION)) {
                List<String> names = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    names.add(online.getName());
                }
                return names;
            }
            return new ArrayList<>();
        }
    }
}
