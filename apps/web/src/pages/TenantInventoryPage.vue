<script setup lang="ts">
import '../styles/adminPrototype.css'
import {computed,nextTick,onBeforeUnmount,ref,shallowRef,useId,watch} from 'vue'
import {api} from '../api/client'
import {session,contextGeneration,switchingTenant,can} from '../stores/session'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import TableScrollTools from '../components/TableScrollTools.vue'
import {useHorizontalScroll} from '../utils/useHorizontalScroll'
import {createTenantInventoryReader,emptyTenantInventory,tenantInventoryColumns,tenantInventoryCell,type TenantInventoryColumn} from '../utils/tenantInventory'
const root=ref<HTMLElement|null>(null),detailHeading=ref<HTMLElement|null>(null),pagingStatus=ref<HTMLElement|null>(null),tableViewport=ref<HTMLElement|null>(null)
const tableId=useId()
const {overflow,atStart,atEnd,scroll,onKeydown}=useHorizontalScroll(tableViewport)
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
// One projection per row for the table; the detail list reads the same projection directly.
const tableRows=computed(()=>state.value.page?.items.map(row=>({row,cells:Object.fromEntries(tenantInventoryColumns.map(column=>[column.key,tenantInventoryCell(row,column.key)])) as Record<TenantInventoryColumn,{text:string;detail?:string}>}))??[])
// Compound headings may break only after "·" (CSS keeps the words whole), so the row fits a desktop window.
const headingParts=(label:string)=>label.split('·')
</script>
<template>
 <section ref="root" class="tenant-inventory-page admin-prototype" :aria-busy="state.busy">
  <h1 class="page-title-sr">租户资源台账</h1>
  <div class="panel inventory-card">
   <header class="mop-card-head">
    <div class="mop-card-title"><h2>租户资源台账</h2><span>{{ state.page ? `本页 ${state.page.items.length} 个已授权租户 · 时间为北京时间` : '已授权租户资源' }}</span></div>
    <div class="mop-card-actions"><el-button :disabled="!permitted" :loading="state.busy" @click="read('refresh')">重新读取第一页</el-button></div>
   </header>
   <p v-if="permitted" class="mop-note"><strong>口径</strong>：资产数、站点数、成员数分别为登记记录（含草稿）、登记站点和有效成员关系，不代表已采集、计费、Proxy 在线或在线人数。其余列尚无数据来源，显示为“未确认/未知”，不采用默认值，也不绘制配额进度条。</p>
   <ApiErrorNotice :error="state.error"/>
   <div v-if="state.canRetry&&permitted" class="inventory-retry"><el-button :disabled="state.busy" @click="read('retry')">重试原分页请求</el-button></div>
   <p v-if="!permitted" class="mop-empty" role="status">当前成员无租户登记资源读取权限</p>
   <div v-else-if="state.busy" class="mop-card-body"><el-skeleton :rows="6" animated/></div>
   <template v-else-if="state.page">
    <TableScrollTools v-if="overflow" :controls="tableId" :at-start="atStart" :at-end="atEnd" @step="scroll"/>
    <div :id="tableId" ref="tableViewport" class="inventory-scroll" tabindex="0" role="region" aria-label="租户登记资源12列表格，可横向滚动" :aria-describedby="overflow?`${tableId}-hint`:undefined" @keydown="onKeydown">
     <table class="admin-table inventory-table"><caption class="sr-only">已授权租户资源台账快照</caption>
      <thead><tr><th v-for="column in tenantInventoryColumns" :key="column.key" scope="col"><template v-for="(part,index) in headingParts(column.label)" :key="index"><template v-if="index">·<wbr/></template>{{part}}</template></th></tr></thead>
      <tbody>
       <tr v-for="{row,cells} in tableRows" :key="row.id" :class="{'is-selected':row.id===selectedId}">
        <td v-for="column in tenantInventoryColumns" :key="column.key">
         <template v-if="column.key==='name'"><button type="button" class="tenant-name" :data-tenant-id="row.id" :aria-expanded="row.id===selectedId" @click="openDetail(row.id)">{{row.name}}</button><small class="mono" :title="row.id">…{{row.id.slice(-6)}}</small></template>
         <span v-else-if="column.key==='status'" class="mop-badge unknown" :title="cells.status.detail">{{cells.status.text}}</span>
         <template v-else-if="column.key==='assets'"><strong class="count" :title="cells.assets.detail">{{cells.assets.text}}</strong><small>配额未知</small></template>
         <strong v-else-if="column.key==='sites'||column.key==='members'" class="count" :title="cells[column.key].detail">{{cells[column.key].text}}</strong>
         <span v-else-if="column.key==='created'" :title="cells.created.detail">{{cells.created.text}}</span>
         <span v-else class="inventory-unknown" :title="cells[column.key].detail">{{cells[column.key].text}}</span>
        </td>
       </tr>
       <tr v-if="!state.page.items.length"><td :colspan="tenantInventoryColumns.length" class="inventory-empty">本页无授权登记记录；不表示平台没有租户</td></tr>
      </tbody>
     </table>
    </div>
   </template>
   <footer v-if="permitted" class="mop-card-foot inventory-pagination"><span>各页为单次请求快照</span><div class="inventory-pager"><el-button :disabled="state.busy||!state.canPrevious" @click="read('previous')">上一页</el-button><span ref="pagingStatus" tabindex="-1" role="status">{{state.busy?'正在读取…':state.error?'读取未完成；可重试原分页':(state.paged?'后续页':'第一页')+' · 每页15条'}}</span><el-button :disabled="state.busy||!state.page?.has_more" @click="read('next')">下一页</el-button></div></footer>
  </div>
  <section v-if="selected" class="panel inventory-detail" aria-label="选中租户登记快照详情">
   <header class="mop-card-head detail-heading"><h2 ref="detailHeading" tabindex="-1">{{selected.name}} · 租户台账快照详情</h2><el-button @click="closeDetail">关闭详情</el-button></header>
   <dl class="mop-facts inventory-facts"><div v-for="column in tenantInventoryColumns" :key="column.key"><dt>{{column.label}}</dt><dd>{{tenantInventoryCell(selected,column.key).text}}<small>{{tenantInventoryCell(selected,column.key).detail}}</small></dd></div></dl>
  </section>
 </section>
