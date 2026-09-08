# Preserved demo lifecycle and deliberate dataset reset

`./scripts/demo.ps1 Stop` stops the four recorded Cutover demo containers: the kind node, equipment simulator, simulator database and its filesystem probe. It closes the named loopback forwards and retains container identities, volumes and the physical world. `./scripts/demo.ps1 Start` resumes those same containers, waits for fresh process readiness and restores the console and dashboards. Repeated Start/Stop commands are idempotent. `Status` is read-only.

Docker may assign a different equipment IP when preserved containers restart. Start verifies the owned network, refreshes the selectorless simulator EndpointSlice and only the adapter/equipment and Prometheus/equipment policy addresses with resource-version checks. It then waits for the adapter to observe that same physical world through the actual connection. Pod readiness alone is insufficient.

Stop can interrupt accepted work. Owner databases, leased outboxes, inbox identities and the independent physical journal recover it on Start. This is process persistence on the same disk. The separate [checkpoint](checkpoint.md) and [restoration](restore.md) procedures cover application backup and recovery.

## New local demo

With Java 21, Node.js/npm, Git, PowerShell 7, kubectl and Docker Desktop's Linux engine installed, run from the repository root:

```powershell
./scripts/bootstrap.ps1
./scripts/demo.ps1 Status
```

Bootstrap verifies/downloads pinned tools and assets, builds local application images, generates private credentials, creates the independent simulator and dedicated kind cluster, migrates the six owner databases and starts the console at `http://localhost:8780`. Grafana is at `http://localhost:8783`. Credentials remain in `.local/secrets/credentials.json`. The seed contains fictional sites, products and stores; outbound zones initially belong to the legacy scheduler. Ownership changes use the documented shadow/migration APIs.

`-SkipBuild` explicitly reuses already built images. A preparation journal permits retrying an interrupted first bootstrap. A previously completed demo uses Start; normal bootstrap never resets its data. A physical volume without a corresponding demo node is rejected unless it belongs to the recorded interrupted bootstrap: use restoration for an existing physical world.

## Explicit reset

Reset deliberately discards the current demo dataset. Finish accepted equipment work, clear synthetic faults, create a current quiescent application checkpoint, then stop the demo:

```powershell
./scripts/backup.ps1 -Name before-new-dataset
node scripts/check-checkpoint.mjs before-new-dataset
./scripts/demo.ps1 Stop
./scripts/reset.ps1 -Checkpoint before-new-dataset -DestroyCutover -NewWorld
./scripts/bootstrap.ps1 -SkipBuild
```

Both reset flags are required. Reset verifies checkpoint checksums, the recorded world/generation/high-water sequence, stopped container identities, volume creation identities, project labels and every volume user. It refuses a separate restoration copy or active older development profile. It briefly starts only the isolated simulator database, archives its immutable journal as a checksummed PostgreSQL dump, and stops it again before removing the exact recorded demo resources. No Docker prune, unrelated workload stop, default Kubernetes context or workspace-tree deletion is used. Dedicated empty networks and cached images remain for reuse.

The six application dumps remain under `.local/checkpoints/<name>`. The old physical journal and removal journal remain under `.local/resets/<name>`. These are private historical artifacts. Application restoration must always reconcile against its live physical world; the physical archive is not an instruction to rewind equipment. The next bootstrap creates a new world and generation, and reloads the original synthetic seed through migrations.

After an interrupted reset, inspect its journal and retry with the same checkpoint and flags. It refuses replaced containers/volumes and never applies an old completed reset to a newer demo. A failed lifecycle Start/Stop can be retried using its preserved journal. Do not delete an active maintenance lock; first inspect its owner process and recorded operation.

## Verification scope

`node tools/scenario-driver/demo-lifecycle-smoke.mjs` checks preserved identities, business totals, receipt idempotency, repeated commands and a real login after restart. The separate explicit reset driver verifies new-world creation and the fresh seeded bootstrap. These scripts record actual results under `.local/evidence`; their presence alone is not an acceptance pass.
