package wtb.models;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * One entry in the item catalog: a stable text key mapped to an exact item
 * template.
 *
 * <p>Two sources:
 * <ul>
 *   <li><b>Built-in</b> — generated at boot from the server's own registries
 *       (every potion / splash / lingering variant, every tipped arrow, every
 *       goat horn, flight 1-3 rockets).  Not persisted, cannot be removed, and
 *       automatically correct for whatever Minecraft version the server runs.</li>
 *   <li><b>Admin-registered</b> — captured from an item in a staff member's
 *       hand via {@code /wtb admin register <key>} (god tools, god books,
 *       specific player heads, patterned banners, …).  Persisted in the DB.</li>
 * </ul>
 *
 * <p>The template is stored with amount 1; matching is full-meta
 * {@code isSimilar}, so anvil-renamed lookalikes can never fill an order for
 * the real item (hidden plugin tags, enchants, and the name itself all differ).
 */
public final class CatalogEntry {

    private final String    key;
    private final ItemStack template;   // amount 1, never handed out directly
    private final byte[]    bytes;      // serialized template (listing storage)
    private final String    label;      // human display label
    private final boolean   builtin;

    public CatalogEntry(String key, ItemStack template, String label, boolean builtin) {
        ItemStack t = template.clone();
        t.setAmount(1);
        this.key      = key;
        this.template = t;
        this.bytes    = t.serializeAsBytes();
        this.label    = label;
        this.builtin  = builtin;
    }

    public String    getKey()      { return key;     }
    public Material  getMaterial() { return template.getType(); }
    public byte[]    getBytes()    { return bytes;   }
    public String    getLabel()    { return label;   }
    public boolean   isBuiltin()   { return builtin; }

    /** Defensive copy — callers may mutate amount/meta freely. */
    public ItemStack template() { return template.clone(); }
}
