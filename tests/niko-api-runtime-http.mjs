// Read-only smoke after real Maven rebuild, using freshly captured real OIDC sessions.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
const root = new URL('../', import.meta.url); let stage = 'initialization';
try {
  let count = 0;
  async function get(path, cookie, expected = 200) {
    const response = await fetch('http://127.0.0.1:8080' + path, { headers: cookie ? { Cookie: cookie } : {}, redirect: 'manual', signal: AbortSignal.timeout(15000) });
    count++; assert.equal(response.status, expected);
    return expected === 200 ? (await response.json()).data : null;
  }
  stage = 'health'; await get('/api/v1/health/live'); await get('/api/v1/health/ready');
  for (const role of ['msp', 'padmin', 'reviewer', 'tadmin', 'viewerA', 'viewerB']) {
    stage = role;
    const session = JSON.parse(readFileSync(new URL(`.local/niko-test/private/sessions/qa-${role}.json`, root), 'utf8'));
    assert.equal(session.environment, 'niko-local-test');
    const context = await get('/api/v1/session', session.cookie);
    const page = await get('/api/v1/assets?page_size=15', session.cookie);
    assert.ok(page.items.length > 0 && page.items.length <= 15);
    assert.ok(page.items.every(item => item.tenant_id === context.tenant.id));
    // The API-only server deliberately has no SPA resource; it must remain 404 rather than linkage-error 500.
    await get('/assets', session.cookie, 404);
  }
  const evidence = { passed: true, identity_contexts: 6, http_requests: count, all_get: true, current_tenant_asset_reads: true, absent_spa_resource_status: 404, browser_dom_tested: false, credentials_output: false };
  writeFileSync(new URL('.local/niko-test/runtime-artifact-http.json', root), JSON.stringify(evidence) + '\n', { mode: 0o600, flag: 'wx' });
  console.log(JSON.stringify(evidence));
} catch { console.error(JSON.stringify({ passed: false, stage, details: 'redacted' })); process.exitCode = 1; }
