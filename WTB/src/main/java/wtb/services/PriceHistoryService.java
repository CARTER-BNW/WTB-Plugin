package wtb.services;

import wtb.database.PriceHistoryDAO;

public class PriceHistoryService {

    private final PriceHistoryDAO dao = new PriceHistoryDAO();

    /**
     * Record a completed trade for price-history statistics.
     *
     * @param historyKey  aggregation key — the material name, or for enchanted
     *                    books {@code MATERIAL;minecraft:enchant;level} so that
     *                    e.g. Mending book prices never average into Sharpness
     *                    book prices (see {@link wtb.models.Listing#historyKey()}).
     * @param totalPayout total money paid for all items in this trade
     * @param quantity    number of items traded (must be > 0)
     */
    public void record(String historyKey, double totalPayout, int quantity) {
        if (quantity <= 0) return; // guard: division-by-zero / nonsensical data
        double pricePerItem = totalPayout / quantity;
        dao.update(historyKey, pricePerItem, quantity);
    }
}
