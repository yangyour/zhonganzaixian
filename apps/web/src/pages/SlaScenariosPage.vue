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
    <div class="page-heading proto-page-head"><div><h1>SLA 显式对照 <span class="proto-badge">{{ session?.tenant.name }}</span></h1><p>只读计算工具 · 当前租户授权 · 最多200条</p></div></div>
    <el-alert title="不是实际项目达标率" type="warning" :closable="false">只计算你明确输入的假设场景，不读取实际工单全集，也不核验真实审批或生效快照。正式达成率、赔偿金额仍为未知（null）。</el-alert>
    <div class="proto-kpis"><div><span>正式项目达成率</span><b>未知</b><small>实际工单全集尚未接入</small></div><div><span>假设输入场景</span><b>{{ draft.tickets.length }}</b><small>仅当前页面输入</small></div><div><span>场景内达标 / 未达标</span><b>{{ state.result ? state.result.counts.met + ' / ' + state.result.counts.not_met : '—' }}</b><small>明确计算后显示</small></div><div><span>赔偿金额</span><b>待配置</b><small>财务口径尚未确认</small></div></div>
    <details class="boundary-details"><summary>输入与计算口径</summary><p>输入仅保留在当前页面，离开或切换租户后清除，不自动保存、不自动计算。修改输入立即清除旧结果。空白字段代表未知，不补当前时间、默认档位或4小时。</p></details>
    <ApiErrorNotice :error="state.error" />
    <div class="scenario-workspace"><section class="proto-panel scenario-inputs"><div class="proto-section-head"><h2>假设场景</h2><span class="proto-sub">输入明确事实后只读对照</span></div>
    <div class="scenario-toolbar"><label>场景总体完整性 <select v-model="draft.population_complete" aria-label="场景总体完整性"><option :value="null">未知</option><option :value="true">我声明这组假设输入完整</option><option :value="false">不完整</option></select></label><el-button :disabled="draft.tickets.length >= 200" @click="add">新增场景</el-button><el-button type="primary" :loading="state.busy" @click="workspace.calculate()">只读对照</el-button></div>
    <div v-if="!draft.tickets.length" class="proto-empty"><span class="scenario-empty-symbol" aria-hidden="true">▤</span><strong>尚未添加场景</strong><p>新增假设输入后可逐项核对响应与恢复时间。空集合不会计算为100%达标。</p><el-button @click="add">添加第一个场景</el-button></div>
    <details v-for="(row, index) in draft.tickets" :key="index" class="scenario-card" open><summary>{{ row.scenario_ref || '场景 ' + (index + 1) }} <span class="proto-badge">{{ row.priority || '优先级未知' }}</span></summary>
      <div class="scenario-toolbar"><h2>场景 {{ index + 1 }}</h2><el-button text type="danger" @click="draft.tickets.splice(index, 1)">移除此输入</el-button></div>
      <div class="scenario-fields">
        <label>场景标签（不是工单ID）<input v-model="row.scenario_ref" aria-label="场景标签" /></label>
        <label>优先级<select v-model="row.priority" aria-label="场景优先级"><option :value="null">未知</option><option v-for="p in ['P1','P2','P3','P4']" :key="p" :value="p">{{ p }}</option></select></label>
        <label>排除状态假设<select v-model="row.exclusion" aria-label="场景排除状态"><option :value="null">未知（null）</option><option v-for="entry in exclusions" :key="entry.value" :value="entry.value">{{ entry.label }}</option></select></label>
        <label v-for="field in times" :key="field.key">{{ field.label }}<input :value="row[field.key] ?? ''" :aria-label="field.label" placeholder="含Z或±时区，例如2026-10-01T08:00:00+08:00" @input="row[field.key] = ($event.target as HTMLInputElement).value || null" /></label>
      </div>
      <div v-for="field in refs" :key="field.key" class="reference-fields"><strong>{{ field.label }}（不代表已验真）</strong><label>引用标识<input :value="row[field.key]?.id ?? ''" :aria-label="field.label + '标识'" @input="reference(row, field.key, 'id', ($event.target as HTMLInputElement).value)" /></label><label>显式版本<input type="number" min="1" step="1" :value="row[field.key]?.version || ''" :aria-label="field.label + '版本'" @input="reference(row, field.key, 'version', ($event.target as HTMLInputElement).value)" /></label><el-button text @click="row[field.key] = null">设为未知</el-button></div>
    </details>
    </section><aside class="proto-panel scenario-overview"><div class="proto-section-head"><h2>对照说明</h2></div><div class="scenario-guide"><h3>响应与恢复分别核对</h3><p>按你输入的明确截止时间和实际时间逐项比较，不采用默认时限。</p><h3>未知单独保留</h3><p>缺失时间、快照或适用性时保留未知，不计为正常或达标。</p><h3>审批与正式报告</h3><p>假设引用不代表真实审批；正式服务可用率、月报快照和赔偿仍待接入。</p></div></aside></div>
    <section v-if="state.result" class="scenario-results" aria-live="polite">
      <h2>{{ labels[state.result.status] }}</h2><p v-if="state.result.reason">{{ labels[state.result.reason] }}</p>
      <p v-if="state.result.scenario_achievement"><strong>仅假设场景达成分数：{{ state.result.scenario_achievement.numerator }} / {{ state.result.scenario_achievement.denominator }}</strong>（精确分数，未选择财务舍入规则）</p>
      <p v-else>场景比率：未知或不适用；不输出0%或100%</p>
      <p>输入 {{ state.result.counts.input_total }} · 已知适用 {{ state.result.counts.known_applicable }} · 适用性未知 {{ state.result.counts.applicability_unknown }} · 达标 {{ state.result.counts.met }} · 未达标 {{ state.result.counts.not_met }} · 时间未定 {{ state.result.counts.timing_unresolved }} · 不适用 {{ state.result.counts.not_applicable }}</p>
      <p>审批：未验真 · 运行快照：未核验 · 正式达成率：未知（null） · 赔偿：配置待确认</p>
      <div class="proto-table-wrap"><table class="proto-table"><thead><tr><th>场景</th><th>响应核对</th><th>恢复核对</th><th>对照结果</th><th>未决项 / 排除说明</th></tr></thead><tbody><tr v-for="item in state.result.items" :key="item.input.scenario_ref"><td>{{ item.input.scenario_ref }}</td><td>{{ labels[item.response_check] }}</td><td>{{ labels[item.recovery_check] }}</td><td><span class="proto-badge">{{ labels[item.scenario_outcome] }}</span></td><td><p v-if="item.unapproved_exclusion_ignored">申请未获准排除，仍在适用分母内</p><p v-if="item.exclusion_applied_in_scenario">仅在假设中排除，不表示真实业务已批准</p><ul><li v-for="reason in item.reasons" :key="reason">{{ labels[reason] }}</li></ul></td></tr></tbody></table></div>
    </section>
  </section>
