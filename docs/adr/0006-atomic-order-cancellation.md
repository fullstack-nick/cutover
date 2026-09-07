# 0006 — Fence every movement before releasing reservations

Status: implemented; verification records are maintained in the progress ledger.

An order can still look reserved while equipment has accepted one of its movements. Releasing stock from the order state alone would make that quantity available for a second order. Cancellation therefore requires a complete adapter certificate covering the immutable movement inventory.

The core first commits a cancellation intent, advances the order version and holds its scheduler. The adapter acquires route locks in sorted zone order, then movement/allocation/command locks. It preflights every movement before writing any cancellation tombstone. A command with a prior send attempt, acceptance evidence, uncertain state or active query lease refuses the entire request. A journaled command with no send and no active lease can be fenced. The send path rechecks allocation state under the same route lock immediately before recording its send attempt; a query returning after an expired lease cannot bypass a committed cancellation.

For a safe inventory, the adapter cancels every allocation in one transaction and retains a hashed certificate. It can create a cancellation tombstone before a delayed movement-intent message arrives. That later registration returns the tombstone and cannot recreate assigned work. It emits movement cancellation observations on the same complete versioned streams used for allocation and command progress.

After receiving the certificate, the core releases all reservations, inserts one uniquely constrained release per reservation, updates the order/tasks, adjusts admission counters, and emits its outcome in one transaction. Physical on-hand stock is unchanged. Both owners retain the actor, reason and versioned audit evidence. No database transaction remains open across the HTTP boundary.

The intent and certificate survive a process restart or lost response. Automatic retries use the same cancellation identity with a bounded six-attempt budget. Exhaustion leaves the order paused with stock reserved. A supervisor from a new browser session can resume that intent using its current version, a new reason and an idempotency key. The retry response describes the recorded request; the order projection supplies the eventual result.

This protocol deliberately refuses cancellation after any submission attempt, even if a later pre-acceptance rejection might eventually make a more complex cancellation safe. The current scope is wholly unstarted work. Restored stale data is not safe absence evidence: restoration must keep workers frozen until the independent physical ledger has been reconciled. The simulator is never rewound to manufacture a cancellation proof.
