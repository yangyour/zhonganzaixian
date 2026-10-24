import wire from '../../../fixtures/customer-sla-controller.json' with { type: 'json' }
/** Explicit synthetic report populations for duration weighting and unknown-coverage boundaries. */
export function weightedAvailabilityReport() {
  const report = structuredClone(wire.report) as any,
    base = report.services[0]
  const row = (id: string, seconds: number, available: number, pct: number | null) => ({
    ...structuredClone(base),
    service_id: id,
    service_name: 'SYNTHETIC_' + id,
    target_pct: 99.9,
    agreed_service_sec: seconds,
    approved_exclusion_sec: 0,
    effective_service_sec: seconds,
    unknown_interval_sec: 0,
    unavailable_union_sec: seconds - available,
    known_available_sec: available,
    verified_availability_pct: pct,
    upper_bound_availability_pct: pct,
    status: pct === null ? 'INSUFFICIENT_SAMPLES' : pct >= 99.9 ? 'MET' : 'NOT_MET',
    unknown_policy_status: 'NO_UNKNOWN_GAPS',
    interval_details: []
  })
  report.services = [row('SERVICE_A', 1000, 1000, 100), row('SERVICE_B', 100, 0, 0)]
  report.kpis.mean_verified_availability_pct = 90.9091
  report.kpis.unavailable_union_total_sec = 100
  report.kpis.unknown_interval_total_sec = 0
  report.kpis.availability_aggregate = {
    source_version: 'mop-availability-effective-milliseconds-v1',
    known_available_milliseconds: 1_000_000,
    effective_service_milliseconds: 1_100_000,
    service_count: 2,
    qualified_service_count: 2,
    coverage_status: 'ALL_SERVICES_QUALIFIED'
  }
  return report
}
export function insufficientAvailabilityReport() {
  const report = weightedAvailabilityReport(),
    row = report.services[1]
  Object.assign(row, {
    effective_service_sec: 0,
    known_available_sec: 0,
    unavailable_union_sec: 0,
    unknown_interval_sec: 100,
    unknown_policy_status: 'UNKNOWN_EXCLUDED_FROM_NUMERATOR_AND_DENOMINATOR',
    verified_availability_pct: null,
    upper_bound_availability_pct: null,
    status: 'INSUFFICIENT_SAMPLES'
  })
  report.kpis.mean_verified_availability_pct = null
  report.kpis.unknown_interval_total_sec = 100
  report.kpis.unavailable_union_total_sec = 0
  Object.assign(report.kpis.availability_aggregate, {
    effective_service_milliseconds: 1_000_000,
    qualified_service_count: 1,
    coverage_status: 'INSUFFICIENT_COVERAGE'
  })
  return report
}
