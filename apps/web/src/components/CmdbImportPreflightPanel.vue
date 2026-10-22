<script setup lang="ts">
import {computed,nextTick,onUnmounted,ref,shallowRef,watch} from 'vue'
import {localCmdbFindingExport,downloadCmdbFindings} from '../utils/cmdbFindingExport'
import type {CmdbTypeCatalog} from '../utils/cmdbTypeCatalog'
import {CMDB_IMPORT_COLUMNS,CMDB_MAX_FILE_BYTES,cmdbBlankCsv,preflightCmdbCsv,type CmdbPreflight} from '../utils/cmdbImportPreflight'
const props=defineProps<{catalog:CmdbTypeCatalog}>()
const result=shallowRef<CmdbPreflight|null>(null),error=ref(''),busy=ref(false),filename=ref(''),selected=ref<number|null>(null),page=ref(0),filter=ref('ALL')
const fileInput=ref<HTMLInputElement>(),summary=ref<HTMLElement>(),detail=ref<HTMLElement>()
const exportError=ref(''),exportStatus=ref('')
const urls=new Set<string>();let epoch=0,alive=true
const issues=computed(()=>result.value?.findings.filter(f=>filter.value==='ALL'||f.level===filter.value)??[])
const pageIssues=computed(()=>issues.value.slice(page.value*25,(page.value+1)*25))
const selectedValues=computed(()=>selected.value===null?null:result.value?.rows[selected.value-2]??null)
function clear(){epoch++;exportError.value='';exportStatus.value='';result.value=null;error.value='';filename.value='';busy.value=false;selected.value=null;page.value=0;filter.value='ALL';if(fileInput.value)fileInput.value.value=''}
async function reset(){clear();const turn=epoch;await nextTick();if(alive&&turn===epoch)fileInput.value?.focus()}
async function choose(event:Event){
 const file=(event.target as HTMLInputElement).files?.[0];if(!file)return
 clear();const current=epoch;busy.value=true
 try{
  if(!/\.csv$/i.test(file.name))throw new Error('请选择 UTF-8 CSV 文件；XLSX 请先另存为 CSV，不能直接改扩展名。')
  if(file.size>CMDB_MAX_FILE_BYTES)throw new Error('文件超过 1 MiB 本地校验上限。')
  const bytes=await file.arrayBuffer();if(current!==epoch)return
  let text:string;try{text=new TextDecoder('utf-8',{fatal:true}).decode(bytes)}catch{throw new Error('文件不是有效 UTF-8，请重新导出，不自动猜测编码。')}
  result.value=preflightCmdbCsv(text,props.catalog);filename.value=file.name
 }catch(cause){if(current===epoch)error.value=cause instanceof Error?cause.message:'读取未完成，请重新选择文件。'}
 finally{if(current===epoch){busy.value=false;await nextTick();if(alive&&current===epoch)summary.value?.focus()}}
}
async function locate(record:number){if(!alive||!result.value||!Number.isInteger(record)||record<2||record>result.value.records+1)return;const turn=epoch;selected.value=record;await nextTick();if(alive&&turn===epoch&&selected.value===record)detail.value?.focus()}
function exportFindings(){if(!result.value||busy.value)return;exportError.value='';exportStatus.value='';try{const file=localCmdbFindingExport(result.value);downloadCmdbFindings(file);exportStatus.value=`已发起全部 ${file.findingCount} 项问题清单下载，不受当前筛选或分页限制；不代表已保存或允许入库。`}catch(e){exportError.value=e instanceof Error?e.message:'下载未完成，可重试。'}}
function download(){
 const url=URL.createObjectURL(new Blob([cmdbBlankCsv()],{type:'text/csv;charset=utf-8'}));urls.add(url)
 const link=document.createElement('a');link.href=url;link.download='CMDB-33列-本地预检模板.csv';document.body.append(link);link.click();link.remove()
 setTimeout(()=>{URL.revokeObjectURL(url);urls.delete(url)},1000)
}
watch(()=>[props.catalog.tenant_id,props.catalog.catalog_hash],clear,{flush:'sync'})
watch(filter,()=>{page.value=0})
onUnmounted(()=>{alive=false;clear();for(const url of urls)URL.revokeObjectURL(url);urls.clear()})
</script>
<template>
 <section class="cmdb-preflight" aria-label="CMDB 导入本地预检">
  <h3>33 列导入本地预检</h3>
  <p>文件只在当前浏览器内存中解析，不上传、不保存、不创建资产。支持 UTF-8 CSV，最多 500 条记录、1 MiB；与现有 15 列导入流程分开。</p>
  <div class="preflight-actions"><el-button @click="download">下载 33 列空 CSV 模板</el-button><label>选择待检查的 CSV <input ref="fileInput" type="file" accept=".csv,text/csv" :disabled="busy" @change="choose"></label><el-button v-if="busy||result||error" @click="reset">{{busy?'取消读取':'清除本地预检'}}</el-button></div>
  <p v-if="busy" role="status">正在读取本地文件…</p>
  <div ref="summary" tabindex="-1" class="preflight-summary">
   <p v-if="error" role="alert">{{error}} 可重新选择文件；原文件没有被修改。</p>
   <template v-if="result"><p role="status">{{filename}}：{{result.records}} 条记录，{{result.errorRows}} 条含错误，{{result.warningRows}} 条含警告（可重叠）。</p>
    <p class="pending">尚不能执行导入：未检查数据库重复/配额、IP 或域名有效性、站点/成员/业务引用、凭据与模板绑定，也未解决客户来源差异。零错误仅表示本地有限检查未发现问题，不是导入许可。</p>
   </template>
  </div>
  <template v-if="result">
   <el-button @click="exportFindings">导出全部预检问题 CSV</el-button><p v-if="exportStatus" role="status">{{exportStatus}}</p><p v-if="exportError" role="alert">{{exportError}}</p>
   <label>筛选检查结果 <select v-model="filter"><option value="ALL">全部问题</option><option value="ERROR">错误</option><option value="WARNING">警告</option></select></label>
   <p v-if="!issues.length" role="status">当前筛选没有问题记录；其他未执行检查仍需完成。</p>
   <div v-else class="preflight-scroll" role="region" aria-label="逐行问题，可横向滚动" tabindex="0"><table><caption>数据记录定位（表头为第 1 条；带换行的引号单元格仍属于同一记录）</caption><thead><tr><th scope="col">记录</th><th scope="col">列</th><th scope="col">级别</th><th scope="col">原因与建议</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="(item,index) in pageIssues" :key="`${page}-${index}`"><td>{{item.record}}</td><td>{{item.column===null?'整条记录':CMDB_IMPORT_COLUMNS[item.column]?.label}}</td><td>{{item.level==='ERROR'?'错误':'警告'}}</td><td>{{item.message}}</td><td><el-button :aria-label="`查看第 ${item.record} 条记录，${item.column===null?'整条记录':CMDB_IMPORT_COLUMNS[item.column]?.label}`" @click="locate(item.record)">查看原值</el-button></td></tr></tbody></table></div>
   <nav v-if="issues.length>25" aria-label="检查问题分页"><el-button :disabled="page===0" @click="page--">上一页</el-button><span>第 {{page+1}} / {{Math.ceil(issues.length/25)}} 页，共 {{issues.length}} 项</span><el-button :disabled="(page+1)*25>=issues.length" @click="page++">下一页</el-button></nav>
   <label>查看记录原值 <select :value="selected??''" @change="locate(Number(($event.target as HTMLSelectElement).value))"><option value="" disabled>选择数据记录</option><option v-for="(_,index) in result.rows" :key="index" :value="index+2">第 {{index+2}} 条记录</option></select></label>
   <section v-if="selectedValues" ref="detail" tabindex="-1" class="preflight-detail" :aria-label="`第 ${selected} 条记录原值`"><h4>第 {{selected}} 条记录原值</h4><p>只读显示，修正请编辑源文件后重新选择；不会自动修剪或改写。</p><dl><template v-for="(value,index) in selectedValues" :key="index"><dt>{{CMDB_IMPORT_COLUMNS[index]?.label??`额外第 ${index+1} 列`}}</dt><dd>{{value===''?'（空）':value}}</dd></template></dl></section>
  </template>
 </section>
