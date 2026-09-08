import { useEffect, useRef, useState } from 'react';
import { ApiError, get, identity, post, sitePath } from './api';
import type { Command, Receipt, ReceiptPage, ReturnCounters } from './api';
import './returns.css';

const names = { REUSABLE: 'Reusable', NEEDS_CLEANING: 'Needs cleaning', DAMAGED: 'Damaged' };
const destinations = { REUSABLE: 'Reusable storage', NEEDS_CLEANING: 'Cleaning', DAMAGED: 'Damaged storage' };
const label = (value: string) => value.replaceAll('_', ' ').toLowerCase().replace(/^./, value => value.toUpperCase());
const date = (value?: string | null) => value ? new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' }) : 'Not yet recorded';
const reasonText = (value: string) => ({
  'CommandOutcomeUnknown.v1': 'The physical outcome needs investigation.',
  'CommandRejected.v1': 'The simulator rejected this movement before execution.',
  LANE_BLOCKED: 'The returns lane is currently blocked.',
  EQUIPMENT_STALE: 'Waiting for fresh equipment observations.',
  ADAPTER_UNAVAILABLE: 'The equipment adapter is temporarily unavailable.',
  RETURN_OWNERSHIP_MISMATCH: 'The recorded sorting assignment needs investigation.',
  EQUIPMENT_HISTORY_UNCERTAIN: 'The retained equipment history needs investigation.',
  RETURN_ROUTE_WAITING: 'Waiting for the returns route to become available.',
} as Record<string, string>)[value] ?? label(value);
function State({ value }: { value: string }) { return <span className={`status ${value === 'COMPLETED' ? 'good' : ['RECONCILIATION_REQUIRED', 'OUTCOME_UNKNOWN', 'QUARANTINED', 'BLOCKED'].includes(value) ? 'attention' : 'neutral'}`}><i/>{label(value)}</span>; }

