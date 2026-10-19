<script setup lang="ts">
import '../styles/adminPrototype.css'
import {computed,onBeforeUnmount,ref,shallowRef,watch} from 'vue'
import {api} from '../api/client'
import {session,contextGeneration,switchingTenant,can} from '../stores/session'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import {createTenantInventoryReader,emptyTenantInventory,tenantInventoryColumns,tenantInventoryCell} from '../utils/tenantInventory'
const state=shallowRef(emptyTenantInventory()),selectedId=ref<string|null>(null)
const reader=createTenantInventoryReader(api,value=>{state.value=value;if(value.busy||!value.page)selectedId.value=null})
const permitted=computed(()=>session.value?.user.role==='padmin'&&can('tenants:read_inventory')&&!switchingTenant.value)
watch(()=>[session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,contextGeneration.value,permitted.value],()=>{const s=session.value;selectedId.value=null;reader.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role}:null);if(permitted.value)void reader.refresh()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>reader.dispose())
const selected=computed(()=>state.value.page?.items.find(row=>row.id===selectedId.value)??null)
const total = (key: 'registered_asset_count' | 'registered_site_count' | 'active_member_count') => state.value.page ? state.value.page.items.reduce((sum, row) => sum + BigInt(row[key]), 0n).toString() : '—'
</script>
<template>
 <section class="tenant-inventory-page admin-prototype">
  <div class="page-heading"><div><h1>租户资源台账</h1><p>展示当前账号具备平台管理员授权的租户基础档案与已登记资源规模</p></div><el-button :disabled="!permitted" :loading="state.busy" @click="reader.refresh()">重新读取第一页</el-button></div>
  <el-alert type="info" title="台账统计范围说明" :closable="false">本页汇总各租户已登记的资产、站点与有效成员数量（含台账草稿）；资源池隔离参数、监控项配额（Item / NVPS）、数据保留期、ITSM 对接及网络拓扑状态待采集源与策略确认后接入展示。</el-alert>
  <ApiErrorNotice :error="state.error"/>
  <p v-if="!permitted">当前成员无租户登记资源读取权限</p>
  <el-skeleton v-else-if="state.busy" :rows="6" animated/>
  <template v-else-if="state.page">
   <div class="admin-stats"><div class="admin-stat"><span>本页授权租户</span><strong>{{ state.page.items.length }}</strong><small>当前账号可管理的租户范围</small></div><div class="admin-stat"><span>本页登记资产</span><strong>{{ total('registered_asset_count') }}</strong><small>含台账草稿记录</small></div><div class="admin-stat"><span>本页登记站点</span><strong>{{ total('registered_site_count') }}</strong><small>已登记站点总数</small></div><div class="admin-stat"><span>本页有效成员关系</span><strong>{{ total('active_member_count') }}</strong><small>跨租户授权成员分别计入</small></div></div>
   <p class="scope-note">当前页已加载 {{state.page.items.length}} 个授权租户记录；创建时间按北京时间显示，详情面板同时保留 UTC 标准时间。</p>
   <div class="inventory-scroll admin-table-scroll" tabindex="0" role="region" aria-label="租户登记资源12列表格，可横向滚动">
    <table class="admin-table"><caption>已授权租户资源台账快照</caption><thead><tr><th v-for="column in tenantInventoryColumns" :key="column.key" scope="col">{{column.label}}</th></tr></thead><tbody><tr v-for="row in state.page.items" :key="row.id"><td v-for="column in tenantInventoryColumns" :key="column.key"><button v-if="column.key==='name'" class="tenant-name" @click="selectedId=row.id">{{row.name}}</button><span v-else>{{tenantInventoryCell(row,column.key).text}}</span><small v-if="['name','created'].includes(column.key)">{{tenantInventoryCell(row,column.key).detail}}</small><span v-else class="inventory-cell-hint" :title="tenantInventoryCell(row,column.key).detail">{{ column.key === 'assets' ? '含草稿登记' : column.key === 'members' ? '有效成员关系' : column.key === 'sites' ? '登记站点' : '待接入配置' }}</span></td></tr><tr v-if="!state.page.items.length"><td :colspan="tenantInventoryColumns.length">本页无授权登记记录；不表示平台没有租户</td></tr></tbody></table>
   </div>
  </template>
  <div v-if="permitted" class="inventory-pagination"><el-button :disabled="state.busy||!state.canPrevious" @click="reader.previous()">上一页</el-button><span>{{state.paged?'后续页':'第一页'}} · 每页15条</span><el-button :disabled="state.busy||!state.page?.has_more" @click="reader.next()">下一页</el-button></div>
  <section v-if="selected" class="inventory-detail admin-editor-panel" aria-label="选中租户登记快照详情">
   <div class="detail-heading"><h2>{{selected.name}} · 租户台账快照详情</h2><el-button @click="selectedId=null">关闭详情</el-button></div><p>展示当前列表快照中的租户台账明细；如需获取最新状态请点击“重新读取第一页”。</p>
   <dl><template v-for="column in tenantInventoryColumns" :key="column.key"><dt>{{column.label}}</dt><dd>{{tenantInventoryCell(selected,column.key).text}}<small>{{tenantInventoryCell(selected,column.key).detail}}</small></dd></template></dl>
  </section>
 </section>
</template>
<style scoped>
.inventory-cell-hint{display:block;font-size:10px;color:#8a97a8;margin-top:3px}.scope-note{color:#46566c;line-height:1.7;font-size:11px;margin:10px 0}.inventory-scroll{overflow:auto;border:1px solid #d5dfec;border-radius:5px;background:#fff}.inventory-scroll:focus-visible{outline:3px solid #245daf;outline-offset:2px}table{min-width:1450px;width:100%;border-collapse:collapse}caption{text-align:left;padding:12px 16px;font-weight:600;background:#fff;font-size:13px}th,td{text-align:left;vertical-align:top;border-bottom:1px solid #dce4ee;padding:12px;min-width:90px}th{background:#f1f5fb;font-size:13px}td{font-size:14px}small{display:block;color:#526276;overflow-wrap:anywhere;line-height:1.6;margin-top:3px;max-width:170px;font-size:10px}.tenant-name{min-height:28px;background:none;border:0;padding:0;color:#1655a2;text-align:left;font:inherit;font-weight:600;max-width:170px;white-space:normal;cursor:pointer;overflow-wrap:anywhere}.tenant-name:focus-visible{outline:2px solid #245daf}.inventory-pagination{display:flex;align-items:center;justify-content:flex-end;gap:12px;flex-wrap:wrap;margin:0;padding:12px 18px;background:#fff;border:1px solid #e2e8f0;border-top:0}.inventory-detail{background:white;padding:18px;border:1px solid #d5dfec;border-radius:8px}.detail-heading{display:flex;justify-content:space-between;gap:12px;align-items:center}.inventory-detail dl{display:grid;grid-template-columns:minmax(140px,1fr) 3fr;gap:10px}.inventory-detail dt{font-weight:600}.inventory-detail dd{margin:0;overflow-wrap:anywhere}.admin-prototype :deep(.el-button){min-height:32px}@media(max-width:700px){.tenant-inventory-page :deep(.el-button),.tenant-name{min-height:44px}.page-heading,.detail-heading{flex-direction:column;align-items:stretch;gap:12px}.inventory-detail dl{grid-template-columns:1fr}.inventory-detail dd{margin-bottom:12px}.inventory-pagination{justify-content:space-between}}
</style>
