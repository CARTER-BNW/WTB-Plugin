package wtb.database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for admin-registered catalog entries.
 * Built-in (registry-generated) entries are never stored here.
 *
 * Row: [0]=key (String), [1]=item bytes (byte[]), [2]=label (String|null).
 */
public class CatalogDAO {

    public boolean insert(String key, byte[] itemBytes, String label, String createdBy) {
        String sql = """
            INSERT INTO catalog (`key`, item, label, created_by, created_at)
            VALUES (?, ?, ?, ?, ?)
        """;
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            stmt.setBytes(2, itemBytes);
            if (label != null) stmt.setString(3, label); else stmt.setNull(3, Types.VARCHAR);
            stmt.setString(4, createdBy);
            stmt.setLong(5, System.currentTimeMillis());
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean delete(String key) {
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement("DELETE FROM catalog WHERE `key`=?")) {
            stmt.setString(1, key);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public List<Object[]> loadAll() {
        List<Object[]> rows = new ArrayList<>();
        try (var conn = DatabaseManager.get().getConnection();
             var stmt = conn.prepareStatement("SELECT `key`, item, label FROM catalog");
             var rs   = stmt.executeQuery()) {
            while (rs.next()) {
                rows.add(new Object[]{ rs.getString(1), rs.getBytes(2), rs.getString(3) });
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return rows;
    }
}
