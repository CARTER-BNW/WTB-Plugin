package wtb.models;

import org.bukkit.Material;
import java.util.Base64;
import java.util.UUID;

public class Listing {

    private int id;
    private final UUID     buyer;
    private final Material material;

    /** Non-null only for ENCHANTED_BOOK spec orders (V6). */
    private final EnchantSpec enchant;

    /**
     * Non-null only for hand-captured orders (/wtb hand): the FULL serialized
     * ItemStack (Paper serializeAsBytes) of the exact item the buyer wants —
     * heads, potions, fireworks, banners, custom god items with PDC tags, …
     * Matching deserializes this and uses isSimilar, so the name, lore,
     * enchants, and hidden plugin data must all match exactly.
     */
    private final byte[] itemBytes;

    /** Stripped display name of the captured item, for logs/GUI (may be null). */
    private final String customName;

    // ORIGINAL VALUES (never change after creation)
    private final int    originalQuantity;
    private final double originalPrice;

    /** Escrow total in cents — derived once from originalPrice (see Payout). */
    private final long priceCents;

    // Mutated on main thread, read from async thread → volatile for visibility
    private volatile int          remainingQuantity;
    private volatile ListingState state;

    /**
     * Cents already paid out to sellers.  Authoritative value lives in the DB
     * (updated atomically by ListingDAO.fulfillIfActive); this field is a
     * read snapshot used for previews.  Volatile for cross-thread visibility.
     */
    private volatile long paidCents;

    private final long createdAt;
    private final long expiresAt;

    public Listing(int id, UUID buyer, Material material, EnchantSpec enchant,
                   byte[] itemBytes, String customName,
                   int originalQuantity, int remainingQuantity,
                   double originalPrice, long paidCents,
                   long createdAt, long expiresAt, ListingState state) {

        if (buyer    == null) throw new IllegalArgumentException("buyer must not be null");
        if (material == null) throw new IllegalArgumentException("material must not be null");
        if (originalQuantity  <= 0) throw new IllegalArgumentException("quantity must be > 0");
        if (remainingQuantity < 0)  throw new IllegalArgumentException("remainingQuantity must be >= 0");
        if (originalPrice     <= 0) throw new IllegalArgumentException("price must be > 0");
        if (paidCents         < 0)  throw new IllegalArgumentException("paidCents must be >= 0");
        if (state == null) throw new IllegalArgumentException("state must not be null");

        this.id                = id;
        this.buyer             = buyer;
        this.material          = material;
        this.enchant           = enchant;
        this.itemBytes         = itemBytes;
        this.customName        = customName;
        this.originalQuantity  = originalQuantity;
        this.remainingQuantity = remainingQuantity;
        this.originalPrice     = originalPrice;
        this.priceCents        = wtb.utils.Payout.toCents(originalPrice);
        this.paidCents         = paidCents;
        this.createdAt         = createdAt;
        this.expiresAt         = expiresAt;
        this.state             = state;
    }

    /** Convenience constructor for brand-new listings (nothing paid yet). */
    public Listing(int id, UUID buyer, Material material, EnchantSpec enchant,
                   byte[] itemBytes, String customName,
                   int originalQuantity, int remainingQuantity,
                   double originalPrice, long createdAt,
                   long expiresAt, ListingState state) {
        this(id, buyer, material, enchant, itemBytes, customName,
                originalQuantity, remainingQuantity,
                originalPrice, 0L, createdAt, expiresAt, state);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public int          getId()                { return id;                }
    public UUID         getBuyer()             { return buyer;             }
    public Material     getMaterial()          { return material;          }
    public EnchantSpec  getEnchant()           { return enchant;           }
    public byte[]       getItemBytes()         { return itemBytes;         }
    public String       getCustomName()        { return customName;        }
    public int          getOriginalQuantity()  { return originalQuantity;  }
    public double       getOriginalPrice()     { return originalPrice;     }
    public long         getPriceCents()        { return priceCents;        }
    public long         getPaidCents()         { return paidCents;         }
    public int          getRemainingQuantity() { return remainingQuantity; }
    public long         getCreatedAt()         { return createdAt;         }
    public long         getExpiresAt()         { return expiresAt;         }
    public ListingState getState()             { return state;             }

    /** True if this is a hand-captured (full item meta) order. */
    public boolean isCustom() { return itemBytes != null; }

    /**
     * Display name used everywhere the listing's item is shown to a player:
     * "DIAMOND" for plain orders, "ENCHANTED_BOOK (Sharpness 5)" for book
     * orders, and e.g. "DIAMOND_SWORD (\"God Sword\")" for hand orders.
     */
    public String displayName() {
        if (itemBytes != null) {
            return customName != null && !customName.isBlank()
                    ? material.name() + " (\"" + customName + "\")"
                    : material.name() + " (custom)";
        }
        return enchant == null
                ? material.name()
                : material.name() + " (" + enchant.displayName() + ")";
    }

    /**
     * Key used for price-history aggregation.  Books are keyed per enchantment.
     * Hand-captured orders return NULL — unique/custom items would only pollute
     * the material's price statistics, so they are not recorded.
     */
    public String historyKey() {
        if (itemBytes != null) return null;
        return enchant == null
                ? material.name()
                : material.name() + ";" + enchant.canonical();
    }

    /**
     * Unique per-template key for inventory-simulation maps (ConfirmSaleGUI):
     * two listings share stock if and only if their templates are identical.
     */
    public String templateKey() {
        if (itemBytes != null) {
            return "custom:" + Base64.getEncoder().encodeToString(itemBytes);
        }
        return enchant == null
                ? material.name()
                : material.name() + ";" + enchant.canonical();
    }

    // ── Setters (limited mutation surface) ───────────────────────────────────

    /** Called once by ListingDAO after INSERT to assign the generated DB id. */
    public void setId(int id) { this.id = id; }

    public void setRemainingQuantity(int qty) {
        if (qty < 0) throw new IllegalArgumentException("remainingQuantity must be >= 0");
        this.remainingQuantity = qty;
    }

    public void setPaidCents(long paidCents) {
        if (paidCents < 0) throw new IllegalArgumentException("paidCents must be >= 0");
        this.paidCents = paidCents;
    }

    public void setState(ListingState state) {
        if (state == null) throw new IllegalArgumentException("state must not be null");
        this.state = state;
    }
}
