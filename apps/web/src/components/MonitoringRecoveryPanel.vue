<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,ref,shallowRef,watch} from 'vue'
import {ElMessageBox} from 'element-plus'
import {RouterLink} from 'vue-router'
import {api} from '../api/client'
import {session,switchingTenant,contextGeneration} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {createMonitoringRecovery,emptyRecoveryState,type RecoveryScope,type RecoveryState} from '../utils/monitoringRecovery'
import {displayTime} from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'

const props=defineProps<{eventId:string}>()
const emit=defineEmits<{pending:[value:boolean];changed:[]}>()
const state=shallowRef<RecoveryState>(emptyRecoveryState()),confirming=ref(false),loadButton=ref<HTMLButtonElement|null>(null),resultRegion=ref<HTMLElement|null>(null)
let alive=true
const workspace=createMonitoringRecovery(api,next=>{state.value=next;emit('pending',workspace.unresolved()||confirming.value)})
const scope=computed<RecoveryScope|null>(()=>{
 const s=session.value
 if(!s||s.mode!=='remote_test'||switchingTenant.value||!['msp','padmin'].includes(s.user.role)||!s.capabilities.includes('tickets:read'))return null
 return {tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,eventId:props.eventId,canWrite:s.capabilities.includes('ticket_notes:write_internal')}
})
const contextKey=computed(()=>JSON.stringify([contextGeneration.value,session.value?.user.role,session.value?.account_version,[...(session.value?.capabilities??[])].sort(),scope.value]))
watch(contextKey,()=>{confirming.value=false;workspace.reset(scope.value);if(scope.value)void workspace.load()},{immediate:true,flush:'sync'})
const mayLeave=()=>!workspace.unresolved()&&!confirming.value
const unregister=contextTransitionGuards.register(mayLeave,()=>JSON.stringify([contextKey.value,state.value.attempt?.key,confirming.value]))
const canObserve=computed(()=>Boolean(scope.value?.canWrite&&state.value.view?.configuration_status==='ACTIVE'&&state.value.view.source_binding_available&&state.value.view.handling_state!=='CLOSED'&&state.value.view.recovery_evidence_status!=='CONFIRMED'&&!state.value.busy&&!state.value.attempt&&!state.value.error&&!confirming.value&&(!state.value.receipt?.applied||state.value.view.event_version>state.value.receipt.event_version)))
const labels:Record<string,string>={RECOVERY_CONFIRMED:'已按运行策略确认恢复',OBSERVING:'已记录采样，观察条件尚未达到',NO_SAMPLES:'没有本次故障及策略生效后的采样',UNKNOWN_LATEST:'最新采样未知或采集错误',STALE_LATEST:'最新采样已过期',NOT_RECOVERED:'最新采样不符合恢复条件'}
const comparisons:Record<string,string>={GT:'>',GE:'≥',LT:'<',LE:'≤'}
async function finish(ok:boolean){if(!alive)return;if(ok&&state.value.receipt?.applied)emit('changed');await nextTick();if(alive)resultRegion.value?.focus()}
async function recover(){await finish(await workspace.recover())}
async function retry(){await finish(await workspace.retry())}
async function observe(){
 if(!canObserve.value)return
 const before=JSON.stringify([contextKey.value,state.value.view]);confirming.value=true;emit('pending',true)
 try{
  await ElMessageBox.confirm('将读取服务端监控采样，并按当前已发布策略记录观察证据。条件满足时确认恢复时点；事件和工单仍须人工复核。','核验恢复观察窗',{confirmButtonText:'开始核验',cancelButtonText:'取消'})
  if(!alive||before!==JSON.stringify([contextKey.value,state.value.view])||workspace.unresolved())return
  confirming.value=false;await finish(await workspace.submit())
 }catch{/* User cancelled; no request is issued. */}
 finally{if(alive){confirming.value=false;emit('pending',workspace.unresolved())}}
}
async function cancelRead(){workspace.cancelRead();await nextTick();if(alive)loadButton.value?.focus()}
onBeforeUnmount(()=>{alive=false;unregister();workspace.dispose();emit('pending',false)})
</script>

