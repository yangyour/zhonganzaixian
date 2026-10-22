<script setup lang="ts">
import '../styles/adminPrototype.css'
import {computed,nextTick,onBeforeUnmount,ref,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {session,contextGeneration,switchingTenant,can} from '../stores/session'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import {createTenantInventoryReader,emptyTenantInventory,tenantInventoryColumns,tenantInventoryCell} from '../utils/tenantInventory'
const root=ref<HTMLElement|null>(null),detailHeading=ref<HTMLElement|null>(null),pagingStatus=ref<HTMLElement|null>(null)
let disposed=false
const state=shallowRef(emptyTenantInventory()),selectedId=ref<string|null>(null)
const reader=createTenantInventoryReader(api,value=>{state.value=value;if(value.busy||!value.page)selectedId.value=null})
const permitted=computed(()=>session.value?.mode==='remote_test'&&session.value?.user.role==='padmin'&&can('tenants:read_inventory')&&!switchingTenant.value)
const scope=computed(()=>JSON.stringify([session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,session.value?.mode,contextGeneration.value,permitted.value,(session.value?.allowed_tenants??[]).map(t=>t.id).sort()]))
watch(scope,()=>{const s=session.value;selectedId.value=null;reader.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role}:null);if(permitted.value)void reader.refresh()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{disposed=true;reader.dispose()})
const selected=computed(()=>state.value.page?.items.find(row=>row.id===selectedId.value)??null)
async function read(action:'refresh'|'next'|'previous'|'retry'){
 if(disposed||!permitted.value||state.value.busy)return
 const owner=scope.value;await reader[action]();await nextTick();if(!disposed&&owner===scope.value)pagingStatus.value?.focus()
}
async function openDetail(id:string){if(!state.value.page?.items.some(row=>row.id===id))return;const owner=scope.value;selectedId.value=id;await nextTick();if(!disposed&&owner===scope.value&&selectedId.value===id)detailHeading.value?.focus()}
async function closeDetail(){const id=selectedId.value,owner=scope.value;selectedId.value=null;await nextTick();if(!disposed&&owner===scope.value&&id)root.value?.querySelector<HTMLButtonElement>(`button[data-tenant-id="${id}"]`)?.focus()}
const total = (key: 'registered_asset_count' | 'registered_site_count' | 'active_member_count') => state.value.page ? state.value.page.items.reduce((sum, row) => sum + BigInt(row[key]), 0n).toString() : '—'
</script>
<template>
 <section ref="root" class="tenant-inventory-page admin-prototype" :aria-busy="state.busy">
  <h1 class="page-title-sr">租户资源台账</h1>
  <div class="content-toolbar inventory-toolbar"><span class="field-help">{{ state.page ? `本页 ${state.page.items.length} 个租户 · 时间为北京时间` : '已授权租户资源' }}</span><div class="content-actions"><el-button :disabled="!permitted" :loading="state.busy" @click="read('refresh')">重新读取第一页</el-button></div></div>
  
  <ApiErrorNotice :error="state.error"/>
  <el-button v-if="state.canRetry&&permitted" :disabled="state.busy" @click="read('retry')">重试原分页请求</el-button>
  <p v-if="!permitted" role="status">当前成员无租户登记资源读取权限</p>
  <el-skeleton v-else-if="state.busy" :rows="6" animated/>
  <template v-else-if="state.page">
   
   <div class="inventory-scroll admin-table-scroll" tabindex="0" role="region" aria-label="租户登记资源12列表格，可横向滚动">
    <table class="admin-table"><caption>已授权租户资源台账快照</caption><thead><tr><th v-for="column in tenantInventoryColumns" :key="column.key" scope="col">{{column.label}}</th></tr></thead><tbody><tr v-for="row in state.page.items" :key="row.id"><td v-for="column in tenantInventoryColumns" :key="column.key"><button v-if="column.key==='name'" class="tenant-name" :data-tenant-id="row.id" @click="openDetail(row.id)">{{row.name}}</button><span v-else>{{tenantInventoryCell(row,column.key).text}}</span><small v-if="['name','created'].includes(column.key)">{{tenantInventoryCell(row,column.key).detail}}</small><span v-else class="inventory-cell-hint" :title="tenantInventoryCell(row,column.key).detail">{{ column.key === 'assets' ? '含草稿登记' : column.key === 'members' ? '有效成员关系' : column.key === 'sites' ? '登记站点' : '待接入配置' }}</span></td></tr><tr v-if="!state.page.items.length"><td :colspan="tenantInventoryColumns.length">本页无授权登记记录；不表示平台没有租户</td></tr></tbody></table>
   </div>
  </template>
  <div v-if="permitted" class="inventory-pagination"><el-button :disabled="state.busy||!state.canPrevious" @click="read('previous')">上一页</el-button><span ref="pagingStatus" tabindex="-1" role="status">{{state.busy?'正在读取…':state.error?'读取未完成；可重试原分页':(state.paged?'后续页':'第一页')+' · 每页15条'}}</span><el-button :disabled="state.busy||!state.page?.has_more" @click="read('next')">下一页</el-button></div>
  <section v-if="selected" class="inventory-detail admin-editor-panel" aria-label="选中租户登记快照详情">
   <div class="detail-heading"><h2 ref="detailHeading" tabindex="-1">{{selected.name}} · 租户台账快照详情</h2><el-button @click="closeDetail">关闭详情</el-button></div>
   <dl><template v-for="column in tenantInventoryColumns" :key="column.key"><dt>{{column.label}}</dt><dd>{{tenantInventoryCell(selected,column.key).text}}<small>{{tenantInventoryCell(selected,column.key).detail}}</small></dd></template></dl>
  </section>
 </section>
