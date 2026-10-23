import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../src/api/client'
import { createAttachmentSecurityFlow, type AttachmentSecurity } from '../src/utils/attachmentSecurity'

const fileId = '11111111-1111-4111-8111-111111111111', tenantId = '22222222-2222-4222-8222-222222222222'
const snapshot = (changes: Partial<AttachmentSecurity> = {}): AttachmentSecurity => ({ file_id: fileId, tenant_id: tenantId,
  version: 0, state: 'QUARANTINED', scan_request_id: null, content_sha256: 'a'.repeat(64), scanned_at: null,
  valid_until: null, recorded_at: null, release_allowed: false, content_allowed: false, can_manage_release: false,
  can_request_scan: true, scanner_status: 'NOT_CONFIGURED', ...changes })
const setup = () => {
  const request = vi.fn().mockResolvedValue(snapshot())
  const flow = createAttachmentSecurityFlow(() => ({ fileId, tenantId }), { request })
  return { request, flow }
}

describe('attachment security business flow', () => {
  it('keeps unconfigured quarantine explicit and blocks client release', async () => {
    const { flow, request } = setup(); await flow.refresh(); await flow.act('RELEASE')
    expect(flow.snapshot.value?.scanner_status).toBe('NOT_CONFIGURED'); expect(request).toHaveBeenCalledTimes(1)
  })
  it('binds writes to observed CAS and original idempotency key', async () => {
    const { flow, request } = setup(); await flow.refresh()
    request.mockRejectedValueOnce(new ApiError(503, 'UPSTREAM_UNAVAILABLE', 'synthetic', 'unit'))
    await flow.act('REQUEST_SCAN'); const original = flow.pending.value!
    expect(original.body).toEqual({ action: 'REQUEST_SCAN', expected_version: 0 })
    await flow.act('QUARANTINE'); expect(request).toHaveBeenCalledTimes(2)
    request.mockResolvedValue(snapshot({ version: 1, state: 'QUEUED' }))
    await flow.retry(); expect(request.mock.calls[2][1]?.idempotencyKey).toBe(original.key)
    expect(request.mock.calls[2][1]?.body).toEqual(original.body); expect(flow.pending.value).toBeNull()
  })
  it('does not resolve an unknown write just because a replay is forbidden', async () => {
    const { flow, request } = setup(); await flow.refresh()
    request.mockRejectedValueOnce(new Error('synthetic network')); await flow.act('REQUEST_SCAN')
    const key = flow.pending.value?.key
    request.mockRejectedValueOnce(new ApiError(403, 'ACTION_FORBIDDEN', 'denied', 'unit')); await flow.retry()
    expect(flow.pending.value?.key).toBe(key)
  })
  it('a known first-write CAS conflict permits refresh and a new explicit attempt', async () => {
    const { flow, request } = setup(); await flow.refresh()
    request.mockRejectedValueOnce(new ApiError(409, 'VERSION_CONFLICT', 'changed', 'unit'))
    await flow.act('REQUEST_SCAN'); expect(flow.pending.value).toBeNull()
  })
  it('rejects cross-tenant and contradictory content permissions', async () => {
    for (const value of [snapshot({ tenant_id: fileId }), snapshot({ state: 'QUEUED', content_allowed: true })]) {
      const { flow, request } = setup(); request.mockResolvedValue(value); await flow.refresh()
      expect(flow.snapshot.value).toBeNull(); expect(flow.error.value).toBeInstanceOf(ApiError)
    }
  })
  it('retains uncertain outcome when user stops waiting', async () => {
    const { flow, request } = setup(); await flow.refresh()
    request.mockImplementationOnce((_path, options) => new Promise((_resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new DOMException('stopped', 'AbortError')))
    }))
    const writing = flow.act('REQUEST_SCAN'); flow.stopWaiting(); await writing
    expect(flow.pending.value).not.toBeNull(); expect(flow.busy.value).toBe(false)
    expect((flow.error.value as ApiError).code).toBe('REQUEST_OUTCOME_UNKNOWN')
  })
  it('ignores late responses after a scope reset', async () => {
    const { flow, request } = setup(); let finish!: (value: AttachmentSecurity) => void
    request.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const reading = flow.refresh(); flow.reset(); finish(snapshot({ state: 'RELEASED', content_allowed: true })); await reading
    expect(flow.snapshot.value).toBeNull()
  })
  it('read-only members cannot queue or cancel another user scan', async () => {
    const { flow, request } = setup(); request.mockResolvedValue(snapshot({ can_request_scan: false })); await flow.refresh()
    await flow.act('REQUEST_SCAN'); expect(request).toHaveBeenCalledTimes(1)
  })
})
