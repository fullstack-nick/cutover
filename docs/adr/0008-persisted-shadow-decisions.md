# 0008 — Persisted SQL decisions and an isolated shadow identity

Date: 8 September 2026. Status: accepted; runtime evidence is recorded separately in the implementation ledger.

The legacy coordinator now supplies a bounded snapshot to its PostgreSQL scheduling function before requesting a new command. The snapshot contains priorities, eligible times, movement IDs, route owner/epoch, current command states, lane versions and observation/decision timestamps. The SQL priority function remains in use. The independent execution application implements the same proposal as a pure Java function.

Both implementations rank priority descending, eligible time ascending, then canonical UUID. PostgreSQL's unsigned UUID order differs from Java's signed `UUID.compareTo`, so Java compares canonical UUID text. Compatible lanes use ascending lane ID. Rejection reasons have an explicit shared contract and precedence. Snapshots admit at most six fractional timestamp digits; the capture path truncates timestamps to microseconds before storage. This avoids a sub-microsecond PostgreSQL rounding difference appearing as a future observation.

The core persists a snapshot, hash and SQL proposal together, then an independent relay publishes `SchedulingSnapshotRecorded.v1` to `cutover.observation.v1`. Every round is a separate version-one aggregate. A snapshot never grants dispatch authority; the adapter still checks route/epoch, owner, immutable allocation, command journal, equipment freshness and world identity under its own locks.

The observation outbox has its own capacity row and worker. Initial limits are 1,000 pending observations / 8 MiB and 4,096 retained observations / 64 MiB. When admission reaches a limit, the core increments `omitted_rounds` and continues its SQL scheduling decision. It does not reserve the business outbox or wait for a broker confirm on the dispatch thread. Transport attempts use backoff and stop after six failures. Omitted observations are visible gaps and cannot be counted as successful comparisons.

Shadow runs the execution application with its own `shadow-scheduler` identity, RabbitMQ credential and isolated `cutover_shadow` evidence database on the existing application PostgreSQL server. An extra evidence database avoids active and shadow workers consuming each other's inbox rows, leases, stream cursors or task tables. It introduces no additional database server and owns no inventory or receipt data. Its runtime role cannot migrate or connect to another owner's database. These credentials are distinct even if the application mode flag is incorrect.

The shadow consumer accepts only the configured core-to-observation exchange, validates the complete payload and input hash, computes Java's proposal and stores both proposals and the input. Its comparison capacity is also bounded at 4,096 / 64 MiB. Runtime authority is enforced separately: RabbitMQ denies authoritative publication, and the adapter rejects shadow allocation and command requests. A network path to the adapter permits read observations and demonstrates that identity denial does not depend on a disconnected network.

The seeded fixture runner submits observations through an explicitly enabled scenario-only core endpoint. It cannot create tasks or physical commands. This complements live snapshots; reports label seeded and live input separately. All 1,000 comparisons are exported, including both outputs and hashes, before disposable component databases are closed.

The trigger and existing legacy task IDs remain until a separately verified registration inventory permits the task-creation boundary migration. Deploying a passive execution process or a shadow process does not change a zone's owner.
