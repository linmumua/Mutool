package cn.linmumua.mutool.menu;

import cn.linmumua.mutool.Mutool;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;

/**
 * Static registration entry point for the menu framework.
 *
 * <p>Call {@link #init(Mutool)} once from {@link Mutool#onEnable()} — the call is idempotent,
 * so repeated invocations (e.g. on reload) never double-register the {@link MenuListener}.
 * Feature modules only construct and open {@link Menu}s; they never touch the listener.</p>
 */
public final class MenuManager {

    private static boolean initialized;
    private static MenuListener listener;

    private MenuManager() {
    }

    /**
     * Register the shared {@link MenuListener} with the plugin. Safe to call multiple times.
     *
     * @param plugin the owning plugin instance
     */
    public static synchronized void init(@NotNull Mutool plugin) {
        if (initialized) {
            return;
        }
        listener = new MenuListener();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        initialized = true;
        IOKt.info("[Mutool/Menu] 菜单框架监听器已注册");
    }

    /** Whether {@link #init(Mutool)} has already run. */
    public static boolean isInitialized() {
        return initialized;
    }
}
