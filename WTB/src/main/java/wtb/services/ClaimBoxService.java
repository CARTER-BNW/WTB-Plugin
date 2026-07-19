package wtb.services;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.milkbowl.vault.economy.EconomyResponse;
import wtb.Main;
import wtb.utils.Format;
import wtb.database.ClaimBoxDAO;
import wtb.models.ClaimEntry;
import wtb.models.ClaimType;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class ClaimBoxService {

    private static final Logger LOG = Logger.getLogger("Minecraft");
    private final ClaimBoxDAO dao = new ClaimBoxDAO();

    // ── Async add variants (for main-thread callers) ──────────────────────────
    // Audit fix #2: these run on the plugin-owned DbExecutor (drained in
    // onDisable BEFORE the pool closes), not the Bukkit async scheduler whose
    // queued tasks never run once the server starts stopping.

    /** Queues an item reward asynchronously. Clones the stack before capture. */
    public void addItem(UUID player, ItemStack item) {
        ItemStack copy = item.clone();
        wtb.database.DbExecutor.submit(() -> {
            if (!dao.add(new ClaimEntry(player, ClaimType.ITEM, copy, 0))) {
                LOG.severe("[WTB] Failed to queue ITEM claim for player " + player);
            }
        });
    }

    /** Queues a money reward asynchronously. */
    public void addMoney(UUID player, double amount) {
        wtb.database.DbExecutor.submit(() -> {
            if (!dao.add(new ClaimEntry(player, ClaimType.MONEY, null, amount))) {
                LOG.severe("[WTB] Failed to queue MONEY claim for player " + player
                        + " (amount=" + amount + ")");
            }
        });
    }

    /** Queues a refund asynchronously. */
    public void addRefund(UUID player, double amount) {
        wtb.database.DbExecutor.submit(() -> {
            if (!dao.add(new ClaimEntry(player, ClaimType.REFUND, null, amount))) {
                LOG.severe("[WTB] Failed to queue REFUND claim for player " + player
                        + " (amount=" + amount + ")");
            }
        });
    }

    // ── Synchronous add variants (already on an async thread) ────────────────

    /** Inserts a money reward synchronously on the calling thread. */
    public void addMoneyDirect(UUID player, double amount) {
        if (!dao.add(new ClaimEntry(player, ClaimType.MONEY, null, amount))) {
            LOG.severe("[WTB] Failed to insert MONEY claim for player " + player
                    + " (amount=" + amount + ")");
        }
    }

    /** Inserts an item reward synchronously on the calling thread. */
    public void addItemDirect(UUID player, ItemStack item) {
        if (!dao.add(new ClaimEntry(player, ClaimType.ITEM, item.clone(), 0))) {
            LOG.severe("[WTB] Failed to insert ITEM claim for player " + player);
        }
    }

    /** Inserts a refund synchronously on the calling thread. */
    public void addRefundDirect(UUID player, double amount) {
        if (!dao.add(new ClaimEntry(player, ClaimType.REFUND, null, amount))) {
            LOG.severe("[WTB] Failed to insert REFUND claim for player " + player
                    + " (amount=" + amount + ")");
        }
    }

    // ── Query ────────────────────────────────────────────────────────────────

    /** Fetches all pending claim entries for the given player. Call from an async thread. */
    public List<ClaimEntry> getClaims(UUID player) {
        return dao.get(player);
    }

    // ── Claim (must run on the main thread) ───────────────────────────────────

    /**
     * Detailed outcome of a {@link #claimDetailed} call.  Claim All needs to
     * distinguish "inventory full" from other failures so it can stop
     * attempting further item claims (V6.3.0 dupe fix).
     */
    public enum ClaimResult {
        /** Reward fully delivered (or corrupt entry dismissed). */
        GRANTED,
        /** Row already deleted by a concurrent claim — nothing happened. */
        ALREADY_CLAIMED,
        /** No (or not enough) inventory space; the undelivered part was re-queued. */
        INVENTORY_FULL,
        /** Off-thread call, offline player, or economy deposit failure. */
        FAILED
    }

    /**
     * Processes a single claim entry, granting the reward to {@code player}.
     *
     * <p><b>Must be called on the main thread</b> — Vault deposits and inventory
     * mutations are not thread-safe.
     *
     * <p><b>Delete-first strategy (Bug #2 fix):</b> the DB row is deleted atomically
     * before the reward is granted.  If {@code deleteIfExists} returns false the
     * entry was already claimed by a concurrent call (e.g. individual click racing
     * with Claim All), and the method returns {@code false} immediately without
     * giving a duplicate reward.
     *
     * <p>If the reward itself fails (inventory full, economy error) the entry is
     * re-inserted with a fresh ID so the player can claim again later.
     *
     * @return true if the reward was successfully granted; false if already claimed,
     *         the player is offline, or the reward failed.
     */
    public boolean claim(Player player, ClaimEntry entry) {
        return claim(player, entry, false);
    }

    /**
     * As {@link #claim(Player, ClaimEntry)}, with {@code quiet} suppressing the
     * per-entry chat messages.  Claim All uses this (V6.2.1): a large claim box
     * is hundreds of entries, and per-entry feedback flooded the chat — one
     * "inventory full" line per stack that didn't fit.  The batch sends a
     * single summary at the end instead.  Money/refund grants, requeues, and
     * file logging are identical in both modes.
     */
    public boolean claim(Player player, ClaimEntry entry, boolean quiet) {
        return claimDetailed(player, entry, quiet) == ClaimResult.GRANTED;
    }

    /**
     * As {@link #claim(Player, ClaimEntry, boolean)} but reports WHY a claim
     * did not succeed, so batch callers can react (stop attempting item claims
     * once the inventory is full instead of churning delete+re-insert on every
     * remaining row).
     */
    public ClaimResult claimDetailed(Player player, ClaimEntry entry, boolean quiet) {

        // Bug #7 fix: refuse to run off the main thread — Vault/inventory APIs are not
        // thread-safe, and silent corruption is worse than a logged refusal.
        if (!Bukkit.isPrimaryThread()) {
            LOG.warning("[WTB] ClaimBoxService.claim() was called off the main thread — aborting.");
            return ClaimResult.FAILED;
        }

        // Bug #6 fix: don't attempt to give rewards to a disconnected player.
        // Without this check, addItem() silently discards items into an offline
        // inventory that gets thrown away on disconnect.
        if (!player.isOnline()) return ClaimResult.FAILED;

        // Bug #2 fix: atomically claim ownership via a synchronous DELETE.
        // executeUpdate() returns the number of rows affected; 0 means the entry
        // was already deleted by a previous claim() call — abort with no effect.
        if (!dao.deleteIfExists(entry.getId())) {
            return ClaimResult.ALREADY_CLAIMED;
        }

        switch (entry.getType()) {

            case MONEY -> {
                EconomyResponse resp = deposit(player, entry.getMoney());
                if (resp == null || !resp.transactionSuccess()) {
                    requeue(entry, player.getName());
                    if (!quiet) player.sendMessage(Main.msg("claim_failed_deposit"));
                    return ClaimResult.FAILED;
                }
                String fmt = Format.money(entry.getMoney());
                if (!quiet) player.sendMessage(Main.msg("claim_money").replace("{amount}", fmt));
                LogService.log("claim-money",
                        player.getName() + " claimed " + fmt + " from Claim Box");
            }

            case REFUND -> {
                EconomyResponse resp = deposit(player, entry.getMoney());
                if (resp == null || !resp.transactionSuccess()) {
                    requeue(entry, player.getName());
                    if (!quiet) player.sendMessage(Main.msg("claim_failed_deposit"));
                    return ClaimResult.FAILED;
                }
                String fmt = Format.money(entry.getMoney());
                if (!quiet) player.sendMessage(Main.msg("claim_refund").replace("{amount}", fmt));
                LogService.log("claim-refund",
                        player.getName() + " claimed refund of " + fmt + " from Claim Box");
            }

            case ITEM -> {
                ItemStack stored = entry.getItem();
                if (stored == null) {
                    // Corrupt entry: row was already deleted, just discard and log.
                    if (!quiet) player.sendMessage(Main.msg("claim_item_corrupt"));
                    LogService.log("claim-items",
                            player.getName() + " dismissed corrupt item entry (ID "
                                    + entry.getId() + ")");
                    return ClaimResult.GRANTED;
                }

                // addItem() MUTATES the passed stack while distributing it, so
                // capture the requested amount first and trust only the returned
                // leftover map afterwards.
                int requested = stored.getAmount();
                var leftover  = player.getInventory().addItem(stored);
                if (!leftover.isEmpty()) {
                    // V6.3.0 dupe fix: addItem() delivers a PARTIAL amount when
                    // the inventory has some room (topping up existing stacks).
                    // The old code re-queued the full original stack, so the
                    // delivered part existed in the player's inventory AND in
                    // claim storage — item duplication (dashv's report).
                    // Re-queue ONLY what did not fit.
                    int leftoverAmount = 0;
                    for (ItemStack l : leftover.values()) leftoverAmount += l.getAmount();
                    int delivered = requested - leftoverAmount;

                    ItemStack requeueStack = entry.getItem(); // fresh full clone incl. meta
                    requeueStack.setAmount(leftoverAmount);
                    requeue(entry, player.getName(), requeueStack);

                    if (delivered > 0) {
                        LogService.log("claim-items",
                                player.getName() + " partially claimed " + delivered
                                        + "x " + requeueStack.getType().name()
                                        + " (of " + requested + ", " + leftoverAmount
                                        + " re-queued) from Claim Box");
                    }
                    if (!quiet) player.sendMessage(Main.msg("claim_inventory_full"));
                    return ClaimResult.INVENTORY_FULL;
                }

                if (!quiet) player.sendMessage(Main.msg("claim_item")
                        .replace("{amount}",   String.valueOf(requested))
                        .replace("{material}", stored.getType().name()));
                LogService.log("claim-items",
                        player.getName() + " claimed " + requested
                                + "x " + stored.getType().name() + " from Claim Box");
            }
        }

        return ClaimResult.GRANTED;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * V6.4.0 hardening: the claim row is deleted BEFORE the deposit, so an
     * economy plugin that THROWS (instead of returning a failure response)
     * would destroy the money with no requeue.  Treat a throw as a failed
     * deposit; the caller requeues.
     */
    private EconomyResponse deposit(Player player, double amount) {
        try {
            return Main.getEconomy().depositPlayer(player, amount);
        } catch (RuntimeException ex) {
            LOG.severe("[WTB] Economy deposit threw for " + player.getName()
                    + " (amount=" + amount + "): " + ex);
            return null;
        }
    }

    /**
     * Re-inserts a claim entry after a failed reward attempt (inventory full,
     * economy error).  Uses the 4-argument constructor so the DB assigns a fresh ID.
     *
     * <p>If this re-insert also fails we log a SEVERE warning, since it means the
     * player's reward is permanently lost.
     */
    private void requeue(ClaimEntry entry, String playerName) {
        requeue(entry, playerName,
                entry.getType() == ClaimType.ITEM ? entry.getItem() : null);
    }

    /**
     * As {@link #requeue(ClaimEntry, String)}, with an explicit stack for ITEM
     * entries.  V6.3.0 dupe fix: after a partial inventory fit, only the
     * portion that did NOT fit may be re-queued — re-inserting the full
     * original stack duplicated the delivered part.
     */
    private void requeue(ClaimEntry entry, String playerName, ItemStack itemToRequeue) {
        // Clone item if present so the entry being re-inserted owns its own copy.
        ItemStack itemCopy = itemToRequeue == null ? null : itemToRequeue.clone();
        ClaimEntry fresh = new ClaimEntry(
                entry.getPlayer(), entry.getType(), itemCopy, entry.getMoney());
        // Audit fix #10: requeue is reached from main-thread claim paths — run
        // the INSERT on the DbExecutor (drained at shutdown) instead of doing
        // synchronous JDBC on the main thread.
        wtb.database.DbExecutor.submit(() -> {
            if (!dao.add(fresh)) {
                LOG.severe("[WTB] CRITICAL: Failed to re-queue claim entry for " + playerName
                        + " (type=" + entry.getType() + "). Reward may be permanently lost!");
            }
        });
    }
}