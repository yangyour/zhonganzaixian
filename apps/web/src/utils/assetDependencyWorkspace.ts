import { ApiError, isCancelled, type ApiClient } from '../api/client'

export interface ProposedDependency { parent_id: string; child_id: string; proposed_type: string | null; proposed_suppression_scope: string | null; proposed_enabled: boolean | null }
export interface DependencyDraft {
  asset_id: string; tenant_id: string; version: number; version_kind: 'tenant_dependency_graph'; updated_at: string | null; complete: true
  proposed_edges: ProposedDependency[]; lifecycle_status: 'DRAFT'; decision_status: 'pending'; configuration_status: 'CONFIG_PENDING'
  effective_rules: null; monitoring_applied: false; question_ids: ['Q20']; source: 'postgresql'
  technical_limits: { max_additions: 100; max_graph_edges: 10000; max_body_bytes: 131072 }
}
export interface DependencyContext { tenantId: string; assetId: string; actorId: string; permissionVersion: number; canWrite: boolean }
interface Attempt { key: string; payload: string; baseline: DependencyDraft; state: 'uncertain' | 'accepted' | 'rejected' }
export interface DependencyState { draft: DependencyDraft | null; receipt: DependencyDraft | null; attempt: Attempt | null; busy: 'read' | 'write' | null; error: unknown }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const object = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value))
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => object(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
const edgeKeys = ['parent_id', 'child_id', 'proposed_type', 'proposed_suppression_scope', 'proposed_enabled']
function nullableText(value: unknown, max: number) { return value === null || typeof value === 'string' && Boolean(value.trim()) && Array.from(value).length <= max && !/[\u0000-\u001f\u007f-\u009f]/.test(value) && !Array.from(value).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c)) }
const invalid = () => new ApiError(200, 'INVALID_DEPENDENCY_RESPONSE', '依赖草稿回读与租户、版本或未生效约定不符，已清除当前显示', '—')
const pair = (e: ProposedDependency) => `${e.parent_id}/${e.child_id}`
const signature = (e: ProposedDependency) => JSON.stringify([e.parent_id, e.child_id, e.proposed_type, e.proposed_suppression_scope, e.proposed_enabled])
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
export function validateDependencyEdge(value: unknown): asserts value is ProposedDependency {
  if (!exact(value, edgeKeys) || typeof value.parent_id !== 'string' || !uuid.test(value.parent_id) || typeof value.child_id !== 'string' || !uuid.test(value.child_id) || value.parent_id === value.child_id || !nullableText(value.proposed_type, 64) || !nullableText(value.proposed_suppression_scope, 128) || value.proposed_enabled !== null && typeof value.proposed_enabled !== 'boolean') throw invalid()
}
function timestamp(value: unknown) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(value) || Number.isNaN(Date.parse(value))) return false
  const normalized = value.includes('.') ? value.replace(/\.(\d{1,3})Z$/, (_, digits: string) => `.${digits.padEnd(3, '0')}Z`) : value.replace(/Z$/, '.000Z')
  return new Date(value).toISOString() === normalized
}
export function readDependencyDraft(value: unknown, context: DependencyContext): DependencyDraft {
  if (![context.tenantId, context.assetId, context.actorId].every(id => uuid.test(id))) throw invalid()
  if (!exact(value, ['asset_id', 'tenant_id', 'version', 'version_kind', 'updated_at', 'complete', 'proposed_edges', 'lifecycle_status', 'decision_status', 'configuration_status', 'effective_rules', 'monitoring_applied', 'question_ids', 'source', 'technical_limits'])
    || value.asset_id !== context.assetId || value.tenant_id !== context.tenantId || !Number.isSafeInteger(value.version) || Number(value.version) < 0 || value.version_kind !== 'tenant_dependency_graph' || value.complete !== true
    || value.lifecycle_status !== 'DRAFT' || value.decision_status !== 'pending' || value.configuration_status !== 'CONFIG_PENDING' || value.effective_rules !== null || value.monitoring_applied !== false || value.source !== 'postgresql'
    || !Array.isArray(value.question_ids) || value.question_ids.length !== 1 || value.question_ids[0] !== 'Q20'
    || !exact(value.technical_limits, ['max_additions', 'max_graph_edges', 'max_body_bytes']) || value.technical_limits.max_additions !== 100 || value.technical_limits.max_graph_edges !== 10000 || value.technical_limits.max_body_bytes !== 131072
    || !Array.isArray(value.proposed_edges) || value.proposed_edges.length > 10000) throw invalid()
  if (value.version === 0 ? value.updated_at !== null || value.proposed_edges.length !== 0 : !timestamp(value.updated_at) || !value.proposed_edges.length) throw invalid()
  const pairs = new Set<string>(), outgoing = new Map<string, string[]>(), indegree = new Map<string, number>()
  for (const edge of value.proposed_edges) {
    validateDependencyEdge(edge)
    if (pairs.has(pair(edge))) throw invalid()
    pairs.add(pair(edge)); indegree.set(edge.parent_id, indegree.get(edge.parent_id) || 0); indegree.set(edge.child_id, (indegree.get(edge.child_id) || 0) + 1)
    const children = outgoing.get(edge.parent_id) || []; children.push(edge.child_id); outgoing.set(edge.parent_id, children)
  }
  const queue = [...indegree].filter(([, count]) => count === 0).map(([id]) => id)
  for (let i = 0; i < queue.length; i++) for (const child of outgoing.get(queue[i]!) || []) { const count = indegree.get(child)! - 1; indegree.set(child, count); if (!count) queue.push(child) }
  if (queue.length !== indegree.size) throw invalid()
  return copy(value) as unknown as DependencyDraft
}
function contains(base: DependencyDraft, result: DependencyDraft) {
  const actual = new Map(result.proposed_edges.map(e => [pair(e), signature(e)]))
  return base.proposed_edges.every(e => actual.get(pair(e)) === signature(e))
}
export function readDependencyReceipt(value: unknown, context: DependencyContext, baseline: DependencyDraft, additions: ProposedDependency[]): DependencyDraft {
  const result = readDependencyDraft(value, context)
  if (result.version !== baseline.version + 1 || result.proposed_edges.length !== baseline.proposed_edges.length + additions.length || !contains(baseline, result) || !additions.every(e => result.proposed_edges.some(saved => signature(saved) === signature(e)))) throw invalid()
  return result
}

