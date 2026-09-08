import { readFileSync, writeFileSync, mkdirSync, openSync, closeSync, unlinkSync, existsSync, chmodSync, readdirSync, fsyncSync, renameSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import https from 'node:https';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const owners = Object.freeze({ core: 'legacy-core', adapter: 'equipment-adapter', execution: 'execution-service', returns: 'returns-service', shadow: 'shadow-scheduler' });
export const databases = Object.freeze([...Object.keys(owners), 'keycloak']);
export const namespaces = Object.freeze(['cutover-apps', 'cutover-platform', 'cutover-observability']);
export const sha = bytes => createHash('sha256').update(bytes).digest('hex');
export const jsonFile = path => JSON.parse(readFileSync(path, 'utf8'));
export function writeJson(path, value) {
  const temporary = `${path}.${process.pid}.${randomUUID()}.tmp`, fd = openSync(temporary, 'wx', 0o600);
  try { writeFileSync(fd, JSON.stringify(value, null, 2) + '\n'); fsyncSync(fd); } finally { closeSync(fd); }
  renameSync(temporary, path);
}
export function privateDirectory(path) {
  mkdirSync(path, { recursive: true, mode: 0o700 });
  if (process.platform === 'win32') {
    const sid = call('whoami', ['/user', '/fo', 'csv', '/nh']).match(/S-1-5-21-[0-9-]+/)?.[0];
    if (!sid) throw new Error('Cannot resolve the current account for private checkpoint permissions.');
    call('icacls', [path, '/inheritance:r', '/grant:r', `*${sid}:(OI)(CI)F`, '*S-1-5-18:(OI)(CI)F']);
  } else chmodSync(path, 0o700);
}
export function call(tool, args, options = {}) {
  const result = spawnSync(tool, args, { cwd: root, encoding: 'utf8', windowsHide: true, maxBuffer: 16 * 1024 * 1024, timeout: 60000, ...options });
  if (result.status !== 0) {
    const directory = resolve(root, '.local/operations'); mkdirSync(directory, { recursive: true });
    writeFileSync(resolve(directory, 'last-platform-command-error.log'), result.stderr ?? result.error?.message ?? `Exit ${result.status}`, { mode: 0o600 });
    throw new Error(`${tool} failed; details are in .local/operations/last-platform-command-error.log.`);
  }
  return result.stdout?.trim();
}
export async function until(check, description, milliseconds = 60000) {
  const end = Date.now() + milliseconds;
  do { const result = await check(); if (result) return result; await delay(500); } while (Date.now() < end);
  throw new Error(`Timed out: ${description}`);
}
export function maintenanceLock(operation) {
  const path = resolve(root, '.local/operations/maintenance.lock'); mkdirSync(dirname(path), { recursive: true });
  const record = { operation, pid: process.pid, nonce: randomUUID(), startedAt: new Date().toISOString() };
  let fd;
  try { fd = openSync(path, 'wx', 0o600); } catch { throw new Error('Another Cutover maintenance record exists. Inspect .local/operations/maintenance.lock before recovery; do not bypass an active operation.'); }
  try { writeFileSync(fd, JSON.stringify(record)); fsyncSync(fd); } finally { closeSync(fd); }
  return () => { if (existsSync(path) && jsonFile(path).nonce === record.nonce) unlinkSync(path); };
}
export function target(profile = 'demo') {
  if (!['demo', 'restoration'].includes(profile)) throw new Error('Use the explicit demo or restoration target.');
  const cluster = profile === 'demo' ? 'cutover' : 'cutover-restored';
  const kubeconfig = resolve(root, profile === 'demo' ? '.local/kubeconfig' : '.local/restoration/kubeconfig');
  const args = ['--kubeconfig', kubeconfig, '--context', `kind-${cluster}`];
  const kube = (command, options) => call('kubectl', [...args, ...command], options);
  const get = (kind, name, namespace) => JSON.parse(kube([...(namespace ? ['-n', namespace] : []), 'get', kind, name, '-o', 'json']));
  function owned(item) {
    if (item.metadata?.labels?.['app.kubernetes.io/part-of'] !== 'cutover') throw new Error('The selected Kubernetes resource has unexpected ownership.');
    return item;
  }
  function verify() {
    const node = JSON.parse(call('docker', ['inspect', `${cluster}-control-plane`]))[0];
    if (node.Config.Labels['io.x-k8s.kind.cluster'] !== cluster) throw new Error('The selected Docker node has unexpected ownership.');
    if (get('node', `${cluster}-control-plane`).metadata.labels['dev.cutover.project'] !== 'cutover') throw new Error('The selected Kubernetes node has unexpected ownership.');
    for (const namespace of namespaces) owned(get('namespace', namespace));
    owned(get('pod', 'application-db-0', 'cutover-platform'));
  }
  function sql(owner, text) {
    if (!databases.includes(owner)) throw new Error('Unrecognized application database.');
    return kube(['-n', 'cutover-platform', 'exec', '-i', 'application-db-0', '--', 'sh', '-c', `export PGPASSWORD="$POSTGRES_PASSWORD"; exec psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U postgres -d cutover_${owner} -At -f -`], { input: text });
  }
  function scale(name, replicas, namespace = 'cutover-apps') {
    if (![...Object.values(owners), 'keycloak'].includes(name) || ![0, 1].includes(replicas)) throw new Error('Unrecognized writer scale operation.');
    owned(get('deployment', name, namespace));
    kube(['-n', namespace, 'scale', `deployment/${name}`, `--replicas=${replicas}`]);
  }
  async function writersStopped() {
    for (const namespace of ['cutover-apps', 'cutover-platform']) {
      await until(() => JSON.parse(kube(['-n', namespace, 'get', 'pods', '-o', 'json'])).items.every(pod => ![...Object.values(owners), 'keycloak'].includes(pod.metadata.labels?.['app.kubernetes.io/name'])), `${namespace} writer processes stop`, 90000);
    }
    const active = Number(sql('core', "SELECT count(*) FROM pg_stat_activity WHERE usename LIKE 'cutover_%_runtime' OR usename LIKE 'cutover_%_migrator';"));
    if (active !== 0) throw new Error('Application database writer connections remain after scaling down.');
  }
  async function forward(name, namespace = 'cutover-apps', remotePort = 8080) {
    owned(get('service', name, namespace));
    const child = spawn('kubectl', [...args, '-n', namespace, 'port-forward', `service/${name}`, `:${remotePort}`, '--address', '127.0.0.1'], { cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const processDirectory = resolve(root, '.local/processes'); mkdirSync(processDirectory, { recursive: true });
    const nonce = randomUUID(), recordPath = resolve(processDirectory, `maintenance-${process.pid}-${nonce}.json`);
    const removeRecord = () => { if (existsSync(recordPath) && jsonFile(recordPath).nonce === nonce) unlinkSync(recordPath); };
    if (child.pid) writeJson(recordPath, { pid: child.pid, parentPid: process.pid, cluster, kubeconfig, namespace, service: name, remotePort, nonce, createdAt: new Date().toISOString() });
    let port, failure, output = '';
    child.stdout.on('data', bytes => { output = (output + bytes).slice(-4096); port ??= Number(output.match(/Forwarding from 127\.0\.0\.1:(\d+)/)?.[1]) || undefined; });
    child.stderr.on('data', () => {}); child.on('error', error => { failure = error; removeRecord(); }); child.on('exit', () => { failure ??= new Error('The temporary Cutover forward exited.'); removeRecord(); });
    try { await until(() => { if (failure) throw failure; return port; }, `temporary ${name} forward`, 20000); }
    catch (error) { child.kill(); throw error; }
    return { origin: `http://127.0.0.1:${port}`, close: () => child.kill() };
  }
  return { profile, cluster, kubeconfig, args, kube, get, owned, verify, sql, scale, writersStopped, forward };
}
export async function request(origin, path, { bearer, method = 'GET', body, key } = {}) {
  const response = await fetch(origin + path, { method, signal: AbortSignal.timeout(12000), headers: { Accept: 'application/json', ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(key ? { 'Idempotency-Key': key } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  if (!response.ok) throw new Error(`Local maintenance API rejected ${method} ${path} (${response.status}).`);
  return response.json();
}
export async function maintenanceToken(origin, credentials) {
  const response = await fetch(origin + '/identity/realms/cutover/protocol/openid-connect/token', { method: 'POST', signal: AbortSignal.timeout(12000), body: new URLSearchParams({ grant_type: 'client_credentials', client_id: 'scenario-driver', client_secret: credentials.passwords['client_scenario-driver'] }) });
  if (!response.ok) throw new Error(`Local identity rejected the maintenance token request (${response.status}).`);
  return (await response.json()).access_token;
}
export function cleanupAbandonedForwards(parentPid, cluster = 'cutover') {
  if (!Number.isSafeInteger(parentPid) || parentPid < 1 || !['cutover', 'cutover-restored'].includes(cluster)) throw new Error('Invalid maintenance process identity.');
  try { process.kill(parentPid, 0); throw new Error('The maintenance parent still exists.'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
  const directory = resolve(root, '.local/processes');
  if (!existsSync(directory)) return;
  for (const file of readdirSync(directory).filter(file => file.startsWith(`maintenance-${parentPid}-`) && /^maintenance-\d+-[a-f0-9-]+\.json$/.test(file))) {
    const path = resolve(directory, file), saved = jsonFile(path);
    if (saved.parentPid !== parentPid || saved.cluster !== cluster || !Number.isSafeInteger(saved.pid) || saved.pid < 1) throw new Error('Unexpected temporary forward record.');
    const expectedConfig = resolve(root, cluster === 'cutover' ? '.local/kubeconfig' : '.local/restoration/kubeconfig');
    if (saved.kubeconfig !== expectedConfig || ![...Object.values(owners), 'proxy', 'keycloak'].includes(saved.service) || !['cutover-apps', 'cutover-platform'].includes(saved.namespace) || saved.remotePort !== 8080) throw new Error('Unexpected temporary forward scope.');
    let running = true;
    try { process.kill(saved.pid, 0); } catch (error) { if (error.code === 'ESRCH') running = false; else throw error; }
    if (running) {
      if (process.platform !== 'win32') throw new Error('Inspect and stop the recorded orphaned kubectl forward on this platform.');
      const output = call('pwsh', ['-NoProfile', '-NonInteractive', '-Command', `Get-CimInstance Win32_Process -Filter 'ProcessId=${saved.pid}' | Select-Object Name,CommandLine | ConvertTo-Json -Compress`]);
      const actual = output ? JSON.parse(output) : null;
      if (actual && (actual.Name.toLowerCase() !== 'kubectl.exe' || !actual.CommandLine.includes(saved.kubeconfig) || !actual.CommandLine.includes(`kind-${cluster}`) || !actual.CommandLine.includes(`service/${saved.service}`) || !actual.CommandLine.includes('port-forward')))
        throw new Error('The recorded forward PID now refers to a different process; it was not stopped.');
      if (actual) process.kill(saved.pid);
    }
    if (jsonFile(path).nonce === saved.nonce) unlinkSync(path);
  }
}
export function simulatorRead(path, credentials = jsonFile(resolve(root, '.local/secrets/credentials.json'))) {
  if (!/^\/sim\/v1\/(equipment|history(?:\?after=\d+&limit=\d+)?|commands\/[0-9a-f-]{36}|test-controls\/faults)$/.test(path)) throw new Error('Unrecognized read-only simulator operation.');
  return new Promise((done, failed) => {
    const request = https.get(`https://localhost:18784${path}`, { pfx: readFileSync(resolve(root, '.local/secrets/equipment/scenario.p12')), passphrase: credentials.passwords.equipment_store, ca: readFileSync(resolve(root, '.local/secrets/equipment/ca.pem')), timeout: 8000 }, response => {
      const parts = []; let bytes = 0;
      response.on('data', chunk => { bytes += chunk.length; if (bytes > 262144) request.destroy(new Error('Simulator evidence exceeded the response bound.')); else parts.push(chunk); });
      response.on('end', () => { try { if (response.statusCode !== 200) throw new Error(`Simulator read failed (${response.statusCode}).`); done(JSON.parse(Buffer.concat(parts))); } catch (error) { failed(error); } });
    });
    request.on('timeout', () => request.destroy(new Error('Simulator read timed out.'))); request.on('error', failed);
  });
}
export function fingerprints(platform, owner) {
  const tables = platform.sql(owner, "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;").split(/\r?\n/).filter(Boolean);
  if (!tables.every(name => /^[a-z_][a-z0-9_]*$/.test(name))) throw new Error('Unexpected application table identifier.');
  if (!tables.length) return [];
  const statements = tables.map(table => `SELECT json_build_object('table','${table}','rows',count(*),'sha256',encode(sha256(convert_to(coalesce(string_agg(encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'),'' ORDER BY encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex')),''),'UTF8')),'hex')) FROM "${table}" t;`);
  return platform.sql(owner, statements.join('\n')).split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
}
