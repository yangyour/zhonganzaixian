<script setup lang="ts">
import {computed,nextTick,onUnmounted,ref,useId,watch} from 'vue'
import type {ProposedDependency} from '../utils/assetDependencyWorkspace'
const props=defineProps<{edges:ProposedDependency[];assetId:string;assetName:string;labels:Record<string,string>;scopeKey:string;graphVersion:number}>()
const page=ref(1),jump=ref('1'),error=ref(''),status=ref<HTMLElement>(),invalidJump=ref<HTMLElement>()
const errorId=useId(),statusId=useId()
const size=25,totalPages=computed(()=>Math.max(1,Math.ceil(props.edges.length/size)))
const rows=computed(()=>props.edges.slice((page.value-1)*size,page.value*size))
const range=computed(()=>props.edges.length?`${(page.value-1)*size+1}–${Math.min(page.value*size,props.edges.length)}`:'0')
let epoch=0,disposed=false
watch(()=>[props.scopeKey,props.assetId,props.graphVersion,props.edges],()=>{epoch++;page.value=1;jump.value='1';error.value=''},{flush:'sync'})
function name(id:string){return id===props.assetId?props.assetName:props.labels[id]??'未查询到名称'}
async function move(next:number){if(disposed||!Number.isInteger(next)||next<1||next>totalPages.value)return false;const turn=++epoch,owner=props.scopeKey,active=document.activeElement;page.value=next;jump.value=String(next);error.value='';await nextTick();if(!disposed&&turn===epoch&&owner===props.scopeKey&&(document.activeElement===active||document.activeElement===document.body))status.value?.focus();return true}
async function go(){
  if(disposed)return false
  if(!/^[1-9]\d*$/.test(jump.value)||!Number.isSafeInteger(Number(jump.value))||Number(jump.value)>totalPages.value){
    const turn=++epoch,owner=props.scopeKey,active=document.activeElement
    error.value=`请输入1至${totalPages.value}的整数页码`;await nextTick()
    if(!disposed&&turn===epoch&&owner===props.scopeKey&&(document.activeElement===active||document.activeElement===document.body))invalidJump.value?.focus()
    return false
  }
  return move(Number(jump.value))
}
onUnmounted(()=>{disposed=true;epoch++})
</script>
<template>
  <section class="dependency-edge-list" aria-label="当前资产关联边明细">
    <p ref="status" :id="statusId" role="status" tabindex="-1">关联边第 {{ range }} 条 / 共 {{ edges.length }} 条 · 第 {{ page }} / {{ totalPages }} 页 · 拓扑版本 v{{ graphVersion }}</p>
    <p class="field-help">每页 25 条</p>
    <p v-if="!edges.length" role="status">当前设备暂无已登记的依赖关系草稿。</p>
    <div v-else class="dependency-edge-scroll" tabindex="0" role="region" aria-label="关联边明细，可横向滚动" :aria-describedby="statusId">
      <table><caption>{{ assetName }} 的依赖草稿关联边</caption><thead><tr><th scope="col">上游设备</th><th scope="col">下游设备</th><th scope="col">建议类型</th><th scope="col">建议抑制范围</th><th scope="col">建议启用状态</th></tr></thead>
      <tbody><tr v-for="row in rows" :key="row.parent_id+'/'+row.child_id"><td>{{ name(row.parent_id) }}<span class="dependency-edge-id">{{ row.parent_id }}</span></td><td>{{ name(row.child_id) }}<span class="dependency-edge-id">{{ row.child_id }}</span></td><td>{{ row.proposed_type ?? '待确认' }}</td><td>{{ row.proposed_suppression_scope ?? '待确认' }}</td><td>{{ row.proposed_enabled===null?'待确认':row.proposed_enabled?'建议启用':'建议停用' }}</td></tr></tbody></table>
    </div>
    <nav v-if="edges.length" class="dependency-edge-pages" aria-label="关联边分页"><button type="button" :disabled="page===1" @click="move(1)">首页</button><button type="button" :disabled="page===1" @click="move(page-1)">上一页</button><button type="button" :disabled="page===totalPages" @click="move(page+1)">下一页</button><button type="button" :disabled="page===totalPages" @click="move(totalPages)">末页</button><form @submit.prevent="go"><label>跳至页码<input v-model="jump" aria-label="关联边页码" inputmode="numeric" :aria-invalid="Boolean(error)" :aria-describedby="error ? errorId : undefined" /></label><button type="submit">跳转</button></form></nav>
    <p v-if="error" ref="invalidJump" :id="errorId" role="alert" tabindex="-1">{{ error }}</p>
  </section>
</template>
<style scoped>
.dependency-edge-list{min-width:0}.dependency-edge-scroll{overflow-x:auto;max-width:100%;border:1px solid #cbd5e1;border-radius:6px}.dependency-edge-scroll table{border-collapse:collapse;width:100%;min-width:680px;table-layout:fixed}.dependency-edge-scroll th,.dependency-edge-scroll td{padding:10px;text-align:left;vertical-align:top;border-bottom:1px solid #cbd5e1;overflow-wrap:anywhere;white-space:pre-wrap}.dependency-edge-scroll th{background:#f1f5f9}.dependency-edge-scroll caption{text-align:left;font-weight:600;padding:10px;overflow-wrap:anywhere}.dependency-edge-id{display:block;font-family:monospace;font-size:12px;overflow-wrap:anywhere}.dependency-edge-pages,.dependency-edge-pages form,.dependency-edge-pages label{display:flex;gap:8px;flex-wrap:wrap;align-items:center}.dependency-edge-pages{margin:12px 0}.dependency-edge-pages button,.dependency-edge-pages input{min-height:44px;border:1px solid #8194ac;border-radius:4px;background:#fff;color:#24384b;font:inherit;padding:8px 12px;box-sizing:border-box}.dependency-edge-pages input{width:100px}.dependency-edge-pages button{cursor:pointer}.dependency-edge-pages button:disabled{cursor:not-allowed;opacity:.55}.dependency-edge-list :focus-visible{outline:3px solid #176bb5;outline-offset:3px}.dependency-edge-list [role=alert]{color:#b91c1c}.dependency-edge-list [role=status]{font-variant-numeric:tabular-nums}
</style>
