package cn.linmumua.mutool.menu;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * Single global listener that drives every {@link Menu} built by this framework.
 *
 * <p>It is registered exactly once, on plugin enable, via {@link MenuManager#init} — feature
 * modules must <strong>not</strong> register it themselves. When a click lands in an inventory
 * whose holder is a {@link MenuHolder}, the framework cancels the event (so items can never be
 * taken out of a GUI) and dispatches to the owning {@link MenuItem}'s callback.</p>
 *
 * <p>Clicks in the player's own inventory (bottom half) while one of our menus is open are
 * also cancelled — this blocks shift-click item moving — but are not forwarded to any item
 * callback. Drag events over our menus are cancelled for the same reason.</p>
 */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        // event.getInventory() is the top inventory of the open view.
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) {
            return;
        }

        // Never let the player remove/insert items in our GUI.
        event.setCancelled(true);

        // Only dispatch when the click actually happened inside the menu itself, not in the
        // player's own inventory bottom half.
        if (event.getClickedInventory() != top) {
            return;
        }

        Menu menu = holder.getMenu();
        MenuItem item = menu.getItem(event.getRawSlot());
        if (item == null) {
            return;
        }
        try {
            item.onClick(event);
        } catch (Throwable throwable) {
            Bukkit.getLogger().log(Level.SEVERE,
                    "[Mutool/Menu] 菜单点击回调执行异常 (slot " + event.getRawSlot() + ")", throwable);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }
}
