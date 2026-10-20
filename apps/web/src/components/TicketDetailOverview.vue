<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import type { RegisteredTicket } from '../api/types'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createInternalNoteWorkspace, emptyNoteState, type NoteState } from '../utils/internalNoteWorkspace'
import { createTicketCommentWorkspace, emptyCommentState, type CommentState } from '../utils/ticketCommentWorkspace'
import { createWorklogWorkspace, emptyWorklogState, type WorklogState } from '../utils/worklogWorkspace'
import { ticketActivities, maskedContact, ticketDetailText } from '../utils/ticketDetailPresentation'
import { formatWorklogDuration, summarizeWorklogPage, worklogKindLabels } from '../utils/worklogPageSummary'
import { displayTime } from '../utils/display'
import { impactScopeLabel, urgencyLabel } from '../utils/ticket'
import ApiErrorNotice from './ApiErrorNotice.vue'
import TicketLifecyclePanel from './TicketLifecyclePanel.vue'

const lifecyclePanel = ref<InstanceType<typeof TicketLifecyclePanel> | null>(null)
function canLeave() { return lifecyclePanel.value?.canLeave() !== false }
function closeOverview() { if (canLeave()) emit('close') }
defineExpose({ canLeave })
const props = defineProps<{ ticket: RegisteredTicket; refreshKey: number }>()
const emit = defineEmits<{ close: []; notes: []; comments: []; worklogs: [] }>()
const notes = shallowRef<NoteState>(emptyNoteState()), comments = shallowRef<CommentState>(emptyCommentState()), worklogs = shallowRef<WorklogState>(emptyWorklogState())
const noteReader = createInternalNoteWorkspace(api, value => { notes.value = value })
const commentReader = createTicketCommentWorkspace(api, value => { comments.value = value })
const worklogReader = createWorklogWorkspace(api, value => { worklogs.value = value })
const customer = computed(() => ['viewer', 'tadmin'].includes(session.value?.user.role || ''))
const permitted = computed(() => Boolean(session.value && !switchingTenant.value && props.ticket.tenant_id === session.value.tenant.id && can('tickets:read') && (!customer.value || props.ticket.reporter_id === session.value.user.id)))
const canNotes = computed(() => permitted.value && ['msp', 'padmin'].includes(session.value?.user.role || '') && can('ticket_notes:read_internal'))
const canComments = computed(() => permitted.value && can('ticket_comments:read'))
const canWorklogs = computed(() => permitted.value && !customer.value && can('worklogs:read_own'))
const showPhone = ref(false), downloadError = ref('')
const scopeKey = computed(() => JSON.stringify([contextGeneration.value, session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, permitted.value, canNotes.value, canComments.value, canWorklogs.value, props.ticket.id, props.ticket.version]))
function resetReaders() {
  showPhone.value = false; downloadError.value = ''
  const current = session.value
  const scope = permitted.value && current ? { tenantId: current.tenant.id, ticketId: props.ticket.id, actorId: current.user.id, permissionVersion: current.permission_version, canWrite: false } : null
  noteReader.reset(canNotes.value ? scope : null)
  commentReader.reset(canComments.value && scope ? { ...scope, authorKind: customer.value ? 'REPORTER' : 'ENGINEER' } : null)
  worklogReader.reset(canWorklogs.value ? scope : null)
  void refresh()
}
async function refresh() {
  await Promise.allSettled([canNotes.value ? noteReader.refresh() : Promise.resolve(), canComments.value ? commentReader.refresh() : Promise.resolve(), canWorklogs.value ? worklogReader.refresh() : Promise.resolve()])
}
watch(scopeKey, resetReaders, { immediate: true, flush: 'sync' })
watch(() => props.refreshKey, () => { void refresh() })
onUnmounted(() => { noteReader.dispose(); commentReader.dispose(); worklogReader.dispose() })
const loadingActivity = computed(() => notes.value.busy === 'read' || comments.value.busy === 'read')
const activity = computed(() => permitted.value ? ticketActivities(props.ticket, canNotes.value ? notes.value.page?.items || [] : [], canComments.value ? comments.value.page?.items || [] : [], session.value?.user.id || '') : [])
const activityScope = computed(() => `登记记录${canNotes.value ? '、最新15条内部备注' : ''}${canComments.value ? '、最新15条客户沟通' : ''}`)
const worklogScope = computed(() => `仅本人 · ${worklogs.value.paged ? '后续页' : '第一页'} · 每页15条${worklogs.value.page?.has_more ? ' · 还有更多记录' : ''}`)
const summary = computed(() => summarizeWorklogPage(canWorklogs.value ? worklogs.value.page?.items ?? null : null))
const handling = computed(() => summary.value?.groups.filter(group => group.kind !== 'COMMUTE').reduce((sum, group) => sum + group.millis, 0n) ?? null)
const commute = computed(() => summary.value?.groups.find(group => group.kind === 'COMMUTE')?.millis ?? null)
const exportReady = computed(() => permitted.value && (!canNotes.value || Boolean(notes.value.page) && !notes.value.busy && !notes.value.error) && (!canComments.value || Boolean(comments.value.page) && !comments.value.busy && !comments.value.error) && (!canWorklogs.value || Boolean(worklogs.value.page) && !worklogs.value.busy && !worklogs.value.error))
function exportDetail() {
  if (!exportReady.value || !session.value) return
  downloadError.value = ''
  let url: string | undefined
  try {
    const body = ticketDetailText(props.ticket, session.value.tenant.name, activity.value, canWorklogs.value ? worklogs.value.page?.items ?? null : null, activityScope.value, canWorklogs.value ? worklogScope.value : '当前身份无工时查询权限', new Date().toISOString())
    url = URL.createObjectURL(new Blob(['\ufeff', body], { type: 'text/plain;charset=utf-8' }))
    const link = document.createElement('a'); link.href = url; link.download = `工单详情-${props.ticket.receipt_no.replace(/[^a-zA-Z0-9_-]/g, '_')}.txt`; document.body.appendChild(link); link.click(); link.remove()
  } catch { downloadError.value = '导出未完成，请重试。' }
  finally { if (url) { const downloadUrl = url; window.setTimeout(() => URL.revokeObjectURL(downloadUrl), 1000) } }
}
const stages = ['工单发起', '工单受理', '原因定位', '解决方案', '服务完结', '客户确认']
</script>