</template>
<style scoped>
.inventory-card{overflow:hidden}
.inventory-retry{padding:0 16px 12px}
.inventory-scroll{overflow:auto;max-width:100%}.inventory-scroll:focus-visible{outline:2px solid var(--mop-primary);outline-offset:-2px}
/* Width comes from content, not a fixed minimum: headings break only after "·" and cell text keeps words
   whole, so all twelve columns fit common desktop widths; narrower windows scroll with the name pinned. */
.prototype-shell #main-content .inventory-table{min-width:0}
.prototype-shell #main-content .inventory-table th{white-space:normal;word-break:keep-all;vertical-align:bottom}
.inventory-table td:not(:first-child){word-break:keep-all}
.inventory-table th:first-child,.inventory-table td:first-child{position:sticky;left:0;z-index:1;min-width:160px;background:#fff;box-shadow:inset -1px 0 0 var(--mop-line-soft)}
.inventory-table th:first-child{z-index:2;background:var(--mop-sunken)}
.inventory-table tbody tr:hover td:first-child{background:var(--mop-hover)}
.inventory-table tbody tr.is-selected td,.inventory-table tbody tr.is-selected:hover td{background:var(--mop-selected)}
.tenant-name{display:block;padding:0;border:0;background:none;color:var(--mop-primary);font:inherit;font-weight:600;text-align:left;cursor:pointer;overflow-wrap:anywhere}
.tenant-name:hover{text-decoration:underline;text-underline-offset:3px}
.tenant-name:focus-visible{outline:2px solid var(--mop-primary);outline-offset:2px;border-radius:2px}
/* Desktop link-style buttons are 28px like other text buttons; narrow and touch screens keep their larger targets. */
@media (min-width:761px) and (pointer:fine){#main-content .tenant-name{min-height:28px}}
.count{white-space:nowrap}
.inventory-unknown{color:var(--mop-faint)}
.inventory-empty{color:var(--mop-muted);text-align:center}
.inventory-pager{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.inventory-pager :deep(.el-button+.el-button){margin-left:0}
.inventory-detail{margin-top:12px;overflow:hidden}
.detail-heading{display:flex;justify-content:space-between;gap:12px;align-items:center}
.inventory-facts{margin:16px}
.inventory-facts dd small{display:block;margin-top:2px;color:var(--mop-muted);font-size:12px;font-weight:400;line-height:1.5}
.inventory-detail h2:focus-visible,.inventory-pagination [tabindex]:focus-visible{outline:2px solid var(--mop-primary);outline-offset:3px}
@media(max-width:700px){.tenant-inventory-page :deep(.el-button),.tenant-name{min-height:44px}.detail-heading{flex-direction:column;align-items:stretch;gap:12px}.inventory-pagination{justify-content:space-between}}
</style>