</template>
<style scoped>
.cmdb-preflight{border-top:1px solid #dce4ee;margin-top:20px;padding-top:12px;font-size:14px;line-height:1.7}.preflight-actions{display:flex;align-items:center;flex-wrap:wrap;gap:12px}.cmdb-preflight label{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin:12px 0}.cmdb-preflight input{max-width:100%;min-width:0}.cmdb-preflight select,.cmdb-preflight .el-button{min-height:44px}.cmdb-preflight .el-button{margin:0}.preflight-summary:focus-visible,.preflight-detail:focus-visible,.preflight-scroll:focus-visible{outline:3px solid #175cd3;outline-offset:2px}.pending{background:#fff8e8;border:1px solid #e8cf91;padding:12px}.preflight-scroll{overflow:auto;max-height:420px}table{width:100%;border-collapse:collapse;text-align:left}th,td{border-bottom:1px solid #dce5ef;padding:8px;min-width:64px;vertical-align:top}td:nth-child(4){min-width:220px}caption{text-align:left;padding:8px}nav{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:12px 0}dl{display:grid;grid-template-columns:minmax(90px,1fr) minmax(0,3fr);margin:0}dt,dd{margin:0;padding:8px;border-bottom:1px solid #dce5ef;overflow-wrap:anywhere;white-space:pre-wrap}.preflight-detail{margin-top:16px}
</style>
