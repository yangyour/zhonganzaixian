import {readSlaReport,type OnCallContext} from './onCallBoard'
export const SLA_REVIEW_MAX_BYTES=8*1024*1024
export const SLA_REVIEW_SCOPE='CURRENT_LOADED_LIVE_SERVICES_ONLY'
export interface SlaServiceReviewExport {csv:string;filename:string;serviceCount:number;period:string}
const fail=():never=>{throw Error('服务核对清单无效或超过 8 MiB，未生成截断文件；原月报仍保留。')}
function cell(value:unknown):string{
 let s=value===null||value===undefined?'':String(value)
 if(s.length>10000||/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\uD800-\uDFFF]/u.test(s))fail()
 // Quoting alone does not prevent formula execution. Keep risky text as escaped review text.
 if(/^\s*[=+@-]/u.test(s)||/^[\t\r]/.test(s)||/^0\d/.test(s)||/^\d{16,}$/.test(s))s="'"+s
 return '"'+s.replaceAll('"','""')+'"'
}
const columns = [
  'row_type',
  'export_scope',
  'tenant_id',
  'period',
  'observed_at',
  'timezone',
  'formula_version',
  'services_in_loaded_report',
  'formally_published',
  'financial_bill',
  'frozen_result_included',
  'service_id',
  'service_name',
  'target_pct',
  'verified_availability_pct',
  'upper_bound_availability_pct',
  'agreed_service_sec',
  'approved_exclusion_sec',
  'effective_service_sec',
  'unavailable_union_sec',
  'unknown_interval_sec',
  'known_available_sec',
  'incident_count',
  'unrecovered_count',
  'mtta_sample_count',
  'mtta_mean_sec',
  'mtta_p50_sec',
  'mtta_p95_sec',
  'mttr_sample_count',
  'mttr_unrecovered_count',
  'mttr_mean_sec',
  'mttr_p50_sec',
  'mttr_p95_sec',
  'status',
  'unknown_policy_status',
  'notice',
  'mtta_source_version',
  'mtta_unknown_count',
  'mttr_source_version',
  'mttr_unknown_count',
  'availability_source_version',
  'availability_known_available_milliseconds',
  'availability_effective_service_milliseconds',
  'availability_qualified_service_count',
  'availability_service_count',
  'availability_coverage_status',
  'availability_verified_percentage'
] as const
export function slaServiceReviewExport(raw: unknown, scope: OnCallContext, period: string): SlaServiceReviewExport {
  const report = readSlaReport(raw, scope, period),
    lines: string[] = []
  let bytes = 3
  const encoder = new TextEncoder()
  const append = (values: unknown[]) => {
    const line = values.map(cell).join(',') + '\r\n'
    bytes += encoder.encode(line).length
    if (bytes > SLA_REVIEW_MAX_BYTES) fail()
    lines.push(line)
  }
  append([...columns])
  const common = {
    export_scope: SLA_REVIEW_SCOPE,
    tenant_id: report.tenant_id,
    period: report.period,
    observed_at: report.observed_at,
    timezone: report.timezone,
    formula_version: report.formula_version,
    services_in_loaded_report: report.services.length,
    formally_published: false,
    financial_bill: false,
    frozen_result_included: false,
    availability_source_version: report.kpis.availability_aggregate?.source_version ?? null,
    availability_known_available_milliseconds: report.kpis.availability_aggregate?.known_available_milliseconds ?? null,
    availability_effective_service_milliseconds:
      report.kpis.availability_aggregate?.effective_service_milliseconds ?? null,
    availability_qualified_service_count: report.kpis.availability_aggregate?.qualified_service_count ?? null,
    availability_service_count: report.kpis.availability_aggregate?.service_count ?? null,
    availability_coverage_status: report.kpis.availability_aggregate?.coverage_status ?? null,
    availability_verified_percentage: report.kpis.availability_aggregate
      ? report.kpis.mean_verified_availability_pct
      : null
  }
  const record = (value: Record<string, unknown>) => append(columns.map((key) => value[key] ?? null))
  record({
    ...common,
    row_type: 'SUMMARY',
    notice:
      '仅核对本次已加载的服务可用性，不代表完整租户台账；不含工单明细、赔付计算或冻结月度快照，不是正式报告或账单。空值保持未知；危险文本和数字样式引用加单引号。'
  })
  for (const service of report.services) {
    const { mtta, mttr, interval_details: _intervals, ...facts } = service
    record({
      ...common,
      ...facts,
      row_type: 'SERVICE',
      mtta_sample_count: mtta.sample_count,
      mtta_mean_sec: mtta.mean_sec,
      mtta_p50_sec: mtta.p50_sec,
      mtta_p95_sec: mtta.p95_sec,
      mttr_sample_count: mttr.sample_count,
      mttr_unrecovered_count: mttr.unrecovered_count,
      mttr_mean_sec: mttr.mean_sec,
      mttr_p50_sec: mttr.p50_sec,
      mttr_p95_sec: mttr.p95_sec,
      mtta_source_version: mtta.source_version ?? null,
      mtta_unknown_count: mtta.unknown_count ?? null,
      mttr_source_version: mttr.source_version ?? null,
      mttr_unknown_count: mttr.unknown_count ?? null
    })
  }
  return {
    csv: '\ufeff' + lines.join(''),
    filename: `sla-services-${report.period}.csv`,
    serviceCount: report.services.length,
    period: report.period
  }
}
/** No request, storage mutation or publication. Caller must supply a current-scope guard. */
export function downloadSlaServiceReview(file:SlaServiceReviewExport,stillCurrent:()=>boolean){
 if(typeof stillCurrent!=='function'||!stillCurrent())return false
 if(!/^sla-services-(?!0000)\d{4}-(0[1-9]|1[0-2])\.csv$/.test(file.filename)||file.filename!==`sla-services-${file.period}.csv`||!file.csv.startsWith('\ufeff"row_type","export_scope"')||new TextEncoder().encode(file.csv).length>SLA_REVIEW_MAX_BYTES)fail()
 const owner=URL;if(typeof owner.createObjectURL!=='function'||typeof owner.revokeObjectURL!=='function')fail()
 const url=owner.createObjectURL(new Blob([file.csv],{type:'text/csv;charset=utf-8'}));let link:HTMLAnchorElement|null=null
 try{if(!stillCurrent())return false;link=document.createElement('a');link.href=url;link.download=file.filename;link.hidden=true;document.body.append(link);if(!stillCurrent())return false;link.click();return true}
 finally{link?.remove();setTimeout(()=>owner.revokeObjectURL(url),1000)}
}
