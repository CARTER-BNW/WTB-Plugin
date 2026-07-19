# WTB Project Status

Updated: 2026-07-20 (Claude session)

## Released: v6.4.0 QoL + stress suite (2026-07-20)

- dashv's three QoL asks implemented: sort toggle (Newest/Item Type),
  shift-click "claim all of this type", better paging (stay on page, clamp,
  page indicator). Plus deposit-throw hardening and stable DAO ordering.
- New rerunnable stress suite committed at `claimbox-stress/` — 12 scenarios,
  49 checks, conservation invariant everywhere. v6.4.0 run: ALL GREEN.
  Timings: 1000-row TYPE sort render 130 ms; 1000-entry Claim All 3.7 s
  (tick-spread, no freeze).
- Jar: `WTB/target/WTB-6.4.0.jar`. Drop-in for 6.3.0, no DB/config changes.
- Released V6.4 and V6.4.1 (help rewrite, admin cmds hidden, xJBACx credit). Deploy WTB-6.4.1.jar to sky-mc.net: https://github.com/CARTER-BNW/WTB-Plugin/releases/tag/V6.4.1

## Shipped earlier: v6.3.0 dupe fix

- **Bug (dashv's report, `Bug/Bug Report.txt`): CONFIRMED.** Claim Box partial-fit
  duplication — `ClaimBoxService.claim` re-queued the FULL stack when only part of
  it fit the inventory. Repro on local Paper 26.2: 64-item entry + room for 20 →
  84 items (+20 duped).
- **Fixed and verified** (same probe: 20 delivered + 44 re-queued = 64 exactly):
  - `ClaimBoxService` — re-queue only the leftover; new `ClaimResult` enum via
    `claimDetailed()`; claim message shows requested amount.
  - `MarketplaceClickListener` — Claim All stops attempting item claims once
    inventory is full; remaining DB rows untouched.
- Version bumped to 6.3.0 (pom + plugin.yml), CHANGELOG.md updated.
- Built jar: `WTB/target/WTB-6.3.0.jar`

## Released 2026-07-20

- PR #7 merged to main; GitHub release published:
  https://github.com/CARTER-BNW/WTB-Plugin/releases/tag/V6.3
  (jar attached: WTB-6.3.0.jar)

## Next steps

1. Deploy jar to the live server (sky-mc.net) — replaces WTB-6.2.1.jar, drop-in,
   no DB/config changes.
2. Live server still has dupe leftovers in `claim_box` (dashv had 451 ITEM rows
   at report time) — the duped ITEMS ALREADY IN PLAYER HANDS are not clawed back
   by the fix; decide whether to audit/compensate manually.
3. Future (from same report): sort Claim Box by type, "Claim All of this type",
   better multi-page claiming.

## Notes

- `Bug/` folder is deliberately NOT committed (contains live server DB
  `WTB.zip` — player UUIDs/economy; don't publish).
- Build: IntelliJ bundled Maven —
  `"C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.2\plugins\maven\lib\maven3\bin\mvn.cmd" package`
- Repro/verify probe source: Claude job tmp `D:\Claude\config\jobs\381f4b56\tmp\probe`
  (fake-player + real-inventory harness driving `ClaimBoxService.claim` reflectively).
