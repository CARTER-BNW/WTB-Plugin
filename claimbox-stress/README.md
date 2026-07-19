# Claim Box stress suite (TEST SERVERS ONLY)

Companion to `stress/` (money engine). This one stresses the **Claim Box**:
Claim All against full / partial / empty inventories, shift-click
claim-all-of-type, sort modes, paging, races, and 1000-entry volume — with an
item/money **conservation invariant** checked in every scenario
(`delivered + still-in-box == seeded`; any violation is a dupe or a loss).

It was written for the v6.2.1 partial-fit duplication bug (fixed in v6.3.0)
and extended to cover the v6.4.0 QoL features.

## Run

1. `mvn package` (needs paper-api in the local repo, same as the main plugin)
2. Drop the jar into a **throwaway** Paper test server's `plugins/` next to the
   WTB jar (Vault + an economy plugin required by WTB itself).
3. Start the server. The suite runs by itself one tick after WTB enables,
   logs `STRESS PASS/FAIL` per check, prints a summary, and **shuts the server
   down** when done.

Everything runs through WTB's real production code paths (reflection into
`ClaimBoxService`, `MarketplaceClickListener.processClaimBatch` /
`claimAllOfType`, and `ClaimBoxGUI`), with proxy players whose inventories are
real `CraftInventory` instances so `addItem()` partial-fit semantics are real.

Expected result on a healthy build: `STRESS ALL GREEN`.
