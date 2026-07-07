package wtb.commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import wtb.Main;
import wtb.database.ListingDAO;
import wtb.models.CatalogEntry;
import wtb.models.EnchantSpec;
import wtb.models.Listing;
import wtb.utils.Format;
import wtb.utils.NameCache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WTBCommand implements CommandExecutor {

    // ConcurrentHashMap: MarketplaceClickListener's PlayerQuitEvent handler
    // calls handlePlayerQuit() from the main thread to evict stale entries.
    private static final Map<UUID, Long> buyCooldowns    = new ConcurrentHashMap<>();
    private static final long            BUY_COOLDOWN_MS = 2_000L;

    /** Called on player quit (from MarketplaceClickListener) to prevent memory leak. */
    public static void handlePlayerQuit(UUID uuid) {
        buyCooldowns.remove(uuid);
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command is for players only.");
            return true;
        }

        if (args.length == 0) {
            Main.getMainGUI().openAsync(player, 0);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            // Legacy alias — /wtb buy <item> <qty> <price> [enchant] [level]
            // still works.  Shifts args left by one so handleCreate sees the same
            // layout as the direct syntax.
            case "buy"    -> handleCreate(player, Arrays.copyOfRange(args, 1, args.length));

            case "my"     -> { Main.getMyListingsGUI().open(player, 0); yield true; }
            case "claim"  -> { Main.getClaimBoxGUI().open(player, 0);  yield true; }
            case "tx"     -> { Main.getTransactionsGUI().open(player, 0); yield true; }
            case "help"   -> { player.sendMessage(Main.msg("help_message")); yield true; }
            case "admin"  -> handleAdmin(player, args);

            // V6: fill everything you can, via the confirmation screen.
            case "fill"   -> handleFill(player);

            // V6: cancel ALL of your open buy orders.  "cancle" is a silent
            // alias for the common typo — neither resolves as a material or
            // catalog key (CatalogService reserves both), so no shadowing.
            case "cancel", "cancle" -> handleCancelAll(player);

            // Direct listing syntax:
            //   /wtb <material> <qty> <price> [enchant] [level]
            //   /wtb <catalog-key> <qty> <price>          (potions, horns, god items, …)
            default       -> handleCreate(player, args);
        };
    }

    // ── /wtb <item> <quantity> <price> [enchant] [level] ─────────────────────
    //
    // args[0] = material name OR catalog key
    // args[1] = quantity
    // args[2] = price
    // args[3] = enchant name  (ENCHANTED_BOOK material orders only)
    // args[4] = enchant level (optional, default 1)
    //
    // Also reached from the "buy" alias above, which strips the leading "buy"
    // token before calling this method.

    private boolean handleCreate(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Main.msg("usage_buy"));
            return true;
        }

        // ── Arg validation (all before cooldown to avoid wasting it on typos) ──

        // Materials take priority; if the token isn't a material, try the
        // catalog (built-in potion/horn/rocket keys + admin-registered items).
        // Registration guarantees no key can ever shadow a material name.
        Material     mat = Material.matchMaterial(args[0]);
        CatalogEntry cat = null;
        if (mat == null || !mat.isItem() || mat.isAir()) {
            cat = Main.getCatalogService().get(args[0]);
            if (cat == null) {
                player.sendMessage(Main.msg("invalid_material"));
                return true;
            }
            mat = cat.getMaterial();
        }

        if (cat != null) {
            // Catalog orders carry their full meta in the key — no extra args.
            if (args.length > 3) {
                player.sendMessage(Main.msg("enchant_not_allowed"));
                return true;
            }
            if (!Main.isTemplateTradeable(mat)) {
                player.sendMessage(Main.msg("material_blocked"));
                return true;
            }
        } else if (!Main.isTradeable(mat)) {
            // isTradeable() covers the admin blocklist AND the built-in
            // non-fungible check — see Main.isTradeable() for the rationale.
            player.sendMessage(Main.msg("material_blocked"));
            return true;
        }

        int quantity;
        try {
            quantity = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(Main.msg("invalid_quantity"));
            return true;
        }
        if (quantity <= 0) {
            player.sendMessage(Main.msg("invalid_quantity"));
            return true;
        }

        double price;
        try {
            price = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(Main.msg("invalid_price"));
            return true;
        }
        if (price <= 0 || !Double.isFinite(price)) {
            player.sendMessage(Main.msg("invalid_price"));
            return true;
        }

        // ── Enchantment spec for plain ENCHANTED_BOOK orders ──────────────────

        EnchantSpec enchant = null;
        if (cat == null) {
            boolean needsSpec = Main.requiresEnchantSpec(mat);

            if (needsSpec) {
                if (args.length < 4) {
                    player.sendMessage(Main.msg("enchant_required"));
                    return true;
                }
                int level = 1;
                if (args.length >= 5) {
                    try {
                        level = Integer.parseInt(args[4]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(Main.msg("invalid_enchant_level"));
                        return true;
                    }
                }

                Enchantment resolved = EnchantSpec.resolveByName(args[3]);
                if (resolved == null) {
                    player.sendMessage(Main.msg("invalid_enchant"));
                    return true;
                }
                if (level < 1 || level > resolved.getMaxLevel()) {
                    player.sendMessage(Main.msg("invalid_enchant_level")
                            .replace("{max}", String.valueOf(resolved.getMaxLevel())));
                    return true;
                }
                enchant = new EnchantSpec(resolved.getKey().toString(), level);

            } else if (args.length > 3) {
                // Extra args on a non-book order are almost certainly a mistake
                // ("/wtb diamond_sword 1 500 sharpness 5") — reject loudly instead
                // of silently creating a plain-sword order the player didn't want.
                player.sendMessage(Main.msg("enchant_not_allowed"));
                return true;
            }
        }

        // ── Cooldown (recorded AFTER validation, not before) ──────────────────
        long now  = System.currentTimeMillis();
        Long last = buyCooldowns.get(player.getUniqueId());
        if (last != null && now - last < BUY_COOLDOWN_MS) {
            player.sendMessage(Main.msg("buy_cooldown"));
            return true;
        }
        buyCooldowns.put(player.getUniqueId(), now);

        // ── Listing cap (COUNT query, fast with index, acceptable on main thread)
        int maxListings = Main.getSettings().getInt("settings.listing.max-listings", 5);
        int active      = Main.getListingService().countActiveListings(player.getUniqueId());
        if (active >= maxListings) {
            player.sendMessage(Main.msg("max_listings_reached")
                    .replace("{max}", String.valueOf(maxListings)));
            return true;
        }

        // createListing: Vault withdrawal + DB write (all on main thread as Vault requires).
        // It handles its own messaging and rolls back the withdrawal on DB failure.
        ItemStack template   = cat != null ? cat.template() : null;
        String    customName = cat != null ? cat.getLabel() : null;
        Main.getListingService().createListing(
                player, mat, enchant, template, customName, quantity, price);
        Main.getMainGUI().clearCache();
        return true;
    }

    // ── /wtb fill ─────────────────────────────────────────────────────────────

    private boolean handleFill(Player player) {
        if (Main.getSettings().getBoolean("settings.listing.confirm-enabled", true)) {
            Main.getConfirmSaleGUI().openAll(player);
        } else {
            // Confirmation disabled by the server owner → behave like the
            // legacy Fill All button: open the marketplace so the button path
            // (with its cooldown) is used.  Direct no-confirm bulk fill from a
            // command would bypass the click cooldown entirely.
            Main.getMainGUI().openAsync(player, 0);
            player.sendMessage(Main.msg("fill_use_button"));
        }
        return true;
    }

    // ── /wtb cancel  (and the /wtb cancle typo alias) ────────────────────────

    private boolean handleCancelAll(Player player) {
        // Fully async; per-row conditional UPDATEs resolve any race with an
        // in-flight fulfilment (see ListingService.cancelAllListings docs).
        Main.getListingService().cancelAllListings(player);
        return true;
    }

    // ── /wtb admin … ─────────────────────────────────────────────────────────

    private boolean handleAdmin(Player player, String[] args) {
        if (!Main.hasAdminPermission(player)) {
            player.sendMessage(Main.msg("no_permission"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(Main.msg("usage_admin"));
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "cancel"     -> handleAdminCancel(player, args);
            case "info"       -> handleAdminInfo(player, args);
            case "register"   -> handleAdminRegister(player, args);
            case "unregister" -> handleAdminUnregister(player, args);
            default           -> { player.sendMessage(Main.msg("usage_admin")); yield true; }
        };
    }

    /** /wtb admin register <key> — captures the item in the admin's main hand. */
    private boolean handleAdminRegister(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /wtb admin register <key>  (hold the item)");
            return true;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            player.sendMessage(Main.msg("catalog_hand_empty"));
            return true;
        }

        String failure = Main.getCatalogService().register(args[2], held, player.getName());
        if (failure != null) {
            player.sendMessage(Main.msg(failure));
            return true;
        }

        player.sendMessage(Main.msg("catalog_registered")
                .replace("{key}", args[2].toLowerCase(Locale.ROOT)));
        return true;
    }

    /** /wtb admin unregister <key> — removes an admin-registered entry. */
    private boolean handleAdminUnregister(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /wtb admin unregister <key>");
            return true;
        }

        String failure = Main.getCatalogService().unregister(args[2], player.getName());
        if (failure != null) {
            player.sendMessage(Main.msg(failure));
            return true;
        }

        player.sendMessage(Main.msg("catalog_unregistered")
                .replace("{key}", args[2].toLowerCase(Locale.ROOT)));
        return true;
    }

    private boolean handleAdminCancel(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /wtb admin cancel <listing-id>");
            return true;
        }

        int id;
        try {
            id = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid listing ID.");
            return true;
        }

        final int fId = id;
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            Listing listing = new ListingDAO().getById(fId);

            if (listing == null) {
                Bukkit.getScheduler().runTask(Main.getInstance(), () ->
                        player.sendMessage("§cNo listing found with ID §e#" + fId + "§c."));
                return;
            }

            // adminCancelListing is DB-only — safe on async thread.
            // Uses the plugin singleton, NOT a throw-away new ListingService().
            boolean cancelled = Main.getListingService()
                    .adminCancelListing(player.getUniqueId(), listing);

            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                if (cancelled) {
                    player.sendMessage(Main.msg("admin_cancel_success")
                            .replace("{id}",    String.valueOf(fId))
                            .replace("{buyer}", NameCache.getName(listing.getBuyer())));
                    Main.getMainGUI().clearCache();
                } else {
                    player.sendMessage("§cListing §e#" + fId
                            + " §cis already completed (filled/expired/cancelled).");
                }
            });
        });
        return true;
    }

    private boolean handleAdminInfo(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /wtb admin info <listing-id>");
            return true;
        }

        int id;
        try {
            id = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid listing ID.");
            return true;
        }

        final int fId = id;
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            Listing listing = new ListingDAO().getById(fId);
            Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                if (listing == null) {
                    player.sendMessage("§cNo listing found with ID §e#" + fId + "§c.");
                    return;
                }
                double perUnit = listing.getOriginalQuantity() > 0
                        ? listing.getOriginalPrice() / listing.getOriginalQuantity() : 0;

                player.sendMessage("§e══ Listing #" + fId + " ══");
                player.sendMessage("§7Buyer:      §f" + NameCache.getName(listing.getBuyer()));
                player.sendMessage("§7Item:       §f" + listing.displayName()
                        + (listing.isCustom() ? " §8[exact-item order]" : ""));
                player.sendMessage("§7Ordered:    §f" + listing.getOriginalQuantity()
                        + "  (remaining: " + listing.getRemainingQuantity() + ")");
                player.sendMessage("§7Total:      §f" + Format.money(listing.getOriginalPrice())
                        + "  (§f" + Format.money(perUnit) + "/unit)");
                player.sendMessage("§7Paid out:   §f"
                        + Format.money(listing.getPaidCents() / 100.0)
                        + "  (§f" + listing.getPaidCents() + " cents)");
                player.sendMessage("§7State:      §f" + listing.getState());
                player.sendMessage("§7Expires:    §f" + new java.util.Date(listing.getExpiresAt()));
            });
        });
        return true;
    }
}
