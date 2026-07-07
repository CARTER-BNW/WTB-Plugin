package wtb.database;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Plugin-owned executor for asynchronous database writes (audit fix #2).
 *
 * <p>V6.0 dispatched DB writes through
 * {@code Bukkit.getScheduler().runTaskAsynchronously(...)}.  That is unsafe at
 * shutdown: the Bukkit scheduler only dispatches queued async tasks on the
 * main-thread heartbeat, which stops ticking once the server begins stopping —
 * so a reward/refund insert queued moments before {@code /stop} simply never
 * ran, and an in-flight one raced {@code onDisable()} closing the Hikari pool.
 * Either way the player's money or items were gone.
 *
 * <p>This executor is owned by the plugin: {@link #shutdownAndDrain} runs in
 * {@code onDisable()} BEFORE the connection pool closes, executing every
 * queued write and waiting for in-flight ones to finish.
 */
public final class DbExecutor {

    private static final Logger LOG = Logger.getLogger("Minecraft");

    private static volatile ScheduledThreadPoolExecutor pool;

    private DbExecutor() {}

    /** Called from Main.onEnable() before any service is constructed. */
    public static synchronized void init() {
        if (pool != null && !pool.isShutdown()) return;
        AtomicInteger n = new AtomicInteger(1);
        ScheduledThreadPoolExecutor p = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "WTB-DB-" + n.getAndIncrement());
            t.setDaemon(false); // must survive until shutdownAndDrain finishes
            return t;
        });
        // On shutdown: run everything already queued, but fire no NEW periodic
        // runs.  NOTE: submit()/execute() tasks are stored internally as
        // zero-delay ScheduledFutureTasks, so the EXISTING-DELAYED policy MUST
        // be true — with false, shutdown() would cancel every queued DB write,
        // recreating the exact data-loss bug this class exists to fix.
        p.setExecuteExistingDelayedTasksAfterShutdownPolicy(true);
        p.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        pool = p;
    }

    /**
     * Submits an async DB task.
     *
     * @return true if the task was accepted; false if the executor is already
     *         drained (plugin shutting down).  Callers holding resources that
     *         the task was going to persist (removed items, withdrawn money)
     *         MUST compensate synchronously when this returns false.
     */
    public static boolean submit(Runnable task) {
        ScheduledThreadPoolExecutor p = pool;
        if (p == null || p.isShutdown()) {
            LOG.severe("[WTB] DB task rejected — executor not running.");
            return false;
        }
        try {
            p.execute(wrap(task));
            return true;
        } catch (RejectedExecutionException e) {
            LOG.severe("[WTB] DB task rejected during shutdown.");
            return false;
        }
    }

    /** Fixed-delay repeating task (used by ExpiryService so sweeps drain on shutdown too). */
    public static ScheduledFuture<?> scheduleRepeating(Runnable task, long initialDelayMs, long periodMs) {
        ScheduledThreadPoolExecutor p = pool;
        if (p == null || p.isShutdown()) return null;
        return p.scheduleWithFixedDelay(wrap(task), initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops accepting new tasks, executes everything already queued, and waits
     * up to {@code timeoutSeconds} for in-flight tasks to finish.  MUST be
     * called from Main.onDisable() BEFORE DatabaseManager.shutdown().
     */
    public static synchronized void shutdownAndDrain(long timeoutSeconds) {
        ScheduledThreadPoolExecutor p = pool;
        pool = null;
        if (p == null) return;
        p.shutdown();
        try {
            if (!p.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                LOG.severe("[WTB] Async DB writes still running after " + timeoutSeconds
                        + "s — forcing shutdown; some writes may be lost.");
                p.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.shutdownNow();
        }
    }

    private static Runnable wrap(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.severe("[WTB] Async DB task failed: " + t);
                t.printStackTrace();
            }
        };
    }
}
