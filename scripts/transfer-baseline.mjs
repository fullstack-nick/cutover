// One-time, frozen development-to-kind transfer. The independent physical world is never restored here.
import { readFileSync, writeFileSync, mkdirSync, existsSync, openSync, closeSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const directory = resolve(root, '.local/checkpoints/platform-baseline');
const credential = JSON.parse(readFileSync(resolve(root, '.local/secrets/credentials.json'), 'utf8')).passwords;
const owners = ['core', 'adapter', 'execution', 'returns', 'keycloak'];
const sha = path => createHash('sha256').update(readFileSync(path)).digest('hex');
const mode = process.argv[2];
const kc = ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover'];
function call(tool, args, options = {}) {
  const result = spawnSync(tool, args, { cwd: root, encoding: 'utf8', windowsHide: true, maxBuffer: 1024 * 1024, ...options });
  if (result.status !== 0) throw new Error(`${tool} failed: ${result.stderr?.trim() ?? result.status}`);
  return result.stdout?.trim();
}
if (mode === 'capture') {
  if (existsSync(resolve(directory, 'manifest.json'))) throw new Error('The baseline transfer checkpoint already exists; it is immutable.');
  for (const service of ['legacy-core', 'equipment-adapter', 'keycloak', 'identity-migrate']) {
    const info = JSON.parse(call('docker', ['inspect', `cutover-dev-${service}-1`]))[0];
    if (info.Config.Labels['dev.cutover.project'] !== 'cutover' || info.State.Running) throw new Error('Freeze all Cutover application/identity writers before capture.');
  }
  if (call('docker', ['inspect', '--format', '{{index .Config.Labels "dev.cutover.project"}}', 'cutover-dev-application-db-1']) !== 'cutover') throw new Error('Source database ownership mismatch.');
  mkdirSync(directory, { recursive: true });
  const manifest = { purpose: 'frozen baseline platform transfer; not the full recovery acceptance suite', startedAt: new Date().toISOString(), databases: [] };
  for (const owner of owners) {
    const file = `${owner}.dump`; const path = resolve(directory, file); const fd = openSync(path, 'w');
    try {
      call('docker', ['exec', '--env', 'PGPASSWORD', 'cutover-dev-application-db-1', 'pg_dump', '-h', '127.0.0.1', '-U', `cutover_${owner}_migrator`, '-d', `cutover_${owner}`, '-Fc', '--no-owner', '--no-acl'], { env: { ...process.env, PGPASSWORD: credential[`${owner}_migrator`] }, stdio: ['ignore', fd, 'pipe'] });
    } finally { closeSync(fd); }
    manifest.databases.push({ owner, file, sha256: sha(path) });
  }
  manifest.finishedAt = new Date().toISOString();
  manifest.images = JSON.parse(readFileSync(resolve(root, '.local/images/manifest.json'), 'utf8'));
  writeFileSync(resolve(directory, 'manifest.json'), JSON.stringify(manifest, null, 2));
  console.log('Captured five frozen application/identity databases. Simulator data was not included.');
} else if (mode === 'restore') {
  const manifest = JSON.parse(readFileSync(resolve(directory, 'manifest.json'), 'utf8'));
  const pod = JSON.parse(call('kubectl', [...kc, '-n', 'cutover-platform', 'get', 'pod', 'application-db-0', '-o', 'json']));
  if (pod.metadata.labels['app.kubernetes.io/part-of'] !== 'cutover') throw new Error('Target database ownership mismatch.');
  const databaseCommand = owner => `export PGPASSWORD="$POSTGRES_PASSWORD"; exec psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U postgres -d cutover_${owner} -At`;
  for (const owner of owners) {
    const tables = call('kubectl', [...kc, '-n', 'cutover-platform', 'exec', '-i', 'application-db-0', '--', 'sh', '-c', databaseCommand(owner)], { input: "SELECT count(*) FROM pg_tables WHERE schemaname='public';\n" });
    if (tables !== '0') throw new Error(`Target ${owner} database is not empty; refusing to overwrite accepted state.`);
  }
  for (const { owner, file, sha256 } of manifest.databases) {
    if (!owners.includes(owner) || file !== `${owner}.dump`) throw new Error('Unexpected checkpoint entry.');
    const path = resolve(directory, file);
    if (sha(path) !== sha256) throw new Error('Checkpoint checksum mismatch.');
    const fd = openSync(path, 'r');
    try {
      call('kubectl', [...kc, '-n', 'cutover-platform', 'exec', '-i', 'application-db-0', '--', 'sh', '-c', `export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_restore -h 127.0.0.1 -U postgres -d cutover_${owner} --role=cutover_${owner}_migrator --single-transaction --clean --if-exists --no-owner --no-acl`], { stdio: [fd, 'pipe', 'pipe'] });
    } finally { closeSync(fd); }
    const migrator = `cutover_${owner}_migrator`, runtime = `cutover_${owner}_runtime`;
    call('kubectl', [...kc, '-n', 'cutover-platform', 'exec', '-i', 'application-db-0', '--', 'sh', '-c', databaseCommand(owner)], { input: `
REVOKE ALL ON SCHEMA public FROM PUBLIC; ALTER SCHEMA public OWNER TO ${migrator}; GRANT USAGE ON SCHEMA public TO ${runtime};
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO ${runtime}; GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO ${runtime};
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC; GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO ${runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO ${runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO ${runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO ${runtime};\n` });
  }
  writeFileSync(resolve(directory, 'restored.json'), JSON.stringify({ restoredAt: new Date().toISOString(), target: 'kind-cutover', source: manifest.finishedAt }, null, 2));
  console.log('Transferred the frozen baseline into empty kind databases and reinstated restricted runtime grants.');
} else throw new Error('Specify capture or restore. This tool never resets a running target or the simulator.');
