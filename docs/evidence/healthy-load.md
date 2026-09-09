# Sustained healthy-load evidence

**A50 failed** in `load-1788930132552`, ending 2026-09-09T05:16:00.906Z. All 3,000 measured movements were included: **93.967% reached post-transaction adapter acceptance within two seconds of original eligibility**, with **p99 6,936.000 ms**. All 3,300 total physical and business effects, including warm-up, completed once. The required 99% threshold remains unmet; this report does not close A50.

The runtime images were built from `dd130962537e2a02f32a9164d3f4e31f1a0950af`; the driver source was `da6027a7afe28e306a4f8885d7a2655ce8885f9e`. [Structured measurements and raw-evidence hashes](healthy-load.json) retain this distinction. [The runtime inventory](runtime-runs.json) identifies the application images and running containers. Raw requests, movement identities, effects and resource samples remain private.

The [declared tracing profile](../adr/0027-domain-and-transport-tracing-profile.md) retains business and transport spans and suppresses automatic JDBC spans. The structured evidence records all five actual owner/shadow pod identities and an explicit non-secret setting allowlist, unchanged before and after offering. No sampling or latency-denominator change was made.



## Workload and denominator

The manually invoked command is `node tools/scenario-driver/healthy-load.mjs`. It offers two orders per second, each with two reserved lines, plus one two-crate receipt per second with one nonzero sorting classification rotating through reusable, needs-cleaning and damaged. The seed is 20260908. A 60-second warm-up precedes the entire 600-second measurement, producing 1,980 HTTP requests and 3,300 movements overall. All HTTP requests returned 202. The driver limits in-flight offers to 32 and retains actual offer lateness.

Original movement eligibility is the start timestamp; the endpoint is post-transaction adapter acceptance. Every movement has its own successful original-trace adapter command-journal span ending after the HTTP journal transaction returns. The [post-commit verifier](../adr/0030-prove-dispatch-timing-after-commit.md) rounds span ends up and original eligibility down to milliseconds. Missing coverage fails qualification. Earlier row-timestamp diagnostics remain separate. The task-created stage is reported separately and is not substituted for that original timestamp. There were **zero blocked, draining or unknown exclusions**. Completed work, failed attempts and eventual effects are checked against the separate owners and the simulator's retained journal. Retained historical failures used earlier pre-commit timestamps unless explicitly labelled otherwise.

| Measurement | Result |
| --- | ---: |
| Original eligibility → recorded dispatch, p99 | 6,936.000 ms |
| Percentage within two seconds | 93.967% |
| Task creation → recorded dispatch, p99 | 3,834.000 ms |
| HTTP acceptance, p99 | 589.00 ms |
| Offer lateness, p99 | 19.00 ms |
| Original eligibility → physical completion, p99 | 9,281.00 ms |
| Measured movements eventually completed once | 3000 / 3,000 |
| Measured movements completed before the offering window ended | 2997 / 3,000 |
| Eventual completions per offered measurement second | 5.00 |

## Measured resources

The Windows host is 13th Gen Intel(R) Core(TM) i9-13900H, 20 logical CPUs, with 31.63 GiB physical memory. The Docker Linux VM has 15.43 GiB. Existing local workloads continued to share CPU and storage. This run collected 67 nominal ten-second samples with no sampling errors; it is a local measurement rather than a production capacity guarantee.

| Environment | Maximum sampled CPU | Minimum available memory |
| --- | ---: | ---: |
| Windows host | 36.75% | 1.98 GiB |
| Docker Linux VM | 16.69% | 7.69 GiB |

| Owned container | Maximum sampled memory | Maximum sampled CPU |
| --- | ---: | ---: |
| cutover-control-plane | 5.58 GiB | 255.75% |
| cutover-dev-equipment-simulator-1 | 0.31 GiB | 48.09% |
| cutover-dev-simulator-db-1 | 0.07 GiB | 11.75% |
| cutover-dev-equipment-volume-probe-1 | 0.00 GiB | 2.38% |

