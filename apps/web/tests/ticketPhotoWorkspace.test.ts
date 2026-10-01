import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../src/api/client'
import type { RegisteredTicket, ReportPhoto, ReportPhotoPolicy, Session } from '../src/api/types'
import { session } from '../src/stores/session'
import { createPhotoQueue } from '../src/utils/photoQueue'
import { createTicketPhotoWorkspace } from '../src/utils/ticketPhotoWorkspace'

const tenantId = '10000000-0000-0000-0000-000000000000', actorId = '20000000-0000-0000-0000-000000000000'
const ticketA = '30000000-0000-0000-0000-000000000000', ticketB = '40000000-0000-0000-0000-000000000000'
const fileId = '50000000-0000-0000-0000-000000000000', operationId = '60000000-0000-0000-0000-000000000000'
const policy: ReportPhotoPolicy = { purpose: 'customer_report_photo', allowed_media_types: ['image/jpeg', 'image/png'], max_size_bytes: 5_000_000, max_attachments: 3, policy_source: 'USER_DEVELOPMENT_DIRECTION', production_approval: 'UNVERIFIED', scan_status: 'UNSCANNED', retention_until: null }
const photo = (ticket_id: string | null = null): ReportPhoto => ({ id: fileId, tenant_id: tenantId, created_by: actorId, purpose: 'customer_report_photo', original_name: 'B.jpg', media_type: 'image/jpeg', size_bytes: 3, sha256: 'a'.repeat(64), upload_status: 'READY', scan_status: 'UNSCANNED', retention_until: null, production_approval: 'UNVERIFIED', policy_source: 'USER_DEVELOPMENT_DIRECTION', ticket_id, operation_id: operationId, version: 1, created_at: '2026-10-01T00:00:00Z', updated_at: '2026-10-01T00:00:00Z', source: 'postgresql' })
const ticket = (id: string, attachments: ReportPhoto[] = []): RegisteredTicket => ({ id, tenant_id: tenantId, reporter_id: actorId, receipt_no: id === ticketA ? 'A-RECEIPT' : 'B-RECEIPT', title: id === ticketA ? 'A original report' : 'B current report', version: attachments.length ? 2 : 1, attachments } as RegisteredTicket)
const operation = () => ({ id: operationId, tenant_id: tenantId, kind: 'FILE_UPLOAD', status: 'SUCCEEDED', step: 'STORED', progress: null, result_ref: `/api/v1/files/${fileId}`, error: null, created_at: '2026-10-01T00:00:00Z', updated_at: '2026-10-01T00:00:00Z' })
const json = (data: unknown, status = 200) => new Response(JSON.stringify({ data, trace_id: 'workspace-unit' }), { status, headers: { 'Content-Type': 'application/json' } })
const unavailable = () => new Response(JSON.stringify({ error: { code: 'UPSTREAM_UNAVAILABLE', message: 'unit read failed', details: [] }, trace_id: 'workspace-read' }), { status: 503 })
const cleanup: (() => void)[] = []
function workflow(transport: typeof fetch) {
  const client = new ApiClient(transport), queue = createPhotoQueue(client), workspace = createTicketPhotoWorkspace(queue, client)
  queue.policy.value = policy; workspace.visit(ticketA); workspace.accept(ticket(ticketA)); queue.policy.value = policy
  cleanup.push(() => { workspace.dispose(); queue.dispose() })
  return { workspace, queue }
}
beforeEach(() => {
  session.value = { user: { id: actorId, role: 'viewer', display_name: 'Unit actor' }, tenant: { id: tenantId, name: 'Unit tenant' }, permission_version: 1, capabilities: ['tickets:report', 'tickets:read'], allowed_tenants: [], csrf_token: 'unit-csrf', mode: 'remote_test', identity_source: 'unit-only' } satisfies Session
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:workspace-${Math.random()}`)
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
})
afterEach(() => { for (const dispose of cleanup.splice(0)) dispose(); session.value = null; vi.restoreAllMocks() })

// These transport substitutes exercise browser state only; the root task verifies real HTTP and drawers.
describe('retained photos while visiting another registration', () => {
  it('retains A on close/open B, then explicitly discards A before uploading and appending only B photos', async () => {
    const transport = vi.fn(async (url: RequestInfo | URL, _options?: RequestInit) => {
      if (url === '/api/v1/files') return json({ operation_id: operationId, status: 'PENDING' }, 202)
      if (url === `/api/v1/tickets/${ticketB}/attachments`) return json(ticket(ticketB, [photo(ticketB)]))
      if (String(url).includes('/operations/')) return json(operation())
      return json(photo())
    })
    const { workspace, queue } = workflow(transport), originalA = new File(['aaa'], 'A.jpg', { type: 'image/jpeg' })
    expect(queue.add(originalA, 1, 0)).toBeNull()
    const previewA = queue.rows.value[0]!.previewUrl
    expect(workspace.visit('')).toBe(false); expect(workspace.visit(ticketB)).toBe(false)
    expect(workspace.accept(ticket(ticketB))).toBe(false)
    expect(workspace.ticket.value?.receipt_no).toBe('A-RECEIPT'); expect(workspace.targetId.value).toBe(ticketA)
    expect(queue.rows.value[0]?.file).toBe(originalA); expect(transport).not.toHaveBeenCalled()
    expect(workspace.canDiscard.value).toBe(true)
    expect(workspace.discard(ticketB)).toBe(true); expect(URL.revokeObjectURL).toHaveBeenCalledWith(previewA)
    expect(workspace.accept(ticket(ticketB))).toBe(true); expect(workspace.appender.readyCount.value).toBe(0)
    queue.policy.value = policy
    const originalB = new File(['bbb'], 'B.jpg', { type: 'image/jpeg' })
    expect(queue.add(originalB, 2, 0)).toBeNull(); await queue.upload(2)
    await vi.waitFor(() => expect(queue.rows.value[0]?.status).toBe('READY'))
    await workspace.appender.append()
    const writes = transport.mock.calls.filter(([, options]) => options?.method === 'POST')
    expect(writes.map(([url]) => url)).toEqual(['/api/v1/files', `/api/v1/tickets/${ticketB}/attachments`])
    expect(((writes[0]![1]?.body as FormData).get('file') as File).name).toBe('B.jpg')
    expect(JSON.parse(String(writes[1]![1]?.body))).toEqual({ expected_version: 1, attachment_ids: [fileId] })
    expect(workspace.ticket.value?.id).toBe(ticketB); expect(queue.rows.value[0]?.record?.ticket_id).toBe(ticketB)
  })

  it('keeps A File and request key through uncertainty and accepted-but-unread state; release needs terminal GETs', async () => {
    let uploads = 0, ready = false
    const transport = vi.fn(async (url: RequestInfo | URL, options?: RequestInit) => {
      if (options?.method === 'POST') { if (++uploads === 1) throw new TypeError('unit upload outcome unknown'); return json({ operation_id: operationId, status: 'PENDING' }, 202) }
      if (!ready) return unavailable()
      return String(url).includes('/operations/') ? json(operation()) : json(photo())
    })
    const { workspace, queue } = workflow(transport), original = new File(['aaa'], 'A.jpg', { type: 'image/jpeg' })
    expect(queue.add(original, 1, 0)).toBeNull(); await queue.upload(1)
    const row = queue.rows.value[0]!, key = row.attempt!.key
    expect(workspace.visit(ticketB)).toBe(false); expect(workspace.canDiscard.value).toBe(false)
    expect(workspace.discard(ticketB)).toBe(false); queue.remove(1)
    expect(queue.rows.value[0]?.file).toBe(original); expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    expect(workspace.visit(ticketA)).toBe(true); await queue.upload(1)
    await vi.waitFor(() => expect(row.error).toMatchObject({ status: 503 }))
    expect(row.attempt?.state).toBe('accepted'); expect(row.record).toBeNull()
    expect(workspace.visit(ticketB)).toBe(false); expect(workspace.discard(ticketB)).toBe(false); queue.remove(1)
    await queue.upload(1); expect(uploads).toBe(2)
    const writes = transport.mock.calls.filter(([, options]) => options?.method === 'POST')
    for (const [, options] of writes) {
      expect(options?.headers).toMatchObject({ 'Idempotency-Key': key })
      expect(await ((options?.body as FormData).get('file') as File).arrayBuffer()).toEqual(await original.arrayBuffer())
    }
    ready = true; await queue.refresh(1)
    expect(row.status).toBe('READY'); expect(workspace.canDiscard.value).toBe(true)
    expect(workspace.discard(ticketB)).toBe(true); expect(queue.rows.value).toEqual([])
    expect(uploads).toBe(2)
  })

  it('retains an uncertain A attachment write when B is opened and replays only the A request', async () => {
    let appends = 0
    const transport = vi.fn(async (url: RequestInfo | URL, options?: RequestInit) => {
      if (url === '/api/v1/files') return json({ operation_id: operationId, status: 'PENDING' }, 202)
      if (String(url).endsWith('/attachments')) { if (++appends === 1) throw new TypeError('unit append outcome unknown'); return json(ticket(ticketA, [photo(ticketA)])) }
      return String(url).includes('/operations/') ? json(operation()) : json(photo())
    })
    const { workspace, queue } = workflow(transport)
    expect(queue.add(new File(['aaa'], 'A.jpg', { type: 'image/jpeg' }), 1, 0)).toBeNull()
    await queue.upload(1); await vi.waitFor(() => expect(queue.rows.value[0]?.status).toBe('READY'))
    await workspace.appender.append(); expect(workspace.appender.uncertain.value).toBe(true)
    const original = workspace.appender.attempt.value!, body = JSON.stringify(original.body)
    expect(workspace.visit(ticketB)).toBe(false); expect(workspace.discard(ticketB)).toBe(false)
    expect(workspace.accept(ticket(ticketB))).toBe(false); expect(workspace.targetId.value).toBe(ticketA)
    expect(workspace.visit(ticketA)).toBe(true); await workspace.appender.append()
    const writes = transport.mock.calls.filter(([url]) => String(url).endsWith('/attachments'))
    expect(writes).toHaveLength(2)
    for (const [url, options] of writes) { expect(url).toBe(`/api/v1/tickets/${ticketA}/attachments`); expect(options?.body).toBe(body); expect(options?.headers).toMatchObject({ 'Idempotency-Key': original.key }) }
    expect(workspace.visit(ticketB)).toBe(true); expect(workspace.targetId.value).toBe(ticketB)
  })
})
