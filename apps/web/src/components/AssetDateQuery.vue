<script setup lang="ts">
import { ElTableColumn as ElementTableColumn } from 'element-plus'
import { computed, onBeforeUnmount, reactive, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createAssetDateQuery, type DateQueryInput, type DateQueryState, type DateQueryItem } from '../utils/assetDateQuery'
import ApiErrorNotice from './ApiErrorNotice.vue'
import AppTable from './AppTable.vue'
const ElTableColumn = ElementTableColumn<DateQueryItem>
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; select: [assetId: string] }>()
const open = computed({get:()=>props.modelValue,set:value=>emit('update:modelValue',value)})
const form = reactive<DateQueryInput>({date_field:null,window:null,name:'',site_id:''})
const state = shallowRef<DateQueryState>({page:null,filters:null,busy:false,error:null,paged:false})
const workspace=createAssetDateQuery(api,value=>{state.value=value})
const permitted=computed(()=>can('asset_dates:read')&&!switchingTenant.value)
watch(()=>[session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,contextGeneration.value,permitted.value],()=>{
 const s=session.value;workspace.reset(s&&permitted.value?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version}:null)
 form.date_field=null;form.window=null;form.name='';form.site_id=''
},{immediate:true,flush:'sync'})
watch(form,()=>workspace.invalidate(),{deep:true,flush:'sync'})
watch(open,value=>{if(!value)workspace.invalidate()},{flush:'sync'})
onBeforeUnmount(()=>workspace.dispose())
const windows=[{value:'UNKNOWN',label:'未登记日期'},{value:'PAST_DATE',label:'已到期'},{value:'WITHIN_30_DAYS',label:'未来30天内（含今天）'},{value:'WITHIN_90_DAYS',label:'未来90天内（含今天）'},{value:'REFERENCE_QUARTER',label:'参考日所在自然季度'}]
const labels={UNKNOWN:'未登记',PAST_DATE:'已到期',DUE_TODAY:'今日到期',WITHIN_30_DAYS:'30天内',FUTURE_DATE:'30天之后'}
const selected=(row:DateQueryItem)=>row.assessment[state.value.page!.date_field]
function inspect(id:string){open.value=false;emit('select',id)}
</script>
<template>
 <el-drawer v-model="open" title="维保与超期日期筛选" size="min(100%, 1060px)" class="date-query-drawer">
  <template v-if="permitted">
   <el-alert title="按指定基准日期核算到期状态，仅筛选已登记的台账日期" type="info" :closable="false">未填写日期的资产保持“未登记”状态；仅做到期核算，不触发通知、工单或 SLA。跨页浏览期间若资料更新，请重新查询。</el-alert>
   <el-form class="date-query-form" label-position="top" @submit.prevent="workspace.apply(form)">
    <el-form-item label="日期字段（必选）"><el-select v-model="form.date_field" placeholder="请选择日期字段" aria-label="日期字段"><el-option label="维保到期日期" value="warranty_expiry"/><el-option label="超期服役日期" value="overdue_service_date"/></el-select></el-form-item>
    <el-form-item label="日期窗口（必选）"><el-select v-model="form.window" placeholder="请选择日期窗口" aria-label="日期窗口"><el-option v-for="w in windows" :key="w.value" :value="w.value" :label="w.label"/></el-select></el-form-item>
    <el-form-item label="设备名称"><el-input v-model="form.name" clearable placeholder="输入名称关键字（包含匹配）" aria-label="日期筛选设备名称"/></el-form-item>
    <el-form-item label="站点 ID"><el-input v-model="form.site_id" clearable placeholder="输入完整站点 ID（精确匹配）" aria-label="日期筛选站点"/></el-form-item>
    <el-form-item class="date-query-actions"><el-button type="primary" native-type="submit" :loading="state.busy">查询到期记录</el-button><el-button :disabled="state.busy || !state.filters" @click="workspace.refresh()">重置到第 1 页</el-button></el-form-item>
   </el-form>
   <p class="date-query-hint">调整筛选条件后请点击“查询到期记录”重新加载。基准日按服务端 Asia/Shanghai 时区核算；若跨日翻页提示基准日变更（409），请重置到第 1 页重新查询。</p>
   <ApiErrorNotice :error="state.error"/>
   <template v-if="state.page">
    <div class="date-query-summary"><span class="tabular-nums">核算基准日：<strong>{{state.page.reference_date}}</strong></span><span class="tabular-nums">本页 <strong>{{state.page.items.length}}</strong> 条</span><span class="page-chip">{{state.paged?'后续页':'第 1 页'}}</span></div>
    <AppTable :rows="state.page.items" :loading="state.busy" empty-text="当前筛选条件下暂无匹配记录">
     <el-table-column label="设备名称" min-width="180"><template #default="{row}"><el-button link type="primary" @click="inspect(row.asset_id)">{{row.name}}</el-button></template></el-table-column>
     <el-table-column label="登记日期" min-width="130"><template #default="{row}"><span class="tabular-nums">{{selected(row).recorded_date??'未登记'}}</span></template></el-table-column>
     <el-table-column label="到期状态" min-width="160"><template #default="{row}"><span class="date-status-badge" :class="{'past-date':selected(row).position==='PAST_DATE','due-soon':selected(row).position==='DUE_TODAY'||selected(row).position==='WITHIN_30_DAYS','is-unknown':selected(row).position==='UNKNOWN'}">{{labels[selected(row).position]}}</span></template></el-table-column>
     <el-table-column label="距基准日（天）" min-width="120"><template #default="{row}"><span class="tabular-nums">{{selected(row).days_until??'—'}}</span></template></el-table-column>
     <el-table-column label="记录版本" min-width="130"><template #default="{row}"><span class="tabular-nums version-text">资产 v{{row.assessment.asset_version}} / 资料 {{row.assessment.metadata_version ? 'v'+row.assessment.metadata_version : '未登记'}}</span></template></el-table-column>
     <el-table-column label="操作" min-width="100"><template #default="{row}"><el-button link type="primary" @click="inspect(row.asset_id)">查看详情</el-button></template></el-table-column>
    </AppTable>
   </template>
   <el-skeleton v-else-if="state.busy" :rows="5" animated/>
   <div v-else-if="!state.error" class="date-query-placeholder">请选择日期字段与日期窗口后点击“查询到期记录”。</div>
   <div class="date-query-footer"><span class="tabular-nums">每页 15 条 · 当前租户授权资产</span><el-button :disabled="state.busy || !state.page?.has_more" @click="workspace.next()">下一页</el-button></div>
  </template>
  <el-alert v-else title="当前账号无维保与超期日期筛选权限" type="warning" :closable="false"/>
 </el-drawer>
