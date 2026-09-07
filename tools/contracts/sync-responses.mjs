import { readFileSync, writeFileSync } from 'node:fs';
const base = new URL('../../contracts/', import.meta.url);
const text = { type: 'string' }, id = { type: 'string', format: 'uuid' }, integer = { type: 'integer' }, time = { type: 'string', format: 'date-time' };
const nullable = schema => ({ ...schema, type: [schema.type, 'null'] });
const array = items => ({ type: 'array', items });
const object = (properties, required = Object.keys(properties)) => ({ type: 'object', properties, required, additionalProperties: true });
const movement = object({ movementId: id, state: text, movement: object({ zoneId: text, quantity: integer, destination: text, source: text }) });
const order = object({ id, siteId: text, externalOrderRef: text, storeId: text, priority: integer, state: { type: 'string', enum: ['ACCEPTED', 'RESERVED', 'IN_PROGRESS', 'COMPLETED', 'COMPLETED_WITH_SHORTAGE', 'SHORTAGE', 'CANCELLED'] }, version: integer, createdAt: time, completedAt: nullable(time), observedAt: time,
  lines: array(object({ sku: text, requested: integer, reserved: integer, shortage: integer })), movements: array(movement) });
const definitions = {
  'order-view': order,
  'order-page': object({ items: array(order), nextCursor: nullable(id), observedAt: time }),
  'task-list': array(object({ id, movementId: id, orderId: id, zoneId: text, state: text, owner: text, epoch: nullable(integer), version: integer, lastError: nullable(text), eligibleAt: time })),
  'equipment-view': object({ worldId: id, journalGeneration: id, completeHistory: { type: 'boolean' }, observedAt: time, journalHighWater: integer, stale: { type: 'boolean' }, worldMismatch: { type: 'boolean' }, lanes: array(object({ siteId: text, laneId: text, zoneId: text, blocked: { type: 'boolean' }, version: integer })) }, ['stale', 'lanes']),
  'command-view': object({ commandId: id, allocationId: id, movementId: id, siteId: text, owner: text, epoch: integer, state: text, version: integer, attempts: integer, failureAttempts: integer,
    payload: object({ quantity: integer, source: text, destination: text, zoneId: text, laneId: text, worldId: id }),
    evidence: nullable(object({ worldId: id, executionSequence: integer, state: text, completedAt: time })), lastError: nullable(text), createdAt: time, completedAt: nullable(time) }),
};
const specPath = new URL('openapi/operations.v1.json', base);
const spec = JSON.parse(readFileSync(specPath, 'utf8'));
for (const [name, schema] of Object.entries(definitions)) {
  spec.components.schemas[name] = schema;
  writeFileSync(new URL(`schemas/${name}.v1.json`, base), JSON.stringify({ $schema: 'https://json-schema.org/draft/2020-12/schema', ...schema }, null, 2) + '\n');
}
const response = schema => ({ description: 'Current owner projection, with observation time where applicable', content: { 'application/json': { schema: { $ref: `#/components/schemas/${schema}` } } } });
spec.paths['/api/v1/sites/{siteId}/orders'].get.responses['200'] = response('order-page');
spec.paths['/api/v1/sites/{siteId}/orders/{id}'].get.responses['200'] = response('order-view');
for (const [suffix, schema, operationId] of [['tasks', 'task-list', 'listTasks'], ['equipment', 'equipment-view', 'getEquipment'], ['commands/{id}', 'command-view', 'getCommand']]) {
  const path = `/api/v1/sites/{siteId}/${suffix}`;
  spec.paths[path] = { parameters: [{ name: 'siteId', in: 'path', required: true, schema: text }, ...(suffix.includes('{id}') ? [{ name: 'id', in: 'path', required: true, schema: id }] : [])], get: { operationId, responses: { '200': response(schema), default: { $ref: '#/components/responses/problem' } } } };
}
writeFileSync(specPath, JSON.stringify(spec, null, 2) + '\n');
