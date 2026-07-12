# WTB Changelog

## v6.2.1 — 2026-07-12

Bug-fix release for two player-reported issues.

### 🐛 Fixes

- **Claim All chat flood** (`ClaimBoxService`, `MarketplaceClickListener`) — with a large
  claim box (e.g. a 10,000-item order ≈ 157 stacks) and a full inventory, Claim All sent
  one *"Your inventory is full"* line **per stack**, flooding the chat. Claim All now runs
  in quiet mode: per-entry messages are suppressed and the existing single summary
  (*"Claimed X item(s). Y could not be claimed (inventory full?)"*) reports the whole run.
  Individual (single-click) claims keep their per-claim feedback; unclaimed stacks stay
  safely in the Claim Box exactly as before.
- **WTB screen hijacking chests** (`WtbGuiHolder`, all five GUIs) — many WTB screens open
  a tick or more after the click that requested them (async database fetches, the Claim-All
  batch chain). A player who closed WTB and immediately opened a chest — or another
  plugin's GUI — inside that window had their view replaced by the late-firing WTB screen.
  Every GUI open is now guarded: it only proceeds if a WTB GUI is still open (navigation /
  refresh) or the player has no container open. No money/item paths are affected — the
  claims and trades themselves always completed correctly; only the wrong screen was shown.

No database changes, no config changes — drop-in replacement for v6.2.0.

---

## v6.2.0 — 2026-07-12

### ✨ New

- **Per-player popup muting — `/wtb settings mute`** (`PlayerSettingsService`,
  `PlayerSettingsDAO`, `WTBCommand`) — every player can now mute the full-screen
  "Buy Order Filled!" popup for themselves, separately for partial and full fills:

  - `/wtb settings mute full` — toggle the popup for **fully completed** orders
  - `/wtb settings mute partial` — toggle the popup for **partial** fills
  - `/wtb settings mute all` / `off` — mute / unmute both at once
  - `/wtb settings mute` — show your current preference

  Preferences persist in a new `player_settings` database table (created
  automatically on first boot, SQLite and MySQL) and are cached in memory on join,
  so the fulfil hot path never touches the database. Muting hides only the title
  popup — chat message, sound, offline notifications, and Claim Box delivery are
  unchanged. A popup shows only when the server-wide v6.1.1 setting AND the
  player's own preference both allow it.

- **`/wtb settings` namespace** (`WTBCommand`, `WTBTabCompleter`) — a discoverable
  home for player commands and preferences. `/wtb settings <anything>` behaves
  exactly like `/wtb <anything>` (`/wtb settings claim` opens the Claim Box, etc.),
  and `/wtb settings` alone shows your current preferences. Existing commands are
  untouched, so the transition is seamless. Admin commands are never advertised in
  the settings tab completion for non-admins (and stay permission-gated regardless).

- `mute` and `settings` are now reserved words for catalog keys (like the other
  sub-commands), so no registered item can ever shadow them.

Drop-in upgrade: the new table is created automatically; no config changes required.

---

## v6.1.1 — 2026-07-11

### ✨ New

