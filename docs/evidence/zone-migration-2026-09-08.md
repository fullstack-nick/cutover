# Selected zone migration evidence — 8 September 2026

These are implementation-stage process checks on the retained kind demo, not the final A01–A54 acceptance bundle. Source revision was `331d021` plus the migration changes. Core V112, adapter V114 and execution V113 ran against separate owner databases. The independent simulator world and its history were retained.

| Check | Observed result |
| --- | --- |
| Ambient migration, run `zone-migration-1788830207094` | A real supervisor used the console to start session `f46628d7-f66d-47be-bd16-b1c040ae90b7`. A blocked legacy allocation prevented the switch, new ambient work stayed unassigned, and chilled work completed. All 24 retained inventory items reconciled; execution received the pending work at epoch 1. The ten-movement sample p99 was 592.983 ms. |
| Role/site controls in the same run | The operator UI exposed no migration mutation, operator creation returned 403, another site returned 404, and a human supervisor could not use the internal process-fault controls. Repeating the start returned the original session; changing its payload under the same key returned 409. |
| Former-owner check, run `migration-stale-owner-1788830496836` | Actual old-owner credentials retrieved the original completed command without repeating it and received `STALE_OWNER` for a new-epoch allocation. A lane hold kept that new allocation undispatched during the refusal. The current owner subsequently produced one inventory and physical effect. |
| Concurrent reversal, run `migration-reversal-1788831400131` | Two different supervisor identities submitted the same chilled route version concurrently. One returned 202 and the other 409; one session committed. |
| Unknown outcome in the reversal | A real accepted command lost its response, then incompatible history evidence quarantined it. The exact movement blocked the drain beyond the transport deadline. A supervisor cleared the selected fault and investigated the original command. Its one accepted physical identity was retained. |
| Completed business reversal | Session `bca36197-f60a-4429-9b0d-b50ad39c928b` switched chilled ownership back to legacy at epoch 2 after reconciling 26 movements. Pending work received a legacy task once; old execution work retained its original owner. Ten new-owner movements had a sample p99 of 297.346 ms. The failed parent observation became `REVERSED`, with its original evidence preserved. |

The first ambient run's later stale-owner assertion failed because its test held adapter dispatch, which also held allocation. The already successful migration was retained, the gate reopened, and the corrected lane-based check ran separately. This is a harness failure, not evidence of a failed ambient ownership switch.

The first actual crash sequence, `migration-crash-1788830550948`, reached the four pre-completion phases with persisted one-shot exit records. Its sample included a 2,981.393 ms dispatch, so the coordinator refused completion. A Maven database check overlapped that run; it did not explain a subsequent isolated 2,784.272 ms miss. Both immutable failed samples remain in their sessions. They were not replaced with later faster traffic.

The repeated crash harness also found a readiness race: immediately after a process halt, a stale Kubernetes status could still report the previous process as ready. The check now waits for the expected restart count, a container start time after the fault, and successful API readback. It excludes a previous run's termination record. A bounded resume option continues the same persisted session after a harness transport failure and writes a separate evidence artifact; it refuses a failed latency sample, which requires reversal.

Investigation traced most of the slow task's delay to assignment publication/delivery. The broker had 25,763 throttled periods out of 97,756 observed periods, while its five-second readiness probe repeatedly invoked `rabbitmq-diagnostics`. The reviewed manifest now uses an AMQP TCP probe. RabbitMQ documents the Erlang distribution overhead of repeated CLI probes and [recommends this TCP readiness approach](https://www.rabbitmq.com/docs/monitoring#health-checks-as-readiness-probes). The named broker was rolled out with its original PVC and queue data.

After that change, `dispatch-latency-1788832405721` admitted 30 two-line orders at one order/second and verified all 60 physical/inventory effects. Assignment/eligibility-to-dispatch p99 was **426.170 ms**; eligibility-to-dispatch p99 was **598.500 ms**. Over 31.630 seconds the broker consumed 2.527 CPU seconds and had **zero throttled periods out of 319**. This diagnostic supports the probe correction; it is shorter and lighter than the required ten-minute fulfilment-plus-returns acceptance workload.

A later reversal, session `baac8925-b728-49c4-adf0-14e2beb17043`, still missed the bound at **2,420.441 ms**. The probe correction was therefore insufficient. Its original failed observation remains retained. Traces from that interval show slow broker confirmations and transaction boundaries across independent services; the sampled garbage-collection pauses were too short to explain the full delay.

