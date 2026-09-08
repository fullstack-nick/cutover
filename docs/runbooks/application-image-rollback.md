# Verify a compatible adapter image rollback

Application rollback changes the process image. Business reversal changes a route's task owner and epoch. Use the migration runbook for a business reversal.

The phase-5 adapter predates the migration worker. Its supported rollback window requires every migration session to be settled, all current allocations terminal, and the additive V115 assignment-timestamp compatibility trigger installed. It is not a supported image while a session still requires work. Retained failures and their reversal lineage must remain visible in the current console before selecting this window.

Keep the current and previous immutable application images in the owned kind node's cache. Record their runtime manifest digests in the local image inventory. A tag alone is insufficient. The current deployment uses `imagePullPolicy: Never`; restoring a cached process does not require a registry.

Run the explicit verifier with the recorded predecessor digest:

```powershell
node tools/scenario-driver/adapter-image-rollback-smoke.mjs --image=docker.io/cutover/equipment-adapter@sha256:<recorded-digest>
```

The verifier checks ownership of the named node and deployment, verifies both images are cached, closes dispatch and rechecks the settled-session boundary. It records every site's route owner, epoch and version. It then runs the older adapter on the expanded schema, opens dispatch and submits three two-line synthetic orders. Each movement must complete once in the physical and inventory ledgers, retain the route's existing owner/epoch and receive its compatibility assignment timestamp.

Finally, the verifier restores the original image and dispatch gate and checks the unchanged routes and schema version. It never runs a down-migration, edits an allocation, resets the simulator or transfers task ownership. Raw evidence records the actual predecessor and restored pod/image identities.

If preflight fails, resolve the named missing cache entry or unsettled work before rollback. If an application process fails after the change, restore the saved current digest and inspect its original journal. Do not assign a fresh physical identity or infer absence from a transport error. The verifier attempts image/gate restoration after a failure; if that cleanup reports failure, inspect the exact saved image and versioned control state before continuing.

This verifier establishes only the selected image/schema/contract combination. A later release must execute its own N−1 gate; additive columns alone are not proof of compatible application behavior.
