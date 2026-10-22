// Explicit current-feature acceptance against the designated project test environment.
// Credentials, cookies, request bodies and signed download URLs are never evidence.
import { readFileSync, writeFileSync, mkdirSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomUUID, createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import assert from 'node:assert/strict'
import Ajv from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import { assertAcceptanceWriteScope } from './data-scope.mjs'

class Blocked extends Error {}
export function createHarness({ output, runId = randomUUID() } = {}) {
  const root = resolve(import.meta.dirname, '../..')
  const cfg = JSON.parse(readFileSync(resolve(root, 'deploy/private/lan-test-runtime.json'), 'utf8').replace(/^\uFEFF/, ''))
  assert.equal(cfg.environment, 'lan-test-20261008', 'Only the designated LAN test environment is allowed')
  const base = new URL(cfg.public_origin)
  assert.equal(base.origin, 'http://192.168.1.215:10010', 'Unexpected test origin')
  const contract = JSON.parse(readFileSync(resolve(root, 'contracts/local-api.openapi.json'), 'utf8'))
  const ajv = new Ajv({ strict: false, allErrors: true }); addFormats(ajv)
  ajv.addSchema({ $id: 'urn:mop:current-acceptance', components: contract.components })
  const validators = new Map()
  const qualifyRefs = value => Array.isArray(value) ? value.map(qualifyRefs) : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([key, item]) => [key, key === '$ref' && typeof item === 'string' && item.startsWith('#/components/') ? 'urn:mop:current-acceptance' + item : qualifyRefs(item)])) : value
  const routes = Object.keys(contract.paths).map(path => ({ path, re: new RegExp('^' + path.split('/').map(segment => segment.startsWith('{') ? '[^/]+' : segment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('/') + '$') }))
  const runnerFiles = Object.fromEntries(readdirSync(import.meta.dirname).filter(name => name.endsWith('.mjs')).map(name => [name, createHash('sha256').update(readFileSync(resolve(import.meta.dirname, name))).digest('hex')]))
  const evidence = { run_id: runId, started_at: new Date().toISOString(), source_workspace_commit: execFileSync('git', ['rev-parse','HEAD'], { cwd: root, encoding: 'utf8' }).trim(), runner_files_sha256: runnerFiles, node_version: process.version, environment: cfg.environment, api_version: contract.info.version, scope: 'Implemented feature checks; not full contract or production approval', cases: [], requests: [], artifacts: [], cleanup: [], credentials_recorded: false }
  const destination = resolve(output || resolve(root, `docs/evidence/feature-acceptance-20261014/http-${runId}.json`))
  mkdirSync(resolve(destination, '..'), { recursive: true })
  let currentCase = 'setup'
  const save = () => writeFileSync(destination, JSON.stringify({ ...evidence, updated_at: new Date().toISOString() }, null, 2) + '\n')
  function safeMetadata(value) {
    if (Array.isArray(value)) return value.map(safeMetadata)
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([key]) => !/password|token|cookie|secret|grant|authorization|download_url/i.test(key)).map(([key, item]) => [key, safeMetadata(item)]))
    if (typeof value === 'string' && /^https?:/.test(value)) return '[URL OMITTED]'
    return value
  }
  class Client {
    constructor(role = 'anonymous') { this.role = role; this.cookies = new Map(); this.csrf = null; this.session = null }
    async call(path, method = 'GET', body, expected = 200, options = {}) {
      assert.ok(path.startsWith('/') && !path.startsWith('//') && !path.includes('://'), 'Relative API path required')
      assertAcceptanceWriteScope(path, method, this.session)
      const url = new URL('/api/v1' + path, base)
      const changesSession = method.toUpperCase() === 'POST' && ['/auth/local/login', '/auth/logout', '/session/tenant'].includes(url.pathname.slice('/api/v1'.length))
      // A switch/login with an uncertain response must not leave an old tenant grant cached.
      if (changesSession) this.session = null
      const headers = { Accept: 'application/json', Cookie: [...this.cookies].map(([k, v]) => k + '=' + v).join('; ') }
      if (body !== undefined && !options.formData) headers['Content-Type'] = 'application/json'
      if (!['GET', 'HEAD'].includes(method)) {
        headers.Origin = base.origin
        if (!options.noKey) headers['Idempotency-Key'] = options.key || randomUUID()
        if (!options.noCsrf && this.csrf) headers['X-CSRF-Token'] = this.csrf
      }
      const apiPath = url.pathname.slice('/api/v1'.length)
      const route = contract.paths[apiPath] ? apiPath : routes.find(candidate => candidate.re.test(apiPath))?.path
      assert.ok(route, 'Request must use the actual API contract')
      let response
      try { response = await fetch(url, { method, headers, body: options.formData || (body === undefined ? undefined : JSON.stringify(body)), redirect: 'manual', signal: AbortSignal.timeout(25000) }) }
      catch { evidence.requests.push({ case: currentCase, role: this.role, method, path: route, transport: 'FAILED' }); save(); throw new Error('TRANSPORT_FAILED') }
      for (const raw of response.headers.getSetCookie()) { const pair = raw.split(';')[0], index = pair.indexOf('='); this.cookies.set(pair.slice(0, index), pair.slice(index + 1)) }
      let payload
      try { payload = await response.json() } catch { throw new Error('NON_JSON_RESPONSE') }
      if (changesSession && response.ok && payload.data?.tenant?.id) this.session = payload.data
      const record = { case: currentCase, role: this.role, method, path: route, status: response.status, trace_id: payload.trace_id, code: payload.error?.code, expected_status: expected }
      if (!['GET', 'HEAD'].includes(method) && response.ok) {
        for (const key of ['id', 'operation_id']) if (/^[0-9a-f-]{36}$/.test(payload.data?.[key] || '')) record['returned_' + key] = payload.data[key]
      }
      evidence.requests.push(record)
      const schema = contract.paths[route]?.[method.toLowerCase()]?.responses[response.status]?.content?.['application/json']?.schema
      if (schema) {
        const key = JSON.stringify(schema)
        if (!validators.has(key)) validators.set(key, ajv.compile(qualifyRefs(schema)))
        const validate = validators.get(key)
        record.contract_valid = validate(payload)
        if (!record.contract_valid) record.schema_errors = validate.errors?.map(({ instancePath, keyword }) => ({ instancePath, keyword }))
      } else record.contract_valid = false
      save()
      assert.ok((Array.isArray(expected) ? expected : [expected]).includes(response.status), `HTTP_STATUS:${method}:${route}`)
      assert.equal(record.contract_valid, true, `RESPONSE_SCHEMA:${method}:${route}`)
      assert.equal(payload.trace_id, response.headers.get('x-trace-id'), 'TRACE_HEADER_MATCH')
      return payload.data ?? payload.error
    }
    async login(user) {
      this.csrf = (await this.call('/auth/csrf')).csrf_token
      this.session = await this.call('/auth/local/login', 'POST', { username: user.username, password: user.password })
      this.csrf = this.session.csrf_token
      assert.equal(this.session.mode, 'remote_test'); assert.equal(this.session.identity_source, 'project_local')
      return this.session
    }
    async upload(path, formData, expected = 202, options = {}) { return this.call(path, 'POST', undefined, expected, { ...options, formData }) }
    async download(contentUrl, expected = 200) {
      const url = new URL(contentUrl, base)
      assert.equal(url.origin, base.origin, 'Downloads must remain on the designated test origin')
      assert.ok(url.pathname.startsWith('/api/v1/'), 'Only project API downloads are accepted')
      const response = await fetch(url, { headers: { Cookie: [...this.cookies].map(([k, v]) => k + '=' + v).join('; ') }, redirect: 'manual', signal: AbortSignal.timeout(25000) })
      const bytes = Buffer.from(await response.arrayBuffer())
      evidence.requests.push({ case: currentCase, role: this.role, method: 'GET', path: '[AUTHORIZED FILE CONTENT]', status: response.status, expected_status: expected, bytes: bytes.length })
      save(); assert.equal(response.status, expected, 'DOWNLOAD_STATUS')
      return { bytes, headers: response.headers, status: response.status }
    }
  }
  const h = {
    prefix: 'SYNTHETIC_ACCEPTANCE_' + runId.slice(0, 8), runId, clients: {}, evidence, contract, root, assetId: null,
    Client, check: (value, label) => assert.ok(value, label), block: reason => { throw new Blocked(reason) },
    artifact: (name, metadata) => { evidence.artifacts.push({ case: currentCase, name, ...safeMetadata(metadata) }); save() },
    async case(id, title, fn) {
      currentCase = id
      const result = { id, title, started_at: new Date().toISOString(), request_start: evidence.requests.length }
      evidence.cases.push(result); save()
      try { await fn(); result.status = 'PASS' }
      catch (error) {
        result.status = error instanceof Blocked ? 'BLOCKED' : 'FAIL'
        result.error_type = error?.name || 'Error'
        // Labels only: node assertion messages can contain actual sensitive values.
        result.reason = error instanceof Blocked ? error.message : String(error?.message || '').split('\n')[0].slice(0, 180)
        if (!/^[A-Za-z0-9_:/ .\-{}]+$/.test(result.reason)) result.reason = 'Assertion failed; inspect the recorded step and response code'
      }
      result.request_count = evidence.requests.length - result.request_start
      result.finished_at = new Date().toISOString(); save()
      console.log(JSON.stringify({ id, title, status: result.status, requests: result.request_count, reason: result.reason }))
    },
    async waitFor(fn, { timeoutMs = 30000, intervalMs = 500 } = {}) { const until = Date.now() + timeoutMs; do { const value = await fn(); if (value) return value; await new Promise(resolve => setTimeout(resolve, intervalMs)) } while (Date.now() < until); throw new Error('POLL_TIMEOUT') },
    async initialize() {
      await h.case('ENV', '环境、当前API与既有测试身份', async () => {
        const anonymous = new Client(); h.clients.anonymous = anonymous
        const ready = await anonymous.call('/health/ready'); assert.equal(ready.ready, true); assert.equal(ready.mode, 'remote_test')
        evidence.readiness = { ready: ready.ready, durable: ready.durable, production_ready: ready.production_ready }
        for (const user of cfg.test_users) { const client = new Client(user.role); await client.login(user); h.clients[user.role] = client; assert.equal(client.session.user.id, user.platform_user_id) }
        assert.ok(h.clients.padmin && h.clients.msp && h.clients.tadmin && h.clients.viewerA && h.clients.viewerB)
      })
      if (evidence.cases.at(-1).status !== 'PASS') throw new Error('ENVIRONMENT_NOT_READY')
    },
    finish() { evidence.finished_at = new Date().toISOString(); evidence.summary = Object.fromEntries(['PASS','FAIL','BLOCKED'].map(status => [status, evidence.cases.filter(item => item.status === status).length])); save(); return evidence.summary },
  }
  return h
}
