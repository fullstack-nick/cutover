import { ShadowPanel } from './ShadowPanel';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, get, identity, post, sitePath } from './api';
import type { Migration, MigrationList, ZoneRoute } from './api';

const names: Record<string, string> = { 'legacy-core': 'Legacy coordinator', 'execution-service': 'Execution service', 'returns-service': 'Returns coordinator' };
const label = (value: string) => names[value] ?? value.replaceAll('_', ' ').toLowerCase().replace(/^./, x => x.toUpperCase());
const timestamp = (value?: string | null) => value ? new Date(value).toLocaleString() : 'Not recorded';
const phases = ['DRAINING', 'RECONCILING', 'READY_TO_SWITCH', 'OBSERVING', 'COMPLETED'];
const unswitched = (session: Migration) => ['DRAINING', 'RECONCILING', 'READY_TO_SWITCH'].includes(session.phase);

type Action = { path: string; version: number; title: string; description: string; target?: string };
function SupervisorAction({ action, done }: { action: Action; done: () => Promise<void> }) {
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const retained = useRef<{ key: string; body: { expectedVersion: number; reason: string; targetOwner?: string } } | undefined>(undefined);
  const submit = async () => {
    setSubmitting(true); setMessage(undefined);
    const request = retained.current ?? { key: crypto.randomUUID(), body: { expectedVersion: action.version, reason: reason.trim(), ...(action.target ? { targetOwner: action.target } : {}) } };
    retained.current = request;
    try {
      await post<Migration>(action.path, request.body, request.key);
      retained.current = undefined; setReason(''); setMessage('Request recorded. The session below shows its current progress.');
    } catch (failure) {
      const definitive = failure instanceof ApiError && failure.status < 500;
      if (definitive) retained.current = undefined;
      setMessage(`${failure instanceof Error ? failure.message : 'The response was interrupted.'} ${definitive ? 'Refresh the evidence before submitting again.' : 'Use Retry original request to learn its recorded outcome.'}`);
    } finally { await done(); setSubmitting(false); }
  };
  return <form className="migration-action" onSubmit={event => { event.preventDefault(); void submit(); }}>
    <h3>{action.title}</h3><p>{action.description}</p>
    <label>Reason<textarea required minLength={8} maxLength={500} rows={2} value={reason} disabled={submitting || !!retained.current} onChange={event => setReason(event.target.value)}/></label>
    <button className="secondary" disabled={submitting || reason.trim().length < 8}>{submitting ? 'Recording request…' : retained.current ? 'Retry original request' : `Confirm ${action.title.toLowerCase()}`}</button>
    {message && <p role="status" className="notice">{message}</p>}
  </form>;
}

