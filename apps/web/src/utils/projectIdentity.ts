import { ApiError, type ApiClient } from '../api/client'
import { createRequestId } from './browserCrypto'
export const baseRoleLabels = { viewer: '客户观察者', tadmin: '客户管理员', msp: 'MSP工程师', padmin: '平台管理员' } as const
export type BaseRole = keyof typeof baseRoleLabels
export interface IdentityPolicy { version: number; minimum_password_length: number | null; password_history_count: number | null; failed_attempt_limit: number | null; lock_minutes: number | null; idle_minutes: number | null; force_change_on_reset: boolean | null }
export interface ProjectUser { id: string; display_name: string; active: boolean; username: string | null; local_login_enabled: boolean; must_change_password: boolean | null; locked_until: string | null; last_login_at: string | null; version: number; base_role: BaseRole; member_active: boolean; permission_version: number; role_id: string; role_name: string; permissions: string[] }
export interface ProjectRole { id: string; tenant_id: string; name: string; description: string | null; base_role: BaseRole; permissions: string[]; builtin: boolean; enabled: boolean; version: number; assigned_members: number; created_at: string; updated_at: string }
export interface PermissionEntry { id: string; label: string; dimension_ids: string[]; declared_by_role: Record<BaseRole, boolean> }
export interface PermissionCatalogue { declaration_hash: string; roles: { id: BaseRole; label: string }[]; dimensions: { id: string; label: string; capability_ids: string[] }[]; entries: PermissionEntry[] }
export interface UserPage { items: ProjectUser[]; has_more: boolean; page: number; page_size: number; tenant_id: string }
export interface RolePage { items: ProjectRole[]; tenant_id: string; permission_catalog: PermissionCatalogue }
export interface OwnAccount { id: string; display_name: string; active: boolean; username: string | null; must_change_password: boolean | null; last_login_at: string | null; version: number; password_policy: IdentityPolicy }
export interface IdentityBinding { id: string; provider: 'oidc'; issuer: string; subject: string; active: boolean; version: number; created_at: string }
export interface IdentityMembership { tenant_id: string; tenant_name: string; base_role: BaseRole; active: boolean; permission_version: number; role_id: string | null }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const version = (v: unknown) => Number.isSafeInteger(v) && Number(v) > 0
const bad = () => new ApiError(200, 'INVALID_IDENTITY_RESPONSE', '账号与权限回读格式未通过校验', '—')
export function validateIdentityWrite(path: string, input: unknown, raw: unknown): void {
  if (!record(input) || !record(raw)) throw bad()
  const hasSecret = (value: unknown): boolean => Array.isArray(value) ? value.some(hasSecret) : record(value) ? Object.entries(value).some(([key, item]) => ['password', 'password_hash', 'current_password', 'new_password', 'client_secret', 'access_token', 'refresh_token', 'id_token', 'csrf_token'].includes(key) || hasSecret(item)) : false
  if (hasSecret(raw)) throw bad()
  const parts = path.split('/').filter(Boolean)
  if (path === '/account/password') { if (raw.password_changed !== true || raw.reauthenticate !== true) throw bad(); return }
  if (path.endsWith('/password')) { if (raw.password_reset !== true || raw.sessions_revoked !== true || raw.user_id !== parts[2]) throw bad(); return }
  if (path.endsWith('/unlock')) { if (raw.unlocked !== true || raw.user_id !== parts[2]) throw bad(); return }
  if (path.endsWith('/revoke-sessions')) { if (raw.sessions_revoked !== true || raw.user_id !== parts[2]) throw bad(); return }
  if (path.endsWith('/disable')) { if (raw.binding_disabled !== true || raw.sessions_revoked !== true) throw bad(); return }
  if (path === '/identity/policy') { if (raw.version !== Number(input.expected_version) + 1 || Object.entries(input).some(([key, value]) => key !== 'expected_version' && raw[key] !== value)) throw bad(); return }
  if (parts.includes('memberships')) { if (raw.user_id !== parts[2] || raw.tenant_id !== parts[4] || !version(raw.permission_version) || Number(raw.permission_version) <= Number(input.expected_version) || raw.active !== input.active) throw bad(); return }
  if (!uuid.test(String(raw.id)) || !version(raw.version)) throw bad()
  if (parts[1] === 'roles') {
    if (parts[2] && raw.id !== parts[2] || raw.version !== Number(input.expected_version) + 1 || raw.name !== input.name || raw.base_role !== input.base_role || raw.enabled !== input.enabled || !Array.isArray(raw.permissions) || !Array.isArray(input.permissions) || JSON.stringify([...raw.permissions].sort()) !== JSON.stringify([...input.permissions].sort())) throw bad()
  } else if (parts.includes('external-identities')) {
    if (raw.issuer !== input.issuer || raw.subject !== input.subject || raw.provider !== 'oidc' || raw.active !== true) throw bad()
  } else {
    if (parts[2] && raw.id !== parts[2] || !Object.hasOwn(baseRoleLabels, String(raw.base_role)) || typeof raw.active !== 'boolean' || !Array.isArray(raw.permissions)) throw bad()
    if (raw.version !== Number(input.expected_version) + 1) throw bad()
    for (const key of ['username', 'display_name', 'active', 'role_id']) if (key in input && raw[key] !== input[key]) throw bad()
  }
}
export function readUserPage(raw: unknown, tenant: string): UserPage {
  if (!record(raw) || raw.tenant_id !== tenant || !Array.isArray(raw.items) || raw.items.length > 50 || typeof raw.has_more !== 'boolean' || !version(raw.page) || raw.page_size !== 50) throw bad()
  for (const u of raw.items) if (!record(u) || !uuid.test(String(u.id)) || !version(u.version) || !version(u.permission_version) || !Object.hasOwn(baseRoleLabels, String(u.base_role)) || typeof u.display_name !== 'string' || typeof u.active !== 'boolean' || typeof u.member_active !== 'boolean' || typeof u.local_login_enabled !== 'boolean' || !Array.isArray(u.permissions) || u.permissions.some(x => typeof x !== 'string') || 'password_hash' in u || 'password' in u) throw bad()
  return structuredClone(raw) as unknown as UserPage
}
export function readRolePage(raw: unknown, tenant: string): RolePage {
  if (!record(raw) || raw.tenant_id !== tenant || !Array.isArray(raw.items) || raw.items.length > 201 || !record(raw.permission_catalog) || !Array.isArray(raw.permission_catalog.entries) || !Array.isArray(raw.permission_catalog.dimensions)) throw bad()
  for (const role of raw.items) if (!record(role) || role.tenant_id !== tenant || !uuid.test(String(role.id)) || !version(role.version) || !Object.hasOwn(baseRoleLabels, String(role.base_role)) || typeof role.name !== 'string' || !Array.isArray(role.permissions) || typeof role.enabled !== 'boolean' || typeof role.builtin !== 'boolean') throw bad()
  return structuredClone(raw) as unknown as RolePage
}
export type IdentityCommandState = 'idle' | 'sending' | 'uncertain' | 'rejected' | 'accepted'
export interface IdentityCommand { state: IdentityCommandState; path: string | null; method: string | null; key: string | null; body: unknown; result: unknown; error: unknown }
/** Password-bearing original commands stay only in component memory, never in display state or storage. */
export function createIdentityCommand(client: Pick<ApiClient, 'request'>, notify: (value: IdentityCommand) => void, keyFactory = createRequestId) {
  let value: IdentityCommand = { state: 'idle', path: null, method: null, key: null, body: null, result: null, error: null }, generation = 0, disposed = false, controller: AbortController | null = null
  const emit = () => notify({ ...value, body: null })
  const clear = () => { generation++; controller?.abort(); controller = null; value = { state: 'idle', path: null, method: null, key: null, body: null, result: null, error: null }; emit() }
  async function run() {
    if (disposed || !value.path || !value.method || !value.key || value.state === 'sending') return false
    const turn = generation, recovering = value.state === 'uncertain', command = { path: value.path, method: value.method, key: value.key, body: structuredClone(value.body) }
    const ownedController = new AbortController(); controller = ownedController
    const signal = ownedController.signal, timeout = setTimeout(() => ownedController.abort(), 30000)
    value.state = 'sending'; value.error = null; emit()
    try {
      if (disposed || turn !== generation) return false
      const result = await client.request(command.path, { method: command.method, body: command.body, idempotencyKey: command.key, signal })
      if (disposed || turn !== generation) return false
      validateIdentityWrite(command.path, command.body, result)
      value.result = result; value.state = 'accepted'; value.body = null; return true
    } catch (error) {
      if (disposed || turn !== generation) return false
      value.error = error
      value.state = !recovering && error instanceof ApiError && [400, 403, 404, 409, 413, 415, 422].includes(error.status) ? 'rejected' : 'uncertain'
      return false
    } finally { clearTimeout(timeout); if (!disposed && turn === generation) { controller = null; emit() } }
  }
  return {
    async submit(path: string, method: string, body: unknown) {
      if (disposed || ['sending', 'uncertain'].includes(value.state)) return false
      // clear() notifies synchronously; a callback may replace or dispose this intent.
      const turn = generation + 1
      clear()
      if (disposed || turn !== generation || value.state !== 'idle') return false
      const key = keyFactory(), snapshot = structuredClone(body)
      if (disposed || turn !== generation) return false
      value.path = path; value.method = method; value.key = key; value.body = snapshot; return run()
    },
    retry() { return value.state === 'uncertain' ? run() : Promise.resolve(false) },
    unresolved: () => ['sending', 'uncertain'].includes(value.state),
    clear, dispose() { disposed = true; clear() },
  }
}
