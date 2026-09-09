# Container I/O diagnostics — 9 September 2026

Both three-minute runs used clean source `f8332e6` and images `822d376`, with the unchanged mixed-product arrival rate and original-eligibility, post-transaction timing proof. Neither qualifies A50. Each accepted all 570 requests including warm-up, completed 950 single business/physical effects, included all 900 measured movements and recorded zero database deadlocks.

| Run | Within two seconds | p99 | Capture |
| --- | --- | --- | --- |
| `load-diagnostic-1788933725206` | 88.222% | 14,011 ms | 225 host, 225 container, 224 database samples |
| `load-diagnostic-1788934202319` | 94.889% | 6,376 ms | 225 host, 225 container/pod, 112 database samples; database capture incomplete |

The first delayed cohort coincided with increased host disk latency. Its counter traversal reached the Cutover cluster container but missed the deeper kind systemd pod groups. After correcting and checking that traversal, the second capture attributed 469,655,552 write bytes to the application database, 68,124,672 to the broker and 56,311,808 to trace storage across the complete observation. In its slow 06:11:20 UTC window, the database accounted for 8,962,048 write bytes; host interval-average write latency reached 35.479 ms and read latency 112.501 ms. These observations narrow the recorded workload but do not identify what caused Windows to delay disk requests.

The second PostgreSQL sampler failed with a container-exec context deadline after 112 snapshots. Its partial data and original error are retained; the independent host/pod captures completed and all container identities, running states and restart counts were preserved. No unrelated service, shared cache or database durability setting was changed.

[Structured observations and original hashes](container-io-diagnostics.json) retain every measured ten-second window. Cgroup byte/operation counters are not latency measurements, parent/child rows must not be added together, and inode writeback ownership can affect attribution. [Kernel counter semantics](https://docs.kernel.org/admin-guide/cgroup-v2.html#io-interface-files). The [full sustained result](healthy-load.md) remains failed.
