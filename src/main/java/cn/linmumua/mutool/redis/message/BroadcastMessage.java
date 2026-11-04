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
 * Example cross-server message: broadcast an Adventure {@link Component} to every player on
 * every server (and the console).
 *
 * <p>Each receiving server renders the message to its own online players, so the broadcast
 * fans out across the whole network while the origin server is de-duplicated by
 * {@link MessageBus}. Register once via {@link #registerHandler()}.</p>
 */
public final class BroadcastMessage {

    public static final String TYPE = MessageTypes.BROADCAST;

    private final String messageJson;

    public BroadcastMessage(@NotNull String messageJson) {
        this.messageJson = messageJson;
    }

    public BroadcastMessage(@NotNull Component message) {
        this(GsonComponentSerializer.gson().serialize(message));
    }

    @NotNull
    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("message", this.messageJson);
        return object;
    }

    public void send() {
        MessageBus bus = Mutool.getMessageBus();
        if (bus != null) {
            bus.send(TYPE, toJson());
        }
    }

    public static void registerHandler() {
        MessageBus bus = Mutool.getMessageBus();
        if (bus != null) {
            bus.register(TYPE, BroadcastMessage::handle);
        }
    }

    private static void handle(JsonObject data) {
        if (data == null || !data.has("message")) {
            return;
        }
        Component message = GsonComponentSerializer.gson().deserialize(data.get("message").getAsString());
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }
}