export function ReturnsPanel({ site, refreshToken }: { site: string; refreshToken: number }) {
  const [page, setPage] = useState<ReceiptPage>();
  const [counters, setCounters] = useState<ReturnCounters>();
  const [cursor, setCursor] = useState<string>();
  const [selected, setSelected] = useState<Receipt>();
  const [command, setCommand] = useState<Command>();
  const [error, setError] = useState<string>();
  const [detailError, setDetailError] = useState<string>();
  const [observedAt, setObservedAt] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);
  const selection = useRef<string | undefined>(undefined);
  const commandId = useRef<string | undefined>(undefined);
  const dialog = useRef<HTMLDialogElement>(null);
  const supervisor = identity.hasRealmRole('supervisor');
  useEffect(() => {
    let cancelled = false; let timer: ReturnType<typeof setTimeout>; let backoff = 5000;
    const poll = async () => {
      setLoading(true);
      try {
        const prefix = sitePath(site);
        const [next, totals] = await Promise.all([get<ReceiptPage>(`${prefix}/return-receipts?limit=25${cursor ? `&cursor=${cursor}` : ''}`), get<ReturnCounters>(`${prefix}/return-counters`)]);
        if (cancelled) return;
        setPage(next); setCounters(totals); setObservedAt(new Date().toISOString()); setError(undefined); backoff = 5000;
        const id = selection.current, movement = commandId.current;
        if (id) {
          const current = await get<Receipt>(`${prefix}/return-receipts/${id}`);
          if (!cancelled && selection.current === id) setSelected(current);
        }
        if (movement) {
          const current = await get<Command>(`${prefix}/commands/${movement}`);
          if (!cancelled && commandId.current === movement) setCommand(current);
        }
      } catch (failure) { if (!cancelled) { setError(failure instanceof Error ? failure.message : 'Returns observations are unavailable.'); backoff = Math.min(backoff * 2, 30000); } }
      finally { if (!cancelled) { setLoading(false); timer = setTimeout(poll, backoff); } }
    };
    void poll(); return () => { cancelled = true; clearTimeout(timer); };
  }, [site, cursor, refreshToken, refresh]);
  useEffect(() => { if (selected && !dialog.current?.open) dialog.current?.showModal(); }, [selected]);
  const open = (receipt: Receipt) => { selection.current = receipt.id; commandId.current = undefined; setSelected(receipt); setCommand(undefined); setDetailError(undefined); setRefresh(value => value + 1); };
  const close = () => { selection.current = undefined; commandId.current = undefined; setSelected(undefined); setCommand(undefined); };
  const inspect = async (id: string) => {
    commandId.current = id; setCommand(undefined); setDetailError(undefined);
    try { const value = await get<Command>(`${sitePath(site)}/commands/${id}`); if (commandId.current === id) setCommand(value); }
    catch (failure) { if (commandId.current === id) { commandId.current = undefined; setDetailError(failure instanceof ApiError && failure.status === 404 ? 'No physical command is recorded yet. The movement remains in the returns queue.' : failure instanceof Error ? failure.message : 'Command observations are unavailable.'); } }
  };
  return <div className="returns-panel">
    {error && <div className="notice" role="alert"><strong>Returns observations are unavailable.</strong> {error} Retained counts may be stale.</div>}
    <div className="observation-line"><span className={`dot ${error ? 'amber' : ''}`}/>{observedAt ? `Returns refreshed ${date(observedAt)}` : 'Waiting for returns observations'}<span>Times shown in {Intl.DateTimeFormat().resolvedOptions().timeZone}</span></div>
    <section className="return-destinations" aria-label="Site crate counters">
      {(['REUSABLE', 'NEEDS_CLEANING', 'DAMAGED'] as const).map((classification, index) => {
        const count = counters?.classifications.find(item => item.classification === classification);
        return <article className={`panel return-destination destination-${index}`} key={classification}><div className="destination-heading"><span className="eyebrow">DESTINATION 0{index + 1}</span><span className="crate-symbol" aria-hidden="true">▤</span></div><h2>{names[classification]}</h2><div className="return-count"><strong>{count?.sorted.toLocaleString() ?? '—'}</strong><span>crates sorted</span></div><div className="counter-detail"><span>Received <b>{count?.received.toLocaleString() ?? '—'}</b></span><span>Outstanding <b>{count?.outstanding.toLocaleString() ?? '—'}</b></span></div></article>;
      })}
    </section>
    <section className="panel return-register"><div className="panel-heading"><div><span className="eyebrow">REUSABLE CRATE RETURNS</span><h2>Receipt register</h2></div><span className="muted">{page?.items.length ?? 0} receipts on this page</span></div><div className="table-wrap"><table><thead><tr><th>Return reference</th><th>Received</th><th>Sorted</th><th>State</th><th>Registered</th></tr></thead><tbody>{page?.items.map(receipt => <tr key={receipt.id}><td><button className="row-link" onClick={() => open(receipt)}>{receipt.externalReceiptRef}</button><small>{receipt.id.slice(0, 8)}</small></td><td>{receipt.counts.reduce((sum, count) => sum + count.received, 0)}</td><td>{receipt.counts.reduce((sum, count) => sum + count.sorted, 0)}</td><td><State value={receipt.state}/></td><td className="muted">{new Date(receipt.createdAt).toLocaleTimeString()}</td></tr>)}</tbody></table>{!page?.items.length && <div className="empty"><strong>{loading ? 'Loading return receipts…' : 'No receipts on this page'}</strong><span>Received crates appear here with their classification and sorting progress.</span></div>}</div><div className="pagination"><button className="secondary" disabled={!cursor} onClick={() => setCursor(undefined)}>First page</button><button className="secondary" disabled={!page?.nextCursor} onClick={() => setCursor(page?.nextCursor ?? undefined)}>Next page →</button></div><div className="panel-foot">Sorted totals increase after confirmed movement to each destination.</div></section>
    <dialog ref={dialog} onClose={close} aria-labelledby="receipt-detail-title"><div className="dialog-header"><div><span className="eyebrow">RETURN RECEIPT</span><h2 id="receipt-detail-title">{selected?.externalReceiptRef}</h2></div><button className="icon-button" aria-label="Close receipt details" onClick={() => dialog.current?.close()}>✕</button></div>{selected && <div className="dialog-body"><div className="detail-summary"><State value={selected.state}/><span>Registered {date(selected.createdAt)}</span></div><h3>Classification totals</h3><div className="table-wrap"><table><thead><tr><th>Classification</th><th>Received</th><th>Sorted</th><th>Outstanding</th></tr></thead><tbody>{selected.counts.map(count => <tr key={count.classification}><td>{names[count.classification]}</td><td>{count.received}</td><td>{count.sorted}</td><td>{count.received - count.sorted}</td></tr>)}</tbody></table></div><h3>Sorting movements</h3><div className="return-movements">{selected.movements.map(movement => <section className="return-movement" key={movement.movementId}><div><strong>{destinations[movement.classification]}</strong><State value={movement.taskState ?? movement.state}/></div><p>{movement.movement.quantity} {movement.movement.quantity === 1 ? 'crate' : 'crates'} · <span>{movement.movementId.slice(0, 8)}</span></p>{movement.lastError && <p className="return-error">{reasonText(movement.lastError)}</p>}<button className="text-button" onClick={() => void inspect(movement.movementId)}>Inspect movement evidence →</button>{movement.transportPaused && <RecoveryForm site={site} path={`return-tasks/${movement.taskId}/recovery`} version={movement.taskVersion!} supervisor={supervisor} title="Connection attempts paused" description="The connection retry budget is exhausted. A supervisor can request another investigation of the original movement." action="Resume status investigation" onRecorded={() => setRefresh(value => value + 1)}/>}</section>)}</div>{detailError && <div className="notice" role="status">{detailError}</div>}{command && <section className="command-proof"><span className="eyebrow">MOVEMENT EVIDENCE</span><h3>{command.state === 'COMPLETED' ? 'Confirmed sorting movement' : 'Recorded physical command'}</h3><dl><div><dt>State</dt><dd><State value={command.state}/></dd></div><div><dt>Sorting owner</dt><dd>Returns service · epoch {command.epoch}</dd></div><div><dt>Destination</dt><dd>{String(command.payload.destination)}</dd></div><div><dt>Physical execution sequence</dt><dd>{command.evidence?.executionSequence ?? 'Not yet confirmed'}</dd></div><div><dt>Completed</dt><dd>{date(command.completedAt)}</dd></div></dl>{['OUTCOME_UNKNOWN', 'QUARANTINED'].includes(command.state) && <RecoveryForm key={command.commandId} site={site} path={`commands/${command.commandId}/reconciliation`} version={command.version} supervisor={supervisor} title="Physical outcome needs investigation" description="The recorded command must be checked against the simulator’s retained history before this movement can proceed." action="Request reconciliation" onRecorded={() => setRefresh(value => value + 1)}/>}</section>}<p className="detail-id">Receipt {selected.id}</p></div>}</dialog>
  </div>;
}

