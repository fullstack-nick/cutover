import { readFileSync, writeFileSync } from 'node:fs';
const base = new URL('../../contracts/', import.meta.url);
const read = path => JSON.parse(readFileSync(new URL(path, base), 'utf8'));
const write = (path, value) => writeFileSync(new URL(path, base), JSON.stringify(value, null, 2) + '\n');
const text = { type: 'string' }, id = { ...text, format: 'uuid' }, time = { ...text, format: 'date-time' };
const integer = { type: 'integer', minimum: 0 }, bool = { type: 'boolean' }, hash = { ...text, pattern: '^[a-f0-9]{64}$' };
const duration = { type: 'number', minimum: 0 };
const object = (properties, required = Object.keys(properties)) => ({ type: 'object', properties, required, additionalProperties: true });
const nullable = schema => ({ ...schema, type: [schema.type, 'null'] });
const array = (items, maxItems) => ({ type: 'array', items, ...(maxItems ? { maxItems } : {}) });
const owner = { ...text, enum: ['legacy-core', 'execution-service'] };
const phase = { ...text, enum: ['DRAINING', 'RECONCILING', 'READY_TO_SWITCH', 'OBSERVING', 'COMPLETED', 'REVERSING', 'REVERSED', 'SUPERSEDED', 'CANCELLED'] };
const route = object({ siteId: text, zoneId: text, owner: text, epoch: integer, state: text, version: integer });
const checkpoint = object({ inventoryHash: hash, count: integer, worldId: id, journalGeneration: id, journalHighWater: integer, proofChunkHashes: array(hash, 313) });
const observation = object({ sampleCount: integer, dispatchP99Millis: duration, withinTwoSeconds: bool, proofHash: hash, movements: array(object({ movementId: id, dispatchMillis: duration }), 10), proof: array({ type: 'object', additionalProperties: true }, 10) });
// Additive evidence fields: retained N-1 observations do not establish post-commit timing.
observation.properties.timingBasis = { ...text, description: 'SIMULATOR_ACCEPTANCE_UPPER_BOUND confirms the preceding adapter commit. Missing or unknown values do not establish durable dispatch timing.' };
observation.properties.movements.items.properties.eligibleAt = time;
observation.properties.movements.items.properties.dispatchConfirmedBy = time;
const migration = object({
  sessionId: id, siteId: text, zoneId: text, sourceOwner: owner, targetOwner: owner, sourceEpoch: integer, targetEpoch: nullable(integer), phase, version: integer,
  actor: text, reason: text, reversesSessionId: nullable(id), inventoryCount: integer, verifiedCount: integer, inventoryHash: nullable(hash), checkpointHash: nullable(hash), checkpoint: nullable(checkpoint),
  blockers: object({ count: integer, items: array(object({ reason: text, movementId: id, allocationId: id, owner: text, allocationState: text, commandState: text }, ['reason']), 100) }),
  observation: nullable(observation), transportAttempts: integer, transportPaused: bool, lastError: nullable(text), createdAt: time, switchedAt: nullable(time), finishedAt: nullable(time), observedAt: time
});
const task = object({ id, movementId: id, allocationId: id, zoneId: text, state: text, owner: text, epoch: integer, version: integer, lastError: nullable(text), eligibleAt: time, transportFailures: integer, transportPaused: bool, dispatchAcceptedAt: nullable(time) });
const operations = read('openapi/operations.v1.json');
for (const [name, schema] of Object.entries({ 'zone-route-list': array(route, 3), 'migration-view': migration, 'migration-list': object({ items: array(migration, 50), observedAt: time }), 'execution-task-list': array(task, 100) })) {
  write(`schemas/${name}.v1.json`, { $schema: 'https://json-schema.org/draft/2020-12/schema', ...schema });
  operations.components.schemas[name] = { $ref: `../schemas/${name}.v1.json` };
}
const parameter = (name, schema = text) => ({ name, in: 'path', required: true, schema });
const response = (description, schema) => ({ description, ...(schema ? { content: { 'application/json': { schema } } } : {}) });
const problem = { $ref: '#/components/responses/problem' };
const body = schema => ({ required: true, content: { 'application/json': { schema: { $ref: `../schemas/${schema}.v1.json` } } } });
const mutation = (operationId, description, schema, status = '200') => ({ operationId, description, parameters: [{ name: 'Idempotency-Key', in: 'header', required: true, schema: { ...text, minLength: 1, maxLength: 128 } }], requestBody: body(schema), responses: { [status]: response('Durably recorded session', { $ref: '#/components/schemas/migration-view' }), default: problem } });
const p = '/api/v1/sites/{siteId}';
for (const [suffix, schema, operationId] of [['zones', 'zone-route-list', 'listZoneRoutes'], ['migrations', 'migration-list', 'listMigrations'], ['migrations/{id}', 'migration-view', 'getMigration']]) {
  operations.paths[`${p}/${suffix}`] = { parameters: [parameter('siteId'), ...(suffix.includes('{id}') ? [parameter('id', id)] : [])], get: { operationId, description: 'Site-scoped operator/supervisor observation. Retained sessions are bounded; observation times identify stale data.', responses: { '200': response('Current migration observations', { $ref: `#/components/schemas/${schema}` }), default: problem } } };
}
operations.paths[`${p}/zones/{zoneId}/migrations`] = { parameters: [parameter('siteId'), parameter('zoneId')], post: mutation('startMigration', 'Supervisor starts drain with an expected route version and reason. New intents remain unassigned; all allocated work and physical evidence must reconcile before an atomic epoch change. A reversal is another complete drain.', 'migration-request', '202') };
for (const [suffix, operationId, description] of [
  ['recovery', 'recoverMigration', 'Supervisor resumes the same session after six exhausted owner-evidence transport attempts. Expected session version and reason are required. No physical identity or proof is replaced.'],
  ['cancellation', 'cancelMigration', 'Supervisor cancels only an unswitched session with the expected session version and reason. Existing owner/epoch remain; pending unassigned work is released. After switching, use a new reverse migration.']
]) operations.paths[`${p}/migrations/{id}/${suffix}`] = { parameters: [parameter('siteId'), parameter('id', id)], post: mutation(operationId, description, 'reconciliation-request') };
operations.paths[`${p}/execution-tasks`].get.responses['200'] = response('Independent execution task owner', { $ref: '#/components/schemas/execution-task-list' });
write('openapi/operations.v1.json', operations);
const internal = read('openapi/internal.v1.json');
internal.paths['/internal/v1/sites/{site}/zones/{zone}/migration-evidence'] = { parameters: [parameter('site'), parameter('zone')], post: { operationId: 'readOwnerMigrationEvidence', description: 'Read-only core/execution API restricted to the authenticated equipment-adapter service. At most 64 unique movement IDs; an empty core request checks that the guarded assignment boundary is installed. Missing and wrong-zone movements are explicit. Each owner reads only its own database.', requestBody: body('migration-evidence-request'), responses: { '200': response('Bounded owner proof and assignment-boundary capability'), default: problem } } };
const faults = '/internal/v1/sites/{site}/test-controls/migration-faults';
internal.paths[faults] = { parameters: [parameter('site')], get: { operationId: 'listMigrationProcessFaults', responses: { '200': response('Latest 100 one-shot process faults'), default: problem } }, post: { ...mutation('armMigrationProcessFault', 'Explicitly enabled scenario-driver/test-control only. Expected adapter control version. Saves a one-shot fault before an actual process halt with exit 73 after the selected durable phase commits. A selected existing session or a session created after arming can consume it. Consumption is committed before halt; restart cannot repeat it.', 'migration-process-fault-request'), responses: { '200': response('Recorded fault identity and version'), default: problem } } };
internal.paths[`${faults}/{id}/clear`] = { parameters: [parameter('site'), parameter('id', id)], post: { ...mutation('clearMigrationProcessFault', 'Explicit scenario-driver/test-control clears an armed fault with expected fault version and an audited reason.', 'reconciliation-request'), responses: { '200': response('Cleared fault record'), default: problem } } };
write('openapi/internal.v1.json', internal);
console.log('Migration API, owner evidence, process fault and task contracts synchronized.');
