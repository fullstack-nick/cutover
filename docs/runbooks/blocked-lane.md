# Blocked lane or stale equipment observation

Open Equipment and inspect the site, zone, lane, blocked status and observation timestamp. Open the affected task to distinguish a known blocked lane from an unavailable/stale equipment snapshot or an unknown command. An old observation cannot establish that a lane is safe or open.

An eligible alternate lane in the same temperature class may continue. Chilled work cannot be assigned to an ambient lane merely because it is available. Returns has a separate lane; a single outbound fault should leave its classification movements independent. A migration drain retains the exact allocated blockers and their existing owner.

The simulator's lane fault is a controlled lab input. The simulation test identity, using its local client certificate, can reverse the specific recorded fault through `POST /sim/v1/test-controls/lanes` with `siteId`, `laneId` and `blocked:false`. A human supervisor's business recovery role does not grant arbitrary equipment test control. Preserve the previous fault state and never change unrelated lanes to hide a backlog.

After reversing the known fault, wait for a fresh adapter observation of that same world/generation and lane version. Verify the retained original movement advances through its existing allocation and command, then reaches one physical and business completion. Pending migration work must still pass the strict drain/reconciliation gate; clearing a lane is not an ownership switch.

If equipment is unreachable, restore its process/network/certificate path and verify observations before dispatch. A changed world or incomplete history belongs to [command investigation](../operations/command-investigation.md), not lane toggling. Do not release stock or replace command IDs because a task waited too long.

`node tools/scenario-driver/returns-smoke.mjs` verifies one blocked outbound lane, compatible alternate work and independent returns with real ledger assertions. `migration-smoke.mjs` verifies a finite blocked drain while the other outbound zone continues. Their scope is the synthetic local equipment model.
