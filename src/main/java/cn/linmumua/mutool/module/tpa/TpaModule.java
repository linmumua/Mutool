package cn.linmumua.mutool.module.tpa;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import cn.linmumua.mutool.redis.MessageBus;
import cn.linmumua.mutool.redis.RedisManager;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tpa module — request-based teleportation ({@code /tpa <player>} + accept / reject).
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_tpa}. It owns the module
 * switch, loads {@code module/tpa/config.yml}, {@code module/tpa/lang.yml} and
 * {@code module/tpa/menu/tpa.yml} through TabooLib's {@link Configuration}, registers the
 * {@code /tpa} command (plus optional {@code /tpaccept} / {@code /tpdeny} aliases) directly
 * through Bukkit's {@link CommandMap}, and wires its own quit listener and timeout sweeper.</p>
 *
 * <h2>Cross-server</h2>
 * <p>Requests are usable across servers through the shared {@link MessageBus}:</p>
 * <ul>
 *   <li>{@code tpa_request} — published when the target is not on this server. The server that
 *       actually holds the target receives it and shows that player the clickable request
 *       panel.</li>
 *   <li>{@code tpa_accept} — published when a player accepts a request whose requester lives on
 *       another server.</li>
 *   <li>{@code tpa_reject} — the reject counterpart, so the requester is notified remotely.</li>
 * </ul>
 * <p><b>Degradation:</b> this base has <em>no</em> BungeeCord transport, so the actual
 * cross-server teleport cannot be performed. Same-server teleport is fully supported; when the
 * two players are on different servers the flow stops at a themed "目标在其它服务器" notice
 * instead of moving the player. Cross-server messaging is only attempted when Redis is enabled
 * (see {@link Mutool#getRedisManager()}); otherwise the target is reported as offline.</p>
 *
 * <p><b>Folia:</b> the real teleport is always scheduled onto the requester's own region thread
 * via {@link FoliaExecutorKt#runTask(Player, Runnable)} before calling
 * {@link Player#teleportAsync(Location)}.</p>
 */
public final class TpaModule implements MutoolModule {

    /** Permission node required to run {@code /tpa} and its aliases. */
    public static final String PERMISSION = "mutool.commands.tpa";

    /** Bus type: a teleport request hops servers so the target's server can render the panel. */
    public static final String TYPE_REQUEST = "tpa_request";
    /** Bus type: a request was accepted on the target's server (tells the origin server). */
    public static final String TYPE_ACCEPT = "tpa_accept";
    /** Bus type: a request was rejected on the target's server (tells the origin server). */
    public static final String TYPE_REJECT = "tpa_reject";

    private static final String CONFIG_RESOURCE = "module/tpa/config.yml";
    private static final String LANG_RESOURCE = "module/tpa/lang.yml";
    private static final String MENU_RESOURCE = "module/tpa/menu/tpa.yml";

    private Mutool plugin;
    private final TpaLang lang = new TpaLang();
    private final TpaMenuConfig menuSettings = new TpaMenuConfig();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;
    private TpaListener listener;
    private ScheduledExecutorService scheduler;

    /** Pending requests, keyed by the requester's lower-cased name. */
    private final Map<String, Request> requests = new ConcurrentHashMap<>();
    /** Per-requester cooldown end timestamps (epoch millis), keyed by lower-cased name. */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public TpaModule() {
    }

    @Override
    public String id() {
        return "tpa";
    }

    /** Release default resources, load config/lang/menu, then register everything. */
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

    /** Reload config/lang/menu from disk and re-apply the enable state / re-register everything. */
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

    /** Unregister the command, listener and bus handlers, stop the sweeper and drop state. */
    @Override
    public void unload() {
        this.unregisterCommand();
        this.unregisterListener();
        this.unregisterBus();
        this.stopScheduler();
        this.requests.clear();
        this.cooldowns.clear();
        this.enabled = false;
    }

    // --- internals ---

    private void applyState() {
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterCommand();
        this.unregisterListener();
        this.unregisterBus();
        this.stopScheduler();

        if (nowEnabled) {
            this.ensurePermission();
            this.registerCommand();
            this.registerListener();
            this.registerBus();
            this.startScheduler();
        }
        this.enabled = nowEnabled && this.command != null;

        if (!nowEnabled) {
            IOKt.info("[Mutool/Tpa] 传送请求模块已关闭 (enable: false)");
        }
    }

    /**
     * Register the {@code mutool.commands.tpa} permission programmatically. The module is not
     * declared in the (immutable) {@code paper-plugin.yml}, so without this the node would be
     * unknown and ordinary players would be denied. Idempotent.
     */
    private void ensurePermission() {
        try {
            PluginManager pluginManager = this.plugin.getServer().getPluginManager();
            if (pluginManager.getPermission(PERMISSION) == null) {
                pluginManager.addPermission(new Permission(
                        PERMISSION, "允许使用 /tpa 传送请求", PermissionDefault.TRUE));
            }
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Tpa] 注册权限节点失败: " + throwable);
        }
    }

    // --- config accessors ---

    int timeoutSeconds() {
        return this.config == null ? 60 : Math.max(1, this.config.getInt("timeout", 60));
    }

    int cooldownSeconds() {
        return this.config == null ? 0 : Math.max(0, this.config.getInt("cooldown", 0));
    }

    private List<String> commandNames() {
        List<String> configured = this.config == null ? null : this.config.getStringList("commands");
        List<String> names = new ArrayList<>();
        if (configured != null) {
            for (String value : configured) {
                if (value != null && !value.isBlank()) {
                    names.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (names.isEmpty()) {
            names.add("tpa");
        }
        return names;
    }

    private boolean isCrossServerAvailable() {
        RedisManager manager = Mutool.getRedisManager();
        return manager != null && manager.isEnabled();
    }

    // --- command registration ---

    // permissionMessage(Component) is deprecated in the current Paper API but still the only
    // way to render a themed MiniMessage permission denied message per command.
    @SuppressWarnings("deprecation")
    private void registerCommand() {
        try {
            Constructor<PluginCommand> constructor =
                    PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);

            List<String> names = this.commandNames();
            PluginCommand cmd = constructor.newInstance(names.get(0), this.plugin);
            if (names.size() > 1) {
                cmd.setAliases(names.subList(1, names.size()));
            }
            cmd.setPermission(PERMISSION);
            cmd.permissionMessage(this.lang.noPermission());
            cmd.setDescription(this.lang.plainDescription());

            TpaExecutor executor = new TpaExecutor(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Tpa] 已注册命令: /" + names.get(0)
                    + (names.size() > 1 ? " (别名: " + String.join(", ", names.subList(1, names.size())) + ")" : "")
                    + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Tpa] 注册 /tpa 命令失败: " + throwable);
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
        this.listener = new TpaListener(this);
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

    private void registerBus() {
        MessageBus bus = Mutool.getMessageBus();
        if (bus == null) {
            return;
        }
        bus.register(TYPE_REQUEST, this::onRemoteRequest);
        bus.register(TYPE_ACCEPT, this::onRemoteAccept);
        bus.register(TYPE_REJECT, this::onRemoteReject);
    }

    private void unregisterBus() {
        MessageBus bus = Mutool.getMessageBus();
        if (bus == null) {
            return;
        }
        bus.unregister(TYPE_REQUEST);
        bus.unregister(TYPE_ACCEPT);
        bus.unregister(TYPE_REJECT);
    }

    private void startScheduler() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Mutool TPA Timeout Thread");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::expireRequests, 1000L, 1000L, TimeUnit.MILLISECONDS);
    }

    private void stopScheduler() {
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
            this.scheduler = null;
        }
    }

    // --- request flow ---

    /** Handle {@code /tpa <player>}: validate, cooldown, then route local or cross-server. */
    void sendRequest(@NotNull Player sender, @NotNull String targetName) {
        if (targetName == null || targetName.isBlank()) {
            sender.sendMessage(this.lang.usageError("tpa"));
            return;
        }
        if (targetName.equalsIgnoreCase(sender.getName())) {
            sender.sendMessage(this.lang.sendSelf());
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = this.cooldownSeconds();
        if (cooldown > 0) {
            Long end = this.cooldowns.get(lower(sender.getName()));
            if (end != null && now < end) {
                long remaining = Math.max(1L, (end - now + 999L) / 1000L);
                sender.sendMessage(this.lang.inDelay(remaining));
                return;
            }
        }

        Player localTarget = Bukkit.getPlayerExact(targetName);
        if (localTarget == null && !this.isCrossServerAvailable()) {
            sender.sendMessage(this.lang.playerOffline());
            return;
        }

        if (cooldown > 0) {
            this.cooldowns.put(lower(sender.getName()), now + cooldown * 1000L);
        }

        if (localTarget != null) {
            this.addRequest(sender.getName(), localTarget.getName(), false);
            sender.sendMessage(this.lang.requestSent(localTarget.getName()));
            localTarget.sendMessage(this.lang.requestPanel(sender.getName()));
            return;
        }

        MessageBus bus = Mutool.getMessageBus();
        if (bus == null) {
            sender.sendMessage(this.lang.playerOffline());
            return;
        }
        bus.send(TYPE_REQUEST, payload(sender.getName(), targetName));
        sender.sendMessage(this.lang.requestSentCross(targetName));
    }

    /** Handle {@code /tpa accept [requester]} and {@code /tpaccept}. */
    void accept(@NotNull Player sender, String requesterArg) {
        Request request = this.resolveFor(sender, requesterArg);
        if (request == null) {
            sender.sendMessage(this.lang.noRequest(requesterArg == null ? "" : requesterArg));
            return;
        }
        this.requests.remove(lower(request.requester()));

        Player requester = Bukkit.getPlayerExact(request.requester());
        if (requester != null) {
            Location destination = sender.getLocation();
            // Folia 安全：投递到请求者区域线程后再执行传送
            FoliaExecutorKt.runTask(requester, () -> {
                requester.teleportAsync(destination);
            });
            requester.sendMessage(this.lang.accepted(sender.getName()));
            sender.sendMessage(this.lang.acceptMessage(request.requester()));
            return;
        }

        if (request.crossServer()) {
            // 跨服：没有 BungeeCord 通道，无法真正把玩家传过来 —— 通知对方服并降级提示
            MessageBus bus = Mutool.getMessageBus();
            if (bus != null) {
                bus.send(TYPE_ACCEPT, payload(request.requester(), sender.getName()));
            }
            sender.sendMessage(this.lang.acceptCrossDegrade(request.requester()));
            return;
        }

        sender.sendMessage(this.lang.playerOffline());
    }

    /** Handle {@code /tpa reject [requester]} and {@code /tpdeny}. */
    void reject(@NotNull Player sender, String requesterArg) {
        Request request = this.resolveFor(sender, requesterArg);
        if (request == null) {
            sender.sendMessage(this.lang.noRequest(requesterArg == null ? "" : requesterArg));
            return;
        }
        this.requests.remove(lower(request.requester()));

        Player requester = Bukkit.getPlayerExact(request.requester());
        if (requester != null) {
            requester.sendMessage(this.lang.rejected(sender.getName()));
            sender.sendMessage(this.lang.rejectMessage(request.requester()));
            return;
        }

        if (request.crossServer()) {
            MessageBus bus = Mutool.getMessageBus();
            if (bus != null) {
                bus.send(TYPE_REJECT, payload(request.requester(), sender.getName()));
            }
            sender.sendMessage(this.lang.rejectMessage(request.requester()));
            return;
        }

        sender.sendMessage(this.lang.playerOffline());
    }

    /** Resolve a request addressed to {@code sender}; {@code requesterArg} null ⇒ newest inbound. */
    private Request resolveFor(@NotNull Player sender, String requesterArg) {
        Request request;
        if (requesterArg == null || requesterArg.isBlank()) {
            request = this.newestInbound(sender.getName());
        } else {
            request = this.requests.get(lower(requesterArg));
        }
        if (request == null || request.isExpired() || !request.target().equalsIgnoreCase(sender.getName())) {
            return null;
        }
        return request;
    }

    private Request newestInbound(@NotNull String target) {
        Request newest = null;
        for (Request request : this.requests.values()) {
            if (request.isExpired() || !request.target().equalsIgnoreCase(target)) {
                continue;
            }
            if (newest == null || request.expireAt() > newest.expireAt()) {
                newest = request;
            }
        }
        return newest;
    }

    private void addRequest(@NotNull String requester, @NotNull String target, boolean crossServer) {
        long expireAt = System.currentTimeMillis() + this.timeoutSeconds() * 1000L;
        this.requests.put(lower(requester), new Request(requester, target, expireAt, crossServer));
    }

    // --- bus handlers ---

    private void onRemoteRequest(JsonObject data) {
        String requester = readString(data, "requester");
        String target = readString(data, "target");
        if (requester == null || target == null) {
            return;
        }
        Player targetPlayer = Bukkit.getPlayerExact(target);
        if (targetPlayer == null) {
            return;
        }
        this.addRequest(requester, targetPlayer.getName(), true);
        targetPlayer.sendMessage(this.lang.requestPanel(requester));
    }

    private void onRemoteAccept(JsonObject data) {
        String requester = readString(data, "requester");
        String target = readString(data, "target");
        if (requester == null) {
            return;
        }
        Player requesterPlayer = Bukkit.getPlayerExact(requester);
        if (requesterPlayer == null) {
            return;
        }
        requesterPlayer.sendMessage(this.lang.acceptCrossNotify(target == null ? "" : target));
    }

    private void onRemoteReject(JsonObject data) {
        String requester = readString(data, "requester");
        String target = readString(data, "target");
        if (requester == null) {
            return;
        }
        Player requesterPlayer = Bukkit.getPlayerExact(requester);
        if (requesterPlayer == null) {
            return;
        }
        requesterPlayer.sendMessage(this.lang.rejectCrossNotify(target == null ? "" : target));
    }

    // --- timeout sweeper ---

    private void expireRequests() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Request> entry : this.requests.entrySet()) {
            Request request = entry.getValue();
            if (request.expireAt() > now) {
                continue;
            }
            if (!this.requests.remove(entry.getKey(), request)) {
                continue;
            }
            Player requester = Bukkit.getPlayerExact(request.requester());
            Player target = Bukkit.getPlayerExact(request.target());
            if (requester != null) {
                requester.sendMessage(this.lang.expireRequester(request.target()));
            }
            if (target != null) {
                target.sendMessage(this.lang.expireTarget(request.requester()));
            }
        }
    }

    /** Drop every pending request (as requester or target) and the cooldown for a quitting player. */
    void forget(@NotNull Player player) {
        String name = player.getName();
        this.requests.entrySet().removeIf(entry ->
                entry.getValue().requester().equalsIgnoreCase(name)
                        || entry.getValue().target().equalsIgnoreCase(name));
        this.cooldowns.remove(lower(name));
    }

    // --- menu ---

    TpaMenuConfig menuConfig() {
        return this.menuSettings;
    }

    TpaLang lang() {
        return this.lang;
    }

    /** Build and open the online-player picker at {@code page} (clamped to {@code >= 1}). */
    void openMenu(@NotNull Player player, int page) {
        if (Bukkit.getOnlinePlayers().size() <= 1) {
            player.sendMessage(this.lang.menuNoPlayers());
            return;
        }
        new TpaMenu(this, player, Math.max(1, page)).open(player);
    }

    // --- command dispatch ---

    /** Root dispatch shared by the executor; branches on the alias actually used. */
    void dispatch(@NotNull Player player, @NotNull Command command, @NotNull String label, String[] args) {
        String name = command.getName();
        if (label.equalsIgnoreCase("tpaccept") || name.equalsIgnoreCase("tpaccept")) {
            this.accept(player, args.length > 0 ? args[0] : null);
            return;
        }
        if (label.equalsIgnoreCase("tpdeny") || name.equalsIgnoreCase("tpdeny")) {
            this.reject(player, args.length > 0 ? args[0] : null);
            return;
        }

        if (args.length == 0) {
            this.openMenu(player, 1);
            return;
        }
        if (args[0].equalsIgnoreCase("accept")) {
            this.accept(player, args.length > 1 ? args[1] : null);
            return;
        }
        if (args[0].equalsIgnoreCase("reject") || args[0].equalsIgnoreCase("deny")) {
            this.reject(player, args.length > 1 ? args[1] : null);
            return;
        }
        if (args.length == 1) {
            this.sendRequest(player, args[0]);
            return;
        }
        player.sendMessage(this.lang.usageError(label));
    }

    List<String> tabComplete(@NotNull Player sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("accept");
            options.add("reject");
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.getName().equalsIgnoreCase(sender.getName())) {
                    options.add(online.getName());
                }
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matched = new ArrayList<>();
            for (String option : options) {
                if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    matched.add(option);
                }
            }
            return matched;
        }
        return new ArrayList<>();
    }

    // --- helpers ---

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static JsonObject payload(String requester, String target) {
        JsonObject object = new JsonObject();
        object.addProperty("requester", requester);
        object.addProperty("target", target);
        return object;
    }

    private static String readString(JsonObject data, String key) {
        if (data == null || !data.has(key) || data.get(key).isJsonNull()) {
            return null;
        }
        return data.get(key).getAsString();
    }

    /** A pending teleport request: who asked, to whom, when it expires, and whether it crossed servers. */
    private record Request(String requester, String target, long expireAt, boolean crossServer) {
        boolean isExpired() {
            return System.currentTimeMillis() >= this.expireAt;
        }
    }

    /** Executor for {@code /tpa}, {@code /tpaccept} and {@code /tpdeny}. */
    private static final class TpaExecutor implements TabExecutor {

        private final TpaModule module;

        private TpaExecutor(TpaModule module) {
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
                this.module.dispatch(player, command, label, args);
            } catch (Throwable throwable) {
                IOKt.severe("[Mutool/Tpa] 执行 /" + label + " 失败: " + throwable);
            }
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (sender instanceof Player player) {
                return this.module.tabComplete(player, args);
            }
            return new ArrayList<>();
        }
    }
}
