# Sustained healthy-load evidence

**A50 failed** in `load-1788921802696`, ending 2026-09-09T02:55:00.857Z. All 3,000 measured movements were included: **89.600% reached a durable adapter command record within two seconds of original eligibility**, with **p99 11,364.910 ms**. All 3,300 total physical and business effects, including warm-up, completed once. The required 99% threshold remains unmet; this report does not close A50.

The runtime images were built from `beeabc15732375017512831a230c408eccecedcf`; the driver source was `f06043a2c6baf2953a75ea1a705a8cd047029782`. [Structured measurements and raw-evidence hashes](healthy-load.json) retain this distinction. [The runtime inventory](runtime-runs.json) identifies the application images and running containers. Raw requests, movement identities, effects and resource samples remain private.

The [declared tracing profile](../adr/0027-domain-and-transport-tracing-profile.md) retains business and transport spans and suppresses automatic JDBC spans. The structured evidence records all five actual owner/shadow pod identities and an explicit non-secret setting allowlist, unchanged before and after offering. No sampling or latency-denominator change was made.



## Workload and denominator

The manually invoked command is `node tools/scenario-driver/healthy-load.mjs`. It offers two orders per second, each with two reserved lines, plus one two-crate receipt per second with one nonzero sorting classification rotating through reusable, needs-cleaning and damaged. The seed is 20260908. A 60-second warm-up precedes the entire 600-second measurement, producing 1,980 HTTP requests and 3,300 movements overall. All HTTP requests returned 202. The driver limits in-flight offers to 32 and retains actual offer lateness.

Original movement eligibility is the start timestamp; the adapter's persisted command creation is the endpoint. The task-created stage is reported separately and is not substituted for that original timestamp. There were **zero blocked, draining or unknown exclusions**. Completed work, failed attempts and eventual effects are checked against the separate owners and the simulator's retained journal.

| Measurement | Result |
| --- | ---: |
| Original eligibility → recorded dispatch, p99 | 11,364.910 ms |
| Percentage within two seconds | 89.600% |
| Task creation → recorded dispatch, p99 | 9,320.000 ms |
| HTTP acceptance, p99 | 509.00 ms |
| Offer lateness, p99 | 21.00 ms |
| Original eligibility → physical completion, p99 | 18,496.00 ms |
| Measured movements eventually completed once | 3000 / 3,000 |
| Measured movements completed before the offering window ended | 2998 / 3,000 |
| Eventual completions per offered measurement second | 5.00 |

## Measured resources

The Windows host is 13th Gen Intel(R) Core(TM) i9-13900H, 20 logical CPUs, with 31.63 GiB physical memory. The Docker Linux VM has 15.43 GiB. Existing local workloads continued to share CPU and storage. This run collected 67 nominal ten-second samples with no sampling errors; it is a local measurement rather than a production capacity guarantee.

| Environment | Maximum sampled CPU | Minimum available memory |
| --- | ---: | ---: |
| Windows host | 43.66% | 0.55 GiB |
| Docker Linux VM | 18.04% | 7.65 GiB |

| Owned container | Maximum sampled memory | Maximum sampled CPU |
| --- | ---: | ---: |
| cutover-control-plane | 5.52 GiB | 321.78% |
| cutover-dev-equipment-simulator-1 | 0.29 GiB | 42.20% |
| cutover-dev-simulator-db-1 | 0.06 GiB | 23.68% |
| cutover-dev-equipment-volume-probe-1 | 0.00 GiB | 1.31% |

Docker CPU percentages use 100% per CPU, while host and VM percentages describe their whole sampled environment. Container memory values are approximate because Docker rounds its displayed strings. Samples can miss shorter peaks.

| Owner | Peak unpublished events | Oldest sampled outbox event | Peak pending inbox | Oldest sampled inbox event |
| --- | ---: | ---: | ---: | ---: |
| core | 31 | 5.100 s | 0 | 0.000 s |
| adapter | 7 | 1.820 s | 0 | 0.000 s |
| execution | 0 | 0.000 s | 0 | 0.000 s |
| returns | 2 | 0.782 s | 0 | 0.000 s |
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

The corrections address [bounded inbox lock order](../adr/0021-bounded-inbox-transactions.md), [broker subscriptions](../adr/0022-bounded-broker-subscriptions.md), [fair work selection](../adr/0023-fair-scheduler-work-selection.md), [independent adapter periodic work](../adr/0024-independent-adapter-periodic-work.md), [expired batch evidence](../adr/0025-refresh-expired-batch-evidence.md), and [independent command observations](../adr/0026-isolate-recorded-command-observations.md). Short diagnostics and component regressions are recorded separately and are not counted as ten-minute qualifications. The requirement remains at least 99% within two seconds at the declared workload.
