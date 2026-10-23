<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, useId, watch } from 'vue'
import { api, ApiError } from '../api/client'
import type { Asset } from '../api/types'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createBackupInventoryReader, type BackupInventoryState } from '../utils/backupVersionInventory'
import ApiErrorNotice from './ApiErrorNotice.vue'
import BackupVersionAccessPanel from './BackupVersionAccessPanel.vue'
const props = defineProps<{ asset: Asset }>()
const headingId = useId(), resultId = useId()
const root = ref<HTMLElement>(), heading = ref<HTMLElement>(), result = ref<HTMLElement>(), failure = ref<HTMLElement>()
const state = shallowRef<BackupInventoryState>({ current: null, target: null, busy: false, error: null, cancelled: false })
const reader = createBackupInventoryReader(api,value => { state.value = value })
const permitted = computed(() => !!session.value && !switchingTenant.value && can('assets:read') && props.asset.tenant_id === session.value.tenant.id)
const signature = computed(() => JSON.stringify([props.asset.id,props.asset.tenant_id,props.asset.version,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,session.value?.user.role,session.value?.mode,session.value?.capabilities,contextGeneration.value,switchingTenant.value,permitted.value]))
let focusEpoch = 0, disposed = false
watch(signature,() => {
  focusEpoch++; const s = session.value
  reader.reset(permitted.value && s ? { tenantId: s.tenant.id, assetId: props.asset.id, actorId: s.user.id, permissionVersion: s.permission_version } : null)
},{ immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; focusEpoch++; reader.dispose() })
async function read(action: () => Promise<boolean>) {
  if (state.value.busy) return
  const mine = ++focusEpoch, owner = signature.value, active = document.activeElement
  const ok = await action(); await nextTick()
  if (disposed || mine !== focusEpoch || owner !== signature.value || !permitted.value) return
  // Do not steal focus after the user deliberately moves elsewhere while awaiting a response.
  if (document.activeElement !== active && document.activeElement !== document.body) return
  if (ok) result.value?.focus(); else if (state.value.error) failure.value?.focus(); else if (state.value.cancelled) heading.value?.focus()
}
async function cancel() { focusEpoch++; if (!reader.cancel()) return; const mine = focusEpoch; await nextTick(); if (!disposed && mine === focusEpoch && permitted.value) heading.value?.focus() }
const problem = computed(() => state.value.error instanceof ApiError ? state.value.error : null)
const guidance = computed(() => problem.value?.code === 'BACKUP_VERSION_INVENTORY_NOT_CONFIGURED'
  ? '备份版本独立授权与查询绑定尚未配置。这不是“没有备份”，请由管理员完成装配后重试原页。'
  : problem.value?.status === 403 ? '当前身份未获独立的备份版本读取授权。资产可见不代表有权枚举备份，请核对授权后重试。'
  : '未展示旧数据或部分结果。可重试原页，也可从首页重新读取；不会下载备份或执行设备命令。')
</script>
<template>
  <section v-if="permitted" ref="root" class="backup-inventory detail-section" :aria-labelledby="headingId" :aria-busy="state.busy">
    <h3 :id="headingId" ref="heading" tabindex="-1">已登记备份版本 <small class="inventory-note">只读 · 不访问对象存储</small></h3>
    <div class="inventory-actions">
      <button type="button" :disabled="state.busy" @click="read(reader.first)">{{ state.target ? '从首页重新读取' : '读取已登记版本' }}</button>
      <button v-if="state.busy" type="button" @click="cancel">取消读取</button>
      <button v-if="state.target && !state.busy && (state.error || state.cancelled)" type="button" @click="read(reader.retry)">重试原页</button>
      <button v-if="state.current?.has_more" type="button" :disabled="state.busy" @click="read(reader.next)">读取下一页</button>
    </div>
    <p role="status" aria-live="polite">{{ state.busy ? '正在读取当前授权范围，请稍候；可以取消。' : state.cancelled ? '读取已取消，未保留旧结果；可重试原页。' : state.current ? `当前页 ${state.current.items.length} 条${state.current.has_more ? '，还有下一页。' : '，此查询边界内已无下一页。'}` : state.error ? '读取未完成，请查看错误及恢复方式。' : '尚未查询，点击“读取已登记版本”开始。' }}</p>
    <div v-if="state.error" ref="failure" role="group" aria-label="备份版本读取失败" tabindex="-1"><ApiErrorNotice :error="state.error" :guidance="guidance" /></div>
    <section v-if="state.current" ref="result" tabindex="-1" :aria-labelledby="resultId">
      <h4 :id="resultId">{{ state.current.items.length ? '本页版本登记记录' : '本页没有已登记版本' }}</h4>
      <p>每页最多 {{ state.current.page_size }} 条，按登记时间及版本标识倒序。只反映本次读取，不是所有分页共享的固定快照；总条数未知。</p>
      <p v-if="state.target?.cursor">本页边界：早于 {{ state.target.cursor.registeredAt }} / {{ state.target.cursor.versionId }}。重试始终沿用该原始成对边界。</p>
      <div v-if="state.current.items.length" class="inventory-scroll" role="region" aria-label="备份版本登记表，可横向滚动" tabindex="0">
        <table><caption>当前资产的备份版本登记元数据，时间均为 UTC</caption><thead><tr><th scope="col">版本标识</th><th scope="col">登记时间 / 对象观测时间</th><th scope="col">运行标识 / 尝试</th><th scope="col">密文字节数 / SHA-256</th></tr></thead>
          <tbody><tr v-for="item in state.current.items" :key="item.version_id"><th scope="row">{{ item.version_id }}</th><td><time :datetime="item.registered_at">{{ item.registered_at }}</time><br /><time :datetime="item.object_observed_at">{{ item.object_observed_at }}</time></td><td>{{ item.run_id }}<br />尝试 {{ item.attempt_number }} · 完成运行版本 {{ item.completed_run_version }}</td><td>{{ item.encrypted_bytes }} 字节<br /><span>{{ item.encrypted_sha256 }}</span></td></tr></tbody>
        </table>
      </div>
      <p>不能将本列表作为可恢复性证明。</p>
      <BackupVersionAccessPanel v-if="state.current.items.length" :asset-id="asset.id" :tenant-id="asset.tenant_id" :versions="state.current.items" />
    </section>
  </section>
</template>
<style scoped>
.backup-inventory{min-width:0;overflow-wrap:anywhere;color:#25354a}.backup-inventory p{line-height:1.65}.inventory-actions{display:flex;flex-wrap:wrap;gap:10px}.inventory-actions button{min-width:44px;min-height:44px;padding:10px 14px;border:1px solid #536276;border-radius:6px;background:#fff;color:#25354a;font:inherit;cursor:pointer}.inventory-actions button:disabled{cursor:not-allowed;opacity:.65}.inventory-scroll{max-width:100%;overflow:auto;border:1px solid #65758a;border-radius:6px}table{width:100%;min-width:820px;border-collapse:collapse;text-align:left}caption{padding:12px;font-weight:600;text-align:left}th,td{padding:12px;border-top:1px solid #d5dde8;vertical-align:top;overflow-wrap:anywhere;max-width:280px}thead th{background:#edf2f7}tbody th{font-weight:400}.backup-inventory :focus-visible{outline:3px solid #175cd3;outline-offset:3px}.backup-inventory [tabindex='-1']:focus{outline:3px solid #175cd3;outline-offset:3px}.backup-inventory :deep(.error-details summary){min-height:44px;line-height:44px}@media(max-width:700px){.inventory-actions{align-items:stretch}.inventory-actions button{flex:1 1 140px}}
</style>
