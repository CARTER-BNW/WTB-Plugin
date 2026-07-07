package wtb.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * All escrow / payout / refund arithmetic in integer <b>cents</b>.
 *
 * <p>V5 computed payouts as {@code Math.floor(pricePerItem * amount * 100) / 100.0}
 * on raw IEEE 754 doubles.  On common two-decimal prices (e.g. a $19.99 total)
 * the double is stored as 19.989999… so the floor truncated one whole cent —
 * the seller was underpaid AND the missing cent stayed locked in the buyer's
 * escrow forever, because nothing ever paid it out or refunded it.
 *
 * <p>V6 rule — <b>escrow conservation</b>:
 * {@code deposit == sum(payouts) + refund}, exactly, for every listing.
 * <ul>
 *   <li>The total price is converted once to cents via the canonical decimal
 *       string (never the raw mantissa).</li>
 *   <li>Every partial payout is {@code priceCents * amount / totalQty} with
 *       integer floor division.</li>
 *   <li>The FINAL fill is paid {@code priceCents - paidCents} so any floor
 *       dust from earlier partials goes to the last seller, not into the void.</li>
 *   <li>Cancellation / expiry refunds are {@code priceCents - paidCents}.</li>
 * </ul>
 */
public final class Payout {

    private Payout() {}

    /** Hard sanity cap on a listing's total price (guards long-math overflow). */
    public static final double MAX_TOTAL_PRICE = 1_000_000_000_000.0; // 1 trillion

    /**
     * Converts a user-supplied price to whole cents using the shortest decimal
     * representation of the double (e.g. 19.99 → 1999, never 1998).
     * Values are rounded HALF_UP to the cent, so "0.005" style input becomes 1 cent.
     */
    public static long toCents(double price) {
        return new BigDecimal(Double.toString(price))
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Cents → double dollars for Vault / display. */
    public static double toMoney(long cents) {
        return cents / 100.0;
    }

    /**
     * Payout in cents for selling {@code amount} of a {@code totalQty}-item
     * listing priced at {@code priceCents} total.  Floor division — dust is
     * settled by {@link #remainder} on the final fill.
     *
     * <p>Audit fix #13: the old claim of being "overflow-safe for
     * priceCents ≤ 10^14 and amount ≤ 10^5" was false — that product is 10^19,
     * past {@code Long.MAX_VALUE} (~9.2×10^18).  Today's callers clamp
     * {@code amount} to physical inventory (≤ ~2,304), so no live overflow
     * existed, but the math is now exact for ANY caller: on 64-bit overflow it
     * falls back to BigInteger instead of silently minting a garbage payout.
     */
    public static long portionCents(long priceCents, int totalQty, int amount) {
        if (totalQty <= 0 || amount <= 0) return 0;
        try {
            return Math.multiplyExact(priceCents, amount) / totalQty;
        } catch (ArithmeticException overflow) {
            return java.math.BigInteger.valueOf(priceCents)
                    .multiply(java.math.BigInteger.valueOf(amount))
                    .divide(java.math.BigInteger.valueOf(totalQty))
                    .longValueExact();
        }
    }

    /** Whatever is still unpaid from escrow: {@code priceCents - paidCents}, never negative. */
    public static long remainder(long priceCents, long paidCents) {
        return Math.max(0, priceCents - paidCents);
    }
}
