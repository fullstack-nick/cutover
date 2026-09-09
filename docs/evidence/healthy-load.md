# Sustained healthy-load evidence

**A50 failed** in `load-1788916732445`, ending 2026-09-09T01:30:33.481Z. All 3,000 measured movements were included: **93.400% reached a durable adapter command record within two seconds of original eligibility**, with **p99 6,988.642 ms**. All 3,300 total physical and business effects, including warm-up, completed once. The required 99% threshold remains unmet; this report does not close A50.

The runtime images were built from `c56cff91e4fec53c657cb523939bb2fc9c939e0b`; the driver source was `b85aedaafddd56be9fd4e3eeb70d532bedba41d6`. [Structured measurements and raw-evidence hashes](healthy-load.json) retain this distinction. [The runtime inventory](runtime-runs.json) identifies the application images and running containers. Raw requests, movement identities, effects and resource samples remain private.

The [declared tracing profile](../adr/0027-domain-and-transport-tracing-profile.md) retains business and transport spans and suppresses automatic JDBC spans. The structured evidence records all five actual owner/shadow pod identities and an explicit non-secret setting allowlist, unchanged before and after offering. No sampling or latency-denominator change was made.

## Workload and denominator

The manually invoked command is `node tools/scenario-driver/healthy-load.mjs`. It offers two orders per second, each with two reserved lines, plus one two-crate receipt per second with one nonzero sorting classification rotating through reusable, needs-cleaning and damaged. The seed is 20260908. A 60-second warm-up precedes the entire 600-second measurement, producing 1,980 HTTP requests and 3,300 movements overall. All HTTP requests returned 202. The driver limits in-flight offers to 32 and retains actual offer lateness.

Original movement eligibility is the start timestamp; the adapter's persisted command creation is the endpoint. The task-created stage is reported separately and is not substituted for that original timestamp. There were **zero blocked, draining or unknown exclusions**. Completed work, failed attempts and eventual effects are checked against the separate owners and the simulator's retained journal.

| Measurement | Result |
| --- | ---: |
| Original eligibility → recorded dispatch, p99 | 6,988.642 ms |
| Percentage within two seconds | 93.400% |
| Task creation → recorded dispatch, p99 | 3,813.000 ms |
| HTTP acceptance, p99 | 417.00 ms |
| Offer lateness, p99 | 17.00 ms |
| Original eligibility → physical completion, p99 | 14,262.00 ms |
| Measured movements eventually completed once | 3000 / 3,000 |
| Measured movements completed before the offering window ended | 2997 / 3,000 |
| Eventual completions per offered measurement second | 5.00 |

## Measured resources

The Windows host is 13th Gen Intel(R) Core(TM) i9-13900H, 20 logical CPUs, with 31.63 GiB physical memory. The Docker Linux VM has 15.43 GiB. Existing local workloads continued to share CPU and storage. This run collected 67 nominal ten-second samples with no sampling errors; it is a local measurement rather than a production capacity guarantee.

| Environment | Maximum sampled CPU | Minimum available memory |
| --- | ---: | ---: |
| Windows host | 39.61% | 0.43 GiB |
| Docker Linux VM | 14.44% | 7.71 GiB |

| Owned container | Maximum sampled memory | Maximum sampled CPU |
| --- | ---: | ---: |
| cutover-control-plane | 5.63 GiB | 204.58% |
| cutover-dev-equipment-simulator-1 | 0.31 GiB | 26.00% |
| cutover-dev-simulator-db-1 | 0.07 GiB | 11.55% |
| cutover-dev-equipment-volume-probe-1 | 0.00 GiB | 1.01% |

Docker CPU percentages use 100% per CPU, while host and VM percentages describe their whole sampled environment. Container memory values are approximate because Docker rounds its displayed strings. Samples can miss shorter peaks.

| Owner | Peak unpublished events | Oldest sampled outbox event | Peak pending inbox | Oldest sampled inbox event |
| --- | ---: | ---: | ---: | ---: |
| core | 18 | 3.610 s | 0 | 0.000 s |
| adapter | 16 | 2.686 s | 0 | 0.000 s |
| execution | 0 | 0.000 s | 0 | 0.000 s |
| returns | 2 | 0.943 s | 0 | 0.000 s |
| shadow | 0 | 0.000 s | 0 | 0.000 s |

Database `fsync` and `synchronous_commit` were both `on` / `on`; no durability setting was relaxed for the run. The structured report includes WAL counters and database CPU/throttling deltas.

The runtime used its default `commit_delay=0`, `commit_siblings=5` and `wal_sync_method=fdatasync`. Both original offering snapshots contain the same settings. No temporary tuning was applied. The structured report retains the actual WAL write and explicit flush counters, zero-deadlock deltas from all six databases, and the server-log hash. All transactions still wait for durable WAL before a successful commit.

## Retained failures and limits

Selected earlier failed full qualifications remain part of the evidence; the implementation ledger retains the other attempts:

| Run | Image source | Within two seconds | Original-eligibility p99 | Result |
| --- | --- | ---: | ---: | --- |
| load-1788880017964 | 968cad5 | 85.900% | 8,340.798 ms | Failed |
| load-1788893862792 | c6486a6 | 6.100% | 156,213.575 ms | Failed |
| load-1788902904030 | b3b09db | 91.533% | 10,616.729 ms | Failed |
| load-1788906898401 | b3b09db | 91.733% | 11,988.404 ms | Failed |
| load-1788913804136 | c56cff9 | 88.200% | 11,551.883 ms | Failed |

The corrections address [bounded inbox lock order](../adr/0021-bounded-inbox-transactions.md), [broker subscriptions](../adr/0022-bounded-broker-subscriptions.md), [fair work selection](../adr/0023-fair-scheduler-work-selection.md), [independent adapter periodic work](../adr/0024-independent-adapter-periodic-work.md), [expired batch evidence](../adr/0025-refresh-expired-batch-evidence.md), and [independent command observations](../adr/0026-isolate-recorded-command-observations.md). Short diagnostics and component regressions are recorded separately and are not counted as ten-minute qualifications. The requirement remains at least 99% within two seconds at the declared workload.
