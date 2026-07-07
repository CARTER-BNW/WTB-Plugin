package wtb.models;

public class PriceHistory {

    /**
     * Aggregation key — a material name ("DIAMOND") or, for enchanted books,
     * material + enchant spec ("ENCHANTED_BOOK;minecraft:mending;1").
     */
    private final String key;
    private final double avgPrice;
    private final double minPrice;
    private final double maxPrice;
    private final long   totalVolume; // long: prevents int overflow on active servers

    public PriceHistory(String key, double avgPrice, double minPrice,
                        double maxPrice, long totalVolume) {
        this.key         = key;
        this.avgPrice    = avgPrice;
        this.minPrice    = minPrice;
        this.maxPrice    = maxPrice;
        this.totalVolume = totalVolume;
    }

    public String getKey()         { return key;         }
    public double getAvgPrice()    { return avgPrice;    }
    public double getMinPrice()    { return minPrice;    }
    public double getMaxPrice()    { return maxPrice;    }
    public long   getTotalVolume() { return totalVolume; }
}
