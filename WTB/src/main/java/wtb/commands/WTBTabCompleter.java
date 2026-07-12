package wtb.commands;

import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import wtb.Main;
import wtb.models.EnchantSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WTBTabCompleter implements TabCompleter {

    /**
     * Cached list of material names that are allowed for listings.
     * Built once and cached; call rebuildCache() after a settings reload.
     */
    private volatile List<String> materialCache;

    /** Cached list of enchantment keys ("sharpness", "mending", …). */
    private volatile List<String> enchantCache;

    /** Called by Main.reloadSettings() so the caches stay in sync. */
    public void rebuildCache() {
        materialCache = null; // force rebuild on next use
        enchantCache  = null;
    }

    private List<String> getMaterialList() {
        if (materialCache != null) return materialCache;

        List<String> list = new ArrayList<>();
        for (Material mat : Material.values()) {
            if (!Main.isTradeable(mat)) continue;
            list.add(mat.name().toLowerCase(Locale.ROOT));
        }
        materialCache = List.copyOf(list); // immutable snapshot
        return materialCache;
    }

    private List<String> getEnchantList() {
        if (enchantCache != null) return enchantCache;

        List<String> list = new ArrayList<>();
        for (Enchantment ench : Registry.ENCHANTMENT) {
            // Vanilla keys are offered without the "minecraft:" prefix (that is
            // how players type them); datapack/plugin enchants keep their
            // namespace so they stay unambiguous.
            var key = ench.getKey();
            list.add("minecraft".equals(key.getNamespace())
                    ? key.getKey()
                    : key.toString());
        }
        list.sort(String::compareTo);
        enchantCache = List.copyOf(list);
        return enchantCache;
    }

    /** True if the given first arg is the ENCHANTED_BOOK material. */
    private static boolean isBook(String materialArg) {
        Material mat = Material.matchMaterial(materialArg);
        return mat == Material.ENCHANTED_BOOK;
    }

    /** Suggests 1..maxLevel for the enchant typed in {@code enchArg}. */
    private static List<String> levelSuggestions(String enchArg) {
        Enchantment ench = EnchantSpec.resolveByName(enchArg);
        if (ench == null) return List.of("<level>");
        List<String> levels = new ArrayList<>();
        for (int i = 1; i <= ench.getMaxLevel(); i++) {
            levels.add(String.valueOf(i));
        }
        return levels;
    }

    /** Adds material names AND catalog keys matching the typed prefix. */
    private void addItemTokens(List<String> completions, String partial) {
        for (String name : getMaterialList()) {
            if (name.startsWith(partial)) completions.add(name);
        }
        // Catalog keys (potion_…, goat_horn_…, god items).  The catalog map is
        // a ConcurrentHashMap — lock-free snapshot, safe on the main thread.
        for (String key : Main.getCatalogService().keys()) {
            if (key.startsWith(partial)) completions.add(key);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                      Command command,
                                      String alias,
                                      String[] args) {

        boolean isAdmin = (sender instanceof Player p)
                ? Main.hasAdminPermission(p)
                : sender.hasPermission("wtb.admin");

        // V6.2: "/wtb settings X…" mirrors "/wtb X…" — delegate with the
        // "settings" token stripped so nested completions stay in sync (the
        // admin sub-command stays hidden for non-admins via the same check).
        if (args.length >= 2 && args[0].equalsIgnoreCase("settings")) {
            return onTabComplete(sender, command, alias,
                    java.util.Arrays.copyOfRange(args, 1, args.length));
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);

            // Named sub-commands first ("buy" kept as legacy alias; the "cancle"
            // typo alias works but is deliberately NOT advertised here).
            List<String> subs = new ArrayList<>(
                    List.of("buy", "fill", "cancel", "my", "claim", "tx",
                            "settings", "mute", "help"));
            if (isAdmin) subs.add("admin");
            for (String sub : subs) {
                if (sub.startsWith(partial)) completions.add(sub);
            }

            // Item tokens for the direct /wtb <item> <qty> <price> syntax.
            // Shown after sub-commands so the short keywords stay at the top.
            addItemTokens(completions, partial);

        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);

            if (sub.equals("buy")) {
                // Legacy: /wtb buy <item>
                addItemTokens(completions, args[1].toLowerCase(Locale.ROOT));
            } else if (sub.equals("admin")) {
                if (isAdmin) completions.addAll(List.of("cancel", "info", "register", "unregister"));
            } else if (sub.equals("mute")) {
                // V6.2: /wtb [settings] mute <full|partial|all|off>
                String partial = args[1].toLowerCase(Locale.ROOT);
                for (String mode : List.of("full", "partial", "all", "off")) {
                    if (mode.startsWith(partial)) completions.add(mode);
                }
            } else if (isItemToken(args[0])) {
                // Direct: /wtb <item> <quantity>
                completions.add("<quantity>");
            }

        } else if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);

            if (sub.equals("buy")) {
                // Legacy: /wtb buy <item> <quantity>
                completions.add("<quantity>");
            } else if (sub.equals("admin")) {
                if (isAdmin) {
                    switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "cancel", "info" -> completions.add("<listing_id>");
                        case "register"       -> completions.add("<new_key>");
                        case "unregister"     -> {
                            String partial = args[2].toLowerCase(Locale.ROOT);
                            for (String key : Main.getCatalogService().keys()) {
                                // Only admin-registered keys can be removed.
                                var entry = Main.getCatalogService().get(key);
                                if (entry != null && !entry.isBuiltin()
                                        && key.startsWith(partial)) {
                                    completions.add(key);
                                }
                            }
                        }
                    }
                }
            } else if (isItemToken(args[0])) {
                // Direct: /wtb <item> <quantity> <total_price>
                completions.add("<total_price>");
            }

        } else if (args.length == 4) {
            if (args[0].equalsIgnoreCase("buy")) {
                // Legacy: /wtb buy <item> <quantity> <total_price>
                completions.add("<total_price>");
            } else if (isBook(args[0])) {
                // Direct book order: /wtb enchanted_book <qty> <price> <enchant>
                String partial = args[3].toLowerCase(Locale.ROOT);
                for (String name : getEnchantList()) {
                    if (name.startsWith(partial)) completions.add(name);
                }
            }

        } else if (args.length == 5) {
            if (args[0].equalsIgnoreCase("buy") && isBook(args[1])) {
                // Legacy book order: /wtb buy enchanted_book <qty> <price> <enchant>
                String partial = args[4].toLowerCase(Locale.ROOT);
                for (String name : getEnchantList()) {
                    if (name.startsWith(partial)) completions.add(name);
                }
            } else if (isBook(args[0])) {
                // Direct book order level: /wtb enchanted_book <qty> <price> <enchant> <level>
                completions.addAll(levelSuggestions(args[3]));
            }

        } else if (args.length == 6
                && args[0].equalsIgnoreCase("buy") && isBook(args[1])) {
            // Legacy book order level.
            completions.addAll(levelSuggestions(args[4]));
        }

        return completions;
    }

    /** True if the token resolves to a material OR a catalog key. */
    private static boolean isItemToken(String token) {
        return Material.matchMaterial(token) != null
                || Main.getCatalogService().get(token) != null;
    }
}
