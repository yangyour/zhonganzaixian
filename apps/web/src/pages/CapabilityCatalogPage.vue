<script setup lang="ts">
import { computed,onBeforeUnmount,shallowRef,watch } from 'vue'
import { api } from '../api/client'
import { can,contextGeneration,session,switchingTenant } from '../stores/session'
import { createCapabilityCatalogReader,type CatalogState } from '../utils/capabilityCatalogReader'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
const state=shallowRef<CatalogState>({current:null,busy:false,error:null})
const reader=createCapabilityCatalogReader(api,value=>{state.value=value})
const permitted=computed(()=>session.value?.user.role==='padmin'&&can('configurations:read')&&!switchingTenant.value)
watch(()=>[session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,contextGeneration.value,permitted.value],()=>{const s=session.value;reader.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role}:null);if(permitted.value)void reader.refresh()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>reader.dispose())
const dimensionLabel=(id:string)=>state.value.current?.dimensions.find(d=>d.id===id)?.label??id
function printReview(){if(permitted.value&&state.value.current&&!state.value.busy)window.print()}
</script>
<template>
 <section class="capability-catalog-page">
  <div class="page-heading"><div><span class="eyebrow">Q09 / TECHNICAL REVIEW</span><h1>代码能力声明核对</h1><p>四类角色 · 五个合同资源维度 · 当前代码目录</p></div><div class="catalog-actions non-print"><el-button :loading="state.busy" @click="reader.refresh()">重新读取</el-button><el-button :disabled="!state.current||state.busy" @click="printReview">打印核对页</el-button></div></div>
  <el-alert title="不是双方签字的有效权限矩阵" type="warning" :closable="false">此页只说明当前代码中声明了哪些能力，不包含实际人员授权，也不能用于判定具体对象是否可访问。不会创建角色、修改权限或发布配置。</el-alert>
  <ApiErrorNotice :error="state.error"/>
  <el-skeleton v-if="state.busy" :rows="6" animated/>
  <template v-if="state.current">
   <div class="catalog-facts"><p>目录范围：代码声明，不是当前租户人员绑定</p><p>当前 {{state.current.entry_count}} 项能力声明 · 项目角色运行：{{state.current.configuration_driven_authorization ? '已接入项目权限' : '未启用'}} · 客户批准：未核验</p><p>生效客户矩阵：未知（null） · 客户/供应商签字：未收集</p><p class="catalog-hash">声明映射SHA256：{{state.current.declaration_hash}}</p><p>摘要只校验能力ID、维度与声明向量，不是签字、审批证据或授权令牌。</p></div>
   <h2>五维目录</h2><ul class="catalog-dimensions"><li v-for="dimension in state.current.dimensions" :key="dimension.id"><strong>{{dimension.label}}</strong>：{{dimension.capability_ids.length}} 项声明<span v-if="!dimension.capability_ids.length">；当前无声明条目，不代表该业务维度已完成或不存在</span></li></ul>
   <p>“已声明/未声明”仅为代码目录事实，不替代当前成员、租户、资源关系与各接口独立守卫。Q09岗位映射、审批回避/替补和实际人员范围仍待双方确认。</p>
   <div class="catalog-table-wrap"><table class="catalog-table"><caption>当前代码声明目录（非生效权限配置）</caption><thead><tr><th scope="col">能力与说明</th><th scope="col">资源维度</th><th v-for="role in state.current.roles" :key="role.id" scope="col">{{role.label}}<small>{{role.id}}</small></th></tr></thead><tbody><tr v-for="entry in state.current.entries" :key="entry.id"><th scope="row">{{entry.label}}<small>{{entry.id}}</small></th><td>{{entry.dimension_ids.map(dimensionLabel).join('、')}}</td><td v-for="role in state.current.roles" :key="role.id"><span :class="entry.declared_by_role[role.id]?'declared':'undeclared'">{{entry.declared_by_role[role.id]?'已声明':'未声明'}}</span></td></tr></tbody></table></div>
   <p class="catalog-footer">配置登记键：{{state.current.configuration_key}} · CONFIG_PENDING。项目自定义角色在授权范围内生效；客户正式岗位矩阵、双方签字和合同验收仍需单独确认。</p>
  </template>
  <p v-else-if="!state.busy&&!state.error">尚未读取目录，不显示空矩阵或授权结论。</p>
 </section>
</template>
<style scoped>
.catalog-actions{display:flex;gap:10px;flex-wrap:wrap}.catalog-actions :deep(.el-button){min-height:44px}.catalog-facts{margin:20px 0;padding:18px;border:1px solid #dce4ee;border-radius:10px;background:white}.catalog-facts p{margin:4px 0;color:#526276}.catalog-hash{font-family:monospace;overflow-wrap:anywhere;font-size:12px}.catalog-dimensions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;padding-left:20px}.catalog-table-wrap{overflow-x:auto;border:1px solid #dce4ee;background:white}.catalog-table{width:100%;min-width:780px;border-collapse:collapse;font-size:13px}.catalog-table caption{text-align:left;padding:12px;font-weight:600}.catalog-table th,.catalog-table td{padding:12px;border:1px solid #dce4ee;vertical-align:top;text-align:left}.catalog-table thead{background:#f4f7fb}.catalog-table small{display:block;color:#64758b;font-size:11px;margin-top:4px;overflow-wrap:anywhere}.declared{font-weight:600;color:#244f87}.undeclared{color:#6b7788}.catalog-footer{margin:16px 0;color:#526276;overflow-wrap:anywhere}@media(max-width:700px){.page-heading{flex-direction:column;align-items:stretch;gap:12px}.catalog-dimensions{grid-template-columns:1fr}.catalog-facts{padding:12px}}
</style>
<style>
@media print{body:has(.capability-catalog-page) .sidebar,body:has(.capability-catalog-page) .topbar,body:has(.capability-catalog-page) .environment-strip,body:has(.capability-catalog-page) .app-footer,.capability-catalog-page .non-print{display:none!important}body:has(.capability-catalog-page) .main-layout{margin-left:0!important}.capability-catalog-page .catalog-table-wrap{overflow:visible}.capability-catalog-page .catalog-table{min-width:0;font-size:9px}.capability-catalog-page .catalog-table th,.capability-catalog-page .catalog-table td{padding:5px;overflow-wrap:anywhere}.capability-catalog-page .catalog-table small{font-size:8px}.capability-catalog-page .catalog-table thead{display:table-header-group}.capability-catalog-page tr{break-inside:avoid}}
</style>
