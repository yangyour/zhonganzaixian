<script setup lang="ts">
import {computed,nextTick,onUnmounted,ref,shallowRef,watch,type DeepReadonly} from 'vue'
import {api,isCancelled} from '../api/client'
import type {AssetImportSource} from '../api/assetImportTypes'
import {session,contextGeneration,can,switchingTenant} from '../stores/session'
import {readCmdbSourcePreflight,type SourcePreflight} from '../utils/cmdbSourcePreflight'
import {CMDB_IMPORT_COLUMNS} from '../utils/cmdbImportPreflight'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{source:DeepReadonly<AssetImportSource>}>()
const readButton=ref<{ $el:HTMLButtonElement }>(),nameButton=ref<{ $el:HTMLButtonElement }>(),summary=ref<HTMLElement>(),databaseMode=ref(false)
async function cancel(){clear();await nextTick();(databaseMode.value?nameButton:readButton).value?.$el.focus()}
const result=shallowRef<SourcePreflight|null>(null),error=shallowRef<unknown>(null),busy=ref(false),page=ref(0)
const permitted=computed(()=>session.value?.mode==='remote_test'&&can('assets:write')&&!switchingTenant.value&&session.value.tenant.id===props.source.tenant_id&&session.value.user.id===props.source.created_by)
const key=computed(()=>JSON.stringify([contextGeneration.value,session.value?.user.id,session.value?.tenant.id,session.value?.permission_version,session.value?.account_version,session.value?.user.role,permitted.value,props.source.id,props.source.version,props.source.parse_status,props.source.parsed_artifact?.parse_hash]))
let epoch=0,controller:AbortController|undefined
function clear(){++epoch;controller?.abort();busy.value=false;error.value=null;result.value=null;page.value=0}
async function check(database=false){
 if(!permitted.value||busy.value||props.source.parse_status!=='PARSED')return
 clear();databaseMode.value=database;const turn=epoch,source=JSON.parse(JSON.stringify(props.source)) as AssetImportSource;controller=new AbortController();const signal=controller.signal;busy.value=true
 try{const response=await api.request<unknown>(`/assets/import-sources/${source.id}/${database?'cmdb-name-check':'cmdb-preflight'}`,{signal});if(turn!==epoch||signal.aborted)return;result.value=readCmdbSourcePreflight(response,source,database);await nextTick();if(turn===epoch&&!signal.aborted)summary.value?.focus()}
 catch(cause){if(turn===epoch&&!isCancelled(cause))error.value=cause}
 finally{if(turn===epoch)busy.value=false}
}
watch(key,clear,{flush:'sync'});onUnmounted(clear)
</script>
<template>
 <section v-if="permitted" class="source-preflight" aria-label="服务端 CMDB 来源校验">
  <h3>服务端 CMDB 来源校验</h3>
  <p>检查当前已解析来源与客户 33 列字典，也可进一步查询库内重名；不使用客户端自报结果。旧 15 列模板不会自动转换。</p>
  <el-button ref="readButton" :disabled="busy||source.parse_status!=='PARSED'" @click="check(false)">读取服务端校验结果</el-button><el-button ref="nameButton" :disabled="busy||source.parse_status!=='PARSED'" @click="check(true)">检查字典与库内重名</el-button><el-button v-if="busy" @click="cancel">取消读取校验</el-button>
  <p v-if="busy" role="status">正在读取当前来源版本的检查结果…</p><ApiErrorNotice v-if="error" :error="error"/>
  <template v-if="result"><p ref="summary" tabindex="-1" role="status">{{result.records}} 条记录，{{result.error_rows}} 条含错误，{{result.warning_rows}} 条含警告。来源版本 {{result.source_version}}。</p>
   <p v-if="result.database_checks==='EXACT_ASSET_NAMES_ONLY'">已检查当前租户库内资产名称原文是否相同，不裁剪空白、不转换大小写、不自动合并。结果仅反映本次读取快照，不预留名称。</p>
   <p>尚不能入库：{{result.database_checks==='NOT_RUN'?'数据库去重、':''}}配额、站点与负责人引用、IP 去重策略及正式发布检查未执行。Excel 数值日期保留原始日期基准，不自动猜测。</p>
   <p v-if="!result.findings.length">本次有限检查未发现问题，不代表完整预检或导入通过。</p>
   <div v-else class="source-preflight-scroll" tabindex="0" role="region" aria-label="服务端逐行校验问题，可滚动查看"><table><caption>服务端来源问题，每页 25 项</caption><thead><tr><th scope="col">记录 / 源行</th><th scope="col">列</th><th scope="col">级别</th><th scope="col">说明</th></tr></thead><tbody><tr v-for="(item,index) in result.findings.slice(page*25,(page+1)*25)" :key="`${page}-${index}`"><td>{{item.record}} / {{item.source_row}}</td><td>{{CMDB_IMPORT_COLUMNS[item.column]?.label}}</td><td>{{item.level==='ERROR'?'错误':'警告'}}</td><td>{{item.message}}</td></tr></tbody></table></div>
   <nav v-if="result.findings.length>25" aria-label="服务端校验分页"><el-button :disabled="page===0" @click="page--">上一页</el-button><span>第 {{page+1}} / {{Math.ceil(result.findings.length/25)}} 页</span><el-button :disabled="(page+1)*25>=result.findings.length" @click="page++">下一页</el-button></nav>
  </template>
 </section>
</template>
<style scoped>
.source-preflight{border-top:1px solid #b6c4d6;padding-top:12px;margin-top:16px;line-height:1.7}.source-preflight-scroll{overflow:auto;max-height:400px}.source-preflight [tabindex="-1"]:focus-visible,.source-preflight-scroll:focus-visible{outline:3px solid #175cd3;outline-offset:2px}table{width:100%;border-collapse:collapse;text-align:left}caption{text-align:left}td,th{padding:8px;border-bottom:1px solid #dce5ef;min-width:80px;vertical-align:top}.source-preflight .el-button{min-height:44px}nav{display:flex;align-items:center;gap:12px;margin-top:12px;flex-wrap:wrap}
</style>
