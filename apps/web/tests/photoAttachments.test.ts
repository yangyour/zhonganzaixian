import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { ApiClient } from '../src/api/client'
import type { RegisteredTicket, ReportPhoto, ReportPhotoPolicy, Session } from '../src/api/types'
import { contextGeneration, session } from '../src/stores/session'
import { createPhotoAppender, type PhotoAppender } from '../src/utils/photoAttachments'
import { createPhotoQueue, type PhotoQueue } from '../src/utils/photoQueue'

const tenantId = '10000000-0000-0000-0000-000000000000', actorId = '20000000-0000-0000-0000-000000000000'
const fileId = '30000000-0000-0000-0000-000000000000', operationId = '40000000-0000-0000-0000-000000000000', ticketId = '50000000-0000-0000-0000-000000000000'
const photo: ReportPhoto = { id: fileId, tenant_id: tenantId, purpose: 'customer_report_photo', original_name: 'original.jpg', media_type: 'image/jpeg', size_bytes: 3, sha256: 'a'.repeat(64), upload_status: 'READY', scan_status: 'UNSCANNED', retention_until: null, production_approval: 'UNVERIFIED', policy_source: 'USER_DEVELOPMENT_DIRECTION', created_by: actorId, ticket_id: null, operation_id: operationId, version: 1, created_at: '2026-10-01T00:00:00Z', updated_at: '2026-10-01T00:00:00Z', source: 'postgresql' }
const policy: ReportPhotoPolicy = { purpose: 'customer_report_photo', allowed_media_types: ['image/jpeg', 'image/png'], max_size_bytes: 5_000_000, max_attachments: 3, policy_source: 'USER_DEVELOPMENT_DIRECTION', production_approval: 'UNVERIFIED', scan_status: 'UNSCANNED', retention_until: null }
const registered = (version = 1, attachments: ReportPhoto[] = []): RegisteredTicket => ({ id: ticketId, tenant_id: tenantId, reporter_id: actorId, description: 'original registered description', contact_name: 'original contact', contact_phone: '1234', version, attachments } as RegisteredTicket)
function json(data: unknown) { return new Response(JSON.stringify({ data, trace_id: 'unit-trace' }), { headers: { 'Content-Type': 'application/json' } }) }
function conflict() { return new Response(JSON.stringify({ error: { code: 'VERSION_CONFLICT', message: 'unit version conflict', details: [] }, trace_id: 'conflict-trace' }), { status: 409, headers: { 'Content-Type': 'application/json' } }) }
const cleanup: (() => void)[] = []
function workflow(transport: typeof fetch) {
  const client = new ApiClient(transport), queue = createPhotoQueue(client)
  queue.policy.value = policy
  const original = new File(['abc'], 'original.jpg', { type: 'image/jpeg' })
  expect(queue.add(original, 1, 0)).toBeNull()
  Object.assign(queue.rows.value[0]!, { status: 'READY', record: photo, operationId })
  const ticket = ref<RegisteredTicket | null>(registered()), appender = createPhotoAppender(ticket, queue, client)
  cleanup.push(() => { appender.dispose(); queue.dispose() })
  return { queue, ticket, appender, original }
}
beforeEach(() => {
  session.value = { user: { id: actorId, role: 'viewer', display_name: 'Unit actor' }, tenant: { id: tenantId, name: 'Unit tenant' }, permission_version: 1, capabilities: ['tickets:report', 'tickets:read'], allowed_tenants: [], csrf_token: 'unit-csrf', mode: 'remote_test', identity_source: 'unit-only' } satisfies Session
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => 'blob:unit-original'); vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
})
afterEach(() => { for (const finish of cleanup.splice(0)) finish(); session.value = null; vi.restoreAllMocks() })
describe('attachment writes on already registered reports', () => {
  it('appends only file references with an explicit version and preserves the original report', async () => {
    const transport = vi.fn(async (_url: RequestInfo | URL, _options?: RequestInit) => json(registered(2, [{ ...photo, ticket_id: ticketId }])))
    const { queue, ticket, appender, original } = workflow(transport)
    await appender.append()
    expect(transport.mock.calls[0]?.[0]).toBe(`/api/v1/tickets/${ticketId}/attachments`)
    expect(JSON.parse(String(transport.mock.calls[0]?.[1]?.body))).toEqual({ expected_version: 1, attachment_ids: [fileId] })
    expect(ticket.value).toMatchObject({ description: 'original registered description', contact_name: 'original contact', version: 2 })
    expect(queue.rows.value[0]?.file).toBe(original); expect(queue.rows.value[0]?.record?.ticket_id).toBe(ticketId)
    expect(appender.readyCount.value).toBe(0); expect(appender.attempt.value).toBeNull()
  })
  it('retains original key/body after a network uncertainty even when an explicit read finds a newer version', async () => {
    let posts = 0
    const transport = vi.fn(async (_url: RequestInfo | URL, options?: RequestInit) => {
      if (options?.method === 'POST') { if (++posts === 1) throw new TypeError('unit uncertain outcome'); return json(registered(3, [{ ...photo, ticket_id: ticketId }])) }
      return json(registered(2))
    })
    const { queue, appender, original } = workflow(transport)
    await appender.append(); expect(appender.uncertain.value).toBe(true)
    const first = appender.attempt.value!, body = JSON.stringify(first.body), key = first.key
    await appender.refreshComparison()
    expect(appender.uncertain.value).toBe(true); expect(appender.attempt.value?.body.expected_version).toBe(1)
    await appender.append()
    const writes = transport.mock.calls.filter(([, options]) => options?.method === 'POST')
    expect(writes).toHaveLength(2)
    for (const [, options] of writes) { expect(options?.body).toBe(body); expect(options?.headers).toMatchObject({ 'Idempotency-Key': key }) }
    expect(queue.rows.value[0]?.file).toBe(original); expect(appender.uncertain.value).toBe(false)
  })
  it('requires a manual read/compare after CAS conflict and retains the local queue throughout', async () => {
    let posts = 0
    const transport = vi.fn(async (_url: RequestInfo | URL, options?: RequestInit) => {
      if (options?.method === 'POST') return ++posts === 1 ? conflict() : json(registered(3, [{ ...photo, ticket_id: ticketId }]))
      return json(registered(2))
    })
    const { queue, appender, original } = workflow(transport)
    await appender.append()
    expect(appender.conflict.value).toBe(true); expect(appender.error.value).toMatchObject({ code: 'VERSION_CONFLICT', traceId: 'conflict-trace' })
    expect(queue.rows.value[0]?.file).toBe(original)
    const oldKey = appender.attempt.value?.key
    await appender.append(); expect(transport).toHaveBeenCalledTimes(1)
    await appender.refreshComparison(); expect(appender.conflict.value).toBe(false)
    expect(appender.comparison.value).toContain('v1 → v2'); expect(queue.rows.value).toHaveLength(1)
    await appender.append()
    expect(JSON.parse(String(transport.mock.calls[2]?.[1]?.body)).expected_version).toBe(2)
    expect((transport.mock.calls[2]?.[1]?.headers as Record<string, string>)['Idempotency-Key']).not.toBe(oldKey)
  })
  it('retains an uncertain request when a malformed success omits its new attachment', async () => {
    const transport = vi.fn(async () => json(registered(2)))
    const { queue, appender } = workflow(transport)
    await appender.append()
    expect(appender.uncertain.value).toBe(true); expect(appender.error.value).toMatchObject({ code: 'INVALID_ATTACHMENT_RESULT' })
    expect(queue.rows.value[0]?.record?.ticket_id).toBeNull(); expect(appender.readyCount.value).toBe(1)
  })
  it('does not write as an internal reader, another reporter, or with three existing attachments', async () => {
    const transport = vi.fn(async () => json(registered()))
    const { ticket, appender, queue } = workflow(transport)
    ticket.value = { ...registered(), reporter_id: 'different-actor' }; await appender.append(); expect(transport).not.toHaveBeenCalled()
    ticket.value = registered(1, [photo, { ...photo, id: '60000000-0000-0000-0000-000000000000' }, { ...photo, id: '70000000-0000-0000-0000-000000000000' }])
    queue.rows.value[0]!.record = { ...photo, id: '80000000-0000-0000-0000-000000000000' }
    await appender.append(); expect(appender.error.value).toMatchObject({ code: 'PHOTO_INPUT_ERROR' }); expect(transport).not.toHaveBeenCalled()
    session.value = { ...session.value!, user: { ...session.value!.user, role: 'msp' } }
    ticket.value = registered(); await appender.append(); expect(transport).not.toHaveBeenCalled()
  })
  it('ignores late successful attachment responses after an authorization context switch', async () => {
    let finish!: (response: Response) => void
    let signal: AbortSignal | undefined
    const transport = vi.fn(async (_url: RequestInfo | URL, options?: RequestInit) => { signal = options?.signal as AbortSignal; return await new Promise<Response>(resolve => { finish = resolve }) })
    const { ticket, appender, queue } = workflow(transport), pending = appender.append()
    contextGeneration.value++
    expect(signal?.aborted).toBe(true)
    finish(json(registered(2, [{ ...photo, ticket_id: ticketId }]))); await pending
    expect(ticket.value?.version).toBe(1); expect(queue.rows.value).toEqual([]); expect(appender.attempt.value).toBeNull()
  })
})

it('denied attachment retry cannot rebase the original uncertain request or force conflict reset',async()=>{for(const status of [400,403,404,409,422]){let writes=0;const {appender}=workflow(vi.fn(async()=>{if(++writes===1)throw new TypeError('lost');return new Response(JSON.stringify({error:{code:status===409?'VERSION_CONFLICT':'ACTION_FORBIDDEN',message:'isolated',details:[]},trace_id:'isolated'}),{status})}));await appender.append();const key=appender.attempt.value!.key,body=JSON.stringify(appender.attempt.value!.body);await appender.append();expect(appender.uncertain.value).toBe(true);expect(appender.conflict.value).toBe(false);expect(appender.attempt.value!.key).toBe(key);expect(JSON.stringify(appender.attempt.value!.body)).toBe(body)}})
