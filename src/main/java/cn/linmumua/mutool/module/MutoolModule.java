package cn.linmumua.mutool.module;

import cn.linmumua.mutool.Mutool;

/**
 * A self-contained feature module of Mutool.
 *
 * <p>Implementations live under {@code cn.linmumua.mutool.module.*}, expose a
 * <strong>public no-arg constructor</strong> and are discovered automatically by
 * {@link ModuleManager} (see there for the scanning rules). Because discovery is
 * purely by package scan, <em>adding a new module requires no change to any shared
 * file</em> — drop in a class implementing this interface and it is loaded.</p>
 */
public interface MutoolModule {

    /** Stable, unique module id (also the sort key for deterministic load order). */
    String id();

    /**
     * Called once on plugin enable. Release default resources, read config &amp; lang,
     * then register commands / listeners / tasks.
     *
     * @param plugin the owning plugin instance (never {@code null})
     */
    void load(Mutool plugin);

    /** Re-read config / lang from disk and re-apply state (register/unregister, etc.). */
    void reload();

    /** Called on plugin disable / module shutdown. Unregister everything and drop state. */
    void unload();

    /** Whether the module is currently active (e.g. its {@code enable} flag is on). */
    boolean isEnabled();
}
