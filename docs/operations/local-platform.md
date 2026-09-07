# Local platform milestone

Run commands from the repository root in PowerShell. This is the current development platform procedure; the final clean-bootstrap and offline walkthrough remain under development.

Prerequisites: Java 21, Node 24/npm, Docker's Linux engine, Git and kubectl. `scripts/bootstrap-tools.ps1` supplies the checksummed project-local kind binary and Maven Wrapper supplies Maven. Use `scripts/doctor.ps1` to inspect capacity and ports before starting. Do not run the Compose application profile and kind application profile simultaneously against the same physical world.

```powershell
./scripts/bootstrap-tools.ps1
./mvnw.cmd -B -ntp clean verify
./scripts/build-images.ps1
node scripts/bootstrap-assets.mjs
./scripts/cluster.ps1 Create
./scripts/deploy.ps1
node tools/scenario-driver/platform-smoke.mjs
node tools/scenario-driver/network-policy-smoke.mjs
node tools/scenario-driver/messaging-smoke.mjs
```

The independently running simulator and its database are required before rendering the demo. The development baseline guide describes their Compose profile. `deploy.ps1` renders secrets privately, runs owner migration Jobs, applies Kustomize output, waits for rollout, and starts the console forward. The ordinary deployment command does not restore or overwrite databases.

On this development host, the first platform deployment used `transfer-baseline.mjs`: application writers were stopped, five application databases were dumped with checksums, and the dumps were restored into empty target databases. The source volumes and independent simulator were preserved. `deploy.ps1 -TransferBaseline` is only for that explicit, captured development transfer; it refuses nonempty targets. It is not the final backup/restore implementation.

Open `http://localhost:8780` and sign in as `operator-a`. Its generated password is in the ignored `.local/secrets/credentials.json`, under `passwords.operator_a`. Never copy that file to a public issue or commit. Seeded human profiles contain fictional names and `.invalid` addresses; no real email service is involved.

The current console shows overview, orders, reservation detail, command evidence and equipment. Supervisor migration/reconciliation and the returns product are later implementation phases. Page counters are explicitly limited to the displayed order page.

The messaging smoke waits for the retained source streams to apply in their receiving owners, submits an order, and checks inboxes, inventory and the physical ledger. `node tools/scenario-driver/messaging-smoke.mjs --broker-outage` also briefly scales only the labelled Cutover RabbitMQ StatefulSet to zero and restores it, preserving its PVC. It requires the legacy milestone's otherwise-settled data; do not run it during another fault experiment. Retry exhaustion remains visible and requires an audited supervisor replay after the underlying cause is corrected.

The next fault checks are `node tools/scenario-driver/process-crash-smoke.mjs` and `node tools/scenario-driver/equipment-recovery-smoke.mjs`. Run them sequentially on settled demonstration data. They deliberately halt/restart owned application processes or the simulator, preserve data, and retain raw evidence locally. The recovery check uses the pinned Playwright browser for real PKCE logins as fictional operator/supervisor accounts; passwords and tokens stay out of reports. It verifies direct API denial and audited recovery, although the recovery console screen is still pending.

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

All Kubernetes commands use `.local/kubeconfig` and `kind-cutover`. `cluster.ps1 Status` reports the dedicated cluster. Do not delete the cluster as a normal stop operation: its local-path volumes belong to the node. Full stop/reset and recovery procedures will be verified separately before release.

Troubleshooting: inspect `.local/operations/` and `.local/processes/` for a failed build, migration, rollout or forward. These logs and rendered manifest diffs may include private configuration and remain ignored. A failed migration must be investigated before rerunning; do not bypass Flyway checksums or clear data to hide it. A changed simulator endpoint requires rerendering its EndpointSlice and policy. A changed simulator world requires business reconciliation, not merely endpoint repair.
