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
const labels = { UNKNOWN: '日期未配置', PAST_DATE: '记录日期已过', DUE_TODAY: '记录日期为今天', WITHIN_30_DAYS: '记录日期在30天内', FUTURE_DATE: '记录日期在30天之后' }
const windows = [{ key: 'within_7_days', label: '未来7天内' }, { key: 'within_30_days', label: '未来30天内' }, { key: 'within_90_days', label: '未来90天内' }, { key: 'in_reference_quarter', label: '参考日所在季度' }] as const
const fields = [{ key: 'warranty_expiry', label: '维保到期日期' }, { key: 'overdue_service_date', label: '超期服役日期' }] as const
</script>
<template>
  <section v-if="permitted" class="date-assessment detail-section">
    <h3>维保与超期日期对照</h3><p>只读对照已登记日期，不证明真实维保覆盖。空日期保持未知；起始日、供应商和实际送达尚未提供。</p>
    <el-button :loading="state.busy" @click="reader.refresh()">{{ state.current ? '重新读取日期' : '读取日期对照' }}</el-button>
    <ApiErrorNotice :error="state.error" />
    <el-skeleton v-if="state.busy" :rows="3" animated />
    <template v-if="state.current">
      <p>服务端参考日：{{ state.current.as_of_date }}（{{ state.current.timezone }}）<br />读取时刻：{{ displayTime(state.current.observed_at) }}<br />资产版本 v{{ state.current.asset_version }} · 资料版本 {{ state.current.metadata_version ?? '未提供' }}</p>
      <p>结果仅截至上述读取时刻。日期或资料改变后请重新读取；已登记资料仍为自述，未核验维保合同。</p>
      <article v-for="field in fields" :key="field.key" class="date-card" :class="{ 'past-date': state.current[field.key].position === 'PAST_DATE' }">
        <h4>{{ field.label }}</h4><strong>{{ state.current[field.key].recorded_date ?? '未配置（null）' }}</strong><p>{{ labels[state.current[field.key].position] }}<span v-if="state.current[field.key].days_until !== null"> · 与参考日相差 {{ state.current[field.key].days_until }} 个日历日</span></p>
        <dl><template v-for="window in windows" :key="window.key"><dt>{{ window.label }}</dt><dd>{{ state.current[field.key][window.key] === null ? '未知' : state.current[field.key][window.key] ? '是' : '否' }}</dd></template></dl>
      </article>
      <el-alert title="本次只读，不生成P4隐患、不发送通知、不修改资产" type="info" :closable="false">尚未查询既有隐患或通知送达记录，不能把日期对照当成提醒已送达。</el-alert>
    </template>
    <p v-else-if="!state.busy && !state.error">尚未读取；不从浏览器当前日期自行推断过期。</p>
  </section>
</template>
<style scoped>
.date-assessment{overflow-wrap:anywhere}.date-assessment p{color:#536276;line-height:1.6}.date-card{border:1px solid #dbe3ed;border-radius:10px;padding:14px;margin:14px 0}.date-card h4{margin:0 0 8px}.past-date{border-color:#ce5858}.past-date strong{color:#a52b2b}dl{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px}dd{margin:0}dt{color:#536276}@media(max-width:700px){.date-card{padding:10px}.date-assessment :deep(.el-button){min-height:44px}}
</style>
