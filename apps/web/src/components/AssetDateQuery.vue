<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, shallowRef, watch } from 'vue'
import { api } from '../api/client'
import { can, contextGeneration, session, switchingTenant } from '../stores/session'
import { createAssetDateQuery, type DateQueryInput, type DateQueryState, type DateQueryItem } from '../utils/assetDateQuery'
import ApiErrorNotice from './ApiErrorNotice.vue'
import AppTable from './AppTable.vue'
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
const windows=[{value:'UNKNOWN',label:'日期未配置'},{value:'PAST_DATE',label:'记录日期已过'},{value:'WITHIN_30_DAYS',label:'未来30天内（含今天）'},{value:'WITHIN_90_DAYS',label:'未来90天内（含今天）'},{value:'REFERENCE_QUARTER',label:'参考日所在自然季度'}]
const labels={UNKNOWN:'日期未配置',PAST_DATE:'记录日期已过',DUE_TODAY:'记录日期为今天',WITHIN_30_DAYS:'30天内',FUTURE_DATE:'30天之后'}
const selected=(row:DateQueryItem)=>row.assessment[state.value.page!.date_field]
function inspect(id:string){open.value=false;emit('select',id)}
</script>
<template>
 <el-drawer v-model="open" title="维保与超期日期筛选" size="min(100%, 1060px)" class="date-query-drawer">
  <template v-if="permitted">
   <el-alert title="只筛选已登记的手工资料日期，不证明真实维保覆盖" type="info" :closable="false">日期未配置保持未知；本次不新建P4隐患、不发送通知、不导出。资料跨页变化不冻结。</el-alert>
   <el-form class="date-query-form" label-position="top" @submit.prevent="workspace.apply(form)">
    <el-form-item label="日期字段（必选）"><el-select v-model="form.date_field" placeholder="请选择日期字段" aria-label="日期字段"><el-option label="维保到期日期" value="warranty_expiry"/><el-option label="超期服役日期" value="overdue_service_date"/></el-select></el-form-item>
    <el-form-item label="日期窗口（必选）"><el-select v-model="form.window" placeholder="请选择日期窗口" aria-label="日期窗口"><el-option v-for="w in windows" :key="w.value" :value="w.value" :label="w.label"/></el-select></el-form-item>
    <el-form-item label="设备名称（字面包含）"><el-input v-model="form.name" clearable aria-label="日期筛选设备名称"/></el-form-item>
    <el-form-item label="站点ID（精确）"><el-input v-model="form.site_id" clearable aria-label="日期筛选站点"/></el-form-item>
    <el-form-item><el-button type="primary" native-type="submit" :loading="state.busy">只读查询</el-button><el-button :disabled="state.busy || !state.filters" @click="workspace.refresh()">从第一页重新读取</el-button></el-form-item>
   </el-form>
   <p>修改条件立即取消旧查询并清除结果，不自动查询。参考日由服务器按Asia/Shanghai确定；跨日分页收到409后，请从第一页重新读取。</p>
   <ApiErrorNotice :error="state.error"/>
   <template v-if="state.page">
    <p>服务端参考日：{{state.page.reference_date}} · 本页 {{state.page.items.length}} 条 · {{state.paged?'后续页':'第一页'}}（非完整快照）</p>
    <AppTable :rows="state.page.items" :loading="state.busy" empty-text="当前授权范围与条件下没有匹配记录">
     <el-table-column label="设备名称" min-width="180"><template #default="{row}"><el-button link type="primary" @click="inspect(row.asset_id)">{{row.name}}</el-button></template></el-table-column>
     <el-table-column label="记录日期" min-width="130"><template #default="{row}">{{selected(row).recorded_date??'未配置（null）'}}</template></el-table-column>
     <el-table-column label="日期对照" min-width="160"><template #default="{row}"><span :class="{'past-date':selected(row).position==='PAST_DATE'}">{{labels[selected(row).position]}}</span></template></el-table-column>
     <el-table-column label="相差日历日" min-width="110"><template #default="{row}">{{selected(row).days_until??'未知'}}</template></el-table-column>
     <el-table-column label="记录版本" min-width="120"><template #default="{row}">资产v{{row.assessment.asset_version}} / 资料{{row.assessment.metadata_version??'未提供'}}</template></el-table-column>
     <el-table-column label="操作" min-width="100"><template #default="{row}"><el-button link @click="inspect(row.asset_id)">资产详情</el-button></template></el-table-column>
    </AppTable>
   </template>
   <el-skeleton v-else-if="state.busy" :rows="5" animated/>
   <p v-else-if="!state.error">尚未查询，不把未读取显示为空集或0台。</p>
   <div class="date-query-footer"><span>每页15条 · 当前授权租户 · 未查询通知送达</span><el-button :disabled="state.busy || !state.page?.has_more" @click="workspace.next()">下一页</el-button></div>
  </template>
  <el-alert v-else title="当前身份没有日期筛选权限" type="warning" :closable="false"/>
 </el-drawer>
</template>
<style scoped>
.date-query-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px;margin-top:18px}.date-query-form :deep(.el-select){width:100%}.date-query-form :deep(.el-button){min-height:44px}.date-query-footer{display:flex;justify-content:space-between;gap:12px;align-items:center;flex-wrap:wrap;margin-top:16px}p,.date-query-footer span{color:#536276;line-height:1.6}.past-date{color:#a52b2b;font-weight:600}@media(max-width:700px){.date-query-form{grid-template-columns:1fr}.date-query-footer{align-items:stretch;flex-direction:column}}
</style>