- **Mutable order-filled popup** (`ListingService`, `settings.yml`) — the full-screen
  "Buy Order Filled!" title shown to buyers can now be muted, separately for partial and
  full fills, via two new settings (both default `true`, reloadable with `/wtbreload`):

  ```yaml
  settings:
    notifications:
      popup-on-full-fill: true
      popup-on-partial-fill: true
  ```

  Muting hides only the title popup — the chat message, pickup sound, offline
  notifications, and Claim Box delivery are unchanged. Servers upgrading keep the old
  behaviour automatically (missing keys resolve to `true` from the jar's bundled defaults).

No database changes — drop-in replacement for v6.1.0.

---

## v6.1.0 — 2026-07-11

Hardening release: the v6 economy engine was put through a 20-player concurrent stress campaign on a live Paper 26.2 server — **~192,000 successful operations across three 45-second runs, with zero violations**: exact-cent money conservation (deposits == payouts + refunds + escrow, over ~$3M escrow per run), no oversell, no double-claims, no stuck money.

### 🛡️ Hardening

- **DAO-level fulfil guard** (`ListingDAO.fulfillIfActive`) — the raw DAO method now rejects zero and negative amounts outright. A negative amount passed at this level would have *increased* `remaining_quantity` (minting items). This was unreachable in production — the service layer already validates amounts — but the raw method is now safe on its own (defense in depth, found by stress probe P2).

### 🧪 Stress-test harness (dev tool, not shipped in the plugin jar)

- New `stress/` module: **WTBStress**, a companion plugin that drives WTB's *real* production code (the actual fulfil transaction, real create/cancel service methods) with 20 concurrent simulated players — weighted create/fulfil/cancel/expire/claim operations, dust-prone prices, deliberate race windows — then audits ten money invariants (exact-cents conservation, no oversell, dust settlement, exactly-once claims/refunds) plus six deterministic loophole probes.
- ⚠️ **Never install `WTBStress` on a real server** — it wipes WTB's `listings` and `claim_box` tables each run. Test servers only.

No database changes, no config changes — drop-in replacement for v6.0.0.

---

## v6.0.0 — 2026-07-03

Major feature release: tools & enchanted books are now tradeable via **pristine-item matching**, every sale goes through an **Approve/Deny confirmation screen**, buyers get **offline notifications**, and two newly discovered v5 economy exploits are eliminated. Upgrading from v5 is fully automatic — drop the jar in; the database migrates itself and no settings changes are required. An **item catalog** makes potions, tipped arrows, goat horns, fireworks, player heads, banners, and custom server items (god gear) tradeable by key with exact-meta matching.

---

### 🔴 Critical Fixes

- **Oversell exploit — stale cache could resurrect already-sold quantity** (`ListingDAO`, `ListingService`) — v5's `updateIfActive` wrote an *absolute* `remaining_quantity` computed from the caller's cached `Listing` object. If another seller partially filled the order inside the 1-second GUI cache window, the stale write restored sold quantity: the buyer received **more items than ordered** and more money left escrow than was ever deposited. Replaced with an atomic **relative** claim — `SET remaining = remaining - ?, paid_cents = paid_cents + ? WHERE state IN ('OPEN','PARTIAL') AND remaining >= ?` — so exactly one concurrent writer can win any unit of quantity, even across multiple servers sharing one MySQL database. Verified with a 12-thread stress test (fills + racing cancels): zero oversell, exact escrow conservation.
- **Payout floor on raw doubles underpaid sellers and stranded cents forever** (`ListingService`, `Payout`) — `Math.floor(pricePerItem × amount × 100) / 100.0` truncated one whole cent on common two-decimal prices (IEEE 754 stores 19.99 as 19.989 999…), and the lost cent stayed locked in escrow with no code path ever paying it out or refunding it. All escrow math now runs in **integer cents** (`paid_cents` ledger column): partial fills use exact floor division, the **final fill is paid `priceCents − paidCents`** so floor dust goes to the last seller, and every refund is `priceCents − paidCents` read fresh from the DB. Conservation (`deposit == payouts + refund`, exactly) verified across 500 000+ randomized scenarios.
- **Items destroyed by dragging into WTB GUIs** (`MarketplaceClickListener`) — v5 cancelled `InventoryClickEvent` but not `InventoryDragEvent`; a click-hold drag deposited stacks into the ephemeral GUI inventory, where they were silently deleted on close. All drag events across every WTB GUI are now cancelled.

### 🟠 Major Fixes

- **Equipped armor / off-hand could be sold off the player's body** (`ItemMatcher`, `ListingService`) — inventory counting/removal used `getContents()` (41 slots incl. armor + off-hand). With tools/armor now tradeable this would have let Fill All strip a worn pristine chestplate. All matching uses `getStorageContents()` — the 36 main slots only.
- **Buyer notified before the trade was committed** (`ListingService`) — the "Order Filled!" title/message fired before the DB claim; an aborted trade still celebrated. Notifications (and the transaction log) now fire only **after** the atomic claim succeeds.
- **Silent revert on fulfilment collision** (`ListingService`) — when a fill lost the race (order cancelled/expired/filled first), v5 quietly dumped the seller's items back into the Claim Box with no explanation. Sellers now receive a `sale_reverted` message and the event is logged under `listing-collision`.
- **Refunds computed from stale in-memory quantity** (`ListingService`, `ExpiryService`) — cancel/expiry refunds recalculated per-item × remaining on doubles from a possibly-outdated object. All refund paths now read `paid_cents` fresh from the DB **after** the state transition and refund the exact unpaid remainder.

### ✨ New Features

- **Tools, weapons, armor & books-and-quills are now tradeable** (`ItemMatcher`, `Main`) — every fulfilment builds a **pristine template** and accepts only `ItemStack.isSimilar` matches. One check rejects damage, enchantments, anvil renames, lore, repair cost, armor trims, custom model data, and plugin PDC tags — so only brand-new, unmodified items can fill an order, in both directions. The v5 durability auto-block is gone. Meta-bearing items (potions, heads, fireworks, goat horns, banners, written books) are blocked from the *plain* syntax but tradeable through the item catalog below; only storage containers, suspicious stew, ominous bottles, and filled maps stay fully untradeable.
- **Enchanted book buy orders** (`EnchantSpec`, `WTBCommand`, `WTBTabCompleter`) — `/wtb enchanted_book <qty> <price> <enchant> [level]` (level defaults to 1; tab-complete offers every registry enchant and its valid levels). Matching is done on the `stored_enchantments` component — never tooltip text — so **villager, enchanting-table, loot, and fishing books are interchangeable**. Exactly one enchant per order; a multi-enchant book will not fill a single-enchant order.
- **Item catalog - exact-item orders by key** (`CatalogService`, `CatalogEntry`, `CatalogDAO`) - `/wtb <key> <qty> <price>` orders an exact item template instead of a bare material. **Built-in keys are generated at boot from the server's own registries** (zero admin work, version-proof): every potion / splash / lingering / tipped-arrow variant (`potion_strong_swiftness`, `tipped_arrow_poison`, ...), every goat horn (`goat_horn_ponder`, ...), and flight 1-3 rockets (`firework_rocket_3`). **Admins register custom items once** with the item in hand - `/wtb admin register god_sword` / `unregister` - covering god tools/books (PDC tags included), specific player heads, patterned banners, and firework stars. Matching stays full-meta `isSimilar`, so an **anvil-renamed fake can never fill a real order** (and vice versa). Every listing stores its own template copy, so unregistering a key never breaks open orders. Keys tab-complete alongside materials; key rules (`[a-z0-9_]{2,40}`, no material/command collisions) make shadowing impossible. Catalog trades are excluded from price history; the GUI renders the real item with an "Exact-item order" notice.
- **Sale confirmation screen** (`ConfirmSaleGUI`) — nothing leaves a seller's inventory from a bare click any more. Single-listing clicks, the Fill All button, and `/wtb fill` all open a review screen showing per order: item, amount, exact payout, **buyer name**, and remaining-after — plus a total summary — with explicit **APPROVE / DENY** buttons. Pending confirmations expire after 60 s and are voided on close/quit. Approve re-validates everything through the atomic DB claim, so a stale preview can only ever sell *less* than shown, never more. Toggleable via `settings.listing.confirm-enabled` (legacy direct path retained when `false`).
- **`/wtb fill`** (`WTBCommand`) — command equivalent of Fill All, via the confirmation screen.
- **`/wtb cancel`** (`WTBCommand`, `ListingService`) — cancels **all** of your open buy orders in one command with a single summary refund message. `cancle` works as a silent typo alias (`Material.matchMaterial("cancle")` is null, so it can never shadow a real material). Races with in-flight fills resolve cleanly: whichever conditional UPDATE commits first wins.
- **Offline notifications** (`NotificationService`, `NotificationDAO`) — if your order is filled, expires, or is admin-cancelled while you're offline, the alert (including the **filler's name**) is stored and delivered ~2 s after your next join, with a sound. Delivery uses a delete-first per-row claim so a message can never double-send.
- **Per-enchant price history** (`PriceHistoryService`, `PriceHistoryDAO`) — book trades aggregate under `ENCHANTED_BOOK;minecraft:<enchant>;<level>`, so Mending prices never average into Bane of Arthropods prices.
- **Order quantity cap** (`settings.listing.max-quantity`, default 10 000; hard ceiling 100 000) and a total-price cap guarding the integer-cents math against overflow.
- **Zero-touch upgrades** (`Main`, `SQLiteDatabase`, `MySQLDatabase`) — the bundled `settings.yml` is layered as *defaults* under your existing file, so new v6 keys resolve without editing anything; the database self-migrates on first boot (see below).
- **Richer GUI lore** — listings render the real template item (books show their stored enchant + glint), display *"Unpaid escrow"*, and flag *"Only NEW, unmodified items are accepted"* on tool/book orders. `/wtb admin info` now shows paid-out cents.

