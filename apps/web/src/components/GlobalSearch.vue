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
const permitted=computed(()=>can('search:read')&&!switchingTenant.value)
watch(()=>[session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.user.role,contextGeneration.value,permitted.value],()=>{const s=session.value;open.value=false;navigationError.value='';workspace.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role}:null)},{immediate:true,flush:'sync'})
watch(open,value=>{if(!value){workspace.clear();navigationError.value=''}},{flush:'sync'})
watch(()=>route.fullPath,()=>{open.value=false},{flush:'sync'})
function show(){if(!permitted.value||open.value)return;open.value=true}
function shortcut(event:KeyboardEvent){if(permitted.value&&shouldFocusGlobalSearch(event)){event.preventDefault();void show()}}
onMounted(()=>window.addEventListener('keydown',shortcut))
onBeforeUnmount(()=>{window.removeEventListener('keydown',shortcut);workspace.dispose()})
async function visit(kind:'assets'|'tickets',id:string){if(navigating.value)return;navigating.value=true;navigationError.value='';try{const failure=await router.push(searchDestination(kind,id));if(isNavigationFailure(failure)&&!isNavigationFailure(failure,NavigationFailureType.duplicated))navigationError.value='跳转未完成，搜索结果已保留，请先处理当前页面的提示';else open.value=false}catch{navigationError.value='跳转失败，搜索结果已保留'}finally{navigating.value=false}}
</script>
<template>
 <el-button v-if="permitted" class="global-search-trigger" aria-label="打开全局搜索，快捷键 Control K 或 Command K" aria-keyshortcuts="Control+k Meta+k" title="搜索当前租户（Ctrl+K / ⌘K）" @click="show"><Search class="search-trigger-icon" aria-hidden="true" /><span>搜索<span class="search-trigger-hint">资产 / 报障记录</span></span><kbd aria-hidden="true">Ctrl / ⌘ K</kbd></el-button>
 <el-dialog v-model="open" title="搜索当前租户" width="680px" class="global-search-dialog" append-to-body :close-on-click-modal="false" :close-on-press-escape="!navigating" :show-close="!navigating" @opened="input?.focus()">
  <el-form @submit.prevent="workspace.search()"><el-form-item><el-input ref="input" :model-value="state.query" :disabled="navigating" clearable aria-label="当前租户搜索词" placeholder="资产名称 / 报障标题 / 受理编号" @update:model-value="workspace.change(String($event))"/></el-form-item><el-button native-type="submit" type="primary" :loading="state.busy" :disabled="navigating">搜索</el-button></el-form>
  <p class="search-boundary">目前可搜索有权查看的资产和报障登记。事件、自动化脚本和通知提醒尚未接入，暂时无法判断是否有相关告警。修改关键词会清除旧结果，请重新搜索。</p>
  <ApiErrorNotice :error="state.error"/>
  <el-alert v-if="navigationError" :title="navigationError" type="warning" :closable="false"/>
  <el-skeleton v-if="state.busy" :rows="4" animated/>
  <template v-if="state.result">
   <p class="search-observed" role="status" aria-live="polite">查询时间：{{displayTime(state.result.observed_at)}} · 仅含当前租户已接入且有权查看的数据</p>
   <section class="search-group"><h3>资产 <small>最多5条</small></h3><p v-if="!state.result.groups[0].returned">没有找到可查看的匹配资产，请尝试其他关键词。</p><ul v-else><li v-for="hit in state.result.groups[0].items" :key="hit.id"><el-button link :disabled="navigating" @click="visit('assets',hit.id)">{{hit.name}}</el-button><span>{{typeLabel(hit.type)}} · 资产记录</span></li></ul><p v-if="state.result.groups[0].has_more">还有更多匹配资产，可到资产管理页继续查询。</p></section>
   <section class="search-group unavailable"><h3>事件 <small>最多4条</small></h3><p>事件来源尚未接入，暂时无法查询，也无法判断是否有事件。</p></section>
   <section class="search-group"><h3>工单（当前仅报障登记） <small>最多3条</small></h3><p>{{state.result.groups[2].source_scope==='OWN_REPORT_REGISTRATIONS'?'仅搜索本人提交的报障登记':'搜索当前租户内有权查看的报障登记'}}</p><p v-if="!state.result.groups[2].returned">没有找到可查看的匹配报障，请尝试其他关键词。</p><ul v-else><li v-for="hit in state.result.groups[2].items" :key="hit.id"><el-button link :disabled="navigating" @click="visit('tickets',hit.id)">{{hit.title}}</el-button><span>{{hit.receipt_no}} · 已登记，正式处理状态待确认</span></li></ul><p v-if="state.result.groups[2].has_more">还有更多匹配登记，可到报障与受理页继续查询。</p></section>
   <section class="search-group unavailable"><h3>自动化脚本 <small>最多3条</small></h3><p>脚本来源尚未接入，暂时无法查询。本搜索不执行脚本。</p></section>
  </template>
  <p v-else-if="!state.busy&&!state.error">输入资产名称、报障标题或受理编号，点击“搜索”或按 Enter 查看结果。</p>
  <template #footer><el-button :disabled="navigating" @click="open=false">关闭并清除搜索</el-button></template>
 </el-dialog>
</template>
<style scoped>
.global-search-trigger{min-height:44px;padding:8px 12px}kbd{margin-left:8px;border:1px solid #c9d4e2;border-radius:4px;padding:0 5px;color:#596d88}.search-boundary,.search-observed,.search-group p{color:#536276;line-height:1.6;font-size:13px}.search-group{border-top:1px solid #e2e9f2;padding:15px 0;overflow-wrap:anywhere}.search-group h3{display:flex;justify-content:space-between;gap:12px}.search-group small{color:#73839a;font-weight:400}.search-group ul{list-style:none;padding:0;margin:0}.search-group li{display:flex;flex-direction:column;align-items:flex-start;padding:8px 0;gap:3px}.search-group li span{font-size:12px;color:#687991}.search-group :deep(.el-button){white-space:normal;text-align:left;min-height:44px}.unavailable{background:#f8fafc;padding:15px 10px}.global-search-dialog :deep(.el-input__wrapper){min-height:44px}@media(max-width:700px){.global-search-trigger{padding:6px 8px}.global-search-trigger kbd{display:none}.search-group h3{flex-wrap:wrap}.search-group small{font-size:12px}}
</style>
<style>
.el-dialog.global-search-dialog{display:flex;flex-direction:column;max-height:calc(100dvh - 48px);margin-block:24px}
.global-search-dialog .el-dialog__body{min-height:0;overflow:auto;overscroll-behavior:contain}
.global-search-dialog .el-dialog__header,.global-search-dialog .el-dialog__footer{flex-shrink:0}
@media(max-width:700px){.el-dialog.global-search-dialog{width:calc(100% - 24px)!important;max-height:calc(100dvh - 24px);margin-top:12px!important;margin-bottom:12px;padding:18px}}
</style>
