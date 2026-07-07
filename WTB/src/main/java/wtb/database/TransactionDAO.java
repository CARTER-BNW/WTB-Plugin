package wtb.database;

import wtb.models.*;
import org.bukkit.Material;

import java.sql.*;
import java.util.*;

public class TransactionDAO {

    public void log(Transaction tx) {
        String sql = """
            INSERT INTO transactions (buyer, seller, material, enchant, custom_name,
                                      quantity, price, timestamp, type)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, tx.getBuyer().toString());
            stmt.setString(2, tx.getSeller().toString());
            stmt.setString(3, tx.getMaterial().name());
            if (tx.getEnchant() != null) {
                stmt.setString(4, tx.getEnchant().canonical());
            } else {
                stmt.setNull(4, Types.VARCHAR);
            }
            if (tx.getCustomName() != null) {
                stmt.setString(5, tx.getCustomName());
            } else {
                stmt.setNull(5, Types.VARCHAR);
            }
            stmt.setInt(6,    tx.getQuantity());
            stmt.setDouble(7, tx.getPrice());
            stmt.setLong(8,   tx.getTimestamp());
            stmt.setString(9, tx.getType().name());

            stmt.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /** Returns up to {@code limit} most-recent global transactions (marketplace feed). */
    public List<Transaction> getRecent(int limit) {
        if (limit <= 0) limit = 50;
        String sql = "SELECT * FROM transactions ORDER BY timestamp DESC LIMIT ?";
        List<Transaction> list = new ArrayList<>();

        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Per-row guard: IllegalArgumentException from UUID.fromString()
                    // or the Material/TransactionType valueOf() calls in map() is
                    // handled per-row and doesn't abort the entire result set.
                    try { list.add(map(rs)); } catch (Exception e) { e.printStackTrace(); }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * Returns transactions in which {@code player} was the buyer OR seller,
     * ordered most-recent first.
     */
    public List<Transaction> getByPlayer(UUID player, int limit) {
        if (limit <= 0) limit = 50;
        String sql = """
            SELECT * FROM transactions
            WHERE buyer=? OR seller=?
            ORDER BY timestamp DESC LIMIT ?
        """;
        List<Transaction> list = new ArrayList<>();
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            String uuidStr = player.toString();
            stmt.setString(1, uuidStr);
            stmt.setString(2, uuidStr);
            stmt.setInt(3, limit);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    try { list.add(map(rs)); } catch (Exception e) { e.printStackTrace(); }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    /**
     * Maps a ResultSet row to a Transaction.
     *
     * <p>Throws {@code Exception} so that all checked and unchecked mapping
     * failures (including {@code UUID.fromString} → {@code IllegalArgumentException})
     * propagate through the same channel and are caught by the per-row
     * {@code catch (Exception e)} guards in the callers.
     */
    private Transaction map(ResultSet rs) throws Exception {
        Material mat;
        try {
            mat = Material.valueOf(rs.getString("material"));
        } catch (IllegalArgumentException e) {
            throw new Exception("Unknown material in transactions DB: "
                    + rs.getString("material"), e);
        }

        TransactionType type;
        try {
            type = TransactionType.valueOf(rs.getString("type"));
        } catch (IllegalArgumentException e) {
            throw new Exception("Unknown TransactionType in DB: " + rs.getString("type"), e);
        }

        UUID buyer;
        try {
            buyer = UUID.fromString(rs.getString("buyer"));
        } catch (IllegalArgumentException e) {
            throw new Exception("Invalid buyer UUID in transactions DB: "
                    + rs.getString("buyer"), e);
        }

        UUID seller;
        try {
            seller = UUID.fromString(rs.getString("seller"));
        } catch (IllegalArgumentException e) {
            throw new Exception("Invalid seller UUID in transactions DB: "
                    + rs.getString("seller"), e);
        }

        // Null / corrupt spec parses to null — the trade row still loads.
        EnchantSpec enchant    = EnchantSpec.parse(rs.getString("enchant"));
        String      customName = rs.getString("custom_name");

        return new Transaction(
                rs.getInt("id"),
                buyer,
                seller,
                mat,
                enchant,
                customName,
                rs.getInt("quantity"),
                rs.getDouble("price"),
                rs.getLong("timestamp"),
                type
        );
    }
}
