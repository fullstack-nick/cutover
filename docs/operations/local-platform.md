# Operating the local platform

Run commands from the repository root in PowerShell 7. Start with the [quickstart and reviewer path](../onboarding/quickstart.md) for a new demo. A fresh bootstrap and preserved lifecycle have recorded runtime evidence; complete offline and reviewer qualification remain separate final gates.

Prerequisites: Java 21, Node 24/npm, Docker's Linux engine, Git and kubectl. `scripts/bootstrap-tools.ps1` supplies the checksummed project-local kind binary and Maven Wrapper supplies Maven. Use `scripts/doctor.ps1` to inspect capacity and ports before starting. Do not run the Compose application profile and kind application profile simultaneously against the same physical world.

```powershell
./scripts/bootstrap.ps1
./scripts/demo.ps1 Status
node tools/scenario-driver/platform-smoke.mjs
node tools/scenario-driver/network-policy-smoke.mjs
node tools/scenario-driver/messaging-smoke.mjs
```

Bootstrap creates the independent simulator and its database before rendering the demo. `deploy.ps1` renders secrets privately, runs owner migration Jobs, applies Kustomize output, waits for rollout, and starts the console forward. The ordinary deployment command does not restore or overwrite databases. Use `./mvnw.cmd -B -ntp verify`, then `./scripts/build-images.ps1 -SkipCompile`, `node scripts/update-simulator.mjs` and `./scripts/deploy.ps1` for a verified source update.

The execution application owns extracted outbound tasks. Its shadow deployment uses a separate restricted credential and `cutover_shadow` evidence database on the same application PostgreSQL server. `ensure-databases.mjs` verifies fixed owner identities, creates missing databases and preserves existing data/passwords. Both outbound routes start legacy-owned; a verified migration changes owner and epoch. Normal deployment and restart preserve those route decisions.

Use the [shadow comparison runbook](../runbooks/shadow-comparison.md) for the component and running-process comparison checks. Its synthetic scheduling endpoint is internal and scenario-only; comparison details are site-authorized reads through the normal console origin.

On this development host, the first platform deployment used `transfer-baseline.mjs`: application writers were stopped, five application databases were dumped with checksums, and the dumps were restored into empty target databases. The source volumes and independent simulator were preserved. `deploy.ps1 -TransferBaseline` is only for that explicit, captured development transfer; it refuses nonempty targets. It is not the final backup/restore implementation.

Open `http://localhost:8780` and sign in as `operator-a`. Its generated password is in the ignored `.local/secrets/credentials.json`, under `passwords.operator_a`. Never copy that file to a public issue or commit. Seeded human profiles contain fictional names and `.invalid` addresses; no real email service is involved.

The console includes overview, orders/shortages, tasks, equipment, command recovery, migration, shadow comparisons, returns and owner audit. It displays stale/error states with last-known records after a failed refresh. Page counters are explicitly limited to the displayed order page; reference/shortage search queries the site-wide register.

The messaging smoke waits for the retained source streams to apply in their receiving owners, submits an order, and checks inboxes, inventory and the physical ledger. `node tools/scenario-driver/messaging-smoke.mjs --broker-outage` also briefly scales only the labelled Cutover RabbitMQ StatefulSet to zero and restores it, preserving its PVC. It requires the legacy milestone's otherwise-settled data; do not run it during another fault experiment. Retry exhaustion remains visible and requires an audited supervisor replay after the underlying cause is corrected.

Process fault checks include `node tools/scenario-driver/process-crash-smoke.mjs` and `node tools/scenario-driver/equipment-recovery-smoke.mjs`. Run them sequentially on settled demonstration data. They deliberately halt/restart owned application processes or the simulator, preserve data, and retain raw evidence locally. Recovery checks use the pinned Playwright browser for real PKCE logins as fictional operator/supervisor accounts; passwords and tokens stay out of reports. `work-console-smoke.mjs` exercises the recovery screen, version conflict, reasoned investigation and exact physical/business effects.

These drivers start authenticated internal forwards through `forward.ps1 -Target core-api` (8784) or `-Target adapter-api` (8785). Starting a forward grants no API role. Only the scenario-driver client with test-control role can arm internal process faults; business recovery requires a supervisor instead. Both forwards remain loopback-only and can be stopped with `-Action Stop`. Ordinary console proxy paths never expose fault controls. See [the internal API](../../contracts/openapi/internal.v1.json) and [equipment protocol](../../contracts/openapi/simulator.v1.json).

After rebuilding the simulator image, `node scripts/update-simulator.mjs` applies its additive migrations and recreates only the owned simulator service. It verifies that world identity, journal generation and physical high-water were preserved. Follow it with `./scripts/deploy.ps1` to refresh the discovered EndpointSlice and policy. Application deployments alone do not update the independent simulator.

Optional diagnostics are manually started and loopback-only:

```powershell
./scripts/forward.ps1 Start -Target prometheus
./scripts/forward.ps1 Start -Target tempo
./scripts/forward.ps1 Start -Target grafana
node tools/scenario-driver/telemetry-smoke.mjs
```

Prometheus uses port 8781, Tempo 8782 and Grafana 8783. Grafana's generated administrator credential is local. The forward helper records and verifies its own process identity. Re-running it tests HTTP health and reconnects after the forwarded pod changes. Stop a diagnostic with the same command and `Stop` in place of `Start`; this closes only that recorded forward.

All Kubernetes commands use `.local/kubeconfig` and `kind-cutover`. `cluster.ps1 Status` reports the dedicated cluster. Use `./scripts/demo.ps1 Stop` and `Start` for the preserved lifecycle. Do not delete the cluster as a normal stop operation: its local-path volumes belong to the node. The [lifecycle/reset](../runbooks/demo-lifecycle.md), [checkpoint](../runbooks/checkpoint.md) and [separate restoration](../runbooks/restore.md) procedures have different guarantees and explicit resource checks.

Troubleshooting: inspect `.local/operations/` and `.local/processes/` for a failed build, migration, rollout or forward. These logs and rendered manifest diffs may include private configuration and remain ignored. A failed migration must be investigated before rerunning; do not bypass Flyway checksums or clear data to hide it. A changed simulator endpoint requires rerendering its EndpointSlice and policy. A changed simulator world requires business reconciliation, not merely endpoint repair.
