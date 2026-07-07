package wtb.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NameCache {

    /** Entries expire after 1 hour so name changes are reflected without a restart. */
    private static final long TTL_MS = 60 * 60 * 1000L;

    private record Entry(String name, long timestamp) {}

    private static final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    /**
     * Pre-populate (or refresh) a name, e.g. from PlayerLoginEvent.
     * Safe to call from any thread.
     */
    public static void put(UUID uuid, String name) {
        cache.put(uuid, new Entry(name, System.currentTimeMillis()));
    }

    /**
     * Returns a display name for the given UUID.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Cache hit (fresh within TTL) — instant, no I/O, any thread.</li>
     *   <li>Online-player lookup via {@code Bukkit.getPlayer()} — safe on any
     *       thread on Paper; also refreshes the cache.</li>
     *   <li>Offline lookup via {@code Bukkit.getOfflinePlayer()} — may perform
     *       disk I/O and is only invoked on the <b>main thread</b>.<br>
     *       <b>Bug #9 fix:</b> from async threads, if the cache is stale or empty,
     *       a truncated UUID is returned instead of calling getOfflinePlayer().
     *       The cache will be populated the next time the player comes online or
     *       this method is called from the main thread.</li>
     * </ol>
     */
    public static String getName(UUID uuid) {
        // Check in-memory cache first — cheapest path and works on any thread.
        Entry entry = cache.get(uuid);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.timestamp() <= TTL_MS) {
            return entry.name();
        }

        // Fast path: player is currently online — Bukkit.getPlayer() is thread-safe
        // on Paper (backed by a ConcurrentHashMap) and does no disk I/O.
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            put(uuid, online.getName()); // refresh cache
            return online.getName();
        }

        // Slow path: offline lookup may do disk I/O and is not guaranteed thread-safe
        // on all server implementations.  Only call it from the main thread.
        if (!Bukkit.isPrimaryThread()) {
            // Return a truncated UUID fragment as a safe fallback.
            // The next main-thread call (or player login) will populate the cache.
            String fallback = uuid.toString().substring(0, 8) + "\u2026";
            // Store the fallback with a very short TTL (10 s) so it's replaced quickly.
            cache.put(uuid, new Entry(fallback, now - (TTL_MS - 10_000L)));
            return fallback;
        }

        var offline = Bukkit.getOfflinePlayer(uuid);
        String name = (offline.getName() != null) ? offline.getName()
                : uuid.toString().substring(0, 8) + "\u2026";
        cache.put(uuid, new Entry(name, now));
        return name;
    }

    /** Force-evict an entry (e.g. on player rename). */
    public static void invalidate(UUID uuid) {
        cache.remove(uuid);
    }
}