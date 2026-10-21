import {it,expect,vi} from 'vitest'
import capture from '../../../fixtures/oncall-http-synthetic-20261021.json'
import {readOnCallBoard,createOnCallBoardWorkspace} from '../src/utils/onCallBoard'
const scope=capture.contexts.msp
const board=()=>structuredClone(capture.board) as any
const changes:Array<[string,(v:any)=>void]>=[
 ['negative count',v=>v.kpis.unacknowledged_count=-1],
 ['numeric string count',v=>v.kpis.critical_count='3'],
 ['fractional count',v=>v.kpis.in_progress_count=0.5],
 ['unsafe count',v=>v.kpis.pending_review_count=Number.MAX_SAFE_INTEGER+1],
 ['missing latency',v=>delete v.kpis.mtta_p50_sec],
 ['nonfinite latency',v=>v.kpis.mtta_p50_sec=Infinity],
 ['negative latency',v=>v.kpis.mtta_p50_sec=-1],
 ['zero target',v=>v.kpis.mtta_target_sec=0],
 ['unknown queue field',v=>v.queue[0].secret='unexpected'],
 ['invalid ticket identity',v=>v.queue[0].ticket_id='not-uuid'],
 ['duplicate ticket',v=>v.queue.push(structuredClone(v.queue[0]))],
 ['wrong queue state',v=>v.queue[0].state='CLOSED'],
 ['wrong priority',v=>v.queue[0].priority='P0'],
 ['bad tier',v=>v.queue[0].tier_snapshot='FALLBACK'],
 ['bad assignee',v=>v.queue[0].assignee_user_id='unknown'],
 ['impossible date',v=>v.queue[0].created_at='2026-02-30T00:00:00Z'],
 ['local due time',v=>v.queue[0].response_due_at='2026-10-21T10:00:00+08:00'],
 ['string countdown',v=>v.queue[0].countdown_remaining_sec='0'],
 ['nonfinite progress',v=>v.queue[0].countdown_progress_pct=NaN],
 ['progress above one',v=>v.queue[0].countdown_progress_pct=1.001],
 ['missing progress with known countdown',v=>v.queue[0].countdown_progress_pct=null],
 ['progress with unknown countdown',v=>v.queue[0].countdown_remaining_sec=null],
 ['boolean breach string',v=>v.queue[0].breached='false'],
 ['zero current step',v=>v.queue[0].current_escalation_step=0],
 ['jumped next step',v=>v.queue[0].next_escalation_step=8],
 ['next step without role',v=>v.queue[0].next_escalation_target_role=null],
 ['role without next step',v=>v.queue[0].next_escalation_step=null],
 ['roster wrong tenant',v=>v.rosters[0].tenant_id='55000000-0000-4000-8000-000000000999'],
 ['roster bad id',v=>v.rosters[0].id='not-uuid'],
 ['zero roster version',v=>v.rosters[0].version=0],
 ['invalid roster leap day',v=>v.rosters[0].shift_date='2026-02-29'],
 ['primary equals backup',v=>v.rosters[0].backup_oncall_user_id=v.rosters[0].primary_oncall_user_id],
 ['empty note',v=>v.rosters[0].note=' '],
 ['long roster note',v=>v.rosters[0].note='a'.repeat(501)],
 ['duplicate roster identity',v=>v.rosters.push(structuredClone(v.rosters[0]))],
 ['duplicate roster slot',v=>{const r=structuredClone(v.rosters[0]);r.id='55000000-0000-4000-8000-000000000999';v.rosters.push(r)}],
 ['handoff bad actor',v=>v.handoffs[0].incoming_user_id='bad'],
 ['handoff same actor',v=>v.handoffs[0].incoming_user_id=v.handoffs[0].outgoing_user_id],
 ['handoff negative count',v=>v.handoffs[0].unacknowledged_count=-1],
 ['handoff over database count limit',v=>v.handoffs[0].pending_review_count=1000001],
 ['handoff empty note',v=>v.handoffs[0].note=''],
 ['duplicate handoff',v=>v.handoffs.push(structuredClone(v.handoffs[0]))],
 ['duplicate policy',v=>v.policies=[v.policies[0],structuredClone(v.policies[0])]],
 ['policy persisted string',v=>v.policies[0].persisted='false'],
 ['zero policy trigger threshold',v=>v.policies[0].unanswered_escalate_count=0],
 ['policy step gap',v=>v.policies[0].steps[1].step_order=7],
 ['negative policy wait',v=>v.policies[0].steps[0].wait_minutes=-1],
 ['fractional policy wait',v=>v.policies[0].steps[0].wait_minutes=0.5],
 ['wrong policy role',v=>v.policies[0].steps[0].target_role='ROOT'],
 ['step run foreign tenant',v=>v.step_runs[0].tenant_id='55000000-0000-4000-8000-000000000999'],
 ['step run missing field',v=>delete v.step_runs[0].recorded_by],
 ['step run numeric string',v=>v.step_runs[0].unanswered_phone_count='2'],
 ['step run bad timestamp',v=>v.step_runs[0].recorded_at='yesterday'],
 ['step run wrong policy version',v=>v.step_runs[0].policy_version=0],
 ['step run jumped destination',v=>v.step_runs[0].escalated_to_step=8],
 ['step run inconsistent sms',v=>{v.step_runs[0].sms_notice_status='NOT_REQUIRED';v.step_runs[0].sms_notice_summary='claims sent'}],
 ['step run fake delivery status',v=>v.step_runs[0].sms_notice_status='DELIVERED'],
 ['duplicate step run',v=>v.step_runs.push(structuredClone(v.step_runs[0]))],
]
it.each(changes)('rejects malformed board: %s',(_name,change)=>{const v=board();change(v);expect(()=>readOnCallBoard(v,scope)).toThrow()})
it('retains legal zeros, negative overdue countdown and unknown latency without inventing values',()=>{const v=board();for(const key of ['unacknowledged_count','critical_count','in_progress_count','pending_review_count','recovered_today_count'])v.kpis[key]=0;v.kpis.mtta_p50_sec=null;v.queue[0].countdown_remaining_sec=-1;v.queue[0].countdown_progress_pct=1;v.queue[0].breached=true;const parsed=readOnCallBoard(v,scope);expect(parsed.kpis.mtta_p50_sec).toBeNull();expect(parsed.kpis.in_progress_count).toBe(0);expect(parsed.queue[0]!.countdown_remaining_sec).toBe(-1)})
it('supports genuinely unknown clock and no-next-step tuples without inferring an absent policy',()=>{const v=board();v.queue[0].response_due_at=null;v.queue[0].recovery_due_at=null;v.queue[0].countdown_remaining_sec=null;v.queue[0].countdown_progress_pct=null;v.queue[0].next_escalation_step=null;v.queue[0].next_escalation_target_role=null;expect(readOnCallBoard(v,scope).queue[0]!.countdown_remaining_sec).toBeNull()})
it('does not derive global KPI totals or previous-clock breach from limited queue entries',()=>{const v=board();v.kpis.critical_count=100;v.queue[0].breached=true;v.queue[0].countdown_remaining_sec=10;v.queue[0].countdown_progress_pct=0.9;expect(readOnCallBoard(v,scope).kpis.critical_count).toBe(100)})
it('accepts default policies only as version zero without a claimed author',()=>{const v=board();for(const p of v.policies){p.persisted=false;p.version=0;p.updated_by=null}expect(readOnCallBoard(v,scope).policies[0]!.persisted).toBe(false);v.policies[0].updated_by=scope.actorId;expect(()=>readOnCallBoard(v,scope)).toThrow();v.policies[0].updated_by=null;v.policies[0].version=1;expect(()=>readOnCallBoard(v,scope)).toThrow()})
it('persisted policies require positive version and a valid author',()=>{const v=board();v.policies[0].persisted=true;v.policies[0].version=1;v.policies[0].updated_by=scope.actorId;expect(()=>readOnCallBoard(v,scope)).not.toThrow();v.policies[0].updated_by=null;expect(()=>readOnCallBoard(v,scope)).toThrow()})
it('rejects response rows beyond the exact workspace request bound without truncating',async()=>{const v=board();v.queue=Array.from({length:26},(_,i)=>({...v.queue[0],ticket_id:`55000000-0000-4000-8000-${String(i+1).padStart(12,'0')}`}));expect(()=>readOnCallBoard(v,scope,25)).toThrow();expect(readOnCallBoard(v,scope,100).queue).toHaveLength(26);const request=vi.fn().mockResolvedValue(v),w=createOnCallBoardWorkspace({request},()=>{});w.reset(scope);expect(await w.load()).toBe(false);expect(w.state().board).toBeNull();expect(w.state().readFailed).toBe(true);expect(request.mock.calls[0]![0]).toBe('/oncall/board?page_size=25')})
it('malformed refresh removes old valid data and successful retry is a GET only',async()=>{const v=board();v.kpis.critical_count='not-a-number';const request=vi.fn().mockResolvedValueOnce(board()).mockResolvedValueOnce(v).mockResolvedValueOnce(board()),w=createOnCallBoardWorkspace({request},()=>{});w.reset(scope);expect(await w.load()).toBe(true);expect(await w.load()).toBe(false);expect(w.state().board).toBeNull();expect(await w.load()).toBe(true);expect(w.state().readFailed).toBe(false);expect(request.mock.calls.every(c=>!c[1]?.method)).toBe(true)})
it('validated data has defensive ownership',()=>{const v=board(),parsed=readOnCallBoard(v,scope);v.queue[0].priority='P0';expect(parsed.queue[0]!.priority).toBe('P1')})
