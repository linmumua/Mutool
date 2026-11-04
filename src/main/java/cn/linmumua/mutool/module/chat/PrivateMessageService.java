package cn.linmumua.mutool.module.chat;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.redis.MessageBus;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private-message service backing {@code /msg} and {@code /reply}.
 *
 * <p>Delivery is local-first: if the target is online on this server the receive line is sent
 * directly; otherwise the pre-rendered receive line is serialized and published on the cross-server
 * {@link MessageBus} under the {@code chat_private} type. The bus also invokes the registered local
 * handler for {@code chat_private}, but because we only publish when the target is <em>not</em>
 * local, that local pass is a no-op and the target receives the message exactly once.</p>
 */
final class PrivateMessageService {

    /** MessageBus message type for cross-server private messages. */
    static final String BUS_TYPE = "chat_private";

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private final ChatModule module;
    /** Bidirectional reply map: sender -> last target, and target -> last sender. */
    private final Map<String, String> replyTargets = new ConcurrentHashMap<>();

    PrivateMessageService(ChatModule module) {
        this.module = module;
    }

    /** The name this sender should reply to, or {@code null} when nothing to reply to. */
    String replyTarget(String sender) {
        return this.replyTargets.get(sender);
    }

    /** Send {@code rawMessage} from {@code sender} to {@code target}. */
    void sendMsg(CommandSender sender, String target, String rawMessage) {
        String clean = ChatService.sanitize(sender, rawMessage);
        Component message = ChatService.renderMessage(sender, clean);
        ChatLang lang = this.module.lang();

        // Feedback to the sender.
        sender.sendMessage(lang.msgSend(sender.getName(), target, message));
        bind(sender.getName(), target);

        Component receive = lang.msgReceive(sender.getName(), target, message);

        Player local = Bukkit.getPlayerExact(target);
        if (local != null && local.isOnline()) {
            local.sendMessage(receive);
            return;
        }

        // Not online here — hand off to the cross-server bus.
        MessageBus bus = Mutool.getMessageBus();
        if (bus == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("target", target);
        payload.addProperty("message", GSON.serialize(receive));
        bus.send(BUS_TYPE, payload);
    }

    /** Handle an inbound cross-server private message (registered on the bus as {@code chat_private}). */
    void onBusMessage(JsonObject data) {
        if (data == null || !data.has("target") || !data.has("message")) {
            return;
        }
        String target = data.get("target").getAsString();
        String json = data.get("message").getAsString();

        Player player = Bukkit.getPlayerExact(target);
        if (player == null) {
            return;
        }
        try {
            Component component = GSON.deserialize(json);
            player.sendMessage(component);
        } catch (Throwable ignored) {
            // Malformed payload — drop silently rather than break the bus.
        }
    }

    private void bind(String sender, String target) {
        this.replyTargets.put(sender, target);
        this.replyTargets.put(target, sender);
    }

    /** Drop all in-memory reply state (called on unload). */
    void clear() {
        this.replyTargets.clear();
    }
}
