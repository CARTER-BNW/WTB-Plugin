package wtb.services;

import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.MusicInstrument;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import wtb.Main;
import wtb.database.CatalogDAO;
import wtb.models.CatalogEntry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The item catalog: stable text keys → exact item templates, so players can
 * order meta-bearing items with the normal command syntax:
 *
 * <pre>/wtb potion_strong_swiftness 8 400
 * /wtb goat_horn_ponder 1 250
 * /wtb god_sword 1 50000</pre>
 *
 * <p><b>Built-in entries</b> are generated at boot from the server's own
 * registries — every potion / splash / lingering variant, every tipped arrow,
 * every goat horn, and flight 1-3 firework rockets.  No hardcoded lists: a new
 * Minecraft version's new potions or horns appear automatically.
 *
 * <p><b>Admin entries</b> come from {@code /wtb admin register <key>} with the
 * item in hand (god tools, god books, player heads, patterned banners, custom
 * firework stars, plugin items with PDC tags).  Persisted in the DB and loaded
 * on boot.
 *
 * <p>Keys can never collide with the command's other token types:
 * registration rejects any key that resolves as a {@code Material} or matches
 * a sub-command name, and built-in keys are constructed with prefixes
 * ({@code potion_…}) that no vanilla material name uses.
 *
 * <p>Thread-safety: the map is a ConcurrentHashMap; entries are immutable.
 * Reads (command parsing, tab-complete) are lock-free.
 */
public class CatalogService {

