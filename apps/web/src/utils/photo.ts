import type { RegisteredTicket, ReportPhoto, ReportPhotoPolicy } from '../api/types'
import { isTicketUuid } from './ticket'

export const photoMaxBytes = 5_000_000
export const photoMaxCount = 3
const uuidSegment = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
export function fileIdFromResultRef(value: unknown): string | null {
  if (typeof value !== 'string') return null
  return new RegExp(`^/api/v1/files/(${uuidSegment})$`).exec(value)?.[1]?.toLowerCase() || null
}
export function fileContentPath(value: unknown, fileId: string): string | null {
  if (typeof value !== 'string' || !isTicketUuid(fileId)) return null
  const match = new RegExp(`^/api/v1/files/(${uuidSegment})/content\\?grant=(${uuidSegment})$`).exec(value)
  if (!match?.[1] || match[1].toLowerCase() !== fileId.toLowerCase()) return null
  return `/files/${match[1].toLowerCase()}/content?grant=${match[2]!.toLowerCase()}`
}
export function photoSelectionError(file: Pick<File, 'name' | 'type' | 'size'>, policy: ReportPhotoPolicy | null, selectedCount: number): string | null {
  if (!policy?.allowed_media_types?.length) return '当前图片格式策略尚未配置，请先不带图登记'
  if (policy.purpose !== 'customer_report_photo' || !Number.isInteger(policy.max_size_bytes) || policy.max_size_bytes < 1 || !Number.isInteger(policy.max_attachments) || policy.max_attachments < 1 || !Number.isInteger(selectedCount) || selectedCount < 0) return '图片策略返回异常，请重新加载'
  if (selectedCount >= Math.min(photoMaxCount, policy.max_attachments)) return '每条报障最多 3 张照片'
  if (!Number.isFinite(file.size) || file.size < 1 || file.size > Math.min(photoMaxBytes, policy.max_size_bytes)) return '每张照片须为 1–5,000,000 bytes（5 MB）'
  const media = file.type || (/\.jpe?g$/i.test(file.name) ? 'image/jpeg' : /\.png$/i.test(file.name) ? 'image/png' : '')
  if (!['image/jpeg', 'image/png'].includes(media) || !policy.allowed_media_types.includes(media as 'image/jpeg' | 'image/png')) return '当前开发方向支持 JPEG、PNG，请选择对应图片；真实内容由服务端核验'
  if ((media === 'image/jpeg' && !/\.jpe?g$/i.test(file.name)) || (media === 'image/png' && !/\.png$/i.test(file.name))) return '图片扩展名与声明格式不一致，请选择原始 JPEG / PNG 文件'
  return null
}
export function readyPhotoIds(files: readonly ReportPhoto[], context: { tenantId: string; actorId: string }, ticketId: string | null = null): string[] {
  return [...new Set(files.filter(file => isTicketUuid(file.id) && file.purpose === 'customer_report_photo' && file.upload_status === 'READY' && file.tenant_id === context.tenantId && file.created_by === context.actorId && (file.ticket_id === null || file.ticket_id === ticketId)).map(file => file.id.toLowerCase()))]
}
export interface PhotoUploadAttempt { file: File; key: string; state: 'ready' | 'uncertain' | 'accepted' }
export function preparePhotoAttempt(previous: PhotoUploadAttempt | null, file: File, newKey: () => string = () => crypto.randomUUID()): PhotoUploadAttempt {
  if (previous?.file !== undefined && previous.file !== file) throw new Error('原上传请求绑定原File对象，不能替换内容或更换请求标识')
  if (previous?.state === 'accepted') throw new Error('图片已受理，请查询上传状态，不要再次提交')
  return previous || { file, key: newKey(), state: 'ready' }
}

export interface AttachmentAttempt {
  ticketId: string; key: string; body: { expected_version: number; attachment_ids: string[] }; state: 'ready' | 'uncertain'
}
export function prepareAttachmentAttempt(previous: AttachmentAttempt | null, ticket: RegisteredTicket, files: readonly ReportPhoto[], context: { tenantId: string; actorId: string }, newKey: () => string = () => crypto.randomUUID()): AttachmentAttempt {
  if (!isTicketUuid(ticket.id) || ticket.tenant_id !== context.tenantId || ticket.reporter_id !== context.actorId) throw new Error('只能向当前租户本人的报障追加照片')
  if (previous?.state === 'uncertain') {
    if (previous.ticketId !== ticket.id) throw new Error('追加结果尚不确定，不能更换目标报障')
    return previous
  }
  if (!Number.isSafeInteger(ticket.version) || ticket.version < 1 || !Array.isArray(ticket.attachments)) throw new Error('登记版本或附件信息异常，请重新查询')
  const existingIds = new Set(ticket.attachments.map(photo => photo.id.toLowerCase()))
  const attachment_ids = readyPhotoIds(files, context, ticket.id).filter(id => !existingIds.has(id))
  if (!attachment_ids.length) throw new Error('请先上传照片并刷新至可关联状态')
  if (existingIds.size + attachment_ids.length > photoMaxCount) throw new Error('每条报障最多 3 张照片，请比较现有附件并移除多余选择')
  const body = { expected_version: ticket.version, attachment_ids }
  if (previous?.ticketId === ticket.id && JSON.stringify(previous.body) === JSON.stringify(body)) return previous
  return { ticketId: ticket.id, key: newKey(), body, state: 'ready' }
}