<template>
 <section v-if="scope" class="monitoring-recovery" aria-label="恢复观察核验">
  <h3>恢复观察核验</h3>
  <p>使用当前已发布策略与服务端监控采样。缺少配置、采样中断或观察条件不足时，事件保持未恢复。</p>
  <div class="actions">
   <button ref="loadButton" type="button" :disabled="Boolean(state.busy)||confirming" @click="workspace.load()">读取当前恢复策略</button>
   <button v-if="state.busy==='read'||state.busy==='receipt'" type="button" @click="cancelRead">取消读取</button>
   <button v-if="scope.canWrite" type="button" :disabled="!canObserve" @click="observe">核验并记录观察结果</button>
  </div>
  <p v-if="state.busy" role="status">{{state.busy==='write'?'正在读取来源并记录证据，请等待原请求结果。':'正在读取并核对当前范围…'}}</p>
  <p v-if="state.cancelled" role="status">已取消本次读取；未撤销或重发原核验请求。</p>
  <div v-if="state.view">
   <p v-if="state.view.recovery_evidence_status==='UNVERIFIED'" role="status">此恢复标记缺少观察证据，尚不能作为正式 t2 或关单依据。</p>
   <p v-if="state.view.configuration_status==='CONFIG_PENDING'" role="status">恢复策略未完整生效，请先在运行配置中核对并发布。{{state.view.missing_reason}}</p>
   <RouterLink v-if="session?.user.role==='padmin'&&session.capabilities.includes('configurations:read')&&!state.attempt&&!state.busy&&!confirming" :to="{path:'/runtime-configurations',query:{key:'monitor.threshold_matrix'}}">核对与发布恢复策略</RouterLink>
   <p v-if="!state.view.source_binding_available" role="status">本资产尚无可用的服务端采样绑定。</p>
   <dl v-if="state.view.rule" class="mop-facts">
    <div><dt>恢复条件</dt><dd>{{state.view.rule.metric}} {{comparisons[state.view.rule.comparison]}} {{state.view.rule.threshold}} {{state.view.rule.unit||'（无量纲）'}}</dd></div>
    <div><dt>观察时长</dt><dd>{{state.view.rule.hold_seconds}} 秒</dd></div>
    <div><dt>最大采样间隔</dt><dd>{{state.view.rule.max_gap_seconds}} 秒</dd></div>
    <div><dt>最少采样点</dt><dd>{{state.view.rule.minimum_samples}}</dd></div>
    <div><dt>策略发布版本</dt><dd>{{state.view.policy_release_version}}</dd></div>
   </dl>
  </div>
  <div v-if="state.attempt" role="status">
   <p>原核验请求结果尚未确认。保留原版本和请求标识，暂不能切换事件或提交新核验。</p>
   <button type="button" :disabled="Boolean(state.busy)" @click="recover">只读查询原回执</button>
   <button type="button" :disabled="Boolean(state.busy)||!scope.canWrite" @click="retry">重试原核验请求</button>
  </div>
  <div ref="resultRegion" tabindex="-1" aria-live="polite">
   <ApiErrorNotice v-if="state.error" :error="state.error" />
   <div v-if="state.receipt">
    <p><strong>{{labels[state.receipt.status]??state.receipt.status}}</strong></p>
    <dl class="mop-facts">
     <div><dt>符合条件的点数</dt><dd>{{state.receipt.matching_samples}}</dd></div>
     <div><dt>已观察跨度</dt><dd>{{state.receipt.span_seconds===null?'未知':`${state.receipt.span_seconds} 秒`}}</dd></div>
     <div><dt>最新采样时间</dt><dd>{{state.receipt.latest_sample_at??'无采样'}}</dd></div>
     <div><dt>正式恢复时点 t2</dt><dd>{{state.receipt.confirmed_at===null?'尚未确认':displayTime(state.receipt.confirmed_at)}}</dd></div>
     <div><dt>证据编号</dt><dd>{{state.receipt.proof_id}}</dd></div>
    </dl>
    <p>原始采样及策略引用已保存。恢复与人工复核、工单关闭分别记录。</p>
   </div>
  </div>
 </section>
</template>

<style scoped>
.monitoring-recovery{margin:12px 0;padding:12px;border:1px solid var(--mop-line,#e2e8f0);border-radius:6px;min-width:0}.monitoring-recovery p{line-height:1.6;overflow-wrap:anywhere}.monitoring-recovery .actions{display:flex;flex-wrap:wrap;gap:8px}.monitoring-recovery button{min-height:36px;margin:4px 8px 4px 0}.monitoring-recovery dd{overflow-wrap:anywhere}
</style>
