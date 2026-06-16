
# WTB v5.0.0 — Operations Manual

**For server administrators and owners.**

---

## Table of Contents

1. [Requirements & Installation](#1-requirements--installation)
2. [File Structure](#2-file-structure)
3. [Configuration Reference](#3-configuration-reference)
4. [Commands & Permissions](#4-commands--permissions)
5. [GUI Reference](#5-gui-reference)
6. [Economy & Trade Flow](#6-economy--trade-flow)
7. [Logging](#7-logging)
8. [Database](#8-database)
9. [Reloading & Maintenance](#9-reloading--maintenance)
10. [Troubleshooting](#10-troubleshooting)

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

1. Drop `WTB-4.0.0.jar` into your server's `plugins/` folder.
2. Confirm **Vault** and an economy plugin are already installed.
3. Start (or restart) the server.
4. WTB creates `plugins/WTB/settings.yml` and `plugins/WTB/config.yml` automatically.
5. Edit `settings.yml` to match your server's economy and rules.
6. Run `/wtbreload` in-game or from console to apply changes without a restart.

---

## 2. File Structure

```
plugins/WTB/
├── config.yml        ← Database connection settings (SQLite or MySQL)
├── settings.yml      ← All gameplay settings and messages
├── logs.txt          ← Append-only event log
└── wtb.db            ← SQLite database file (only if using SQLite)
```

---

## 3. Configuration Reference

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

When `enabled: false`, the plugin uses SQLite (`plugins/WTB/wtb.db`). SQLite is fine for most servers. Use MySQL for large networks or if you share economy data across multiple servers.

---

### `settings.yml` — Full Reference

#### Listing Settings

```yaml
settings:
  listing:
    max-listings: 5            # Maximum active listings per player (OPEN + PARTIAL combined)
    min-price-per-item: 1.0    # Minimum price per individual item (prevents spam/griefing)
    expiry-days: 7             # Days before an unfulfilled listing expires and is auto-refunded
    fill-all-enabled: true     # Show the "Fill All Open" button in the marketplace GUI
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

Material names must match Minecraft's internal names exactly (e.g. `OAK_LOG`, `NETHERITE_SWORD`). Use `/wtb buy <material>` tab-complete to check what names are valid — blocked materials are excluded from suggestions.

#### Admin Groups

```yaml
  admin-groups: []
  # Example:
  # admin-groups:
  #   - group.staff
  #   - permission.market_admin
```

Extra permission nodes that grant WTB admin access in addition to the `wtb.admin` node. Useful for permission group plugins (LuckPerms, etc.) where staff have a group permission rather than a direct node.

#### Logging

```yaml
logging:
  enabled: true             # Master switch — disabling this silences all WTB logging
  listing-created: true     # Log when a player creates a buy order
  listing-filled: true      # Log when a listing is fully filled
  listing-partial: true     # Log when a listing is partially filled
  listing-cancelled: true   # Log when a player cancels their own listing
  listing-expired: true     # Log when a listing expires automatically
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
| `sold_items` | `{amount}`, `{material}`, `{payout}` |
| `order_filled_subtitle` | `{seller}` |
| `order_fully_filled` | `{material}`, `{seller}` |
| `order_partially_filled` | `{material}`, `{seller}`, `{remaining}` |
| `claim_money` | `{amount}` |
| `claim_item` | `{amount}`, `{material}` |
| `claim_all_success` | `{count}` |
| `claim_all_partial` | `{count}`, `{failed}` |
| `admin_cancel_success` | `{id}`, `{buyer}` |
| `fill_all_success` | `{count}` |

---

## 4. Commands & Permissions

### Player Commands

| Command | Description |
|---|---|
| `/wtb` | Open the marketplace GUI |
| `/wtb buy <material> <quantity> <total_price>` | Create a new buy order |
| `/wtb my` | View and manage your own buy orders |
| `/wtb claim` | Open your Claim Box |
| `/wtb tx` | View recent transaction history |
| `/wtb help` | Show in-game help |

**Example:**
```
/wtb buy IRON_INGOT 64 32.00
```
Posts a buy order for 64 Iron Ingots for a total price of $32.00 ($0.50 per ingot).

The `<total_price>` is the **total** you will pay for all items, not the per-item price. The per-item price is calculated from `total_price / quantity`.

---

### Admin Commands

| Command | Permission | Description |
|---|---|---|
| `/wtb admin cancel <id>` | `wtb.admin` | Force-cancel any listing by ID, with automatic refund |
| `/wtb admin info <id>` | `wtb.admin` | Inspect a listing's full details |
| `/wtbreload` | `wtb.reload` | Reload `settings.yml` without a server restart |

**Finding listing IDs:** Hover over any listing in the marketplace as an admin — the listing ID is shown at the bottom of the item's lore.

![Admin Hover](assets/screenshots/WTB%20V4/WTB_Market_GUI_Admin_Hover.png)

---

### Permission Nodes

| Node | Default | Description |
|---|---|---|
| `wtb.admin` | OP | Access to `/wtb admin cancel` and `/wtb admin info`; shows listing IDs on hover |
| `wtb.reload` | OP | Access to `/wtbreload` |

To grant non-OP staff admin access without the `wtb.admin` node directly, add their permission group to `settings.admin-groups` in `settings.yml`.

---

## 5. GUI Reference

### Marketplace GUI (`/wtb`)

![WTB Market GUI](assets/screenshots/WTB%20V4/WTB_Market_GUI.png)

The main 54-slot inventory showing all open and partial buy orders.

| Slot | Button | Function |
|---|---|---|
| 45 | 📖 My Listings | Open your personal buy orders |
| 46 | 🎁 Claim Box | Open your pending rewards/refunds |
| 47 | ⚙️ Sort | Cycle sort: Name → Price Low→High → Price High→Low |
| 48 | ◀ Previous | Previous page |
| 49 | ⭐ Refresh | Refresh the listing cache |
| 50 | ▶ Next | Next page |
| 51 | 📄 Transactions | Open recent transaction history |
| 52 | 📗 Help | Show in-game help message |
| 53 | 🔽 Fill All | Sell matching items across all open listings at once |

**Hover — Player:**

![Player Hover](assets/screenshots/WTB%20V4/WTB_Market_GUI_Player_Hover.png)

**Hover — Admin (listing ID visible):**

![Admin Hover](assets/screenshots/WTB%20V4/WTB_Market_GUI_Admin_Hover.png)

---

### My Buy Orders GUI (`/wtb my`)

![My Listings](assets/screenshots/WTB%20V4/WTB_My_Listings_GUI.png)

Shows the current player's own listings (active and filled). Clicking an active listing cancels it and queues a refund for the unspent balance. Clicking a filled listing redirects to the Claim Box.

Filter buttons (slots 51/52) toggle visibility of active and filled orders independently.

---

### Claim Box GUI (`/wtb claim`)

The Claim Box holds money payments, item deliveries, and refunds until the player collects them. All entries are persistent across restarts.

**Money Reward:**

![Money Reward](assets/screenshots/WTB%20V4/WTB_Claim_Box_Money_Reward_GUI.png)

**Item Reward:**

![Item Reward](assets/screenshots/WTB%20V4/WTB_Claim_Box_Item_Reward_GUI.png)

**Refund:**

![Refund](assets/screenshots/WTB%20V4/WTB_Claim_Box_Refund_GUI.png)

**Full Claim Box:**

![Claim Box](assets/screenshots/WTB%20V4/WTB_Claim_Box_GUI.png)

- **Click individual entry** — Claim that single reward.
- **Fill All button (slot 53)** — Claim everything at once. Stops if inventory is full and reports how many succeeded.

> Items that cannot be delivered (inventory full) stay in the Claim Box until the player has space.

---

### Transaction History GUI (`/wtb tx`)

![Transactions](assets/screenshots/WTB%20V4/WTB_Transactions_GUI.png)

Shows the most recent trades server-wide (configurable limit, default 200). Each entry shows buyer, seller, material, quantity, price, trade type, and date.

---

### Order Filled Notification

When a buyer's order is filled (fully or partially), they receive a title alert and chat message:

![Order Filled](assets/screenshots/WTB%20V4/WTB_Order_Filled_Text_Alert.png)

Chat messages also appear:

![Chat 1](assets/screenshots/WTB%20V4/WTB_Chat_1.png)
![Chat 2](assets/screenshots/WTB%20V4/WTB_Chat_2.png)

---

## 6. Economy & Trade Flow

### Creating a Buy Order

```
Player A runs: /wtb buy DIAMOND 10 50.00
```

1. WTB validates the material, quantity, and price.
2. It checks Player A has not reached the per-player listing cap.
3. **$50.00 is immediately withdrawn** from Player A's balance via Vault.
4. The listing is inserted into the database (state: `OPEN`).
5. If the database write fails for any reason, the $50.00 is **automatically refunded**.

---

### Fulfilling a Listing

```
Player B opens /wtb and clicks the Diamond listing.
```

1. Player B's inventory is checked for Diamonds.
2. The matching count is calculated (up to the listing's remaining quantity).
3. **Items are removed from Player B's inventory immediately** (main thread).
4. The DB writes (claim entries, state update, transaction log) run asynchronously.
5. Player B's money goes into their **Claim Box** (`MONEY` entry).
6. Player A's items go into their **Claim Box** (`ITEM` entries, batched at 64 per entry).

If the listing is fully filled, state transitions `OPEN/PARTIAL → FILLED`. If partially filled, state becomes `PARTIAL` and the remaining quantity is decremented.

---

### Cancelling a Listing

When Player A cancels their own listing (or an admin force-cancels it):

1. The listing is atomically set to `CANCELLED` (only if still `OPEN` or `PARTIAL`).
2. The unspent balance is calculated: `(original_price / original_quantity) × remaining_quantity`.
3. A `REFUND` entry is placed in Player A's Claim Box.

The refund is proportional — if the listing was 50% filled before cancellation, the player is refunded for the unfilled 50%.

---

### Listing Expiry

Listings that exceed `expiry-days` are expired automatically by the background task (runs every `check-interval-minutes`):

1. Only expired rows are queried (`WHERE expires_at <= NOW`) — no full table scan.
2. Each expired listing is conditionally set to `EXPIRED` (skipped if already filled/cancelled).
3. The remaining balance is re-fetched from DB immediately after the state change to ensure the refund is accurate.
4. A `REFUND` entry is placed in the buyer's Claim Box.

---

### Claim Box Delivery

Players collect from the Claim Box at any time via `/wtb claim`:

- **`MONEY`** — Deposited directly to Vault balance.
- **`REFUND`** — Same as MONEY, labelled differently for clarity.
- **`ITEM`** — Added to inventory. If inventory is full, delivery is blocked and the entry remains.

All entries persist in the database until successfully claimed. Items are never destroyed.

---

## 7. Logging

All economy events are logged to `plugins/WTB/logs.txt` in the format:

```
[2026-06-16 14:32:01] Steve created listing: 64x IRON_INGOT for $32.00 (ID: 42)
[2026-06-16 14:35:18] Alex fulfilled listing ID 42: sold 32x IRON_INGOT for $16.00 to Steve (remaining: 32)
[2026-06-16 14:40:55] [ADMIN] ServerAdmin force-cancelled listing ID 42 owned by Steve (refund: $16.00)
```

Each log category can be toggled individually in `settings.yml` under `logging:`. The master switch `logging.enabled: false` silences everything.

The log file is opened once when the plugin enables and kept open (buffered writes), ensuring no data is lost on a crash and no file descriptor is wasted.

---

## 8. Database

### SQLite (Default)

- File: `plugins/WTB/wtb.db`
- Uses WAL (Write-Ahead Logging) mode for concurrent reads.
- `busy_timeout` is set to 5000 ms to handle contention gracefully.
- No additional configuration required.
- Back up by copying `wtb.db` while the server is stopped.

### MySQL

Set `mysql.enabled: true` in `config.yml` and fill in connection details. The plugin creates all tables and indexes on first start.

**Recommended MySQL setup:**
```sql
CREATE DATABASE wtb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'wtb_user'@'%' IDENTIFIED BY 'strongpassword';
GRANT ALL PRIVILEGES ON wtb.* TO 'wtb_user'@'%';
```

### Tables

| Table | Purpose |
|---|---|
| `listings` | All buy orders (open, partial, filled, cancelled, expired) |
| `claim_box` | Pending money, item, and refund entries per player |
| `transactions` | Immutable record of every completed trade |
| `price_history` | Running avg/min/max price per material |

### Indexes

The following indexes are created automatically and are critical for performance on large servers:

```
listings(buyer), listings(state), listings(expires_at)
claim_box(player)
transactions(buyer), transactions(seller), transactions(timestamp)
```

---

## 9. Reloading & Maintenance

### `/wtbreload`

Reloads `settings.yml` without a server restart. This updates:
- All message strings
- Gameplay settings (max listings, min price, expiry days, fill-all toggle)
- Blocked materials list
- Logging toggles
- Admin groups

The tab-completer's material cache is also cleared so new blocked-material entries are reflected immediately.

> ⚠️ `config.yml` (database settings) is **not** reloaded. A full server restart is required to change database backends.

### Backups

- **SQLite:** Copy `plugins/WTB/wtb.db` while the server is offline.
- **MySQL:** Use `mysqldump wtb > backup.sql` on your schedule.
- **Logs:** `logs.txt` is append-only and safe to copy at any time.

### Clearing Old Data

There is no built-in data purge command. To remove old `FILLED`/`CANCELLED`/`EXPIRED` listings from the database, run:

```sql
DELETE FROM listings WHERE state IN ('FILLED','CANCELLED','EXPIRED') AND created_at < UNIX_TIMESTAMP(NOW() - INTERVAL 90 DAY) * 1000;
```

Adjust the interval as needed. Always back up before running manual queries.

---

## 10. Troubleshooting

### Plugin does not load

- Check console for `[WTB]` error messages.
- Confirm Vault is installed and an economy plugin is registered.
- Confirm Java 21+ is in use (`java -version` in console).

### Players cannot create listings (`/wtb buy`)

- Check `settings.listing.max-listings` — the player may have hit their cap.
- Check `settings.listing.min-price-per-item` — the offered price may be too low.
- Check that the material is not in `settings.blocked-materials`.
- Verify the player has sufficient Vault balance.

### Claim Box shows items but claiming does nothing

- The player's inventory is likely full. Items stay in the Claim Box until space is available.
- Check console for `claim_failed_deposit` errors indicating a Vault issue.

### Listings are not expiring

- Check that `settings.expiry.check-interval-minutes` is a reasonable value (default 10).
- Verify `settings.listing.expiry-days` is set correctly.
- Check `logging.listing-expired: true` and look at `logs.txt` to confirm the task is running.

### Price history is inaccurate

- Price history is a running weighted average updated with each trade. It is not retroactively calculated from existing transactions.
- If the `price_history` table contains obviously wrong data, it can be reset per-material: `DELETE FROM price_history WHERE material = 'IRON_INGOT';`

### Database connection errors (MySQL)

- Confirm the MySQL server is reachable from the game server.
- Check credentials in `config.yml`.
- If you see `Communications link failure` after idle periods, the keepalive is configured at 60 seconds which should prevent this — verify your MySQL `wait_timeout` is at least 120 seconds.
- Check that the user has `ALL PRIVILEGES` on the WTB database.

### `/wtbreload` does not seem to apply changes

- Confirm you saved `settings.yml` before reloading.
- Check console — the reload will log `[WTB] Settings reloaded.`
- Note that database settings (`config.yml`) require a full restart to take effect.

---

*WTB v5.0.0 · Paper 1.21+ · Author: xJBACx*
