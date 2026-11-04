package cn.linmumua.mutool.module.eventaction;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Generic {@link Listener} for the event-action module.
 *
 * <p>Covers exactly the event types the original {@code MumuTools} {@code module_eventaction}
 * supported. Each handler maps a Bukkit event to its configured Chinese event name and forwards
 * the involved {@link Player} to {@link EventActionModule#runAction(Player, String)}.</p>
 *
 * <p>All handlers use {@code ignoreCancelled = true} to match the original behaviour — an event
 * that another plugin already cancelled does not trigger actions.</p>
 */
public final class EventActionListener implements Listener {

    private final EventActionModule module;

    public EventActionListener(EventActionModule module) {
        this.module = module;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.module.runAction(event.getPlayer(), "玩家加入服务器");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.module.runAction(event.getPlayer(), "玩家退出服务器");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        this.module.runAction(event.getPlayer(), "玩家切换世界");
    }

    // AsyncPlayerChatEvent is deprecated on modern Paper but still fired for compatibility when a
    // listener is registered; using it mirrors the original module's "玩家聊天" semantics exactly.
    @SuppressWarnings("deprecation")
    @EventHandler(ignoreCancelled = true)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        this.module.runAction(event.getPlayer(), "玩家聊天");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        this.module.runAction(event.getPlayer(), "玩家死亡");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        this.module.runAction(event.getPlayer(), "玩家复活");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        this.module.runAction(event.getPlayer(), "玩家移动");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        this.module.runAction(event.getPlayer(), "玩家传送");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        this.module.runAction(event.getPlayer(), "玩家破坏方块");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        this.module.runAction(event.getPlayer(), "玩家放置方块");
    }
}
