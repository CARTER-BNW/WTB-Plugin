package wtb.services;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import wtb.Main;
import wtb.database.*;
import wtb.models.*;
import wtb.utils.Format;
import wtb.utils.ItemMatcher;
import wtb.utils.Payout;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ListingService {

    // JVM-level lock: only one thread may fulfil a given listing at a time.
    // ConcurrentHashMap.add() is atomic check-and-set.
    private static final Set<Integer> PROCESSING =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Used by ExpiryService to skip listings currently mid-fulfillment. */
    public static boolean isProcessing(int listingId) {
        return PROCESSING.contains(listingId);
    }

    private final ListingDAO         listingDAO = new ListingDAO();
    private final ClaimBoxService    claimBox   = new ClaimBoxService();
    private final TransactionService txService  = new TransactionService();
    private final PriceHistoryService priceHist = new PriceHistoryService();

    // ── Active-listing count (used by WTBCommand) ─────────────────────────────

    public int countActiveListings(UUID buyer) {
        return listingDAO.countActiveByBuyer(buyer);
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a new buy listing.  Must be called on the main thread
     * (Vault economy operations are not thread-safe).
     *
     * <p>Three order flavours:
     * <ul>
     *   <li>Plain — {@code material} only ({@code enchant} and {@code template} null)</li>
     *   <li>Book spec — {@code material == ENCHANTED_BOOK} with an {@code enchant}</li>
     *   <li>Catalog — {@code template} non-null (exact-item order from the
     *       built-in or admin-registered catalog); {@code enchant} must be null
     *       and the template's own material governs blocking rules</li>
     * </ul>
     *
     * @return the persisted Listing, or null if creation failed for any reason.
     */
    public Listing createListing(Player buyer, Material material, EnchantSpec enchant,
                                 ItemStack template, String customName,
                                 int quantity, double totalPrice) {

        // Defense in depth: WTBCommand already rejects these before calling here,
        // but any future caller (admin tooling, external API, another command)
        // gets the same protection without needing to remember to re-check.
        if (template != null) {
            if (enchant != null) return null; // invalid combination — programmer error
            material = template.getType();
            if (!Main.isTemplateTradeable(material)) {
                buyer.sendMessage(Main.msg("material_blocked"));
                return null;
            }
        } else {
            if (!Main.isTradeable(material)) {
                buyer.sendMessage(Main.msg("material_blocked"));
                return null;
            }
            if (Main.requiresEnchantSpec(material) != (enchant != null)) {
                buyer.sendMessage(Main.msg(enchant == null ? "enchant_required" : "enchant_not_allowed"));
                return null;
            }
        }

        int maxQty = Math.min(100_000,
                Math.max(1, Main.getSettings().getInt("settings.listing.max-quantity", 10_000)));
        if (quantity > maxQty) {
            buyer.sendMessage(Main.msg("max_quantity_exceeded")
                    .replace("{max}", String.valueOf(maxQty)));
            return null;
        }

        // Overflow guard for the integer-cents escrow math (see Payout).
        if (totalPrice > Payout.MAX_TOTAL_PRICE) {
            buyer.sendMessage(Main.msg("invalid_price"));
            return null;
        }

        double pricePerItem = totalPrice / quantity;
        double minPricePerItem = Main.getSettings().getDouble("settings.listing.min-price-per-item", 1.0);
        if (pricePerItem < minPricePerItem) {
            buyer.sendMessage(Main.msg("price_per_item_low")
                    .replace("{min_price}", Format.money(minPricePerItem)));
            return null;
        }

        // Withdraw first (Vault must be on main thread).
        if (!Main.getEconomy().withdrawPlayer(buyer, totalPrice).transactionSuccess()) {
            buyer.sendMessage(Main.msg("not_enough_money"));
            return null;
        }

        int  expiryDays = Main.getSettings().getInt("settings.listing.expiry-days", 7);
        long now        = System.currentTimeMillis();
        long expiresAt  = now + (expiryDays * 24L * 60 * 60 * 1000);

        byte[] itemBytes = null;
        if (template != null) {
            ItemStack one = template.clone();
            one.setAmount(1);
            itemBytes = one.serializeAsBytes();
        }

        Listing listing = new Listing(-1, buyer.getUniqueId(), material, enchant,
                itemBytes, customName,
                quantity, quantity, totalPrice, now, expiresAt, ListingState.OPEN);

        Listing created = listingDAO.create(listing);

        if (created == null || created.getId() == -1) {
            // DB failed — refund the withdrawn money to avoid loss.
            Main.getEconomy().depositPlayer(buyer, totalPrice);
            buyer.sendMessage(Main.msg("buy_create_failed"));
            LogService.log("listing-created",
                    "DB ERROR: failed to persist listing for " + buyer.getName());
            return null;
        }

        buyer.sendMessage(Main.msg("buy_created")
                .replace("{material}", created.displayName())
                .replace("{quantity}", String.valueOf(quantity))
                .replace("{price}",    Format.money(totalPrice)));

        LogService.log("listing-created",
                buyer.getName() + " created listing: " + quantity + "x " + created.displayName()
                        + " for " + Format.money(totalPrice)
                        + " (ID: " + created.getId() + ")");

        return created;
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    /**
     * Atomically cancels an active listing and queues a refund for whatever
     * escrow has not yet been paid out ({@code priceCents - paidCents}, read
     * FRESH from the DB after the state transition — never from the caller's
     * possibly-stale object).  Safe to call from an async thread.
     *
     * @return true if the listing was cancelled; false if it was already
     *         filled, expired, or cancelled (no refund issued).
     */
    public boolean cancelListing(Listing listing) {
        // cancelIfActive is a conditional UPDATE — prevents race with ExpiryService
        // and with in-flight fulfilments (exactly one side wins the state).
        boolean cancelled = listingDAO.cancelIfActive(listing.getId());
        if (!cancelled) return false;

        listing.setState(ListingState.CANCELLED);

        long refundCents = freshRefundCents(listing);
        if (refundCents > 0) {
            claimBox.addRefundDirect(listing.getBuyer(), Payout.toMoney(refundCents));
        }
        LogService.log("listing-cancelled",
                "Listing ID " + listing.getId()
                        + " (" + listing.displayName() + ")"
                        + " cancelled by " + LogService.name(listing.getBuyer())
                        + (refundCents > 0 ? " (refund: " + Format.money(Payout.toMoney(refundCents)) + ")" : ""));
        return true;
    }

    /**
     * Admin force-cancel.  Identical logic to cancelListing but with an
     * admin-actions log entry and an offline notification for the buyer.
     * Safe to call from an async thread.
     */
    public boolean adminCancelListing(UUID admin, Listing listing) {
        boolean cancelled = listingDAO.cancelIfActive(listing.getId());
        if (!cancelled) return false;

        listing.setState(ListingState.CANCELLED);

        long refundCents = freshRefundCents(listing);
        if (refundCents > 0) {
            claimBox.addRefundDirect(listing.getBuyer(), Payout.toMoney(refundCents));
        }

        // Alert the buyer.  Online: immediate message (main thread).
        // Offline: queued notification for next join.
        String alert = Main.msg("order_admin_cancelled")
                .replace("{material}", listing.displayName())
                .replace("{refund}",   Format.money(Payout.toMoney(refundCents)));
        Player buyerOnline = Bukkit.getPlayer(listing.getBuyer());
        if (buyerOnline != null && buyerOnline.isOnline()) {
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> buyerOnline.sendMessage(alert));
        } else {
            Main.getNotificationService().queue(listing.getBuyer(), alert);
        }

        LogService.log("admin-actions",
                "[ADMIN] " + LogService.name(admin)
                        + " force-cancelled listing ID " + listing.getId()
                        + " (" + listing.displayName() + ")"
                        + " owned by " + LogService.name(listing.getBuyer())
                        + (refundCents > 0 ? " (refund: " + Format.money(Payout.toMoney(refundCents)) + ")" : " (no refund)"));
        return true;
    }

    /**
     * Cancels ALL of a player's OPEN / PARTIAL listings (V6: /wtb cancel).
     *
     * <p>Runs fully async; each row is claimed via the conditional
     * cancelIfActive so a listing being fulfilled at this very moment is
     * resolved cleanly: whichever conditional UPDATE commits first wins, and
     * the loser aborts (a losing fulfilment returns the seller's items via
     * the Claim Box; a losing cancel simply skips that row).
     */
    public void cancelAllListings(Player player) {
        final UUID uuid = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            List<Listing> active = listingDAO.getActiveByBuyer(uuid);

            int  cancelled   = 0;
            long refundCents = 0;

            for (Listing l : active) {
                if (!listingDAO.cancelIfActive(l.getId())) continue; // lost race — skip

                long rc = freshRefundCents(l);
                if (rc > 0) {
                    claimBox.addRefundDirect(uuid, Payout.toMoney(rc));
                }
                refundCents += rc;
                cancelled++;

                LogService.log("listing-cancelled",
                        "Listing ID " + l.getId() + " (" + l.displayName() + ")"
                                + " cancelled by " + LogService.name(uuid)
                                + " via /wtb cancel"
                                + (rc > 0 ? " (refund: " + Format.money(Payout.toMoney(rc)) + ")" : ""));
            }

            if (cancelled > 0) {
                LogService.log("bulk-cancel",
                        LogService.name(uuid) + " bulk-cancelled " + cancelled
                                + " listing(s), total refund "
                                + Format.money(Payout.toMoney(refundCents)));
            }

            final int  fCount  = cancelled;
            final long fRefund = refundCents;
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                Main.getMainGUI().clearCache();
                if (!player.isOnline()) return;
                if (fCount == 0) {
                    player.sendMessage(Main.msg("cancel_all_none"));
                } else {
                    player.sendMessage(Main.msg("cancel_all_success")
                            .replace("{count}",  String.valueOf(fCount))
                            .replace("{refund}", Format.money(Payout.toMoney(fRefund))));
                }
            });
        });
    }

    /**
     * Reads the AUTHORITATIVE unpaid escrow for a just-transitioned listing.
     * Uses a fresh DB row so payouts committed by concurrent fulfilments are
     * always included — the caller's object may be stale.
     */
    private long freshRefundCents(Listing listing) {
        Listing fresh = listingDAO.getById(listing.getId());
        if (fresh != null) {
            return Payout.remainder(fresh.getPriceCents(), fresh.getPaidCents());
        }
        return Payout.remainder(listing.getPriceCents(), listing.getPaidCents());
    }

    // ── Fulfil ────────────────────────────────────────────────────────────────

    /** Returns all open/partial listings, sorted, from cache or DB. */
    public List<Listing> getOpenListings() {
        return listingDAO.getAllOpen();
    }

    /**
     * Fulfils a listing up to {@code amount} items.
     *
     * <p>Inventory removal happens synchronously on the calling (main) thread.
     * All DB writes happen asynchronously afterward.
     *
     * <p><b>Write order (critical for correctness):</b>
     * <ol>
     *   <li>{@code fulfillIfActive} runs first as an atomic RELATIVE ownership
     *       claim ({@code remaining = remaining - n WHERE remaining >= n}).  If
     *       it returns false the listing was already filled, expired, cancelled,
     *       or no longer has enough remaining — the seller's items are returned
     *       via the claim box, the seller is told the sale was reverted, and no
     *       rewards are issued.  Because the decrement is relative to the DB's
     *       own value, a stale cached Listing can never resurrect already-sold
     *       quantity (the V5 oversell exploit).</li>
     *   <li>If the fill emptied the listing, any floor-division dust cents are
     *       settled to the final seller so escrow conservation is exact.</li>
     *   <li>Claim entries are written only after the claim succeeds.</li>
     *   <li>Player-facing notifications fire only after the claim succeeds —
     *       the buyer is never told "order filled!" for a trade that then
     *       aborts.  Offline buyers get a queued notification instead.</li>
     *   <li>Transaction log + price history follow (non-critical, can lag).</li>
     * </ol>
     *
     * <p>Trade-off: if the server crashes between step 1 and step 3 the listing
     * is marked fulfilled but rewards have not been written.  This is preferable
     * to the alternative (rewards written but state not updated) which enables
     * an economy exploit.
     *
     * @param onComplete Runnable fired on the main thread after all DB writes
     *                   complete.  Pass null when the caller manages its own refresh.
     * @return true if the async task was dispatched (trade is in progress);
     *         false if validation failed (nothing changed).
     */
    public boolean fulfillListing(Player seller, Listing listing, int amount, Runnable onComplete) {

        if (!PROCESSING.add(listing.getId())) {
            seller.sendMessage(Main.msg("listing_processing"));
            return false;
        }

        boolean scheduledAsync = false;
        try {
            if (amount <= 0)                           return false;
            if (listing.getRemainingQuantity() <= 0)   return false;

            if (listing.getBuyer().equals(seller.getUniqueId())) {
                seller.sendMessage(Main.msg("no_self_trade"));
                return false;
            }

            // Exact-item template (V6): plain material, a book with the ordered
            // stored enchantment, or a catalog item's full serialized meta.
            // Null = spec/blob no longer resolves → listing is unfulfillable.
            ItemStack template = ItemMatcher.template(listing);
            if (template == null) {
                seller.sendMessage(Main.msg("listing_unfulfillable"));
                return false;
            }

            int invAmount = ItemMatcher.countMatching(seller, template);
            if (invAmount <= 0) {
                seller.sendMessage(Main.msg("not_enough_items"));
                return false;
            }

            int actualAmount = Math.min(amount, Math.min(invAmount, listing.getRemainingQuantity()));
            if (actualAmount <= 0) {
                seller.sendMessage(Main.msg("not_enough_items"));
                return false;
            }

            // Exact integer-cents payout — no floating-point truncation (see Payout).
            long portionCents = Payout.portionCents(
                    listing.getPriceCents(), listing.getOriginalQuantity(), actualAmount);

            // Inventory removal is Bukkit API — must be on main thread.
            // Removal only touches the 36 storage slots and only stacks matching
            // the exact template (equipped armor / off-hand are never taken).
            int removed = ItemMatcher.removeMatching(seller, template, actualAmount);
            if (removed <= 0) {
                seller.sendMessage(Main.msg("not_enough_items"));
                return false;
            }
            if (removed < actualAmount) {
                // Should be impossible in the same tick, but never trade more
                // than was actually taken from the seller.
                actualAmount = removed;
                portionCents = Payout.portionCents(
                        listing.getPriceCents(), listing.getOriginalQuantity(), actualAmount);
            }

            // Optimistic in-memory update so subsequent GUI reads look current.
            // The DB write below is the authority; caches are cleared on complete.
            int optimisticRemaining = Math.max(0, listing.getRemainingQuantity() - actualAmount);
            listing.setRemainingQuantity(optimisticRemaining);
            listing.setState(optimisticRemaining <= 0 ? ListingState.FILLED : ListingState.PARTIAL);

            // Pre-build delivery stacks (safe off-thread).  Batched at the
            // template's real max stack size — books, tools, and potions are 1-16.
            List<ItemStack> stacks = new ArrayList<>();
            int stackSize = Math.max(1, template.getMaxStackSize());
            int left = actualAmount;
            while (left > 0) {
                int give = Math.min(stackSize, left);
                ItemStack s = template.clone();
                s.setAmount(give);
                stacks.add(s);
                left -= give;
            }

            // Capture finals for the async closure.
            final int      fAmount   = actualAmount;
            final long     fPortion  = portionCents;
            final UUID     fSeller   = seller.getUniqueId();
            final UUID     fBuyer    = listing.getBuyer();
            final int      fId       = listing.getId();
            final long     fPriceCts = listing.getPriceCents();
            final int      fOrigQty  = listing.getOriginalQuantity();
            final Material fMaterial = listing.getMaterial();
            final EnchantSpec fEnchant = listing.getEnchant();
            final String   fCustom   = listing.getCustomName() != null
                    ? listing.getCustomName()
                    : (listing.isCustom() ? "custom" : null);
            final String   fDisplay  = listing.displayName();
            final String   fHistKey  = listing.historyKey(); // null for catalog/custom orders
            final String   fSellerName = seller.getName();

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                try {
                    // ── Step 1: atomic relative ownership claim ────────────────
                    boolean claimed = listingDAO.fulfillIfActive(fId, fAmount, fPortion);

                    if (!claimed) {
                        // Return the seller's items via claim box so they are not lost,
                        // and TELL them (V5 reverted silently — items just "appeared"
                        // back in the claim box with no explanation).
                        for (ItemStack stack : stacks) {
                            claimBox.addItemDirect(fSeller, stack);
                        }
                        LogService.log("listing-collision",
                                "Listing " + fId + " was no longer active (or lacked "
                                        + fAmount + " remaining) when "
                                        + LogService.name(fSeller)
                                        + " attempted fulfillment — items returned to claim box.");
                        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                            Player s = Bukkit.getPlayer(fSeller);
                            if (s != null && s.isOnline()) {
                                s.sendMessage(Main.msg("sale_reverted"));
                            }
                        });
                        return;
                    }

                    // ── Step 2: read authoritative post-state, settle dust ─────
                    long payoutCents = fPortion;
                    int  dbRemaining = -1;
                    Listing fresh = listingDAO.getById(fId);
                    if (fresh != null) {
                        dbRemaining = fresh.getRemainingQuantity();
                        if (dbRemaining <= 0) {
                            long dust = Payout.remainder(fPriceCts, fresh.getPaidCents());
                            if (dust > 0 && listingDAO.settleDust(fId, fPriceCts)) {
                                payoutCents += dust; // final seller receives the dust
                            }
                        }
                    }
                    final int    remaining = dbRemaining >= 0
                            ? dbRemaining
                            : Math.max(0, fOrigQty - fAmount); // fallback, display only
                    final double payout    = Payout.toMoney(payoutCents);
                    final boolean fullyFilled = remaining <= 0;

                    // ── Step 3: ownership confirmed — write rewards ────────────
                    claimBox.addMoneyDirect(fSeller, payout);
                    for (ItemStack stack : stacks) {
                        claimBox.addItemDirect(fBuyer, stack);
                    }

                    // ── Step 4: notify AFTER the trade is real ─────────────────
                    String buyerMsg = (fullyFilled
                            ? Main.msg("order_fully_filled")
                            : Main.msg("order_partially_filled")
                                    .replace("{remaining}", String.valueOf(remaining)))
                            .replace("{material}", fDisplay)
                            .replace("{seller}",   fSellerName);

                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        Player s = Bukkit.getPlayer(fSeller);
                        if (s != null && s.isOnline()) {
                            s.sendMessage(Main.msg("sold_items")
                                    .replace("{amount}",   String.valueOf(fAmount))
                                    .replace("{material}", fDisplay)
                                    .replace("{payout}",   Format.money(payout)));
                        }
                        Player b = Bukkit.getPlayer(fBuyer);
                        if (b != null && b.isOnline()) {
                            b.playSound(b.getLocation(),
                                    org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                            b.sendTitle(
                                    Main.msg("order_filled_title"),
                                    Main.msg("order_filled_subtitle")
                                            .replace("{seller}", fSellerName),
                                    10, 60, 10);
                            b.sendMessage(buyerMsg);
                            b.sendMessage(Main.msg("collect_items"));
                        } else {
                            // Offline buyer → queue for next join (DB write → async).
                            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () ->
                                    Main.getNotificationService().queue(fBuyer,
                                            buyerMsg + "\n" + Main.msg("collect_items")));
                        }
                    });

                    // ── Step 5: non-critical audit / analytics ────────────────
                    String logCat = fullyFilled ? "listing-filled" : "listing-partial";
                    txService.logTransaction(fBuyer, fSeller, fMaterial, fEnchant, fCustom,
                            fAmount, payout,
                            fullyFilled ? TransactionType.FULL : TransactionType.PARTIAL);
                    if (fHistKey != null) {
                        // Catalog/custom orders are excluded from price statistics —
                        // unique items would only pollute the material's averages.
                        priceHist.record(fHistKey, payout, fAmount);
                    }

                    LogService.log(logCat,
                            LogService.name(fSeller) + " fulfilled listing ID " + fId
                                    + ": sold " + fAmount + "x " + fDisplay
                                    + " for " + Format.money(payout)
                                    + " to " + LogService.name(fBuyer)
                                    + " (remaining: " + remaining + ")");

                } finally {
                    PROCESSING.remove(fId);
                    if (onComplete != null) {
                        Bukkit.getScheduler().runTask(Main.getInstance(), onComplete);
                    }
                }
            });

            scheduledAsync = true;
            return true;

        } finally {
            if (!scheduledAsync) {
                PROCESSING.remove(listing.getId());
            }
        }
    }
}