A subsequent idle observation measured **2,593 WAL fsyncs and 8,558.992 ms of cumulative client-backend fsync wait** across five snapshots spaced five seconds apart, without submitted business traffic. Empty worker polls acquired row locks on control and inbox quota rows. PostgreSQL documents that [row locking can require disk writes](https://www.postgresql.org/docs/18/explicit-locking.html); PostgreSQL 18 records WAL timings in [`pg_stat_io`](https://www.postgresql.org/docs/18/monitoring-stats.html#MONITORING-PG-STAT-IO-VIEW) when `track_wal_io_timing` is enabled. Read-only empty checks now precede these write transactions, while actual mutations retain the same locking, synchronous commit and fsync requirements. Verification of that change is in progress.

The full verification subsequently passed 105 checks. After rollout, diagnostic `wal-idle-1788834542817` recorded **21 WAL fsyncs over 20.809 seconds** and **66.143 ms** cumulative fsync wait, with synchronous commit and fsync enabled. See [ADR 0011](../adr/0011-empty-poll-durability-cost.md) for the guarded read-only fast path and its limits.

Running application image manifests:

| Application | Manifest digest |
| --- | --- |
| Core | `sha256:2e2a49b6fae1dca5493513fd26f4989660a21fc055843f1dd06312331ce9375c` |
| Adapter | `sha256:8e6865016234d5872566c35c87f3b7b7b74c00b6e95d957801d40f2827ab3bc2` |
| Execution and shadow | `sha256:9ccfd28d10be97d1840220916c02dbe57f1f247cc27ed1fc3adad4acb99a19b2` |
| Console | `sha256:a884ea2ee58c5dc37c86bcae139239c08ee975d744231dd469be2adcf94a2c36` |

The subsequent verified images used adapter V115. The adapter manifest was `sha256:c68168638df53aacd973280656fcab7e175fd0363a1a7e87470fa6abe93b80ea`, core was `sha256:ab96993621fa6232a9239516e54ac4a7abdaa1dd828e301c909cd10b078dd6af`, and execution/shadow was `sha256:adc74d3d4a96a6aa8217851a1f3fe368d3d7c128af448e7329fae735d323d63f`.

| Subsequent check | Observed result |
| --- | --- |
| All five process phases, `migration-crash-1788834766024-resume-1788835287007` | Session `e908b60b-42e9-4669-a16a-98fdd93219a9` retained 87 verified inventory items and switched chilled ownership to execution at epoch 5. Five distinct exit-73 records and a restart-count increase from 0 to 5 occurred on the same adapter pod. The session completed with ten single physical/inventory effects and dispatch p99 **552.919 ms**. Checkpoint hash: `ca177b5a1278e1c9e055af0389ed50a3d820c52efa94b611c1b09a629896f503`. |
| Resumed failure and lineage | The original run stopped when the simulator's omitted shared storage migration prevented command admission. That failure produced no physical command. After the actual schema repair, run `migration-simulator-recovery-1788835267551` used a supervisor investigation and real absence proof to complete the same command once. The original migration and four recorded phase exits were then resumed. Its immediate failed parent became `REVERSED`; the older failed reversal became `SUPERSEDED`. Their latency samples were preserved. |
| Compatible application image, `adapter-image-rollback-1788835517276` | The cached phase-5 adapter manifest `sha256:b78a4c29a06b42f76c6bedd22929cd0bb5f4544d9d40aed6434d39567e33b260` ran against expanded V115 after all sessions settled. Three two-line orders produced six unique physical/inventory effects with non-null compatibility assignment timestamps. All route owners, epochs and versions and the schema version remained unchanged. The current adapter image and open dispatch gate were restored. |
| Migration UI, `migration-ui-1788835719573` | Real supervisor and operator PKCE sessions verified the current completed migration, the preserved 2,784.272 ms superseded sample, expandable checkpoint details and no operator mutation controls. Screenshots were inspected at 1440 and 390 pixels; the narrow layout had no document overflow. |

The image verifier's first preflight run incorrectly expected its control-mutation response to contain the full current state. Dispatch was reopened, the verifier was changed to read the actual control state, and the corrected run passed. The failed preflight and explicit gate cleanup are retained. The phase-crash verifier also now identifies a failed sample from its recorded observation, since a later transport error can legitimately replace the latest-error field.

These passes establish the selected implementation-stage migration/image checks. Final restore, capacity, offline and complete acceptance gates remain required. Raw local artifacts contain full proof pages, IDs, timestamps, images, fault records and failed attempts; they are excluded from Git.
