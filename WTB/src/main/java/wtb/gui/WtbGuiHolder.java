package wtb.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Identity holder for every WTB GUI inventory (audit fix #6).
 *
 * <p>V6.0 identified its GUIs by TITLE STRING — any other plugin's inventory
 * that happened to be titled "Claim Box" or "Confirm Sale" would trigger WTB's
 * slot handlers (including Claim All and Approve) and have its own clicks
 * cancelled.  Dispatching on {@code getHolder() instanceof WtbGuiHolder}
 * makes GUI identity unforgeable; the title is display-only.
 */
public final class WtbGuiHolder implements InventoryHolder {

    public enum Type { MAIN, MY_LISTINGS, CLAIM_BOX, TRANSACTIONS, CONFIRM }

    private final Type type;
    private Inventory inventory;

    public WtbGuiHolder(Type type) {
        this.type = type;
    }

    public Type getType() {
        return type;
    }

    /** Called once by the owning GUI right after Bukkit.createInventory. */
    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
