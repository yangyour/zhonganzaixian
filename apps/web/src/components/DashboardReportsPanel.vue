<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { api, isCancelled } from '../api/client'
import type { TicketPage } from '../api/types'
import { session, can, switchingTenant, contextGeneration } from '../stores/session'
import { readTicketPage } from '../utils/ticketReader'
import { displayTime } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const page = shallowRef<TicketPage | null>(null), busy = ref(false), error = ref<unknown>(null)
const cursor = ref(''), number = ref(1)
const permitted = computed(() => session.value?.mode === 'remote_test' && can('tickets:read') && !switchingTenant.value)
const own = computed(() => ['viewer','tadmin'].includes(session.value?.user.role ?? ''))
let epoch = 0, controller: AbortController | null = null, alive = true, attempt = { cursor:'', number:1 }
async function load(target = cursor.value, targetNumber = number.value) {
  if (!alive || !permitted.value || !session.value || busy.value) return
  const current = session.value, scope = { tenantId:current.tenant.id, actorId:current.user.id, role:current.user.role, permissionVersion:current.permission_version }
  const turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort
  attempt = { cursor:target, number:targetNumber }; page.value = null; error.value = null; busy.value = true
  try {
    const params = new URLSearchParams({ page_size:'10' }); if (target) params.set('cursor',target)
    const result = await api.request<unknown>('/tickets?'+params, { method:'GET',signal:abort.signal })
    if (!alive || turn !== epoch || abort.signal.aborted) return
    page.value = readTicketPage(result,scope,10); cursor.value = target; number.value = targetNumber
  } catch (cause) { if (alive && turn === epoch && !isCancelled(cause)) error.value = cause }
  finally { if (alive && turn === epoch) { busy.value = false; controller = null } }
}
function retry() { void load(attempt.cursor,attempt.number) }
function reset() { ++epoch;controller?.abort();controller=null;page.value=null;error.value=null;busy.value=false;cursor.value='';number.value=1;attempt={cursor:'',number:1} }
watch(() => [contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,permitted.value], () => { reset();if(permitted.value)void load() },{immediate:true,flush:'sync'})
onUnmounted(()=>{alive=false;reset()})
</script>
<template>
 <section v-if="permitted" class="dashboard-reports" :aria-busy="busy" aria-label="实际报障登记">
  <header><h2>{{ own ? '我的报障登记' : '报障登记' }}</h2><div class="report-actions"><button type="button" :disabled="busy" @click="load()">{{ busy ? '刷新中…' : '刷新登记' }}</button><router-link to="/tickets">报障工作区</router-link></div></header>
  <ApiErrorNotice :error="error" :retrying="busy" @retry="retry" />
  <p v-if="!page" role="status" class="report-state">{{ busy ? '正在加载报障登记…' : error ? '登记读取失败，未显示旧列表。' : '暂未加载报障登记。' }}</p>
  <template v-else>
   <p v-if="!page.items.length" class="report-state">当前授权范围没有报障登记。</p>
   <ul v-else class="report-list" aria-label="报障登记列表"><li v-for="row in page.items" :key="row.id"><div class="report-main"><router-link class="report-title" :to="{ path:'/tickets',query:{ticket_id:row.id} }" :aria-label="'查看登记'+row.receipt_no">{{ row.title }}</router-link><p><span class="receipt-no">{{ row.receipt_no }}</span> · {{ row.category }} · {{ row.asset_name ?? '未关联资产' }}</p></div><div class="report-side"><span class="urgency-tag" :class="{ urgent: row.urgency === 'urgent' }">{{ row.urgency === 'urgent' ? '紧急' : '一般' }}</span><time class="report-time" :datetime="row.created_at">{{ displayTime(row.created_at) }}</time></div></li></ul>
   <div class="report-pagination"><span>第 {{ number }} 页 · 本页 {{ page.items.length }} 条{{ page.has_more ? ' · 还有更多记录' : '' }}</span><button type="button" :disabled="busy || !cursor" @click="load('',1)">返回第一页</button><button type="button" :disabled="busy || !page.has_more" @click="load(page!.next_cursor!,number+1)">下一页登记</button></div>
  </template>
 </section>
</template>
<style scoped>
.dashboard-reports{padding:16px 18px;background:#fff;border:1px solid #e3e9f2;border-radius:10px;min-width:0;color:#24374a}
.dashboard-reports header,.report-actions,.report-pagination{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}
.dashboard-reports h2{font-size:15px;font-weight:600;margin:0;color:#0f172a}
.dashboard-reports button,.report-actions a{min-height:32px;display:inline-flex;align-items:center;box-sizing:border-box;padding:0 12px;border:1px solid #d5deea;border-radius:6px;background:#fff;color:#1d4ed8;text-decoration:none;cursor:pointer;font:inherit;font-size:13px}
.dashboard-reports button:hover:not(:disabled),.report-actions a:hover{background:#eff6ff;border-color:#93c5fd}
.dashboard-reports button:disabled{opacity:.5;cursor:default}
.report-state{margin:12px 0 0;padding:20px;border-radius:8px;background:#f8fafc;color:#475569;font-size:13px;text-align:center}
.report-list{list-style:none;margin:8px 0 0;padding:0}
.report-list li{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0;border-top:1px solid #eef2f7}
.report-list li:first-child{border-top:0}
.report-main{min-width:0}
.report-title{color:#0f172a;font-size:14px;font-weight:600;text-decoration:none;overflow-wrap:anywhere}
.report-title:hover{color:#1d4ed8;text-decoration:underline}
.report-main p{margin:2px 0 0;font-size:12px;color:#64748b;overflow-wrap:anywhere}
.report-side{display:flex;flex-direction:column;align-items:flex-end;gap:4px;flex:none}
.receipt-no,.report-time,.report-pagination>span{font-variant-numeric:tabular-nums}
.report-time{font-size:12px;color:#64748b;white-space:nowrap}
.urgency-tag{display:inline-flex;align-items:center;height:20px;padding:0 8px;border-radius:999px;font-size:12px;background:#f1f5f9;color:#475569}
.urgency-tag.urgent{background:#ffe4e6;color:#be123c;font-weight:600}
.report-pagination{margin-top:10px;padding-top:10px;border-top:1px solid #eef2f7;font-size:12px;color:#64748b}
.report-pagination>span{margin-right:auto}
.dashboard-reports :focus-visible{outline:2px solid #2563eb;outline-offset:2px}
@media(max-width:760px){.dashboard-reports{padding:14px}.dashboard-reports button,.report-actions a{min-height:44px}.report-pagination>span{width:100%}.report-list li{align-items:flex-start}.report-title{display:inline-block;min-height:24px}}
</style>
