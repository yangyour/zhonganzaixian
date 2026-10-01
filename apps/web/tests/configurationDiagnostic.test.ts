import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../src/api/client'
import type { ConfigurationDiagnostic } from '../src/api/types'
import { createConfigurationDiagnosticReader, validateConfigurationDiagnostic, type ConfigurationDiagnosticContext, type ConfigurationDiagnosticReadState } from '../src/utils/configurationDiagnostic'

const tenantId = '10000000-0000-0000-0000-000000000000', otherTenantId = '20000000-0000-0000-0000-000000000000'
const proposalId = '30000000-0000-0000-0000-000000000000', otherProposalId = '40000000-0000-0000-0000-000000000000'
const configKey = 'monitor.poll_seconds'
function diagnostic(key = configKey, questions: string[] | null = ['Q03', 'Q06']): ConfigurationDiagnostic {
  return {
    key, status: 'CONFIG_PENDING', effective_value: null, context: { tenant_id: tenantId, environment: 'remote_test' }, observed_at: '2026-10-01T00:00:00Z',
    registry: { origin: 'DESIGN_REGISTRY', version: 1, type_label: 'integer', unit_label: null, scope_label: '登记范围原文', source_ref: 'D04 design register', decision_status_label: 'pending', classification_label: null, question_ids: questions },
    recent_proposals: { items: [{ id: proposalId, version: 3, workflow_status: 'APPROVED', origin: 'INTERNAL_REVISION', confirmation_status: 'UNVERIFIED', evidence_input_status: 'REFERENCE_ONLY', evidence_reference_count: 1 }], truncated: false },
    runtime_definition: { availability: 'NOT_IMPLEMENTED', schema_version: null, allowed_layers: null, precedence: null, resolved_scope: null },
    publication: { availability: 'NOT_IMPLEMENTED', record_ref: null, revision_id: null, revision_version: null, effective_at: null, authorization_verified: null, production_approval_verified: null },
    customer_verification: { availability: 'NOT_IMPLEMENTED', required: null, decision_ref: null, authority_verified: null, authenticity_verified: null },
    reasons: [
      { code: 'DESIGN_REGISTRY_NOT_RUNTIME_CONFIG', source_kind: 'DESIGN_REGISTRY', source_id: key, source_version: 1 },
      { code: 'RUNTIME_DEFINITION_SOURCE_UNAVAILABLE', source_kind: 'RUNTIME_DEFINITION', source_id: null, source_version: null },
      { code: 'PUBLICATION_SOURCE_UNAVAILABLE', source_kind: 'RUNTIME_PUBLICATION', source_id: null, source_version: null },
      { code: 'CUSTOMER_VERIFICATION_SOURCE_UNAVAILABLE', source_kind: 'CUSTOMER_VERIFICATION', source_id: null, source_version: null },
      { code: 'INTERNAL_REVISION_NOT_RUNTIME_PUBLICATION', source_kind: 'INTERNAL_REVISION', source_id: proposalId, source_version: 3 },
      ...(questions === null ? [{ code: 'REGISTRY_QUESTIONS_UNAVAILABLE' as const, source_kind: 'DESIGN_REGISTRY' as const, source_id: key, source_version: 1 }] : []),
    ], question_ids: questions,
  }
}
const json = (data: unknown) => new Response(JSON.stringify({ data, trace_id: 'diagnostic-unit' }), { headers: { 'Content-Type': 'application/json' } })
const failure = (status: number) => new Response(JSON.stringify({ error: { code: `UNIT_${status}`, message: 'unit diagnostic unavailable', details: [] }, trace_id: 'diagnostic-failure' }), { status })
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(accept => { resolve = accept }); return { promise, resolve } }
function reader(transport: typeof fetch) {
  let context: ConfigurationDiagnosticContext = { tenantId, key: 'padmin:actor-a:permission-1:context-1', permitted: true }
  const states: ConfigurationDiagnosticReadState[] = []
  const result = createConfigurationDiagnosticReader({ context: () => context, onChange: value => { states.push(value) } }, new ApiClient(transport))
  return { result, states, changeContext: (next: Partial<ConfigurationDiagnosticContext>) => { context = { ...context, ...next } } }
}

