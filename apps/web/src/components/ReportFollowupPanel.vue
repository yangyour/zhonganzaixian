<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { api, isCancelled } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { readReportFollowups, type FollowupPage, type FollowupQuery, type FollowupCursor } from '../utils/reportFollowups'
import type { ReadershipFilter } from '../utils/reportReadership'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{ disabled?: boolean }>()
const emit=defineEmits<{ open:[id:string] }>()
const permitted=computed(()=>session.value?.mode==='remote_test'&&!switchingTenant.value&&can('dashboard:read')&&can('inspection_quarterly_reports:read')&&can('inspection_quarterly_reports:write'))
const scope=computed(()=>JSON.stringify([session.value?.tenant.id,session.value?.user.id,session.value?.user.role,session.value?.mode,
 session.value?.permission_version,session.value?.account_version,session.value?.capabilities,contextGeneration.value,permitted.value]))
const today=new Date(),start=ref(new Date(Date.UTC(today.getUTCFullYear(),today.getUTCMonth(),1)).toISOString().slice(0,10))
const until=ref(new Date(Date.UTC(today.getUTCFullYear(),today.getUTCMonth(),today.getUTCDate()+1)).toISOString().slice(0,10))
const filter=ref<ReadershipFilter>('FOLLOWUP_DUE'),expanded=ref(false),busy=ref(false),cancelled=ref(false),error=ref<unknown>(null)
const page=ref<FollowupPage|null>(null),requested=ref<FollowupQuery|null>(null),status=ref<HTMLElement>(),retry=ref<HTMLButtonElement>()
let generation=0,controller:AbortController|null=null,alive=true
function reset(){generation++;controller?.abort();controller=null;busy.value=false;page.value=null;error.value=null;cancelled.value=false;requested.value=null}
const datesValid=computed(()=>/^\d{4}-\d{2}-\d{2}$/.test(start.value)&&/^\d{4}-\d{2}-\d{2}$/.test(until.value)
 &&Number.isFinite(Date.parse(start.value))&&Number.isFinite(Date.parse(until.value))&&start.value<until.value)
function criteria(before:FollowupCursor|null=null):FollowupQuery{return {from:start.value+'T00:00:00Z',until:until.value+'T00:00:00Z',filter:filter.value,before}}
async function load(before:FollowupCursor|null=null){
 if(!alive||!permitted.value||props.disabled||busy.value||!datesValid.value)return
 const query=criteria(before),current=++generation,key=scope.value,tenant=session.value!.tenant.id,focus=document.activeElement
 controller?.abort();controller=new AbortController();busy.value=true;cancelled.value=false;error.value=null;page.value=null;requested.value=query
 const params=new URLSearchParams({from:query.from,until:query.until,filter:query.filter})
 if(before){params.set('before',before.published_at);params.set('before_id',before.document_id)}
 try{const raw=await api.request<unknown>('/report-publications/followups?'+params.toString(),{signal:controller.signal})
  if(!alive||current!==generation||key!==scope.value||!permitted.value)return
  page.value=readReportFollowups(raw,tenant,query)
 }catch(e){if(alive&&current===generation&&!isCancelled(e))error.value=e}
 finally{if(alive&&current===generation){busy.value=false;controller=null;await nextTick();if(document.activeElement===focus)status.value?.focus()}}
}
async function stop(){generation++;controller?.abort();controller=null;busy.value=false;page.value=null;error.value=null;cancelled.value=true;await nextTick();retry.value?.focus()}
function toggle(event:Event){expanded.value=(event.target as HTMLDetailsElement).open;if(!expanded.value)reset()}
watch([scope,start,until,filter],reset,{flush:'sync'})
onUnmounted(()=>{alive=false;reset()})
</script>
<template>
 <details v-if="permitted" aria-label="跨报告未读跟进" @toggle="toggle">
  <summary>跨报告未读跟进</summary>
  <div class="actions">
   <label>冻结时间起（UTC）<input v-model="start" type="date" :disabled="busy||disabled" /></label>
   <label>冻结时间止（UTC，不含）<input v-model="until" type="date" :disabled="busy||disabled" /></label>
   <label>报告筛选<select v-model="filter" :disabled="busy||disabled"><option value="FOLLOWUP_DUE">超过7天未读</option><option value="UNREAD">有未读接收人</option><option value="ALL">全部已发布修订</option></select></label>
   <button :disabled="busy||disabled||!datesValid" @click="load()">查询跟进列表</button>
   <button v-if="busy" @click="stop">取消跟进查询</button>
  </div>
  <p v-if="!datesValid" role="alert">请选择有效且递增的冻结时间范围。</p>
  <div ref="status" tabindex="-1" role="status"><span v-if="busy">正在核对范围内全部报告…</span><span v-else-if="cancelled">查询已取消，旧结果已清除。</span><span v-else-if="page">匹配 {{page.matching_reports}} 份修订 · 未读 {{page.unread_count}} 人次 · 超过7天未读 {{page.followup_due_count}} 人次</span></div>
  <ApiErrorNotice :error="error" />
  <button v-if="error||cancelled" ref="retry" :disabled="busy||disabled||!datesValid" @click="load(requested?.before??null)">重试原跟进页</button>
  <template v-if="page">
   <div role="region" aria-label="跨报告跟进表格" tabindex="0" class="followup-table">
    <table v-if="page.items.length"><caption>已发布修订，按发布时间倒序</caption><thead><tr><th scope="col">报告</th><th scope="col">发布时间</th><th scope="col">接收人</th><th scope="col">未读</th><th scope="col">超过7天</th><th scope="col">操作</th></tr></thead>
     <tbody><tr v-for="row in page.items" :key="row.document_id"><td>{{row.label}}</td><td>{{row.published_at}}</td><td>{{row.total_recipients}}</td><td>{{row.unread_count}}</td><td>{{row.followup_due_count}}</td><td><button :disabled="disabled||busy" :aria-label="'查看阅读情况：'+row.label" @click="emit('open',row.document_id)">查看阅读情况</button></td></tr></tbody>
    </table>
   </div>
   <p v-if="!page.items.length">当前范围及筛选没有待显示的已发布修订。</p>
   <div class="actions"><button :disabled="busy||disabled||!requested?.before" @click="load()">跟进列表第一页</button><button v-if="page.next" :disabled="busy||disabled" @click="load(page.next)">跟进列表下一页</button></div>
   <small>统计时间 {{page.observed_at}}；修订分别计数。查询不会登记阅读或发送提醒。</small>
  </template>
 </details>
</template>
<style scoped>
.actions{display:flex;align-items:center;flex-wrap:wrap;gap:8px}.followup-table{overflow:auto;max-height:540px}table{width:100%;text-align:left;border-collapse:collapse}th,td{padding:8px;border-bottom:1px solid var(--mop-line-soft,#edf1f6)}button,input,select{min-height:32px}label{display:flex;gap:4px;align-items:center}summary{cursor:pointer}
</style>
