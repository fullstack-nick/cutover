import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, get, identity, sitePath } from './api';
import type { Command, CommandTimeline, Task, ExecutionTask, ReturnTask } from './api';
import { useObservation } from './useObservation';
import { RecoveryAction } from './RecoveryAction';
import './work.css';

type Work = Task | ExecutionTask | ReturnTask;
type RecoveryItem = { commandId: string; owner: string; state: string; version: number; failureAttempts: number; lastError: string | null; createdAt: string };
type RecoveryQueue = { observedAt: string; items: RecoveryItem[] };
type Selection = { movement: string; task?: Work };
const label = (value: string) => value.replace(/([a-z])([A-Z])/g, '$1 $2').replaceAll('_', ' ').replaceAll('-', ' ').toLowerCase().replace(/^./, value => value.toUpperCase());
const date = (value?: string | null) => value ? new Date(value).toLocaleString() : 'Not recorded';
const finished = (state: string) => ['COMPLETED', 'CANCELLED', 'REJECTED_BEFORE_EXECUTION'].includes(state);
const routes: Record<string, string> = { 'legacy-core': 'tasks', 'execution-service': 'execution-tasks', 'returns-service': 'return-tasks' };
function State({ value }: { value: string }) { return <span className={`status ${finished(value) ? 'good' : ['BLOCKED', 'QUARANTINED', 'OUTCOME_UNKNOWN', 'RECONCILIATION_REQUIRED'].includes(value) ? 'attention' : 'neutral'}`}><i/>{label(value)}</span>; }

function CommandDetail({ site, selection, onClose, onRefresh }: { site: string; selection: Selection; onClose: () => void; onRefresh: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null), [refresh, setRefresh] = useState(0);
  const load = useCallback(async () => {
    let task = selection.task, taskUnavailable = false;
    if (task) try { task = await get<Work>(`${sitePath(site)}/${routes[task.owner]}/${task.id}`); }
    catch (failure) { if (failure instanceof ApiError && failure.status === 404) taskUnavailable = true; else throw failure; }
    let command: Command;
    try { command = await get<Command>(`${sitePath(site)}/commands/${selection.movement}`); }
    catch (failure) { if (failure instanceof ApiError && failure.status === 404) return { queued: true as const, task, taskUnavailable }; throw failure; }
    let timeline: CommandTimeline | undefined;
    try { timeline = await get<CommandTimeline>(`${sitePath(site)}/commands/${selection.movement}/timeline`); }
    catch (failure) { if (!(failure instanceof ApiError && failure.status === 404)) throw failure; }
    return { queued: false as const, command, timeline, task, taskUnavailable };
  }, [site, selection.movement, selection.task]);
  const observation = useObservation(load, refresh), command = observation.data?.queued === false ? observation.data.command : undefined;
  const timeline = observation.data?.queued === false ? observation.data.timeline : undefined;
  const task = observation.data?.task ?? selection.task;
  useEffect(() => { dialog.current?.showModal(); }, []);
  const refreshEvidence = () => { setRefresh(value => value + 1); onRefresh(); };
  return <dialog className="work-detail" ref={dialog} onClose={onClose} aria-labelledby="work-detail-title">
    <div className="dialog-header"><div><span className="eyebrow">MOVEMENT EVIDENCE</span><h2 id="work-detail-title">Task and command timeline</h2></div><button className="icon-button" aria-label="Close task details" onClick={() => dialog.current?.close()}>×</button></div>
    <div className="dialog-body">
      <p className="detail-id">Movement {selection.movement}</p>
      {task && <div className="detail-summary"><State value={task.state}/><span>{task.owner} · epoch {task.epoch}</span><span>Task version {task.version}</span></div>}
      {observation.data?.taskUnavailable && <p className="notice" role="status">Current task details could not be retrieved. The last selected owner state is retained.</p>}
      {observation.error && <div className="notice" role="alert">Command observations are unavailable. {observation.error} Retained evidence may be stale.</div>}
      {!observation.data && <p role="status">{observation.loading ? 'Loading retained command evidence…' : 'No current observation is available.'}</p>}
      {observation.data?.queued && <p className="notice" role="status">No physical command is recorded yet. The task remains with its owner until the adapter accepts dispatch.</p>}
      {command && <section className="command-proof"><h3>{command.state === 'COMPLETED' ? 'Verified completion' : 'Recorded command state'}</h3><State value={command.state}/><dl>
        <div><dt>Dispatch owner</dt><dd>{command.owner} · epoch {command.epoch}</dd></div><div><dt>Command version</dt><dd>{command.version}</dd></div>
        <div><dt>Transport attempts</dt><dd>{command.attempts}</dd></div><div><dt>Simulator acceptance observed</dt><dd>{command.acceptedEver ? 'Yes' : 'Not confirmed'}</dd></div>
        <div><dt>Physical execution sequence</dt><dd>{command.evidence?.executionSequence ?? 'Not confirmed'}</dd></div><div><dt>Completion recorded</dt><dd>{date(command.completedAt)}</dd></div>
      </dl>{command.lastError && <p>{label(command.lastError)}</p>}</section>}
      {timeline && <section aria-label="Retained movement timeline"><h3>Retained movement timeline</h3><p>Command journal recorded {date(timeline.journalRecordedAt)}. Observed {date(timeline.observedAt)}.</p>
        {!timeline.historyComplete && <p className="notice" role="status">Part of this event history is unavailable. The retained command proof remains authoritative. Event payload retention is {timeline.retentionDays} days.</p>}
        <ol className="event-timeline">{timeline.events.map(event => <li key={event.id}><div><strong>{label(event.type.replace(/\.v\d+$/, ''))}</strong><span>Version {event.version}{event.state ? ` · ${label(event.state)}` : ''}</span></div><time dateTime={event.at}>{date(event.at)}</time></li>)}</ol>
      </section>}
      {command && !timeline && <p className="notice" role="status">The detailed timeline is unavailable. Inspect the retained command proof above.</p>}
      {identity.hasRealmRole('supervisor') && command && !finished(command.state) && <RecoveryAction key={command.commandId} path={`${sitePath(site)}/commands/${command.commandId}/reconciliation`} version={command.version} onRefresh={refreshEvidence}/>}
      {identity.hasRealmRole('supervisor') && !observation.data?.taskUnavailable && task?.transportPaused === true && <RecoveryAction path={`${sitePath(site)}/${routes[task.owner]}/${task.id}/recovery`} version={task.version} title="Resume task status investigation" onRefresh={refreshEvidence}/>}
      {!identity.hasRealmRole('supervisor') && (command && !finished(command.state) || task?.transportPaused === true) && <p>Supervisor authorization is required to request investigation.</p>}
      <button className="secondary" onClick={refreshEvidence} disabled={observation.loading}>Refresh evidence</button>
    </div>
  </dialog>;
}

