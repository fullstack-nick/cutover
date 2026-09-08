# Broker outage or rejected publication

## Detect and inspect

Inspect the owner's durable outbox, oldest pending age and last publication error. An accepted order/receipt can remain visible while its movement has not reached the next owner. A broker connection failure, mandatory return and reject-publish nack are different observations; preserve the reported category and original event ID.

Read the exact Cutover broker before changing anything:

```powershell
kubectl --kubeconfig .local/kubeconfig --context kind-cutover -n cutover-platform get statefulset rabbitmq
kubectl --kubeconfig .local/kubeconfig --context kind-cutover -n cutover-platform get pod rabbitmq-0
```

Check its `app.kubernetes.io/part-of=cutover` label, configured replica count, readiness and PVC. Keep raw logs private. The owner messaging diagnostic shows retained bytes and admission state; see [quarantine and replay](../operations/message-quarantine.md).

## Expected business behavior

Business state and its event commit together in the source database. Broker loss cannot turn an unconfirmed publication into a published outbox row. New intake continues only within active-work and storage bounds, then returns a controlled 503 with `Retry-After`. Already accepted reservations, receipts and immutable events remain retained.

A full quorum queue uses reject-publish. The producer keeps the rejected original; it does not request drop-head behavior. The independent shadow queue and observation capacity must not consume business admission capacity. A positive confirmation accompanied by a mandatory return also retains the source event.

## Restore and verify

If a deliberate fault left the owned StatefulSet scaled to zero, restore that exact controller to one replica, preserving its PVC:

```powershell
kubectl --kubeconfig .local/kubeconfig --context kind-cutover -n cutover-platform scale statefulset/rabbitmq --replicas=1
kubectl --kubeconfig .local/kubeconfig --context kind-cutover -n cutover-platform rollout status statefulset/rabbitmq --timeout=180s
```

If routing is wrong, restore the verified original exchange/binding under the broker administrator's configuration authority. If the consumer is unavailable, recover its original process or clear only its recorded test pause. Do not purge the queue, replace its arguments in place, delete the source outbox or generate new event IDs.

Automatic retries are bounded. After the real cause is corrected, a supervisor may replay a paused original through the versioned owner messaging API with an idempotency key and reason. A stale version requires rereading the record. Observation retries use their separate [shadow recovery API](shadow-comparison.md).

Verify the original outbox is published, the destination inbox has retained the original identity, and the owning product reaches the expected state. Compare business and simulator ledgers for one effect. Declining queue depth alone cannot prove absence of loss.

`broker-capacity-smoke.mjs`, `mandatory-return-smoke.mjs` and `queue-overflow-smoke.mjs` exercise these different failure boundaries. Run one at a time on a settled demo. Their private reports preserve the queue arguments, publication outcome, original payload hashes and ledger assertions.

If durable originals, queue routing or storage identity cannot be established, leave work blocked and retain the evidence. Recreating a lost broker after an application restore follows the checkpoint replay procedure; attaching stale queues to restored tables is unsupported.
