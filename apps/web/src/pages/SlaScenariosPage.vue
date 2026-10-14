<script setup lang="ts">
import { onBeforeUnmount, reactive, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { session, contextGeneration } from '../stores/session'
import { createSlaScenarioWorkspace, type SlaInput, type SlaRow, type SlaState } from '../utils/slaScenarioWorkspace'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const draft = reactive<SlaInput>({ population_complete: null, tickets: [] })
const state = shallowRef<SlaState>({ input: { population_complete: null, tickets: [] }, result: null, busy: false, error: null })
const workspace = createSlaScenarioWorkspace(api, value => { state.value = value })
let serial = 0
watch(() => [session.value?.tenant.id, session.value?.user.id, session.value?.permission_version, contextGeneration.value], () => {
  const s = session.value; workspace.reset(s ? { tenantId: s.tenant.id, actorId: s.user.id, permissionVersion: s.permission_version } : null)
  draft.population_complete = null; draft.tickets = []; serial = 0
}, { immediate: true, flush: 'sync' })
watch(draft, () => workspace.change(draft), { deep: true, flush: 'sync' })
onBeforeUnmount(() => workspace.dispose())
function add() { if (draft.tickets.length >= 200) return; draft.tickets.push({ scenario_ref: `场景-${++serial}`, priority: null, exclusion: null, exclusion_approval: null, sla_snapshot: null, response_due_at: null, recovery_due_at: null, response_actual_at: null, recovery_actual_at: null }) }
function reference(row: SlaRow, key: 'sla_snapshot' | 'exclusion_approval', field: 'id' | 'version', value: string) {
  const current = row[key] ?? { id: '', version: 0 }; row[key] = field === 'id' ? { ...current, id: value } : { ...current, version: Number(value) }
}
const times = [{ key: 'response_due_at', label: '响应截止' }, { key: 'response_actual_at', label: '实际响应' }, { key: 'recovery_due_at', label: '恢复截止' }, { key: 'recovery_actual_at', label: '实际恢复' }] as const
const refs = [{ key: 'sla_snapshot', label: '假设SLA快照引用' }, { key: 'exclusion_approval', label: '假设排除审批引用' }] as const
const exclusions = [{ value: 'NONE', label: '明确不排除' }, { value: 'PENDING_APPROVAL', label: '申请待批（仍计入）' }, { value: 'REJECTED', label: '申请拒绝（仍计入）' }, { value: 'APPROVED', label: '假设已批准（须引用）' }, { value: 'UNKNOWN', label: '状态未知' }]
const labels: Record<string, string> = { CALCULATED: '显式场景已计算', UNRESOLVED: '输入仍有未解决项', NO_APPLICABLE: '无适用分母', MET: '场景内达标', NOT_MET: '场景内未达标', NOT_APPLICABLE: '不适用', UNKNOWN: '未知', POPULATION_NOT_COMPLETE: '总体完整性未声明为完整', APPLICABILITY_UNKNOWN: '部分场景适用性未知', TIMING_UNRESOLVED: '时间或快照输入未完整', NO_APPLICABLE_TICKETS: '没有适用场景，不显示0%或100%', PRIORITY_UNKNOWN: '优先级未知', PRIORITY_OUTSIDE_SLA: 'P4不在适用范围', EXCLUSION_UNKNOWN: '排除状态未知', APPROVAL_EVIDENCE_MISSING: '假设批准缺少引用', APPROVED_EXCLUSION: '仅按假设批准排除', SLA_SNAPSHOT_MISSING: '快照引用缺失', RESPONSE_DUE_MISSING: '响应截止缺失', RESPONSE_ACTUAL_MISSING: '实际响应缺失', RECOVERY_DUE_MISSING: '恢复截止缺失', RECOVERY_ACTUAL_MISSING: '实际恢复缺失', RESPONSE_LATE: '响应超时', RECOVERY_LATE: '恢复超时' }
</script>
<template>
  <section class="sla-scenarios">
    <div class="page-heading"><div><h1>SLA 显式对照</h1><p>逐条核对场景的响应与恢复时间，最多200条。</p></div><span class="workbench-status">假设场景 · 非正式达成率</span></div>
<details class="page-guidance"><summary>计算口径与说明</summary>    <el-alert title="不是实际项目达标率" type="warning" :closable="false">只计算你明确输入的假设场景，不读取实际工单全集，也不核验真实审批或生效快照。正式达成率、赔偿金额仍为未知（null）。</el-alert>
    <p>输入仅保留在当前页面，离开或切换租户后清除，不自动保存、不自动计算。修改输入立即清除旧结果。空白字段代表未知，不补当前时间、默认档位或4小时。</p>
</details>
      <ApiErrorNotice :error="state.error" />
    <div class="scenario-toolbar"><label>场景总体完整性 <select v-model="draft.population_complete" aria-label="场景总体完整性"><option :value="null">未知</option><option :value="true">我声明这组假设输入完整</option><option :value="false">不完整</option></select></label><el-button :disabled="draft.tickets.length >= 200" @click="add">新增场景</el-button><el-button type="primary" :loading="state.busy" @click="workspace.calculate()">只读对照</el-button></div>
    <p v-if="!draft.tickets.length">尚未添加场景；空集合不会计算为100%达标。</p>
    <details v-for="(row, index) in draft.tickets" :key="index" class="scenario-card" :open="index === draft.tickets.length - 1"><summary>{{row.scenario_ref || '未命名场景'}} · {{row.priority || '优先级未定'}}</summary>
      <div class="scenario-toolbar"><h2>场景 {{ index + 1 }}</h2><el-button text type="danger" @click="draft.tickets.splice(index, 1)">移除此输入</el-button></div>
      <div class="scenario-fields">
        <label>场景标签（不是工单ID）<input v-model="row.scenario_ref" aria-label="场景标签" /></label>
        <label>优先级<select v-model="row.priority" aria-label="场景优先级"><option :value="null">未知</option><option v-for="p in ['P1','P2','P3','P4']" :key="p" :value="p">{{ p }}</option></select></label>
        <label>排除状态假设<select v-model="row.exclusion" aria-label="场景排除状态"><option :value="null">未知（null）</option><option v-for="entry in exclusions" :key="entry.value" :value="entry.value">{{ entry.label }}</option></select></label>
        <label v-for="field in times" :key="field.key">{{ field.label }}<input :value="row[field.key] ?? ''" :aria-label="field.label" placeholder="含Z或±时区，例如2026-10-01T08:00:00+08:00" @input="row[field.key] = ($event.target as HTMLInputElement).value || null" /></label>
      </div>
      <div v-for="field in refs" :key="field.key" class="reference-fields"><strong>{{ field.label }}（不代表已验真）</strong><label>引用标识<input :value="row[field.key]?.id ?? ''" :aria-label="field.label + '标识'" @input="reference(row, field.key, 'id', ($event.target as HTMLInputElement).value)" /></label><label>显式版本<input type="number" min="1" step="1" :value="row[field.key]?.version || ''" :aria-label="field.label + '版本'" @input="reference(row, field.key, 'version', ($event.target as HTMLInputElement).value)" /></label><el-button text @click="row[field.key] = null">设为未知</el-button></div>
    </details>
    <section v-if="state.result" class="scenario-results" aria-live="polite">
      <h2>{{ labels[state.result.status] }}</h2><p v-if="state.result.reason">{{ labels[state.result.reason] }}</p>
      <p v-if="state.result.scenario_achievement"><strong>仅假设场景达成分数：{{ state.result.scenario_achievement.numerator }} / {{ state.result.scenario_achievement.denominator }}</strong>（精确分数，未选择财务舍入规则）</p>
      <p v-else>场景比率：未知或不适用；不输出0%或100%</p>
      <p>输入 {{ state.result.counts.input_total }} · 已知适用 {{ state.result.counts.known_applicable }} · 适用性未知 {{ state.result.counts.applicability_unknown }} · 达标 {{ state.result.counts.met }} · 未达标 {{ state.result.counts.not_met }} · 时间未定 {{ state.result.counts.timing_unresolved }} · 不适用 {{ state.result.counts.not_applicable }}</p>
      <p>审批：未验真 · 运行快照：未核验 · 正式达成率：未知（null） · 赔偿：配置待确认</p>
      <div class="scenario-result-row" v-for="item in state.result.items" :key="item.input.scenario_ref"><h3>{{ item.input.scenario_ref }}：{{ labels[item.scenario_outcome] }}</h3><p>响应：{{ labels[item.response_check] }} · 恢复：{{ labels[item.recovery_check] }}</p><p v-if="item.unapproved_exclusion_ignored">申请未获准排除，仍在适用分母内</p><p v-if="item.exclusion_applied_in_scenario">仅在假设中排除，不表示真实业务已批准</p><ul><li v-for="reason in item.reasons" :key="reason">{{ labels[reason] }}</li></ul></div>
    </section>
  </section>
</template>
<style scoped>
.sla-scenarios{min-width:0}.scenario-toolbar{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:16px 0}.scenario-toolbar h2{margin:0;flex:1}.scenario-card,.scenario-results{border:1px solid #dce4ee;border-radius:12px;background:white;padding:18px;margin:16px 0;overflow-wrap:anywhere}.scenario-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.scenario-fields label,.reference-fields label{display:flex;flex-direction:column;gap:6px;min-width:0}.reference-fields{display:flex;align-items:end;flex-wrap:wrap;gap:12px;padding-top:16px}.reference-fields strong{flex-basis:100%}input,select{box-sizing:border-box;max-width:100%;width:100%;min-height:40px;border:1px solid #c9d4e2;border-radius:6px;padding:8px;color:#20334c;background:#fff}.reference-fields label{flex:1;min-width:150px}.scenario-toolbar label{min-width:220px}p{line-height:1.65;color:#526176}input:focus,select:focus{outline:2px solid #377bdd;outline-offset:1px}@media(max-width:700px){.scenario-fields{grid-template-columns:1fr}.scenario-card,.scenario-results{padding:12px}.scenario-toolbar label{width:100%}.reference-fields label{flex-basis:100%;min-width:0}}
.scenario-card>summary{cursor:pointer;padding:4px 0;font-weight:600;min-height:36px}.scenario-result-row{display:grid;grid-template-columns:minmax(160px,1fr) minmax(180px,1fr) minmax(220px,2fr);gap:8px 20px;padding:16px 0;border-top:1px solid var(--mop-border)}.scenario-result-row h3,.scenario-result-row p,.scenario-result-row ul{margin:0}.scenario-result-row h3{font-size:14px}@media(max-width:760px){.scenario-result-row{grid-template-columns:1fr}.scenario-card>summary{min-height:44px}}
</style>
