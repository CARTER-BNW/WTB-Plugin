package wtb.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import wtb.Main;
import wtb.models.Listing;
import wtb.models.SortMode;
import wtb.utils.Format;
import wtb.utils.ItemMatcher;
import wtb.utils.NameCache;
import wtb.utils.Payout;

import java.util.*;

public class MainListingsGUI {

    private static final int    GUI_SIZE = 54;
    private static final String TITLE    = "§2WTB Marketplace";

    private static final int SLOT_MY_LISTINGS  = 45;
    private static final int SLOT_CLAIM_BOX    = 46;
    private static final int SLOT_FILTERS      = 47;
    private static final int SLOT_PREV         = 48;
    private static final int SLOT_REFRESH      = 49;
    private static final int SLOT_NEXT         = 50;
    private static final int SLOT_TRANSACTIONS = 51;
    private static final int SLOT_HELP         = 52;
    private static final int SLOT_FILL_ALL     = 53;

    // volatile: written by main thread (cycleSortMode), read by async thread (getSortedListings)
    private volatile SortMode sortMode = SortMode.NAME;

    private volatile List<Listing> cachedListings = Collections.emptyList();
    private volatile long          cacheTime      = 0;

    private final Map<UUID, Integer> pageMap = new HashMap<>(); // main-thread only

    // ── Open ─────────────────────────────────────────────────────────────────

