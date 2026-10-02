// Actual frontend column projection and ApiClient against real niko-local-test PG/OIDC API.
// Read-only requests; no DOM, fixture, internal metadata lookup or synthetic monitoring values.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { ApiClient, assetLedgerColumns, assetLedgerCell } from '../.local/niko-test/asset-columns-client.mjs';
const root = new URL('../', import.meta.url);
let stage = 'initialization';
try {
  const args = process.argv.slice(2);
  assert.ok(args.length === 0 || (args.length === 1 && args[0] === '--fresh-session-verification'));
  const evidenceFile = args.length ? 'niko-asset-columns-fresh-session-http-20261002.json' : 'niko-asset-columns-client-http-20261002.json';
  const contract = JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json', root), 'utf8'));
  const ajv = new Ajv({ strict: false, allErrors: true }); addFormats(ajv);
  ajv.addSchema({ $id: 'urn:mop:asset-columns', components: contract.components });
  const validators = new Map(), methods = [], roles = ['msp', 'tadmin', 'viewerA', 'viewerB', 'padmin', 'reviewer'];
  let projectedRows = 0;
  function client(role) {
    const session = JSON.parse(readFileSync(new URL(`.local/niko-test/private/sessions/qa-${role}.json`, root), 'utf8'));
    assert.equal(session.environment, 'niko-local-test');
    return new ApiClient(async (url, options) => {
      assert.equal(options.method, 'GET'); methods.push(options.method);
      const path = new URL(url).pathname.replace('/api/v1', '');
      const route = /^\/assets\/[0-9a-f-]+$/.test(path) ? '/assets/{id}' : path;
      assert.ok(['/session', '/assets', '/assets/{id}', '/audit/records'].includes(route));
      const response = await fetch(url, { ...options, headers: { ...options.headers, Cookie: session.cookie }, redirect: 'manual', signal: AbortSignal.timeout(15000) });
      assert.equal(response.status, 200);
      if (!validators.has(route)) {
        const schema = JSON.stringify(contract.paths[route].get.responses['200'].content['application/json'].schema).replaceAll('"#/components/', '"urn:mop:asset-columns#/components/');
        validators.set(route, ajv.compile(JSON.parse(schema)));
      }
      assert.equal(validators.get(route)(await response.clone().json()), true);
      return response;
    }, 'http://127.0.0.1:8080/api/v1');
  }
  const admin = client('padmin');
  stage = 'audit_before'; const tail = (await admin.request('/audit/records?page_size=10')).snapshot_sequence;
  for (const role of roles) {
    stage = `${role}_authorized_asset_projection`;
    const api = client(role), context = await api.request('/session');
    const page = await api.request('/assets?page_size=15');
    assert.ok(page.items.length > 0 && page.items.length <= 15);
    for (const asset of page.items) {
      assert.equal(asset.tenant_id, context.tenant.id);
      const before = JSON.stringify(asset), cells = assetLedgerColumns.map(column => assetLedgerCell(asset, column.key));
      assert.equal(cells.length, 12); assert.ok(cells.every(cell => typeof cell.text === 'string' && cell.text.length > 0));
      for (const key of ['business', 'cpu', 'memory', 'problems', 'owner']) assert.deepEqual(assetLedgerCell(asset, key), { text: '—', detail: '当前接口未提供' });
      assert.equal(asset.availability, 'UNKNOWN'); assert.equal(assetLedgerCell(asset, 'availability').text, '未知');
      assert.equal(asset.sync_state, 'PENDING_ONBOARDING'); assert.equal(assetLedgerCell(asset, 'sync').text, '待接入');
      assert.equal(asset.sampled_at, null); assert.equal(assetLedgerCell(asset, 'sampled').text, '—');
      assert.equal(JSON.stringify(asset), before); projectedRows++;
    }
    const detail = await api.request('/assets/' + page.items[0].id);
    assert.equal(detail.tenant_id, context.tenant.id);
    for (const column of assetLedgerColumns) assert.deepEqual(assetLedgerCell(detail, column.key), assetLedgerCell(page.items[0], column.key));
  }
  stage = 'audit_after'; assert.equal((await admin.request('/audit/records?page_size=10')).snapshot_sequence, tail);
  const evidence = { verified_at: new Date().toISOString(), environment: 'niko-local-test', passed: true, frontend: 'actual assetLedgerColumns and ApiClient bundle', http_requests: methods.length, all_get: true, all_responses_contract_valid: true, identity_contexts: roles.length, projected_rows: projectedRows, column_labels: assetLedgerColumns.map(column => column.label), missing_values_explicit: true, internal_metadata_requests: 0, audit_unchanged: true, credentials_output: false, browser_dom_tested: false, limitations: ['No DOM/390px/scroll/visual acceptance', 'Proxy/business/CPU/memory/problem count/owner not supplied by current public Asset DTO', 'No actual monitoring facts', 'M3-2 not DONE'] };
  writeFileSync(new URL('docs/evidence/' + evidenceFile, root), JSON.stringify(evidence, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ status: 'passed', http_requests: methods.length, projected_rows: projectedRows, browser_dom_tested: false }));
} catch { console.error(JSON.stringify({ status: 'failed', stage, details: 'redacted' })); process.exitCode = 1; }
