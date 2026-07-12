package wtb.database;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Per-player preference storage (V6.2), e.g. muting the order-filled title
 * popup.  A generic (player, setting, value) key-value table so future
 * per-player options need no schema change.
 *
 * <p>Upserts use {@code REPLACE INTO}, which both SQLite and MySQL support
 * with identical semantics for a full-row write like this.
 */
public class PlayerSettingsDAO {

    private static final Logger LOG = Logger.getLogger("Minecraft");

    /** All stored settings for one player.  Call from an async thread. */
    public Map<String, String> get(UUID player) {
        String sql = "SELECT setting, value FROM player_settings WHERE player=?";
        Map<String, String> map = new HashMap<>();
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, player.toString());
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("setting"), rs.getString("value"));
                }
            }
        } catch (SQLException e) {
            LOG.severe("[WTB] FAILED to load player settings for " + player
                    + ": " + e.getMessage());
        }
        return map;
    }

    /** Upserts one setting.  Call from an async thread. */
    public boolean set(UUID player, String setting, String value) {
        String sql = "REPLACE INTO player_settings (player, setting, value) VALUES (?, ?, ?)";
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, player.toString());
            stmt.setString(2, setting);
            stmt.setString(3, value);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOG.severe("[WTB] FAILED to save player setting " + setting + " for "
                    + player + ": " + e.getMessage());
            return false;
        }
    }
}
