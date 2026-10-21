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
  <div class="page-heading"><div><h1>系统能力与权限目录</h1><p>展示平台预置的 4 类基础角色、5 大业务资源维度与权限点映射基线</p></div><div class="catalog-actions non-print"><el-button :loading="state.busy" @click="reader.refresh()">刷新目录</el-button><el-button :disabled="!state.current||state.busy" @click="printReview">打印核对页</el-button></div></div>
  <el-alert title="系统内置权限点目录（只读核对视图）" type="info" :closable="false">本页展示平台代码登记的功能权限点与基础角色映射关系，仅供权限基线核对。实际人员授权与角色分配请在“用户与成员”或“角色与权限”中管理。</el-alert>
  <ApiErrorNotice :error="state.error"/>
  <el-skeleton v-if="state.busy" :rows="6" animated/>
  <template v-if="state.current">
   <div class="admin-stats"><div class="admin-stat"><span>能力声明</span><strong>{{ state.current.entry_count }}</strong><small>系统已注册权限点</small></div><div class="admin-stat"><span>资源维度</span><strong>{{ state.current.dimensions.length }}</strong><small>业务模块维度</small></div><div class="admin-stat"><span>业务角色</span><strong>{{ state.current.roles.length }}</strong><small>基础角色分类</small></div><div class="admin-stat"><span>客户正式矩阵</span><strong>待确认</strong><small>正式权限基线待双方确认</small></div></div>
   <details class="admin-explainer"><summary>权限目录适用范围与正式基线说明（配置项 Q09）</summary><p>目录范围：平台代码权限点声明 · 角色鉴权状态：{{state.current.configuration_driven_authorization ? '已接入项目权限校验' : '未启用'}} · 客户正式签字基线：待确认</p><p>正式岗位权限矩阵：待确认 · 双方签字确认件：待归档</p><p class="catalog-hash">目录映射摘要（SHA-256）：{{state.current.declaration_hash}}</p><p>接口访问控制均结合当前会话租户、成员角色及权限点实时校验。对应配置项 Q09 的正式岗位映射与审批回避规则待客户确认后归档生效。</p></details>
   <div class="admin-card"><div class="admin-toolbar identity-form"><label>搜索能力<input v-model="searchText" placeholder="能力名称 / ID" aria-label="搜索能力声明" /></label><label>资源维度<select v-model="selectedDimension" aria-label="能力资源维度"><option value="all">全部维度</option><option v-for="dimension in state.current.dimensions" :key="dimension.id" :value="dimension.id">{{ dimension.label }}（{{ dimension.capability_ids.length }}）</option></select></label><span class="muted">显示 {{ visibleEntries.length }} / {{ state.current.entry_count }} 项声明</span></div>
   <div class="catalog-table-wrap" role="region" tabindex="0" aria-label="能力声明表，可横向滚动"><table class="catalog-table admin-table"><caption>系统预置能力目录与基础角色映射</caption><thead><tr><th scope="col">能力与说明</th><th scope="col">资源维度</th><th v-for="role in state.current.roles" :key="role.id" scope="col">{{role.label}}<small>{{role.id}}</small></th></tr></thead><tbody><tr v-for="entry in visibleEntries" :key="entry.id"><th scope="row">{{entry.label}}<small>{{entry.id}}</small></th><td>{{entry.dimension_ids.map(dimensionLabel).join('、')}}</td><td v-for="role in state.current.roles" :key="role.id"><span :class="entry.declared_by_role[role.id]?'declared':'undeclared'">{{entry.declared_by_role[role.id]?'已声明':'未声明'}}</span></td></tr></tbody></table><p v-if="!visibleEntries.length" class="admin-empty">当前筛选条件下暂无匹配的能力项。</p></div></div>
   <p class="catalog-footer">关联配置项：{{state.current.configuration_key}}（待确认）。平台自定义角色在基础角色授权范围内生效，正式岗位权限矩阵以双方确认归档版本为准。</p>
  </template>
  <p v-else-if="!state.busy&&!state.error">暂未加载系统能力目录数据。</p>
 </section>
</template>
<style scoped>
.catalog-actions{display:flex;gap:10px;flex-wrap:wrap}.admin-prototype .catalog-actions :deep(.el-button){min-height:32px}.catalog-hash{font-family:monospace;overflow-wrap:anywhere;font-size:12px}.catalog-table-wrap{overflow-x:auto;border:0;background:white}.catalog-table{width:100%;min-width:780px;border-collapse:collapse;font-size:13px}.catalog-table caption{text-align:left;padding:12px 16px;font-weight:600;border-bottom:1px solid #edf1f6}.catalog-table th,.catalog-table td{padding:12px 16px;border-bottom:1px solid #edf1f6;vertical-align:top;text-align:left}.catalog-table td:not(:first-child){vertical-align:middle}.catalog-table thead{background:#f7f9fc}.catalog-table small{display:block;color:#64758b;font-size:11px;margin-top:4px;overflow-wrap:anywhere}.declared{display:inline-block;font-weight:600;color:#2870c8;background:#edf5ff;padding:2px 8px;border-radius:3px;font-size:11px}.undeclared{font-size:11px;color:#8a96a7}.catalog-footer{margin:16px 0;color:#526276;overflow-wrap:anywhere}@media(max-width:700px){.page-heading{flex-direction:column;align-items:stretch;gap:12px}.admin-prototype .catalog-actions :deep(.el-button){min-height:44px}}
</style>
<style>
@media print{body:has(.capability-catalog-page) .sidebar,body:has(.capability-catalog-page) .topbar,body:has(.capability-catalog-page) .environment-strip,body:has(.capability-catalog-page) .app-footer,.capability-catalog-page .non-print{display:none!important}body:has(.capability-catalog-page) .main-layout{margin-left:0!important}.capability-catalog-page .catalog-table-wrap{overflow:visible}.capability-catalog-page .catalog-table{min-width:0;font-size:9px}.capability-catalog-page .catalog-table th,.capability-catalog-page .catalog-table td{padding:5px;overflow-wrap:anywhere}.capability-catalog-page .catalog-table small{font-size:8px}.capability-catalog-page .catalog-table thead{display:table-header-group}.capability-catalog-page tr{break-inside:avoid}}
</style>
