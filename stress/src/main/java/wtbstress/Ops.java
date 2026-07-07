package wtbstress;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import wtb.Main;
import wtb.database.ClaimBoxDAO;
import wtb.database.ListingDAO;
import wtb.models.ClaimEntry;
import wtb.models.Listing;
import wtb.models.ListingState;
import wtb.services.ClaimBoxService;
import wtb.utils.Payout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The five stress operations.  CREATE and CANCEL call WTB's real public
 * methods; FULFIL invokes the real private {@code fulfillTransactionally}
 * via reflection (it is pure DB — no Player/Vault/main-thread inputs);
 * EXPIRE and CLAIM mirror only the 4-line money cores of their real
 * counterparts (which need Player/Vault above this layer).
 */
final class Ops {

    /** Fails loudly if WTB's money-path signature changes — re-review then. */
    static final Method FULFILL;
    static {
        try {
            FULFILL = wtb.services.ListingService.class.getDeclaredMethod(
                    "fulfillTransactionally",
                    int.class, int.class, long.class, long.class,
                    UUID.class, UUID.class, List.class);
            FULFILL.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "WTB fulfillTransactionally signature changed — re-review the money path", e);
        }
    }

    private final Ledger ledger;
    private final ListingDAO      listingDAO = new ListingDAO();
    private final ClaimBoxDAO     claimDAO   = new ClaimBoxDAO();
    private final ClaimBoxService claimBox   = new ClaimBoxService();

    Ops(Ledger ledger) {
        this.ledger = ledger;
    }

    // ── CREATE ────────────────────────────────────────────────────────────────

    /** Dust-prone listing: total cents deliberately NOT divisible by quantity. */
    void create(UUID buyer) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[] awkwardQty = {7, 13, 17, 23, 8, 16, 31, 47, 64};
        int  qty        = awkwardQty[rnd.nextInt(awkwardQty.length)];
        long perItem    = 100 + rnd.nextInt(900);            // $1.00 - $9.99 per item
        long totalCents = qty * perItem + 1 + rnd.nextInt(qty - 1 > 0 ? qty - 1 : 1); // force dust
        double price    = Payout.toMoney(totalCents);

        boolean expiresSoon = rnd.nextInt(5) == 0; // ~20% born expired
        long now       = System.currentTimeMillis();
        long expiresAt = expiresSoon ? now - 60_000 : now + 3_600_000;

        try {
            Listing l = new Listing(-1, buyer, Material.DIRT, null, null, null,
                    qty, qty, price, now, expiresAt, ListingState.OPEN);
            Listing created = listingDAO.create(l);
            if (created == null || created.getId() == -1) {
                ledger.benignAborts.increment(); // DB busy — nothing persisted
                return;
            }
            ledger.recordCreated(
                    new Ledger.Info(created.getId(), created.getPriceCents(), qty, buyer),
                    expiresSoon);
            ledger.opCreate.increment();
        } catch (Throwable t) {
            classify("create", t);
        }
    }

    // ── FULFIL (real production transaction, via reflection) ─────────────────

    void fulfil(UUID seller, Ledger.Info target) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int  amount  = 1 + rnd.nextInt(8);
        long portion = Payout.portionCents(target.priceCents(), target.origQty(), amount);
        List<ItemStack> stacks = List.of(new ItemStack(Material.DIRT, Math.min(amount, 64)));

        try {
            Object outcome = FULFILL.invoke(Main.getListingService(),
                    target.id(), amount, portion, target.priceCents(),
                    seller, target.buyer(), stacks);
            if (outcome != null) {
                ledger.recordFill(target.id(), amount, stacks.size());
                ledger.opFulfil.increment();
            } else {
                ledger.benignAborts.increment(); // not claimable / txn rolled back
            }
        } catch (Throwable t) {
            classify("fulfil", t instanceof InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause() : t);
        }
    }

    // ── CANCEL (real public service method — pure DB) ─────────────────────────

    void cancel(Ledger.Info target) {
        try {
            Listing fresh = listingDAO.getById(target.id());
            if (fresh == null
                    || (fresh.getState() != ListingState.OPEN
                            && fresh.getState() != ListingState.PARTIAL)) {
                ledger.benignAborts.increment();
                return;
            }
            if (Main.getListingService().cancelListing(fresh)) {
                ledger.opCancel.increment();
            } else {
                ledger.benignAborts.increment(); // lost the race — fine
            }
        } catch (Throwable t) {
            classify("cancel", t);
        }
    }

    // ── EXPIRE (mirror of ExpiryService.expireListing money core — keep in sync) ──

    void expire(Ledger.Info target) {
        try {
            if (!listingDAO.setExpiredIfActive(target.id())) {
                ledger.benignAborts.increment();
                return;
            }
            Listing fresh = listingDAO.getById(target.id());
            if (fresh != null) {
                long refund = Payout.remainder(fresh.getPriceCents(), fresh.getPaidCents());
                if (refund > 0) {
                    claimBox.addRefundDirect(fresh.getBuyer(), Payout.toMoney(refund));
                }
            }
            ledger.opExpire.increment();
        } catch (Throwable t) {
            classify("expire", t);
        }
    }

    // ── CLAIM (mirror of ClaimBoxService.claim delete-first guard) ────────────

    void claim(UUID owner) {
        try {
            for (ClaimEntry e : claimDAO.get(owner)) {
                if (claimDAO.deleteIfExists(e.getId())) {   // exactly one racer wins
                    ledger.claimedRows.increment();
                    switch (e.getType()) {
                        case MONEY  -> {
                            ledger.claimedMoneyCents.add(Payout.toCents(e.getMoney()));
                            ledger.claimedMoneyRows.increment();
                        }
                        case REFUND -> {
                            ledger.claimedRefundCents.add(Payout.toCents(e.getMoney()));
                            ledger.claimedRefundRows.increment();
                        }
                        case ITEM   -> ledger.claimedItemRows.increment();
                    }
                }
            }
            ledger.opClaim.increment();
        } catch (Throwable t) {
            classify("claim", t);
        }
    }

    // ── Failure classification ────────────────────────────────────────────────

    /**
     * Pool-acquisition timeouts and SQLITE_BUSY/LOCKED are BENIGN (the code
     * degrades to a no-op by design under contention).  Everything else is a
     * real defect and is captured with a stack.
     */
    private void classify(String where, Throwable t) {
        if (isBenign(t)) {
            ledger.benignAborts.increment();
        } else {
            ledger.unexpected(where, t);
        }
    }

    private static boolean isBenign(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SQLTransientConnectionException) return true;
            String msg = String.valueOf(c.getMessage());
            if (c.getClass().getName().contains("SQLite")
                    && (msg.contains("SQLITE_BUSY") || msg.contains("SQLITE_LOCKED")
                        || msg.contains("database is locked"))) {
                return true;
            }
        }
        return false;
    }
}