</template>
<style scoped>
.tabular-nums{font-variant-numeric:tabular-nums}.date-query-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px;margin-top:18px}.date-query-form :deep(.el-select){width:100%}.date-query-form :deep(.el-button){min-height:44px}.date-query-actions{grid-column:1/-1}.date-query-hint{font-size:13px;color:#536276;line-height:1.65;margin:4px 0 12px}.date-query-summary{display:flex;align-items:center;flex-wrap:wrap;gap:14px;padding:10px 14px;margin:10px 0 12px;background:#f8fafc;border:1px solid #e4ebf3;border-radius:8px;font-size:13px;color:#3b4d63}.page-chip{padding:2px 8px;border-radius:999px;background:#eaf1f8;font-size:12px;color:#2b4c7e}.date-status-badge{display:inline-block;padding:2px 8px;border-radius:6px;font-size:12px;background:#f1f5f9;color:#334155}.past-date{color:#a52b2b;background:#fef2f2;font-weight:600}.due-soon{color:#9a5b13;background:#fffbeb;font-weight:600}.is-unknown{color:#64748b;background:#f1f5f9}.version-text{color:#536276;font-size:12px}.date-query-placeholder{padding:28px 16px;text-align:center;background:#f8fafc;border:1px dashed #d5e0ec;border-radius:8px;color:#536276;font-size:13px;margin:12px 0}.date-query-footer{display:flex;justify-content:space-between;gap:12px;align-items:center;flex-wrap:wrap;margin-top:16px;padding-top:12px;border-top:1px solid #edf2f7}p,.date-query-footer span{color:#536276;line-height:1.6}@media(max-width:700px){.date-query-form{grid-template-columns:1fr}.date-query-footer{align-items:stretch;flex-direction:column}}
</style>
