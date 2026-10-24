<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { pendingTicketLabel, pendingTicketTarget } from '../utils/dashboardPendingTickets'
import { actionableClockLabel, createActionableTickets, emptyActionableTickets, type ActionableTicketState } from '../utils/dashboardActionableTickets'
import { detachedWorkspaceState } from '../utils/detachedWorkspaceState'
import { displayTime } from '../utils/display'
import { restoreActionableNavigation } from '../utils/actionableTicketNavigation'
import ApiErrorNotice from './ApiErrorNotice.vue'

const props = withDefaults(defineProps<{ fullList?: boolean }>(), { fullList: false })
const emit = defineEmits<{ state: [ActionableTicketState] }>()
const pageSize = props.fullList ? 20 : 5
const router = useRouter(), route = useRoute()
let navigationView: string | null = null
const state = shallowRef(emptyActionableTickets(pageSize))
const status = ref<HTMLElement | null>(null), refreshButton = ref<HTMLButtonElement | null>(null)
const workspace = createActionableTickets(api, value => {
  state.value = value
  emit('state', detachedWorkspaceState(value))
}, pageSize)
let disposed = false
const customer = computed(() => ['viewer', 'tadmin'].includes(session.value?.user.role ?? ''))
const permitted = computed(() => session.value?.mode === 'remote_test' && can('dashboard:read') && can('tickets:read') && !switchingTenant.value)
const scope = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.user.role, session.value?.permission_version, session.value?.account_version, session.value?.mode, [...(session.value?.capabilities ?? [])].sort(), permitted.value]))
watch(scope, () => {
  const current = session.value
  workspace.reset(permitted.value && current ? { tenantId: current.tenant.id, actorId: current.user.id, role: current.user.role, permissionVersion: current.permission_version } : null)
  if (permitted.value) {
    const restored = props.fullList ? restoreActionableNavigation(router, scope.value, route.query) : null
    navigationView = restored?.view ?? null
    void read(false, restored?.page ?? 1, false)
  }
}, { immediate: true, flush: 'sync' })
async function read(retry = false, page = state.value.page, focus = true) {
  if (!permitted.value || state.value.busy || disposed) return
  const owner = scope.value
  const ok = await (retry ? workspace.retry() : workspace.refresh(page))
  if (ok && props.fullList && !disposed && owner === scope.value && navigationView && route.path === '/dashboard/actionable-tickets') {
    await router.replace({ path: route.path, query: { ...route.query, page: String(state.value.page), view: navigationView } }).catch(() => {
      // The router's recovery notice reports failed navigation; never leave a rejected background promise.
    })
  }
  await nextTick()
  if (focus && !disposed && owner === scope.value) status.value?.focus()
}
async function cancel() {
  workspace.cancel()
  await nextTick()
  if (!disposed && permitted.value) refreshButton.value?.focus()
}
onUnmounted(() => { disposed = true; workspace.dispose() })
defineExpose({ refresh: () => workspace.refresh() })
const summary = computed(() => {
  const value = state.value
  if (value.busy) return '正在读取待办汇总…'
  if (value.cancelled) return '读取已取消，未显示旧列表或数量。'
  if (value.error) return '读取失败，未显示旧列表或数量。'
  if (!value.current) return '尚未读取。'
  const snapshot = value.current
  return (customer.value ? '仅本人报障范围；' : '当前租户授权范围；')
    + (snapshot.pending_tickets === null
      ? '完整待办数未知，已确认待处理 ' + snapshot.known_pending + ' 条，另有 ' + snapshot.unconfigured + ' 条登记未配置流程。'
      : '待处理 ' + snapshot.pending_tickets + ' 条，' + (props.fullList ? '本页显示 ' : '按登记时间显示最近 ') + snapshot.items.length + ' 条。')
})
</script>
<template>
  <section v-if="permitted" class="dashboard-workflows" aria-labelledby="dashboard-workflows-title" :aria-busy="state.busy">
    <header>
      <div class="wf-title"><h2 id="dashboard-workflows-title">{{ customer ? '我的待处理工单' : '待处理工单' }}</h2><span>未关闭且未解决 · {{ fullList ? '完整分页' : '最近5条' }}</span></div>
      <div class="actions">
        <button ref="refreshButton" type="button" :disabled="state.busy" @click="read()">刷新工单</button>
        <button v-if="state.busy" type="button" @click="cancel">取消读取工单</button>
        <router-link v-if="!fullList" to="/dashboard/actionable-tickets">全部待处理与SLA</router-link>
        <router-link v-else to="/dashboard">返回运营总览</router-link>
      </div>
    </header>
    <ApiErrorNotice :error="state.error" />
    <p ref="status" tabindex="-1" role="status" class="wf-status">{{ summary }}</p>
    <button v-if="state.canRetry" type="button" class="wf-retry" :disabled="state.busy" @click="read(true)">重试待办汇总</button>
    <template v-if="state.current">
      <p class="wf-status">汇总时间 {{ displayTime(state.current.observed_at) }} · SLA以该时刻的服务端评估为准。</p>
      <p class="wf-status wf-sla-summary">已超 SLA <strong :class="{ 'wf-overdue': (state.current.overdue_sla ?? state.current.known_overdue) > 0 }">{{ state.current.overdue_sla ?? '未知' }}</strong><span v-if="state.current.overdue_sla === null"> · 已确认超时 {{ state.current.known_overdue }} 条，另有 {{ state.current.unknown_sla }} 条SLA未知</span></p>
      <p v-if="!state.current.items.length" class="wf-empty">{{ state.current.known_pending ? '当前页已无工单，请返回第一页重新查看。' : state.current.unconfigured ? '暂无已确认的待处理流程；未配置登记仍待核对。' : '当前授权范围没有未关闭且未解决的工单。' }}</p>
      <div v-else class="workflow-scroll" tabindex="0" role="region" aria-label="跨状态待处理工单，可横向滚动">
        <table class="mop-table">
          <caption>当前授权范围内未关闭且未解决工单{{ fullList ? '的分页列表' : '的最近五条' }}，按登记时间降序</caption>
          <thead><tr><th scope="col">工单号</th><th scope="col">标题</th><th scope="col">状态</th><th scope="col">SLA</th><th scope="col">详情</th></tr></thead>
          <tbody>
            <tr v-for="row in state.current.items" :key="row.ticket_id">
              <td>{{ row.receipt_no }}</td><td>{{ row.title }}</td>
              <td><span class="wf-state mop-badge info">{{ pendingTicketLabel(row) }}</span></td>
              <td class="wf-time">
                <span class="wf-clock" :class="{ 'wf-overdue': row.response_clock.breached === true }">响应：{{ actionableClockLabel(row.response_clock) }}</span>
                <span class="wf-clock" :class="{ 'wf-overdue': row.recovery_clock.breached === true }">恢复：{{ actionableClockLabel(row.recovery_clock) }}</span>
              </td>
              <td><router-link :to="pendingTicketTarget(row)" :aria-label="(row.workflow_model === 'WORKFLOW_V33' ? '查看工单处理' : '查看工单登记') + row.receipt_no">{{ row.workflow_model === 'WORKFLOW_V33' ? '查看处理' : '查看登记' }}</router-link></td>
            </tr>
          </tbody>
        </table>
      </div>
      <nav v-if="fullList" class="paging" aria-label="待处理工单分页">
        <span v-if="state.current.total_pages">第 {{ state.current.page }} 页 / 共 {{ state.current.total_pages }} 页 · 每页 {{ state.current.page_size }} 条</span>
        <span v-else>暂无可分页的已确认待处理流程</span>
        <button v-if="state.current.page > state.current.total_pages && state.current.page > 1" type="button" @click="read(false, 1)">返回第一页</button>
        <button type="button" :disabled="state.busy || state.current.page <= 1" @click="read(false, state.current.page - 1)">上一页</button>
        <button type="button" :disabled="state.busy || state.current.page >= state.current.total_pages" @click="read(false, state.current.page + 1)">下一页</button>
      </nav>
    </template>
  </section>
