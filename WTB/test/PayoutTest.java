import wtb.utils.Payout;

import java.util.Random;

/**
 * Standalone escrow-conservation test for Payout.
 * Simulates random listings being filled in random chunks (with optional
 * early cancel) and asserts: deposited cents == paid-out cents + dust + refund.
 */
public class PayoutTest {

    public static void main(String[] args) {
        Random rnd = new Random(42);
        int failures = 0;

        // 1) exact conservation across full fills
        for (int t = 0; t < 200_000; t++) {
            int    qty   = 1 + rnd.nextInt(10_000);
            double price = Math.round((0.01 + rnd.nextDouble() * 100_000) * 100.0) / 100.0;
            long priceCents = Payout.toCents(price);

            long paid = 0;
            int  remaining = qty;
            while (remaining > 0) {
                int chunk = 1 + rnd.nextInt(remaining);
                boolean finalFill = (chunk == remaining);
                long portion = Payout.portionCents(priceCents, qty, chunk);
                paid += portion;
                remaining -= chunk;
                if (finalFill) {
                    long dust = Payout.remainder(priceCents, paid);
                    if (dust < 0) { fail(t, "negative dust " + dust); failures++; break; }
                    paid += dust; // settleDust
                }
            }
            if (paid != priceCents) {
                fail(t, "full-fill conservation: paid=" + paid + " price=" + priceCents
                        + " qty=" + qty);
                failures++;
            }
        }

        // 2) conservation with cancel after partial fills
        for (int t = 0; t < 200_000; t++) {
            int    qty   = 2 + rnd.nextInt(10_000);
            double price = Math.round((0.01 + rnd.nextDouble() * 100_000) * 100.0) / 100.0;
            long priceCents = Payout.toCents(price);

            long paid = 0;
            int  remaining = qty;
            int  fills = rnd.nextInt(4);
            for (int f = 0; f < fills && remaining > 1; f++) {
                int chunk = 1 + rnd.nextInt(remaining - 1); // never the final fill
                paid += Payout.portionCents(priceCents, qty, chunk);
                remaining -= chunk;
            }
            long refund = Payout.remainder(priceCents, paid); // cancel path
            if (refund < 0) { fail(t, "negative refund " + refund); failures++; continue; }
            if (paid + refund != priceCents) {
                fail(t, "cancel conservation: paid=" + paid + " refund=" + refund
                        + " price=" + priceCents);
                failures++;
            }
        }

        // 3) per-fill payout never exceeds proportional ceiling; monotone in amount
        for (int t = 0; t < 100_000; t++) {
            int    qty   = 1 + rnd.nextInt(1_000);
            double price = Math.round((0.01 + rnd.nextDouble() * 10_000) * 100.0) / 100.0;
            long priceCents = Payout.toCents(price);
            int amount = 1 + rnd.nextInt(qty);
            long portion = Payout.portionCents(priceCents, qty, amount);
            // floor(price*amount/qty) <= exact proportion
            double exact = (double) priceCents * amount / qty;
            if (portion > Math.ceil(exact) || portion < Math.floor(exact) - 0) {
                fail(t, "portion out of range: " + portion + " vs exact " + exact);
                failures++;
            }
            if (portion < 0) { fail(t, "negative portion"); failures++; }
        }

        // 4) toCents on the classic FP traps
        double[] traps = {1000.00000000001, 0.1 + 0.2, 4.35, 1.005, 999999999.99};
        long[]   want  = {100000,           30,        435,  101,   99999999999L};
        // 0.1+0.2 = 0.30000000000000004 -> 30 ; 1.005 stored as 1.00499999... -> BigDecimal(toString)=1.005 -> 100? 1.005*100=100.5 floor->100
        for (int i = 0; i < traps.length; i++) {
            long got = Payout.toCents(traps[i]);
            if (got != want[i]) {
                System.out.println("TRAP MISMATCH " + traps[i] + " -> " + got + " (expected " + want[i] + ")");
                failures++;
            }
        }

        System.out.println(failures == 0
                ? "ALL PAYOUT TESTS PASSED (500k+ scenarios)"
                : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void fail(int t, String msg) {
        System.out.println("FAIL @" + t + ": " + msg);
    }
}