function RecoveryForm({ site, path, version, supervisor, title, description, action, onRecorded }: { site: string; path: string; version: number; supervisor: boolean; title: string; description: string; action: string; onRecorded: () => void }) {
  const [reason, setReason] = useState(''); const [busy, setBusy] = useState(false); const [result, setResult] = useState<string>();
  const pending = useRef<{ key: string; body: { expectedVersion: number; reason: string } } | undefined>(undefined);
  const submit = async () => {
    const attempt = pending.current ?? { key: crypto.randomUUID(), body: { expectedVersion: version, reason: reason.trim() } }; pending.current = attempt;
    setBusy(true); setResult(undefined);
    try { await post(`${sitePath(site)}/${path}`, attempt.body, attempt.key); pending.current = undefined; setReason(''); setResult('The investigation request was recorded. Refreshing current evidence.'); onRecorded(); }
    catch (failure) {
      if (failure instanceof ApiError && failure.status < 500) pending.current = undefined;
      setResult(failure instanceof ApiError && failure.status === 409 ? 'This resource changed. Review its refreshed state before requesting another investigation.' : failure instanceof Error ? failure.message : 'The request could not be confirmed. Retry to check the same request.'); onRecorded();
    } finally { setBusy(false); }
  };
  return <section className="cancellation-panel"><h3>{title}</h3><p>{description}</p>{supervisor ? <form onSubmit={event => { event.preventDefault(); void submit(); }}><label>Reason for investigation<textarea value={reason} minLength={8} maxLength={500} required disabled={busy} onChange={event => { setReason(event.target.value); if (!busy) pending.current = undefined; }} placeholder="Describe the correction or evidence to investigate."/></label><button className="secondary" disabled={busy || reason.trim().length < 8}>{busy ? 'Recording request…' : action}</button></form> : <p>A supervisor can request this investigation.</p>}{result && <div className="notice" role="status">{result}</div>}</section>;
}
