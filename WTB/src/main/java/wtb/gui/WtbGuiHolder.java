package wtb.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
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

    /**
     * True if a scheduled WTB screen may open for {@code player} right now:
     * a WTB GUI is already open (navigation / refresh) or nothing is open.
     *
     * <p>Many WTB opens fire a tick or more after the click that requested
     * them (async DB fetches, Claim-All batch chains).  Without this check,
     * a player who closed WTB and opened a chest — or another plugin's GUI —
     * inside that window had their view hijacked by the late-firing WTB
     * screen (V6.2.1 fix).  {@code CRAFTING} is the view type a player has
     * when no container is open.
     */
    public static boolean mayOpenFor(Player player) {
        var view = player.getOpenInventory();
        return view.getTopInventory().getHolder() instanceof WtbGuiHolder
                || view.getType() == InventoryType.CRAFTING;
    }
}
