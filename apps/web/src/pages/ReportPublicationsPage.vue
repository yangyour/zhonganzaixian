<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { session, contextGeneration, switchingTenant, can } from '../stores/session'
import { contextTransitionGuards } from '../utils/contextTransitionGuard'
import { createReportCommands, readPublication, type Publication } from '../utils/reportPublications'
import { fetchPublicationContent } from '../utils/reportPublicationContent'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import ReportReadershipPanel from '../components/ReportReadershipPanel.vue'
import ReportFollowupPanel from '../components/ReportFollowupPanel.vue'
const route=useRoute(),items=ref<Publication[]>([]),selected=ref<Publication|null>(null),loading=ref(false),error=ref<unknown>(null),next=ref<{before:string;before_id:string}|null>(null),reason=ref('')
const router=useRouter()
const detailHeading=ref<HTMLElement|null>(null)
const allowed=computed(()=>session.value?.mode==='remote_test'&&can('dashboard:read')&&!switchingTenant.value)
const writable=computed(()=>allowed.value&&can('inspection_quarterly_reports:write')),manager=computed(()=>writable.value&&can('configurations:write'))
const journalScope=computed(()=>JSON.stringify([session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,allowed.value,writable.value,manager.value]))
const scope=computed(()=>journalScope.value+':'+contextGeneration.value)
const storage={getItem:(k:string)=>window.sessionStorage.getItem(k),setItem:(k:string,v:string)=>window.sessionStorage.setItem(k,v),removeItem:(k:string)=>window.sessionStorage.removeItem(k)}
const command=createReportCommands(()=>({key:journalScope.value,tenant:session.value?.tenant.id||'',actor:session.value?.user.id||''}),storage)
const locked=computed(()=>loading.value||command.busy.value||!!command.pending.value||command.blocked.value)
const source=computed(()=>typeof route.query.source_id==='string'?route.query.source_id:null),sourceHash=computed(()=>typeof route.query.source_sha256==='string'?route.query.source_sha256:null)
const isUuid=(value:unknown):value is string=>typeof value==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const revising=computed(()=>route.query.revision_id!==undefined)
const revisionTarget=computed(()=>{
 const id=route.query.revision_id,raw=route.query.revision_version
 if(!isUuid(id)||typeof raw!=='string'||!/^\d+$/.test(raw))return null
 const version=Number(raw)
 return Number.isSafeInteger(version)&&version>=1&&version<9007199254740990?{id,version}:null
})
const revisionReady=computed(()=>writable.value&&revisionTarget.value&&selected.value
 &&selected.value.state.document.id===revisionTarget.value.id&&selected.value.state.version===revisionTarget.value.version
 &&['RETURNED','PUBLISHED'].includes(selected.value.state.phase)&&isUuid(source.value)&&!!sourceHash.value&&/^[a-f0-9]{64}$/.test(sourceHash.value))
const phases:Record<string,string>={DRAFT:'草稿',GENERATING:'制作中',REVIEW_REQUIRED:'待审核',APPROVED:'已审核，未发布',PUBLISHED:'已发布',RETURNED:'已退回',FAILED:'制作失败'}
let epoch=0,abort:AbortController|null=null
async function browse(cursor:{before:string;before_id:string}|null=null){if(!allowed.value||locked.value)return;const turn=++epoch,current=scope.value,controller=new AbortController();abort=controller;loading.value=true;items.value=[];next.value=null;selected.value=null;error.value=null
 try{const result=await api.request<{items:unknown[];next:{before:string;before_id:string}|null;external_dispatch_enabled:false}>('/report-publications'+(cursor?'?'+new URLSearchParams(cursor):''),{signal:controller.signal});if(turn!==epoch||current!==scope.value||controller.signal.aborted)return
  if(!result||!Array.isArray(result.items)||result.items.length>20||result.external_dispatch_enabled!==false)throw new Error('报告列表未通过核对')
  const rows=result.items.map(v=>readPublication(v,session.value!.tenant.id));if(new Set(rows.map(v=>v.state.document.id)).size!==rows.length)throw new Error('报告列表重复')
  if(result.next&&(result.next.before_id!==rows.at(-1)?.state.document.id||result.next.before!==rows.at(-1)?.state.document.captured_at))throw new Error('报告分页游标不一致')
  items.value=rows;next.value=result.next
 }catch(e){if(turn===epoch)error.value=e}finally{if(turn===epoch)loading.value=false}}
