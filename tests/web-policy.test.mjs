import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { verifyWebPolicy } from '../tools/verify-web-policy.mjs'
const source = await readFile(new URL('../deploy/lan-test/web-static-locations.conf', import.meta.url), 'utf8')
test('accepts the reviewed same-origin policy and explicit inline-style compatibility exception', () => {
  assert.equal(verifyWebPolicy(source).status, 'PASSED')
})
test('rejects script eval, inline script and wildcard source expansion', () => {
  for (const addition of [" 'unsafe-eval'", " 'unsafe-inline'", ' *']) assert.throws(() => verifyWebPolicy(source.replace("script-src 'self';", "script-src 'self'" + addition + ';')), /UNSAFE_OR_MISSING/)
})
test('rejects a missing or duplicated security header', () => {
  assert.throws(() => verifyWebPolicy(source.replace('add_header X-Frame-Options "DENY" always;', '')), /MISSING_SECURITY_HEADER/)
  assert.throws(() => verifyWebPolicy(source + '\nadd_header X-Frame-Options "DENY" always;'), /DUPLICATE_SECURITY_HEADER/)
})
test('preserves blob previews and self-camera use required by the report-photo flow', () => {
  assert.throws(() => verifyWebPolicy(source.replace('data: blob:', 'data:')), /COMPONENT_COMPATIBILITY/)
  assert.throws(() => verifyWebPolicy(source.replace('camera=(self)', 'camera=()')), /MISSING_SECURITY_HEADER/)
})
test('requires child cache locations to inherit the security headers', () => {
  assert.throws(() => verifyWebPolicy(source.replace('add_header_inherit merge;', '')), /SECURITY_HEADERS_NOT_INHERITED/)
})
test('requires static compression without adding authenticated JSON to compressed types', () => {
  assert.throws(() => verifyWebPolicy(source.replace('gzip on;', 'gzip off;')), /STATIC_COMPRESSION/)
  assert.throws(() => verifyWebPolicy(source.replace('gzip_types text/css application/javascript;', 'gzip_types text/css application/javascript application/json;')), /STATIC_COMPRESSION/)
})
