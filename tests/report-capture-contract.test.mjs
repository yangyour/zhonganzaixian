import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const contract = JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json', import.meta.url), 'utf8'));
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema({ $id: 'urn:mop:report-capture', components: contract.components });
const validate = ajv.getSchema('urn:mop:report-capture#/components/schemas/InspectionQuarterlyReportCapture');
const input = () => ({
  expected_version: 0, label: 'SYNTHETIC_REPORT_CAPTURE', year: 2026, quarter: 4,
  sources: [{ worksheet_id: '10000000-0000-4000-8000-000000000001', version: 2, expected_source_sha256: 'a'.repeat(64) }],
  configuration: { revision: null, time_zone: null, duplicate_rule: null, archived_rule: null, metric_version: null, report_template_version: null }
});

test('capture contract requires exact source versions and preserves null configuration', () => {
  assert.equal(validate(input()), true);
  for (const patch of [{ expected_version: 1 }, { year: 2026.5 }, { quarter: 5 }, { tenant_id: 'forged' }, { report_published: true }]) {
    assert.equal(validate({ ...input(), ...patch }), false);
  }
  for (const patch of [{ version: 0 }, { expected_source_sha256: null }, { expected_source_sha256: 'unknown' }]) {
    const body = input();
    Object.assign(body.sources[0], patch);
    assert.equal(validate(body), false);
  }
});

test('capture contract covers all permissions, atomic writes and replay protection', () => {
  const post = contract.paths['/inspection-quarterly-reports/capture'].post;
  assert.equal(post['x-mop-capability'], 'inspection_quarterly_reports:write');
  assert.deepEqual(post['x-mop-additional-capabilities'], ['inspection_source_collections:read', 'inspection_source_collections:write', 'inspection_worksheets:write']);
  assert.equal(post['x-mop-idempotent-replay-reauthorization'], true);
  assert.equal(post['x-mop-max-body-bytes'], 262144);
  assert.deepEqual(post.parameters.map(parameter => parameter.name), ['Idempotency-Key', 'X-CSRF-Token']);
  assert.deepEqual(post['x-mop-atomic-writes'], ['source_collection', 'source_collection_items', 'quarterly_report', 'audit', 'idempotency_receipt']);
  assert.equal(contract.components.schemas.InspectionQuarterlyReportCaptureSummary.properties.report_published.const, false);
});

test('pending ticket facts preserve unknown counts and exclude terminal rows', () => {
  const validate = ajv.getSchema('urn:mop:report-capture#/components/schemas/DashboardPendingTickets');
  const sample = { tenant_id: '20000000-0000-4000-8000-000000000002', actor_id: '10000000-0000-4000-8000-000000000001',
    permission_version: 1, observed_at: '2026-10-23T08:00:00Z', scope: 'REPORTER_ONLY', source: 'postgresql',
    metric_version: 'MOP_VISIBLE_NONTERMINAL_TICKETS_V1', registrations: 1, known_pending: 0, terminal: 0, unconfigured: 1,
    pending_tickets: null, state: 'PARTIAL', unavailable_reason: 'REGISTRATIONS_WITHOUT_WORKFLOW', item_limit: 5,
    sort: 'REGISTRATION_CREATED_AT_DESC_ID_DESC', items: [], overdue_sla: null, writes_performed: false };
  assert.equal(validate(sample), true);
  assert.equal(validate({ ...sample, pending_tickets: 0 }), false);
  assert.equal(validate({ ...sample, state: 'AVAILABLE' }), false);
  assert.equal(validate({ ...sample, overdue_sla: 0 }), false);
  assert.equal(validate({ ...sample, items: [{ ticket_id: sample.tenant_id, receipt_no: 'SYNTHETIC', title: '合成',
    registered_at: sample.observed_at, workflow_model: 'WORKFLOW_V33', state: 'CANCELLED', version: 2 }] }), false);
  const route = contract.paths['/dashboard/pending-tickets'].get;
  assert.equal(route['x-mop-customer-scope'], 'REPORTER_ONLY');
  assert.equal(route['x-mop-snapshot-isolation'], 'REPEATABLE_READ');
});

test('SLA V2 remains additive and unknown SLA cannot become a complete zero count', () => {
  const validate = ajv.getSchema('urn:mop:report-capture#/components/schemas/DashboardPendingTicketSla');
  const sample = { tenant_id: '20000000-0000-4000-8000-000000000002', actor_id: '10000000-0000-4000-8000-000000000001',
    permission_version: 1, observed_at: '2026-10-23T08:00:00Z', scope: 'REPORTER_ONLY', source: 'postgresql',
    projection_version: 'MOP_PENDING_TICKETS_SLA_V2', registrations: 1, known_pending: 0, terminal: 0, unconfigured: 1,
    pending_tickets: null, overdue_sla: null, known_overdue: 0, unknown_sla: 1, evaluated_pending: 0, unevaluated_pending: 0,
    evaluation_limit: 100, unavailable_reasons: ['REGISTRATIONS_WITHOUT_WORKFLOW'], item_limit: 5,
    sort: 'REGISTRATION_CREATED_AT_DESC_ID_DESC', writes_performed: false, items: [] };
  assert.equal(validate(sample), true);
  assert.equal(validate({ ...sample, overdue_sla: 0 }), false);
  assert.equal(validate({ ...sample, pending_tickets: 0 }), false);
  assert.equal(validate({ ...sample, evaluation_limit: 10000 }), false);
  assert.equal(contract.components.schemas.DashboardPendingTickets.properties.overdue_sla.type, 'null');
  assert.equal(contract.paths['/dashboard/pending-tickets/sla'].get['x-mop-snapshot-isolation'], 'REPEATABLE_READ');
});
