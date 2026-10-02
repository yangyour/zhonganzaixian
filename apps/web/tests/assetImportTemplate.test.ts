import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { ApiError } from '../src/api/client'
import { assetImportTemplateSpec as spec, fetchAssetImportTemplate } from '../src/utils/assetImportTemplate'
const content = new Uint8Array(readFileSync(new URL('../../../server/api/src/main/resources/templates/asset-import-standard15.xlsx', import.meta.url)))
const headers = () => ({ 'Content-Type': spec.media_type, 'Content-Length': String(spec.size_bytes), 'Content-Disposition': `attachment; filename="${spec.filename}"`, 'X-MOP-Template-SHA256': spec.sha256, 'X-MOP-Template-Schema': spec.schema_version, 'X-MOP-Business-Status': 'CONFIG_PENDING' })
const response = (bytes = content, fields = headers()) => new Response(new Uint8Array(bytes), { status: 200, headers: fields })
describe('verified same-origin template download', () => {
  it('returns only verified XLSX bytes and uses GET without mutation or cross-origin credentials', async () => {
    const transport = vi.fn().mockResolvedValue(response())
    const result = await fetchAssetImportTemplate({ transport })
    expect(result.filename).toBe(spec.filename); expect(result.sha256).toBe(spec.sha256); expect(new Uint8Array(await result.blob.arrayBuffer())).toEqual(content)
    expect(transport).toHaveBeenCalledWith('/api/v1/assets/import-template', expect.objectContaining({ method: 'GET', credentials: 'same-origin', redirect: 'error' }))
    expect(transport.mock.calls[0]![1].body).toBeUndefined(); expect(transport.mock.calls[0]![1].headers).not.toHaveProperty('Idempotency-Key')
  })
  it('rejects wrong content metadata or an invented business-approved status', async () => {
    for (const key of Object.keys(headers())) await expect(fetchAssetImportTemplate({ transport: vi.fn().mockResolvedValue(response(content, { ...headers(), [key]: 'wrong' })) })).rejects.toBeInstanceOf(ApiError)
  })
  it('rejects changed bytes despite correct advertised hash and length', async () => {
    const changed = new Uint8Array(content); changed[0] = changed[0]! ^ 1
    await expect(fetchAssetImportTemplate({ transport: vi.fn().mockResolvedValue(response(changed)) })).rejects.toMatchObject({ code: 'INVALID_TEMPLATE_RESPONSE' })
  })
  it('rejects short or oversized bodies rather than generating a partial file', async () => {
    await expect(fetchAssetImportTemplate({ transport: vi.fn().mockResolvedValue(new Response(new Uint8Array(content), { status: 206, headers: headers() })) })).rejects.toBeInstanceOf(ApiError)
    for (const bytes of [content.slice(0, -1), new Uint8Array(spec.size_bytes + 1)]) await expect(fetchAssetImportTemplate({ transport: vi.fn().mockResolvedValue(response(bytes)) })).rejects.toBeInstanceOf(ApiError)
  })
  it('cancels an over-limit stream without consuming unbounded extra chunks', async () => {
    const cancel = vi.fn(); const stream = new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(spec.size_bytes + 1)) }, cancel })
    await expect(fetchAssetImportTemplate({ transport: vi.fn().mockResolvedValue(new Response(stream, { headers: headers() })) })).rejects.toBeInstanceOf(ApiError); expect(cancel).toHaveBeenCalledTimes(1)
  })
  it('does not download after context invalidation or cancellation', async () => {
    const transport = vi.fn(); await expect(fetchAssetImportTemplate({ active: () => false, transport })).rejects.toMatchObject({ name: 'AbortError' }); expect(transport).not.toHaveBeenCalled()
    let active = true; transport.mockImplementation(async () => { active = false; return response() })
    await expect(fetchAssetImportTemplate({ active: () => active, transport })).rejects.toMatchObject({ name: 'AbortError' })
    const controller = new AbortController(); controller.abort(); await expect(fetchAssetImportTemplate({ signal: controller.signal, transport })).rejects.toMatchObject({ name: 'AbortError' })
  })
  it('reports authorization failure safely without returning a JSON error as an XLSX', async () => {
    const onUnauthorized = vi.fn(); await expect(fetchAssetImportTemplate({ transport: vi.fn().mockResolvedValue(new Response('not a workbook', { status: 401 })), onUnauthorized })).rejects.toMatchObject({ status: 401, code: 'SESSION_EXPIRED' }); expect(onUnauthorized).toHaveBeenCalledTimes(1)
    await expect(fetchAssetImportTemplate({ transport: vi.fn().mockResolvedValue(new Response('private provider message', { status: 503 })) })).rejects.toMatchObject({ status: 503, message: '标准模板下载未成功，请稍后重试' })
  })
})
