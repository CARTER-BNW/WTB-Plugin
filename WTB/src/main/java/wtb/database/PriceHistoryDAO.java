package wtb.database;

import wtb.models.PriceHistory;

import java.sql.*;

public class PriceHistoryDAO {

    /**
     * Read price history within an existing connection (used by update() to
     * keep read and write on the same connection / transaction).
     */
    private PriceHistory getWithConn(Connection conn, String key) throws SQLException {
        String sql = "SELECT * FROM price_history WHERE material=?";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return new PriceHistory(
                        key,
                        rs.getDouble("avg_price"),
                        rs.getDouble("min_price"),
                        rs.getDouble("max_price"),
                        rs.getLong("total_volume")
                );
            }
        }
    }

    public PriceHistory get(String key) {
        try (var conn = DatabaseManager.get().getConnection()) {
            return getWithConn(conn, key);
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Atomically update price statistics for the given aggregation key
     * (a material name, or material+enchant for book orders — the column is
     * still named {@code material} for V5 compatibility).
     *
     * Read and write share the same connection with autoCommit=false, which:
     *  - Prevents the TOCTOU race where two concurrent threads both read null
     *    and both attempt an INSERT (one would crash on the PK constraint).
     *  - Rolls back cleanly on error so stats are never partially updated.
     *
     * The caller (PriceHistoryService) already guarantees single-threaded access
     * (called from a single async task per fulfillment), so no further locking
     * is needed at the Java level.
     *
     * @param pricePerItem  price paid per individual item in this trade
     * @param quantity      number of items traded
     */
    public void update(String key, double pricePerItem, long quantity) {
        try (var conn = DatabaseManager.get().getConnection()) {
            conn.setAutoCommit(false);
            try {
                PriceHistory existing = getWithConn(conn, key);

                double newAvg;
                double newMin;
                double newMax;
                long   newVolume;

                if (existing == null) {
                    newAvg    = pricePerItem;
                    newMin    = pricePerItem;
                    newMax    = pricePerItem;
                    newVolume = quantity;
                } else {
                    long oldVol = existing.getTotalVolume();
                    newAvg    = (existing.getAvgPrice() * oldVol + pricePerItem * quantity)
                            / (oldVol + quantity);
                    newMin    = Math.min(existing.getMinPrice(), pricePerItem);
                    newMax    = Math.max(existing.getMaxPrice(), pricePerItem);
                    newVolume = oldVol + quantity;
                }

                if (existing == null) {
                    String sql = """
                        INSERT INTO price_history
                            (material, avg_price, min_price, max_price, total_volume)
                        VALUES (?, ?, ?, ?, ?)
                    """;
                    try (var stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, key);
                        stmt.setDouble(2, newAvg);
                        stmt.setDouble(3, newMin);
                        stmt.setDouble(4, newMax);
                        stmt.setLong(5,   newVolume);
                        stmt.executeUpdate();
                    }
                } else {
                    String sql = """
                        UPDATE price_history
                        SET avg_price=?, min_price=?, max_price=?, total_volume=?
                        WHERE material=?
                    """;
                    try (var stmt = conn.prepareStatement(sql)) {
                        stmt.setDouble(1, newAvg);
                        stmt.setDouble(2, newMin);
                        stmt.setDouble(3, newMax);
                        stmt.setLong(4,   newVolume);
                        stmt.setString(5, key);
                        stmt.executeUpdate();
                    }
                }

                conn.commit();

            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException rb) { rb.printStackTrace(); }
                e.printStackTrace();
            } finally {
                // Always reset autoCommit to true before the connection is
                // returned to the pool.  Without this, the next query that borrows
                // this connection sees autoCommit=false and its writes are never committed.
                try { conn.setAutoCommit(true); } catch (SQLException ex) { ex.printStackTrace(); }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
