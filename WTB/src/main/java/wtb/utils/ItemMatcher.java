package wtb.utils;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import wtb.models.EnchantSpec;
import wtb.models.Listing;

/**
 * Strict "blank / factory-new" item matching for order fulfilment.
 *
 * <p>V5 matched by {@code Material} alone, which is why tools and enchanted
 * books had to be blocked outright — a renamed keepsake diamond, a Sharpness V
 * axe at 1 durability, or a plugin-tagged custom item would all have fulfilled
 * a plain order.  V6 instead builds a <b>template</b> stack for each listing
 * and accepts only stacks for which {@link ItemStack#isSimilar} against that
 * template is true.
 *
 * <p>Templates come in three flavours:
 * <ul>
 *   <li><b>Plain</b> — a factory-new stack of the material.</li>
 *   <li><b>Enchant spec</b> — an enchanted book carrying exactly the ordered
 *       stored enchantment.</li>
 *   <li><b>Hand-captured</b> — the buyer's exact held item, fully serialized
 *       at order time ({@code /wtb hand}); heads, potions, fireworks, banners,
 *       and custom server items (god tools/books) all work this way.</li>
 * </ul>
 *
 * <p>{@code isSimilar} compares the full item-component/meta payload, so this
 * single check rejects (for ANY material, not just tools):
 * <ul>
 *   <li>damage / lost durability</li>
 *   <li>enchantments (or the wrong enchantments)</li>
 *   <li>custom display names and lore (anvil renames — a renamed fake can
 *       never fill an order for the real item, and the real item never fills
 *       an order captured from a fake, because hidden plugin tags differ)</li>
 *   <li>anvil repair-cost ("prior work") from combined items</li>
 *   <li>armor trims, custom model data, attribute modifiers, hide-flags</li>
 *   <li>PersistentDataContainer tags added by other plugins</li>
 * </ul>
 *
 * <p><b>Inventory scope:</b> counting/removal deliberately uses
 * {@link org.bukkit.inventory.PlayerInventory#getStorageContents()} — the 36
 * main slots only.  Equipped armor and the off-hand slot are never touched.
 */
public final class ItemMatcher {

    private ItemMatcher() {}

    // ── Template ─────────────────────────────────────────────────────────────

    /**
     * Builds the pristine reference stack for a material + optional enchant spec.
     * Returns null only if an ENCHANTED_BOOK spec no longer resolves on this
     * server (e.g. datapack enchantment removed) — such listings cannot be
     * fulfilled and callers must skip them.
     */
    public static ItemStack template(Material material, EnchantSpec spec) {
        ItemStack stack = new ItemStack(material);
        if (spec == null) return stack;

        Enchantment ench = spec.resolve();
        if (ench == null) return null;

        if (stack.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            meta.addStoredEnchant(ench, spec.level(), false);
            stack.setItemMeta(meta);
            return stack;
        }
        // Spec on a non-book material — invalid listing data.
        return null;
    }

    /**
     * Template for a listing.  Hand-captured orders deserialize the stored
     * bytes (returns null if the blob is corrupt or from an incompatible
     * future version — the listing is then unfulfillable but still cancellable);
     * otherwise falls back to the material/enchant template.
     */
    public static ItemStack template(Listing listing) {
        byte[] bytes = listing.getItemBytes();
        if (bytes != null) {
            try {
                ItemStack stack = ItemStack.deserializeBytes(bytes);
                stack.setAmount(1);
                return stack;
            } catch (Exception e) {
                return null; // corrupt / cross-version blob → unfulfillable
            }
        }
        return template(listing.getMaterial(), listing.getEnchant());
    }

    // ── Matching ─────────────────────────────────────────────────────────────

    /** True if {@code item} is an exact template match. */
    public static boolean matches(ItemStack item, ItemStack template) {
        return item != null && template != null && item.isSimilar(template);
    }

    // ── Inventory helpers (MAIN THREAD ONLY) ─────────────────────────────────

    /**
     * Counts matching items across the player's 36 storage slots.
     * Main thread only (live inventory access).
     */
    public static int countMatching(Player player, ItemStack template) {
        if (template == null) return 0;
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matches(item, template)) total += item.getAmount();
        }
        return total;
    }

    /**
     * Counts matching items in a snapshot array (safe off-thread when the
     * array holds clones taken on the main thread).
     */
    public static int countMatching(ItemStack[] snapshot, ItemStack template) {
        if (template == null || snapshot == null) return 0;
        int total = 0;
        for (ItemStack item : snapshot) {
            if (matches(item, template)) total += item.getAmount();
        }
        return total;
    }

    /**
     * Removes up to {@code amount} matching items from the player's 36 storage
     * slots.  Main thread only.  Nulls out depleted slots (no 0-amount ghosts).
     *
     * @return the number of items actually removed.
     */
    public static int removeMatching(Player player, ItemStack template, int amount) {
        if (template == null || amount <= 0) return 0;
        ItemStack[] storage = player.getInventory().getStorageContents();
        int remaining = amount;

        for (int i = 0; i < storage.length && remaining > 0; i++) {
            ItemStack item = storage[i];
            if (!matches(item, template)) continue;

            int take = Math.min(item.getAmount(), remaining);
            remaining -= take;
            int newAmt = item.getAmount() - take;
            if (newAmt <= 0) {
                player.getInventory().setItem(i, null); // storage indices == slots 0..35
            } else {
                item.setAmount(newAmt);
            }
        }
        return amount - remaining;
    }

    /**
     * Deep-clones the player's storage contents for safe off-thread reading.
     * Main thread only.
     */
    public static ItemStack[] snapshotStorage(Player player) {
        ItemStack[] live = player.getInventory().getStorageContents();
        ItemStack[] copy = new ItemStack[live.length];
        for (int i = 0; i < live.length; i++) {
            copy[i] = live[i] == null ? null : live[i].clone();
        }
        return copy;
    }
}
