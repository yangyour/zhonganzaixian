<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch, type DeepReadonly } from 'vue'
import type { AssetImportSource } from '../api/assetImportTypes'
import { can } from '../stores/session'
import { CMDB_IMPORT_COLUMNS } from '../utils/cmdbImportPreflight'
import { useCmdbImportExecution } from '../utils/cmdbImportExecution'
import ApiErrorNotice from './ApiErrorNotice.vue'

const props = defineProps<{ source: DeepReadonly<AssetImportSource>; stagingBusy?: boolean }>()
const emit = defineEmits<{ pending: [value: boolean]; completed: [value: boolean]; imported: []; navigate: [] }>()
const workflow = useCmdbImportExecution(() => props.source)
const { receipt, attempt, error, storageError, busy, lookup, permitted, supported, unresolved, canSubmit } = workflow
const acknowledged = ref(false), summary = ref<HTMLElement>(), retryButton = ref<{ $el: HTMLButtonElement }>()
const page = ref(0)
const historical = computed(() => receipt.value && (receipt.value.source_version !== props.source.version || receipt.value.parse_hash !== props.source.parsed_artifact?.parse_hash))
watch(unresolved, value => emit('pending', value), { immediate: true, flush: 'sync' })
watch(receipt, value => { emit('completed', !!value); if (value) emit('imported'); page.value = 0 })
watch(() => [props.source.id, props.source.version], () => { acknowledged.value = false; page.value = 0 })
async function run(action: () => Promise<unknown>) {
  const active = document.activeElement
  await action(); await nextTick()
  if (permitted.value && (document.activeElement === active || document.activeElement === document.body)) summary.value?.focus()
}
async function stopWaiting() { workflow.stopWaiting(); await nextTick(); retryButton.value?.$el.focus() }
function navigateToAsset(event: MouseEvent) {
  if (event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) emit('navigate')
}
function destination(position: number) {
  if (position <= 14) return position === 9 ? '基本资料中的来源业务说明；不建立业务系统关联' : '资产或基本资料；按已发布规则核验'
  if (position === 16) return '按已发布子类型映射转换；不自动套用监控模板'
  if (position === 17 || position === 18) return '维保资料历史；不代表供应商核验'
  return '须由已发布规则明确保留在原始来源；尚不建立关联或生效'
}
onUnmounted(() => { workflow.dispose(); emit('pending', false) })
</script>

