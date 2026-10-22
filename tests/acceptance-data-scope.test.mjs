import test from 'node:test'
import assert from 'node:assert/strict'
import { assertAcceptanceWriteScope, REAL_DATA_TENANT } from '../tools/acceptance/data-scope.mjs'

const real = { tenant: { id: REAL_DATA_TENANT } }
test('real tenant business writes fail before transport', () => {
  for (const method of ['POST', 'PATCH', 'PUT', 'DELETE', 'post']) {
    assert.throws(() => assertAcceptanceWriteScope('/assets?source=synthetic', method, real), /REAL_DATA_TENANT_PROTECTED/)
  }
})
test('unknown tenant cannot perform synthetic writes', () => {
  assert.throws(() => assertAcceptanceWriteScope('/tickets', 'POST', null), /REAL_DATA_TENANT_PROTECTED/)
})
test('real tenant read-only validation remains available', () => {
  for (const method of ['GET', 'HEAD', 'OPTIONS']) assert.doesNotThrow(() => assertAcceptanceWriteScope('/assets', method, real))
})
test('authentication and switching do not grant business write access', () => {
  assert.doesNotThrow(() => assertAcceptanceWriteScope('/auth/local/login', 'POST', null))
  assert.doesNotThrow(() => assertAcceptanceWriteScope('/session/tenant', 'POST', real))
  assert.throws(() => assertAcceptanceWriteScope('/auth/local/login/extra', 'POST', real), /REAL_DATA_TENANT_PROTECTED/)
})
test('a separately selected acceptance tenant may use the existing harness', () => {
  assert.doesNotThrow(() => assertAcceptanceWriteScope('/assets', 'POST', { tenant: { id: 'isolated-acceptance-tenant' } }))
})
