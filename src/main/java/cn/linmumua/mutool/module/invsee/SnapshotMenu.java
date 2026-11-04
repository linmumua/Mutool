package cn.linmumua.mutool.module.invsee;

import cn.linmumua.mutool.menu.Menu;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * A read-only {@link Menu} that displays a pre-captured {@link ItemStack} snapshot.
 *
 * <p><strong>Folia note:</strong> the {@code contents} array passed to the constructor must have
 * been cloned from the target's inventory <em>on the target's region thread</em> (see
 * {@link InvseeModule}). This menu never touches the live target inventory again — {@link #build()}
 * merely re-reads the already-detached snapshot on the <em>viewer's</em> region thread, so no
 * cross-region inventory access can occur.</p>
 *
 * <p>The menu has no click handlers and the {@code MenuListener} cancels every click, so the
 * snapshot is strictly display-only — the viewer can look but never take or move an item.</p>
 */
final class SnapshotMenu extends Menu {

    private final ItemStack[] contents;

    SnapshotMenu(@NotNull Component title, int size, @NotNull ItemStack[] contents) {
        super(title, size);
        this.contents = contents;
    }

    @Override
    protected void build() {
        int limit = Math.min(this.contents.length, getSize());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = this.contents[slot];
            if (stack != null && !stack.getType().isAir()) {
                setItem(slot, stack);
            }
        }
    }
}
