package cn.linmumua.mutool.module.pvp;

import cn.linmumua.mutool.database.Dao;
import cn.linmumua.mutool.database.DatabaseManager;
import cn.linmumua.mutool.database.entity.PvpStatus;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.UUID;

/**
 * Cancels player-vs-player damage unless <em>both</em> sides have PVP enabled.
 *
 * <p>This is the direct port of {@code MumuTools} {@code module_pvp}
 * {@code listener/PlayerDamage}: it runs at {@link EventPriority#LOWEST} (and only for
 * events not already cancelled) so other plugins can still override it afterwards.
 * The enabling flag is read from the {@code mumutools_pvp} table; a player without a
 * stored row is treated as {@link PvpModule#DEFAULT_PVP} (the original module's
 * {@code default: true}) without persisting a row on every hit.</p>
 *
 * <p>Damage events are dispatched on the region thread that owns the victim, so the
 * read-only ORMLite lookup here runs on that thread. On failure the event is left
 * untouched (damage proceeds) rather than blocking combat.</p>
 */
public final class PvpDamageListener implements Listener {

    private final PvpModule module;

    public PvpDamageListener(PvpModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player damager)) return;
        if (!(event.getEntity() instanceof Player target)) return;

        DatabaseManager database = this.module.databaseManager();
        if (database == null) return; // 数据层不可用时不干预战斗

        Dao<PvpStatus, UUID> dao = database.dao(PvpStatus.class);
        if (!this.isEnabled(dao, damager.getUniqueId()) || !this.isEnabled(dao, target.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Read a player's PVP flag; a missing row falls back to the module default. */
    private boolean isEnabled(Dao<PvpStatus, UUID> dao, UUID uuid) {
        try {
            PvpStatus status = dao.getById(uuid);
            return status == null ? PvpModule.DEFAULT_PVP : status.isEnable();
        } catch (Throwable throwable) {
            // 查询失败：不干预战斗，避免误伤正常 PVP
            return true;
        }
    }
}
