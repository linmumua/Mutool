package cn.linmumua.mutool.menu;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A single slot in a {@link Menu}: the icon rendered there plus an optional click handler.
 *
 * <p>The {@link ItemStack} is only a <em>template</em> — {@link Menu} copies it into the
 * freshly built inventory on each {@link Menu#open(org.bukkit.entity.Player)}. The click
 * handler receives the raw {@link InventoryClickEvent} after the event has already been
 * cancelled by {@link MenuListener}, so it can freely read the player / clicked slot but
 * does not need to worry about item theft.</p>
 *
 * @param itemStack    the icon shown in this slot (never {@code null})
 * @param clickHandler the click callback, or {@code null} when the slot is display-only
 */
public record MenuItem(@NotNull ItemStack itemStack, @Nullable Consumer<InventoryClickEvent> clickHandler) {

    /** A display-only item with no click behaviour. */
    public MenuItem(@NotNull ItemStack itemStack) {
        this(itemStack, null);
    }

    /** Invoke the click handler, if any. Safe to call with a {@code null} handler. */
    public void onClick(@NotNull InventoryClickEvent event) {
        if (this.clickHandler != null) {
            this.clickHandler.accept(event);
        }
    }
}
