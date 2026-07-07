package wtb.database;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Pending offline notifications ("your order was filled by X while you were away").
 *
 * <p>Rows are inserted from async fulfilment / expiry / admin-cancel tasks and
 * delivered on the player's next join.  Delivery uses a delete-first ownership
 * claim per row (same pattern as the Claim Box) so a message can never be
 * delivered twice, even if a player triggers two overlapping join deliveries.
 */
public class NotificationDAO {

    private static final Logger LOG = Logger.getLogger("Minecraft");

    /** Inserts a notification row.  Call from an async thread. */
    public boolean add(UUID player, String message) {
        String sql = """
            INSERT INTO notifications (player, message, created_at)
            VALUES (?, ?, ?)
        """;
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, player.toString());
            stmt.setString(2, message);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOG.severe("[WTB] FAILED to insert notification for " + player + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Fetches all pending notifications for a player, oldest first.
     * Call from an async thread.  Each entry: [0]=id, [1]=message.
     */
    public List<Object[]> get(UUID player) {
        String sql = "SELECT id, message FROM notifications WHERE player=? ORDER BY created_at ASC";
        List<Object[]> list = new ArrayList<>();
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, player.toString());
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new Object[]{ rs.getInt("id"), rs.getString("message") });
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /** Atomic delete-first claim.  Returns true if this caller owns the row. */
    public boolean deleteIfExists(int id) {
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement("DELETE FROM notifications WHERE id=?")) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
}
