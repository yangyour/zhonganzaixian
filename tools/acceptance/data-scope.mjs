// This tenant now contains JiangFaMing's real integrations. It is not a fixture namespace.
export const REAL_DATA_TENANT = '55000000-0000-4000-8000-000000000001'

export function assertAcceptanceWriteScope(path, method, session) {
  if (['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase())) return
  const pathname = new URL(path, 'http://acceptance.invalid').pathname
  if (method.toUpperCase() === 'POST' && ['/auth/local/login', '/auth/logout', '/session/tenant'].includes(pathname)) return
  if (!session?.tenant?.id || session.tenant.id === REAL_DATA_TENANT) {
    throw new Error('REAL_DATA_TENANT_PROTECTED: synthetic business writes require an isolated acceptance tenant')
  }
}
