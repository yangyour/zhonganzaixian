<script setup lang="ts">
import {computed,onUnmounted,reactive,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {api,ApiError} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {createControlCommands,readControlEntry,readControlPage,type ControlEntry,type ControlPage,type ControlKind,type ControlCommandState} from '../utils/notificationControl'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'

const channels=['EMAIL','WECHAT_WORK','SMS','DINGTALK','WEBHOOK','VOICE'] as const
const priorities=['P1','P2','P3','P4'] as const
const labels:Record<string,string>={EMAIL:'邮件',WECHAT_WORK:'企业微信',SMS:'短信',DINGTALK:'钉钉',WEBHOOK:'Webhook',VOICE:'语音'}
interface Policy {published:boolean;resource_pool_id:string|null;matrix:Record<string,string[]>|null;aggregate_seconds:number|null;precedence:string|null;retry_budget:number|null;retry_delay_seconds:number|null}
const fresh=():Policy=>({published:false,resource_pool_id:null,matrix:null,aggregate_seconds:null,precedence:null,retry_budget:null,retry_delay_seconds:null})
const policy=shallowRef<ControlEntry|null>(null),page=shallowRef<ControlPage|null>(null),form=reactive<Policy>(fresh())
const policyLoaded=ref(false)
const kind=ref<ControlKind>('REQUEST'),loading=ref(false),error=shallowRef<unknown>(null),dirty=ref(false),ack=ref(false),notice=ref('')
const commandState=shallowRef<ControlCommandState>({pending:null,busy:false,error:null,receipt:null,restorationBlocked:false})
const pendingStorage={getItem:(key:string)=>window.sessionStorage.getItem(key),setItem:(key:string,value:string)=>window.sessionStorage.setItem(key,value),removeItem:(key:string)=>window.sessionStorage.removeItem(key)}
const commands=createControlCommands(api,pendingStorage,value=>commandState.value=value)
const permitted=computed(()=>session.value?.mode==='remote_test'&&can('ticket_notes:read_internal')&&!switchingTenant.value)
const writable=computed(()=>permitted.value&&can('configurations:write'))
const scope=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,permitted.value,writable.value]))
const locked=computed(()=>loading.value||commandState.value.busy||!!commandState.value.pending||commandState.value.restorationBlocked)
let epoch=0,abort:AbortController|null=null
type BindingChoice={id:string;receiver:string;channel:'EMAIL'|'WECHAT_WORK';label:string;active:boolean}
const choices=ref<BindingChoice[]>([]),selected=ref(''),binding=shallowRef<ControlEntry|null>(null),bindingChecked=ref(false),until=ref(''),bindingActive=ref(false)
const bindingDirty=ref(false)
const choice=computed(()=>choices.value.find(row=>row.channel+':'+row.id===selected.value))
const selectedLabel=computed(()=>choice.value?.label??'')
function changed(){dirty.value=true;ack.value=false}
function matrixChanged(enabled:boolean){form.matrix=enabled?Object.fromEntries(priorities.map(priority=>[priority,[]])):null;changed()}
function channelChanged(priority:string,channel:string,checked:boolean){if(!form.matrix)return;form.matrix[priority]=checked?[...form.matrix[priority]!,channel]:form.matrix[priority]!.filter(value=>value!==channel);changed()}
function numberChanged(field:'aggregate_seconds'|'retry_budget'|'retry_delay_seconds',value:string){form[field]=value===''?null:Number(value);changed()}
function fromPolicy(entry:ControlEntry|null){
 const value=entry?.state
 if(!value){Object.assign(form,fresh());return}
 if(typeof value.published!=='boolean'||!(value.resource_pool_id===null||typeof value.resource_pool_id==='string')
  ||!(value.precedence===null||typeof value.precedence==='string')||['aggregate_seconds','retry_budget','retry_delay_seconds'].some(key=>value[key]!==null&&!Number.isSafeInteger(value[key])))throw new ApiError(0,'INVALID_NOTIFICATION_POLICY','路由配置内容无效','—')
 if(value.matrix!==null){if(!value.matrix||typeof value.matrix!=='object'||Array.isArray(value.matrix))throw new Error('路由矩阵格式无效');for(const priority of priorities){const row=(value.matrix as Record<string,unknown>)[priority];if(!Array.isArray(row)||row.some(channel=>!channels.includes(channel))||new Set(row).size!==row.length)throw new Error('路由矩阵格式无效')}}
 Object.assign(form,{published:value.published,resource_pool_id:value.resource_pool_id,matrix:structuredClone(value.matrix),aggregate_seconds:value.aggregate_seconds,precedence:value.precedence,retry_budget:value.retry_budget,retry_delay_seconds:value.retry_delay_seconds})
}
async function read(next=false){
 if(!permitted.value||locked.value||dirty.value||bindingDirty.value)return
 const turn=++epoch,currentKind=kind.value,controller=new AbortController();abort=controller
 const cursor=next&&page.value?.next_id?{id:page.value.next_id,time:page.value.next_time}:null
 if(next&&!cursor)return
 loading.value=true;error.value=null;page.value=null;policyLoaded.value=false
 try{
  const query=new URLSearchParams({kind:currentKind,page_size:'25'});if(cursor){query.set('before_id',cursor.id);query.set('before_time',cursor.time!)}
  const [rawPolicy,rawPage]=await Promise.all([api.request<{record:unknown;external_dispatch_enabled:boolean}>('/notification-routing/policy',{signal:controller.signal}),api.request<unknown>('/notification-routing/entries?'+query,{signal:controller.signal})])
  if(turn!==epoch||controller.signal.aborted)return
  if(!rawPolicy||rawPolicy.external_dispatch_enabled!==false)throw new Error('通知外发门禁未通过核对')
  const record=rawPolicy.record===null?null:readControlEntry(rawPolicy.record);if(record&&record.kind!=='POLICY')throw new Error('路由配置类型不一致')
  fromPolicy(record);policy.value=record;page.value=readControlPage(rawPage,currentKind);policyLoaded.value=true;ack.value=false
 }catch(failure){if(turn===epoch){error.value=failure;policy.value=null;page.value=null}}
 finally{if(turn===epoch){loading.value=false;abort=null}}
}
function cancelRead(){epoch++;abort?.abort();abort=null;loading.value=false;notice.value='已停止等待；没有撤销后台命令。'}
function discard(){if(locked.value)return;fromPolicy(policy.value);dirty.value=false;ack.value=false;notice.value='已放弃未保存编辑。'}
async function save(){if(!writable.value||locked.value||bindingDirty.value||!dirty.value||!ack.value)return;const owner=scope.value;const ok=await commands.submit('/notification-routing/policy',{expected_version:policy.value?.version??-1,policy:JSON.parse(JSON.stringify(form))},'POLICY',policy.value?.id??null);if(ok&&owner===scope.value){dirty.value=false;notice.value='内部路由配置已记录，外部发送仍关闭。';await read()}}
async function plan(entry:ControlEntry){if(!writable.value||locked.value||dirty.value||bindingDirty.value||entry.kind!=='REQUEST')return;const owner=scope.value;if(await commands.submit(`/notification-routing/requests/${entry.id}/plan`,{expected_version:entry.version},'REQUEST',entry.id)&&owner===scope.value)await read()}
async function recovery(retry=false){const owner=scope.value;const ok=retry?await commands.retry():await commands.recover();if(ok&&owner===scope.value){dirty.value=false;bindingDirty.value=false;notice.value='原命令结果已核对，请以刷新后的当前版本为准。';bindingChecked.value=false;await read()}}
async function loadBindings(){
 if(!writable.value||locked.value||dirty.value||bindingDirty.value)return
 const turn=++epoch,controller=new AbortController();abort=controller;loading.value=true;error.value=null;choices.value=[];selected.value='';bindingChecked.value=false
 try{
  const unavailable:string[]=[]
  for(const channel of ['EMAIL','WECHAT_WORK'] as const){
   try{
    const result=await api.request<{items:Array<{id:string;user_id:string;display_name?:string;active:boolean}>}>(channel==='EMAIL'?'/email/bindings':'/wecom/bindings',{signal:controller.signal})
    if(turn!==epoch||controller.signal.aborted)return
    if(!result||!Array.isArray(result.items)||result.items.some(row=>typeof row.id!=='string'||typeof row.user_id!=='string'||typeof row.active!=='boolean'))throw new Error('渠道绑定内容无效')
    choices.value.push(...result.items.map(row=>({id:row.id,receiver:row.user_id,channel,label:`${labels[channel]} · ${row.display_name??row.user_id}${row.active?'':'（停用）'}`,active:row.active})))
   }catch{if(turn!==epoch||controller.signal.aborted)return;unavailable.push(labels[channel]!)}
  }
  if(unavailable.length)error.value=new Error(`${unavailable.join('、')}绑定未读取成功；已读取的其他渠道仍可核对。`)
 }catch(failure){if(turn===epoch){error.value=failure;choices.value=[]}}
 finally{if(turn===epoch){loading.value=false;abort=null}}
}
async function checkBinding(){
 if(!choice.value||locked.value||dirty.value||bindingDirty.value||!writable.value)return
 const selectedChoice={...choice.value},selection=selected.value,turn=++epoch,controller=new AbortController();abort=controller;loading.value=true;error.value=null;bindingChecked.value=false
 try{const result=await api.request<{record:unknown}>(`/notification-routing/bindings/${selectedChoice.receiver}/${selectedChoice.channel}`,{signal:controller.signal});if(turn!==epoch||selection!==selected.value||controller.signal.aborted)return;const record=result.record===null?null:readControlEntry(result.record);if(record&&(record.kind!=='BINDING'||record.state.receiver_id!==selectedChoice.receiver||record.state.channel!==selectedChoice.channel))throw new Error('路由绑定范围不一致');binding.value=record;bindingChecked.value=true;bindingActive.value=false;until.value=''}catch(failure){if(turn===epoch)error.value=failure}finally{if(turn===epoch){loading.value=false;abort=null}}
}
async function saveBinding(){
 if(!choice.value||!bindingChecked.value||locked.value||dirty.value||!writable.value||!until.value)return
 const expires=new Date(until.value);if(!Number.isFinite(expires.valueOf())){error.value=new Error('请选择有效的到期时间');return}
 const owner=scope.value,selectedChoice={...choice.value}
 if(await commands.submit('/notification-routing/bindings',{expected_version:binding.value?.version??-1,binding:{receiver_id:selectedChoice.receiver,channel:selectedChoice.channel,binding_id:selectedChoice.id,valid_until:expires.toISOString(),active:bindingActive.value}},'BINDING',binding.value?.id??null)&&owner===scope.value){bindingChecked.value=false;bindingDirty.value=false;notice.value='路由绑定引用已记录，未调用供应商。';await read()}
}
function discardBinding(){if(locked.value)return;bindingDirty.value=false;bindingChecked.value=false;binding.value=null;until.value='';bindingActive.value=false}
function mayLeave(){if(dirty.value||bindingDirty.value||commandState.value.pending||commandState.value.busy){notice.value='请先保存或放弃编辑，并核对未确认的原命令。';return false}return true}
onBeforeRouteLeave(mayLeave);const unregister=contextTransitionGuards.register(mayLeave)
function beforeUnload(event:BeforeUnloadEvent){if(dirty.value||bindingDirty.value||commandState.value.pending){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
watch(selected,()=>{bindingChecked.value=false;binding.value=null;until.value='';bindingActive.value=false})
watch(scope,()=>{epoch++;abort?.abort();abort=null;loading.value=false;policyLoaded.value=false;policy.value=null;page.value=null;choices.value=[];selected.value='';binding.value=null;bindingChecked.value=false;bindingDirty.value=false;until.value='';bindingActive.value=false;dirty.value=false;error.value=null;notice.value='';Object.assign(form,fresh());commands.reset(permitted.value?scope.value:null);if(permitted.value&&!commandState.value.pending&&!commandState.value.restorationBlocked)void read()},{immediate:true,flush:'sync'})
onUnmounted(()=>{epoch++;abort?.abort();commands.dispose();unregister();window.removeEventListener('beforeunload',beforeUnload)})
const statusLabels:Record<string,string>={WAITING_CONFIGURATION:'等待配置',QUEUED:'待派发',ROUTED:'已记录路由',CANCELLED:'已取消',UNKNOWN:'结果不明',ACCEPTED:'已受理',DELIVERED:'已记录送达',FAILED:'失败',DEAD_LETTER:'死信',MANUAL_CLOSED:'人工结束'}
const reasonLabels:Record<string,string>={PUBLISHED_ROUTING_CONFIGURATION_REQUIRED:'等待已发布规则和本人偏好',PLANNED:'路由条件已核对；尚未派发',CONFIG_PENDING:'路由、偏好或渠道绑定未就绪',FILTERED_LEVEL:'未达到本人订阅级别',FILTERED_CHANNEL:'不在当前渠道范围',QUIET:'当前在静音时段',DUPLICATE:'处于去重窗口，未重复派发',RESULT_UNKNOWN:'原请求结果不明，须核对回执'}
</script>
<template>
 <section class="notification-routing">
  <h1 class="page-title-sr">通知路由与处理</h1>
  <p v-if="!permitted" class="panel">当前身份没有内部通知处理权限。</p>
  <template v-else>
   <div class="proto-panel">
    <div class="proto-toolbar content-toolbar"><strong>通知路由与处理</strong><button :disabled="locked||dirty||bindingDirty" @click="read()">刷新</button><button v-if="loading" @click="cancelRead">停止等待读取</button><RouterLink to="/ticket-notifications">站内通知</RouterLink><RouterLink to="/my-notification-preferences">本人偏好</RouterLink></div>
    <p>外部发送保持关闭。内部规则发布、任务排队、供应商受理和送达分别记录。</p>
    <ApiErrorNotice :error="error"/><ApiErrorNotice :error="commandState.error"/>
    <p v-if="notice" role="status">{{notice}}</p><p v-if="loading" role="status">正在核对当前租户的通知记录…</p>
    <p v-if="commandState.restorationBlocked" role="alert">浏览器保存的原请求无法读取。请保留当前记录并核对后台命令，暂不能提交新请求。</p>
    <div v-if="commandState.pending" role="alert"><p>上次写入结果尚未确认。核对或重试会使用同一条原请求。</p><button :disabled="commandState.busy" @click="recovery()">核对原命令</button><button :disabled="commandState.busy" @click="recovery(true)">重试原请求</button><button v-if="commandState.busy" @click="commands.cancelWait">停止等待</button></div>
   </div>
   <form v-if="writable&&policyLoaded" class="proto-panel routing-form" @submit.prevent="save">
    <h2>渠道与级别规则</h2><p>当前{{policy?`版本 ${policy.version}`:'尚无配置'}}。未确认项保持空白；完整配置才能发布。</p>
    <fieldset :disabled="locked||bindingDirty"><legend>路由范围与等待</legend>
     <label>资源池标识<input class="routing-pool-reference" :value="form.resource_pool_id??''" @input="form.resource_pool_id=($event.target as HTMLInputElement).value||null;changed()"></label>
     <label>聚合等待（秒）<input type="number" min="0" max="86400" :value="form.aggregate_seconds??''" @input="numberChanged('aggregate_seconds',($event.target as HTMLInputElement).value)"></label>
     <label>规则优先顺序<select :value="form.precedence??''" @change="form.precedence=($event.target as HTMLSelectElement).value||null;changed()"><option value="">未确认</option><option value="MATRIX_THEN_PREFERENCE_WITH_P1_QUIET_BYPASS">先级别矩阵，再本人偏好；P1 跳过静音</option></select></label>
     <label>失败重试额度<input type="number" min="0" max="100" :value="form.retry_budget??''" @input="numberChanged('retry_budget',($event.target as HTMLInputElement).value)"></label>
     <label>重试间隔（秒）<input type="number" min="1" max="86400" :value="form.retry_delay_seconds??''" @input="numberChanged('retry_delay_seconds',($event.target as HTMLInputElement).value)"></label>
    </fieldset>
    <fieldset class="routing-matrix" :disabled="locked||bindingDirty"><legend>渠道矩阵</legend><label><input type="checkbox" :checked="form.matrix!==null" @change="matrixChanged(($event.target as HTMLInputElement).checked)">明确配置各级渠道（某级不选渠道表示只记录）</label>
     <div v-if="form.matrix" class="admin-table-scroll" tabindex="0" role="region" aria-label="渠道级别矩阵"><table class="admin-table"><caption>渠道与级别</caption><thead><tr><th scope="col">级别</th><th v-for="channel in channels" :key="channel" scope="col">{{labels[channel]}}</th></tr></thead><tbody><tr v-for="priority in priorities" :key="priority"><th scope="row">{{priority}}</th><td v-for="channel in channels" :key="channel"><input type="checkbox" :aria-label="`${priority} ${labels[channel]}`" :checked="form.matrix[priority]?.includes(channel)" @change="channelChanged(priority,channel,($event.target as HTMLInputElement).checked)"></td></tr></tbody></table></div>
    </fieldset>
    <label><input type="checkbox" v-model="form.published" :disabled="locked||bindingDirty" @change="changed">发布为内部路由规则</label>
    <label><input type="checkbox" v-model="ack" :disabled="locked||bindingDirty||!dirty">我已核对本次规则，外部发送仍保持关闭</label>
    <div class="routing-actions"><button type="submit" :disabled="locked||bindingDirty||!dirty||!ack">保存规则</button><button type="button" :disabled="locked||!dirty" @click="discard">放弃编辑</button></div>
   </form>
   <div v-if="writable" class="proto-panel routing-form"><h2>接收人路由绑定</h2><p>关联已有邮件或企业微信绑定；停用的演示绑定不能变成可派发目标。</p><div class="routing-actions"><button :disabled="locked||dirty||bindingDirty" @click="loadBindings">读取已有渠道绑定</button><RouterLink to="/email">管理邮件绑定</RouterLink><RouterLink to="/wecom">管理企业微信绑定</RouterLink></div>
    <label>渠道接收人<select v-model="selected" :disabled="locked||dirty||bindingDirty"><option value="">请选择</option><option v-for="row in choices" :key="row.channel+row.id" :value="row.channel+':'+row.id">{{row.label}}</option></select></label>
    <button :disabled="locked||dirty||bindingDirty||!choice" @click="checkBinding">核对所选路由绑定</button>
    <template v-if="bindingChecked"><p>{{selectedLabel}}；{{binding?`路由版本 ${binding.version}`:'尚无路由绑定'}}。</p><label>有效期至（浏览器本地时间）<input type="datetime-local" v-model="until" :disabled="locked||dirty" @input="bindingDirty=true"></label><label><input type="checkbox" v-model="bindingActive" :disabled="locked||dirty||!choice?.active" @change="bindingDirty=true">允许内部路由使用此绑定</label><button :disabled="locked||dirty||!until" @click="saveBinding">保存绑定引用</button><button v-if="bindingDirty" :disabled="locked" @click="discardBinding">放弃绑定编辑</button></template>
   </div>
   <div class="proto-panel"><div class="proto-toolbar content-toolbar"><label>查看记录<select v-model="kind" :disabled="locked||dirty||bindingDirty" @change="read()"><option value="REQUEST">业务来源请求</option><option value="ROUTE">渠道派发任务</option><option value="BINDING">路由绑定</option></select></label></div>
    <p v-if="!page&&!loading">{{error?'当前记录未能读取，数量未知。':'尚未读取记录。'}}</p><p v-if="page&&!page.items.length">当前没有此类通知记录。</p>
    <div v-if="page?.items.length" class="admin-table-scroll" tabindex="0" role="region" aria-label="通知处理记录"><table class="admin-table"><caption>当前租户的通知记录</caption><thead><tr><th scope="col">记录</th><th scope="col">渠道／级别</th><th scope="col">状态与原因</th><th scope="col">版本／更新时间</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="entry in page.items" :key="entry.id"><td>{{entry.id}}</td><td>{{labels[String(entry.state.channel)]??entry.state.priority??'—'}}</td><td>{{entry.kind==='BINDING'?(entry.state.active?'有效绑定':'停用绑定'):(statusLabels[String(entry.state.status)]??'未知状态')}}<small>{{reasonLabels[String(entry.state.reason??entry.state.decision)]??entry.state.reason??entry.state.decision??''}}</small></td><td>{{entry.version}} · {{displayTime(entry.recorded_at)}}</td><td><button v-if="writable&&entry.kind==='REQUEST'" :disabled="locked||dirty||bindingDirty" @click="plan(entry)">重新核对路由</button></td></tr></tbody></table></div>
    <button v-if="page?.next_id" :disabled="locked||dirty||bindingDirty" @click="read(true)">下一页</button>
   </div>
  </template>
 </section>
</template>
<style scoped>
.notification-routing{display:grid;gap:16px;min-width:0}.notification-routing .proto-panel>p,.notification-routing .proto-panel>div[role=alert]{margin:12px 16px}.routing-form{display:grid;gap:12px;padding:16px}.routing-form h2{margin:0}.routing-form fieldset{display:flex;gap:12px;flex-wrap:wrap;min-width:0;border:1px solid var(--border-color,#d7dee8);padding:12px}.routing-form label{display:grid;gap:6px}.routing-form label:has(input[type=checkbox]){display:flex;align-items:center;gap:8px}.routing-form input:not([type=checkbox]),.routing-form select{min-height:36px;min-width:180px}.routing-actions{display:flex;align-items:center;gap:12px}.notification-routing .admin-table td{max-width:320px;overflow-wrap:anywhere}.notification-routing small{display:block}.notification-routing .admin-table-scroll:focus-visible{outline:2px solid #2563eb;outline-offset:2px}
.routing-form .routing-pool-reference{width:320px;max-width:100%}.routing-form fieldset.routing-matrix{display:block}.routing-matrix>label{margin-bottom:12px}.routing-matrix .admin-table-scroll{width:100%}
</style>
