# Restore an application checkpoint

This procedure restores six application/identity databases into separate local storage and reconciles the independent simulator. It requires a checkpoint captured with adapter V117 and recovery-capable application images, all matching image archives in the local cache, and a running simulator with its original data volume. See [checkpoint capture](checkpoint.md) and [ADR 0014](../adr/0014-physical-history-aware-application-restoration.md).

The original Cutover demo node is stopped while the restored node runs. Other local projects are outside this procedure. Secrets, database dumps, Kubernetes manifests and raw evidence remain under ignored, access-restricted `.local/` directories. Do not publish them.

From the repository root in PowerShell, choose existing checkpoint and unique run names:

```powershell
.\scripts\restore.ps1 -Name <checkpoint-name> -Run <recovery-run-name>
```

The script verifies checksums, cached images and available network ranges before stopping the original node. It creates `cutover-restored` with `.local/restoration/kubeconfig`, initializes an empty broker, imports each database transactionally and verifies all table fingerprints. Only then does it install intake/dispatch holds, start identity and application writers, replay original checkpoint events and compare current physical evidence.

Read `.local/restoration/runs/<recovery-run-name>/journal.json`. `VERIFIED_HELD` means the imported checkpoint and current physical evidence agree, with intake and dispatch still closed. `QUARANTINED` means physical work or evidence could not be reconciled. The journal records elapsed time, replay counts, the checkpoint timestamp, observed physical high water and missing-context counts. A quarantined result is detection of a recovery gap; it is not complete business recovery. When scanning stops on a failure, the retained missing-context counts describe the evidence scanned so far, not a claimed exhaustive loss inventory.

For a verified result, release is a separate, versioned operation:

```powershell
.\scripts\restoration.ps1 -Run <recovery-run-name> -Action Release
.\scripts\restoration.ps1 -Run <recovery-run-name> -Action Open
```

Release checks replay settlement and current simulator evidence again, clears only the verified restore hold, resumes ordinary retention and restores the source's recorded control flags. It records the duration through release. `Open` connects the console and local dashboards on their usual loopback ports. It stops the matching demo forwards first; it does not stop unrelated processes or replace the default Kubernetes context.

If a known command completed after verification, release remains held. Extend the same session's evidence, then retry release:

```powershell
.\scripts\restoration.ps1 -Run <recovery-run-name> -Action Reconcile
.\scripts\restoration.ps1 -Run <recovery-run-name> -Action Release
```

Missing intent, a changed physical world, incomplete history or conflicting immutable payloads require preserving the checkpoint, source data and findings for investigation. Do not clear flags with SQL, invent replacement command IDs, acknowledge missing context away, or reset the simulator. The physical gate has no ignore-and-release action.

For a read-only restore experiment, stop the restored node and return to the preserved primary:

```powershell
.\scripts\restoration.ps1 -Run <recovery-run-name> -Action ResumeDemo
```

This freezes the restored writers and checks the exact node identities, the accepted order/receipt identities and the independent simulator command inventory. It refuses to resume a primary made stale by new accepted business work or commands from the restored environment. Keep that restored application storage if this happens.

To preserve and later resume the restored copy itself, use `-Action Stop` followed by `-Action Start`. Stop records the original control flags, freezes writers and retains the node and volumes. Start requires the primary to remain stopped and an unchanged simulator world and command inventory. It waits for ready processes from the new node start, then restores the recorded flags through versioned, idempotent control requests. A restore quarantine remains in force. Starting a node directly with Docker bypasses this lifecycle and is refused on subsequent Start attempts.

A later explicit `-Action Remove` removes only that separately restored cluster and owned network after the accepted-work and physical checks; source checkpoints and recovery evidence remain. Removing the cluster deletes its local application copy, so inspect the journal first.

A failure records its stage and leaves data and dispatch holds intact. The original application database volumes are not overwritten. Use the saved run's lifecycle command to resume the preserved demo when safe; inspect and explicitly remove a failed restoration environment before making a new uniquely named attempt. Do not delete the maintenance lock of a live process. Interrupted replay can repeat confirmed events safely because the event identities and inbox duplicate records are retained; the final checkpoint event list must still be fully confirmed before release.

Deployed restore acceptance and measured timings are recorded in the implementation progress ledger. A component test or successful dump import alone does not establish A46/A47.
