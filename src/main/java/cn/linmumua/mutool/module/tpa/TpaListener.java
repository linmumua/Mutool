package cn.linmumua.mutool.module.tpa;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Cleans up a player's pending teleport requests and cooldown when they leave the server, so a
 * stale request never lingers (as either the requester or the target).
 *
 * <p>Created and registered by {@link TpaModule} only while the module is enabled, and removed
 * again through {@link org.bukkit.event.HandlerList#unregisterAll}.</p>
 */
public final class TpaListener implements Listener {

    private final TpaModule module;

    TpaListener(@NotNull TpaModule module) {
        this.module = module;
    }

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.module.forget(player);
    }
}
