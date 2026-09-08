# Run Cutover locally

Use PowerShell 7 from the repository root. Initial acquisition requires internet access. A prepared runtime has a separate [offline procedure](../runbooks/prepared-offline.md).

## Prerequisites

- Windows with Docker Desktop's Linux engine available.
- Java 21 JDK, Node.js 24/npm, Git, kubectl and PowerShell 7 on PATH.
- At least 20 GiB of free workspace disk for initial acquisition. Keep additional room for image archives, checkpoints and retained evidence.
- Sufficient Docker VM memory: doctor checks a 10 GiB planning budget, counting Cutover's existing working set when it is already running. The measured development host has 32 GiB host RAM and a roughly 15.4 GiB Docker VM. Other workloads and host memory still affect performance.
- Loopback port 8780 for the console and identity; optional diagnostics use 8781–8785. The independent simulator uses 18784. Doctor and the scoped forward helpers report conflicts.

The Maven Wrapper provides pinned Maven. Bootstrap acquires the pinned project-local kind binary, infrastructure assets and browser. Exact versions, digests and provenance are in [the version lock](../../infra/versions.lock.json) and upstream notices. No global Kubernetes context change or registry account is required.

## First start

```powershell
git clone https://github.com/fullstack-nick/cutover.git
Set-Location cutover
./scripts/bootstrap.ps1
./scripts/demo.ps1 Status
```

Bootstrap builds local images, generates local credentials, creates the independent equipment world and the dedicated `kind-cutover` platform, runs owner migrations, and opens managed loopback forwards. First acquisition/build time depends on network and machine capacity. It is separate from the short reviewer walkthrough.

Open [the local console](http://localhost:8780). Sign in as `operator-a`; read its generated password locally from `.local/secrets/credentials.json` under `passwords.operator_a`. `supervisor-a` can investigate commands and migrate zones. `platform-admin` reads platform diagnostics; that role does not imply business recovery authority. These are fictional local accounts. Keep the credential file private.

The seeded site has 100 synthetic products, 10 stores, ambient/chilled outbound lanes and a separate returns lane. Both outbound zones begin with legacy ownership. Normal restarts preserve later ownership changes and consumed stock; they do not reseed inventory.

## A 10–15 minute reviewer path

This path starts with healthy, legacy-owned outbound zones, either after a fresh bootstrap or the documented supervised reversal. The [complete rehearsal](../evidence/reviewer-walkthrough-2026-09-08.md) passed all six commands in 4 minutes 49.5 seconds, followed by populated console captures. Allow additional time to inspect the views and architecture. Run commands sequentially and inspect their result directories when a check fails.

1. **See both products.** Run `node tools/scenario-driver/platform-smoke.mjs`, then `node tools/scenario-driver/returns-smoke.mjs`. In the console, open Orders and Returns; inspect the generated references and movement details. These drivers check real owner APIs, single physical/business effects, duplicate receipt handling and progress during an unrelated lane fault.
2. **Compare before switching.** Run `node tools/scenario-driver/shadow-smoke.mjs`. In Migrations, open a stored Shadow decision and inspect its input/hash and both proposals. The driver verifies 1,000 persisted comparisons and the actual shadow identity's command denial.
3. **Change one owner while work continues.** Run `node tools/scenario-driver/migration-smoke.mjs`. It uses real operator/supervisor browser sessions, holds an ambient lane drain, checks chilled work, migrates ambient to execution and records ten new-owner movements. Open Migrations as `supervisor-a` to inspect its inventory, checkpoint, owner and epoch. This driver requires ambient to begin legacy-owned; use the documented reversal for later demonstrations, never reset an epoch by SQL.
4. **Inspect uncertainty and its evidence.** Run `node tools/scenario-driver/work-console-smoke.mjs`. It verifies the recovery UI with a real unknown command, a conflicting stale version, a reviewed supervisor investigation, one physical/business effect and retained audit.
5. **Review the operating tradeoffs.** After the owner migration, run `node tools/scenario-driver/causal-trace-smoke.mjs` to join both product traces across the running owners. Open [Grafana](http://localhost:8783), then the [architecture](../architecture/overview.md), [restoration evidence](../evidence/restore-2026-09-08.md) and [implementation ledger](../planning/implementation-progress.md). Grafana's generated password is in the local credentials. Missing telemetry is not proof that business work failed.

The drivers add synthetic records and deliberately exercise the stated faults or ownership changes. They use authorized APIs for business actions and owner-specific observer credentials for assertions. Do not run them during a checkpoint, restore, load benchmark or another fault experiment.

The same six commands can be rehearsed with `node tools/scenario-driver/reviewer-walkthrough.mjs`. It checks the starting ownership, records each command's duration and result, and captures the populated current console. It refuses to qualify an incomplete or failed sequence. Each rehearsal retains 1,000 shadow comparisons; its explicit capacity check refuses a repeat without sufficient headroom. Existing evidence and task history are preserved.

For a prepared lab whose local image build is already verified, `./scripts/bootstrap.ps1 -SkipBuild` exercises platform creation with those recorded images. This is the bootstrap variant used by the checkpoint-backed fresh-dataset rehearsal. Initial acquisition and source building remain separate from the short reviewer path.

## Stop, resume and rebuild

```powershell
./scripts/demo.ps1 Stop
./scripts/demo.ps1 Start
```

Stop retains the named Cutover containers, volumes, world and route epochs. Start reconnects the independent simulator endpoint and waits for actual equipment observations. See [the lifecycle runbook](../runbooks/demo-lifecycle.md) for the separate deliberate reset command and its required checkpoint.

To verify and deploy changed source on the existing demo:

```powershell
./mvnw.cmd -B -ntp verify
./scripts/build-images.ps1 -SkipCompile
node scripts/update-simulator.mjs
./scripts/deploy.ps1
```

Deployment checks its explicit `kind-cutover` context, loads local images, retains the rendered diff privately, applies additive migrations and waits for rollout. Simulator update preserves its database and checks world/generation before the platform refreshes the endpoint. Do not deploy during an active migration, physical investigation or maintenance experiment.

## When a command stops

Inspect the named operation under `.local/operations/` and its raw evidence under `.local/evidence/`. These files can contain private configuration and must remain ignored. Repeated Start repairs its own managed forwards. An interrupted first bootstrap has a preparation journal and can resume; an existing physical world without the application node requires restoration, not an implicit new dataset.

Use [the operating guide](../operations/local-platform.md) to select a runbook. Never fix an evidence gap by deleting a command, replacing its identity, forcing route ownership, clearing a queue or editing stock.
