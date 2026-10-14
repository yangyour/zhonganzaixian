import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
export function verifyWebPolicy(source) {
  const headers = {}
  for (const [, name, value] of source.matchAll(/add_header ([A-Za-z-]+) "([^"]+)" always;/g)) {
    if (name === 'Cache-Control') continue
    if (headers[name]) throw Error('DUPLICATE_SECURITY_HEADER')
    headers[name] = value
  }
  const csp = headers['Content-Security-Policy']
  if (!csp) throw Error('MISSING_CSP')
  const directives = new Map()
  for (const text of csp.split(';').map(value => value.trim()).filter(Boolean)) {
    const [key, ...values] = text.split(/\s+/)
    if (directives.has(key)) throw Error('DUPLICATE_CSP_DIRECTIVE')
    directives.set(key, values.join(' '))
  }
  for (const [key, expected] of Object.entries({ 'default-src': "'self'", 'script-src': "'self'", 'script-src-attr': "'none'", 'connect-src': "'self'", 'object-src': "'none'", 'base-uri': "'none'", 'frame-ancestors': "'none'", 'form-action': "'self'" })) {
    if (directives.get(key) !== expected) throw Error('UNSAFE_OR_MISSING_CSP_DIRECTIVE')
  }
  if (directives.get('style-src') !== "'self' 'unsafe-inline'" || directives.get('img-src') !== "'self' data: blob:") throw Error('COMPONENT_COMPATIBILITY_POLICY_CHANGED')
  for (const [name, value] of Object.entries({ 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY', 'Permissions-Policy': 'camera=(self), microphone=(), geolocation=()' })) {
    if (headers[name] !== value) throw Error('MISSING_SECURITY_HEADER')
  }
  if (!source.includes('add_header_inherit merge;')) throw Error('SECURITY_HEADERS_NOT_INHERITED')
  for (const name of Object.keys(headers)) {
    if (!new RegExp('^proxy_hide_header ' + name + ';$', 'm').test(source)) throw Error('UPSTREAM_SECURITY_HEADER_DUPLICATION')
  }
  if (!/^gzip on;$/m.test(source) || !/^gzip_vary on;$/m.test(source) || !/^gzip_types text\/css application\/javascript;$/m.test(source)) throw Error('STATIC_COMPRESSION_POLICY_CHANGED')
  return { status: 'PASSED', headers: Object.keys(headers), scope: 'candidate-policy-only', inline_styles: 'required_by_element_plus', hsts: 'requires_verified_https_deployment' }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const file = fileURLToPath(new URL('../deploy/lan-test/web-static-locations.conf', import.meta.url))
    console.log(JSON.stringify(verifyWebPolicy(await readFile(file, 'utf8'))))
  } catch (error) { console.error(JSON.stringify({ status: 'FAILED', reason: /^[A-Z_]+$/.test(error.message) ? error.message : 'POLICY_READ_FAILED' })); process.exitCode = 1 }
}
