# WTB — Pending push & release refresh (2026-07-08)

## Current state

- **Published:** Release **V6** on GitHub (tag `V6` @ `a399a27`) with the jar built from the
  12-bug audit-fix merge (PR #1). Live and public.
- **Local, NOT yet in the release:** branch **`stress-hardening`** (commit `4c16c89`), containing:
  - `ListingDAO.fulfillIfActive` guard rejecting zero/negative amounts (defense-in-depth;
    found by stress probe P2 — a negative amount at raw DAO level would have *increased*
    remaining quantity / minted items; unreachable in production, service layer already guards).
  - `stress/` — the WTBStress harness plugin (**test servers only** — it WIPES WTB's
    `listings` + `claim_box` tables each run).
  - This note.
- The branch was also pushed and **PR #2** is open: https://github.com/CARTER-BNW/WTB-Plugin/pull/2
- Stress results (3 × 45 s, 20 players, ~192k ops, Paper 26.2): **PASS, 0 violations** in all
  runs — exact-cent conservation, no oversell, exactly-once claims/refunds. Report format:
  see `wtbstress-report.txt` produced in the test server folder per run.

## When ready to release the hardened build

1. Merge PR #2 (or `gh pr merge 2 --merge` from this folder).
2. Rebuild if needed (jar already built at `WTB\target\WTB-6.0.0.jar` with the guard):
   `JAVA_HOME='C:\Program Files\Java\jdk-26.0.1'` +
   `'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' -f WTB\pom.xml clean package`
3. Replace the release asset:
   `gh release upload V6 "WTB/target/WTB-6.0.0.jar" --clobber`
   (or cut a V6.1 release if you prefer not to swap a published asset).

## Reminders

- Never install `WTBStress-1.0.0.jar` on a real server (it wipes WTB data per run).
- `stress/pom.xml` references the WTB jar by absolute path — machine-specific dev tool.
- Test server lives at `C:\Users\johnc\.claude\jobs\3ad0b6b3\tmp\mc262` (temporary; will be
  cleaned up with the session job — recreate by downloading Paper + copying plugins if needed).
