<script setup lang="ts">
import {computed,onUnmounted,reactive,ref,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {createOwnWorklogBrowser,emptyOwnWorklogBrowserState,type OwnWorklogBrowserState} from '../utils/ownWorklogBrowser'
import type {OwnWorklogContext,OwnWorklogFilters} from '../utils/ownWorklogBrowse'
import type {WorklogKind} from '../utils/worklogWorkspace'
import {displayTime} from '../utils/display'
import { summarizeWorklogPage, formatWorklogDuration } from '../utils/worklogPageSummary'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import OwnWorklogFacts from '../components/OwnWorklogFacts.vue'
const labels:Record<WorklogKind,string>={REMOTE:'远程处理',ONSITE:'现场处理',INSPECTION:'巡检',COMMUTE:'通勤'}
const form=reactive({ticket:'',kind:'',from:'',before:''}),dirty=ref(false),validation=ref(''),expanded=ref<number|null>(null)
let resetting=false,disposed=false,draftEpoch=0
const state=shallowRef<OwnWorklogBrowserState>(emptyOwnWorklogBrowserState()),reader=createOwnWorklogBrowser(api,s=>{state.value=s})
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&session.value.user.role==='msp'&&can('worklogs:read_own')&&!switchingTenant.value))
const contextKey=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,permitted.value]))
const pageSummary=computed(()=>summarizeWorklogPage(state.value.page?.items??null))
const newerThanList=computed(()=>state.value.selected&&state.value.page?.items.some(v=>v.id===state.value.selected!.id&&v.version<state.value.selected!.version))
function context():OwnWorklogContext|null{const s=session.value;return permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role,canRead:true}:null}
function filters():OwnWorklogFilters{return{ticket_id:form.ticket===''?null:form.ticket.toLowerCase(),kind:form.kind===''?null:form.kind as WorklogKind,started_from:form.from===''?null:form.from,started_before:form.before===''?null:form.before}}
async function apply(){if(disposed||!permitted.value)return false;const turn=draftEpoch,key=contextKey.value;validation.value='';expanded.value=null;const okay=await reader.apply(filters());if(disposed||turn!==draftEpoch||key!==contextKey.value)return false;if(okay)dirty.value=false;else if(state.value.error)validation.value='条件未通过或读取失败；旧结果已清除。起始范围须成对，不能指定他人或团队';return okay}
async function clearFilters(){if(!permitted.value||disposed)return false;Object.assign(form,{ticket:'',kind:'',from:'',before:''});return apply()}
async function refresh(){if(dirty.value||!permitted.value)return false;validation.value='';expanded.value=null;return reader.refresh()}
async function next(){if(dirty.value||!permitted.value)return false;expanded.value=null;return reader.next()}
async function show(id:string){expanded.value=null;return reader.show(id)}
async function moreHistory(){expanded.value=null;return reader.moreHistory()}
function closeDetails(){expanded.value=null;return reader.closeDetails()}
watch(()=>JSON.stringify(form),()=>{if(resetting||disposed)return;++draftEpoch;dirty.value=true;validation.value='';expanded.value=null;reader.invalidate()},{flush:'sync'})
watch(contextKey,()=>{++draftEpoch;resetting=true;Object.assign(form,{ticket:'',kind:'',from:'',before:''});resetting=false;dirty.value=false;validation.value='';expanded.value=null;reader.reset(disposed?null:context());if(permitted.value&&!disposed)void apply()},{immediate:true,flush:'sync'})
onUnmounted(()=>{disposed=true;reader.dispose()})
defineExpose({form,state,dirty,filters,apply,clearFilters,refresh,next,show,moreHistory,closeDetails})
</script>
<template>
 <section class="own-worklogs-page"><div class="page-heading proto-page-head"><div><h1>我的工时记录 <span class="proto-badge">{{ session?.tenant.name }}</span></h1><p>查询当前账号在本租户下登记的工单作业工时明细</p></div></div>
 <el-alert type="warning" :closable="false" show-icon title="展示工程师本人填报的原始作业工时；缺记录不默认0小时，成本与默认工时核算规则待配置。" />
 <p v-if="!permitted" class="panel no-access">当前没有本人记录读取权限，或租户正在切换；未展示旧数据</p>
 <template v-else>
  <div class="proto-kpis"><div><span>本页工时记录</span><b>{{ state.page ? state.page.items.length : '—' }}</b><small>仅统计当前查询页</small></div><div><span>本页时长合计</span><b class="worklog-duration-total">{{ pageSummary ? formatWorklogDuration(pageSummary.totalMillis) : '—' }}</b><small>按记录逐条相加，重叠区间不扣除</small></div><div><span>本页关联工单</span><b>{{ pageSummary?.ticketCount ?? '—' }}</b><small>当前页按工单去重</small></div><div><span>记录归属</span><b>本人</b><small>当前租户 · 人工填报</small></div></div>
  <form v-form-feedback class="panel own-filters" @submit.prevent="apply"><div class="proto-section-head"><h2>工时明细筛选</h2><span class="proto-sub">支持按关联工单、作业类型及开始时间范围筛选</span></div>
   <div class="filter-grid"><label>关联工单 ID（可选）<input v-model="form.ticket" aria-label="本人工时工单筛选" maxlength="36" placeholder="输入工单 UUID，留空表示不限" /></label><label>作业类型<select v-model="form.kind" aria-label="本人工时类型筛选"><option value="">全部类型</option><option v-for="(label,key) in labels" :key="key" :value="key">{{label}}</option></select></label><label>开始时间起（含）<input v-model="form.from" aria-label="本人工时开始下界" maxlength="35" placeholder="例如 2026-10-01T00:00:00+08:00" /></label><label>开始时间止（不含）<input v-model="form.before" aria-label="本人工时开始上界" maxlength="35" placeholder="需与起始时间同时填写，且晚于起始时间" /></label></div><details class="boundary-details"><summary>时间筛选口径说明</summary><p>留空默认查询本人全部历史工时。时间范围按“作业开始时刻”（前闭后开）匹配，跨界记录的完整时长不裁剪；查询结果統一按 Asia/Shanghai 时区展示。</p></details>
   <p v-if="dirty" class="query-dirty" aria-live="polite">条件已修改，旧结果已清除；请明确应用后读取</p><p v-if="validation" class="validation" role="alert">{{validation}}</p><div class="actions"><button type="submit" :disabled="Boolean(state.busy)">应用条件并读取</button><button type="button" :disabled="Boolean(state.busy)" @click="clearFilters">清除条件并读取</button><button type="button" :disabled="dirty||Boolean(state.busy)||!state.filters" @click="refresh">重新读取已应用条件</button></div>
  </form>
  <ApiErrorNotice :error="state.error" />
  <section class="panel own-results"><p v-if="!state.page" class="query-empty">{{state.busy==='list'?'正在读取，数量未知':dirty?'尚未应用新条件，数量未知':state.error?'读取未通过，数量未知':'尚未读取，数量未知'}}</p>
   <template v-else><details class="applied-filters boundary-details"><summary>当前生效筛选条件</summary><p>关联工单：{{state.page.filters.ticket_id??'不限'}} · 作业类型：{{state.page.filters.kind===null?'全部类型':labels[state.page.filters.kind]}}</p><p>开始时间范围 (UTC)：{{state.page.filters.started_from??'不限'}} → {{state.page.filters.started_before??'不限'}}（前闭后开）</p></details><p class="query-count">当前页共 {{state.page.items.length}} 条记录 · {{state.paged?'后续页':'第 1 页'}}（下方分类汇总仅统计当前页）</p>
    <section v-if="pageSummary" class="worklog-breakdown" aria-label="本页工时分类汇总"><div class="proto-section-head"><h2>本页类型分布</h2><span class="proto-sub">原始填报区间汇总</span></div><div class="worklog-kind-grid"><article v-for="group in pageSummary.groups" :key="group.kind"><span>{{ group.label }}</span><strong>{{ group.count }} 条</strong><small>{{ group.count ? formatWorklogDuration(group.millis) : '本页无此类型' }}</small></article></div><p class="field-help">分类汇总仅统计当前页记录，不跨页累加、不裁剪筛选边界；存在重叠的记录会重复计入上述合计。</p></section>
    <div class="proto-table-wrap"><table class="worklog-cards proto-table"><thead><tr><th>作业类型 / 版本</th><th>开始时间</th><th>结束时间</th><th>完整时长</th><th>关联工单</th><th>操作</th></tr></thead><tbody><tr v-for="r in state.page.items" :key="r.id"><td><span class="proto-badge">{{labels[r.kind]}}</span><span class="proto-sub">v{{r.version}} · 人工填报</span></td><td>{{displayTime(r.started_at)}}</td><td>{{displayTime(r.ended_at)}}</td><td>{{formatWorklogDuration(BigInt(r.elapsed_millis))}}<span class="proto-sub">{{r.elapsed_millis}} ms · {{r.elapsed_millis===0?'实填 0 时长':'完整作业区间'}} · 成本待配置</span></td><td><span class="worklog-id">{{r.ticket_id}}</span></td><td><button class="proto-link" type="button" :disabled="Boolean(state.busy)" @click="show(r.id)">查看当前记录与历史</button></td></tr></tbody></table></div>
    <p v-if="!state.page.items.length" class="no-matches">当前筛选条件下暂无匹配记录；不生成默认工时或零成本</p><div class="actions"><button type="button" :disabled="dirty||Boolean(state.busy)||!state.page.has_more" @click="next">下一页本人记录</button><span class="hint">按当前最新修订分页读取</span></div>
   </template>
  </section>
  <section v-if="state.busy==='detail'||state.selected" class="panel current-worklog"><div class="actions"><h2>工时记录详情</h2><button type="button" @click="closeDetails">关闭当前记录</button></div><p v-if="!state.selected">正在加载当前记录与修订历史…</p><template v-else><p>{{state.selected.id}} · v{{state.selected.version}}</p><small>关联工单 {{state.selected.ticket_id}} · 填报人 {{state.selected.owner_id}}</small><p v-if="newerThanList" class="validation">当前回读版本比列表更新，筛选结果可能已变化；请重新读取查询，不会拼接旧历史</p><OwnWorklogFacts :value="state.selected" /></template></section>
  <section v-if="state.history" class="panel own-history"><h2>修订历史记录</h2><p v-if="state.historyRestarted" class="validation">记录已更新，已重新从最新历史开始读取，没有沿用旧边界</p><article v-for="r in state.history.items" :key="r.version"><div class="actions"><h3>v{{r.version}} · {{r.action==='CREATE'?'首次填报':'修订记录'}}</h3><button type="button" :aria-expanded="expanded===r.version" @click="expanded=expanded===r.version?null:r.version">{{expanded===r.version?'收起该版原文':'展开该版原文'}}</button></div><p>{{displayTime(r.recorded_at)}}（Asia/Shanghai）· {{r.elapsed_millis}} ms</p><OwnWorklogFacts v-if="expanded===r.version" :value="r" /></article><button type="button" :disabled="Boolean(state.busy)||!state.history.has_more" @click="moreHistory">加载更早修订</button></section>
 </template></section>
