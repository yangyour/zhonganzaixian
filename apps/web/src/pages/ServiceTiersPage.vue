<script setup lang="ts">
import {computed,onUnmounted,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {
 billingCycleLabels,clockStatusLabels,createServiceTierWorkspace,effectiveModeLabels,
 emptyServiceTierState,tierLabels,type BillingCycle,type EffectiveMode,type ServiceTierCode,type ServiceTierState
} from '../utils/serviceTiers'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'

const state=shallowRef<ServiceTierState>(emptyServiceTierState())
const workspace=createServiceTierWorkspace(api,s=>{state.value=s})
const internal=computed(()=>['padmin','msp'].includes(session.value?.user.role??''))
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&internal.value&&can('sla_scenarios:read')&&!switchingTenant.value))
const canWrite=computed(()=>Boolean(permitted.value&&session.value?.user.role==='padmin'&&can('configurations:write')))
const tab=ref<'ledger'|'sla'>('ledger')
const toTier=ref<ServiceTierCode>('STANDARD')
const effectiveMode=ref<EffectiveMode>('IMMEDIATE')
const deviceCount=ref<string>('')
const billingCycle=ref<BillingCycle|''>('')
const monthlyFeeFen=ref<string>('')
const reason=ref('')
const ticketIdInput=ref('')
const blocked=ref(false)

const context=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,permitted.value,canWrite.value]))
const locked=computed(()=>!!state.value.busy||workspace.unresolved())

watch(context,()=>{
 const s=session.value
 tab.value='ledger'
 workspace.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,canWrite:canWrite.value}:null)
 if(permitted.value)void workspace.load(false)
},{immediate:true,flush:'sync'})

async function submitChange(){
 const devices=deviceCount.value.trim()===''?null:Number.parseInt(deviceCount.value.trim(),10)
 const fee=monthlyFeeFen.value.trim()===''?null:Number.parseInt(monthlyFeeFen.value.trim(),10)
 const ok=await workspace.submitChange({
  to_tier:toTier.value,
  effective_mode:effectiveMode.value,
  contract_device_count:devices!==null&&Number.isFinite(devices)?devices:null,
  billing_cycle:billingCycle.value===''?null:billingCycle.value,
  monthly_fee_fen:fee!==null&&Number.isFinite(fee)?fee:null,
  reason:reason.value
 })
 if(ok)reason.value=''
}

