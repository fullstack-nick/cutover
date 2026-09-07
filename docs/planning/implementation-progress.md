# Cutover — implementation progress

Implementation goal started 7 September 2026. The implementation plan and all 54 acceptance scenarios remain the completion contract. Research is the baseline; runtime results below must come from executed checks.

| Phase | Status | Evidence / next work |
| --- | --- | --- |
| 0. Setup and compatibility | Initial compatibility verified | Git/public remote, checksums, backend, Kubernetes, identity, browser and telemetry smoke gates pass. Full clean/offline verification remains phase 8. |
| 1. Walking legacy system | Baseline verified | Stored routines, trigger-created tasks, polling scheduler, adapter journal and independent ledger pass component and real process checks. |
| 2. Reproducible packaging | Baseline verified | Clean Maven verification, reproducible generated SQL types, local images, JWT/mTLS workflow and process restart checks pass. |
| 3. Local platform | Initial milestone verified | Dedicated kind/Calico, frozen baseline transfer, 44 traffic checks, authenticated console, seven metric targets and retrievable traces. |
| 4. Reliability boundary | In progress | Transactional messages, bounded admission/retries, fault tests, and durable uncertainty handling. |
| 5. Extraction and shadow | Pending | Independent execution tasks and at least 1,000 identical-input comparisons. |
| 6. Cutover and rollback | Pending | Durable drain sessions, fencing, restart/reversal tests, and compatible image rollback. |
| 7. Second product | Pending | Scaffold and verify independent reusable-crate returns. |
| 8. Full operations verification | Pending | All acceptance scenarios, measured capacity, restore, and prepared offline operation. |
| 9. Portfolio finish | Pending | Console polish, documentation, reviewer walkthrough, evidence, and public repository. |

## Setup observations

