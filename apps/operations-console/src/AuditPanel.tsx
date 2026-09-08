import { useCallback, useState } from 'react';
import { get, sitePath } from './api';
import type { AuditPage } from './api';
import { useObservation } from './useObservation';
import './work.css';

const owners = { adapter: 'Equipment adapter', core: 'Legacy core', execution: 'Fulfilment execution', returns: 'Crate returns' };
export function AuditPanel({ site, refreshToken }: { site: string; refreshToken: number }) {
  const [owner, setOwner] = useState<keyof typeof owners>('adapter'), [cursor, setCursor] = useState<string>();
  const [resource, setResource] = useState(''), [filter, setFilter] = useState('');
  const queryKey = `${owner}:${cursor ?? ''}:${filter}`;
  const load = useCallback(async () => ({ queryKey, page: await get<AuditPage>(`${sitePath(site)}/audit/${owner}?limit=25${cursor ? `&cursor=${cursor}` : ''}${filter ? `&resource=${encodeURIComponent(filter)}` : ''}`) }), [site, owner, cursor, filter, queryKey]);
  const observed = useObservation(load, refreshToken), page = observed.data?.queryKey === queryKey ? observed.data.page : undefined;
  return <section className="panel audit-panel" aria-labelledby="audit-title">
    <div className="panel-heading"><div><span className="eyebrow">RECORDED OPERATIONS</span><h2 id="audit-title">Audit history</h2></div><span className="quiet-tag">Newest first · {page?.items.length ?? 0} on this page</span></div>
    <form className="work-filters" onSubmit={event => { event.preventDefault(); setFilter(resource.trim()); setCursor(undefined); }}>
      <label>Record owner<select value={owner} onChange={event => { setOwner(event.target.value as keyof typeof owners); setCursor(undefined); }}>{Object.entries(owners).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      <label>Resource ID<input type="search" maxLength={128} value={resource} onChange={event => setResource(event.target.value)} placeholder="Optional exact resource reference"/></label><button className="secondary">Filter audit</button>
      {(filter || cursor) && <button type="button" className="row-link" onClick={() => { setResource(''); setFilter(''); setCursor(undefined); }}>Clear audit filters</button>}
    </form>
    {observed.error && <div className="notice" role="alert">Audit observations are unavailable. {observed.error} Retained records may be stale.</div>}
    <div className="table-wrap"><table><thead><tr><th>Action / time</th><th>Actor / resource</th><th>Reason</th><th>Version</th><th>Outcome</th></tr></thead><tbody>{page?.items.map(item => <tr key={item.id}><td>{item.action}<small>{new Date(item.occurredAt).toLocaleString()}</small></td><td className="audit-reference">{item.actor}<small>{item.resourceId}</small></td><td>{item.reason}</td><td>{item.beforeVersion ?? '—'} → {item.afterVersion ?? '—'}</td><td>{item.outcome}</td></tr>)}</tbody></table></div>
    {!page?.items.length && <div className="empty" role="status"><strong>{observed.loading ? 'Loading audit records…' : 'No matching audit records'}</strong><span>{observed.error ? 'The latest audit state could not be verified.' : 'Consequential actions retain their actor, reason, versions and recorded outcome.'}</span></div>}
    <div className="panel-foot audit-pagination"><button className="secondary" disabled={!cursor || observed.loading} onClick={() => setCursor(undefined)}>First audit page</button><span>Observed {page ? new Date(page.observedAt).toLocaleTimeString() : 'not yet'}</span><button className="secondary" disabled={!page?.nextCursor || observed.loading} onClick={() => setCursor(page?.nextCursor ?? undefined)}>Next audit page →</button></div>
  </section>;
}
