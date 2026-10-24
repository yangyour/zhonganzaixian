<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {api} from '../api/client'
import {session,can,contextGeneration,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {monitoringPresets,type MonitoringDraft} from '../utils/monitoringOnboarding'
import {createEnrollmentCommands,enrollmentPlanRequest,readEnrollment,type Enrollment} from '../utils/monitoringEnrollments'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{draft:MonitoringDraft}>()
const collector=ref(''),rows=ref<Enrollment[]|null>(null),selected=ref<Enrollment|null>(null),error=ref<unknown>(null),loading=ref(false),reason=ref(''),next=ref<{before:string;before_id:string}|null>(null)
const prepared=ref<{request:Record<string,unknown>;configuration_sha256:string;catalog_sha256:string;target_count:number}|null>(null),diagnostics=ref<string[]>([])
const permitted=computed(()=>session.value?.mode==='remote_test'&&can('assets:read')&&can('configurations:read')&&!switchingTenant.value)
const writable=computed(()=>permitted.value&&session.value?.user.role==='padmin'&&can('configurations:write'))
const stableScope=computed(()=>JSON.stringify([session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,writable.value,permitted.value]))
const scope=computed(()=>stableScope.value+':'+contextGeneration.value)
const storage={getItem:(key:string)=>sessionStorage.getItem(key),setItem:(key:string,value:string)=>sessionStorage.setItem(key,value),removeItem:(key:string)=>sessionStorage.removeItem(key)}
const commands=createEnrollmentCommands(()=>({key:stableScope.value,tenant:session.value?.tenant.id||''}),storage)
const locked=computed(()=>loading.value||commands.busy.value||!!commands.pending.value||commands.blocked.value)
const collectorRequired=computed(()=>['blackbox','snmp'].includes(monitoringPresets.find(p=>p.id===props.draft.presetId)?.test_kind||''))
const labels={REQUESTED:'待审批',APPROVED:'已审批，尚未登记',REGISTERED_DISABLED:'已登记，采集停用',CANCELLED:'已取消'}
let epoch=0,abort:AbortController|null=null
async function plan(){if(!writable.value||locked.value)return;const turn=++epoch,current=scope.value,controller=new AbortController();abort=controller;loading.value=true;error.value=null;prepared.value=null;diagnostics.value=[]
 try{const request=enrollmentPlanRequest(props.draft,collector.value,monitoringPresets);const result=await api.request<{tenant_id:string;actor_id:string;permission_version:number;plan:{valid:boolean;configuration_sha256:string;catalog_sha256:string;target_count:number;applied:false;continuous_collection_verified:false;diagnostics:{code:string}[]}}>('/monitoring-onboarding/plans',{method:'POST',body:request,signal:controller.signal});if(turn!==epoch||current!==scope.value||controller.signal.aborted)return
  if(result.tenant_id!==session.value!.tenant.id||result.actor_id!==session.value!.user.id||result.permission_version!==session.value!.permission_version||result.plan.applied!==false||result.plan.continuous_collection_verified!==false)throw new Error('计划授权范围不一致')
  if(!result.plan.valid){diagnostics.value=result.plan.diagnostics.map(d=>d.code);return}
  if(!/^[a-f0-9]{64}$/.test(result.plan.configuration_sha256)||!/^[a-f0-9]{64}$/.test(result.plan.catalog_sha256)||!Number.isSafeInteger(result.plan.target_count)||result.plan.target_count<1||result.plan.target_count>64)throw new Error('计划摘要不完整')
  prepared.value={request,configuration_sha256:result.plan.configuration_sha256,catalog_sha256:result.plan.catalog_sha256,target_count:result.plan.target_count}
 }catch(e){if(turn===epoch)error.value=e}finally{if(turn===epoch)loading.value=false}}
async function browse(cursor:{before:string;before_id:string}|null=null){if(!permitted.value||locked.value)return;const turn=++epoch,current=scope.value,controller=new AbortController();abort=controller;loading.value=true;error.value=null;rows.value=null;selected.value=null;next.value=null
 try{const result=await api.request<{items:unknown[];next:{before:string;before_id:string}|null;external_configuration_applied:false;continuous_collection_verified:false}>('/monitoring-onboarding/enrollments'+(cursor?'?'+new URLSearchParams(cursor):''),{signal:controller.signal});if(turn!==epoch||current!==scope.value||controller.signal.aborted)return
  if(!Array.isArray(result.items)||result.items.length>20||result.external_configuration_applied!==false||result.continuous_collection_verified!==false)throw new Error('登记列表范围不一致')
  const values=result.items.map(v=>readEnrollment(v,session.value!.tenant.id));if(new Set(values.map(v=>v.state.document.id)).size!==values.length)throw new Error('登记列表重复')
  if(result.next&&(values.at(-1)?.state.document.id!==result.next.before_id||values.at(-1)?.state.document.created_at!==result.next.before))throw new Error('登记分页游标不一致');rows.value=values;next.value=result.next
 }catch(e){if(turn===epoch)error.value=e}finally{if(turn===epoch)loading.value=false}}
async function create(){if(!prepared.value||!writable.value||locked.value)return;const p=prepared.value;if(await commands.send('/monitoring-onboarding/enrollments',{plan_request:p.request,expected_configuration_sha256:p.configuration_sha256,expected_catalog_sha256:p.catalog_sha256})){prepared.value=null;await browse()}}
async function select(id:string){if(locked.value)return;const turn=++epoch,current=scope.value,controller=new AbortController();abort=controller;loading.value=true;error.value=null;selected.value=null
 try{const result=await api.request<unknown>(`/monitoring-onboarding/enrollments/${id}`,{signal:controller.signal});if(turn===epoch&&current===scope.value&&!controller.signal.aborted)selected.value=readEnrollment(result,session.value!.tenant.id,id)}catch(e){if(turn===epoch)error.value=e}finally{if(turn===epoch)loading.value=false}}
async function change(action:string){if(!selected.value||!writable.value||locked.value)return;const id=selected.value.state.document.id;if(await commands.send(`/monitoring-onboarding/enrollments/${id}/actions`,{action,expected_version:selected.value.state.version,reason:action==='CANCEL'?reason.value:null})){reason.value='';await select(id)}}
async function probe(target:Enrollment['targets'][number]){if(!selected.value||locked.value||!writable.value||!target.test_authorized)return;const id=selected.value.state.document.id;if(await commands.send(`/monitoring-onboarding/enrollments/${id}/probe`,{target_id:target.target_id,configuration_sha256:target.configuration_sha256,expected_version:selected.value.state.version}))await select(id)}
async function retry(){if(await commands.retry())await browse()}
function mayLeave(){return !commands.pending.value&&!commands.busy.value&&!commands.blocked.value}
onBeforeRouteLeave(mayLeave);const unregister=contextTransitionGuards.register(mayLeave)
function beforeUnload(e:BeforeUnloadEvent){if(!mayLeave()){e.preventDefault();e.returnValue=''}}window.addEventListener('beforeunload',beforeUnload)
watch(scope,()=>{epoch++;abort?.abort();loading.value=false;rows.value=null;selected.value=null;prepared.value=null;error.value=null;diagnostics.value=[];reason.value='';collector.value='';commands.reset()},{immediate:true,flush:'sync'})
watch(()=>JSON.stringify([props.draft,collector.value]),()=>{epoch++;abort?.abort();loading.value=false;prepared.value=null;diagnostics.value=[]},{flush:'sync'})
onBeforeUnmount(()=>{epoch++;abort?.abort();commands.dispose();unregister();window.removeEventListener('beforeunload',beforeUnload)})
</script>
<template>
 <section v-if="permitted" aria-label="持久监控登记" class="ws-card">
  <div class="actions"><h2>持久监控登记</h2><button :disabled="locked" @click="browse()">读取登记任务</button></div>
  <p>使用当前资产的管理地址和已选场景；本地填写的地址、凭据及启用选项不会写入登记。</p>
  <template v-if="writable"><label v-if="collectorRequired">服务器采集器名称<input v-model="collector" maxlength="64" :disabled="locked" placeholder="已配置名称，不填地址或密码" /></label><div class="actions"><button :disabled="locked" @click="plan">核对服务器计划</button><button v-if="prepared" :disabled="locked" @click="create">保存 {{prepared.target_count}} 个停用目标的登记申请</button></div></template>
  <ApiErrorNotice :error="error"/><ApiErrorNotice :error="commands.error.value"/><p v-if="diagnostics.length" role="alert">{{diagnostics.join('、')}}</p><p v-if="loading" role="status">正在读取…</p>
  <p v-if="commands.pending.value" role="alert">操作结果待确认，请保留原请求。<button :disabled="commands.busy.value" @click="retry">按原请求核对</button><button v-if="commands.busy.value" @click="commands.cancel">停止等待</button></p><p v-if="commands.blocked.value" role="alert">原请求无法安全恢复，已阻止新增操作。</p>
  <table v-if="rows?.length"><thead><tr><th>登记任务</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.state.document.id"><td>{{row.state.document.id}}</td><td>{{labels[row.state.phase]}}</td><td>{{row.state.document.created_at}}</td><td><button :disabled="locked" @click="select(row.state.document.id)">查看</button></td></tr></tbody></table><p v-else-if="rows">当前页没有登记任务。</p>
  <div v-if="rows" class="actions"><button :disabled="locked" @click="browse()">第一页</button><button v-if="next" :disabled="locked" @click="browse(next)">下一页</button></div>
  <section v-if="selected" aria-label="登记详情"><p v-if="!selected.catalog_current" role="status">预设目录已变化，旧任务保留历史，不能继续登记或探测；请取消后创建新任务。</p><p>{{labels[selected.state.phase]}} · 版本 {{selected.state.version}}</p><p v-if="selected.state.reason">{{selected.state.reason}}</p><div class="actions" v-if="writable"><button v-if="selected.state.phase==='REQUESTED'" :disabled="locked" @click="change('APPROVE')">按明确策略审批</button><button v-if="selected.state.phase==='APPROVED'" :disabled="locked" @click="change('REGISTER_DISABLED')">登记为停用目标</button></div>
   <ul><li v-for="target in selected.targets" :key="target.target_id">{{target.target_id}} · {{target.configuration.targets[0]?.preset_id}}<button v-if="writable&&target.test_authorized" :disabled="locked" @click="probe(target)">一次只读探测并记录</button><span v-else-if="selected.state.phase==='REGISTERED_DISABLED'"> 探测许可未配置</span></li></ul>
   <details><summary>冻结目标配置</summary><pre>{{JSON.stringify(selected.targets.map(t=>t.configuration),null,2)}}</pre></details>
   <p v-if="selected.state.last_probe" role="status">最近一次：{{selected.state.last_probe.status}} · {{selected.state.last_probe.observed_at}} {{selected.state.last_probe.reason||''}}。不代表持续采集、设备身份或健康。</p>
   <div v-if="writable&&selected.state.phase!=='CANCELLED'"><label>取消原因<input v-model="reason" maxlength="1000" :disabled="locked" /></label><button :disabled="locked||!reason.trim()" @click="change('CANCEL')">取消登记任务</button></div>
  </section>
 </section>
</template>

<style scoped>pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:320px;overflow:auto}table{width:100%;text-align:left}td{overflow-wrap:anywhere}</style>
