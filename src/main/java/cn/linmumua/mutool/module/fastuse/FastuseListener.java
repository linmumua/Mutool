package cn.linmumua.mutool.module.fastuse;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import taboolib.platform.util.FoliaExecutorKt;

import java.util.Objects;

/**
 * 快捷使用监听器 —— 忠实移植 {@code MumuTools} {@code module_fastuse} 的三个监听器
 * （{@code CraftingTable} / {@code EnderChest} / {@code ShulkerBox}），合并为一个监听器。
 *
 * <p>触发条件与原模块一致：<b>对空气右键</b>（{@link Action#RIGHT_CLICK_AIR}）且物品使用
 * 未被拒绝（{@code useItemInHand() != Event.Result.DENY}）。对空气触发可保证不会影响
 * “把这些方块正常放置到地面”的原版行为。</p>
 *
 * <p>三类物品：</p>
 * <ul>
 *   <li>工作台 → {@link Player#openWorkbench(org.bukkit.Location, boolean)}；</li>
 *   <li>末影箱 → {@link Player#openInventory(Inventory)} 打开玩家末影箱；</li>
 *   <li>潜影盒 → 打开一个可编辑的潜影盒界面，点击/关闭时把内容回写到手中物品，
 *       并阻止潜影盒嵌套。</li>
 * </ul>
 *
 * <p>所有涉及玩家状态的调用（打开界面、回写物品）都通过
 * {@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)} 调度到玩家所在区域线程，
 * 以兼容 Folia。</p>
 */
public final class FastuseListener implements Listener {

    private final FastuseModule module;

    public FastuseListener(FastuseModule module) {
        this.module = module;
    }

    // --- 对空气右键：工作台 / 末影箱 / 潜影盒 ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        Material type = item == null ? Material.AIR : item.getType();

        // 工作台
        if (type == Material.CRAFTING_TABLE) {
            if (!this.module.craftingTableEnabled()) {
                return;
            }
            if (!player.hasPermission(FastuseModule.PERMISSION_CRAFTING_TABLE)) {
                return;
            }
            FoliaExecutorKt.runTask(player, () -> player.openWorkbench(null, true));
            return;
        }

        // 末影箱
        if (type == Material.ENDER_CHEST) {
            if (!this.module.enderChestEnabled()) {
                return;
            }
            if (!player.hasPermission(FastuseModule.PERMISSION_ENDER_CHEST)) {
                return;
            }
            FoliaExecutorKt.runTask(player, () -> player.openInventory(player.getEnderChest()));
            return;
        }

        // 潜影盒（任意颜色的潜影盒物品均带 BlockStateMeta → ShulkerBox）
        if (isShulkerBox(item)) {
            if (!this.module.shulkerBoxEnabled()) {
                return;
            }
            if (!player.hasPermission(FastuseModule.PERMISSION_SHULKER_BOX)) {
                return;
            }
            this.openShulkerBox(player);
        }
    }

    /** 打开玩家手中潜影盒的内容界面（构建 + 打开都在玩家区域线程上完成）。 */
    private void openShulkerBox(Player player) {
        FoliaExecutorKt.runTask(player, () -> {
            ItemStack item = player.getInventory().getItemInMainHand();
            ShulkerBox box = getShulkerBox(item);
            if (box == null) {
                return;
            }

            Component title = getItemName(item);
            ShulkerBoxHolder holder = new ShulkerBoxHolder(player);
            Inventory inventory = Bukkit.createInventory(holder, InventoryType.SHULKER_BOX, title);
            holder.setInventory(inventory);
            inventory.setContents(box.getInventory().getContents());

            player.openInventory(inventory);
        });
    }

    // --- 潜影盒界面：点击 / 拖拽 / 关闭 ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShulkerBoxHolder holder)) {
            return;
        }

        // 防嵌套：点击槽位里的物品或光标上的物品是潜影盒时，禁止放入/取出。
        if (isShulkerBox(getClickItem(event)) || isShulkerBox(event.getCursor())) {
            event.setCancelled(true);
            return;
        }

        // 与该物品的潜影盒内容保持同步（关闭时还会再写一次，作为最终状态）。
        updateShulker(holder.player(), event.getInventory());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShulkerBoxHolder)) {
            return;
        }
        // 防嵌套：拖拽光标上的是潜影盒时禁止拖入。
        if (isShulkerBox(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShulkerBoxHolder holder)) {
            return;
        }
        updateShulker(holder.player(), event.getInventory());
    }

    // --- 物品工具（移植自源 common ItemStackUtil / MenuUtil） ---

    /** 读取物品携带的潜影盒方块状态；非潜影盒返回 {@code null}。 */
    private static @Nullable ShulkerBox getShulkerBox(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) {
            return null;
        }
        if (!(meta.getBlockState() instanceof ShulkerBox box)) {
            return null;
        }
        return box;
    }

    /** 判断物品是否为潜影盒。 */
    private static boolean isShulkerBox(@Nullable ItemStack item) {
        return getShulkerBox(item) != null;
    }

    /** 读取物品显示名称：优先自定义名称，其次物品名，最后取翻译键。 */
    private static @NotNull Component getItemName(@NotNull ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasCustomName()) {
                return Objects.requireNonNull(meta.customName());
            } else if (meta.hasItemName()) {
                return Objects.requireNonNull(meta.itemName());
            }
        }
        return Component.translatable(item.translationKey());
    }

    /**
     * 移植自源 {@code MenuUtil.getClickItem}：取出本次点击“涉及”的物品，用于嵌套判断。
     * 数字键交换（{@link ClickType#NUMBER_KEY}）时优先取目标槽物品，其次取对应快捷栏物品。
     */
    private static @Nullable ItemStack getClickItem(InventoryClickEvent event) {
        if (event.getClickedInventory() == null) {
            return null;
        }

        Player player = (Player) event.getWhoClicked();

        ItemStack currentItem = event.getCurrentItem();
        if (event.getClick() == ClickType.NUMBER_KEY) {
            if (currentItem != null) {
                return currentItem;
            }
            ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
            if (hotbarItem == null) {
                return event.getClickedInventory().getItem(event.getRawSlot());
            }
            return hotbarItem;
        }

        return currentItem;
    }

    /**
     * 把潜影盒界面的当前内容回写到玩家主手的潜影盒物品上（移植自源
     * {@code ShulkerBoxMenu#updateShulker}，并补上 {@code setItemInMainHand} 以避免
     * “读取到副本、改完没写回”的问题）。必须运行在玩家所在区域线程上。
     */
    private static void updateShulker(Player player, Inventory inventory) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) {
            return;
        }
        if (!(meta.getBlockState() instanceof ShulkerBox handBox)) {
            return;
        }
        handBox.getInventory().setContents(inventory.getContents());
        meta.setBlockState(handBox);
        item.setItemMeta(meta);
        player.getInventory().setItemInMainHand(item);
    }

    // --- 潜影盒界面的 InventoryHolder ---

    /** 标记“这是我们打开的潜影盒界面”，并记住界面归属玩家以便回写。 */
    static final class ShulkerBoxHolder implements InventoryHolder {

        private final Player player;
        private Inventory inventory;

        ShulkerBoxHolder(Player player) {
            this.player = player;
        }

        Player player() {
            return this.player;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return this.inventory;
        }
    }
}
