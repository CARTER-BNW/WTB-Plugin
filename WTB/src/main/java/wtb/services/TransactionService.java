package wtb.services;

import wtb.database.TransactionDAO;
import wtb.models.EnchantSpec;
import wtb.models.Transaction;
import wtb.models.TransactionType;
import org.bukkit.Material;

import java.util.List;
import java.util.UUID;

public class TransactionService {

    private final TransactionDAO dao = new TransactionDAO();

    /** Log a completed trade.  Safe to call from an async thread. */
    public void logTransaction(UUID buyer, UUID seller, Material material, EnchantSpec enchant,
                               String customName, int quantity, double price,
                               TransactionType type) {
        dao.log(new Transaction(buyer, seller, material, enchant, customName,
                quantity, price, type));
    }

    /** Retrieve the most-recent global transactions for the marketplace feed. */
    public List<Transaction> getRecent(int limit) {
        return dao.getRecent(limit);
    }

    /** Retrieve transactions where the given player was buyer or seller. */
    public List<Transaction> getByPlayer(UUID player, int limit) {
        return dao.getByPlayer(player, limit);
    }
}