<template>
  <div v-if="permitted" class="ticket-overview">
    <nav class="ticket-breadcrumb" aria-label="工单详情路径"><button type="button" @click="closeOverview">工单中心</button><span aria-hidden="true">/</span><span>报障与受理</span><span aria-hidden="true">/</span><strong>工单详情</strong></nav>
    <header class="ticket-overview-heading">
      <div class="ticket-heading-title"><h2>工单详情</h2><span class="ticket-registration-badge"><span aria-hidden="true">●</span> 已登记</span><span class="ticket-heading-receipt">{{ ticket.receipt_no }}</span></div>
      <div class="ticket-overview-actions"><el-button :disabled="!exportReady" @click="exportDetail">导出详情</el-button><el-button disabled title="正式流转与处理资格尚未接入">转派</el-button><el-button v-if="canNotes && can('ticket_notes:write_internal')" type="primary" @click="emit('notes')">新增处理记录</el-button><el-button v-else-if="canComments && can('ticket_comments:write')" type="primary" @click="emit('comments')">追加沟通记录</el-button></div>
    </header>
    <p v-if="downloadError" role="alert">{{ downloadError }}</p>
    <section class="ticket-stage-card" aria-label="工单阶段">
      <ol class="ticket-stage-list"><li v-for="(stage, index) in stages" :key="stage" :class="{ recorded: index === 0 }"><span class="ticket-stage-number" aria-hidden="true">{{ index === 0 ? '✓' : index + 1 }}</span><span>{{ stage }}<small>{{ index === 0 ? '已受理入库' : '暂未提供' }}</small></span></li></ol>
    </section>

    <div class="ticket-overview-grid">
      <section class="ticket-detail-card ticket-basic-card" aria-labelledby="ticket-basic-title">
        <header class="ticket-card-header"><h3 id="ticket-basic-title">基本信息</h3><span>创建于 {{ displayTime(ticket.created_at) }}</span></header>
        <div class="ticket-basic-content">
          <dl class="ticket-basic-fields">
            <div><dt>登记编号</dt><dd class="mono">{{ ticket.receipt_no }}</dd></div>
            <div><dt>工单标题</dt><dd>{{ ticket.title }}</dd></div>
            <div><dt>告警编号</dt><dd class="ticket-muted">暂未提供</dd></div>
            <div><dt>客户名称</dt><dd>{{ session?.tenant.name }}</dd></div>
            <div><dt>渠道名称</dt><dd class="ticket-muted">人工登记</dd></div>
            <div><dt>客户联系人</dt><dd>{{ ticket.contact_name }}</dd></div>
            <div><dt>客户联系方式</dt><dd class="ticket-contact">{{ showPhone ? ticket.contact_phone : maskedContact(ticket.contact_phone) }}<button type="button" :aria-pressed="showPhone" @click="showPhone = !showPhone">{{ showPhone ? '隐藏' : '显示' }}</button></dd></div>
            <div><dt>关联设备</dt><dd>{{ ticket.asset_name || (ticket.asset_id ? '已关联（名称暂未提供）' : '未关联') }}</dd></div>
            <div><dt>问题类型</dt><dd><span class="ticket-category">{{ ticket.category }}</span></dd></div>
            <div><dt>业务影响</dt><dd><span :class="['ticket-impact', { urgent: ticket.urgency === 'urgent' }]">{{ impactScopeLabel[ticket.impact_scope] }}</span><small>{{ urgencyLabel[ticket.urgency] }} · 人工登记</small></dd></div>
            <div><dt>优先级 / SLA</dt><dd class="ticket-muted">暂未提供</dd></div>
            <div class="ticket-description-field"><dt>问题描述</dt><dd><p>{{ ticket.description }}</p></dd></div>
          </dl>
        </div>
      </section>

      <div class="ticket-detail-right">
        <section class="ticket-detail-card ticket-progress-card" aria-labelledby="ticket-progress-title" :aria-busy="loadingActivity">
          <header class="ticket-card-header"><h3 id="ticket-progress-title">处理进展</h3><span>{{ loadingActivity ? '正在读取动态…' : `已显示 ${activity.length} 条动态` }}<template v-if="!loadingActivity && activity[0]"> · 更新于 {{ displayTime(activity[0].time) }}</template></span></header>
          <div class="ticket-progress-content">
            <ApiErrorNotice v-if="canNotes" :error="notes.error"><el-button size="small" @click="noteReader.refresh()">重试内部记录</el-button></ApiErrorNotice>
            <ApiErrorNotice v-if="canComments" :error="comments.error"><el-button size="small" @click="commentReader.refresh()">重试客户沟通</el-button></ApiErrorNotice>
            <p v-if="loadingActivity" class="ticket-muted" role="status">正在读取处理进展记录…</p>
            <ol class="ticket-activity-list"><li v-for="item in activity" :key="item.id"><time :datetime="item.time">{{ displayTime(item.time) }}</time><div :class="['ticket-activity-title', item.kind]">{{ item.title }}<small v-if="item.author">{{ item.author }}</small></div><p>{{ item.text }}</p></li></ol>
          </div>
          <footer class="ticket-card-links"><span>{{ activityScope }}<template v-if="notes.page?.has_more || comments.page?.has_more"> · 更早记录请查看全部</template></span><div><el-button v-if="canNotes" link type="primary" @click="emit('notes')">全部内部记录</el-button><el-button v-if="canComments" link type="primary" @click="emit('comments')">客户沟通</el-button><el-button link type="primary" :disabled="loadingActivity" @click="refresh">刷新动态</el-button></div></footer>
        </section>

        <section class="ticket-detail-card ticket-hours-card" aria-labelledby="ticket-hours-title" :aria-busy="worklogs.busy === 'read'">
          <header class="ticket-card-header"><h3 id="ticket-hours-title">处理工时</h3><span>{{ !canWorklogs ? '当前身份无查询权限' : worklogs.busy ? '读取中…' : worklogs.page ? `本页 ${worklogs.page.items.length} 条工时记录 · 仅本人` : '尚未读取' }}</span></header>
          <div class="ticket-hours-content">
            <p v-if="!canWorklogs" class="ticket-empty">当前身份没有工时查看权限。</p>
            <template v-else>
              <ApiErrorNotice :error="worklogs.error"><el-button size="small" @click="worklogReader.refresh()">重试工时</el-button></ApiErrorNotice>
              <p v-if="worklogs.busy" class="ticket-empty" role="status">正在读取本人工时…</p>
              <p v-else-if="worklogs.page && !worklogs.page.items.length" class="ticket-empty">本页暂无本人工时记录，可通过下方入口填报。</p>
              <div v-if="worklogs.page?.items.length" class="ticket-hours-scroll" tabindex="0" role="region" aria-label="本人工时表，可横向滚动">
                <table class="ticket-hours-table"><thead><tr><th scope="col">处理人</th><th scope="col">工作时间</th><th scope="col">工作时长</th><th scope="col">处理工时</th><th scope="col">路程工时</th><th scope="col">工作描述</th></tr></thead><tbody><tr v-for="row in worklogs.page.items" :key="row.id"><td>{{ session?.user.display_name || '我' }}<small>{{ worklogKindLabels[row.kind] }}</small></td><td><time :datetime="row.started_at">{{ displayTime(row.started_at) }}</time><span class="ticket-time-separator">至</span><time :datetime="row.ended_at">{{ displayTime(row.ended_at) }}</time></td><td>{{ formatWorklogDuration(BigInt(row.elapsed_millis)) }}</td><td class="ticket-hours-emphasis">{{ row.kind === 'COMMUTE' ? '—' : formatWorklogDuration(BigInt(row.elapsed_millis)) }}</td><td>{{ row.kind === 'COMMUTE' ? formatWorklogDuration(BigInt(row.elapsed_millis)) : '—' }}</td><td class="ticket-work-note">{{ row.note || '未填写' }}</td></tr></tbody></table>
              </div>
            </template>
          </div>
          <footer v-if="canWorklogs" class="ticket-hours-footer">
            <div class="ticket-hours-total"><span>本页累计：<strong>{{ summary ? formatWorklogDuration(summary.totalMillis) : '— · 尚未读取' }}</strong></span><span v-if="summary?.count">处理 {{ formatWorklogDuration(handling) }} · 路程 {{ formatWorklogDuration(commute) }}</span></div>
            <p>{{ worklogScope }}；按实际填报时段汇总，计费核算以商务结算规则为准。</p>
            <div class="ticket-hours-pagination"><el-button v-if="worklogs.paged" size="small" :disabled="Boolean(worklogs.busy)" @click="worklogReader.refresh()">返回第一页</el-button><el-button v-if="worklogs.page?.has_more" size="small" :disabled="Boolean(worklogs.busy)" @click="worklogReader.refresh(true)">下一页</el-button><el-button size="small" type="primary" plain @click="emit('worklogs')">{{ can('worklogs:write_own') ? '填报 / 管理本人工时' : '查看本人工时' }}</el-button></div>
          </footer>
        </section>
      </div>
    </div>
    <TicketLifecyclePanel ref="lifecyclePanel" :ticket="ticket" :refresh-key="refreshKey" />
    <footer class="ticket-overview-bottom"><span>登记凭证与生命周期分别展示；流转状态以“工单流转”读回为准，外部同步与 SLA 考核尚未完成接线。</span><div class="ticket-overview-actions"><el-button disabled title="挂起状态与SLA暂停规则尚未接入">挂起</el-button><el-button disabled title="协助申请与分派规则尚未接入">申请协助</el-button><el-button v-if="canNotes && can('ticket_notes:write_internal')" type="primary" @click="emit('notes')">新增处理记录</el-button><el-button v-else-if="canComments && can('ticket_comments:write')" type="primary" @click="emit('comments')">追加沟通记录</el-button><el-button @click="closeOverview">返回列表</el-button></div></footer>
    <section class="ticket-detail-card ticket-supporting-card"><details><summary>报障照片与登记凭证 <span>查看附件、登记操作与记录标识</span></summary><div class="ticket-supporting-content"><slot /></div></details></section>
    <p class="ticket-record-explanation">登记凭证与生命周期分别展示；流转状态以“工单流转”读回为准，外部同步与 SLA 考核尚未完成接线。</p>
    <p v-if="canNotes" class="ticket-record-explanation">新增处理记录保存为仅内部可见的备注；需要客户查看的内容请使用“客户沟通”。</p>
  </div>
  <p v-else role="status">当前身份或租户无法查看此工单，请返回列表重新查询。</p>
