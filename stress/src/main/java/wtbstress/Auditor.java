package wtbstress;

import wtb.Main;
import wtb.database.ClaimBoxDAO;
import wtb.database.DatabaseManager;
import wtb.database.ListingDAO;
import wtb.models.ClaimEntry;
import wtb.models.Listing;
import wtb.models.ListingState;
import wtb.utils.Payout;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.FileWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Single-threaded end-of-run audit.  All money in integer CENTS.
 *
 * <p>The claim_box.money column is REAL (dollars); the authoritative sums here
 * convert each row via Payout.toCents and add as longs — never SQL SUM(money)
 * (I10 cross-checks the float sum against the exact one).
 */
final class Auditor {

    private final StressMain plugin;
    private final Ledger ledger;
    private final Ops ops;

    private final StringBuilder out = new StringBuilder();
    private int failures = 0;

    Auditor(StressMain plugin, Ledger ledger, Ops ops) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.ops = ops;
    }

    String buildReport(int players, int seconds, long elapsedMs, boolean realistic) {
        try {
            header(players, seconds, elapsedMs, realistic);
            invariants();   // reads the DB BEFORE probes mutate it
            probes();
        } catch (Exception e) {
            fail("AUDITOR CRASHED: " + e);
            e.printStackTrace();
        }
        out.append("\n").append(failures == 0
                ? "RESULT: PASS (0 violations)"
                : "RESULT: FAIL (" + failures + " violations)");
        return out.toString();
    }

    // ── Header / op stats ─────────────────────────────────────────────────────

    private void header(int players, int seconds, long elapsedMs, boolean realistic) {
        long okOps = ledger.opCreate.sum() + ledger.opFulfil.sum() + ledger.opCancel.sum()
                + ledger.opExpire.sum() + ledger.opClaim.sum();
        long aborts = ledger.benignAborts.sum();
        out.append("==== WTB STRESS REPORT ====\n")
           .append("mode=").append(realistic ? "realistic" : "adversarial")
           .append(" players=").append(players).append(" seconds=").append(seconds)
           .append(" elapsedMs=").append(elapsedMs).append('\n')
           .append("ops: create=").append(ledger.opCreate.sum())
           .append(" fulfil=").append(ledger.opFulfil.sum())
           .append(" cancel=").append(ledger.opCancel.sum())
           .append(" expire=").append(ledger.opExpire.sum())
           .append(" claim=").append(ledger.opClaim.sum())
           .append(" | successful=").append(okOps)
           .append(" benignAborts=").append(aborts)
           .append(" ops/sec=").append(String.format("%.1f", okOps * 1000.0 / Math.max(1, elapsedMs)))
           .append('\n');
        if (okOps > 0 && aborts > okOps) {
            out.append("WARN: benign-abort rate ").append(aborts).append('/').append(aborts + okOps)
               .append(" — pool saturated; coverage thin (tuning signal, not a bug)\n");
        }
        long unexpectedCount = ledger.unexpected.size();
        if (unexpectedCount > 0) {
            failures++;
            out.append("UNEXPECTED EXCEPTIONS: ").append(unexpectedCount).append(" — FAIL\n");
            ledger.unexpected.stream().limit(5).forEach(s -> out.append("  ").append(s).append('\n'));
        } else {
            out.append("unexpected exceptions: 0 — OK\n");
        }
    }

    // ── Invariants I1-I10 ─────────────────────────────────────────────────────

    private void invariants() throws Exception {
        long deposits = 0, paidTotal = 0, refundOwed = 0, escrowActive = 0;
        long refundEligibleTerminals = 0;
        int listingRows = 0;
        boolean i4 = true, i5 = true, i6 = true, i7 = true;

        try (Connection c = DatabaseManager.get().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id, original_quantity, remaining_quantity, original_price, paid_cents, state FROM listings")) {
            while (rs.next()) {
                listingRows++;
                int    id    = rs.getInt(1);
                int    oq    = rs.getInt(2);
                int    rem   = rs.getInt(3);
                long   price = Payout.toCents(rs.getDouble(4));
                long   paid  = rs.getLong(5);
                String state = rs.getString(6);

                deposits  += price;
                paidTotal += paid;
                switch (state) {
                    case "CANCELLED", "EXPIRED" -> {
                        long r = price - paid;
                        refundOwed += Math.max(0, r);
                        if (r > 0) refundEligibleTerminals++;
                    }
                    case "OPEN", "PARTIAL" -> escrowActive += price - paid;
                    default -> { }
                }

                long filledLedger = ledger.filledOf(id);
                if (oq - rem != filledLedger) {
                    i4 = fail("I4 oversell/undercount: listing " + id + " db sold " + (oq - rem)
                            + " but ledger committed " + filledLedger);
                }
                if (rem < 0 || rem > oq) i4 = fail("I4 remaining out of range: listing " + id + " rem=" + rem);
                if (state.equals("FILLED") && (paid != price || rem != 0)) {
                    i5 = fail("I5 FILLED not settled: listing " + id + " paid=" + paid
                            + " price=" + price + " rem=" + rem);
                }
                if ((state.equals("OPEN") || state.equals("PARTIAL")) && rem <= 0) {
                    i6 = fail("I6 active-but-empty: listing " + id + " state=" + state + " rem=" + rem);
                }
                if (paid > price || paid < 0) {
                    i7 = fail("I7 paid out of range: listing " + id + " paid=" + paid + " price=" + price);
                }
            }
        }
        if (i4) ok("I4 no oversell (per-listing fills match DB exactly, " + listingRows + " listings)");
        if (i5) ok("I5 FILLED => fully paid + dust settled");
        if (i6) ok("I6 no active-but-empty rows");
        if (i7) ok("I7 0 <= paid <= price everywhere");

        // claim_box: exact per-row cents + float cross-check
        long moneyInBox = 0, refundInBox = 0, itemRowsInBox = 0, boxRows = 0;
        long refundRowsInBox = 0;
        double floatSum = 0;
        try (Connection c = DatabaseManager.get().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT type, money FROM claim_box")) {
            while (rs.next()) {
                boxRows++;
                String type = rs.getString(1);
                double m    = rs.getDouble(2);
                switch (type) {
                    case "MONEY"  -> moneyInBox  += Payout.toCents(m);
                    case "REFUND" -> { refundInBox += Payout.toCents(m); refundRowsInBox++; }
                    case "ITEM"   -> itemRowsInBox++;
                }
            }
        }
        try (Connection c = DatabaseManager.get().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COALESCE(SUM(money),0) FROM claim_box WHERE type IN ('MONEY','REFUND')")) {
            rs.next();
            floatSum = rs.getDouble(1);
        }

        long moneyCredited  = moneyInBox  + ledger.claimedMoneyCents.sum();
        long refundCredited = refundInBox + ledger.claimedRefundCents.sum();

        check("I1 payouts materialized exactly",
                paidTotal == moneyCredited,
                "paid_cents total=" + paidTotal + " vs MONEY credited=" + moneyCredited);
        check("I2 refunds materialized exactly",
                refundOwed == refundCredited,
                "refund owed=" + refundOwed + " vs REFUND credited=" + refundCredited);
        check("I3 grand conservation (to the cent)",
                deposits == moneyCredited + refundCredited + escrowActive,
                "deposits=" + deposits + " payouts=" + moneyCredited + " refunds=" + refundCredited
                        + " activeEscrow=" + escrowActive
                        + " diff=" + (deposits - moneyCredited - refundCredited - escrowActive));
        check("I3b ledger deposits match DB",
                ledger.depositedCents.sum() == deposits,
                "ledger=" + ledger.depositedCents.sum() + " db=" + deposits);

        long insertedRows = ledger.committedFulfils.sum() + ledger.itemRowsInserted.sum()
                + refundEligibleTerminals;
        check("I8 claims exactly-once (row count balance)",
                insertedRows == boxRows + ledger.claimedRows.sum(),
                "inserted=" + insertedRows + " inBox=" + boxRows
                        + " claimed=" + ledger.claimedRows.sum());
        long refundRowsEver = refundRowsInBox + ledger.claimedRefundRows.sum();
        check("I9 refunds exactly-once",
                refundEligibleTerminals == refundRowsEver,
                "terminal-with-remainder=" + refundEligibleTerminals
                        + " refundRows(box+claimed)=" + refundRowsEver);
        long exactAsFloatCents = Math.round(floatSum * 100.0);
        check("I10 REAL-column precision cross-check",
                exactAsFloatCents == moneyInBox + refundInBox,
                "SQL float SUM=" + exactAsFloatCents + " exact long-sum=" + (moneyInBox + refundInBox));

        out.append(String.format(
                "money table: deposits=%d paid=%d refundOwed=%d activeEscrow=%d "
                        + "boxMONEY=%d boxREFUND=%d boxITEMrows=%d claimedMONEY=%d claimedREFUND=%d itemsClaimed=%d%n",
                deposits, paidTotal, refundOwed, escrowActive, moneyInBox, refundInBox, itemRowsInBox,
                ledger.claimedMoneyCents.sum(), ledger.claimedRefundCents.sum(),
                ledger.claimedItemRows.sum()));
    }

    // ── Probes P1-P6 (mutate the DB — run AFTER the invariant reads) ─────────

    private void probes() throws Exception {
        // P1 — Payout dust property (pure math)
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boolean p1 = true;
        for (int i = 0; i < 5_000 && p1; i++) {
            long price = 1 + rnd.nextLong(10_000_000);
            int  qty   = 1 + rnd.nextInt(1_000);
            int  left  = qty;
            long paid  = 0;
            while (left > 0) {
                int amt = 1 + rnd.nextInt(left);
                left -= amt;
                if (left == 0) {
                    paid += Payout.remainder(price, paid);      // final fill takes the dust
                } else {
                    paid += Payout.portionCents(price, qty, amt);
                }
            }
            if (paid != price) {
                p1 = fail("P1 dust property broken: price=" + price + " qty=" + qty + " paid=" + paid);
            }
        }
        if (Payout.portionCents(1000, 10, 0) != 0 || Payout.portionCents(1000, 10, -5) != 0) {
            p1 = fail("P1 portionCents(<=0) != 0");
        }
        if (p1) ok("P1 Payout dust property (5000 random partitions sum exactly)");

        ListingDAO dao = new ListingDAO();

        // P2 — DAO zero/negative amounts must be rejected at the DAO too
        // (defense-in-depth guard added after the first stress run found that
        // a negative amount INCREASED remaining_quantity at the raw DAO level).
        int p2id = insertSynthetic(10, 10, 10.00, "OPEN");
        boolean zeroCommitted = dao.fulfillIfActive(p2id, 0, 5);
        boolean negCommitted  = dao.fulfillIfActive(p2id, -3, 0);
        Listing p2 = dao.getById(p2id);
        check("P2 DAO rejects zero/negative amounts",
                !zeroCommitted && !negCommitted
                        && p2 != null && p2.getRemainingQuantity() == 10 && p2.getPaidCents() == 0,
                "zero=" + zeroCommitted + " neg=" + negCommitted
                        + " rem=" + (p2 != null ? p2.getRemainingQuantity() : -1)
                        + " paid=" + (p2 != null ? p2.getPaidCents() : -1));

        // P3 — settleDust idempotency
        int p3id = insertSynthetic(5, 0, 7.77, "FILLED");
        dao.settleDust(p3id, 777);
        dao.settleDust(p3id, 777);
        Listing p3 = dao.getById(p3id);
        check("P3 settleDust idempotent", p3 != null && p3.getPaidCents() == 777,
                "paid=" + (p3 != null ? p3.getPaidCents() : -1) + " expected 777");
        int p3b = insertSynthetic(5, 5, 7.77, "OPEN");
        dao.settleDust(p3b, 777);
        Listing p3bRow = dao.getById(p3b);
        check("P3b settleDust no-op on non-FILLED", p3bRow != null && p3bRow.getPaidCents() == 0,
                "paid=" + (p3bRow != null ? p3bRow.getPaidCents() : -1) + " expected 0");

        // P4 — backfill idempotency (same SQL as SQLiteDatabase.migrate)
        int p4id = insertSynthetic(10, 4, 100.00, "PARTIAL"); // pre-V6-style: paid_cents 0, partially filled
        String backfill = """
            UPDATE listings
            SET paid_cents = CAST(original_price * 100.0
                    * (original_quantity - remaining_quantity)
                    / original_quantity AS INTEGER)
            WHERE original_quantity > 0
              AND paid_cents = 0
              AND remaining_quantity < original_quantity
              AND state IN ('OPEN','PARTIAL')
              AND id = %d
            """.formatted(p4id);
        int run1, run2;
        try (Connection c = DatabaseManager.get().getConnection();
             Statement s = c.createStatement()) {
            run1 = s.executeUpdate(backfill);
            run2 = s.executeUpdate(backfill);
        }
        Listing p4 = dao.getById(p4id);
        check("P4 backfill idempotent", run1 == 1 && run2 == 0
                        && p4 != null && p4.getPaidCents() == 6000,
                "run1=" + run1 + " run2=" + run2
                        + " paid=" + (p4 != null ? p4.getPaidCents() : -1) + " expected 6000");

        // P5 — cancel-after-fill returns false, no refund
        int p5id = insertSynthetic(4, 4, 4.44, "OPEN");
        dao.fulfillIfActive(p5id, 4, 444);
        boolean cancelled = dao.cancelIfActive(p5id);
        check("P5 cancel-after-full-fill rejected", !cancelled, "cancelIfActive returned true");

        // P6 — triple race: full-fulfil vs cancel vs expire on one listing
        boolean p6ok = tripleRace();
        if (p6ok) ok("P6 fulfil/cancel/expire triple race: one terminal state, exact conservation");
    }

    /** Fires fulfil+cancel+expire simultaneously at one listing; exactly one must win. */
    private boolean tripleRace() throws Exception {
        ListingDAO dao = new ListingDAO();
        ClaimBoxDAO claims = new ClaimBoxDAO();
        boolean allOk = true;

        for (int round = 0; round < 20; round++) {
            UUID buyer  = UUID.randomUUID();
            UUID seller = UUID.randomUUID();
            long priceCents = 1_237;                 // dust-prone
            int  qty = 7;
            wtb.models.Listing l = new wtb.models.Listing(-1, buyer, Material.DIRT, null, null, null,
                    qty, qty, Payout.toMoney(priceCents), System.currentTimeMillis(),
                    System.currentTimeMillis() - 1000, ListingState.OPEN);
            wtb.models.Listing created = dao.create(l);
            if (created == null) continue;
            final int id = created.getId();

            CountDownLatch go = new CountDownLatch(1);
            Thread fulfil = new Thread(() -> {
                try {
                    go.await();
                    Ops.FULFILL.invoke(Main.getListingService(), id, qty,
                            Payout.portionCents(priceCents, qty, qty), priceCents,
                            seller, buyer, List.of(new ItemStack(Material.DIRT, 7)));
                } catch (Throwable ignored) { }
            });
            Thread cancel = new Thread(() -> {
                try {
                    go.await();
                    wtb.models.Listing fresh = dao.getById(id);
                    if (fresh != null) Main.getListingService().cancelListing(fresh);
                } catch (Throwable ignored) { }
            });
            Thread expire = new Thread(() -> {
                try {
                    go.await();
                    if (dao.setExpiredIfActive(id)) {
                        wtb.models.Listing fresh = dao.getById(id);
                        if (fresh != null) {
                            long refund = Payout.remainder(fresh.getPriceCents(), fresh.getPaidCents());
                            if (refund > 0) new wtb.services.ClaimBoxService()
                                    .addRefundDirect(fresh.getBuyer(), Payout.toMoney(refund));
                        }
                    }
                } catch (Throwable ignored) { }
            });
            fulfil.start(); cancel.start(); expire.start();
            go.countDown();
            fulfil.join(20_000); cancel.join(20_000); expire.join(20_000);

            wtb.models.Listing end = dao.getById(id);
            if (end == null) { allOk = fail("P6 listing vanished"); continue; }

            long refundCents = 0;
            for (ClaimEntry e : claims.get(buyer)) {
                if (e.getType() == wtb.models.ClaimType.REFUND) refundCents += Payout.toCents(e.getMoney());
            }
            long paid = end.getPaidCents();
            String st = end.getState().name();
            boolean terminal = st.equals("FILLED") || st.equals("CANCELLED") || st.equals("EXPIRED");
            if (!terminal) allOk = fail("P6 round " + round + ": non-terminal state " + st);
            if (paid + refundCents != priceCents) {
                allOk = fail("P6 round " + round + ": conservation broke — state=" + st
                        + " paid=" + paid + " refund=" + refundCents + " price=" + priceCents);
            }
        }
        return allOk;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int insertSynthetic(int qty, int remaining, double price, String state) throws Exception {
        UUID buyer = UUID.randomUUID();
        try (Connection c = DatabaseManager.get().getConnection();
             var ps = c.prepareStatement("""
                     INSERT INTO listings (buyer, material, original_quantity, remaining_quantity,
                                           original_price, paid_cents, created_at, expires_at, state)
                     VALUES (?, 'DIRT', ?, ?, ?, 0, ?, ?, ?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, buyer.toString());
            ps.setInt(2, qty);
            ps.setInt(3, remaining);
            ps.setDouble(4, price);
            ps.setLong(5, System.currentTimeMillis());
            ps.setLong(6, System.currentTimeMillis() + 3_600_000);
            ps.setString(7, state);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private boolean fail(String msg) {
        failures++;
        out.append("FAIL ").append(msg).append('\n');
        return false;
    }

    private void ok(String msg) {
        out.append("OK   ").append(msg).append('\n');
    }

    private void check(String name, boolean cond, String detail) {
        if (cond) ok(name);
        else fail(name + " — " + detail);
    }

    void writeReportFile(String report) {
        try (FileWriter w = new FileWriter(new File("wtbstress-report.txt"), false)) {
            w.write(report);
            w.write("\n\nNOT COVERED at this layer (needs real players): self-trade guard, "
                    + "inventory template matching, Vault balance checks, GUI click handling.\n");
        } catch (Exception e) {
            plugin.getLogger().warning("[WTBSTRESS] could not write report file: " + e);
        }
    }
}
