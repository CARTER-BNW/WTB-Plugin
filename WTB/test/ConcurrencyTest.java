import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency stress test for the V6 fulfilment SQL — proves the atomic
 * RELATIVE claim (fulfillIfActive) cannot oversell under heavy contention,
 * and that escrow conservation holds exactly (paid + dust == priceCents).
 *
 * Mirrors ListingDAO.fulfillIfActive / settleDust byte-for-byte, plus a
 * concurrent cancel thread racing the sellers (cancelIfActive).
 */
public class ConcurrencyTest {

    static final String URL = "jdbc:sqlite:" +
            System.getProperty("db", "stress.db");

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        int failures = 0;
        failures += overSellScenario(false); // fills only
        failures += overSellScenario(true);  // fills + racing cancel
        System.out.println(failures == 0 ? "ALL CONCURRENCY TESTS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static Connection open() throws SQLException { Connection c = DriverManager.getConnection(URL); try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=10000"); } return c; }

    static int overSellScenario(boolean withCancel) throws Exception {
        java.io.File f = new java.io.File("stress.db");
        f.delete(); new java.io.File("stress.db-wal").delete(); new java.io.File("stress.db-shm").delete();

        final int  QTY        = 1_500;
        final long PRICE_CTS  = 1_234_567L; // deliberately awkward for floor dust
        final int  THREADS    = 12;
        final int  ROUNDS     = 2;          // scenario repeats

        int fails = 0;
        for (int round = 0; round < ROUNDS; round++) {
            try (Connection c = open();
                 Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL"); s.execute("DROP TABLE IF EXISTS listings");
                s.execute("""
                    CREATE TABLE listings (
                        id INTEGER PRIMARY KEY,
                        remaining_quantity INTEGER NOT NULL,
                        paid_cents INTEGER NOT NULL DEFAULT 0,
                        state TEXT NOT NULL
                    )""");
                s.execute("INSERT INTO listings (id, remaining_quantity, paid_cents, state) VALUES (1, "
                        + QTY + ", 0, 'OPEN')");
            }

            ExecutorService pool = Executors.newFixedThreadPool(THREADS + 1);
            AtomicLong claimedItems = new AtomicLong();
            AtomicLong claimedCents = new AtomicLong();
            AtomicLong refundCents  = new AtomicLong();
            CountDownLatch start = new CountDownLatch(1);

            Runnable seller = () -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                try (Connection c = open()) {
                    start.await();
                    while (true) {
                        int amt = 1 + rnd.nextInt(40);
                        long portion = (PRICE_CTS * amt) / QTY; // portionCents
                        try (PreparedStatement ps = c.prepareStatement("""
                                UPDATE listings
                                SET remaining_quantity = remaining_quantity - ?,
                                    paid_cents         = paid_cents + ?,
                                    state = CASE WHEN remaining_quantity - ? <= 0 THEN 'FILLED' ELSE 'PARTIAL' END
                                WHERE id = 1 AND state IN ('OPEN','PARTIAL') AND remaining_quantity >= ?
                                """)) {
                            ps.setInt(1, amt); ps.setLong(2, portion);
                            ps.setInt(3, amt); ps.setInt(4, amt);
                            if (ps.executeUpdate() > 0) {
                                claimedItems.addAndGet(amt);
                                claimedCents.addAndGet(portion);
                                // final-fill dust settlement
                                try (PreparedStatement q = c.prepareStatement(
                                        "SELECT remaining_quantity, paid_cents FROM listings WHERE id=1");
                                     ResultSet rs = q.executeQuery()) {
                                    if (rs.next() && rs.getInt(1) <= 0) {
                                        long paid = rs.getLong(2);
                                        long dust = PRICE_CTS - paid;
                                        if (dust > 0) {
                                            try (PreparedStatement d = c.prepareStatement(
                                                    "UPDATE listings SET paid_cents=? WHERE id=1 AND state='FILLED' AND paid_cents=?")) {
                                                d.setLong(1, PRICE_CTS); d.setLong(2, paid);
                                                if (d.executeUpdate() > 0) claimedCents.addAndGet(dust);
                                            }
                                        }
                                        return;
                                    }
                                }
                            } else {
                                // claim failed — listing done or cancelled
                                try (PreparedStatement q = c.prepareStatement(
                                        "SELECT state FROM listings WHERE id=1");
                                     ResultSet rs = q.executeQuery()) {
                                    if (rs.next() && !rs.getString(1).equals("OPEN")
                                            && !rs.getString(1).equals("PARTIAL")) return;
                                }
                            }
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
            };

            for (int i = 0; i < THREADS; i++) pool.submit(seller);

            if (withCancel) {
                pool.submit(() -> {
                    try (Connection c = open()) {
                        start.await();
                        Thread.sleep(15); // let some fills land first
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE listings SET state='CANCELLED' WHERE id=1 AND state IN ('OPEN','PARTIAL')")) {
                            if (ps.executeUpdate() > 0) {
                                try (PreparedStatement q = c.prepareStatement(
                                        "SELECT paid_cents FROM listings WHERE id=1");
                                     ResultSet rs = q.executeQuery()) {
                                    rs.next();
                                    refundCents.set(Math.max(0, PRICE_CTS - rs.getLong(1)));
                                }
                            }
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                });
            }

            start.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(40, TimeUnit.SECONDS)) { System.out.println("TIMEOUT"); return 1; }

            // ── Assertions ──
            long dbRemaining, dbPaid; String dbState;
            try (Connection c = open();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT remaining_quantity, paid_cents, state FROM listings WHERE id=1")) {
                rs.next();
                dbRemaining = rs.getLong(1); dbPaid = rs.getLong(2); dbState = rs.getString(3);
            }

            String tag = (withCancel ? "[cancel-race r" : "[fills r") + round + "] ";
            if (claimedItems.get() != QTY - dbRemaining) {
                System.out.println(tag + "OVERSELL/UNDERCOUNT: claimed=" + claimedItems.get()
                        + " but db shows " + (QTY - dbRemaining) + " sold"); fails++;
            }
            if (dbRemaining < 0) { System.out.println(tag + "NEGATIVE remaining " + dbRemaining); fails++; }
            if (claimedCents.get() != dbPaid) {
                System.out.println(tag + "cents mismatch: sellers got " + claimedCents.get()
                        + " db paid " + dbPaid); fails++;
            }
            long conserved = claimedCents.get() + refundCents.get();
            if (!withCancel) {
                if (dbRemaining != 0 || !dbState.equals("FILLED") || conserved != PRICE_CTS) {
                    System.out.println(tag + "conservation fail: paid+refund=" + conserved
                            + " price=" + PRICE_CTS + " state=" + dbState + " rem=" + dbRemaining); fails++;
                }
            } else {
                if (conserved != PRICE_CTS) {
                    System.out.println(tag + "conservation fail (cancel): paid=" + claimedCents.get()
                            + " refund=" + refundCents.get() + " sum=" + conserved
                            + " price=" + PRICE_CTS + " state=" + dbState); fails++;
                }
            }
            System.out.println(tag + "sold=" + claimedItems.get() + " paid=" + claimedCents.get()
                    + " refund=" + refundCents.get() + " state=" + dbState + " => "
                    + (fails == 0 ? "OK" : "FAIL"));
        }
        return fails;
    }
}
