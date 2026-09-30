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

/** Browser session state stays in memory; business data is stored by the API. Invalidation rejects late responses. */
export class ApiClient {
  private controllers = new Set<AbortController>()
  private generation = 0
  private csrfToken: string | null = null
  onUnauthorized: () => void = () => undefined
  constructor(private transport: typeof fetch = (...args) => fetch(...args), private base = '/api/v1') {}
  setCsrfToken(value: string | null) { this.csrfToken = value }
  invalidateContext() {
    this.generation += 1
    for (const controller of this.controllers) controller.abort()
    this.controllers.clear()
    return this.generation
  }
  async request<T>(path: string, options: { method?: string; body?: unknown; signal?: AbortSignal; idempotencyKey?: string } = {}): Promise<T> {
    const generation = this.generation
    const controller = new AbortController()
    this.controllers.add(controller)
    const cancel = () => controller.abort()
    options.signal?.addEventListener('abort', cancel, { once: true })
    if (options.signal?.aborted) controller.abort()
    const method = options.method || 'GET'
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (options.body !== undefined) headers['Content-Type'] = 'application/json'
    if (!['GET', 'HEAD'].includes(method)) {
      headers['Idempotency-Key'] = options.idempotencyKey || crypto.randomUUID()
      if (this.csrfToken) headers['X-CSRF-Token'] = this.csrfToken
    }
    try {
      const response = await this.transport(this.base + path, {
        method, headers, credentials: 'same-origin', signal: controller.signal,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      })
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      const raw = await response.text()
      if (generation !== this.generation || controller.signal.aborted) throw new StaleRequestError()
      let envelope: Envelope<T> & FailureEnvelope
      try { envelope = raw ? JSON.parse(raw) : {} }
      catch { throw new ApiError(response.status, 'INVALID_RESPONSE', '服务端返回格式异常', response.headers.get('X-Trace-Id') || '—') }
      const traceId = envelope.trace_id || response.headers.get('X-Trace-Id') || '—'
      if (!response.ok) {
        throw new ApiError(response.status, envelope.error?.code || `HTTP_${response.status}`, envelope.error?.message || '请求未完成，请稍后重试', traceId, envelope.error?.details || [])
      }
      if (!Object.prototype.hasOwnProperty.call(envelope, 'data')) throw new ApiError(response.status, 'INVALID_RESPONSE', '接口响应缺少 data', traceId)
      return envelope.data
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
