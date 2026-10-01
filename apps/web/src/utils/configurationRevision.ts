import type { Configuration, ConfigurationEvidenceReference, ConfigurationRevision, ConfigurationRevisionDraft, ConfigurationRevisionStatus, PlatformUser } from '../api/types'
import { editorKind, parseProposedValue } from './configuration'

export interface RevisionForm {
  proposed: string | boolean; reason: string; evidence_refs: ConfigurationEvidenceReference[]
}
export class RevisionValidationError extends Error {
  constructor(public fields: Record<string, string>) { super('请检查提案填写内容'); this.name = 'RevisionValidationError' }
}
export function validateRevisionComment(value: string, label = '意见'): string {
  if (/[\u0000-\u001f\u007f-\u009f]/.test(value)) throw new Error(`${label}不能包含换行、制表或其他控制字符`)
  const result = value.trim()
  if (!result || Array.from(result).length > 500) throw new Error(`${label}须为 1–500 个字符`)
  return result
}
export function parseRevisionForm(configuration: Pick<Configuration, 'type' | 'contract_value_kind'>, form: RevisionForm): ConfigurationRevisionDraft {
  const fields: Record<string, string> = {}
  if (configuration.contract_value_kind === 'fixed_constraint') fields.proposed = '合同固定约束不能创建修订'
  let proposed_value: unknown
  try { proposed_value = parseProposedValue(editorKind(configuration), form.proposed) }
  catch (cause) { fields.proposed = cause instanceof Error ? cause.message : '建议值有误' }
  let reason = ''
  try { reason = validateRevisionComment(form.reason, '修订理由') }
  catch (cause) { fields.reason = (cause as Error).message }
  if (form.evidence_refs.length < 1 || form.evidence_refs.length > 5) fields.evidence_refs = '请登记 1–5 项证据引用'
  const evidence_refs = form.evidence_refs.map((item, index) => {
    const reference = item.reference.trim(), title = item.title.trim(), sha256 = item.sha256.trim()
    const suffix = reference.slice('document-ref:'.length)
    if (/[\u0000-\u001f\u007f-\u009f]/.test(item.reference) || reference.length > 160 || !/^document-ref:[A-Za-z0-9._:-]+$/.test(reference)
      || /^[A-Za-z]:/.test(suffix) || /token|password|secret|credential|private[_:-]?key/i.test(reference)) {
      fields[`evidence_refs.${index}.reference`] = '使用 document-ref:ASCII标识（总长不超过 160）；禁止 URL、磁盘路径或秘密'
    }
    if (/[\u0000-\u001f\u007f-\u009f]/.test(item.title) || !title || Array.from(title).length > 200) fields[`evidence_refs.${index}.title`] = '证据标题须为 1–200 个字符，不能包含换行或控制字符'
    if (/[\u0000-\u001f\u007f-\u009f]/.test(item.sha256) || !/^[a-fA-F0-9]{64}$/.test(sha256)) fields[`evidence_refs.${index}.sha256`] = '请填写 64 位十六进制 SHA-256，不能包含控制字符'
    return { reference, title, sha256: sha256.toLowerCase() }
  })
  if (Object.keys(fields).length) throw new RevisionValidationError(fields)
  return { proposed_value, reason, evidence_refs }
}
export function revisionActions(revision: Pick<ConfigurationRevision, 'created_by' | 'workflow_status'>, user: PlatformUser | null | undefined, canWrite: boolean) {
  const writer = user?.role === 'padmin' && canWrite
  const creator = Boolean(user && revision.created_by === user.id)
  return {
    submit: writer && creator && revision.workflow_status === 'DRAFT',
    withdraw: writer && creator && ['DRAFT', 'IN_REVIEW'].includes(revision.workflow_status),
    review: writer && !creator && revision.workflow_status === 'IN_REVIEW',
    waitingForReviewer: creator && revision.workflow_status === 'IN_REVIEW',
  }
}
export const revisionStatusLabel: Record<ConfigurationRevisionStatus, string> = {
  DRAFT: '提案草稿', IN_REVIEW: '等待内部审批', APPROVED: '内部审批通过，尚未生效', REJECTED: '内部审批驳回', WITHDRAWN: '已撤回',
}
export const revisionActionLabel = { CREATE: '创建提案', SUBMIT: '提交审批', APPROVE: '内部审批通过', REJECT: '内部审批驳回', WITHDRAW: '撤回提案' }
