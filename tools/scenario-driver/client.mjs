import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { randomBytes } from 'node:crypto';
import https from 'node:https';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const credentialPath = resolve(root, '.local/secrets/credentials.json');
export const credentials = JSON.parse(readFileSync(credentialPath, 'utf8'));
export const profile = process.env.CUTOVER_PROFILE ?? 'dev';
if (!['dev', 'demo'].includes(profile)) throw new Error('Unknown Cutover verification profile.');
const origin = 'http://localhost:8780';
export async function token(client = 'scenario-driver') {
  const response = await fetch(`${origin}/identity/realms/cutover/protocol/openid-connect/token`, {
    method: 'POST', body: new URLSearchParams({ grant_type: 'client_credentials', client_id: client, client_secret: credentials.passwords[`client_${client}`] }),
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) throw new Error(`Local identity rejected the service token request (${response.status}).`);
  return (await response.json()).access_token;
}
export async function api(path, { method = 'GET', body, key, bearer, target = 'console' } = {}) {
  const origins = { console: origin, core: 'http://127.0.0.1:8784', adapter: 'http://127.0.0.1:8785' };
  if (!Object.hasOwn(origins, target)) throw new Error('Unknown Cutover API forward.');
  const headers = { Accept: 'application/json' };
  if (bearer) headers.Authorization = `Bearer ${bearer}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const response = await fetch(origins[target] + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(8000) });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}
export function simulator(path, body, identity = 'scenario', method = body === undefined ? 'GET' : 'POST') {
  if (!['GET','POST','PUT','DELETE'].includes(method)) throw new Error('Unsupported equipment HTTP operation.');
  const equipment = resolve(root, '.local/secrets/equipment');
  return new Promise((done, failed) => {
    const request = https.request(`https://localhost:18784${path}`, {
      method, pfx: readFileSync(resolve(equipment, `${identity}.p12`)),
      passphrase: credentials.passwords.equipment_store, ca: readFileSync(resolve(equipment, 'ca.pem')),
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' }, timeout: 6000,
    }, response => {
      const chunks = []; let bytes = 0;
      response.on('data', chunk => { bytes += chunk.length; if (bytes > 262144) request.destroy(new Error('Equipment response exceeded its bound.')); else chunks.push(chunk); });
      response.on('end', () => { try { done({ status: response.statusCode, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) }); } catch (error) { failed(error); } });
    });
    request.on('timeout', () => request.destroy(new Error('Equipment request timed out.')));
    request.on('error', failed);
    if (body !== undefined) request.write(JSON.stringify(body));
    request.end();
  });
}

