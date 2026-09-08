import { useState } from 'react';
import { ApiError, post } from './api';

export function RecoveryAction({ path, version, title = 'Investigate command status', onRefresh }: { path: string; version: number; title?: string; onRefresh: () => void }) {
  const [reason, setReason] = useState('');
  const [prepared, setPrepared] = useState<{ key: string; body: { expectedVersion: number; reason: string } }>();
  const [sending, setSending] = useState(false), [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState<string>(), [message, setMessage] = useState<string>();
  const send = async () => {
    if (!prepared || sending) return;
    setSending(true); setError(undefined); setMessage(undefined);
    try {
      await post(path, prepared.body, prepared.key);
      setPrepared(undefined); setUncertain(false); setReason(''); setMessage('Investigation recorded. Follow the retained command evidence for its outcome.'); onRefresh();
    } catch (failure) {
      const definitive = failure instanceof ApiError && [400, 403, 404, 409, 422].includes(failure.status);
      setError(failure instanceof Error ? failure.message : 'The request could not be confirmed.');
      if (definitive) { setPrepared(undefined); setUncertain(false); onRefresh(); }
      else setUncertain(true);
    } finally { setSending(false); }
  };
  return <section className="recovery-action" aria-label={title}>
    <h3>{title}</h3><p>Request an investigation using the original movement identity. Physical completion still requires verified equipment evidence.</p>
    {error && <div className="notice" role="alert">{error}{!prepared && ' Review the latest state and version before preparing another request.'}</div>}
    {message && <p className="success-message" role="status">{message}</p>}
    {prepared ? <div className="action-review"><strong>Review the recorded request</strong><p>{prepared.body.reason}</p><p>Expected version {prepared.body.expectedVersion}{prepared.body.expectedVersion !== version && ` · latest observed version ${version}`}</p>
      {uncertain && <p role="status">The response was not confirmed. Retry sends the same request identity and content.</p>}
      <div className="action-buttons"><button className="primary" disabled={sending} onClick={() => void send()}>{sending ? 'Recording…' : uncertain ? 'Retry recorded request' : 'Confirm status investigation'}</button><button className="secondary" disabled={sending || uncertain} onClick={() => setPrepared(undefined)}>Back</button></div>
    </div> : <form onSubmit={event => { event.preventDefault(); const text = reason.trim(); if (text.length < 8) return; setError(undefined); setMessage(undefined); setPrepared({ key: crypto.randomUUID(), body: { reason: text, expectedVersion: version } }); }}>
      <label>Reason for investigation<textarea value={reason} onChange={event => setReason(event.target.value)} minLength={8} maxLength={500} required rows={3}/></label>
      <button className="secondary" disabled={reason.trim().length < 8}>Review investigation</button>
    </form>}
  </section>;
}
