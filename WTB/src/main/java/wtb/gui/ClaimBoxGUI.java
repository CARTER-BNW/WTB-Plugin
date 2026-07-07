package wtb.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import wtb.Main;
import wtb.models.ClaimEntry;
import wtb.models.ClaimType;
import wtb.utils.Format;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ClaimBoxGUI {

    private static final int    GUI_SIZE      = 54;
    private static final int    SLOT_CLAIM_ALL = 53;
    private static final String TITLE         = "§6Claim Box";

    private static final int SLOT_BACK    = 45;
    private static final int SLOT_REFRESH = 49;

    // Main-thread-only maps
    private final Map<UUID, Integer> playerPages = new HashMap<>();

    // ── Open ─────────────────────────────────────────────────────────────────

    /** Fetches claims off the main thread, then renders on it. */
    public void open(Player player, int page) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            List<ClaimEntry> claims = Main.getClaimBoxService().getClaims(playerId);
            Bukkit.getScheduler().runTask(Main.getInstance(), () ->
                    render(player, page, claims));
        });
    }

    public int getPage(Player player) {
        return playerPages.getOrDefault(player.getUniqueId(), 0);
    }

    /** Remove per-player page state on disconnect. */
    public void cleanupPlayer(UUID uuid) {
        playerPages.remove(uuid);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void render(Player player, int page, List<ClaimEntry> claims) {
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, TITLE);

        int start = Math.max(0, page * 45);
        int end   = Math.min(start + 45, claims.size());

        for (int i = start; i < end; i++) {
            ItemStack claimItem = createClaimItem(claims.get(i));
            if (claimItem != null) inv.setItem(i - start, claimItem);
        }

        playerPages.put(player.getUniqueId(), page);

        inv.setItem(SLOT_BACK,      button(Material.ARROW,    "§aBack"));
        inv.setItem(SLOT_REFRESH,   button(Material.SUNFLOWER, "§eRefresh"));
        inv.setItem(SLOT_CLAIM_ALL, button(Material.HOPPER,   "§aClaim All"));

        if (page > 0)
            inv.setItem(48, button(Material.ARROW, "§aPrevious Page"));
        if (end < claims.size())
            inv.setItem(50, button(Material.ARROW, "§aNext Page"));

        if (!player.isOnline()) return;
        player.openInventory(inv);
    }

    // ── Item builder ──────────────────────────────────────────────────────────

    private ItemStack createClaimItem(ClaimEntry entry) {
        return switch (entry.getType()) {

            case MONEY -> {
                ItemStack item = new ItemStack(Material.GOLD_INGOT);
                ItemMeta  meta = item.getItemMeta();
                meta.setDisplayName("§eMoney Reward");
                meta.setLore(List.of(
                        "§7Amount: §a" + Format.money(entry.getMoney()),
                        "§8Entry ID: " + entry.getId(),
                        "",
                        "§eClick to claim"
                ));
                item.setItemMeta(meta);
                yield item;
            }

            case REFUND -> {
                ItemStack item = new ItemStack(Material.REDSTONE);
                ItemMeta  meta = item.getItemMeta();
                meta.setDisplayName("§cRefund");
                meta.setLore(List.of(
                        "§7Amount: §a" + Format.money(entry.getMoney()), // FIXED: was raw double
                        "§8Entry ID: " + entry.getId(),
                        "",
                        "§eClick to claim"
                ));
                item.setItemMeta(meta);
                yield item;
            }

            case ITEM -> {
                ItemStack stored = entry.getItem(); // returns clone from ClaimEntry
                if (stored == null) {
                    // Corrupt entry — show a placeholder; click handler dismisses it safely.
                    ItemStack err  = new ItemStack(Material.BARRIER);
                    ItemMeta  meta = err.getItemMeta();
                    meta.setDisplayName("§cCorrupt Item Entry");
                    meta.setLore(List.of(
                            "§7This entry could not be loaded.",
                            "§8Entry ID: " + entry.getId(),
                            "",
                            "§eClick to dismiss"
                    ));
                    err.setItemMeta(meta);
                    yield err;
                }
                ItemMeta meta = stored.getItemMeta();
                if (meta == null) {
                    // Extremely rare: ItemStack from DB has no meta — yield as-is, lore not added.
                    yield stored;
                }
                meta.setLore(List.of(
                        "§7Item Reward",
                        "§8Entry ID: " + entry.getId(),
                        "",
                        "§eClick to claim"
                ));
                stored.setItemMeta(meta);
                yield stored;
            }
        };
        // switch is now exhaustive — no unreachable null return needed
    }

    private ItemStack button(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }
}