import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const read = path => readFileSync(new URL('../' + path, import.meta.url), 'utf8');
const catalog = JSON.parse(read('config/demo/catalog.v1.json'));
const golden = JSON.parse(read('fixtures/demo-preparation-golden.json'));
const contract = JSON.parse(read('contracts/local-api.openapi.json'));
const ajv = new Ajv2020({ strict: false, allErrors: true }); addFormats(ajv); ajv.addFormat('binary', true);
ajv.addSchema({ $id: 'urn:mop:demo-api', components: contract.components });
const refs = value => Array.isArray(value) ? value.map(refs) : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([k, v]) => [k, k === '$ref' && typeof v === 'string' && v.startsWith('#/components/') ? 'urn:mop:demo-api' + v : refs(v)])) : value;

test('demo coverage locks actual source versions without modifying frontend', () => {
  for (const [path, sha] of Object.entries(catalog.source_sha256)) assert.equal(createHash('sha256').update(read(path)).digest('hex'), sha, path);
  assert.equal(catalog.reference_api_version, contract.info.version);
  const routes = read('apps/web/src/router.ts').split('\n').filter(line => line.includes('component:')).map(line => /path: '([^']+)'/.exec(line)?.[1]);
  assert.ok(routes.every(Boolean));
  assert.deepEqual(catalog.features.map(f => f.path).sort(), routes.filter(path => !['/login','/access-unavailable','/:pathMatch(.*)*'].includes(path)).sort());
});

test('all prepared demo request bodies match current API schemas, not runtime existence', () => {
  assert.equal(golden.request_fixtures.length, 40);
  for (const request of golden.request_fixtures) {
    const operation = contract.paths[request.path]?.[request.method.toLowerCase()];
    assert.ok(operation, request.key);
    const schema = operation.requestBody?.content?.['application/json']?.schema; assert.ok(schema, request.key);
    const validate = ajv.compile(refs(schema));
    assert.equal(validate(request.body), true, request.key + ': ' + JSON.stringify(validate.errors));
    assert.equal(request.executed, false); assert.equal(request.references_verified, false);
  }
});

test('demo account preparation does not assert granted access or all-feature acceptance', () => {
  assert.equal(golden.coverage.pages_declared, 42); assert.equal(golden.coverage.capability_union_count, 48);
  assert.equal(golden.coverage.pages_accepted, 0); assert.equal(golden.coverage.all_functions_ready, false);
  for (const flag of ['account_created','credentials_created','permissions_granted','business_data_written','outbound_enabled']) assert.equal(golden[flag], false);
  assert.ok(golden.blockers.includes('GLOBAL_POLICY_SCOPE_UNRESOLVED'));
  for (const constraint of catalog.global_operation_constraints) assert.ok(Object.values(contract.paths).flatMap(path => Object.values(path)).some(op => op.operationId === constraint.operation_id));
});

test('demo proposed roles respect declared capabilities and page restrictions', () => {
  const source = read('server/domain/src/main/java/cn/mop/domain/Capabilities.java');
  const roles = Object.fromEntries([...source.matchAll(/case "([a-z]+)" -> List.of\((.*?)\);/g)].map(([, role, body]) => [role, [...body.matchAll(/"([a-z_]+:[a-z_]+)"/g)].map(m => m[1])]));
  for (const context of golden.contexts) assert.deepEqual(context.proposed_permissions, roles[context.base_role]);
  for (const feature of golden.features) for (const role of feature.proposed_contexts) {
    assert.ok(feature.required_capabilities.every(cap => roles[role].includes(cap)));
    assert.ok(!feature.allowed_roles.length || feature.allowed_roles.includes(role));
    assert.equal(feature.acceptance_status, 'NOT_RUN');
  }
});
