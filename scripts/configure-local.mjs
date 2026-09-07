import { mkdirSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes, createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const directory = resolve(root, '.local/secrets');
mkdirSync(directory, { recursive: true });
const credentialFile = resolve(directory, 'credentials.json');
const credentials = existsSync(credentialFile) ? JSON.parse(readFileSync(credentialFile, 'utf8')) : { createdAt: new Date().toISOString(), passwords: {} };
const password = key => credentials.passwords[key] ??= randomBytes(32).toString('hex');
const owners = ['core', 'adapter', 'execution', 'returns', 'keycloak', 'shadow'];
const databaseNames = [...owners, 'simulator'];
for (const owner of databaseNames) for (const purpose of ['migrator', 'runtime']) password(`${owner}_${purpose}`);
for (const key of ['postgres_admin', 'simulator_postgres_admin', 'keycloak_admin', 'grafana_admin', 'equipment_store', 'operator_a', 'supervisor_a', 'operator_b', 'platform_admin']) password(key);
const clients = ['legacy-core', 'equipment-adapter', 'execution-service', 'returns-service', 'shadow-scheduler', 'scenario-driver'];
for (const client of clients) password(`client_${client}`);
for (const owner of ['core', 'adapter', 'execution', 'returns', 'shadow', 'scenario', 'admin']) password(`rabbit_${owner}`);
writeFileSync(credentialFile, JSON.stringify(credentials, null, 2) + '\n', { mode: 0o600 });

function write(name, contents) { writeFileSync(resolve(directory, name), contents, { mode: 0o600 }); }
function initSql(names) {
  return 'REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;\nREVOKE CONNECT ON DATABASE template1 FROM PUBLIC;\n' + names.map(owner => {
    const migrator = `cutover_${owner}_migrator`, runtime = `cutover_${owner}_runtime`, database = `cutover_${owner}`;
    // Identifiers are a fixed allowlist and passwords are generated hex; neither comes from a user payload.
    return `CREATE ROLE ${migrator} LOGIN PASSWORD '${password(`${owner}_migrator`)}';
CREATE ROLE ${runtime} LOGIN PASSWORD '${password(`${owner}_runtime`)}';
CREATE DATABASE ${database} OWNER ${migrator};
REVOKE ALL ON DATABASE ${database} FROM PUBLIC;
GRANT CONNECT ON DATABASE ${database} TO ${migrator},${runtime};
\\connect ${database}
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO ${migrator};
GRANT USAGE ON SCHEMA public TO ${runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO ${runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO ${runtime};
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE ${migrator} IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO ${runtime};
`;
  }).join('\n');
}
write('application-init.sql', initSql(owners));
write('simulator-init.sql', initSql(['simulator']));

const roles = ['operator', 'supervisor', 'platform-admin', 'scenario', 'service', 'test-control', 'shadow'];
const audience = value => ({ name: `audience-${value}`, protocol: 'openid-connect', protocolMapper: 'oidc-audience-mapper', config: { 'included.custom.audience': value, 'access.token.claim': 'true', 'id.token.claim': 'false' } });
const siteMapper = { name: 'site-membership', protocol: 'openid-connect', protocolMapper: 'oidc-usermodel-attribute-mapper', config: { 'user.attribute': 'sites', 'claim.name': 'sites', 'jsonType.label': 'String', multivalued: 'true', 'access.token.claim': 'true', 'id.token.claim': 'false', 'userinfo.token.claim': 'false' } };
const audiences = ['cutover-core', 'cutover-adapter', 'cutover-execution', 'cutover-returns'];
const realm = {
  realm: 'cutover', enabled: true, displayName: 'Cutover local operations', sslRequired: 'none',
  registrationAllowed: false, resetPasswordAllowed: false, rememberMe: false,
  accessTokenLifespan: 300, ssoSessionIdleTimeout: 1800, ssoSessionMaxLifespan: 28800,
  bruteForceProtected: true, failureFactor: 5, waitIncrementSeconds: 30,
  roles: { realm: roles.map(name => ({ name })) },
  clients: [{
    clientId: 'operations-console', name: 'Cutover operations console', enabled: true, protocol: 'openid-connect',
    publicClient: true, standardFlowEnabled: true, implicitFlowEnabled: false, directAccessGrantsEnabled: false,
    redirectUris: ['http://localhost:8780/callback'], webOrigins: ['http://localhost:8780'],
    attributes: { 'pkce.code.challenge.method': 'S256', 'post.logout.redirect.uris': 'http://localhost:8780/' },
    protocolMappers: [siteMapper, ...audiences.map(audience)],
  }, ...clients.map(clientId => ({
    clientId, enabled: true, protocol: 'openid-connect', publicClient: false, secret: password(`client_${clientId}`),
    standardFlowEnabled: false, implicitFlowEnabled: false, directAccessGrantsEnabled: false, serviceAccountsEnabled: true,
    protocolMappers: [siteMapper, ...audiences.map(audience)],
  }))],
  users: [
    ...[
      ['operator-a', 'operator_a', ['operator'], 'site-a'],
      ['supervisor-a', 'supervisor_a', ['operator', 'supervisor'], 'site-a'],
      ['operator-b', 'operator_b', ['operator'], 'site-b'],
      ['platform-admin', 'platform_admin', ['platform-admin'], 'site-a'],
    ].map(([username, key, realmRoles, site]) => ({ username, enabled: true, firstName: 'Local', lastName: username, email: `${username}@cutover.invalid`, emailVerified: true, realmRoles, attributes: { sites: [site] }, credentials: [{ type: 'password', value: password(key), temporary: false }] })),
    ...clients.map(client => ({ username: `service-account-${client}`, enabled: true, serviceAccountClientId: client,
      realmRoles: ['service', ...(client === 'scenario-driver' ? ['scenario', 'test-control'] : []), ...(client === 'shadow-scheduler' ? ['shadow'] : [])],
      attributes: { sites: ['site-a'] },
    })),
  ],
};
mkdirSync(resolve(directory, 'realm'), { recursive: true });
write('realm/cutover-realm.json', JSON.stringify(realm, null, 2) + '\n');

const publishers = ['legacy-core', 'equipment-adapter', 'execution-service', 'returns-service'];
const shortNames = { 'legacy-core': 'core', 'equipment-adapter': 'adapter', 'execution-service': 'execution', 'returns-service': 'returns', 'shadow-scheduler': 'shadow' };
const consumers = [...publishers, 'shadow-scheduler'];
const previousBroker = existsSync(resolve(directory, 'rabbit-definitions.json')) ? JSON.parse(readFileSync(resolve(directory, 'rabbit-definitions.json'), 'utf8')) : { users: [] };
const hashPassword = (name, value) => {
  const previous = previousBroker.users.find(user => user.name === name && user.hashing_algorithm === 'rabbit_password_hashing_sha256');
  if (previous) {
    const bytes = Buffer.from(previous.password_hash, 'base64'), salt = bytes.subarray(0, 4);
    if (bytes.length === 36 && bytes.subarray(4).equals(createHash('sha256').update(salt).update(value).digest())) return previous.password_hash;
  }
  const salt = randomBytes(4); return Buffer.concat([salt, createHash('sha256').update(salt).update(value).digest()]).toString('base64');
};
const rabbit = {
  users: ['admin', ...Object.values(shortNames)].map(name => ({ name: `cutover_${name}`, password_hash: hashPassword(`cutover_${name}`, password(`rabbit_${name}`)), hashing_algorithm: 'rabbit_password_hashing_sha256', tags: name === 'admin' ? ['administrator'] : [] })),
  vhosts: [{ name: 'cutover' }],
  permissions: [
    { user: 'cutover_admin', vhost: 'cutover', configure: '.*', write: '.*', read: '.*' },
    ...consumers.map(name => ({ user: `cutover_${shortNames[name]}`, vhost: 'cutover', configure: '^$',
      write: name === 'shadow-scheduler' ? '^$' : `^(cutover\\.${name}\\.v1${name === 'legacy-core' ? '|cutover\\.observation\\.v1' : ''})$`,
      read: `^cutover\\.${name}\\.inbox$` })),
  ],
  exchanges: [...publishers.map(name => `cutover.${name}.v1`), 'cutover.observation.v1'].map(name => ({ name, vhost: 'cutover', type: 'topic', durable: true, auto_delete: false, internal: false, arguments: {} })),
  queues: consumers.map(name => ({ name: `cutover.${name}.inbox`, vhost: 'cutover', durable: true, auto_delete: false,
    arguments: { 'x-queue-type': 'quorum', 'x-max-length': name === 'shadow-scheduler' ? 1000 : 10000, 'x-max-length-bytes': name === 'shadow-scheduler' ? 8388608 : 67108864, 'x-overflow': 'reject-publish', 'x-delivery-limit': -1 } })),
  bindings: [
    ...['legacy-core', 'returns-service'].map(source => [source, 'equipment-adapter']),
    ...['legacy-core', 'execution-service', 'returns-service'].map(destination => ['equipment-adapter', destination]),
  ].map(([source, destination]) => ({ source: `cutover.${source}.v1`, vhost: 'cutover', destination: `cutover.${destination}.inbox`, destination_type: 'queue', routing_key: '#', arguments: {} })),
};
rabbit.bindings.push({ source: 'cutover.observation.v1', vhost: 'cutover', destination: 'cutover.shadow-scheduler.inbox', destination_type: 'queue', routing_key: '#', arguments: {} });
write('rabbit-definitions.json', JSON.stringify(rabbit, null, 2) + '\n');

const equipment = resolve(directory, 'equipment');
mkdirSync(equipment, { recursive: true });
function keytool(args) {
  const result = spawnSync(process.platform === 'win32' ? 'keytool.exe' : 'keytool', args, {
    cwd: equipment, encoding: 'utf8', windowsHide: true,
    env: { ...process.env, CUTOVER_CERT_PASSWORD: password('equipment_store') },
  });
  if (result.error || result.status !== 0) throw new Error(`Equipment certificate operation failed: ${result.error?.message ?? result.stderr}`);
}
const storePassword = ['-storepass:env', 'CUTOVER_CERT_PASSWORD'];
if (!existsSync(resolve(equipment, 'ca.p12'))) {
  keytool(['-genkeypair', '-alias', 'cutover-ca', '-dname', 'CN=Cutover Local Equipment CA', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650', '-ext', 'bc=ca:true', '-ext', 'ku=keyCertSign,cRLSign', '-storetype', 'PKCS12', '-keystore', 'ca.p12', ...storePassword]);
  keytool(['-exportcert', '-rfc', '-alias', 'cutover-ca', '-keystore', 'ca.p12', '-file', 'ca.pem', ...storePassword]);
}
for (const [name, commonName, usage] of [['simulator', 'cutover-equipment-simulator', 'serverAuth'], ['adapter', 'cutover-adapter', 'clientAuth'], ['scenario', 'cutover-scenario', 'clientAuth']]) {
  if (existsSync(resolve(equipment, `${name}.complete`))) continue;
  if (!existsSync(resolve(equipment, `${name}.p12`))) keytool(['-genkeypair', '-alias', name, '-dname', `CN=${commonName}`, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '365', '-storetype', 'PKCS12', '-keystore', `${name}.p12`, ...storePassword]);
  keytool(['-certreq', '-alias', name, '-keystore', `${name}.p12`, '-file', `${name}.csr`, ...storePassword]);
  const extensions = ['-ext', 'ku=digitalSignature,keyEncipherment', '-ext', `eku=${usage}`];
  if (name === 'simulator') extensions.push('-ext', 'san=dns:localhost,ip:127.0.0.1,dns:equipment-simulator,dns:cutover-equipment-simulator,dns:equipment-simulator.cutover-platform.svc.cluster.local');
  keytool(['-gencert', '-alias', 'cutover-ca', '-keystore', 'ca.p12', '-infile', `${name}.csr`, '-outfile', `${name}.pem`, '-rfc', '-validity', '365', ...extensions, ...storePassword]);
  const list = spawnSync(process.platform === 'win32' ? 'keytool.exe' : 'keytool', ['-list', '-alias', 'cutover-ca', '-keystore', `${name}.p12`, ...storePassword], { cwd: equipment, encoding: 'utf8', windowsHide: true, env: { ...process.env, CUTOVER_CERT_PASSWORD: password('equipment_store') } });
  if (list.status !== 0) keytool(['-importcert', '-noprompt', '-alias', 'cutover-ca', '-keystore', `${name}.p12`, '-file', 'ca.pem', ...storePassword]);
  keytool(['-importcert', '-noprompt', '-alias', name, '-keystore', `${name}.p12`, '-file', `${name}.pem`, ...storePassword]);
  writeFileSync(resolve(equipment, `${name}.complete`), new Date().toISOString() + '\n');
}
if (!existsSync(resolve(equipment, 'trust.p12'))) keytool(['-importcert', '-noprompt', '-alias', 'cutover-ca', '-storetype', 'PKCS12', '-keystore', 'trust.p12', '-file', 'ca.pem', ...storePassword]);

const environment = {};
for (const [key, value] of Object.entries(credentials.passwords)) environment[`CUTOVER_${key.toUpperCase().replaceAll('-', '_')}`] = value;
write('dev.env', Object.entries(environment).map(([key, value]) => `${key}=${value}`).join('\n') + '\n');
console.log('Local database credentials, realm configuration, and mutual TLS certificates are ready. Existing passwords and certificate identities were preserved.');
