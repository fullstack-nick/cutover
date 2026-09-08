import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
process.env.CUTOVER_PROFILE = 'demo';
const { root, query, saveEvidence } = await import('./client.mjs');
const runId = 'wal-idle-' + Date.now(), evidence = { snapshots: [], profile: 'Twenty-second idle database diagnostic; no final load/SLO claim.' };
const scope = ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover', '-n', 'cutover-platform'];
function capture(args) {
  const result = spawnSync('kubectl', [...scope, ...args], { encoding: 'utf8', windowsHide: true, timeout: 15000 });
  assert.equal(result.status, 0, 'Scoped database statistics inspection must succeed.'); return result.stdout;
}
try {
  const pod = JSON.parse(capture(['get', 'pod', 'application-db-0', '-o', 'json']));
  assert.equal(pod.metadata.labels['app.kubernetes.io/part-of'], 'cutover'); evidence.podUid = pod.metadata.uid;
  assert.equal(query('adapter', "SELECT count(*) FROM movement_allocations WHERE state IN ('PENDING','ASSIGNED');"), '0');
  for (let index = 0; index < 5; index++) {
    // Local statistics only, under the named project database administrator.
    // No business-table writes, counter reset, or durability-setting changes.
    evidence.snapshots.push(JSON.parse(capture(['exec', 'application-db-0', '--', 'psql', '-X', '-U', 'postgres', '-d', 'postgres', '-Atc',
      "SELECT jsonb_build_object('at',clock_timestamp(),'timingEnabled',current_setting('track_wal_io_timing'),'fsyncEnabled',current_setting('fsync'),'synchronousCommit',current_setting('synchronous_commit'),'io',(SELECT row_to_json(s) FROM pg_stat_io s WHERE object='wal' AND context='normal' AND backend_type='client backend'),'wal',(SELECT row_to_json(s) FROM pg_stat_wal s));"])));
    if (index < 4) await delay(5000);
  }
  const first = evidence.snapshots[0], last = evidence.snapshots.at(-1);
  assert.ok(evidence.snapshots.every(s => s.timingEnabled === 'on' && s.fsyncEnabled === 'on' && s.synchronousCommit === 'on'));
  assert.equal(first.io.stats_reset, last.io.stats_reset);
  evidence.elapsedMillis = Date.parse(last.at) - Date.parse(first.at);
  evidence.fsyncs = last.io.fsyncs - first.io.fsyncs; evidence.fsyncMillis = last.io.fsync_time - first.io.fsync_time;
  evidence.walBytes = last.wal.wal_bytes - first.wal.wal_bytes; evidence.fsyncsPerSecond = evidence.fsyncs / (evidence.elapsedMillis / 1000);
  evidence.cases = [{ status: 'passed', name: 'idle WAL statistics recorded with fsync and synchronous commits enabled' }];
  console.log(JSON.stringify({ fsyncs: evidence.fsyncs, fsyncMillis: evidence.fsyncMillis, elapsedMillis: evidence.elapsedMillis, fsyncsPerSecond: evidence.fsyncsPerSecond, path: saveEvidence(runId, evidence) }));
} catch (failure) { saveEvidence(runId, { ...evidence, failure: failure.message }); throw failure; }
