import assert from 'node:assert/strict';
import { installComposeVolumeStatus } from './lib/volume-probe.mjs';
assert.equal(process.argv.length, 3, 'Specify application or simulator.');
installComposeVolumeStatus(process.argv[2]);
console.log('Installed the fixed local filesystem observation function in the selected Cutover owner databases.');
