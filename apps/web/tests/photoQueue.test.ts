import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../src/api/client'
import type { FileUploadOperation, ReportPhoto, ReportPhotoPolicy, Session } from '../src/api/types'
import { contextGeneration, session } from '../src/stores/session'
import { createPhotoQueue, type PhotoQueue } from '../src/utils/photoQueue'

const tenantId = '10000000-0000-0000-0000-000000000000', actorId = '20000000-0000-0000-0000-000000000000'
const fileId = '30000000-0000-0000-0000-000000000000', operationId = '40000000-0000-0000-0000-000000000000', grantId = '50000000-0000-0000-0000-000000000000'
const policy: ReportPhotoPolicy = { purpose: 'customer_report_photo', allowed_media_types: ['image/jpeg', 'image/png'], max_size_bytes: 5_000_000, max_attachments: 3, policy_source: 'USER_DEVELOPMENT_DIRECTION', production_approval: 'UNVERIFIED', scan_status: 'UNSCANNED', retention_until: null }
const photo = (overrides: Partial<ReportPhoto> = {}): ReportPhoto => ({ id: fileId, tenant_id: tenantId, purpose: 'customer_report_photo', original_name: 'original.jpg', media_type: 'image/jpeg', size_bytes: 3, sha256: 'a'.repeat(64), upload_status: 'READY', scan_status: 'UNSCANNED', retention_until: null, production_approval: 'UNVERIFIED', policy_source: 'USER_DEVELOPMENT_DIRECTION', created_by: actorId, ticket_id: null, operation_id: operationId, version: 1, created_at: '2026-10-01T00:00:00Z', updated_at: '2026-10-01T00:00:00Z', source: 'postgresql', ...overrides })
const operation = (status: FileUploadOperation['status'] = 'SUCCEEDED'): FileUploadOperation => ({ id: operationId, tenant_id: tenantId, kind: 'FILE_UPLOAD', status, step: status === 'FAILED' ? 'STORAGE_CONFLICT' : status === 'RUNNING' ? 'UPLOADING' : status === 'PENDING' ? 'QUEUED' : 'STORED', progress: null, result_ref: `/api/v1/files/${fileId}`, error: status === 'FAILED' ? { code: 'FILE_INTEGRITY_CONFLICT', message: 'stored job failed', retryable: false, question_ids: [] } : null, created_at: '2026-10-01T00:00:00Z', updated_at: '2026-10-01T00:00:00Z' })
function json(data: unknown, status = 200) { return new Response(JSON.stringify({ data, trace_id: 'unit-trace' }), { status, headers: { 'Content-Type': 'application/json' } }) }
function failure(status: number, code = 'UPSTREAM_UNAVAILABLE') { return new Response(JSON.stringify({ error: { code, message: 'unit failure', details: [] }, trace_id: 'unit-trace' }), { status, headers: { 'Content-Type': 'application/json' } }) }
const queues: PhotoQueue[] = []
function queue(transport: typeof fetch) { const result = createPhotoQueue(new ApiClient(transport)); queues.push(result); result.policy.value = policy; return result }
function selected(result: PhotoQueue) { const original = new File([new Uint8Array([0xff, 0xd8, 0xff])], 'original.jpg', { type: 'image/jpeg' }); expect(result.add(original, 1, 0)).toBeNull(); return original }
beforeEach(() => {
  session.value = { user: { id: actorId, role: 'viewer', display_name: 'Unit actor' }, tenant: { id: tenantId, name: 'Unit tenant' }, permission_version: 1, capabilities: ['tickets:report', 'tickets:read'], allowed_tenants: [], csrf_token: 'unit-csrf', mode: 'remote_test', identity_source: 'unit-only' } satisfies Session
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:unit-${Math.random()}`)
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
})
afterEach(() => { for (const item of queues.splice(0)) item.dispose(); session.value = null; vi.restoreAllMocks() })

describe('durable photo upload state in the current page', () => {
  it('treats a malformed successful acceptance as uncertain and retains the same original upload request', async () => {
    const transport = vi.fn(async (_url: RequestInfo | URL, _options?: RequestInit) => json(null, 202))
    const result = queue(transport), original = selected(result)
    await result.upload(1)
    expect(result.rows.value[0]?.status).toBe('UNCERTAIN')
    const key = result.rows.value[0]?.attempt?.key
    await result.upload(1)
    expect(result.rows.value[0]?.attempt?.file).toBe(original)
    expect(result.rows.value[0]?.attempt?.key).toBe(key)
    expect(transport.mock.calls[1]?.[1]?.headers).toMatchObject({ 'Idempotency-Key': key })
  })
  it('preserves original bytes/File/key across an uncertain upload and never reposts after 202', async () => {
    let uploads = 0
    const transport = vi.fn(async (url: RequestInfo | URL, options?: RequestInit) => {
      if (url === '/api/v1/files') { if (++uploads === 1) throw new TypeError('unit network outcome unknown'); return json({ operation_id: operationId, status: 'PENDING' }, 202) }
      return failure(503)
    })
    const result = queue(transport), original = selected(result)
    await result.upload(1)
    const row = result.rows.value[0]!
    expect(row.status).toBe('UNCERTAIN'); expect(row.attempt?.file).toBe(original)
    result.remove(1); expect(result.rows.value).toHaveLength(1)
    await result.upload(1)
    await vi.waitFor(() => expect(row.error).toMatchObject({ status: 503 }))
    const posts = transport.mock.calls.filter(([, options]) => options?.method === 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[0]![1]?.headers).toMatchObject({ 'Idempotency-Key': row.attempt?.key })
    expect(posts[1]![1]?.headers).toMatchObject({ 'Idempotency-Key': row.attempt?.key })
    for (const [, options] of posts) {
      const form = options?.body as FormData
      expect(form.get('purpose')).toBe('customer_report_photo')
      expect(await (form.get('file') as File).arrayBuffer()).toEqual(await original.arrayBuffer())
      expect((options?.headers as Record<string, string>)['Content-Type']).toBeUndefined()
    }
    expect(row.status).toBe('PENDING'); expect(row.operationId).toBe(operationId); expect(row.attempt?.state).toBe('accepted')
    await result.upload(1); await result.refresh(1)
    expect(transport.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(2)
  })
  it('only accepts an owned READY file and keeps security/retention declarations unchanged', async () => {
    const transport = vi.fn(async (url: RequestInfo | URL) => url === '/api/v1/files' ? json({ operation_id: operationId, status: 'PENDING' }, 202) : String(url).includes('/operations/') ? json(operation()) : json(photo()))
    const result = queue(transport); selected(result)
    await result.upload(1); await vi.waitFor(() => expect(result.rows.value[0]?.status).toBe('READY'))
    expect(result.readyIds()).toEqual([fileId])
    expect(result.rows.value[0]?.record).toMatchObject({ scan_status: 'UNSCANNED', production_approval: 'UNVERIFIED', retention_until: null })
  })
  it('shows a real worker failure and refreshes it without another upload', async () => {
    const transport = vi.fn(async (url: RequestInfo | URL, _options?: RequestInit) => url === '/api/v1/files' ? json({ operation_id: operationId, status: 'PENDING' }, 202) : String(url).includes('/operations/') ? json(operation('FAILED')) : json(photo({ upload_status: 'FAILED' })))
    const result = queue(transport); selected(result)
    await result.upload(1); await vi.waitFor(() => expect(result.rows.value[0]?.status).toBe('FAILED'))
    expect(result.rows.value[0]?.message).toBe('stored job failed'); expect(result.readyIds()).toEqual([])
    await result.upload(1); await result.refresh(1)
    expect(transport.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  })
  it('rejects cross-tenant and different-uploader metadata instead of associating it', async () => {
    let detail = photo({ created_by: 'other-uploader' })
    const transport = vi.fn(async (url: RequestInfo | URL) => url === '/api/v1/files' ? json({ operation_id: operationId, status: 'PENDING' }, 202) : String(url).includes('/operations/') ? json(operation()) : json(detail))
    const result = queue(transport); selected(result)
    await result.upload(1); await vi.waitFor(() => expect(result.rows.value[0]?.error).toMatchObject({ code: 'INVALID_UPLOAD_RESULT' }))
    expect(result.readyIds()).toEqual([]); expect(result.rows.value[0]?.record).toBeNull()
    detail = photo({ tenant_id: 'other-tenant' }); await result.refresh(1)
    expect(result.readyIds()).toEqual([]); expect(result.rows.value[0]?.record).toBeNull()
  })
  it('counts existing attachments and frees previews only after explicit removal or context change', () => {
    const result = queue(vi.fn()), original = new File(['x'], 'original.jpg', { type: 'image/jpeg' })
    expect(result.add(original, 1, 2)).toBeNull()
    expect(result.add(original, 2, 2)).toContain('最多 3')
    const url = result.rows.value[0]!.previewUrl
    result.remove(1); expect(URL.revokeObjectURL).toHaveBeenCalledWith(url)
    expect(result.add(original, 2, 2)).toBeNull()
    const nextUrl = result.rows.value[0]!.previewUrl
    contextGeneration.value++
    expect(result.rows.value).toEqual([]); expect(URL.revokeObjectURL).toHaveBeenCalledWith(nextUrl)
  })
  it('deduplicates only files actually present in the detail when enforcing the three-photo total', () => {
    const result = queue(vi.fn()), original = selected(result)
    result.rows.value[0]!.record = photo({ ticket_id: grantId })
    expect(result.add(original, 2, 1, [fileId])).toBeNull()
    expect(result.add(original, 3, 1, [fileId])).toBeNull()
    expect(result.add(original, 4, 1, [fileId])).toContain('最多 3')
    result.clear(); result.policy.value = policy
    expect(result.add(original, 1, 0)).toBeNull(); result.rows.value[0]!.record = photo({ ticket_id: grantId })
    expect(result.add(original, 2, 0)).toBeNull(); expect(result.add(original, 3, 0)).toBeNull()
    expect(result.add(original, 4, 0)).toContain('最多 3')
  })
  it('cancels a tenant/role change and ignores late accepted uploads even if fetch ignores abort', async () => {
    let finish!: (response: Response) => void
    let signal: AbortSignal | undefined
    const transport = vi.fn(async (_url: RequestInfo | URL, options?: RequestInit) => { signal = options?.signal as AbortSignal; return await new Promise<Response>(resolve => { finish = resolve }) })
    const result = queue(transport); selected(result); const pending = result.upload(1)
    session.value = { ...session.value!, user: { ...session.value!.user, role: 'msp' } }
    expect(signal?.aborted).toBe(true)
    finish(json({ operation_id: operationId, status: 'PENDING' }, 202)); await pending
    expect(result.rows.value).toEqual([]); expect(transport).toHaveBeenCalledTimes(1)
  })
})
describe('platform-granted original image reads', () => {
  it('rejects a storage URL or wrong file grant before fetching binary content', async () => {
    const transport = vi.fn(async () => json({ content_url: `https://storage.example/original.jpg?grant=${grantId}`, expires_at: '2099-10-01T00:00:00Z' }))
    const result = queue(transport); await result.view(photo())
    expect(transport).toHaveBeenCalledTimes(1)
    expect(result.contentErrors.value[fileId]).toMatchObject({ code: 'INVALID_CONTENT_REFERENCE' })
    expect(result.contentUrls.value).toEqual({})
  })
  it('reads exact original bytes through the platform, rejects mismatches, and releases authorized previews', async () => {
    let bytes = new Uint8Array([0xff, 0xd8, 0xff])
    const transport = vi.fn(async (url: RequestInfo | URL) => String(url).endsWith('/content-grants') ? json({ content_url: `/api/v1/files/${fileId}/content?grant=${grantId}`, expires_at: '2099-10-01T00:00:00Z' }) : new Response(bytes, { headers: { 'Content-Type': 'image/jpeg' } }))
    const result = queue(transport); await result.view(photo())
    expect(result.contentUrls.value[fileId]).toMatch(/^blob:unit-/)
    expect(transport.mock.calls[1]?.[0]).toBe(`/api/v1/files/${fileId}/content?grant=${grantId}`)
    const blob = vi.mocked(URL.createObjectURL).mock.calls[0]![0] as Blob
    expect(new Uint8Array(await blob.arrayBuffer())).toEqual(bytes)
    const originalUrl = result.contentUrls.value[fileId]!
    result.releaseContent(); expect(URL.revokeObjectURL).toHaveBeenCalledWith(originalUrl)
    bytes = new Uint8Array([1, 2]); await result.view(photo())
    expect(result.contentErrors.value[fileId]).toMatchObject({ code: 'INVALID_IMAGE_CONTENT' }); expect(result.contentUrls.value).toEqual({})
  })
  it('rejects an expired grant and a late binary read after an authorization change', async () => {
    const expired = queue(vi.fn(async () => json({ content_url: `/api/v1/files/${fileId}/content?grant=${grantId}`, expires_at: '2000-10-01T00:00:00Z' })))
    await expired.view(photo()); expect(expired.contentErrors.value[fileId]).toMatchObject({ code: 'INVALID_CONTENT_REFERENCE' })
    let finish!: (response: Response) => void
    const transport = vi.fn(async (url: RequestInfo | URL) => String(url).endsWith('/content-grants') ? json({ content_url: `/api/v1/files/${fileId}/content?grant=${grantId}`, expires_at: '2099-10-01T00:00:00Z' }) : await new Promise<Response>(resolve => { finish = resolve }))
    const result = queue(transport), pending = result.view(photo())
    await vi.waitFor(() => expect(transport).toHaveBeenCalledTimes(2))
    session.value = { ...session.value!, permission_version: 2 }
    finish(new Response(new Uint8Array([1, 2, 3]), { headers: { 'Content-Type': 'image/jpeg' } })); await pending
    expect(result.contentUrls.value).toEqual({})
  })
})
