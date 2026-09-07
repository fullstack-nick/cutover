# 0001 — Transactional admission and broker confirmation

Accepted 7 September 2026.

Cutover retains accepted requests in the owning database and publishes through a transactional outbox. A publisher marks delivery only after a positive confirmation with no mandatory return. Consumers commit their inbox and effect before acknowledging.

The initial compatibility test observed RabbitMQ 4.3.5 positively confirming an unroutable mandatory message while returning it. It also observed a quorum queue accepting an additional in-flight message after its configured length was reached. RabbitMQ documents that quorum `reject-publish` limits can overshoot while rejection reaches publishers. See [quorum queue length limits](https://www.rabbitmq.com/docs/quorum-queues#length-limit) and [publisher confirmations](https://www.rabbitmq.com/docs/confirms).

Therefore, queue limits are a second line of storage control. Strict business admission uses transactional database quotas with reserved recovery headroom. Relays use bounded batches and in-flight publications. A negative confirm, return, or timeout leaves the original event retryable. The queue must never use drop-head overflow.

The compatibility check requires bounded capacity rejection and proves that every positively confirmed queued payload remains available in order. It does not assume the second publication to a length-one quorum queue is immediately rejected. This preserves the safety property while testing the documented broker behavior.
