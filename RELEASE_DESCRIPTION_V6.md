# WTB — Want To Buy Marketplace · v6.0.0

> A fully async, SMP-safe player-driven buy-order marketplace for Paper 1.21+ servers.

WTB lets players post **buy orders** for any item they want. Other players browse the marketplace, click a listing, and sell straight from their inventory — no chest shops, no signs, no standing around. Money and items are delivered securely through a per-player **Claim Box** so nothing is ever lost even if someone is offline.

---

## ✨ What's New in v6.0.0

v6.0.0 is the biggest WTB release yet:

- **Tools, weapons, armor & enchanted books are now tradeable.** Every sale is matched against a *pristine* item template — only brand-new, unmodified items (no damage, no enchants, no renames, no plugin tags) can fill an order, so both sides always know exactly what they're trading.
- **Enchanted book orders** — `/wtb enchanted_book 5 2500 mending` orders five Mending books. Books from villagers, enchanting tables, loot chests, and fishing are all interchangeable; matching is done on the actual stored enchantment, never tooltip text.
- **Approve/Deny before every sale.** Clicking a listing (or Fill All) now opens a confirmation screen showing exactly what you'd sell, to whom, and for how much — nothing leaves your inventory until you hit APPROVE.
- **Item catalog - potions, tipped arrows, goat horns, fireworks, heads, banners, and your server's custom items.** Built-in keys are generated from the server's own registries (`/wtb potion_strong_swiftness 8 400`, `/wtb goat_horn_ponder 1 250` - all tab-completed), and admins can register any custom item once with `/wtb admin register god_sword` while holding it. Full item meta is captured, so an anvil-renamed fake can never fill a real order.
- **Offline notifications.** Order filled while you were away? You'll be told who filled it the moment you log back in.
- **`/wtb fill`** to sell into every order you can, and **`/wtb cancel`** to pull all your open orders at once.
- **Two newly discovered v5 economy exploits eliminated** — a stale-cache oversell that could mint items and money, and floor-on-doubles payout math that underpaid sellers and stranded cents in escrow forever. All escrow now runs on an exact integer-cents ledger, stress-tested for perfect conservation under heavy concurrency.
- **Seamless upgrade** — drop the jar in over v5. The database migrates itself on first boot and your existing `settings.yml` keeps working untouched.

Full details in `CHANGELOG.md`.

---

## 🖼️ Screenshots

**The Marketplace**

![WTB Market GUI](assets/screenshots/WTB%20V6/WTB_Market_GUI.png)

**Sale Confirmation — Approve / Deny**

![Confirm Sale](assets/screenshots/WTB%20V6/WTB_Confirm_Sale_GUI.png)

**Enchanted Book Order — Hover**

![Book Order Hover](assets/screenshots/WTB%20V6/WTB_Market_GUI_Book_Hover.png)

**Listing Hover — Player View**

![Player Hover](assets/screenshots/WTB%20V6/WTB_Market_GUI_Player_Hover.png)

**Listing Hover — Admin View**

![Admin Hover](assets/screenshots/WTB%20V6/WTB_Market_GUI_Admin_Hover.png)

**My Buy Orders**

![My Listings](assets/screenshots/WTB%20V6/WTB_My_Listings_GUI.png)

**Claim Box**

![Claim Box](assets/screenshots/WTB%20V6/WTB_Claim_Box_GUI.png)

**Offline Notification on Join**

![Offline Notification](assets/screenshots/WTB%20V6/WTB_Offline_Notification.png)

**Transaction History**

![Transactions](assets/screenshots/WTB%20V6/WTB_Transactions_GUI.png)

---

## 🎮 Feature Overview

| Feature | Description |
|---|---|
| **Buy Orders** | Post the item, the quantity, and your price — done |
| **Pristine Matching** | Only new, unmodified items fill orders — damage, enchants, renames, and plugin-tagged items are rejected automatically |
| **Tools & Armor Trading** | Order brand-new pickaxes, swords, elytra — anything |
| **Enchanted Book Orders** | Order a specific enchant + level; all book sources are interchangeable |
| **Item Catalog** | Order exact potions, tipped arrows, goat horns, rockets, and admin-registered server items (god gear, heads, banners) by key |
| **Sale Confirmation** | Approve/Deny screen shows items, payout, buyer, and remaining before anything moves |
| **One-click Selling** | Browse, click, review, approve |
| **Fill All / `/wtb fill`** | Sell matching items across all open listings in one confirmed action |
| **`/wtb cancel`** | Cancel every open order you have with one command and one summary refund |
| **Offline Notifications** | Fill/expiry/admin-cancel alerts delivered on next join, with the filler's name |
| **Claim Box** | Async-safe escrow for money, items, and refunds |
| **Exact-Cents Escrow** | Integer-cents ledger; deposits always equal payouts + refunds, to the cent |
| **Transaction Log** | In-game GUI showing recent market activity |
| **Price History** | Avg/min/max tracking per material — and per enchant for books |
| **Auto-Expiry** | Listings expire after a configurable number of days with automatic refund |
| **Sort Modes** | Sort by name, price low→high, or price high→low |
| **SQLite / MySQL** | Switchable database backend with connection pooling |
| **Per-player cap** | Configurable limit on active listings per player |
| **Quantity cap** | Configurable maximum quantity per order |
| **Material blocklist** | Prevent specific items from being traded |
| **Admin tools** | Force-cancel any listing and inspect full details (incl. escrow ledger) in-game |
| **Full logging** | Category-gated file logging for every economy event |

---

## ⚙️ Requirements

- **Paper** 1.21 or newer (Spigot not supported)
- **Vault** + any compatible economy plugin (EssentialsX, CMI, etc.)
- Java 21+

---

## 📦 Installation

1. Drop `WTB-6.0.0.jar` into your `plugins/` folder.
2. Ensure **Vault** and an economy plugin are installed.
3. Start the server — `plugins/WTB/settings.yml` is generated automatically.
4. Configure as needed and run `/wtbreload`.

## ⬆️ Upgrading from v5

1. Stop the server (recommended) and back up `plugins/WTB/wtb.db` (or your MySQL database).
2. Replace `WTB-5.x.jar` with `WTB-6.0.0.jar`.
3. Start the server. The console will show `[WTB] Migration: …` lines as the database upgrades itself — new columns are added and the escrow ledger is backfilled for existing listings. **No manual SQL, no config edits.**
4. Your existing `settings.yml` keeps working: any key added in v6 is resolved from the jar's bundled defaults until you choose to customise it.
