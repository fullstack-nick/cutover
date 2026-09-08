# Prepared local operation

The offline boundary covers the prepared local runtime, restart and cached application rollback. Initial dependency acquisition is online. A source rebuild using warmed package caches is a separate check. Neither establishes installation on a machine that has never connected to the internet.

## Cache inventory

After the local build, verification and browser acquisition, record the installed files and images. Select a successful deployed evidence run containing the predecessor adapter image:

```powershell
node scripts/cache.mjs Record --rollback-evidence=<passed-run-id>
node scripts/cache.mjs Check
```

The private inventory records SHA-256/SHA-512 file hashes, pinned browser revisions, npm tarballs, the available Maven dependency/plugin repository and wrapper distribution, tools, network manifests, the Java agent, current image archives and a tested predecessor archive. Docker image IDs and node runtime digests remain distinct. The Maven inventory can contain extra dependencies used by other local builds; it is an inventory, not a minimal dependency claim. No cache files or credentials belong in Git.

The offline rehearsal verifies and imports the recorded predecessor archive into the owned kind node while external-egress denial is active, then selects its exact runtime digest for rollback. It does not require that a fresh node already contain the predecessor. `node scripts/load-cached-rollback.mjs` performs the same bounded import independently; it checks the archive hash, platform, manifest digest, project alias and target-node ownership before loading.

`Check` fails when a recorded file, checksum, image ID or dependency input differs. It does not download replacements. After deliberate dependency changes, acquire and verify them, then record a new inventory. Re-record after building new application images.

Cold-cache verification can use an explicitly empty directory within `.local/verification`:

```powershell
New-Item -ItemType Directory .local/verification/my-empty-cache
node scripts/cache.mjs Check --empty-fixture=.local/verification/my-empty-cache
```

The second command must return exit code 1 and list missing assets. It reroots all file checks into that fixture, preserving the real caches and Docker daemon images. The complete private report identifies package names, image archive digests and browser revisions. This is a cold **file-cache fixture**, not a reset of Docker Desktop.

## Scoped runtime denial

For the complete automated rehearsal, start the prepared demo with a verified cache and no active denial, then run:

```powershell
node tools/scenario-driver/offline-smoke.mjs
```

This command manages Enable/Probe/Disable, both product workflows, lost-response recovery, simulator and whole-lab restarts, the cached predecessor import/rollback, and actual browser/dashboard checks. It retains private evidence and attempts to remove its exact firewall rules in cleanup. The commands below expose the individual network controls for inspection or recovery; do not manually enable denial before starting the automated rehearsal.

Start the prepared demo first. These commands inspect the exact Cutover containers and their three dedicated Docker networks before changing rules:

```powershell
node scripts/offline-egress.mjs Enable
node scripts/offline-egress.mjs Probe
node scripts/offline-egress.mjs Status
# Run the offline business, browser, restart, rollback and dashboard checks.
node scripts/offline-egress.mjs Disable
```

A temporary pinned helper has only `NET_ADMIN`, no host filesystem/PID mount and no privileged flag. It installs a reserved, journalled firewall chain reached only from the three verified Cutover bridges. The configured Cutover Docker, pod and service address ranges remain reachable. Other IPv4 destinations are rejected before Docker's existing forwarding rules. A network with an unrelated container or IPv6 enabled is rejected by preflight. No machine-wide network switch or unrelated container stop is used.

`Probe` requires failed external TCP connections from both kind and the simulator plus a nonzero actual reject counter. It separately tests any configured HTTP(S) proxy in either container, recording absent configuration explicitly; a successful connection or TLS-only failure cannot prove isolation. The journal in `.local/offline/egress.json` records network identities, exact rules and counters. A failed or interrupted walkthrough leaves this journal available: inspect `Status` and run `Disable` to remove only its exact tagged jumps and reserved chain. Do not flush global firewall tables.

Passing `Check` and `Probe` alone does not qualify A48. That requires actual local login, both products, recovery, restart, compatible cached rollback and dashboard evidence while denial is active. Browser checks must also deny external destinations in their own isolated browser context; the Windows browser is outside the Docker bridge boundary.

The automated walkthrough also exercises the whole-lab lifecycle with `node scripts/demo.mjs Stop --preserve-offline-denial` and the corresponding `Start` command. This explicit mode requires an ENABLED journal and unchanged identities for all three isolated containers and dedicated bridges; it also rejects a foreign network member. It preserves the installed rules. The walkthrough probes actual external rejection again after restart and uses a fresh browser context with its own external-request denial for the restarted login.

## Separate cached source build

With the warmed inventory available, Maven's `-o` and npm's `--offline` make missing packages fail explicitly:

```powershell
.\mvnw.cmd -B -ntp -o verify
Push-Location apps/operations-console
npm ci --offline
npm run build
Pop-Location
```

Run one heavy verification profile at a time. Package-cache flags are evidence about package resolution, not a claim that the Windows host is disconnected. Keep source-build, prepared-runtime and cold-fixture results separate in the evidence bundle.
