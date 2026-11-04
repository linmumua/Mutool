package cn.linmumua.mutool.module.tpahere;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of pending tpahere requests, keyed by <em>requester</em> name.
 *
 * <p>Replaces the source module's Redis {@code RequestCache}: Mutool keeps single-server state in
 * memory. A request means "requester asked target to teleport to them"; the {@code target} is the
 * one who accepts/rejects and teleports. Entries carry an {@link Entry#expireAt() expiry} and are
 * invalidated lazily on read (a scheduled task in {@link TpahereModule} also prunes them).</p>
 *
 * <p>All operations are thread-safe; the map is read from command / menu (region) threads and
 * written from the cross-server bus thread.</p>
 */
final class TpahereRequests {

    /** A pending request: {@code requester} asked {@code target} to come over. */
    record Entry(String requester, String target, long expireAt) {
    }

    private final Map<String, Entry> byRequester = new ConcurrentHashMap<>();

    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** Store a request and return the created entry (caller can schedule its timeout). */
    Entry put(String requester, String target, long timeoutMillis) {
        long expireAt = System.currentTimeMillis() + Math.max(timeoutMillis, 1000L);
        Entry entry = new Entry(requester, target, expireAt);
        byRequester.put(key(requester), entry);
        return entry;
    }

    /** The live request made by {@code requester}, or {@code null} if absent/expired. */
    Entry get(String requester) {
        Entry entry = byRequester.get(key(requester));
        if (entry == null) {
            return null;
        }
        if (entry.expireAt() <= System.currentTimeMillis()) {
            byRequester.remove(key(requester), entry);
            return null;
        }
        return entry;
    }

    /** The most recent live request addressed to {@code target}, or {@code null}. */
    Entry findByTarget(String target) {
        long now = System.currentTimeMillis();
        Entry newest = null;
        for (Entry entry : byRequester.values()) {
            if (entry.expireAt() <= now || !entry.target().equalsIgnoreCase(target)) {
                continue;
            }
            if (newest == null || entry.expireAt() > newest.expireAt()) {
                newest = entry;
            }
        }
        return newest;
    }

    void removeByRequester(String requester) {
        byRequester.remove(key(requester));
    }

    /**
     * Remove the stored entry only when it is still exactly the one identified by
     * (requester, target, expireAt). Returns whether it was removed — used by the timeout task so
     * it never deletes a newer request that replaced the one it was scheduled for.
     */
    boolean removeIfMatches(String requester, String target, long expireAt) {
        Entry entry = byRequester.get(key(requester));
        if (entry == null || entry.expireAt() != expireAt || !entry.target().equalsIgnoreCase(target)) {
            return false;
        }
        return byRequester.remove(key(requester), entry);
    }
}
