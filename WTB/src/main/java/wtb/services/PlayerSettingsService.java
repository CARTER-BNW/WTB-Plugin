package wtb.services;

import org.bukkit.entity.Player;

import wtb.database.DbExecutor;
import wtb.database.PlayerSettingsDAO;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player preferences (V6.2), cached in memory so hot paths — the fulfil
 * notify step runs on the main thread — never touch the database.
 *
 * <p>The cache is loaded asynchronously on join and evicted on quit.  Until a
 * player's rows have loaded, every preference reads as its default; for the
 * popup mutes that means "not muted", i.e. exactly the pre-V6.2 behaviour.
 */
public class PlayerSettingsService {

    /** Setting keys (values stored as "true"/"false"). */
    public static final String MUTE_FULL_POPUP    = "mute-full-popup";
    public static final String MUTE_PARTIAL_POPUP = "mute-partial-popup";

    private final PlayerSettingsDAO dao = new PlayerSettingsDAO();

    private final Map<UUID, Map<String, String>> cache = new ConcurrentHashMap<>();

    /** Called from PlayerJoinEvent (main thread) — loads the rows async. */
    public void loadOnJoin(Player player) {
        final UUID uuid = player.getUniqueId();
        DbExecutor.submit(() -> {
            Map<String, String> loaded = dao.get(uuid);
            // Merge instead of put: if the player already ran a settings
            // command in the window before this load finished, those live
            // values are newer than the DB snapshot and must win.
            Map<String, String> live =
                    cache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
            loaded.forEach(live::putIfAbsent);
        });
    }

    /** Called from PlayerQuitEvent (main thread) to prevent memory leaks. */
    public void handleQuit(UUID uuid) {
        cache.remove(uuid);
    }

    /** Main-thread safe: pure cache read; defaults to "not muted". */
    public boolean isPopupMuted(UUID player, boolean fullyFilled) {
        Map<String, String> map = cache.get(player);
        if (map == null) return false;
        return Boolean.parseBoolean(
                map.get(fullyFilled ? MUTE_FULL_POPUP : MUTE_PARTIAL_POPUP));
    }

    /** Updates the cache immediately (instant feedback), persists async. */
    public void setSetting(UUID player, String setting, boolean value) {
        cache.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
             .put(setting, String.valueOf(value));
        DbExecutor.submit(() -> dao.set(player, setting, String.valueOf(value)));
    }
}
