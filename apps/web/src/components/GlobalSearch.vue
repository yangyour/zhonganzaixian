<script setup lang="ts">
import { computed,onBeforeUnmount,onMounted,ref,shallowRef,watch } from 'vue'
import { isNavigationFailure,NavigationFailureType,useRoute,useRouter } from 'vue-router'
import { api } from '../api/client'
import { can,contextGeneration,session,switchingTenant } from '../stores/session'
import { createGlobalSearchWorkspace,searchDestination,shouldFocusGlobalSearch,type SearchState } from '../utils/globalSearchWorkspace'
import { displayTime,typeLabel } from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
import { Search } from '@element-plus/icons-vue'
const router=useRouter(),route=useRoute(),open=ref(false),navigating=ref(false),navigationError=ref(''),input=ref<{focus:()=>void}|null>(null)
const state=shallowRef<SearchState>({query:'',result:null,busy:false,error:null})
const workspace=createGlobalSearchWorkspace(api,value=>{state.value=value})
const capabilityScope=computed(()=>JSON.stringify([...new Set(session.value?.capabilities??[])].sort()))
const permitted=computed(()=>can('search:read')&&!switchingTenant.value)
// Equivalent /session refreshes must retain the modal if a later navigation guard blocks the move.
watch([()=>session.value?.tenant.id,()=>session.value?.user.id,()=>session.value?.permission_version,()=>session.value?.user.role,()=>capabilityScope.value,()=>contextGeneration.value,()=>permitted.value],()=>{const s=session.value;open.value=false;navigationError.value='';workspace.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role}:null)},{immediate:true,flush:'sync'})
watch(open,value=>{if(!value){workspace.clear();navigationError.value=''}},{flush:'sync'})
watch(()=>route.fullPath,()=>{open.value=false},{flush:'sync'})
function show(){if(!permitted.value||open.value)return;open.value=true}
function shortcut(event:KeyboardEvent){if(permitted.value&&shouldFocusGlobalSearch(event)){event.preventDefault();void show()}}
onMounted(()=>window.addEventListener('keydown',shortcut))
onBeforeUnmount(()=>{window.removeEventListener('keydown',shortcut);workspace.dispose()})
async function visit(kind:'assets'|'events'|'tickets',id:string){if(navigating.value)return;navigating.value=true;navigationError.value='';try{const failure=await router.push(searchDestination(kind,id));if(isNavigationFailure(failure)&&!isNavigationFailure(failure,NavigationFailureType.duplicated))navigationError.value='跳转未完成，搜索结果已保留，请先处理当前页面的提示';else open.value=false}catch{navigationError.value='跳转失败，搜索结果已保留'}finally{navigating.value=false}}
</script>
<template>
 <el-button v-if="permitted" class="global-search-trigger" aria-label="打开全局搜索，快捷键 Control K 或 Command K" aria-keyshortcuts="Control+k Meta+k" title="搜索当前租户（Ctrl+K / ⌘K）" @click="show"><Search class="search-trigger-icon" aria-hidden="true" /><span>搜索<span class="search-trigger-hint">资产 / 事件 / 报障记录</span></span><kbd aria-hidden="true">Ctrl / ⌘ K</kbd></el-button>
 <el-dialog v-model="open" title="搜索当前租户" width="680px" class="global-search-dialog" append-to-body :close-on-click-modal="false" :close-on-press-escape="!navigating" :show-close="!navigating" @opened="input?.focus()">
  <el-form class="global-search-form" @submit.prevent="workspace.search()"><el-form-item><el-input ref="input" :model-value="state.query" :disabled="navigating" clearable aria-label="当前租户搜索词" placeholder="资产名称 / 事件规则名称或代码 / 报障标题 / 受理编号" @update:model-value="workspace.change(String($event))"/></el-form-item><el-button native-type="submit" type="primary" :loading="state.busy" :disabled="navigating">搜索</el-button></el-form>
  <p class="search-boundary">检索范围限于当前租户已授权资源（支持资产、内部监控事件与报障记录）；修改关键词后请重新搜索。</p>
  <ApiErrorNotice :error="state.error"/>
  <el-alert v-if="navigationError" :title="navigationError" type="warning" :closable="false"/>
  <el-skeleton v-if="state.busy" :rows="4" animated/>
  <template v-if="state.result">
   <p class="search-observed" role="status" aria-live="polite">查询时间：{{displayTime(state.result.observed_at)}} · 当前租户授权数据范围</p>
   <section class="search-group"><h3>资产 <small>最多5条</small></h3><p v-if="!state.result.groups[0].returned">未找到匹配的资产，请尝试其他关键词。</p><ul v-else><li v-for="hit in state.result.groups[0].items" :key="hit.id"><el-button link :disabled="navigating" @click="visit('assets',hit.id)">{{hit.name}}</el-button><span>{{typeLabel(hit.type)}} · 资产记录</span></li></ul><p v-if="state.result.groups[0].has_more">还有更多匹配资产，可前往资产管理页继续查询。</p></section>
   <section class="search-group" :class="{unavailable:state.result.groups[1].status==='NOT_AUTHORIZED'}"><h3>监控事件 <small>最多4条</small></h3>
    <p v-if="state.result.groups[1].status==='NOT_AUTHORIZED'">当前角色没有内部监控事件读取权限。</p>
    <template v-else>
     <p>按当前租户内监控事件的规则名称或规则代码检索。</p>
     <p v-if="!state.result.groups[1].returned">未找到匹配的监控事件，请尝试其他关键词。</p>
     <ul v-else><li v-for="hit in state.result.groups[1].items" :key="hit.id"><el-button link :disabled="navigating" @click="visit('events',hit.id)">{{hit.rule_name}}</el-button><span>{{hit.rule_code}} · {{hit.severity}} · {{hit.monitor_state}} / {{hit.handling_state}} · ×{{hit.occurrence_count}} · {{displayTime(hit.last_occurred_at)}}</span></li></ul>
     <p v-if="state.result.groups[1].has_more">还有更多匹配事件，请细化关键词后重新搜索。</p>
    </template>
   </section>
   <section class="search-group"><h3>报障与工单 <small>最多3条</small></h3><p>{{state.result.groups[2].source_scope==='OWN_REPORT_REGISTRATIONS'?'仅检索本人提交的报障记录':'检索当前租户内有权查看的报障记录'}}</p><p v-if="!state.result.groups[2].returned">未找到匹配的报障记录，请尝试其他关键词。</p><ul v-else><li v-for="hit in state.result.groups[2].items" :key="hit.id"><el-button link :disabled="navigating" @click="visit('tickets',hit.id)">{{hit.title}}</el-button><span>{{hit.receipt_no}} · 已受理登记</span></li></ul><p v-if="state.result.groups[2].has_more">还有更多匹配记录，可前往报障与受理页继续查询。</p></section>
   <section class="search-group unavailable"><h3>自动化脚本 <small>最多3条</small></h3><p>自动化脚本检索待接入。</p></section>
  </template>
  <p v-else-if="!state.busy&&!state.error">输入资产名称、事件规则名称或代码、报障标题或受理编号，按 Enter 开始搜索。</p>
  <template #footer><el-button :disabled="navigating" @click="open=false">关闭</el-button></template>
 </el-dialog>
