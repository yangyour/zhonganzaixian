import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const contract = JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json', import.meta.url), 'utf8'));
const ajv = new Ajv2020({ strict: false });
addFormats(ajv); ajv.addFormat('binary', true);
ajv.addSchema({ $id: 'urn:mop:delivery-attention', components: contract.components });
const validate = ajv.getSchema('urn:mop:delivery-attention#/components/schemas/DeliveryAttentionPage');
const uuid = '79000000-0000-4000-8000-000000000001';

test('attention contract exposes complete bounded read metadata while preserving unverified declarations', () => {
  const page = { tenant_id: uuid, observed_at: '2026-10-24T00:00:00Z', source: 'postgresql_delivery_receipt_journal',
    provider_receipt_verified: false, automatic_send_enabled: false, provider_lookup_invoked: false,
    verified_delivered_count: null, status_meaning: 'JOURNAL_DECLARATION_NOT_PROVIDER_VERIFICATION',
    total_count: 0, page_size: 10, items: [], next: null, snapshot_scope: 'CURRENT_READ_PAGE_NOT_GLOBAL_SNAPSHOT',
    status_filter: 'ATTENTION', scanned_roots: 12, population_scope: 'COMPLETE_BOUNDED_VERIFIED_JOURNAL_HEADS',
    root_scan_limit: 1000, revision_scan_limit: 24002 };
  assert.equal(validate(page), true);
  for (const patch of [{ scanned_roots: 1001 }, { root_scan_limit: 20 }, { population_scope: 'PAGE_ONLY' },
    { status_filter: 'DELIVERED' }, { provider_receipt_verified: true }, { fresh_send_authorized: true }]) {
    assert.equal(validate({ ...page, ...patch }), false);
  }
});
test('attention route stays read-only with scope capabilities and an explicit budget error', () => {
  const path = contract.paths['/notification-deliveries/attention'];
  assert.deepEqual(Object.keys(path), ['get']);
  assert.equal(path.get['x-mop-external-send'], false);
  assert.equal(path.get['x-mop-business-writes'], false);
  assert.equal(path.get['x-mop-current-membership-recheck'], true);
  assert.ok(path.get.responses['422']);
  assert.deepEqual(path.get.parameters[0].schema.enum, ['ATTENTION', 'UNKNOWN', 'FAILED', 'DEAD_LETTER']);
});
