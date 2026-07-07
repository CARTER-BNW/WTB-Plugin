package wtb.database;

import wtb.models.*;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

public class ClaimBoxDAO {

    private static final Logger LOG = Logger.getLogger("Minecraft");

    // ── Write operations ─────────────────────────────────────────────────────

    /**
     * Inserts a new claim entry.
     *
     * <p>Bug #12 fix: returns {@code true} on success, {@code false} on DB error.
     * Also logs a SEVERE message on failure so the issue is visible in the
     * server console — a silent {@code printStackTrace()} is not enough when
     * an item or money reward is about to be lost.
     *
     * @return true if the row was inserted; false if a DB error occurred.
     */
    public boolean add(ClaimEntry entry) {
        String sql = """
            INSERT INTO claim_box (player, type, item, money, created_at)
            VALUES (?, ?, ?, ?, ?)
        """;

        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, entry.getPlayer().toString());
            stmt.setString(2, entry.getType().name());

            if (entry.getType() == ClaimType.ITEM) {
                byte[] bytes = serialize(entry.getItem());
                if (bytes != null) {
                    stmt.setBytes(3, bytes);
                } else {
                    stmt.setNull(3, Types.BLOB);
                }
            } else {
                stmt.setNull(3, Types.BLOB);
            }

            stmt.setDouble(4, entry.getMoney());
            stmt.setLong(5,   entry.getCreatedAt());

            stmt.executeUpdate();
            return true;

        } catch (Exception e) {
            // Bug #12 fix: log a visible error so admins are alerted to data loss.
            LOG.severe("[WTB] FAILED to insert ClaimEntry for player="
                    + entry.getPlayer() + " type=" + entry.getType()
                    + " money=" + entry.getMoney() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Deletes the claim entry with the given id and reports whether a row
     * was actually removed.
     *
     * <p>Bug #2 fix: returning {@code true} only when a row was deleted lets
     * {@link wtb.services.ClaimBoxService#claim} use this as an atomic ownership
     * claim — if two concurrent calls both try to delete the same entry, exactly
     * one receives {@code true} and gives the reward; the other sees {@code false}
     * and aborts.
     *
     * @return true if the entry existed and was deleted; false if it was already gone.
     */
    public boolean deleteIfExists(int id) {
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement("DELETE FROM claim_box WHERE id=?")) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // ── Read operations ──────────────────────────────────────────────────────

    public List<ClaimEntry> get(UUID player) {
        String sql = "SELECT * FROM claim_box WHERE player=? ORDER BY created_at DESC";
        List<ClaimEntry> list = new ArrayList<>();

        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, player.toString());
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    try {
                        list.add(map(rs));
                    } catch (Exception e) {
                        // Corrupt row — log and skip so the rest of the list is still returned.
                        e.printStackTrace();
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private ClaimEntry map(ResultSet rs) throws Exception {
        ClaimType type;
        try {
            type = ClaimType.valueOf(rs.getString("type"));
        } catch (IllegalArgumentException e) {
            throw new Exception("Unknown ClaimType in DB: " + rs.getString("type"), e);
        }

        UUID player;
        try {
            player = UUID.fromString(rs.getString("player"));
        } catch (IllegalArgumentException e) {
            throw new Exception("Invalid player UUID in DB: " + rs.getString("player"), e);
        }

        ItemStack item = null;
        if (type == ClaimType.ITEM) {
            byte[] data = rs.getBytes("item");
            if (data != null) {
                item = deserialize(data);
            }
        }

        return new ClaimEntry(
                rs.getInt("id"),
                player,
                type,
                item,
                rs.getDouble("money"),
                rs.getLong("created_at")
        );
    }

    // ── Serialisation ────────────────────────────────────────────────────────

    private byte[] serialize(ItemStack item) throws IOException {
        if (item == null) return null;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeObject(item);
        }
        return baos.toByteArray();
    }

    private ItemStack deserialize(byte[] data) throws IOException, ClassNotFoundException {
        if (data == null) return null;
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        try (BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            return (ItemStack) ois.readObject();
        }
    }
}