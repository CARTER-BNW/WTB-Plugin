# WTB — V6.1 release: two clicks left (2026-07-11)

## Done already

- Version bumped to **6.1.0** (pom, plugin.yml), changelog entry added, jar rebuilt
  (`WTB\target\WTB-6.1.0.jar`) — all committed and pushed on branch `stress-hardening`.
- **Draft release V6.1** created on GitHub with full V6-style notes and the
  `WTB-6.1.0.jar` asset attached, targeting the `stress-hardening` branch:
  https://github.com/CARTER-BNW/WTB-Plugin/releases (shown as Draft)
- Stress results backing this release: 3 × 45 s, 20 players, ~192k ops, Paper 26.2 —
  **PASS, 0 violations** (exact-cent conservation, no oversell, exactly-once claims/refunds).

## To publish (run these yourself — they're permission-gated for the agent)

1. Merge PR #2 (do NOT delete the branch until step 2 is done — the draft tags from it):
   `gh pr merge 2 --repo CARTER-BNW/WTB-Plugin --merge`
2. Publish the draft (this creates tag `V6.1`):
   `gh release edit V6.1 --repo CARTER-BNW/WTB-Plugin --draft=false`
3. (Optional) delete the `stress-hardening` branch afterwards.

## Reminders

- Never install `WTBStress-1.0.0.jar` on a real server (it wipes WTB data per run).
- `stress/pom.xml` references the WTB jar by absolute path — machine-specific dev tool.
- The old V6 release stays as-is; V6.1 supersedes it (drop-in replacement, no DB/config changes).