function postgres(owner, role, sql, { admin = false } = {}) {
  if (!['core', 'adapter', 'simulator', 'execution', 'returns', 'keycloak'].includes(owner)) throw new Error('Unknown Cutover database owner.');
  if (profile === 'demo' && owner !== 'simulator') {
    const target = ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover', '-n', 'cutover-platform'];
    const inspected = spawnSync('kubectl', [...target, 'get', 'pod', 'application-db-0', '-o', 'json'], { encoding: 'utf8', windowsHide: true });
    if (inspected.status !== 0 || JSON.parse(inspected.stdout).metadata.labels['app.kubernetes.io/part-of'] !== 'cutover') throw new Error('Kubernetes database ownership check failed.');
    const user = admin ? 'postgres' : `cutover_${owner}_${role}`;
    const password = credentials.passwords[admin ? 'postgres_admin' : `${owner}_${role}`];
    if (!password) throw new Error('Required local database credential is missing.');
    return spawnSync('kubectl', [...target, 'exec', '-i', 'application-db-0', '--', 'sh', '-c', `IFS= read -r PGPASSWORD; export PGPASSWORD; exec psql -X -v ON_ERROR_STOP=1 -v VERBOSITY=verbose -h 127.0.0.1 -U ${user} -d cutover_${owner} -At -f -`], {
      input: password + '\n' + sql, encoding: 'utf8', windowsHide: true, maxBuffer: 1024 * 1024,
    });
  }
  const container = `cutover-dev-${owner === 'simulator' ? 'simulator' : 'application'}-db-1`;
  const inspected = spawnSync('docker', ['inspect', '--format', '{{index .Config.Labels "com.docker.compose.project"}}', container], { encoding: 'utf8', windowsHide: true });
  if (inspected.status !== 0 || inspected.stdout.trim() !== 'cutover-dev') throw new Error('Database container ownership check failed.');
  const user = admin ? 'postgres' : `cutover_${owner}_${role}`;
  const password = credentials.passwords[admin ? (owner === 'simulator' ? 'simulator_postgres_admin' : 'postgres_admin') : `${owner}_${role}`];
  if (!password) throw new Error('Required local database credential is missing.');
  return spawnSync('docker', ['exec', '-i', '--env', 'PGPASSWORD', container, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=verbose', '-h', '127.0.0.1', '-U', user, '-d', `cutover_${owner}`, '-At', '-f', '-'], {
    input: sql, encoding: 'utf8', windowsHide: true, env: { ...process.env, PGPASSWORD: password }, maxBuffer: 1024 * 1024,
  });
}
export function provisionObservers() {
  for (const owner of ['core', 'adapter', 'simulator', 'execution', 'returns', 'keycloak']) credentials.passwords[`${owner}_observer`] ??= randomBytes(32).toString('hex');
  writeFileSync(credentialPath, JSON.stringify(credentials, null, 2) + '\n', { mode: 0o600 });
  for (const owner of ['core', 'adapter', 'simulator', 'execution', 'returns', 'keycloak']) {
    const user = `cutover_${owner}_observer`;
    credentials.passwords[`${owner}_observer`] ??= randomBytes(32).toString('hex');
    const secret = credentials.passwords[`${owner}_observer`];
    const sql = `DO $$ BEGIN IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='${user}') THEN CREATE ROLE ${user} LOGIN PASSWORD '${secret}'; END IF; END $$;
GRANT CONNECT ON DATABASE cutover_${owner} TO ${user};
GRANT USAGE ON SCHEMA public TO ${user};
GRANT SELECT ON ALL TABLES IN SCHEMA public TO ${user};
ALTER DEFAULT PRIVILEGES FOR ROLE cutover_${owner}_migrator IN SCHEMA public GRANT SELECT ON TABLES TO ${user};
ALTER ROLE ${user} SET default_transaction_read_only=on;`;
    const result = postgres(owner, 'observer', sql, { admin: true });
    if (result.status !== 0) throw new Error(`Could not provision the ${owner} read-only verification identity.`);
  }
  writeFileSync(credentialPath, JSON.stringify(credentials, null, 2) + '\n', { mode: 0o600 });
}
export function query(owner, sql) {
  const result = postgres(owner, 'observer', sql);
  if (result.status !== 0) throw new Error(`Read-only ${owner} evidence query failed: ${result.stderr.trim()}`);
  return result.stdout.trim();
}
export function roleCannotCreate(owner) {
  const identity = postgres(owner, 'runtime', 'SELECT current_user;');
  if (identity.status !== 0 || identity.stdout.trim() !== `cutover_${owner}_runtime`) throw new Error(`The ${owner} runtime identity could not authenticate for its permission test.`);
  const result = postgres(owner, 'runtime', 'CREATE TABLE cutover_forbidden_ddl_probe(id integer);');
  if (result.status === 0) postgres(owner, 'runtime', 'DROP TABLE cutover_forbidden_ddl_probe;', { admin: true });
  return result.status !== 0 && result.stderr.includes('42501');
}
export async function restartDevelopmentServices(services) {
  if (profile !== 'dev') throw new Error('Development restart checks cannot run against the demo profile.');
  const allowed = ['legacy-core', 'equipment-adapter', 'equipment-simulator', 'application-db', 'simulator-db', 'rabbitmq', 'keycloak'];
  const names = services.map(service => {
    if (!allowed.includes(service)) throw new Error('Unrecognized development service.');
    const name = `cutover-dev-${service}-1`;
    const inspect = spawnSync('docker', ['inspect', '--format', '{{index .Config.Labels "com.docker.compose.project"}}', name], { encoding: 'utf8', windowsHide: true });
    if (inspect.status !== 0 || inspect.stdout.trim() !== 'cutover-dev') throw new Error('Container ownership check failed.');
    return name;
  });
  await new Promise((done, failed) => {
    const child = spawn('docker', ['restart', '--time', '10', ...names], { windowsHide: true, stdio: 'ignore' });
    child.on('error', failed); child.on('exit', code => code === 0 ? done() : failed(new Error('Cutover process restart failed.')));
  });
  const deadline = Date.now() + 90000;
  do {
    const health = spawnSync('docker', ['inspect', '--format', '{{.State.Health.Status}}', ...names], { encoding: 'utf8', windowsHide: true });
    if (health.status === 0 && health.stdout.trim().split(/\r?\n/).every(value => value === 'healthy')) return;
    await delay(1000);
  } while (Date.now() < deadline);
  throw new Error('Restarted Cutover services did not become healthy.');
}
export function saveEvidence(name, evidence) {
  const directory = resolve(root, '.local/evidence', name); mkdirSync(directory, { recursive: true });
  const revision = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8', windowsHide: true }).stdout.trim();
  const dirty = spawnSync('git', ['status', '--porcelain'], { cwd: root, encoding: 'utf8', windowsHide: true }).stdout.trim().length > 0;
  const runtime = {};
  const simulatorState = spawnSync('docker', ['inspect', '--format', '{{json .}}', 'cutover-dev-equipment-simulator-1'], { encoding: 'utf8', windowsHide: true });
  if (simulatorState.status === 0) {
    const actual = JSON.parse(simulatorState.stdout);
    if (actual.Config.Labels['dev.cutover.project'] === 'cutover') runtime.simulator = { imageId: actual.Image, imageReference: actual.Config.Image, startedAt: actual.State.StartedAt };
  }
  if (profile === 'demo') {
    runtime.pods = [];
    for (const namespace of ['cutover-apps', 'cutover-platform', 'cutover-observability']) {
      const result = spawnSync('kubectl', ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover', '-n', namespace, 'get', 'pods', '-l', 'app.kubernetes.io/part-of=cutover', '-o', 'json'], { encoding: 'utf8', windowsHide: true });
      if (result.status === 0) runtime.pods.push(...JSON.parse(result.stdout).items.map(pod => ({ namespace, name: pod.metadata.name, uid: pod.metadata.uid, containers: (pod.status.containerStatuses ?? []).map(item => ({ name: item.name, image: item.image, imageId: item.imageID, ready: item.ready, restartCount: item.restartCount })) })));
    }
  }
  writeFileSync(resolve(directory, 'results.json'), JSON.stringify({ recordedAt: new Date().toISOString(), profile, revision, dirty, images: JSON.parse(readFileSync(resolve(root, '.local/images/manifest.json'), 'utf8')), runtime, ...(profile === 'demo' ? { nodeImages: JSON.parse(readFileSync(resolve(root, '.local/images/node-images.json'), 'utf8')) } : {}), ...evidence }, null, 2) + '\n');
  return directory;
}
