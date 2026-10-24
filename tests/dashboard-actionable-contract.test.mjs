import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const contract = JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json', import.meta.url), 'utf8'));
const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
ajv.addSchema({ $id: 'urn:mop:actionable', components: contract.components });
const validate = ajv.getSchema('urn:mop:actionable#/components/schemas/DashboardActionableTicketsResult');
const id = '11000000-0000-4000-8000-000000000001';
const clock = { status: 'RUNNING', breached: false, missing_reason: null,
  deadline_at: '2026-10-24T12:00:00Z', completed_at: null, remaining_millis: 0,
  remaining_basis: 'FROZEN_SERVICE_WINDOWS' };
const item = { ticket_id: id, receipt_no: 'SYNTHETIC-1', title: 'Synthetic contract check',
  registered_at: '2026-10-23T12:00:00Z', workflow_model: 'WORKFLOW_V33', state: 'OPEN', version: 1,
  priority: 'P1', frozen_service_tier: 'STANDARD', sla_metric_version: 'SLA_CLOCK_V2', sla_version: 1,
  overdue: false, response_clock: clock, recovery_clock: clock };
const base = { tenant_id: id, actor_id: id, permission_version: 1, observed_at: '2026-10-24T00:00:00Z',
  scope: 'CURRENT_TENANT_INTERNAL', source: 'postgresql', metric_version: 'MOP_ACTIONABLE_TICKETS_V1',
  projection_version: 'MOP_ACTIONABLE_TICKETS_SLA_V1', excluded_states: ['CLOSED', 'CANCELLED', 'RESOLVED'],
  registrations: 101, known_pending: 101, excluded: 0, unconfigured: 0, pending_tickets: 101,
  overdue_sla: 0, known_overdue: 0, unknown_sla: 0, evaluated_pending: 101, unevaluated_pending: 0,
  evaluation_limit: null, unavailable_reasons: [], item_limit: 1, page: 101, page_size: 1,
  total_pages: 101, sort: 'REGISTRATION_CREATED_AT_DESC_ID_DESC', writes_performed: false, items: [item] };
const accepts = patch => validate({ data: { ...structuredClone(base), ...patch }, trace_id: 'synthetic-contract' });

test('actionable contract permits full-population SLA and a page beyond the legacy 100-ticket boundary', () => {
  assert.equal(accepts({}), true, JSON.stringify(validate.errors));
  assert.equal(accepts({ evaluated_pending: 201, known_pending: 201, pending_tickets: 201, registrations: 201 }), true);
  assert.equal(accepts({ evaluation_limit: 100 }), false);
  assert.equal(accepts({ unevaluated_pending: 1 }), false);
});

test('both persisted models exclude resolved and closed while lifecycle pending customer remains visible', () => {
  for (const workflow_model of ['WORKFLOW_V33', 'LIFECYCLE_V42']) {
    for (const state of ['RESOLVED', 'CLOSED', 'CANCELLED', 'UNKNOWN']) {
      assert.equal(accepts({ items: [{ ...item, workflow_model, state }] }), false, workflow_model + ':' + state);
    }
  }
  assert.equal(accepts({ items: [{ ...item, workflow_model: 'LIFECYCLE_V42', state: 'PENDING_CUSTOMER' }] }), true);
  assert.equal(accepts({ items: [{ ...item, workflow_model: 'WORKFLOW_V33', state: 'PENDING_CUSTOMER' }] }), false);
});

test('unknown aggregate fields remain null and explicit zero remains a valid SLA result', () => {
  assert.equal(accepts({ unknown_sla: 1, overdue_sla: null }), true);
  assert.equal(accepts({ unknown_sla: 1, overdue_sla: 0 }), false);
  assert.equal(accepts({ unconfigured: 1, pending_tickets: null, unknown_sla: 1, overdue_sla: null }), true);
  assert.equal(accepts({ unconfigured: 1, pending_tickets: 101 }), false);
  assert.equal(accepts({ overdue_sla: 0, unknown_sla: 0 }), true);
});

test('new endpoint declares strict pagination and leaves legacy contracts unchanged', () => {
  const operation = contract.paths['/dashboard/actionable-tickets'].get;
  assert.deepEqual(operation['x-mop-allowed-query-keys'], ['page', 'page_size']);
  assert.equal(operation['x-mop-snapshot-isolation'], 'REPEATABLE_READ');
  assert.deepEqual(operation['x-mop-capabilities'], ['dashboard:read', 'tickets:read']);
  assert.equal(operation.parameters.find(x => x.name === 'page_size').schema.maximum, 100);
  assert.equal(contract.components.schemas.DashboardPendingTicketSla.properties.evaluation_limit.const, 100);
  assert.equal(contract.components.schemas.DashboardPendingTickets.properties.metric_version.const,
    'MOP_VISIBLE_NONTERMINAL_TICKETS_V1');
});
