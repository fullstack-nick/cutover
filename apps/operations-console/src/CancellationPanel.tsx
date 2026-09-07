import { useRef, useState } from 'react';
import { ApiError, get, identity, post, sitePath } from './api';
import type { Order } from './api';

export function CancellationPanel({ order, onUpdate }: { order: Order; onUpdate: (order: Order) => void }) {
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const retained = useRef<{ path: string; key: string; body: { expectedVersion: number; reason: string } } | undefined>(undefined);
  const cancellation = order.cancellation;
  const pending = cancellation?.state === 'PENDING';
  const paused = cancellation?.state === 'PAUSED';
  const canCancel = identity.hasRealmRole('supervisor') && ['ACCEPTED', 'RESERVED'].includes(order.state) && !pending;
  const prefix = `${sitePath(order.siteId)}/orders/${order.id}`;
  const refreshOrder = async () => {
    try {
      const latest = await get<Order>(prefix);
      if (latest.cancellation && retained.current?.path.endsWith('/cancellation')) retained.current = undefined;
      onUpdate(latest);
    }
    catch (failure) { setMessage(failure instanceof Error ? failure.message : 'Order observations are unavailable.'); }
  };
  const submit = async () => {
    setSubmitting(true); setMessage(undefined);
    const action = retained.current ?? {
      path: paused ? `${prefix}/cancellations/${cancellation.cancellationId}/retry` : `${prefix}/cancellation`,
      key: crypto.randomUUID(),
      body: { expectedVersion: paused ? cancellation.version : order.version, reason: reason.trim() }
    };
    retained.current = action;
    try {
      await post(action.path, action.body, action.key);
      retained.current = undefined; setReason('');
      setMessage(paused ? 'Retry recorded. Refresh the order to see the fence result.' : 'Cancellation confirmed. Reserved stock was released once.');
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : 'The response was interrupted. Refresh the order before retrying.');
      if (failure instanceof ApiError && failure.status < 500) retained.current = undefined;
    } finally { await refreshOrder(); setSubmitting(false); }
  };
  return <section className="cancellation-panel" aria-labelledby="cancellation-title">
    <div className="action-heading"><h3 id="cancellation-title">Order cancellation</h3><button className="text-button" disabled={submitting} onClick={() => void refreshOrder()}>Refresh order evidence</button></div>
    {cancellation && <p>Latest request: <strong>{cancellation.state.toLowerCase()}</strong> · {cancellation.attempts} attempts · version {cancellation.version}{cancellation.lastError && <> · {cancellation.lastError}</>}</p>}
    {pending && <p>The request is retained. Dispatch for this order is held until the adapter returns a safe result. Reservations remain held while the result is uncertain.</p>}
    {canCancel && <form onSubmit={event => { event.preventDefault(); void submit(); }}>
      <p>{paused ? 'After repairing the dependency, resume the same cancellation with a fresh bounded retry budget.' : 'Confirm cancellation of the entire order. Stock is released only after the adapter proves every movement was never submitted. Started or uncertain work will be refused.'}</p>
      <label htmlFor="cancellation-reason">{paused ? 'Recovery reason' : 'Cancellation reason'}</label>
      <textarea id="cancellation-reason" required minLength={8} maxLength={500} value={reason} disabled={submitting || !!retained.current} onChange={event => setReason(event.target.value)} rows={3}/>
      <button className="secondary" disabled={submitting || reason.trim().length < 8}>{submitting ? 'Recording request…' : paused ? 'Confirm cancellation retry' : 'Confirm order cancellation'}</button>
    </form>}
    {!canCancel && !pending && !cancellation && <p>{identity.hasRealmRole('supervisor') ? 'Only wholly unstarted orders can be cancelled.' : 'Supervisors can cancel wholly unstarted orders.'}</p>}
    {message && <div className="notice" role="status">{message}</div>}
  </section>;
}
