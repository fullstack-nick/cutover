import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { volumeStatusSql } from './lib/volume-probe.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const kc = ['--kubeconfig', resolve(root, '.local/kubeconfig'), '--context', 'kind-cutover', '-n', 'cutover-platform'];
const credentials = JSON.parse(readFileSync(resolve(root, '.local/secrets/credentials.json'), 'utf8')).passwords;
function kubectl(args, input) {
  const result = spawnSync('kubectl', [...kc, ...args], { cwd: root, input, encoding: 'utf8', windowsHide: true, maxBuffer: 4 * 1024 * 1024 });
  if (result.status !== 0) {
    mkdirSync(resolve(root, '.local/operations'), { recursive: true });
    writeFileSync(resolve(root, '.local/operations/database-provision-error.log'), result.stderr, { mode: 0o600 });
    throw new Error('Scoped database provisioning failed; details remain in ignored local operational output.');
  }
  return result.stdout.trim();
}
const pod = JSON.parse(kubectl(['get', 'pod', 'application-db-0', '-o', 'json']));
if (pod.metadata.labels['app.kubernetes.io/part-of'] !== 'cutover') throw new Error('Database pod ownership mismatch.');
// Only these fixed owner identifiers may be provisioned. Existing databases and passwords are preserved.
for (const owner of ['core', 'adapter', 'execution', 'returns', 'keycloak', 'shadow']) {
  const migrator = 'cutover_' + owner + '_migrator', runtime = 'cutover_' + owner + '_runtime', database = 'cutover_' + owner;
  for (const purpose of ['migrator', 'runtime']) if (!/^[a-f0-9]{64}$/.test(credentials[owner + '_' + purpose] ?? '')) throw new Error('Generate local owner credentials before deployment.');
  let sql = "SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', '" + migrator + "', '" + credentials[owner + '_migrator'] + "') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='" + migrator + "')\\gexec\n";
  sql += "SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', '" + runtime + "', '" + credentials[owner + '_runtime'] + "') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='" + runtime + "')\\gexec\n";
  sql += "SELECT 'CREATE DATABASE " + database + " OWNER " + migrator + "' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='" + database + "')\\gexec\n";
  sql += "\\connect " + database + "\n";
  sql += "DO $$ BEGIN IF (SELECT datdba::regrole::text FROM pg_database WHERE datname=current_database()) <> '" + migrator + "' THEN RAISE EXCEPTION 'Unexpected owner'; END IF; END $$;\n";
  sql += "REVOKE ALL ON DATABASE " + database + " FROM PUBLIC;\nGRANT CONNECT ON DATABASE " + database + " TO " + migrator + "," + runtime + ";\n";
  sql += "REVOKE ALL ON SCHEMA public FROM PUBLIC;\nALTER SCHEMA public OWNER TO " + migrator + ";\nGRANT USAGE ON SCHEMA public TO " + runtime + ";\n";
  sql += "ALTER DEFAULT PRIVILEGES FOR ROLE " + migrator + " IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO " + runtime + ";\n";
  sql += "ALTER DEFAULT PRIVILEGES FOR ROLE " + migrator + " IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO " + runtime + ";\n";
  sql += "ALTER DEFAULT PRIVILEGES FOR ROLE " + migrator + " IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;\n";
  sql += "ALTER DEFAULT PRIVILEGES FOR ROLE " + migrator + " IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO " + runtime + ";\n";
  if (owner !== 'keycloak') sql += volumeStatusSql(owner);
  kubectl(['exec', '-i', 'application-db-0', '--', 'sh', '-c', 'export PGPASSWORD="$POSTGRES_PASSWORD"; exec psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U postgres -d postgres -q'], sql);
  console.log('Verified isolated database and role grants: ' + database);
}
