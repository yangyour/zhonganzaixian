<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, useId, watch } from 'vue'
import { api } from '../api/client'
import type { Asset } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createAssetDateReader, type AssetDateState } from '../utils/assetDateReader'
import { displayTime } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props = defineProps<{ asset: Asset }>()
const state = shallowRef<AssetDateState>({ current: null, busy: false, error: null, attempted: false, cancelled: false })
const reader = createAssetDateReader(api, value => { state.value = value })
const permitted = computed(() => can('asset_dates:read') && !switchingTenant.value && props.asset.tenant_id === session.value?.tenant.id)
const headingId = useId(), resultId = useId(), heading = ref<HTMLElement>(), result = ref<HTMLElement>(), failure = ref<HTMLElement>()
const signature = computed(() => JSON.stringify([props.asset.id, props.asset.tenant_id, props.asset.version, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, session.value?.account_version, session.value?.user.role, session.value?.mode, session.value?.capabilities, contextGeneration.value, switchingTenant.value, permitted.value]))
let focusEpoch = 0, disposed = false
watch(signature, () => {
  focusEpoch++; const s = session.value; reader.reset(permitted.value && s ? { assetId: props.asset.id, tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version, minimumAssetVersion: props.asset.version } : null)
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; focusEpoch++; reader.dispose() })
async function read(action: () => Promise<boolean>) {
  if (state.value.busy) return
  const mine = ++focusEpoch, owner = signature.value, active = document.activeElement
  const ok = await action(); await nextTick()
  if (disposed || mine !== focusEpoch || owner !== signature.value || !permitted.value) return
  if (document.activeElement !== active && document.activeElement !== document.body) return
  if (ok) result.value?.focus(); else if (state.value.error) failure.value?.focus(); else if (state.value.cancelled) heading.value?.focus()
}
async function cancel() { focusEpoch++; if (!reader.cancel()) return; const mine = focusEpoch; await nextTick(); if (!disposed && mine === focusEpoch && permitted.value) heading.value?.focus() }
const labels = { UNKNOWN: '未登记', PAST_DATE: '已到期', DUE_TODAY: '今日到期', WITHIN_30_DAYS: '30天内到期', FUTURE_DATE: '30天以上' }
const windows = [{ key: 'within_7_days', label: '未来7天内' }, { key: 'within_30_days', label: '未来30天内' }, { key: 'within_90_days', label: '未来90天内' }, { key: 'in_reference_quarter', label: '参考日所在季度' }] as const
const fields = [{ key: 'warranty_expiry', label: '维保到期日期' }, { key: 'overdue_service_date', label: '超期服役日期' }] as const
</script>
<template>
  <section v-if="permitted" class="date-assessment detail-section" :aria-labelledby="headingId" :aria-busy="state.busy">
    <h3 :id="headingId" ref="heading" tabindex="-1">维保与超期日期核算</h3><p>按指定基准日期核算到期状态，仅核对已登记台账日期。未填写的日期保持“未登记”状态；如需维护维保起始日或服务商，请使用资产详情中的“维保档案”入口。</p>
    <div class="date-actions"><el-button :disabled="state.busy" @click="read(reader.refresh)">{{ state.attempted ? '重新核算日期' : '执行到期核算' }}</el-button>
      <el-button v-if="state.busy" @click="cancel">取消日期核算</el-button>
      <el-button v-if="state.attempted && !state.busy && (state.error || state.cancelled)" @click="read(reader.retry)">重试原资产核算</el-button>
    </div>
    <p role="status" aria-live="polite">{{ state.busy ? '正在核算已登记日期，可以取消。' : state.cancelled ? '日期核算已取消，旧结果已清除；可重试原资产。' : state.error ? '核算未完成，请查看错误及恢复方式。' : state.current ? '已取得本次核算结果。' : '尚未执行日期核算。' }}</p>
    <div v-if="state.error" ref="failure" role="group" aria-label="资产日期核算失败" tabindex="-1"><ApiErrorNotice :error="state.error" guidance="未将读取失败当作日期未登记。请重试原资产；重试会使用服务端当次基准日，不是重放旧日期快照。" /></div>
    <el-skeleton v-if="state.busy" :rows="3" animated />
    <section v-if="state.current" ref="result" tabindex="-1" :aria-labelledby="resultId">
      <h4 :id="resultId">原资产日期核算结果</h4>
      <p>核算基准日：{{ state.current.as_of_date }}（{{ state.current.timezone }}）<br />核算时间：{{ displayTime(state.current.observed_at) }}<br />资产版本 v{{ state.current.asset_version }} · 资料版本 {{ state.current.metadata_version ?? '未提供' }}</p>
      <p>核算结果基于上述时刻的已登记台账日期；若后续修改了维保或服役日期，请重新执行核算。</p>
      <article v-for="field in fields" :key="field.key" class="date-card" :class="{ 'past-date': state.current[field.key].position === 'PAST_DATE' }">
        <h4>{{ field.label }}</h4><strong>{{ state.current[field.key].recorded_date ?? '未登记' }}</strong><p>{{ labels[state.current[field.key].position] }}<span v-if="state.current[field.key].days_until !== null"> · 距基准日 {{ state.current[field.key].days_until }} 个日历日</span></p>
        <dl><template v-for="window in windows" :key="window.key"><dt>{{ window.label }}</dt><dd>{{ state.current[field.key][window.key] === null ? '待定' : state.current[field.key][window.key] ? '是' : '否' }}</dd></template></dl>
      </article>
      <el-alert title="仅执行查询核算，不变更台账记录，不触发工单或通知" type="info" :closable="false">到期提醒通知与隐患工单需按独立流程触发。</el-alert>
    </section>
    <p v-else-if="!state.busy && !state.error && !state.cancelled">点击“执行到期核算”获取服务端基准日对照结果。</p>
  </section>
</template>
<style scoped>
.date-assessment{overflow-wrap:anywhere;min-width:0}.date-actions{display:flex;flex-wrap:wrap;gap:10px}.date-actions :deep(.el-button){min-height:44px;min-width:44px;margin-left:0}.date-assessment :focus-visible,.date-assessment [tabindex='-1']:focus{outline:3px solid #175cd3;outline-offset:3px}.date-assessment :deep(.error-details summary){min-height:44px;line-height:44px}.date-assessment p{color:#536276;line-height:1.6}.date-card{border:1px solid #dbe3ed;border-radius:10px;padding:14px;margin:14px 0}.date-card h4{margin:0 0 8px}.past-date{border-color:#ce5858}.past-date strong{color:#a52b2b}dl{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px}dd{margin:0}dt{color:#536276}@media(max-width:700px){.date-card{padding:10px}.date-assessment :deep(.el-button){min-height:44px}}
</style>
