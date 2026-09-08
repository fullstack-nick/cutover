import { useEffect, useState } from 'react';
import { get, sitePath } from './api';
import type { Order, OrderPage } from './api';
import './orders.css';

const label = (value: string) => value.replaceAll('_', ' ').toLowerCase().replace(/^./, value => value.toUpperCase());
export function OrdersPanel({ site, refreshToken, onOpen }: { site: string; refreshToken: number; onOpen: (order: Order) => void }) {
  const [page, setPage] = useState<OrderPage>();
  const [reference, setReference] = useState('');
  const [search, setSearch] = useState('');
  const [shortagesOnly, setShortagesOnly] = useState(false);
  const [cursor, setCursor] = useState<string>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let cancelled = false; let timer: ReturnType<typeof setTimeout>; let backoff = 5000;
    const poll = async () => {
      setLoading(true);
      try {
        const params = new URLSearchParams({ limit: '25', ...(search ? { reference: search } : {}), ...(shortagesOnly ? { shortagesOnly: 'true' } : {}), ...(cursor ? { cursor } : {}) });
        const result = await get<OrderPage>(`${sitePath(site)}/orders?${params}`);
        if (!cancelled) { setPage(result); setError(undefined); backoff = 5000; }
      } catch (failure) { if (!cancelled) { setError(failure instanceof Error ? failure.message : 'Order observations are unavailable.'); backoff = Math.min(backoff * 2, 30000); } }
      finally { if (!cancelled) { setLoading(false); timer = setTimeout(poll, backoff); } }
    };
    void poll(); return () => { cancelled = true; clearTimeout(timer); };
  }, [site, search, shortagesOnly, cursor, refreshToken, refresh]);
  const resetPage = () => { setCursor(undefined); setPage(undefined); setRefresh(value => value + 1); };
  return <section className="panel order-panel">
    <div className="panel-heading"><div><span className="eyebrow">OUTBOUND FULFILMENT</span><h2>Order register</h2></div><span className="muted">Newest first · {page?.items.length ?? 0} on this page</span></div>
    <form className="order-filters" onSubmit={event => { event.preventDefault(); setSearch(reference.trim()); resetPage(); }}>
      <label className="reference-filter">Order reference<input type="search" value={reference} maxLength={128} onChange={event => setReference(event.target.value)} placeholder="Find a full or partial reference"/></label>
      <button className="secondary" type="submit">Search orders</button>
      <label className="shortage-filter"><input type="checkbox" checked={shortagesOnly} onChange={event => { setShortagesOnly(event.target.checked); resetPage(); }}/>Has unreserved units</label>
      {(search || shortagesOnly) && <button className="text-button" type="button" onClick={() => { setReference(''); setSearch(''); setShortagesOnly(false); resetPage(); }}>Clear filters</button>}
    </form>
    {error && <div className="notice" role="alert"><strong>Order observations are unavailable.</strong> {error} Retained rows may be stale.</div>}
    <div className="table-wrap" aria-busy={loading}><table><thead><tr><th>Order reference</th><th>Store</th><th>Reserved</th><th>Short</th><th>State</th><th>Received</th></tr></thead><tbody>{page?.items.map(order => {
      const short = order.lines.reduce((sum, line) => sum + line.shortage, 0);
      return <tr key={order.id}><td><button className="row-link" onClick={() => onOpen(order)}>{order.externalOrderRef}</button><small>{order.id.slice(0, 8)}</small></td><td>{order.storeId}</td><td>{order.lines.reduce((sum, line) => sum + line.reserved, 0)}</td><td className={short ? 'shortage' : ''}>{short}</td><td><span className={`status ${short ? 'attention' : order.state === 'COMPLETED' ? 'good' : 'neutral'}`}><i/>{label(order.state)}</span></td><td className="muted">{new Date(order.createdAt).toLocaleString()}</td></tr>;
    })}</tbody></table>{!page?.items.length && <div className="empty"><strong>{loading ? 'Loading orders…' : 'No matching orders'}</strong><span>{search || shortagesOnly ? 'Change or clear the filters to see more work.' : 'Orders appear after the site receives work.'}</span></div>}</div>
    <div className="pagination"><button className="secondary" disabled={!cursor} onClick={() => resetPage()}>First page</button><span className="muted" role="status">{page?.observedAt ? `Observed ${new Date(page.observedAt).toLocaleTimeString()}` : 'Waiting for the order register'}</span><button className="secondary" disabled={!page?.nextCursor || loading} onClick={() => { setCursor(page?.nextCursor ?? undefined); setPage(undefined); }}>Next page →</button></div>
    <div className="panel-foot">Shortage filters search the whole site register. Inventory changes only after confirmed physical movement.</div>
  </section>;
}