export function WorkPanel({ site, mode, refreshToken }: { site: string; mode: 'tasks' | 'recovery'; refreshToken: number }) {
  const [refresh, setRefresh] = useState(0), [selection, setSelection] = useState<Selection>(), [activeOnly, setActiveOnly] = useState(true);
  const load = useCallback(async () => {
    if (mode === 'recovery') return { tasks: [] as Work[], recovery: (await get<RecoveryQueue>(`${sitePath(site)}/commands`)).items };
    const [legacy, execution, returns] = await Promise.all([get<Task[]>(`${sitePath(site)}/tasks`), get<ExecutionTask[]>(`${sitePath(site)}/execution-tasks`), get<ReturnTask[]>(`${sitePath(site)}/return-tasks`)]);
    return { tasks: [...legacy, ...execution, ...returns], recovery: [] as RecoveryItem[] };
  }, [site, mode]);
  const observed = useObservation(load, refreshToken + refresh), tasks = (observed.data?.tasks ?? []).filter(task => !activeOnly || !finished(task.state));
  const recovery = observed.data?.recovery ?? [];
  return <section className="panel work-panel" aria-labelledby="work-panel-title">
    <div className="panel-heading"><div><span className="eyebrow">{mode === 'tasks' ? 'PRODUCT COORDINATION' : 'ADAPTER COMMAND JOURNAL'}</span><h2 id="work-panel-title">{mode === 'tasks' ? 'Task queues' : 'Commands needing investigation'}</h2></div><span className="quiet-tag">Observed {date(observed.observedAt)}</span></div>
    {observed.error && <div className="notice" role="alert">Work observations are unavailable. {observed.error} Retained rows may be stale.</div>}
    {mode === 'tasks' && <div className="work-filters"><label><input type="checkbox" checked={activeOnly} onChange={event => setActiveOnly(event.target.checked)}/> Show active tasks</label><span>Up to 100 tasks per owner; active work is listed before recent completed work.</span></div>}
    <div className="table-wrap"><table><thead><tr><th>Movement</th><th>Owner</th><th>State</th><th>{mode === 'tasks' ? 'Zone / epoch' : 'Failed investigations'}</th><th>Observation</th></tr></thead><tbody>
      {mode === 'tasks' ? tasks.map(task => <tr key={`${task.owner}:${task.id}`}><td><button className="row-link" onClick={() => setSelection({ movement: task.movementId, task })}>{task.movementId.slice(0, 8)}</button><small>Task {task.id.slice(0, 8)}</small></td><td>{task.owner}</td><td><State value={task.state}/></td><td>{task.zoneId} · {task.epoch}</td><td>{task.transportPaused ? 'Transport paused · investigation available' : task.lastError ? label(task.lastError) : 'No recorded error'}</td></tr>) : recovery.map(item => <tr key={item.commandId}><td><button className="row-link" onClick={() => setSelection({ movement: item.commandId })}>{item.commandId.slice(0, 8)}</button><small>Recorded {date(item.createdAt)}</small></td><td>{item.owner}</td><td><State value={item.state}/></td><td>{item.failureAttempts}</td><td>{item.lastError ? label(item.lastError) : 'Status investigation required'}</td></tr>)}
    </tbody></table></div>
    {!(mode === 'tasks' ? tasks : recovery).length && <div className="empty" role="status"><strong>{!observed.data && observed.loading ? 'Loading work…' : mode === 'tasks' ? 'No matching tasks in the observed queues' : 'No commands currently need investigation'}</strong><span>{observed.error ? 'The current state could not be verified.' : mode === 'tasks' ? 'Completed work can be included using the filter.' : 'Unknown outcomes remain held until evidence resolves them.'}</span></div>}
    <div className="panel-foot">{mode === 'tasks' ? 'Task state comes from its owning service. Equipment evidence comes from the adapter.' : 'Up to 100 unresolved commands, oldest first. A retry never creates another physical command.'}</div>
    {selection && <CommandDetail key={selection.movement} site={site} selection={selection} onClose={() => setSelection(undefined)} onRefresh={() => setRefresh(value => value + 1)}/>}
  </section>;
}