export function createDependencyWorkspace(client: Pick<ApiClient, 'request'>, changed: (state: DependencyState) => void, newKey: () => string = () => crypto.randomUUID()) {
  let context: DependencyContext | null = null, epoch = 0, request: AbortController | undefined
  let state: DependencyState = { draft: null, receipt: null, attempt: null, busy: null, error: null }
  const emit = () => changed({ ...state })
  const path = () => `/assets/${context!.assetId}/dependencies`
  const unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: DependencyContext | null) { request?.abort(); ++epoch; const valid = next && [next.tenantId, next.assetId, next.actorId].every(id => uuid.test(id)) && Number.isSafeInteger(next.permissionVersion) && next.permissionVersion >= 1 && typeof next.canWrite === 'boolean'; context = valid ? { ...next! } : null; state = { draft: null, receipt: null, attempt: null, busy: null, error: next && !valid ? invalid() : null }; emit() }
  async function refresh() {
    if (!context || state.busy === 'write') return false
    request?.abort(); const current = ++epoch, scope = { ...context }, controller = new AbortController(); request = controller
    state = { ...state, draft: null, busy: 'read', error: null }; emit()
    try {
      const result = readDependencyDraft(await client.request<unknown>(path(), { signal: controller.signal }), scope)
      if (current !== epoch || controller.signal.aborted) return false
      if (state.receipt && (result.version < state.receipt.version || !contains(state.receipt, result))) throw invalid()
      state = { ...state, draft: result }; return true
    } catch (error) { if (current === epoch && !isCancelled(error)) state = { ...state, error }; return false }
    finally { if (current === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function send(attempt: Attempt) {
    if (!context?.canWrite || state.busy) return false
    const current = ++epoch, scope = { ...context }, controller = new AbortController(); request?.abort(); request = controller
    state = { ...state, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try {
      const body = JSON.parse(attempt.payload) as { expected_version: number; additions: ProposedDependency[] }
      const raw = await client.request<unknown>(path(), { method: 'POST', body, idempotencyKey: attempt.key, signal: controller.signal })
      if (current !== epoch || controller.signal.aborted) return false
      const receipt = readDependencyReceipt(raw, scope, attempt.baseline, body.additions)
      state = { ...state, receipt, draft: receipt, busy: null, attempt: { ...attempt, state: 'accepted' } }; emit()
      await refresh(); return true
    } catch (error) {
      if (current !== epoch) return false
      const uncertain = isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_')
      state = { ...state, error, draft: null, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false
    } finally { if (current === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function submit(edge: ProposedDependency) {
    if (!context?.canWrite || state.busy || unresolved() || !state.draft || state.draft.version >= Number.MAX_SAFE_INTEGER) return false
    validateDependencyEdge(edge)
    if (edge.parent_id !== context.assetId && edge.child_id !== context.assetId) throw invalid()
    const baseline = copy(state.draft), payload = JSON.stringify({ expected_version: baseline.version, additions: [copy(edge)] })
    return send({ key: newKey(), payload, baseline, state: 'uncertain' })
  }
  async function retry() { return state.attempt?.state === 'uncertain' ? send(state.attempt) : false }
  emit()
  return { reset, refresh, submit, retry, unresolved, dispose: () => reset(null), state: () => state }
}
