<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { api, isCancelled } from '../api/client'
import { session, contextGeneration, switchingTenant } from '../stores/session'
import { capacityRuntimeFields, capacityImportedValues, capacityImportChanges, readCapacityRuntimeInputs, type CapacityRuntimeInputs, type CapacityImportDraft } from '../utils/capacityRuntimeInputs'
import ApiErrorNotice from './ApiErrorNotice.vue'
const emit=defineEmits<{ apply:[values:ReturnType<typeof capacityImportedValues>, checkedAt:string] }>()
const props=defineProps<{current?:CapacityImportDraft}>()
const snapshot=shallowRef<CapacityRuntimeInputs|null>(null),error=shallowRef<unknown>(null),busy=ref(false),now=ref(performance.now()),loaded=ref(0)
let epoch=0,abort:AbortController|null=null,alive=true
const scopeKey=computed(()=>JSON.stringify([session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,session.value?.capabilities.includes('capacity:estimate'),contextGeneration.value,switchingTenant.value]))
function clear(){epoch++;abort?.abort();abort=null;snapshot.value=null;error.value=null;busy.value=false}
watch(scopeKey,clear,{immediate:true,flush:'sync'})
const fresh=computed(()=>{const p=snapshot.value,elapsed=now.value-loaded.value;if(!p||elapsed>90000||elapsed<0)return false;return p.items.every(i=>i.status!=='ACTIVE'||!i.expires_at||Date.parse(i.expires_at)>Date.parse(p.checked_at)+elapsed)})
const changes=computed(()=>snapshot.value&&props.current?capacityImportChanges(snapshot.value,props.current):[])
const changedCount=computed(()=>changes.value.filter(row=>row.changed).length)
const clearedCount=computed(()=>changes.value.filter(row=>row.cleared).length)
async function load(){const s=session.value;if(!s||s.user.role!=='padmin'||!s.capabilities.includes('capacity:estimate')||switchingTenant.value)return;clear();const turn=epoch,requestedAt=performance.now();abort=new AbortController();busy.value=true;try{const raw=await api.request('/capacity/runtime-inputs',{signal:abort.signal});if(!alive||turn!==epoch)return;snapshot.value=readCapacityRuntimeInputs(raw,{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version});now.value=performance.now();loaded.value=requestedAt}catch(e){if(alive&&turn===epoch&&!isCancelled(e))error.value=e}finally{if(alive&&turn===epoch){busy.value=false;abort=null}}}
function apply(){now.value=performance.now();if(!fresh.value||!snapshot.value||busy.value)return;emit('apply',capacityImportedValues(snapshot.value),snapshot.value.checked_at)}
const statuses:Record<string,string>={ACTIVE:'有效',UNCONFIGURED:'未发布',SCHEDULED:'待生效',DISABLED:'已禁用',EXPIRED:'已到期',DEFINITION_CHANGED:'定义变化，需重审',INVALID_VALUE:'值无效',INCOMPATIBLE_MODEL:'超出当前模型支持范围'}
const timer=setInterval(()=>{now.value=performance.now()},1000)
onBeforeUnmount(()=>{alive=false;clearInterval(timer);clear()})
</script>
<template>
 <section class="capacity-runtime-inputs"><div class="panel-heading"><h2>导入已发布运行配置参数</h2><el-button :loading="busy" @click="load">读取当前运行配置</el-button></div><p>读取当前租户已发布的采样周期、数据保留期与容量余量参数并填入下方模型（主机规模、模型系数与站点负载请按评估场景填写）。</p><ApiErrorNotice :error="error"/>
 <template v-if="snapshot"><div class="capacity-runtime-table"><table><thead><tr><th>参数</th><th>发布值 / 版本</th><th>本次输入 / 状态</th></tr></thead><tbody><tr v-for="(item,i) in snapshot.items" :key="item.configuration_key"><td>{{capacityRuntimeFields[i]!.label}}</td><td>{{item.value??'—'}} {{item.value===null?'':item.unit==='ratio_0_to_1'?'比例':item.unit}}<small>{{item.publication_version===null?'无当前版本':`v${item.publication_version}`}}</small></td><td>{{item.input_value??'留空'}} · {{statuses[item.status]}}</td></tr></tbody></table></div><p>容量倍率 1.2 对应额外余量比例 0.2（当前模型支持 1—2 倍）。快照读取时间：{{snapshot.checked_at}}。</p>
 <section v-if="snapshot&&current" class="import-preview" aria-label="运行参数替换预览">
  <h3>填入前核对</h3>
  <p role="status">将修改 {{changedCount}} 项输入<span v-if="clearedCount">，其中 {{clearedCount}} 项现有输入将清空</span>。未列出的主机规模、模型系数和缓冲负载保持不变；不会自动开启缓冲计算。</p>
  <div class="capacity-runtime-table"><table><thead><tr><th>输入字段</th><th>当前输入</th><th>填入后</th><th>变化</th></tr></thead><tbody><tr v-for="row in changes" :key="row.key"><th scope="row">{{row.label}}</th><td>{{row.before===''?'未填写':row.before}}</td><td>{{row.after===''?'留空':row.after}}</td><td>{{row.cleared?'将清空':row.changed?'将替换':'保持不变'}}</td></tr></tbody></table></div>
 </section>
<el-button type="primary" :disabled="busy||!fresh" @click="apply">将本次参数填入估算</el-button><p v-if="!fresh" role="status">读取已超过90秒或有参数到期，请重新读取。</p><p>填入操作将更新对应表单字段（未发布、已禁用或超出模型范围的参数将保持留空）。导入后作为本次估算的参数快照，后续运行配置变更不会自动改写当前表单。</p></template>

 </section>
</template>
<style scoped>
.capacity-runtime-inputs{padding:18px;border:1px solid #e2e9f2;border-radius:8px;background:#fff;margin-top:14px;min-width:0;box-shadow:0 1px 2px rgba(15,23,42,.02)}.capacity-runtime-inputs p{font-size:12px;color:#6a798c;line-height:1.7}.capacity-runtime-table{max-width:100%;overflow-x:auto;border:1px solid #e7edf4;border-radius:6px;margin:10px 0 14px}table{width:100%;border-collapse:collapse;font-size:12px}th{background:#f6f8fc;color:#334155;font-weight:600}tbody tr{transition:background-color .15s ease}tbody tr:hover{background:#f8fbff}th,td{padding:10px 12px;text-align:left;border-bottom:1px solid #edf1f6;overflow-wrap:anywhere}.num-cell{font-variant-numeric:tabular-nums}.input-val{margin-right:8px;font-weight:500;color:#1e293b}.runtime-status-badge{display:inline-block;padding:2px 7px;border-radius:4px;font-size:11px;background:#f1f5f9;color:#475569;border:1px solid #e2e8f0}.runtime-status-badge.active{background:#ecfdf5;color:#047857;border-color:#a7f3d0}td small{display:block;color:#7d8998;margin-top:3px}.runtime-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:10px 0}.runtime-meta{font-size:12px;color:#64748b;font-variant-numeric:tabular-nums}.runtime-stale{color:#b45309!important;background:#fffbeb;border:1px solid #fde68a;padding:8px 12px;border-radius:6px}.runtime-footnote{margin-bottom:0}.panel-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}h2{font-size:15px;margin:0;color:#1e293b}@media(max-width:600px){.capacity-runtime-inputs{padding:12px}th,td{padding:8px 6px}.panel-heading{align-items:flex-start}}
</style>

