# 0017 — Keep ordinary control checks read-only

Status: accepted and component-verified, 8 September 2026; deployed performance qualification remains pending.

## Context

Every durable worker must finish its transaction before an operational freeze is acknowledged. Previously, workers held a shared lock on the single `service_control` row. These checks can generate PostgreSQL row-lock writes even when no business work exists. Sustained-load observations found WAL flush waits and delayed movement assignment. Increasing database CPU and memory reduced throttling without meeting the dispatch target; it is not evidence that the storage bottleneck disappeared.

## Decision

Use a transaction-scoped shared advisory lock before reading the control row. Every application control mutation takes the corresponding exclusive advisory lock before its existing row/version lock. The key is the reserved two-integer pair `(0x4355544f, 1)` within the owner's database, separate from the bigint keys used for business identities. PostgreSQL releases the barrier at transaction completion; no process-local flag or session lock substitutes for it.

The lock statement and control-row read are separate statements in a `READ COMMITTED` transaction. A worker queued behind a completed freeze therefore reads its committed flag after obtaining the barrier. Reject other isolation levels rather than accepting a potentially older snapshot. Control writers take the exclusive barrier first, avoiding shared-to-exclusive upgrades. All direct runtime and restoration control paths must follow this order.

Durable flags, optimistic versions, idempotency records, audit rows and storage checks remain authoritative. This change does not relax synchronous commit, suppress delivery acknowledgements or release stock on a timeout. Historical, already applied migrations remain unchanged; their one-time locks are not ordinary runtime checks.

All application owner deployments use Kubernetes `Recreate` and one replica. An image rollback replaces that owner's process before starting its predecessor. Mixed predecessor/current processes writing the same owner database are unsupported because predecessor binaries use the earlier barrier protocol. Other independently owned applications may run different compatible versions. Administrative restoration SQL acquires the new advisory barrier and retains row locks so it also excludes predecessor workers.

## Verification and limits

Real PostgreSQL tests check read-only execution with no assigned write transaction ID, refusal of a stale transaction isolation mode, a freeze waiting for an earlier business transaction, and a later queued worker observing the committed freeze. Existing checkpoint, fault, migration and restoration tests remain required. The first concurrency-test run checked a nonexistent response field after reaching the freeze; its failed result is retained separately.

Component correctness does not establish A50. Build and deploy the final implementation, retain world and business identities, then measure the original movement-eligibility denominator under the full declared workload. Keep failed and short diagnostic runs visible.

The complete backend suite passed 155 checks at 12:10:06 Europe/Berlin in 11 minutes 19 seconds, with no failures, errors or skips. This includes the final shared barrier in inbox, simulator and baseline-registration paths. Restoration SQL uses the same exclusive key before row updates; its scripts passed syntax checks, with a renewed deployed checkpoint check still required.

## References

PostgreSQL documents transaction-lifetime advisory locks and the application's responsibility to use them consistently in [explicit locking](https://www.postgresql.org/docs/18/explicit-locking.html#ADVISORY-LOCKS). Its [READ COMMITTED semantics](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-READ-COMMITTED) provide a fresh snapshot for each statement. The [advisory-lock functions](https://www.postgresql.org/docs/18/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS) distinguish the two-integer and bigint key spaces.
