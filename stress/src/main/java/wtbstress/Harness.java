package wtbstress;

import wtb.database.DatabaseManager;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates one stress run: wipe → seed → N actor threads for T seconds →
 * join → single-threaded audit + probes → report.
 */
final class Harness {

    private final StressMain plugin;
    private final int players;
    private final int seconds;
    private final boolean realistic;

    private final Ledger ledger = new Ledger();
    private final Ops ops = new Ops(ledger);

    /** realistic mode mirrors production's ≤3 concurrent DB users (DbExecutor 2 + main). */
    private final Semaphore gate;

    Harness(StressMain plugin, int players, int seconds, boolean realistic) {
        this.plugin = plugin;
        this.players = players;
        this.seconds = seconds;
        this.realistic = realistic;
        this.gate = realistic ? new Semaphore(3) : null;
    }

    void run() throws Exception {
        log("run starting: players=" + players + " seconds=" + seconds
                + " mode=" + (realistic ? "realistic" : "adversarial"));

        wipeTables();

        // Seed a SMALL pool of listings so all actors fight over the same rows.
        List<UUID> actors = new ArrayList<>();
        for (int i = 0; i < players; i++) actors.add(UUID.randomUUID());
        for (int i = 0; i < 10; i++) ops.create(actors.get(i % actors.size()));
        log("seeded " + ledger.listings.size() + " listings");

        AtomicBoolean stop = new AtomicBoolean(false);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            final UUID me = actors.get(i);
            Thread t = new Thread(() -> actorLoop(me, actors, stop), "WTBStress-Actor-" + i);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }

        long start = System.currentTimeMillis();
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        for (Thread t : threads) t.join(15_000);
        long elapsedMs = System.currentTimeMillis() - start;

        boolean anyAlive = threads.stream().anyMatch(Thread::isAlive);
        if (anyAlive) log("WARN: some actor threads did not stop within 15s (still counted)");

        // Final drain: claim every remaining box once, single-threaded, so the
        // exactly-once counters cover the whole population (audit tolerates
        // rows left in the box either way — this just exercises more claims).
        for (UUID a : actors) ops.claim(a);

        Auditor auditor = new Auditor(plugin, ledger, ops);
        String report = auditor.buildReport(players, seconds, elapsedMs, realistic);
        for (String line : report.split("\n")) log(line);
        auditor.writeReportFile(report);
    }

    // ── Actor ─────────────────────────────────────────────────────────────────

    private void actorLoop(UUID me, List<UUID> actors, AtomicBoolean stop) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (!stop.get()) {
            try {
                if (gate != null) gate.acquire();
                try {
                    int roll = rnd.nextInt(100);
                    if (roll < 55) {                      // FULFIL 55%
                        Ledger.Info target = randomListing(rnd);
                        if (target != null) ops.fulfil(me, target);
                    } else if (roll < 75) {               // CREATE 20%
                        ops.create(me);
                    } else if (roll < 85) {               // CANCEL 10% (any listing — races fulfil)
                        Ledger.Info target = randomListing(rnd);
                        if (target != null) ops.cancel(target);
                    } else if (roll < 93) {               // EXPIRE 8% (expired-eligible only)
                        Integer id = randomExpirable(rnd);
                        if (id != null) {
                            Ledger.Info info = ledger.listings.get(id);
                            if (info != null) ops.expire(info);
                        }
                    } else {                              // CLAIM 7% — sometimes someone
                        UUID box = rnd.nextInt(3) == 0    // else's box → claim-vs-claim race
                                ? actors.get(rnd.nextInt(actors.size())) : me;
                        ops.claim(box);
                    }
                } finally {
                    if (gate != null) gate.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                ledger.unexpected("actorLoop", t);
            }
        }
    }

    private Ledger.Info randomListing(ThreadLocalRandom rnd) {
        Object[] infos = ledger.listings.values().toArray();
        return infos.length == 0 ? null : (Ledger.Info) infos[rnd.nextInt(infos.length)];
    }

    private Integer randomExpirable(ThreadLocalRandom rnd) {
        Object[] ids = ledger.expirable.toArray();
        return ids.length == 0 ? null : (Integer) ids[rnd.nextInt(ids.length)];
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    /** TEST SERVER ONLY: the audit's whole-table sums must cover only this run. */
    private void wipeTables() throws Exception {
        try (Connection c = DatabaseManager.get().getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM claim_box");
            s.executeUpdate("DELETE FROM listings");
        }
        log("wiped listings + claim_box");
    }

    private void log(String msg) {
        plugin.getLogger().info("[WTBSTRESS] " + msg);
    }
}
