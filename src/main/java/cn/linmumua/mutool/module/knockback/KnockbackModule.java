package cn.linmumua.mutool.module.knockback;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
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
import java.util.Random;

/**
 * Knockback module — pushes a target player with a configurable velocity.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_knockback}. It owns the
 * module switch, loads {@code module/knockback/config.yml} &amp; {@code module/knockback/lang.yml}
 * through TabooLib's {@link Configuration}, and registers the {@code /knockback} command
 * directly through Bukkit's {@link CommandMap} (no plugin.yml command entries).</p>
 *
 * <p>Command: {@code /knockback <玩家> [normal|random]}. The velocity change is Folia-safe:
 * it is scheduled onto the target's own region thread via
 * {@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)} before calling
 * {@link Player#setVelocity(Vector)}. Because Mutool has no cross-server ability, the
 * player name tab-completion uses {@link Bukkit#getOnlinePlayers()} instead of the
 * original {@code BungeeCordManager} online list.</p>
 */
public final class KnockbackModule implements MutoolModule {

    /** Permission node required to run {@code /knockback}. */
    public static final String PERMISSION = "mutool.commands.knockback";

    private static final String CONFIG_RESOURCE = "module/knockback/config.yml";
    private static final String LANG_RESOURCE = "module/knockback/lang.yml";

    private final Random random = new Random();

    private Mutool plugin;
    private final KnockbackLang lang = new KnockbackLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public KnockbackModule() {
    }

    @Override
    public String id() {
        return "knockback";
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
            IOKt.info("[Mutool/Knockback] 击退模块已关闭 (enable: false)");
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
            aliases.add("knockback");
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

            KnockbackExecutor executor = new KnockbackExecutor(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Knockback] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Knockback] 注册 /knockback 命令失败: " + throwable);
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

    // --- config getters used by the executor ---

    /** Default knockback type from config ({@code default_type}); falls back to {@code normal}. */
    String defaultType() {
        String type = this.config == null ? null : this.config.getString("default_type");
        return type == null ? "normal" : type.trim().toLowerCase(Locale.ROOT);
    }

    double vectorX() {
        return this.config == null ? 0.75 : this.config.getDouble("vector.x", 0.75);
    }

    double vectorY() {
        return this.config == null ? 0.5 : this.config.getDouble("vector.y", 0.5);
    }

    double vectorZ() {
        return this.config == null ? 0.75 : this.config.getDouble("vector.z", 0.75);
    }

    /** The configured base knockback vector ({@code vector.x/y/z}). */
    Vector baseVector() {
        return new Vector(this.vectorX(), this.vectorY(), this.vectorZ());
    }

    /** Executor for {@code /knockback}; applies the knockback onto the target's region thread. */
    private static final class KnockbackExecutor implements TabExecutor {

        private final KnockbackModule module;

        private KnockbackExecutor(KnockbackModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            KnockbackLang lang = this.module.lang;

            // 参数数量必须为 1 或 2：/knockback <玩家> [击退类型]
            if (args.length != 1 && args.length != 2) {
                sender.sendMessage(lang.usageError(label));
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(lang.playerOffline());
                return true;
            }

            String type = args.length == 1
                    ? this.module.defaultType()
                    : args[1].toLowerCase(Locale.ROOT);

            if (!"normal".equals(type) && !"random".equals(type)) {
                sender.sendMessage(lang.noType());
                return true;
            }

            Vector base = this.module.baseVector();
            String playerName = target.getName();

            // Folia 安全：投递到目标玩家所属区域线程后再改变速度
            FoliaExecutorKt.runTask(target, () -> {
                Vector velocity = "normal".equals(type)
                        ? base
                        : randomUnitVector(this.module.random).multiply(base.length());
                target.setVelocity(velocity);
                sender.sendMessage(lang.message(playerName));
            });
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            // 第一参数补全在线玩家名（Mutool 无跨服能力，改用本地在线列表）
            if (args.length == 1) {
                List<String> names = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    names.add(player.getName());
                }
                return names;
            }
            if (args.length == 2) {
                List<String> types = new ArrayList<>();
                types.add("normal");
                types.add("random");
                return types;
            }
            return new ArrayList<>();
        }

        /** A uniformly random unit vector on the unit sphere. */
        private static Vector randomUnitVector(Random random) {
            double theta = random.nextDouble() * 2 * Math.PI;
            double phi = random.nextDouble() * Math.PI;
            double randX = Math.sin(phi) * Math.cos(theta);
            double randY = Math.sin(phi) * Math.sin(theta);
            double randZ = Math.cos(phi);
            return new Vector(randX, randY, randZ);
        }
    }
}