</template>
<style scoped>
.global-search-trigger{min-height:44px;padding:8px 12px}kbd{margin-left:8px;border:1px solid #c9d4e2;border-radius:4px;padding:0 5px;color:#596d88}.global-search-form{display:flex;align-items:center;gap:10px;margin-bottom:10px}.global-search-form :deep(.el-form-item){flex:1;margin-bottom:0}.search-boundary,.search-observed,.search-group p{color:#536276;line-height:1.6;font-size:13px}.search-group{border-top:1px solid #e2e9f2;padding:15px 0;overflow-wrap:anywhere}.search-group h3{display:flex;justify-content:space-between;gap:12px}.search-group small{color:#596d84;font-weight:400}.search-group ul{list-style:none;padding:0;margin:0}.search-group li{display:flex;flex-direction:column;align-items:flex-start;padding:8px 0;gap:3px}.search-group li span{font-size:12px;color:#536276}.search-group :deep(.el-button){white-space:normal;text-align:left;min-height:44px}.unavailable{background:#f8fafc;padding:15px 10px}.global-search-dialog :deep(.el-input__wrapper){min-height:44px}@media(max-width:700px){.global-search-trigger{padding:6px 8px}.global-search-trigger kbd{display:none}.search-group h3{flex-wrap:wrap}.search-group small{font-size:12px}}
</style>
<style>
.el-dialog.global-search-dialog{display:flex;flex-direction:column;max-height:calc(100dvh - 48px);margin-block:24px}
.global-search-dialog .el-dialog__body{min-height:0;overflow:auto;overscroll-behavior:contain}
.global-search-dialog .el-dialog__header,.global-search-dialog .el-dialog__footer{flex-shrink:0}
@media(max-width:700px){.el-dialog.global-search-dialog{width:calc(100% - 24px)!important;max-height:calc(100dvh - 24px);margin-top:12px!important;margin-bottom:12px;padding:18px}}
</style>
