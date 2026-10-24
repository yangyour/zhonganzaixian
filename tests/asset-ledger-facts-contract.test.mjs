import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const contract = JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json', import.meta.url), 'utf8'));
const operation = contract.paths['/assets/ledger-facts'].get;
const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
const validate = ajv.compile(operation.responses[200].content['application/json'].schema);
const id = '22000000-0000-4000-8000-000000000001';
const fact = { asset_id: id, asset_version: 1, site_id: 'synthetic', site_name: 'Synthetic site',
  metadata_version: null, metadata_recorded_at: null, metadata_source: 'NOT_RECORDED',
  business_description: null, owner: null };
const base = { tenant_id: id, observed_at: '2026-10-24T00:00:00Z', items: [fact], source: 'postgresql',
  scope: 'CURRENT_TENANT_PUBLIC_ASSET_FIELDS', business_relationship_verified: false, proxy_verified: false };
const accepts = patch => validate({ data: { ...structuredClone(base), ...patch }, trace_id: 'synthetic' });

test('ledger public facts retain unknown metadata and never imply verified business or proxy mappings', () => {
  assert.equal(accepts({}), true);
  for (const patch of [{ business_relationship_verified: true }, { proxy_verified: true }, { source: 'fixture' }, { items: [] }]) {
    assert.equal(accepts(patch), false);
  }
  for (const patch of [{ owner: { id, display_name: 'Synthetic', status: 'ACTIVE' } },
    { business_description: 'Invented description' }, { metadata_version: 2 }, { metadata_recorded_at: base.observed_at }]) {
    assert.equal(accepts({ items: [{ ...fact, ...patch }] }), false);
  }
});

test('recorded declarations require exact revision identity and permit inactive public owners without internal fields', () => {
  const recorded = { ...fact, asset_version: 3, metadata_version: 2, metadata_recorded_at: base.observed_at,
    metadata_source: 'MANUAL_DECLARATION', business_description: 'Synthetic manual declaration',
    owner: { id, display_name: 'Synthetic owner', status: 'INACTIVE' } };
  assert.equal(accepts({ items: [recorded] }), true);
  for (const patch of [{ metadata_version: null }, { metadata_recorded_at: null }, { internal_note: 'Hidden' },
    { before_snapshot: {} }, { management_ip: '192.0.2.1' }, { owner: { ...recorded.owner, email: 'hidden@example.invalid' } }]) {
    assert.equal(accepts({ items: [{ ...recorded, ...patch }] }), false);
  }
});

test('ledger facts declare a read-only authorized bounded batch and uniform hidden-object errors', () => {
  assert.equal(operation['x-mop-read-only'], true);
  assert.deepEqual(operation['x-mop-capabilities'], ['assets:read']);
  assert.equal(operation.parameters[0].name, 'ids');
  for (const code of [400, 401, 403, 404, 503]) {
    assert.equal(operation.responses[code].content['application/json'].schema.$ref, '#/components/schemas/Error');
  }
});
