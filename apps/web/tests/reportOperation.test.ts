import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../src/api/client'
import type { CustomerReportOperation, RegisteredTicket } from '../src/api/types'
import { createReportOperationReader, validateReportOperation, type ReportOperationContext, type ReportOperationReadState, type ReportOperationTarget } from '../src/utils/reportOperation'

const tenantId = '10000000-0000-0000-0000-000000000000'
const ticketId = '20000000-0000-0000-0000-000000000000'
const operationId = '30000000-0000-0000-0000-000000000000'
const otherTicketId = '40000000-0000-0000-0000-000000000000'
const otherOperationId = '50000000-0000-0000-0000-000000000000'
const target: ReportOperationTarget = { id: ticketId, tenant_id: tenantId, operation_id: operationId }
const operation = (changes: Record<string, unknown> = {}): CustomerReportOperation => ({
  id: operationId, tenant_id: tenantId, kind: 'CUSTOMER_REPORT_REGISTRATION', status: 'PENDING', step: 'AWAITING_TICKET_CONFIGURATION', progress: null,
  result_ref: `/api/v1/tickets/${ticketId}`, error: { code: 'CONFIG_PENDING', message: '服务端原文：工单配置尚待确认', retryable: false, question_ids: ['Q07', 'Q08', 'Q09', 'Q14', 'Q16'] },
  created_at: '2026-10-01T00:00:00Z', updated_at: '2026-10-01T00:00:00Z', ...changes,
} as CustomerReportOperation)
const json = (value: unknown) => new Response(JSON.stringify({ data: value, trace_id: 'unit-trace' }), { headers: { 'Content-Type': 'application/json' } })
const failed = () => new Response(JSON.stringify({ error: { code: 'UPSTREAM_UNAVAILABLE', message: 'unit read unavailable', details: [] }, trace_id: 'read-trace' }), { status: 503, headers: { 'Content-Type': 'application/json' } })
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (cause: unknown) => void
  const promise = new Promise<T>((accept, fail) => { resolve = accept; reject = fail })
  return { promise, resolve, reject }
}
function reader(transport: typeof fetch) {
  let context: ReportOperationContext = { tenantId, key: 'actor-a:viewer:permission-1:context-1' }
  const states: ReportOperationReadState[] = []
  const result = createReportOperationReader({ context: () => context, onChange: state => { states.push(state) } }, new ApiClient(transport))
  return { result, states, changeContext: (next: ReportOperationContext) => { context = next } }
}

describe('registration operation response boundaries', () => {
  it('keeps the actual pending gate, null progress, server message and Q identifiers unchanged', () => {
    const value = operation()
    expect(validateReportOperation(value, target)).toBe(value)
    expect(value).toMatchObject({ status: 'PENDING', progress: null, error: { message: '服务端原文：工单配置尚待确认', question_ids: ['Q07', 'Q08', 'Q09', 'Q14', 'Q16'], retryable: false } })
    const differentQuestions = operation({ error: { ...value.error, question_ids: ['Q28', 'Q10'] } })
    expect(validateReportOperation(differentQuestions, target).error.question_ids).toEqual(['Q28', 'Q10'])
  })
  it.each([
    ['wrong operation', { id: otherOperationId }],
    ['missing tenant', { tenant_id: undefined }],
    ['other tenant', { tenant_id: 'other-tenant' }],
    ['file operation', { kind: 'FILE_UPLOAD' }],
    ['other registration result', { result_ref: `/api/v1/tickets/${otherTicketId}` }],
  ])('rejects %s instead of exposing another object as the current operation', (_boundary, changes) => {
    expect(() => validateReportOperation(operation(changes as Record<string, unknown>), target)).toThrow('当前报障')
  })
  it.each([
    ['success status', { status: 'SUCCEEDED' }],
    ['running status', { status: 'RUNNING' }],
    ['unknown step', { step: 'WORKING' }],
    ['zero progress', { progress: 0 }],
    ['invented complete progress', { progress: 100 }],
    ['missing progress', { progress: undefined }],
    ['retry instruction', { error: { ...operation().error, retryable: true } }],
    ['other error gate', { error: { ...operation().error, code: 'RETRY_NOW' } }],
    ['malformed questions', { error: { ...operation().error, question_ids: ['Q07', 8] } }],
    ['missing gate', { error: null }],
    ['invalid timestamp', { updated_at: 'not-a-date' }],
  ])('rejects %s without filling unconfirmed business values', (_boundary, changes) => {
    expect(() => validateReportOperation(operation(changes as Record<string, unknown>), target)).toThrow('规则门禁')
  })
  it('rejects external, encoded, suffixed and unrelated result references and malformed envelopes', () => {
    for (const result_ref of [`https://other.example/api/v1/tickets/${ticketId}`, `//other.example/api/v1/tickets/${ticketId}`, `/tickets/${ticketId}`, `/api/v1/tickets/${ticketId}?redirect=1`, `/api/v1/tickets/${ticketId}#x`, `/api/v1/tickets/%32${ticketId.slice(1)}`]) expect(() => validateReportOperation(operation({ result_ref }), target)).toThrow()
    for (const value of [null, undefined, [], 'PENDING', {}]) expect(() => validateReportOperation(value, target)).toThrow()
  })
})

