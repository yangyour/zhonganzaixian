import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { worklogInstant } from './worklogWorkspace'
export interface RecordedDateResult { recorded_date: string | null; days_until: number | null; position: 'UNKNOWN' | 'PAST_DATE' | 'DUE_TODAY' | 'WITHIN_30_DAYS' | 'FUTURE_DATE'; within_7_days: boolean | null; within_30_days: boolean | null; within_90_days: boolean | null; in_reference_quarter: boolean | null }
export interface AssetDateAssessment { asset_id: string; tenant_id: string; asset_version: number; metadata_version: number | null; observed_at: string; as_of_date: string; timezone: 'Asia/Shanghai'; policy_version: 'contract-m3-10-date-v1'; scope: 'RECORDED_DATE_ASSESSMENT_ONLY'; source: 'postgresql'; recorded_dates_source: 'MANUAL_ASSET_METADATA'; verification_status: 'SELF_REPORTED' | null; coverage_verified: false; warranty_expiry: RecordedDateResult; overdue_service_date: RecordedDateResult; warranty_start: null; warranty_provider: null; hazard_lookup_status: 'NOT_IMPLEMENTED'; notification_lookup_status: 'NOT_IMPLEMENTED'; actions_this_request: { hazard_creation: 'NOT_REQUESTED'; notification: 'NOT_REQUESTED'; asset_updated: false } }
export interface AssetDateContext { assetId: string; tenantId: string; actorId: string; permissionVersion: number; minimumAssetVersion: number }
export interface AssetDateState { current: AssetDateAssessment | null; busy: boolean; error: unknown }
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const copy = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 1
const bad = () => new ApiError(200, 'INVALID_ASSET_DATE_RESPONSE', '记录日期、租户或版本回读异常，旧结果已清除', '—')
function date(v: unknown): number { if (typeof v !== 'string' || !/^(?!0000)\d{4}-\d{2}-\d{2}$/.test(v)) throw bad(); const value = Date.parse(v + 'T00:00:00Z'); if (!Number.isFinite(value) || new Date(value).toISOString().slice(0,10) !== v) throw bad(); return value }
function readDate(v: unknown, asOf: string): RecordedDateResult {
  const keys = ['recorded_date', 'days_until', 'position', 'within_7_days', 'within_30_days', 'within_90_days', 'in_reference_quarter']
  if (!exact(v, keys)) throw bad()
  if (v.recorded_date === null) { if (v.position !== 'UNKNOWN' || keys.filter(k => k !== 'position').some(k => v[k] !== null)) throw bad(); return copy(v) as unknown as RecordedDateResult }
  const days = (date(v.recorded_date) - date(asOf)) / 86400000; const position = days < 0 ? 'PAST_DATE' : days === 0 ? 'DUE_TODAY' : days <= 30 ? 'WITHIN_30_DAYS' : 'FUTURE_DATE'
  if (v.days_until !== days || v.position !== position) throw bad()
  for (const n of [7,30,90]) if (v['within_' + n + '_days'] !== (days >= 0 && days <= n)) throw bad()
  const recorded = v.recorded_date as string; const quarter = recorded.slice(0,4) === asOf.slice(0,4) && Math.floor((Number(recorded.slice(5,7))-1)/3) === Math.floor((Number(asOf.slice(5,7))-1)/3)
  if (v.in_reference_quarter !== quarter) throw bad(); return copy(v) as unknown as RecordedDateResult
}
export function readServerReferenceDate(observedAt: unknown, referenceDate: unknown): string {
  date(referenceDate); if (typeof observedAt !== 'string' || !observedAt.endsWith('Z')) throw bad()
  let observed: number; try { observed = worklogInstant(observedAt) } catch { throw bad() }
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA',{ timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(observed)).filter(p => p.type !== 'literal').map(p => [p.type,p.value]))
  if (`${parts.year!.padStart(4,'0')}-${parts.month}-${parts.day}` !== referenceDate) throw bad()
  return referenceDate as string
}
export function readAssetDateAssessment(v: unknown, scope: AssetDateContext): AssetDateAssessment {
  const keys = ['asset_id','tenant_id','asset_version','metadata_version','observed_at','as_of_date','timezone','policy_version','scope','source','recorded_dates_source','verification_status','coverage_verified','warranty_expiry','overdue_service_date','warranty_start','warranty_provider','hazard_lookup_status','notification_lookup_status','actions_this_request']
  if (!exact(v, keys) || v.asset_id !== scope.assetId || v.tenant_id !== scope.tenantId || !positive(v.asset_version) || v.asset_version < scope.minimumAssetVersion || v.metadata_version !== null && (!positive(v.metadata_version) || v.metadata_version < 2 || v.metadata_version > v.asset_version) || v.verification_status !== (v.metadata_version === null ? null : 'SELF_REPORTED') || v.timezone !== 'Asia/Shanghai' || v.policy_version !== 'contract-m3-10-date-v1' || v.scope !== 'RECORDED_DATE_ASSESSMENT_ONLY' || v.source !== 'postgresql' || v.recorded_dates_source !== 'MANUAL_ASSET_METADATA' || v.coverage_verified !== false || v.warranty_start !== null || v.warranty_provider !== null || v.hazard_lookup_status !== 'NOT_IMPLEMENTED' || v.notification_lookup_status !== 'NOT_IMPLEMENTED' || !exact(v.actions_this_request, ['hazard_creation','notification','asset_updated']) || v.actions_this_request.hazard_creation !== 'NOT_REQUESTED' || v.actions_this_request.notification !== 'NOT_REQUESTED' || v.actions_this_request.asset_updated !== false) throw bad()
  readServerReferenceDate(v.observed_at,v.as_of_date)
  const warranty = readDate(v.warranty_expiry,v.as_of_date as string), overdue = readDate(v.overdue_service_date,v.as_of_date as string)
  if (v.metadata_version === null && (warranty.recorded_date !== null || overdue.recorded_date !== null)) throw bad()
  return { ...copy(v), warranty_expiry: warranty, overdue_service_date: overdue } as unknown as AssetDateAssessment
}
export function createAssetDateReader(client: Pick<ApiClient,'request'>, notify: (state: AssetDateState) => void) {
  let scope: AssetDateContext | null = null, generation = 0, controller: AbortController | null = null, disposed = false
  let state: AssetDateState = { current: null, busy: false, error: null }
  const snapshot = () => ({ ...state, current: state.current ? copy(state.current) : null }); const emit = () => notify(snapshot())
  const clear = () => { generation++; controller?.abort(); controller = null; state = { current: null, busy: false, error: null } }
  return {
    state: snapshot,
    reset(value: AssetDateContext | null) { clear(); scope = !disposed && value && uuid.test(value.assetId) && uuid.test(value.tenantId) && uuid.test(value.actorId) && positive(value.permissionVersion) && positive(value.minimumAssetVersion) ? { ...value } : null; emit() },
    async refresh() {
      if (disposed || !scope || state.busy) return false
      clear(); const current = generation, context = { ...scope }; controller = new AbortController(); state.busy = true; emit()
      try { const raw = await client.request(`/assets/${context.assetId}/manual-metadata/date-assessment`,{ signal: controller.signal }); if (current !== generation || disposed) return false; state.current = readAssetDateAssessment(raw,context); scope = { ...context, minimumAssetVersion: state.current.asset_version }; return true }
      catch (error) { if (current === generation && !disposed && !isCancelled(error)) state.error = error; return false }
      finally { if (current === generation && !disposed) { state.busy = false; controller = null; emit() } }
    },
    dispose() { clear(); scope = null; disposed = true; emit() },
  }
}
