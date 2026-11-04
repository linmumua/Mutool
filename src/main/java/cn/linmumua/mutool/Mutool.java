package cn.linmumua.mutool;

import cn.linmumua.mutool.database.DatabaseManager;
import cn.linmumua.mutool.menu.MenuManager;
import cn.linmumua.mutool.module.ModuleManager;
import cn.linmumua.mutool.redis.MessageBus;
import cn.linmumua.mutool.redis.RedisManager;
import cn.linmumua.mutool.redis.RedisSettings;
import taboolib.platform.BukkitPlugin;

import java.util.logging.Level;

/**
 * Mutool — TabooLib-based base skeleton for the Mutool plugin.
 *
 * <p>Feature modules are discovered automatically by {@link ModuleManager}; there is
 * no hard-coded list here, so adding a module requires no change to this class.</p>
 */
public final class Mutool extends BukkitPlugin {

    private ModuleManager moduleManager;
    private DatabaseManager databaseManager;

    /** Cross-server message bus; non-null after onEnable (local-only fallback on failure). */
    private static MessageBus messageBus;
    /** Redis pub/sub owner; {@code null} when disabled or init failed. */
    private static RedisManager redisManager;

    @Override
    public void onEnable() {
        super.onEnable();

        // Register the shared inventory-GUI listener once (idempotent). Must happen before any
        // module opens a menu, so it is registered here rather than per-module.
        MenuManager.init(this);

        // Initialize the data layer before any module loads, so DB-backed modules can
        // obtain their DAO in ModuleManager.loadAll(). A failure here is logged but does
        // not abort startup — modules that need the DB will surface their own errors.
        try {
            this.databaseManager = new DatabaseManager(this);
            this.databaseManager.connect();
            this.databaseManager.createAllTables();
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "[Mutool] 数据层初始化失败，数据库相关功能将不可用", throwable);
        }

        // Initialize the cross-server message bus BEFORE any module loads, so modules can
        // call Mutool.getMessageBus() from their own load(). Fully degrades to local-only
        // when redis is disabled or unreachable — never throws, never blocks startup.
        try {
            RedisSettings redisSettings = new RedisSettings(getDataFolder());
            RedisManager manager = RedisManager.initialize(redisSettings, getLogger());
            manager.connect();
            redisManager = manager;
            messageBus = new MessageBus(manager, redisSettings);
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "[Mutool] Redis 消息总线初始化失败，降级为本地直发", throwable);
            messageBus = MessageBus.localOnly();
        }

        this.moduleManager = new ModuleManager(this);
        this.moduleManager.loadAll();

        getLogger().info("[Mutool] onEnable() — TabooLib base skeleton loaded, "
                + this.moduleManager.modules().size() + " module(s) active.");
    }

    @Override
    public void onDisable() {
        if (this.moduleManager != null) {
            this.moduleManager.unloadAll();
            this.moduleManager = null;
        }

        // Close the message bus AFTER every module has been unloaded, so modules can still
        // publish during their own unload().
        messageBus = null;
        if (redisManager != null) {
            redisManager.close();
            redisManager = null;
        }

        // Close the data layer only after every module has been unloaded, so modules can
        // still flush their buffers during unload().
        if (this.databaseManager != null) {
            this.databaseManager.close();
            this.databaseManager = null;
        }

        super.onDisable();
        getLogger().info("[Mutool] onDisable() — TabooLib base skeleton unloaded.");
    }

    public ModuleManager getModuleManager() {
        return this.moduleManager;
    }

    /** The plugin's database manager, or {@code null} if the data layer failed to start. */
    public DatabaseManager getDatabaseManager() {
        return this.databaseManager;
    }

    /**
     * The cross-server message bus. Never {@code null} once {@code onEnable} has run — when
     * Redis is disabled or unreachable this is a local-only bus.
     */
    public static MessageBus getMessageBus() {
        return messageBus;
    }

    /** The Redis manager, or {@code null} when Redis is disabled / failed to initialize. */
    public static RedisManager getRedisManager() {
        return redisManager;
    }
}
