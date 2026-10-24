import type { FollowupPage, FollowupQuery } from '../src/utils/reportFollowups'
export const id=(n:number)=>'79000000-0000-4000-8000-'+String(n).padStart(12,'0')
export const tenant=id(1)
export const query:FollowupQuery={from:'2026-10-01T00:00:00Z',until:'2026-11-01T00:00:00Z',filter:'FOLLOWUP_DUE',before:null}
export function followups(q:FollowupQuery=query):FollowupPage{
 const indexes=q.before?[2,1]:Array.from({length:20},(_,i)=>22-i)
 const items=indexes.map(n=>({document_id:id(100+n),label:'Synthetic report '+n,document_sha256:'a'.repeat(64),state_version:5,captured_at:'2026-10-01T00:00:00Z',published_at:'2026-10-02T00:00:00Z',total_recipients:2,read_count:1,unread_count:1,followup_due_count:1}))
 return {tenant_id:tenant,observed_at:'2026-10-24T00:00:00Z',query:q,scanned_documents:25,published_reports:22,unread_reports:22,followup_due_reports:22,matching_reports:22,total_recipients:44,read_count:22,unread_count:22,followup_due_count:22,items,next:q.before?null:{published_at:items[19]!.published_at,document_id:items[19]!.document_id},population_scope:'COMPLETE_BOUNDED_CAPTURE_WINDOW',document_scan_limit:1000,recipient_scan_limit:50000,page_size:20,external_dispatch_enabled:false,writes_performed:false}
}
