import type { Session } from '../api/types'
import { readExportFilters, type AssetExportFilters } from './assetExportJobs'

const keys = ['q', 'type', 'status', 'site_id', 'cursor', 'page_size'] as const
export function assetExportHandoffScope(session: Session | null, generation: number): string {
  return JSON.stringify([generation, session?.tenant.id, session?.user.id, session?.user.role,
    session?.permission_version, session?.account_version, session?.identity_source])
}

/** Presentation handoff only; it neither authorizes nor submits an export. */
export function createAssetExportHandoff(scope: string, query: Record<string, unknown>): string {
  const listQuery: Record<string, string> = {}
  for (const key of keys) {
    const value = query[key]
    if (value !== undefined && value !== null && value !== '') {
      if (typeof value !== 'string' || value.length > 2048) throw new Error('INVALID_EXPORT_HANDOFF')
      listQuery[key] = value
    }
  }
  const filters = readExportFilters({
    type: listQuery.type || null, status: listQuery.status || null,
    site_id: listQuery.site_id || null, query: listQuery.q || null,
  })
  return JSON.stringify({ version: 1, scope, filters, listQuery })
}

export function readAssetExportHandoff(value: unknown, scope: string): {
  filters: AssetExportFilters; listQuery: Record<string, string>
} | null {
  if (typeof value !== 'string' || value.length > 8192) return null
  try {
    const data = JSON.parse(value)
    if (!data || data.version !== 1 || data.scope !== scope || !data.listQuery
        || typeof data.listQuery !== 'object' || Array.isArray(data.listQuery)
        || Object.keys(data.listQuery).some(key => !keys.includes(key as typeof keys[number]))) return null
    const verified = JSON.parse(createAssetExportHandoff(scope, data.listQuery))
    const filters = readExportFilters(data.filters)
    if (Object.keys(filters).some(key => filters[key as keyof AssetExportFilters] !== verified.filters[key])) return null
    return { filters, listQuery: verified.listQuery }
  } catch { return null }
}
