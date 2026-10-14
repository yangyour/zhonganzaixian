<script setup lang="ts">
import '../styles/adminPrototype.css'
import { computed,onBeforeUnmount,ref,shallowRef,watch } from 'vue'
import { api } from '../api/client'
import { can,contextGeneration,session,switchingTenant } from '../stores/session'
import { createCapabilityCatalogReader,type CatalogState } from '../utils/capabilityCatalogReader'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const selectedDimension=ref('all'), searchText=ref('')
const state=shallowRef<CatalogState>({current:null,busy:false,error:null})
const reader=createCapabilityCatalogReader(api,value=>{state.value=value})
const permitted=computed(()=>session.value?.user.role==='padmin'&&can('configurations:read')&&!switchingTenant.value)
watch(()=>[session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,contextGeneration.value,permitted.value],()=>{const s=session.value;reader.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role}:null);if(permitted.value)void reader.refresh()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>reader.dispose())
const dimensionLabel=(id:string)=>state.value.current?.dimensions.find(d=>d.id===id)?.label??id
function printReview(){if(permitted.value&&state.value.current&&!state.value.busy)window.print()}
const visibleEntries=computed(()=>state.value.current?.entries.filter(entry=>(selectedDimension.value==='all'||entry.dimension_ids.some(id=>id===selectedDimension.value))&&(!searchText.value||(entry.id+' '+entry.label).toLowerCase().includes(searchText.value.toLowerCase())))??[])
watch(permitted,()=>{selectedDimension.value='all';searchText.value=''})
</script>
<template>
 <section class="capability-catalog-page admin-prototype">
  <div class="page-heading"><div><span class="eyebrow">Q09 / TECHNICAL REVIEW</span><h1>代码能力声明核对</h1><p>四类角色 · 五个合同资源维度 · 当前代码目录</p></div><div class="catalog-actions non-print"><el-button :loading="state.busy" @click="reader.refresh()">重新读取</el-button><el-button :disabled="!state.current||state.busy" @click="printReview">打印核对页</el-button></div></div>
  <el-alert title="不是双方签字的有效权限矩阵" type="warning" :closable="false">此页只说明当前代码中声明了哪些能力，不包含实际人员授权，也不能用于判定具体对象是否可访问。不会创建角色、修改权限或发布配置。</el-alert>
  <ApiErrorNotice :error="state.error"/>
  <el-skeleton v-if="state.busy" :rows="6" animated/>
  <template v-if="state.current">
   <div class="admin-stats"><div class="admin-stat"><span>能力声明</span><strong>{{ state.current.entry_count }}</strong><small>当前代码目录</small></div><div class="admin-stat"><span>资源维度</span><strong>{{ state.current.dimensions.length }}</strong><small>合同资源维度</small></div><div class="admin-stat"><span>业务角色</span><strong>{{ state.current.roles.length }}</strong><small>声明向量 · 非人员授权</small></div><div class="admin-stat"><span>客户正式矩阵</span><strong>待确认</strong><small>生效矩阵null · 签字未收集</small></div></div>
   <details class="admin-explainer"><summary>声明范围与客户批准边界 · CONFIG_PENDING</summary><p>目录范围：代码声明，不是当前租户人员绑定。项目角色运行：{{state.current.configuration_driven_authorization ? '已接入项目权限' : '未启用'}} · 客户批准：未核验</p><p>生效客户矩阵：未知（null） · 客户/供应商签字：未收集</p><p class="catalog-hash">声明映射SHA256：{{state.current.declaration_hash}}</p><p>摘要只校验能力ID、维度与声明向量，不是签字、审批证据或授权令牌。“已声明/未声明”不替代当前成员、租户、资源关系与各接口独立守卫。Q09岗位映射、审批回避/替补和实际人员范围仍待双方确认。</p></details>
   <div class="admin-card"><div class="admin-toolbar identity-form"><label>搜索能力<input v-model="searchText" placeholder="能力名称 / ID" aria-label="搜索能力声明" /></label><label>资源维度<select v-model="selectedDimension" aria-label="能力资源维度"><option value="all">全部维度</option><option v-for="dimension in state.current.dimensions" :key="dimension.id" :value="dimension.id">{{ dimension.label }}（{{ dimension.capability_ids.length }}）</option></select></label><span class="muted">显示 {{ visibleEntries.length }} / {{ state.current.entry_count }} 项声明</span></div>
   <div class="catalog-table-wrap" role="region" tabindex="0" aria-label="能力声明表，可横向滚动"><table class="catalog-table admin-table"><caption>当前代码声明目录（非生效权限配置）</caption><thead><tr><th scope="col">能力与说明</th><th scope="col">资源维度</th><th v-for="role in state.current.roles" :key="role.id" scope="col">{{role.label}}<small>{{role.id}}</small></th></tr></thead><tbody><tr v-for="entry in visibleEntries" :key="entry.id"><th scope="row">{{entry.label}}<small>{{entry.id}}</small></th><td>{{entry.dimension_ids.map(dimensionLabel).join('、')}}</td><td v-for="role in state.current.roles" :key="role.id"><span :class="entry.declared_by_role[role.id]?'declared':'undeclared'">{{entry.declared_by_role[role.id]?'已声明':'未声明'}}</span></td></tr></tbody></table><p v-if="!visibleEntries.length" class="admin-empty">当前筛选没有能力声明；不代表该业务维度已完成或不存在。</p></div></div>
   <p class="catalog-footer">配置登记键：{{state.current.configuration_key}} · CONFIG_PENDING。项目自定义角色在授权范围内生效；客户正式岗位矩阵、双方签字和合同验收仍需单独确认。</p>
  </template>
  <p v-else-if="!state.busy&&!state.error">尚未读取目录，不显示空矩阵或授权结论。</p>
 </section>
