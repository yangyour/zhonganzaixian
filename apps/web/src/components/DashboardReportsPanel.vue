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
  <header><div><h2>{{ own ? '我的报障登记' : '报障登记' }}</h2><p>{{ own ? '仅本人记录' : '当前租户授权记录' }} · 按登记时间正序，每页 10 条</p></div><div class="report-actions"><button type="button" :disabled="busy" @click="load()">{{ busy ? '刷新中…' : '刷新登记' }}</button><router-link to="/tickets">进入报障工作区</router-link></div></header>
  <ApiErrorNotice :error="error" :retrying="busy" @retry="retry" />
  <p v-if="!page" role="status">{{ busy ? '正在加载报障登记…' : error ? '登记读取失败，未显示旧列表。' : '暂未加载报障登记。' }}</p>
  <template v-else>
   <div class="report-table-wrap" tabindex="0" role="region" aria-label="报障登记列表"><table><thead><tr><th>登记号 / 标题</th><th>分类</th><th>关联资产</th><th>紧急程度</th><th>登记时间</th><th>详情</th></tr></thead><tbody><tr v-for="row in page.items" :key="row.id"><td><strong class="receipt-no">{{ row.receipt_no }}</strong><span>{{ row.title }}</span></td><td>{{ row.category }}</td><td>{{ row.asset_name ?? '未关联资产' }}</td><td><span class="urgency-tag" :class="{ urgent: row.urgency === 'urgent' }">{{ row.urgency === 'urgent' ? '紧急（用户提报）' : '一般' }}</span></td><td class="report-time">{{ displayTime(row.created_at) }}</td><td><router-link :to="{ path:'/tickets',query:{ticket_id:row.id} }" :aria-label="'查看登记'+row.receipt_no">查看</router-link></td></tr></tbody></table></div>
   <p v-if="!page.items.length">当前授权范围没有报障登记。</p>
   <div class="report-pagination"><span>第 {{ number }} 页 · 本页 {{ page.items.length }} 条{{ page.has_more ? ' · 还有更多记录' : '' }}</span><button type="button" :disabled="busy || !cursor" @click="load('',1)">返回第一页</button><button type="button" :disabled="busy || !page.has_more" @click="load(page!.next_cursor!,number+1)">下一页登记</button></div>
  </template>
  <p class="report-boundary">注：此处展示已提交的报障登记，派单流转与 SLA 计时以正式工单流程为准，不把登记数算作待处理工单数。周期报表导出待统计周期、季度口径与模板版式（Q02 / Q04 / Q09）确认后开放。</p>
 </section>
</template>
<style scoped>
.dashboard-reports{margin:16px 0;padding:20px;background:white;border:1px solid #e2e9f1;border-radius:8px;min-width:0;box-shadow:0 1px 2px rgba(15,23,42,.02)}.dashboard-reports header,.report-actions,.report-pagination{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.dashboard-reports h2{font-size:17px;margin:0;color:#1e293b}.dashboard-reports p{font-size:12px;color:#65768c;line-height:1.7}.dashboard-reports button,.dashboard-reports a{min-height:36px;display:inline-flex;align-items:center;padding:6px 12px;border:1px solid #cfdaea;border-radius:5px;background:white;color:#2262ac;text-decoration:none;cursor:pointer;font-size:12px;transition:background-color .15s ease,border-color .15s ease,color .15s ease}.dashboard-reports button:hover:not(:disabled),.dashboard-reports a:hover{background:#f0f7ff;border-color:#93c5fd;color:#1d4ed8}.dashboard-reports button:disabled{opacity:.5;cursor:default}.report-table-wrap{overflow:auto;border:1px solid #e5ebf3;border-radius:6px}.report-table-wrap table{width:100%;min-width:760px;border-collapse:collapse;font-size:13px;text-align:left;table-layout:fixed}.report-table-wrap th{background:#f6f8fc;color:#334155;font-weight:600}.report-table-wrap tbody tr{transition:background-color .15s ease}.report-table-wrap tbody tr:hover{background:#f8fbff}.report-table-wrap td,.report-table-wrap th{padding:12px;border-bottom:1px solid #e9eef5;overflow-wrap:anywhere}.report-table-wrap th:first-child{width:28%}.report-table-wrap th:last-child{width:76px}.report-table-wrap td span:not(.urgency-tag){display:block;margin-top:6px;color:#63758d}.receipt-no,.report-time,.report-pagination>span{font-variant-numeric:tabular-nums}.urgency-tag{display:inline-block;padding:2px 8px;border-radius:4px;font-size:12px;background:#f1f5f9;color:#475569;border:1px solid #e2e8f0}.urgency-tag.urgent{background:#fff1f2;color:#be123c;border-color:#fecdd3;font-weight:500}.report-pagination{margin:12px 0;font-size:12px}.report-pagination>span{margin-right:auto}.report-boundary{margin-bottom:0}.dashboard-reports :focus-visible{outline:2px solid #2674cf;outline-offset:2px}@media(max-width:760px){.dashboard-reports{padding:14px}.dashboard-reports header{align-items:stretch}.dashboard-reports button,.dashboard-reports a{min-height:44px}.report-actions{justify-content:flex-start}.report-pagination>span{width:100%}}
</style>
