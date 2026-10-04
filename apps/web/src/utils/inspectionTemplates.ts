import { ApiError } from '../api/client'
import { utcInstantNanos, utcNotBefore } from './utcInstant'

export type InspectionAction = 'CREATE' | 'UPDATE' | 'ARCHIVE' | 'RESTORE'
export type InspectionView = 'CURRENT' | 'ARCHIVED' | 'ALL'
export interface InspectionItem { key: string; section: string; label: string; instruction: string | null }
export interface InspectionDefinition { device_type: string; name: string; items: InspectionItem[] }
export interface InspectionContext { tenantId: string; actorId: string; permissionVersion: number; role: string; canRead: boolean; canWrite: boolean }
interface InspectionFacts { device_type: string; name: string; item_count: number; content_sha256: string; approval_status: 'UNVERIFIED'; publication_status: 'NOT_IMPLEMENTED'; report_published: false; active_scan_performed: false; source: 'postgresql'; record_source: 'EXPLICIT_TEMPLATE_DEFINITION' }
export interface InspectionSummary extends InspectionFacts { id: string; tenant_id: string; created_by: string; version: number; created_at: string; updated_at: string; recorded_by: string; recorded_at: string; action: InspectionAction; archived: boolean }
export interface InspectionDetail extends InspectionSummary { items: InspectionItem[] }
export interface InspectionRevision extends InspectionFacts { version: number; previous_version: number | null; recorded_by: string; recorded_at: string; action: InspectionAction; archived: boolean; items: InspectionItem[] }
export interface InspectionPage { tenant_id: string; items: InspectionSummary[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; view: InspectionView; source: 'postgresql'; scope: 'CURRENT_TENANT_PADMIN'; page_size_limit: 100 }
export interface InspectionHistory { tenant_id: string; template_id: string; items: InspectionRevision[]; has_more: boolean; next_before_version: number | null; source: 'postgresql'; scope: 'CURRENT_TENANT_PADMIN'; page_size_limit: 100 }
export type InspectionPayload = { expected_version: number } & Partial<InspectionDefinition>
export const inspectionMaxVersion = 9_007_199_254_740_990
export const inspectionUuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
export const inspectionBad = () => new ApiError(0, 'INVALID_INSPECTION_TEMPLATE', '巡检模板字段、范围、版本或内容摘要未通过核对；未知写入请保留原请求重试', '—')
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)))
const key = (value: unknown): value is string => typeof value === 'string' && /^[a-z][a-z0-9_-]{0,63}$/.test(value)
const version = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 1 && Number(value) <= inspectionMaxVersion
export function validInspectionText(value: unknown, maximum: number, nullable = false, multiline = false, required = true): boolean {
  if (value === null) return nullable
  return typeof value === 'string' && Array.from(value).length <= maximum && (!required || Boolean(value.trim())) && !(multiline ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/ : /[\u0000-\u001f\u007f-\u009f]/).test(value) && !Array.from(value).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c))
}
export function validInspectionContext(value: InspectionContext | null): value is InspectionContext { return Boolean(value && inspectionUuid(value.tenantId) && inspectionUuid(value.actorId) && Number.isSafeInteger(value.permissionVersion) && value.permissionVersion > 0 && value.role === 'padmin' && value.canRead === true && typeof value.canWrite === 'boolean') }
export function readInspectionDefinition(value: unknown): InspectionDefinition {
  if (!exact(value, ['device_type','name','items']) || !key(value.device_type) || !validInspectionText(value.name, 200) || !Array.isArray(value.items) || value.items.length < 1 || value.items.length > 100) throw inspectionBad()
  const seen = new Set<string>(), items: InspectionItem[] = []
  for (const item of value.items) {
    if (!exact(item, ['key','section','label','instruction']) || !key(item.key) || seen.has(item.key) || !validInspectionText(item.section, 100) || !validInspectionText(item.label, 200) || !validInspectionText(item.instruction, 2000, true, true, false)) throw inspectionBad()
    seen.add(item.key); items.push({ key: item.key, section: item.section as string, label: item.label as string, instruction: item.instruction as string | null })
  }
  return { device_type: value.device_type, name: value.name as string, items }
}
export function inspectionDefinition(value: InspectionDetail | InspectionRevision): InspectionDefinition { return readInspectionDefinition({ device_type: value.device_type, name: value.name, items: value.items }) }
export function readInspectionPayload(value: unknown, action: InspectionAction): InspectionPayload {
  if (!['CREATE','UPDATE','ARCHIVE','RESTORE'].includes(action) || !exact(value, action === 'ARCHIVE' ? ['expected_version'] : ['expected_version','device_type','name','items']) || (action === 'CREATE' ? value.expected_version !== 0 : !version(value.expected_version) || value.expected_version >= inspectionMaxVersion)) throw inspectionBad()
  const payload: InspectionPayload = action === 'ARCHIVE' ? { expected_version: value.expected_version as number } : { expected_version: value.expected_version as number, ...readInspectionDefinition({ device_type: value.device_type, name: value.name, items: value.items }) }
  if (new TextEncoder().encode(JSON.stringify(payload)).length > 1_048_576) throw inspectionBad()
  return payload
}
/** Same ordered UTF-8 framing as Java and PG, not a signature or authorization proof. */
export function inspectionTemplateBytes(value: unknown): Uint8Array {
  const definition = readInspectionDefinition(value), parts: Uint8Array[] = []
  const integer = (n: number) => { const bytes = new Uint8Array(4); new DataView(bytes.buffer).setInt32(0, n, false); return bytes }
  const field = (value: string | null) => { if (value === null) { parts.push(integer(-1)); return } const bytes = new TextEncoder().encode(value); parts.push(integer(bytes.length), bytes) }
  field('MOP_INSPECTION_TEMPLATE_V1'); field(definition.device_type); field(definition.name); parts.push(integer(definition.items.length))
  for (const item of definition.items) { field(item.key); field(item.section); field(item.label); field(item.instruction) }
  const bytes = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0)); let offset = 0
  for (const part of parts) { bytes.set(part, offset); offset += part.length }
  return bytes
}
export async function inspectionFingerprint(value: unknown): Promise<string> {
  const bytes = inspectionTemplateBytes(value), hash = await crypto.subtle.digest('SHA-256', bytes as Uint8Array<ArrayBuffer>)
  return Array.from(new Uint8Array(hash)).map(byte => byte.toString(16).padStart(2, '0')).join('')
}
const factKeys = ['device_type','name','item_count','content_sha256','approval_status','publication_status','report_published','active_scan_performed','source','record_source']
const metadataKeys = ['id','tenant_id','created_by','version','created_at','updated_at','recorded_by','recorded_at','action','archived']
function facts(value: Record<string, unknown>) {
  if (!key(value.device_type) || !validInspectionText(value.name, 200) || !Number.isSafeInteger(value.item_count) || Number(value.item_count) < 1 || Number(value.item_count) > 100 || typeof value.content_sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(value.content_sha256) || value.approval_status !== 'UNVERIFIED' || value.publication_status !== 'NOT_IMPLEMENTED' || value.report_published !== false || value.active_scan_performed !== false || value.source !== 'postgresql' || value.record_source !== 'EXPLICIT_TEMPLATE_DEFINITION') throw inspectionBad()
}
function lifecycle(value: Record<string, unknown>) {
  if (!version(value.version) || typeof value.action !== 'string' || !['CREATE','UPDATE','ARCHIVE','RESTORE'].includes(value.action) || typeof value.archived !== 'boolean' || value.archived !== (value.action === 'ARCHIVE') || (value.version === 1 ? value.action !== 'CREATE' : value.action === 'CREATE') || !inspectionUuid(value.recorded_by) || utcInstantNanos(value.recorded_at) === null) throw inspectionBad()
}
export function readInspectionSummary(value: unknown, context: InspectionContext): InspectionSummary {
  if (!validInspectionContext(context) || !exact(value, [...metadataKeys,...factKeys]) || value.tenant_id !== context.tenantId || !inspectionUuid(value.id) || !inspectionUuid(value.created_by) || !utcNotBefore(value.updated_at, value.created_at) || !utcNotBefore(value.recorded_at, value.created_at)) throw inspectionBad()
  facts(value); lifecycle(value); return structuredClone(value) as unknown as InspectionSummary
}
/** Shape validation is synchronous; every accepted full response additionally verifies the digest below. */
export function readInspectionDetailShape(value: unknown, context: InspectionContext): InspectionDetail {
  if (!exact(value, [...metadataKeys,...factKeys,'items'])) throw inspectionBad()
  const { items, ...summary } = value, row = readInspectionSummary(summary, context), definition = readInspectionDefinition({ device_type: row.device_type, name: row.name, items })
  if (definition.items.length !== row.item_count) throw inspectionBad()
  return { ...row, items: definition.items }
}
export async function readInspectionDetail(value: unknown, context: InspectionContext): Promise<InspectionDetail> {
  const row = readInspectionDetailShape(value, context)
  if (await inspectionFingerprint(inspectionDefinition(row)) !== row.content_sha256) throw inspectionBad()
  return row
}
export function readInspectionPage(value: unknown, context: InspectionContext, view: InspectionView, deviceType: string, before?: { time: string; id: string }): InspectionPage {
  if (!validInspectionContext(context) || !['CURRENT','ARCHIVED','ALL'].includes(view) || deviceType !== '' && !key(deviceType) || before && (!inspectionUuid(before.id) || utcInstantNanos(before.time) === null) || !exact(value, ['tenant_id','items','has_more','next_before_created_at','next_before_id','view','source','scope','page_size_limit']) || value.tenant_id !== context.tenantId || !Array.isArray(value.items) || value.items.length > 15 || typeof value.has_more !== 'boolean' || value.view !== view || value.source !== 'postgresql' || value.scope !== 'CURRENT_TENANT_PADMIN' || value.page_size_limit !== 100) throw inspectionBad()
  const items = value.items.map(row => readInspectionSummary(row, context)), seen = new Set<string>(); let cursor = before
  for (const row of items) {
    if (seen.has(row.id) || deviceType && row.device_type !== deviceType || view === 'CURRENT' && row.archived || view === 'ARCHIVED' && !row.archived || cursor && (utcInstantNanos(row.created_at)! > utcInstantNanos(cursor.time)! || utcInstantNanos(row.created_at) === utcInstantNanos(cursor.time) && row.id >= cursor.id)) throw inspectionBad()
    seen.add(row.id); cursor = { time: row.created_at, id: row.id }
  }
  if (value.has_more ? items.length !== 15 || value.next_before_id !== cursor?.id || value.next_before_created_at !== cursor?.time : value.next_before_id !== null || value.next_before_created_at !== null) throw inspectionBad()
  return structuredClone({ ...value, items }) as unknown as InspectionPage
}
export async function readInspectionHistory(value: unknown, context: InspectionContext, id: string, before?: number): Promise<InspectionHistory> {
  if (!validInspectionContext(context) || !inspectionUuid(id) || before !== undefined && !version(before) || !exact(value, ['tenant_id','template_id','items','has_more','next_before_version','source','scope','page_size_limit']) || value.tenant_id !== context.tenantId || value.template_id !== id || !Array.isArray(value.items) || value.items.length > 15 || before === undefined && value.items.length === 0 || typeof value.has_more !== 'boolean' || value.source !== 'postgresql' || value.scope !== 'CURRENT_TENANT_PADMIN' || value.page_size_limit !== 100) throw inspectionBad()
  // Detach before the first asynchronous digest, so the transport cannot alter later entries while validation waits.
  const snapshot = structuredClone(value); let previous = before, time: string | undefined
  for (const [index, row] of (snapshot.items as unknown[]).entries()) {
    if (!exact(row, ['version','previous_version','recorded_by','recorded_at','action','archived','items',...factKeys])) throw inspectionBad()
    facts(row); lifecycle(row); const current = row.version as number
    if (row.previous_version !== (current === 1 ? null : current - 1) || previous !== undefined && current >= previous || index > 0 && current !== previous! - 1 || time && !utcNotBefore(time, row.recorded_at)) throw inspectionBad()
    const definition = readInspectionDefinition({ device_type: row.device_type, name: row.name, items: row.items })
    if (definition.items.length !== row.item_count || await inspectionFingerprint(definition) !== row.content_sha256) throw inspectionBad()
    previous = current; time = row.recorded_at as string
  }
  if (snapshot.has_more ? (snapshot.items as unknown[]).length !== 15 || previous === undefined || previous <= 1 || snapshot.next_before_version !== previous : snapshot.next_before_version !== null || (snapshot.items as unknown[]).length > 0 && previous !== 1) throw inspectionBad()
  return snapshot as unknown as InspectionHistory
}
