export type Role = 'viewer' | 'tadmin' | 'msp' | 'padmin'
export type RuntimeMode = 'remote_test' | 'local_fixture'
export interface PlatformUser { id: string; display_name: string; role: Role }
export interface AuthOptions { mode: RuntimeMode; identity_source: string; login_url: string | null; local_enabled?: boolean; sso_enabled?: boolean; local_login_url?: string; csrf_url?: string; sso_protocol?: string; wecom_enabled?: boolean; wecom_start_url?: string | null }
export interface LogoutResult { logged_out: boolean; redirect_url: string | null }
export interface Tenant { id: string; name: string }
export interface Session {
  user: PlatformUser; tenant: Tenant; allowed_tenants: Tenant[]; capabilities: string[]
  permission_version: number; csrf_token: string; mode: RuntimeMode; identity_source: string
  account_version?: number; password_change_required?: boolean
}
export type AssetStatus = 'unknown' | 'ok' | 'problem' | 'maintenance' | 'disabled'
export type AssetType = 'router' | 'switch' | 'firewall' | 'ap' | 'wireless_controller' | 'other'
export interface Asset {
  id: string; tenant_id: string; name: string; type: AssetType; management_ip: string
  site_id: string; site_name?: string; status: AssetStatus; lifecycle_status: string
  source: string; sampled_at: string | null; unknown_reason: string | null
  availability?: string | null; sync_state?: string | null
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
export type ConfigurationDiagnosticSource = 'DESIGN_REGISTRY' | 'INTERNAL_REVISION' | 'RUNTIME_DEFINITION' | 'RUNTIME_PUBLICATION' | 'CUSTOMER_VERIFICATION'
export type ConfigurationDiagnosticReasonCode = 'DESIGN_REGISTRY_NOT_RUNTIME_CONFIG' | 'RUNTIME_DEFINITION_SOURCE_UNAVAILABLE'
  | 'PUBLICATION_SOURCE_UNAVAILABLE' | 'CUSTOMER_VERIFICATION_SOURCE_UNAVAILABLE' | 'INTERNAL_REVISION_NOT_RUNTIME_PUBLICATION'
  | 'PROPOSAL_EVIDENCE_REFERENCES_UNAVAILABLE' | 'REGISTRY_QUESTIONS_UNAVAILABLE'
export interface ConfigurationDiagnostic {
  key: string; status: 'CONFIG_PENDING'; effective_value: null
  context: { tenant_id: string; environment: 'remote_test' }; observed_at: string
  registry: {
    origin: 'DESIGN_REGISTRY'; version: number; type_label: string | null; unit_label: string | null
    scope_label: string | null; source_ref: string | null; decision_status_label: string | null
    classification_label: string | null; question_ids: string[] | null
  }
  recent_proposals: {
    items: { id: string; version: number; workflow_status: ConfigurationRevisionStatus; origin: 'INTERNAL_REVISION'
      confirmation_status: 'UNVERIFIED'; evidence_input_status: 'REFERENCE_ONLY'; evidence_reference_count: number | null }[]
    truncated: boolean
  }
  runtime_definition: { availability: 'NOT_IMPLEMENTED'; schema_version: null; allowed_layers: null; precedence: null; resolved_scope: null }
  publication: { availability: 'NOT_IMPLEMENTED'; record_ref: null; revision_id: null; revision_version: null; effective_at: null
    authorization_verified: null; production_approval_verified: null }
  customer_verification: { availability: 'NOT_IMPLEMENTED'; required: null; decision_ref: null; authority_verified: null; authenticity_verified: null }
  reasons: { code: ConfigurationDiagnosticReasonCode; source_kind: ConfigurationDiagnosticSource; source_id: string | null; source_version: number | null }[]
  question_ids: string[] | null
}
export interface AuditEvent {
  id: string; occurred_at: string; actor_name: string; action: string; resource_type: string
  resource_id: string; result: string; reason: string | null; trace_id: string
}
export interface Operation {
  id?: string; operation_id?: string; status: string; progress?: number | null
  result?: unknown; error?: { code: string; message: string } | null
}
export type TicketCategory = '网络不通' | '网速慢' | 'WiFi问题' | '设备异常' | '其他'
export type TicketImpactScope = 'company' | 'partial_area' | 'individual'
export type TicketUrgency = 'normal' | 'urgent'
export interface CustomerReportCreate {
  source: 'customer_report'; title?: string; description: string; category: TicketCategory
  impact_scope: TicketImpactScope; contact_name: string; contact_phone: string; urgency: TicketUrgency
  asset_id?: string; attachment_ids?: string[]
}
export interface CustomerReportAcceptance { operation_id: string; status: 'PENDING' }
export interface CustomerReportOperation {
  id: string; tenant_id: string; kind: 'CUSTOMER_REPORT_REGISTRATION'; status: 'PENDING'
  step: 'AWAITING_TICKET_CONFIGURATION'; progress: null; result_ref: string
  error: { code: 'CONFIG_PENDING'; message: string; retryable: false; question_ids: string[] }
  created_at: string; updated_at: string
}
export interface RegisteredTicket {
  id: string; tenant_id: string; receipt_no: string; title: string; description: string
  source: 'customer_report'; category: TicketCategory; impact_scope: TicketImpactScope; urgency: TicketUrgency
  asset_id: string | null; asset_name: string | null; reporter_id: string; contact_name: string; contact_phone: string
  contact_binding_status: 'UNVERIFIED'; registration_status: 'REGISTERED'; state: null
  external_id: null; external_state: null; sync_state: 'NOT_APPLICABLE'; authority_source: 'MOP'; authority_status: 'RULES_PENDING'
  priority: null; response_due_at: null; recovery_due_at: null; operation_id: string; version: number
  created_at: string; updated_at: string; attachments: ReportPhoto[]
  history: { action: 'REGISTER' | 'MONITORING_REGISTER' | 'LEGACY_MONITORING_REGISTER' | 'ADD_ATTACHMENTS'; actor_id: string; occurred_at: string; version: number }[]
}
export interface TicketPage {
  items: RegisteredTicket[]; next_cursor: string | null; has_more: boolean; source: 'postgresql'
  page_size_limit: number; page_size_limit_kind: 'test_technical_limit'
}
export interface ReportPhotoPolicy {
  purpose: 'customer_report_photo'; allowed_media_types: ('image/jpeg' | 'image/png')[] | null
  max_size_bytes: number; max_attachments: number; policy_source: 'USER_DEVELOPMENT_DIRECTION'
  production_approval: 'UNVERIFIED'; scan_status: 'UNSCANNED'; retention_until: null
}
export interface ReportPhoto {
  id: string; tenant_id: string; purpose: 'customer_report_photo'; original_name: string
  media_type: 'image/jpeg' | 'image/png'; size_bytes: number; sha256: string; upload_status: 'PENDING' | 'READY' | 'FAILED'
  scan_status: 'UNSCANNED'; retention_until: null; production_approval: 'UNVERIFIED'; policy_source: 'USER_DEVELOPMENT_DIRECTION'
  created_by: string; ticket_id: string | null; operation_id: string; version: number
  created_at: string; updated_at: string; source: 'postgresql'
}
export interface FileUploadOperation {
  id: string; tenant_id: string; kind: 'FILE_UPLOAD'; status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  step: string; progress: null; result_ref: string; error: { code: string; message: string; retryable: boolean; question_ids: string[] } | null
  created_at: string; updated_at: string
}
export interface FileContentGrant { content_url: string; expires_at: string }
