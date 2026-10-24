import { expect,it } from 'vitest'
import { readReportFollowups } from '../src/utils/reportFollowups'
import { followups,query,tenant,id } from './reportFollowups.fixture'
it('validates complete counts independently of page and preserves exact cursor',()=>{
 const first=followups();expect(readReportFollowups(first,tenant,query)).toEqual(first)
 const q={...query,before:first.next};expect(readReportFollowups(followups(q),tenant,q).items).toHaveLength(2)
})
it.each(['tenant_id','observed_at','query','population_scope','document_scan_limit','recipient_scan_limit','page_size','external_dispatch_enabled','writes_performed','scanned_documents','published_reports','unread_reports','followup_due_reports','matching_reports','total_recipients','read_count','unread_count','followup_due_count','next','items'])('rejects invalid %s',(field)=>{expect(()=>readReportFollowups({...followups(),[field]:'invalid'},tenant,query)).toThrow()})
it('rejects ordering, duplicates, future publication, false due classification and truncation',()=>{
 for(const change of [
  (p:ReturnType<typeof followups>)=>{p.items.reverse()},(p:ReturnType<typeof followups>)=>{p.items[1]={...p.items[0]!}},
  (p:ReturnType<typeof followups>)=>{p.items[0]!.published_at='2026-10-25T00:00:00Z'},
  (p:ReturnType<typeof followups>)=>{p.observed_at='2026-10-09T00:00:00Z'},
  (p:ReturnType<typeof followups>)=>{p.items.pop()},(p:ReturnType<typeof followups>)=>{p.next!.document_id=id(999)},
  (p:ReturnType<typeof followups>)=>{p.items[0]!.unread_count=0},
  (p:ReturnType<typeof followups>)=>{p.items[0]!.captured_at=query.until},
 ]){const p=followups();change(p);expect(()=>readReportFollowups(p,tenant,query)).toThrow()}
})
it('rejects cursor or window mismatch and partial/global-count substitution',()=>{
 const p=followups();expect(()=>readReportFollowups(p,tenant,{...query,from:'2026-09-01T00:00:00Z'})).toThrow()
 expect(()=>readReportFollowups(p,tenant,{...query,before:p.next})).toThrow()
 expect(()=>readReportFollowups({...p,matching_reports:20},tenant,query)).toThrow()
 expect(()=>readReportFollowups({...p,read_count:0},tenant,query)).toThrow()
})
