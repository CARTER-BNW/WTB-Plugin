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

    // Audit fix #10d: file writes drain through a single background thread.
    // The old code did a synchronized write+flush per line directly on the
    // CALLING thread — including main-thread paths (claim, create, confirm) —
    // so every log line was a disk sync competing with async threads for the
    // same lock, stalling ticks under load.
    private static final java.util.concurrent.BlockingQueue<String> QUEUE =
            new java.util.concurrent.LinkedBlockingQueue<>(10_000);
    private static volatile Thread  drainThread;
    private static volatile boolean running;

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
        running = true;
        drainThread = new Thread(LogService::drainLoop, "WTB-Log");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    /** Background loop: writes and flushes queued lines off the main thread. */
    private static void drainLoop() {
        try {
            while (running || !QUEUE.isEmpty()) {
                String line = QUEUE.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (line == null) continue;
                writeLine(line);
            }
        } catch (InterruptedException ignored) {
            // closeWriter() drains whatever is left
        }
    }

    /**
     * Every line is flushed immediately (as the old synchronous log() did) so
     * the on-disk audit trail survives a hard crash — the cost now lands on
     * this dedicated thread, never the main thread.  Also keeps the old
     * reopen-fallback: if init() failed to open the writer, retry per line
     * instead of silently dropping the whole session's audit log.
     */
    private static synchronized void writeLine(String line) {
        try {
            if (writer == null) {
                if (logFile == null) return;
                writer = new BufferedWriter(new FileWriter(logFile, true));
            }
            writer.write(line);
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Called from Main.onDisable() to drain remaining lines, then flush and
     * close the writer cleanly.  Runs AFTER DbExecutor.shutdownAndDrain() so
     * lines logged by draining DB tasks still land in the file.
     */
    public static void closeWriter() {
        running = false;
        Thread t = drainThread;
        if (t != null) {
            try {
                t.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drainThread = null;
        }
        synchronized (LogService.class) {
            String line;
            while ((line = QUEUE.poll()) != null) {
                writeLine(line);
            }
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
     * The console line is emitted immediately; the file line is queued for the
     * background drain thread, so no caller (main thread included) ever blocks
     * on disk I/O here.
     */
    public static void log(String message) {
        // Null-guard: instance is set in onEnable() and never explicitly nulled,
        // but a defensive check costs nothing and prevents NPE if somehow called
        // during a non-standard lifecycle (e.g. test harnesses, PlugMan reload).
        Main main = Main.getInstance();
        if (main != null) {
            main.getLogger().info(message);
        }
        String line = "[" + FORMAT.format(Instant.now()) + "] " + message + "\n";
        if (!QUEUE.offer(line) && main != null) {
            main.getLogger().warning("[WTB] File-log queue full — line not written to logs.txt");
        }
    }
}