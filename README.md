# WTB — Want To Buy Marketplace

> A fully async, SMP-safe **buy-order marketplace** plugin for Paper 1.21+ servers. Current version: **v6.2.0**.

Instead of auction-style selling, WTB flips the market: players post **buy orders** for the items they want, and other players browse the marketplace and sell straight from their inventory. Money and items are delivered through a per-player **Claim Box**, so nothing is ever lost — even if someone is offline.

📦 **[Download the latest release](https://github.com/CARTER-BNW/WTB-Plugin/releases)** · 📖 [Operations Manual](OPERATIONS_MANUAL.md) · 📜 [Changelog](CHANGELOG.md)

---

## ✨ Features

### For players

- **Buy orders** — `/wtb diamond_pickaxe 2 800` posts an order; sellers come to you.
- **Pristine-item matching** — only brand-new, unmodified items fill orders. Damage, enchants, renames, lore, and plugin tags are all rejected automatically, in both directions.
- **Tools, weapons, armor & enchanted books** are tradeable — `/wtb enchanted_book 5 2500 mending` orders five Mending books from any source (villager, table, loot, fishing).
- **Item catalog** — order exact potions, tipped arrows, goat horns, rockets, and admin-registered server items by key (`/wtb potion_strong_swiftness 8 400`), all tab-completed.
- **Approve/Deny confirmation** before every sale — see exactly what you'd sell, to whom, and for how much before anything leaves your inventory.
- **Fill All / `/wtb fill`** — sell into every order you can in one confirmed action.
- **`/wtb cancel`** — pull all your open orders with one command and one summary refund.
- **Claim Box** — async-safe escrow delivery for money, items, and refunds.
- **Offline notifications** — order filled while you were away? You're told who filled it on your next join.
- **Personal settings (v6.2)** — `/wtb settings mute <full|partial|all|off>` lets each player mute the order-filled screen popup for themselves, per fill type, saved across restarts. `/wtb settings <anything>` mirrors every player command (`/wtb settings claim` = `/wtb claim`).

### For server owners

- **Exact-cents escrow** — all money runs on an integer-cents ledger; deposits always equal payouts + refunds + escrow, **to the cent**. Verified by a 20-player concurrent stress campaign (~192,000 operations, zero violations) on a live Paper 26.2 server.
- **SQLite or MySQL** with connection pooling; the database migrates itself between versions — no manual SQL, ever.
- **Fully async** — no database work on the main server thread; writes are drained safely on shutdown so a restart never destroys owed money or items.
- **Configurable everything** — listing caps, quantity caps, price minimums, expiry days, blocked materials, per-message text and colors, category-gated file logging.
- **Admin tools** — force-cancel any listing, inspect the exact escrow ledger in-game, and register custom server items (`/wtb admin register god_sword`) as orderable catalog keys.
- **Auto-expiry** with automatic refunds; per-material price history; transaction log GUI.

---

## 🚀 Quick Start

1. Drop `WTB-6.2.0.jar` into `plugins/`.
2. Make sure **Vault** and an economy plugin (EssentialsX, CMI, …) are installed.
3. Start the server — `plugins/WTB/settings.yml` is generated automatically.
4. Players type `/wtb` to open the marketplace. That's it.

Upgrading from any older WTB: replace the jar and restart — the database migrates itself and your existing `settings.yml` keeps working.

---

## ⚙️ Requirements

| Requirement | Version |
|---|---|
| **Paper** | 1.21 or newer — verified working through **Paper 26.2** (pure Paper API, no NMS) |
| **Java** | 21+ |
| **Vault** | any current release, plus a Vault-compatible economy plugin |

> ⚠️ Spigot is not supported — WTB uses Paper-specific APIs.

---

## 🎮 Commands

| Command | Description |
|---|---|
| `/wtb` | Open the marketplace GUI |
| `/wtb <item> <qty> <total_price>` | Create a buy order (material or catalog key) |
| `/wtb enchanted_book <qty> <price> <enchant> [lvl]` | Order enchanted books |
| `/wtb fill` | Sell into every order you can (with confirmation) |
| `/wtb cancel` | Cancel all your open orders |
| `/wtb my` / `claim` / `tx` | Your orders / Claim Box / transaction history |
| `/wtb settings` | Your personal preferences (popup mutes, …) |
| `/wtb settings mute <full\|partial\|all\|off>` | Mute the order-filled popup for yourself |
| `/wtb admin …` | Force-cancel, inspect, register/unregister catalog items (`wtb.admin`) |
| `/wtbreload` | Reload settings without a restart (`wtb.reload`) |

Full command, permission, configuration, and troubleshooting reference: **[Operations Manual](OPERATIONS_MANUAL.md)**.

---

## 🏗️ Architecture (for the curious)

- `ListingService` — trade pipeline: atomic conditional SQL claims, single-connection transactions, main-thread Vault interaction only.
- `ListingDAO` / `ClaimBoxDAO` — relative conditional UPDATEs (no oversell possible), delete-first claim semantics (no double-delivery possible).
- `Payout` — integer-cents math with exact remainder settlement (no stranded escrow).
- `stress/` — the WTBStress harness that drives the real production code with 20 concurrent simulated players and audits ten money invariants. **Dev tool only — it wipes WTB data; never install it on a real server.**

---

## 📬 Support

- 🐛 Bugs & suggestions: [Discord](https://discord.gg/XHUdvWyQ8B) or open a GitHub Issue
- 📜 Release history: [Releases](https://github.com/CARTER-BNW/WTB-Plugin/releases) · [CHANGELOG.md](CHANGELOG.md)
