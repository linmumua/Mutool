package cn.linmumua.mutool.redis.message;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.redis.MessageBus;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Example cross-server message: deliver an Adventure {@link Component} to a player by name,
 * resolved on whichever server that player is currently connected to.
 *
 * <p>Usage: call {@link #registerHandler()} once during enable, then from any module build a
 * {@link PlayerMessage} and call {@link #send()}. The originating server processes it
 * immediately; other servers receive it via Redis and resolve the player locally.</p>
 *
 * <p>The receive handler runs on lettuce's netty thread — sending a chat message from off
 * the main thread is safe on Paper; world-state mutations should hop to the main thread.</p>
 */
public final class PlayerMessage {

    public static final String TYPE = MessageTypes.PLAYER_MESSAGE;

    private final String target;
    private final String messageJson;

    public PlayerMessage(@NotNull String target, @NotNull String messageJson) {
        this.target = target;
        this.messageJson = messageJson;
    }

    public PlayerMessage(@NotNull String target, @NotNull Component message) {
        this(target, GsonComponentSerializer.gson().serialize(message));
    }

    @NotNull
    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("target", this.target);
        object.addProperty("message", this.messageJson);
        return object;
    }

    /** Send over the bus: handled locally now and on the other servers via Redis. */
    public void send() {
        MessageBus bus = Mutool.getMessageBus();
        if (bus != null) {
            bus.send(TYPE, toJson());
        }
    }

    /** Register the receiving side. Call once during plugin/module enable. */
    public static void registerHandler() {
        MessageBus bus = Mutool.getMessageBus();
        if (bus != null) {
            bus.register(TYPE, PlayerMessage::handle);
        }
    }

    private static void handle(JsonObject data) {
        if (data == null || !data.has("target") || !data.has("message")) {
            return;
        }
        String target = data.get("target").getAsString();
        String messageJson = data.get("message").getAsString();
        Player player = Bukkit.getPlayerExact(target);
        if (player == null) {
            return;
        }
        player.sendMessage(GsonComponentSerializer.gson().deserialize(messageJson));
    }
}