</template>
<style scoped>
.inventory-cell-hint{display:block;font-size:12px;color:#526276;margin-top:3px}.scope-note{color:#46566c;line-height:1.7;font-size:11px;margin:10px 0}.inventory-scroll{overflow:auto;border:1px solid #d5dfec;border-radius:5px;background:#fff}.inventory-scroll:focus-visible{outline:3px solid #245daf;outline-offset:2px}table{min-width:1450px;width:100%;border-collapse:collapse}caption{text-align:left;padding:12px 16px;font-weight:600;background:#fff;font-size:13px}th,td{text-align:left;vertical-align:top;border-bottom:1px solid #dce4ee;padding:12px;min-width:90px}th{background:#f1f5fb;font-size:13px}td{font-size:14px}small{display:block;color:#526276;overflow-wrap:anywhere;line-height:1.6;margin-top:3px;max-width:170px;font-size:10px}.tenant-name{min-width:140px;min-height:44px;background:none;border:0;padding:0;color:#1655a2;text-align:left;font:inherit;font-weight:600;max-width:200px;white-space:normal;cursor:pointer;overflow-wrap:break-word}.tenant-name:hover{text-decoration:underline;text-underline-offset:3px}.tenant-name:focus-visible{outline:2px solid #245daf}.inventory-pagination{display:flex;align-items:center;justify-content:flex-end;gap:12px;flex-wrap:wrap;margin:0;padding:12px 18px;background:#fff;border:1px solid #e2e8f0;border-top:0}.inventory-detail{background:white;padding:18px;border:1px solid #d5dfec;border-radius:8px}.detail-heading{display:flex;justify-content:space-between;gap:12px;align-items:center}.inventory-detail dl{display:grid;grid-template-columns:minmax(140px,1fr) 3fr;gap:10px}.inventory-detail dt{font-weight:600}.inventory-detail dd{margin:0;overflow-wrap:anywhere}.admin-prototype :deep(.el-button){min-height:44px}@media(max-width:700px){.tenant-inventory-page :deep(.el-button),.tenant-name{min-height:44px}.page-heading,.detail-heading{flex-direction:column;align-items:stretch;gap:12px}.inventory-detail dl{grid-template-columns:1fr}.inventory-detail dd{margin-bottom:12px}.inventory-pagination{justify-content:space-between}}
.inventory-detail h2:focus-visible,.inventory-pagination [tabindex]:focus-visible{outline:2px solid #245daf;outline-offset:3px}</style>