function mayLeave(){if(workspace.unresolved()){blocked.value=true;return false}return true}
onBeforeRouteLeave(mayLeave)
const unregister=contextTransitionGuards.register(mayLeave)
onUnmounted(()=>{unregister();workspace.dispose()})
defineExpose({state,workspace,mayLeave})
</script>
<template>
 <section class="service-tiers">
  <div class="page-heading proto-page-head">
   <div>
    <h1>服务档位与 SLA 时钟台账 <span class="proto-badge">{{ session?.tenant.name }}</span></h1>
    <p>管理当前租户服务档位快照、只追加档位变更历史，并核验在途工单冻结 SLA 时钟（M2-10 / M5-8 / M5-18 / M5-20）。</p>
   </div>
   <RouterLink to="/sla-scenarios" class="back">SLA 场景测算</RouterLink>
  </div>
  <p class="panel" v-if="!permitted">当前身份无权访问内部服务档位与 SLA 时钟台账，或正在切换租户。</p>
  <template v-else>
   <p class="proto-scope-note">
    在途工单 SLA 快照只在开单时冻结，后续修改租户服务档位不会追溯更改已开单工单的响应或恢复截止时间（M2-10 / M5-20）。客户正式商务审批状态保持未核验（UNVERIFIED）；重开续算策略（Q27）与基础/标准档 P3 次日口径（Q08）按已发布运行配置执行。
   </p>
   <div class="proto-kpis">
    <div>
     <span>当前档位</span>
     <b>{{ state.page?.current ? tierLabels[state.page.current.tier] : '未登记' }}</b>
     <small>版本 {{ state.page?.current ? `v${state.page.current.version}` : '—' }}</small>
    </div>
    <div>
     <span>可用性目标</span>
     <b>{{ state.page?.current?.availability_target != null ? `${state.page.current.availability_target}%` : '待配置' }}</b>
     <small>来自 sla.tier_availability_target</small>
    </div>
    <div>
     <span>生效方式</span>
     <b>{{ state.page?.current ? effectiveModeLabels[state.page.current.effective_mode] : '—' }}</b>
     <small>{{ state.page?.current ? displayTime(state.page.current.effective_from) : '尚未设置' }}</small>
    </div>
    <div>
     <span>在途工单追溯</span>
     <b>不追溯</b>
     <small>开单冻结快照（M2-10）</small>
    </div>
   </div>

   <div class="proto-panel workspace">
    <div class="proto-toolbar">
     <div class="proto-tabs" aria-label="服务档位视图">
      <button type="button" :class="{active:tab==='ledger'}" :aria-pressed="tab==='ledger'" :disabled="locked" @click="tab='ledger'">档位快照与变更历史</button>
      <button type="button" :class="{active:tab==='sla'}" :aria-pressed="tab==='sla'" :disabled="locked" @click="tab='sla'">工单 SLA 冻结时钟核验</button>
     </div>
     <button v-if="tab==='ledger'" type="button" :disabled="locked" @click="workspace.load(false)">刷新</button>
    </div>

    <template v-if="tab==='ledger'">
     <ApiErrorNotice :error="state.error" />
     <p v-if="state.attempt||blocked&&workspace.unresolved()" role="alert">服务档位变更结果尚未确认，请使用原幂等键重试。</p>
     <button v-if="state.attempt&&!state.busy" type="button" @click="workspace.retry()">重试提交变更</button>
     <p v-if="state.busy" role="status" aria-live="polite">{{ state.busy==='write'?'正在提交服务档位变更…':'正在读取服务档位台账…' }}</p>

     <form v-if="canWrite&&state.page" class="tier-form" @submit.prevent="submitChange">
      <h2>登记服务档位变更（仅平台管理员 · 只追加）</h2>
      <div class="form-grid">
       <label>目标档位
        <select v-model="toTier" :disabled="locked">
         <option value="BASIC">基础版（5×8 工作日历）</option>
         <option value="STANDARD">标准版（7×24 全天）</option>
         <option value="ADVANCED">进阶版（7×24 高优保障）</option>
        </select>
       </label>
       <label>生效方式
        <select v-model="effectiveMode" :disabled="locked">
         <option value="IMMEDIATE">立即生效</option>
         <option value="NEXT_MONTH_FIRST_DAY">次月 1 日 00:00 生效（Asia/Shanghai）</option>
        </select>
       </label>
       <label>合同设备数（可选）
        <input v-model="deviceCount" type="number" min="0" max="1000000" placeholder="未填保留 null" :disabled="locked" />
       </label>
       <label>计费周期（可选）
        <select v-model="billingCycle" :disabled="locked">
         <option value="">未指定（null）</option>
         <option value="MONTHLY">按月</option>
         <option value="QUARTERLY">按季</option>
         <option value="YEARLY">按年</option>
        </select>
       </label>
       <label>月度服务费（分，可选）
        <input v-model="monthlyFeeFen" type="number" min="0" placeholder="未填保留 null" :disabled="locked" />
       </label>
       <label class="wide">变更原因（必填）
        <input v-model="reason" type="text" maxlength="1000" required placeholder="说明档位调整依据；记录后不可修改或删除" :disabled="locked" />
       </label>
      </div>
      <button type="submit" :disabled="locked||!reason.trim()">记录档位变更（期望版本 v{{ state.page.current?.version ?? 0 }}）</button>
     </form>

     <template v-if="state.page">
      <div class="proto-table-wrap">
       <table class="proto-table">
        <thead>
         <tr><th>版本</th><th>原档位 → 新档位</th><th>可用性目标</th><th>生效方式 / 时间</th><th>合同设备数 / 周期</th><th>变更原因</th><th>记录时间</th></tr>
        </thead>
        <tbody>
         <tr v-for="c in state.page.items" :key="c.id">
          <td>v{{ c.version }}</td>
          <td>{{ c.from_tier ? tierLabels[c.from_tier] : '初始设置' }} → <strong>{{ tierLabels[c.to_tier] }}</strong></td>
          <td>{{ c.availability_target != null ? `${c.availability_target}%` : '—' }}</td>
          <td>{{ effectiveModeLabels[c.effective_mode] }}（{{ displayTime(c.effective_from) }}）</td>
          <td>{{ c.contract_device_count ?? '—' }} / {{ c.billing_cycle ? billingCycleLabels[c.billing_cycle] : '—' }}</td>
          <td>{{ c.reason }}</td>
          <td>{{ displayTime(c.changed_at) }}</td>
         </tr>
        </tbody>
       </table>
       <p v-if="!state.page.items.length" class="panel">当前租户尚无服务档位变更记录。</p>
      </div>
      <button type="button" :disabled="locked||!state.page.has_more" @click="workspace.load(true)">下一页</button>
     </template>
    </template>

    <template v-else>
     <ApiErrorNotice :error="state.slaError" />
     <form class="sla-search" @submit.prevent="workspace.inspectTicketSla(ticketIdInput)">
      <label>工单 ID（UUID）
       <input v-model="ticketIdInput" type="text" placeholder="输入工单 UUID 核验开单冻结 SLA 时钟" />
      </label>
      <button type="submit" :disabled="state.slaBusy||!ticketIdInput.trim()">核验 SLA 时钟</button>
     </form>
     <p v-if="state.slaBusy" role="status">正在读取工单 SLA 时钟…</p>
     <div v-if="state.slaInspection" class="sla-detail">
      <p>
       工单号：<strong>{{ state.slaInspection.receipt_no }}</strong> ·
       状态：<strong>{{ state.slaInspection.state ?? '未建立正式流程' }}</strong> ·
       优先级：<strong>{{ state.slaInspection.priority ?? '—' }}</strong> ·
       开单冻结档位：<strong>{{ state.slaInspection.frozen_service_tier ? tierLabels[state.slaInspection.frozen_service_tier] : '—' }}</strong> ·
       当前租户档位：<strong>{{ state.slaInspection.current_tenant_service_tier ? tierLabels[state.slaInspection.current_tenant_service_tier] : '未登记' }}</strong> ·
       开单后档位是否变更：<strong>{{ state.slaInspection.tier_changed_since_open ? '是（在途工单截止时间保持不变）' : '否' }}</strong>
      </p>
      <div v-if="state.slaInspection.response_clock && state.slaInspection.recovery_clock" class="proto-table-wrap">
       <table class="proto-table">
        <thead>
         <tr><th>时钟</th><th>状态</th><th>截止时间</th><th>完成时间</th><th>50% 提醒</th><th>80% 提醒</th><th>100% 超时</th></tr>
        </thead>
        <tbody>
         <tr v-for="clock in [state.slaInspection.response_clock, state.slaInspection.recovery_clock]" :key="clock.name">
          <td>{{ clock.name === 'RESPONSE' ? '响应 SLA' : '恢复 SLA' }}</td>
          <td>{{ clockStatusLabels[clock.status] }}</td>
          <td>{{ clock.due_at ? displayTime(clock.due_at) : (clock.missing_reason ?? '待配置') }}</td>
          <td>{{ clock.completed_at ? displayTime(clock.completed_at) : '—' }}</td>
          <td>{{ clock.thresholds.p50_at ? displayTime(clock.thresholds.p50_at) : '—' }}</td>
          <td>{{ clock.thresholds.p80_at ? displayTime(clock.thresholds.p80_at) : '—' }}</td>
          <td>{{ clock.thresholds.p100_at ? displayTime(clock.thresholds.p100_at) : '—' }}</td>
         </tr>
        </tbody>
       </table>
      </div>
      <p class="metric-footer">
       重开次数：{{ state.slaInspection.reopen_count }} · 重开计时策略：{{ state.slaInspection.reopen_policy_status === 'PENDING_Q27' ? '待客户确认（Q27）' : '未触发重开' }} · 口径版本：<code>{{ state.slaInspection.metric_version }}</code>
      </p>
     </div>
    </template>
   </div>
  </template>
 </section>
