import { createRequestId } from '../utils/browserCrypto'
import { observeHttpClock, type HttpClockObservation } from '../utils/httpResponseClock'

export interface ClockedResponse<T> { data: T; clock: HttpClockObservation }
export interface RequestOptions { method?: string; body?: unknown; signal?: AbortSignal; idempotencyKey?: string; timeoutMs?: number }
export interface ErrorDetail { [key: string]: unknown }
interface Envelope<T> { data: T; trace_id: string }
interface FailureEnvelope { error?: { code?: string; message?: string; details?: ErrorDetail[] }; trace_id?: string }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public traceId: string, public details: ErrorDetail[] = []) {
    super(message); this.name = 'ApiError'
  }
}
export class StaleRequestError extends Error {
  constructor() { super('请求所属的租户或授权上下文已变化'); this.name = 'AbortError' }
}
export const isCancelled = (error: unknown) => error instanceof Error && error.name === 'AbortError'

// A transport or body reader that ignores abort must not leave the UI waiting forever.
function cancellable<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const cancel = () => { cleanup(); reject(new StaleRequestError()) }
    const cleanup = () => signal.removeEventListener('abort', cancel)
    signal.addEventListener('abort', cancel, { once: true })
    promise.then(value => { cleanup(); resolve(value) }, cause => { cleanup(); reject(cause) })
    if (signal.aborted) cancel()
  })
}
function failure(response: Response, candidate: unknown): ApiError {
  const object = candidate && typeof candidate === 'object' && !Array.isArray(candidate) ? candidate as FailureEnvelope : {}
  const error = object.error
  return new ApiError(response.status, typeof error?.code === 'string' && error.code ? error.code : `HTTP_${response.status}`,
    typeof error?.message === 'string' && error.message ? error.message : '请求未完成，请稍后重试',
    typeof object.trace_id === 'string' ? object.trace_id : response.headers.get('X-Trace-Id') || '—',
    Array.isArray(error?.details) ? error.details.filter(detail => detail && typeof detail === 'object' && !Array.isArray(detail)) : [])
}

