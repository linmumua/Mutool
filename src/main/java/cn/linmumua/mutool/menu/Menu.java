package cn.linmumua.mutool.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import taboolib.platform.util.FoliaExecutorKt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight, Folia-safe inventory GUI base class used by the home / back / invsee style
 * modules.
 *
 * <p>A menu is a fixed {@link #getSize() size} inventory with a MiniMessage
 * {@link #getTitle() title} and a sparse map of {@link MenuItem}s keyed by slot. Subclasses
 * (or direct users) place items with {@link #setItem(int, MenuItem)} and open the menu with
 * {@link #open(Player)}.</p>
 *
 * <h2>Threading model</h2>
 * <p>Building an {@link Inventory} and calling {@link Player#openInventory(Inventory)} must
 * run on the owning player's region thread under Folia. {@link #open(Player)} therefore
 * wraps the whole build+open sequence in
 * {@link FoliaExecutorKt#runTask(Player, Runnable)}: the subclass hook {@link #build()} runs
 * inside that task, which means reading YAML, generating {@link ItemStack}s and rendering
 * lore are all guaranteed to happen on the region thread — never on a global scheduler
 * thread.</p>
 *
 * <p>This class intentionally does <strong>not</strong> implement {@link
 * org.bukkit.inventory.InventoryHolder}; that responsibility belongs to {@link MenuHolder} so
 * a single {@link Menu} instance can be rebuilt for repeated opens without leaking holder
 * state.</p>
 */
public class Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Component title;
    private int size;
    private final Map<Integer, MenuItem> items = new LinkedHashMap<>();

    /**
     * @param title the inventory title (rendered as-is; use MiniMessage when passing a
     *              pre-built {@link Component})
     * @param size  desired row-count * 9; normalised to a value in {@code [9, 54]} that is a
     *              multiple of 9
     */
    public Menu(@NotNull Component title, int size) {
        this.title = title;
        this.size = normalizeSize(size);
    }

    /** Convenience constructor: the title string is parsed with MiniMessage. */
    public Menu(@NotNull String miniMessageTitle, int size) {
        this(MINI.deserialize(miniMessageTitle), size);
    }

    /** Convenience constructor for the common single-row / fixed-size case. */
    public Menu(@NotNull String miniMessageTitle) {
        this(miniMessageTitle, 27);
    }

    // --- configuration ---

    public @NotNull Component getTitle() {
        return this.title;
    }

    public void setTitle(@NotNull Component title) {
        this.title = title;
    }

    public void setTitle(@NotNull String miniMessageTitle) {
        this.title = MINI.deserialize(miniMessageTitle);
    }

    public int getSize() {
        return this.size;
    }

    /** Resize the menu. Existing items outside the new bounds are dropped. */
    public void setSize(int size) {
        this.size = normalizeSize(size);
        this.items.keySet().removeIf(slot -> slot < 0 || slot >= this.size);
    }

    // --- item management ---

    /**
     * Place (or replace) the item at {@code slot}.
     *
     * @throws IllegalArgumentException if the slot is outside {@code [0, size)}
     */
    public void setItem(int slot, @NotNull MenuItem item) {
        if (slot < 0 || slot >= this.size) {
            throw new IllegalArgumentException(
                    "slot " + slot + " out of bounds for menu size " + this.size);
        }
        this.items.put(slot, item);
    }

    /** Place an item with no click handler. */
    public void setItem(int slot, @NotNull ItemStack itemStack) {
        setItem(slot, new MenuItem(itemStack));
    }

    /** Remove the item at {@code slot}; no-op when empty. */
    public void removeItem(int slot) {
        this.items.remove(slot);
    }

    /** Remove every item from the menu. */
    public void clearItems() {
        this.items.clear();
    }

    /** The item currently at {@code slot}, or {@code null}. */
    public @Nullable MenuItem getItem(int slot) {
        return this.items.get(slot);
    }

    /** Read-only view of the placed items. */
    public @NotNull Map<Integer, MenuItem> getItems() {
        return java.util.Collections.unmodifiableMap(this.items);
    }

    // --- open ---

    /**
     * Build and open this menu for {@code player}, Folia-safely.
     *
     * <p>The entire sequence runs inside {@link FoliaExecutorKt#runTask(Player, Runnable)} so
     * it executes on the player's region thread: {@link #build()} (subclass hook), inventory
     * creation and {@link Player#openInventory(Inventory)}.</p>
     */
    public void open(@NotNull Player player) {
        FoliaExecutorKt.runTask(player, () -> {
            build();
            Inventory inventory = buildInventory();
            player.openInventory(inventory);
        });
    }

    /**
     * Subclass hook invoked inside the Folia task immediately before the inventory is built.
     * Override to populate / refresh items (e.g. read YAML, compute pagination) on the region
     * thread. The default implementation does nothing.
     */
    protected void build() {
    }

    /**
     * Create the concrete {@link Inventory} for the current item map. Called on the region
     * thread from {@link #open(Player)}; only override when the inventory itself (not just
     * its contents) needs to differ.
     */
    protected @NotNull Inventory buildInventory() {
        MenuHolder holder = new MenuHolder(this);
        Inventory inventory = Bukkit.createInventory(holder, this.size, this.title);
        holder.setInventory(inventory);
        for (Map.Entry<Integer, MenuItem> entry : this.items.entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue().itemStack());
        }
        return inventory;
    }

    private static int normalizeSize(int requested) {
        int clamped = Math.max(9, Math.min(54, requested));
        // Round up to the next multiple of 9 (row).
        return ((clamped + 8) / 9) * 9;
    }
}