<template>
  <section class="asset-import-section cmdb-execution" aria-label="33列来源资产导入执行" :aria-busy="Boolean(busy)">
    <h3>5. 导入资产草稿与回执</h3>
    <p v-if="!permitted" class="field-help">当前角色或来源归属不允许执行导入；需具有资产写权限的内部成员操作本人来源。</p>
    <template v-else>
      <p v-if="!supported && !receipt" class="field-help">请先完成客户33列来源解析。旧15列映射不会自动转换为33列导入。</p>
      <p v-else class="field-help">导入执行时重新核对当前租户的已发布类型映射、站点与负责人引用、地址策略、配额和重复资产。配置未齐备会拒绝整批写入，不能根据字典预检直接判定可入库。</p>
      <details v-if="supported"><summary>核对33列的保存与生效边界</summary>
        <div class="cmdb-execution-scroll" tabindex="0" role="region" aria-label="33列字段处置">
          <table><caption>以服务端当前租户已发布配置为准</caption><thead><tr><th scope="col">列</th><th scope="col">字段</th><th scope="col">保存或生效范围</th></tr></thead>
            <tbody><tr v-for="column in CMDB_IMPORT_COLUMNS" :key="column.position"><td>{{ column.position }}</td><td>{{ column.label }}</td><td>{{ destination(column.position) }}</td></tr></tbody>
          </table>
        </div>
      </details>
      <div class="asset-import-actions">
        <el-button :disabled="Boolean(busy || stagingBusy)" @click="run(workflow.refresh)">读取导入回执</el-button>
        <el-button v-if="busy" @click="stopWaiting">停止等待{{ busy === 'write' ? '提交响应' : '回执读取' }}</el-button>
      </div>
      <p ref="summary" role="status" tabindex="-1">
        <template v-if="busy === 'write'">正在提交本次来源。停止等待不会撤销服务端事务。</template>
        <template v-else-if="busy === 'read'">正在读取当前创建者的已提交回执…</template>
        <template v-else-if="receipt">已确认导入 {{ receipt.imported_count }} 条资产草稿。</template>
        <template v-else-if="unresolved">原请求结果待确认。请读取回执或使用原幂等键重试，不要重新创建请求。</template>
        <template v-else-if="attempt?.state === 'REJECTED'">本次请求已被明确拒绝。修正来源或配置后，重新读取并准备新请求。</template>
        <template v-else-if="lookup === 'NO_COMMITTED_RECEIPT'">本次读取尚未发现已提交回执；这不证明其他正在处理的请求失败。</template>
        <template v-else>尚未取得可核验的导入回执。</template>
      </p>
      <ApiErrorNotice :error="error" />
      <ApiErrorNotice :error="storageError" />
      <template v-if="unresolved">
        <p class="field-help">当前标签页仅保存原请求标识、来源版本/摘要及行号用于刷新恢复，不保存单元格或凭据。租户/权限变化会清除当前展示，服务端仍重新授权。</p>
        <p class="mono">原来源 {{ attempt?.sourceId }} · 版本 {{ attempt?.body.expected_version }}</p>
        <el-button ref="retryButton" type="primary" :disabled="Boolean(busy || stagingBusy)" @click="run(workflow.retry)">使用原请求重试</el-button>
      </template>
      <template v-else-if="supported && !receipt">
        <el-button v-if="attempt?.state === 'REJECTED'" :disabled="Boolean(busy || stagingBusy)" @click="run(workflow.prepareAgain)">重新读取并准备请求</el-button>
        <el-checkbox v-else v-model="acknowledged" :disabled="!canSubmit || stagingBusy">已核对来源与33列处置；仅创建草稿，不启用监控或通知</el-checkbox>
        <el-button v-if="!attempt" type="primary" :disabled="!canSubmit || !acknowledged || stagingBusy" @click="run(workflow.submit)">按当前已发布规则导入草稿</el-button>
      </template>
      <template v-if="receipt">
        <p>草稿状态：未纳管、监控未知；扩展字段明确保留在不可变原始来源。生产批准尚未核验。</p>
        <p v-if="historical" class="field-help">此回执属于来源的历史解析版本，当前来源已变化；不会将旧成功显示为当前版本再次导入。</p>
        <p class="mono">来源版本 v{{ receipt.source_version }} · 产物 {{ receipt.artifact_id }}<br>解析摘要 {{ receipt.parse_hash }}</p>
        <p>设备配额：本次 {{ receipt.quota.before }} → {{ receipt.quota.after }} / {{ receipt.quota.limit }}；这是提交时的事实，不代表当前剩余额度。</p>
        <details><summary>查看本次使用的配置发布版本</summary><ul><li v-for="(release, key) in receipt.configuration_releases" :key="key">{{ key }}：v{{ release.version }} · {{ release.id }}</li></ul></details>
        <div class="cmdb-execution-scroll" tabindex="0" role="region" aria-label="逐行资产导入回执">
          <table><caption>当前第 {{ page + 1 }} 页，全部 {{ receipt.imported_count }} 条成功回执</caption><thead><tr><th scope="col">记录 / 来源行</th><th scope="col">资产</th><th scope="col">版本</th></tr></thead>
            <tbody><tr v-for="row in receipt.assets.slice(page * 25, (page + 1) * 25)" :key="row.asset_id"><td>{{ row.record }} / {{ row.source_row }}</td><td><router-link v-if="can('assets:read')" :to="{ path: '/assets', query: { asset_id: row.asset_id } }" @click="navigateToAsset">查看资产 {{ row.asset_id }}</router-link><span v-else>{{ row.asset_id }}</span></td><td>{{ row.asset_version }}</td></tr></tbody>
          </table>
        </div>
        <nav v-if="receipt.assets.length > 25" aria-label="导入回执分页"><el-button :disabled="page === 0" @click="page--">上一页</el-button><el-button :disabled="(page + 1) * 25 >= receipt.assets.length" @click="page++">下一页</el-button></nav>
      </template>
    </template>
  </section>
</template>

<style scoped>
.cmdb-execution{min-width:0;overflow-wrap:anywhere}.cmdb-execution-scroll{max-height:360px;overflow:auto;margin:12px 0}.cmdb-execution table{border-collapse:collapse;width:100%;text-align:left}.cmdb-execution th,.cmdb-execution td{padding:8px 10px;border-bottom:1px solid var(--el-border-color);vertical-align:top}.cmdb-execution caption{text-align:left;padding:6px 0}.cmdb-execution summary{cursor:pointer;padding:8px 0}.cmdb-execution [tabindex]:focus,.cmdb-execution summary:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:3px}.cmdb-execution nav{display:flex;gap:12px}
</style>
