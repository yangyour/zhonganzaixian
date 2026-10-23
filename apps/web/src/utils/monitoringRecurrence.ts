/** Labels only server-returned durable keys; this does not infer recovery or recalculate SLA. */
export function monitoringRecurrenceLabel(key: string): string | null {
  const match = /^recovery:(50|80|100):monitor:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}):([1-9][0-9]{0,9})$/.exec(key)
  if (!match || Number(match[3]) > 2147483647) return null
  return `恢复 SLA ${match[1]}% · 故障周期 ${match[3]}`
}
