<script setup lang="ts">
import {computed,nextTick,ref,watch,type DeepReadonly} from 'vue'
import type {AssetImportParsedArtifact} from '../api/assetImportTypes'
const props=defineProps<{artifact:DeepReadonly<AssetImportParsedArtifact>}>()
const page=ref(0),status=ref<HTMLElement>(),size=25
const pages=computed(()=>Math.max(1,Math.ceil(props.artifact.rows.length/size)))
const rows=computed(()=>props.artifact.rows.slice(page.value*size,(page.value+1)*size))
watch(()=>props.artifact,()=>{page.value=0},{flush:'sync'})
async function move(offset:number){const next=page.value+offset;if(next<0||next>=pages.value)return;page.value=next;await nextTick();status.value?.focus()}
</script>
<template>
 <section class="import-raw-preview" aria-label="源文件原始数据分页预览">
  <p ref="status" tabindex="-1" role="status">共 {{artifact.rows.length}} 条原始数据，第 {{page+1}} / {{pages}} 页，每页最多 25 条。原文保留，不执行公式或导入。</p>
  <p v-if="!rows.length">当前解析结果没有数据记录。</p>
  <div v-else class="raw-scroll" tabindex="0" role="region" aria-label="原始单元格内容，可滚动查看"><table><caption>所有解析记录的原始单元格，可使用下方分页查看</caption><thead><tr><th scope="col">逻辑记录 / 源行号</th><th scope="col">原始内容</th></tr></thead><tbody>
   <tr v-for="row in rows" :key="row.logical_record_number"><td>{{row.logical_record_number}} / {{row.source_row_number}}<br>文件行 {{row.physical_start_line??'—'}} 至 {{row.physical_end_line??'—'}}</td><td><p v-for="cell in row.cells" :key="cell.column_index">列 {{cell.column_index}} [{{cell.kind}}] 文本={{cell.lexical===null?'空':cell.lexical}} · 显示={{cell.display===null?'空':cell.display}}<template v-if="cell.format_code"> · 格式={{cell.format_code}}</template></p></td></tr>
  </tbody></table></div>
  <nav v-if="pages>1" aria-label="原始数据分页"><el-button :disabled="page===0" @click="move(-1)">上一页原始数据</el-button><el-button :disabled="page+1>=pages" @click="move(1)">下一页原始数据</el-button></nav>
 </section>
</template>
<style scoped>
.import-raw-preview{margin-top:16px;line-height:1.7}.raw-scroll{overflow:auto;max-height:480px}table{border-collapse:collapse;width:100%;text-align:left}caption{text-align:left;padding:8px}th,td{padding:8px;border-bottom:1px solid #dce5ef;vertical-align:top}td:first-child{min-width:120px}td:nth-child(2){min-width:240px}td p{margin:3px 0;white-space:pre-wrap;overflow-wrap:anywhere}nav{display:flex;gap:12px;flex-wrap:wrap;margin-top:12px}.el-button{min-height:44px;height:auto;margin:0;white-space:normal}[tabindex]:focus-visible{outline:3px solid #175cd3;outline-offset:2px}
</style>
