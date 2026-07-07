package wtbstress;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe in-memory records the end audit cross-checks against the DB. */
final class Ledger {

    /** Static facts about a listing the harness created (immutable snapshot). */
    record Info(int id, long priceCents, int origQty, UUID buyer) {}

    final ConcurrentHashMap<Integer, Info>      listings  = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, LongAdder> filled    = new ConcurrentHashMap<>();
    final Set<Integer> expirable = ConcurrentHashMap.newKeySet();

    // Money movements the harness performed / observed
    final LongAdder depositedCents     = new LongAdder(); // escrow of every created listing
    final LongAdder committedFulfils   = new LongAdder(); // MONEY claim rows inserted (1 per commit)
    final LongAdder itemRowsInserted   = new LongAdder(); // ITEM claim rows inserted
    final LongAdder claimedMoneyCents  = new LongAdder();
    final LongAdder claimedRefundCents = new LongAdder();
    final LongAdder claimedMoneyRows   = new LongAdder();
    final LongAdder claimedRefundRows  = new LongAdder();
    final LongAdder claimedItemRows    = new LongAdder();
    final LongAdder claimedRows        = new LongAdder(); // successful deleteIfExists count

    // Op outcome counters
    final LongAdder opCreate = new LongAdder(), opFulfil = new LongAdder(),
                    opCancel = new LongAdder(), opExpire = new LongAdder(),
                    opClaim  = new LongAdder();
    final LongAdder benignAborts = new LongAdder();
    final ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();

    void recordCreated(Info info, boolean expiresSoon) {
        listings.put(info.id(), info);
        depositedCents.add(info.priceCents());
        if (expiresSoon) expirable.add(info.id());
    }

    void recordFill(int listingId, int amount, int itemRows) {
        filled.computeIfAbsent(listingId, k -> new LongAdder()).add(amount);
        committedFulfils.increment();
        itemRowsInserted.add(itemRows);
    }

    long filledOf(int listingId) {
        LongAdder a = filled.get(listingId);
        return a == null ? 0 : a.sum();
    }

    void unexpected(String where, Throwable t) {
        StringBuilder sb = new StringBuilder(where).append(": ").append(t);
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(6, st.length); i++) sb.append("\n    at ").append(st[i]);
        unexpected.add(sb.toString());
    }
}
