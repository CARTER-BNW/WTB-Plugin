package wtb.listeners;

import wtb.Main;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import wtb.commands.WTBCommand;
import wtb.database.ListingDAO;
import wtb.gui.*;
import wtb.models.*;
import wtb.services.ClaimBoxService;
import wtb.utils.ItemMatcher;
import wtb.utils.NameCache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MarketplaceClickListener implements Listener {

    // Audit fix #6: GUIs are identified by their WtbGuiHolder (unforgeable
    // inventory identity), never by title string — a foreign inventory that
    // happens to share a title can no longer trigger WTB handlers.

    // C1/C2: per-entry and per-player claim locks — prevent double-claim on rapid clicks
    private static final Set<Integer> CLAIMING     = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<UUID>    CLAIMING_ALL = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // L3: per-player fulfill cooldown (500 ms) — throttles macro-speed clicks
    private static final Map<UUID, Long> FULFILL_COOLDOWNS = new ConcurrentHashMap<>();
    private static final long FULFILL_COOLDOWN_MS = 500L;

    // ── Player lifecycle ─────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        // Pre-populate NameCache so GUI lookups never need disk I/O for online players.
        NameCache.put(e.getPlayer().getUniqueId(), e.getPlayer().getName());

        // V6: deliver any "your order was filled/expired/cancelled while you
        // were offline" alerts a couple of seconds after login.
        Main.getNotificationService().deliverOnJoin(e.getPlayer());

        // V6.2: load per-player preferences (popup mutes) into the cache.
        Main.getPlayerSettingsService().loadOnJoin(e.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        FULFILL_COOLDOWNS.remove(uuid);

        // Remove CLAIMING_ALL so the player is not locked out on reconnect if
        // they disconnected while a "Claim All" async task was in flight.  The
        // finally blocks in the still-running async tasks will call remove()
        // again, which is a safe no-op on ConcurrentHashMap.
        CLAIMING_ALL.remove(uuid);

        WTBCommand.handlePlayerQuit(uuid);
        Main.getPlayerSettingsService().handleQuit(uuid);
        // Clean up per-player GUI state to prevent memory leaks.
        Main.getMainGUI().cleanupPlayer(uuid);
        Main.getMyListingsGUI().cleanupPlayer(uuid);
        Main.getClaimBoxGUI().cleanupPlayer(uuid);
        Main.getTransactionsGUI().cleanupPlayer(uuid);
        Main.getConfirmSaleGUI().cleanupPlayer(uuid);
    }

    // ── Click dispatch ───────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() == null) return;

        if (!(e.getView().getTopInventory().getHolder() instanceof WtbGuiHolder holder)) return;

        e.setCancelled(true);

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // Only react to clicks in the TOP (GUI) inventory — clicks in the
        // player's own inventory are cancelled above (no shift-moving items in)
        // but must not trigger buttons that happen to share the slot number.
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        switch (holder.getType()) {
            case MAIN         -> handleMainGUI(player, clicked, e.getSlot());
            case MY_LISTINGS  -> handleMyListingsGUI(player, clicked, e.getSlot());
            case CLAIM_BOX    -> handleClaimBoxGUI(player, clicked, e.getSlot());
            case TRANSACTIONS -> handleTransactionsGUI(player, clicked, e.getSlot());
            case CONFIRM      -> Main.getConfirmSaleGUI().handleClick(player, e.getSlot());
        }
    }

    /**
     * V6 fix: cancel DRAG events across all WTB GUIs.  V5 only cancelled clicks,
     * so a click-hold drag could deposit item stacks INTO the ephemeral GUI
     * inventory — those items were silently destroyed when the GUI closed.
     */
    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getView().getTopInventory().getHolder() instanceof WtbGuiHolder) {
            e.setCancelled(true);
        }
    }

    /** Invalidate a pending sale confirmation when its screen is closed. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        if (e.getView().getTopInventory().getHolder() instanceof WtbGuiHolder holder
                && holder.getType() == WtbGuiHolder.Type.CONFIRM) {
            Main.getConfirmSaleGUI().handleClose(player.getUniqueId());
        }
    }

    // ── Main marketplace GUI ──────────────────────────────────────────────────

    private void handleMainGUI(Player player, ItemStack clicked, int slot) {
        MainListingsGUI mainGUI = Main.getMainGUI();

        switch (slot) {
            case 45 -> Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> Main.getMyListingsGUI().open(player, 0));

            case 46 -> Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> Main.getClaimBoxGUI().open(player, 0));

            case 47 -> { // Sort cycle
                mainGUI.cycleSortMode();
                Bukkit.getScheduler().runTask(Main.getInstance(),
                        () -> mainGUI.openAsync(player, 0));
            }

            case 48 -> { // Previous page
                int prev = Math.max(0, mainGUI.getPage(player) - 1);
                Bukkit.getScheduler().runTask(Main.getInstance(),
                        () -> mainGUI.openAsync(player, prev));
            }

            case 49 -> Bukkit.getScheduler().runTask(Main.getInstance(), // Refresh
                    () -> mainGUI.openAsync(player, 0));

            case 50 -> { // Next page — openAsync clamps to the last real page
                int next = mainGUI.getPage(player) + 1;
                Bukkit.getScheduler().runTask(Main.getInstance(),
                        () -> mainGUI.openAsync(player, next));
            }

            case 51 -> Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> Main.getTransactionsGUI().open(player, 0));

            case 52 -> player.sendMessage(Main.msg("help_message"));

            case 53 -> { // Fill All
                if (!Main.getSettings().getBoolean("settings.listing.fill-all-enabled", true)) return;
                if (isOnFulfillCooldown(player)) return;

                if (confirmEnabled()) {
                    Bukkit.getScheduler().runTask(Main.getInstance(),
                            () -> Main.getConfirmSaleGUI().openAll(player));
                } else {
                    fillAllOpenListings(player);
                }
            }

            default -> {
                if (slot >= 45) return;
                if (isOnFulfillCooldown(player)) return;

                // Audit fix #8: resolve the click by the listing ID stored on
                // the rendered item (PDC) — never by slot index into a
                // re-fetched, possibly re-sorted list, which could silently
                // target a DIFFERENT order.  This also removes the main-thread
                // DB query the old index path did on every cache miss (fix #10).
                final int listingId = extractListingIdPdc(clicked);
                if (listingId == -1) return;

                // V6: route through the confirmation screen (re-fetches the
                // listing FRESH from the DB) instead of trading on a bare click.
                if (confirmEnabled()) {
                    Bukkit.getScheduler().runTask(Main.getInstance(),
                            () -> Main.getConfirmSaleGUI().openSingle(player, listingId));
                    return;
                }

                // Legacy direct path (confirm-enabled: false): re-fetch FRESH by
                // ID off the main thread, validate, then fulfil on the main thread.
                final int currentPage = mainGUI.getPage(player);
                Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                    final Listing fresh = new ListingDAO().getById(listingId);
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        if (!player.isOnline()) return;

                        if (fresh == null
                                || (fresh.getState() != ListingState.OPEN
                                        && fresh.getState() != ListingState.PARTIAL)
                                || fresh.getRemainingQuantity() <= 0) {
                            player.sendMessage(Main.msg("already_filled"));
                            mainGUI.clearCache();
                            mainGUI.openAsync(player, currentPage);
                            return;
                        }

                        boolean scheduled = Main.getListingService().fulfillListing(
                                player, fresh, Integer.MAX_VALUE, () -> {
                                    mainGUI.clearCache();
                                    mainGUI.openAsync(player, currentPage);
                                });

                        if (!scheduled) {
                            mainGUI.clearCache();
                            mainGUI.openAsync(player, currentPage);
                        }
                    });
                });
            }
        }
    }

    private static boolean confirmEnabled() {
        return Main.getSettings().getBoolean("settings.listing.confirm-enabled", true);
    }

    /** Per-player 500 ms throttle shared by listing clicks and Fill All. */
    private boolean isOnFulfillCooldown(Player player) {
        long now       = System.currentTimeMillis();
        Long lastClick = FULFILL_COOLDOWNS.get(player.getUniqueId());
        if (lastClick != null && now - lastClick < FULFILL_COOLDOWN_MS) return true;
        FULFILL_COOLDOWNS.put(player.getUniqueId(), now);
        return false;
    }

    // ── My Listings GUI ──────────────────────────────────────────────────────

    private void handleMyListingsGUI(Player player, ItemStack clicked, int slot) {
        MyListingsGUI myGUI = Main.getMyListingsGUI();

        if (slot == 45) {
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> Main.getMainGUI().openAsync(player, 0));
            return;
        }
        if (slot == 49) {
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> myGUI.open(player, 0));
            return;
        }
        if (slot == 51) {
            myGUI.toggleShowOpen(player);
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> myGUI.open(player, 0));
            return;
        }
        if (slot == 52) {
            myGUI.toggleShowFilled(player);
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> myGUI.open(player, 0));
            return;
        }

        if (slot < 45) {
            int listingId = extractListingId(clicked);
            if (listingId == -1) return;

            // DbExecutor (audit fix #2): this can WRITE (cancel + refund), so it
            // must drain on shutdown rather than die with the Bukkit async queue.
            wtb.database.DbExecutor.submit(() -> {
                Listing target = null;
                for (Listing l : new ListingDAO().getByBuyerVisible(player.getUniqueId())) {
                    if (l.getId() == listingId) { target = l; break; }
                }
                if (target == null) return;
                final Listing listing = target;

                if (listing.getState() == ListingState.FILLED) {
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        player.sendMessage(Main.msg("filled_items_in_claimbox"));
                        Main.getClaimBoxGUI().open(player, 0);
                    });
                } else {
                    // cancelListing is DB-only — safe to call on async thread
                    boolean cancelled = Main.getListingService().cancelListing(listing);
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        if (cancelled) {
                            player.sendMessage(Main.msg("listing_cancelled"));
                        } else {
                            player.sendMessage("§cListing could not be cancelled (already processed).");
                        }
                        Main.getMainGUI().clearCache();
                        myGUI.open(player, 0);
                    });
                }
            });
        }
    }

    // ── Claim Box GUI ─────────────────────────────────────────────────────────

    private void handleClaimBoxGUI(Player player, ItemStack clicked, int slot) {
        ClaimBoxGUI claimBoxGUI = Main.getClaimBoxGUI();

        if (slot == 45) {
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> Main.getMainGUI().openAsync(player, 0));
            return;
        }
        if (slot == 49) {
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> claimBoxGUI.open(player, 0));
            return;
        }
        if (slot == 48) {
            int prev = Math.max(0, claimBoxGUI.getPage(player) - 1);
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> claimBoxGUI.open(player, prev));
            return;
        }
        if (slot == 50) {
            int next = claimBoxGUI.getPage(player) + 1;
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> claimBoxGUI.open(player, next));
            return;
        }

        // Claim All (slot 53)
        if (slot == 53) {
            if (!CLAIMING_ALL.add(player.getUniqueId())) return;

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                // If getClaims() throws, the inner runTask is never scheduled and
                // CLAIMING_ALL would leak permanently.  Release it here.
                List<ClaimEntry> entries;
                try {
                    entries = Main.getClaimBoxService().getClaims(player.getUniqueId());
                } catch (Exception ex) {
                    ex.printStackTrace();
                    CLAIMING_ALL.remove(player.getUniqueId());
                    return;
                }

                // Audit fix #10: the old code processed EVERY entry in a single
                // tick — each claim() is one synchronous JDBC delete, so a big
                // claim box froze the server for the whole loop.  Spread the
                // work across ticks; each claim() stays atomic (delete-first +
                // grant on the main thread), so correctness is unchanged.
                Deque<ClaimEntry> queue = new ArrayDeque<>(entries);
                Bukkit.getScheduler().runTask(Main.getInstance(),
                        () -> processClaimBatch(player, claimBoxGUI, queue, new int[3]));
            });
            return;
        }

        // Individual claim (slot < 45)
        if (slot < 45) {
            int clickedId = extractEntryId(clicked);
            if (clickedId == -1) return;

            if (!CLAIMING.add(clickedId)) return; // duplicate-click guard

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                // Release CLAIMING lock if getClaims() throws.
                ClaimEntry target;
                try {
                    target = Main.getClaimBoxService()
                            .getClaims(player.getUniqueId())
                            .stream()
                            .filter(e -> e.getId() == clickedId)
                            .findFirst()
                            .orElse(null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    CLAIMING.remove(clickedId);
                    return;
                }

                if (target == null) {
                    CLAIMING.remove(clickedId);
                    return;
                }
                final ClaimEntry finalEntry = target;
                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                    try {
                        Main.getClaimBoxService().claim(player, finalEntry);
                        claimBoxGUI.open(player, 0);
                    } finally {
                        CLAIMING.remove(clickedId);
                    }
                });
            });
        }
    }

    /** Max claim entries processed per tick during Claim All (audit fix #10). */
    private static final int CLAIMS_PER_TICK = 5;

    /**
     * Processes up to {@link #CLAIMS_PER_TICK} claims on the main thread, then
     * re-schedules itself for the next tick until the queue is empty (or the
     * player leaves).  counters[0] = succeeded, counters[1] = failed,
     * counters[2] = 1 once the inventory came back full.
     */
    private void processClaimBatch(Player player, ClaimBoxGUI claimBoxGUI,
                                   Deque<ClaimEntry> queue, int[] counters) {
        try {
            int done = 0;
            while (!queue.isEmpty() && done < CLAIMS_PER_TICK) {
                ClaimEntry entry = queue.poll();

                // V6.3.0 dupe fix: once the inventory is full, stop ATTEMPTING
                // item claims.  The old code kept delete+re-inserting every
                // remaining item row, and any partial fit (topping up existing
                // stacks) duplicated the delivered part.  Skipped entries are
                // left untouched in the DB and counted as "could not be
                // claimed"; money/refund entries need no inventory space, so
                // they still process.
                if (counters[2] == 1 && entry.getType() == ClaimType.ITEM) {
                    counters[1]++;
                    continue;
                }

                // quiet=true (V6.2.1): per-entry chat lines flooded the screen
                // on big boxes ("inventory full" once per stack) — the summary
                // below covers the whole run in one message.
                ClaimBoxService.ClaimResult result =
                        Main.getClaimBoxService().claimDetailed(player, entry, true);
                if (result == ClaimBoxService.ClaimResult.GRANTED) {
                    counters[0]++;
                } else {
                    counters[1]++;
                    if (result == ClaimBoxService.ClaimResult.INVENTORY_FULL) counters[2] = 1;
                }
                done++;
            }

            if (!queue.isEmpty() && player.isOnline()) {
                Bukkit.getScheduler().runTaskLater(Main.getInstance(),
                        () -> processClaimBatch(player, claimBoxGUI, queue, counters), 1L);
                return; // lock stays held until the chain finishes
            }

            if (player.isOnline()) {
                claimBoxGUI.open(player, 0);
                if (counters[1] == 0) {
                    player.sendMessage(Main.msg("claim_all_success")
                            .replace("{count}", String.valueOf(counters[0])));
                } else {
                    player.sendMessage(Main.msg("claim_all_partial")
                            .replace("{count}",  String.valueOf(counters[0]))
                            .replace("{failed}", String.valueOf(counters[1])));
                }
            }
            CLAIMING_ALL.remove(player.getUniqueId());
        } catch (Exception ex) {
            ex.printStackTrace();
            CLAIMING_ALL.remove(player.getUniqueId());
        }
    }

    // ── Transactions GUI ──────────────────────────────────────────────────────

    private void handleTransactionsGUI(Player player, ItemStack clicked, int slot) {
        TransactionsGUI txGUI = Main.getTransactionsGUI();

        if (slot == 45) {
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> Main.getMainGUI().openAsync(player, 0));
            return;
        }
        if (slot == 48) {
            int prev = Math.max(0, txGUI.getPage(player) - 1);
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> txGUI.open(player, prev));
            return;
        }
        if (slot == 49) {
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> txGUI.open(player, 0));
            return;
        }
        if (slot == 50) {
            int next = txGUI.getPage(player) + 1;
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> txGUI.open(player, next));
        }
    }

    // ── Fill All (legacy direct path — used only when confirm-enabled: false) ─

    private void fillAllOpenListings(Player player) {
        // Audit fix #10: clearCache() guarantees the next getSortedListings()
        // hits the database, so the fetch MUST happen off the main thread —
        // the old code ran it directly in the click handler.
        Main.getMainGUI().clearCache();
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            List<Listing> listings = Main.getMainGUI().getSortedListings();
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                if (!player.isOnline()) return;
                fillAllFromListings(player, listings);
            });
        });
    }

    /** Main-thread continuation of Fill All once the listing snapshot is fetched. */
    private void fillAllFromListings(Player player, List<Listing> listings) {
        // ListingService.fulfillListing performs an atomic RELATIVE claim in the
        // DB, so even if the cached list is stale, a listing that was already
        // fulfilled (or lacks the requested remaining) simply aborts and the
        // seller's items are returned via claim box — no phantom rewards.

        // Start at 1 so the counter cannot reach 0 until the loop finishes adding all tasks.
        // The final decrementAndGet() below acts as the "loop-done" signal.
        AtomicInteger pending = new AtomicInteger(1);
        int attempted = 0;

        for (Listing listing : listings) {
            if (listing.getState() == ListingState.FILLED
                    || listing.getRemainingQuantity() <= 0) continue;
            if (listing.getBuyer().equals(player.getUniqueId())) continue;

            ItemStack template = ItemMatcher.template(listing);
            if (template == null) continue; // unresolvable enchant spec
            if (ItemMatcher.countMatching(player, template) <= 0) continue;

            pending.incrementAndGet();
            boolean scheduled = Main.getListingService().fulfillListing(
                    player, listing, Integer.MAX_VALUE, () -> {
                        // Fired on main thread after each async DB write completes.
                        if (pending.decrementAndGet() == 0) {
                            Main.getMainGUI().clearCache();
                            Main.getMainGUI().openAsync(player, 0);
                        }
                    });

            if (scheduled) {
                attempted++;
            } else {
                pending.decrementAndGet();
            }
        }

        if (attempted == 0) {
            player.sendMessage(Main.msg("fill_all_none"));
            Main.getMainGUI().clearCache();
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> Main.getMainGUI().openAsync(player, 0));
            return; // skip the final decrement — no async tasks to wait for
        }

        player.sendMessage(Main.msg("fill_all_success")
                .replace("{count}", String.valueOf(attempted)));

        // Final decrement for the initial +1.  If all async tasks have already
        // completed by the time the loop finishes, this triggers the refresh.
        if (pending.decrementAndGet() == 0) {
            Main.getMainGUI().clearCache();
            Bukkit.getScheduler().runTask(Main.getInstance(),
                    () -> Main.getMainGUI().openAsync(player, 0));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Reads the listing ID a marketplace icon carries in its
     * PersistentDataContainer (set by MainListingsGUI.createListingItem).
     * Returns -1 for buttons/foreign items that carry no ID.
     */
    private int extractListingIdPdc(ItemStack item) {
        if (!item.hasItemMeta()) return -1;
        Integer id = item.getItemMeta().getPersistentDataContainer()
                .get(MainListingsGUI.LISTING_ID_KEY,
                        org.bukkit.persistence.PersistentDataType.INTEGER);
        return id != null ? id : -1;
    }

    private int extractListingId(ItemStack item) {
        if (!item.hasItemMeta()) return -1;
        List<String> lore = item.getItemMeta().getLore();
        if (lore == null) return -1;
        for (String line : lore) {
            String clean = line.replaceAll("§.", "").trim();
            if (clean.startsWith("ID: ")) {
                try {
                    return Integer.parseInt(clean.substring(4).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private int extractEntryId(ItemStack item) {
        if (!item.hasItemMeta()) return -1;
        List<String> lore = item.getItemMeta().getLore();
        if (lore == null) return -1;
        for (String line : lore) {
            // Strip ALL colour/format codes (not just the handful of hardcoded ones).
            String clean = line.replaceAll("§.", "").trim();
            if (clean.toLowerCase().contains("entry id")) {
                String digits = clean.replaceAll("\\D+", "");
                if (digits.isEmpty()) return -1;
                try {
                    return Integer.parseInt(digits);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
