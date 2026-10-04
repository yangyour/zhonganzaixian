import { observeHttpClock, type HttpClockObservation } from '../utils/httpResponseClock'

export interface ClockedResponse<T> { data: T; clock: HttpClockObservation }
export interface RequestOptions { method?: string; body?: unknown; signal?: AbortSignal; idempotencyKey?: string }
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
    const generation = this.generation, result = await this.requestResult<T>(path, options)
    if (generation !== this.generation || options.signal?.aborted) throw new StaleRequestError()
    return result.data
  }
  /** GET only. Date is response metadata, never sampling success or a verified synchronization clock. */
  async requestWithClock<T>(path: string, options: { signal?: AbortSignal } = {}): Promise<ClockedResponse<T>> {
    const generation = this.generation, result = await this.requestResult<T>(path, { method: 'GET', signal: options.signal }, true)
    if (generation !== this.generation || options.signal?.aborted) throw new StaleRequestError()
    return result
  }
  private clockNow(): number { try { return this.monotonicNow() } catch { return NaN } }
  private async requestResult<T>(path: string, options: RequestOptions, observeClock = false): Promise<ClockedResponse<T>> {
    assertApiPath(path)
    const generation = this.generation
    const controller = new AbortController()
    this.controllers.add(controller)
    const cancel = () => controller.abort()
    options.signal?.addEventListener('abort', cancel, { once: true })
    if (options.signal?.aborted) controller.abort()
    const method = options.method || 'GET'
    const headers: Record<string, string> = { Accept: 'application/json' }
    const multipart = options.body instanceof FormData
    if (options.body !== undefined && !multipart) headers['Content-Type'] = 'application/json'
    if (!['GET', 'HEAD'].includes(method)) {
      headers['Idempotency-Key'] = options.idempotencyKey || crypto.randomUUID()
      if (this.csrfToken) headers['X-CSRF-Token'] = this.csrfToken
    }
    const started = observeClock ? this.clockNow() : 0
    try {
      const response = await this.transport(this.base + path, {
        method, headers, credentials: 'same-origin', redirect: 'error', signal: controller.signal,
        body: options.body === undefined ? undefined : multipart ? options.body as FormData : JSON.stringify(options.body),
      })
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      const raw = await response.text()
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      let envelope: Envelope<T> & FailureEnvelope
      try { envelope = raw ? JSON.parse(raw) : {} }
      catch { throw new ApiError(response.status, 'INVALID_RESPONSE', '服务端返回格式异常', response.headers.get('X-Trace-Id') || '—') }
      if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)) throw new ApiError(response.status, 'INVALID_RESPONSE', '接口响应信封异常', response.headers.get('X-Trace-Id') || '—')
      const traceId = envelope.trace_id || response.headers.get('X-Trace-Id') || '—'
      if (!response.ok) {
        throw new ApiError(response.status, envelope.error?.code || `HTTP_${response.status}`, envelope.error?.message || '请求未完成，请稍后重试', traceId, envelope.error?.details || [])
      }
      if (!Object.prototype.hasOwnProperty.call(envelope, 'data')) throw new ApiError(response.status, 'INVALID_RESPONSE', '接口响应缺少 data', traceId)
      const clock: HttpClockObservation = observeClock ? observeHttpClock(response.headers, started, this.clockNow()) : Object.freeze({ status: 'UNKNOWN', reason: 'NOT_REQUESTED', clock_accuracy_verified: false })
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      return { data: envelope.data, clock }
    } catch (error) {
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      if (error instanceof ApiError) { if (error.status === 401) this.onUnauthorized(); throw error }
      if (isCancelled(error)) throw error
      throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务，请检查 API 是否启动后重试', '—')
    } finally {
      this.controllers.delete(controller)
      options.signal?.removeEventListener('abort', cancel)
    }
  }
  async requestBlob(path: string, options: { signal?: AbortSignal } = {}): Promise<Blob> {
    assertApiPath(path)
    const generation = this.generation, controller = new AbortController()
    this.controllers.add(controller)
    const cancel = () => controller.abort()
    options.signal?.addEventListener('abort', cancel, { once: true })
    if (options.signal?.aborted) controller.abort()
    try {
      const response = await this.transport(this.base + path, { headers: { Accept: 'image/jpeg, image/png' }, credentials: 'same-origin', redirect: 'error', signal: controller.signal })
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      if (!response.ok) {
        let failure: FailureEnvelope = {}
        try { const candidate: unknown = await response.json(); if (candidate && typeof candidate === 'object' && !Array.isArray(candidate)) failure = candidate as FailureEnvelope } catch { /* Retain the HTTP failure when error content is malformed. */ }
        if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
        throw new ApiError(response.status, failure.error?.code || `HTTP_${response.status}`, failure.error?.message || '图片读取失败，请重新申请查看', failure.trace_id || response.headers.get('X-Trace-Id') || '—', failure.error?.details || [])
      }
      const result = await response.blob()
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      return result
    } catch (cause) {
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      if (cause instanceof ApiError) { if (cause.status === 401) this.onUnauthorized(); throw cause }
      if (isCancelled(cause)) throw cause
      throw new ApiError(0, 'NETWORK_ERROR', '无法读取图片，请检查连接后重新申请查看', '—')
    } finally { this.controllers.delete(controller); options.signal?.removeEventListener('abort', cancel) }
  }
  async pollOperation<T extends { status: string }>(id: string, options: { signal?: AbortSignal; intervalMs?: number; maxAttempts?: number; onUpdate?: (operation: T) => void } = {}): Promise<T> {
    const generation = this.generation
    const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])
    for (let attempt = 0; attempt < (options.maxAttempts ?? 120); attempt++) {
      if (generation !== this.generation || options.signal?.aborted) throw new StaleRequestError()
      const operation = await this.request<T>(`/operations/${encodeURIComponent(id)}`, { signal: options.signal })
      options.onUpdate?.(operation)
      if (terminal.has(operation.status.toUpperCase())) return operation
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => { options.signal?.removeEventListener('abort', cancel); resolve() }, options.intervalMs ?? 1500)
        const cancel = () => { clearTimeout(timer); reject(new StaleRequestError()) }
        options.signal?.addEventListener('abort', cancel, { once: true })
      })
    }
    throw new ApiError(408, 'OPERATION_TIMEOUT', '操作仍未完成，请稍后重新查询；未判定为成功', '—')
  }
}
export const api = new ApiClient()
