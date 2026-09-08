import { useCallback, useState } from 'react';
import { get, sitePath } from './api';
import type { ShadowSummary, ShadowComparison } from './api';
import { useObservation } from './useObservation';
import './work.css';

function Comparison({ site, id }: { site: string; id: string }) {
  const load = useCallback(() => get<ShadowComparison>(`${sitePath(site)}/shadow-comparisons/${id}`), [site, id]);
  const observed = useObservation(load), comparison = observed.data;
  const ranks = [...new Set([...(comparison?.legacyProposal.ranking ?? []), ...(comparison?.executionProposal.ranking ?? [])].map(item => item.movementId))];
  return <section className="shadow-detail" aria-label="Stored scheduling comparison">
    {observed.error && <div className="notice" role="alert">Comparison detail is unavailable. {observed.error}</div>}
    {!comparison && <p role="status">{observed.loading ? 'Loading the stored decision inputs…' : 'No comparison is currently available.'}</p>}
    {comparison && <><h3>{comparison.matches ? 'Both schedulers produced the same decision' : 'The stored scheduler decisions differ'}</h3>
      <p>Compared {new Date(comparison.comparedAt).toLocaleString()} · {comparison.input.zoneId} · rule version {comparison.input.ruleVersion}</p>
      <p className="detail-id">Input SHA-256 {comparison.inputHash}</p>
      <div className="shadow-outputs">{[['Legacy SQL', comparison.legacyProposal], ['Extracted Java', comparison.executionProposal]].map(([name, value]) => { const proposal = value as ShadowComparison['legacyProposal']; return <div key={name as string}><strong>{name as string}</strong><p>Selected movement: {proposal.selectedMovementId ?? 'None'}</p><p>Selected lane: {proposal.selectedLaneId ?? 'None'}</p></div>; })}</div>
      <div className="table-wrap"><table><thead><tr><th>Movement</th><th>Legacy rank / decision</th><th>Extracted rank / decision</th><th>Same ranking entry</th></tr></thead><tbody>{ranks.map(movement => {
        const oldIndex = comparison.legacyProposal.ranking.findIndex(item => item.movementId === movement), newIndex = comparison.executionProposal.ranking.findIndex(item => item.movementId === movement);
        const old = comparison.legacyProposal.ranking[oldIndex], next = comparison.executionProposal.ranking[newIndex], same = oldIndex === newIndex && JSON.stringify(old) === JSON.stringify(next);
        return <tr key={movement}><td className="audit-reference">{movement}</td><td>{old ? `${oldIndex + 1} · ${old.reason} · ${old.laneId ?? 'no lane'}` : 'Absent'}</td><td>{next ? `${newIndex + 1} · ${next.reason} · ${next.laneId ?? 'no lane'}` : 'Absent'}</td><td><span className={`status ${same ? 'good' : 'attention'}`}>{same ? 'Yes' : 'Difference'}</span></td></tr>;
      })}</tbody></table></div>
      <details className="migration-evidence"><summary>Exact stored scheduling input</summary><pre className="snapshot-input">{JSON.stringify(comparison.input, null, 2)}</pre></details>
    </>}
  </section>;
}

export function ShadowPanel({ site }: { site: string }) {
  const load = useCallback(() => get<ShadowSummary>(`${sitePath(site)}/shadow-comparisons`), [site]);
  const observed = useObservation(load), [selected, setSelected] = useState<string>(), [round, setRound] = useState('');
  return <section className="panel shadow-panel" aria-labelledby="shadow-title">
    <div className="panel-heading"><div><span className="eyebrow">DETERMINISTIC COMPARISON</span><h2 id="shadow-title">Shadow decisions</h2></div><span className="quiet-tag">{observed.data?.compared ?? 0} compared · {observed.data?.mismatches ?? 0} differences</span></div>
    {observed.error && <div className="notice" role="alert">Shadow observations are unavailable. {observed.error} Retained comparisons are historical evidence.</div>}
    <p className="shadow-explanation">These decisions compare identical stored inputs. They do not authorize shadow dispatch or establish agreement for unobserved work.</p>
    <form className="work-filters" onSubmit={event => { event.preventDefault(); if (/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(round)) setSelected(round); }}><label>Stored round ID<input type="search" value={round} onChange={event => setRound(event.target.value)} required pattern="[a-fA-F0-9-]{36}" maxLength={36}/></label><button className="secondary">Inspect round</button></form>
    <div className="table-wrap"><table><thead><tr><th>Stored round</th><th>Comparison</th><th>Rule</th><th>Compared</th></tr></thead><tbody>{observed.data?.items.map(item => <tr key={item.roundId}><td><button className="row-link" onClick={() => { setSelected(item.roundId); setRound(item.roundId); }}>{item.roundId.slice(0, 8)}</button></td><td><span className={`status ${item.matches ? 'good' : 'attention'}`}>{item.matches ? 'Same decision' : 'Difference'}</span></td><td>{item.ruleVersion}</td><td>{new Date(item.comparedAt).toLocaleString()}</td></tr>)}</tbody></table></div>
    {!observed.data?.items.length && <div className="empty" role="status">{observed.loading ? 'Loading comparisons…' : 'No stored comparisons are available for this site.'}</div>}
    {selected && <Comparison key={`${site}:${selected}`} site={site} id={selected}/>}
    <div className="panel-foot">Latest 50 stored rounds. Inspect a known round ID to retrieve older retained evidence.</div>
  </section>;
}
