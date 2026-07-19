package wtb.services;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.milkbowl.vault.economy.EconomyResponse;
import wtb.Main;
import wtb.utils.Format;
import wtb.database.ClaimBoxDAO;
import wtb.database.DatabaseManager;
import wtb.models.ClaimEntry;
import wtb.models.ClaimType;

import java.sql.Connection;

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

        // V6.4.2: the whole claim is ONE transaction on ONE connection.  The old
        // flow committed the DELETE immediately and re-queued failed/partial
        // claims with an ASYNC insert — an insert failure (SQLITE_BUSY under
        // Claim-All churn), a crash, or a shutdown inside that window destroyed
        // the entry with only a console warning (~79k items were lost on a live
        // server this way).  Now the DELETE (still Bug #2's atomic ownership
        // guard — exactly one concurrent claimer sees rowcount 1) and any
        // re-queue INSERT commit or roll back TOGETHER: on any DB error the row
        // survives untouched, the grant is undone, and the player just retries.
        ClaimResult result;
        ClaimEntry  requeueRow = null;   // re-inserted in the SAME transaction
        Runnable    undoGrant  = null;   // reverses the grant if the tx fails
        String      postMsg    = null;   // chat line — sent only after commit
        String      postLogTag = null, postLogLine = null; // file log, after commit

        Connection conn = null;
        try {
            conn = DatabaseManager.get().getConnection();
            conn.setAutoCommit(false);

            if (!dao.deleteInTx(conn, entry.getId())) {
                conn.rollback();
                return ClaimResult.ALREADY_CLAIMED;
            }

            switch (entry.getType()) {

                case MONEY, REFUND -> {
                    EconomyResponse resp = deposit(player, entry.getMoney());
                    if (resp == null || !resp.transactionSuccess()) {
                        requeueRow = new ClaimEntry(
                                entry.getPlayer(), entry.getType(), null, entry.getMoney());
                        postMsg = quiet ? null : Main.msg("claim_failed_deposit");
                        result  = ClaimResult.FAILED;
                    } else {
                        double amount = entry.getMoney();
                        undoGrant = () -> Main.getEconomy().withdrawPlayer(player, amount);
                        String  fmt    = Format.money(amount);
                        boolean refund = entry.getType() == ClaimType.REFUND;
                        postMsg = quiet ? null : Main
                                .msg(refund ? "claim_refund" : "claim_money")
                                .replace("{amount}", fmt);
                        postLogTag  = refund ? "claim-refund" : "claim-money";
                        postLogLine = player.getName()
                                + (refund ? " claimed refund of " : " claimed ")
                                + fmt + " from Claim Box";
                        result = ClaimResult.GRANTED;
                    }
                }

                case ITEM -> {
                    ItemStack stored = entry.getItem();
                    if (stored == null) {
                        // Corrupt entry: discard the row and tell the player.
                        postMsg = quiet ? null : Main.msg("claim_item_corrupt");
                        postLogTag  = "claim-items";
                        postLogLine = player.getName()
                                + " dismissed corrupt item entry (ID " + entry.getId() + ")";
                        result = ClaimResult.GRANTED;
                    } else {
                        // addItem() MUTATES the passed stack while distributing it,
                        // so capture the requested amount first and trust only the
                        // returned leftover map afterwards.
                        int requested = stored.getAmount();
                        var leftover  = player.getInventory().addItem(stored);
                        if (!leftover.isEmpty()) {
                            // V6.3.0 dupe fix: addItem() delivers a PARTIAL amount
                            // when the inventory has some room (topping up existing
                            // stacks) — re-queue ONLY what did not fit.
                            int leftoverAmount = 0;
                            for (ItemStack l : leftover.values()) leftoverAmount += l.getAmount();
                            int delivered = requested - leftoverAmount;

                            ItemStack requeueStack = entry.getItem(); // fresh full clone incl. meta
                            requeueStack.setAmount(leftoverAmount);
                            requeueRow = new ClaimEntry(
                                    entry.getPlayer(), ClaimType.ITEM, requeueStack, 0);

                            if (delivered > 0) {
                                ItemStack deliveredStack = entry.getItem();
                                deliveredStack.setAmount(delivered);
                                undoGrant = () -> player.getInventory().removeItem(deliveredStack);
                                postLogTag  = "claim-items";
                                postLogLine = player.getName() + " partially claimed " + delivered
                                        + "x " + requeueStack.getType().name()
                                        + " (of " + requested + ", " + leftoverAmount
                                        + " re-queued) from Claim Box";
                            }
                            postMsg = quiet ? null : Main.msg("claim_inventory_full");
                            result  = ClaimResult.INVENTORY_FULL;
                        } else {
                            ItemStack deliveredStack = entry.getItem();
                            undoGrant = () -> player.getInventory().removeItem(deliveredStack);
                            postMsg = quiet ? null : Main.msg("claim_item")
                                    .replace("{amount}",   String.valueOf(requested))
                                    .replace("{material}", deliveredStack.getType().name());
                            postLogTag  = "claim-items";
                            postLogLine = player.getName() + " claimed " + requested
                                    + "x " + deliveredStack.getType().name() + " from Claim Box";
                            result = ClaimResult.GRANTED;
                        }
                    }
                }

                default -> throw new IllegalStateException(
                        "Unknown claim type " + entry.getType());
            }

            if (requeueRow != null) dao.addOrThrow(conn, requeueRow);
            conn.commit();

        } catch (Exception e) {
            try { if (conn != null) conn.rollback(); } catch (Exception ignore) {}
            if (undoGrant != null) {
                try { undoGrant.run(); } catch (Exception undoEx) {
                    LOG.severe("[WTB] CRITICAL: failed to undo claim grant for entry "
                            + entry.getId() + " after tx failure — possible duplicate: " + undoEx);
                }
            }
            LOG.severe("[WTB] Claim transaction failed for entry " + entry.getId()
                    + " (" + player.getName() + ") — entry kept, nothing granted: " + e);
            LogService.log("claim-items", "TX-FAIL: claim of entry " + entry.getId()
                    + " by " + player.getName() + " rolled back — entry kept");
            if (!quiet) player.sendMessage(Main.msg("claim_failed_retry"));
            return ClaimResult.FAILED;
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (Exception ignore) {}
                try { conn.close(); } catch (Exception ignore) {}
            }
        }

        // Committed — now it is safe to tell the player and write the file log.
        if (postMsg != null) player.sendMessage(postMsg);
        if (postLogTag != null) LogService.log(postLogTag, postLogLine);
        return result;
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

    // V6.4.2: the old async requeue helpers are gone — re-queueing now happens
    // INSIDE the claim transaction (see claimDetailed), so a failed insert can
    // no longer orphan a deleted row.
}