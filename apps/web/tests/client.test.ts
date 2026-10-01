import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError, StaleRequestError } from '../src/api/client'

function response(data: unknown, status = 200) { return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } }) }
describe('API context and error boundaries', () => {
  it('aborts active requests and rejects a late result even when the transport ignores abort', async () => {
    let finish!: (response: Response) => void
    let signal: AbortSignal | undefined
    const transport = vi.fn(async (_url, options) => { signal = options?.signal as AbortSignal; return await new Promise<Response>(resolve => { finish = resolve }) })
    const client = new ApiClient(transport)
    const pending = client.request('/assets')
    const rejected = expect(pending).rejects.toBeInstanceOf(StaleRequestError)
    client.invalidateContext()
    expect(signal?.aborted).toBe(true)
    finish(response({ data: { items: [{ tenant_id: 'old' }] }, trace_id: 'old-trace' }))
    await rejected
  })
  it('an old tenant 401 cannot log out the current tenant', async () => {
    let finish!: (response: Response) => void
    const client = new ApiClient(vi.fn(() => new Promise<Response>(resolve => { finish = resolve })))
    client.onUnauthorized = vi.fn()
    const pending = client.request('/assets')
    const rejected = expect(pending).rejects.toBeInstanceOf(StaleRequestError)
    client.invalidateContext()
    finish(response({ error: { code: 'SESSION_EXPIRED', message: 'expired' }, trace_id: 'old-trace' }, 401))
    await rejected
    expect(client.onUnauthorized).not.toHaveBeenCalled()
  })
  it('a current 401 invokes login handling and preserves error trace_id', async () => {
    const client = new ApiClient(vi.fn(async () => response({ error: { code: 'SESSION_EXPIRED', message: '会话失效' }, trace_id: 'tr-auth' }, 401)))
    client.onUnauthorized = vi.fn(() => client.invalidateContext())
    await expect(client.request('/session')).rejects.toMatchObject({ status: 401, code: 'SESSION_EXPIRED', traceId: 'tr-auth' })
    expect(client.onUnauthorized).toHaveBeenCalledTimes(1)
  })
  it('writes use the current CSRF token and independent idempotency keys', async () => {
    const transport = vi.fn(async (_url: RequestInfo | URL, _options?: RequestInit) => response({ data: { id: 'new' }, trace_id: 'tr-write' }))
    const client = new ApiClient(transport); client.setCsrfToken('csrf-a')
    await client.request('/assets', { method: 'POST', body: { name: 'A' } })
    client.setCsrfToken('csrf-b')
    await client.request('/assets', { method: 'POST', body: { name: 'B' } })
    const first = transport.mock.calls[0]![1] as RequestInit
    const second = transport.mock.calls[1]![1] as RequestInit
    expect(first.credentials).toBe('same-origin')
    expect(first.headers).toMatchObject({ 'X-CSRF-Token': 'csrf-a' })
    expect(second.headers).toMatchObject({ 'X-CSRF-Token': 'csrf-b' })
    expect((first.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy()
    expect((first.headers as Record<string, string>)['Idempotency-Key']).not.toEqual((second.headers as Record<string, string>)['Idempotency-Key'])
  })
  it('keeps CONFIG_PENDING details and null configuration values intact', async () => {
    const client = new ApiClient(vi.fn(async () => response({ error: { code: 'CONFIG_PENDING', message: '待客户确认', details: [{ question_ids: ['Q02', 'Q03'] }] }, trace_id: 'tr-config' }, 422)))
    await expect(client.request('/configurations/a/publish', { method: 'POST', body: { expected_version: 1 } })).rejects.toMatchObject({ code: 'CONFIG_PENDING', traceId: 'tr-config', details: [{ question_ids: ['Q02', 'Q03'] }] })
  })
  it('cannot accept malformed successful responses as completed writes', async () => {
    const client = new ApiClient(vi.fn(async () => response({ unexpected: true })))
    await expect(client.request('/assets', { method: 'POST', body: {} })).rejects.toBeInstanceOf(ApiError)
  })
  it('polls the documented status field and terminates at FAILED without assuming success', async () => {
    const transport = vi.fn().mockResolvedValueOnce(response({ data: { status: 'RUNNING' }, trace_id: 'tr-op1' })).mockResolvedValueOnce(response({ data: { status: 'FAILED', error: { code: 'ADAPTER_UNAVAILABLE' } }, trace_id: 'tr-op2' }))
    const client = new ApiClient(transport)
    const operation = await client.pollOperation('op-1', { intervalMs: 1 })
    expect(operation.status).toBe('FAILED'); expect(transport).toHaveBeenCalledTimes(2)
  })
})

describe('multipart and binary API safety', () => {
  it('sends the original FormData without JSON encoding or overriding the multipart boundary', async () => {
    const transport = vi.fn(async (_url: RequestInfo | URL, _options?: RequestInit) => response({ data: { operation_id: 'op-upload', status: 'PENDING' }, trace_id: 'tr-upload' }, 202))
    const client = new ApiClient(transport); client.setCsrfToken('csrf-upload')
    const file = new File(['original bytes'], 'original.jpg', { type: 'image/jpeg' }), form = new FormData()
    form.append('file', file); form.append('purpose', 'customer_report_photo')
    await client.request('/files', { method: 'POST', body: form, idempotencyKey: 'stable-upload-key' })
    const options = transport.mock.calls[0]![1]!
    expect(options.body).toBe(form); expect((options.body as FormData).get('file')).toBe(file)
    expect(options.headers).toMatchObject({ 'X-CSRF-Token': 'csrf-upload', 'Idempotency-Key': 'stable-upload-key', Accept: 'application/json' })
    expect((options.headers as Record<string, string>)['Content-Type']).toBeUndefined()
    expect(options).toMatchObject({ credentials: 'same-origin', redirect: 'error' })
  })
  it('rejects external URLs, duplicated API prefixes and encoded path escape before calling fetch', async () => {
    const transport = vi.fn(), client = new ApiClient(transport)
    for (const path of ['https://storage.example/picture', '//storage.example/picture', '/api/v1/files/id/content', '/api%2fv1/files/id/content', '/files/../content', '/files/%2e%2e/content', '/files/%252e%252e/content', '/files/%2f%2fhost/content', '/files/id\\content', '/files/id/content#fragment', '/files/id/content\n']) {
      await expect(client.requestBlob(path)).rejects.toMatchObject({ code: 'INVALID_API_PATH' })
      await expect(client.request(path)).rejects.toMatchObject({ code: 'INVALID_API_PATH' })
    }
    expect(transport).not.toHaveBeenCalled()
  })
  it('preserves binary error envelopes and handles only the current 401 as expired', async () => {
    const client = new ApiClient(vi.fn(async () => response({ error: { code: 'SESSION_EXPIRED', message: 'expired', details: [{ question_ids: ['Q28'] }] }, trace_id: 'blob-trace' }, 401)))
    client.onUnauthorized = vi.fn(() => client.invalidateContext())
    await expect(client.requestBlob('/files/id/content?grant=uuid')).rejects.toMatchObject({ status: 401, code: 'SESSION_EXPIRED', traceId: 'blob-trace', details: [{ question_ids: ['Q28'] }] })
    expect(client.onUnauthorized).toHaveBeenCalledTimes(1)
  })
  it('preserves HTTP status when a binary failure has a null or malformed error envelope', async () => {
    const transport = vi.fn().mockResolvedValueOnce(response(null, 404)).mockResolvedValueOnce(new Response('broken JSON', { status: 503, headers: { 'X-Trace-Id': 'header-trace' } }))
    const client = new ApiClient(transport)
    await expect(client.requestBlob('/files/id/content?grant=uuid')).rejects.toMatchObject({ status: 404, code: 'HTTP_404' })
    await expect(client.requestBlob('/files/id/content?grant=uuid')).rejects.toMatchObject({ status: 503, code: 'HTTP_503', traceId: 'header-trace' })
  })
  it('rejects late text and blob body consumption after invalidation', async () => {
    let finishText!: (value: string) => void, finishBlob!: (value: Blob) => void
    const textStarted = vi.fn(), blobStarted = vi.fn()
    const textClient = new ApiClient(vi.fn(async () => ({ ok: true, status: 200, headers: new Headers(), text: () => { textStarted(); return new Promise<string>(resolve => { finishText = resolve }) } } as Response)))
    const textPending = textClient.request('/files/policy'), textRejected = expect(textPending).rejects.toBeInstanceOf(StaleRequestError)
    await vi.waitFor(() => expect(textStarted).toHaveBeenCalled())
    textClient.invalidateContext(); finishText(JSON.stringify({ data: { allowed_media_types: ['image/jpeg'] }, trace_id: 'old-text' })); await textRejected
    const blobClient = new ApiClient(vi.fn(async () => ({ ok: true, status: 200, headers: new Headers(), blob: () => { blobStarted(); return new Promise<Blob>(resolve => { finishBlob = resolve }) } } as Response)))
    const blobPending = blobClient.requestBlob('/files/id/content?grant=uuid'), blobRejected = expect(blobPending).rejects.toBeInstanceOf(StaleRequestError)
    await vi.waitFor(() => expect(blobStarted).toHaveBeenCalled())
    blobClient.invalidateContext(); finishBlob(new Blob(['old photo'], { type: 'image/jpeg' })); await blobRejected
  })
})
