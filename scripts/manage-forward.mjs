import assert from 'node:assert/strict';
import { existsSync, openSync, closeSync, readFileSync, unlinkSync, mkdirSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { resolve } from 'node:path';
import { root, call, jsonFile, writeJson, until } from './lib/local-platform.mjs';

const args = process.argv.slice(2), action = args.find(arg => arg.startsWith('--action='))?.slice(9), profile = args.find(arg => arg.startsWith('--profile='))?.slice(10), target = args.find(arg => arg.startsWith('--target='))?.slice(9);
const settings = { console: ['cutover-apps', 'proxy', 8780, 8080, '/', 200], grafana: ['cutover-observability', 'grafana', 8783, 3000, '/api/health', 200], prometheus: ['cutover-observability', 'prometheus', 8781, 9090, '/-/ready', 200], tempo: ['cutover-observability', 'tempo', 8782, 3200, '/ready', 200], 'core-api': ['cutover-apps', 'legacy-core', 8784, 8080, '/internal/v1/sites/site-a/test-controls', 401], 'adapter-api': ['cutover-apps', 'equipment-adapter', 8785, 8080, '/internal/v1/sites/site-a/test-controls', 401] };
assert.equal(args.length, 3);assert.ok(['Start', 'Stop'].includes(action));assert.ok(['demo', 'restoration'].includes(profile));assert.ok(Object.hasOwn(settings, target));
const [namespace, service, port, remotePort, path, expected] = settings[target];
const directory = resolve(root, '.local/processes'), prefix = `${profile === 'restoration' ? 'restoration-' : ''}${target}-forward`;
const kubeconfig = resolve(root, profile === 'restoration' ? '.local/restoration/kubeconfig' : '.local/kubeconfig'), context = profile === 'restoration' ? 'kind-cutover-restored' : 'kind-cutover';
const record = resolve(directory, `${prefix}.json`);mkdirSync(directory, { recursive: true });
async function healthy() { try { return (await fetch(`http://127.0.0.1:${port}${path}`, { signal: AbortSignal.timeout(2500) })).status === expected; } catch { return false; } }
function identity(pid) {
  assert.ok(Number.isSafeInteger(pid) && pid > 0);
  if(process.platform === 'win32') {
    const output = call('pwsh', ['-NoProfile', '-NonInteractive', '-Command', `Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}' | Select-Object Name,CommandLine | ConvertTo-Json -Compress`]);
    if(!output)return null;const state = JSON.parse(output);return { executable: state.Name, arguments: state.CommandLine };
  }
  try { const arguments_ = readFileSync(`/proc/${pid}/cmdline`, 'utf8').replaceAll('\0', ' ');return { executable: arguments_.split(' ')[0].split('/').at(-1), arguments: arguments_ }; }
  catch(error) { if(error.code === 'ENOENT')return null;throw error; }
}
let child;
try {
  let reused = false;
  if(existsSync(record)) {
    const saved = jsonFile(record);assert.equal(saved.kubeconfig, kubeconfig);assert.equal(saved.target, target);assert.equal(saved.port, port);
    const state = identity(saved.pid);
    if(state) {
      assert.ok(['kubectl', 'kubectl.exe'].includes(state.executable?.toLowerCase()) && [kubeconfig, context, 'port-forward', `service/${service}`, `${port}:${remotePort}`, '127.0.0.1'].every(value => state.arguments?.includes(value)), 'Recorded process identity changed; no process was stopped.');
      if(action === 'Start' && await healthy())reused = true;
      else { process.kill(saved.pid);await until(() => !identity(saved.pid), 'the exact recorded forward stops', 12000); }
    }
    if(!reused)unlinkSync(record);
  }
  if(action === 'Stop')console.log(`Stopped the recorded Cutover ${target} forward, if present.`);
  else if(reused)console.log(`Cutover ${target} forward is healthy on loopback port ${port}.`);
  else {
    const reservation = createServer();
    await new Promise((done, failed) => { reservation.once('error', () => failed(new Error(`Loopback port ${port} is occupied; no unrelated process was stopped.`)));reservation.listen(port, '127.0.0.1', done); });
    await new Promise(done => reservation.close(done));
    const stdout = openSync(resolve(directory, `${prefix}.stdout.log`), 'w', 0o600), stderr = openSync(resolve(directory, `${prefix}.stderr.log`), 'w', 0o600);
    try {
      // Detach every standard handle. A surviving kubectl must not keep the caller's capture pipes open.
      child = spawn('kubectl', ['--kubeconfig', kubeconfig, '--context', context, '-n', namespace, 'port-forward', `service/${service}`, `${port}:${remotePort}`, '--address', '127.0.0.1'], { cwd: root, detached: true, windowsHide: true, stdio: ['ignore', stdout, stderr] });
      await new Promise((done, failed) => { child.once('spawn', done);child.once('error', failed); });
    } finally { closeSync(stdout);closeSync(stderr); }
    writeJson(record, { pid: child.pid, target, port, kubeconfig, createdAt: new Date().toISOString() });child.unref();
    await until(async () => { if(child.exitCode !== null || child.signalCode !== null)throw new Error(`The Cutover ${target} forward exited; inspect its private log.`);return healthy(); }, 'the owned loopback forward becomes healthy', 30000);
    console.log(`Started healthy Cutover ${target} forward on 127.0.0.1:${port}.`);
  }
} catch(error) { console.error(error.message);process.exitCode = 1; }
