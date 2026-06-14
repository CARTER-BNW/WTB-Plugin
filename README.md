# WTB Plugin – Project Overview

## 📘 Introduction
The **WTB Plugin** (Want‑To‑Buy Marketplace System) is a modern, GUI‑driven economy plugin for Minecraft servers.  
Instead of traditional auction‑style trading, WTB introduces a **buyer‑driven marketplace**, where players create buy orders and other players fulfill them.

This creates a healthier, more dynamic server economy and encourages player interaction, resource gathering, and long‑term progression.

---

## 🎯 Project Goals
- Build a clean, intuitive buy‑order system for Minecraft servers.
- Provide a fully GUI‑based experience for players.
- Offer powerful but simple admin tools.
- Ensure safe, scalable, database‑backed storage.
- Integrate seamlessly with Vault and existing economy plugins.
- Maintain a lightweight, efficient codebase suitable for large SMP servers.

---

## 🧩 Core Features
### Player Systems
- Create buy orders with material, quantity, and price.
- Browse all active listings through a GUI.
- Manage personal listings via **My Listings**.
- Claim fulfilled items through the **Claim Box** (infinite pagination).
- View transaction history in the **Transactions GUI**.
- Automatic cooldowns and validation to prevent spam or abuse.

### Admin Systems
- `/wtb admin list` — view all active listings.
- `/wtb admin cancel <id>` — force‑cancel any listing.
- Admin‑only listing IDs in GUIs.
- Admin‑only tab completion.
- UUID → player name resolution for readable output.

---

## 🛠 Technical Architecture
- **Platform:** Spigot/Paper 1.20+
- **Economy:** Vault API
- **Database:** SQLite or MySQL
- **Structure:**
  - `ListingService` — business logic
  - `ListingDAO` — database operations
  - `GUI` classes — inventory‑based interfaces
- **Permissions:**
  - `wtb.admin` — admin commands and listing visibility

---

## 🧱 Design Principles
- **Player‑first UX:** All interactions are GUI‑based.
- **Safety:** No item loss, safe claim handling, strict validation.
- **Performance:** Lightweight queries, efficient caching, async DB operations.
- **Scalability:** Supports thousands of listings with pagination.
- **Maintainability:** Clean service/DAO architecture.

---

## 📄 Plugin Description (for documentation or plugin pages)
**WTB is the cleanest and most intuitive buy‑order marketplace plugin for Minecraft.**

Players can:
- Request items they want  
- Track and manage their listings  
- Claim fulfilled orders  
- Use a modern, polished GUI system  

Admins get:
- Full visibility  
- Full control  
- Zero clutter  

Perfect for:
- Survival servers  
- Economy servers  
- Community SMPs  
- Modded servers with resource‑heavy gameplay  

---

## 📌 Future Roadmap (Optional)
- Listing expiration system  
- Sorting and filtering in GUIs  
- Click‑to‑cancel from admin GUI  
- Seller‑side GUI for fulfilling orders  
- Analytics dashboard for admins  
- Multi‑currency support  

---

## 📬 Contact & Support
For issues, suggestions, or feature requests, open an Issue on GitHub.