### 🗄️ Database Migration (automatic, v5 → v6)

Runs once on first boot; every step is logged as `[WTB] Migration: …` and is a no-op afterwards.

| Change | Details |
|---|---|
| `listings.enchant` *(new column)* | Enchanted-book order spec (`minecraft:sharpness;5`), NULL for plain orders |
| `listings.paid_cents` *(new column)* | Integer-cents payout ledger; **backfilled** for existing rows from `price × filled/total` so pre-v6 partial listings cancel/expire with correct refunds |
| `transactions.enchant` *(new column)* | Audit detail for book trades |
| `listings.item` *(new column)* | Serialized exact-item template (BLOB) for catalog orders, NULL otherwise |
| `listings.custom_name` *(new column)* | Display label for catalog orders |
| `transactions.custom_name` *(new column)* | Audit label for catalog trades |
| `catalog` *(new table)* | Admin-registered items (`key` PK, `item` BLOB, `label`, `created_by`, `created_at`) |
| `notifications` *(new table)* | Pending offline alerts (`id`, `player`, `message`, `created_at`, indexed by player) |
| `price_history.material` | Widened to VARCHAR(128) on MySQL for enchant aggregation keys |

### ⚙️ New Settings & Messages

- Settings: `listing.confirm-enabled`, `listing.max-quantity`
- Logging categories: `listing-collision`, `bulk-cancel`, `confirm-approved`, `confirm-denied`, `notifications`
- Messages: `enchant_required`, `enchant_not_allowed`, `invalid_enchant`, `invalid_enchant_level`, `max_quantity_exceeded`, `listing_unfulfillable`, `sale_reverted`, `sale_denied`, `confirm_approved`, `confirm_expired`, `cancel_all_success`, `cancel_all_none`, `notify_header`, `order_expired`, `order_admin_cancelled`, `fill_use_button`, and catalog: `catalog_hand_empty`, `catalog_key_invalid`, `catalog_key_taken`, `catalog_key_unknown`, `catalog_key_builtin`, `catalog_item_blocked`, `catalog_registered`, `catalog_unregistered` (plus updated `usage_buy`, `usage_admin`, `not_enough_items`, `help_message`)

