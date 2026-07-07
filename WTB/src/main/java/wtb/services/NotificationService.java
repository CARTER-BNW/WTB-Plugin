package wtb.services;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import wtb.Main;
import wtb.database.NotificationDAO;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Offline-alert delivery: when a player's buy order is filled, expires, or is
 * admin-cancelled while they are offline, the message is stored and delivered
 * on their next join (a couple of seconds after login so it isn't drowned out
 * by the join-message wall).
 */
public class NotificationService {

    /** Delay (ticks) after join before pending notifications are shown. */
    private static final long JOIN_DELAY_TICKS = 40L; // 2 seconds

    private final NotificationDAO dao = new NotificationDAO();

    /**
     * Queues a pre-rendered message for an offline player.
     * Safe to call from an async thread (DB-only).
     */
    public void queue(UUID player, String message) {
        if (dao.add(player, message)) {
            LogService.log("notifications",
                    "Queued offline notification for " + LogService.name(player)
                            + ": " + message.replaceAll("§.", ""));
        }
    }

    /**
     * Called from PlayerJoinEvent (main thread).  Fetches + claims pending rows
     * asynchronously, then delivers them on the main thread after a short delay.
     */
    public void deliverOnJoin(Player player) {
        final UUID uuid = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            List<Object[]> rows = dao.get(uuid);
            if (rows.isEmpty()) return;

            // Delete-first per row: only rows this task successfully claims are
            // delivered — a duplicate concurrent delivery can never double-send.
            List<String> messages = new ArrayList<>();
            for (Object[] row : rows) {
                int    id  = (int)    row[0];
                String msg = (String) row[1];
                if (dao.deleteIfExists(id)) {
                    messages.add(msg);
                }
            }
            if (messages.isEmpty()) return;

            Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
                if (!player.isOnline()) {
                    // Player left before the delay elapsed — re-queue asynchronously
                    // (DB write must not run on the main thread) so nothing is lost.
                    Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                        for (String msg : messages) dao.add(uuid, msg);
                    });
                    return;
                }
                player.sendMessage(Main.msg("notify_header"));
                for (String msg : messages) {
                    player.sendMessage(msg);
                }
                player.playSound(player.getLocation(),
                        org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            }, JOIN_DELAY_TICKS);
        });
    }
}
