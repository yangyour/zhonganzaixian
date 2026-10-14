<script setup lang="ts">
import {computed,onUnmounted,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {createExportInbox,emptyInbox,type InboxState,type ExportMessage} from '../utils/exportInbox'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const state=shallowRef<InboxState>(emptyInbox()),workspace=createExportInbox(api,s=>{state.value=s}),elapsed=ref(0)
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&['padmin','msp'].includes(session.value.user.role)&&can('assets:read')&&!switchingTenant.value))
const context=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,permitted.value]))
const locked=computed(()=>!!state.value.busy||workspace.unresolved())
watch(context,()=>{const s=session.value;workspace.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role,canRead:true}:null);if(permitted.value)void workspace.list()},{immediate:true,flush:'sync'})
let loaded=performance.now();watch(()=>state.value.page?.observed_at,p=>{if(p){loaded=performance.now();elapsed.value=0}})
const timer=setInterval(()=>{elapsed.value=Math.max(0,performance.now()-loaded)},1000)
function available(m:ExportMessage){return m.link_status==='AVAILABLE'&&m.content_url!==null&&state.value.page!==null&&Date.parse(m.expires_at)-Date.parse(state.value.page.observed_at)>elapsed.value}
async function mark(id:string){if(await workspace.mark(id))await workspace.list()}
async function recover(){if(await workspace.retry())await workspace.list()}
const blocked=ref(false)
function mayLeave(){if(workspace.unresolved()){blocked.value=true;return false}return true}
onBeforeRouteLeave(mayLeave);const unregister=contextTransitionGuards.register(mayLeave)
function beforeUnload(e:BeforeUnloadEvent){if(workspace.unresolved()){e.preventDefault();e.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{clearInterval(timer);unregister();window.removeEventListener('beforeunload',beforeUnload);workspace.dispose()})
defineExpose({state,workspace,mayLeave})
</script>
<template>
 <section class="export-inbox"><div class="page-heading"><div><p class="eyebrow">工单与服务</p><h1>我的导出消息</h1><p>已发布文件的下载消息，仅当前租户下的本人可见。</p></div><RouterLink to="/asset-export-jobs" class="export-back">台账导出</RouterLink></div>
  <p class="panel" v-if="!permitted">当前身份没有导出消息权限，或正在切换租户。</p>
  <template v-else>
   <div class="panel"><p>文件发布成功后保存一条消息。下载链接从发布时起15分钟有效；查看、刷新或标记已读都不会续期。文件不会自动下载。</p><div class="actions"><label><input type="checkbox" aria-label="只看未读导出消息" :checked="state.unreadOnly" :disabled="locked" @change="workspace.list(($event.target as HTMLInputElement).checked)" />只看未读</label><button type="button" :disabled="locked" @click="workspace.list()">刷新消息</button></div></div>
   <ApiErrorNotice :error="state.error" />
   <p v-if="state.attempt||blocked&&workspace.unresolved()" role="alert">标记已读的结果尚未确认，请先恢复原请求。</p><button v-if="state.attempt&&!state.busy" type="button" @click="recover">恢复原已读请求</button>
   <p v-if="state.receipt" role="status">已读状态已记录：{{displayTime(state.receipt.read_at)}}。</p>
   <p v-if="state.busy" role="status" aria-live="polite">{{state.busy==='write'?'正在记录已读状态…':'正在读取导出消息…'}}</p>
   <p v-if="!state.page&&!state.busy">{{state.error?'读取未完成，消息数量未知。':'尚未读取消息。'}}</p>
   <template v-if="state.page"><p>本次读取未读 {{state.page.unread_count}} 条 · 更新于 {{displayTime(state.page.observed_at)}}</p><p v-if="!state.page.items.length" class="panel">{{state.unreadOnly?'当前没有未读导出消息。':'当前没有导出消息。启用前的历史发布不会自动补发。'}}</p>
    <ul class="message-list"><li v-for="m in state.page.items" :key="m.job_id" class="panel message"><div class="message-heading"><h2>台账文件已发布</h2><span class="read-state">{{m.read_at?'已读':'未读'}}</span></div><p>{{m.row_count}} 行 · {{m.size_bytes}} 字节</p><p>发布时间 {{displayTime(m.issued_at)}}</p><p>链接有效至 {{displayTime(m.expires_at)}}</p><div class="actions"><a v-if="available(m)" :href="m.content_url!" referrerpolicy="no-referrer">下载台账 XLSX</a><span v-else class="expired">原下载链接已过期，可在台账导出页重新申请。</span><button v-if="!m.read_at" type="button" :disabled="locked" @click="mark(m.job_id)">标记已读</button></div><p v-if="m.read_at" class="meta">已读于 {{displayTime(m.read_at)}}</p><details><summary>查看文件标识</summary><p class="meta">作业 {{m.job_id}}</p><p class="meta">SHA256 {{m.file_sha256}}</p></details></li></ul>
    <button type="button" :disabled="locked||!state.page.has_more" @click="workspace.list(state.unreadOnly,true)">下一页消息</button>
   </template>
  </template>
 </section>
</template>
<style scoped>
.export-inbox{min-width:0}.export-inbox .panel{padding:20px;margin:16px 0}.export-inbox p{line-height:1.7;overflow-wrap:anywhere}.actions,.message-heading{display:flex;gap:14px;align-items:center;flex-wrap:wrap}.message-heading{justify-content:space-between}.message-heading h2{font-size:18px;margin:0}.message-list{list-style:none;padding:0;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.message-list .panel{margin:0;min-width:0}.export-inbox button,.actions a,.export-back{display:inline-flex;align-items:center;min-height:44px;padding:9px 14px;border:1px solid #cbd7e5;border-radius:8px;background:#fff;color:#245a85;font:inherit;box-sizing:border-box;cursor:pointer}.export-inbox button:disabled{opacity:.5;cursor:not-allowed}.actions label{display:flex;gap:10px;align-items:center;min-height:44px}.actions input{width:20px;height:20px}.meta{font-size:12px;color:#607086;overflow-wrap:anywhere}.read-state{color:#245a85}.expired{color:#80501c}.export-inbox details{margin-top:16px}.export-inbox summary{cursor:pointer}.export-inbox :is(button,a,input,summary):focus-visible{outline:3px solid #176bb5;outline-offset:2px}@media(max-width:760px){.message-list{grid-template-columns:1fr}.export-inbox .panel{padding:14px}}
</style>