export function MigrationsPanel({ site }: { site: string }) {
  const [routes, setRoutes] = useState<ZoneRoute[]>([]);
  const [sessions, setSessions] = useState<MigrationList>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(false);
  const busy = useRef(false); const mounted = useRef(true);
  const supervisor = identity.hasRealmRole('supervisor');
  const prefix = sitePath(site);
  const refresh = useCallback(async () => {
    if (busy.current) return;
    busy.current = true; setLoading(true);
    try {
      const [nextRoutes, nextSessions] = await Promise.all([get<ZoneRoute[]>(`${prefix}/zones`), get<MigrationList>(`${prefix}/migrations`)]);
      if (mounted.current) { setRoutes(nextRoutes); setSessions(nextSessions); setError(undefined); }
    } catch (failure) { if (mounted.current) setError(failure instanceof Error ? failure.message : 'Migration observations are unavailable.'); }
    finally { busy.current = false; if (mounted.current) setLoading(false); }
  }, [prefix]);
  useEffect(() => {
    mounted.current = true; let timer: ReturnType<typeof setTimeout>;
    const poll = async () => { await refresh(); if (mounted.current) timer = setTimeout(poll, 3000); };
    void poll(); return () => { mounted.current = false; clearTimeout(timer); };
  }, [refresh]);
  return <div className="migrations-view">
    <div className="migration-intro"><div><h2>One zone. One dispatch owner.</h2><p>Allocated work finishes with its current owner. New work waits until the drain is reconciled and the next owner is active.</p></div><button className="secondary" onClick={() => void refresh()} disabled={loading}>{loading ? 'Refreshing routes…' : 'Refresh routes'}</button></div>
    {error && <div className="notice" role="alert">{error} Retained migration data may be stale. Actions are unavailable until a fresh observation succeeds.</div>}
    <p className="muted">Observed {timestamp(sessions?.observedAt)} · {supervisor ? 'Supervisor controls' : 'Read-only operator view'}</p>
    <div className="route-grid">{routes.filter(route => ['ambient', 'chilled'].includes(route.zoneId)).map(route => {
      const active = sessions?.items.find(session => session.zoneId === route.zoneId && ['DRAINING', 'RECONCILING', 'READY_TO_SWITCH', 'OBSERVING'].includes(session.phase));
      const target = route.owner === 'legacy-core' ? 'execution-service' : 'legacy-core';
      return <article className="panel route-card" key={route.zoneId}>
        <div className="route-heading"><h2>{label(route.zoneId)}</h2><span className={`status ${route.state === 'ACTIVE' ? 'good' : 'attention'}`}><i/>{label(route.state)}</span></div>
        <strong className="route-owner">{label(route.owner)}</strong><p className="muted">Ownership epoch {route.epoch} · Route version {route.version}</p>
        {active && <p>Current session: <a href={`#migration-${active.sessionId}`}>{label(active.phase)}</a></p>}
        {supervisor && !error && route.state === 'ACTIVE' && (!active || active.phase === 'OBSERVING') && <SupervisorAction key={`${site}:${route.zoneId}:${route.owner}`} done={refresh} action={{ path: `${prefix}/zones/${route.zoneId}/migrations`, version: route.version, target, title: active || route.owner === 'execution-service' ? 'Reverse ownership' : 'Start migration', description: `Drain ${label(route.zoneId).toLowerCase()}, verify every retained movement, then switch to ${label(target)}. ${active ? 'The current observation session will link to this reversal.' : 'The other outbound zone can continue working.'}` }}/>}
        {!supervisor && <p className="muted">A supervisor can start or reverse an ownership migration.</p>}
      </article>;
    })}</div>
    <div className="migration-register-heading"><h2>Migration register</h2><span className="muted">Latest {sessions?.items.length ?? 0} sessions · up to 50</span></div>
    {!sessions?.items.length && <section className="panel empty"><strong>{loading ? 'Loading sessions…' : 'No migrations recorded'}</strong><span>The current route remains authoritative until a verified session changes it.</span></section>}
    {sessions?.items.map(session => <article className="panel migration-session" key={session.sessionId} id={`migration-${session.sessionId}`}>
      <div className="route-heading"><div><span className="eyebrow">{label(session.zoneId)} · {session.sessionId.slice(0, 8)}</span><h2>{label(session.sourceOwner)} → {label(session.targetOwner)}</h2></div><span className={`status ${['COMPLETED', 'REVERSED'].includes(session.phase) ? 'good' : session.blockers.count ? 'attention' : 'neutral'}`}><i/>{label(session.phase)}</span></div>
      <ol className="migration-phases" aria-label="Migration phases">{phases.map((phase, index) => <li key={phase} aria-current={phase === session.phase ? 'step' : undefined} className={phases.indexOf(session.phase) > index ? 'passed' : ''}><span>{index + 1}</span>{label(phase)}</li>)}</ol>
      <dl className="migration-facts"><div><dt>Verified inventory</dt><dd>{session.verifiedCount} / {session.inventoryCount} movements</dd></div><div><dt>Ownership epochs</dt><dd>{session.sourceEpoch} → {session.targetEpoch ?? 'Awaiting switch'}</dd></div><div><dt>Started</dt><dd>{timestamp(session.createdAt)}</dd></div><div><dt>Switched</dt><dd>{timestamp(session.switchedAt)}</dd></div></dl>
      <p className="migration-reason"><strong>Recorded reason:</strong> {session.reason}</p>
      {session.transportPaused && <p className="notice" role="status">Evidence collection paused after {session.transportAttempts} failed transport attempts. Repair the dependency, then resume this session. Ownership safety still applies.</p>}
      {session.blockers.count > 0 && <section className="migration-blockers" aria-label="Migration blockers"><h3>{session.blockers.count} {session.blockers.count === 1 ? 'blocker' : 'blockers'}</h3><p>Waiting does not authorize a forced switch.</p><ul>{session.blockers.items.map((item, index) => <li key={`${item.movementId ?? item.reason}:${index}`}><strong>{label(item.reason)}</strong>{item.movementId && <code>{item.movementId}</code>}{item.commandState && <span>{label(item.commandState)} · {label(item.owner ?? '')}</span>}</li>)}</ul>{session.blockers.count > session.blockers.items.length && <p>Showing the first {session.blockers.items.length} blockers.</p>}</section>}
      {session.phase === 'OBSERVING' && <p>{session.observation?.withinTwoSeconds === false ? 'The original ten-movement sample missed the two-second target. It remains recorded; use an explicit ownership reversal after investigating the delay.' : 'Checking the first ten completed movements assigned to the new epoch, with a dispatch latency target of two seconds for every sampled movement.'}</p>}
      {session.phase === 'SUPERSEDED' && <p>A later reversal settled this zone. This session retains its original observation and does not claim a successful latency sample.</p>}
      {session.observation && <p className={session.observation.withinTwoSeconds ? 'migration-success' : 'notice'}>{session.observation.sampleCount} completed sample movements · observed dispatch p99 {session.observation.dispatchP99Millis} ms · {session.observation.withinTwoSeconds ? 'within target' : 'target missed; original sample retained'}</p>}
      <details className="migration-evidence"><summary>Checkpoint and audit details</summary><dl><dt>Session</dt><dd><code>{session.sessionId}</code></dd><dt>Requested by</dt><dd><code>{session.actor}</code></dd><dt>Inventory hash</dt><dd><code>{session.inventoryHash ?? 'Awaiting finite inventory'}</code></dd><dt>Checkpoint hash</dt><dd><code>{session.checkpointHash ?? 'Awaiting reconciliation'}</code></dd><dt>Physical world</dt><dd><code>{session.checkpoint?.worldId ?? 'Awaiting checkpoint'}</code></dd><dt>Journal high water</dt><dd>{session.checkpoint?.journalHighWater ?? 'Awaiting checkpoint'}</dd><dt>Finished</dt><dd>{timestamp(session.finishedAt)}</dd><dt>Session version</dt><dd>{session.version}</dd></dl>{session.reversesSessionId && <p>Reverses session <a href={`#migration-${session.reversesSessionId}`}>{session.reversesSessionId}</a></p>}</details>
      {supervisor && !error && session.transportPaused && <SupervisorAction done={refresh} action={{ path: `${prefix}/migrations/${session.sessionId}/recovery`, version: session.version, title: 'Resume evidence collection', description: 'Use after repairing the unavailable dependency. The original session and movement identities are retained.' }}/>}
      {supervisor && !error && unswitched(session) && <details className="migration-evidence"><summary>Cancel this unswitched session</summary><SupervisorAction done={refresh} action={{ path: `${prefix}/migrations/${session.sessionId}/cancellation`, version: session.version, title: 'Cancel migration', description: 'Keep the current owner and epoch. Pending unassigned movements resume with that owner; allocated work is retained.' }}/></details>}
    </article>)}
    <ShadowPanel site={site}/>
  </div>;
}
