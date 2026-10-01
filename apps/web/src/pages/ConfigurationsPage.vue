<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, ApiError, isCancelled } from '../api/client'
import type { Configuration } from '../api/types'
import { displayValue } from '../utils/display'
import { editorKind, parseProposedValue } from '../utils/configuration'
import AppTable from '../components/AppTable.vue'
import PermissionGate from '../components/PermissionGate.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import ConfigurationRevisions from '../components/ConfigurationRevisions.vue'
const rows = ref<Configuration[]>([])
const loading = ref(false)
const error = ref<unknown>(null)
const controller = new AbortController()
let loadGeneration = 0
const pendingCount = computed(() => rows.value.filter(item => item.decision_status === 'pending').length)
const draftCount = computed(() => rows.value.filter(item => item.lifecycle_status === 'DRAFT').length)
async function load() {
  const generation = ++loadGeneration
  loading.value = true; error.value = null
  try { const result = await api.request<{ items: Configuration[] }>('/configurations', { signal: controller.signal }); if (generation === loadGeneration) rows.value = result.items }
  catch (cause) { if (!isCancelled(cause) && generation === loadGeneration) { error.value = cause; rows.value = [] } }
  finally { if (generation === loadGeneration) loading.value = false }
}
onMounted(load)
onUnmounted(() => controller.abort())
const editing = ref<Configuration | null>(null)
const editingOpen = computed({ get: () => Boolean(editing.value), set: value => { if (!value) editing.value = null } })
const proposed = ref<string | boolean>('')
const validationError = ref('')
const editError = ref<unknown>(null)
const saving = ref(false)
const kind = computed(() => editing.value ? editorKind(editing.value) : 'text')
function openDraft(item: Configuration) {
  editing.value = item; editError.value = null; validationError.value = ''
  const value = item.draft_value
  proposed.value = value === null || value === undefined ? '' : typeof value === 'boolean' ? value : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value)
}
async function saveDraft() {
  if (!editing.value) return
  let value: unknown
  try { value = parseProposedValue(kind.value, proposed.value) }
  catch (cause) { validationError.value = cause instanceof Error ? cause.message : '建议值有误'; return }
  validationError.value = ''; saving.value = true; editError.value = null
  try {
    await api.request(`/configurations/${encodeURIComponent(editing.value.key)}/draft`, { method: 'POST', body: { proposed_value: value, expected_version: editing.value.version }, signal: controller.signal })
    editing.value = null; ElMessage.success('配置草稿已保存，尚未生效'); await load()
  } catch (cause) { if (!isCancelled(cause)) editError.value = cause }
  finally { saving.value = false }
}
const publishing = ref<Configuration | null>(null)
const publishOpen = computed({ get: () => Boolean(publishing.value), set: value => { if (!value) publishing.value = null } })
const publishError = ref<unknown>(null)
const checking = ref(false)
function openPublish(item: Configuration) { publishing.value = item; publishError.value = null }
async function publish() {
  if (!publishing.value) return
  checking.value = true; publishError.value = null
  try {
    await api.request(`/configurations/${encodeURIComponent(publishing.value.key)}/publish`, { method: 'POST', body: { expected_version: publishing.value.version }, signal: controller.signal })
    // This local API currently always blocks publication. If the contract changes, re-read facts.
    await load(); publishing.value = null
  } catch (cause) { if (!isCancelled(cause)) publishError.value = cause }
  finally { checking.value = false }
}
const showConflict = computed(() => editError.value instanceof ApiError && editError.value.status === 409)
const revisionsOpen = ref(false)
const revisionConfiguration = ref<Configuration | null>(null)
function openRevisions(item: Configuration) { revisionConfiguration.value = item; revisionsOpen.value = true }
</script>
<template>
  <section>
    <div class="page-heading"><div><span class="eyebrow">ADMINISTRATION / CONFIGURATION</span><h1>配置确认登记</h1><p>统一记录合同值、客户待确认项与建议草稿。测试环境草稿均未发布为运行配置。</p></div><el-button :loading="loading" @click="load">刷新登记表</el-button></div>
    <div class="scope-card"><div><span class="small-label">登记项</span><strong>{{ loading ? '—' : rows.length }} <small>项</small></strong></div><div><span class="small-label">待客户确认</span><strong>{{ loading ? '—' : pendingCount }} <small>项</small></strong></div><div><span class="small-label">草稿版本</span><strong>{{ loading ? '—' : draftCount }} <small>项</small></strong></div><div class="scope-explanation">运行配置尚未启用。待确认项保留 null，禁止回退为演示默认。</div></div>
    <el-alert class="page-note" type="warning" :closable="false" show-icon title="配置修订支持证据引用与内部审批；证据未核验，客户确认与外部下发仍待接入，内部审批通过后尚未生效。" />
    <div class="panel"><ApiErrorNotice :error="error"><el-button size="small" @click="load">重试</el-button></ApiErrorNotice><AppTable :rows="rows" :loading="loading" row-key="key" :empty-text="error ? '登记表加载失败，请重试' : '暂无配置登记项'">
      <el-table-column label="配置项" min-width="280"><template #default="{ row }"><strong class="configuration-name">{{ row.description }}</strong><span class="cell-sub mono">{{ row.key }}</span></template></el-table-column>
      <el-table-column label="作用域 / 类型" min-width="130"><template #default="{ row }">{{ row.scope }}<span class="cell-sub">{{ row.type }}{{ row.unit ? ` · ${row.unit}` : '' }}</span></template></el-table-column>
      <el-table-column label="登记 / 合同值" min-width="150"><template #default="{ row }"><span :class="{ muted: row.registered_value === null }">{{ displayValue(row.registered_value) }}</span><span class="cell-sub">{{ row.source }} · 未运行激活</span></template></el-table-column>
      <el-table-column label="确认状态" min-width="140"><template #default="{ row }"><el-tag :type="row.decision_status === 'pending' ? 'warning' : 'info'">{{ row.decision_status === 'pending' ? '待客户确认' : row.decision_status === 'contract_fixed' ? '合同有据' : row.decision_status === 'confirmed' ? '已确认' : row.decision_status }}</el-tag><span class="cell-sub">{{ row.question_ids?.join(' / ') || '—' }}</span></template></el-table-column>
      <el-table-column label="建议草稿 / 版本" min-width="180"><template #default="{ row }"><span>{{ row.draft_value === null || row.draft_value === undefined ? '尚未填写' : displayValue(row.draft_value) }}</span><span class="cell-sub">v{{ row.version }} · {{ row.lifecycle_status === 'DRAFT' ? '草稿' : row.lifecycle_status }}</span></template></el-table-column>
      <el-table-column label="操作" min-width="250" fixed="right"><template #default="{ row }"><el-button type="primary" link @click="openRevisions(row)">修订与审批</el-button><PermissionGate capability="configurations:write"><el-button type="primary" link :disabled="row.contract_value_kind === 'fixed_constraint'" @click="openDraft(row)">{{ row.contract_value_kind === 'fixed_constraint' ? '固定约束' : '编辑草稿' }}</el-button><el-button type="primary" link @click="openPublish(row)">发布检查</el-button></PermissionGate></template></el-table-column>
    </AppTable><div class="table-footer"><span>客户确认结论与生产许可按文档登记；测试环境草稿不构成正式确认。</span></div></div>
    <ConfigurationRevisions v-model="revisionsOpen" :configuration="revisionConfiguration" @changed="load" />
    <el-dialog v-model="editingOpen" title="编辑配置草稿" width="560px" class="responsive-dialog" :close-on-click-modal="!saving" :show-close="!saving" destroy-on-close>
      <template v-if="editing"><div class="config-dialog-context"><strong>{{ editing.description }}</strong><span class="mono">{{ editing.key }}</span><p>当前版本 v{{ editing.version }} · 登记值：{{ displayValue(editing.registered_value) }}</p><p>运行生效：{{ editing.effective_value === null ? '尚未启用' : displayValue(editing.effective_value) }}</p><p v-if="editing.question_ids?.length">关联确认：{{ editing.question_ids.join(' / ') }}</p></div>
        <el-alert type="info" :closable="false" title="仅保存建议值；原登记值与待确认状态保持不变。" show-icon />
        <ApiErrorNotice :error="editError" />
        <p v-if="showConflict" class="field-help">版本已变化，建议值仍保留在表单。关闭后刷新登记表，再基于新版本编辑；系统不会覆盖他人修改。</p>
        <el-form class="dialog-form" label-position="top" @submit.prevent="saveDraft"><el-form-item :label="`建议值${editing.unit ? `（${editing.unit}）` : ''}`" :error="validationError">
          <el-select v-if="kind === 'boolean'" v-model="proposed" placeholder="请选择；未选择不等于否"><el-option label="是（true）" :value="true" /><el-option label="否（false）" :value="false" /></el-select>
          <el-input v-else v-model="proposed" :type="kind === 'json' ? 'textarea' : 'text'" :rows="kind === 'json' ? 7 : 1" :placeholder="kind === 'number' ? '请输入数字，本批校验登记类型' : kind === 'json' ? '请输入 JSON，保持字段类型与结构' : '请输入建议值'" />
        </el-form-item><p v-if="editing.allowed_range !== null" class="field-help">登记范围：{{ displayValue(editing.allowed_range) }} · 范围待正式规则核验</p><p v-if="editing.notes" class="field-help">{{ editing.notes }}</p></el-form>
      </template><template #footer><el-button :disabled="saving" @click="editingOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveDraft">保存草稿</el-button></template>
    </el-dialog>
    <el-dialog v-model="publishOpen" title="配置发布条件" width="520px" class="responsive-dialog" :close-on-click-modal="!checking" :show-close="!checking" destroy-on-close>
      <template v-if="publishing"><div class="config-dialog-context"><strong>{{ publishing.description }}</strong><span class="mono">{{ publishing.key }}</span></div><el-alert type="warning" :closable="false" show-icon title="发布等待客户确认与审批。服务端将检查门禁，未满足条件时返回 CONFIG_PENDING。" /><p class="muted">关联确认：{{ publishing.question_ids?.join(' / ') || '以服务端门禁为准' }}<br>发布条件：{{ publishing.activation_gate || '生产发布流程尚未启用' }}</p><ApiErrorNotice :error="publishError" /></template>
      <template #footer><el-button :disabled="checking" @click="publishOpen = false">关闭</el-button><el-button type="primary" plain :loading="checking" @click="publish">检查发布门禁</el-button></template>
    </el-dialog>
  </section>
</template>
