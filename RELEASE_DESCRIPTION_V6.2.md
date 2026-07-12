# WTB — Want To Buy Marketplace · v6.2.0

> A fully async, SMP-safe player-driven buy-order marketplace for Paper 1.21+ servers.

WTB lets players post **buy orders** for any item they want. Other players browse the marketplace, click a listing, and sell straight from their inventory — no chest shops, no signs, no standing around. Money and items are delivered securely through a per-player **Claim Box** so nothing is ever lost even if someone is offline.

---

## ✨ What's New in v6.2.0

**Every player can now mute the "Buy Order Filled!" screen popup for themselves** — no more waiting for the server owner to decide for everyone:

```
/wtb settings mute full      – toggle the popup for fully completed orders
/wtb settings mute partial   – toggle the popup for partial fills
/wtb settings mute all       – mute both
/wtb settings mute off       – unmute both
/wtb settings mute           – show your current preference
```

- Preferences are **saved in the database** — they survive relogs and server restarts.
- Muting hides **only the full-screen title**. The chat message, pickup sound, offline notifications, and Claim Box delivery all keep working, so a muted player never misses a trade.
- The v6.1.1 server-wide keys (`settings.notifications.popup-on-*`) still apply on top: a popup shows only when the **server and the player both allow it**.

**New `/wtb settings` namespace** — one discoverable home for everything player-facing:

- `/wtb settings <anything>` works exactly like `/wtb <anything>` — `/wtb settings claim` opens your Claim Box, `/wtb settings my` shows your orders, and so on.
- `/wtb settings` alone shows your current preferences.
- All existing commands are completely untouched — this is purely additive, so the transition is seamless.
- Admin commands are never suggested under `settings` for regular players.

**Under the hood:** a new `player_settings` database table is created automatically on first boot (SQLite and MySQL), and preferences are cached in memory per player — the trade hot path never touches the database. Verified with a clean boot/shutdown cycle on a live Paper 26.2 server.

Full details in [CHANGELOG.md](https://github.com/CARTER-BNW/WTB-Plugin/blob/main/CHANGELOG.md).

---

## ⚙️ Requirements

- **Paper** 1.21 or newer (Spigot not supported) — ✅ verified working on Paper 26.2. WTB is pure Paper API with no NMS/internal mappings, so it is unaffected by the Spigot-mappings removal in Paper 26.1+.
- **Vault** + any compatible economy plugin (EssentialsX, CMI, etc.)
- Java 21+

---

## 📦 Installation

1. Drop `WTB-6.2.0.jar` into your `plugins/` folder.
2. Ensure **Vault** and an economy plugin are installed.
3. Start the server — `plugins/WTB/settings.yml` is generated automatically.
4. Configure as needed and run `/wtbreload`.

## ⬆️ Upgrading from v6.x

Replace your WTB jar with `WTB-6.2.0.jar` and restart. The `player_settings` table is created automatically — no manual SQL, no config changes. The new chat messages (mute status lines) resolve from the jar's bundled defaults until you choose to customise them in `settings.yml`.

## ⬆️ Upgrading from v5

1. Stop the server (recommended) and back up `plugins/WTB/wtb.db` (or your MySQL database).
2. Replace `WTB-5.x.jar` with `WTB-6.2.0.jar`.
3. Start the server — the database migrates itself. **No manual SQL, no config edits.**

---

## 🔗 Links

- 📖 [Operations Manual](https://github.com/CARTER-BNW/WTB-Plugin/blob/main/OPERATIONS_MANUAL.md)
- 📜 [Changelog](https://github.com/CARTER-BNW/WTB-Plugin/blob/main/CHANGELOG.md)
- 🐛 [Report a Bug](https://discord.gg/XHUdvWyQ8B)
- 💬 [Discussions](https://discord.gg/XHUdvWyQ8B)
