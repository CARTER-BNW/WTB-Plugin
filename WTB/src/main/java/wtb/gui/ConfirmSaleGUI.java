package wtb.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import wtb.Main;
import wtb.database.ListingDAO;
import wtb.models.Listing;
import wtb.models.ListingState;
import wtb.services.LogService;
import wtb.utils.Format;
import wtb.utils.ItemMatcher;
import wtb.utils.NameCache;
import wtb.utils.Payout;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V6 sale-confirmation screen.
 *
 * <p>Nothing leaves a seller's inventory from a bare marketplace click any
 * more.  Clicking a listing (or Fill All / {@code /wtb fill}) first opens this
 * GUI, which shows exactly:
 * <ul>
 *   <li>WHAT would be sold (item + amount per order),</li>
 *   <li>HOW MUCH the seller will receive per order and in total,</li>
 *   <li>WHO each buyer is,</li>
 *   <li>HOW MUCH of each order will remain afterwards,</li>
 * </ul>
 * with explicit <b>Approve</b> / <b>Deny</b> buttons.  Items are only removed
 * after Approve — and even then every line re-validates through
 * {@code ListingService.fulfillListing}, whose atomic relative DB claim means
 * a stale preview can only ever sell <i>less</i> than shown, never more.
 *
 * <p>Pending state is main-thread-only and expires after 60 s so an
 * abandoned screen can't be approved minutes later against changed orders.
 */
public class ConfirmSaleGUI {

    private static final int    GUI_SIZE     = 54;
    private static final String TITLE        = "§8Confirm Sale";

    public  static final int SLOT_DENY    = 45;
    public  static final int SLOT_SUMMARY = 49;
    public  static final int SLOT_APPROVE = 53;

    /** A pending confirmation is void after this long. */
    private static final long PENDING_TTL_MS = 60_000L;

    /** One order-line of a pending sale (fresh listing + preview amount/payout). */
    public record Line(Listing listing, int amount, long payoutCents) {}

    private record Pending(List<Line> lines, long createdAt) {}

    // Main-thread only (all reads/writes happen inside click events or runTask).
    private final Map<UUID, Pending> pending = new HashMap<>();

    // ── Open ─────────────────────────────────────────────────────────────────

    /** Confirm selling into ONE listing.  Main thread. */
    public void openSingle(Player player, int listingId) {
        openInternal(player, listingId, false);
    }

    /** Confirm selling into ALL open listings (Fill All button, /wtb fill).  Main thread. */
    public void openAll(Player player) {
        openInternal(player, -1, true);
    }