- Existing Java 21, Node 24, Docker Linux engine, Git, GitHub CLI, and kubectl were found.
- kind and global Maven are absent. Install kind locally to the project tools cache; supply Maven Wrapper.
- The owner authorized pausing one unrelated local workload. Its 13 containers were stopped with volumes preserved; other workloads remain running. The Docker VM subsequently reported about 12.8 GiB available memory, including reclaimable cache. Host and VM free memory must be measured separately. The exact local workload permissions are retained in ignored operational notes.
- Git was initialized with baseline commit `90bcba5`. The public repository is [fullstack-nick/cutover](https://github.com/fullstack-nick/cutover); GitHub Actions is disabled. Only reviewed public source was pushed.
- Maven 3.9.16 and kind 0.33.0 were acquired with verified checksums. Maven Wrapper 3.3.4 pins the distribution SHA-256. Java dependency resolution and compilation succeeded.
- The runtime smoke tests pass PostgreSQL/Flyway/jOOQ generation and compilation, Boot HTTP JSON, JSON Schema formats, mandatory returns, and bounded quorum rejection. The initial capacity assumption was corrected after observed behavior; see ADR 0001.
- No full acceptance scenario is declared passed yet. Component evidence is not a substitute for the required final platform/failure/security/portfolio runs.

## Verification record

Record each milestone's command, result, tested commit, and evidence path here. Raw machine details and credentials belong under ignored `.local/` storage. A blocked or failed check remains visible until rerun successfully.

### Legacy component baseline — 7 September 2026

`./mvnw.cmd -B -ntp test` completed successfully at 19:19 Europe/Berlin in 1 minute 54 seconds: 3 compatibility checks, 5 simulator checks, 5 adapter checks, and 7 connected legacy workflow checks; no failures, errors, or skipped tests. Raw build output is retained locally. Each suite uses disposable PostgreSQL 18.6 databases; broker compatibility uses RabbitMQ 4.3.5.

The connected workflow tests use real owner databases, routines, constraints, task polling, command state machines, and physical ledgers. Their transport ports are deterministic in-process test adapters. Actual HTTP, JWT identity, mutual TLS, container restarts, and network-policy evidence are the next gates and are not implied by these results.

Corrections found by these checks included explicit timestamp casts in plain SQL, unambiguous bind-marker spacing around PostgreSQL operators, and using the injected clock for initial command deadlines. The tests retain the database assertions that exposed them.

### Reproducible process baseline — 7 September 2026

`./mvnw.cmd -B -ntp clean verify` passed all 20 tests with no failures, errors, or skips in 2 minutes 37 seconds at 20:02 Europe/Berlin. Every app generated its SQL types against disposable PostgreSQL using its actual Flyway migrations. Repeating generation and packaging produced identical SHA-256 hashes for all 98 generated Java files. Representative runtime queries now consume those generated types.

`./scripts/dev.ps1 -Action Start -SkipBuild` and `node tools/scenario-driver/baseline.mjs` passed on the rebuilt images. The six process checks establish real Keycloak authentication, HTTP intake/idempotency, service-to-service HTTP, simulator mutual TLS, site and signature rejection, authenticated runtime DDL denial (including Keycloak), one physical/inventory effect after a lost response, and blocked accepted work surviving restart of core, adapter and simulator. Evidence is recorded in ignored run `baseline-1788804441006`, with exact image IDs, jar hashes, and source revision `0ca4677` plus the then-uncommitted packaging changes. These results are development-profile milestone evidence, not final A01–A54 acceptance.

Startup tests found three configuration defects: an unquoted comma in a YAML tmpfs option, a Windows-reserved simulator port, and a missing `/identity` path in the advertised issuer. They also exposed Docker's internal-network-only port-publication behavior. All were corrected and the complete process suite rerun. See ADR 0002 and the development-baseline runbook.

### Local platform milestone — 7 September 2026

The dedicated kind 0.33.0 / Kubernetes 1.36.4 cluster uses Calico 3.32.2 with VXLAN, explicit policies and project-local image digests. Five frozen application databases were transferred from the development profile into empty cluster databases; the independent simulator and its physical history were preserved. Runtime role grants were reverified after restoration.

`platform-smoke.mjs` passed four checks in run `platform-1788806310326`: fresh simulator evidence through the in-cluster adapter, an authenticated ambient/chilled order with one physical and inventory effect per movement, site restriction, and runtime DDL denial. `network-policy-smoke.mjs` passed all 44 checks in run `policy-1788806688461`. `telemetry-smoke.mjs` passed three checks in run `telemetry-1788808276745`: seven expected scrape targets, an indexed/retrievable application trace, and Grafana database health. Evidence retains revision `579ff51` plus the uncommitted platform changes and exact image inventories.

Playwright CLI verified local PKCE login, real order/reservation detail, command-journal display, equipment navigation, Escape dismissal and a 390-pixel viewport without document overflow. The browser reported no console warnings/errors in the exercised flow; a storage-key check found no persistent token key. Screenshots are local development evidence, not final portfolio images or full A51 acceptance. The incomplete first-login human profile, narrow sidebar overflow, stale forward after rollout and loss of a requested view across identity redirection were found during this work and corrected in source.

The initial single-platform image import, Calico liveness and Tempo writable-path failures are recorded in ADR 0003 with the successful corrective configuration. The first telemetry assertion used the wrong Prometheus label; the corrected check follows the actual configured job/service labels. No full A01–A54 acceptance claim is made from these milestone checks. Extraction, migration, returns, recovery and the complete failure suite remain required.

### Durable messaging foundation — 7 September 2026

`./mvnw.cmd -B -ntp verify` passed 35 tests with no failures, errors or skips in 5 minutes 7 seconds at 21:47 Europe/Berlin. This includes 13 real PostgreSQL/RabbitMQ reliability checks and two additional owner-transaction checks. The latter force failure after inventory consumption or allocation registration and verify that those effects roll back with the inbox transaction before a successful retry.

The reliability checks cover commit/confirm/acknowledgement crash boundaries, single effects after duplicate transport delivery, mandatory returns, quorum rejection without dropping confirmed messages, stream gaps/conflicts, malformed/source/site quarantine, retry exhaustion, audited replay, capacity refusal and worker pause. Early runs caught plain-SQL bind parsing and JSONB casts; an idempotent response test exposed differing numeric node representations after storage. Those failures were corrected before the passing run.

The technical migration was moved to V101 so an existing V100 owner schema can upgrade in order. A focused clean run then passed 14 reliability checks, including a previous-schema upgrade that retains business rows. Clean packaging regenerated each application's SQL types and produced updated images. No original applied migration was edited.

The updated images deployed into the existing cluster without clearing application or physical data. `messaging-smoke.mjs` passed two process checks in run `messaging-1788811240256`: retained source streams drain into their receiving owners, and a new ambient/chilled order records applied intent/completion inboxes with exactly one physical and inventory effect per movement. This used source revision `354bb4e` plus the then-uncommitted messaging changes. Evidence now separately records actual running pod and simulator image identities, in addition to the build inventory.

Configuration-content hashes now trigger workload rollouts for mounted files and environment secrets. `messaging-smoke.mjs --broker-outage` then passed all three process checks in run `messaging-1788811360770`. RabbitMQ's pod was stopped with its volume preserved; an order was accepted with a nonempty durable outbox, and the source streams drained after the broker returned. Actual inventory/physical uniqueness assertions passed for the normal order in the same run. This small outage run does not replace the full capacity/exhaustion scenario. The remaining equipment/reconciliation/admission work is still in progress; phase 4 is not complete.

### Equipment evidence and process crash boundaries — 7 September 2026

`./mvnw.cmd -B -ntp verify` passed 45 checks in 3 minutes 34 seconds at 22:40 Europe/Berlin: 3 compatibility, 17 reliability, 7 simulator, 9 adapter and 9 connected core checks, with no failures, errors or skips. A preceding focused equipment run passed 16 checks. The first full run exposed an outdated assertion that assumed exactly one new migration after V100; the upgrade check now verifies the required V101/V102 versions and preserved business rows without forbidding later additive migrations.

The adapter now retains valid proof separately from stale/conflicting observations and records an acceptance tombstone. Tests prove executing work cannot regress on an old accepted observation, contradictory same-version contents are quarantined, known acceptance cannot be replaced by a later absence claim, and an audited investigation neither creates a new command ID nor reopens a terminal command. Simulator holds, delayed/repeated evidence and history/world response faults are durable, selectable, inspectable and clearable. See ADR 0005.

Rebuilt images and additive V102/V103/V104 migrations were applied to the existing environment. The independent simulator advanced from its earlier V100 schema with its world ID, journal generation and physical ledger preserved. `process-crash-smoke.mjs` passed three real pod-process checks in run `process-crash-1788814171314`: each deliberately fired the selected boundary, recorded exit code 73 and an increased restart count on the same pod, and finished accepted work with one physical/inventory effect. The confirm-before-mark and effect-before-ack cases observed repeated inbox delivery.

`equipment-recovery-smoke.mjs` passed six process checks in run `equipment-recovery-1788814389493`: lost response, failure before acceptance, simulator restart while executing, two incompatible-history investigations, and independent immutable-payload rejection by adapter and simulator. Real PKCE sessions checked operator denial, cross-site rejection, supervisor version/key semantics and one durable recovery audit. These runs used source revision `fd500dd` plus the recorded uncommitted changes and exact running image IDs. Their A-ID candidates are milestone evidence; the complete final acceptance bundle is still pending.

Continued operation exposed repeated Tempo OOM terminations under its initial 512 MiB limit. Business crash/recovery checks proceeded successfully during that monitoring failure. The local trace buffers and query concurrency were reduced, a 768 MiB Go soft limit was set inside a 1 GiB container limit, and only the owned failed Tempo pod was recreated with its PVC retained. The repaired process was observed at about 212 MiB working set and 169 MiB RSS, with no restart at that observation. `telemetry-smoke.mjs` passed all three checks in run `telemetry-1788814545498`. This short observation is not the required phase-8 capacity run or a guarantee about later memory peaks.

Remaining phase-4 work includes cancellation without unsafe reservation release, total storage/admission pressure, and complete quarantine/repair diagnostics. Extraction, shadow, migration, returns, stale restoration, prepared offline operation and all final A01–A54 evidence remain required. Compatibility of old writers with the new command-evidence columns must be included in the selected N−1 image gate; additive schema shape alone is insufficient.

### Cancellation fence and restartable reservation release — 7 September 2026

The focused owner verification passed 7 simulator, 10 adapter and 13 core checks at 23:21 Europe/Berlin. After adding recovery from a new browser session and the concurrent-supervisor case, `./mvnw.cmd -B -ntp -pl apps/legacy-core -am -Dtest=LegacyWorkflowTest -Dsurefire.failIfNoSpecifiedTests=false verify` passed all 15 core workflow checks at 23:32 with no failures, errors or skips. These checks use real owner PostgreSQL databases; controlled ports reproduce response loss and an expired query lease. The new tests establish all-or-nothing reservation release, late-intent fencing, no send from an expired worker after cancellation, retained recovery after a lost certificate response, retry-budget exhaustion and version-checked resumption by a different supervisor.

The public and internal contracts now describe cancellation and retry; the console generates its types from them and builds successfully. The deployed V105 adapter and V106 core migrations preserve existing data. `cancellation-smoke.mjs` passed all three process checks in run `cancellation-1788817068207`: the real PKCE supervisor UI cancels two unstarted lines with one release each, operator/cross-site requests are refused, a physically executing movement rejects cancellation and later completes once, and concurrent requests plus changed-payload key reuse cannot duplicate stock release. Evidence records source revision `5b0bbe9` plus the cancellation changes and the actual running image identities. The independent simulator retained its existing world and history.

See ADR 0006 and the order-cancellation runbook. The remaining phase-4 work is retained-storage pressure and complete quarantine/repair diagnostics. This milestone does not declare the complete A08 or A51 final acceptance bundle, or any later phase, finished.
