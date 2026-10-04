// Explicit environment labels and separate private/evidence paths. Never infer a fallback.
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync } from 'node:fs';
export const realEnvironment = process.env.MOP_REAL_TEST_ENVIRONMENT ?? 'niko-local-test';
assert.ok(['niko-local-test', 'designated-server'].includes(realEnvironment), 'Unknown real test environment');
export const realTestDirectory = realEnvironment === 'designated-server' ? '.local/designated-test/' : '.local/niko-test/';
export const realEvidencePrefix = realEnvironment === 'designated-server' ? 'local' : 'niko';
export const realEnvironmentInstance = realEnvironment === 'designated-server' ? 'original-server-from-Windows' : 'rebuilt-20261003-after-executor-loss';
export const actualApiVersion = JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json',import.meta.url),'utf8')).info.version;
mkdirSync(new URL('../' + realTestDirectory, import.meta.url), { recursive: true });
