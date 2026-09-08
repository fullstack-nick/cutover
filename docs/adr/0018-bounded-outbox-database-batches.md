# 0018 — Batch the relay's database work

Status: component-verified, 8 September 2026; deployed qualification pending.

## Context

The complete `63bdde7` load run retained every accepted effect but missed its original-eligibility dispatch target. Backlog observations included old, unattempted events without broker errors. The relay previously committed one claim and one confirmation transaction per event. Each durable commit competes for the local database's WAL flush capacity.

## Decision

Claim up to the existing bounded poll limit from already available events in one owner-local transaction. Select only the first unpublished event of each stream, including the rule that a paused predecessor blocks its successors. Row locks and distinct lease IDs fence concurrent or expired claims. An empty poll remains read-only; a batch never waits for more arrivals.

Publish each original envelope separately using the existing mandatory-routing and positive-confirm protocol. Only confirmed events enter the settlement set. One database transaction marks matching leases and decrements admission counts/bytes for exactly the rows that changed. A failed event retains its bounded retry state. Claims that were never attempted are released without consuming a transport attempt. No business effect is applied by this batching mechanism.

Stop starting another publish when less than ten seconds remain on the twenty-second batch lease. Settlement still requires the exact lease ID, so elapsed/replaced work cannot falsely consume another lease's admission credit. A freeze prevents settlement until resume and replay. A process crash before settlement leaves all original event identities available for redelivery after lease expiry, including any already accepted by the broker.

The batch is a database optimization, not an atomic broker transaction or an exactly-once transport claim. Consumers still persist/deduplicate each delivery before acknowledging it. The separate observation relay and domain coordinators retain their own boundaries.

## Verification

The 34 focused durable-delivery checks passed against PostgreSQL and RabbitMQ quorum queues at 12:42:32 Europe/Berlin. New cases cover two streams' ordering and atomic claims; a partial failure that settles only the confirmed event; two broker confirmations followed by a crash and five deliveries yielding three effects; a freeze that retains the complete unpublished batch; and releasing the remainder of a slow burst before lease expiry.

The existing mandatory-return, real queue-overflow/nack, retry-exhaustion, stale-lease, quarantine and inbox-deduplication checks also passed. These results do not establish the healthy-load or deployed outage acceptance gates. Retain the failed full run and repeat those process checks on the built images.
