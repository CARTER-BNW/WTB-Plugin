package wtb.models;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;

import java.util.Locale;

/**
 * A single-enchantment specification for ENCHANTED_BOOK buy orders.
 *
 * <p>Books from villagers, enchanting tables, loot chests, and fishing all
 * carry the same {@code stored_enchantments} component even though their
 * on-screen tooltip text can differ.  WTB therefore never matches books by
 * display text — it matches by the enchantment key + level pair below, which
 * is identical regardless of where the book came from.
 *
 * <p>Exactly ONE enchantment per order.  A book only fulfils the order if it
 * contains exactly this enchantment at exactly this level and nothing else
 * (see {@link wtb.utils.ItemMatcher}).  This keeps value unambiguous in both
 * directions: a seller can't dump a multi-enchant book into a cheaper
 * single-enchant order, and a buyer can't receive a lower-value book.
 *
 * @param key   canonical namespaced key, e.g. {@code minecraft:sharpness}
 * @param level enchantment level, 1..maxLevel
 */
public record EnchantSpec(String key, int level) {

    /** Separator used in the DB column (':' is taken by the namespace). */
    private static final char SEP = ';';

    /** Canonical DB string, e.g. {@code minecraft:sharpness;5}. */
    public String canonical() {
        return key + SEP + level;
    }

    /**
     * Parses the canonical DB string.  Returns null for null/blank/corrupt
     * input so DAO row-mapping can treat a bad column as "no spec".
     */
    public static EnchantSpec parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int i = raw.lastIndexOf(SEP);
        if (i <= 0 || i == raw.length() - 1) return null;
        try {
            int level = Integer.parseInt(raw.substring(i + 1));
            if (level < 1) return null;
            String key = raw.substring(0, i);
            if (NamespacedKey.fromString(key) == null) return null;
            return new EnchantSpec(key, level);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Builds a spec from user command input.
     *
     * @param name  enchantment name as typed (e.g. "sharpness", "minecraft:sharpness")
     * @param level requested level
     * @return the spec, or null if the enchantment does not exist on this server
     *         or the level is outside 1..maxLevel.
     */
    public static EnchantSpec of(String name, int level) {
        Enchantment ench = resolveByName(name);
        if (ench == null) return null;
        if (level < 1 || level > ench.getMaxLevel()) return null;
        return new EnchantSpec(ench.getKey().toString(), level);
    }

    /**
     * Resolves an enchantment name (with or without namespace) to the registry entry.
     *
     * <p>Audit fix #7: both branches must go through {@link NamespacedKey#fromString},
     * which returns null on invalid characters.  {@code NamespacedKey.minecraft(...)}
     * THROWS IllegalArgumentException instead, so any player typing
     * {@code /wtb enchanted_book 1 100 foo!bar} crashed the command with an
     * uncaught exception.
     */
    public static Enchantment resolveByName(String name) {
        if (name == null || name.isBlank()) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        NamespacedKey key = lower.contains(":")
                ? NamespacedKey.fromString(lower)
                : NamespacedKey.fromString("minecraft:" + lower);
        if (key == null) return null;
        return Registry.ENCHANTMENT.get(key);
    }

    /** Resolves this spec's key against the server registry (null if the key is unknown). */
    public Enchantment resolve() {
        NamespacedKey nk = NamespacedKey.fromString(key);
        return nk == null ? null : Registry.ENCHANTMENT.get(nk);
    }

    /** Human-readable form, e.g. "Sharpness 5". */
    public String displayName() {
        String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        String[] words = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb + " " + level;
    }
}