</template>
<style scoped>
.admin-prototype .catalog-table-wrap{border:0}.admin-prototype .catalog-table th,.admin-prototype .catalog-table td{border-left:0;border-right:0;padding:12px 16px}.admin-prototype .catalog-table td:not(:first-child){vertical-align:middle}.admin-prototype .declared{display:inline-block;color:#2870c8;background:#edf5ff;padding:2px 8px;border-radius:3px;font-size:11px}.admin-prototype .undeclared{font-size:11px;color:#9aa5b4}.admin-prototype .catalog-actions :deep(.el-button){min-height:32px}

.catalog-actions{display:flex;gap:10px;flex-wrap:wrap}.catalog-actions :deep(.el-button){min-height:44px}.catalog-facts{margin:20px 0;padding:18px;border:1px solid #dce4ee;border-radius:10px;background:white}.catalog-facts p{margin:4px 0;color:#526276}.catalog-hash{font-family:monospace;overflow-wrap:anywhere;font-size:12px}.catalog-dimensions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;padding-left:20px}.catalog-table-wrap{overflow-x:auto;border:1px solid #dce4ee;background:white}.catalog-table{width:100%;min-width:780px;border-collapse:collapse;font-size:13px}.catalog-table caption{text-align:left;padding:12px;font-weight:600}.catalog-table th,.catalog-table td{padding:12px;border:1px solid #dce4ee;vertical-align:top;text-align:left}.catalog-table thead{background:#f4f7fb}.catalog-table small{display:block;color:#64758b;font-size:11px;margin-top:4px;overflow-wrap:anywhere}.declared{font-weight:600;color:#244f87}.undeclared{color:#6b7788}.catalog-footer{margin:16px 0;color:#526276;overflow-wrap:anywhere}@media(max-width:700px){.page-heading{flex-direction:column;align-items:stretch;gap:12px}.catalog-dimensions{grid-template-columns:1fr}.catalog-facts{padding:12px}}
</style>
<style>
@media print{body:has(.capability-catalog-page) .sidebar,body:has(.capability-catalog-page) .topbar,body:has(.capability-catalog-page) .environment-strip,body:has(.capability-catalog-page) .app-footer,.capability-catalog-page .non-print{display:none!important}body:has(.capability-catalog-page) .main-layout{margin-left:0!important}.capability-catalog-page .catalog-table-wrap{overflow:visible}.capability-catalog-page .catalog-table{min-width:0;font-size:9px}.capability-catalog-page .catalog-table th,.capability-catalog-page .catalog-table td{padding:5px;overflow-wrap:anywhere}.capability-catalog-page .catalog-table small{font-size:8px}.capability-catalog-page .catalog-table thead{display:table-header-group}.capability-catalog-page tr{break-inside:avoid}}
</style>