function assertApiPath(path: string) {
  let pathname: string
  try { pathname = decodeURIComponent(path.split('?')[0]!) } catch { throw new ApiError(0, 'INVALID_API_PATH', '接口路径编码异常', '—') }
  if (!/^\/[a-zA-Z0-9]/.test(path) || /^\/api\/v1(?:\/|$)/i.test(pathname) || /[\\#\u0000-\u0020\u007f]/.test(path) || /[\\?#%\u0000-\u0020\u007f]/.test(pathname) || pathname.includes('//') || pathname.split('/').some(part => part === '.' || part === '..')) throw new ApiError(0, 'INVALID_API_PATH', '仅允许本平台的相对接口路径', '—')
}

/** Browser session state stays in memory; business data is stored by the API. Invalidation rejects late responses. */
export class ApiClient {
  private controllers = new Set<AbortController>()
  private generation = 0
  private csrfToken: string | null = null
  onUnauthorized: () => void = () => undefined
  constructor(private transport: typeof fetch = (...args) => fetch(...args), private base = '/api/v1', private monotonicNow: () => number = () => performance.now()) {}
  setCsrfToken(value: string | null) { this.csrfToken = value }
  invalidateContext() {
    this.generation += 1
    for (const controller of this.controllers) controller.abort()
    this.controllers.clear()
    return this.generation
  }
  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const generation = this.generation, signal = options.signal, result = await this.requestResult<T>(path, { ...options, signal })
    if (generation !== this.generation || signal?.aborted) throw new StaleRequestError()
    return result.data
  }
  /** GET only. Date is response metadata, never sampling success or a verified synchronization clock. */
  async requestWithClock<T>(path: string, options: { signal?: AbortSignal } = {}): Promise<ClockedResponse<T>> {
    const generation = this.generation, signal = options.signal, result = await this.requestResult<T>(path, { method: 'GET', signal }, true)
    if (generation !== this.generation || signal?.aborted) throw new StaleRequestError()
    return result
  }
  private clockNow(): number { try { return this.monotonicNow() } catch { return NaN } }
  private async requestResult<T>(path: string, options: RequestOptions, observeClock = false): Promise<ClockedResponse<T>> {
    assertApiPath(path)
    const generation = this.generation
    const controller = new AbortController()
    this.controllers.add(controller)
    const signal = options.signal
    const cancel = () => controller.abort()
    signal?.addEventListener('abort', cancel, { once: true })
    if (signal?.aborted) controller.abort()
    let timedOut = false
    const timeoutMs = options.timeoutMs ?? (options.body instanceof FormData ? 120000 : 30000)
    const timeout = setTimeout(() => { timedOut = true; controller.abort() }, Number.isFinite(timeoutMs) && timeoutMs > 0 ? timeoutMs : 30000)
    try {
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      const method = (options.method || 'GET').toUpperCase()
      const headers: Record<string, string> = { Accept: 'application/json' }
      const multipart = options.body instanceof FormData
      if (options.body !== undefined && !multipart) headers['Content-Type'] = 'application/json'
      if (!['GET', 'HEAD'].includes(method)) {
        headers['Idempotency-Key'] = options.idempotencyKey || createRequestId()
        if (this.csrfToken) headers['X-CSRF-Token'] = this.csrfToken
      }
      const started = observeClock ? this.clockNow() : 0
      const body = options.body === undefined ? undefined : multipart ? options.body as FormData : JSON.stringify(options.body)
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      const response = await cancellable(this.transport(this.base + path, {
        method, headers, credentials: 'same-origin', redirect: 'error', signal: controller.signal,
        body,
      }), controller.signal)
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      const raw = await cancellable(response.text(), controller.signal)
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      let envelope: Envelope<T> & FailureEnvelope
      try { envelope = raw ? JSON.parse(raw) : {} }
      catch { if (!response.ok) throw failure(response, null); throw new ApiError(response.status, 'INVALID_RESPONSE', '服务端返回格式异常', response.headers.get('X-Trace-Id') || '—') }
      if (!response.ok) throw failure(response, envelope)
      if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)) throw new ApiError(response.status, 'INVALID_RESPONSE', '接口响应信封异常', response.headers.get('X-Trace-Id') || '—')
      const traceId = typeof envelope.trace_id === 'string' ? envelope.trace_id : response.headers.get('X-Trace-Id') || '—'
      if (envelope.error) throw new ApiError(response.status, 'INVALID_RESPONSE', '响应同时包含错误，暂时无法确认结果', traceId)
      if (!Object.prototype.hasOwnProperty.call(envelope, 'data')) throw new ApiError(response.status, 'INVALID_RESPONSE', '接口响应缺少 data', traceId)
      const clock: HttpClockObservation = observeClock ? observeHttpClock(response.headers, started, this.clockNow()) : Object.freeze({ status: 'UNKNOWN', reason: 'NOT_REQUESTED', clock_accuracy_verified: false })
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      return { data: envelope.data, clock }
    } catch (error) {
      if (timedOut && generation === this.generation && !signal?.aborted) throw new ApiError(0, 'REQUEST_TIMEOUT', '等待响应超时，操作结果尚未确认', '—')
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      if (error instanceof ApiError) { if (error.status === 401) this.onUnauthorized(); throw error }
      if (isCancelled(error)) throw error
      throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务，请检查网络后重试', '—')
    } finally {
      clearTimeout(timeout)
      this.controllers.delete(controller)
      signal?.removeEventListener('abort', cancel)
    }
  }
  async requestBlob(path: string, options: { signal?: AbortSignal; timeoutMs?: number } = {}): Promise<Blob> {
    assertApiPath(path)
    const generation = this.generation, controller = new AbortController()
    this.controllers.add(controller)
    const signal = options.signal
    const cancel = () => controller.abort()
    signal?.addEventListener('abort', cancel, { once: true })
    if (signal?.aborted) controller.abort()
    let timedOut = false
    const timeoutMs = options.timeoutMs ?? 120000
    const timeout = setTimeout(() => { timedOut = true; controller.abort() }, Number.isFinite(timeoutMs) && timeoutMs > 0 ? timeoutMs : 120000)
    try {
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      const response = await cancellable(this.transport(this.base + path, { headers: { Accept: 'image/jpeg, image/png' }, credentials: 'same-origin', redirect: 'error', signal: controller.signal }), controller.signal)
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      if (!response.ok) {
        let failure: FailureEnvelope = {}
        try { const candidate: unknown = await cancellable(response.json(), controller.signal); if (candidate && typeof candidate === 'object' && !Array.isArray(candidate)) failure = candidate as FailureEnvelope } catch { /* Retain the HTTP failure when error content is malformed. */ }
        if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
        throw new ApiError(response.status, failure.error?.code || `HTTP_${response.status}`, failure.error?.message || '图片读取失败，请重新申请查看', failure.trace_id || response.headers.get('X-Trace-Id') || '—', failure.error?.details || [])
      }
      const result = await cancellable(response.blob(), controller.signal)
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      return result
    } catch (cause) {
      if (timedOut && generation === this.generation && !signal?.aborted) throw new ApiError(0, 'REQUEST_TIMEOUT', '图片读取超时，请重新申请查看', '—')
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      if (cause instanceof ApiError) { if (cause.status === 401) this.onUnauthorized(); throw cause }
      if (isCancelled(cause)) throw cause
      throw new ApiError(0, 'NETWORK_ERROR', '无法读取图片，请检查连接后重新申请查看', '—')
    } finally { clearTimeout(timeout); this.controllers.delete(controller); signal?.removeEventListener('abort', cancel) }
  }
  async pollOperation<T extends { status: string }>(id: string, options: { signal?: AbortSignal; intervalMs?: number; maxAttempts?: number; onUpdate?: (operation: T) => void } = {}): Promise<T> {
    const generation = this.generation, signal = options.signal
    const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])
    for (let attempt = 0; attempt < (options.maxAttempts ?? 120); attempt++) {
      if (generation !== this.generation || signal?.aborted) throw new StaleRequestError()
      const operation = await this.request<T>(`/operations/${encodeURIComponent(id)}`, { signal })
      options.onUpdate?.(operation)
      if (generation !== this.generation || signal?.aborted) throw new StaleRequestError()
      if (terminal.has(operation.status.toUpperCase())) return operation
      const delay = new AbortController()
      this.controllers.add(delay)
      const cancel = () => delay.abort()
      signal?.addEventListener('abort', cancel, { once: true })
      let timer: ReturnType<typeof setTimeout> | undefined
      let rejectDelay: (() => void) | undefined
      try {
        await new Promise<void>((resolve, reject) => {
          rejectDelay = () => reject(new StaleRequestError())
          delay.signal.addEventListener('abort', rejectDelay, { once: true })
          if (generation !== this.generation || signal?.aborted) { delay.abort(); return }
          timer = setTimeout(resolve, options.intervalMs ?? 1500)
        })
      } finally {
        if (timer !== undefined) clearTimeout(timer)
        if (rejectDelay) delay.signal.removeEventListener('abort', rejectDelay)
        signal?.removeEventListener('abort', cancel)
        this.controllers.delete(delay)
      }
    }
    if (generation !== this.generation || signal?.aborted) throw new StaleRequestError()
    throw new ApiError(408, 'OPERATION_TIMEOUT', '操作仍未完成，请稍后重新查询；未判定为成功', '—')
  }
}
export const api = new ApiClient()
