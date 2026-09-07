# 0005 — Monotonic command evidence and controlled process loss

Status: implemented component behavior; platform fault results are recorded separately in the progress ledger.

An uncertain transport result provides no permission to invent a new physical command. The adapter keeps the immutable command payload, the latest valid command evidence, and the last observation separately. It records whether acceptance was ever established and the evidence version. Older versions cannot move an executing command backwards. Contradictory contents at the same version quarantine the command while retaining the earlier proof. A completed journal is terminal.

A supervisor can record a status investigation with an expected command version, idempotency key and explanation. The request resets the bounded investigation budget and is audited in the adapter transaction. It cannot reopen a terminal command, ignore a live investigation lease, replace the payload, override known acceptance, or turn a history gap into safe absence. A healthy same-world status query remains necessary before any send. Responses distinguish a recorded investigation request from physical acceptance and completion.

The simulator retains deterministic faults in its own PostgreSQL database. A hold records EXECUTING and a future execution deadline before a restart; eventual load movement, execution ledger and terminal status commit together. Delayed and repeated status snapshots let checks exercise stale observations. History/world faults alter a response without deleting or rewinding physical history. Their selector, remaining activations and clear time remain inspectable. Clearing a fault never resets business data.

The application fault mechanism operates only when explicitly enabled in the local development/demo manifests. It is absent by default. Internal controls require both the scenario-driver client and test-control role for site-a; the ordinary proxy rejects the entire internal prefix. Operator and supervisor identities do not acquire fault authority through their business roles.

Each process-loss checkpoint records one-shot consumption and audit before exiting its own application with code 73. The callback runs outside the business/inbox/outbox transaction at one of three boundaries: business commit before publication, positive publish confirmation before the outbox mark, or committed consumer effect before acknowledgement. A restart cannot fire that activation a second time. The tests inspect actual pod restart counts and termination code; throwing an in-process test exception alone is only component evidence.

Process-wide pause flags are versioned and audited. `workersPaused` supplies the checkpoint freeze; relay and consumer flags support narrower fault experiments. Equipment and product data are never modified through arbitrary test SQL or an unbounded action endpoint. Broker transport backoff applies across aggregate streams as well as to individual outbox rows, so many pending streams cannot cause repeated immediate connection attempts.

The added columns are non-destructive. Compatibility with a selected N−1 application image is a separate phase-6 gate; adding columns alone does not establish that gate. Full storage-pressure detection, cancellation, migration barriers and operational retention remain separate requirements.
