package cn.linmumua.mutool;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;

/**
 * Proves that PacketEvents API classes are resolvable at compile time and are
 * expected to be visible at runtime through Paper's {@code join-classpath: true}
 * (declared for {@code packetevents} in paper-plugin.yml).
 *
 * <p>PacketEvents itself is provided by the PacketEvents plugin; we never shade
 * it into Mutool's jar (it is a {@code compileOnly} dependency).</p>
 */
public final class PacketEventsHook {

    private PacketEventsHook() {
    }

    /**
     * Returns the server version reported by PacketEvents, or {@code null} when
     * the PacketEvents plugin is not present / not yet initialised.
     */
    public static ServerVersion serverVersion() {
        try {
            return PacketEvents.getAPI().getServerManager().getVersion();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
