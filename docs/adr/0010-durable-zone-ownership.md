# ADR 0010 — reconcile a durable drain before changing ownership

Status: accepted, 8 September 2026. Platform verification is in progress.

Adapter V114 adds persistent migration sessions to the assignment boundary. A supervisor supplies a target owner, the observed route version and a reason. The adapter locks the route, records the session and changes its state to `DRAINING` in one transaction. Idempotent retries return the same session. Concurrent requests cannot create two owners.

During a drain, allocations already owned continue with that owner. New movement intents remain ownerless and receive no task until the route is active again. The barrier includes every allocated unfinished task and every nonterminal journal entry, including unknown and quarantined commands. The coordinator reports the exact blocked movement IDs. Timeouts never authorize a switch.

Once the barrier is clear, the adapter snapshots a finite retained inventory of at most 20,000 allocated movements. It asks core and execution for owner evidence in pages of at most 64 IDs. These service APIs are restricted to the adapter's identity and read only their own databases. Core must also prove that guarded V112 replaced the original task-creating trigger. The capability check applies even to an empty inventory.

Completed movements must match their immutable core intent, consumed reservation, owner task, allocation, adapter command and retained physical completion. Cancelled movements instead require a released reservation and a matching retained never-submitted certificate, with no inventory effect. Missing or inconsistent evidence remains a blocker. A physical world/generation change, incomplete history or regressed journal high-water mark cannot satisfy the checkpoint.

Proof pages, hashes and progress survive process loss. Before committing the checkpoint and again before switching, the coordinator takes the authoritative route lock and rechecks the entire inventory and barrier. Command admission uses that same lock. The switch transaction increments the epoch, changes the owner, activates the route and appends audit and `ZoneOwnershipChanged.v1` records. Its event stream uses the ownership epoch, independently of route versions used for drain and completion transitions.

An independent worker releases pending intents in bounded batches under the now-active route. The session observes the first ten noncancelled movements assigned to the new epoch. Each must have matching owner, inventory and physical completion proof. Dispatch latency runs from the later of assignment and eligibility to durable command recording. For this ten-item sample, its maximum is the sample p99; all ten must meet two seconds. A missed target stays visible and does not become a successful migration on a later poll.

The durable phases are `DRAINING`, `RECONCILING`, `READY_TO_SWITCH`, `OBSERVING` and `COMPLETED`. Each worker lease has a 60-second token; a stale response cannot advance another worker's progress. Owner transport failures have six bounded attempts and then need versioned, audited supervisor recovery. The migration evidence worker is separate from equipment polling. Retained sessions and proof bytes have independent limits of 128 sessions and 128 MiB.

Before switching, a supervisor can cancel the unchanged session; pending work resumes with the existing owner/epoch. After switching, reversal is another full drain. Reversing an observation session links a child session, marks the parent `REVERSING`, and changes it to `REVERSED` only after the child completes its own observation. Cancelling an unswitched reverse session restores the parent's observation. No operation substitutes new physical identities.

Additive V115 closes a nested reversal lineage atomically when its latest child completes. The immediate parent becomes `REVERSED`; older ancestors become `SUPERSEDED`, with an audit linking the successful settling session and epoch. Their failed observations, errors and proof hashes remain intact. Superseded does not mean that an earlier latency sample passed. Failed immutable latency samples no longer trigger repeated background proof reads; an explicit reversal remains available.

V115 also supplies an assignment timestamp for a compatible older adapter writer that omits the new column. Pending ownerless intents retain a null timestamp; the current writer's explicit injected-clock timestamp is preserved. This database compatibility measure does not by itself establish image rollback compatibility.

Explicit scenario controls can arm a one-shot process exit after each committed phase. The fault itself is consumed and audited before exit 73. Restart cannot repeat a consumed fault. These endpoints require the enabled local test controls and the exact scenario client, independently of human supervisor access.

An application-image rollback is a separate operation and must preserve the route. A retained image is not declared compatible until it has been tested against the expanded schema and current service contracts. Cached proofs must also be invalidated/reconciled by the later restore workflow; this ADR does not claim stale-backup safety.

Component verification: all 102 checks passed on 8 September at 03:08 Europe/Berlin, including eleven migration/evidence checks and three one-shot fault checks. Nine migration checks subsequently passed with validation of every phase's public response schema and fractional millisecond observations. Actual process evidence and remaining compatibility/restore gates are tracked in the implementation ledger.

The next full `verify` passed **105 checks at 04:24 Europe/Berlin in 7 minutes 57 seconds**. It covers nested failed reversals settling their complete lineage while preserving both failed samples, read-only empty worker polls and processing a later arrival, plus the existing durability, freeze, duplicate-delivery, equipment and 1,000-comparison checks. The console type check and production build also passed.
