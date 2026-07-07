# WTB v6.0.0 — Operations Manual

**For server administrators and owners.**

---

## Table of Contents

1. [Requirements & Installation](#1-requirements--installation)
2. [Upgrading from v5](#2-upgrading-from-v5)
3. [File Structure](#3-file-structure)
4. [Configuration Reference](#4-configuration-reference)
5. [Commands & Permissions](#5-commands--permissions)
6. [Pristine-Item Matching](#6-pristine-item-matching)
7. [GUI Reference](#7-gui-reference)
8. [Economy & Trade Flow](#8-economy--trade-flow)
9. [Logging](#9-logging)
10. [Database](#10-database)
11. [Reloading & Maintenance](#11-reloading--maintenance)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Requirements & Installation

### Requirements

| Requirement | Version |
|---|---|
| Paper | 1.21 or newer |
| Java | 21 or newer |
| Vault | Any current release |
| Economy plugin | EssentialsX, CMI, or any Vault-compatible provider |

> ⚠️ **Spigot is not supported.** WTB uses Paper-specific APIs.

### Installation Steps

1. Drop `WTB-6.0.0.jar` into your server's `plugins/` folder.
2. Confirm **Vault** and an economy plugin are already installed.
3. Start (or restart) the server.
4. WTB creates `plugins/WTB/settings.yml` and `plugins/WTB/config.yml` automatically.
5. Edit `settings.yml` to match your server's economy and rules.
6. Run `/wtbreload` in-game or from console to apply changes without a restart.

---

## 2. Upgrading from v5

The v5 → v6 upgrade is fully automatic:

1. Stop the server (recommended) and back up `plugins/WTB/wtb.db` (or your MySQL database).
2. Replace the old jar with `WTB-6.0.0.jar`.
3. Start the server.

On first boot the console logs each migration step:

```
[WTB] Migration: added listings.enchant (V6).
[WTB] Migration: added listings.paid_cents and backfilled 8 row(s) (V6).
[WTB] Migration: added listings.item (V6).
[WTB] Migration: added listings.custom_name (V6).
[WTB] Migration: added transactions.enchant (V6).
[WTB] Migration: added transactions.custom_name (V6).
[WTB] Item catalog loaded: 200 built-in + 0 registered.
```

What happens:

- **`listings.enchant`** and **`transactions.enchant`** columns are added (NULL for all existing rows).
- **`listings.paid_cents`** (the exact-cents escrow ledger) is added and **backfilled** for every existing listing from `price × filled/total`, so pre-v6 partial listings cancel and expire with correct refunds.
- A new **`notifications`** table is created for offline alerts.
- **`listings.item`**, **`listings.custom_name`**, and **`transactions.custom_name`** columns are added (NULL for all existing rows) for exact-item catalog orders.
- A new **`catalog`** table is created for admin-registered items.
- On MySQL, `price_history.material` is widened to VARCHAR(128) for enchant aggregation keys.

**Settings:** your existing `settings.yml` is left untouched. Every key introduced in v6 is resolved from the jar's bundled defaults, so nothing breaks and no messages show as "missing". Copy new keys into your file only when you want to customise them (the full v6 reference is in section 4).

The migration is idempotent — it runs as a no-op on every subsequent boot. Downgrading back to v5 is **not** supported once real v6 (enchanted-book) listings exist, since v5 ignores the `enchant` column and would treat a book order as a plain-book order.

---

## 3. File Structure

```
plugins/WTB/
├── config.yml        ← Database connection settings (SQLite or MySQL)
├── settings.yml      ← All gameplay settings and messages
├── logs.txt          ← Append-only event log
└── wtb.db            ← SQLite database file (only if using SQLite)
```

---

## 4. Configuration Reference

### `config.yml` — Database

```yaml
mysql:
  enabled: false          # Set true to use MySQL instead of SQLite
  host: localhost
  port: 3306
  database: wtb
  username: root
  password: ""
```

When `enabled: false`, the plugin uses SQLite (`plugins/WTB/wtb.db`). SQLite is fine for most servers. Use MySQL for large networks or if you share economy data across multiple servers — v6's atomic relative fulfilment claims are safe even with several servers writing to one database.

---

### `settings.yml` — Full Reference

#### Listing Settings

```yaml
settings:
  listing:
    max-listings: 5            # Maximum active listings per player (OPEN + PARTIAL combined)
    max-quantity: 10000        # v6: maximum quantity per single order (hard ceiling 100000)
    min-price-per-item: 1.0    # Minimum price per individual item (prevents spam/griefing)
    expiry-days: 7             # Days before an unfulfilled listing expires and is auto-refunded
    fill-all-enabled: true     # Show the "Fill All Open" button in the marketplace GUI
    confirm-enabled: true      # v6: route every sale through the Approve/Deny confirmation
                               # screen. Set false to restore v5's instant-sale behaviour.
```

#### Expiry Settings

```yaml
  expiry:
    check-interval-minutes: 10  # How often the server scans for expired listings
```

The expiry task runs asynchronously and only queries listings that have already passed their expiry timestamp — it does not scan the full table.

#### Transaction History

```yaml
  transactions:
    history-limit: 200  # Maximum number of transactions shown in the /wtb tx GUI
```

#### Blocked Materials

```yaml
  blocked-materials:
    - AIR
    - BARRIER
    - COMMAND_BLOCK
    # Add any Minecraft material name (uppercase)
    - BEDROCK
    - DEBUG_STICK
```

Material names must match Minecraft's internal names exactly (e.g. `OAK_LOG`, `NETHERITE_SWORD`). Use `/wtb` tab-complete to check what names are valid — blocked materials are excluded from suggestions.

In addition to this admin list, WTB blocks meta-bearing items from the *plain* material syntax: written books, player heads, potions (all types), tipped arrows, suspicious stew, ominous bottles, filled maps, fireworks, firework stars, goat horns, and banners. Almost all of these ARE tradeable through the **item catalog** (section 6) as exact-item orders. Only shulker boxes and bundles are fully untradeable (their hidden contents make orders ungriefable to verify), plus suspicious stew, ominous bottles, and filled maps, which have no sensible catalog form. Materials in your `blocked-materials` list are excluded everywhere, including the catalog.

> **v6 change:** tools, weapons, armor, enchanted books, and books-and-quills are **no longer auto-blocked** — see section 6. If you want any of them blocked on your server, add them to `blocked-materials` explicitly.

#### Admin Groups

```yaml
  admin-groups: []
  # Example:
  # admin-groups:
  #   - group.staff
  #   - permission.market_admin
```

Extra permission nodes that grant WTB admin access in addition to the `wtb.admin` node.

#### Logging

```yaml
logging:
  enabled: true             # Master switch — disabling this silences all WTB logging
  listing-created: true     # Log when a player creates a buy order
  listing-filled: true      # Log when a listing is fully filled
  listing-partial: true     # Log when a listing is partially filled
  listing-cancelled: true   # Log when a player cancels a listing (incl. /wtb cancel rows)
  listing-expired: true     # Log when a listing expires automatically
  listing-collision: true   # v6: a sale lost a race and was safely reverted
  bulk-cancel: true         # v6: /wtb cancel summary lines
  confirm-approved: true    # v6: player approved a sale on the confirmation screen
  confirm-denied: true      # v6: player denied a sale on the confirmation screen
  notifications: true       # v6: offline notifications queued for later delivery
  admin-actions: true       # Log all /wtb admin commands
  claim-money: true         # Log when a player claims a money reward
  claim-refund: true        # Log when a player claims a refund
  claim-items: true         # Log when a player claims an item reward
```

All logs are written to `plugins/WTB/logs.txt` in append mode.

#### Messages

All player-facing messages live under `messages:` in `settings.yml`. They support `&` colour codes and the following placeholders:

| Message key | Placeholders |
|---|---|
| `buy_created` | `{quantity}`, `{material}`, `{price}` |
| `price_per_item_low` | `{min_price}` |
| `max_listings_reached` | `{max}` |
| `max_quantity_exceeded` *(v6)* | `{max}` |
| `invalid_enchant_level` *(v6)* | `{max}` |
| `sold_items` | `{amount}`, `{material}`, `{payout}` |
| `order_filled_subtitle` | `{seller}` |
| `order_fully_filled` | `{material}`, `{seller}` |
| `order_partially_filled` | `{material}`, `{seller}`, `{remaining}` |
| `order_expired` *(v6)* | `{material}`, `{refund}` |
| `order_admin_cancelled` *(v6)* | `{material}`, `{refund}` |
| `confirm_approved` *(v6)* | `{count}` |
| `cancel_all_success` *(v6)* | `{count}`, `{refund}` |
| `claim_money` | `{amount}` |
| `claim_item` | `{amount}`, `{material}` |
| `claim_all_success` | `{count}` |
| `claim_all_partial` | `{count}`, `{failed}` |
| `admin_cancel_success` | `{id}`, `{buyer}` |
| `fill_all_success` | `{count}` |
| `catalog_registered` *(v6)* | `{key}` |
| `catalog_unregistered` *(v6)* | `{key}` |

New v6 keys without placeholders: `enchant_required`, `enchant_not_allowed`, `invalid_enchant`, `listing_unfulfillable`, `sale_reverted`, `sale_denied`, `confirm_expired`, `cancel_all_none`, `notify_header`, `fill_use_button`, `catalog_hand_empty`, `catalog_key_invalid`, `catalog_key_taken`, `catalog_key_unknown`, `catalog_key_builtin`, `catalog_item_blocked`.

For book orders, `{material}` renders as e.g. `ENCHANTED_BOOK (Mending 1)`.

---

## 5. Commands & Permissions

### Player Commands

| Command | Description |
|---|---|
| `/wtb` | Open the marketplace GUI |
| `/wtb <material> <quantity> <total_price>` | Create a new buy order |
| `/wtb enchanted_book <qty> <price> <enchant> [level]` | Create an enchanted-book order (level defaults to 1) |
| `/wtb <catalog-key> <quantity> <total_price>` | Order an exact special item by key: potions, tipped arrows, goat horns, rockets, and admin-registered server items |
| `/wtb fill` | Sell into every open order you can — via the confirmation screen |
| `/wtb cancel` | Cancel **all** of your open buy orders (one summary refund) |
| `/wtb my` | View and manage your own buy orders |
| `/wtb claim` | Open your Claim Box |
| `/wtb tx` | View recent transaction history |
| `/wtb help` | Show in-game help |

`/wtb buy <material> …` still works as a legacy alias, and `cancle` is a silent typo alias for `cancel`.

**Examples:**
```
/wtb IRON_INGOT 64 32.00
/wtb enchanted_book 3 4500 mending
/wtb enchanted_book 1 2000 sharpness 5
/wtb diamond_pickaxe 2 800
/wtb potion_strong_swiftness 8 400
/wtb tipped_arrow_poison 64 320
/wtb goat_horn_ponder 1 250
/wtb god_sword 1 50000
```

The `<total_price>` is the **total** you will pay for all items, not the per-item price. Tab-complete suggests valid materials, then enchant names, then valid levels for the chosen enchant.

---

### Admin Commands

| Command | Permission | Description |
|---|---|---|
| `/wtb admin cancel <id>` | `wtb.admin` | Force-cancel any listing by ID, with automatic refund and buyer notification |
| `/wtb admin info <id>` | `wtb.admin` | Inspect a listing's full details, including the exact-cents escrow ledger |
| `/wtb admin register <key>` | `wtb.admin` | Register the item **in your main hand** as an orderable catalog key (full meta captured) |
| `/wtb admin unregister <key>` | `wtb.admin` | Remove a registered key. Existing orders keep working (each stores its own template copy) |
| `/wtbreload` | `wtb.reload` | Reload `settings.yml` without a server restart |

**Finding listing IDs:** Hover over any listing in the marketplace as an admin — the listing ID is shown at the bottom of the item's lore.

---

### Permission Nodes

| Node | Default | Description |
|---|---|---|
| `wtb.admin` | OP | Access to `/wtb admin cancel` and `/wtb admin info`; shows listing IDs on hover |
| `wtb.reload` | OP | Access to `/wtbreload` |

To grant non-OP staff admin access without the `wtb.admin` node directly, add their permission group to `settings.admin-groups` in `settings.yml`.

---

## 6. Pristine-Item Matching

**The v6 rule: only brand-new, unmodified items can fill an order.**

Every fulfilment builds a *pristine template* for the ordered item and accepts only inventory stacks that are exact component-level matches (`ItemStack.isSimilar`). One check rejects, for any material:

- Damage / lost durability
- Enchantments (or, for books, the wrong stored enchantment)
- Custom display names and lore (anvil renames)
- Anvil repair-cost ("prior work") from combined items
- Armor trims, custom model data, attribute modifiers
- Hidden plugin data (PersistentDataContainer tags — soulbound items, quest items, crate keys disguised as vanilla items, …)

This is why tools, weapons, and armor are now safely tradeable: a Sharpness V axe at 1 durability simply doesn't match a `DIAMOND_AXE` order, in either direction. Sellers see *"Only NEW, unmodified items are accepted"* in the listing lore for durability/book orders, and `not_enough_items` explains the rule when a modified item is all they have.

**Enchanted books:** an order specifies exactly **one** enchantment and level (`/wtb enchanted_book 1 2000 sharpness 5`). A book fills the order only if it contains exactly that enchantment at exactly that level and nothing else. Matching is done on the book's `stored_enchantments` component — never on tooltip text — so books from **villagers, enchanting tables, loot chests, and fishing are fully interchangeable**. Multi-enchant books never match single-enchant orders, in either direction, so value is unambiguous.

**Inventory scope:** counting and removal only touch the 36 main storage slots. Equipped armor and the off-hand are never taken, so Fill All cannot sell a pristine chestplate off a player's body.

**Unfulfillable orders:** if a book order's enchantment no longer exists on the server (a removed datapack enchant), the listing renders as a barrier with an explanation. It can't be filled, but the owner can still cancel it for a full refund of the unpaid balance.

### The Item Catalog (exact-item orders)

The plain syntax can't express meta (which potion? whose head?), so v6 adds a **catalog**: stable text keys mapped to exact item templates, ordered with the normal syntax and tab-completed alongside materials.

**Built-in keys - zero setup.** Generated at boot from the server's own registries, so they're automatically complete and correct for your Minecraft version (the boot log prints the count):

| Key pattern | Covers |
|---|---|
| `potion_<type>` | Every drinkable potion variant, incl. `strong_`/`long_` (e.g. `potion_strong_swiftness`) |
| `splash_potion_<type>` / `lingering_potion_<type>` | Every splash / lingering variant |
| `tipped_arrow_<type>` | Every tipped-arrow variant |
| `goat_horn_<instrument>` | All goat horns (`goat_horn_ponder`, `goat_horn_seek`, ...) |
| `firework_rocket_1/2/3` | Plain crafted rockets by flight duration |

**Admin-registered keys - one command per item.** Hold the item and run `/wtb admin register <key>`. The FULL item is captured - name, lore, enchantments, durability, and hidden plugin data (PDC tags) - so god tools, god books, specific player heads, patterned banners, and custom firework stars all work. Key rules: 2-40 chars of `a-z 0-9 _`; names that match a material or a /wtb sub-command are rejected, so a key can never shadow anything.

**Matching and anvil renames.** Catalog fulfilment uses the same exact `isSimilar` check as everything else. A cheap item renamed to look like a god item does NOT match the real template (its hidden tags and components differ), and the real item does not match an order captured from a fake - renaming can never be used to cheat an order in either direction. If your god item genuinely has no plugin tags and a player crafts a byte-identical copy, that copy IS the item and fills legitimately.

**Lifecycle.** Every listing stores its own copy of the template, so `/wtb admin unregister` (or changing a key) never breaks open orders - they keep matching the item as it was when ordered. Catalog trades are excluded from price history. If a stored template ever fails to deserialize (e.g. after a huge version jump), the listing renders as a barrier and can still be cancelled for a full refund.

---

## 7. GUI Reference

### Marketplace GUI (`/wtb`)

The main 54-slot inventory showing all open and partial buy orders. Book orders render as real enchanted books with their stored enchant and glint.

| Slot | Button | Function |
|---|---|---|
| 45 | 📖 My Listings | Open your personal buy orders |
| 46 | 🎁 Claim Box | Open your pending rewards/refunds |
| 47 | ⚖️ Sort | Cycle sort: Name → Price Low→High → Price High→Low |
| 48 | ◀ Previous | Previous page |
| 49 | 🔄 Refresh | Refresh the listing cache |
| 50 | ▶ Next | Next page |
| 51 | 📄 Transactions | Open recent transaction history |
| 52 | 📘 Help | Show in-game help message |
| 53 | 🪣 Fill All | Review & sell matching items across all open listings |

Listing lore shows buyer, quantities, per-unit and total price, **unpaid escrow**, and state. Clicking a listing opens the **sale confirmation screen** (below) rather than trading instantly.

---

### Sale Confirmation GUI (v6)

Opened by clicking any listing, the Fill All button, or `/wtb fill`. Nothing leaves your inventory until you approve.

Each order line shows: the item (with enchant, for books), the **amount you'd sell**, the **exact payout**, the **buyer's name**, how much of the order **remains afterwards**, and the order ID.

| Slot | Button | Function |
|---|---|---|
| 45 | 🟥 DENY | Cancel — nothing leaves your inventory |
| 49 | 📄 Sale Summary | Total orders, total items, total payout |
| 53 | 🟩 APPROVE | Execute the sale; payment is delivered to your Claim Box |

Behaviour notes:

- The preview is built from a **fresh database read** plus a snapshot of your inventory. On Approve, every line re-validates through the atomic database claim — if an order changed in the meantime you can only ever sell *less* than previewed, never more; any line that lost a race is reverted (items returned via Claim Box, `sale_reverted` message).
- A pending confirmation **expires after 60 seconds** and is voided if you close the screen or disconnect.
- Overlapping orders for the same item share your stock correctly — two 64-diamond orders won't both be promised the same 64 diamonds.
- Server owners can disable the screen entirely with `settings.listing.confirm-enabled: false`, restoring v5's instant-sale click (the 500 ms anti-spam click cooldown still applies).

---

### My Buy Orders GUI (`/wtb my`)

Shows the current player's own listings (active and filled). Clicking an active listing cancels it and queues a refund for the unpaid balance. Clicking a filled listing redirects to the Claim Box. Filter buttons (slots 51/52) toggle visibility of active and filled orders independently.

---

### Claim Box GUI (`/wtb claim`)

The Claim Box holds money payments, item deliveries, and refunds until the player collects them. All entries are persistent across restarts.

- **Click individual entry** — Claim that single reward.
- **Claim All button (slot 53)** — Claim everything at once. Stops if inventory is full and reports how many succeeded.

> Items that cannot be delivered (inventory full) stay in the Claim Box until the player has space. Items are never destroyed.

---

### Transaction History GUI (`/wtb tx`)

Shows the most recent trades server-wide (configurable limit, default 200). Each entry shows buyer, seller, item (with enchant for books), quantity, price, trade type, and date.

---

### Notifications

**Online buyers** get a title alert, sound, and chat message the moment an order is filled.

**Offline players (v6):** if your order is filled, expires, or is admin-cancelled while you're away, the alert — including the filler's name — is stored and delivered about two seconds after your next join, under a `[WTB] While you were away:` header with a sound. Messages are claimed atomically per row, so they can never be delivered twice.

---

## 8. Economy & Trade Flow

### Exact-Cents Escrow (v6)

All money movement is tracked in an integer-cents ledger (`paid_cents` per listing). The invariant, enforced and stress-tested:

```
deposit == sum of all payouts + refund       (exactly, in cents)
```

- The deposit is converted to cents **once**, from the price's decimal representation — never from raw floating-point bits, so a $19.99 order is 1999 cents, never 1998.
- Each partial fill pays `floor(priceCents × amount / totalQty)`.
- The **final** fill pays `priceCents − paidCents`, so any floor-division dust goes to the last seller instead of vanishing.
- Every refund (cancel, bulk cancel, admin cancel, expiry) is `priceCents − paidCents`, read **fresh from the database after** the state transition — concurrent fills are always accounted for.

### Creating a Buy Order

```
Player A runs: /wtb DIAMOND 10 50.00
```

1. WTB validates the material, quantity (≤ `max-quantity`), price, and — for books — the enchantment spec.
2. It checks Player A has not reached the per-player listing cap.
3. **$50.00 is immediately withdrawn** from Player A's balance via Vault.
4. The listing is inserted into the database (state: `OPEN`).
5. If the database write fails for any reason, the $50.00 is **automatically refunded**.

### Fulfilling a Listing

```
Player B opens /wtb, clicks the Diamond listing, reviews, and hits APPROVE.
```

1. The confirmation preview was built from fresh DB rows + an inventory snapshot.
2. On Approve, Player B's **pristine** matching items are counted and removed (main thread, storage slots only).
3. Asynchronously, WTB executes the **atomic relative claim**: `remaining = remaining − n … WHERE state IN ('OPEN','PARTIAL') AND remaining ≥ n`. Exactly one concurrent seller can win any unit of quantity — overselling is impossible even across multiple servers on one MySQL database.
4. **Only if the claim succeeds:** Player B's payout (exact cents, plus dust on the final fill) goes into their Claim Box; Player A's items go into theirs (batched at the item's real max stack size — books and tools ship one per entry).
5. Player A is notified — immediately if online, on next join if not. Player B gets a `sold_items` receipt.
6. If the claim **fails** (order was cancelled/expired/out-filled first), Player B's items are returned via their Claim Box and they're told the sale was reverted. No money moves.
7. The transaction log and price history are updated last (book trades aggregate per enchant).

### Cancelling

- **Single:** click your listing in `/wtb my`. Atomic `OPEN/PARTIAL → CANCELLED`; refund = exact unpaid cents.
- **All (v6):** `/wtb cancel` cancels every open order asynchronously and posts one summary (`Cancelled 4 order(s). Refund of $1,240.00 …`). If a sale is mid-flight on one of them, whichever database write commits first wins — the loser aborts cleanly.
- **Admin:** `/wtb admin cancel <id>` — same refund logic, logged under `admin-actions`, and the buyer is notified (offline delivery included).

### Listing Expiry

1. Only expired rows are queried (`WHERE expires_at <= NOW`) — no full table scan.
2. Each is conditionally set to `EXPIRED` (skipped if already filled/cancelled or mid-fulfilment).
3. The unpaid balance is re-read from the DB after the transition; a `REFUND` entry is queued.
4. The buyer is alerted — online immediately, offline on next join.

### Claim Box Delivery

- **`MONEY`** — Deposited directly to Vault balance.
- **`REFUND`** — Same as MONEY, labelled differently for clarity.
- **`ITEM`** — Added to inventory. If inventory is full, delivery is blocked and the entry remains.

All entries persist in the database until successfully claimed.

---

## 9. Logging

All economy events are logged to `plugins/WTB/logs.txt`:

```
[2026-07-03 19:36:53] Steve created listing: 3x ENCHANTED_BOOK (Mending 1) for $4,500.00 (ID: 42)
[2026-07-03 19:41:18] Alex fulfilled listing ID 42: sold 1x ENCHANTED_BOOK (Mending 1) for $1,500.00 to Steve (remaining: 2)
[2026-07-03 19:42:02] Alex approved sale into 3 listing(s).
[2026-07-03 19:46:54] Queued offline notification for Steve: Your buy order for CACTUS expired. Refund of $1,035.10 is in your Claim Box.
[2026-07-03 19:50:11] Listing 42 was no longer active (or lacked 2 remaining) when Alex attempted fulfillment — items returned to claim box.
[2026-07-03 19:55:40] [ADMIN] ServerAdmin force-cancelled listing ID 42 owned by Steve (refund: $1,500.00)
```

Each category can be toggled individually under `logging:` (see section 4). The v6 additions — `listing-collision`, `bulk-cancel`, `confirm-approved`, `confirm-denied`, `notifications` — give admins a complete audit trail of the confirmation flow and every race-resolution event. The log file is opened once on enable and kept open (buffered writes).

---

## 10. Database

### SQLite (Default)

- File: `plugins/WTB/wtb.db`
- WAL (Write-Ahead Logging) mode for concurrent reads; `busy_timeout` 5000 ms.
- No additional configuration required.
- Back up by copying `wtb.db` while the server is stopped.

### MySQL

Set `mysql.enabled: true` in `config.yml` and fill in connection details. The plugin creates all tables and indexes on first start, and the v6 migration runs automatically.

```sql
CREATE DATABASE wtb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'wtb_user'@'%' IDENTIFIED BY 'strongpassword';
GRANT ALL PRIVILEGES ON wtb.* TO 'wtb_user'@'%';
```

### Tables

| Table | Purpose |
|---|---|
| `listings` | All buy orders — now includes `enchant` (book spec), `paid_cents` (escrow ledger), and `item`/`custom_name` (exact-item catalog orders) |
| `claim_box` | Pending money, item, and refund entries per player |
| `transactions` | Immutable record of every completed trade (now with `enchant` and `custom_name`) |
| `price_history` | Running avg/min/max per material — books keyed per enchant (`ENCHANTED_BOOK;minecraft:mending;1`) |
| `notifications` *(v6)* | Pending offline alerts, delivered and deleted on next join |
| `catalog` *(v6)* | Admin-registered exact-item templates (`key`, serialized `item`, `label`, `created_by`, `created_at`) |

### Indexes

Created automatically:

```
listings(buyer), listings(state), listings(expires_at)
claim_box(player)
transactions(buyer), transactions(seller), transactions(timestamp)
notifications(player)
```

---

## 11. Reloading & Maintenance

### `/wtbreload`

Reloads `settings.yml` without a server restart: all messages, gameplay settings (including the v6 `confirm-enabled` and `max-quantity`), blocked materials, logging toggles, and admin groups. The tab-completer's material and enchantment caches are also rebuilt.

> ⚠️ `config.yml` (database settings) is **not** reloaded. A full server restart is required to change database backends.

### Backups

- **SQLite:** Copy `plugins/WTB/wtb.db` while the server is offline.
- **MySQL:** `mysqldump wtb > backup.sql` on your schedule.
- **Logs:** `logs.txt` is append-only and safe to copy at any time.

### Clearing Old Data

```sql
DELETE FROM listings WHERE state IN ('FILLED','CANCELLED','EXPIRED') AND created_at < UNIX_TIMESTAMP(NOW() - INTERVAL 90 DAY) * 1000;
DELETE FROM notifications WHERE created_at < UNIX_TIMESTAMP(NOW() - INTERVAL 90 DAY) * 1000;
```

Adjust the interval as needed. Always back up before running manual queries.

---

## 12. Troubleshooting

### Plugin does not load

- Check console for `[WTB]` error messages.
- Confirm Vault is installed and an economy plugin is registered.
- Confirm Java 21+ is in use.

### Players cannot create listings

- `max-listings` — the player may have hit their cap.
- `max-quantity` — the order may be too large.
- `min-price-per-item` — the offered price may be too low.
- The material may be in `blocked-materials`, or it may be a meta-bearing item that must be ordered by **catalog key** instead (potions, heads, banners, horns, fireworks - press TAB or see section 6). Shulker boxes and bundles are never tradeable.
- For enchanted books: the enchant name may be wrong (`invalid_enchant`) or the level out of range (`invalid_enchant_level`). Use tab-complete.
- Verify the player has sufficient Vault balance.

### "You don't have a matching item" but the seller has the item

Working as intended — the item is not **pristine**. Any damage, enchantment, rename, lore, repair cost, trim, or plugin tag disqualifies it (section 6). Only brand-new, unmodified items fill orders.

### A sale said it was reverted

The order changed between preview and execution (cancelled, expired, or out-filled by another seller). The seller's items were returned via their Claim Box and the event is logged under `listing-collision`. This is the race-safety system working correctly — no items or money were lost.

### A book listing shows as a barrier ("enchantment no longer exists")

The order's enchantment key (usually from a removed datapack) no longer resolves on this server. The order can't be filled, but the owner can still cancel it for a full refund of the unpaid balance, or an admin can `/wtb admin cancel` it.

### Offline notifications not arriving

- Check `logging.notifications` in `logs.txt` to confirm they're being queued.
- Delivery happens ~2 seconds after join; if the player relogs within that window the messages are re-queued, not lost.
- Confirm the `notifications` table exists (created by the v6 migration).

### Claim Box shows items but claiming does nothing

- The player's inventory is likely full. Items stay in the Claim Box until space is available.
- Check console for `claim_failed_deposit` errors indicating a Vault issue.

### Listings are not expiring

- Check `settings.expiry.check-interval-minutes` (default 10) and `settings.listing.expiry-days`.
- Enable `logging.listing-expired` and check `logs.txt` to confirm the task is running.

### Price history is inaccurate

- Price history is a running weighted average updated with each trade; it is not retroactive.
- Book prices are keyed per enchant — `DELETE FROM price_history WHERE material = 'ENCHANTED_BOOK;minecraft:mending;1';` resets one book type.

### Database connection errors (MySQL)

- Confirm the MySQL server is reachable and credentials in `config.yml` are correct.
- `Communications link failure` after idle: the 60 s keepalive should prevent this — verify MySQL `wait_timeout` ≥ 120 s.
- Confirm the user has `ALL PRIVILEGES` on the WTB database (the migration needs `ALTER TABLE`).

### `/wtbreload` does not seem to apply changes

- Confirm you saved `settings.yml` before reloading; console logs `[WTB] Settings reloaded.`
- Database settings (`config.yml`) require a full restart.

---

*WTB v6.0.0 · Paper 1.21+ · Author: xJBACx*
