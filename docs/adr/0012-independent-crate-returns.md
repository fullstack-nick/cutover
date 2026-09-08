# 0012 — A second product with its own receipt and sorting state

Date: 8 September 2026. Status: accepted; platform verification is recorded separately.

Cutover needs to demonstrate that its technical service conventions support another product without sharing fulfilment's persistence model. Reusable-crate returns therefore has an independently packaged service, database, runtime and migration roles, broker identity, OAuth client and HTTP audience.

The returns service was created with `node scripts/scaffold-service.mjs returns-service returns`. Its committed `.scaffold.json` records the four original template hashes. Product development supplied the V116 schema, receipt API, message handler and bounded coordinator. The shared starter still contains technical concerns only. The production module depends on that starter; its connected component tests add the adapter and simulator, with no dependency on core or execution.

## Receipt and movement ownership

An external reference records three integer counts: reusable, needs cleaning and damaged. Each count is 0–10,000 and the total must be positive. A transaction serializes the external reference, checks the canonical payload hash, creates one receipt and at most three stable movement IDs, increments received totals, and records the movement requests in its own outbox. New HTTP idempotency keys cannot create another receipt for the same reference.

The adapter assigns the returns zone to `returns-service`. Only a matching `MovementAssigned` observation can create an active sorting task. The coordinator uses FIFO order and a bounded batch of 16 tasks; it asks the adapter for the returns route, assignment inventory and fresh lane observations. It does not import the outbound scheduling rule or call a fulfilment service. The returns route is outside the outbound migration API.

Each nonzero classification moves from `returns-inbound` to its fixed destination: `reusable`, `cleaning` or `damaged`. The logical movement is also the stable physical command ID. Transport retries cannot replace it.

## Sorting proof and failure behavior

The completion transaction compares the original intent and assignment with terminal adapter evidence and the simulator's retained world, generation and execution sequence. It writes one sorting-ledger row per movement, increments the matching sorted totals, and updates the receipt. A unique command identity and physical execution identity provide further constraints. Completion and inbox application share the same transaction when delivered through the broker.

Repeated delivery with another event ID is checked by the technical stream history; the domain ledger also protects against repeated semantic completion under a later stream version. A late nonterminal event cannot regress an already completed child. A receipt remains in reconciliation when another child is uncertain, even if one classification finishes.

Blocked lanes remain waiting conditions. Physical unknown, rejected or quarantined commands require adapter investigation and are excluded from automatic sorting dispatch. A later verified completion event can settle them. Connection failures use six bounded attempts and a durable pause. Supervisor recovery needs a reason, expected version and idempotency key and resumes status investigation for the original task. It grants no authority to override physical evidence.

## Capacity and lifecycle

Intake uses the shared durability checks and conservative limits of 800 active receipts, 8,000 unpublished events and approximately 51 MiB of unpublished payloads. Durable storage also enforces the shared retained-message limits. Each completion releases active-receipt capacity only after the last classification finishes. Empty coordinator polls do not allocate write transactions. All writers honor the checkpoint control barrier.

These are lab bounds, not a general crate-routing system. The simulator's independent lane abstraction permits concurrent compatible movements and does not model conveyor spacing. The final mixed-product load and recovery scenarios must establish the measured operating envelope.
