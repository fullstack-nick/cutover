# ADR 0032: confirm migration dispatch after commit

Date: 9 September 2026. Status: implemented; all 206 repository checks and deployed migration qualification passed.

The migration observation gate used `command_journal.created_at`. That value is written inside the adapter transaction. A row created promptly can still commit after the two-second deadline. The migration must not qualify such a sample from its early timestamp.

The gate now uses the simulator's retained `acceptedAt` as a conservative upper bound on the preceding adapter journal commit. `CommandJournal.work` reads committed rows on a separate connection; its send-attempt transaction also returns before `equipment.send`. The simulator records `acceptedAt` only after receiving that send. This proof follows the business protocol and does not require traces or another database commit. It includes scheduling and transport between adapter commit and simulator acceptance, so it can reject a sample even when the narrower adapter-commit target would pass. It cannot make that target easier to pass. It does not claim that the simulator's own commit happened at `acceptedAt`.

The existing physical and owner proofs remain mandatory: immutable movement/allocation/owner/epoch, completed owner task, consumed reservation, matching single inventory effect, completed simulator command and current physical world/generation/history. The same finite inventory is rechecked before the gate commits. Missing or malformed acceptance/completion timestamps keep the session observing. Acceptance must not precede eligibility or journal creation, completion must not precede acceptance, and completion must not be in the future. All local processes use UTC clocks on the same host; no test changes the host clock.

The original sample remains the first ten completed movements assigned to the new owner and epoch. Eligibility remains the later of original intent eligibility and assignment, retaining the established treatment of work held unassigned during a drain. All ten must satisfy the unchanged two-second bound. A missed sample stays immutable and requires an explicit reverse migration; no sample replacement or forced completion is introduced.

New observations add `timingBasis: SIMULATOR_ACCEPTANCE_UPPER_BOUND` and per-movement eligibility and confirmation timestamps. These fields are optional in the wire schema so retained N−1 samples remain readable. Earlier observations are not rewritten. The console labels samples without the recognized basis as lacking durable timing verification, and shows the new confirmation bound for current samples.

The real-database regression holds ten command rows in an uncommitted outer transaction, confirms their absence from a separate connection and from the simulator, advances only the injected clock by three seconds, then commits. All ten physical and inventory effects complete once. The old gate incorrectly qualified the early row timestamps; the corrected gate retains `OBSERVATION_LATENCY_TARGET_MISSED`. The complete migration workflow class passed all eleven checks after the correction, including durable phase crashes, concurrent supervisors, unknown outcomes and reversal lineage. Separate timing-proof guards and complete repository verification are recorded in the implementation ledger when finished.

This runtime correction is distinct from [A50's post-transaction trace verifier](0030-prove-dispatch-timing-after-commit.md). It does not change or qualify the failed sustained-load result.

The additional database check passed at **07:26:11 Europe/Berlin**: absent, malformed, past and contradictory future physical timestamps each kept the original session observing. Restoring its original valid timing proof completed that same sample with ten single effects. The console API generation, type check and production build passed. Original log SHA-256 values:

- migration-commit-timing-before: `7ed8295b11bca4dcb93141413622ad58c232a18dddbeac0d1066f130f8dcfb8d`
- migration-commit-timing-after: `68166d3cfd20373cfe18c1b580ee8b9603f232baa44aaff4e8330073af2dae6f`
- migration-timing-proof-guards: `1ad17a60c94ef3a4e39fd7f25175d2e41ca4eadbb6322b07e8c3a7390b505cd9`
- migration-timing-console-build: `6edbef1315a2cfc993c0064bc03847895ede8abc90e73ed4af9608c3e7b1d939`

Complete offline verification of `38dc3a1e5bd2eefeb7e45c743017835c9d994ec0` passed at **2026-09-09T07:39:11+02:00**: **206 checks in 25 classes**, zero failures/errors/skips, in **530 seconds**. Original log SHA-256: `c8a99dd407e3b9f4c867ec2f35e6a05937022012c3823d260e6403418369def1`. [Per-class evidence](../evidence/backend-checks.json) records the run. Deployed qualification remains separate.

The deployed `822d376` images passed explicit reversal (`migration-reversal-1788932786983`) and forward migration with five actual process exits (`migration-crash-1788932966281`). Reconciliation retained 1,335 and 1,345 inventory records respectively; each switch changed ownership once. Their original ten-movement confirmation bounds were **579.663 ms** and **657.299 ms**. Real PKCE console verification confirmed both new samples and the distinct, unchanged historical labels; screenshots were inspected. [Structured timing evidence](../evidence/migration-timing.json) preserves the run identities and original result hashes. This establishes these controlled migration scenarios; A50 remains failed.
