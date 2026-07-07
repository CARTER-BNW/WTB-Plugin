package wtb.database;

import wtb.Main;

import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseManager {

    private static volatile DatabaseManager instance;

    private final DatabaseProvider provider;

    public DatabaseManager() {
        var config = Main.getInstance().getConfig();

        boolean useMySQL = config.getBoolean("mysql.enabled", false);

        if (useMySQL) {
            String host     = config.getString("mysql.host",     "localhost");
            String database = config.getString("mysql.database", "wtb");
            String username = config.getString("mysql.username", "root");
            String password = config.getString("mysql.password", "");
            int    port     = config.getInt("mysql.port",        3306);

            if (host.isBlank() || database.isBlank()) {
                throw new IllegalStateException("MySQL host/database must not be blank in config.yml");
            }

            provider = new MySQLDatabase(host, port, database, username, password);
        } else {
            provider = new SQLiteDatabase();
        }

        provider.init();
        instance = this; // publish after init — visible to all threads (volatile)
    }

    public static DatabaseManager get() {
        return instance;
    }

    public Connection getConnection() throws SQLException {
        return provider.getConnection();
    }

    public void shutdown() {
        provider.shutdown();
    }
}