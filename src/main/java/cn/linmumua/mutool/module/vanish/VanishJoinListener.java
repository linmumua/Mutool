package cn.linmumua.mutool.module.vanish;

import cn.linmumua.mutool.Mutool;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import taboolib.platform.util.FoliaExecutorKt;

import java.util.Set;
import java.util.UUID;

/**
 * Re-applies vanish visibility when a player joins the server.
 *
 * <p>This is the direct port of {@code MumuTools} {@code module_vanish}
 * {@code listener/PlayerJoin}. On join it does two things, both mirroring the original:</p>
 * <ul>
 *   <li>If the <em>joining</em> player is themself vanished, re-show the {@code 隐身中}
 *       BossBar to them and hide them from every other online player
 *       ({@code viewer.hidePlayer(plugin, joiner)}).</li>
 *   <li>Hide every other vanished player from the joining player
 *       ({@code joiner.hidePlayer(plugin, vanished)}).</li>
 * </ul>
 *
 * <p><b>Folia:</b> {@code hidePlayer}/{@code showPlayer} mutate the <em>viewer's</em>
 * entity-tracking state, so every such call is dispatched onto the viewer's own region
 * thread via {@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)}. The
 * BossBar update runs inline on the joining player's thread (the {@link PlayerJoinEvent}
 * is already fired on the region thread that owns the player).</p>
 */
public final class VanishJoinListener implements Listener {

    private final VanishModule module;

    public VanishJoinListener(VanishModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Mutool plugin = this.module.plugin();
        if (plugin == null) {
            return;
        }

        Player player = event.getPlayer();
        Set<UUID> hiddenPlayerIds = this.module.hiddenPlayerIds();
        if (hiddenPlayerIds.isEmpty()) {
            return;
        }

        // Case 1: the joining player is themself vanished — re-show the bossbar and hide
        // them from everyone already online.
        if (hiddenPlayerIds.contains(player.getUniqueId())) {
            if (this.module.isBossBarEnabled()) {
                BossBar bossBar = this.module.bossBar();
                if (bossBar != null) {
                    player.showBossBar(bossBar);
                }
            }
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(player)) {
                    continue;
                }
                FoliaExecutorKt.runTask(viewer, () -> viewer.hidePlayer(plugin, player));
            }
        }

        // Case 2: hide every other vanished player from the joining player.
        for (UUID uuid : hiddenPlayerIds) {
            if (uuid.equals(player.getUniqueId())) {
                continue;
            }
            Player target = Bukkit.getPlayer(uuid);
            if (target == null) {
                continue;
            }
            FoliaExecutorKt.runTask(player, () -> player.hidePlayer(plugin, target));
        }
    }
}
