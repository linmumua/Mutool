package cn.linmumua.mutool.module.bugfix;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Bugfix module — hardens the server against known crash / dupe exploits by intercepting
 * crafted packets. This port keeps the "bundle" crash protection of
 * {@code MumuTools} {@code module_bugfix} ({@link BundleCrashListener}): it cancels
 * malformed {@code SELECT_BUNDLE_ITEM} packets sent by 1.21.1+ clients.
 *
 * <p>It owns the module switch ({@code module/bugfix/config.yml} → {@code enable}) and a
 * single PacketEvents {@link BundleCrashListener}. There is no command. The listener is
 * registered through PacketEvents' global {@code EventManager} (with priority
 * {@code LOWEST}) and holds the returned {@link PacketListenerCommon} handle so it can be
 * unregistered again on reload / unload.</p>
 */
public final class BugfixModule implements MutoolModule {

    private static final String CONFIG_RESOURCE = "module/bugfix/config.yml";

    private Mutool plugin;
    private Configuration config;

    private boolean enabled;
    private BundleCrashListener listener;
    private PacketListenerCommon listenerHandle;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public BugfixModule() {
    }

    @Override
    public String id() {
        return "bugfix";
    }

    /** Release the default resource, load config, then (re)apply the enable state + listener. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);

        this.listener = new BundleCrashListener();
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

    /** Unregister the PacketEvents listener and drop state. Called on plugin disable. */
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
            IOKt.info("[Mutool/Bugfix] 漏洞修复模块已关闭 (enable: false)");
        }
    }

    private void registerListener() {
        if (this.listener == null) {
            return;
        }
        try {
            this.listenerHandle = PacketEvents.getAPI().getEventManager()
                    .registerListener(this.listener, PacketListenerPriority.LOWEST);
            IOKt.info("[Mutool/Bugfix] 已注册 bundle 崩溃拦截监听器");
        } catch (Throwable throwable) {
            this.listenerHandle = null;
            IOKt.severe("[Mutool/Bugfix] 注册 PacketEvents 监听器失败: " + throwable);
        }
    }

    private void unregisterListener() {
        if (this.listenerHandle == null) {
            return;
        }
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(this.listenerHandle);
        } catch (Throwable ignored) {
        }
        this.listenerHandle = null;
    }
}
