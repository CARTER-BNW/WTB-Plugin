package wtb.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import wtb.Main;
import wtb.models.Transaction;
import wtb.utils.Format;
import wtb.utils.ItemMatcher;
import wtb.utils.NameCache;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TransactionsGUI {

    private static final int    GUI_SIZE = 54;
    private static final String TITLE    = "§fRecent Transactions";

    private static final int SLOT_BACK    = 45;
    private static final int SLOT_REFRESH = 49;

    // DateTimeFormatter is thread-safe; SimpleDateFormat was not.
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    // Main-thread-only map
    private final Map<UUID, Integer> playerPages = new HashMap<>();

    // ── Open ─────────────────────────────────────────────────────────────────

    /** Fetches transactions off the main thread, then renders on it. */
    public void open(Player player, int page) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            int limit = Main.getSettings().getInt("settings.transactions.history-limit", 200);
            List<Transaction> txList = Main.getTransactionService().getRecent(limit);
            Bukkit.getScheduler().runTask(Main.getInstance(), () ->
                    render(player, page, txList));
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

    private void render(Player player, int page, List<Transaction> txList) {
        WtbGuiHolder holder = new WtbGuiHolder(WtbGuiHolder.Type.TRANSACTIONS);
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, TITLE);
        holder.setInventory(inv);

        int start = Math.max(0, page * 45);
        int end   = Math.min(start + 45, txList.size());

        for (int i = start; i < end; i++) {
            inv.setItem(i - start, createTransactionItem(txList.get(i)));
        }

        playerPages.put(player.getUniqueId(), page);

        inv.setItem(SLOT_BACK,    button(Material.ARROW,    "§aBack"));
        inv.setItem(SLOT_REFRESH, button(Material.SUNFLOWER, "§eRefresh"));

        if (page > 0)
            inv.setItem(48, button(Material.ARROW, "§aPrevious Page"));
        if (end < txList.size())
            inv.setItem(50, button(Material.ARROW, "§aNext Page"));

        if (!player.isOnline()) return;
        player.openInventory(inv);
    }

    // ── Item builder ──────────────────────────────────────────────────────────

    private ItemStack createTransactionItem(Transaction tx) {
        // Template stack so enchanted-book trades show the actual stored enchant.
        ItemStack item = ItemMatcher.template(tx.getMaterial(), tx.getEnchant());
        if (item == null) item = new ItemStack(tx.getMaterial()); // spec no longer resolves

        ItemMeta  meta = item.getItemMeta();
        if (meta == null) {
            // Meta-less stack (corrupt material in DB) — don't NPE the render.
            item = new ItemStack(Material.BARRIER);
            meta = item.getItemMeta();
        }

        // NameCache: checks online players first (no disk I/O), then TTL cache.
        String buyerName  = NameCache.getName(tx.getBuyer());
        String sellerName = NameCache.getName(tx.getSeller());

        meta.setDisplayName("§fTrade Record");
        meta.setLore(List.of(
                "§7Buyer: §a"    + buyerName,
                "§7Seller: §c"   + sellerName,
                "§7Item: §f"     + tx.displayName(),
                "§7Quantity: §f" + tx.getQuantity(),
                "§7Price: §a"    + Format.money(tx.getPrice()),
                "§7Type: §e"     + tx.getType(),
                "§7Date: §f"     + DATE_FMT.format(Instant.ofEpochMilli(tx.getTimestamp())),
                "",
                "§7(Trades are read-only)"
        ));

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
