import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = (path) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const contract = read('../contracts/local-api.openapi.json');
const baseline = read('../contracts/config-baseline.json');

test('设计基线复制保持原始待确认值，没有补默认值', () => {
  assert.deepEqual(baseline, read('../doc/development/contracts/config-baseline.json'));
  assert.equal(baseline.status, 'design_registry_not_runtime_config');
  for (const setting of baseline.settings) {
    if (setting.decision_status === 'pending') {
      assert.equal(setting.value, null, setting.key);
      assert.ok(setting.question_ids.length, setting.key);
    }
  }
});

test('所有业务写接口明确要求幂等及CSRF，remote_test不存在fixture身份入口', () => {
  assert.equal(contract.paths['/fixture/users'], undefined);
  assert.equal(contract.paths['/fixture/session'], undefined);
  for (const [path, methods] of Object.entries(contract.paths)) {
    for (const [method, operation] of Object.entries(methods)) {
      if (!['post', 'put', 'patch', 'delete'].includes(method)) continue;
      for (const name of ['Idempotency-Key', 'X-CSRF-Token']) {
        assert.ok(operation.parameters.some((p) => p.in === 'header' && p.name === name && p.required), `${method} ${path} ${name}`);
      }
    }
  }
});

test('没有可被误读为真实异步受理或已纳管的契约', () => {
  for (const path of ['/assets/{id}/onboarding', '/configurations/{key}/publish']) {
    const responses = contract.paths[path].post.responses;
    assert.ok(responses['422']);
    assert.equal(responses['200'], undefined);
    assert.equal(responses['202'], undefined);
  }
  const create = contract.paths['/assets'].post.requestBody.content['application/json'].schema;
  assert.equal(create.additionalProperties, false);
  for (const field of ['tenant_id', 'actor_id', 'source', 'secret_ref']) assert.equal(create.properties[field], undefined);
});