</template>
<style scoped>
.own-worklogs-page{min-width:0}
.own-worklogs-page .panel{padding:20px;margin-top:18px;min-width:0}
.own-worklogs-page h2{font-size:14px;margin:0 0 12px;overflow-wrap:anywhere}
.own-worklogs-page h3{font-size:13px;margin:10px 0}
.own-worklogs-page p{line-height:1.7;overflow-wrap:anywhere}
.own-worklogs-page small{display:block;font:12px monospace;overflow-wrap:anywhere;color:#607086;margin:6px 0}
.own-worklogs-page input,.own-worklogs-page select,.own-worklogs-page button{min-height:32px;border-radius:3px;padding:6px 10px;font-size:12px;border:1px solid #cbd7e5;background:#fff;color:#294665;font-family:inherit}
.own-worklogs-page input,.own-worklogs-page select{width:100%;box-sizing:border-box}
.own-worklogs-page button{cursor:pointer}
.own-worklogs-page button[type=submit]{background:#2d79f6;color:#fff;border-color:#2d79f6}
.own-worklogs-page button:disabled{opacity:.5;cursor:not-allowed}
.own-worklogs-page button:focus-visible,.own-worklogs-page input:focus-visible,.own-worklogs-page select:focus-visible{outline:3px solid #176bb5;outline-offset:2px}
.own-worklogs-page .own-filters{margin-top:12px;padding:0 18px 14px;border-radius:3px;border-bottom:0;margin-bottom:0}
.own-filters .proto-section-head{margin:0 -18px 14px}
.filter-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}
.filter-grid label{display:grid;gap:7px;min-width:0;font-size:13px}
.actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.hint,.query-empty{color:#607086;font-size:13px}
.query-dirty,.validation{color:#a04913}
.own-worklogs-page .own-results{margin-top:0;padding:0;border-radius:0 0 3px 3px}
.own-results .query-empty,.own-results .no-matches{min-height:210px;display:grid;place-items:center;padding:20px}
.own-results .applied-filters,.own-results .query-count,.own-results>.actions{margin:0;padding:12px 18px;border-left:0;border-bottom:1px solid #edf1f7;font-size:12px}
.worklog-cards{display:table;width:100%;border-collapse:collapse;margin:0;list-style:none;padding:0}
.worklog-id{font-size:11px;color:#8090a2;display:block;max-width:180px;overflow-wrap:anywhere}
.own-worklogs-page .proto-link{border:0;padding:0;color:#2d79f6;background:none}
.own-worklogs-page .current-worklog,.own-worklogs-page .own-history{margin-top:12px;border-radius:3px}
.own-history article{padding:12px 0;border-bottom:1px solid #dce5f0}
.own-history>button{margin-top:16px}
.own-worklogs-page .boundary-details{font-size:11px;color:#8b96a3;margin:10px 0;cursor:pointer}
.own-worklogs-page .boundary-details p{color:#607086}
.worklog-duration-total{font-size:18px!important;line-height:1.5!important;overflow-wrap:anywhere}
.worklog-breakdown{margin:18px 0;padding:16px;border:1px solid #e0e8f1;border-radius:6px;background:#fafcff}
.worklog-kind-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}
.worklog-kind-grid article{background:#fff;padding:14px;border:1px solid #e3eaf2;border-radius:5px}
.worklog-kind-grid span,.worklog-kind-grid strong,.worklog-kind-grid small{display:block;overflow-wrap:anywhere}
.worklog-kind-grid strong{margin:8px 0;font-size:20px}
.worklog-kind-grid small{line-height:1.7;color:#53667d}
@media(max-width:1100px){.filter-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:760px){.filter-grid,.worklog-cards{grid-template-columns:1fr}.own-worklogs-page .panel{padding:14px}.own-worklogs-page :is(input,select,button){min-height:44px}.worklog-kind-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.worklog-breakdown{padding:12px}.worklog-duration-total{font-size:16px!important}}
</style>
