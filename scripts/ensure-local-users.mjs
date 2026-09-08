import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

// Additive fixture provisioning: realm import intentionally does not overwrite an existing realm.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const configured = JSON.parse(readFileSync(resolve(root, '.local/secrets/realm/cutover-realm.json'), 'utf8'));
const credentials = JSON.parse(readFileSync(resolve(root, '.local/secrets/credentials.json'), 'utf8')).passwords;
const allowed = ['operator-a', 'supervisor-a', 'supervisor-a2', 'operator-b', 'platform-admin'];
assert.equal(configured.realm, 'cutover');
const origin = 'http://localhost:8780/identity';
const signed = await fetch(origin + '/realms/master/protocol/openid-connect/token', {
  method: 'POST', body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli', username: 'bootstrap-admin', password: credentials.keycloak_admin }), signal: AbortSignal.timeout(10000)
});
if (!signed.ok) throw Error('Local fixture administration could not authenticate (' + signed.status + '). Existing identities were preserved.');
const authorization = 'Bearer ' + (await signed.json()).access_token;
async function api(path, method = 'GET', body) {
  const response = await fetch(origin + '/admin/realms/cutover' + path, { method, headers: { Authorization: authorization, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) });
  if (!response.ok) {
    const detail = path === '/users/profile' ? JSON.stringify(await response.json().catch(() => ({}))).slice(0, 2000) : 'no credential data is logged';
    throw Error('Local fixture identity operation failed (' + response.status + '): ' + detail);
  }
  return response.status === 204 || response.status === 201 ? undefined : response.json();
}
let added = 0;
// Site membership is authority, so only an administrator may edit or view it in profile APIs.
// Defining it explicitly avoids Keycloak's default filtering of unmanaged attributes.
const profile = await api('/users/profile');
const sites = { name: 'sites', displayName: 'Authorized sites', multivalued: true, permissions: { view: ['admin'], edit: ['admin'] }, validations: { pattern: { pattern: '^site-[ab]$' }, multivalued: { max: 2 } } };
const existingSites = profile.attributes.findIndex(attribute => attribute.name === 'sites');
if (existingSites < 0) profile.attributes.push(sites); else profile.attributes[existingSites] = sites;
// Keycloak 26.7.3 represents disabled by absence; its enum has no DISABLED member.
delete profile.unmanagedAttributePolicy;
await api('/users/profile', 'PUT', profile);
const actualProfile = await api('/users/profile');
assert.deepEqual(actualProfile.attributes.find(attribute => attribute.name === 'sites').permissions, sites.permissions);
for (const name of allowed) {
  const fixture = configured.users.find(user => user.username === name);
  if (!fixture) throw Error('Regenerate the local realm before provisioning its configured fictional users.');
  let matches = await api('/users?exact=true&username=' + encodeURIComponent(name));
  if (!matches.length) {
    const { realmRoles, ...user } = fixture;
    await api('/users', 'POST', user);
    matches = await api('/users?exact=true&username=' + encodeURIComponent(name));
    added++;
  }
  assert.equal(matches.length, 1, 'A configured local fixture must have exactly one identity.');
  const user = await api('/users/' + matches[0].id); assert.equal(user.username, name); assert.equal(user.enabled, true);
  assert.deepEqual(user.attributes?.sites, fixture.attributes.sites, 'Existing fixture site memberships must match the reviewed realm.');
  const roles = await api('/users/' + user.id + '/role-mappings/realm');
  const missing = [];
  for (const role of fixture.realmRoles) if (!roles.some(actual => actual.name === role)) missing.push(await api('/roles/' + encodeURIComponent(role)));
  if (missing.length) await api('/users/' + user.id + '/role-mappings/realm', 'POST', missing);
  const verified = await api('/users/' + user.id + '/role-mappings/realm');
  assert.ok(fixture.realmRoles.every(role => verified.some(actual => actual.name === role)));
}
console.log('Verified ' + allowed.length + ' configured fictional identities; added ' + added + '. Existing credentials and site memberships were preserved.');
