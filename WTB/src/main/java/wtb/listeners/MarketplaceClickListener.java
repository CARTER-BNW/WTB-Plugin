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
import wtb.utils.ItemMatcher;
import wtb.utils.NameCache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MarketplaceClickListener implements Listener {

    // GUI title constants — must exactly match the plain-text title each GUI class sets.
    // matchesTitle() strips §-colour codes before comparing so colour-prefixed titles
    // still match, and any renamed container whose stripped title differs does NOT match.
    private static final String GUI_MAIN         = "WTB Marketplace";
    private static final String GUI_MY_LISTINGS  = "My Buy Orders";
    private static final String GUI_CLAIM_BOX    = "Claim Box";
    private static final String GUI_TRANSACTIONS = "Recent Transactions";
    private static final String GUI_CONFIRM      = ConfirmSaleGUI.TITLE_PLAIN;

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

        String title = e.getView().getTitle();
        if (!isWtbGui(title)) return;

        e.setCancelled(true);

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // Only react to clicks in the TOP (GUI) inventory — clicks in the
        // player's own inventory are cancelled above (no shift-moving items in)
        // but must not trigger buttons that happen to share the slot number.
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        if      (matchesTitle(title, GUI_MAIN))         handleMainGUI(player, clicked, e.getSlot());
        else if (matchesTitle(title, GUI_MY_LISTINGS))  handleMyListingsGUI(player, clicked, e.getSlot());
        else if (matchesTitle(title, GUI_CLAIM_BOX))    handleClaimBoxGUI(player, clicked, e.getSlot());
        else if (matchesTitle(title, GUI_TRANSACTIONS)) handleTransactionsGUI(player, clicked, e.getSlot());
        else if (matchesTitle(title, GUI_CONFIRM))      Main.getConfirmSaleGUI().handleClick(player, e.getSlot());
    }

    /**
     * V6 fix: cancel DRAG events across all WTB GUIs.  V5 only cancelled clicks,
     * so a click-hold drag could deposit item stacks INTO the ephemeral GUI
     * inventory — those items were silently destroyed when the GUI closed.
     */
    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (isWtbGui(e.getView().getTitle())) {
            e.setCancelled(true);
        }
    }

    /** Invalidate a pending sale confirmation when its screen is closed. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        if (matchesTitle(e.getView().getTitle(), GUI_CONFIRM)) {
            Main.getConfirmSaleGUI().handleClose(player.getUniqueId());
        }
    }

    private static boolean isWtbGui(String title) {
        return matchesTitle(title, GUI_MAIN)
                || matchesTitle(title, GUI_MY_LISTINGS)
                || matchesTitle(title, GUI_CLAIM_BOX)
                || matchesTitle(title, GUI_TRANSACTIONS)
                || matchesTitle(title, GUI_CONFIRM);
    }

    /**
     * Strips all §-colour/format codes from {@code rawTitle} and compares the
     * result to {@code expected} for exact equality.
     */
    private static boolean matchesTitle(String rawTitle, String expected) {
        return rawTitle.replaceAll("§.", "").equals(expected);
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

            case 50 -> { // Next page
                int next = mainGUI.getPage(player) + 1;
                if (!mainGUI.hasPage(next)) {
                    player.sendMessage(Main.msg("no_more_pages"));
                    return;
                }
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

                int page  = mainGUI.getPage(player);
                int index = slot + (page * 45);
                List<Listing> listings = mainGUI.getSortedListings();
                if (index >= listings.size()) return;

                Listing listing = listings.get(index);

                if (listing.getState() == ListingState.FILLED
                        || listing.getRemainingQuantity() <= 0) {
                    player.sendMessage(Main.msg("already_filled"));
                    Bukkit.getScheduler().runTask(Main.getInstance(),
                            () -> { mainGUI.clearCache(); mainGUI.openAsync(player, page); });
                    return;
                }

                // V6: route through the confirmation screen (re-fetches the
                // listing FRESH from the DB) instead of trading on a bare click.
                if (confirmEnabled()) {
                    final int fId = listing.getId();
                    Bukkit.getScheduler().runTask(Main.getInstance(),
                            () -> Main.getConfirmSaleGUI().openSingle(player, fId));
                    return;
                }

                // Legacy direct path (confirm-enabled: false).
                final int currentPage = mainGUI.getPage(player);
                boolean scheduled = Main.getListingService().fulfillListing(
                        player, listing, Integer.MAX_VALUE, () -> {
                            mainGUI.clearCache();
                            mainGUI.openAsync(player, currentPage);
                        });

                if (!scheduled) {
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        mainGUI.clearCache();
                        mainGUI.openAsync(player, currentPage);
                    });
                }
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

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
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

                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                    try {
                        int succeeded = 0, failed = 0;
                        for (ClaimEntry entry : entries) {
                            if (Main.getClaimBoxService().claim(player, entry)) succeeded++;
                            else failed++;
                        }
                        claimBoxGUI.open(player, 0);
                        if (failed == 0) {
                            player.sendMessage(Main.msg("claim_all_success")
                                    .replace("{count}", String.valueOf(succeeded)));
                        } else {
                            player.sendMessage(Main.msg("claim_all_partial")
                                    .replace("{count}",  String.valueOf(succeeded))
                                    .replace("{failed}", String.valueOf(failed)));
                        }
                    } finally {
                        CLAIMING_ALL.remove(player.getUniqueId());
                    }
                });
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
        // ListingService.fulfillListing performs an atomic RELATIVE claim in the
        // DB, so even if the cached list is stale, a listing that was already
        // fulfilled (or lacks the requested remaining) simply aborts and the
        // seller's items are returned via claim box — no phantom rewards.
        Main.getMainGUI().clearCache();
        List<Listing> listings = Main.getMainGUI().getSortedListings();

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
