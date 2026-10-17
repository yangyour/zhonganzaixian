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
   <el-alert title="按指定基准日期核算到期状态，仅筛选已登记的台账日期" type="info" :closable="false">未填写日期保持“未登记”状态；仅执行查询核算，不变更台账记录，不触发通知、工单或 SLA。跨页浏览期间若资料更新，请重新查询。</el-alert>
   <el-form class="date-query-form" label-position="top" @submit.prevent="workspace.apply(form)">
    <el-form-item label="日期字段（必选）"><el-select v-model="form.date_field" placeholder="请选择日期字段" aria-label="日期字段"><el-option label="维保到期日期" value="warranty_expiry"/><el-option label="超期服役日期" value="overdue_service_date"/></el-select></el-form-item>
    <el-form-item label="日期窗口（必选）"><el-select v-model="form.window" placeholder="请选择日期窗口" aria-label="日期窗口"><el-option v-for="w in windows" :key="w.value" :value="w.value" :label="w.label"/></el-select></el-form-item>
    <el-form-item label="设备名称（包含匹配）"><el-input v-model="form.name" clearable aria-label="日期筛选设备名称"/></el-form-item>
    <el-form-item label="站点ID（精确匹配）"><el-input v-model="form.site_id" clearable aria-label="日期筛选站点"/></el-form-item>
    <el-form-item><el-button type="primary" native-type="submit" :loading="state.busy">查询到期记录</el-button><el-button :disabled="state.busy || !state.filters" @click="workspace.refresh()">返回首页重新查询</el-button></el-form-item>
   </el-form>
   <p>修改筛选条件将清空当前结果，请点击“查询到期记录”重新加载。核算基准日按服务端 Asia/Shanghai 时区确定；若跨日翻页提示基准日变更（409），请返回首页重新查询。</p>
   <ApiErrorNotice :error="state.error"/>
   <template v-if="state.page">
    <p>核算基准日：{{state.page.reference_date}} · 本页 {{state.page.items.length}} 条 · {{state.paged?'后续页':'第 1 页'}}</p>
    <AppTable :rows="state.page.items" :loading="state.busy" empty-text="当前筛选条件下暂无匹配记录">
     <el-table-column label="设备名称" min-width="180"><template #default="{row}"><el-button link type="primary" @click="inspect(row.asset_id)">{{row.name}}</el-button></template></el-table-column>
     <el-table-column label="登记日期" min-width="130"><template #default="{row}">{{selected(row).recorded_date??'未登记'}}</template></el-table-column>
     <el-table-column label="到期状态" min-width="160"><template #default="{row}"><span :class="{'past-date':selected(row).position==='PAST_DATE'}">{{labels[selected(row).position]}}</span></template></el-table-column>
     <el-table-column label="距基准日（天）" min-width="120"><template #default="{row}">{{selected(row).days_until??'—'}}</template></el-table-column>
     <el-table-column label="记录版本" min-width="120"><template #default="{row}">资产v{{row.assessment.asset_version}} / 资料{{row.assessment.metadata_version??'未提供'}}</template></el-table-column>
     <el-table-column label="操作" min-width="100"><template #default="{row}"><el-button link @click="inspect(row.asset_id)">资产详情</el-button></template></el-table-column>
    </AppTable>
   </template>
   <el-skeleton v-else-if="state.busy" :rows="5" animated/>
   <p v-else-if="!state.error">请选择日期字段与日期窗口后点击“查询到期记录”。</p>
   <div class="date-query-footer"><span>每页 15 条 · 仅限当前租户授权资产</span><el-button :disabled="state.busy || !state.page?.has_more" @click="workspace.next()">下一页</el-button></div>
  </template>
  <el-alert v-else title="当前身份没有日期筛选权限" type="warning" :closable="false"/>
 </el-drawer>
</template>
<style scoped>
.date-query-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px;margin-top:18px}.date-query-form :deep(.el-select){width:100%}.date-query-form :deep(.el-button){min-height:44px}.date-query-footer{display:flex;justify-content:space-between;gap:12px;align-items:center;flex-wrap:wrap;margin-top:16px}p,.date-query-footer span{color:#536276;line-height:1.6}.past-date{color:#a52b2b;font-weight:600}@media(max-width:700px){.date-query-form{grid-template-columns:1fr}.date-query-footer{align-items:stretch;flex-direction:column}}
</style>
