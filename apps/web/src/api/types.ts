export type Role = 'viewer' | 'tadmin' | 'msp' | 'padmin'
export type RuntimeMode = 'remote_test' | 'local_fixture'
export interface PlatformUser { id: string; display_name: string; role: Role }
export interface AuthOptions { mode: RuntimeMode; identity_source: string; login_url: string | null }
export interface LogoutResult { logged_out: boolean; redirect_url: string | null }
export interface Tenant { id: string; name: string }
export interface Session {
  user: PlatformUser; tenant: Tenant; allowed_tenants: Tenant[]; capabilities: string[]
  permission_version: number; csrf_token: string; mode: RuntimeMode; identity_source: string
}
export type AssetStatus = 'unknown' | 'ok' | 'problem' | 'maintenance' | 'disabled'
export type AssetType = 'router' | 'switch' | 'firewall' | 'ap' | 'wireless_controller' | 'other'
export interface Asset {
  id: string; tenant_id: string; name: string; type: AssetType; management_ip: string
  site_id: string; site_name?: string; status: AssetStatus; lifecycle_status: string
  source: string; sampled_at: string | null; unknown_reason: string | null
  version: number; created_at: string; updated_at: string
}
export interface AssetPage { items: Asset[]; next_cursor: string | null; has_more: boolean }
export interface AssetDraft { name: string; type: AssetType; management_ip: string; site_id: string }
export interface Configuration {
  key: string; description: string; type: string; scope: string; unit: string | null
  value: unknown; registered_value: unknown; effective_value: unknown; decision_status: string; source: string; question_ids: string[]
  activation_gate: string | null; notes: string | null; allowed_range: unknown
  contract_value_kind: string; version: number; draft_value: unknown; lifecycle_status: string
}
export type ConfigurationRevisionStatus = 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
export interface ConfigurationEvidenceReference { reference: string; title: string; sha256: string }
export interface ConfigurationRevisionHistory {
  action: 'CREATE' | 'SUBMIT' | 'APPROVE' | 'REJECT' | 'WITHDRAW'
  actor_id: string; occurred_at: string; from_status: ConfigurationRevisionStatus | null
  to_status: ConfigurationRevisionStatus; comment: string | null
}
export interface ConfigurationRevision {
  id: string; tenant_id: string; configuration_key: string; proposed_value: unknown; reason: string
  evidence_refs: ConfigurationEvidenceReference[]; workflow_status: ConfigurationRevisionStatus; version: number
  created_by: string; created_at: string; updated_at: string
  reviewed_by: string | null; reviewed_at: string | null; review_comment: string | null
  decision_status: 'pending' | 'contract_fixed'; effective_value: null; confirmation_status: 'UNVERIFIED'
  source: 'postgresql'; history: ConfigurationRevisionHistory[]
}
export interface ConfigurationRevisionDraft {
  proposed_value: unknown; reason: string; evidence_refs: ConfigurationEvidenceReference[]
}
export interface AuditEvent {
  id: string; occurred_at: string; actor_name: string; action: string; resource_type: string
  resource_id: string; result: string; reason: string | null; trace_id: string
}
export interface Operation {
  id?: string; operation_id?: string; status: string; progress?: number | null
  result?: unknown; error?: { code: string; message: string } | null
}
