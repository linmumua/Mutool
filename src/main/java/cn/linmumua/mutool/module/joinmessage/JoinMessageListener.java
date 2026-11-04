package cn.linmumua.mutool.module.joinmessage;

import cn.linmumua.mutool.placeholder.PlaceholderService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * {@link PlayerJoinEvent} listener for the join-message module.
 *
 * <p>Runs at {@link EventPriority#LOWEST} so other plugins can still override the message
 * afterwards, exactly as the original {@code MumuTools} implementation did. All decision
 * state (enable switch, {@code remove_message}, parsed groups) lives on the owning
 * {@link JoinMessageModule} and is re-read on every reload.</p>
 */
public final class JoinMessageListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JoinMessageModule module;

    public JoinMessageListener(JoinMessageModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // remove_message: true → suppress the join message entirely.
        if (this.module.removeMessage()) {
            event.joinMessage(null);
            return;
        }

        // Highest-weight group whose conditions all pass; null when none match.
        JoinMessageModule.Group group = this.module.selectGroup(player);
        if (group == null) {
            event.joinMessage(null);
            return;
        }

        // Resolve placeholders (built-in {player} etc. + PlaceholderAPI), then MiniMessage.
        String resolved = PlaceholderService.parse(player, group.message());
        Component component = MINI.deserialize(resolved == null ? "" : resolved);
        event.joinMessage(component);
    }
}
