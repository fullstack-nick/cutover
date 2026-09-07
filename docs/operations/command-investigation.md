# Investigating an uncertain command

Use the order's movement ID to read `GET /api/v1/sites/{site}/commands/{id}`. The recovery list is `GET /api/v1/sites/{site}/commands`. Its rows are bounded and site-scoped. Inspect state, failure attempts, latest valid evidence, last observation, world/generation and error. A missing or stale observation does not mean the movement failed.

OUTCOME_UNKNOWN means the transport outcome could not be established. QUARANTINED means the available identity, history or sequence evidence could not establish a safe transition. Both retain the original immutable command. An accepted command must remain discoverable in the same simulator history; a later 404 cannot erase that knowledge. Reads and diagnostics remain useful while dispatch is paused.

First correct the actual cause: restore the equipment connection, allow a known simulator restart to finish, or clear the specific exhausted/incorrect scenario fault. Do not reset the simulator, replace a movement ID, delete journal rows, or release its reservation. A real world/generation mismatch requires investigation of the independent history and the application restoration boundary.

A supervisor then sends `POST /api/v1/sites/{site}/commands/{id}/reconciliation` with an `Idempotency-Key`, the current `expectedVersion`, and an 8–500-character reason describing the evidence or correction. The response records an investigation. The worker queries the original command and may discover completion. It may send the same ID only after authoritative absence in the same complete history and no prior acceptance. It cannot blindly repeat a movement.

A stale version or active investigation lease returns 409. Refresh the record and inspect the changed evidence before making a new decision. Repeat a request with the same key and identical body to retrieve its recorded result after a lost HTTP response. Reusing the key with changed content conflicts. Operator credentials cannot authorize this action, even through a direct API call; cross-site IDs return 404.

Verify a terminal result in the adapter journal, then confirm the owning product applied completion. The audit retains actor, site, reason, before/after version and recorded outcome. The acceptance checks additionally compare the inventory and independent physical ledgers for exactly one effect. A green response to the recovery request itself is not that verification.

If the history remains incomplete, leave the command blocked with its evidence visible. The local lab has no force-complete or new-identity escape path. The recovery console workflow is still under implementation; the current authenticated API and reproducible process checks establish this behavior.
