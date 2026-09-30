<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api, isCancelled } from '../api/client'
import type { AuditEvent } from '../api/types'
import { displayTime } from '../utils/display'
import AppTable from '../components/AppTable.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const rows = ref<AuditEvent[]>([])
const loading = ref(false)
const error = ref<unknown>(null)
const controller = new AbortController()
let generation = 0
async function load() {
  const request = ++generation
  loading.value = true; error.value = null
  try { const result = await api.request<{ items: AuditEvent[] }>('/audit', { signal: controller.signal }); if (request === generation) rows.value = result.items }
  catch (cause) { if (!isCancelled(cause) && request === generation) { error.value = cause; rows.value = [] } }
  finally { if (request === generation) loading.value = false }
}
onMounted(load)
onUnmounted(() => controller.abort())
</script>
<template>
  <section>
    <div class="page-heading"><div><span class="eyebrow">ADMINISTRATION / AUDIT</span><h1>审计日志</h1><p>查看服务端记录的操作、结果与拒绝原因，通过 trace_id 关联请求。</p></div><el-button :loading="loading" @click="load">刷新日志</el-button></div>
    <el-alert class="page-note" type="info" :closable="false" show-icon title="测试环境审计记录保存于项目独立 PostgreSQL schema，仅展示当前租户授权记录。生产留存策略仍待确认。" />
    <div class="panel"><ApiErrorNotice :error="error"><el-button size="small" @click="load">重试</el-button></ApiErrorNotice><AppTable :rows="rows" :loading="loading" :empty-text="error ? '日志加载失败，请重试' : '当前租户暂没有审计记录'">
      <el-table-column label="时间（北京）" min-width="180"><template #default="{ row }">{{ displayTime(row.occurred_at) }}</template></el-table-column>
      <el-table-column prop="actor_name" label="操作者" min-width="130" />
      <el-table-column prop="action" label="操作" min-width="180" />
      <el-table-column label="对象" min-width="190"><template #default="{ row }">{{ row.resource_type }}<span class="cell-sub mono">{{ row.resource_id || '—' }}</span></template></el-table-column>
      <el-table-column label="结果 / 原因" min-width="220"><template #default="{ row }"><el-tag :type="['SUCCESS', 'SUCCEEDED', 'success'].includes(row.result) ? 'success' : 'warning'">{{ ['SUCCESS', 'SUCCEEDED', 'success'].includes(row.result) ? '完成' : row.result }}</el-tag><span class="cell-sub">{{ row.reason || '—' }}</span></template></el-table-column>
      <el-table-column label="trace_id" min-width="250"><template #default="{ row }"><span class="mono trace-value">{{ row.trace_id }}</span></template></el-table-column>
    </AppTable><div class="table-footer"><span>本页 {{ rows.length }} 条 · 当前租户上下文 · 不展示设备秘密或凭据</span></div></div>
  </section>
</template>
