# Initial task-creation transition

Use this once when upgrading the retained original baseline. It preserves the independent simulator and all accepted business evidence. For a fresh empty final database, normal deployment applies V112 directly.

1. Build and verify the current source, then deploy the registration-capable image while retaining the original trigger:

   ```powershell
   ./mvnw.cmd -B -ntp verify
   ./scripts/build-images.ps1 -SkipCompile
   ./scripts/deploy.ps1 -LegacyMigrationTarget 109
   ```

2. Finish or safely cancel every original task. Start with healthy, open process gates. Run:

   ```powershell
   node scripts/register-legacy-boundary.mjs
   ```

   The script checks the named Cutover context and pod, pauses intake and dispatch, and invokes the core's bounded registration process. It prints the verified count and evidence location. It leaves the gates paused. The private `.local/operations/legacy-boundary-session.json` retains the registration ID, exact control requests, completed steps and response for crash recovery. Do not edit that file or discard its evidence.

3. Apply the guarded task boundary, then reopen the same saved gates:

   ```powershell
   ./scripts/deploy.ps1 -SkipImageLoad
   node scripts/register-legacy-boundary.mjs release
   ```

   V112 verifies the current checkpoint before changing the trigger. The release step requires the new trigger to exist and the old trigger to be absent. It restores adapter dispatch before core intake/dispatch. Neither operation changes route ownership or epoch.

4. Run `node tools/scenario-driver/assignment-boundary-smoke.mjs` and inspect the retained original task IDs, registration counts, and a newly accepted order. The new order must begin with an intent and acquire a task from the durable adapter assignment. Verify one physical and inventory effect per movement. Run this check while both outbound routes are still legacy-owned, before the later business migration scenarios.

If registration fails or its response is lost, the gates remain paused and the script retains its failure code. Repair the actual missing evidence or unavailable connection, then rerun the same command. It resumes the saved registration instead of creating new physical work. A changed control version or unsettled task requires investigation; never bypass the DDL guard.

Before V112, `node scripts/register-legacy-boundary.mjs cancel` explicitly reopens the original gates while retaining the evidence. After V112, use `release`; this initial operation has no destructive down-migration. Numeric migration targets refuse to downgrade an already newer database.

The settled original baseline is the only scope of this operation. Use the business migration workflow for a later ownership change; it must drain all allocated work and show unknown or quarantined blockers while unrelated zones keep flowing.
