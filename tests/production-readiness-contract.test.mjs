import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const contract = JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json', import.meta.url), 'utf8'));
const ajv = new Ajv2020({ strict: false, allErrors: true }); addFormats(ajv);
const validate = ajv.compile(contract.components.schemas.ProductionReadiness);
const fixed = [
  { id: 'SECURE_SESSION_SETTINGS', status: 'BLOCKED', code: 'SECURE_SESSION_NOT_CONFIGURED' },
  { id: 'PUBLIC_TLS_CERTIFICATE', status: 'NOT_RUN', code: 'PUBLIC_ENTRY_PROBE_REQUIRED' },
  { id: 'PRODUCTION_DATABASE_ISOLATION', status: 'CUSTOMER_GATED', code: 'Q02_PENDING' },
  { id: 'PRODUCTION_RUNTIME', status: 'BLOCKED', code: 'REMOTE_TEST_IS_NOT_PRODUCTION' },
];
const observed = Object.entries({ DATABASE_NAMESPACE: 'DEDICATED_TEST_SCOPE', DATABASE_ROLE: 'ROLE_RESTRICTED',
  DATABASE_DDL: 'RUNTIME_DDL_DENIED', MIGRATION_IDENTITY: 'MIGRATION_IDENTITY_SEPARATE',
  GLOBAL_DATABASE_CAPABILITIES: 'GLOBAL_CAPABILITIES_DENIED' }).map(([id, code]) => ({ id, status: 'PASS', code }));
const unknown = { tenant_id: '33000000-0000-4000-8000-000000000001', actor_id: '33000000-0000-4000-8000-000000000002',
  permission_version: 1, runtime_mode: 'remote_test', runtime_database_restricted: false,
  database_observed_at: null, database_version: null, production_database_kind: null, production_isolation_strategy: null,
  production_ready: false, activation_performed: false,
  checks: [{ id: 'DATABASE_OBSERVATION', status: 'NOT_READY', code: 'DATABASE_OBSERVATION_UNAVAILABLE' }, ...fixed] };

test('unobserved readiness cannot claim a passed database observation or substitute partial checks', () => {
  assert.equal(validate(unknown), true, JSON.stringify(validate.errors));
  for (const patch of [{ runtime_database_restricted: true }, { database_version: '17.9' },
    { checks: [{ ...unknown.checks[0], status: 'PASS' }, ...fixed] },
    { checks: [...observed, ...fixed] }, { checks: [...unknown.checks, fixed[0]] }]) {
    assert.equal(validate({ ...structuredClone(unknown), ...patch }), false);
  }
});

test('observed readiness has all five exact results and restricted iff they all pass', () => {
  const good = { ...unknown, database_observed_at: '2026-10-24T00:00:00Z', database_version: '17.9',
    runtime_database_restricted: true, checks: [...observed, ...fixed] };
  assert.equal(validate(good), true, JSON.stringify(validate.errors));
  assert.equal(validate({ ...good, runtime_database_restricted: false }), false);
  const blocked = { ...good, runtime_database_restricted: false,
    checks: [{ id: 'DATABASE_NAMESPACE', status: 'BLOCKED', code: 'RUNTIME_SCOPE_REJECTED' }, ...observed.slice(1), ...fixed] };
  assert.equal(validate(blocked), true, JSON.stringify(validate.errors));
  assert.equal(validate({ ...blocked, runtime_database_restricted: true }), false);
  assert.equal(validate({ ...good, checks: [{ ...observed[0], code: 'ROLE_RESTRICTED' }, ...observed.slice(1), ...fixed] }), false);
  assert.equal(validate({ ...good, checks: [...observed.slice(1), observed[1], ...fixed] }), false);
});
