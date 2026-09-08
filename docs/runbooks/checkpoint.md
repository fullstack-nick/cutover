# Application checkpoint

This local maintenance procedure pauses intake and dispatch, settles published/received messages, freezes all five application owners, then stops those writers and Keycloak while exporting six databases. The console remains available; business and identity APIs are temporarily unavailable. The separate simulator keeps its own database and can finish an already accepted command.

The [8 September deployed verification](../evidence/checkpoint-2026-09-08.md) passed complete capture, corruption rejection and interrupted-process recovery. This procedure alone does not establish fresh-cluster restoration, stale-checkpoint reconciliation, or the fifteen-minute restoration target.

## Capture and verify

Start with the complete demo healthy. Finish active migration Jobs, clear unused one-shot process faults, and resolve pending delivery retries. Durable quarantines are retained and counted; they are not silently deleted to create a clean-looking checkpoint. Existing intake/dispatch pauses are saved and restored. Other paused workers, broker consumers/publishers, or critical-storage controls must be investigated first.

```powershell
./scripts/backup.ps1 -Name reviewer-checkpoint
node scripts/check-checkpoint.mjs reviewer-checkpoint
```

Names are immutable. The scripts use only `.local/kubeconfig`, context `kind-cutover`, and labelled Cutover resources. They reject an active maintenance record and never select the user's default context. Temporary loopback forwards belong to the calling script and close afterward.

Each private checkpoint records the source revision and actual container identities, schema checksums, routing epochs, outstanding commands, source outbox replay identities, simulator world/generation and journal positions at the start/freeze/end. Every database table has a count and sorted row-content SHA-256 before and after capture. All databases are checked again after the final dump; sequential dumps must describe the same stopped-writer interval. The PostgreSQL and RabbitMQ volumes stay mounted, and normal cleanup restores the observed controls and writer replicas.

The administrator-owned `cutover_ops` schema contains only a fixed filesystem-observation function and is excluded from business dumps. It is provisioned on the destination; its transient free-space report must describe the destination's current filesystem. Application table fingerprints cover the owner-managed `public` schema.

`manifest.json`, `manifest.sha256`, six PostgreSQL custom-format dumps, scoped resource manifests, image inventory and required local credentials stay under `.local/checkpoints/<name>`. Windows permissions remove inherited access and grant the current account and SYSTEM; other platforms use a private directory. These artifacts contain credentials and business fixtures. Keep them out of Git, screenshots and public evidence. A same-host checkpoint does not protect against loss of the host disk.

## Interrupted capture

Inspect `recovery-journal.json` in that checkpoint directory. It records each planned control command before sending it and each planned writer stop before scaling. A transport retry uses the original idempotency key and request; uncertainty never authorizes a new physical command.

```powershell
node scripts/resume-checkpoint.mjs reviewer-checkpoint
```

This recovery command refuses a maintenance process that still exists. It can remove only its own stopped process's matching lock, restart the named writer deployments, resolve recorded control requests with their original keys, and restore the saved control flags with version checks. A changed version requires inspection of the current audit; the script does not overwrite another operator's change. It does not change a failed capture into a successful one. Run the checksum verifier before selecting any checkpoint for restoration.

## Recovery boundary

Fresh and stale restoration must use separate empty application storage and a recreated broker. Retained published events must be replayed with their original identities; `published_at` in the dump is not evidence that a new broker holds them. Start with dispatch disabled and reconcile against the live simulator before reopening it. Never restore or reset the simulator to match the application checkpoint. Later physical effects with missing business intent require a recorded quarantine and an explicit data-loss window.
