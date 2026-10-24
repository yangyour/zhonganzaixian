import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const contract = JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json', import.meta.url), 'utf8'));
const ajv = new Ajv2020({ strict: false });
addFormats(ajv);
ajv.addFormat('binary', true);
ajv.addSchema({ $id: 'urn:mop:readership', components: contract.components });
const validate = ajv.getSchema('urn:mop:readership#/components/schemas/ReportReadership');
const uuid = '79000000-0000-4000-8000-000000000001';
const page = { tenant_id: uuid, document_id: uuid, document_sha256: 'a'.repeat(64), state_version: 5,
  published_at: '2026-10-15T00:00:00Z', observed_at: '2026-10-24T00:00:00Z', filter: 'FOLLOWUP_DUE',
  total_recipients: 1, read_count: 0, unread_count: 1, followup_due_count: 1, matching_count: 1,
  items: [{ customer_id: uuid, display_name: null, first_read_at: null, followup_due: true }], next_after: null };

test('readership exposes only management GET, strict filters, bounded cursor and no sending operation', () => {
  const path = contract.paths['/report-publications/{id}/readership'];
  assert.deepEqual(Object.keys(path), ['get']);
  assert.equal(path.get['x-mop-external-dispatch'], 'DISABLED');
  assert.deepEqual(path.get.parameters.map(value => value.name), ['id', 'filter', 'after']);
  assert.deepEqual(path.get.parameters[1].schema.enum, ['ALL', 'UNREAD', 'FOLLOWUP_DUE']);
  assert.ok(path.get.responses['403']);
  assert.ok(path.get.responses['404']);
  assert.ok(path.get.responses['503']);
});
test('readership preserves unknown names/read times and rejects scope overrides and malformed rows', () => {
  assert.equal(validate(page), true);
  for (const patch of [{ tenant_id: 'foreign' }, { items: Array(51).fill(page.items[0]) },
    { read_count: -1 }, { matching_count: 1001 }, { next_after: 'bad' }, { filter: 'DELIVERED' },
    { notification_sent: true }, { items: [{ ...page.items[0], followup_due: 'true' }] }]) {
    assert.equal(validate({ ...page, ...patch }), false);
  }
});
