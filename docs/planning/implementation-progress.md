# Cutover — implementation progress

Implementation goal started 7 September 2026. The implementation plan and all 54 acceptance scenarios remain the completion contract. Research is the baseline; runtime results below must come from executed checks.

| Phase | Status | Evidence / next work |
| --- | --- | --- |
| 0. Setup and compatibility | In progress | Cutover and MIT confirmed. Initialize Git, acquire checksum-verified tools, lock dependencies, and run the compatibility smoke gate. |
| 1. Walking legacy system | Component checks pass; process demo next | Stored reservation/priority routines, trigger-created tasks, polling coordinator, adapter gate/journal, and independent physical ledger. Twenty tests pass; process/network verification is still required. |
| 2. Reproducible packaging | In progress | Build executable jars/images and scoped development infrastructure; verify actual authenticated HTTP and mutual TLS. |
| 3. Local platform | Pending | kind/Calico, identity, policies, persistence, console proxy, and telemetry. |
| 4. Reliability boundary | Pending | Transactional messages, bounded admission/retries, fault tests, and durable uncertainty handling. |
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
