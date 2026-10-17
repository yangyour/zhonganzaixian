<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import type { Asset } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createAssetDateReader, type AssetDateState } from '../utils/assetDateReader'
import { displayTime } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props = defineProps<{ asset: Asset }>()
const state = shallowRef<AssetDateState>({ current: null, busy: false, error: null })
const reader = createAssetDateReader(api, value => { state.value = value })
const permitted = computed(() => can('asset_dates:read') && !switchingTenant.value && props.asset.tenant_id === session.value?.tenant.id)
watch(() => [props.asset.id, props.asset.version, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, contextGeneration.value, permitted.value], () => {
  const s = session.value; reader.reset(permitted.value && s ? { assetId: props.asset.id, tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, minimumAssetVersion: props.asset.version } : null)
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => reader.dispose())
const labels = { UNKNOWN: '未登记', PAST_DATE: '已到期', DUE_TODAY: '今日到期', WITHIN_30_DAYS: '30天内到期', FUTURE_DATE: '30天以上' }
const windows = [{ key: 'within_7_days', label: '未来7天内' }, { key: 'within_30_days', label: '未来30天内' }, { key: 'within_90_days', label: '未来90天内' }, { key: 'in_reference_quarter', label: '参考日所在季度' }] as const
const fields = [{ key: 'warranty_expiry', label: '维保到期日期' }, { key: 'overdue_service_date', label: '超期服役日期' }] as const
</script>
<template>
  <section v-if="permitted" class="date-assessment detail-section">
    <h3>维保与超期日期核算</h3><p>按指定基准日期核算到期状态，仅核对已登记台账日期。未填写的日期保持“未登记”状态；如需维护维保起始日或服务商，请点击上方“维护维保日期与供应商”。</p>
    <el-button :loading="state.busy" @click="reader.refresh()">{{ state.current ? '重新核算日期' : '执行到期核算' }}</el-button>
    <ApiErrorNotice :error="state.error" />
    <el-skeleton v-if="state.busy" :rows="3" animated />
    <template v-if="state.current">
      <p>核算基准日：{{ state.current.as_of_date }}（{{ state.current.timezone }}）<br />核算时间：{{ displayTime(state.current.observed_at) }}<br />资产版本 v{{ state.current.asset_version }} · 资料版本 {{ state.current.metadata_version ?? '未提供' }}</p>
      <p>核算结果基于上述时刻的已登记台账日期；若后续修改了维保或服役日期，请重新执行核算。</p>
      <article v-for="field in fields" :key="field.key" class="date-card" :class="{ 'past-date': state.current[field.key].position === 'PAST_DATE' }">
        <h4>{{ field.label }}</h4><strong>{{ state.current[field.key].recorded_date ?? '未登记' }}</strong><p>{{ labels[state.current[field.key].position] }}<span v-if="state.current[field.key].days_until !== null"> · 距基准日 {{ state.current[field.key].days_until }} 个日历日</span></p>
        <dl><template v-for="window in windows" :key="window.key"><dt>{{ window.label }}</dt><dd>{{ state.current[field.key][window.key] === null ? '待定' : state.current[field.key][window.key] ? '是' : '否' }}</dd></template></dl>
      </article>
      <el-alert title="仅执行查询核算，不变更台账记录，不触发工单或通知" type="info" :closable="false">到期提醒通知与隐患工单需按独立流程触发。</el-alert>
    </template>
    <p v-else-if="!state.busy && !state.error">点击“执行到期核算”获取服务端基准日对照结果。</p>
  </section>
</template>
<style scoped>
.date-assessment{overflow-wrap:anywhere}.date-assessment p{color:#536276;line-height:1.6}.date-card{border:1px solid #dbe3ed;border-radius:10px;padding:14px;margin:14px 0}.date-card h4{margin:0 0 8px}.past-date{border-color:#ce5858}.past-date strong{color:#a52b2b}dl{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px}dd{margin:0}dt{color:#536276}@media(max-width:700px){.date-card{padding:10px}.date-assessment :deep(.el-button){min-height:44px}}
</style>
