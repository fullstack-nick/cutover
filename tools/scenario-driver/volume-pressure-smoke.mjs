import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createServer as portReservation } from 'node:net';
import { spawn } from 'node:child_process';
import { readFileSync, openSync, closeSync } from 'node:fs';
import { resolve } from 'node:path';
import { generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { root, call, until, privateDirectory, writeJson, sha } from '../../scripts/lib/local-platform.mjs';
import { volumeStatusSql, volumeProbeScript } from '../../scripts/lib/volume-probe.mjs';

const runId = `volume-pressure-${Date.now()}`, directory = resolve(root, '.local/evidence', runId);
privateDirectory(directory);
const name = `cutover-${runId}`, jar = resolve(root, 'apps/legacy-core/target/legacy-core-0.1.0-SNAPSHOT-exec.jar');
const image = JSON.parse(readFileSync(resolve(root, 'infra/versions.lock.json'))).images.postgres.reference;
const admin = randomBytes(32).toString('hex'), migrator = randomBytes(32).toString('hex'), runtime = randomBytes(32).toString('hex'), observer = randomBytes(32).toString('hex');
const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: runId, alg: 'RS256', use: 'sig' };
let app, containerId, apiOrigin, issuer, server, brokerRefusal, connections = 0, cases = [];
const startedAt = new Date().toISOString();
const environment = { ...process.env, POSTGRES_PASSWORD: admin };
function token() {
  const now = Math.floor(Date.now() / 1000), encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const input = `${encode({ alg: 'RS256', typ: 'JWT', kid: runId })}.${encode({ iss: issuer, aud: 'cutover-core', sub: 'isolated-volume-scenario', azp: 'scenario-driver', iat: now, nbf: now - 1, exp: now + 300, sites: ['site-a'], realm_access: { roles: ['operator', 'scenario', 'service', 'test-control', 'platform-admin'] } })}`;
  return `${input}.${sign('RSA-SHA256', Buffer.from(input), privateKey).toString('base64url')}`;
}
async function api(path, { method = 'GET', body, key } = {}) {
  const response = await fetch(`${apiOrigin}${path}`, { method, signal: AbortSignal.timeout(8000), headers: { Authorization: `Bearer ${token()}`, ...(body ? { 'Content-Type': 'application/json' } : {}), ...(key ? { 'Idempotency-Key': key } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await response.text();
  if(!text) throw new Error(`${method} ${path} returned HTTP ${response.status} without JSON (${response.headers.get('www-authenticate') ?? 'no authentication challenge'}).`);
  return { status: response.status, retryAfter: response.headers.get('Retry-After'), body: JSON.parse(text) };
}
function sql(statement, privileged = false) {
  return call('docker', ['exec', '-i', '--env', 'PGPASSWORD', name, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-U', privileged ? 'postgres' : 'cutover_core_observer', '-d', 'cutover_core', '-Atq'], { input: statement, env: { ...process.env, PGPASSWORD: privileged ? admin : observer } });
}
function java(arguments_, file, env) {
  const fd = openSync(resolve(directory, file), 'a', 0o600);
  const child = spawn('java', ['-Xmx384m', '-XX:+ExitOnOutOfMemoryError', ...arguments_], { cwd: root, windowsHide: true, stdio: ['ignore', fd, fd], env: { ...process.env, ...env } });
  closeSync(fd);return child;
}
async function stopped(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  child.kill();await until(() => child.exitCode !== null || child.signalCode !== null, 'the owned pressure-test JVM stops', 20000);
}
try {
  const reserved = [portReservation(), portReservation()];
  for (const socket of reserved) await new Promise(done => socket.listen(0, '127.0.0.1', done));
  const [httpPort, managementPort] = reserved.map(socket => socket.address().port);
  for (const socket of reserved) await new Promise(done => socket.close(done));
  apiOrigin = `http://127.0.0.1:${httpPort}`;
  brokerRefusal = portReservation(socket => socket.destroy());
  await new Promise(done => brokerRefusal.listen(0, '127.0.0.1', done));
  server = createServer((request, response) => {
    response.setHeader('Content-Type', 'application/json');
    if (request.url === '/jwks') response.end(JSON.stringify({ keys: [jwk] }));
    else if (request.url === '/token') response.end(JSON.stringify({ access_token: token(), expires_in: 300, token_type: 'Bearer' }));
    else { connections++;response.statusCode = 503;response.end(JSON.stringify({ code: 'ISOLATED_EQUIPMENT_BOUNDARY' })); }
  });
  await new Promise(done => server.listen(0, '127.0.0.1', done));issuer = `http://127.0.0.1:${server.address().port}`;
  containerId = call('docker', ['run', '--pull=never', '-d', '--name', name, '--label', 'dev.cutover.project=cutover', '--label', 'dev.cutover.purpose=physical-pressure-verification', '--env', 'POSTGRES_PASSWORD', '--publish', '127.0.0.1::5432', '--tmpfs', '/var/lib/postgresql:rw,size=402653184', '--mount', `type=bind,source=${volumeProbeScript},target=/etc/cutover/volume-probe.sh,readonly`, '--memory', '768m', '--cpus', '1', image, '-c', 'shared_buffers=32MB', '-c', 'max_wal_size=64MB'], { env: environment });
  await until(() => { try { call('docker', ['exec', name, 'pg_isready', '-U', 'postgres']);return true; } catch { return false; } }, 'disposable PostgreSQL starts', 60000);
  const inspected = JSON.parse(call('docker', ['inspect', name]))[0];
  assert.equal(inspected.Id, containerId);assert.equal(inspected.HostConfig.Tmpfs['/var/lib/postgresql'], 'rw,size=402653184');
  assert.ok(!inspected.Mounts.some(mount => mount.Type === 'volume' && mount.Destination === '/var/lib/postgresql'));
  const dbPort = inspected.NetworkSettings.Ports['5432/tcp'][0].HostPort;
  call('docker', ['exec', name, 'sh', '-c', 'nohup sh /etc/cutover/volume-probe.sh >/tmp/volume-probe.log 2>&1 &']);
  const provision = `CREATE ROLE cutover_core_migrator LOGIN PASSWORD '${migrator}'; CREATE ROLE cutover_core_runtime LOGIN PASSWORD '${runtime}'; CREATE ROLE cutover_core_observer LOGIN PASSWORD '${observer}'; CREATE DATABASE cutover_core OWNER cutover_core_migrator;\n\\connect cutover_core\nREVOKE ALL ON DATABASE cutover_core FROM PUBLIC; GRANT CONNECT ON DATABASE cutover_core TO cutover_core_runtime,cutover_core_migrator,cutover_core_observer; REVOKE ALL ON SCHEMA public FROM PUBLIC; ALTER SCHEMA public OWNER TO cutover_core_migrator; GRANT USAGE ON SCHEMA public TO cutover_core_runtime,cutover_core_observer; ALTER DEFAULT PRIVILEGES FOR ROLE cutover_core_migrator IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO cutover_core_runtime; ALTER DEFAULT PRIVILEGES FOR ROLE cutover_core_migrator IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO cutover_core_runtime; ALTER DEFAULT PRIVILEGES FOR ROLE cutover_core_migrator IN SCHEMA public GRANT SELECT ON TABLES TO cutover_core_observer; ALTER ROLE cutover_core_observer SET default_transaction_read_only=on;\n${volumeStatusSql('core')}`;
  call('docker', ['exec', '-i', '--env', 'PGPASSWORD', name, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'postgres', '-q'], { input: provision, env: { ...process.env, PGPASSWORD: admin } });
  const databaseEnv = { CUTOVER_DATABASE_URL: `jdbc:postgresql://127.0.0.1:${dbPort}/cutover_core`, CUTOVER_DATABASE_PASSWORD: runtime };
  const migration = java(['-Dloader.main=dev.cutover.platform.MigrationMain', '-cp', jar, 'org.springframework.boot.loader.launch.PropertiesLauncher'], 'migration.log', { ...databaseEnv, CUTOVER_DATABASE_PASSWORD: migrator, CUTOVER_DATABASE_USER: 'cutover_core_migrator', CUTOVER_MIGRATION_LOCATIONS: 'classpath:db/platform,classpath:db/legacy-core', CUTOVER_MIGRATION_TARGET: '109' });
  await until(() => migration.exitCode !== null, 'isolated legacy schema migration completes', 90000);assert.equal(migration.exitCode, 0, 'The isolated migration failed; inspect migration.log.');
  const appEnv = { ...databaseEnv, CUTOVER_DATABASE_USER: 'cutover_core_runtime', CUTOVER_HTTP_PORT: `${httpPort}`, CUTOVER_MANAGEMENT_PORT: `${managementPort}`, SERVER_ADDRESS: '127.0.0.1', MANAGEMENT_SERVER_ADDRESS: '127.0.0.1', CUTOVER_MIGRATIONS_ENABLED: 'false', CUTOVER_WORKERS_ENABLED: 'true', CUTOVER_MESSAGING_ENABLED: 'true', SPRING_RABBITMQ_HOST: '127.0.0.1', SPRING_RABBITMQ_PORT: `${brokerRefusal.address().port}`, CUTOVER_TEST_CONTROLS_ENABLED: 'true', CUTOVER_ISSUER: issuer, CUTOVER_JWKS_URI: `${issuer}/jwks`, CUTOVER_TOKEN_URI: `${issuer}/token`, CUTOVER_CLIENT_SECRET: randomBytes(32).toString('hex'), CUTOVER_ADAPTER_URL: issuer };
  async function startApp() {
    app = java(['-jar', jar], 'core.log', appEnv);
    await until(async () => { assert.equal(app.exitCode, null, 'The isolated core exited; inspect its private log.');try { return (await fetch(`http://127.0.0.1:${managementPort}/actuator/health/readiness`, { signal: AbortSignal.timeout(2000) })).status === 200; } catch { return false; } }, 'the actual isolated core HTTP process becomes ready', 120000);
  }
  await startApp();
  const controls = '/internal/v1/sites/site-a/test-controls';
  async function dispatchPaused(value) {
    const before = await api(controls);assert.equal(before.status, 200);
    const changed = await api(controls, { method: 'POST', key: `${runId}-${before.body.version}`, body: { expectedVersion: before.body.version, dispatchPaused: value, relayPaused: true, consumerPaused: true, reason: 'Prepare the isolated physical filesystem pressure experiment.' } });assert.equal(changed.status, 200);
  }
  await dispatchPaused(true);
  const order = { sourceSystem: 'scenario-driver', externalOrderRef: `${runId}-accepted`, storeId: 'store-07', priority: 5, lines: [{ sku: 'SKU-057', quantity: 2 }] };
  const accepted = await api('/api/v1/sites/site-a/orders', { method: 'POST', key: order.externalOrderRef, body: order });assert.equal(accepted.status, 202);
  const initial = (await api('/internal/v1/platform/storage')).body;
  assert.equal(initial.volume.state, 'HEALTHY');assert.equal(initial.volume.totalBytes, 402653184);
  const filler = Math.floor((initial.volume.availableBytes - 50331648) / 1048576);assert.ok(filler > 0 && filler < 320);
  call('docker', ['exec', name, 'dd', 'if=/dev/zero', 'of=/var/lib/postgresql/cutover-pressure-fixture', 'bs=1048576', `count=${filler}`, 'status=none']);
  await until(async () => (await api('/internal/v1/platform/storage')).body.volume.state === 'CRITICAL', 'the actual filesystem pressure reaches the HTTP diagnostics', 12000);
  const critical = (await api('/internal/v1/platform/storage')).body;
  assert.ok(critical.volume.availableBytes < critical.volume.reserveBytes);assert.ok(critical.volume.availableBytes > 33554432);
  await dispatchPaused(false);
  await until(() => sql("SELECT last_error FROM legacy_tasks LIMIT 1;") === 'STORAGE_OBSERVATION', 'the running scheduler observes the physical dispatch barrier', 20000);
  assert.equal(connections, 0, 'The guarded scheduler must make no equipment-boundary call.');
  const rejectedOrder = { ...order, externalOrderRef: `${runId}-rejected` };
  const rejected = await api('/api/v1/sites/site-a/orders', { method: 'POST', key: rejectedOrder.externalOrderRef, body: rejectedOrder });
  assert.equal(rejected.status, 503);assert.equal(rejected.body.code, 'STORAGE_OBSERVATION');assert.ok(Number(rejected.retryAfter) >= 1);
  assert.equal(sql('SELECT count(*) FROM orders;'), '1');assert.equal(sql("SELECT count(*) FROM reservations WHERE state='RESERVED';"), '1');
  assert.equal(sql('SELECT count(*) FROM inventory_ledger;'), '0');assert.equal((await api(accepted.body.statusUrl)).status, 200);
  await stopped(app);await startApp();
  assert.equal((await api(accepted.body.statusUrl)).status, 200);assert.equal((await api('/internal/v1/platform/storage')).body.volume.state, 'CRITICAL');assert.equal(connections, 0);
  call('docker', ['exec', name, 'rm', '--', '/var/lib/postgresql/cutover-pressure-fixture']);
  await until(async () => (await api('/internal/v1/platform/storage')).body.volume.state === 'HEALTHY', 'filesystem headroom returns after deleting only the disposable filler', 12000);
  const retry = await api('/api/v1/sites/site-a/orders', { method: 'POST', key: rejectedOrder.externalOrderRef, body: rejectedOrder });assert.equal(retry.status, 202);
  await until(() => connections > 0, 'the running scheduler can attempt its isolated equipment boundary again', 20000);
  const counts = JSON.parse(sql("SELECT jsonb_build_object('orders',(SELECT count(*) FROM orders),'reserved',(SELECT count(*) FROM reservations WHERE state='RESERVED'),'inventoryEffects',(SELECT count(*) FROM inventory_ledger));"));
  assert.deepEqual(counts, { orders: 2, reserved: 2, inventoryEffects: 0 });
  cases.push({ id: 'A26', status: 'passed', name: 'real bounded filesystem pressure refuses HTTP intake and scheduler dispatch while retained reads and cold JVM restart work', initial, critical, counts, refusedCode: rejected.body.code, retryAfter: rejected.retryAfter, recoveryBoundaryAttempts: connections });
  const manifest = { runId, startedAt, endedAt: new Date().toISOString(), revision: call('git', ['rev-parse', 'HEAD']), dirty: call('git', ['status', '--porcelain']).length > 0, profile: 'isolated-physical-storage', java: call('java', ['--version']), jarSha256: sha(readFileSync(jar)), databaseImage: inspected.Image, databaseReference: image, filesystemBytes: 402653184, fixture: 'Synthetic core migrations through V109, 100 products and 10 stores', cpuLimit: 1, databaseMemoryLimit: 805306368, jvmHeapMiB: 384, exclusions: 'No live simulator or existing application database is connected. Equipment HTTP is a counted refusal endpoint; no end-to-end physical completion or load SLO is claimed by this experiment.' };
  writeJson(resolve(directory, 'manifest.json'), manifest);writeJson(resolve(directory, 'results.json'), { manifest, cases });
  console.log(JSON.stringify({ runId, status: 'passed', scenarios: cases.length, counts }));
} catch (error) { writeJson(resolve(directory, 'failure.json'), { runId, cases, error: error.message, stack: error.stack });console.error(error.message);process.exitCode = 1; }
finally {
  await stopped(app);server?.closeAllConnections();if(server)await new Promise(done => server.close(done));
  if(brokerRefusal)await new Promise(done => brokerRefusal.close(done));
  if(containerId) {
    const owned = JSON.parse(call('docker', ['inspect', name]))[0];assert.equal(owned.Id, containerId);assert.equal(owned.Config.Labels['dev.cutover.purpose'], 'physical-pressure-verification');
    call('docker', ['rm', '-f', '-v', containerId]); // Only this newly created disposable container and its anonymous volumes.
  }
}
