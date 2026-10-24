<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { readReportReadership, type ReadershipFilter, type ReportReadership } from '../utils/reportReadership'
import ApiErrorNotice from './ApiErrorNotice.vue'

const props = defineProps<{ id: string; hash: string; version: number }>()
const allowed = computed(() => session.value?.mode === 'remote_test' && can('dashboard:read')
  && can('inspection_quarterly_reports:write') && !switchingTenant.value)
const scope = computed(() => JSON.stringify([session.value?.tenant.id, session.value?.user.id,
  session.value?.permission_version, session.value?.account_version, contextGeneration.value, allowed.value,
  props.id, props.hash, props.version]))
const filter = ref<ReadershipFilter>('ALL'), page = ref<ReportReadership | null>(null)
const busy = ref(false), error = ref<unknown>(null), after = ref<string | null>(null)
let epoch = 0, controller: AbortController | null = null

async function load(cursor: string | null = null) {
  if (!allowed.value || busy.value) return
  const turn = ++epoch, current = scope.value, abort = new AbortController()
  const selectedFilter = filter.value
  const expected = { tenant: session.value!.tenant.id, id: props.id, hash: props.hash,
    version: props.version, filter: selectedFilter, after: cursor }
  controller = abort; busy.value = true; error.value = null; page.value = null; after.value = cursor
  const query = new URLSearchParams({ filter: selectedFilter })
  if (cursor) query.set('after', cursor)
  try {
    const raw = await api.request<unknown>(`/report-publications/${props.id}/readership?${query}`, { signal: abort.signal })
    if (turn === epoch && current === scope.value && !abort.signal.aborted) page.value = readReportReadership(raw, expected)
  } catch (failure) {
    if (turn === epoch && current === scope.value) error.value = failure
  } finally {
    if (turn === epoch) { busy.value = false; controller = null }
  }
}
function stop() { controller?.abort() }
watch([scope, filter], () => {
  epoch++; controller?.abort(); controller = null; busy.value = false; page.value = null; error.value = null; after.value = null
  if (allowed.value) void load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { epoch++; controller?.abort() })
</script>

<template>
  <section v-if="allowed" aria-label="报告阅读看板" class="readership-panel">
    <div class="actions">
      <h3>阅读情况</h3>
      <label>接收人筛选
        <select v-model="filter" :disabled="busy">
          <option value="ALL">全部接收人</option>
          <option value="UNREAD">未读</option>
          <option value="FOLLOWUP_DUE">超过 7 天未读</option>
        </select>
      </label>
      <button :disabled="busy" @click="load()">刷新阅读情况</button>
      <button v-if="busy" @click="stop">停止读取阅读情况</button>
    </div>
    <ApiErrorNotice :error="error" />
    <button v-if="error" :disabled="busy" @click="load(after)">重试当前阅读页</button>
    <p v-if="busy" role="status">正在读取接收人阅读记录…</p>
    <template v-if="page">
      <p role="status">接收人 {{ page.total_recipients }} · 已读 {{ page.read_count }} · 未读 {{ page.unread_count }} · 超过 7 天未读 {{ page.followup_due_count }}</p>
      <p>统计时间：{{ page.observed_at }}；首次阅读仅表示接收人登记了阅读，不代表送达或验收。</p>
      <div class="readership-table" role="region" aria-label="接收人阅读记录表格" tabindex="0">
        <table v-if="page.items.length">
          <caption>当前修订接收人阅读记录</caption>
          <thead><tr><th scope="col">接收人</th><th scope="col">首次阅读时间</th><th scope="col">跟进状态</th></tr></thead>
          <tbody><tr v-for="reader in page.items" :key="reader.customer_id">
            <td>{{ reader.display_name || reader.customer_id }}<small v-if="reader.display_name">{{ reader.customer_id }}</small></td>
            <td>{{ reader.first_read_at || '未登记阅读' }}</td>
            <td>{{ reader.followup_due ? '超过 7 天未读，待人工跟进' : reader.first_read_at ? '已登记阅读' : '未读' }}</td>
          </tr></tbody>
        </table>
      </div>
      <p v-if="!page.items.length">当前筛选页没有接收人记录。</p>
      <div class="actions"><button :disabled="busy || after === null" @click="load()">阅读记录第一页</button>
        <button v-if="page.next_after" :disabled="busy" @click="load(page.next_after)">阅读记录下一页</button></div>
      <p>未读跟进供人工查看，刷新或筛选不会发送提醒。</p>
    </template>
  </section>
</template>

<style scoped>
.readership-panel { margin-top: 20px; padding-top: 12px; border-top: 1px solid var(--mop-line, #e2e8f0); }
.actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.actions h3 { margin-right: auto; }
.readership-table { overflow: auto; max-height: 540px; border: 1px solid var(--mop-line, #e2e8f0); border-radius: 6px; }
table { width: 100%; border-collapse: collapse; text-align: left; }
caption { padding: 8px 12px; text-align: left; background: var(--mop-sunken, #f7f9fc); }
th, td { padding: 10px 12px; border-bottom: 1px solid var(--mop-line-soft, #edf1f6); }
th { position: sticky; top: 0; background: var(--mop-sunken, #f7f9fc); }
td { vertical-align: top; }
small { display: block; overflow-wrap: anywhere; }
select, button { min-height: 32px; }
</style>