Docker CPU percentages use 100% per CPU, while host and VM percentages describe their whole sampled environment. Container memory values are approximate because Docker rounds its displayed strings. Samples can miss shorter peaks.

| Owner | Peak unpublished events | Oldest sampled outbox event | Peak pending inbox | Oldest sampled inbox event |
| --- | ---: | ---: | ---: | ---: |
| core | 29 | 4.238 s | 0 | 0.000 s |
| adapter | 13 | 3.378 s | 0 | 0.000 s |
| execution | 0 | 0.000 s | 0 | 0.000 s |
| returns | 2 | 1.050 s | 0 | 0.000 s |
| shadow | 0 | 0.000 s | 0 | 0.000 s |

Database `fsync` and `synchronous_commit` were both `on` / `on`; no durability setting was relaxed for the run. The structured report includes WAL counters and database CPU/throttling deltas.

The runtime used its default `commit_delay=0`, `commit_siblings=5` and `wal_sync_method=fdatasync`. Both original offering snapshots contain the same settings. No temporary database tuning was applied. The structured report retains the actual WAL write and explicit flush counters, zero-deadlock deltas from all six databases, and the server-log hash. All transactions still wait for durable WAL before a successful commit.

## Retained failures and limits

Selected earlier failed full qualifications remain part of the evidence; the implementation ledger retains the other attempts:

| Run | Image source | Within two seconds | Original-eligibility p99 | Result |
| --- | --- | ---: | ---: | --- |
| load-1788880017964 | 968cad5 | 85.900% | 8,340.798 ms | Failed |
| load-1788893862792 | c6486a6 | 6.100% | 156,213.575 ms | Failed |
| load-1788902904030 | b3b09db | 91.533% | 10,616.729 ms | Failed |
| load-1788906898401 | b3b09db | 91.733% | 11,988.404 ms | Failed |
| load-1788913804136 | c56cff9 | 88.200% | 11,551.883 ms | Failed |
| load-1788914705350 | c56cff9 | 92.933% | 9,291.489 ms | Failed |
| load-1788916732445 | c56cff9 | 93.400% | 6,988.642 ms | Failed |
| load-1788918305739 | c56cff9 | 88.300% | 9,611.997 ms | Failed |
| load-1788921802696 | beeabc1 | 89.600% | 11,364.910 ms | Failed |

The corrections address [bounded inbox lock order](../adr/0021-bounded-inbox-transactions.md), [broker subscriptions](../adr/0022-bounded-broker-subscriptions.md), [fair work selection](../adr/0023-fair-scheduler-work-selection.md), [independent adapter periodic work](../adr/0024-independent-adapter-periodic-work.md), [expired batch evidence](../adr/0025-refresh-expired-batch-evidence.md), and [independent command observations](../adr/0026-isolate-recorded-command-observations.md). Short diagnostics and component regressions are recorded separately and are not counted as ten-minute qualifications. The requirement remains at least 99% within two seconds at the declared workload.

## Additional host and database observations

The same run retained 750 one-second host samples, 743 PostgreSQL wait/counter snapshots and 150 five-second process samples. [The complete correlation table and original hashes](healthy-load-io.json) use the post-transaction timing of all 3,000 measured movements: 181 exceeded two seconds. Slow cohorts clustered around 05:04:40–05:05:00, 05:07:10–05:07:30, 05:09:40–05:10:00 and 05:12:10–05:12:30 UTC, alongside elevated disk latency and WAL waits. Across the complete capture, the highest sampled interval-average disk write latency was 157.158 ms and read latency 271.672 ms; available Windows memory stayed at or above 1,854 MiB. These observations support a storage-delay contribution without identifying the producer. PostgreSQL wait counts are sampled states, and process file/device counters include non-disk I/O. No unrelated workload or shared-cache operation was changed.

Two later [container I/O diagnostics](container-io-diagnostics.md) reproduced the latency failure on `822d376` images without changing the full target. Their bounded measurements and one incomplete database capture do not replace this full A50 result.
