package cn.linmumua.mutool.module.tpahere;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.redis.MessageBus;
import com.google.gson.JsonObject;

/**
 * Cross-server message helpers for the tpahere module, layered on the shared
 * {@link MessageBus} (Redis pub/sub).
 *
 * <p>Two message types are multiplexed on the bus:</p>
 * <ul>
 *   <li>{@link #TYPE_REQUEST} — {@code {requester, target}}: delivered when a requester asks a
 *       target to teleport over. Each receiving server delivers the clickable request panel to the
 *       target if that player is online locally, and records a pending request on that server.</li>
 *   <li>{@link #TYPE_RESPONSE} — {@code {requester, target, action, teleported}}: a result
 *       ({@code accept} / {@code reject} / {@code timeout}) sent back so the requester is notified
 *       from whichever server the target was on.</li>
 * </ul>
 *
 * <p>Because {@link MessageBus#send} dispatches locally first and de-duplicates the origin server,
 * a single {@code publish} call reaches the local server (when the player is local) and every other
 * server exactly once. Mutool has no BungeeCord transfer channel, so actual cross-server teleport
 * cannot move a player between servers — {@code teleported=false} on the response signals the
 * degraded, same-server-only outcome (see {@code TpahereModule}).</p>
 */
final class TpahereNet {

    /** A requester asks a target to teleport over. */
    static final String TYPE_REQUEST = "tpahere_request";

    /** The target accepted / rejected / the request timed out. */
    static final String TYPE_RESPONSE = "tpahere_response";

    static final String ACTION_ACCEPT = "accept";
    static final String ACTION_REJECT = "reject";
    static final String ACTION_TIMEOUT = "timeout";

    private TpahereNet() {
    }

    /** Broadcast a request for {@code target} on whatever server they are online. */
    static void publishRequest(String requester, String target) {
        MessageBus bus = Mutool.getMessageBus();
        if (bus == null) {
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("requester", requester);
        data.addProperty("target", target);
        bus.send(TYPE_REQUEST, data);
    }

    /** Broadcast the outcome of a request back to the requester's server. */
    static void publishResponse(String requester, String target, String action, boolean teleported) {
        MessageBus bus = Mutool.getMessageBus();
        if (bus == null) {
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("requester", requester);
        data.addProperty("target", target);
        data.addProperty("action", action);
        data.addProperty("teleported", teleported);
        bus.send(TYPE_RESPONSE, data);
    }
}
