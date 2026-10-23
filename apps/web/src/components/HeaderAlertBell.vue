<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElPopover } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createAlertBell, emptyAlertBell } from '../utils/alertBell'
import { displayTime } from '../utils/display'
import { navigateFromLink } from '../utils/linkNavigation'
import ApiErrorNotice from './ApiErrorNotice.vue'

const router = useRouter(), open = ref(false), trigger = ref<HTMLButtonElement | null>(null)
const panel = ref<HTMLElement | null>(null), status = ref<HTMLElement | null>(null)
const refreshButton = ref<HTMLButtonElement | null>(null)
const state = shallowRef(emptyAlertBell())
const workspace = createAlertBell(api, value => { state.value = value })
const permitted = computed(() => session.value?.mode === 'remote_test' && ['padmin', 'msp'].includes(session.value.user.role)
  && can('dashboard:read') && can('tickets:read') && !switchingTenant.value)
const scope = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id,
  session.value?.permission_version, session.value?.account_version, session.value?.user.role,
  [...(session.value?.capabilities ?? [])].sort(), permitted.value]))
let timer: ReturnType<typeof setInterval> | undefined, alive = true
watch(scope, () => {
  open.value = false
  const current = session.value, role = current?.user.role
  workspace.reset(permitted.value && current && (role === 'padmin' || role === 'msp')
    ? { tenantId: current.tenant.id, actorId: current.user.id, permissionVersion: current.permission_version, role } : null)
  if (permitted.value) void workspace.refresh()
}, { immediate: true, flush: 'sync' })
watch(open, async value => {
  if (!value || !permitted.value) return
  const owner = scope.value
  void workspace.refresh()
  await nextTick()
  if (alive && open.value && permitted.value && owner === scope.value) panel.value?.focus()
})
async function refresh(retry = false) {
  if (!permitted.value) return
  const owner = scope.value
  await (retry ? workspace.retry() : workspace.refresh())
  await nextTick()
  if (alive && open.value && owner === scope.value && !state.value.cancelled && !state.value.busy) status.value?.focus()
}
async function close() {
  open.value = false
  await nextTick()
  if (alive && permitted.value) trigger.value?.focus()
}
async function cancel() {
  workspace.cancel()
  await nextTick()
  if (alive && open.value && permitted.value) refreshButton.value?.focus()
}
function visibleAgain() { if (permitted.value && !document.hidden) void workspace.refresh() }
onMounted(() => {
  timer = setInterval(visibleAgain, 30000)
  document.addEventListener('visibilitychange', visibleAgain)
})
onUnmounted(() => {
  alive = false; clearInterval(timer); document.removeEventListener('visibilitychange', visibleAgain); workspace.dispose()
})
const badge = computed(() => state.value.busy ? '…' : state.value.current ? state.value.current.total > 99 ? '99+' : String(state.value.current.total) : '—')
const description = computed(() => state.value.busy ? '正在读取未确认告警' : state.value.error ? '未确认告警读取失败'
  : state.value.current ? '当前租户未确认告警 ' + state.value.current.total + ' 条' : '未确认告警数量未知')
