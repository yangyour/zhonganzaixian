<script setup lang="ts">
import {computed,onUnmounted,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {activeInboxDownload,createExportInbox,emptyInbox,type InboxState,type ExportMessage} from '../utils/exportInbox'
import {displayTime} from '../utils/display'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const state=shallowRef<InboxState>(emptyInbox()),workspace=createExportInbox(api,s=>{state.value=s}),clock=ref(performance.now())
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&['padmin','msp'].includes(session.value.user.role)&&can('assets:read')&&!switchingTenant.value))
const context=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,permitted.value]))
const locked=computed(()=>!!state.value.busy||workspace.unresolved())
watch(context,()=>{const s=session.value;workspace.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role,canRead:true}:null);if(permitted.value)void workspace.list()},{immediate:true,flush:'sync'})
watch(state,()=>{clock.value=performance.now()})
const timer=setInterval(()=>{clock.value=performance.now()},1000)
const lastRenewed=computed(()=>state.value.page?.items.find(m=>m.job_id===state.value.lastGrantId))
function download(m:ExportMessage){return activeInboxDownload(state.value,m,clock.value)}
async function mark(id:string){if(await workspace.mark(id))await workspace.list()}
async function recover(){const kind=state.value.attempt?.kind;if(await workspace.retry()&&kind==='read')await workspace.list()}
const blocked=ref(false)
function mayLeave(){if(workspace.unresolved()){blocked.value=true;return false}return true}
onBeforeRouteLeave(mayLeave);const unregister=contextTransitionGuards.register(mayLeave)
function beforeUnload(e:BeforeUnloadEvent){if(workspace.unresolved()){e.preventDefault();e.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{clearInterval(timer);unregister();window.removeEventListener('beforeunload',beforeUnload);workspace.dispose()})
defineExpose({state,workspace,mayLeave})
</script>
<template>
 <section class="export-inbox"><h1 class="page-title-sr">我的导出消息 </h1>
  <p class="panel" v-if="!permitted">当前身份没有导出消息权限，或正在切换租户。</p>
  <template v-else>
   
   <div class="proto-panel inbox-workspace"><div class="proto-toolbar content-toolbar"><div class="proto-tabs" aria-label="导出消息筛选"><button type="button" :class="{ active: !state.unreadOnly }" :aria-pressed="!state.unreadOnly" :disabled="locked" @click="workspace.list(false)">全部消息</button><button type="button" :class="{ active: state.unreadOnly }" :aria-pressed="state.unreadOnly" :disabled="locked" @click="workspace.list(true)">未读消息</button></div><button type="button" :disabled="locked" @click="workspace.list()">刷新消息</button><div class="content-actions"><RouterLink to="/asset-export-jobs" class="export-back">台账导出</RouterLink></div></div>
   
   <ApiErrorNotice :error="state.error" :on-retry="state.page===null&&state.readRequest&&!state.attempt?workspace.retryRead:undefined" :retrying="state.busy==='read'" retry-label="重新加载" />
   <p v-if="state.attempt||blocked&&workspace.unresolved()" role="alert">{{state.attempt?.kind==='grant'?'下载授权':'标记已读'}}的结果尚未确认，请点击下方按钮重试。</p><button v-if="state.attempt&&!state.busy" type="button" @click="recover">{{state.attempt.kind==='grant'?'重试获取链接':'重试标记已读'}}</button>
   <p v-if="lastRenewed" role="status">{{download(lastRenewed)?'新的下载链接已取得，原消息的期限未修改。':'原下载请求已恢复，但链接已经过期，可以重新申请。'}}</p>
   <p v-if="state.receipt" role="status">已读状态已记录：{{displayTime(state.receipt.read_at)}}。</p>
   <p v-if="state.busy" role="status" aria-live="polite">{{state.busy==='write'?(state.attempt?.kind==='grant'?'正在取得下载链接…':'正在记录已读状态…'):'正在读取导出消息…'}}</p>
   <p v-if="!state.page&&!state.busy">{{state.error?'读取未完成，消息数量未知。':'尚未读取消息。'}}</p>
   <template v-if="state.page"><p>本次读取未读 {{state.page.unread_count}} 条 · 更新于 {{displayTime(state.page.observed_at)}}</p><p v-if="!state.page.items.length" class="panel">{{state.unreadOnly?'当前没有未读导出消息。':'当前没有导出消息。'}}</p>
    <div class="proto-table-wrap"><table class="message-list proto-table"><thead><tr><th>消息 / 文件</th><th>阅读状态</th><th>发布时间</th><th>下载链接期限</th><th>操作</th></tr></thead><tbody><tr v-for="m in state.page.items" :key="m.job_id" class="message"><td><strong class="message-title">台账文件已发布</strong><span class="proto-sub">{{m.row_count}} 行 · {{m.size_bytes}} 字节 · XLSX</span><details><summary>查看文件标识</summary><p class="meta">作业 {{m.job_id}}</p><p class="meta">SHA256 {{m.file_sha256}}</p></details></td><td><span class="proto-badge" :class="{ unread: !m.read_at }">{{m.read_at?'已读':'未读'}}</span><span v-if="m.read_at" class="proto-sub">已读于 {{displayTime(m.read_at)}}</span></td><td>{{displayTime(m.issued_at)}}</td><td><span>原链接有效至 {{displayTime(m.expires_at)}}</span><span v-if="download(m)?.renewed" class="proto-sub renewed-expiry">新链接有效至 {{displayTime(download(m)!.expiresAt)}}</span><span v-if="!download(m)" class="proto-sub expired">{{state.grants[m.job_id]?'新下载链接已过期。':'原下载链接已过期。'}}</span></td><td><div class="actions"><a v-if="download(m)" :href="download(m)!.url" referrerpolicy="no-referrer">下载台账 XLSX</a><button v-else type="button" :disabled="locked" @click="workspace.renew(m.job_id)">重新申请下载链接</button><button v-if="!m.read_at" type="button" :disabled="locked" @click="mark(m.job_id)">标记已读</button></div></td></tr></tbody></table></div>
    <button type="button" :disabled="locked||!state.page.has_more" @click="workspace.list(state.unreadOnly,true)">下一页消息</button>
   </template>
   </div>
  </template>
 </section>
</template>
<style scoped>
.export-inbox{min-width:0}.export-inbox .panel{padding:20px;margin:16px 0}.export-inbox p{line-height:1.7;overflow-wrap:anywhere}.actions,.message-heading{display:flex;gap:14px;align-items:center;flex-wrap:wrap}.message-heading{justify-content:space-between}.message-heading h2{font-size:18px;margin:0}.export-inbox .proto-panel{margin-top:12px}.export-inbox .proto-toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:0 18px}.export-inbox button,.actions a,.export-back{display:inline-flex;align-items:center;min-height:32px;padding:6px 10px;font-size:12px;border:1px solid #cbd7e5;border-radius:3px;background:#fff;color:#245a85;font-family:inherit;box-sizing:border-box;cursor:pointer}.export-inbox button:disabled{opacity:.5;cursor:not-allowed}.export-inbox .proto-tabs{display:flex;gap:24px;padding:0;border:0}.export-inbox .proto-tabs button{border:0;border-bottom:2px solid transparent;border-radius:0;min-height:48px;padding:0 4px;background:none;color:#8b96a3}.export-inbox .proto-tabs button.active{border-bottom-color:#2d79f6;color:#2d79f6}.inbox-workspace>p,.inbox-workspace>button{margin:12px 18px;font-size:12px}.message-list{display:table;width:100%;border-collapse:collapse;margin:0;padding:0}.message-list .panel{margin:0;min-width:0}.message-title{font-size:13px;font-weight:400;color:#327ff2}.message-list details{margin-top:7px;font-size:11px;color:#8b96a3}.export-inbox summary{cursor:pointer}.message-list .actions{gap:6px}.message-list .actions :is(a,button){border:0;color:#327ff2;background:none;padding:3px 0;white-space:nowrap}.message-list .proto-badge.unread{background:#edf5ff;color:#2d79f6}.message-list td:first-child{min-width:240px}.meta{font-size:12px;color:#607086;overflow-wrap:anywhere;max-width:260px}.message-list td:nth-child(4){font-size:11px}.read-state{color:#245a85}.expired{color:#80501c}.export-inbox .proto-kpis b>small{font-size:12px;color:#8b96a3;display:inline}.export-inbox .panel:has(+.message-list){min-height:200px}.export-inbox :is(button,a,input,summary):focus-visible{outline:3px solid #176bb5;outline-offset:2px}@media(max-width:760px){.export-inbox .panel{padding:14px}.export-inbox button,.actions a,.export-back{min-height:44px}.export-inbox .proto-toolbar{padding:0 12px}}
</style>