</template>
<style scoped>
.service-tiers{min-width:0}.service-tiers .panel{padding:20px;margin:16px 0}.workspace{margin-top:12px}.workspace>p,.workspace>button{margin:12px 18px;font-size:12px}.service-tiers .proto-toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:0 18px}.service-tiers button,.back{display:inline-flex;align-items:center;min-height:32px;padding:6px 10px;font-size:12px;border:1px solid #cbd7e5;border-radius:3px;background:#fff;color:#245a85;font-family:inherit;cursor:pointer}.service-tiers button:disabled{opacity:.5;cursor:not-allowed}.service-tiers .proto-tabs{display:flex;gap:24px}.service-tiers .proto-tabs button{border:0;border-bottom:2px solid transparent;border-radius:0;min-height:48px;padding:0 4px;background:none;color:#5f6b78}.service-tiers .proto-tabs button.active{border-bottom-color:#2d79f6;color:#1f5fc8}.tier-form,.sla-search,.sla-detail{margin:16px 18px;padding:14px;border:1px solid #e2e8f0;border-radius:4px;background:#f8fafc}.tier-form h2{margin:0 0 12px;font-size:14px}.form-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin-bottom:12px}.form-grid label,.sla-search label{display:flex;flex-direction:column;gap:4px;font-size:12px;color:#334155}.form-grid label.wide{grid-column:1/-1}.form-grid input,.form-grid select,.sla-search input{min-height:32px;padding:4px 8px;border:1px solid #cbd7e5;border-radius:3px;font-size:12px}.sla-search{display:flex;gap:12px;align-items:flex-end;flex-wrap:wrap}.sla-search label{flex:1;min-width:260px}.metric-footer{margin-top:10px;font-size:12px;color:#64748b}
</style>
