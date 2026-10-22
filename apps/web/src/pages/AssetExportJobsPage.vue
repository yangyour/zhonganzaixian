<script setup lang="ts">
import {computed,onMounted,onUnmounted,reactive,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave,useRouter} from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {createAssetExportWorkspace,emptyAssetExportState,type AssetExportState} from '../utils/assetExportWorkspace'
import {assetExportInput,type AssetExportFilters} from '../utils/assetExportJobs'
import {displayTime} from '../utils/display'
import ReadRecoveryActions from '../components/ReadRecoveryActions.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import AssetExportPublicationPanel from '../components/AssetExportPublicationPanel.vue'
import {assetExportHandoffScope,readAssetExportHandoff} from '../utils/assetExportHandoff'
const router=useRouter()
const incomingHandoff=router.options?.history?.state?.assetExportHandoff
const sourceListQuery=ref<Record<string,string>|null>(null)
let handoffConsumed=false
const publicationPanel=ref<InstanceType<typeof AssetExportPublicationPanel>|null>(null)
const fresh=()=>({type:'',status:'',siteAny:true,site:'',queryAny:true,query:''})
const form=reactive(fresh()),acknowledge=ref(false),dirty=ref(false),confirming=ref(false),validation=ref('')
const state=shallowRef<AssetExportState>(emptyAssetExportState()),workspace=createAssetExportWorkspace(api,s=>{state.value=s})
let disposed=false
const autoRefresh=ref(true),formOpen=ref(false),readScope=ref(0)
let preparationTimer:ReturnType<typeof setInterval>|undefined
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&['padmin','msp'].includes(session.value.user.role)&&can('assets:read')&&!switchingTenant.value))
const contextKey=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,session.value?.user.role,permitted.value]))
const busy=computed(()=>Boolean(state.value.busy)||confirming.value),locked=computed(()=>busy.value||state.value.attempt!==null)
const originalFilters=computed<AssetExportFilters|null>(()=>state.value.attempt?JSON.parse(state.value.attempt.payload).filters:null)
const typeLabels:Record<string,string>={router:'路由器',switch:'交换机',firewall:'防火墙',ap:'无线AP',wireless_controller:'无线控制器',other:'其他'}
const statusLabels:Record<string,string>={unknown:'未知',ok:'正常记录',problem:'问题记录',maintenance:'维护记录',disabled:'禁用记录'}
function filters():AssetExportFilters{return{type:form.type||null,status:form.status||null,site_id:form.siteAny?null:form.site,query:form.queryAny?null:form.query}}
function change(key:keyof typeof form,value:string|boolean){if(disposed||!permitted.value||locked.value||typeof value!==typeof form[key])return false;Object.assign(form,{[key]:value});dirty.value=true;acknowledge.value=false;validation.value='';return true}
function inputValue(event:Event){return(event.target as HTMLInputElement).value}
function checked(event:Event){return(event.target as HTMLInputElement).checked}
function changeAck(value:boolean){if(disposed||!permitted.value||locked.value)return false;acknowledge.value=value;return true}
watch(contextKey,()=>{
  readScope.value++;Object.assign(form,fresh());acknowledge.value=false;dirty.value=false;confirming.value=false;validation.value=''
  sourceListQuery.value=null
  const s=session.value
  workspace.reset(!disposed&&permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role,canRead:true}:null)
  if(!disposed&&permitted.value){
    if(!handoffConsumed){
      handoffConsumed=true
      const handoff=readAssetExportHandoff(incomingHandoff,assetExportHandoffScope(s,contextGeneration.value))
      if(handoff){
        const f=handoff.filters
        Object.assign(form,{type:f.type??'',status:f.status??'',siteAny:f.site_id===null,site:f.site_id??'',queryAny:f.query===null,query:f.query??''})
        sourceListQuery.value=handoff.listQuery
        formOpen.value=true
      }else if(incomingHandoff!==undefined&&incomingHandoff!==null){
        formOpen.value=true
        validation.value='原列表筛选已失效或未通过核对，请返回资产列表重新发起，或重新填写并确认导出范围。'
      }
    }
    void workspace.list()
  }
},{immediate:true,flush:'sync'})
async function readAccepted(){const receipt=state.value.receipt;if(!disposed&&permitted.value&&receipt)await workspace.select(receipt.job_id)}
async function submit(){if(disposed||!permitted.value||locked.value||!acknowledge.value||publicationPanel.value&&!publicationPanel.value.mayLeave())return false;validation.value='';try{assetExportInput(filters())}catch{validation.value='筛选未通过：请核对类型、状态和文本长度；没有受理';return false}const accepted=await workspace.submit(filters());if(accepted)await readAccepted();return accepted}
async function retry(){if(disposed||!permitted.value||busy.value||publicationPanel.value&&!publicationPanel.value.mayLeave())return false;const accepted=await workspace.retry();if(accepted)await readAccepted();return accepted}
function pollPreparation(){const current=state.value.selected;if(disposed||!permitted.value||document.hidden||!autoRefresh.value||busy.value||workspace.unresolved()||publicationPanel.value?.unresolved()||!current||current.prepared!==null||!['QUEUED','RUNNING'].includes(current.status))return;void workspace.refreshSelected()}
onMounted(()=>{preparationTimer=setInterval(pollPreparation,2000);document.addEventListener('visibilitychange',pollPreparation)})
async function browse(next=false){if(disposed||!permitted.value||busy.value||publicationPanel.value&&!publicationPanel.value.mayLeave())return false;return workspace.list(next)}
function cancelRead(){if(publicationPanel.value&&!publicationPanel.value.mayLeave())return false;const cancelled=workspace.cancelRead();if(cancelled)autoRefresh.value=false;return cancelled}
async function retryRead(){if(disposed||!permitted.value||busy.value||publicationPanel.value&&!publicationPanel.value.mayLeave())return false;return workspace.retryRead()}
async function select(id:string){if(disposed||!permitted.value||busy.value||publicationPanel.value&&!publicationPanel.value.mayLeave())return false;return workspace.select(id)}
async function discard(){if(publicationPanel.value&&!publicationPanel.value.mayLeave())return false;if(workspace.unresolved()){ElMessage.warning('原请求正在提交或结果未知，必须先用原请求恢复；普通刷新不能确认');return false}if(confirming.value)return false;if(!dirty.value&&!state.value.attempt)return true;const turn=contextKey.value;confirming.value=true;try{await ElMessageBox.confirm('已受理作业会保留在服务器；继续将清除本页筛选草稿与原收据显示，确认继续？','离开当前准备请求',{confirmButtonText:'清除并继续',cancelButtonText:'留在本页',type:'warning'});return!disposed&&turn===contextKey.value&&!workspace.unresolved()}catch{return false}finally{if(!disposed&&turn===contextKey.value)confirming.value=false}}
async function beginNew(){const turn=contextKey.value;if(disposed||!permitted.value||busy.value||!await discard()||turn!==contextKey.value||!workspace.begin())return false;readScope.value++;Object.assign(form,fresh());sourceListQuery.value=null;acknowledge.value=false;dirty.value=false;validation.value='';return true}
function unlockRejected(){if(disposed||!permitted.value||busy.value||state.value.attempt?.state!=='rejected'||!workspace.begin())return false;readScope.value++;acknowledge.value=false;dirty.value=true;return true}
onBeforeRouteLeave(discard);const unregister=contextTransitionGuards.register(discard)
function beforeUnload(event:BeforeUnloadEvent){if(dirty.value||state.value.attempt||workspace.unresolved()){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{disposed=true;if(preparationTimer!==undefined)clearInterval(preparationTimer);document.removeEventListener('visibilitychange',pollPreparation);unregister();workspace.dispose();window.removeEventListener('beforeunload',beforeUnload)})
const statusText=(s:string)=>({QUEUED:'排队等待生成',RUNNING:'正在生成导出文件',RECONCILING_STORAGE:'正在核对存储状态',SUCCEEDED:'库内结果记为成功，存储尚未复核',FAILED:'作业执行未完成，可查看错误原因'}[s]??'未知阶段')
const valueText=(v:string|null)=>v===null?'不限':v===''?'明确空字符串':v
const errorText=(s:string)=>({AUTHORIZATION_CHANGED:'原受理授权已变化',SOURCE_UNAVAILABLE:'来源不可用',INPUT_LIMIT:'输入超过技术边界',ENCODING_FAILED:'文件编码失败',CONFIRMED_STORAGE_REJECTION:'存储已明确拒绝'}[s]??'未知错误')
defineExpose({form,state,acknowledge,locked,autoRefresh,pollPreparation,filters,change,changeAck,submit,retry,browse,select,beginNew,unlockRejected,discard,cancelRead,retryRead,hasUnresolved:workspace.unresolved})
</script>
<template>
 <section class="export-page"><h1 class="page-title-sr">资产台账导出 </h1>
 
 <p v-if="!permitted" class="panel">当前身份没有内部导出准备权限，或租户正在切换；未展示旧数据</p>
 <template v-else>
  <div v-if="sourceListQuery" class="panel content-toolbar" aria-label="资产列表导出衔接">
    <span>已带入资产列表的已应用筛选，请核对后提交；导出全部匹配记录，不仅是当前页。<template v-if="!state.attempt&&!state.receipt">尚未创建导出任务。</template></span>
    <router-link :to="{path:'/assets',query:sourceListQuery}">返回原筛选列表</router-link>
  </div>
  
  <section class="panel export-list"><div class="actions content-toolbar"><h2>我的导出作业</h2><button type="button" :disabled="busy" @click="browse()">刷新任务列表</button><div class="content-actions"><button v-if="permitted" class="export-create" type="button" @click="formOpen=true">新建导出请求</button></div></div><p v-if="!state.page">{{state.busy==='read'?'正在加载任务列表…':state.error?'任务列表加载未完成':'暂未加载任务列表'}}</p>
   <template v-else><p class="export-count">{{state.paged?'续页':'第一页'}} · 本页 {{state.page.items.length}} 条</p><p v-if="!state.page.items.length">当前筛选范围内暂无导出作业记录</p><div class="proto-table-wrap"><table class="job-cards proto-table"><thead><tr><th>导出作业</th><th>准备状态</th><th>筛选文本</th><th>创建时间</th><th>操作</th></tr></thead><tbody><tr v-for="job in state.page.items" :key="job.id"><td><span class="export-file-icon">X</span><span>设备台账 XLSX</span><small>{{job.id}} · v{{job.version}}</small></td><td><span class="proto-badge">{{statusText(job.status)}}</span></td><td>{{valueText(job.filters.query)}}</td><td>{{displayTime(job.created_at)}}<span class="proto-sub">Asia/Shanghai</span></td><td><button class="proto-link" type="button" :disabled="busy" @click="select(job.id)">读取当前进度</button></td></tr></tbody></table></div><button type="button" :disabled="busy||!state.page.has_more" @click="browse(true)">下一页本人作业</button></template>
  </section>
  <details class="export-compose proto-panel" :open="formOpen || dirty || !!state.attempt" @toggle="formOpen=($event.target as HTMLDetailsElement).open"><summary>新建导出</summary>
  <form v-form-feedback class="panel export-form" @submit.prevent="submit"><h2>导出筛选范围</h2>
   <fieldset :disabled="locked"><legend>导出筛选条件</legend><div class="filter-grid">
    <label>类型<select aria-label="导出设备类型" :value="form.type" @change="change('type',inputValue($event))"><option value="">不限</option><option v-for="(label,key) in typeLabels" :key="key" :value="key">{{label}}</option></select></label>
    <label>状态<select aria-label="导出设备状态" :value="form.status" @change="change('status',inputValue($event))"><option value="">不限</option><option v-for="(label,key) in statusLabels" :key="key" :value="key">{{label}}</option></select></label>
    <div><label class="check"><input type="checkbox" aria-label="导出站点不限" :checked="form.siteAny" @change="change('siteAny',checked($event))" />全部站点（不限）</label><label>指定站点 ID<input aria-label="导出站点ID" :disabled="form.siteAny" :value="form.site" maxlength="64" @input="change('site',inputValue($event))" /></label></div>
    <div><label class="check"><input type="checkbox" aria-label="导出文本不限" :checked="form.queryAny" @change="change('queryAny',checked($event))" />全部关键字（不限）</label><label>指定检索文本<input aria-label="导出检索文本" :disabled="form.queryAny" :value="form.query" maxlength="100" @input="change('query',inputValue($event))" /></label></div>
   </div><label class="check acknowledge"><input type="checkbox" aria-label="确认仅准备不发布" :checked="acknowledge" @change="changeAck(checked($event))" />我已核对导出筛选条件，确认提交后台文件生成任务</label></fieldset>
   <p v-if="validation" role="alert" class="warning">{{validation}}</p><div class="actions"><button type="submit" :disabled="locked||!acknowledge">提交导出申请</button><button type="button" :disabled="busy||workspace.unresolved()" @click="beginNew">重置条件</button></div>
  </form>
  </details>
  <ReadRecoveryActions :busy="state.busy" :can-retry="!confirming&&workspace.canRetryRead()" :scope-key="contextKey+':'+readScope" label="导出作业" :cancel="cancelRead" :retry="retryRead" />
  <p v-if="!autoRefresh" class="hint">自动刷新已暂停，不会取消后台导出任务。</p>
  <ApiErrorNotice :error="state.error" />
  <section v-if="state.attempt" class="panel original-request"><h2>本次提交状态与受理回执</h2><p>状态：{{state.attempt.state==='uncertain'?'结果未知，请先通过重试确认提交结果':state.attempt.state==='accepted'?'已收到后台受理回执':'请求未通过校验，可解锁后修改筛选条件'}}</p><p v-if="originalFilters">类型：{{valueText(originalFilters.type)}} · 状态：{{valueText(originalFilters.status)}}</p><p v-if="originalFilters">站点：{{valueText(originalFilters.site_id)}} · 检索文本：{{valueText(originalFilters.query)}}</p>
   <button v-if="state.attempt.state==='uncertain'" type="button" :disabled="busy" @click="retry">重试提交</button><button v-if="state.attempt.state==='rejected'" type="button" :disabled="busy" @click="unlockRejected">返回修改筛选</button>
   <div v-if="state.receipt" class="original-receipt"><h3>受理回执：QUEUED / v0</h3><p>任务编号 {{state.receipt.job_id}} · 已加入后台生成队列</p><button type="button" :disabled="busy" @click="select(state.receipt.job_id)">刷新进度</button></div>
  </section>
  <section v-if="state.selected" class="panel current-export" :aria-busy="state.busy==='read'"><h2>导出进度与文件发布</h2><label class="check"><input v-model="autoRefresh" type="checkbox" aria-label="自动刷新准备进度" />自动刷新准备进度</label><h3>{{statusText(state.selected.status)}}</h3><p>{{state.selected.id}} · v{{state.selected.version}} · 已调度执行 {{state.selected.attempts}} 次</p><p>更新时间 {{displayTime(state.selected.updated_at)}}（Asia/Shanghai）</p><p>类型：{{valueText(state.selected.filters.type)}} · 状态：{{valueText(state.selected.filters.status)}}</p><p>站点：{{valueText(state.selected.filters.site_id)}} · 检索文本：{{valueText(state.selected.filters.query)}}</p>
   <p v-if="!state.selected.prepared" class="no-preparation">文件尚未生成完毕，行数与文件大小未知</p><div v-else class="prepared-descriptor"><h3>已生成的文件信息</h3><p>{{state.selected.prepared.row_count}} 行{{state.selected.prepared.row_count===0?'（明确空结果）':''}} · {{state.selected.prepared.size_bytes}} 字节</p><p>生成时间 {{displayTime(state.selected.prepared.generated_at)}}（Asia/Shanghai）</p><small>文件SHA256 {{state.selected.prepared.file_sha256}}</small><small>筛选SHA256 {{state.selected.prepared.selection_sha256}}</small></div>
   <p v-if="state.selected.error_code" class="warning" role="alert">{{errorText(state.selected.error_code)}}（历史请求参数与文件摘要已保留）</p><AssetExportPublicationPanel :key="state.selected.id+':'+state.selected.version" ref="publicationPanel" :job-id="state.selected.id" :job-version="state.selected.version" :parent-busy="busy" @changed="select(state.selected!.id)" />
  </section>
 </template></section>
</template>
<style scoped>
.export-page{min-width:0}.export-page .panel{margin-top:12px;border-radius:3px;padding:18px;min-width:0}.export-page p{line-height:1.7;overflow-wrap:anywhere;font-size:12px}.export-page h2{font-size:14px;margin:0 0 12px}.export-page h3{font-size:13px;margin:12px 0}.export-page small{display:block;overflow-wrap:anywhere;font:12px monospace;color:#607086;margin:8px 0}.export-page fieldset{border:0;border-radius:0;padding:12px 0;min-width:0}.export-page legend{padding:0;color:#8b96a3;font-size:12px}.filter-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.filter-grid label{display:grid;gap:7px;min-width:0}.export-page :is(input:not([type=checkbox]),select,button){min-height:32px;box-sizing:border-box;padding:6px 10px;border:1px solid #cbd7e5;border-radius:3px;background:#fff;color:#294665;font:inherit;font-size:12px}.export-page input:not([type=checkbox]),.export-page select{width:100%}.export-page .check{display:flex;align-items:center;gap:10px;min-height:36px;line-height:1.6}.export-page input[type=checkbox]{width:18px;height:18px;flex-shrink:0}.acknowledge{margin:12px 0}.actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.export-page button{cursor:pointer}.export-page button:disabled{opacity:.5;cursor:not-allowed}.export-page :is(button,input,select):focus-visible{outline:3px solid #176bb5;outline-offset:2px}.export-page .warning{color:#a04913}.export-page .hint{font-size:12px;color:#607086}.export-page .export-create,.export-page button[type=submit]{background:#2d79f6;color:white;border-color:#2d79f6}.export-page .export-list{padding:0;overflow:hidden}.export-list>.actions{padding:12px 18px;border-bottom:1px solid #edf1f7;justify-content:space-between}.export-list h2{margin:0}.export-list>p,.export-list>button{margin:12px 18px}.job-cards{display:table;width:100%;border-collapse:collapse;margin:0}.job-cards small{font-size:10px;max-width:200px;margin:5px 0 0}.job-cards .proto-badge{white-space:normal;line-height:1.6}.export-page .proto-link{border:0;padding:0;color:#2d79f6;background:none}.export-file-icon{display:inline-grid;place-items:center;color:#269775;background:#ebf8f2;border:1px solid #d4eee2;width:24px;height:28px;font-size:12px;margin-right:8px}.export-compose{margin-top:12px}.export-compose>summary{padding:15px 18px;cursor:pointer;color:#327ff2;font-size:13px}.export-page .export-compose .export-form{border:0;border-top:1px solid #edf1f7;border-radius:0;margin:0}.export-page .original-request{border-left:3px solid #e5ab52}.export-page .current-export{border-top:2px solid #2d79f6}.prepared-descriptor,.original-receipt{border:1px solid #dce5f0;border-radius:3px;padding:14px;min-width:0;background:#f8fbff}.original-receipt{margin-top:14px}@media(max-width:760px){.filter-grid{grid-template-columns:1fr}.export-page .panel{padding:14px}.export-page :is(input:not([type=checkbox]),select,button){min-height:44px}}
</style>
