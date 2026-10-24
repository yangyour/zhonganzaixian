import { ApiError } from '../api/client'
import { isUtcInstant, utcInstantNanos } from './utcInstant'
import type { ReadershipFilter } from './reportReadership'
export interface FollowupCursor { published_at: string; document_id: string }
export interface FollowupQuery { from: string; until: string; filter: ReadershipFilter; before: FollowupCursor | null }
export interface FollowupRow {
 document_id: string; label: string; document_sha256: string; state_version: number; captured_at: string; published_at: string
 total_recipients: number; read_count: number; unread_count: number; followup_due_count: number
}
export interface FollowupPage {
 tenant_id: string; observed_at: string; query: FollowupQuery; scanned_documents: number; published_reports: number
 unread_reports: number; followup_due_reports: number; matching_reports: number; total_recipients: number; read_count: number
 unread_count: number; followup_due_count: number; items: FollowupRow[]; next: FollowupCursor | null
 population_scope: 'COMPLETE_BOUNDED_CAPTURE_WINDOW'; document_scan_limit: 1000; recipient_scan_limit: 50000; page_size: 20
 external_dispatch_enabled: false; writes_performed: false
}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const time=(v:unknown):v is string=>typeof v==='string'&&isUtcInstant(v)&&utcInstantNanos(v)!%1000000n===0n
const count=(v:unknown,max:number):v is number=>Number.isSafeInteger(v)&&Number(v)>=0&&Number(v)<=max
const invalid=()=>new ApiError(200,'INVALID_REPORT_FOLLOWUPS','报告跟进列表未通过范围、时间或完整计数核对','—')
const cursor=(v:unknown):v is FollowupCursor=>object(v)&&uuid(v.document_id)&&time(v.published_at)
const sameCursor=(a:unknown,b:FollowupCursor|null)=>a===null?b===null:cursor(a)&&b!==null&&a.document_id===b.document_id&&a.published_at===b.published_at
export function readReportFollowups(raw:unknown,tenant:string,query:FollowupQuery):FollowupPage {
 if(!object(raw)||raw.tenant_id!==tenant||!time(raw.observed_at)||!object(raw.query)||raw.query.from!==query.from||raw.query.until!==query.until
  ||raw.query.filter!==query.filter||!sameCursor(raw.query.before,query.before)||!time(query.from)||!time(query.until)||utcInstantNanos(query.from)!>=utcInstantNanos(query.until)!
  ||raw.population_scope!=='COMPLETE_BOUNDED_CAPTURE_WINDOW'||raw.document_scan_limit!==1000||raw.recipient_scan_limit!==50000||raw.page_size!==20
  ||raw.external_dispatch_enabled!==false||raw.writes_performed!==false||!Array.isArray(raw.items)||raw.items.length>20)throw invalid()
 for(const k of ['scanned_documents','published_reports','unread_reports','followup_due_reports','matching_reports'])if(!count(raw[k],1000))throw invalid()
 for(const k of ['total_recipients','read_count','unread_count','followup_due_count'])if(!count(raw[k],50000))throw invalid()
 const p=raw as unknown as FollowupPage
 if(p.published_reports>p.scanned_documents||p.unread_reports>p.published_reports||p.followup_due_reports>p.unread_reports
  ||p.total_recipients<p.published_reports||p.total_recipients>p.published_reports*1000||p.read_count+p.unread_count!==p.total_recipients
  ||p.followup_due_count>p.unread_count||p.unread_count<p.unread_reports||p.followup_due_count<p.followup_due_reports
  ||p.matching_reports!==(query.filter==='ALL'?p.published_reports:query.filter==='UNREAD'?p.unread_reports:p.followup_due_reports)
  ||p.items.length>p.matching_reports||!query.before&&p.items.length!==Math.min(20,p.matching_reports))throw invalid()
 let prior=query.before;const ids=new Set<string>();let total=0,unread=0,due=0,read=0
 for(const r of p.items){
  if(!object(r)||!uuid(r.document_id)||ids.has(r.document_id)||typeof r.label!=='string'||!r.label.trim()||r.label.length>500
   ||typeof r.document_sha256!=='string'||! /^[a-f0-9]{64}$/.test(r.document_sha256)||!count(r.state_version,9007199254740990)||r.state_version<1
   ||!time(r.captured_at)||!time(r.published_at)||utcInstantNanos(r.captured_at)!<utcInstantNanos(query.from)!||utcInstantNanos(r.captured_at)!>=utcInstantNanos(query.until)!
   ||utcInstantNanos(r.published_at)!<utcInstantNanos(r.captured_at)!||utcInstantNanos(r.published_at)!>utcInstantNanos(p.observed_at)!)throw invalid()
  for(const k of ['total_recipients','read_count','unread_count','followup_due_count'] as const)if(!count(r[k],1000))throw invalid()
  if(r.total_recipients<1||r.read_count+r.unread_count!==r.total_recipients||r.followup_due_count>r.unread_count
   ||query.filter==='UNREAD'&&r.unread_count===0||query.filter==='FOLLOWUP_DUE'&&r.followup_due_count===0
   ||r.followup_due_count>0&&utcInstantNanos(p.observed_at)!-utcInstantNanos(r.published_at)!<=604800000000000n
   ||prior&&(utcInstantNanos(r.published_at)!>utcInstantNanos(prior.published_at)!
       ||utcInstantNanos(r.published_at)===utcInstantNanos(prior.published_at)&&r.document_id>=prior.document_id))throw invalid()
  ids.add(r.document_id);prior={document_id:r.document_id,published_at:r.published_at};total+=r.total_recipients;read+=r.read_count;unread+=r.unread_count;due+=r.followup_due_count
 }
 if(total>p.total_recipients||read>p.read_count||unread>p.unread_count||due>p.followup_due_count
  ||p.next!==null&&(!cursor(p.next)||p.items.length!==20||!sameCursor(p.next,prior))
  ||!query.before&&(p.next!==null)!==(p.matching_reports>20))throw invalid()
 return p
}
