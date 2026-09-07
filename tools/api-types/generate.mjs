import openapiTS, { astToString } from 'openapi-typescript';
import { writeFileSync, mkdirSync } from 'node:fs';
const input = new URL('../../contracts/openapi/operations.v1.json', import.meta.url);
const output = new URL('../../apps/operations-console/src/api.generated.ts', import.meta.url);
const ast = await openapiTS(input);
mkdirSync(new URL('.', output), { recursive: true });
writeFileSync(output, astToString(ast));
console.log('Generated console API types from the committed OpenAPI contract.');
