package cn.linmumua.mutool.redis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Unified cross-server message dispatcher built on top of {@link RedisManager}.
 *
 * <p>Messages are multiplexed on a single channel ({@link RedisSettings#busChannel()}) and
 * carry an envelope:</p>
 * <pre>{@code {"type":"chat","source":"<server_name>","data":{ ... }}}</pre>
 *
 * <p>Delivery model:</p>
 * <ul>
 *   <li>{@link #send} first invokes the local handler for {@code type} (immediate, same
 *       server), then publishes the envelope to Redis.</li>
 *   <li>When an envelope arrives from Redis, its {@code source} is compared with this
 *       server's {@code server_name}; a match is dropped so the origin server never
 *       processes its own message twice.</li>
 *   <li>When Redis is disabled/unreachable the publish half is silently skipped and the
 *       bus behaves exactly like a local event dispatcher — no errors, no blocking.</li>
 * </ul>
 *
 * <p>Handlers run on the calling thread for local dispatch, and on lettuce's netty thread
 * for remote messages; handlers that touch the Bukkit world should hop to the main thread
 * themselves.</p>
 */
public final class MessageBus {

    static final String KEY_TYPE = "type";
    static final String KEY_SOURCE = "source";
    static final String KEY_DATA = "data";

    private static final Gson GSON = new Gson();

    private final RedisManager redis;
    private final RedisSettings settings;

    private final Map<String, Consumer<JsonObject>> handlers = new ConcurrentHashMap<>();

    public MessageBus(@Nullable RedisManager redis, @Nullable RedisSettings settings) {
        this.redis = redis;
        this.settings = settings;
        if (this.redis != null && this.redis.isEnabled() && this.settings != null) {
            this.redis.subscribe(this.settings.busChannel(), this::onRawMessage);
        }
    }

    /** A fully-local bus (no Redis). Used as the guaranteed fallback when init fails. */
    @NotNull
    public static MessageBus localOnly() {
        return new MessageBus(null, null);
    }

    /**
     * Register the handler invoked (locally and for remote messages of the same type) with
     * the {@code data} object. Re-registering a type replaces its previous handler.
     */
    public void register(@NotNull String type, @NotNull Consumer<JsonObject> localHandler) {
        this.handlers.put(type, localHandler);
    }

    /** Remove a handler. */
    public void unregister(@NotNull String type) {
        this.handlers.remove(type);
    }

    /**
     * Process a message locally, then publish it to the other servers. The local pass runs
     * synchronously so behavior is identical whether or not Redis is enabled.
     */
    public void send(@NotNull String type, @NotNull JsonObject payload) {
        dispatchLocal(type, payload);

        if (this.redis == null || !this.redis.isEnabled() || this.settings == null) {
            return;
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty(KEY_TYPE, type);
        envelope.addProperty(KEY_SOURCE, this.settings.getServerName());
        envelope.add(KEY_DATA, payload);
        this.redis.publish(this.settings.busChannel(), GSON.toJson(envelope));
    }

    private void dispatchLocal(String type, JsonObject data) {
        Consumer<JsonObject> handler = this.handlers.get(type);
        if (handler == null) {
            return;
        }
        try {
            handler.accept(data);
        } catch (Throwable throwable) {
            // A misbehaving handler must not break the bus or the sender.
            java.util.logging.Logger.getLogger("Mutool").warning(
                    "[Mutool/MessageBus] 处理消息失败 type=" + type + ": " + throwable.getMessage());
        }
    }

    private void onRawMessage(String raw) {
        try {
            JsonObject envelope = JsonParser.parseString(raw).getAsJsonObject();
            String source = envelope.has(KEY_SOURCE) && !envelope.get(KEY_SOURCE).isJsonNull()
                    ? envelope.get(KEY_SOURCE).getAsString() : null;

            // Drop our own messages: this server already handled them in send().
            if (this.settings != null && this.settings.getServerName().equals(source)) {
                return;
            }

            String type = envelope.has(KEY_TYPE) && !envelope.get(KEY_TYPE).isJsonNull()
                    ? envelope.get(KEY_TYPE).getAsString() : null;
            if (type == null) {
                return;
            }

            JsonObject data = envelope.has(KEY_DATA) && envelope.get(KEY_DATA).isJsonObject()
                    ? envelope.getAsJsonObject(KEY_DATA) : new JsonObject();

            dispatchLocal(type, data);
        } catch (Throwable throwable) {
            java.util.logging.Logger.getLogger("Mutool").warning(
                    "[Mutool/MessageBus] 解析跨服消息失败: " + throwable.getMessage());
        }
    }
}