    /** Keys reserved by /wtb sub-commands — never allowed as catalog keys. */
    private static final Set<String> RESERVED = Set.of(
            "buy", "my", "claim", "tx", "help", "admin",
            "fill", "cancel", "cancle", "hand",
            "settings", "mute"); // V6.2 sub-commands

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9_]{2,40}");

    private final Map<String, CatalogEntry> entries = new ConcurrentHashMap<>();
    private final CatalogDAO dao = new CatalogDAO();

    // ── Boot ─────────────────────────────────────────────────────────────────

    /** Builds the built-in registry catalog and loads admin entries.  Call from onEnable. */
    public void load() {
        int builtin = buildBuiltins();

        int custom = 0;
        for (Object[] row : dao.loadAll()) {
            String key   = (String) row[0];
            byte[] bytes = (byte[]) row[1];
            String label = (String) row[2];
            try {
                ItemStack stack = ItemStack.deserializeBytes(bytes);
                entries.put(key, new CatalogEntry(key, stack, label, false));
                custom++;
            } catch (Exception e) {
                Main.getInstance().getLogger().warning(
                        "[WTB] Catalog entry '" + key + "' failed to deserialize and was skipped: "
                                + e.getMessage());
            }
        }

        Main.getInstance().getLogger().info(
                "[WTB] Item catalog loaded: " + builtin + " built-in + " + custom + " registered.");
    }

    /**
     * Registry-driven built-ins.  Each category is guarded individually so an
     * API change in a future Paper version degrades to a logged warning for
     * that category instead of breaking plugin enable.
     */
    private int buildBuiltins() {
        int count = 0;

        // Potions / splash / lingering / tipped arrows — one entry per PotionType.
        try {
            record Variant(String prefix, Material material) {}
            List<Variant> variants = List.of(
                    new Variant("potion_",           Material.POTION),
                    new Variant("splash_potion_",    Material.SPLASH_POTION),
                    new Variant("lingering_potion_", Material.LINGERING_POTION),
                    new Variant("tipped_arrow_",     Material.TIPPED_ARROW));

            for (PotionType type : Registry.POTION) {
                String typeKey = type.getKey().getKey(); // e.g. "strong_swiftness"
                for (Variant v : variants) {
                    ItemStack stack = new ItemStack(v.material());
                    if (!(stack.getItemMeta() instanceof PotionMeta meta)) continue;
                    meta.setBasePotionType(type);
                    stack.setItemMeta(meta);

                    String key = v.prefix() + typeKey;
                    entries.put(key, new CatalogEntry(key, stack, prettify(key), true));
                    count++;
                }
            }
        } catch (Throwable t) {
            Main.getInstance().getLogger().warning(
                    "[WTB] Could not build potion catalog: " + t.getMessage());
        }

        // Goat horns — one entry per instrument.
        try {
            for (MusicInstrument inst : Registry.INSTRUMENT) {
                ItemStack stack = new ItemStack(Material.GOAT_HORN);
                if (!(stack.getItemMeta() instanceof MusicInstrumentMeta meta)) continue;
                meta.setInstrument(inst);
                stack.setItemMeta(meta);

                String instKey = inst.getKey().getKey().replace("goat_horn.", "").replace('.', '_');
                String key = "goat_horn_" + instKey;
                entries.put(key, new CatalogEntry(key, stack, prettify(key), true));
                count++;
            }
        } catch (Throwable t) {
            Main.getInstance().getLogger().warning(
                    "[WTB] Could not build goat-horn catalog: " + t.getMessage());
        }

        // Firework rockets — flight duration 1-3 (the craftable commodities).
        try {
            for (int power = 1; power <= 3; power++) {
                ItemStack stack = new ItemStack(Material.FIREWORK_ROCKET);
                if (!(stack.getItemMeta() instanceof FireworkMeta meta)) continue;
                meta.setPower(power);
                stack.setItemMeta(meta);

                String key = "firework_rocket_" + power;
                entries.put(key, new CatalogEntry(key, stack, "Firework Rocket (Flight " + power + ")", true));
                count++;
            }
        } catch (Throwable t) {
            Main.getInstance().getLogger().warning(
                    "[WTB] Could not build firework catalog: " + t.getMessage());
        }

        return count;
    }

    // ── Lookup ───────────────────────────────────────────────────────────────

    /** Returns the entry for a key (case-insensitive), or null. */
    public CatalogEntry get(String key) {
        if (key == null) return null;
        return entries.get(key.toLowerCase(Locale.ROOT));
    }

    /** All keys, for tab completion.  Snapshot — safe to iterate. */
    public List<String> keys() {
        List<String> list = new ArrayList<>(entries.keySet());
        list.sort(String::compareTo);
        return list;
    }

    // ── Admin registration ───────────────────────────────────────────────────

    /**
     * Registers the given item under {@code key}.  Main thread (the DB insert
     * is small; registration is a rare admin action).
     *
     * @return null on success, otherwise a message key describing the failure:
     *         catalog_key_invalid / catalog_key_taken / catalog_item_blocked.
     */
    public String register(String rawKey, ItemStack held, String adminName) {
        String key = rawKey == null ? "" : rawKey.toLowerCase(Locale.ROOT);

        if (!KEY_PATTERN.matcher(key).matches()
                || RESERVED.contains(key)
                || Material.matchMaterial(key) != null) {
            return "catalog_key_invalid";
        }
        if (entries.containsKey(key)) {
            return "catalog_key_taken";
        }
        Material mat = held.getType();
        if (!Main.isTemplateTradeable(mat)) {
            return "catalog_item_blocked";
        }

        // Label: the item's own display name (stripped), else prettified key.
        String label = null;
        var meta = held.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            label = meta.getDisplayName().replaceAll("§.", "").trim();
            if (label.isBlank()) label = null;
        }
        if (label == null) label = prettify(key);

        CatalogEntry entry = new CatalogEntry(key, held, label, false);
        if (!dao.insert(key, entry.getBytes(), label, adminName)) {
            return "catalog_key_taken"; // PK collision or DB error — safest message
        }
        entries.put(key, entry);
        LogService.log("admin-actions",
                "[ADMIN] " + adminName + " registered catalog item '" + key + "' ("
                        + mat.name() + ", \"" + label + "\")");
        return null;
    }

    /**
     * Unregisters an admin entry.  Built-ins cannot be removed.  Existing
     * listings are unaffected — every listing stores its own template copy.
     *
     * @return null on success, otherwise a failure message key.
     */
    public String unregister(String rawKey, String adminName) {
        String key = rawKey == null ? "" : rawKey.toLowerCase(Locale.ROOT);
        CatalogEntry entry = entries.get(key);
        if (entry == null)      return "catalog_key_unknown";
        if (entry.isBuiltin())  return "catalog_key_builtin";

        if (dao.delete(key)) {
            entries.remove(key);
            LogService.log("admin-actions",
                    "[ADMIN] " + adminName + " unregistered catalog item '" + key + "'");
            return null;
        }
        return "catalog_key_unknown";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** "potion_strong_swiftness" → "Potion Strong Swiftness". */
    private static String prettify(String key) {
        StringBuilder sb = new StringBuilder();
        for (String w : key.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
