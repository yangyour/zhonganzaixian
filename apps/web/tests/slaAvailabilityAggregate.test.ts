import { it, expect } from 'vitest'
import Papa from 'papaparse'
import wire from '../../../fixtures/customer-sla-controller.json'
import { readSlaReport } from '../src/utils/onCallBoard'
import { slaServiceReviewExport } from '../src/utils/slaServiceReviewExport'
import { weightedAvailabilityReport, insufficientAvailabilityReport } from './slaAvailabilityAggregate.fixture'
const scope = {
  tenantId: wire.report.tenant_id,
  actorId: wire.report.monthly_result.evaluated_by,
  permissionVersion: 3,
  canWriteInternal: true,
  canWriteConfig: true
}
const read = (report: unknown) => readSlaReport(report, scope, '2026-10')

it('accepts the duration-weighted percentage and preserves the original frozen financial record', () => {
  const report = read(weightedAvailabilityReport())
  expect(report.kpis.mean_verified_availability_pct).toBe(90.9091)
  expect(report.monthly_result).toEqual(wire.report.monthly_result)
  expect(report.kpis.availability_aggregate?.effective_service_milliseconds).toBe(1_100_000)
})
it('rejects an arithmetic mean carrying an exact-duration source tag', () => {
  const report = weightedAvailabilityReport()
  report.kpis.mean_verified_availability_pct = 50
  expect(() => read(report)).toThrow()
})
it('does not silently remove one insufficient service from the aggregate', () => {
  const report = insufficientAvailabilityReport()
  expect(read(report).kpis.mean_verified_availability_pct).toBeNull()
  report.kpis.mean_verified_availability_pct = 100
  expect(() => read(report)).toThrow()
})
it.each([
  'source_version',
  'effective_service_milliseconds',
  'known_available_milliseconds',
  'service_count',
  'qualified_service_count',
  'coverage_status'
])('rejects inconsistent aggregate field %s', (key) => {
  const report = weightedAvailabilityReport(),
    a = report.kpis.availability_aggregate
  a[key] = key === 'source_version' ? 'UNKNOWN' : key === 'coverage_status' ? 'COMPLETE' : Number(a[key]) + 1000
  expect(() => read(report)).toThrow()
})
it('preserves subsecond measures and rounds only the final percentage', () => {
  const report = weightedAvailabilityReport()
  Object.assign(report.services[0], {
    agreed_service_sec: 1,
    effective_service_sec: 1,
    known_available_sec: 0,
    unavailable_union_sec: 0,
    verified_availability_pct: 10,
    upper_bound_availability_pct: 10
  })
  Object.assign(report.services[1], {
    agreed_service_sec: 0,
    effective_service_sec: 0,
    known_available_sec: 0,
    unavailable_union_sec: 0,
    verified_availability_pct: 100,
    upper_bound_availability_pct: 100
  })
  Object.assign(report.kpis.availability_aggregate, {
    known_available_milliseconds: 200,
    effective_service_milliseconds: 1100
  })
  report.kpis.unavailable_union_total_sec = 0
  report.kpis.mean_verified_availability_pct = 18.1818
  expect(read(report).kpis.mean_verified_availability_pct).toBe(18.1818)
})
it('no-service source is explicit unknown rather than 100 percent', () => {
  const report = weightedAvailabilityReport()
  report.services = []
  report.kpis.unavailable_union_total_sec = 0
  report.kpis.mean_verified_availability_pct = null
  Object.assign(report.kpis.availability_aggregate, {
    known_available_milliseconds: 0,
    effective_service_milliseconds: 0,
    service_count: 0,
    qualified_service_count: 0,
    coverage_status: 'NO_SERVICES'
  })
  expect(read(report).kpis.mean_verified_availability_pct).toBeNull()
  report.kpis.mean_verified_availability_pct = 100
  expect(() => read(report)).toThrow()
})
it('legacy projections remain readable without fabricated duration evidence', () => {
  expect(read(wire.report).kpis.availability_aggregate).toBeUndefined()
})
it('CSV includes the same aggregate numerator denominator and source while legacy cells stay empty', () => {
  const parsed = (value: unknown) =>
    Papa.parse<Record<string, string>>(slaServiceReviewExport(value, scope, '2026-10').csv, {
      header: true,
      skipEmptyLines: true
    }).data
  const report = weightedAvailabilityReport(),
    rows = parsed(report)
  expect(rows[0]).toMatchObject({
    availability_source_version: 'mop-availability-effective-milliseconds-v1',
    availability_known_available_milliseconds: '1000000',
    availability_effective_service_milliseconds: '1100000',
    availability_verified_percentage: '90.9091'
  })
  expect(rows[1]!.availability_effective_service_milliseconds).toBe(
    rows[0]!.availability_effective_service_milliseconds
  )
  expect(parsed(wire.report)[0]!.availability_source_version).toBe('')
})
