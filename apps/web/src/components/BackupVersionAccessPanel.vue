<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, useId, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { backupVersionId, createBackupContentReader, type BackupContentState } from '../utils/backupVersionContent'
import type { BackupInventoryItem } from '../utils/backupVersionInventory'
import ApiErrorNotice from './ApiErrorNotice.vue'

const props = defineProps<{ assetId: string; tenantId: string; versions: BackupInventoryItem[] }>()
const id = useId(), version = ref(''), before = ref(''), after = ref(''), format = ref<'envelope' | 'plaintext'>('envelope')
const allowed = computed(() => !!session.value && !switchingTenant.value && can('assets:read')
  && ['padmin','msp'].includes(session.value.user.role) && session.value.tenant.id === props.tenantId)
const state = shallowRef<BackupContentState>({ busy: false, cancelled: false, error: null, difference: null, request: null, downloaded: false })
const urls = new Set<string>()
const reader = createBackupContentReader(api, value => { state.value = value }, (blob, filename) => {
  const url = URL.createObjectURL(blob); urls.add(url)
  const link = document.createElement('a'); link.href = url; link.download = filename; link.rel = 'noopener'
  document.body.append(link); link.click(); link.remove()
  setTimeout(() => { URL.revokeObjectURL(url); urls.delete(url) }, 1000)
})
function release() { for (const url of urls) URL.revokeObjectURL(url); urls.clear() }
const context = computed(() => JSON.stringify([props.assetId, props.tenantId, session.value?.user.id, session.value?.user.role,
  session.value?.permission_version, session.value?.account_version, session.value?.tenant.id, contextGeneration.value, allowed.value]))
watch(context, () => {
  release(); version.value = ''; before.value = ''; after.value = ''; format.value = 'envelope'
  const current = session.value
  reader.reset(allowed.value && current ? { assetId: props.assetId, tenantId: props.tenantId, actorId: current.user.id, permissionVersion: current.permission_version } : null)
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { reader.dispose(); release() })
const statusText = computed(() => state.value.difference?.status === 'IDENTICAL' ? '两份已读取内容一致'
  : state.value.difference?.status === 'CHANGED' ? '存在差异，正文已全部隐藏'
  : state.value.difference ? '比较未形成确定结论，请缩小范围或重试' : '')
</script>
<template>
  <section v-if="allowed" class="detail-section" :aria-labelledby="`${id}-heading`" :aria-busy="state.busy">
    <h4 :id="`${id}-heading`">版本访问与脱敏差异</h4>
    <p>需要独立授权。明文仅作为文件下载，请按敏感配置保管；页面不显示配置正文。</p>
    <datalist :id="`${id}-versions`"><option v-for="item in versions" :key="item.version_id" :value="item.version_id">{{ item.registered_at }}</option></datalist>
    <div class="backup-access-fields">
      <label :for="`${id}-version`">下载版本标识</label><input :id="`${id}-version`" v-model="version" :list="`${id}-versions`" :disabled="state.busy" autocomplete="off" />
      <label :for="`${id}-format`">文件格式</label><select :id="`${id}-format`" v-model="format" :disabled="state.busy"><option value="envelope">密文信封</option><option value="plaintext">明文（需解密授权）</option></select>
    </div>
    <button type="button" :disabled="state.busy || !backupVersionId(version)" @click="reader.execute({kind:'download',version,format})">下载所选格式</button>
    <div class="backup-access-fields">
      <label :for="`${id}-before`">比较前版本标识</label><input :id="`${id}-before`" v-model="before" :list="`${id}-versions`" :disabled="state.busy" autocomplete="off" />
      <label :for="`${id}-after`">比较后版本标识</label><input :id="`${id}-after`" v-model="after" :list="`${id}-versions`" :disabled="state.busy" autocomplete="off" />
    </div>
    <button type="button" :disabled="state.busy || !backupVersionId(before) || !backupVersionId(after)" @click="reader.execute({kind:'diff',before,after})">比较两个版本</button>
    <button v-if="state.busy" type="button" @click="reader.cancel()">取消内容请求</button>
    <button v-if="!state.busy && state.request && (state.error || state.cancelled)" type="button" @click="reader.retry()">重试原内容请求</button>
    <p role="status" aria-live="polite">{{ state.busy ? '正在核对当前授权与原版本内容…' : state.cancelled ? '请求已取消，未保留差异结果。' : state.downloaded ? '已交给浏览器下载，请查看下载状态。' : statusText }}</p>
    <p v-if="state.request && (state.error || state.cancelled)">原请求：{{ state.request.kind === 'download' ? `${state.request.version} / ${state.request.format === 'plaintext' ? '明文' : '密文信封'}` : `${state.request.before} → ${state.request.after}` }}。重试保持此范围。</p>
    <ApiErrorNotice v-if="state.error" :error="state.error" guidance="本次请求未确认完成。配置缺失、授权撤销或对象/密钥不可用时会拒绝；可核对后重试原请求。" />
    <div v-if="state.difference">
      <p>本次比较范围：{{ state.difference.before_version_id }} → {{ state.difference.after_version_id }}</p>
      <p>比较前 {{ state.difference.before_lines ?? '未知' }} 行，比较后 {{ state.difference.after_lines ?? '未知' }} 行。尚未据此批准变更或建立工单。</p>
      <table v-if="state.difference.hunks.length"><caption>脱敏差异位置，行位置从0开始</caption><thead><tr><th>原位置</th><th>移除行数</th><th>新位置</th><th>新增行数</th></tr></thead><tbody><tr v-for="(hunk,index) in state.difference.hunks" :key="index"><td>{{ hunk.before_start }}</td><td>{{ hunk.before_count }}</td><td>{{ hunk.after_start }}</td><td>{{ hunk.after_count }}</td></tr></tbody></table>
    </div>
  </section>
</template>
<style scoped>
.backup-access-fields{display:grid;grid-template-columns:minmax(8rem,auto) minmax(0,1fr);align-items:center;gap:8px 12px;margin:12px 0}
.backup-access-fields input,.backup-access-fields select{min-width:0;width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid #536276;border-radius:6px;background:var(--mop-card,#fff);color:var(--mop-text,#25354a);font:inherit}
section{min-width:0;overflow-wrap:anywhere}button{margin:4px 8px 4px 0;min-height:44px;padding:8px 14px;border:1px solid #536276;border-radius:6px;background:var(--mop-card,#fff);color:var(--mop-text,#25354a);font:inherit;cursor:pointer}button:disabled{cursor:not-allowed;opacity:.65}
table{width:100%;border-collapse:collapse;text-align:left}caption{padding:12px;text-align:left;font-weight:600}th,td{padding:10px 12px;border-top:1px solid var(--mop-line,#d5dde8)}thead th{background:var(--mop-sunken,#edf2f7)}:focus-visible{outline:3px solid #175cd3;outline-offset:3px}
</style>
