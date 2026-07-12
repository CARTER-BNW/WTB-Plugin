package wtb;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import wtb.commands.*;
import wtb.gui.*;
import wtb.listeners.MarketplaceClickListener;
import wtb.services.*;

import java.io.File;
import java.util.*;

public class Main extends JavaPlugin {

    // ── Statics (volatile so async threads always see post-onEnable values) ──

    private static volatile Main              instance;
    private static volatile Economy           economy;
    private static volatile FileConfiguration settings;
    private static volatile Set<Material>     blockedMaterials = Set.of();

    /**
     * Materials that can never be listed via the plain
     * {@code /wtb <material> …} syntax, regardless of server configuration.
     *
     * <p>Unlike {@code blockedMaterials} (an admin's discretionary economy-balance
     * list, editable via settings.yml), this set reflects an inherent limitation
     * of a bare-material order: these items' value lives in meta the plain
     * syntax can't express (container contents, potion effects, authored text,
     * player identity, cosmetic patterns).
     *
     * <p><b>V6:</b> most of them ARE tradeable through the item <b>catalog</b>
     * (built-in registry entries + {@code /wtb admin register}) — potions,
     * tipped arrows, goat horns, fireworks, banners, player heads, and custom
     * server items all order by catalog key with exact-meta matching.  Only
     * storage containers stay fully untradeable (see isTemplateTradeable).
     *
     * <p>Built once at class-load time; immutable for the lifetime of the JVM.
     */
    private static final Set<Material> NON_FUNGIBLE_MATERIALS = buildNonFungibleSet();

    // Services (written once in onEnable, never changed)
    private static volatile ListingService      listingService;
    private static volatile ClaimBoxService     claimBoxService;
    private static volatile TransactionService  transactionService;
    private static volatile NotificationService notificationService;
    private static volatile CatalogService      catalogService;
    private static volatile PlayerSettingsService playerSettingsService;

    // GUIs (written once in onEnable, never changed)
    private static volatile MainListingsGUI  mainGUI;
    private static volatile MyListingsGUI    myListingsGUI;
    private static volatile ClaimBoxGUI      claimBoxGUI;
    private static volatile TransactionsGUI  transactionsGUI;
    private static volatile ConfirmSaleGUI   confirmSaleGUI;

    // Instance fields for lifecycle management
    private ExpiryService   expiryService;
    private WTBTabCompleter tabCompleter;

    // ── Plugin lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        instance = this;

        // Config + settings must be loaded before anything else.
        saveDefaultConfig();
        loadSettingsFile();
        rebuildBlockedMaterials();

        // Open log file; must happen before any LogService.log() call.
        LogService.init(getDataFolder());

