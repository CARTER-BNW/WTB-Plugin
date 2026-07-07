package wtb.database;

import org.bukkit.Material;
import wtb.models.*;

import java.sql.*;
import java.util.*;

public class ListingDAO {

    // ── Write operations ─────────────────────────────────────────────────────

    public Listing create(Listing listing) {
        String sql = """
            INSERT INTO listings (
                buyer, material, enchant, item, custom_name,
                original_quantity, remaining_quantity,
                original_price, paid_cents,
                created_at, expires_at, state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, listing.getBuyer().toString());
            stmt.setString(2, listing.getMaterial().name());
            if (listing.getEnchant() != null) {
                stmt.setString(3, listing.getEnchant().canonical());
            } else {
                stmt.setNull(3, Types.VARCHAR);
            }
            if (listing.getItemBytes() != null) {
                stmt.setBytes(4, listing.getItemBytes());
            } else {
                stmt.setNull(4, Types.BLOB);
            }
            if (listing.getCustomName() != null) {
                stmt.setString(5, listing.getCustomName());
            } else {
                stmt.setNull(5, Types.VARCHAR);
            }
            stmt.setInt(6,    listing.getOriginalQuantity());
            stmt.setInt(7,    listing.getRemainingQuantity());
            stmt.setDouble(8, listing.getOriginalPrice());
            stmt.setLong(9,   listing.getPaidCents());
            stmt.setLong(10,  listing.getCreatedAt());
            stmt.setLong(11,  listing.getExpiresAt());
            stmt.setString(12, listing.getState().name());

            stmt.executeUpdate();

            try (var keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    listing.setId(keys.getInt(1));
                }
            }
            return listing;

        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Atomic RELATIVE fulfilment claim — the single write that makes overselling
     * impossible.
     *
     * <p>V5's updateIfActive wrote an <i>absolute</i> remaining_quantity computed
     * from the caller's (possibly stale, cached) Listing object.  If another
     * seller had partially filled the listing in the meantime, the stale write
     * silently resurrected already-sold quantity: the buyer received more items
     * than ordered and more money left escrow than was ever deposited.
     *
     * <p>This version decrements <b>relative to the database's own current
     * value</b> and refuses to act unless the row still has at least
     * {@code amount} remaining:
     *
     * <pre>
     * SET remaining = remaining - amt, paid_cents = paid_cents + payout
     * WHERE state IN ('OPEN','PARTIAL') AND remaining_quantity &gt;= amt
     * </pre>
     *
     * Exactly one concurrent writer can win any given unit of quantity — even
     * across multiple servers sharing one MySQL database.
     *
     * @return true if the claim succeeded (rewards may be written);
     *         false if the listing was already filled/expired/cancelled or no
     *         longer has {@code amount} remaining (caller must revert).
     */
    public boolean fulfillIfActive(int id, int amount, long payoutCents) {
        String sql = """
            UPDATE listings
            SET remaining_quantity = remaining_quantity - ?,
                paid_cents         = paid_cents + ?,
                state = CASE WHEN remaining_quantity - ? <= 0 THEN 'FILLED' ELSE 'PARTIAL' END
            WHERE id = ? AND state IN ('OPEN','PARTIAL') AND remaining_quantity >= ?
        """;
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1,  amount);
            stmt.setLong(2, payoutCents);
            stmt.setInt(3,  amount);
            stmt.setInt(4,  id);
            stmt.setInt(5,  amount);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * After the FINAL fill, claims any floor-division dust so escrow conservation
     * holds exactly: sets paid_cents to the full escrow total.  Only applies to
     * rows already FILLED.
     *
     * @return true if the row was updated.
     */
    public boolean settleDust(int id, long fullPriceCents) {
        String sql = "UPDATE listings SET paid_cents = ? WHERE id = ? AND state = 'FILLED'";
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, fullPriceCents);
            stmt.setInt(2, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Conditional update — only sets EXPIRED if the listing is still OPEN or PARTIAL.
     * Returns true if a row was changed; false if it was already filled/cancelled.
     */
    public boolean setExpiredIfActive(int id) {
        String sql = "UPDATE listings SET state='EXPIRED' WHERE id=? AND state IN ('OPEN','PARTIAL')";
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Atomic cancel — only transitions OPEN/PARTIAL → CANCELLED.
     * Returns true if the listing was actually cancelled (not already done/expired).
     */
    public boolean cancelIfActive(int id) {
        String sql = "UPDATE listings SET state='CANCELLED' WHERE id=? AND state IN ('OPEN','PARTIAL')";
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // ── Read operations ──────────────────────────────────────────────────────

    public Listing getById(int id) {
        String sql = "SELECT * FROM listings WHERE id=?";
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                try {
                    return map(rs);
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    public List<Listing> getAllOpen() {
        String sql = "SELECT * FROM listings WHERE state IN ('OPEN','PARTIAL') ORDER BY created_at DESC";
        List<Listing> list = new ArrayList<>();
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql);
             var rs   = stmt.executeQuery()) {
            while (rs.next()) {
                // Per-row guard: a single corrupt row (bad UUID / unknown material /
                // unknown state) is skipped instead of aborting the whole result set.
                try {
                    list.add(map(rs));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * Targeted expiry query — fetches only listings that have already passed
     * their expiry time.  Far cheaper than getAllOpen() on busy servers.
     */
    public List<Listing> getExpiredListings(long now) {
        String sql = """
            SELECT * FROM listings
            WHERE state IN ('OPEN','PARTIAL')
              AND expires_at > 0
              AND expires_at <= ?
        """;
        List<Listing> list = new ArrayList<>();
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, now);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    try {
                        list.add(map(rs));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public List<Listing> getByBuyerVisible(UUID buyer) {
        String sql = """
            SELECT * FROM listings
            WHERE buyer=? AND state IN ('OPEN','PARTIAL','FILLED')
            ORDER BY created_at DESC
        """;
        List<Listing> list = new ArrayList<>();
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, buyer.toString());
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    try {
                        list.add(map(rs));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /** All of a player's OPEN + PARTIAL listings — used by /wtb cancel (bulk cancel). */
    public List<Listing> getActiveByBuyer(UUID buyer) {
        String sql = """
            SELECT * FROM listings
            WHERE buyer=? AND state IN ('OPEN','PARTIAL')
            ORDER BY created_at DESC
        """;
        List<Listing> list = new ArrayList<>();
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, buyer.toString());
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    try {
                        list.add(map(rs));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /** Returns count of OPEN + PARTIAL listings for a player (for per-player cap). */
    public int countActiveByBuyer(UUID buyer) {
        String sql = "SELECT COUNT(*) FROM listings WHERE buyer=? AND state IN ('OPEN','PARTIAL')";
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, buyer.toString());
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    /**
     * Maps a ResultSet row to a Listing.
     *
     * <p>Throws {@code Exception} (not just {@code SQLException}) so that
     * unchecked mapping failures ({@code UUID.fromString},
     * {@code Material.valueOf}, {@code ListingState.valueOf}) are surfaced
     * through the per-row {@code catch (Exception e)} guards in callers.
     */
    private Listing map(ResultSet rs) throws Exception {
        Material mat;
        try {
            mat = Material.valueOf(rs.getString("material"));
        } catch (IllegalArgumentException e) {
            throw new Exception("Unknown material in DB: " + rs.getString("material"), e);
        }

        ListingState state;
        try {
            state = ListingState.valueOf(rs.getString("state"));
        } catch (IllegalArgumentException e) {
            throw new Exception("Unknown listing state in DB: " + rs.getString("state"), e);
        }

        UUID buyer;
        try {
            buyer = UUID.fromString(rs.getString("buyer"));
        } catch (IllegalArgumentException e) {
            throw new Exception("Invalid buyer UUID in DB: " + rs.getString("buyer"), e);
        }

        // Null / corrupt enchant column simply parses to null.  A book listing
        // with an unparseable spec is unfulfillable but must still LOAD so the
        // owner can see and cancel it (getting their refund) instead of the row
        // being invisible forever.  Same philosophy for the item blob — it is
        // carried as raw bytes here and only deserialized in ItemMatcher, so a
        // corrupt blob can never abort row-mapping.
        EnchantSpec enchant   = EnchantSpec.parse(rs.getString("enchant"));
        byte[]      itemBytes = rs.getBytes("item");
        String      custom    = rs.getString("custom_name");

        return new Listing(
                rs.getInt("id"),
                buyer,
                mat,
                enchant,
                itemBytes,
                custom,
                rs.getInt("original_quantity"),
                rs.getInt("remaining_quantity"),
                rs.getDouble("original_price"),
                rs.getLong("paid_cents"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                state
        );
    }
}
