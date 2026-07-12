package wtb.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import wtb.database.ListingDAO;
import wtb.Main;
import wtb.models.Listing;
import wtb.models.ListingState;
import wtb.utils.Format;
import wtb.utils.ItemMatcher;

import java.util.*;
import java.util.stream.Collectors;

public class MyListingsGUI {

    private static final int    GUI_SIZE = 54;
    private static final String TITLE    = "§eMy Buy Orders";

    private static final int SLOT_BACK    = 45;
    private static final int SLOT_REFRESH = 49;

    // Per-player filter toggles: active ON by default, filled OFF by default.
    // Accessed only on the main thread → HashMap is safe.
    private final Map<UUID, Boolean> showOpen   = new HashMap<>();
    private final Map<UUID, Boolean> showFilled = new HashMap<>();
    private final Map<UUID, Integer> pageMap    = new HashMap<>();

    // ── Filter toggles ────────────────────────────────────────────────────────

    private boolean isShowOpen(UUID id)   { return showOpen.getOrDefault(id, true);  }
    private boolean isShowFilled(UUID id) { return showFilled.getOrDefault(id, false); }

    public void toggleShowOpen(Player player) {
        UUID id = player.getUniqueId();
        showOpen.put(id, !isShowOpen(id));
    }

    public void toggleShowFilled(Player player) {
        UUID id = player.getUniqueId();
        showFilled.put(id, !isShowFilled(id));
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    /** Fetches listings off the main thread, then filters and renders on it. */
    public void open(Player player, int page) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            List<Listing> all = new ListingDAO().getByBuyerVisible(playerId);
            Bukkit.getScheduler().runTask(Main.getInstance(), () ->
                    render(player, page, all));
        });
    }

    public int getPage(Player player) {
        return pageMap.getOrDefault(player.getUniqueId(), 0);
    }

    /** Remove per-player state when they disconnect. */
    public void cleanupPlayer(UUID uuid) {
        showOpen.remove(uuid);
        showFilled.remove(uuid);
        pageMap.remove(uuid);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void render(Player player, int page, List<Listing> all) {
        WtbGuiHolder holder = new WtbGuiHolder(WtbGuiHolder.Type.MY_LISTINGS);
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, TITLE);
        holder.setInventory(inv);
        UUID id = player.getUniqueId();

        boolean filterOpen   = isShowOpen(id);
        boolean filterFilled = isShowFilled(id);

        List<Listing> filtered = all.stream()
                .filter(l -> l.getState() == ListingState.FILLED ? filterFilled : filterOpen)
                .collect(Collectors.toList());

        int start = Math.max(0, page * 45);
        int end   = Math.min(start + 45, filtered.size());

        for (int i = start; i < end; i++) {
            inv.setItem(i - start, createListingItem(filtered.get(i)));
        }

        pageMap.put(id, page);

        inv.setItem(SLOT_BACK,    button(Material.ARROW,    "§aBack to Marketplace"));
        inv.setItem(SLOT_REFRESH, button(Material.SUNFLOWER, "§eRefresh"));

        inv.setItem(51, button(
                filterOpen ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                filterOpen ? "§aShowing: Active Orders" : "§cHiding: Active Orders"));

        inv.setItem(52, button(
                filterFilled ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                filterFilled ? "§aShowing: Filled Orders" : "§cHiding: Filled Orders"));

        if (page > 0)
            inv.setItem(48, button(Material.ARROW, "§aPrevious Page"));
        if (end < filtered.size())
            inv.setItem(50, button(Material.ARROW, "§aNext Page"));

        // Offline, or viewing a chest/another GUI while this open was queued —
        // don't hijack it (V6.2.1).
        if (!player.isOnline() || !WtbGuiHolder.mayOpenFor(player)) return;
        player.openInventory(inv);
    }

    // ── Item builder ──────────────────────────────────────────────────────────

    private ItemStack createListingItem(Listing listing) {
        // Template stack: enchanted-book orders show the real stored enchant.
        ItemStack item = ItemMatcher.template(listing);
        if (item == null) item = new ItemStack(Material.BARRIER); // unresolvable spec

        ItemMeta  meta = item.getItemMeta();
        if (meta == null) {
            // Meta-less template (corrupt DB row) — don't NPE the whole render.
            item = new ItemStack(Material.BARRIER);
            meta = item.getItemMeta();
        }

        double pricePerUnit = listing.getOriginalQuantity() > 0
                ? listing.getOriginalPrice() / listing.getOriginalQuantity()
                : 0;

        meta.setDisplayName("§eYour Buy Order");
        List<String> lore = new ArrayList<>();
        lore.add("§7Item: §f"       + listing.displayName());
        lore.add("§7Total Qty: §f"  + listing.getOriginalQuantity());
        lore.add("§7Remaining: §f"  + listing.getRemainingQuantity());
        lore.add("§7Price/unit: §f" + Format.money(pricePerUnit));
        lore.add("§7Total: §f"      + Format.money(listing.getOriginalPrice()));
        lore.add("§7State: §f"      + listing.getState());
        lore.add("");

        if (listing.getState() == ListingState.FILLED) {
            lore.add("§aClick to go to Claim Box");
        } else {
            lore.add("§cClick to cancel listing");
        }

        lore.add("§8ID: " + listing.getId()); // used by click handler
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
}