</template>

<style scoped>
.ticket-overview{--ticket-muted:#64748b;--ticket-line:#dce5f1;color:#142c4a;font-size:13px;max-width:1320px;margin:0 auto;min-width:0}
.ticket-overview button{font:inherit}.ticket-breadcrumb{display:flex;align-items:center;gap:7px;flex-wrap:wrap;color:var(--ticket-muted);font-size:12px;margin:0 0 14px}.ticket-breadcrumb button{border:0;background:none;color:var(--ticket-muted);padding:6px 0;cursor:pointer}.ticket-breadcrumb strong{font-weight:500;color:#173b6c}
.ticket-overview-heading,.ticket-heading-title,.ticket-overview-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.ticket-overview-heading{justify-content:space-between;margin-bottom:18px}.ticket-heading-title{min-width:0;max-width:100%}.ticket-heading-title h2{font-size:22px;margin:0;font-weight:650}.ticket-heading-receipt{overflow-wrap:anywhere;min-width:0;max-width:100%;font-size:12px;color:var(--ticket-muted)}.ticket-registration-badge{border-radius:20px;padding:5px 10px;color:#945300;background:#fff5dc;font-size:12px}.ticket-registration-badge>span{font-size:9px}.ticket-overview-actions :deep(.el-button){margin:0;min-height:36px}
.ticket-stage-card,.ticket-detail-card,.ticket-overview-bottom{background:#fff;border:1px solid var(--ticket-line);border-radius:10px}.ticket-stage-card{padding:22px 24px 12px;margin-bottom:16px}.ticket-stage-list{list-style:none;display:flex;margin:0;padding:0;gap:12px}.ticket-stage-list li{display:flex;align-items:flex-start;gap:9px;position:relative;flex:1;min-width:0;color:var(--ticket-muted);font-size:13px}.ticket-stage-list li:not(:last-child)::after{content:'';flex:1;border-top:2px dotted #c9d8ef;margin-top:12px;min-width:10px}.ticket-stage-list li:last-child{flex:0 0 auto}.ticket-stage-number{display:grid;place-items:center;flex-shrink:0;width:25px;height:25px;border-radius:50%;background:#edf1f7;color:#62748c;font-weight:650}.ticket-stage-list li.recorded{color:#184b9a}.recorded .ticket-stage-number{background:#2563eb;color:white}.ticket-stage-list li>span:last-child{min-width:0}.ticket-stage-list small{display:block;font-size:11px;line-height:1.7;color:var(--ticket-muted);margin-top:3px}.ticket-stage-note{color:var(--ticket-muted);font-size:12px;margin:14px 0 0;line-height:1.7}
.ticket-overview-grid{display:grid;grid-template-columns:minmax(0,.95fr) minmax(0,1.05fr);gap:16px;align-items:stretch}.ticket-detail-card{min-width:0;overflow:hidden}.ticket-card-header{min-height:49px;padding:12px 20px;border-bottom:1px solid var(--ticket-line);display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.ticket-card-header h3{font-size:14px;margin:0;padding-left:10px;position:relative;color:#17375e;font-weight:650}.ticket-card-header h3::before{content:'';position:absolute;left:0;top:3px;bottom:3px;width:3px;background:#2868ec;border-radius:2px}.ticket-hours-card .ticket-card-header h3::before{background:#c8700c}.ticket-card-header>span{font-size:12px;color:var(--ticket-muted)}.ticket-basic-content{padding:6px 20px 16px}.ticket-basic-fields{margin:0}.ticket-basic-fields>div{display:grid;grid-template-columns:108px minmax(0,1fr);gap:14px;border-bottom:1px dashed #e5edf6;padding:13px 0;line-height:1.65}.ticket-basic-fields>div:last-child{border:0}.ticket-basic-fields dt{color:var(--ticket-muted)}.ticket-basic-fields dd{margin:0;overflow-wrap:anywhere;min-width:0}.ticket-basic-fields small{display:block;color:var(--ticket-muted);font-size:11px;margin-top:5px}.ticket-contact{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.ticket-contact button{border:0;background:none;color:#245bc4;padding:0 5px;cursor:pointer;min-height:24px}.ticket-category,.ticket-impact{display:inline-block;background:#eef5ff;color:#245bc4;padding:2px 9px;border-radius:3px}.ticket-impact.urgent{background:#fff0f1;color:#b42335}.ticket-description-field p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.85;margin:0;background:#f6f8fb;border-radius:6px;padding:11px 12px}.ticket-muted{color:var(--ticket-muted)}
.ticket-detail-right{display:flex;flex-direction:column;gap:16px;min-width:0}.ticket-progress-card{display:flex;flex-direction:column;min-height:310px}.ticket-progress-content{padding:16px 22px 4px;max-height:330px;overflow:auto;scrollbar-width:thin;flex:1}.ticket-activity-list{margin:0;padding:0 0 0 10px;list-style:none;border-left:1px solid #dbe6f4}.ticket-activity-list li{padding:0 0 18px 13px;position:relative}.ticket-activity-list li::before{content:'';position:absolute;left:-15px;top:4px;width:6px;height:6px;border:2px solid #2868ec;background:white;border-radius:50%}.ticket-activity-list time{font-size:12px;color:#365a91}.ticket-activity-title{padding:7px 10px;margin-top:8px;background:#edf4ff;color:#245bc4;border-radius:3px;font-weight:600;display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap}.ticket-activity-title.comment{background:#eef8f6;color:#246e60}.ticket-activity-title small{font-size:11px;font-weight:400}.ticket-activity-list p{margin:8px 0 0;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.85}.ticket-card-links{border-top:1px solid var(--ticket-line);padding:9px 20px;color:var(--ticket-muted);font-size:11px;line-height:1.7}.ticket-card-links>div{display:flex;gap:14px;flex-wrap:wrap}.ticket-card-links :deep(.el-button){margin:0;min-height:30px}
.ticket-hours-card{display:flex;flex:1;flex-direction:column}.ticket-hours-content{flex:1;min-width:0}.ticket-empty{padding:24px 20px;color:var(--ticket-muted);margin:0;line-height:1.7}.ticket-hours-scroll{overflow:auto;max-height:305px;scrollbar-width:thin}.ticket-hours-table{border-collapse:collapse;width:100%;font-size:12px;table-layout:fixed;min-width:660px;text-align:left}.ticket-hours-table th{font-weight:500;color:#526781;background:#f7f9fc;padding:10px 9px}.ticket-hours-table td{padding:12px 9px;vertical-align:top;line-height:1.8;border-top:1px solid #e7edf5;overflow-wrap:anywhere}.ticket-hours-table th:nth-child(1){width:76px}.ticket-hours-table th:nth-child(2){width:144px}.ticket-hours-table th:nth-child(3),.ticket-hours-table th:nth-child(4),.ticket-hours-table th:nth-child(5){width:90px}.ticket-hours-table th:last-child{width:170px}.ticket-hours-table small,.ticket-time-separator{display:block;color:var(--ticket-muted)}.ticket-hours-table small{font-size:11px}.ticket-hours-emphasis{color:#245bc4;font-weight:600}.ticket-work-note{white-space:pre-wrap}.ticket-hours-footer{background:#f8faff;border-top:1px solid var(--ticket-line);padding:12px 18px;font-size:11px;color:var(--ticket-muted);line-height:1.75}.ticket-hours-total{display:flex;gap:6px 14px;flex-wrap:wrap}.ticket-hours-total strong{color:#245bc4;font-size:12px}.ticket-hours-footer p{margin:6px 0}.ticket-hours-pagination{display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap}.ticket-hours-pagination :deep(.el-button){margin:0}
.ticket-supporting-card{margin-top:16px}.ticket-supporting-card summary{cursor:pointer;padding:15px 20px;font-weight:600;line-height:1.7}.ticket-supporting-card summary span{font-weight:400;font-size:12px;color:var(--ticket-muted);margin-left:12px}.ticket-supporting-content{padding:0 20px 20px}.ticket-overview-bottom{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:12px 16px;margin-top:16px;flex-wrap:wrap}.ticket-overview-bottom>span,.ticket-record-explanation{font-size:12px;color:var(--ticket-muted);line-height:1.7}.ticket-record-explanation{margin:9px 2px 0}.ticket-overview button:focus-visible,.ticket-overview summary:focus-visible,.ticket-hours-scroll:focus-visible{outline:2px solid #2563eb;outline-offset:3px}
@media(min-width:1100px){.ticket-overview-grid{height:clamp(480px,calc(100dvh - 370px),660px)}.ticket-basic-card{display:flex;flex-direction:column}.ticket-basic-content{min-height:0;overflow:auto;scrollbar-width:thin;flex:1}.ticket-basic-fields>div{padding:8px 0}.ticket-detail-right{min-height:0}.ticket-progress-card{flex:1;min-height:0}.ticket-progress-content{max-height:none;min-height:0}.ticket-hours-card{min-height:0}.ticket-hours-content{min-height:0;overflow:auto}.ticket-hours-scroll{max-height:none}.ticket-stage-card{padding-top:18px}.ticket-overview .ticket-breadcrumb button{min-height:24px!important;padding:0!important;border:0!important;background:transparent!important;box-shadow:none!important}.ticket-overview .ticket-contact button{height:auto!important;min-height:24px!important;padding:0 5px!important;border:0!important;background:transparent!important;box-shadow:none!important}}
@media(max-width:1000px){.ticket-overview-grid{grid-template-columns:minmax(0,1fr)}.ticket-basic-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));column-gap:24px}.ticket-basic-fields .ticket-description-field{grid-column:1/-1}.ticket-stage-card{padding:18px 16px 12px}.ticket-stage-list{flex-wrap:wrap;row-gap:18px}.ticket-stage-list li,.ticket-stage-list li:last-child{flex:0 0 calc((100% - 24px)/3)}.ticket-stage-list li:nth-child(3)::after{display:none}.ticket-stage-list li:last-child::after{display:none}}
@media(max-width:600px){.ticket-heading-title{gap:8px}.ticket-heading-title h2{font-size:20px}.ticket-heading-receipt{flex-basis:100%}.ticket-overview-heading{gap:14px}.ticket-overview-actions{gap:8px;width:100%}.ticket-overview-actions :deep(.el-button){flex:1;min-height:44px;padding:9px 10px}.ticket-stage-list{gap:16px 8px}.ticket-stage-list li,.ticket-stage-list li:last-child{flex-basis:calc((100% - 16px)/3);gap:6px;font-size:12px}.ticket-stage-list li::after{display:none}.ticket-stage-number{width:23px;height:23px}.ticket-stage-card{padding:16px 12px 12px}.ticket-basic-fields{display:block}.ticket-basic-fields>div{grid-template-columns:86px minmax(0,1fr);gap:10px}.ticket-card-header{padding:12px 14px;gap:7px}.ticket-card-header>span{font-size:11px}.ticket-basic-content{padding:4px 14px 14px}.ticket-progress-content{padding:14px 16px 2px;max-height:420px}.ticket-activity-title small{display:block;flex-basis:100%}.ticket-card-links{padding:10px 14px}.ticket-card-links :deep(.el-button),.ticket-hours-pagination :deep(.el-button),.ticket-contact button{min-height:44px}.ticket-overview-bottom .ticket-overview-actions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}.ticket-supporting-card summary span{display:block;margin-left:0}.ticket-supporting-content{padding:0 14px 14px}.ticket-breadcrumb button{min-height:44px}.ticket-overview-bottom{padding:12px}}
</style>
