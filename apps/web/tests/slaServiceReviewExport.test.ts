// @vitest-environment jsdom
import {it,expect,vi,afterEach} from 'vitest'
import Papa from 'papaparse'
import wire from '../../../fixtures/customer-sla-controller.json'
import {slaServiceReviewExport,downloadSlaServiceReview,SLA_REVIEW_SCOPE} from '../src/utils/slaServiceReviewExport'
const scope={tenantId:wire.report.tenant_id,actorId:wire.report.monthly_result.evaluated_by,permissionVersion:3,canWriteInternal:true,canWriteConfig:false}
const data=():any=>structuredClone(wire.report)
const build=(value:unknown=wire.report)=>slaServiceReviewExport(value,scope,'2026-10')
const parse=(csv:string)=>Papa.parse<Record<string,string>>(csv,{header:true,skipEmptyLines:true})
function downloads(){vi.useFakeTimers();const create=vi.fn((_blob:Blob|MediaSource)=> 'blob:synthetic-review'),revoke=vi.fn(),Base=URL;vi.stubGlobal('URL',class extends Base{static createObjectURL=create;static revokeObjectURL=revoke});const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});return{create,revoke,click}}
afterEach(()=>{if(vi.isFakeTimers())vi.runOnlyPendingTimers();vi.useRealTimers();vi.restoreAllMocks();vi.unstubAllGlobals()})
it('exports all loaded rows with scope month observation and non-publication labels',()=>{const out=build(),p=parse(out.csv);expect(p.errors).toEqual([]);expect(out.filename).toBe('sla-services-2026-10.csv');expect(out.csv.startsWith('\ufeff')).toBe(true);expect(out.csv.endsWith('\r\n')).toBe(true);expect(p.data).toHaveLength(wire.report.services.length+1);for(const r of p.data){expect(r.export_scope).toBe(SLA_REVIEW_SCOPE);expect(r.tenant_id).toBe(scope.tenantId);expect(r.period).toBe('2026-10');expect(r.observed_at).toBe(wire.report.observed_at);expect(r.formally_published).toBe('false');expect(r.financial_bill).toBe('false');expect(r.frozen_result_included).toBe('false')}expect(p.data[1]!.verified_availability_pct).toBe('');expect(p.data[1]!.unavailable_union_sec).toBe('0');expect(p.data[1]!.status).toBe('INSUFFICIENT_SAMPLES')})
it('omits financial candidates frozen records ticket notes and interval text',()=>{const value=data();value.monthly_result.monthly_fee_fen=70000123;value.services[0].interval_details[0].explanation='PRIVATE_INTERVAL_NOT_EXPORTED';const csv=build(value).csv;expect(csv).not.toContain('70000123');expect(csv).not.toContain(value.monthly_result.id);expect(csv).not.toContain('PRIVATE_INTERVAL_NOT_EXPORTED');expect(csv).not.toContain('monthly_fee_fen');expect(csv).not.toContain('compensation_amount_fen')})
it('empty loaded set still includes scope summary and no fake healthy service',()=>{const value=data();value.services=[];const out=build(value),p=parse(out.csv);expect(out.serviceCount).toBe(0);expect(p.data).toHaveLength(1);expect(p.data[0]!.row_type).toBe('SUMMARY');expect(p.data[0]!.services_in_loaded_report).toBe('0');expect(p.data[0]!.status).toBe('')})
it.each(['=SUM(1,2)',' +CMD','-1+2','@RISK','000123','1234567890123456'])('neutralizes formula or numeric-style source name %s',name=>{const value=data();value.services[0].service_name=name;expect(parse(build(value).csv).data[1]!.service_name).toBe("'"+name)})
it('quotes Unicode commas and quotes without normalizing source labels',()=>{const value=data();value.services[0].service_name='合成😀,"设备"';expect(parse(build(value).csv).data[1]!.service_name).toBe(value.services[0].service_name)})
it('all received services are included in order independently of screen viewport',()=>{const value=data();value.services=Array.from({length:40},(_,i)=>({...structuredClone(value.services[0]),service_id:'SERVICE_'+i}));const out=build(value),p=parse(out.csv);expect(out.serviceCount).toBe(40);expect(p.data[40]!.service_id).toBe('SERVICE_39')})
it('refuses scope period malformed values and oversized exports instead of truncation',()=>{expect(()=>slaServiceReviewExport(wire.report,{...scope,tenantId:scope.actorId},'2026-10')).toThrow();expect(()=>slaServiceReviewExport(wire.report,scope,'2026-11')).toThrow();const broken=data();broken.services[0].known_available_sec='0';expect(()=>build(broken)).toThrow();const huge=data(),original=huge.services[0];huge.services=Array.from({length:1000},(_,i)=>({...structuredClone(original),service_id:'SERVICE_'+i,service_name:'中'.repeat(10000)}));expect(()=>build(huge)).toThrow('8 MiB')})
it('download uses bounded CSV blob and fixed filename and revokes URL',()=>{const x=downloads(),out=build();expect(downloadSlaServiceReview(out,()=>true)).toBe(true);expect(x.create).toHaveBeenCalledOnce();expect((x.create.mock.calls[0]![0] as Blob).type).toBe('text/csv;charset=utf-8');expect(x.click).toHaveBeenCalledOnce();expect((x.click.mock.instances[0] as HTMLAnchorElement).download).toBe(out.filename);expect(document.querySelector('a[download]')).toBeNull();vi.advanceTimersByTime(1000);expect(x.revoke).toHaveBeenCalledWith('blob:synthetic-review')})
it('scope revocation before or during object URL creation prevents click and cleans up',()=>{const x=downloads();expect(downloadSlaServiceReview(build(),()=>false)).toBe(false);expect(x.create).not.toHaveBeenCalled();let allowed=true;x.create.mockImplementation(()=>{allowed=false;return 'blob:synthetic-review'});expect(downloadSlaServiceReview(build(),()=>allowed)).toBe(false);expect(x.click).not.toHaveBeenCalled();vi.advanceTimersByTime(1000);expect(x.revoke).toHaveBeenCalledOnce()})
it('failed click still removes link and revokes the local URL',()=>{const x=downloads();x.click.mockImplementation(()=>{throw Error('synthetic click failure')});expect(()=>downloadSlaServiceReview(build(),()=>true)).toThrow();expect(document.querySelector('a[download]')).toBeNull();vi.advanceTimersByTime(1000);expect(x.revoke).toHaveBeenCalledOnce()})
it('anchor construction failure still revokes its owned object URL',()=>{const x=downloads();vi.spyOn(document,'createElement').mockImplementationOnce(()=>{throw Error('synthetic DOM failure')});expect(()=>downloadSlaServiceReview(build(),()=>true)).toThrow();expect(x.click).not.toHaveBeenCalled();vi.advanceTimersByTime(1000);expect(x.revoke).toHaveBeenCalledOnce()})
it.each(['filename','header','oversize'])('refuses altered download %s before creating a URL',kind=>{const x=downloads(),out=build();if(kind==='filename')out.filename='../outside.csv';else if(kind==='header')out.csv='unverified';else out.csv+='x'.repeat(8*1024*1024);expect(()=>downloadSlaServiceReview(out,()=>true)).toThrow();expect(x.create).not.toHaveBeenCalled()})

it('review CSV retains monitoring clock provenance and unknown counts without inventing legacy proof',()=>{
 const value=data()
 for(const row of value.services){
  Object.assign(row.mtta,{source_version:'mop-event-acknowledgement-v1',unknown_count:1,sample_count:1,mean_sec:null,p50_sec:null,p95_sec:null})
  Object.assign(row.mttr,{source_version:'mop-monitoring-recovery-t2-v1',unknown_count:1,sample_count:1,mean_sec:null,p50_sec:null,p95_sec:null})
 }
 const row=parse(build(value).csv).data[1]!
 expect(row.mtta_source_version).toBe('mop-event-acknowledgement-v1')
 expect(row.mttr_unknown_count).toBe('1')
 expect(row.mttr_mean_sec).toBe('')
 expect(parse(build().csv).data[1]!.mttr_source_version).toBe('')
})
