package wtb.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import wtb.Main;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MySQLDatabase implements DatabaseProvider {

    private final String host;
    private final int    port;
    private final String database;
    private final String username;
    private final String password;

    private HikariDataSource dataSource;

    public MySQLDatabase(String host, int port, String database,
                         String username, String password) {
        this.host     = host;
        this.port     = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    @Override
    public void init() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&characterEncoding=utf8mb4");
        config.setUsername(username);
        config.setPassword(password);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        // Keep-alive ping prevents connections from being silently dropped
        // by MySQL's wait_timeout when the pool is idle.
        config.setKeepaliveTime(60_000);
        config.setPoolName("WTB-MySQL");

        // Prepared-statement cache
        config.addDataSourceProperty("cachePrepStmts",      "true");
        config.addDataSourceProperty("prepStmtCacheSize",   "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit","2048");
        config.addDataSourceProperty("useServerPrepStmts",  "true");

        dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection()) {
            createTables(conn);
            migrate(conn);
        } catch (SQLException e) {
            dataSource.close();
            throw new RuntimeException("Failed to initialise MySQL database", e);
        }

        Main.getInstance().getLogger().info("MySQL database initialised (HikariCP, pool=10).");
    }

    private void createTables(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS listings (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    buyer VARCHAR(36) NOT NULL,
                    material VARCHAR(64) NOT NULL,
                    enchant VARCHAR(96),
                    item BLOB,
                    custom_name VARCHAR(64),
                    original_quantity INT NOT NULL,
                    remaining_quantity INT NOT NULL,
                    original_price DOUBLE NOT NULL,
                    paid_cents BIGINT NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL,
                    expires_at BIGINT NOT NULL,
                    state VARCHAR(16) NOT NULL
                ) CHARACTER SET utf8mb4;
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS claim_box (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    player VARCHAR(36) NOT NULL,
                    type VARCHAR(16) NOT NULL,
                    item BLOB,
                    money DOUBLE,
                    created_at BIGINT NOT NULL
                ) CHARACTER SET utf8mb4;
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    buyer VARCHAR(36) NOT NULL,
                    seller VARCHAR(36) NOT NULL,
                    material VARCHAR(64) NOT NULL,
                    enchant VARCHAR(96),
                    custom_name VARCHAR(64),
                    quantity INT NOT NULL,
                    price DOUBLE NOT NULL,
                    timestamp BIGINT NOT NULL,
                    type VARCHAR(16) NOT NULL
                ) CHARACTER SET utf8mb4;
            """);

            // material is also the aggregation key for enchanted-book history
            // ("ENCHANTED_BOOK;minecraft:mending;1") — 128 chars covers custom
            // datapack enchantment keys.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS price_history (
                    material VARCHAR(128) PRIMARY KEY,
                    avg_price DOUBLE,
                    min_price DOUBLE,
                    max_price DOUBLE,
                    total_volume BIGINT
                ) CHARACTER SET utf8mb4;
            """);

            // V6: offline "order filled / expired / cancelled" alerts.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS notifications (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    player VARCHAR(36) NOT NULL,
                    message TEXT NOT NULL,
                    created_at BIGINT NOT NULL
                ) CHARACTER SET utf8mb4;
            """);

            // V6: admin-registered item catalog (god items, heads, banners, …).
            // Built-in registry entries are generated at boot, never stored here.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS catalog (
                    `key` VARCHAR(40) PRIMARY KEY,
                    item BLOB NOT NULL,
                    label VARCHAR(64),
                    created_by VARCHAR(32),
                    created_at BIGINT NOT NULL
                ) CHARACTER SET utf8mb4;
            """);

            // Performance indexes.
            // Audit fix #5: NO version of MySQL supports CREATE INDEX IF NOT
            // EXISTS (that is MariaDB syntax) — the old statements threw
            // SQLSyntaxErrorException on every boot and the catch swallowed it,
            // so no index was ever created.  Create without the clause and
            // treat only "duplicate key name" (error 1061) as the no-op.
            String[][] indexes = {
                    {"idx_listings_buyer",   "CREATE INDEX idx_listings_buyer   ON listings(buyer)"},
                    {"idx_listings_state",   "CREATE INDEX idx_listings_state   ON listings(state)"},
                    {"idx_listings_expires", "CREATE INDEX idx_listings_expires ON listings(expires_at)"},
                    {"idx_claimbox_player",  "CREATE INDEX idx_claimbox_player  ON claim_box(player)"},
                    {"idx_tx_buyer",         "CREATE INDEX idx_tx_buyer         ON transactions(buyer)"},
                    {"idx_tx_seller",        "CREATE INDEX idx_tx_seller        ON transactions(seller)"},
                    {"idx_tx_timestamp",     "CREATE INDEX idx_tx_timestamp     ON transactions(timestamp)"},
                    {"idx_notify_player",    "CREATE INDEX idx_notify_player    ON notifications(player)"}
            };
            for (String[] idx : indexes) {
                try {
                    stmt.execute(idx[1]);
                } catch (SQLException e) {
                    if (e.getErrorCode() != 1061) { // 1061 = ER_DUP_KEYNAME (already exists)
                        Main.getInstance().getLogger().warning(
                                "[WTB] Could not create index " + idx[0] + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * In-place V5 → V6 schema migration.  Runs on every start and is a no-op
     * once applied — drop the new jar in, no manual SQL, no config change.
     *
     * <p>Adds {@code listings.enchant}, {@code listings.item},
     * {@code listings.custom_name}, {@code listings.paid_cents} (with backfill
     * for pre-V6 partial listings), {@code transactions.enchant},
     * {@code transactions.custom_name}, and widens
     * {@code price_history.material} for book aggregation keys.
     * The {@code catalog} table is created by createTables().
     */
    private void migrate(Connection conn) throws SQLException {
        var log = Main.getInstance().getLogger();

        if (ensureColumn(conn, "listings", "enchant", "VARCHAR(96) NULL")) {
            log.info("[WTB] Migration: added listings.enchant (V6).");
        }
        if (ensureColumn(conn, "listings", "item", "BLOB NULL")) {
            log.info("[WTB] Migration: added listings.item (V6).");
        }
        if (ensureColumn(conn, "listings", "custom_name", "VARCHAR(64) NULL")) {
            log.info("[WTB] Migration: added listings.custom_name (V6).");
        }
        if (ensureColumn(conn, "listings", "paid_cents", "BIGINT NOT NULL DEFAULT 0")) {
            log.info("[WTB] Migration: added listings.paid_cents (V6).");
        }
        // Audit fix #4: the backfill runs EVERY boot against rows that still
        // need it, not only in the same breath as the column-add.  The old
        // shape (add column, then backfill as a second statement) was not
        // atomic: DDL auto-commits, so a backfill failure on first V6 boot
        // left the column present and the backfill skipped forever — a
        // pre-V6 partially-filled listing with paid_cents=0 then refunded the
        // FULL price on cancel/expiry even though items had been delivered.
        // The WHERE clause makes this idempotent: it only touches active,
        // partially-filled rows that still carry the pre-V6 default of 0.
        try (var stmt = conn.createStatement()) {
            int rows = stmt.executeUpdate("""
                UPDATE listings
                SET paid_cents = FLOOR(original_price * 100.0
                        * (original_quantity - remaining_quantity)
                        / original_quantity)
                WHERE original_quantity > 0
                  AND paid_cents = 0
                  AND remaining_quantity < original_quantity
                  AND state IN ('OPEN','PARTIAL')
            """);
            if (rows > 0) {
                log.info("[WTB] Migration: backfilled paid_cents on " + rows
                        + " pre-V6 partially-filled row(s).");
            }
        }
        if (ensureColumn(conn, "transactions", "enchant", "VARCHAR(96) NULL")) {
            log.info("[WTB] Migration: added transactions.enchant (V6).");
        }
        if (ensureColumn(conn, "transactions", "custom_name", "VARCHAR(64) NULL")) {
            log.info("[WTB] Migration: added transactions.custom_name (V6).");
        }

        // Widen the price_history key column for enchanted-book aggregation keys.
        // Audit fix #17: only run the MODIFY when the column is actually
        // narrower than 128 — an unconditional MODIFY can trigger a table
        // rebuild on every boot.
        boolean needsWiden = false;
        try (PreparedStatement stmt = conn.prepareStatement("""
                SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'price_history' AND column_name = 'material'
                """);
             ResultSet rs = stmt.executeQuery()) {
            needsWiden = rs.next() && rs.getLong(1) < 128;
        } catch (SQLException ignored) {
            // information_schema unreadable — skip rather than rebuild blindly.
        }
        if (needsWiden) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE price_history MODIFY material VARCHAR(128) NOT NULL");
                log.info("[WTB] Migration: widened price_history.material to VARCHAR(128).");
            } catch (SQLException ignored) {
                // Insufficient privileges — non-fatal.
            }
        }
    }

    /**
     * Adds a column if it does not already exist (information_schema check —
     * portable across MySQL 5.7 / 8.x, unlike ADD COLUMN IF NOT EXISTS).
     *
     * @return true if the column was ADDED by this call (i.e. first V6 boot).
     */
    private boolean ensureColumn(Connection conn, String table, String column,
                                 String definition) throws SQLException {
        String check = """
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
        """;
        try (PreparedStatement stmt = conn.prepareStatement(check)) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return false; // already present
                }
            }
        }
        try (var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
        return true;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