</template>
<style scoped>
.dashboard-workflows{display:flex;flex-direction:column;min-width:0;border:1px solid var(--mop-line,#e2e8f0);border-radius:8px;background:#fff;color:var(--mop-text,#2f4157)}
.dashboard-workflows header{display:flex;gap:8px 12px;align-items:center;justify-content:space-between;flex-wrap:wrap;min-height:48px;padding:10px 16px;border-bottom:1px solid var(--mop-line-soft,#edf1f6)}
.wf-title{display:flex;align-items:baseline;gap:8px}.wf-title span{font-size:12px;color:var(--mop-muted,#5d6e83)}
.actions,.paging{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.dashboard-workflows h2{margin:0;font-size:14px;font-weight:600;color:var(--mop-ink,#17263a)}
.dashboard-workflows select{min-height:32px;padding:0 8px;font-size:13px}
.actions a{display:inline-flex;align-items:center;min-height:32px;padding:0 4px;color:var(--mop-primary,#2165c5);font-size:13px;font-weight:500;text-decoration:none}
.actions a::after{content:'›';margin-left:4px;font-size:15px;line-height:1}
.actions a:hover{text-decoration:underline;text-underline-offset:3px}
.state-filter{display:inline-flex}
.sr-label,.workflow-scroll caption{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap;border:0}
.wf-status{margin:0;padding:8px 16px 0;font-size:12px;color:var(--mop-muted,#5d6e83);outline:none;font-variant-numeric:tabular-nums}
.wf-retry{margin:8px 16px 0;align-self:flex-start}
.wf-empty{margin:12px 16px;padding:24px 16px;border-radius:6px;background:var(--mop-sunken,#f7f9fc);color:var(--mop-text-2,#4f6177);font-size:13px;text-align:center}
.workflow-scroll{overflow:auto;max-width:100%;margin-top:8px}
.workflow-scroll table{min-width:520px}
.workflow-scroll th:first-child,.workflow-scroll td:first-child{padding-left:16px}
.workflow-scroll th:last-child,.workflow-scroll td:last-child{padding-right:16px}
.workflow-scroll td:first-child{max-width:280px;overflow-wrap:anywhere}
.workflow-scroll td strong{display:block;font-weight:600;color:var(--mop-ink,#17263a)}
.workflow-scroll td:first-child span{display:block;margin-top:2px;font-size:12px;color:var(--mop-muted,#5d6e83);font-variant-numeric:tabular-nums}
.workflow-scroll td a{color:var(--mop-primary,#2165c5);text-decoration:none;font-weight:500}.workflow-scroll td a:hover{text-decoration:underline}
.wf-time{white-space:nowrap;font-variant-numeric:tabular-nums;color:var(--mop-text-2,#4f6177)}
.wf-clock{display:block;font-size:12px;color:var(--mop-muted,#5d6e83);line-height:1.7}
.wf-overdue{color:var(--mop-danger,#b42318);font-weight:600}
.wf-sla-summary strong{font-variant-numeric:tabular-nums}
.wf-due{display:block;font-size:11px;color:var(--mop-muted,#5d6e83)}
.paging{justify-content:flex-end;margin-top:auto;padding:10px 16px;border-top:1px solid var(--mop-line-soft,#edf1f6)}
.dashboard-workflows :focus-visible{outline:2px solid var(--mop-primary,#2165c5);outline-offset:2px}
@media(max-width:760px){.dashboard-workflows header{padding:10px 12px;align-items:stretch}.dashboard-workflows button,.dashboard-workflows select,.actions a{min-height:44px}.actions{justify-content:flex-start}.workflow-scroll td a{display:inline-flex;min-height:44px;align-items:center}.paging{padding:10px 12px}}
</style>
