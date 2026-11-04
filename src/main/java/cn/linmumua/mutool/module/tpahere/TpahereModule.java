package cn.linmumua.mutool.module.tpahere;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import cn.linmumua.mutool.redis.MessageBus;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import org.jetbrains.annotations.Nullable;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;
import taboolib.platform.util.FoliaExecutorKt;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tpahere module — a player can ask another player to teleport to their own location.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_tpahere}. Sub-commands:</p>
 * <ul>
 *   <li>{@code /tpahere <player>} — ask {@code player} to teleport to you (also opens the request
 *       panel when run with no arguments);</li>
 *   <li>{@code /tpahere accept [player]} — the target accepts and is teleported to the requester;</li>
 *   <li>{@code /tpahere reject [player]} — the target declines the request.</li>
 * </ul>
 *
 * <p>It owns its {@code module/tpahere/config.yml}, {@code module/tpahere/lang.yml} and
 * {@code module/tpahere/menu/tpahere.yml}, and registers the {@code /tpahere} command directly
 * through Bukkit's {@link CommandMap} (no plugin.yml entries), guarded by the
 * {@value #PERMISSION} permission.</p>
 *
 * <h2>Cross-server</h2>
 * <p>Request delivery and accept/reject/timeout notifications travel over the shared
 * {@link MessageBus} (see {@link TpahereNet}) so they reach whichever server the other player is
 * on. The actual teleport, however, requires both players to share a server: Mutool ships with no
 * BungeeCord transfer channel, so when the requester is not local the teleport is <em>degraded to
 * same-server only</em> and both parties are told ({@code accept.degraded}). Teleporting is
 * Folia-safe via {@link FoliaExecutorKt#runTask(Player, Runnable)} before
 * {@link Player#teleportAsync(Location)}.</p>
 */
public final class TpahereModule implements MutoolModule {

    /** Permission node required to run {@code /tpahere}. */
    public static final String PERMISSION = "mutool.commands.tpahere";

    private static final String CONFIG_RESOURCE = "module/tpahere/config.yml";
    private static final String LANG_RESOURCE = "module/tpahere/lang.yml";
    private static final String MENU_RESOURCE = "module/tpahere/menu/tpahere.yml";

    private static final String DEFAULT_COMMAND = "tpahere";

    private Mutool plugin;

    private final TpahereLang lang = new TpahereLang();
    private final TpahereMenuConfig menuSettings = new TpahereMenuConfig();
    private final TpahereRequests requests = new TpahereRequests();

    /** Requester name (lower-case) -> cooldown end epoch millis. */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    private Configuration config;
    private ScheduledExecutorService scheduler;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public TpahereModule() {
    }

    @Override
    public String id() {
        return "tpahere";
    }

    /** Release default resources, load config/lang/menu, register the bus handlers, then wire the command. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);
        File menuFile = IOKt.releaseResourceFile(MENU_RESOURCE, false, MENU_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);
        this.menuSettings.load(menuFile);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Mutool-Tpahere-Timeout");
            thread.setDaemon(true);
            return thread;
        });

        this.registerBusHandlers();
        this.applyState();
    }

    /** Reload config/lang/menu from disk, re-register the bus handlers and re-apply the enable state. */
    @Override
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
        this.lang.reload();
        this.menuSettings.reload();
        this.registerBusHandlers();
        this.applyState();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    /** Unregister the command and bus handlers, stop the scheduler and drop state. */
    @Override
    public void unload() {
        this.unregisterCommand();
        this.unregisterBusHandlers();
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
            this.scheduler = null;
        }
        this.enabled = false;
    }

    // --- lifecycle internals ---

    private void applyState() {
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterCommand();
        if (nowEnabled) {
            this.ensurePermission();
            this.registerCommand();
        }
        this.enabled = nowEnabled && this.command != null;

        if (!nowEnabled) {
            IOKt.info("[Mutool/Tpahere] 传送请求模块已关闭 (enable: false)");
        }
    }

    /**
     * Register the {@value #PERMISSION} permission programmatically — the module is not declared in
     * the immutable {@code paper-plugin.yml}, so without this the node would be unknown and default
     * players denied. Idempotent.
     */
    private void ensurePermission() {
        try {
            PluginManager pluginManager = this.plugin.getServer().getPluginManager();
            if (pluginManager.getPermission(PERMISSION) == null) {
                pluginManager.addPermission(new Permission(
                        PERMISSION, "允许使用 /tpahere 请求他人传送到自己身边", PermissionDefault.TRUE));
            }
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Tpahere] 注册权限节点失败: " + throwable);
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
            names.add(DEFAULT_COMMAND);
        }
        return names;
    }

    // permissionMessage(Component) is deprecated in the current Paper API but still the only way to
    // render a themed MiniMessage permission denied message per command.
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

            TpahereExecutor executor = new TpahereExecutor(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Tpahere] 已注册命令: /" + names.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Tpahere] 注册 /tpahere 命令失败: " + throwable);
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

    // --- cross-server bus wiring ---

    private void registerBusHandlers() {
        MessageBus bus = Mutool.getMessageBus();
        if (bus == null) {
            return;
        }
        bus.register(TpahereNet.TYPE_REQUEST, this::onBusRequest);
        bus.register(TpahereNet.TYPE_RESPONSE, this::onBusResponse);
    }

    private void unregisterBusHandlers() {
        MessageBus bus = Mutool.getMessageBus();
        if (bus == null) {
            return;
        }
        bus.unregister(TpahereNet.TYPE_REQUEST);
        bus.unregister(TpahereNet.TYPE_RESPONSE);
    }

    /** A request arrived (locally or from another server): deliver the panel if the target is here. */
    private void onBusRequest(@Nullable JsonObject data) {
        if (data == null || !data.has("requester") || !data.has("target")) {
            return;
        }
        String requester = data.get("requester").getAsString();
        String target = data.get("target").getAsString();
        this.deliverRequest(requester, target);
    }

    /** A response arrived on the requester's server: notify the requester. */
    private void onBusResponse(@Nullable JsonObject data) {
        if (data == null || !data.has("requester") || !data.has("action")) {
            return;
        }
        String requester = data.get("requester").getAsString();
        String target = data.has("target") ? data.get("target").getAsString() : "";
        String action = data.get("action").getAsString();
        boolean teleported = data.has("teleported") && data.get("teleported").getAsBoolean();

        Player requesterPlayer = Bukkit.getPlayerExact(requester);
        if (requesterPlayer == null) {
            return;
        }
        switch (action) {
            case TpahereNet.ACTION_ACCEPT ->
                    this.tell(requesterPlayer, teleported ? this.lang.accepted(target) : this.lang.acceptDegraded(target));
            case TpahereNet.ACTION_REJECT -> this.tell(requesterPlayer, this.lang.rejected(target));
            case TpahereNet.ACTION_TIMEOUT -> this.tell(requesterPlayer, this.lang.delayMessage(target));
            default -> {
            }
        }
    }

    /**
     * Record a pending request on whichever server the {@code target} is online and deliver the
     * clickable accept/reject panel. No-op when the target is not on this server.
     */
    private void deliverRequest(String requester, String target) {
        Player targetPlayer = Bukkit.getPlayerExact(target);
        if (targetPlayer == null) {
            return;
        }
        TpahereRequests.Entry entry = this.requests.put(requester, target, this.timeoutSeconds() * 1000L);
        this.scheduleTimeout(requester, target, entry.expireAt());
        this.tell(targetPlayer, this.lang.requestMessage(requester));
    }

    private void scheduleTimeout(String requester, String target, long expireAt) {
        if (this.scheduler == null) {
            return;
        }
        long delay = Math.max(0L, expireAt - System.currentTimeMillis());
        try {
            this.scheduler.schedule(() -> {
                if (!this.requests.removeIfMatches(requester, target, expireAt)) {
                    return;
                }
                Player targetLocal = Bukkit.getPlayerExact(target);
                if (targetLocal != null) {
                    this.tell(targetLocal, this.lang.delayTimeOut(requester));
                }
                Player requesterLocal = Bukkit.getPlayerExact(requester);
                if (requesterLocal != null) {
                    this.tell(requesterLocal, this.lang.delayMessage(target));
                } else {
                    TpahereNet.publishResponse(requester, target, TpahereNet.ACTION_TIMEOUT, false);
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Tpahere] 调度请求超时任务失败: " + throwable);
        }
    }

    // --- command handling (invoked from the executor / menu) ---

    /** {@code /tpahere <player>}: validate, apply cooldown, then ask {@code target} to come over. */
    void sendRequest(Player sender, String targetName) {
        if (targetName == null || targetName.isBlank()) {
            sender.sendMessage(this.lang.usageError(DEFAULT_COMMAND));
            return;
        }
        if (this.isBlackWorld(sender)) {
            sender.sendMessage(this.lang.blackWorld());
            return;
        }
        if (targetName.equalsIgnoreCase(sender.getName())) {
            sender.sendMessage(this.lang.sendSelf());
            return;
        }

        long now = System.currentTimeMillis();
        String key = TpahereRequests.key(sender.getName());
        Long until = this.cooldowns.get(key);
        if (until != null) {
            if (until > now) {
                long remaining = (until - now + 999L) / 1000L;
                this.tell(sender, this.lang.inDelay(String.valueOf(remaining)));
                return;
            }
            this.cooldowns.remove(key);
        }

        Player targetLocal = Bukkit.getPlayerExact(targetName);
        boolean crossServer = this.isCrossServerAvailable();
        if (targetLocal == null && !crossServer) {
            sender.sendMessage(this.lang.playerOffline());
            return;
        }

        int cooldown = this.cooldownSeconds();
        if (cooldown > 0) {
            this.cooldowns.put(key, now + cooldown * 1000L);
        }

        this.tell(sender, this.lang.message(targetName));
        // Dispatches locally (delivers now if the target shares this server) and, with Redis on,
        // relays to whichever server the target is actually on.
        TpahereNet.publishRequest(sender.getName(), targetName);
    }

    void openMenu(Player player, int page) {
        if (this.isBlackWorld(player)) {
            player.sendMessage(this.lang.blackWorld());
            return;
        }
        new TpahereMenu(this, player, Math.max(1, page)).open(player);
    }

    /** {@code /tpahere accept [requester]}: the target accepts and is teleported to the requester. */
    void handleAccept(Player sender, @Nullable String requesterArg) {
        TpahereRequests.Entry entry = this.resolveRequest(sender, requesterArg);
        if (entry == null) {
            return;
        }
        this.requests.removeByRequester(entry.requester());

        String requester = entry.requester();
        Player requesterLocal = Bukkit.getPlayerExact(requester);

        if (requesterLocal != null) {
            // Same server: teleport the accepting target to the requester, Folia-safely.
            Location destination = requesterLocal.getLocation();
            this.tell(sender, this.lang.acceptMessage(requester));
            this.tell(requesterLocal, this.lang.accepted(sender.getName()));
            FoliaExecutorKt.runTask(sender, () -> sender.teleportAsync(destination));
        } else {
            // Cross-server without a BungeeCord transfer channel: degrade to same-server only.
            this.tell(sender, this.lang.crossServer());
            TpahereNet.publishResponse(requester, sender.getName(), TpahereNet.ACTION_ACCEPT, false);
        }
    }

    /** {@code /tpahere reject [requester]}: the target declines the request. */
    void handleReject(Player sender, @Nullable String requesterArg) {
        TpahereRequests.Entry entry = this.resolveRequest(sender, requesterArg);
        if (entry == null) {
            return;
        }
        this.requests.removeByRequester(entry.requester());

        String requester = entry.requester();
        Player requesterLocal = Bukkit.getPlayerExact(requester);

        this.tell(sender, this.lang.rejectMessage(requester));
        if (requesterLocal != null) {
            this.tell(requesterLocal, this.lang.rejected(sender.getName()));
        } else {
            TpahereNet.publishResponse(requester, sender.getName(), TpahereNet.ACTION_REJECT, false);
        }
    }

    /**
     * Resolve the pending request a {@code sender} is acting on. When {@code requesterArg} is given
     * it must name the requester and the request must be addressed to {@code sender}; when omitted
     * the newest request addressed to {@code sender} is used. Sends the appropriate "no request"
     * message and returns {@code null} when nothing matches.
     */
    @Nullable
    private TpahereRequests.Entry resolveRequest(Player sender, @Nullable String requesterArg) {
        TpahereRequests.Entry entry;
        if (requesterArg != null && !requesterArg.isBlank()) {
            entry = this.requests.get(requesterArg);
            if (entry == null || !entry.target().equalsIgnoreCase(sender.getName())) {
                this.tell(sender, this.lang.noRequest(requesterArg));
                return null;
            }
        } else {
            entry = this.requests.findByTarget(sender.getName());
            if (entry == null) {
                this.tell(sender, this.lang.noPendingRequest());
                return null;
            }
        }
        return entry;
    }

    private boolean isBlackWorld(Player player) {
        List<String> blackWorld = this.config == null ? null : this.config.getStringList("black_world");
        return blackWorld != null && blackWorld.contains(player.getWorld().getName());
    }

    // --- config accessors ---

    int timeoutSeconds() {
        int value = this.config == null ? 60 : this.config.getInt("timeout", 60);
        return Math.max(1, value);
    }

    int cooldownSeconds() {
        int value = this.config == null ? 0 : this.config.getInt("cooldown", 10);
        return Math.max(0, value);
    }

    private boolean isCrossServerAvailable() {
        var redis = Mutool.getRedisManager();
        return redis != null && redis.isEnabled();
    }

    /** The parsed menu configuration, shared read-only with {@link TpahereMenu}. */
    TpahereMenuConfig menuConfig() {
        return this.menuSettings;
    }

    /** Deliver {@code message} to {@code player} on their own region thread (Folia-safe). */
    private void tell(@Nullable Player player, Component message) {
        if (player == null) {
            return;
        }
        FoliaExecutorKt.runTask(player, () -> player.sendMessage(message));
    }

    // --- executor ---

    /** Executor + tab completer for {@code /tpahere}. */
    private static final class TpahereExecutor implements TabExecutor {

        private final TpahereModule module;

        private TpahereExecutor(TpahereModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(this.module.lang.onlyPlayer());
                return true;
            }
            try {
                if (args.length == 0) {
                    this.module.openMenu(player, 1);
                    player.sendMessage(this.module.lang.openMenu());
                    return true;
                }

                String sub = args[0];
                if (sub.equalsIgnoreCase("accept")) {
                    this.module.handleAccept(player, args.length >= 2 ? args[1] : null);
                    return true;
                }
                if (sub.equalsIgnoreCase("reject")) {
                    this.module.handleReject(player, args.length >= 2 ? args[1] : null);
                    return true;
                }
                if (args.length == 1) {
                    this.module.sendRequest(player, sub);
                    return true;
                }

                player.sendMessage(this.module.lang.usageError(label));
            } catch (Throwable throwable) {
                IOKt.severe("[Mutool/Tpahere] 执行 /" + label + " 失败: " + throwable);
            }
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (args.length == 1) {
                List<String> names = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    names.add(online.getName());
                }
                names.add("accept");
                names.add("reject");
                return names;
            }
            if (args.length == 2
                    && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("reject"))) {
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
