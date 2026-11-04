package cn.linmumua.mutool.module.chat;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import cn.linmumua.mutool.redis.MessageBus;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat module — the TabooLib port of {@code MumuTools} {@code module_chat}.
 *
 * <p>Owns the module switch, loads {@code module/chat/config.yml} &amp; {@code module/chat/lang.yml}
 * through TabooLib's {@link Configuration}, and wires up:</p>
 * <ul>
 *   <li>the {@link ChatListener} (format groups, anti-delay, anti-spam, colour/MiniMessage gating,
 *       {@code @mention} highlight + title) on Paper's {@code AsyncChatEvent};</li>
 *   <li>the {@code /msg} &amp; {@code /reply} commands through Bukkit's {@code CommandMap};</li>
 *   <li>a {@code chat_private} handler on the cross-server {@link MessageBus}.</li>
 * </ul>
 *
 * <p>Delay / last-message / reply caches are intentionally in-memory
 * {@link ConcurrentHashMap}s — no external cache dependency is pulled in. They reset on restart,
 * which matches the original per-process cache behaviour for a single server.</p>
 */
public final class ChatModule implements MutoolModule {

    private static final String CONFIG_RESOURCE = "module/chat/config.yml";
    private static final String LANG_RESOURCE = "module/chat/lang.yml";

    /** A message must not repeat within this window to count as spam (milliseconds). */
    private static final long SPAM_WINDOW_MS = 60_000L;

    private Mutool plugin;
    private final ChatLang lang = new ChatLang();
    private PrivateMessageService privateService;
    private ChatCommands commands;
    private ChatListener listener;

    private Configuration config;
    private volatile ChatConfig chatConfig;
    private boolean enabled;

    private boolean busHandlerRegistered;

    private final Map<UUID, Long> delayUntil = new ConcurrentHashMap<>();
    private final Map<UUID, LastMessage> lastChat = new ConcurrentHashMap<>();

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public ChatModule() {
    }

    @Override
    public String id() {
        return "chat";
    }

    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);

        this.privateService = new PrivateMessageService(this);
        this.commands = new ChatCommands(this);
        this.listener = new ChatListener(this);

        this.applyState();
    }

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

    @Override
    public void unload() {
        this.unregisterListener();
        if (this.commands != null) {
            this.commands.unregisterAll();
        }
        this.unregisterBus();
        if (this.privateService != null) {
            this.privateService.clear();
        }
        this.delayUntil.clear();
        this.lastChat.clear();
        this.enabled = false;
    }

    // --- state ---

    private void applyState() {
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterListener();
        if (this.commands != null) {
            this.commands.unregisterAll();
        }
        this.unregisterBus();

        this.chatConfig = ChatConfig.load(this.config);

        if (nowEnabled) {
            this.registerListener();
            if (this.commands != null) {
                this.commands.registerAll();
            }
            this.registerBus();
        } else {
            IOKt.info("[Mutool/Chat] 聊天模块已关闭 (enable: false)");
        }
        this.enabled = nowEnabled;
    }

    private void registerListener() {
        if (this.listener == null) {
            this.listener = new ChatListener(this);
        }
        Bukkit.getPluginManager().registerEvents(this.listener, this.plugin);
    }

    private void unregisterListener() {
        if (this.listener != null) {
            try {
                HandlerList.unregisterAll(this.listener);
            } catch (Throwable ignored) {
            }
        }
    }

    private void registerBus() {
        MessageBus bus = Mutool.getMessageBus();
        if (bus == null || this.privateService == null) {
            return;
        }
        bus.register(PrivateMessageService.BUS_TYPE, this.privateService::onBusMessage);
        this.busHandlerRegistered = true;
    }

    private void unregisterBus() {
        if (!this.busHandlerRegistered) {
            return;
        }
        MessageBus bus = Mutool.getMessageBus();
        if (bus != null) {
            bus.unregister(PrivateMessageService.BUS_TYPE);
        }
        this.busHandlerRegistered = false;
    }

    // --- delay / spam caches ---

    long remainingDelay(UUID player) {
        Long until = this.delayUntil.get(player);
        if (until == null) {
            return 0L;
        }
        long remaining = (until - System.currentTimeMillis() + 999L) / 1000L;
        if (remaining <= 0L) {
            this.delayUntil.remove(player);
            return 0L;
        }
        return remaining;
    }

    void putDelay(UUID player, int seconds) {
        long window = Math.max(seconds, 0) * 1000L;
        this.delayUntil.put(player, System.currentTimeMillis() + window);
    }

    boolean isSpam(UUID player, String message) {
        LastMessage last = this.lastChat.get(player);
        if (last == null) {
            return false;
        }
        return last.text().equals(message) && (System.currentTimeMillis() - last.at()) < SPAM_WINDOW_MS;
    }

    void putLastChat(UUID player, String message) {
        this.lastChat.put(player, new LastMessage(message, System.currentTimeMillis()));
    }

    // --- package accessors for collaborators ---

    Mutool plugin() {
        return this.plugin;
    }

    ChatLang lang() {
        return this.lang;
    }

    ChatConfig config() {
        return this.chatConfig;
    }

    PrivateMessageService privateService() {
        return this.privateService;
    }

    private record LastMessage(String text, long at) {
    }
}