        // Vault
        if (!setupEconomy()) {
            getLogger().severe("Vault not found or no economy plugin registered — disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Database (runs the automatic V5 → V6 schema migration on first boot).
        new wtb.database.DatabaseManager();

        // Plugin-owned async DB executor — all reward/refund/notification
        // writes go through it so onDisable can drain them before the pool
        // closes (audit fix #2).
        wtb.database.DbExecutor.init();

        // Services (singletons)
        listingService      = new ListingService();
        claimBoxService     = new ClaimBoxService();
        transactionService  = new TransactionService();
        notificationService = new NotificationService();
        catalogService      = new CatalogService();
        catalogService.load(); // built-in registry entries + admin-registered items
        playerSettingsService = new PlayerSettingsService();

        // GUIs (singletons)
        mainGUI         = new MainListingsGUI();
        myListingsGUI   = new MyListingsGUI();
        claimBoxGUI     = new ClaimBoxGUI();
        transactionsGUI = new TransactionsGUI();
        confirmSaleGUI  = new ConfirmSaleGUI();

        // Expiry scheduler
        expiryService = new ExpiryService();
        expiryService.start();

        // Commands
        tabCompleter = new WTBTabCompleter();
        registerCommand("wtb",       new WTBCommand(), tabCompleter);
        registerCommand("wtbreload", new WTBReloadCommand(), null);

        // Listeners
        getServer().getPluginManager().registerEvents(
                new MarketplaceClickListener(), this);

        // V6.2: players already online (plugin-manager reload) never fired a
        // join event for us — load their preference rows now.
        getServer().getOnlinePlayers().forEach(playerSettingsService::loadOnJoin);

        getLogger().info("WTB v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (expiryService != null) {
            expiryService.stop();
        }
        // Audit fix #2 — order matters:
        //  1. Drain every queued/in-flight async DB write (rewards, refunds,
        //     notifications) BEFORE anything they depend on goes away.
        //  2. Close the file log (drained tasks may still have logged).
        //  3. Only then close the connection pool.
        // The old order closed the pool with writes still pending, so a plain
        // restart moments after a trade destroyed the owed money and items.
        wtb.database.DbExecutor.shutdownAndDrain(10);
        LogService.closeWriter();
        // Guard: if onEnable returned early (e.g. no Vault), DatabaseManager was never created.
        var dbManager = wtb.database.DatabaseManager.get();
        if (dbManager != null) dbManager.shutdown();
        getLogger().info("WTB disabled.");
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    /**
     * Reloads settings.yml and rebuilds all derived caches.
     *
     * <p>{@code instance} is set as the very first line of {@code onEnable()} and
     * is never nulled, so the null-guard below will never fire in production.  It
     * is present to make non-standard lifecycle scenarios (tests, PlugMan partial
     * reload) fail gracefully rather than with an NPE.
     */
    public static void reloadSettings() {
        Main inst = instance;
        if (inst == null) return;
        inst.loadSettingsFile();
        rebuildBlockedMaterials();
        if (inst.tabCompleter != null) {
            inst.tabCompleter.rebuildCache();
        }
        inst.getLogger().info("[WTB] Settings reloaded.");
    }

    /**
     * Loads settings.yml and attaches the bundled resource as DEFAULTS.
     * A server upgrading from V5 keeps its existing file untouched — any key
     * added in V6 (confirm-enabled, new messages, …) is resolved from the
     * jar's bundled copy instead of showing "Missing message key".  Admins
     * can copy new keys into their file whenever they want to customise them.
     */
    private void loadSettingsFile() {
        File file = new File(getDataFolder(), "settings.yml");
        if (!file.exists()) saveResource("settings.yml", false);
        FileConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (java.io.InputStream in = getResource("settings.yml")) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (java.io.IOException e) {
            getLogger().warning("Could not read bundled settings.yml defaults: " + e.getMessage());
        }
        settings = loaded;
    }

    /**
     * Builds an immutable Set of blocked materials from settings.
     * Stored as volatile so all threads always see the latest value after reload.
     */
    private static void rebuildBlockedMaterials() {
        Set<Material> blocked = new HashSet<>();
        for (String name : settings.getStringList("settings.blocked-materials")) {
            Material mat = Material.matchMaterial(name);
            if (mat != null) blocked.add(mat);
        }
        blockedMaterials = Collections.unmodifiableSet(blocked);
    }

    /**
     * Builds the built-in non-fungible set (see {@link #NON_FUNGIBLE_MATERIALS}).
     *
     * <p>{@code matchMaterial(String)} (rather than a direct enum reference) is
     * used for any material that isn't guaranteed to exist on every Paper 1.21.x
     * build, so an older or newer server doesn't fail to compile or load over a
     * single missing constant — it just silently skips that one entry.
     */
    private static Set<Material> buildNonFungibleSet() {
        Set<Material> set = new HashSet<>();

        // Storage containers — can hold arbitrary hidden contents (highest risk).
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("SHULKER_BOX")) set.add(mat);
        }
        addIfPresent(set, "BUNDLE"); // stabilised in 1.21.2 — harmless no-op on older builds

        // Player-authored content / player identity.
        // (Tradeable via catalog registration; not via bare material.)
        set.add(Material.WRITTEN_BOOK);
        set.add(Material.PLAYER_HEAD);

        // Potion-effect-bearing — identical Material, wildly different value.
        // Every variant is orderable through the built-in catalog instead
        // (potion_<type>, splash_potion_<type>, lingering_potion_<type>,
        //  tipped_arrow_<type>).
        set.add(Material.POTION);
        set.add(Material.SPLASH_POTION);
        set.add(Material.LINGERING_POTION);
        set.add(Material.TIPPED_ARROW);
        set.add(Material.SUSPICIOUS_STEW);   // effect component — a "plain" stew doesn't exist
        addIfPresent(set, "OMINOUS_BOTTLE"); // amplifier component — only drops with one
        set.add(Material.FILLED_MAP);        // map-id component — every real map is unique

        // Cosmetic-variant — orderable through the catalog (built-in horns/rockets,
        // admin-registered stars/banners), not via bare material.
        set.add(Material.FIREWORK_ROCKET);
        set.add(Material.FIREWORK_STAR);
        set.add(Material.GOAT_HORN);
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_BANNER")) set.add(mat);
        }

