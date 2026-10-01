import { describe, expect, it } from 'vitest'
import type { RegisteredTicket, ReportPhoto, ReportPhotoPolicy } from '../src/api/types'
import { fileContentPath, fileIdFromResultRef, photoSelectionError, prepareAttachmentAttempt, preparePhotoAttempt, readyPhotoIds } from '../src/utils/photo'

const fileId = '11111111-2222-3333-4444-555555555555', grantId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
const context = { tenantId: 'tenant-a', actorId: 'actor-a' }
const policy: ReportPhotoPolicy = { purpose: 'customer_report_photo', allowed_media_types: ['image/jpeg', 'image/png'], max_size_bytes: 5_000_000, max_attachments: 3, policy_source: 'USER_DEVELOPMENT_DIRECTION', production_approval: 'UNVERIFIED', scan_status: 'UNSCANNED', retention_until: null }
const photo = (overrides: Partial<ReportPhoto> = {}): ReportPhoto => ({ id: fileId, tenant_id: context.tenantId, purpose: 'customer_report_photo', original_name: 'original.jpg', media_type: 'image/jpeg', size_bytes: 3, sha256: 'a'.repeat(64), upload_status: 'READY', scan_status: 'UNSCANNED', retention_until: null, production_approval: 'UNVERIFIED', policy_source: 'USER_DEVELOPMENT_DIRECTION', created_by: context.actorId, ticket_id: null, operation_id: grantId, version: 1, created_at: '2026-10-01T00:00:00Z', updated_at: '2026-10-01T00:00:00Z', source: 'postgresql', ...overrides })
const ticket = (attachments: ReportPhoto[] = []): RegisteredTicket => ({ id: grantId, tenant_id: context.tenantId, reporter_id: context.actorId, version: 1, attachments } as RegisteredTicket)

