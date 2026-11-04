package cn.linmumua.mutool.module.broadcast;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import cn.linmumua.mutool.redis.MessageBus;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
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

/**
 * Broadcast module — sends a server-wide announcement to every player on this server and,
 * when Redis is enabled, to every other server on the network via the shared
 * {@link MessageBus} (bus type {@value #BUS_TYPE}).
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_broadcast}. It owns the
 * module switch, loads {@code module/broadcast/config.yml} &amp; {@code module/broadcast/lang.yml}
 * through TabooLib's {@link Configuration}, and registers the {@code /broadcast} command
 * (with any configured aliases) directly through Bukkit's {@link CommandMap} — no
 * {@code paper-plugin.yml} command entries.</p>
 *
 * <p><strong>Cross-server delivery.</strong> The command builds the raw announcement text
 * and calls {@code messageBus.send("broadcast", {"message": <raw>})}. {@link MessageBus}
 * invokes the local handler immediately (so this server renders without a Redis round-trip)
 * and then publishes the same payload; other servers receive it, run the identical handler
 * and render it locally. The origin server is de-duplicated by {@link MessageBus}, so no
 * shadow double-broadcast occurs. Delivery degrades to local-only when Redis is disabled.</p>
 *
 * <p><strong>Folia safety.</strong> Both the local and remote receive paths simply call
 * {@code Bukkit.getServer().broadcast(Component)}, which is thread-safe on Paper/Folia and
 * does not touch world state — so no explicit main/region-thread hop is required. The remote
 * handler runs on lettuce's netty thread and this direct broadcast is intentional.</p>
 */
public final class BroadcastModule implements MutoolModule {

    /** Permission node required to run {@code /broadcast}. */
    public static final String PERMISSION = "mutool.commands.broadcast";

    /** Bus type tag for the broadcast payload (matches {@code MessageTypes.BROADCAST}). */
    public static final String BUS_TYPE = "broadcast";

    /** Payload field carrying the raw (un-parsed) announcement text. */
    private static final String KEY_MESSAGE = "message";

    private static final String CONFIG_RESOURCE = "module/broadcast/config.yml";
    private static final String LANG_RESOURCE = "module/broadcast/lang.yml";

    private Mutool plugin;
    private final BroadcastLang lang = new BroadcastLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public BroadcastModule() {
    }

    @Override
    public String id() {
        return "broadcast";
    }

    /** Release default resources, load config/lang, then register the bus handler + command. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);

        this.applyState();
    }

    /** Reload config + lang from disk and re-apply the enable state / (re)register handlers. */
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

    /** Unregister the bus handler + command and drop state. Called on plugin disable. */
    @Override
    public void unload() {
        this.unregisterCommand();
        this.unregisterBusHandler();
        this.enabled = false;
    }

    // --- internals ---

    private void applyState() {
        List<String> aliases = this.resolveAliases();
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterCommand();
        this.unregisterBusHandler();
        if (nowEnabled) {
            this.registerBusHandler();
            this.registerCommand(aliases);
        }
        this.enabled = nowEnabled && this.command != null;

        if (!nowEnabled) {
            IOKt.info("[Mutool/Broadcast] 广播模块已关闭 (enable: false)");
        }
    }

    // --- cross-server bus ---

    /** Register the receive-side handler (idempotent — re-registering a type replaces it). */
    private void registerBusHandler() {
        MessageBus bus = Mutool.getMessageBus();
        if (bus != null) {
            bus.register(BUS_TYPE, this::handleIncoming);
        }
    }

    private void unregisterBusHandler() {
        MessageBus bus = Mutool.getMessageBus();
        if (bus != null) {
            bus.unregister(BUS_TYPE);
        }
    }

    /**
     * Receive-side handler: runs on the calling thread for a local {@code send()} and on
     * lettuce's netty thread for a remote message. Reads the raw {@code message} field,
     * renders it through the lang layer and broadcasts the resulting Adventure
     * {@link Component} to the whole server.
     */
    private void handleIncoming(@NotNull JsonObject data) {
        if (data == null || !data.has(KEY_MESSAGE) || data.get(KEY_MESSAGE).isJsonNull()) {
            return;
        }
        String raw = data.get(KEY_MESSAGE).getAsString();
        Bukkit.getServer().broadcast(this.lang.render(raw));
    }

    /**
     * Dispatch a broadcast over the bus: the local pass renders immediately on this server,
     * then the payload is published for the other servers (no-op when Redis is disabled).
     */
    private void broadcast(String rawMessage) {
        MessageBus bus = Mutool.getMessageBus();
        String raw = rawMessage == null ? "" : rawMessage;
        if (bus == null) {
            // No bus at all (should not happen after onEnable) — render locally so the
            // announcement still goes out instead of silently vanishing.
            Bukkit.getServer().broadcast(this.lang.render(raw));
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty(KEY_MESSAGE, raw);
        bus.send(BUS_TYPE, payload);
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
            aliases.add("broadcast");
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

            BroadcastExecutor executor = new BroadcastExecutor(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Broadcast] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Broadcast] 注册 /broadcast 命令失败: " + throwable);
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

    /** Executor for {@code /broadcast <消息>}. Accepts both players and the console. */
    private static final class BroadcastExecutor implements TabExecutor {

        private final BroadcastModule module;

        private BroadcastExecutor(BroadcastModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (args.length == 0) {
                sender.sendMessage(this.module.lang.usageError(label));
                return true;
            }

            String message = String.join(" ", args);
            this.module.broadcast(message);
            sender.sendMessage(this.module.lang.message());
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            // Free-form announcement text — nothing sensible to complete.
            return new ArrayList<>();
        }
    }
}
