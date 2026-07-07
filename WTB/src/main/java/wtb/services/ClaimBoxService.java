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

    /** Queues an item reward asynchronously. Clones the stack before capture. */
    public void addItem(UUID player, ItemStack item) {
        ItemStack copy = item.clone();
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            if (!dao.add(new ClaimEntry(player, ClaimType.ITEM, copy, 0))) {
                LOG.severe("[WTB] Failed to queue ITEM claim for player " + player);
            }
        });
    }

    /** Queues a money reward asynchronously. */
    public void addMoney(UUID player, double amount) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            if (!dao.add(new ClaimEntry(player, ClaimType.MONEY, null, amount))) {
                LOG.severe("[WTB] Failed to queue MONEY claim for player " + player
                        + " (amount=" + amount + ")");
            }
        });
    }

    /** Queues a refund asynchronously. */
    public void addRefund(UUID player, double amount) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
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

        // Bug #7 fix: refuse to run off the main thread — Vault/inventory APIs are not
        // thread-safe, and silent corruption is worse than a logged refusal.
        if (!Bukkit.isPrimaryThread()) {
            LOG.warning("[WTB] ClaimBoxService.claim() was called off the main thread — aborting.");
            return false;
        }

        // Bug #6 fix: don't attempt to give rewards to a disconnected player.
        // Without this check, addItem() silently discards items into an offline
        // inventory that gets thrown away on disconnect.
        if (!player.isOnline()) return false;

        // Bug #2 fix: atomically claim ownership via a synchronous DELETE.
        // executeUpdate() returns the number of rows affected; 0 means the entry
        // was already deleted by a previous claim() call — abort with no effect.
        if (!dao.deleteIfExists(entry.getId())) {
            return false; // already claimed
        }

        switch (entry.getType()) {

            case MONEY -> {
                EconomyResponse resp = Main.getEconomy().depositPlayer(player, entry.getMoney());
                if (!resp.transactionSuccess()) {
                    requeue(entry, player.getName());
                    player.sendMessage(Main.msg("claim_failed_deposit"));
                    return false;
                }
                String fmt = Format.money(entry.getMoney());
                player.sendMessage(Main.msg("claim_money").replace("{amount}", fmt));
                LogService.log("claim-money",
                        player.getName() + " claimed " + fmt + " from Claim Box");
            }

            case REFUND -> {
                EconomyResponse resp = Main.getEconomy().depositPlayer(player, entry.getMoney());
                if (!resp.transactionSuccess()) {
                    requeue(entry, player.getName());
                    player.sendMessage(Main.msg("claim_failed_deposit"));
                    return false;
                }
                String fmt = Format.money(entry.getMoney());
                player.sendMessage(Main.msg("claim_refund").replace("{amount}", fmt));
                LogService.log("claim-refund",
                        player.getName() + " claimed refund of " + fmt + " from Claim Box");
            }

            case ITEM -> {
                ItemStack stored = entry.getItem();
                if (stored == null) {
                    // Corrupt entry: row was already deleted, just discard and log.
                    player.sendMessage(Main.msg("claim_item_corrupt"));
                    LogService.log("claim-items",
                            player.getName() + " dismissed corrupt item entry (ID "
                                    + entry.getId() + ")");
                    return true;
                }

                var leftover = player.getInventory().addItem(stored);
                if (!leftover.isEmpty()) {
                    // Inventory full — re-queue so the player can try again later.
                    requeue(entry, player.getName());
                    player.sendMessage(Main.msg("claim_inventory_full"));
                    return false;
                }

                player.sendMessage(Main.msg("claim_item")
                        .replace("{amount}",   String.valueOf(stored.getAmount()))
                        .replace("{material}", stored.getType().name()));
                LogService.log("claim-items",
                        player.getName() + " claimed " + stored.getAmount()
                                + "x " + stored.getType().name() + " from Claim Box");
            }
        }

        return true;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Re-inserts a claim entry after a failed reward attempt (inventory full,
     * economy error).  Uses the 4-argument constructor so the DB assigns a fresh ID.
     *
     * <p>If this re-insert also fails we log a SEVERE warning, since it means the
     * player's reward is permanently lost.
     */
    private void requeue(ClaimEntry entry, String playerName) {
        // Clone item if present so the entry being re-inserted owns its own copy.
        ItemStack itemCopy = (entry.getType() == ClaimType.ITEM && entry.getItem() != null)
                ? entry.getItem().clone() : null;
        ClaimEntry fresh = new ClaimEntry(
                entry.getPlayer(), entry.getType(), itemCopy, entry.getMoney());
        if (!dao.add(fresh)) {
            LOG.severe("[WTB] CRITICAL: Failed to re-queue claim entry for " + playerName
                    + " (type=" + entry.getType() + "). Reward may be permanently lost!");
        }
    }
}