async function select(id:string){
 if(locked.value)return
 const turn=++epoch,current=scope.value,controller=new AbortController()
 abort=controller;loading.value=true;selected.value=null;error.value=null
 try{
  const value=await api.request<unknown>(`/report-publications/${id}`,{signal:controller.signal})
  if(turn===epoch&&current===scope.value&&!controller.signal.aborted){
   selected.value=readPublication(value,session.value!.tenant.id,id)
   await nextTick()
   if(turn===epoch&&current===scope.value&&!controller.signal.aborted)detailHeading.value?.focus()
  }
 }catch(e){if(turn===epoch)error.value=e}finally{if(turn===epoch)loading.value=false}
}
async function prepare(){if(!writable.value||locked.value||!source.value||!sourceHash.value)return;if(await command.submit('/report-publications',{source_id:source.value,expected_source_sha256:sourceHash.value}))await browse()}
async function revise(){
 const target=revisionTarget.value
 if(!revisionReady.value||!target||locked.value||!reason.value.trim())return
 if(await command.submit(`/report-publications/${target.id}/revisions`,{expected_version:target.version,
  source_id:source.value,expected_source_sha256:sourceHash.value,reason:reason.value}))await showConfirmedRevision()
}
async function showConfirmedRevision(){
 const result=command.confirmed.value
 if(result){reason.value='';await router.replace({path:route.path,query:{publication_id:result.state.document.id}})}
}
async function action(action:string){if(!selected.value||locked.value||!writable.value)return;const id=selected.value.state.document.id;if(await command.submit(`/report-publications/${id}/actions`,{action,expected_version:selected.value.state.version,reason:action==='RETURN'?reason.value:null})){reason.value='';await select(id)}}
async function download(format:'html'|'pdf') {
 if(!selected.value||locked.value)return
 const current=scope.value,record=selected.value
 if(!writable.value&&!await command.submit(`/report-publications/${record.state.document.id}/read`,{expected_version:record.state.version}))return
 if(current!==scope.value)return
 const turn=++epoch,controller=new AbortController();abort=controller;loading.value=true;error.value=null
 try {
  const file=await fetchPublicationContent({id:record.state.document.id,tenant:session.value!.tenant.id,documentHash:record.state.document.document_sha256,contentHash:record.state[format]!.content_sha256,phase:record.state.phase,format,signal:controller.signal,active:()=>current===scope.value&&turn===epoch})
  if(current!==scope.value||turn!==epoch||controller.signal.aborted)return
  const url=URL.createObjectURL(file.blob),a=document.createElement('a');try{a.href=url;a.download=file.filename;a.hidden=true;document.body.append(a);a.click()}finally{a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000)}
 }catch(e){if(turn===epoch)error.value=e}finally{if(turn===epoch)loading.value=false}
}
async function retry(){const revision=command.pending.value?.path.endsWith('/revisions');if(await command.retry()){if(revision)await showConfirmedRevision();else await browse()}}
function unresolved(){return !!command.pending.value||command.busy.value||command.blocked.value}
async function mayLeave(){
 if(unresolved())return false
 if(!reason.value.trim())return true
 const original=reason.value,current=scope.value
 try{await ElMessageBox.confirm('原因尚未提交，是否放弃输入并离开？','未保存内容',
  {confirmButtonText:'放弃并离开',cancelButtonText:'继续编辑',type:'warning'})}
 catch{return false}
 return current===scope.value&&reason.value===original&&!unresolved()
}
onBeforeRouteLeave(mayLeave);onBeforeRouteUpdate(mayLeave);const unregister=contextTransitionGuards.register(mayLeave)
function beforeUnload(e:BeforeUnloadEvent){if(unresolved()||reason.value.trim()){e.preventDefault();e.returnValue=''}}window.addEventListener('beforeunload',beforeUnload)
watch([scope,()=>route.fullPath],()=>{
 epoch++;abort?.abort();items.value=[];selected.value=null;next.value=null;loading.value=false;error.value=null;reason.value='';command.reset()
 if(allowed.value){const current=scope.value,path=route.fullPath;void(async()=>{
  await browse()
  if(current!==scope.value||path!==route.fullPath||locked.value)return
  const id=revisionTarget.value?.id??route.query.publication_id
  if(isUuid(id))await select(id)
 })()}
},{immediate:true,flush:'sync'})
onUnmounted(()=>{epoch++;abort?.abort();command.dispose();unregister();window.removeEventListener('beforeunload',beforeUnload)})
</script>
<template>
 <section aria-label="报告中心" class="proto-panel">
  <div class="actions"><h1>报告中心</h1><button :disabled="locked||!allowed" @click="browse()">刷新</button><router-link v-if="writable" to="/inspection-quarterly-reports">冻结来源草稿</router-link></div>
  <ReportFollowupPanel v-if="writable" :disabled="locked" @open="select" />
  <p v-if="!allowed">当前模式或授权不支持读取报告中心。</p><ApiErrorNotice :error="error"/><ApiErrorNotice :error="command.error.value"/>
  <p v-if="command.pending.value" role="alert">上次操作结果待确认，请按原请求核对。<button :disabled="command.busy.value" @click="retry">核对原请求</button><button v-if="command.busy.value" @click="command.cancel">停止等待</button></p>
  <p v-if="command.blocked.value" role="alert">无法安全恢复原请求，已阻止新增操作。</p>
  <button v-if="writable&&source&&sourceHash&&!revising" :disabled="locked" @click="prepare">由已选冻结来源创建报告</button>
  <p v-if="revising&&!revisionTarget" role="alert">修订入口已失效，请从原报告重新选择来源。</p>
  <p v-if="loading" role="status">正在读取…</p>
  <table v-if="items.length"><thead><tr><th>报告</th><th>状态</th><th>冻结时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in items" :key="row.state.document.id"><td>{{row.label}}</td><td>{{phases[row.state.phase]}}</td><td>{{row.state.document.captured_at}}</td><td><button :disabled="locked" @click="select(row.state.document.id)">查看</button></td></tr></tbody></table>
  <p v-else-if="allowed&&!loading&&!error">当前页没有可见报告。</p><div class="actions"><button :disabled="locked" @click="browse()">第一页</button><button v-if="next" :disabled="locked" @click="browse(next)">下一页</button></div>
  <section v-if="selected" aria-label="报告详情"><h2 ref="detailHeading" tabindex="-1">{{selected.label}}</h2><p>{{phases[selected.state.phase]}} · 第 {{selected.state.document.revision}} 次修订 · 状态版本 {{selected.state.version}}</p><p v-if="selected.state.reason" role="status">{{selected.state.reason}}</p>
   <router-link v-if="selected.state.document.parent_id" class="report-revision-link" :to="{path:route.path,query:{publication_id:selected.state.document.parent_id}}">查看上一修订</router-link>
   <div v-if="writable&&['RETURNED','PUBLISHED'].includes(selected.state.phase)">
    <router-link class="report-revision-link" :to="{path:'/inspection-quarterly-reports',query:{revision_id:selected.state.document.id,revision_version:String(selected.state.version)}}">选择冻结来源并创建新修订</router-link>
    <div v-if="revisionReady">
     <p>已选择冻结来源。新修订需重新制作并审核，原报告及阅读记录保留。</p>
     <label>更正原因<input v-model="reason" maxlength="3000" :disabled="locked" /></label>
     <button :disabled="locked||!reason.trim()" @click="revise">创建新修订</button>
    </div>
    <p v-else-if="revisionTarget?.id===selected.state.document.id&&selected.state.version!==revisionTarget.version" role="alert">原报告状态已变化，请重新选择修订来源。</p>
   </div>
   <details v-if="selected.state.document.incomplete_reasons.length"><summary>待补全事项（{{selected.state.document.incomplete_reasons.length}}）</summary><ul><li v-for="reason in selected.state.document.incomplete_reasons" :key="reason">{{reason}}</li></ul></details>
   <div class="actions"><button v-if="writable&&['DRAFT','FAILED'].includes(selected.state.phase)" :disabled="locked" @click="action('RENDER')">准备 HTML / PDF</button>
   <button v-if="manager&&selected.state.phase==='REVIEW_REQUIRED'" :disabled="locked" @click="action('APPROVE')">审核通过</button><button v-if="manager&&selected.state.phase==='APPROVED'" :disabled="locked" @click="action('PUBLISH')">发布给配置中的接收人</button><button v-if="manager&&selected.state.phase==='PUBLISHED'" :disabled="locked" @click="action('RESEND')">登记重发意图（不外发）</button></div>
   <div v-if="manager&&['REVIEW_REQUIRED','APPROVED'].includes(selected.state.phase)"><label>退回原因<input v-model="reason" maxlength="3000" :disabled="locked" /></label><button :disabled="locked||!reason.trim()" @click="action('RETURN')">退回</button></div>
   <div class="actions"><button v-if="selected.state.html" :disabled="locked" @click="download('html')">{{writable?'下载 HTML':'登记阅读并下载 HTML'}}</button><button v-if="selected.state.pdf" :disabled="locked" @click="download('pdf')">{{writable?'下载 PDF':'登记阅读并下载 PDF'}}</button></div>
   <p>发布和阅读记录不代表客户验收；交付意图不代表已发送。下载含租户数据，离线副本无法随撤权收回。</p>
   <ReportReadershipPanel v-if="writable&&selected.state.phase==='PUBLISHED'" :key="selected.state.document.id" :id="selected.state.document.id" :hash="selected.state.document.document_sha256" :version="selected.state.version" />
  </section>
 </section>
</template>
<style scoped>
.report-revision-link {
 display: inline-flex;
 align-items: center;
 min-height: 32px;
 padding: 4px 0;
}
</style>
