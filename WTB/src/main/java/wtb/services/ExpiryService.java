package wtb.services;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wtb.Main;
import wtb.database.DbExecutor;
import wtb.database.ListingDAO;
import wtb.models.Listing;
import wtb.utils.Format;
import wtb.utils.Payout;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

public class ExpiryService {

    private final ListingDAO      listingDAO = new ListingDAO();
    private final ClaimBoxService claimBox   = new ClaimBoxService();

    private ScheduledFuture<?> task; // stored so we can cancel on plugin disable

    public void start() {
        long intervalMins = Main.getSettings().getLong("settings.expiry.check-interval-minutes", 10);
        long intervalMs   = intervalMins * 60_000L;

        // Audit fix #2: runs on the plugin-owned DbExecutor (not the Bukkit
        // async scheduler) so an in-flight sweep is drained in onDisable()
        // before the connection pool closes, instead of racing it.
        task = DbExecutor.scheduleRepeating(this::checkExpired, intervalMs, intervalMs);
    }

    /** Cancel the repeating task.  Called from Main.onDisable(). */
    public void stop() {
        if (task != null) {
            task.cancel(false); // let an in-flight sweep finish; DbExecutor drains it
        }
    }

    private void checkExpired() {
        long now = System.currentTimeMillis();

        // Targeted query — only rows whose expires_at has already passed.
        // Much cheaper than getAllOpen() + Java-side filter on busy servers.
        List<Listing> expired = listingDAO.getExpiredListings(now);

        for (Listing listing : expired) {
            // Skip listings currently mid-fulfillment; the async task will
            // update state once its DB writes complete.
            if (ListingService.isProcessing(listing.getId())) continue;

            expireListing(listing);
        }
    }

    private void expireListing(Listing listing) {
        // Conditional UPDATE — only transitions OPEN/PARTIAL → EXPIRED.
        // If the listing was FILLED between getExpiredListings() and here,
        // this returns false and we skip the refund entirely.
        boolean didExpire = listingDAO.setExpiredIfActive(listing.getId());
        if (!didExpire) return;

        // Re-fetch the listing from DB to get the ACTUAL paid_cents at the time
        // of expiry.  The cached object may be stale if a seller partially
        // filled it between getExpiredListings() and setExpiredIfActive().
        Listing fresh = listingDAO.getById(listing.getId());
        if (fresh == null) return; // should never happen

        // Escrow conservation: refund exactly what was never paid out
        // (price - paid), in integer cents.  V5 recomputed per-item × remaining
        // on raw doubles, which could strand cents in the void forever.
        long refundCents = Payout.remainder(fresh.getPriceCents(), fresh.getPaidCents());
        double refund = Payout.toMoney(refundCents);

        if (refundCents > 0) {
            claimBox.addRefundDirect(fresh.getBuyer(), refund);
        }

        // Alert the buyer that their order expired and a refund is waiting.
        // Online: immediate message.  Offline: queued for next join (V6).
        String alert = Main.msg("order_expired")
                .replace("{material}", fresh.displayName())
                .replace("{refund}",   Format.money(refund));
        Player buyerOnline = Bukkit.getPlayer(fresh.getBuyer());
        if (buyerOnline != null && buyerOnline.isOnline()) {
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> buyerOnline.sendMessage(alert));
        } else {
            Main.getNotificationService().queue(fresh.getBuyer(), alert);
        }

        LogService.log("listing-expired",
                "[EXPIRY] Listing ID " + fresh.getId()
                        + " (" + fresh.displayName() + ")"
                        + " expired for " + LogService.name(fresh.getBuyer())
                        + " (refund: " + Format.money(refund) + ")");
    }
}
