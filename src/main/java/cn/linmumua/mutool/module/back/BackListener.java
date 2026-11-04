package cn.linmumua.mutool.module.back;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * Records the two back-relevant events into the {@code mumutools_back} table:
 *
 * <ul>
 *   <li>{@link PlayerTeleportEvent} → the world/position the player is leaving
 *       ({@link PlayerTeleportEvent#getFrom()}), stored as type {@code teleport};</li>
 *   <li>{@link PlayerDeathEvent} → the death position, stored as type {@code death}.</li>
 * </ul>
 *
 * <p>The listener is created and registered by {@link BackModule} only while the module is
 * enabled, and removed again through {@link org.bukkit.event.HandlerList#unregisterAll}.
 * Each handler still double-checks the config toggles ({@code save.teleport} /
 * {@code save.death}).</p>
 */
public final class BackListener implements Listener {

    private final BackModule module;

    BackListener(@NotNull BackModule module) {
        this.module = module;
    }

    @EventHandler
    public void onPlayerTeleport(@NotNull PlayerTeleportEvent event) {
        if (!this.module.isEnabled() || !this.module.saveTeleport()) {
            return;
        }
        Location from = event.getFrom();
        if (from == null || from.getWorld() == null) {
            return;
        }
        save(event.getPlayer(), BackModule.TYPE_TELEPORT, from);
    }

    @EventHandler
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!this.module.isEnabled() || !this.module.saveDeath()) {
            return;
        }
        if (this.module.respawnMessage()) {
            player.sendMessage(this.module.lang().respawnMessage());
        }
        save(player, BackModule.TYPE_DEATH, player.getLocation());
    }

    private void save(@NotNull Player player, @NotNull String type, @NotNull Location location) {
        try {
            this.module.addRecord(player, type, location);
        } catch (Throwable throwable) {
            taboolib.common.platform.function.IOKt.severe(
                    "[Mutool/Back] 记录 " + type + " 位置失败: " + throwable);
        }
    }
}