</template>
<style scoped>
.sla-scenarios{max-width:1200px}.scenario-toolbar{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:16px 0}.scenario-toolbar h2{margin:0;flex:1}.scenario-card,.scenario-results{border:1px solid #dce4ee;border-radius:12px;background:white;padding:18px;margin:16px 0;overflow-wrap:anywhere}.scenario-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.scenario-fields label,.reference-fields label{display:flex;flex-direction:column;gap:6px;min-width:0}.reference-fields{display:flex;align-items:end;flex-wrap:wrap;gap:12px;padding-top:16px}.reference-fields strong{flex-basis:100%}input,select{box-sizing:border-box;max-width:100%;width:100%;min-height:40px;border:1px solid #c9d4e2;border-radius:6px;padding:8px;color:#20334c;background:#fff}.reference-fields label{flex:1;min-width:150px}.scenario-toolbar label{min-width:220px}p{line-height:1.65;color:#526176}input:focus,select:focus{outline:2px solid #377bdd;outline-offset:1px}@media(max-width:700px){.scenario-fields{grid-template-columns:1fr}.scenario-card,.scenario-results{padding:12px}.scenario-toolbar label{width:100%}.reference-fields label{flex-basis:100%;min-width:0}}
.sla-scenarios{max-width:none}.scenario-workspace{display:grid;grid-template-columns:minmax(0,2fr) minmax(270px,1fr);gap:12px;align-items:start;margin-top:12px}.scenario-toolbar{padding:12px 16px;margin:0;border-bottom:1px solid #edf1f7}.scenario-toolbar label{display:flex;gap:12px;align-items:center;font-size:12px;flex:1}.scenario-toolbar label select{width:auto}.scenario-toolbar h2{font-size:14px}.scenario-card{margin:12px;border-radius:3px;padding:0;border-color:#e7edf5}.scenario-card>summary{padding:14px 16px;cursor:pointer;background:#f8fbff;font-size:13px;color:#4677b4}.scenario-card>summary .proto-badge{margin-left:12px}.scenario-card .scenario-fields{grid-template-columns:repeat(2,minmax(0,1fr));padding:16px;font-size:12px}.scenario-card .reference-fields{padding:0 16px 16px;font-size:12px}.scenario-card .reference-fields strong{font-weight:500;color:#8b96a3}.sla-scenarios input,.sla-scenarios select{min-height:32px;border-radius:3px;padding:6px 8px;font-size:12px}.scenario-guide{padding:16px}.scenario-guide h3{font-size:13px;font-weight:500;margin:0 0 8px}.scenario-guide p{font-size:12px;color:#8b96a3;margin:0 0 22px;line-height:1.8}.scenario-empty-symbol{font-size:56px;color:#b7d4f2;line-height:1}.scenario-inputs .proto-empty{min-height:300px;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:12px;padding:36px 20px;text-align:center;font-size:13px}.scenario-inputs .proto-empty p{font-size:12px;color:#8b96a3;max-width:350px;margin:0}.scenario-inputs .proto-empty strong{font-weight:400;color:#7c8da0}.scenario-results{border-radius:3px;padding:18px}.scenario-results h2{font-size:14px}.scenario-results>p{font-size:12px}.scenario-results .proto-table-wrap{margin:16px -18px -18px}.sla-scenarios>.boundary-details{padding:10px 16px;background:#fff;border:1px solid #edf1f7;font-size:11px;color:#8b96a3;cursor:pointer}.boundary-details p{font-size:12px}@media(max-width:1000px){.scenario-workspace{grid-template-columns:1fr}}@media(max-width:760px){.scenario-card .scenario-fields{grid-template-columns:1fr}.sla-scenarios input,.sla-scenarios select{min-height:44px}}
</style>
