package cn.linmumua.mutool.module.fastuse;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Fastuse module — 快捷使用：手持工作台 / 末影箱 / 潜影盒对空气右键即可直接使用，
 * 无需先放置到地上。
 *
 * <p>这是 {@code MumuTools} 的 {@code module_fastuse} 到 TabooLib 基座的移植版。源模块由
 * 三个独立的 {@code feature.Listener} 组成（{@code CraftingTable} / {@code EnderChest} /
 * {@code ShulkerBox}），分别处理：</p>
 * <ul>
 *   <li><b>工作台</b>：主手持工作台空右键 → 打开工作台合成界面；</li>
 *   <li><b>末影箱</b>：主手持末影箱空右键 → 打开自己的末影箱；</li>
 *   <li><b>潜影盒</b>：主手持潜影盒空右键 → 打开潜影盒内容界面，可直接编辑，关闭后回写到该
 *       物品；禁止把潜影盒放进潜影盒（防嵌套）。</li>
 * </ul>
 *
 * <p>本模块没有命令，只注册 {@link FastuseListener}（右键事件 + 潜影盒界面）。模块开关与
 * 每种物品的开关来自 {@code module/fastuse/config.yml}。监听器只在 {@code enable: true} 时
 * 注册，每种物品是否生效在事件触发时按 {@code items.*} 与权限节点判断——与源模块
 * “按 item 开关过滤监听器注册”的语义一致。</p>
 *
 * <p>权限节点沿用源模块的 {@code mumutools.fastuse.*}，按移植约定将前缀改为 {@code mutool}
 * （即 {@code mutool.fastuse.crafting_table} / {@code mutool.fastuse.ender_chest} /
 * {@code mutool.fastuse.shulker_box}）。</p>
 */
public final class FastuseModule implements MutoolModule {

    /** 潜影盒快捷使用所需的权限。 */
    public static final String PERMISSION_SHULKER_BOX = "mutool.fastuse.shulker_box";
    /** 末影箱快捷使用所需的权限。 */
    public static final String PERMISSION_ENDER_CHEST = "mutool.fastuse.ender_chest";
    /** 工作台快捷使用所需的权限。 */
    public static final String PERMISSION_CRAFTING_TABLE = "mutool.fastuse.crafting_table";

    private static final String CONFIG_RESOURCE = "module/fastuse/config.yml";

    private Mutool plugin;
    private Configuration config;

    private boolean enabled;
    private FastuseListener listener;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public FastuseModule() {
    }

    @Override
    public String id() {
        return "fastuse";
    }

    /** Release the default resource, load config, then (re)apply the enable state + listener. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);

        this.listener = new FastuseListener(this);
        this.applyState();
    }

    /** Reload config from disk and re-apply the enable state / listener registration. */
    @Override
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
        this.applyState();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    /** Unregister the listener and drop state. Called on plugin disable. */
    @Override
    public void unload() {
        this.unregisterListener();
        this.enabled = false;
    }

    // --- internals ---

    private void applyState() {
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        // Always drop the old registration first so a reload never double-registers.
        this.unregisterListener();
        if (nowEnabled) {
            this.registerListener();
        }
        this.enabled = nowEnabled;

        if (!nowEnabled) {
            IOKt.info("[Mutool/Fastuse] 快捷使用模块已关闭 (enable: false)");
        }
    }

    private void registerListener() {
        if (this.listener == null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this.listener, this.plugin);
        IOKt.info("[Mutool/Fastuse] 已注册快捷使用监听器");
    }

    private void unregisterListener() {
        if (this.listener == null) {
            return;
        }
        HandlerList.unregisterAll(this.listener);
    }

    /** 潜影盒快捷使用是否开启（{@code items.shulker_box}）。 */
    boolean shulkerBoxEnabled() {
        return this.config != null && this.config.getBoolean("items.shulker_box", true);
    }

    /** 末影箱快捷使用是否开启（{@code items.ender_chest}）。 */
    boolean enderChestEnabled() {
        return this.config != null && this.config.getBoolean("items.ender_chest", true);
    }

    /** 工作台快捷使用是否开启（{@code items.crafting_table}）。 */
    boolean craftingTableEnabled() {
        return this.config != null && this.config.getBoolean("items.crafting_table", true);
    }
}