// All transports below are isolated unit substitutes. They do not provide real OIDC or HTTP evidence.
describe('read-only registration operation state', () => {
  it('issues one GET for the ticket operation and treats a pending response as a read result', async () => {
    const transport = vi.fn(async (_url: RequestInfo | URL, _options?: RequestInit) => json(operation()))
    const { result, states } = reader(transport)
    const read = result.load(target)
    expect(result.state).toEqual({ status: 'loading', target })
    expect(await read).toBe(true)
    expect(transport).toHaveBeenCalledTimes(1)
    expect(transport.mock.calls[0]?.[0]).toBe(`/api/v1/operations/${operationId}`)
    expect(transport.mock.calls[0]?.[1]).toMatchObject({ method: 'GET', body: undefined })
    expect(states.map(state => state.status)).toEqual(['loading', 'ready'])
    expect(result.state).toMatchObject({ status: 'ready', operation: { status: 'PENDING', progress: null, error: { retryable: false } } })
    expect((transport.mock.calls[0]?.[1]?.headers as Record<string, string>)['Idempotency-Key']).toBeUndefined()
  })
  it('keeps the registered report after a read failure and permits only an explicit GET requery', async () => {
    const transport = vi.fn().mockResolvedValueOnce(json(operation())).mockResolvedValueOnce(failed()).mockResolvedValueOnce(json(operation()))
    const { result } = reader(transport)
    const ticket = Object.freeze({ ...target, registration_status: 'REGISTERED', description: 'registered description', state: null, response_due_at: null } as unknown as RegisteredTicket)
    expect(await result.load(ticket)).toBe(true)
    expect(await result.load(ticket)).toBe(false)
    expect(result.state).toMatchObject({ status: 'error', error: { status: 503, code: 'UPSTREAM_UNAVAILABLE', traceId: 'read-trace' } })
    expect(result.state).not.toHaveProperty('operation')
    expect(ticket).toMatchObject({ description: 'registered description', registration_status: 'REGISTERED', state: null, response_due_at: null })
    expect(await result.load(ticket)).toBe(true)
    expect(transport).toHaveBeenCalledTimes(3)
    for (const [, options] of transport.mock.calls) expect(options).toMatchObject({ method: 'GET', body: undefined })
  })
  it('rejects invalid operation references or a target outside the current tenant before requesting', async () => {
    const transport = vi.fn(), { result } = reader(transport)
    for (const ticket of [{ ...target, operation_id: 'https://other.example/op' }, { ...target, id: 'not-a-uuid' }, { ...target, tenant_id: 'other-tenant' }]) {
      expect(await result.load(ticket)).toBe(false)
      expect(result.state).toMatchObject({ status: 'error', error: { code: 'INVALID_REPORT_OPERATION_REFERENCE' } })
    }
    expect(transport).not.toHaveBeenCalled()
  })
  it('cancels a previous detail read and drops its late response even when fetch ignores abort', async () => {
    const previous = deferred<Response>()
    const signals: AbortSignal[] = []
    const transport = vi.fn(async (_url: RequestInfo | URL, options?: RequestInit) => { signals.push(options?.signal as AbortSignal); return signals.length === 1 ? previous.promise : json(operation({ id: otherOperationId, result_ref: `/api/v1/tickets/${otherTicketId}` })) })
    const { result } = reader(transport), oldRead = result.load(target)
    const next = { ...target, id: otherTicketId, operation_id: otherOperationId }
    expect(await result.load(next)).toBe(true); expect(signals[0]?.aborted).toBe(true)
    previous.resolve(json(operation())); expect(await oldRead).toBe(false)
    expect(result.state).toMatchObject({ status: 'ready', target: next, operation: { id: otherOperationId } })
  })
  it('cancels an old operation when the same ticket receives a different operation reference', async () => {
    const previous = deferred<Response>()
    const signals: AbortSignal[] = []
    const transport = vi.fn(async (_url: RequestInfo | URL, options?: RequestInit) => { signals.push(options?.signal as AbortSignal); return signals.length === 1 ? previous.promise : json(operation({ id: otherOperationId })) })
    const { result } = reader(transport), oldRead = result.load(target)
    expect(await result.load({ ...target, operation_id: otherOperationId })).toBe(true)
    expect(signals[0]?.aborted).toBe(true)
    previous.resolve(json(operation())); expect(await oldRead).toBe(false)
    expect(result.state).toMatchObject({ status: 'ready', operation: { id: otherOperationId } })
  })
  it.each(['close', 'tenant', 'permission', 'context'] as const)('clears and aborts on %s reset, discarding a late failure', async boundary => {
    const pending = deferred<Response>()
    let signal!: AbortSignal
    const transport = vi.fn(async (_url: RequestInfo | URL, options?: RequestInit) => { signal = options?.signal as AbortSignal; return pending.promise })
    const { result, changeContext } = reader(transport), read = result.load(target)
    if (boundary !== 'close') changeContext({ tenantId: boundary === 'tenant' ? 'other-tenant' : tenantId, key: `changed-${boundary}` })
    result.reset(); expect(signal.aborted).toBe(true); expect(result.state).toEqual({ status: 'idle' })
    pending.resolve(failed()); expect(await read).toBe(false); expect(result.state).toEqual({ status: 'idle' })
  })
  it('guards a changed authorization context at response time even before an explicit reset', async () => {
    const pending = deferred<Response>(), { result, changeContext } = reader(vi.fn(async () => pending.promise))
    const read = result.load(target)
    changeContext({ tenantId, key: 'actor-a:viewer:permission-2:context-2' })
    pending.resolve(json(operation())); expect(await read).toBe(false)
    expect(result.state).toEqual({ status: 'idle' })
  })
  it('drops a malformed current read while preserving the actual registration', async () => {
    const ticket = Object.freeze({ ...target, description: 'original registration' })
    const { result } = reader(vi.fn(async () => json(operation({ status: 'SUCCEEDED', progress: 100 }))))
    expect(await result.load(ticket)).toBe(false)
    expect(result.state).toMatchObject({ status: 'error', error: { code: 'INVALID_REPORT_OPERATION' } })
    expect(ticket.description).toBe('original registration'); expect(result.state).not.toHaveProperty('operation')
  })
})
