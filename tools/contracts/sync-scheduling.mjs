import { readFileSync, writeFileSync } from 'node:fs';
const base = new URL('../../contracts/', import.meta.url);
const read = path => JSON.parse(readFileSync(new URL(path, base), 'utf8'));
const write = (path, value) => writeFileSync(new URL(path, base), JSON.stringify(value, null, 2) + '\n');
const id = { type: 'string', format: 'uuid' }, text = { type: 'string' }, time = { type: 'string', format: 'date-time' };
const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: true });
const nullable = schema => ({ ...schema, type: [schema.type, 'null'] });
const snapshot = read('schemas/scheduling-snapshot.v1.json'); delete snapshot.$schema;
const proposal = object({
  ruleVersion: { const: 1 }, selectedMovementId: nullable(id), selectedLaneId: nullable(text),
  ranking: { type: 'array', maxItems: 64, items: object({ movementId: id, reason: { enum: ['READY', 'WORLD_MISMATCH', 'OBSERVATION_STALE', 'ROUTE_UNCERTAIN', 'ZONE_MISMATCH', 'OWNER_MISMATCH', 'UNASSIGNED', 'COMMAND_RECORDED', 'NOT_ELIGIBLE', 'LANE_BLOCKED'] }, laneId: nullable(text) }) },
});
const payload = object({ roundId: id, siteId: { ...text, pattern: '^[a-z][a-z0-9-]{0,39}$' }, inputHash: { ...text, pattern: '^[a-f0-9]{64}$' }, origin: { enum: ['LIVE', 'SEEDED_TEST'] }, input: snapshot, legacyProposal: proposal });
const comparison = object({ roundId: id, inputHash: { ...text, pattern: '^[a-f0-9]{64}$' }, input: snapshot, legacyProposal: proposal, executionProposal: proposal, matches: { type: 'boolean' }, comparedAt: time });
for (const [name, schema] of Object.entries({ 'scheduling-proposal': proposal, 'scheduling-observation': payload, 'scheduling-comparison': comparison })) write('schemas/' + name + '.v1.json', { $schema: 'https://json-schema.org/draft/2020-12/schema', ...schema });
const operations = read('openapi/operations.v1.json');
const parameter = (name, schema = text) => ({ name, in: 'path', required: true, schema });
const response = (description, schema) => ({ description, ...(schema ? { content: { 'application/json': { schema } } } : {}) });
const problem = { $ref: '#/components/responses/problem' };
operations.components.schemas['scheduling-comparison'] = comparison;
operations.paths['/api/v1/sites/{siteId}/shadow-comparisons'] = { parameters: [parameter('siteId')], get: {
  operationId: 'listShadowComparisons', description: 'Operator/supervisor read of the isolated shadow evidence database; latest 50 summaries and total/mismatch counts.',
  responses: { '200': response('Comparison totals and retained summaries'), default: problem },
} };
operations.paths['/api/v1/sites/{siteId}/shadow-comparisons/{id}'] = { parameters: [parameter('siteId'), parameter('id', id)], get: {
  operationId: 'getShadowComparison', responses: { '200': response('Identical input with both proposals', { $ref: '#/components/schemas/scheduling-comparison' }), default: problem },
} };
operations.paths['/api/v1/sites/{siteId}/tasks/{id}/recovery'] = { parameters: [parameter('siteId'), parameter('id', id)], post: {
  operationId: 'recoverLegacyTask', description: 'Supervisor resumes six exhausted transport observations with expected version and reason. Retains movement/allocation/command identities.',
  parameters: [{ name: 'Idempotency-Key', in: 'header', required: true, schema: { ...text, maxLength: 128 } }],
  requestBody: { required: true, content: { 'application/json': { schema: { $ref: '../schemas/reconciliation-request.v1.json' } } } },
  responses: { '200': response('Audited status investigation requested'), default: problem },
} };
write('openapi/operations.v1.json', operations);
const internal = read('openapi/internal.v1.json');
internal.paths['/internal/v1/sites/{site}/zones/{zone}/scheduling-context'] = { parameters: [parameter('site'), parameter('zone')], post: {
  operationId: 'readSchedulingContext', description: 'Read-only service query. Returns route, site-filtered equipment, allocations and known commands for at most 64 unique movement IDs while sharing the authoritative route lock. Intake independently rechecks every dispatch.',
  requestBody: { required: true, content: { 'application/json': { schema: object({ movementIds: { type: 'array', maxItems: 64, uniqueItems: true, items: id } }) } } },
  responses: { '200': response('Bounded authoritative observations'), default: problem },
} };
internal.paths['/internal/v1/sites/{site}/test-controls/scheduling-rounds'] = { parameters: [parameter('site')], post: {
  operationId: 'recordSeededSchedulingRound', description: 'Explicitly enabled scenario-driver/test-control interface. Runs the actual legacy SQL, persists the synthetic input and publishes only to the separate observation queue. Never creates a task or equipment command.',
  requestBody: { required: true, content: { 'application/json': { schema: { $ref: '../schemas/scheduling-snapshot.v1.json' } } } },
  responses: { '200': response('Persisted round identity and input hash'), default: problem },
} };
write('openapi/internal.v1.json', internal);
const asyncapi = read('asyncapi/events.v1.json');
const envelope = read('schemas/event-envelope.v1.json'); delete envelope.$schema;
envelope.properties.eventType = { const: 'SchedulingSnapshotRecorded.v1' };
envelope.properties.source = { const: 'legacy-core' };
envelope.properties.payload = payload;
asyncapi.components.messages.schedulingObservation = { name: 'SchedulingSnapshotRecordedV1', payload: envelope };
asyncapi.channels.observation = { address: 'cutover.observation.v1', description: 'Dedicated bounded observation exchange. Only legacy-core publishes; the shadow identity consumes. It is independent of every business outbox quota.', messages: { snapshot: { $ref: '#/components/messages/schedulingObservation' } } };
asyncapi.operations.publishSchedulingObservation = { action: 'send', channel: { $ref: '#/channels/observation' } };
write('asyncapi/events.v1.json', asyncapi);
console.log('Scheduling schemas and API/event contracts synchronized.');
