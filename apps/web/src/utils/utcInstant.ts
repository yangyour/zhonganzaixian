/** Strict server UTC timestamp parsing. Calendar round-trip rejects Date.parse normalization; BigInt preserves nanoseconds. */
export function utcInstantNanos(value: unknown): bigint | null {
  if (typeof value !== 'string') return null
  const match = /^(?!0000)(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value)
  if (!match) return null
  const canonical = `${match[1]}.000Z`, milliseconds = Date.parse(canonical)
  if (!Number.isFinite(milliseconds) || new Date(milliseconds).toISOString() !== canonical) return null
  return BigInt(milliseconds) * 1_000_000n + BigInt((match[2] || '').padEnd(9, '0'))
}
export function isUtcInstant(value: unknown): value is string { return utcInstantNanos(value) !== null }
export function utcNotBefore(value: unknown, baseline: unknown): boolean {
  const current = utcInstantNanos(value), previous = utcInstantNanos(baseline)
  return current !== null && previous !== null && current >= previous
}
