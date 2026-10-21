import fixture from '../../../fixtures/monitoring-event-center-controller.json'
import { readMonitoringWorkspace, type MonitoringEventContext, type MonitoringEventDetailPayload, type MonitoringEventWorkspacePayload } from '../src/utils/monitoringEvents'

// Produced by MonitoringEventCenterHttpContractTest through the production MockMvc controller.
export const controllerFixture = fixture as unknown as {
  generator: string
  workspace: { data: MonitoringEventWorkspacePayload; trace_id: string }
  detail: { data: MonitoringEventDetailPayload; trace_id: string }
  exchanges: Array<{ name: string; method: string; path: string; request: Record<string, unknown>; response: { data: Record<string, unknown>; trace_id: string } }>
  enums: Record<string, string[]>
}
export const context: MonitoringEventContext = {
  tenantId: controllerFixture.workspace.data.tenant_id,
  actorId: controllerFixture.workspace.data.events[0]!.updated_by,
  permissionVersion: 3, canWriteInternal: true, canAdminConfirm: true,
}
export const sampleWorkspace = () => readMonitoringWorkspace(structuredClone(controllerFixture.workspace), context)
export const sampleDetail = () => structuredClone(controllerFixture.detail)