const labels = { INFO: '信息', WARNING: '警告', AVERAGE: '一般', HIGH: '严重', DISASTER: '灾难' }
function target(id: string) { return { path: '/monitoring-events', query: { event_id: id } } }
function visit(event: MouseEvent, id: string) {
  if (!permitted.value || !state.value.current?.items.some(item => item.event_id === id)) { event.preventDefault(); return }
  const owner = scope.value
  navigateFromLink(event, () => {
    if (!permitted.value || owner !== scope.value || !state.value.current?.items.some(item => item.event_id === id)) return
    open.value = false
    return router.push(target(id))
  })
}
</script>
<template>
  <el-popover v-if="permitted" v-model:visible="open" trigger="click" placement="bottom-end" :width="420" :show-arrow="false" :persistent="false" role="dialog" aria-label="未确认告警" popper-class="mop-alert-bell-popover">
    <template #reference>
      <button ref="trigger" type="button" class="alert-bell-trigger" :aria-label="description" :title="description" aria-haspopup="dialog" :aria-expanded="open">
        <Bell aria-hidden="true" /><span>告警</span><span class="alert-bell-count" :class="{ active: state.current && state.current.total > 0 }" aria-hidden="true">{{ badge }}</span>
      </button>
    </template>
    <section ref="panel" class="alert-bell-panel" tabindex="-1" aria-labelledby="alert-bell-title" @keydown.esc.stop.prevent="close">
      <header><h2 id="alert-bell-title">未确认告警</h2><button type="button" @click="close">关闭</button></header>
      <p ref="status" role="status" tabindex="-1">{{ description }}<span v-if="state.current"> · {{ displayTime(state.current.observed_at) }}</span></p>
      <p class="alert-bell-scope">仅当前租户的异常、新建事件，显示最近5条；抑制状态单独标注。</p>
      <ApiErrorNotice :error="state.error" :retrying="state.busy" retry-label="重试告警读取" @retry="refresh(true)" />
      <p v-if="state.cancelled">读取已取消，未显示旧数量。</p>
      <p v-if="state.current?.total === 0">当前没有符合条件的未确认告警。</p>
      <ul v-if="state.current?.items.length">
        <li v-for="item in state.current.items" :key="item.event_id">
          <a :href="router.resolve(target(item.event_id)).href" @click="visit($event, item.event_id)">{{ item.rule_name }}</a>
          <div class="alert-bell-facts"><span>{{ labels[item.severity] }}</span><span v-if="item.occurrence_count > 1">×{{ item.occurrence_count }}</span><span v-if="item.suppression_state !== 'NONE'">已抑制</span><time :datetime="item.last_occurred_at">{{ displayTime(item.last_occurred_at) }}</time></div>
        </li>
      </ul>
      <footer><button ref="refreshButton" type="button" :disabled="state.busy" @click="refresh()">刷新告警</button><button v-if="state.busy" type="button" @click="cancel">取消读取告警</button></footer>
    </section>
  </el-popover>
</template>
<style scoped>
.alert-bell-trigger{display:inline-flex;align-items:center;gap:6px;min-height:32px;padding:4px 8px;border:1px solid #d8e3f1;border-radius:6px;background:#f8fbff;color:#335f9a;font-size:12px;cursor:pointer}
.alert-bell-trigger svg{width:16px;height:16px}.alert-bell-count{min-width:18px;padding:1px 4px;border-radius:10px;background:#edf2f8;color:#536276;text-align:center;font-variant-numeric:tabular-nums}.alert-bell-count.active{background:#fdecea;color:#9f241a}
.alert-bell-panel{color:#2f4157;outline:none}.alert-bell-panel header,.alert-bell-panel footer{display:flex;align-items:center;justify-content:space-between;gap:8px}.alert-bell-panel h2{margin:0;font-size:15px}.alert-bell-panel p{font-size:12px;line-height:1.6}.alert-bell-scope{color:#536276}.alert-bell-panel ul{list-style:none;margin:12px 0;padding:0;max-height:420px;overflow:auto}.alert-bell-panel li{border-top:1px solid #e2e8f0;padding:10px 0;overflow-wrap:anywhere}.alert-bell-panel a{color:#2165c5;font-size:13px;font-weight:600}.alert-bell-facts{display:flex;flex-wrap:wrap;gap:4px 10px;margin-top:5px;color:#536276;font-size:12px}.alert-bell-panel button{min-height:32px;padding:4px 8px;border:1px solid #cfd9e5;border-radius:4px;background:#fff;color:#335f9a;cursor:pointer}.alert-bell-panel button:disabled{cursor:default;opacity:.6}.alert-bell-panel :focus-visible,.alert-bell-trigger:focus-visible{outline:2px solid #2165c5;outline-offset:2px}
</style>
<style>
.mop-alert-bell-popover{max-width:calc(100vw - 24px);box-sizing:border-box}
</style>
