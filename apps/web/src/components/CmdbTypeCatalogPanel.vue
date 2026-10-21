<script setup lang="ts">
import { computed,onUnmounted,ref,shallowRef,watch } from 'vue'
import { api,isCancelled } from '../api/client'
import { can,contextGeneration,session,switchingTenant } from '../stores/session'
import { cmdbIssueLabels,readCmdbTypeCatalog,type CmdbTypeCatalog } from '../utils/cmdbTypeCatalog'
import ApiErrorNotice from './ApiErrorNotice.vue'
const value=shallowRef<CmdbTypeCatalog|null>(null),error=shallowRef<unknown>(null),busy=ref(false),expanded=ref(false),primary=ref(''),search=ref('')
const permitted=computed(()=>Boolean(session.value&&!switchingTenant.value&&can('assets:read')))
const key=computed(()=>JSON.stringify([contextGeneration.value,session.value?.user.id,session.value?.tenant.id,session.value?.permission_version,permitted.value]))
const selectedTypes=computed(()=>value.value?.subtypes.filter(x=>!primary.value||x.parent===primary.value)??[])
const attributes=computed(()=>value.value?.attributes.filter(x=>[x.code,x.label,x.group].some(s=>s.toLowerCase().includes(search.value.toLowerCase())))??[])
const dimensionLabels:Record<string,string>={lifecycle_state:'生命周期',sync_state:'采集同步',health_state:'运行健康',billable:'计费状态'}
let epoch=0,controller:AbortController|undefined
function clear(){++epoch;controller?.abort();value.value=null;error.value=null;busy.value=false;expanded.value=false;primary.value='';search.value=''}
async function load(){
 controller?.abort();const abort=new AbortController();controller=abort;const turn=++epoch;value.value=null;error.value=null;primary.value='';search.value=''
 if(!permitted.value||!session.value)return
 const tenantId=session.value.tenant.id;expanded.value=true;busy.value=true
 try{const raw=await api.request<unknown>('/cmdb/type-catalog',{signal:abort.signal});if(turn!==epoch||abort.signal.aborted)return;value.value=readCmdbTypeCatalog(raw,tenantId)}
 catch(cause){if(turn===epoch&&!isCancelled(cause))error.value=cause}
 finally{if(turn===epoch)busy.value=false}
}
watch(key,clear,{flush:'sync'})
onUnmounted(clear)
</script>
<template>
 <section v-if="permitted" class="cmdb-catalog" aria-label="客户 CMDB 字典">
  <div class="catalog-heading"><div><strong>客户 CMDB 字典</strong><p>只读查看分类、属性和待确认项，不修改现有资产或导入规则。</p></div><el-button :disabled="busy" @click="load">{{expanded?'重新读取字典':'查看 CMDB 类型字典'}}</el-button></div>
  <template v-if="expanded">
   <p v-if="busy" role="status">正在读取当前授权范围的字典…</p><ApiErrorNotice v-if="error" :error="error"/>
   <template v-if="value">
    <p class="catalog-version">{{value.catalog_version}} · {{value.primary_types.length}} 主类型 / {{value.subtypes.length}} 子类型 / {{value.attributes.length}} 属性。未发布到运行配置。</p>
    <div class="catalog-issues" role="note"><strong>待确认的来源差异</strong><ul><li v-for="issue in value.unresolved" :key="issue.code">{{cmdbIssueLabels[issue.code]}}</li></ul></div>
    <label class="catalog-filter">筛选主类型 <select v-model="primary"><option value="">全部主类型</option><option v-for="item in value.primary_types" :key="item.code" :value="item.code">{{item.code}} · {{item.label}}</option></select></label>
    <div class="catalog-table"><table><thead><tr><th>主类型</th><th>子类型代码</th><th>名称</th><th>监控类型映射</th></tr></thead><tbody><tr v-for="item in selectedTypes" :key="item.code"><td>{{item.parent}}</td><td>{{item.code}}</td><td>{{item.label}}</td><td>{{item.monitoring_mapping_resolved?'来源有对应类型（不表示已接入）':'待确认，不自动套用'}}</td></tr></tbody></table></div>
    <div class="catalog-dimensions"><section v-for="dimension in value.state_dimensions" :key="dimension.field"><h4>{{dimensionLabels[dimension.field]}}</h4><p>{{dimension.values.map(v=>v.label).join(' / ')}}</p></section></div>
    <p>四个状态维度独立；未知不是正常，维护不等于停用。</p>
    <details><summary>查看 {{value.attributes.length}} 个属性定义</summary><label class="catalog-filter">搜索属性 <input v-model="search" type="search" maxlength="64" placeholder="字段名、中文名或属性组"></label><p role="status">匹配 {{attributes.length}} 项</p><div class="catalog-table"><table><thead><tr><th>字段</th><th>名称 / 分组</th><th>类型</th><th>来源必填说明</th><th>来源行</th></tr></thead><tbody><tr v-for="item in attributes" :key="item.code"><td>{{item.code}}<small v-if="item.derived">来源标注：派生只读</small></td><td>{{item.label}}<small>{{item.group}}</small></td><td>{{item.data_type}}</td><td>{{item.required_note}}</td><td>{{item.source_row}}</td></tr></tbody></table></div></details>
   </template>
  </template>
 </section>
</template>
<style scoped>
.cmdb-catalog{margin:16px 0;padding:16px;border:1px solid #dce5ef;border-radius:12px;background:#fff;color:#25354a}.catalog-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap}.catalog-heading p,.catalog-version{color:#5b6c82;font-size:13px;line-height:1.7;margin:6px 0}.catalog-issues{padding:12px 16px;background:#fff8e8;border:1px solid #e8cf91;border-radius:8px;margin:12px 0}.catalog-issues ul{margin:8px 0;padding-left:20px;line-height:1.8}.catalog-filter{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:14px 0}.catalog-filter select,.catalog-filter input{max-width:100%;min-height:34px;padding:6px 10px;border:1px solid #b6c4d6;border-radius:6px;background:#fff;color:inherit}.catalog-table{max-height:380px;overflow:auto}table{width:100%;border-collapse:collapse;text-align:left;font-size:13px}th,td{padding:10px 12px;border-bottom:1px solid #e5ebf2;vertical-align:top;overflow-wrap:anywhere}th{background:#f3f6fa;position:sticky;top:0;white-space:nowrap}td small{display:block;color:#64748b;margin-top:4px}.catalog-dimensions{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;margin-top:16px}.catalog-dimensions section{padding:10px;border:1px solid #e2e8f0;border-radius:8px}.catalog-dimensions h4{margin:0 0 8px}.catalog-dimensions p{font-size:13px;line-height:1.8;margin:0}summary{cursor:pointer;padding:10px 0;font-weight:600}@media(max-width:640px){.cmdb-catalog{padding:12px}th,td{padding:8px;min-width:90px}}
</style>
