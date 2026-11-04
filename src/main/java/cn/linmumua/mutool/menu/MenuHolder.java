package cn.linmumua.mutool.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link InventoryHolder} attached to every inventory built by {@link Menu}.
 *
 * <p>{@link MenuListener} uses {@code event.getInventory().getHolder() instanceof MenuHolder}
 * to recognise <em>our</em> menus and route the click back to the owning {@link Menu}. Any
 * inventory whose holder is not a {@code MenuHolder} is left untouched by the framework.</p>
 */
public final class MenuHolder implements InventoryHolder {

    private final Menu menu;
    private Inventory inventory;

    public MenuHolder(@NotNull Menu menu) {
        this.menu = menu;
    }

    /** The menu that owns this inventory. */
    @NotNull
    public Menu getMenu() {
        return this.menu;
    }

    /** Set once the inventory has been created — used to satisfy {@link #getInventory()}. */
    void setInventory(@Nullable Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return this.inventory;
    }
}
