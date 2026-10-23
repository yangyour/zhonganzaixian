// Development-only synthetic API for the full-shell UI preview (qa/vite.preview.config.ts).
// Every /api/v1 request is answered in-page with explicitly synthetic data. Nothing is sent to
// any server, writes are refused, and the payloads are not integration or acceptance evidence.
import { syntheticRoutes, type SyntheticRequest } from './synthetic-routes'

const original = window.fetch.bind(window)
const role = (() => {
  const requested = new URLSearchParams(location.search).get('preview_role')
  if (requested && ['padmin', 'msp', 'tadmin', 'viewer'].includes(requested)) sessionStorage.setItem('mop-preview-role', requested)
  return (sessionStorage.getItem('mop-preview-role') || 'padmin') as 'padmin' | 'msp' | 'tadmin' | 'viewer'
})()
const failing = new Set((new URLSearchParams(location.search).get('preview_fail') || sessionStorage.getItem('mop-preview-fail') || '').split(',').filter(Boolean))
if (new URLSearchParams(location.search).has('preview_fail')) sessionStorage.setItem('mop-preview-fail', [...failing].join(','))

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', Date: new Date().toUTCString(), 'X-Trace-Id': 'SYNTHETIC-PREVIEW' },
  })
}

window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
  const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, location.origin)
  if (url.origin !== location.origin || !url.pathname.startsWith('/api/')) return original(input, init)
  const method = (init?.method || 'GET').toUpperCase()
  const path = url.pathname.replace(/^\/api\/v1/, '')
  await new Promise(resolve => setTimeout(resolve, 120 + Math.random() * 180))
  if (init?.signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  if (method !== 'GET' && method !== 'HEAD') {
    return json(405, { error: { code: 'SYNTHETIC_PREVIEW_READ_ONLY', message: '合成预览环境：写操作未发送，也不会产生业务结果' }, trace_id: 'SYNTHETIC-PREVIEW' })
  }
  for (const route of syntheticRoutes) {
    const match = route.pattern.exec(path)
    if (!match) continue
    if (failing.has(route.name)) return json(503, { error: { code: 'SYNTHETIC_FAILURE', message: '合成故障：用于检查读取失败状态' }, trace_id: 'SYNTHETIC-PREVIEW' })
    const request: SyntheticRequest = { url, path, params: match.slice(1), role }
    try { return json(200, { data: route.handle(request), trace_id: 'SYNTHETIC-PREVIEW' }) }
    catch (cause) { return json(500, { error: { code: 'SYNTHETIC_HANDLER_ERROR', message: cause instanceof Error ? cause.message : '合成数据生成失败' }, trace_id: 'SYNTHETIC-PREVIEW' }) }
  }
  return json(503, { error: { code: 'SYNTHETIC_NOT_COVERED', message: `合成预览尚未提供 ${path} 的数据` }, trace_id: 'SYNTHETIC-PREVIEW' })
}

const badge = document.createElement('div')
badge.textContent = `合成预览 · 虚构数据 · ${role} · 写操作不发送`
badge.setAttribute('aria-hidden', 'true')
badge.style.cssText = 'position:fixed;left:8px;bottom:8px;z-index:9999;padding:2px 8px;border-radius:4px;background:#5b3a00;color:#fff;font:12px/20px system-ui;opacity:.82;pointer-events:none'
document.addEventListener('DOMContentLoaded', () => document.body.appendChild(badge))
if (document.readyState !== 'loading') document.body.appendChild(badge)
