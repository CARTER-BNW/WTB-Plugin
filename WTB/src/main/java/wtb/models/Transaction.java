package wtb.models;

import org.bukkit.Material;

import java.util.UUID;

public class Transaction {

    private int id;
    private UUID buyer;
    private UUID seller;
    private Material material;

    /** Non-null only for ENCHANTED_BOOK spec trades (V6) — kept for audit review. */
    private EnchantSpec enchant;

    /** Stripped display name for hand-captured item trades (V6), else null. */
    private String customName;

    private int quantity;
    private double price;
    private long timestamp;
    private TransactionType type;

    public Transaction(int id, UUID buyer, UUID seller, Material material, EnchantSpec enchant,
                       String customName, int quantity, double price, long timestamp,
                       TransactionType type) {
        this.id = id;
        this.buyer = buyer;
        this.seller = seller;
        this.material = material;
        this.enchant = enchant;
        this.customName = customName;
        this.quantity = quantity;
        this.price = price;
        this.timestamp = timestamp;
        this.type = type;
    }

    public Transaction(UUID buyer, UUID seller, Material material, EnchantSpec enchant,
                       String customName, int quantity, double price, TransactionType type) {
        this(-1, buyer, seller, material, enchant, customName, quantity, price,
                System.currentTimeMillis(), type);
    }

    public int getId() { return id; }
    public UUID getBuyer() { return buyer; }
    public UUID getSeller() { return seller; }
    public Material getMaterial() { return material; }
    public EnchantSpec getEnchant() { return enchant; }
    public String getCustomName() { return customName; }
    public int getQuantity() { return quantity; }
    public double getPrice() { return price; }
    public long getTimestamp() { return timestamp; }
    public TransactionType getType() { return type; }

    /** "DIAMOND", "ENCHANTED_BOOK (Mending 1)", or "DIAMOND_SWORD (\"God Sword\")". */
    public String displayName() {
        if (customName != null && !customName.isBlank()) {
            return material.name() + " (\"" + customName + "\")";
        }
        return enchant == null
                ? material.name()
                : material.name() + " (" + enchant.displayName() + ")";
    }

    public void setId(int id) { this.id = id; }
}
