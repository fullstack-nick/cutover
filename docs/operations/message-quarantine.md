# Inspecting and reprocessing message quarantine

A valid but temporarily unapplied event appears in the owner's inbox with its original event ID, sequence and retry reason. The local worker retries with bounded backoff. A permanent error or exhausted budget remains quarantined and acknowledged only after that owner has durably retained it. Broker redelivery does not repeat the business effect.

Use an explicitly started core-api or adapter-api loopback forward and the local supervisor identity. `GET /internal/v1/sites/{site}/messaging` shows the bounded inbox/outbox backlog. `GET /internal/v1/sites/{site}/messaging/quarantine` shows original deliveries that failed before inbox admission but have a trustworthy site. Fields include the body hash, byte count, version and attempt count. Check the original publisher contract and missing predecessor or dependency before recovery.

For a valid inbox event, `POST /internal/v1/sites/{site}/messaging/inbox/{eventId}/replay` accepts `expectedVersion` and a reason, with an `Idempotency-Key` header. For an original raw delivery, use `POST /internal/v1/sites/{site}/messaging/quarantine/{deliveryId}/reprocess` with the same action fields. Neither operation edits the retained payload. A recorded retry is not evidence of successful application; refresh the entry and inspect its owner's business rows or authenticated projection.

`TRANSFERRED` on a raw delivery means the ordinary durable inbox now owns it. Its inbox may still be pending or quarantined. Verify its `transferredEventId` and final inbox state. `QUARANTINED` means the unchanged bytes still fail admission. Repeating the same action key returns the same recorded response; a new correction requires the latest version and a new reason/key.

Malformed bytes or an untrusted source/site appear only in the platform administrator's `GET /internal/v1/platform/untrusted-deliveries` diagnostic pool. That view shows no raw payload or claimed business identifiers. An administrator may diagnose configuration but needs the supervisor role for business recovery. If the original event cannot establish an authorized site after a real correction, reprocessing fails closed. Do not copy another site's payload into a new local event or change a command identity.

If storage is full or workers are frozen for a checkpoint, recovery writes pause. Keep accepted and unresolved records; inspect `GET /internal/v1/platform/storage` as a platform administrator and follow the storage runbook. Escalate an unresolved source/history gap rather than forcing a stream cursor forward.
