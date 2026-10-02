import { describe, expect, it } from 'vitest'
import { emptyReportForm, parseCustomerReport, prepareReportAttempt, ReportValidationError, ticketIdFromResultRef } from '../src/utils/ticket'

const assetId = '11111111-2222-3333-4444-555555555555'
const validForm = () => ({ ...emptyReportForm(), description: '总部网络不通\n请协助检查。\t影响办公', category: '网络不通', impact_scope: 'partial_area', contact_name: '测试联系人', contact_phone: '+86 (010) 1234-5678', urgency: 'urgent' })
function errors(form: ReturnType<typeof validForm>) {
  try { parseCustomerReport(form, [assetId]); throw new Error('Expected validation failure') }
  catch (cause) { expect(cause).toBeInstanceOf(ReportValidationError); return (cause as ReportValidationError).fields }
}
describe('customer report input', () => {
  it('keeps unknown devices absent, fixes source, and does not invent a title or priority', () => {
    const body = parseCustomerReport(validForm(), [])
    expect(body).toMatchObject({ source: 'customer_report', impact_scope: 'partial_area', urgency: 'urgent' })
    expect(body).not.toHaveProperty('asset_id')
    expect(body).not.toHaveProperty('title')
    expect(body).not.toHaveProperty('priority')
    expect(body.description).toContain('\n')
  })
  it('accepts only currently selectable assets and validates category, impact and urgency', () => {
    expect(parseCustomerReport({ ...validForm(), asset_id: assetId }, [assetId]).asset_id).toBe(assetId)
    expect(errors({ ...validForm(), asset_id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' }).asset_id).toBeTruthy()
    expect(errors({ ...validForm(), asset_id: 'https://example.test/asset' }).asset_id).toBeTruthy()
    expect(errors({ ...validForm(), category: '自定类别', impact_scope: 'department', urgency: 'P1' })).toMatchObject({ category: expect.any(String), impact_scope: expect.any(String), urgency: expect.any(String) })
  })
  it('counts Unicode codepoints, requires a numeric phone and rejects unsafe text', () => {
    expect(parseCustomerReport({ ...validForm(), description: '😀'.repeat(3000), contact_name: '😀'.repeat(100) }, []).description).toHaveLength(6000)
    expect(errors({ ...validForm(), description: '😀'.repeat(3001) }).description).toBeTruthy()
    expect(errors({ ...validForm(), description: '  ', contact_phone: '+-()' })).toMatchObject({ description: expect.any(String), contact_phone: expect.any(String) })
    expect(errors({ ...validForm(), contact_phone: '123abc' }).contact_phone).toBeTruthy()
    expect(errors({ ...validForm(), contact_name: '联系人\n' }).contact_name).toBeTruthy()
    expect(errors({ ...validForm(), title: '\ud800' }).title).toBeTruthy()
    expect(errors({ ...validForm(), description: '描述\u0000' }).description).toBeTruthy()
  })
})
describe('Unicode report boundaries', () => {
  it('rejects space-only required text and omits an empty optional title', () => {
    for (const space of ['\u00a0', '\u2007', '\u202f', '\ufeff', '\u3000']) {
      expect(errors({ ...validForm(), description: space }).description).toBeTruthy()
      expect(errors({ ...validForm(), contact_name: space }).contact_name).toBeTruthy()
      expect(parseCustomerReport({ ...validForm(), title: space }, [])).not.toHaveProperty('title')
    }
  })
  it('trims boundaries while preserving interior nonbreaking spaces and multiline content', () => {
    expect(parseCustomerReport({ ...validForm(), title: '\ufeff标题\u00a0', description: '\u00a0一\u00a0行\n二行\ufeff', contact_name: '\u202f测试\u2007联系人\u2007', contact_phone: '\u00a0+00 000\ufeff' }, [])).toMatchObject({ title: '标题', description: '一\u00a0行\n二行', contact_name: '测试\u2007联系人', contact_phone: '+00 000' })
  })
})
describe('registration result references', () => {
  it('only accepts the exact local ticket API path with a UUID', () => {
    expect(ticketIdFromResultRef(`/api/v1/tickets/${assetId}`)).toBe(assetId)
    expect(ticketIdFromResultRef('/api/v1/tickets/AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE')).toBe('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
    for (const value of [null, `/tickets/${assetId}`, `/API/v1/tickets/${assetId}`, `https://example.test/api/v1/tickets/${assetId}`, `//example.test/api/v1/tickets/${assetId}`, `/api/v1/tickets/${assetId}?x=1`, `/api/v1/tickets/${assetId}#fragment`, `/api/v1/tickets/${assetId}/`, `/api/v1/tickets/../${assetId}`, `/api/v1/tickets/%31${assetId.slice(1)}`, ` /api/v1/tickets/${assetId}`, '/api/v1/tickets/not-an-id']) expect(ticketIdFromResultRef(value)).toBeNull()
  })
})
describe('manual registration retries', () => {
  it('retains the same key and body after an uncertain outcome and blocks payload replacement', () => {
    const body = parseCustomerReport(validForm(), [])
    const attempt = prepareReportAttempt(null, body, () => 'stable-key')
    attempt.state = 'uncertain'
    expect(prepareReportAttempt(attempt, body, () => 'new-key')).toBe(attempt)
    expect(() => prepareReportAttempt(attempt, { ...body, description: '修改描述' }, () => 'new-key')).toThrow('结果尚不确定')
    attempt.state = 'accepted'
    expect(() => prepareReportAttempt(attempt, body)).toThrow('已登记')
  })
  it('reuses an unchanged rejected request and permits a new key for a corrected request', () => {
    const body = parseCustomerReport(validForm(), [])
    const attempt = prepareReportAttempt(null, body, () => 'first-key')
    expect(prepareReportAttempt(attempt, body, () => 'unused')).toBe(attempt)
    expect(prepareReportAttempt(attempt, { ...body, contact_phone: '12345' }, () => 'corrected-key').key).toBe('corrected-key')
  })
})
