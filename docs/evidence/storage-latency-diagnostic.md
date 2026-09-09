# Storage latency diagnostic — 9 September 2026

`load-diagnostic-1788923477821` failed its two-minute diagnostic: **72.667%** of 600 measured movements reached durable dispatch within two seconds; original-eligibility p99 was **20,381.502 ms**. All 650 warm-up/measured effects completed once and all six database deadlock deltas were zero. The original workload rate, eligibility definition, durability and tracing settings remained unchanged. This instrumented short run does not qualify A50. [Structured measurements and exact source identities](storage-latency-diagnostic.json).

The additional read-only collectors recorded 165 one-second Windows samples and 164 PostgreSQL snapshots. The delayed cohort was concentrated around **05:11:50–05:12:30 Europe/Berlin**. The maximum one-second average host disk write latency was **137.961 ms**; database snapshots in the same interval repeatedly showed `WalSync` and `WALWrite` waits. Individual sampled `COMMIT` query ages reached 372.538 ms in `WalSync` and 407.152 ms in `WALWrite`. Query age is not a completed wait duration. Later ten-second arrival cohorts reached p99 around 501–546 ms, without a code or configuration change.

Windows available memory remained between **3,826 and 4,968 MiB** throughout capture. The high page-input peaks occurred outside the main delayed cohort; memory availability alone does not explain that cohort. Physical-disk counters include all host workloads and cannot identify the producer of the storage slowdown. These results support a storage-stall contribution to this failure, while leaving its underlying cause unresolved.

PostgreSQL cumulative counters are published with delay. Unchanged sampled checkpointer counters therefore do not prove the absence of work still in progress. The auxiliary sampler retained `pg_stat_wal` activity, which does not provide PostgreSQL 18 fsync timings; those whole-run deltas come from the workload harness's separate `pg_stat_io` snapshots. No per-second fsync mean is inferred from missing fields. [PostgreSQL 18 statistics documentation](https://www.postgresql.org/docs/18/monitoring-stats.html).

The collectors add overhead and can miss shorter events. The complete ten-minute failure remains the [formal load result](healthy-load.md). No shared-cache reclamation, process stop, subscription filtering or database tuning was performed during this diagnostic.

Original SHA-256 values:

- Workload result: `f72acfc926900dbcbca267ec01d65548362e23143173cf5fcd0bd28963bb6b73`.
- Host samples: `340af8488a90cb2c066632a7e74cf255204496c7a9baf300568c415e9a04880c`.
- Database samples: `2905c94c06b704d395eefa7e07362a61acc8848afa58b60f1f3777cd2a13a04b`.
