package cn.linmumua.mutool.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Singleton owner of the lettuce connections used for cross-server messaging.
 *
 * <p>Two connections are used, matching Redis' pub/sub semantics (a RESP2 connection that
 * has {@code SUBSCRIBE}d cannot also issue {@code PUBLISH}):</p>
 * <ul>
 *   <li>a <b>pub/sub connection</b> ({@link RedisPubSubCommands}) for subscribing and
 *       receiving messages, and</li>
 *   <li>a <b>regular connection</b> ({@link RedisCommands}) used only for publishing.</li>
 * </ul>
 *
 * <p>Deliberately tolerant of a missing/dead Redis: when {@link RedisSettings#isEnable()}
 * is {@code false}, or when the connection cannot be established, every method degrades
 * to a silent no-op ({@link #isEnabled()} returns {@code false}) instead of throwing —
 * so the plugin keeps starting and {@link MessageBus} keeps delivering locally.</p>
 *
 * <p>Lettuce dispatches incoming messages on its own netty event-loop threads; handlers run
 * there. Lifecycle: {@link #initialize} builds the singleton, {@link #connect()} opens the
 * connections, {@link #close()} tears them down.</p>
 */
public final class RedisManager {

    private static volatile RedisManager instance;

    private final RedisSettings settings;
    private final Logger logger;

    private volatile boolean enabled;

    private RedisClient client;
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private RedisPubSubCommands<String, String> subCommands;
    private StatefulRedisConnection<String, String> publishConnection;
    private RedisCommands<String, String> publishCommands;

    /** channel -> handlers. Channels with at least one handler are subscribed at protocol level. */
    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();
    /** Channels currently subscribed at the Redis protocol level (guards double SUBSCRIBE). */
    private final Set<String> activeSubscriptions = ConcurrentHashMap.newKeySet();

    private RedisManager(@NotNull RedisSettings settings, @NotNull Logger logger) {
        this.settings = settings;
        this.logger = logger;
    }

    /**
     * Create (once) and return the process-wide singleton.
     *
     * @param settings redis.yml view
     * @param logger   plugin logger
     */
    @NotNull
    public static synchronized RedisManager initialize(@NotNull RedisSettings settings, @NotNull Logger logger) {
        if (instance == null) {
            instance = new RedisManager(settings, logger);
        }
        return instance;
    }

    /** The singleton, or {@code null} before {@link #initialize}. */
    @Nullable
    public static RedisManager getInstance() {
        return instance;
    }

    // ---- lifecycle ----

    /**
     * Open the connections when enabled. Any failure is caught and logged, leaving the
     * manager disabled (local-only) rather than propagating — never blocks startup.
     */
    public synchronized void connect() {
        if (!this.settings.isEnable()) {
            this.enabled = false;
            this.logger.info("[Mutool/Redis] 未启用 (enable: false)，跨服消息将降级为本地直发。");
            return;
        }
        if (this.client != null) {
            return;
        }
        try {
            RedisURI.Builder builder = RedisURI.builder()
                    .withHost(this.settings.getHost())
                    .withPort(this.settings.getPort())
                    .withTimeout(Duration.ofSeconds(5));
            String password = this.settings.getPassword();
            if (password != null && !password.isBlank()) {
                builder.withPassword(password.toCharArray());
            }

            this.client = RedisClient.create(builder.build());

            // Subscribe/receive side.
            this.pubSubConnection = this.client.connectPubSub();
            this.pubSubConnection.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    dispatch(channel, message);
                }
            });
            this.subCommands = this.pubSubConnection.sync();

            // Publish side (a RESP2 subscribed connection may not PUBLISH).
            this.publishConnection = this.client.connect();
            this.publishCommands = this.publishConnection.sync();

            this.enabled = true;

            // Subscribe to any channels that were registered before connect().
            for (String channel : this.subscribers.keySet()) {
                ensureSubscribed(channel);
            }

            this.logger.info("[Mutool/Redis] 已连接 " + this.settings.getHost() + ":"
                    + this.settings.getPort() + " (server_name=" + this.settings.getServerName()
                    + ", channel=" + this.settings.busChannel() + ")");
        } catch (Throwable throwable) {
            this.enabled = false;
            this.logger.log(Level.WARNING,
                    "[Mutool/Redis] 连接 Redis 失败，跨服消息将降级为本地直发: " + throwable.getMessage(), throwable);
            closeQuietly();
        }
    }

    /** Whether a live Redis pub/sub connection is up. {@code false} ⇒ local-only mode. */
    public boolean isEnabled() {
        return this.enabled && this.pubSubConnection != null && this.publishConnection != null;
    }

    /**
     * Publish a payload to a channel. No-op (returns silently) when disabled so callers
     * never have to branch on Redis availability.
     */
    public void publish(@NotNull String channel, @NotNull String payload) {
        if (!isEnabled()) {
            return;
        }
        try {
            this.publishCommands.publish(channel, payload);
        } catch (Throwable throwable) {
            this.logger.log(Level.WARNING, "[Mutool/Redis] 发布消息失败 channel=" + channel, throwable);
        }
    }

    /**
     * Register a handler for a channel. Handlers run on lettuce's netty thread. If the
     * connection is already up the channel is subscribed immediately; otherwise it will be
     * subscribed during {@link #connect()}.
     */
    public void subscribe(@NotNull String channel, @NotNull Consumer<String> handler) {
        this.subscribers.computeIfAbsent(channel, key -> new CopyOnWriteArrayList<>()).add(handler);
        ensureSubscribed(channel);
    }

    /** Remove every handler bound to a channel and unsubscribe it at protocol level. */
    public void unsubscribe(@NotNull String channel) {
        this.subscribers.remove(channel);
        if (isEnabled() && this.activeSubscriptions.remove(channel)) {
            try {
                this.subCommands.unsubscribe(channel);
            } catch (Throwable throwable) {
                this.logger.log(Level.WARNING, "[Mutool/Redis] 取消订阅失败 channel=" + channel, throwable);
            }
        }
    }

    public synchronized void close() {
        this.enabled = false;
        this.activeSubscriptions.clear();
        closeQuietly();
    }

    // ---- internals ----

    private synchronized void ensureSubscribed(String channel) {
        if (!isEnabled()) {
            return;
        }
        if (!this.activeSubscriptions.add(channel)) {
            return;
        }
        try {
            this.subCommands.subscribe(channel);
        } catch (Throwable throwable) {
            this.activeSubscriptions.remove(channel);
            this.logger.log(Level.WARNING, "[Mutool/Redis] 订阅频道失败 channel=" + channel, throwable);
        }
    }

    private void dispatch(String channel, String message) {
        List<Consumer<String>> handlers = this.subscribers.get(channel);
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        for (Consumer<String> handler : handlers) {
            try {
                handler.accept(message);
            } catch (Throwable throwable) {
                this.logger.log(Level.WARNING, "[Mutool/Redis] 处理消息失败 channel=" + channel, throwable);
            }
        }
    }

    private void closeQuietly() {
        if (this.pubSubConnection != null) {
            try {
                this.pubSubConnection.close();
            } catch (Throwable throwable) {
                this.logger.log(Level.WARNING, "[Mutool/Redis] 关闭 pub/sub 连接失败", throwable);
            }
            this.pubSubConnection = null;
        }
        this.subCommands = null;
        if (this.publishConnection != null) {
            try {
                this.publishConnection.close();
            } catch (Throwable throwable) {
                this.logger.log(Level.WARNING, "[Mutool/Redis] 关闭发布连接失败", throwable);
            }
            this.publishConnection = null;
        }
        this.publishCommands = null;
        if (this.client != null) {
            try {
                this.client.shutdown();
            } catch (Throwable throwable) {
                this.logger.log(Level.WARNING, "[Mutool/Redis] 关闭 Redis 客户端失败", throwable);
            }
            this.client = null;
        }
    }
}
