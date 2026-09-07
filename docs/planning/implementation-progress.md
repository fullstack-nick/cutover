# Cutover — implementation progress

Implementation goal started 7 September 2026. The implementation plan and all 54 acceptance scenarios remain the completion contract. Research is the baseline; runtime results below must come from executed checks.

| Phase | Status | Evidence / next work |
| --- | --- | --- |
| 0. Setup and compatibility | In progress | Cutover and MIT confirmed. Initialize Git, acquire checksum-verified tools, lock dependencies, and run the compatibility smoke gate. |
| 1. Walking legacy system | Pending | Build and characterize the database-heavy core, durable adapter, and independent simulator. |
| 2. Reproducible packaging | Pending | Disposable-database migrations/codegen, images, restart checks, and development profile. |
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
- The initial implementation check found approximately 1.2 GiB free host memory and running workloads from other projects. Permission to pause them was requested; no unrelated workload has been stopped.
- No acceptance scenario has passed yet. Application implementation is starting.

## Verification record

Record each milestone's command, result, tested commit, and evidence path here. Raw machine details and credentials belong under ignored `.local/` storage. A blocked or failed check remains visible until rerun successfully.
