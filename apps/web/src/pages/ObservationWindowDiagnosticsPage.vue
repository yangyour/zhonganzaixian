<script setup lang="ts">
import { useDemoExample } from '../utils/useDemoExample'
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
async function discard(){if(demoExample.pristine.value)return true;if(discarding.value)return false;if(!state.value.text)return true;const turn=contextKey.value;discarding.value=true;try{await ElMessageBox.confirm('输入和诊断结果只在当前页面内存中，未保存规则。继续会清除这些内容，确认继续？','保留观察窗诊断输入',{confirmButtonText:'清除并继续',cancelButtonText:'保留输入',type:'warning'});return !disposed&&turn===contextKey.value}catch{return false}finally{if(!disposed&&turn===contextKey.value)discarding.value=false}}
async function clear(){const turn=contextKey.value;if(permitted.value&&await discard()&&!disposed&&turn===contextKey.value){workspace.change('');await nextTick();editor.value?.focus()}}
const skeleton=JSON.stringify({condition:{comparison:null,threshold:null,unit:null},hold_seconds:null,max_gap_seconds:null,observed_at:null,samples:[]},null,2)
async function insertSkeleton(){const turn=contextKey.value;if(permitted.value&&await discard()&&!disposed&&turn===contextKey.value){workspace.change(skeleton);await nextTick();editor.value?.focus()}}
onBeforeRouteLeave(discard);const unregister=contextTransitionGuards.register(discard)
function beforeUnload(event:BeforeUnloadEvent){if(demoExample.pristine.value)return;if(state.value.text){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{disposed=true;unregister();workspace.dispose();window.removeEventListener('beforeunload',beforeUnload)})
const bytes=computed(()=>state.value.text.length>524288?'超过 524288':new TextEncoder().encode(state.value.text).length)
const statusLabels:Record<ObservationStatus,string>={CONFIG_PENDING:'候选参数未完整，结论未定',CONFLICT:'候选冲突，未自动改写',NO_SAMPLES:'未提供采样，不能判断',STALE_LATEST:'最新样本已超过明确间隔',UNKNOWN_LATEST:'最新样本未知或采集错误',NO_CURRENT_MATCH:'最新样本不满足候选条件',MATCHING_SPAN_NOT_REACHED:'已观察到的匹配跨度不足',MATCHING_SPAN_REACHED:'候选观察跨度达到，但不是实际恢复确认'}
const reasonLabels:Record<string,string>={CONDITION_INCOMPLETE:'比较符、阈值或单位未完整',HOLD_MISSING:'保持时长未提供',MAX_GAP_MISSING:'允许最大采样间隔未提供',NEGATIVE_HOLD:'保持时长为负数',NEGATIVE_MAX_GAP:'最大采样间隔为负数',UNIT_MISMATCH:'已知值的单位不一致，未换算'}
const demoExample = useDemoExample<unknown>('observation-window-diagnostics', permitted, () => state.value.text, async value => { workspace.change(JSON.stringify(value, null, 2)); await analyze() })
</script>
<template>
<section class="observation-page">
 <h1 class="page-title-sr">采样观察窗诊断</h1>
 <p v-if="demoExample.loaded.value" class="hint" role="note">[DEMO] 已加载独立测试样例；这些是假设输入，不代表真实业务或已生效配置。</p>
 <p v-if="!permitted">当前账号没有此诊断的使用权限。</p>
 <div v-else class="observation-layout">
  <section class="obs-panel input-panel">
   <form @submit.prevent="analyze" :aria-busy="state.busy"><div class="input-head content-toolbar"><label for="observation-input">显式观察窗 JSON 输入</label><span id="observation-help" class="byte-count">{{bytes}} / 524288 字节</span><div class="content-actions"><router-link to="/threshold-diagnostics">返回阈值条件诊断</router-link></div></div><textarea id="observation-input" ref="editor" :value="state.text" :disabled="discarding" aria-describedby="observation-help observation-errors" spellcheck="false" rows="14" @input="edit"/><div class="actions"><button type="submit" :disabled="!state.text||state.busy||discarding">{{state.busy?'诊断中…':'诊断明确输入'}}</button><button v-if="state.busy" type="button" @click="cancel">取消诊断</button><button type="button" :disabled="!state.text||discarding" @click="clear">清空输入与结果</button></div></form>
   <details><summary>输入格式说明</summary><p>condition 使用 GT / GE / LT / LE、精确小数字符串 threshold 和 unit；hold_seconds 与 max_gap_seconds 为秒的小数字符串，未确定用 null。observed_at 必须填写明确 UTC 时刻，最多毫秒，不自动填当前时间。</p><p>samples 最多1000条，时间严格递增且不晚于 observed_at。每条包含 recorded_at、state、value、unit；state 为 KNOWN / UNKNOWN / COLLECTOR_ERROR。KNOWN 必须有数值字符串和单位；未知/错误的 value 必须 null，不能补0。</p><p>空结构仅供填写，不发送请求，observed_at 的 null 必须补全后才能诊断。</p><button type="button" :disabled="discarding" @click="insertSkeleton">填入空结构（不发送）</button></details>
  </section>
  <section class="obs-panel result-panel" aria-label="诊断结果">
   <h2 class="result-title">诊断结果</h2>
   <div id="observation-errors"><ApiErrorNotice :error="state.error" :retrying="state.busy" retry-label="重试本次诊断" @retry="analyze" /></div>
   <p role="status" class="status-line" :class="{'sr-only':state.result}">{{state.result?statusLabels[state.result.status]:state.busy?'正在核对显式采样…':state.error?'诊断未完成，输入已保留，旧结果已清除。':'尚无诊断结果'}}</p>
   <div v-if="state.result" class="result" role="group" aria-label="候选观察窗诊断结果"><h3>{{statusLabels[state.result.status]}}</h3><ul v-if="state.result.reasons.length"><li v-for="reason in state.result.reasons" :key="reason">{{reasonLabels[reason]}}</li></ul><dl><div><dt>最新输入采样（UTC）</dt><dd>{{state.result.latest_sample_at??'未提供'}}</dd></div><div><dt>匹配跨度起点（UTC）</dt><dd>{{state.result.matching_span_started_at??'未定'}}</dd></div><div><dt>末尾匹配样本数</dt><dd>{{state.result.matching_sample_count??'未知'}}</dd></div><div><dt>已观察跨度（秒）</dt><dd>{{state.result.observed_span_seconds??'未知'}}</dd></div><div><dt>候选跨度是否达到</dt><dd>{{state.result.candidate_span_reached===null?'未定':state.result.candidate_span_reached?'达到候选跨度':'未达到'}}</dd></div><div><dt>真实恢复确认 / 规则发布 / 事件写入</dt><dd>均未执行，也未授权</dd></div></dl><p class="algo">算法 {{state.result.analysis_version}}</p><details><summary>核对本次已提交输入（{{state.result.inputs.samples.length}}条样本）</summary><pre tabindex="0" aria-label="本次已提交观察窗输入">{{JSON.stringify(state.result.inputs,null,2)}}</pre></details></div>
  </section>
 </div>
</section>
</template>
<style scoped>
.observation-page{min-width:0;color:#24374a}.observation-page header,.actions{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.observation-page p{font-size:13px;line-height:1.7;overflow-wrap:anywhere}
.observation-layout{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(0,1fr);gap:16px;align-items:start}
.obs-panel{min-width:0;padding:16px 18px;border:1px solid #dbe3ed;border-radius:10px;background:#fff;box-shadow:0 1px 2px rgba(15,23,42,.04)}
.input-head{display:flex;align-items:baseline;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:8px}.input-head label{font-weight:600;font-size:14px}.byte-count{font-size:12px;color:#5b6b7f;font-variant-numeric:tabular-nums}
.observation-page textarea{display:block;box-sizing:border-box;width:100%;max-width:100%;min-height:320px;padding:12px;border:1px solid #c4cfdc;border-radius:6px;font:13px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace;color:#24374a;background:#fbfcfe;resize:vertical}
.actions{justify-content:flex-start;margin-top:12px}
.observation-page button,.observation-page header a{min-height:36px;padding:6px 14px;border:1px solid #c4cfdc;background:#fff;color:#185695;border-radius:6px;font:inherit;font-size:13px;cursor:pointer;box-sizing:border-box}
.observation-page form button[type=submit]{background:#1f6fd1;border-color:#1f6fd1;color:#fff}.observation-page header a{display:inline-flex;align-items:center;text-decoration:none}
.observation-page button:disabled{opacity:.55;cursor:default}
.observation-page details{margin-top:12px;border-top:1px solid #edf1f6;padding-top:4px}.observation-page summary{min-height:36px;box-sizing:border-box;padding:8px 0;cursor:pointer;color:#185695;font-size:13px}.observation-page details p{color:#526176;font-size:12px}
.observation-page :focus-visible{outline:3px solid #176bb5;outline-offset:3px}
.result-title{font-size:15px;margin:0 0 8px}.status-line{margin:0 0 10px;color:#526176}
.result h3{font-size:16px;margin:0 0 8px}.result ul{margin:0 0 12px;padding-left:18px;font-size:13px;color:#7a4b00}
.result dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:0}.result dl>div{padding:10px 12px;border:1px solid #e3eaf3;border-radius:8px;background:#f8fafd}.result dt{font-weight:500;font-size:12px;color:#5b6b7f}.result dd{margin:4px 0 0;overflow-wrap:anywhere;font-weight:600;font-size:14px}
.algo{color:#5b6b7f;font-size:12px!important;margin:10px 0 0}
.result pre{max-height:320px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;background:#f1f5f9;padding:12px;font-size:12px;border-radius:6px}
@media(max-width:1100px){.observation-layout{grid-template-columns:1fr}}
@media(max-width:700px){.result dl{grid-template-columns:1fr}.observation-page header{align-items:stretch}.observation-page textarea{font-size:14px;min-height:240px}.observation-page button,.observation-page header a,.observation-page summary{min-height:44px}}
</style>
