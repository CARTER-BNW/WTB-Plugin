package wtb.services;

import wtb.Main;
import wtb.utils.NameCache;

import java.io.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class LogService {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static File logFile;
    private static BufferedWriter writer;

    /**
     * Called from Main.onEnable() before any logging happens.
     * Opens (and creates if needed) the append-mode log file.
     */
    public static synchronized void init(File dataFolder) {
        logFile = new File(dataFolder, "logs.txt");
        try {
            if (!logFile.getParentFile().exists()) logFile.getParentFile().mkdirs();
            writer = new BufferedWriter(new FileWriter(logFile, true));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Called from Main.onDisable() to flush and close the writer cleanly. */
    public static synchronized void closeWriter() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                writer = null;
            }
        }
    }

    /**
     * Resolves a UUID to a display name without blocking disk I/O on the main thread
     * when the player is online (NameCache checks the online-player map first).
     */
    public static String name(UUID uuid) {
        return NameCache.getName(uuid);
    }

    /**
     * Category-gated log.
     * "logging.enabled" is the master switch; "logging.{category}" gates the specific
     * event type.  Both must be true for the message to be written.
     *
     * <p>Reads the settings reference once into a local variable so that both
     * boolean checks see the same configuration snapshot even if a reload races
     * between them (settings is volatile in Main).
     */
    public static void log(String category, String message) {
        // Read once — settings is volatile, so a concurrent reload could otherwise
        // produce inconsistent results across the two getBoolean() calls.
        var cfg = Main.getSettings();
        if (cfg == null) return; // called before onEnable() — shouldn't happen, but guard anyway
        if (!cfg.getBoolean("logging.enabled", true)) return;
        if (!cfg.getBoolean("logging." + category, true)) return;
        log(message);
    }

    /**
     * Unconditional log — use for internal errors or admin-facing output.
     * Keeps the BufferedWriter open so every call is a buffered write, not a
     * full open-write-close cycle.  Flushes immediately so lines appear in the
     * file without a server restart.
     */
    public static synchronized void log(String message) {
        try {
            if (writer == null) {
                // Fallback: writer not initialised (shouldn't happen after onEnable)
                if (logFile != null) {
                    writer = new BufferedWriter(new FileWriter(logFile, true));
                } else {
                    return;
                }
            }
            String line = "[" + FORMAT.format(Instant.now()) + "] " + message + "\n";
            writer.write(line);
            writer.flush();

            // Null-guard: instance is set in onEnable() and never explicitly nulled,
            // but a defensive check costs nothing and prevents NPE if somehow called
            // during a non-standard lifecycle (e.g. test harnesses, PlugMan reload).
            Main main = Main.getInstance();
            if (main != null) {
                main.getLogger().info(message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}