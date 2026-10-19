import {describe,it,expect,vi} from 'vitest'
import {readFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {ApiError} from '../src/api/client'
import {
 createOnCallBoardWorkspace,createSlaReportWorkspace,formatCountdown,formatDurationSec,formatFenToYuan,
 readOnCallBoard,readSlaReport,readSlaServiceDetail,type OnCallContext
} from '../src/utils/onCallBoard'

const tenant='55000000-0000-4000-8000-000000000001'
const actor='55000000-0000-4000-8000-000000000011'
const incoming='55000000-0000-4000-8000-000000000012'
const ticket='55000000-0000-4000-8000-000000000077'
const rosterId='55000000-0000-4000-8000-000000000088'
const handoffId='55000000-0000-4000-8000-000000000089'
const stepRunId='55000000-0000-4000-8000-000000000090'
const monthlyId='55000000-0000-4000-8000-000000000091'
const at='2026-10-18T10:00:00Z'

const padminScope:OnCallContext={tenantId:tenant,actorId:actor,permissionVersion:4,canWriteInternal:true,canWriteConfig:true}
const mspScope:OnCallContext={...padminScope,canWriteConfig:false}

const boardPayload=(patch:Record<string,unknown>={})=>({
 tenant_id:tenant,
 kpis:{
  unacknowledged_count:2,critical_count:1,in_progress_count:3,
  pending_review_count:1,recovered_today_count:4,mtta_p50_sec:210,mtta_target_sec:900
 },
 queue:[{
  ticket_id:ticket,priority:'P1',state:'OPEN',tier_snapshot:'STANDARD',
  assignee_user_id:actor,created_at:at,response_due_at:'2026-10-18T10:10:00Z',
  recovery_due_at:'2026-10-18T12:00:00Z',countdown_remaining_sec:300,
  countdown_progress_pct:0.5,breached:false,current_escalation_step:1,
  next_escalation_step:2,next_escalation_target_role:'L2_ONCALL'
 }],
 rosters:[{
  id:rosterId,tenant_id:tenant,version:1,shift_date:'2026-10-18',shift_code:'DAY',
  handoff_local_time:'18:00',primary_oncall_user_id:actor,backup_oncall_user_id:incoming,
  note:'SYNTHETIC_ROSTER',roster_policy_status:'CONFIG_PENDING_Q15',updated_by:actor,updated_at:at
 }],
 handoffs:[{
  id:handoffId,tenant_id:tenant,roster_id:rosterId,outgoing_user_id:actor,incoming_user_id:incoming,
  handoff_local_time:'18:00',unacknowledged_count:2,in_progress_count:3,pending_review_count:1,
  note:'SYNTHETIC_1800_HANDOFF',handed_off_at:at
 }],
 policies:[{
  tenant_id:tenant,tier:'STANDARD',version:1,unanswered_escalate_count:2,
  unanswered_retry_interval_sec:null,
  steps:[
   {step_order:1,trigger_condition:'IMMEDIATE',wait_minutes:0,target_role:'L1_ONCALL'},
   {step_order:2,trigger_condition:'UNACKNOWLEDGED',wait_minutes:15,target_role:'L2_ONCALL'}
  ],
  policy_status:'CONFIG_PENDING_Q15',updated_by:actor,updated_at:at,persisted:true
 }],
 step_runs:[{
  id:stepRunId,tenant_id:tenant,ticket_id:ticket,tier:'STANDARD',policy_version:1,
  step_order:1,trigger_condition:'PHONE_UNANSWERED',wait_minutes:0,target_role:'L1_ONCALL',
  unanswered_phone_count:2,escalated_to_step:2,escalated_target_role:'L2_ONCALL',
  sms_notice_status:'INTENT_RECORDED_PENDING_GATEWAY',sms_notice_summary:'电话连续2次未接听自动升级并登记短信记录',
  recorded_by:actor,recorded_at:at
 }],
 auto_refresh_sec:15,handoff_local_time:'18:00',roster_policy_status:'CONFIG_PENDING_Q15',
 observed_at:at,source:'postgresql',...patch
})

const serviceRow=(patch:Record<string,unknown>={})=>({
 service_id:'CORE_NETWORK',service_name:'核心网络互联服务',target_pct:99.9,
 agreed_service_sec:86400,approved_exclusion_sec:3600,effective_service_sec:82800,
 unavailable_union_sec:1800,unknown_interval_sec:1800,known_available_sec:79200,
 verified_availability_pct:95.6522,upper_bound_availability_pct:97.8261,
 incident_count:2,
 mtta:{sample_count:2,unrecovered_count:0,mean_sec:240,p50_sec:240,p95_sec:300},
 mttr:{sample_count:2,unrecovered_count:0,mean_sec:1800,p50_sec:1800,p95_sec:2400},
 unrecovered_count:0,status:'UNKNOWN_PENDING_Q08',unknown_policy_status:'ISOLATED_PENDING_Q08',
 interval_details:[{
  kind:'UNKNOWN_ISOLATED',start:'2026-10-18T04:00:00Z',end:'2026-10-18T04:30:00Z',
  duration_sec:1800,explanation:'监控缺失或P3次日口径未决未知区间（1800s，单独隔离不默认视为可用，待Q08确认）'
 }],...patch
})

const monthlyEval=(patch:Record<string,unknown>={})=>({
 tier_snapshot:'STANDARD',total_ticket_count:10,applicable_count:8,met_count:6,
 not_met_count:2,unresolved_count:1,not_applicable_count:1,
 achievement_numerator:6,achievement_denominator:8,achievement_rate_pct:75,
 compensation_ratio:0.2,monthly_fee_fen:2000000,compensation_amount_fen:null,
 compensation_status:'CONFIG_PENDING_Q26',candidate_fixed_ratio_fen:400000,
 candidate_shortfall_weighted_fen:100000,
 formula_version:'D16_SLA_AVAILABILITY_AND_COMPENSATION_V1',items:[],...patch
})

const reportPayload=(patch:Record<string,unknown>={})=>({
 tenant_id:tenant,period:'2026-10',current_tier:'STANDARD',target_availability_pct:99.9,
 kpis:{
  mean_verified_availability_pct:95.6522,unavailable_union_total_sec:1800,
  mtta:{sample_count:2,unrecovered_count:0,mean_sec:240,p50_sec:240,p95_sec:300},
  mttr:{sample_count:2,unrecovered_count:0,mean_sec:1800,p50_sec:1800,p95_sec:2400},
  unrecovered_total_count:0,unknown_interval_total_sec:1800
 },
 services:[serviceRow()],
 monthly_result:{
  id:monthlyId,tenant_id:tenant,report_month:'2026-10',version:1,
  ...monthlyEval(),frozen:true,frozen_at:at,evaluated_by:actor,evaluated_at:at
 },
 live_monthly_evaluation:monthlyEval(),
 formula_version:'D16_SLA_AVAILABILITY_AND_COMPENSATION_V1',timezone:'Asia/Shanghai',
 observed_at:at,source:'postgresql',...patch
})

describe('on-call board, 18:00 handoff, escalation policies and SLA monthly reports',()=>{
 it('validates on-call board payload and rejects invalid handoff time or self-handoff',()=>{
  const parsed=readOnCallBoard(boardPayload(),padminScope)
  expect(parsed.auto_refresh_sec).toBe(15)
  expect(parsed.handoff_local_time).toBe('18:00')
  expect(parsed.roster_policy_status).toBe('CONFIG_PENDING_Q15')
  expect(()=>readOnCallBoard(boardPayload({auto_refresh_sec:30}),padminScope)).toThrow()
  expect(()=>readOnCallBoard(boardPayload({handoff_local_time:'09:00'}),padminScope)).toThrow()
 })

 it('validates SLA report payload, unknown interval isolation (Q08) and Q26 compensation gate',()=>{
  const parsed=readSlaReport(reportPayload(),padminScope,'2026-10')
  expect(parsed.live_monthly_evaluation.compensation_status).toBe('CONFIG_PENDING_Q26')
  expect(parsed.live_monthly_evaluation.compensation_amount_fen).toBeNull()
  expect(parsed.live_monthly_evaluation.candidate_fixed_ratio_fen).toBe(400000)
  expect(parsed.live_monthly_evaluation.candidate_shortfall_weighted_fen).toBe(100000)
  expect(()=>readSlaReport(reportPayload({
   live_monthly_evaluation:monthlyEval({compensation_amount_fen:400000})
  }),padminScope,'2026-10')).toThrow()
  expect(()=>readSlaReport(reportPayload({
   services:[serviceRow({unknown_interval_sec:1800,unknown_policy_status:'NONE'})]
  }),padminScope,'2026-10')).toThrow()
 })

 it('preserves idempotency key on uncertain write failure in on-call board workspace and retries',async()=>{
  const request=vi.fn(),key=vi.fn().mockReturnValue('synthetic-handoff-key')
  const w=createOnCallBoardWorkspace({request},()=>{},key)
  w.reset(mspScope)
  request
   .mockResolvedValueOnce(boardPayload())
   .mockRejectedValueOnce(new ApiError(503,'UPSTREAM_UNAVAILABLE','timeout','t'))
   .mockResolvedValueOnce({id:handoffId})
   .mockResolvedValueOnce(boardPayload())
  await w.load()
  expect(await w.submitHandoff({roster_id:rosterId,incoming_user_id:incoming,note:'18:00 HANDOFF'})).toBe(false)
  expect(w.unresolved()).toBe(true)
  expect(await w.retry()).toBe(true)
  expect(w.unresolved()).toBe(false)
  const posts=request.mock.calls.filter(c=>c[1]?.method==='POST')
  expect(posts).toHaveLength(2)
  expect(posts[0]?.[1]?.idempotencyKey).toBe('synthetic-handoff-key')
  expect(posts[1]?.[1]?.idempotencyKey).toBe('synthetic-handoff-key')
 })

 it('msp cannot patch escalation policy configuration, while padmin can',async()=>{
  const request=vi.fn().mockResolvedValue(boardPayload()),key=vi.fn().mockReturnValue('policy-key')
  const w=createOnCallBoardWorkspace({request},()=>{},key)
  w.reset(mspScope)
  expect(await w.submitPolicyUpdate({
   tier:'STANDARD',expected_version:1,unanswered_escalate_count:2,
   steps:[{step_order:1,trigger_condition:'IMMEDIATE',wait_minutes:0,target_role:'L1_ONCALL'}]
  })).toBe(false)
  w.reset(padminScope)
  expect(await w.submitPolicyUpdate({
   tier:'STANDARD',expected_version:1,unanswered_escalate_count:2,
   steps:[{step_order:1,trigger_condition:'IMMEDIATE',wait_minutes:0,target_role:'L1_ONCALL'}]
  })).toBe(true)
 })

 it('loads SLA service detail and formats countdown, duration and yuan',async()=>{
  const request=vi.fn()
  const w=createSlaReportWorkspace({request},()=>{})
  w.reset(mspScope)
  const detailPayload={
   tenant_id:tenant,period:'2026-10',service:serviceRow(),
   interval_details:serviceRow().interval_details,ticket_judgments:[],
   formula_version:'D16_SLA_AVAILABILITY_AND_COMPENSATION_V1',timezone:'Asia/Shanghai',
   observed_at:at,source:'postgresql'
  }
  expect(readSlaServiceDetail(detailPayload,mspScope,'2026-10','CORE_NETWORK').service.service_id).toBe('CORE_NETWORK')
  request.mockResolvedValueOnce(detailPayload)
  expect(await w.loadServiceDetail('2026-10','CORE_NETWORK')).toBe(true)
  expect(formatCountdown(300)).toBe('剩余 00:05:00')
  expect(formatCountdown(-65)).toBe('已超时 00:01:05')
  expect(formatDurationSec(210)).toBe('3m 30s')
  expect(formatFenToYuan(400000)).toBe('¥4000.00')
 })

 it('wires routes, navigation and explicit M5-9 / M5-10 / M5-12 / M5-13 / M5-19 disclosures',()=>{
  const router=readFileSync(resolve('src/router.ts'),'utf8')
  const nav=readFileSync(resolve('src/utils/navigationModules.ts'),'utf8')
  const boardPage=readFileSync(resolve('src/pages/OnCallBoardPage.vue'),'utf8')
  const slaPage=readFileSync(resolve('src/pages/SlaReportsPage.vue'),'utf8')
  expect(router).toContain("path: '/oncall-board'")
  expect(router).toContain("path: '/sla-reports'")
  expect(nav).toContain("['oncall-board','值班看板与升级链']")
  expect(nav).toContain("['sla-reports','SLA月报与赔付']")
  expect(boardPage).toContain('M5-12')
  expect(boardPage).toContain('M5-13')
  expect(boardPage).toContain('CONFIG_PENDING_Q15')
  expect(boardPage).not.toContain('v-html')
  expect(slaPage).toContain('M5-9')
  expect(slaPage).toContain('M5-10')
  expect(slaPage).toContain('M5-19')
  expect(slaPage).toContain('CONFIG_PENDING_Q26')
  expect(slaPage).not.toContain('v-html')
 })
})