### 🔧 API Changes

| Change | Type | Notes |
|---|---|---|
| `ListingDAO.fulfillIfActive(id, amount, payoutCents)` | Added | Atomic relative fulfilment claim |
| `ListingDAO.settleDust(id, fullPriceCents)` | Added | Final-fill dust settlement |
| `ListingDAO.getActiveByBuyer(uuid)` | Added | Powers `/wtb cancel` |
| `ListingDAO.update / updateState / updateIfActive` | **Removed** | Replaced by conditional relative updates |
| `ListingService.createListing(player, material, enchant, template, customName, qty, price)` | Changed | `EnchantSpec` + exact-item `ItemStack` template + label |
| `ListingService.cancelAllListings(player)` | Added | Async bulk cancel |
| `Listing` constructor | Changed | `+ EnchantSpec enchant`, `+ byte[] itemBytes`, `+ String customName`, `+ long paidCents`; new `getPriceCents/getPaidCents/displayName()/historyKey()/templateKey()/isCustom()` |
| `Transaction` constructor | Changed | `+ EnchantSpec enchant`, `+ String customName`; new `displayName()` |
| `Main.isTemplateTradeable(Material)` | Added | Catalog-order material rule (blocklist + containers only) |
| `Main.getCatalogService()` | Added | New singleton |
| `WTBCommand` admin subs `register` / `unregister` | Added | Catalog administration |
| `TransactionService.logTransaction(…)` | Changed | `+ EnchantSpec enchant`, `+ String customName` parameters |
| `PriceHistoryService.record(String key, …)` / `PriceHistoryDAO` | Changed | Keyed by `String` history key instead of `Material` |
| `Main.isTradeable(Material)` | Changed | Durability auto-block removed; `ENCHANTED_BOOK` / `WRITABLE_BOOK` no longer blocked |
| `Main.requiresEnchantSpec(Material)` | Added | True for `ENCHANTED_BOOK` |
| `Main.getNotificationService()` / `Main.getConfirmSaleGUI()` | Added | New singletons |
| `EnchantSpec`, `Payout`, `ItemMatcher`, `NotificationDAO`, `NotificationService`, `ConfirmSaleGUI`, `CatalogEntry`, `CatalogDAO`, `CatalogService` | Added | New classes |

---

## v5.1.0 / v5.0.0

See `RELEASE_DESCRIPTION_V5.md` — 15 bugs resolved including two economy exploits, a double-claim race, and a one-cent payout formatting bug.
