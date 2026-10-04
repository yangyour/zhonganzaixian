<script setup lang="ts">
import {computed,onBeforeUnmount,ref,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {session,contextGeneration,switchingTenant} from '../stores/session'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import {createTenantInventoryReader,emptyTenantInventory,tenantInventoryColumns,tenantInventoryCell} from '../utils/tenantInventory'
const state=shallowRef(emptyTenantInventory()),selectedId=ref<string|null>(null)
const reader=createTenantInventoryReader(api,value=>{state.value=value;if(value.busy||!value.page)selectedId.value=null})
const permitted=computed(()=>session.value?.user.role==='padmin'&&!switchingTenant.value)
watch(()=>[session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,contextGeneration.value,permitted.value],()=>{const s=session.value;selectedId.value=null;reader.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role}:null);if(permitted.value)void reader.refresh()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>reader.dispose())
const selected=computed(()=>state.value.page?.items.find(row=>row.id===selectedId.value)??null)
</script>
<template>
 <section class="tenant-inventory-page">
  <div class="page-heading"><div><span class="eyebrow">M2 / RECORDED INVENTORY</span><h1>租户登记资源</h1><p>仅展示本人在目标租户有平台管理员成员关系的记录</p></div><el-button :disabled="!permitted" :loading="state.busy" @click="reader.refresh()">重新读取第一页</el-button></div>
  <el-alert type="warning" title="登记数量不等于监控、配额或接入完成" :closable="false">运行状态、资源池、Item/NVPS、配额、保留期、ITSM和网络拓扑尚无已验证来源，保留未知；不显示虚构进度条、不新建租户或开通身份。</el-alert>
  <ApiErrorNotice :error="state.error"/>
  <p v-if="!permitted">当前成员无租户登记资源读取权限</p>
  <el-skeleton v-else-if="state.busy" :rows="6" animated/>
  <template v-else-if="state.page">
   <p class="scope-note">每次读取重新授权；本次 {{state.page.items.length}} 条，不是平台总数。分页之间数据可能变化；创建时间按北京时间显示，详情保留UTC原值。</p>
   <div class="inventory-scroll" tabindex="0" role="region" aria-label="租户登记资源12列表格，可横向滚动">
    <table><caption>本次授权范围内的登记快照</caption><thead><tr><th v-for="column in tenantInventoryColumns" :key="column.key" scope="col">{{column.label}}</th></tr></thead><tbody><tr v-for="row in state.page.items" :key="row.id"><td v-for="column in tenantInventoryColumns" :key="column.key"><button v-if="column.key==='name'" class="tenant-name" @click="selectedId=row.id">{{row.name}}</button><span v-else>{{tenantInventoryCell(row,column.key).text}}</span><small>{{tenantInventoryCell(row,column.key).detail}}</small></td></tr><tr v-if="!state.page.items.length"><td :colspan="tenantInventoryColumns.length">本页无授权登记记录；不表示平台没有租户</td></tr></tbody></table>
   </div>
  </template>
  <div v-if="permitted" class="inventory-pagination"><el-button :disabled="state.busy||!state.canPrevious" @click="reader.previous()">上一页</el-button><span>{{state.paged?'后续页':'第一页'}} · 每页15条</span><el-button :disabled="state.busy||!state.page?.has_more" @click="reader.next()">下一页</el-button></div>
  <section v-if="selected" class="inventory-detail" aria-label="选中租户登记快照详情">
   <div class="detail-heading"><h2>{{selected.name}} · 本次快照详情</h2><el-button @click="selectedId=null">关闭详情</el-button></div><p>与当前列表使用同一已读取记录，不是另一次实时查询。更新时请重新读取列表。</p>
   <dl><template v-for="column in tenantInventoryColumns" :key="column.key"><dt>{{column.label}}</dt><dd>{{tenantInventoryCell(selected,column.key).text}}<small>{{tenantInventoryCell(selected,column.key).detail}}</small></dd></template></dl>
  </section>
 </section>
</template>
<style scoped>
.tenant-inventory-page :deep(.el-button){min-height:44px}.scope-note{color:#46566c;line-height:1.7}.inventory-scroll{overflow:auto;border:1px solid #d5dfec;border-radius:8px;background:#fff}.inventory-scroll:focus-visible{outline:3px solid #245daf;outline-offset:2px}table{min-width:1850px;width:100%;border-collapse:collapse}caption{text-align:left;padding:14px;font-weight:600}th,td{text-align:left;vertical-align:top;border-bottom:1px solid #dce4ee;padding:12px;min-width:110px}th{background:#f1f5fb;font-size:13px}td{font-size:14px}small{display:block;color:#526276;overflow-wrap:anywhere;line-height:1.6;margin-top:6px}.tenant-name{min-height:44px;background:none;border:0;padding:0 8px;color:#1655a2;text-align:left;font:inherit;cursor:pointer;overflow-wrap:anywhere}.tenant-name:focus-visible{outline:2px solid #245daf}.inventory-pagination{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:18px 0}.inventory-detail{background:white;padding:18px;border:1px solid #d5dfec;border-radius:8px}.detail-heading{display:flex;justify-content:space-between;gap:12px;align-items:center}.inventory-detail dl{display:grid;grid-template-columns:minmax(140px,1fr) 3fr;gap:10px}.inventory-detail dt{font-weight:600}.inventory-detail dd{margin:0;overflow-wrap:anywhere}@media(max-width:700px){.page-heading,.detail-heading{flex-direction:column;align-items:stretch;gap:12px}.inventory-detail dl{grid-template-columns:1fr}.inventory-detail dd{margin-bottom:12px}.inventory-pagination{justify-content:space-between}}
</style>
