# Sustained healthy-load evidence

**A50 failed** in `load-1788918305739`, ending 2026-09-09T01:56:43.611Z. All 3,000 measured movements were included: **88.300% reached a durable adapter command record within two seconds of original eligibility**, with **p99 9,611.997 ms**. All 3,300 total physical and business effects, including warm-up, completed once. The required 99% threshold remains unmet; this report does not close A50.

The runtime images were built from `c56cff91e4fec53c657cb523939bb2fc9c939e0b`; the driver source was `c90ca017401993ed020bd8713ffb043c14a45151`. [Structured measurements and raw-evidence hashes](healthy-load.json) retain this distinction. [The runtime inventory](runtime-runs.json) identifies the application images and running containers. Raw requests, movement identities, effects and resource samples remain private.

The [declared tracing profile](../adr/0027-domain-and-transport-tracing-profile.md) retains business and transport spans and suppresses automatic JDBC spans. The structured evidence records all five actual owner/shadow pod identities and an explicit non-secret setting allowlist, unchanged before and after offering. No sampling or latency-denominator change was made.

This run followed one explicitly approved reclamation of the shared VM's clean file cache. Before and after that action, all 46 container identities/running/restart states, 19 Cutover pod identities, physical world/journal, routes and durable database settings were preserved. Every container remained unchanged through the full workload. The action is not part of project startup scripts, and was not repeated during the run. The [Linux kernel documents](https://kernel.org/doc/html/latest/admin-guide/sysctl/vm.html#drop-caches) that rebuilding discarded caches can cost CPU and I/O.

Windows available memory was already 4.61 GiB immediately before reclamation, and 4.70 GiB thirty seconds afterward. VM clean-cache preparation therefore does not establish why host headroom had recovered from the previous run, or isolate a performance cause. This measurement qualifies only its recorded shared-host conditions. The structured report retains all five memory snapshots, exact preparation timestamps, invariant counts, full durability settings, and hashes of the private one-use journal and full-run wrapper.

## Workload and denominator

The manually invoked command is `node tools/scenario-driver/healthy-load.mjs`. It offers two orders per second, each with two reserved lines, plus one two-crate receipt per second with one nonzero sorting classification rotating through reusable, needs-cleaning and damaged. The seed is 20260908. A 60-second warm-up precedes the entire 600-second measurement, producing 1,980 HTTP requests and 3,300 movements overall. All HTTP requests returned 202. The driver limits in-flight offers to 32 and retains actual offer lateness.

Original movement eligibility is the start timestamp; the adapter's persisted command creation is the endpoint. The task-created stage is reported separately and is not substituted for that original timestamp. There were **zero blocked, draining or unknown exclusions**. Completed work, failed attempts and eventual effects are checked against the separate owners and the simulator's retained journal.

| Measurement | Result |
| --- | ---: |
| Original eligibility → recorded dispatch, p99 | 9,611.997 ms |
| Percentage within two seconds | 88.300% |
| Task creation → recorded dispatch, p99 | 7,527.000 ms |
| HTTP acceptance, p99 | 693.00 ms |
| Offer lateness, p99 | 20.00 ms |
| Original eligibility → physical completion, p99 | 17,220.00 ms |
| Measured movements eventually completed once | 3000 / 3,000 |
| Measured movements completed before the offering window ended | 2996 / 3,000 |
| Eventual completions per offered measurement second | 5.00 |

## Measured resources

The Windows host is 13th Gen Intel(R) Core(TM) i9-13900H, 20 logical CPUs, with 31.63 GiB physical memory. The Docker Linux VM has 15.43 GiB. Existing local workloads continued to share CPU and storage. This run collected 67 nominal ten-second samples with no sampling errors; it is a local measurement rather than a production capacity guarantee.

| Environment | Maximum sampled CPU | Minimum available memory |
| --- | ---: | ---: |
| Windows host | 45.26% | 3.49 GiB |
| Docker Linux VM | 22.47% | 7.23 GiB |

| Owned container | Maximum sampled memory | Maximum sampled CPU |
| --- | ---: | ---: |
| cutover-control-plane | 5.67 GiB | 390.05% |
| cutover-dev-equipment-simulator-1 | 0.30 GiB | 99.09% |
| cutover-dev-simulator-db-1 | 0.06 GiB | 12.33% |
| cutover-dev-equipment-volume-probe-1 | 0.00 GiB | 5.52% |

Docker CPU percentages use 100% per CPU, while host and VM percentages describe their whole sampled environment. Container memory values are approximate because Docker rounds its displayed strings. Samples can miss shorter peaks.

| Owner | Peak unpublished events | Oldest sampled outbox event | Peak pending inbox | Oldest sampled inbox event |
| --- | ---: | ---: | ---: | ---: |
| core | 27 | 4.815 s | 0 | 0.000 s |
| adapter | 9 | 2.610 s | 0 | 0.000 s |
| execution | 0 | 0.000 s | 0 | 0.000 s |
| returns | 4 | 1.889 s | 0 | 0.000 s |
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

The corrections address [bounded inbox lock order](../adr/0021-bounded-inbox-transactions.md), [broker subscriptions](../adr/0022-bounded-broker-subscriptions.md), [fair work selection](../adr/0023-fair-scheduler-work-selection.md), [independent adapter periodic work](../adr/0024-independent-adapter-periodic-work.md), [expired batch evidence](../adr/0025-refresh-expired-batch-evidence.md), and [independent command observations](../adr/0026-isolate-recorded-command-observations.md). Short diagnostics and component regressions are recorded separately and are not counted as ten-minute qualifications. The requirement remains at least 99% within two seconds at the declared workload.
