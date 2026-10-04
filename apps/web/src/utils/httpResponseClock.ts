import { utcInstantNanos } from './utcInstant'

export type ClockUnknownReason = 'NOT_REQUESTED' | 'DATE_MISSING' | 'DATE_INVALID' | 'CACHE_POLICY_UNVERIFIED' | 'AGE_HEADER_PRESENT' | 'MONOTONIC_TIME_INVALID'
export type HttpClockObservation = Readonly<
  | { status: 'UNKNOWN'; reason: ClockUnknownReason; clock_accuracy_verified: false }
  | { status: 'OBSERVED'; source: 'HTTP_DATE_HEADER_NOT_SAMPLE'; date_iso: string; started_mono_ms: number; received_mono_ms: number; clock_accuracy_verified: false }
>
const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
const monotonic = (value: number) => Number.isFinite(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER
/** Canonical IMF-fixdate only; a permissively normalized date is not an observed clock. */
export function httpDateIso(value: string | null): string | null {
  if (value === null) return null
  const match = /^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), (\d{2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$/.exec(value)
  if (!match) return null
  const iso = `${match[4]}-${String(months.indexOf(match[3]!)+1).padStart(2,'0')}-${match[2]}T${match[5]}:${match[6]}:${match[7]}Z`, nanos = utcInstantNanos(iso)
  if (nanos === null || new Date(Number(nanos / 1_000_000n)).toUTCString() !== value) return null
  return iso
}
export function observeHttpClock(headers: Pick<Headers,'get'>, started: number, received: number): HttpClockObservation {
  const unknown = (reason: ClockUnknownReason): HttpClockObservation => Object.freeze({status:'UNKNOWN',reason,clock_accuracy_verified:false})
  if (!monotonic(started) || !monotonic(received) || received < started) return unknown('MONOTONIC_TIME_INVALID')
  const date = headers.get('date'); if (date === null) return unknown('DATE_MISSING')
  const date_iso = httpDateIso(date); if (date_iso === null) return unknown('DATE_INVALID')
  if (headers.get('age') !== null) return unknown('AGE_HEADER_PRESENT')
  if (!headers.get('cache-control')?.split(',').some(token=>token.trim().toLowerCase()==='no-store')) return unknown('CACHE_POLICY_UNVERIFIED')
  return Object.freeze({status:'OBSERVED',source:'HTTP_DATE_HEADER_NOT_SAMPLE',date_iso,started_mono_ms:started,received_mono_ms:received,clock_accuracy_verified:false})
}
export type SampleAgeEstimate = Readonly<{ status: 'NO_SAMPLE'|'INVALID_SAMPLE'|'CLOCK_UNAVAILABLE'|'CLOCK_REGRESSED'|'SAMPLE_AFTER_CLOCK_ESTIMATE'|'ESTIMATED'; min_age_ms: string|null; max_age_ms: string|null; clock_accuracy_verified: false; monitoring_status: null; freshness_verified: false }>
/** A Date-header/round-trip heuristic range, not a clock-sync guarantee, sampling success or device health verdict. */
export function estimateSampleAge(clock: HttpClockObservation, sampledAt: string|null, nowMonotonic: number): SampleAgeEstimate {
  const result = (status: SampleAgeEstimate['status'], min_age_ms: string|null=null,max_age_ms: string|null=null): SampleAgeEstimate => Object.freeze({status,min_age_ms,max_age_ms,clock_accuracy_verified:false,monitoring_status:null,freshness_verified:false})
  if (sampledAt === null) return result('NO_SAMPLE')
  const sample = utcInstantNanos(sampledAt); if (sample === null) return result('INVALID_SAMPLE')
  if (clock.status !== 'OBSERVED') return result('CLOCK_UNAVAILABLE')
  if (!monotonic(nowMonotonic) || nowMonotonic < clock.received_mono_ms) return result('CLOCK_REGRESSED')
  const base = utcInstantNanos(clock.date_iso); if (base === null || !monotonic(clock.started_mono_ms) || !monotonic(clock.received_mono_ms) || clock.received_mono_ms < clock.started_mono_ms) return result('CLOCK_UNAVAILABLE')
  const elapsed = nowMonotonic-clock.received_mono_ms, trip = clock.received_mono_ms-clock.started_mono_ms
  const lower = base+BigInt(Math.floor(elapsed))*1_000_000n-sample, upper = base+(1000n+BigInt(Math.ceil(trip))+BigInt(Math.ceil(elapsed)))*1_000_000n-sample
  if (upper <= 0n) return result('SAMPLE_AFTER_CLOCK_ESTIMATE')
  return result('ESTIMATED',(lower<=0n?0n:lower/1_000_000n).toString(),((upper+999_999n)/1_000_000n).toString())
}
