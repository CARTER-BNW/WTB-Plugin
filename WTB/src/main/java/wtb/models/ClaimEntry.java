package wtb.models;

import org.bukkit.inventory.ItemStack;
import java.util.UUID;

public class ClaimEntry {

    private int      id;
    private final UUID      player;
    private final ClaimType type;
    private final ItemStack item;   // may be null for MONEY / REFUND entries
    private final double    money;
    private final long      createdAt;

    public ClaimEntry(int id, UUID player, ClaimType type, ItemStack item,
                      double money, long createdAt) {
        this.id        = id;
        this.player    = player;
        this.type      = type;
        this.item      = item;
        this.money     = money;
        this.createdAt = createdAt;
    }

    public ClaimEntry(UUID player, ClaimType type, ItemStack item, double money) {
        this(-1, player, type, item, money, System.currentTimeMillis());
    }

    public int      getId()        { return id;        }
    public UUID     getPlayer()    { return player;    }
    public ClaimType getType()     { return type;      }
    public double   getMoney()     { return money;     }
    public long     getCreatedAt() { return createdAt; }

    /**
     * Returns a defensive clone so callers cannot mutate the stored ItemStack.
     * Returns null for MONEY / REFUND entries.
     */
    public ItemStack getItem() {
        return item == null ? null : item.clone();
    }

    /** Called once by ClaimBoxDAO after INSERT to assign the generated id. */
    public void setId(int id) { this.id = id; }
}