// Synthetic unit/page input only; actual HTTP proof uses existing approved components separately.
import { createHash } from 'node:crypto'
import { inspectionTemplateBytes, type InspectionContext, type InspectionDefinition, type InspectionDetail, type InspectionPage, type InspectionRevision, type InspectionHistory } from '../src/utils/inspectionTemplates'
export const id = (n: number) => `79000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`
export const scope: InspectionContext = { tenantId: id(1), actorId: id(2), permissionVersion: 7, role: 'padmin', canRead: true, canWrite: true }
export const definition = (): InspectionDefinition => ({ device_type: 'new_device', name: '合成模板', items: [{ key: 'firmware', section: '安全', label: '固件版本', instruction: null }, { key: 'backup', section: '配置备份', label: '人工观察', instruction: '' }] })
export const fingerprint = (value: InspectionDefinition) => createHash('sha256').update(inspectionTemplateBytes(value)).digest('hex')
export function row(version = 1, patch: Partial<InspectionDetail> = {}): InspectionDetail {
  const value: InspectionDetail = { ...definition(), id: id(10), tenant_id: scope.tenantId, created_by: scope.actorId, version, created_at: '2026-10-04T08:00:00.000Z', updated_at: `2026-10-04T08:00:00.${String(version).padStart(3,'0')}Z`, recorded_by: scope.actorId, recorded_at: `2026-10-04T08:00:00.${String(version).padStart(3,'0')}Z`, action: version === 1 ? 'CREATE' : 'UPDATE', archived: false, item_count: 2, content_sha256: '', approval_status: 'UNVERIFIED', publication_status: 'NOT_IMPLEMENTED', report_published: false, active_scan_performed: false, source: 'postgresql', record_source: 'EXPLICIT_TEMPLATE_DEFINITION', ...patch }
  value.content_sha256 = patch.content_sha256 ?? fingerprint({ device_type: value.device_type, name: value.name, items: value.items }); value.item_count = patch.item_count ?? value.items.length
  return value
}
export const summary = (value: InspectionDetail) => { const { items: _items, ...rest } = value; return rest }
export const page = (items = [row()]): InspectionPage => ({ tenant_id: scope.tenantId, items: items.map(summary), has_more: false, next_before_created_at: null, next_before_id: null, view: 'CURRENT', source: 'postgresql', scope: 'CURRENT_TENANT_PADMIN', page_size_limit: 100 })
export function revision(value: InspectionDetail): InspectionRevision { const { id: _id, tenant_id: _tenant, created_by: _creator, created_at: _created, updated_at: _updated, ...rest } = value; return { ...rest, previous_version: value.version === 1 ? null : value.version - 1 } }
export const history = (items = [row()]): InspectionHistory => ({ tenant_id: scope.tenantId, template_id: id(10), items: items.map(revision), has_more: false, next_before_version: null, source: 'postgresql', scope: 'CURRENT_TENANT_PADMIN', page_size_limit: 100 })