        return Collections.unmodifiableSet(set);
    }

    /** Adds a material by name only if it resolves on the running server's API version. */
    private static void addIfPresent(Set<Material> set, String name) {
        Material mat = Material.matchMaterial(name);
        if (mat != null) set.add(mat);
    }

    /**
     * Returns true if {@code mat} can be listed via the PLAIN
     * {@code /wtb <material> …} syntax — the admin blocklist plus the built-in
     * {@link #NON_FUNGIBLE_MATERIALS} set.  Tools, weapons, armor, enchanted
     * books, and books-and-quills are tradeable as of V6 because fulfilment
     * matches a pristine item template (see {@code wtb.utils.ItemMatcher}).
     * Safe to call with any {@code Material}, including blocks and {@code AIR}.
     */
    public static boolean isTradeable(Material mat) {
        if (mat == null || !mat.isItem() || mat.isAir())  return false;
        if (blockedMaterials.contains(mat))               return false; // admin-configured
        if (NON_FUNGIBLE_MATERIALS.contains(mat))         return false; // built-in meta-bearing items
        return true;
    }

    /**
     * Returns true if {@code mat} may back a CATALOG (exact-meta) order —
     * used by /wtb admin register and by catalog-key listings.  Deliberately
     * wider than {@link #isTradeable}: the whole point of the catalog is
     * meta-bearing items (heads, potions, banners, custom god items).  Only
     * the admin blocklist and storage containers remain excluded — a container
     * template would require the seller's container contents to match
     * slot-for-slot, which is a griefable, effectively-unfillable order.
     */
    public static boolean isTemplateTradeable(Material mat) {
        if (mat == null || !mat.isItem() || mat.isAir())  return false;
        if (blockedMaterials.contains(mat))               return false; // admin-configured
        if (mat.name().endsWith("SHULKER_BOX"))           return false;
        if (mat.name().equals("BUNDLE"))                  return false;
        return true;
    }

    /**
     * Returns true if listing {@code mat} REQUIRES an enchantment spec
     * ({@code /wtb <material> <qty> <price> <enchant> [level]}).
     * Currently only {@code ENCHANTED_BOOK} — a bare enchanted book with no
     * stored enchantment isn't a legitimate item, so the spec is mandatory.
     */
    public static boolean requiresEnchantSpec(Material mat) {
        return mat == Material.ENCHANTED_BOOK;
    }

    // ── Vault ────────────────────────────────────────────────────────────────

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    // ── Public accessors ─────────────────────────────────────────────────────

    public static Main              getInstance()          { return instance;          }
    public static Economy           getEconomy()           { return economy;           }
    public static FileConfiguration getSettings()          { return settings;          }
    public static Set<Material>     getBlockedMaterials()  { return blockedMaterials;  }

    public static ListingService      getListingService()      { return listingService;      }
    public static ClaimBoxService     getClaimBoxService()     { return claimBoxService;     }
    public static TransactionService  getTransactionService()  { return transactionService;  }
    public static NotificationService getNotificationService() { return notificationService; }
    public static CatalogService      getCatalogService()      { return catalogService;      }
    public static PlayerSettingsService getPlayerSettingsService() { return playerSettingsService; }

    public static MainListingsGUI  getMainGUI()          { return mainGUI;         }
    public static MyListingsGUI    getMyListingsGUI()    { return myListingsGUI;   }
    public static ClaimBoxGUI      getClaimBoxGUI()      { return claimBoxGUI;     }
    public static TransactionsGUI  getTransactionsGUI()  { return transactionsGUI; }
    public static ConfirmSaleGUI   getConfirmSaleGUI()   { return confirmSaleGUI;  }

    // ── Messages ─────────────────────────────────────────────────────────────

    /** Returns a colour-translated message from settings.yml, or a fallback. */
    public static String msg(String key) {
        String raw = settings.getString("messages." + key);
        if (raw == null) raw = "§c[WTB] Missing message key: " + key;
        return raw.replace("&", "§");
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    /**
     * Returns true if the player has the {@code wtb.admin} node OR is in one
     * of the extra admin groups listed under {@code settings.admin-groups}.
     */
    public static boolean hasAdminPermission(Player player) {
        if (player.hasPermission("wtb.admin")) return true;

        List<String> groups = settings.getStringList("settings.admin-groups");
        for (String perm : groups) {
            if (player.hasPermission(perm)) return true;
        }
        return false;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void registerCommand(String name, CommandExecutor executor,
                                 TabCompleter tabComp) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command '" + name + "' not found in plugin.yml — skipping.");
            return;
        }
        cmd.setExecutor(executor);
        if (tabComp != null) cmd.setTabCompleter(tabComp);
    }
}