// Isolated transports verify read boundaries; root records actual PG/OIDC/HTTP and browser evidence separately.
describe('current-tenant metadata-only configuration diagnosis', () => {
  it('reads exactly one key using GET and keeps APPROVED an unpublished proposal with unknown runtime/customer sources', async () => {
    const transport = vi.fn(async (_url: RequestInfo | URL, _options?: RequestInit) => json(diagnostic()))
    const { result } = reader(transport)
    expect(await result.load(configKey)).toBe(true)
    expect(transport).toHaveBeenCalledTimes(1)
    expect(transport.mock.calls[0]?.[0]).toBe('/api/v1/settings/effective?key=monitor.poll_seconds')
    expect(transport.mock.calls[0]?.[1]).toMatchObject({ method: 'GET', body: undefined })
    expect(transport.mock.calls[0]?.[1]?.headers).not.toHaveProperty('Idempotency-Key')
    expect(result.state).toMatchObject({ status: 'ready', key: configKey, diagnostic: {
      status: 'CONFIG_PENDING', effective_value: null,
      recent_proposals: { items: [{ workflow_status: 'APPROVED', confirmation_status: 'UNVERIFIED', evidence_input_status: 'REFERENCE_ONLY' }] },
      publication: { availability: 'NOT_IMPLEMENTED', revision_id: null, effective_at: null, authorization_verified: null },
      customer_verification: { required: null, authority_verified: null, authenticity_verified: null },
    } })
  })

  it('preserves null Q metadata and unknown evidence counts without converting them into an empty source or approval', async () => {
    const value = diagnostic(configKey, null)
    value.recent_proposals.items[0]!.evidence_reference_count = null
    value.reasons.push({ code: 'PROPOSAL_EVIDENCE_REFERENCES_UNAVAILABLE', source_kind: 'INTERNAL_REVISION', source_id: proposalId, source_version: 3 })
    const { result } = reader(vi.fn(async () => json(value)))
    expect(await result.load(configKey)).toBe(true)
    expect(result.state).toMatchObject({ diagnostic: { question_ids: null, registry: { question_ids: null, unit_label: null }, recent_proposals: { items: [{ evidence_reference_count: null }] }, customer_verification: { required: null } } })
    const empty = diagnostic(configKey, [])
    empty.recent_proposals.items[0]!.evidence_reference_count = 0
    expect(validateConfigurationDiagnostic(empty, configKey, tenantId)).toMatchObject({ question_ids: [], recent_proposals: { items: [{ evidence_reference_count: 0 }] } })
  })

  it('accepts five recent metadata rows and an explicit truncation flag without selecting any of their versions', () => {
    const value = diagnostic()
    value.recent_proposals.items = Array.from({ length: 5 }, (_, index) => ({ ...value.recent_proposals.items[0]!, id: `30000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`, version: index + 1 }))
    value.recent_proposals.truncated = true
    value.reasons = [...value.reasons.filter(reason => reason.source_kind !== 'INTERNAL_REVISION'), ...value.recent_proposals.items.map(item => ({ code: 'INTERNAL_REVISION_NOT_RUNTIME_PUBLICATION' as const, source_kind: 'INTERNAL_REVISION' as const, source_id: item.id, source_version: item.version }))]
    expect(validateConfigurationDiagnostic(value, configKey, tenantId)).toMatchObject({ effective_value: null, recent_proposals: { truncated: true }, publication: { revision_version: null } })
  })

  it.each([
    ['other configuration', (value: ConfigurationDiagnostic) => ({ ...value, key: 'other.key' })],
    ['other tenant', (value: ConfigurationDiagnostic) => ({ ...value, context: { ...value.context, tenant_id: otherTenantId } })],
    ['fixture environment', (value: ConfigurationDiagnostic) => ({ ...value, context: { ...value.context, environment: 'local_fixture' } })],
    ['date without observation time', (value: ConfigurationDiagnostic) => ({ ...value, observed_at: '2026-10-01' })],
    ['effective zero', (value: ConfigurationDiagnostic) => ({ ...value, effective_value: 0 })],
    ['effective false', (value: ConfigurationDiagnostic) => ({ ...value, effective_value: false })],
    ['invented publication', (value: ConfigurationDiagnostic) => ({ ...value, publication: { ...value.publication, revision_id: proposalId, authorization_verified: true } })],
    ['customer approval', (value: ConfigurationDiagnostic) => ({ ...value, customer_verification: { ...value.customer_verification, authenticity_verified: true } })],
    ['invented precedence', (value: ConfigurationDiagnostic) => ({ ...value, runtime_definition: { ...value.runtime_definition, precedence: [] } })],
    ['mismatched Q source', (value: ConfigurationDiagnostic) => ({ ...value, question_ids: ['Q99'] })],
    ['unrelated proposal reason', (value: ConfigurationDiagnostic) => ({ ...value, reasons: value.reasons.map(reason => reason.source_kind === 'INTERNAL_REVISION' ? { ...reason, source_id: otherProposalId } : reason) })],
    ['different proposal version', (value: ConfigurationDiagnostic) => ({ ...value, reasons: value.reasons.map(reason => reason.source_kind === 'INTERNAL_REVISION' ? { ...reason, source_version: 99 } : reason) })],
    ['incomplete pending reasons', (value: ConfigurationDiagnostic) => ({ ...value, reasons: value.reasons.filter(reason => reason.source_kind !== 'RUNTIME_PUBLICATION') })],
    ['registered value', (value: ConfigurationDiagnostic) => ({ ...value, registry: { ...value.registry, registered_value: 'private unit value' } })],
    ['proposal content', (value: ConfigurationDiagnostic) => ({ ...value, recent_proposals: { ...value.recent_proposals, items: value.recent_proposals.items.map(item => ({ ...item, proposed_value: 'private unit proposal', evidence_refs: ['private unit body'] })) } })],
    ['array workflow status', (value: ConfigurationDiagnostic) => ({ ...value, recent_proposals: { ...value.recent_proposals, items: value.recent_proposals.items.map(item => ({ ...item, workflow_status: ['APPROVED'] })) } })],
    ['object workflow status', (value: ConfigurationDiagnostic) => ({ ...value, recent_proposals: { ...value.recent_proposals, items: value.recent_proposals.items.map(item => ({ ...item, workflow_status: { value: 'APPROVED' } })) } })],
    ['selected effective version', (value: ConfigurationDiagnostic) => ({ ...value, selected_revision_id: proposalId })],
    ['false truncation count', (value: ConfigurationDiagnostic) => ({ ...value, recent_proposals: { ...value.recent_proposals, truncated: true } })],
  ])('rejects %s before exposing the refreshed snapshot and allows only an explicit GET retry', async (_boundary, mutate) => {
    let malformed = false
    const transport = vi.fn(async (_url: RequestInfo | URL, _options?: RequestInit) => json(malformed ? mutate(diagnostic()) : diagnostic()))
    const { result } = reader(transport)
    expect(await result.load(configKey)).toBe(true)
    malformed = true
    const refresh = result.load(configKey)
    expect(result.state).toEqual({ status: 'loading', key: configKey })
    expect(await refresh).toBe(false)
    expect(result.state).toMatchObject({ status: 'error', error: { code: 'INVALID_CONFIGURATION_DIAGNOSTIC' } })
    expect(result.state).not.toHaveProperty('diagnostic')
    expect(JSON.stringify(result.state)).not.toContain('private unit')
    malformed = false; expect(await result.load(configKey)).toBe(true)
    expect(transport).toHaveBeenCalledTimes(3)
    for (const [, options] of transport.mock.calls) expect(options).toMatchObject({ method: 'GET', body: undefined })
  })

  it.each([401, 403, 404, 503])('keeps an actual %s failure visible without falling back to old metadata or posting a write', async status => {
    const transport = vi.fn().mockResolvedValueOnce(json(diagnostic())).mockResolvedValueOnce(failure(status)).mockResolvedValueOnce(json(diagnostic()))
    const { result, states } = reader(transport)
    expect(await result.load(configKey)).toBe(true)
    expect(await result.load(configKey)).toBe(false)
    expect(result.state).toMatchObject({ status: 'error', key: configKey, error: { status, traceId: 'diagnostic-failure' } })
    expect(result.state).not.toHaveProperty('diagnostic')
    expect(await result.load(configKey)).toBe(true)
    expect(states.map(state => state.status)).toEqual(['loading', 'ready', 'loading', 'error', 'loading', 'ready'])
    for (const [, options] of transport.mock.calls) expect(options).toMatchObject({ method: 'GET', body: undefined })
  })

  it('aborts A and drops its late response when another key B finishes first', async () => {
    const old = deferred<Response>(), signals: AbortSignal[] = []
    const transport = vi.fn(async (url: RequestInfo | URL, options?: RequestInit) => { signals.push(options!.signal as AbortSignal); return String(url).endsWith('key=other.key') ? json(diagnostic('other.key')) : old.promise })
    const { result } = reader(transport), previous = result.load(configKey)
    expect(await result.load('other.key')).toBe(true); expect(signals[0]?.aborted).toBe(true)
    old.resolve(json(diagnostic())); expect(await previous).toBe(false)
    expect(result.state).toMatchObject({ status: 'ready', key: 'other.key', diagnostic: { key: 'other.key' } })
  })

  it.each(['close', 'tenant', 'actor', 'permission', 'role', 'logout'] as const)('discards a response after %s invalidates the diagnostic context', async boundary => {
    const pending = deferred<Response>(), transport = vi.fn(async (_url: RequestInfo | URL, _options?: RequestInit) => pending.promise)
    const { result, changeContext } = reader(transport), read = result.load(configKey)
    if (boundary === 'close') result.reset()
    else changeContext({ key: `changed-${boundary}`, tenantId: boundary === 'tenant' ? otherTenantId : tenantId, permitted: !['role', 'logout'].includes(boundary) })
    pending.resolve(json(diagnostic())); expect(await read).toBe(false)
    expect(result.state).toEqual({ status: 'idle' })
  })

  it('does not query as an unauthorized role or with an invalid key/tenant reference', async () => {
    const transport = vi.fn(), { result, changeContext } = reader(transport)
    changeContext({ permitted: false }); expect(await result.load(configKey)).toBe(false)
    expect(result.state).toMatchObject({ status: 'error', error: { status: 403 } })
    changeContext({ permitted: true })
    for (const key of ['', 'other/key', 'valid.key&tenant_id=other', 'a'.repeat(129)]) expect(await result.load(key)).toBe(false)
    changeContext({ tenantId: 'invalid-tenant' }); expect(await result.load(configKey)).toBe(false)
    expect(transport).not.toHaveBeenCalled()
  })
})
