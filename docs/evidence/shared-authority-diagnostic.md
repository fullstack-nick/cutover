# Shared command authority — deployed diagnostic

`load-diagnostic-1788925571030` **failed** its two-minute check on images `6cbbccc8b18e71f912b541bdbeb19584ca122b87`, using clean driver source `0fc72945d8ecdf7c97f593d4a3c10c36171e53dc`. All 650 effects completed once, all 390 requests returned 202 and all six database deadlock deltas were zero. All 600 measured movements were included.

The original journal-timestamp result was **93.0% within two seconds, p99 7,101.515 ms**. A complete [post-commit timing audit](../adr/0030-prove-dispatch-timing-after-commit.md) of the same 650 movements through 390 original traces measured **92.833% within two seconds, p99 7,189 ms**. The audit changes no original result or physical work. [Structured measurements](shared-authority-diagnostic.json).

The 165 host and 164 PostgreSQL samples locate the 42 movements late by the original timestamp around **05:48:20–05:48:40 Europe/Berlin**. The largest sampled one-second average disk write latency was **61.517 ms**, and read latency reached **190.579 ms**. Windows available memory stayed between **3,677 and 4,105 MiB**. These host counters include unrelated workloads and do not identify the source of storage activity. PostgreSQL snapshots retain wait types and, in this run, the correct `pg_stat_io` WAL counters as well.

Compared with the [preceding diagnostic](storage-latency-diagnostic.md), fewer movements missed the timestamp target. This is not a controlled estimate of the lock change's performance effect: storage behavior and process startup differed. The focused database regression establishes that independent command readers can proceed together while owner changes remain fenced. Neither diagnostic satisfies the unchanged full sustained-load requirement.

Original result SHA-256: `1c5da21c391fc4388cbbcd2229069dd753ee9ae9cea683b764aa92fd2247a93f`. The earlier full [A50 failure](healthy-load.md) remains the formal sustained result.
