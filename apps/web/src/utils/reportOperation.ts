import { detachedReadState } from './detachedWorkspaceState'
import { isUtcInstant, utcNotBefore } from './utcInstant'
import { api, ApiError, isCancelled, StaleRequestError } from '../api/client'
import type { CustomerReportOperation, RegisteredTicket } from '../api/types'
import { isTicketUuid, ticketIdFromResultRef } from './ticket'

export type ReportOperationTarget = Pick<RegisteredTicket, 'id' | 'tenant_id' | 'operation_id'>
export interface ReportOperationContext { tenantId: string; key: string }
export type ReportOperationReadState =
  | { status: 'idle' }
  | { status: 'loading'; target: ReportOperationTarget }
  | { status: 'ready'; target: ReportOperationTarget; operation: CustomerReportOperation }
  | { status: 'error'; target: ReportOperationTarget; error: unknown }

const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const timestamp = isUtcInstant
function validTarget(target: ReportOperationTarget, tenantId: string) {
  if (!isTicketUuid(target.id) || !isTicketUuid(target.operation_id) || !tenantId || target.tenant_id !== tenantId) throw new ApiError(0, 'INVALID_REPORT_OPERATION_REFERENCE', '当前登记的操作引用或租户不一致，请重新查询详情；已登记的报障继续保留', '—')
}

/** Accept the current registration gate verbatim; do not translate it into business success or progress. */
export function validateReportOperation(value: unknown, target: ReportOperationTarget): CustomerReportOperation {
  validTarget(target, target.tenant_id)
  if (!record(value) || !isTicketUuid(value.id) || value.id.toLowerCase() !== target.operation_id.toLowerCase()
    || value.tenant_id !== target.tenant_id || value.kind !== 'CUSTOMER_REPORT_REGISTRATION'
    || ticketIdFromResultRef(value.result_ref) !== target.id.toLowerCase()
    || value.status !== 'PENDING' || value.step !== 'AWAITING_TICKET_CONFIGURATION' || value.progress !== null
    || !record(value.error) || value.error.code !== 'CONFIG_PENDING' || value.error.retryable !== false
    || typeof value.error.message !== 'string' || !value.error.message.trim()
    || !Array.isArray(value.error.question_ids) || !value.error.question_ids.every(id => typeof id === 'string' && id.trim())
    || !timestamp(value.created_at) || !timestamp(value.updated_at) || !utcNotBefore(value.updated_at, value.created_at)) throw new ApiError(200, 'INVALID_REPORT_OPERATION', '登记操作返回与当前报障或既有规则门禁不一致，请手动重查；不会再次提交报障', '—')
  return value as unknown as CustomerReportOperation
}

/** Resolve a durable acceptance with GETs only, publishing nothing until both references agree. */
export async function readAcceptedReport(operationId: string, context: { tenantId: string; actorId: string }, options: {
  signal?: AbortSignal; isCurrent?: () => boolean
} = {}, client: Pick<typeof api, 'request'> = api): Promise<{ operation: CustomerReportOperation; ticket: RegisteredTicket }> {
  const current = () => {
    if (options.signal?.aborted || options.isCurrent?.() === false) throw new StaleRequestError()
  }
  current()
  if (!isTicketUuid(operationId) || !isTicketUuid(context.tenantId) || !isTicketUuid(context.actorId)) throw new ApiError(0, 'INVALID_REPORT_OPERATION_REFERENCE', '当前登记操作或登录主体不一致，请重新查询；已受理报障不会再次提交', '—')
  const id = operationId.toLowerCase()
  const result = await client.request<unknown>(`/operations/${id}`, { signal: options.signal })
  current()
  const ticketId = record(result) ? ticketIdFromResultRef(result.result_ref) : null
  if (!ticketId) throw new ApiError(200, 'INVALID_REPORT_OPERATION', '登记结果引用不符合本应用路径，请重新查询；不会打开外部地址或再次提交', '—')
  const operation = validateReportOperation(result, { id: ticketId, tenant_id: context.tenantId, operation_id: id })
  const detail = await client.request<unknown>(`/tickets/${ticketId}`, { signal: options.signal })
  current()
  if (!record(detail) || !isTicketUuid(detail.id) || detail.id.toLowerCase() !== ticketId
    || detail.tenant_id !== context.tenantId || detail.reporter_id !== context.actorId
    || !isTicketUuid(detail.operation_id) || detail.operation_id.toLowerCase() !== id
    || typeof detail.version !== 'number' || !Number.isSafeInteger(detail.version) || detail.version < 1 || !Array.isArray(detail.attachments)) throw new ApiError(200, 'INVALID_REPORT_DETAIL', '登记详情与本次操作或当前报障主体不一致，请重查；不会重新提交报障', '—')
  return { operation, ticket: detail as unknown as RegisteredTicket }
}

/** A read is independent of the ticket snapshot. Failed or superseded reads cannot erase the registration. */
export function createReportOperationReader(options: {
  context: () => ReportOperationContext
  onChange: (state: ReportOperationReadState) => void
}, client: Pick<typeof api, 'request'> = api) {
  let generation = 0
  let controller: AbortController | undefined
  let state: ReportOperationReadState = { status: 'idle' }
  function update(next: ReportOperationReadState) { state = detachedReadState(next); options.onChange(detachedReadState(state)) }
  return {
    get state() { return detachedReadState(state) },
    reset() { ++generation; controller?.abort(); controller = undefined; update({ status: 'idle' }) },
    async load(ticket: ReportOperationTarget): Promise<boolean> {
      const target = { id: ticket.id, tenant_id: ticket.tenant_id, operation_id: ticket.operation_id }
      const context = { ...options.context() }, epoch = ++generation
      controller?.abort(); const request = new AbortController(); controller = request
      const current = () => epoch === generation && !request.signal.aborted && options.context().key === context.key && options.context().tenantId === context.tenantId
      update({ status: 'loading', target })
      try {
        validTarget(target, context.tenantId)
        const result = await client.request<unknown>(`/operations/${target.operation_id.toLowerCase()}`, { signal: request.signal })
        if (!current()) { if (epoch === generation) update({ status: 'idle' }); return false }
        const operation = validateReportOperation(result, target)
        update({ status: 'ready', target, operation })
        return true
      } catch (cause) {
        if (!current()) { if (epoch === generation) update({ status: 'idle' }); return false }
        if (!isCancelled(cause)) update({ status: 'error', target, error: cause })
        else update({ status: 'idle' })
        return false
      }
    },
  }
}
