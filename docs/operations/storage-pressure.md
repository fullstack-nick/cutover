# Storage pressure

The current logical guards expose retained outbox bytes, unpublished backlog, retained inbox bytes and the owner's actual PostgreSQL database size at `GET /internal/v1/platform/storage`. This diagnostic endpoint requires the platform-administrator role on an explicitly started local service forward.

The default owner budget is 512 MiB of database files, with a separate 256 MiB retained outbox payload limit. Intake and new dispatch return `503 STORAGE_HEADROOM` at 80% of either limit. The append transaction independently enforces the hard retained payload cap. The inbox retains its 10,000 active messages, 64 MiB active payload and 256 MiB retained payload limits. Counter reservation and the affected business changes commit together or roll back together.

Read accepted work, command evidence, delivery state and audit while resolving pressure. Quiesce new traffic, repair unavailable broker/consumer dependencies, and let retained work drain. Do not delete unpublished, pending, quarantined or business records to clear an alarm. Retention can compact only settled payloads older than seven days; it never shortens the supported replay horizon to make a test pass. A cleared broker backlog may leave retained history near its independent limit.

The explicit critical-storage control stops new intake/dispatch while leaving diagnostic reads and already accepted outcome recording available where durable capacity remains. Worker freeze additionally prevents background mutations for a checkpoint. Normal business operation resumes only after the underlying condition is repaired and the relevant gate is cleared with a versioned, audited operation.

Database size is not filesystem free space. kind's local-path claim size is a provisioning request, not an enforced filesystem quota. Physical host/PVC pressure detection and the measured exhaustion/recovery exercise are still being implemented; no successful A26 result is claimed from the logical guards. Never fill or prune the user's shared Docker disk to simulate this scenario. Use an isolated Cutover verification budget or volume and preserve the independent simulator ledger.
