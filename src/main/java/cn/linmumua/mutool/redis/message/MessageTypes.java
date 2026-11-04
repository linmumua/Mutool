package cn.linmumua.mutool.redis.message;

/**
 * Canonical {@code type} tags used on the cross-server bus.
 *
 * <p>Keeping them in one place avoids typo mismatches between {@code send()} callers and
 * {@code register()} handlers. New modules (chat / tpa / tpahere / gamemode …) should add
 * their own constant here and register a matching handler via
 * {@link cn.linmumua.mutool.redis.MessageBus#register}.
 */
public final class MessageTypes {

    /** Deliver a component to a single player by name, wherever they are online. */
    public static final String PLAYER_MESSAGE = "player_message";

    /** Broadcast a component to every player on every server (plus console). */
    public static final String BROADCAST = "broadcast";

    private MessageTypes() {
    }
}
