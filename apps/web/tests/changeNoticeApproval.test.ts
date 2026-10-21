import {describe,it,expect} from 'vitest'
import {readApprovalSnapshot,readApprovalHistory,readApprovalCommand} from '../src/utils/changeNoticeApproval'
import {approval,submitted,approved,event,history,row,scope,id,decided} from './changeNoticeApproval.fixture'
describe('approval strict readers (isolated fixtures)',()=>{
 it('checks exact identity, immutable digest and non-execution flags',async()=>{
  expect((await readApprovalSnapshot(approval(),scope,row())).state).toBe('DRAFT')
  for(const patch of [{tenant_id:id(99)},{notice_id:id(99)},{draft_revision:2},{content_sha256:'a'.repeat(64)},{change_summary:'tampered'},{asset_ids:[id(99)]},{effective:true},{device_execution_allowed:true},{alert_suppression_allowed:true},{automatic_p3_decision:false},{extra:'unknown'},{version:0},{allowed_actions:['EXECUTE']},{allowed_actions:['SUBMIT','SUBMIT']},{allowed_actions:['APPROVE']}])await expect(readApprovalSnapshot({...approval(),...patch},scope,row())).rejects.toThrow()
 })
 it('does not infer Q09 reviewer authority from role or technical capability',async()=>{
  const reviewer={...scope,actorId:id(9)},none=submitted()
  expect((await readApprovalSnapshot(none,reviewer,row())).allowed_actions).toEqual([])
  expect((await readApprovalSnapshot(submitted({allowed_actions:['APPROVE','REJECT']}),reviewer,row())).allowed_actions).toEqual(['APPROVE','REJECT'])
  await expect(readApprovalSnapshot(submitted({allowed_actions:['APPROVE']}),scope,row())).rejects.toThrow()
  await expect(readApprovalSnapshot(approval(),reviewer,row())).rejects.toThrow()
 })
 it('requires consistent decisions and preserves unknown optional values',async()=>{
  expect((await readApprovalSnapshot(approved(),scope,row())).effective).toBe(false)
  for(const patch of [{reviewer_id:scope.actorId},{review_policy_release_id:null},{decided_at:null},{submitted_at:null},{state:'REJECTED',decision_note:null},{allowed_actions:['APPROVE']}])await expect(readApprovalSnapshot({...approved(),...patch},scope,row())).rejects.toThrow()
  await expect(readApprovalSnapshot(approval({reviewer_id:id(9)}),scope,row())).rejects.toThrow()
 })
 it('validates exact commands without trimming note or relaxing CAS',()=>{
  expect(readApprovalCommand({expected_version:1,action:'SUBMIT',note:' 原文😀\n '})).toEqual({expected_version:1,action:'SUBMIT',note:' 原文😀\n '})
  for(const value of [{expected_version:0,action:'SUBMIT',note:null},{expected_version:1,action:'REJECT',note:null},{expected_version:1,action:'REJECT',note:'   '},{expected_version:1,action:'SUBMIT'},{expected_version:1,action:'SUBMIT',note:'x',extra:1},{expected_version:1,action:'SUBMIT',note:'x'.repeat(2001)}])expect(()=>readApprovalCommand(value)).toThrow()
 })
 it('checks bounded history identity, actors, sequence, safety and transition chain',()=>{
  const decision=event({version:3,from_state:'IN_REVIEW',to_state:'APPROVED',action:'APPROVE',actor_id:id(9),review_policy_release_id:id(10),occurred_at:decided})
  expect(readApprovalHistory(history([decision,event()]),scope,row()).items).toHaveLength(2)
  expect(readApprovalHistory(history(),scope,row()).items).toEqual([])
  for(const patch of [{tenant_id:id(99)},{draft_revision:2},{next_before_version:2},{items:[event({version:3})]},{items:[event({actor_id:id(99)})]},{items:[event({to_state:'APPROVED'})]},{items:[{...event(),effective:true}]},{items:[decision,event({to_state:'WITHDRAWN',action:'WITHDRAW'})]}])expect(()=>readApprovalHistory({...history([event()]),...patch},scope,row())).toThrow()
 })
})
