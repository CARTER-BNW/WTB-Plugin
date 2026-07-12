package wtb.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import wtb.Main;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

public class SQLiteDatabase implements DatabaseProvider {

    private HikariDataSource dataSource;

    @Override
    public void init() {
        File dbFile = new File(Main.getInstance().getDataFolder(), "wtb.db");
        File parent = dbFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new RuntimeException("Could not create data folder: " + parent.getAbsolutePath());
        }

        HikariConfig config = new HikariConfig();
        // WAL mode: concurrent reads don't block writes.
        // busy_timeout retries instead of immediately throwing SQLITE_BUSY.
        // Audit fix #9: these MUST be URL parameters (parsed per-connection by
        // the Xerial driver), NOT a multi-statement connectionInitSql — the
        // driver executes only the FIRST statement of a semicolon-joined
        // string, so busy_timeout and synchronous were silently never applied.
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath()
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(60_000);
        config.setPoolName("WTB-SQLite");

        dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection()) {
            createTables(conn);
            migrate(conn);
        } catch (SQLException e) {
            dataSource.close();
            throw new RuntimeException("Failed to initialise SQLite database", e);
        }

        Main.getInstance().getLogger().info("SQLite database initialised (HikariCP, WAL mode).");
    }

    private void createTables(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS listings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    buyer TEXT NOT NULL,
                    material TEXT NOT NULL,
                    enchant TEXT,
                    item BLOB,
                    custom_name TEXT,
                    original_quantity INTEGER NOT NULL,
                    remaining_quantity INTEGER NOT NULL,
                    original_price REAL NOT NULL,
                    paid_cents INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    state TEXT NOT NULL
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS claim_box (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player TEXT NOT NULL,
                    type TEXT NOT NULL,
                    item BLOB,
                    money REAL,
                    created_at INTEGER NOT NULL
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    buyer TEXT NOT NULL,
                    seller TEXT NOT NULL,
                    material TEXT NOT NULL,
                    enchant TEXT,
                    custom_name TEXT,
                    quantity INTEGER NOT NULL,
                    price REAL NOT NULL,
                    timestamp INTEGER NOT NULL,
                    type TEXT NOT NULL
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS price_history (
                    material TEXT PRIMARY KEY,
                    avg_price REAL,
                    min_price REAL,
                    max_price REAL,
                    total_volume INTEGER
                );
            """);

            // V6: offline "order filled / expired / cancelled" alerts.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player TEXT NOT NULL,
                    message TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );
            """);

            // V6.2: per-player preferences (e.g. /wtb settings mute).
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_settings (
                    player  TEXT NOT NULL,
                    setting TEXT NOT NULL,
                    value   TEXT NOT NULL,
                    PRIMARY KEY (player, setting)
                );
            """);

            // V6: admin-registered item catalog (god items, heads, banners, …).
            // Built-in registry entries are generated at boot, never stored here.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS catalog (
                    key TEXT PRIMARY KEY,
                    item BLOB NOT NULL,
                    label TEXT,
                    created_by TEXT,
                    created_at INTEGER NOT NULL
                );
            """);

            // Performance indexes — created once, silently skipped if they exist.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_listings_buyer   ON listings(buyer);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_listings_state   ON listings(state);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_listings_expires ON listings(expires_at);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_claimbox_player  ON claim_box(player);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_buyer         ON transactions(buyer);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_seller        ON transactions(seller);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_timestamp     ON transactions(timestamp);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_notify_player    ON notifications(player);");
        }
    }

    /**
     * In-place V5 → V6 schema migration.  Runs on every start and is a no-op
     * once applied, so a server owner just drops the new jar in — no manual DB
     * work, no config change (see docs: "Upgrading from v5").
     *
     * <p>Adds:
     * <ul>
     *   <li>{@code listings.enchant} — enchanted-book order spec</li>
     *   <li>{@code listings.item} / {@code listings.custom_name} — exact-item
     *       (catalog) order template + display label</li>
     *   <li>{@code listings.paid_cents} — exact escrow-conservation ledger.
     *       Backfilled for existing OPEN/PARTIAL rows from
     *       {@code price × filled/total} so cancels/expiries of pre-V6 partial
     *       listings refund correctly.</li>
     *   <li>{@code transactions.enchant} / {@code transactions.custom_name}
     *       — audit detail</li>
     * </ul>
     * The {@code catalog} table is created by createTables().
     */
    private void migrate(Connection conn) throws SQLException {
        var log = Main.getInstance().getLogger();

        if (ensureColumn(conn, "listings", "enchant", "TEXT")) {
            log.info("[WTB] Migration: added listings.enchant (V6).");
        }
        if (ensureColumn(conn, "listings", "item", "BLOB")) {
            log.info("[WTB] Migration: added listings.item (V6).");
        }
        if (ensureColumn(conn, "listings", "custom_name", "TEXT")) {
            log.info("[WTB] Migration: added listings.custom_name (V6).");
        }
        if (ensureColumn(conn, "listings", "paid_cents", "INTEGER NOT NULL DEFAULT 0")) {
            log.info("[WTB] Migration: added listings.paid_cents (V6).");
        }
        // Backfill: approximate cents already paid on pre-V6 rows.
        // CAST(... AS INTEGER) truncates toward zero == floor for positives.
        // Audit fix #4: runs EVERY boot against rows that still need it — the
        // old add-column-then-backfill pair was not atomic, so a backfill
        // failure on first V6 boot was never retried and pre-V6 partial
        // listings over-refunded on cancel/expiry.  The WHERE clause keeps
        // this idempotent (only active, partially-filled rows still at 0).
        try (var stmt = conn.createStatement()) {
            int rows = stmt.executeUpdate("""
                UPDATE listings
                SET paid_cents = CAST(original_price * 100.0
                        * (original_quantity - remaining_quantity)
                        / original_quantity AS INTEGER)
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
        if (ensureColumn(conn, "transactions", "enchant", "TEXT")) {
            log.info("[WTB] Migration: added transactions.enchant (V6).");
        }
        if (ensureColumn(conn, "transactions", "custom_name", "TEXT")) {
            log.info("[WTB] Migration: added transactions.custom_name (V6).");
        }
    }

    /**
     * Adds a column if it does not already exist.
     *
     * @return true if the column was ADDED by this call (i.e. first V6 boot).
     */
    private boolean ensureColumn(Connection conn, String table, String column,
                                 String definition) throws SQLException {
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
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
