import { verifyCheckpoint } from './lib/checkpoint.mjs';
const name = process.argv[2];
if (process.argv.length !== 3) throw new Error('Use node scripts/check-checkpoint.mjs <checkpoint-name>.');
const { manifest, manifestSha256 } = verifyCheckpoint(name);
console.log(JSON.stringify({ name, status: 'CHECKSUMS_VERIFIED', manifestSha256, finishedAt: manifest.finishedAt, databases: manifest.databases.map(database => ({ owner: database.owner, tables: database.rows.length, replayEvents: database.replay?.events.length })), worldId: manifest.physicalEnd.worldId, journalHighWater: manifest.physicalEnd.journalHighWater }, null, 2));