    public void openAsync(Player player, int page) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            List<Listing> listings = getSortedListings();
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                pageMap.put(player.getUniqueId(), page);
                openWithListings(player, page, listings);
            });
        });
    }

    public void openWithListings(Player player, int page, List<Listing> listings) {
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, TITLE);

        int start = page * 45;
        int end   = Math.min(start + 45, listings.size());

        for (int i = start; i < end; i++) {
            inv.setItem(i - start, createListingItem(player, listings.get(i)));
        }

        inv.setItem(SLOT_MY_LISTINGS,  button(Material.BOOK,           "§eMy Listings"));
        inv.setItem(SLOT_CLAIM_BOX,    button(Material.CHEST,          "§6Claim Box"));
        inv.setItem(SLOT_FILTERS,      button(Material.COMPARATOR,     "§bSort: " + getSortName()));
        if (page > 0)
            inv.setItem(SLOT_PREV,     button(Material.ARROW,          "§aPrevious Page"));
        inv.setItem(SLOT_REFRESH,      button(Material.SUNFLOWER,      "§eRefresh"));
        if (end < listings.size())
            inv.setItem(SLOT_NEXT,     button(Material.ARROW,          "§aNext Page"));
        inv.setItem(SLOT_TRANSACTIONS, button(Material.PAPER,          "§fTransactions"));
        inv.setItem(SLOT_HELP,         button(Material.WRITABLE_BOOK,  "§aHelp"));
        if (Main.getSettings().getBoolean("settings.listing.fill-all-enabled", true))
            inv.setItem(SLOT_FILL_ALL, button(Material.HOPPER,         "§aFill All Open"));

        // Player may have disconnected during the async fetch — don't open to a ghost.
        if (!player.isOnline()) return;
        player.openInventory(inv);
    }

    // ── Cache / sort ─────────────────────────────────────────────────────────

    public List<Listing> getSortedListings() {
        long now = System.currentTimeMillis();

        // Fast path — no lock needed when cache is warm (volatile read is safe).
        if (!cachedListings.isEmpty() && now - cacheTime <= 1_000) {
            return cachedListings;
        }

        // Slow path — only one thread rebuilds the cache at a time.
        synchronized (this) {
            if (!cachedListings.isEmpty() && System.currentTimeMillis() - cacheTime <= 1_000) {
                return cachedListings;
            }

            List<Listing> fresh = new ArrayList<>(Main.getListingService().getOpenListings());
            SortMode mode = sortMode; // read volatile once inside lock

            switch (mode) {
                case NAME ->
                        fresh.sort(Comparator.comparing(Listing::displayName));
                case PRICE_LOW ->
                        fresh.sort(Comparator.comparingDouble(
                                (Listing l) -> l.getOriginalPrice() / (double) l.getOriginalQuantity()));
                case PRICE_HIGH ->
                        fresh.sort(Comparator.comparingDouble(
                                (Listing l) -> l.getOriginalPrice() / (double) l.getOriginalQuantity()
                        ).reversed());
            }

            cachedListings = Collections.unmodifiableList(fresh);
            cacheTime      = System.currentTimeMillis();
        }
        return cachedListings;
    }

    public void clearCache() {
        synchronized (this) {
            cachedListings = Collections.emptyList(); // unmodifiable empty — safe
        }
    }

    /** Cycles NAME → PRICE_LOW → PRICE_HIGH → NAME. */
    public void cycleSortMode() {
        sortMode = switch (sortMode) {      // volatile write
            case NAME       -> SortMode.PRICE_LOW;
            case PRICE_LOW  -> SortMode.PRICE_HIGH;
            case PRICE_HIGH -> SortMode.NAME;
        };
        clearCache();
    }

    // ── Page helpers ─────────────────────────────────────────────────────────

    public int getPage(Player player) {
        return pageMap.getOrDefault(player.getUniqueId(), 0);
    }

    public boolean hasPage(int page) {
        return page * 45 < cachedListings.size();
    }

    /** Remove page tracking for a player who has disconnected. */
    public void cleanupPlayer(UUID uuid) {
        pageMap.remove(uuid);
    }

    // ── Item builders ────────────────────────────────────────────────────────

    private ItemStack createListingItem(Player player, Listing listing) {
        // Template stack so book orders show the real stored enchant and
        // catalog orders show the actual item (god sword, potion, head, ...).
        ItemStack item = ItemMatcher.template(listing);
        boolean unfulfillable = (item == null);
        if (unfulfillable) item = new ItemStack(Material.BARRIER);

        ItemMeta  meta = item.getItemMeta();

        String buyerName    = NameCache.getName(listing.getBuyer());
        double pricePerUnit = listing.getOriginalPrice() / listing.getOriginalQuantity();

        meta.setDisplayName("§aBuy Order: §e" + listing.displayName());

        List<String> lore = new ArrayList<>();
        lore.add("§7Buyer: §f"        + buyerName);
        lore.add("§7Total Qty: §f"    + listing.getOriginalQuantity());
        lore.add("§7Remaining: §f"    + listing.getRemainingQuantity());
        lore.add("§7Price/unit: §f"   + Format.money(pricePerUnit));
        lore.add("§7Total price: §f"  + Format.money(listing.getOriginalPrice()));
        lore.add("§7Unpaid escrow: §f"
                + Format.money(Payout.toMoney(
                        Payout.remainder(listing.getPriceCents(), listing.getPaidCents()))));
        lore.add("§7State: §f"        + listing.getState());
        lore.add("");
        if (unfulfillable) {
            lore.add("§cThis order's item can no longer be");
            lore.add("§cresolved on this server.");
        } else if (listing.isCustom()) {
            lore.add("§dExact-item order: name, enchants, and");
            lore.add("§dhidden data must all match precisely.");
            lore.add("§eClick to review & sell");
        } else if (listing.getEnchant() != null || listing.getMaterial().getMaxDurability() > 0) {
            lore.add("§8Only NEW, unmodified items are accepted.");
            lore.add("§eClick to review & sell");
        } else {
            lore.add("§eClick to review & sell");
        }

        if (Main.hasAdminPermission(player)) {
            lore.add("");
            lore.add("§cListing ID: §e" + listing.getId());
        }

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

    private String getSortName() {
        return switch (sortMode) {
            case NAME       -> "Name (A\u2192Z)";
            case PRICE_LOW  -> "Price (Low\u2192High)";
            case PRICE_HIGH -> "Price (High\u2192Low)";
        };
    }
}
