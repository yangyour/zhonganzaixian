<script setup lang="ts">
import {computed,nextTick,onUnmounted,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {ElMessageBox} from 'element-plus'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {createObservationWorkspace,emptyObservationState,type ObservationState,type ObservationStatus} from '../utils/observationWindowWorkspace'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const state=shallowRef<ObservationState>(emptyObservationState()),discarding=ref(false),editor=ref<HTMLTextAreaElement|null>(null)
const workspace=createObservationWorkspace(api,value=>state.value=value)
const permitted=computed(()=>session.value?.mode==='remote_test'&&session.value.user.role==='padmin'&&can('configurations:read')&&!switchingTenant.value)
const contextKey=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,permitted.value]))
let disposed=false
watch(contextKey,()=>{discarding.value=false;const s=session.value;workspace.reset(s&&permitted.value?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role,canRead:true}:null)},{immediate:true,flush:'sync'})
function edit(event:Event){if(permitted.value&&!discarding.value)workspace.change((event.target as HTMLTextAreaElement).value)}
async function analyze(){if(!permitted.value||discarding.value)return false;return workspace.analyze()}
async function cancel(){workspace.cancel();await nextTick();editor.value?.focus()}
async function discard(){if(discarding.value)return false;if(!state.value.text)return true;const turn=contextKey.value;discarding.value=true;try{await ElMessageBox.confirm('输入和诊断结果只在当前页面内存中，未保存规则。继续会清除这些内容，确认继续？','保留观察窗诊断输入',{confirmButtonText:'清除并继续',cancelButtonText:'保留输入',type:'warning'});return !disposed&&turn===contextKey.value}catch{return false}finally{if(!disposed&&turn===contextKey.value)discarding.value=false}}
async function clear(){const turn=contextKey.value;if(permitted.value&&await discard()&&!disposed&&turn===contextKey.value){workspace.change('');await nextTick();editor.value?.focus()}}
const skeleton=JSON.stringify({condition:{comparison:null,threshold:null,unit:null},hold_seconds:null,max_gap_seconds:null,observed_at:null,samples:[]},null,2)
async function insertSkeleton(){const turn=contextKey.value;if(permitted.value&&await discard()&&!disposed&&turn===contextKey.value){workspace.change(skeleton);await nextTick();editor.value?.focus()}}
onBeforeRouteLeave(discard);const unregister=contextTransitionGuards.register(discard)
function beforeUnload(event:BeforeUnloadEvent){if(state.value.text){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{disposed=true;unregister();workspace.dispose();window.removeEventListener('beforeunload',beforeUnload)})
const bytes=computed(()=>state.value.text.length>524288?'超过 524288':new TextEncoder().encode(state.value.text).length)
const statusLabels:Record<ObservationStatus,string>={CONFIG_PENDING:'候选参数未完整，结论未定',CONFLICT:'候选冲突，未自动改写',NO_SAMPLES:'未提供采样，不能判断',STALE_LATEST:'最新样本已超过明确间隔',UNKNOWN_LATEST:'最新样本未知或采集错误',NO_CURRENT_MATCH:'最新样本不满足候选条件',MATCHING_SPAN_NOT_REACHED:'已观察到的匹配跨度不足',MATCHING_SPAN_REACHED:'候选观察跨度达到，但不是实际恢复确认'}
const reasonLabels:Record<string,string>={CONDITION_INCOMPLETE:'比较符、阈值或单位未完整',HOLD_MISSING:'保持时长未提供',MAX_GAP_MISSING:'允许最大采样间隔未提供',NEGATIVE_HOLD:'保持时长为负数',NEGATIVE_MAX_GAP:'最大采样间隔为负数',UNIT_MISMATCH:'已知值的单位不一致，未换算'}
</script>
<template>
<section class="observation-page">
 <header class="compact-page-toolbar"><div><h1 class="page-title-sr">采样观察窗诊断</h1></div><router-link to="/threshold-diagnostics">返回阈值条件诊断</router-link></header>
 <p class="boundary">已观察采样点的跨度不证明点与点之间连续正常。不会保存或发布规则，不修改事件状态；物理范围、实际设备和客户确认仍未验证。</p>
 <p v-if="!permitted">当前账号没有此诊断的使用权限。</p>
 <template v-else>
  <details><summary>输入格式与缺失值说明</summary><p>condition 使用 GT / GE / LT / LE、精确小数字符串 threshold 和 unit；hold_seconds 与 max_gap_seconds 为秒的小数字符串，未确定用 null。observed_at 必须填写明确 UTC 时刻，最多毫秒，不自动填当前时间。</p><p>samples 最多1000条，时间严格递增且不晚于 observed_at。每条包含 recorded_at、state、value、unit；state 为 KNOWN / UNKNOWN / COLLECTOR_ERROR。KNOWN 必须有数值字符串和单位；未知/错误的 value 必须 null，不能补0。</p><p>空结构仅供填写，不发送请求，observed_at 的 null 必须补全后才能诊断。</p><button type="button" :disabled="discarding" @click="insertSkeleton">填入空结构（不发送）</button></details>
  <form @submit.prevent="analyze" :aria-busy="state.busy"><label for="observation-input">显式观察窗 JSON 输入</label><textarea id="observation-input" ref="editor" :value="state.text" :disabled="discarding" aria-describedby="observation-help observation-errors" spellcheck="false" rows="14" @input="edit"/><p id="observation-help">UTF-8 {{bytes}} / 524288 字节技术上限。不会截断；编辑会取消旧请求并清除旧结果。数据仅在当前页面内存保留。</p><div class="actions"><button type="submit" :disabled="!state.text||state.busy||discarding">{{state.busy?'诊断中…':'诊断明确输入'}}</button><button v-if="state.busy" type="button" @click="cancel">取消诊断</button><button type="button" :disabled="!state.text||discarding" @click="clear">清空输入与结果</button></div></form>
  <div id="observation-errors"><ApiErrorNotice :error="state.error" :retrying="state.busy" retry-label="重试本次诊断" @retry="analyze" /></div>
  <p role="status">{{state.result?statusLabels[state.result.status]:state.busy?'正在核对显式采样…':state.error?'诊断未完成，输入已保留，旧结果已清除。':'尚无诊断结果。'}}</p>
  <section v-if="state.result" class="result" aria-label="候选观察窗诊断结果"><h2>{{statusLabels[state.result.status]}}</h2><ul v-if="state.result.reasons.length"><li v-for="reason in state.result.reasons" :key="reason">{{reasonLabels[reason]}}</li></ul><dl><div><dt>最新输入采样（UTC）</dt><dd>{{state.result.latest_sample_at??'未提供'}}</dd></div><div><dt>匹配跨度起点（UTC）</dt><dd>{{state.result.matching_span_started_at??'未定'}}</dd></div><div><dt>末尾匹配样本数</dt><dd>{{state.result.matching_sample_count??'未知'}}</dd></div><div><dt>已观察跨度（秒）</dt><dd>{{state.result.observed_span_seconds??'未知'}}</dd></div><div><dt>候选跨度是否达到</dt><dd>{{state.result.candidate_span_reached===null?'未定':state.result.candidate_span_reached?'达到候选跨度':'未达到'}}</dd></div><div><dt>真实恢复确认 / 规则发布 / 事件写入</dt><dd>均未执行，也未授权</dd></div></dl><p>算法：{{state.result.analysis_version}}。不向观察时刻外推无样本的尾部；没有采样不是健康零值。</p><details><summary>核对本次已提交输入（{{state.result.inputs.samples.length}}条样本）</summary><pre tabindex="0" aria-label="本次已提交观察窗输入">{{JSON.stringify(state.result.inputs,null,2)}}</pre></details></section>
 </template>
</section>
</template>
<style scoped>
.observation-page{min-width:0;color:#24374a}.observation-page header,.actions{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.observation-page p{font-size:13px;line-height:1.7;overflow-wrap:anywhere}.boundary,.result{padding:16px;border:1px solid #8293a6;border-radius:6px;background:#fff;margin:16px 0}.boundary{background:#fff8e9}.observation-page h2{font-size:18px}.observation-page label{display:block;font-weight:600;margin:16px 0 8px}.observation-page textarea{display:block;box-sizing:border-box;width:100%;max-width:100%;min-height:240px;padding:12px;border:1px solid #8293a6;border-radius:4px;font:13px/1.6 monospace;color:#24374a;resize:vertical}.actions{justify-content:flex-start}.observation-page button,.observation-page header a{min-height:44px;padding:8px 12px;border:1px solid #8293a6;background:#fff;color:#185695;border-radius:4px;font:inherit;cursor:pointer;box-sizing:border-box}.observation-page header a{display:inline-flex;align-items:center;text-decoration:underline}.observation-page button:disabled{opacity:.6;cursor:default}.observation-page summary{min-height:44px;box-sizing:border-box;padding:10px 0;cursor:pointer}.observation-page :focus-visible{outline:3px solid #176bb5;outline-offset:3px}.result dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.result dt{font-weight:600;font-size:13px}.result dd{margin:5px 0;overflow-wrap:anywhere}.result pre{max-height:320px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;background:#f1f5f9;padding:12px;font-size:12px}@media(max-width:700px){.result dl{grid-template-columns:1fr}.observation-page header{align-items:stretch}.observation-page textarea{font-size:14px}}
.observation-page :deep(.el-button){min-height:44px}.observation-page :deep(.api-error summary){min-height:44px;box-sizing:border-box;line-height:1.6;padding-block:10px}.observation-page :deep(.api-error :focus-visible){outline:3px solid #176bb5;outline-offset:3px}
</style>
