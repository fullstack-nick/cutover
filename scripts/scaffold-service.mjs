import { mkdirSync, readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { resolve, dirname, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const [service, packageName] = process.argv.slice(2);
if (!/^[a-z][a-z0-9-]{2,39}-service$/.test(service ?? '') || !/^[a-z][a-z0-9]{1,20}$/.test(packageName ?? '')) throw new Error('Usage: node scripts/scaffold-service.mjs <name-service> <java-package-segment>');
const target = resolve(root, 'apps', service), source = resolve(root, 'platform/service-template/files');
if (!target.startsWith(resolve(root, 'apps') + sep) || existsSync(target)) throw new Error('The service target must be a new directory inside apps. Existing source is never overwritten.');
const replacements = value => value.replaceAll('__SERVICE__', service).replaceAll('__PACKAGE__', packageName);
const templates = [];
function walk(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.isSymbolicLink()) throw new Error('Template symlinks are not supported.');
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) walk(path);
    else if (entry.name.endsWith('.template')) templates.push(path);
  }
}
walk(source);
const plan = templates.sort().map(path => {
  const template = readFileSync(path, 'utf8').replaceAll('\r\n', '\n');
  const destination = replacements(relative(source, path).replace(/\.template$/, ''));
  return { destination, templateSha256: createHash('sha256').update(template).digest('hex'), contents: replacements(template) };
});
const pomPath = resolve(root, 'pom.xml'), pom = readFileSync(pomPath, 'utf8');
if (!pom.includes('  </modules>') || pom.includes(`<module>apps/${service}</module>`)) throw new Error('The root Maven module list must be reviewed before generation.');
for (const item of plan) { const path = resolve(target, item.destination); mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, item.contents, { flag: 'wx' }); }
writeFileSync(resolve(target, '.scaffold.json'), JSON.stringify({ template: 'platform/service-template', version: 1, service, packageName, files: plan.map(({ destination, templateSha256 }) => ({ path: destination.split(sep).join('/'), templateSha256 })) }, null, 2) + '\n');
writeFileSync(pomPath, pom.replace('  </modules>', `    <module>apps/${service}</module>\n  </modules>`));
console.log(`Generated ${plan.length} technical files for ${service}; implement and verify its own product behavior before deployment.`);
