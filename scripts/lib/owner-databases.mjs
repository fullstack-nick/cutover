import assert from 'node:assert/strict';
import { databases } from './local-platform.mjs';
import { volumeStatusSql } from './volume-probe.mjs';

export function ownerGrants(owner) {
  assert.ok(databases.includes(owner));
  const migrator = `cutover_${owner}_migrator`, runtime = `cutover_${owner}_runtime`, database = `cutover_${owner}`;
  return `REVOKE ALL ON DATABASE ${database} FROM PUBLIC;
GRANT CONNECT ON DATABASE ${database} TO ${migrator},${runtime};
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO ${migrator};
GRANT USAGE ON SCHEMA public TO ${runtime};
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO ${runtime};
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO ${runtime};
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO ${runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO ${runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO ${runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO ${runtime};\n${owner === 'keycloak' ? '' : volumeStatusSql(owner)}`;
}

// Used only by the explicit local administrator procedure, never by application runtime code.
export function provisionDatabases(platform, credentials) {
  platform.owned(platform.get('pod', 'application-db-0', 'cutover-platform'));
  for (const owner of databases) {
    const migrator = `cutover_${owner}_migrator`, runtime = `cutover_${owner}_runtime`, database = `cutover_${owner}`;
    for (const purpose of ['migrator', 'runtime']) assert.match(credentials.passwords[`${owner}_${purpose}`] ?? '', /^[a-f0-9]{64}$/);
    let sql = '';
    for (const purpose of ['migrator', 'runtime']) {
      const role = `cutover_${owner}_${purpose}`, password = credentials.passwords[`${owner}_${purpose}`];
      sql += `SELECT format('CREATE ROLE %I LOGIN PASSWORD %L','${role}','${password}') WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='${role}')\\gexec\n`;
    }
    sql += `SELECT 'CREATE DATABASE ${database} OWNER ${migrator}' WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='${database}')\\gexec\n`;
    sql += `\\connect ${database}\nDO $$ BEGIN IF (SELECT datdba::regrole::text FROM pg_database WHERE datname=current_database()) <> '${migrator}' THEN RAISE EXCEPTION 'Unexpected database owner'; END IF; END $$;\n`;
    sql += ownerGrants(owner);
    platform.kube(['-n', 'cutover-platform', 'exec', '-i', 'application-db-0', '--', 'sh', '-c', 'export PGPASSWORD="$POSTGRES_PASSWORD"; exec psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U postgres -d postgres -q'], { input: sql });
  }
}
