import spec from '../../../../contracts/asset-import-template.json'
import { ApiError, StaleRequestError } from '../api/client'

export const assetImportTemplateSpec = Object.freeze({ ...spec })
const invalid = () => new ApiError(200, 'INVALID_TEMPLATE_RESPONSE', '模板下载的类型、版本、长度或摘要不符，未生成下载文件', '—')
async function bounded(response: Response, maximum: number): Promise<Uint8Array<ArrayBuffer>> {
  if (!response.body) throw invalid()
  const reader = response.body.getReader(), chunks: Uint8Array[] = []; let size = 0
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > maximum) { await reader.cancel(); throw invalid() }
      chunks.push(value)
    }
  } finally { reader.releaseLock() }
  const bytes = new Uint8Array(size); let offset = 0
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length }
  return bytes
}

/** Fixed same-origin GET only; verify complete bytes before offering a browser download. */
export async function fetchAssetImportTemplate(options: { signal?: AbortSignal; active?: () => boolean; transport?: typeof fetch; onUnauthorized?: () => void } = {}) {
  const active = () => !options.signal?.aborted && (options.active?.() ?? true)
  if (!active()) throw new StaleRequestError()
  const response = await (options.transport ?? fetch)('/api/v1/assets/import-template', { method: 'GET', credentials: 'same-origin', redirect: 'error', headers: { Accept: spec.media_type }, signal: options.signal })
  if (!active()) { await response.body?.cancel(); throw new StaleRequestError() }
  if (response.status !== 200) {
    await response.body?.cancel()
    if (response.status === 401) options.onUnauthorized?.()
    throw new ApiError(response.status, response.status === 401 ? 'SESSION_EXPIRED' : 'TEMPLATE_DOWNLOAD_FAILED', response.status === 401 ? '会话已失效，请重新登录后下载模板' : response.status === 403 ? '当前身份没有模板下载权限' : '标准模板下载未成功，请稍后重试', response.headers.get('X-Trace-Id') || '—')
  }
  const length = response.headers.get('Content-Length')
  if (response.headers.get('Content-Type')?.split(';')[0]?.trim() !== spec.media_type || response.headers.get('X-MOP-Template-SHA256') !== spec.sha256 || response.headers.get('X-MOP-Template-Schema') !== spec.schema_version || response.headers.get('X-MOP-Business-Status') !== 'CONFIG_PENDING' || response.headers.get('Content-Disposition') !== `attachment; filename="${spec.filename}"` || length !== String(spec.size_bytes)) {
    await response.body?.cancel(); throw invalid()
  }
  const bytes = await bounded(response, spec.size_bytes)
  if (!active()) throw new StaleRequestError()
  if (bytes.length !== spec.size_bytes) throw invalid()
  const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))).map(value => value.toString(16).padStart(2, '0')).join('')
  if (!active()) throw new StaleRequestError()
  if (digest !== spec.sha256) throw invalid()
  return { blob: new Blob([bytes], { type: spec.media_type }), filename: spec.filename, sha256: digest }
}