    private void openInternal(Player player, int singleId, boolean all) {
        // Deep-clone the inventory on the MAIN thread; the async task below only
        // ever reads these clones, never the live inventory.
        final ItemStack[] snapshot = ItemMatcher.snapshotStorage(player);
        final UUID uuid = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            ListingDAO dao = new ListingDAO();

            // FRESH rows straight from the DB — never the 1 s GUI cache — so the
            // preview reflects reality as closely as possible.
            List<Listing> candidates;
            if (all) {
                candidates = dao.getAllOpen();
            } else {
                Listing l = dao.getById(singleId);
                candidates = (l == null) ? List.of() : List.of(l);
            }

            List<Line> lines = new ArrayList<>();
            // Simulated per-template consumption: several orders for the same
            // item must not all be promised the same stack of it.
            Map<String, Integer> avail = new HashMap<>();
            String failReason = null; // best message for single-listing mode

            for (Listing l : candidates) {
                if (l.getState() != ListingState.OPEN && l.getState() != ListingState.PARTIAL) {
                    failReason = "already_filled"; continue;
                }
                if (l.getRemainingQuantity() <= 0) { failReason = "already_filled"; continue; }
                if (l.getBuyer().equals(uuid))     { failReason = "no_self_trade";  continue; }

                ItemStack template = ItemMatcher.template(l);
                if (template == null) { failReason = "listing_unfulfillable"; continue; }

                String key = l.templateKey();
                int have = avail.computeIfAbsent(key,
                        k -> ItemMatcher.countMatching(snapshot, template));
                if (have <= 0) { failReason = "not_enough_items"; continue; }

                int amount = Math.min(have, l.getRemainingQuantity());
                avail.put(key, have - amount);

                // Preview payout mirrors the real math: partial fills get the
                // floor portion; a fill that would empty the order gets the full
                // unpaid remainder (including any dust from earlier partials).
                long payoutCents = (amount >= l.getRemainingQuantity())
                        ? Payout.remainder(l.getPriceCents(), l.getPaidCents())
                        : Payout.portionCents(l.getPriceCents(), l.getOriginalQuantity(), amount);

                lines.add(new Line(l, amount, payoutCents));
            }

            final String fFail = failReason;
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                if (!player.isOnline()) return;

                if (lines.isEmpty()) {
                    player.sendMessage(Main.msg(all
                            ? "fill_all_none"
                            : (fFail != null ? fFail : "not_enough_items")));
                    return;
                }

                pending.put(uuid, new Pending(List.copyOf(lines), System.currentTimeMillis()));
                render(player, lines);
            });
        });
    }

    // ── Render (main thread) ─────────────────────────────────────────────────

    private void render(Player player, List<Line> lines) {
        WtbGuiHolder holder = new WtbGuiHolder(WtbGuiHolder.Type.CONFIRM);
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, TITLE);
        holder.setInventory(inv);

        int shown = Math.min(lines.size(), 45);
        long totalCents = 0;
        int  totalItems = 0;

        for (Line line : lines) {
            totalCents += line.payoutCents();
            totalItems += line.amount();
        }

        for (int i = 0; i < shown; i++) {
            inv.setItem(i, createLineItem(lines.get(i)));
        }

        // Bottom-row filler so misclicks hit inert glass, not empty air.
        for (int slot = 45; slot < 54; slot++) {
            inv.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE, "§7 "));
        }

        // Deny
        ItemStack deny = button(Material.RED_CONCRETE, "§c§lDENY");
        ItemMeta dMeta = deny.getItemMeta();
        dMeta.setLore(List.of("§7Cancel this sale.", "§7Nothing leaves your inventory."));
        deny.setItemMeta(dMeta);
        inv.setItem(SLOT_DENY, deny);

        // Summary
        ItemStack summary = button(Material.PAPER, "§e§lSale Summary");
        ItemMeta sMeta = summary.getItemMeta();
        List<String> sLore = new ArrayList<>();
        sLore.add("§7Orders to fill: §f" + lines.size());
        sLore.add("§7Items to sell: §f" + totalItems);
        sLore.add("§7You receive:  §a" + Format.money(Payout.toMoney(totalCents)));
        if (lines.size() > shown) {
            sLore.add("");
            sLore.add("§8(+" + (lines.size() - shown) + " more order(s) not shown —");
            sLore.add("§8 Approve applies to ALL of them)");
        }
        sMeta.setLore(sLore);
        summary.setItemMeta(sMeta);
        inv.setItem(SLOT_SUMMARY, summary);

        // Approve
        ItemStack approve = button(Material.LIME_CONCRETE, "§a§lAPPROVE");
        ItemMeta aMeta = approve.getItemMeta();
        aMeta.setLore(List.of(
                "§7Sell §f" + totalItems + " §7item(s) across §f" + lines.size() + " §7order(s)",
                "§7for §a" + Format.money(Payout.toMoney(totalCents)) + "§7.",
                "",
                "§ePayment is delivered to your Claim Box."));
        approve.setItemMeta(aMeta);
        inv.setItem(SLOT_APPROVE, approve);

        // Offline, or viewing a chest/another GUI while this open was queued —
        // don't hijack it (V6.2.1).
        if (!player.isOnline() || !WtbGuiHolder.mayOpenFor(player)) return;
        player.openInventory(inv);
    }

    private ItemStack createLineItem(Line line) {
        Listing l = line.listing();

        // Template stack (books show the real stored enchant + glint).
        ItemStack item = ItemMatcher.template(l);
        if (item == null) item = new ItemStack(Material.BARRIER); // defensive — filtered earlier
        item.setAmount(Math.max(1, Math.min(line.amount(), item.getMaxStackSize())));

        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§eSell: §f" + line.amount() + "x " + l.displayName());

        int remainingAfter = Math.max(0, l.getRemainingQuantity() - line.amount());
        List<String> lore = new ArrayList<>();
        lore.add("§7Selling to: §f" + NameCache.getName(l.getBuyer()));
        lore.add("§7You receive: §a" + Format.money(Payout.toMoney(line.payoutCents())));
        lore.add("§7Order remaining after: §f" + remainingAfter
                + (remainingAfter == 0 ? " §a(fully filled)" : ""));
        lore.add("§8Order ID: " + l.getId());
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    // ── Clicks (main thread, routed from MarketplaceClickListener) ──────────

    public void handleClick(Player player, int slot) {
        if (slot == SLOT_APPROVE)      approve(player);
        else if (slot == SLOT_DENY)    deny(player);
        // Everything else (order lines, glass) is informational — ignore.
    }

    private void approve(Player player) {
        UUID uuid = player.getUniqueId();
        Pending p = pending.remove(uuid);

        if (p == null) {
            player.closeInventory();
            return;
        }
        if (System.currentTimeMillis() - p.createdAt() > PENDING_TTL_MS) {
            player.sendMessage(Main.msg("confirm_expired"));
            Main.getMainGUI().clearCache();
            Main.getMainGUI().openAsync(player, 0);
            return;
        }

        // Start at 1 so the counter cannot reach 0 until the loop finishes adding
        // all tasks.  The final decrementAndGet() acts as the "loop-done" signal.
        AtomicInteger pendingTasks = new AtomicInteger(1);
        int attempted = 0;

        for (Line line : p.lines()) {
            pendingTasks.incrementAndGet();
            boolean scheduled = Main.getListingService().fulfillListing(
                    player, line.listing(), line.amount(), () -> {
                        if (pendingTasks.decrementAndGet() == 0) {
                            Main.getMainGUI().clearCache();
                            Main.getMainGUI().openAsync(player, 0);
                        }
                    });

            if (scheduled) {
                attempted++;
            } else {
                pendingTasks.decrementAndGet();
            }
        }

        if (attempted == 0) {
            player.sendMessage(Main.msg("fill_all_none"));
            Main.getMainGUI().clearCache();
            Main.getMainGUI().openAsync(player, 0);
            return; // skip the final decrement — no async tasks to wait for
        }

        player.sendMessage(Main.msg("confirm_approved")
                .replace("{count}", String.valueOf(attempted)));
        LogService.log("confirm-approved",
                player.getName() + " approved sale into " + attempted + " listing(s).");

        // Final decrement for the initial +1.  If all async tasks have already
        // completed by the time the loop finishes, this triggers the refresh.
        if (pendingTasks.decrementAndGet() == 0) {
            Main.getMainGUI().clearCache();
            Main.getMainGUI().openAsync(player, 0);
        }
    }

    private void deny(Player player) {
        pending.remove(player.getUniqueId());
        player.sendMessage(Main.msg("sale_denied"));
        LogService.log("confirm-denied",
                player.getName() + " denied a pending sale confirmation.");
        Main.getMainGUI().clearCache();
        Main.getMainGUI().openAsync(player, 0);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Invalidate pending state when the confirm screen is closed without a choice. */
    public void handleClose(UUID uuid) {
        pending.remove(uuid);
    }

    /** Remove per-player state when they disconnect. */
    public void cleanupPlayer(UUID uuid) {
        pending.remove(uuid);
    }
}