describe('photo policy boundaries', () => {
  it('uses exact byte and attachment limits including existing attachments', () => {
    expect(photoSelectionError({ name: 'original.JPG', type: 'image/jpeg', size: 5_000_000 }, policy, 2)).toBeNull()
    expect(photoSelectionError({ name: 'original.png', type: 'image/png', size: 1 }, policy, 0)).toBeNull()
    expect(photoSelectionError({ name: 'original.png', type: '', size: 3 }, policy, 0)).toBeNull()
    expect(photoSelectionError({ name: 'original.jpg', type: 'image/jpeg', size: 5_000_001 }, policy, 0)).toContain('5,000,000')
    expect(photoSelectionError({ name: 'original.jpg', type: 'image/jpeg', size: 0 }, policy, 0)).toContain('1–')
    expect(photoSelectionError({ name: 'original.jpg', type: 'image/jpeg', size: 1 }, policy, 3)).toContain('最多 3')
    expect(photoSelectionError({ name: 'original.jpg', type: 'image/jpeg', size: 11 }, { ...policy, max_size_bytes: 10 }, 0)).toBeTruthy()
    expect(photoSelectionError({ name: 'original.jpg', type: 'image/jpeg', size: 1 }, { ...policy, max_attachments: 1 }, 1)).toBeTruthy()
    expect(photoSelectionError({ name: 'original.jpg', type: 'image/jpeg', size: 5_000_001 }, { ...policy, max_size_bytes: 10_000_000, max_attachments: 10 }, 0)).toBeTruthy()
  })
  it('fails closed for missing/invalid policy and unsupported or contradictory format declarations', () => {
    const file = { name: 'original.jpg', type: 'image/jpeg', size: 1 }
    for (const value of [null, { ...policy, allowed_media_types: null }, { ...policy, allowed_media_types: [] }]) expect(photoSelectionError(file, value, 0)).toBeTruthy()
    for (const value of [0, -1, NaN, Infinity, 1.5]) expect(photoSelectionError(file, { ...policy, max_size_bytes: value }, 0)).toBeTruthy()
    for (const value of [0, -1, NaN, Infinity, 1.5]) expect(photoSelectionError(file, { ...policy, max_attachments: value }, 0)).toBeTruthy()
    for (const value of [{ name: 'x.svg', type: 'image/svg+xml', size: 1 }, { name: 'x.webp', type: 'image/webp', size: 1 }, { name: 'x.png', type: 'image/jpeg', size: 1 }, { name: 'x.jpg', type: 'image/png', size: 1 }, { name: 'x', type: '', size: 1 }]) expect(photoSelectionError(value, policy, 0)).toBeTruthy()
    expect(photoSelectionError(file, { ...policy, allowed_media_types: ['image/png'] }, 0)).toBeTruthy()
  })
})
describe('file references and original upload retries', () => {
  it('accepts only an exact platform UUID grant for the requested file', () => {
    expect(fileIdFromResultRef(`/api/v1/files/${fileId.toUpperCase()}`)).toBe(fileId)
    expect(fileContentPath(`/api/v1/files/${fileId}/content?grant=${grantId}`, fileId)).toBe(`/files/${fileId}/content?grant=${grantId}`)
    for (const path of [`https://storage.example/api/v1/files/${fileId}/content?grant=${grantId}`, `//storage.example/api/v1/files/${fileId}/content?grant=${grantId}`, `/api/v1/files/${grantId}/content?grant=${grantId}`, `/api/v1/files/${fileId}/content?grant=${grantId}&extra=1`, `/api/v1/files/${fileId}/content?grant=${grantId}#x`, `/api/v1/files/${fileId}/content?grant=not-uuid`, `/api/v1/files/${fileId}/../content?grant=${grantId}`, null]) expect(fileContentPath(path, fileId)).toBeNull()
    expect(fileIdFromResultRef(`/api/v1/files/${fileId}?x=1`)).toBeNull()
  })
  it('retains the original File object and key, and prevents another POST after acceptance', () => {
    const file = new File(['original bytes'], 'original.jpg', { type: 'image/jpeg' })
    const original = preparePhotoAttempt(null, file, () => 'upload-key')
    original.state = 'uncertain'
    expect(preparePhotoAttempt(original, file, () => 'different-key')).toBe(original)
    expect(original.file).toBe(file)
    expect(() => preparePhotoAttempt(original, new File(['different'], file.name, { type: file.type }))).toThrow('原File对象')
    original.state = 'accepted'
    expect(() => preparePhotoAttempt(original, file)).toThrow('已受理')
  })
})
describe('attachment eligibility and compare-and-set requests', () => {
  it('selects only current actor/tenant READY files and never borrows a file bound to another report', () => {
    const allowed = photo()
    const files = [allowed, allowed, photo({ tenant_id: 'tenant-b' }), photo({ created_by: 'actor-b' }), photo({ upload_status: 'PENDING' }), photo({ upload_status: 'FAILED' }), photo({ ticket_id: grantId })]
    expect(readyPhotoIds(files, context)).toEqual([fileId])
    expect(readyPhotoIds([photo({ ticket_id: grantId })], context, grantId)).toEqual([fileId])
    expect(readyPhotoIds([photo({ ticket_id: grantId })], context, fileId)).toEqual([])
  })
  it('counts preexisting files, preserves CAS/version and blocks ownership errors', () => {
    const old = photo({ id: 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff', ticket_id: grantId })
    const next = prepareAttachmentAttempt(null, ticket([old]), [old, photo()], context, () => 'append-key')
    expect(next.body).toEqual({ expected_version: 1, attachment_ids: [fileId] })
    expect(prepareAttachmentAttempt(next, ticket([old]), [photo()], context, () => 'unused')).toBe(next)
    next.state = 'uncertain'
    expect(prepareAttachmentAttempt(next, { ...ticket([old]), version: 8 }, [], context)).toBe(next)
    expect(() => prepareAttachmentAttempt(null, { ...ticket(), reporter_id: 'actor-b' }, [photo()], context)).toThrow('本人')
    expect(() => prepareAttachmentAttempt(null, { ...ticket(), tenant_id: 'tenant-b' }, [photo()], context)).toThrow('当前租户')
    const existing = [old, photo({ id: 'cccccccc-dddd-eeee-ffff-000000000000' }), photo({ id: 'dddddddd-eeee-ffff-0000-111111111111' })]
    expect(() => prepareAttachmentAttempt(null, ticket(existing), [photo()], context)).toThrow('最多 3')
  })
})
