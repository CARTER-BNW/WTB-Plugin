package probe;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Claim Box stress suite for WTB v6.4.0.
 *
 * Exercises the REAL production code paths reflectively:
 *  - ClaimBoxService.claimDetailed / addItemDirect / addMoneyDirect / addRefundDirect
 *  - MarketplaceClickListener.processClaimBatch  (Claim All batch chain)
 *  - MarketplaceClickListener.claimAllOfType     (shift-click type claim)
 *  - ClaimBoxGUI.open/toggleSort/getPage         (sort, paging, rendering)
 *
 * Fake players: java.lang.reflect.Proxy players whose PlayerInventory delegates
 * to a real CraftInventory (real addItem partial-fit semantics) and whose
 * openInventory() captures the rendered GUI for assertions.
 *
 * INVARIANT under test everywhere: delivered + still-in-box == seeded.
 * Any violation is item duplication or item loss.
 */
public class ClaimStressProbe extends JavaPlugin {

    private int pass = 0, fail = 0;

    // WTB reflection handles
    private Object svc;                 // ClaimBoxService
    private Object gui;                 // ClaimBoxGUI
    private Object listener;            // MarketplaceClickListener (fresh instance)
    private Method mAddItemDirect, mAddMoneyDirect, mAddRefundDirect,
                   mGetClaims, mClaimDetailed, mBatch, mTypeClaim,
                   mGuiOpen, mGuiToggleSort, mGuiGetPage;
    private Set<?> claimingAll;         // MarketplaceClickListener.CLAIMING_ALL

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTask(this, () -> {
            try { bind(); } catch (Throwable t) { abort(t); return; }
            queue.addAll(List.of(
                    this::s1_claimAll_emptyInv,
                    this::s2_claimAll_partialInv,
                    this::s3_claimAll_fullInv,
                    this::s4_typeClaim_basic,
                    this::s5_typeClaim_limitedSpace,
                    this::s6_typeClaim_moneyFallback,
                    this::s7_sort_type_render,
                    this::s8_sort_newest_render,
                    this::s9_paging_clamp_and_stay,
                    this::s10_races,
                    this::s11_volume_1000,
                    this::s12_lock_released,
                    this::s13_db_contention
            ));
            next();
        });
    }

    @SuppressWarnings("unchecked")
    private void bind() throws Exception {
        Class<?> main     = Class.forName("wtb.Main");
        Class<?> entryCls = Class.forName("wtb.models.ClaimEntry");
        Class<?> guiCls   = Class.forName("wtb.gui.ClaimBoxGUI");
        Class<?> lisCls   = Class.forName("wtb.listeners.MarketplaceClickListener");

        svc = main.getMethod("getClaimBoxService").invoke(null);
        gui = main.getMethod("getClaimBoxGUI").invoke(null);
        listener = lisCls.getConstructor().newInstance();

        Class<?> svcCls = svc.getClass();
        mAddItemDirect   = svcCls.getMethod("addItemDirect", UUID.class, ItemStack.class);
        mAddMoneyDirect  = svcCls.getMethod("addMoneyDirect", UUID.class, double.class);
        mAddRefundDirect = svcCls.getMethod("addRefundDirect", UUID.class, double.class);
        mGetClaims       = svcCls.getMethod("getClaims", UUID.class);
        mClaimDetailed   = svcCls.getMethod("claimDetailed", Player.class, entryCls, boolean.class);

        mBatch = lisCls.getDeclaredMethod("processClaimBatch",
                Player.class, guiCls, Deque.class, int[].class, String.class);
        mBatch.setAccessible(true);
        mTypeClaim = lisCls.getDeclaredMethod("claimAllOfType",
                Player.class, guiCls, int.class);
        mTypeClaim.setAccessible(true);

        var f = lisCls.getDeclaredField("CLAIMING_ALL");
        f.setAccessible(true);
        claimingAll = (Set<?>) f.get(null);

        mGuiOpen       = guiCls.getMethod("open", Player.class, int.class);
        mGuiToggleSort = guiCls.getMethod("toggleSort", Player.class);
        mGuiGetPage    = guiCls.getMethod("getPage", Player.class);
    }

    // ── Scenario driver ──────────────────────────────────────────────────────

    private final Deque<Runnable> queue = new ArrayDeque<>();

    private void next() {
        Runnable r = queue.poll();
        if (r == null) { finish(); return; }
        try { r.run(); } catch (Throwable t) { abort(t); }
    }

    /** Polls cond every 2 ticks (max ~30 s), then waits settleTicks and runs then. */
    private void await(BooleanSupplier cond, int settleTicks, Runnable then) {
        long deadline = System.currentTimeMillis() + 30_000;
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            boolean ok;
            try { ok = cond.getAsBoolean(); } catch (Throwable t) { ok = false; }
            if (ok) {
                task.cancel();
                Bukkit.getScheduler().runTaskLater(this, then, settleTicks);
            } else if (System.currentTimeMillis() > deadline) {
                task.cancel();
                check(false, "await timed out");
                Bukkit.getScheduler().runTaskLater(this, then, 1L);
            }
        }, 2L, 2L);
    }

    private void check(boolean ok, String what) {
        if (ok) { pass++; getLogger().info("STRESS PASS: " + what); }
        else    { fail++; getLogger().severe("STRESS FAIL: " + what); }
    }

    private void checkEq(long expected, long actual, String what) {
        check(expected == actual, what + " (expected " + expected + ", got " + actual + ")");
    }

    private void finish() {
        getLogger().info("STRESS ================= SUMMARY =================");
        getLogger().info("STRESS RESULT: " + pass + " passed, " + fail + " failed");
        getLogger().info(fail == 0 ? "STRESS ALL GREEN" : "STRESS HAS FAILURES");
        getLogger().info("STRESS DONE");
        Bukkit.shutdown();
    }

    private void abort(Throwable t) {
        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ? t.getCause() : t;
        java.io.StringWriter sw = new java.io.StringWriter();
        cause.printStackTrace(new java.io.PrintWriter(sw));
        getLogger().severe("STRESS ABORT:\n" + sw);
        fail++;
        finish();
    }

    // ── WTB helpers (reflective) ─────────────────────────────────────────────

    private void seedItem(UUID id, Material mat, int amount) throws Exception {
        mAddItemDirect.invoke(svc, id, new ItemStack(mat, amount));
    }

    private List<?> claims(UUID id) throws Exception {
        return (List<?>) mGetClaims.invoke(svc, id);
    }

    private static int entryId(Object entry) throws Exception {
        return (int) entry.getClass().getMethod("getId").invoke(entry);
    }

    private static ItemStack entryItem(Object entry) throws Exception {
        return (ItemStack) entry.getClass().getMethod("getItem").invoke(entry);
    }

    private static String entryType(Object entry) throws Exception {
        return String.valueOf(entry.getClass().getMethod("getType").invoke(entry));
    }

    /** Sum of ITEM amounts in the box, optionally for one material. */
    private long boxItems(UUID id, Material mat) throws Exception {
        long total = 0;
        for (Object c : claims(id)) {
            ItemStack it = entryItem(c);
            if (it != null && (mat == null || it.getType() == mat)) total += it.getAmount();
        }
        return total;
    }

    private double boxMoney(UUID id) throws Exception {
        double total = 0;
        for (Object c : claims(id)) {
            String t = entryType(c);
            if (t.equals("MONEY") || t.equals("REFUND")) {
                total += (double) c.getClass().getMethod("getMoney").invoke(c);
            }
        }
        return total;
    }

    private String claimResult(FakePlayer p, Object entry) throws Exception {
        return String.valueOf(mClaimDetailed.invoke(svc, p.player, entry, true));
    }

    private void runBatch(FakePlayer p, List<?> entries, int[] counters, String label) throws Exception {
        mBatch.invoke(listener, p.player, gui, new ArrayDeque<>(entries), counters, label);
    }

    private static long invCount(Inventory inv, Material mat) {
        long n = 0;
        for (ItemStack it : inv.getContents()) {
            if (it != null && (mat == null || it.getType() == mat)) n += it.getAmount();
        }
        return n;
    }

    private static void fillSlots(Inventory inv, int from, int to, Material mat, int amount) {
        for (int i = from; i < to; i++) inv.setItem(i, new ItemStack(mat, amount));
    }

    // ── Scenarios ────────────────────────────────────────────────────────────

    /** S1: Claim All, EMPTY inventory, 100x 64-COBBLE. 36 slots fit -> 36 granted. */
    private void s1_claimAll_emptyInv() {
        try {
            getLogger().info("STRESS S1: Claim All / empty inventory / 100 stacks");
            FakePlayer p = new FakePlayer(this, "S1");
            for (int i = 0; i < 100; i++) seedItem(p.id, Material.COBBLESTONE, 64);
            List<?> entries = claims(p.id);
            checkEq(100, entries.size(), "S1 seeded entries");

            int[] counters = new int[3];
            long t0 = System.currentTimeMillis();
            runBatch(p, entries, counters, null);
            await(() -> counters[0] + counters[1] >= 100, 50, () -> {
                try {
                    long ms = System.currentTimeMillis() - t0;
                    checkEq(36, counters[0], "S1 granted");
                    checkEq(64, counters[1], "S1 failed");
                    checkEq(1,  counters[2], "S1 inventory-full flag set");
                    long delivered = invCount(p.backing, Material.COBBLESTONE);
                    long inBox     = boxItems(p.id, Material.COBBLESTONE);
                    checkEq(36 * 64, delivered, "S1 delivered");
                    checkEq(64 * 64, inBox,     "S1 left in box");
                    checkEq(6400, delivered + inBox, "S1 CONSERVATION");
                    getLogger().info("STRESS S1 timing: " + ms + " ms for 100-entry batch");
                } catch (Throwable t) { abort(t); return; }
                next();
            });
        } catch (Throwable t) { abort(t); }
    }

    /** S2: Claim All, PARTIAL inventory (capacity 416 of 1280). Forces a partial fit. */
    private void s2_claimAll_partialInv() {
        try {
            getLogger().info("STRESS S2: Claim All / partial inventory / partial-fit stack");
            FakePlayer p = new FakePlayer(this, "S2");
            // 28 slots dirt-full, 3 slots 32x cobble (96 room), 5 empty (320 room) = 416 capacity
            fillSlots(p.backing, 0, 28, Material.DIRT, 64);
            fillSlots(p.backing, 28, 31, Material.COBBLESTONE, 32);
            for (int i = 0; i < 20; i++) seedItem(p.id, Material.COBBLESTONE, 64);

            long startCobble = invCount(p.backing, Material.COBBLESTONE); // 96
            int[] counters = new int[3];
            runBatch(p, claims(p.id), counters, null);
            await(() -> counters[0] + counters[1] >= 20, 50, () -> {
                try {
                    checkEq(6,  counters[0], "S2 granted (full fits)");
                    checkEq(14, counters[1], "S2 failed (1 partial + 13 skipped)");
                    long delivered = invCount(p.backing, Material.COBBLESTONE) - startCobble;
                    long inBox     = boxItems(p.id, Material.COBBLESTONE);
                    checkEq(416, delivered, "S2 delivered (inventory exactly filled)");
                    checkEq(864, inBox,     "S2 left in box (32 requeued + 13x64 untouched)");
                    checkEq(20 * 64, delivered + inBox, "S2 CONSERVATION (the v6.2.1 dupe test)");
                } catch (Throwable t) { abort(t); return; }
                next();
            });
        } catch (Throwable t) { abort(t); }
    }

    /** S3: Claim All, FULL inventory. Nothing delivered, nothing lost, one churned row. */
    private void s3_claimAll_fullInv() {
        try {
            getLogger().info("STRESS S3: Claim All / full inventory / 15 stacks");
            FakePlayer p = new FakePlayer(this, "S3");
            fillSlots(p.backing, 0, 36, Material.DIRT, 64);
            for (int i = 0; i < 15; i++) seedItem(p.id, Material.COBBLESTONE, 64);

            int[] counters = new int[3];
            runBatch(p, claims(p.id), counters, null);
            await(() -> counters[0] + counters[1] >= 15, 50, () -> {
                try {
                    checkEq(0,  counters[0], "S3 granted");
                    checkEq(15, counters[1], "S3 failed");
                    checkEq(0,  invCount(p.backing, Material.COBBLESTONE), "S3 delivered nothing");
                    checkEq(15 * 64, boxItems(p.id, Material.COBBLESTONE), "S3 CONSERVATION");
                } catch (Throwable t) { abort(t); return; }
                next();
            });
        } catch (Throwable t) { abort(t); }
    }

    /** S4: shift-click type claim: only the clicked material is claimed, across pages. */
    private void s4_typeClaim_basic() {
        try {
            getLogger().info("STRESS S4: claim-all-of-type / SAND only, COBBLE+MONEY untouched");
            FakePlayer p = new FakePlayer(this, "S4");
            for (int i = 0; i < 10; i++) seedItem(p.id, Material.SAND, 64);
            for (int i = 0; i < 8;  i++) seedItem(p.id, Material.COBBLESTONE, 64);
            mAddMoneyDirect.invoke(svc, p.id, 123.45);
            seedItem(p.id, Material.SAND, 16);

            // Click target: any SAND entry.
            int sandId = -1;
            for (Object c : claims(p.id)) {
                ItemStack it = entryItem(c);
                if (it != null && it.getType() == Material.SAND) { sandId = entryId(c); break; }
            }
            check(sandId != -1, "S4 found a SAND entry to click");

            mTypeClaim.invoke(listener, p.player, gui, sandId);
            await(() -> !claimingAll.contains(p.id), 50, () -> {
                try {
                    checkEq(656, invCount(p.backing, Material.SAND), "S4 all SAND delivered (10x64+16)");
                    checkEq(0,   invCount(p.backing, Material.COBBLESTONE), "S4 no COBBLE delivered");
                    checkEq(0,   boxItems(p.id, Material.SAND), "S4 no SAND left in box");
                    checkEq(8 * 64, boxItems(p.id, Material.COBBLESTONE), "S4 COBBLE untouched");
                    check(Math.abs(boxMoney(p.id) - 123.45) < 1e-9, "S4 money untouched");
                    check(p.lastMessage().contains("SAND"), "S4 summary names the material: " + p.lastMessage());
                } catch (Throwable t) { abort(t); return; }
                next();
            });
        } catch (Throwable t) { abort(t); }
    }

    /** S5: type claim with limited space — inventory-full stop + conservation. */
    private void s5_typeClaim_limitedSpace() {
        try {
            getLogger().info("STRESS S5: claim-all-of-type / 2 free slots for 5 stacks");
            FakePlayer p = new FakePlayer(this, "S5");
            fillSlots(p.backing, 0, 34, Material.DIRT, 64);
            for (int i = 0; i < 5; i++) seedItem(p.id, Material.SAND, 64);

            int sandId = entryId(claims(p.id).get(0));
            mTypeClaim.invoke(listener, p.player, gui, sandId);
            await(() -> !claimingAll.contains(p.id), 50, () -> {
                try {
                    long delivered = invCount(p.backing, Material.SAND);
                    long inBox     = boxItems(p.id, Material.SAND);
                    checkEq(128, delivered, "S5 delivered (2 slots)");
                    checkEq(192, inBox,     "S5 left in box");
                    checkEq(5 * 64, delivered + inBox, "S5 CONSERVATION");
                } catch (Throwable t) { abort(t); return; }
                next();
            });
        } catch (Throwable t) { abort(t); }
    }

    /** S6: shift-click on a MONEY row falls back to a single claim, no crash, no loss. */
    private void s6_typeClaim_moneyFallback() {
        try {
            getLogger().info("STRESS S6: shift-click MONEY entry fallback");
            FakePlayer p = new FakePlayer(this, "S6");
            mAddMoneyDirect.invoke(svc, p.id, 500.0);
            mAddRefundDirect.invoke(svc, p.id, 250.0);
            seedItem(p.id, Material.SAND, 64);

            int moneyId = -1;
            for (Object c : claims(p.id)) {
                if (entryType(c).equals("MONEY")) { moneyId = entryId(c); break; }
            }
            check(moneyId != -1, "S6 found MONEY entry");

            mTypeClaim.invoke(listener, p.player, gui, moneyId);
            await(() -> !claimingAll.contains(p.id), 50, () -> {
                try {
                    // Fake players have no Essentials account: the Vault deposit
                    // either succeeds (money row consumed) or fails (row
                    // requeued).  Either way NOTHING may be lost or duplicated,
                    // items must be untouched, and the lock must be released.
                    double money = boxMoney(p.id);
                    check(money == 750.0 || money == 250.0,
                            "S6 money conserved (750 requeued or 250 after deposit), got " + money);
                    checkEq(64, boxItems(p.id, Material.SAND), "S6 SAND untouched");
                    checkEq(0,  invCount(p.backing, Material.SAND), "S6 nothing delivered");
                } catch (Throwable t) { abort(t); return; }
                next();
            });
        } catch (Throwable t) { abort(t); }
    }

    /** S7: TYPE sort renders money, refund, then items A-Z. */
    private void s7_sort_type_render() {
        try {
            getLogger().info("STRESS S7: TYPE sort render order");
            FakePlayer p = new FakePlayer(this, "S7");
            seedItem(p.id, Material.SAND, 64);
            mAddMoneyDirect.invoke(svc, p.id, 10.0);
            seedItem(p.id, Material.COBBLESTONE, 3);
            mAddRefundDirect.invoke(svc, p.id, 20.0);
            seedItem(p.id, Material.ANDESITE, 10);
            seedItem(p.id, Material.COBBLESTONE, 64);   // newest COBBLE

            mGuiToggleSort.invoke(gui, p.player);        // NEWEST -> TYPE
            p.captured = null;
            mGuiOpen.invoke(gui, p.player, 0);
            await(() -> p.captured != null, 4, () -> {
                try {
                    Inventory rendered = p.captured;
                    Material[] want = { Material.GOLD_INGOT, Material.REDSTONE,
                            Material.ANDESITE, Material.COBBLESTONE,
                            Material.COBBLESTONE, Material.SAND };
                    boolean order = true;
                    for (int i = 0; i < want.length; i++) {
                        ItemStack it = rendered.getItem(i);
                        if (it == null || it.getType() != want[i]) { order = false; break; }
                    }
                    check(order, "S7 TYPE order: money, refund, andesite, cobble x2, sand");
                    ItemStack c1 = rendered.getItem(3);
                    check(c1 != null && c1.getAmount() == 64,
                            "S7 newest-first inside a material (64x before 3x)");
                    ItemStack sortBtn = rendered.getItem(47);
                    check(sortBtn != null && sortBtn.getItemMeta().getDisplayName().contains("Item Type"),
                            "S7 sort button shows Item Type");
                } catch (Throwable t) { abort(t); return; }
                next();
            });
        } catch (Throwable t) { abort(t); }
    }

    /** S8: toggling back to NEWEST renders newest-first (insert order reversed). */
    private void s8_sort_newest_render() {
        try {
            getLogger().info("STRESS S8: NEWEST sort render order");
            FakePlayer p = new FakePlayer(this, "S8");
            seedItem(p.id, Material.SAND, 1);
            seedItem(p.id, Material.COBBLESTONE, 2);
            seedItem(p.id, Material.ANDESITE, 3);       // newest

            p.captured = null;
            mGuiOpen.invoke(gui, p.player, 0);          // default NEWEST
            await(() -> p.captured != null, 4, () -> {
                try {
                    Inventory rendered = p.captured;
                    Material[] want = { Material.ANDESITE, Material.COBBLESTONE, Material.SAND };
                    boolean order = true;
                    for (int i = 0; i < want.length; i++) {
                        ItemStack it = rendered.getItem(i);
                        if (it == null || it.getType() != want[i]) { order = false; break; }
                    }
                    check(order, "S8 NEWEST order via id-DESC tiebreak (same-millis inserts)");
                    ItemStack sortBtn = rendered.getItem(47);
                    check(sortBtn != null && sortBtn.getItemMeta().getDisplayName().contains("Newest"),
                            "S8 sort button shows Newest");
                } catch (Throwable t) { abort(t); return; }
                next();
            });
        } catch (Throwable t) { abort(t); }
    }

    /** S9: page clamp on out-of-range open; page survives an individual claim; clamp on shrink. */
    private void s9_paging_clamp_and_stay() {
        try {
            getLogger().info("STRESS S9: paging (100 entries = 3 pages)");
            FakePlayer p = new FakePlayer(this, "S9");
            for (int i = 0; i < 100; i++) {
                seedItem(p.id, i % 2 == 0 ? Material.SAND : Material.COBBLESTONE, 1 + (i % 64));
            }

            p.captured = null;
            mGuiOpen.invoke(gui, p.player, 7);          // way past the end -> clamp to page 2
            await(() -> p.captured != null, 4, () -> {
                try {
                    checkEq(2, (int) mGuiGetPage.invoke(gui, p.player), "S9 page clamped to last (2)");
                    ItemStack refresh = p.captured.getItem(49);
                    String lore = String.join("|", refresh.getItemMeta().getLore());
                    check(lore.contains("Page 3 of 3"), "S9 page indicator shows 3 of 3: " + lore);
                    int icons = 0;
                    for (int i = 0; i < 45; i++) if (p.captured.getItem(i) != null) icons++;
                    checkEq(10, icons, "S9 last page shows the 10 overflow entries");

                    // Claim one entry "from page 3", then reopen at the remembered page.
                    List<?> all = claims(p.id);
                    String r = claimResult(p, all.get(97));
                    check(r.equals("GRANTED"), "S9 claim from page 3 granted, got " + r);
                    p.captured = null;
                    mGuiOpen.invoke(gui, p.player, (int) mGuiGetPage.invoke(gui, p.player));
                    await(() -> p.captured != null, 4, () -> {
                        try {
                            checkEq(2, (int) mGuiGetPage.invoke(gui, p.player), "S9 still on page 3 after claim");

                            // Claim the rest of page 3's entries -> 90 left -> 2 pages; reopen clamps.
                            List<?> rest = claims(p.id);
                            for (int i = 90; i < rest.size(); i++) claimResult(p, rest.get(i));
                            p.captured = null;
                            mGuiOpen.invoke(gui, p.player, 2);
                            await(() -> p.captured != null, 4, () -> {
                                try {
                                    checkEq(1, (int) mGuiGetPage.invoke(gui, p.player),
                                            "S9 clamped to page 2 after box shrank");
                                    String lore2 = String.join("|",
                                            p.captured.getItem(49).getItemMeta().getLore());
                                    check(lore2.contains("Page 2 of 2"), "S9 indicator now 2 of 2: " + lore2);
                                } catch (Throwable t) { abort(t); return; }
                                next();
                            });
                        } catch (Throwable t) { abort(t); }
                    });
                } catch (Throwable t) { abort(t); }
            });
        } catch (Throwable t) { abort(t); }
    }

    /** S10: race hammering — double claims, double type-claims, batch vs individual. */
    private void s10_races() {
        try {
            getLogger().info("STRESS S10: races (double-claim, concurrent type claim, batch vs single)");
            FakePlayer p = new FakePlayer(this, "S10");
            for (int i = 0; i < 50; i++) seedItem(p.id, Material.SAND, 64);
            List<?> entries = claims(p.id);

            // 1) same entry claimed twice back to back
            String r1 = claimResult(p, entries.get(0));
            String r2 = claimResult(p, entries.get(0));
            check(r1.equals("GRANTED") && r2.equals("ALREADY_CLAIMED"),
                    "S10 double-claim: " + r1 + " then " + r2);

            // 2) type claim while a type claim is running (lock must reject the 2nd)
            int id1 = entryId(entries.get(1));
            int id2 = entryId(entries.get(2));
            mTypeClaim.invoke(listener, p.player, gui, id1);
            mTypeClaim.invoke(listener, p.player, gui, id2); // silently rejected by CLAIMING_ALL
            // 3) individual claim racing the running batch on a later entry
            String r3 = claimResult(p, entries.get(40));

            await(() -> !claimingAll.contains(p.id), 50, () -> {
                try {
                    long delivered = invCount(p.backing, Material.SAND);
                    long inBox     = boxItems(p.id, Material.SAND);
                    checkEq(50 * 64, delivered + inBox,
                            "S10 CONSERVATION after races (single racer result was " + r3 + ")");
                    check(delivered <= 36 * 64, "S10 no over-delivery");
                } catch (Throwable t) { abort(t); return; }
                next();
            });
        } catch (Throwable t) { abort(t); }
    }

    /** S11: 1000-entry box — seed, TYPE-sort render timing, Claim All drain, conservation. */
    private void s11_volume_1000() {
        try {
            getLogger().info("STRESS S11: 1000-entry volume");
            FakePlayer p = new FakePlayer(this, "S11");
            Material[] mats = { Material.SAND, Material.COBBLESTONE, Material.ANDESITE,
                    Material.DIORITE, Material.GRANITE, Material.RAW_COPPER };
            long t0 = System.currentTimeMillis();
            long seededItems = 0;
            for (int i = 0; i < 1000; i++) {
                int amt = 1 + (i % 64);
                seedItem(p.id, mats[i % mats.length], amt);
                seededItems += amt;
            }
            getLogger().info("STRESS S11 timing: seed 1000 rows in "
                    + (System.currentTimeMillis() - t0) + " ms");

            mGuiToggleSort.invoke(gui, p.player); // TYPE
            p.captured = null;
            long t1 = System.currentTimeMillis();
            mGuiOpen.invoke(gui, p.player, 0);
            final long seeded = seededItems;
            await(() -> p.captured != null, 2, () -> {
                try {
                    getLogger().info("STRESS S11 timing: TYPE-sorted render of 1000 rows in "
                            + (System.currentTimeMillis() - t1) + " ms (async fetch + sort + render)");
                    String lore = String.join("|", p.captured.getItem(49).getItemMeta().getLore());
                    check(lore.contains("Page 1 of 23"), "S11 1000 rows = 23 pages: " + lore);

                    int[] counters = new int[3];
                    long t2 = System.currentTimeMillis();
                    runBatch(p, claims(p.id), counters, null);
                    await(() -> counters[0] + counters[1] >= 1000, 60, () -> {
                        try {
                            getLogger().info("STRESS S11 timing: 1000-entry Claim All drained in "
                                    + (System.currentTimeMillis() - t2) + " ms ("
                                    + counters[0] + " granted, " + counters[1] + " failed)");
                            long delivered = invCount(p.backing, null);
                            long inBox     = boxItems(p.id, null);
                            checkEq(seeded, delivered + inBox, "S11 CONSERVATION at 1000-entry volume");
                            check(counters[2] == 1, "S11 stopped on inventory-full");
                        } catch (Throwable t) { abort(t); return; }
                        next();
                    });
                } catch (Throwable t) { abort(t); }
            });
        } catch (Throwable t) { abort(t); }
    }

    /** S12: CLAIMING_ALL must be empty when everything is done (no lock leaks). */
    private void s12_lock_released() {
        getLogger().info("STRESS S12: lock hygiene");
        check(claimingAll.isEmpty(), "S12 CLAIMING_ALL empty after all scenarios ("
                + claimingAll.size() + " leaked)");
        next();
    }

    /**
     * S13 (V6.4.2): DB-contention torture.  200 claim transactions (mostly
     * partial/full-inventory requeues — the delete+reinsert path that used to
     * lose items) run on the main thread WHILE the DbExecutor floods the same
     * SQLite file with 600 async inserts for another player.  The old async
     * requeue lost rows in exactly this situation (~79k items on the live
     * server).  Invariants: claimer conservation EXACT, every flood insert
     * lands, no claim may vanish regardless of BUSY errors.
     */
    private void s13_db_contention() {
        try {
            getLogger().info("STRESS S13: 200 claim txs vs 600 concurrent async inserts");
            FakePlayer p = new FakePlayer(this, "S13");
            UUID flood  = UUID.nameUUIDFromBytes("stress-S13-flood".getBytes());

            // 2 free slots + 1 partial stack -> grants, one partial, then skips
            // would hide churn; drive claimDetailed DIRECTLY so all 200 entries
            // run a full delete+requeue transaction.
            fillSlots(p.backing, 0, 33, Material.DIRT, 64);
            p.backing.setItem(33, new ItemStack(Material.GRANITE, 32));
            for (int i = 0; i < 200; i++) seedItem(p.id, Material.GRANITE, 64);

            Method mAddItemAsync = svc.getClass().getMethod("addItem", UUID.class, ItemStack.class);
            List<?> entries = claims(p.id);
            final int[] idx = {0}, floods = {0};
            final long t0 = System.currentTimeMillis();

            Bukkit.getScheduler().runTaskTimer(this, task -> {
                try {
                    // flood the executor every tick while claims run
                    for (int i = 0; i < 60 && floods[0] < 600; i++, floods[0]++) {
                        mAddItemAsync.invoke(svc, flood, new ItemStack(Material.MUD, 16));
                    }
                    for (int i = 0; i < 40 && idx[0] < entries.size(); i++, idx[0]++) {
                        claimResult(p, entries.get(idx[0]));
                    }
                    if (idx[0] >= entries.size() && floods[0] >= 600) {
                        task.cancel();
                        // wait for the DbExecutor queue to drain
                        await(() -> {
                            try { return boxItems(flood, Material.MUD) >= 600 * 16; }
                            catch (Exception e) { return false; }
                        }, 40, () -> {
                            try {
                                getLogger().info("STRESS S13 timing: "
                                        + (System.currentTimeMillis() - t0) + " ms");
                                long delivered = invCount(p.backing, Material.GRANITE) - 32;
                                long inBox     = boxItems(p.id, Material.GRANITE);
                                checkEq(200 * 64, delivered + inBox,
                                        "S13 CONSERVATION under DB contention");
                                checkEq(600L * 16, boxItems(flood, Material.MUD),
                                        "S13 all concurrent async inserts landed");
                            } catch (Throwable t) { abort(t); return; }
                            next();
                        });
                    }
                } catch (Throwable t) { task.cancel(); abort(t); }
            }, 1L, 1L);
        } catch (Throwable t) { abort(t); }
    }

    // ── Fake player ──────────────────────────────────────────────────────────

    private static final class FakePlayer {
        final UUID id;
        final String name;
        final Inventory backing;
        final Player player;
        volatile Inventory captured;             // last GUI passed to openInventory
        private final List<String> messages = new ArrayList<>();

        FakePlayer(ClaimStressProbe probe, String label) {
            this.id      = UUID.nameUUIDFromBytes(("stress-" + label).getBytes());
            this.name    = "Stress" + label;
            this.backing = Bukkit.createInventory(null, 36);
            this.player  = build(probe);
        }

        synchronized String lastMessage() {
            return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        }

        private Player build(ClaimStressProbe probe) {
            PlayerInventory pinv = (PlayerInventory) Proxy.newProxyInstance(
                    PlayerInventory.class.getClassLoader(),
                    new Class<?>[]{PlayerInventory.class},
                    (proxy, method, args) -> {
                        try {
                            Method real = backing.getClass()
                                    .getMethod(method.getName(), method.getParameterTypes());
                            return real.invoke(backing, args);
                        } catch (NoSuchMethodException e) {
                            return defaultFor(method.getReturnType());
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });

            // Minimal InventoryView so WtbGuiHolder.mayOpenFor() sees a player
            // with "nothing open" (CRAFTING = the vanilla no-GUI state).
            InventoryView view = (InventoryView) Proxy.newProxyInstance(
                    InventoryView.class.getClassLoader(),
                    new Class<?>[]{InventoryView.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getType"         -> InventoryType.CRAFTING;
                        case "getTopInventory" -> backing;
                        default                -> defaultFor(method.getReturnType());
                    });

            InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId"      -> id;
                case "getName"          -> name;
                case "isOnline"         -> true;
                case "getInventory"     -> pinv;
                case "getOpenInventory" -> view;
                case "openInventory"    -> {
                    if (args != null && args.length == 1 && args[0] instanceof Inventory inv) {
                        captured = inv;
                    }
                    yield null;
                }
                case "sendMessage"      -> {
                    if (args != null && args.length > 0) {
                        String msg = String.valueOf(args[0]);
                        synchronized (this) { messages.add(msg); }
                        probe.getLogger().info("   [" + name + " msg] " + msg);
                    }
                    yield null;
                }
                case "hasPermission"    -> false;
                case "equals"           -> proxy == args[0];
                case "hashCode"         -> id.hashCode();
                case "toString"         -> name;
                default                 -> defaultFor(method.getReturnType());
            };
            return (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(), new Class<?>[]{Player.class}, h);
        }

        private static Object defaultFor(Class<?> rt) {
            if (rt == boolean.class) return false;
            if (rt == int.class)     return 0;
            if (rt == long.class)    return 0L;
            if (rt == double.class)  return 0.0d;
            if (rt == float.class)   return 0.0f;
            if (rt == short.class)   return (short) 0;
            if (rt == byte.class)    return (byte) 0;
            if (rt == char.class)    return (char) 0;
            return null;
        }
    }
}
