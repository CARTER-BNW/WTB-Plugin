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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ClaimBoxGUI {

    private static final int    GUI_SIZE      = 54;
    private static final int    SLOT_CLAIM_ALL = 53;
    private static final String TITLE         = "§6Claim Box";

    private static final int SLOT_BACK    = 45;
    private static final int SLOT_SORT    = 47;
    private static final int SLOT_REFRESH = 49;

    /** Entries shown per page (slots 0-44; the bottom row is buttons). */
    private static final int PAGE_SIZE = 45;

    /**
     * V6.4.0: per-player claim box ordering.
     * NEWEST = DB order (created_at DESC, id DESC).
     * TYPE   = money first, then refunds, then items A-Z by material,
     *          newest first within a material.
     */
    public enum ClaimSort { NEWEST, TYPE }

    // Main-thread-only maps
    private final Map<UUID, Integer>   playerPages = new HashMap<>();
    private final Map<UUID, ClaimSort> playerSorts = new HashMap<>();

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

    /** Current sort mode for this player (defaults to NEWEST). Main thread only. */
    public ClaimSort getSort(Player player) {
        return playerSorts.getOrDefault(player.getUniqueId(), ClaimSort.NEWEST);
    }

    /** Flips NEWEST &lt;-&gt; TYPE and returns the new mode. Main thread only. */
    public ClaimSort toggleSort(Player player) {
        ClaimSort next = getSort(player) == ClaimSort.NEWEST ? ClaimSort.TYPE : ClaimSort.NEWEST;
        playerSorts.put(player.getUniqueId(), next);
        return next;
    }

    /** Remove per-player page/sort state on disconnect. */
    public void cleanupPlayer(UUID uuid) {
        playerPages.remove(uuid);
        playerSorts.remove(uuid);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void render(Player player, int page, List<ClaimEntry> claims) {
        ClaimSort sort = getSort(player);
        if (sort == ClaimSort.TYPE) claims = sortedByType(claims);

        // V6.4.0: clamp the page so a box that shrank since the last render
        // (claims succeeded, another session claimed) can never show an empty
        // page past the end.
        int maxPage = Math.max(0, (claims.size() - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, maxPage));

        WtbGuiHolder holder = new WtbGuiHolder(WtbGuiHolder.Type.CLAIM_BOX);
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, TITLE);
        holder.setInventory(inv);

        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, claims.size());

        for (int i = start; i < end; i++) {
            ItemStack claimItem = createClaimItem(claims.get(i));
            if (claimItem != null) inv.setItem(i - start, claimItem);
        }

        playerPages.put(player.getUniqueId(), page);

        inv.setItem(SLOT_BACK, button(Material.ARROW, "§aBack"));
        inv.setItem(SLOT_SORT, button(Material.COMPARATOR,
                "§eSort: " + (sort == ClaimSort.TYPE ? "§bItem Type" : "§aNewest First"),
                List.of("§7Click to toggle")));
        inv.setItem(SLOT_REFRESH, button(Material.SUNFLOWER, "§eRefresh",
                List.of("§7Page " + (page + 1) + " of " + (maxPage + 1),
                        "§7" + claims.size() + " claim(s) waiting")));
        inv.setItem(SLOT_CLAIM_ALL, button(Material.HOPPER, "§aClaim All"));

        if (page > 0)
            inv.setItem(48, button(Material.ARROW, "§aPrevious Page"));
        if (end < claims.size())
            inv.setItem(50, button(Material.ARROW, "§aNext Page"));

        // Offline, or viewing a chest/another GUI while this open was queued —
        // don't hijack it (V6.2.1).
        if (!player.isOnline() || !WtbGuiHolder.mayOpenFor(player)) return;
        player.openInventory(inv);
    }

    // ── Sorting ───────────────────────────────────────────────────────────────

    /** Sort key computed ONCE per entry — getItem() clones the stack, so
     *  comparing entries directly would clone O(n log n) times on big boxes. */
    private record SortKey(int typeOrder, String material, long createdAt, int id,
                           ClaimEntry entry) {}

    /**
     * TYPE mode: money first, then refunds, then items grouped A-Z by material
     * (corrupt item rows last), newest first within a group.
     */
    private static List<ClaimEntry> sortedByType(List<ClaimEntry> claims) {
        List<SortKey> keys = new ArrayList<>(claims.size());
        for (ClaimEntry e : claims) {
            int order; String mat;
            switch (e.getType()) {
                case MONEY  -> { order = 0; mat = ""; }
                case REFUND -> { order = 1; mat = ""; }
                default     -> {
                    ItemStack it = e.getItem();
                    order = 2;
                    mat   = it == null ? "￿" : it.getType().name(); // corrupt last
                }
            }
            keys.add(new SortKey(order, mat, e.getCreatedAt(), e.getId(), e));
        }
        keys.sort(Comparator.comparingInt(SortKey::typeOrder)
                .thenComparing(SortKey::material)
                .thenComparing(Comparator.comparingLong(SortKey::createdAt).reversed())
                .thenComparing(Comparator.comparingInt(SortKey::id).reversed()));
        List<ClaimEntry> out = new ArrayList<>(keys.size());
        for (SortKey k : keys) out.add(k.entry());
        return out;
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
                    // Extremely rare: stack from DB has no meta.  Audit fix #15:
                    // the old code yielded the bare stack WITHOUT the Entry ID
                    // lore, so the click handler could never resolve it and the
                    // entry became unclaimable.  Render a placeholder icon that
                    // carries the ID — the claim itself grants the real stored
                    // item from the DB entry, not this icon.
                    ItemStack fallback = new ItemStack(Material.BARRIER);
                    ItemMeta  fMeta    = fallback.getItemMeta();
                    fMeta.setDisplayName("§fItem Reward: §e" + stored.getType().name());
                    fMeta.setLore(List.of(
                            "§7Item Reward",
                            "§8Entry ID: " + entry.getId(),
                            "",
                            "§eClick to claim",
                            "§eShift-click: claim all of this type"
                    ));
                    fallback.setItemMeta(fMeta);
                    yield fallback;
                }
                meta.setLore(List.of(
                        "§7Item Reward",
                        "§8Entry ID: " + entry.getId(),
                        "",
                        "§eClick to claim",
                        "§eShift-click: claim all of this type"
                ));
                stored.setItemMeta(meta);
                yield stored;
            }
        };
        // switch is now exhaustive — no unreachable null return needed
    }

    private ItemStack button(Material mat, String name) {
        return button(mat, name, null);
    }

    private ItemStack button(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
}