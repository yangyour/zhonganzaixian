/** Presentation only: never changes tenant identifiers or persisted names. */
export function displayTenantName(name: string): string {
  return name.replace(/\s*[（(]测试环境[）)]\s*$/u, '').trim() || '当前租户'
}
