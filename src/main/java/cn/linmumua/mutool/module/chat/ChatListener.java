package cn.linmumua.mutool.module.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Set;

/**
 * Chat pipeline for the {@code chat} module: anti-delay, anti-spam, colour / MiniMessage gating,
 * {@code @mention} highlighting and per-group chat formatting.
 *
 * <p>Registered on Paper's {@link AsyncChatEvent}. The rendered message component is attached via
 * {@link AsyncChatEvent#message(Component)} and a {@link io.papermc.paper.chat.ChatRenderer} is set
 * to wrap it in the sender's format group. This keeps vanilla delivery (all viewers + console) and
 * respects any other plugins that already limit the viewer set.</p>
 */
final class ChatListener implements Listener {

    /** Bypass permissions (single-server port keeps the source node names under the mutool namespace). */
    private static final String BYPASS_DELAY = "mutool.bypass.chat.delay";
    private static final String BYPASS_SPAM = "mutool.bypass.chat.spam";

    private final ChatModule module;

    ChatListener(ChatModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        ChatConfig config = this.module.config();
        if (config == null) {
            return;
        }
        Player player = event.getPlayer();
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());

        // --- delay ---
        if (config.delayEnabled() && !player.hasPermission(BYPASS_DELAY)) {
            long remaining = this.module.remainingDelay(player.getUniqueId());
            if (remaining > 0L) {
                player.sendMessage(this.module.lang().delay((int) remaining));
                event.setCancelled(true);
                return;
            }
        }

        // --- spam ---
        if (config.spamEnabled() && !player.hasPermission(BYPASS_SPAM)
                && this.module.isSpam(player.getUniqueId(), raw)) {
            player.sendMessage(this.module.lang().spam());
            event.setCancelled(true);
            return;
        }

        this.module.putDelay(player.getUniqueId(), config.delaySeconds());
        this.module.putLastChat(player.getUniqueId(), raw);

        // --- render message + @mentions ---
        Component message = ChatService.renderMessage(player, raw);
        Set<String> atList = AtService.getAtList(player, raw, config);
        message = AtService.applyAt(message, atList, config, this.module.lang());

        event.message(message);
        event.renderer((source, sourceDisplayName, rendered, viewer) ->
                ChatService.applyFormat(player, rendered, config));

        AtService.notifyTitles(player.getName(), atList, this.module.lang());
    }
}
