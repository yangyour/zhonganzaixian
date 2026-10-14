<script setup lang="ts">
import {computed,onMounted,onUnmounted,reactive,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {createAssetExportWorkspace,emptyAssetExportState,type AssetExportState} from '../utils/assetExportWorkspace'
import {assetExportInput,type AssetExportFilters} from '../utils/assetExportJobs'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import AssetExportPublicationPanel from '../components/AssetExportPublicationPanel.vue'
const publicationPanel=ref<InstanceType<typeof AssetExportPublicationPanel>|null>(null)
const fresh=()=>({type:'',status:'',siteAny:true,site:'',queryAny:true,query:''})
const form=reactive(fresh()),acknowledge=ref(false),dirty=ref(false),confirming=ref(false),validation=ref('')
const state=shallowRef<AssetExportState>(emptyAssetExportState()),workspace=createAssetExportWorkspace(api,s=>{state.value=s})
let disposed=false
const autoRefresh=ref(true)
let preparationTimer:ReturnType<typeof setInterval>|undefined
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&['padmin','msp'].includes(session.value.user.role)&&can('assets:read')&&!switchingTenant.value))
const contextKey=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,permitted.value]))
const busy=computed(()=>Boolean(state.value.busy)||confirming.value),locked=computed(()=>busy.value||state.value.attempt!==null)
const originalFilters=computed<AssetExportFilters|null>(()=>state.value.attempt?JSON.parse(state.value.attempt.payload).filters:null)
const typeLabels:Record<string,string>={router:'路由器',switch:'交换机',firewall:'防火墙',ap:'无线AP',wireless_controller:'无线控制器',other:'其他'}
const statusLabels:Record<string,string>={unknown:'未知',ok:'正常记录',problem:'问题记录',maintenance:'维护记录',disabled:'禁用记录'}
function filters():AssetExportFilters{return{type:form.type||null,status:form.status||null,site_id:form.siteAny?null:form.site,query:form.queryAny?null:form.query}}
function change(key:keyof typeof form,value:string|boolean){if(disposed||!permitted.value||locked.value||typeof value!==typeof form[key])return false;Object.assign(form,{[key]:value});dirty.value=true;acknowledge.value=false;validation.value='';return true}
function inputValue(event:Event){return(event.target as HTMLInputElement).value}
function checked(event:Event){return(event.target as HTMLInputElement).checked}
function changeAck(value:boolean){if(disposed||!permitted.value||locked.value)return false;acknowledge.value=value;return true}
watch(contextKey,()=>{Object.assign(form,fresh());acknowledge.value=false;dirty.value=false;confirming.value=false;validation.value='';const s=session.value;workspace.reset(!disposed&&permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role,canRead:true}:null);if(permitted.value&&!disposed)void workspace.list()},{immediate:true,flush:'sync'})
async function readAccepted(){const receipt=state.value.receipt;if(!disposed&&permitted.value&&receipt)await workspace.select(receipt.job_id)}
async function submit(){if(disposed||!permitted.value||locked.value||!acknowledge.value)return false;validation.value='';try{assetExportInput(filters())}catch{validation.value='筛选未通过：请核对类型、状态和文本长度；没有受理';return false}const accepted=await workspace.submit(filters());if(accepted)await readAccepted();return accepted}
async function retry(){if(disposed||!permitted.value||busy.value)return false;const accepted=await workspace.retry();if(accepted)await readAccepted();return accepted}
function pollPreparation(){const current=state.value.selected;if(disposed||!permitted.value||document.hidden||!autoRefresh.value||busy.value||workspace.unresolved()||publicationPanel.value?.unresolved()||!current||current.prepared!==null||!['QUEUED','RUNNING'].includes(current.status))return;void workspace.refreshSelected()}
onMounted(()=>{preparationTimer=setInterval(pollPreparation,2000);document.addEventListener('visibilitychange',pollPreparation)})
async function browse(next=false){if(disposed||!permitted.value||busy.value)return false;return workspace.list(next)}
async function select(id:string){if(disposed||!permitted.value||busy.value||publicationPanel.value&&!publicationPanel.value.mayLeave())return false;return workspace.select(id)}
async function discard(){if(publicationPanel.value&&!publicationPanel.value.mayLeave())return false;if(workspace.unresolved()){ElMessage.warning('原请求正在提交或结果未知，必须先用原请求恢复；普通刷新不能确认');return false}if(confirming.value)return false;if(!dirty.value&&!state.value.attempt)return true;const turn=contextKey.value;confirming.value=true;try{await ElMessageBox.confirm('已受理作业会保留在服务器；继续将清除本页筛选草稿与原收据显示，确认继续？','离开当前准备请求',{confirmButtonText:'清除并继续',cancelButtonText:'留在本页',type:'warning'});return!disposed&&turn===contextKey.value&&!workspace.unresolved()}catch{return false}finally{if(!disposed&&turn===contextKey.value)confirming.value=false}}
async function beginNew(){const turn=contextKey.value;if(disposed||!permitted.value||busy.value||!await discard()||turn!==contextKey.value||!workspace.begin())return false;Object.assign(form,fresh());acknowledge.value=false;dirty.value=false;validation.value='';return true}
function unlockRejected(){if(disposed||!permitted.value||busy.value||state.value.attempt?.state!=='rejected'||!workspace.begin())return false;acknowledge.value=false;dirty.value=true;return true}
onBeforeRouteLeave(discard);const unregister=contextTransitionGuards.register(discard)
function beforeUnload(event:BeforeUnloadEvent){if(dirty.value||state.value.attempt||workspace.unresolved()){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{disposed=true;if(preparationTimer!==undefined)clearInterval(preparationTimer);document.removeEventListener('visibilitychange',pollPreparation);unregister();workspace.dispose();window.removeEventListener('beforeunload',beforeUnload)})
const statusText=(s:string)=>({QUEUED:'排队中，执行尚未确认',RUNNING:'处理阶段已登记，Worker在线未知',RECONCILING_STORAGE:'等待存储核查，不能盲目重传',SUCCEEDED:'库内结果记为成功，存储尚未复核',FAILED:'作业失败，原请求和调查记录保留'}[s]??'未知阶段')
const valueText=(v:string|null)=>v===null?'不限（null）':v===''?'明确空字符串':v
const errorText=(s:string)=>({AUTHORIZATION_CHANGED:'原受理授权已变化',SOURCE_UNAVAILABLE:'来源不可用',INPUT_LIMIT:'输入超过技术边界',ENCODING_FAILED:'文件编码失败',CONFIRMED_STORAGE_REJECTION:'存储已明确拒绝'}[s]??'未知错误')
defineExpose({form,state,acknowledge,locked,autoRefresh,pollPreparation,filters,change,changeAck,submit,retry,browse,select,beginNew,unlockRejected,discard,hasUnresolved:workspace.unresolved})
</script>
<template>
 <section class="export-page"><div class="page-heading"><div><p class="eyebrow">台账 / 内部本人作业</p><h1>台账导出准备</h1><p>明确筛选 → 耐久受理 → 独立查看进度</p></div></div>
 <el-alert type="info" :closable="false" show-icon title="新请求由后台自动准备文件；发布和下载仍需分别确认。站内信通知尚未接通" />
 <p v-if="!permitted" class="panel">当前身份没有内部导出准备权限，或租户正在切换；未展示旧数据</p>
 <template v-else>
  <form v-form-feedback class="panel export-form" @submit.prevent="submit"><h2>1. 核对本次筛选</h2><p>只选择当前租户台账；全部“不限”会选择全部匹配记录。1000行、16MiB文件是技术保护，超限拒绝，不静默截断</p>
   <fieldset :disabled="locked"><legend>明确的四项条件</legend><div class="filter-grid">
    <label>类型<select aria-label="导出设备类型" :value="form.type" @change="change('type',inputValue($event))"><option value="">不限</option><option v-for="(label,key) in typeLabels" :key="key" :value="key">{{label}}</option></select></label>
    <label>状态<select aria-label="导出设备状态" :value="form.status" @change="change('status',inputValue($event))"><option value="">不限</option><option v-for="(label,key) in statusLabels" :key="key" :value="key">{{label}}</option></select></label>
    <div><label class="check"><input type="checkbox" aria-label="导出站点不限" :checked="form.siteAny" @change="change('siteAny',checked($event))" />站点不限（null）</label><label>明确站点ID<input aria-label="导出站点ID" :disabled="form.siteAny" :value="form.site" maxlength="64" @input="change('site',inputValue($event))" /></label></div>
    <div><label class="check"><input type="checkbox" aria-label="导出文本不限" :checked="form.queryAny" @change="change('queryAny',checked($event))" />文本不限（null）</label><label>明确检索文本<input aria-label="导出检索文本" :disabled="form.queryAny" :value="form.query" maxlength="100" @input="change('query',inputValue($event))" /></label></div>
   </div><p class="hint">取消“不限”后，空输入会保留为空字符串；不会自动trim、补月份或改变筛选。状态筛选不证明当前监控健康</p><label class="check acknowledge"><input type="checkbox" aria-label="确认仅准备不发布" :checked="acknowledge" @change="changeAck(checked($event))" />我已核对条件，理解本次只受理准备请求，不发布或下载</label></fieldset>
   <p v-if="validation" role="alert" class="warning">{{validation}}</p><div class="actions"><button type="submit" :disabled="locked||!acknowledge">受理本次准备请求</button><button type="button" :disabled="busy||workspace.unresolved()" @click="beginNew">清除并准备新请求</button></div>
  </form>
  <ApiErrorNotice :error="state.error" />
  <section v-if="state.attempt" class="panel original-request"><h2>2. 本页原请求</h2><p>状态：{{state.attempt.state==='uncertain'?'结果未知，不能当作失败重建':state.attempt.state==='accepted'?'已收到原受理回执':'本次明确被拒绝，可显式返回修改'}}</p><p v-if="originalFilters">原类型 {{valueText(originalFilters.type)}} · 原状态 {{valueText(originalFilters.status)}}</p><p v-if="originalFilters">原站点 {{valueText(originalFilters.site_id)}} · 原文本 {{valueText(originalFilters.query)}}</p><p>普通刷新只读取当前状态，不能确认原POST；未知时保留完全相同的原请求恢复</p>
   <button v-if="state.attempt.state==='uncertain'" type="button" :disabled="busy" @click="retry">重试完全相同原请求</button><button v-if="state.attempt.state==='rejected'" type="button" :disabled="busy" @click="unlockRejected">明确返回修改筛选</button>
   <div v-if="state.receipt" class="original-receipt"><h3>原受理回执：QUEUED / v0</h3><p>{{state.receipt.job_id}} · 仅排队，不是文件完成</p><button type="button" :disabled="busy" @click="select(state.receipt.job_id)">独立读取该作业当前进度</button></div>
  </section>
  <section class="panel export-list"><div class="actions"><h2>3. 我的作业</h2><button type="button" :disabled="busy" @click="browse()">只读刷新第一页</button></div><p v-if="!state.page">{{state.busy==='read'?'正在读取，数量未知':state.error?'读取未通过，数量未知':'尚未读取，数量未知'}}</p>
   <template v-else><p>本页 {{state.page.items.length}} 条 · {{state.paged?'续页':'第一页'}}，不是全部作业数量；仅当前创建人和受理权限版本</p><p v-if="!state.page.items.length">当前范围没有作业，不代表整个租户没有导出</p><ul class="job-cards"><li v-for="job in state.page.items" :key="job.id"><h3>{{statusText(job.status)}}</h3><small>{{job.id}} · v{{job.version}}</small><p>{{displayTime(job.created_at)}}（Asia/Shanghai）</p><p>筛选文本 {{valueText(job.filters.query)}}</p><button type="button" :disabled="busy" @click="select(job.id)">读取当前进度</button></li></ul><button type="button" :disabled="busy||!state.page.has_more" @click="browse(true)">下一页本人作业</button></template>
  </section>
  <section v-if="state.selected" class="panel current-export" :aria-busy="state.busy==='read'"><h2>4. 独立回读的当前进度</h2><label class="check"><input v-model="autoRefresh" type="checkbox" aria-label="自动刷新准备进度" />自动刷新准备进度</label><p class="hint">每2秒查看未完成的准备请求；切到后台、完成准备或读取失败后暂停。历史请求可能需要管理员处理。</p><h3>{{statusText(state.selected.status)}}</h3><p>{{state.selected.id}} · v{{state.selected.version}} · 已登记领取 {{state.selected.attempts}} 次</p><p>更新时间 {{displayTime(state.selected.updated_at)}}（Asia/Shanghai）；Worker在线未知</p><p>类型 {{valueText(state.selected.filters.type)}} · 状态 {{valueText(state.selected.filters.status)}}</p><p>站点 {{valueText(state.selected.filters.site_id)}} · 文本 {{valueText(state.selected.filters.query)}}</p>
   <p v-if="!state.selected.prepared" class="no-preparation">没有准备描述，行数与文件大小未知；不显示为0行成功</p><div v-else class="prepared-descriptor"><h3>已保存的文件准备信息</h3><p>{{state.selected.prepared.row_count}} 行{{state.selected.prepared.row_count===0?'（明确空结果）':''}} · {{state.selected.prepared.size_bytes}} 字节</p><p>生成 {{displayTime(state.selected.prepared.generated_at)}}（Asia/Shanghai）</p><small>文件SHA256 {{state.selected.prepared.file_sha256}}</small><small>筛选SHA256 {{state.selected.prepared.selection_sha256}}</small></div>
   <p v-if="state.selected.error_code" class="warning" role="alert">{{errorText(state.selected.error_code)}}；失败不删除原请求或已准备描述</p><AssetExportPublicationPanel :key="state.selected.id+':'+state.selected.version" ref="publicationPanel" :job-id="state.selected.id" :job-version="state.selected.version" @changed="select(state.selected!.id)" />
  </section>
 </template></section>
</template>
<style scoped>
.export-page{min-width:0}.export-page .panel{margin-top:18px;padding:20px;min-width:0}.export-page p{line-height:1.7;overflow-wrap:anywhere}.export-page h2{font-size:18px;margin:0 0 12px}.export-page h3{font-size:15px;margin:12px 0}.export-page small{display:block;overflow-wrap:anywhere;font:12px monospace;color:#607086;margin:8px 0}.export-page fieldset{border:1px solid #dbe4ef;border-radius:8px;padding:14px;min-width:0}.export-page legend{padding:0 6px}.filter-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.filter-grid label{display:grid;gap:7px;min-width:0}.export-page input:not([type=checkbox]),.export-page select{width:100%;min-height:44px;box-sizing:border-box;padding:10px;border:1px solid #bccbdc;border-radius:7px;background:#fff;font:inherit}.export-page .check{display:flex;align-items:center;gap:10px;min-height:44px;line-height:1.6}.export-page input[type=checkbox]{width:20px;height:20px;flex-shrink:0}.acknowledge{margin:12px 0}.actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.export-page button{min-height:44px;padding:9px 14px;font:inherit;border:1px solid #cbd7e5;border-radius:8px;background:white;color:#294665;cursor:pointer}.export-page button:disabled{opacity:.5;cursor:not-allowed}.export-page :is(button,input,select):focus-visible{outline:3px solid #176bb5;outline-offset:2px}.export-page .warning{color:#a04913}.export-page .hint{font-size:13px;color:#607086}.job-cards{list-style:none;padding:0;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.job-cards li,.prepared-descriptor,.original-receipt{border:1px solid #dce5f0;border-radius:9px;padding:14px;min-width:0}.original-receipt{margin-top:14px}@media(max-width:760px){.filter-grid,.job-cards{grid-template-columns:1fr}.export-page .panel{padding:14px}}
</style>
