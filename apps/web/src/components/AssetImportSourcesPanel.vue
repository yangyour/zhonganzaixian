<script setup lang="ts">
import {computed,nextTick,onUnmounted,ref,shallowRef,watch} from 'vue'
import {api,isCancelled} from '../api/client'
import {session,contextGeneration,can,switchingTenant} from '../stores/session'
import {readImportSourcePage,type ImportSourcePage,type ImportSourceItem} from '../utils/assetImportSources'
import ApiErrorNotice from './ApiErrorNotice.vue'
const storageLabels={PENDING:'处理中',READY:'已校验',FAILED:'失败'},parseLabels={NOT_REQUESTED:'未请求',PENDING:'处理中',PARSED:'已解析',SELECTION_REQUIRED:'待选择工作表',FAILED:'失败'}
const props=defineProps<{canOpen:boolean}>(),emit=defineEmits<{select:[id:string]}>()
const visible=ref(false),busy=ref(false),result=shallowRef<ImportSourcePage|null>(null),error=shallowRef<unknown>(null),summary=ref<HTMLElement>(),readButton=ref<{ $el:HTMLButtonElement }>()
const permitted=computed(()=>session.value?.mode==='remote_test'&&can('assets:write')&&!switchingTenant.value)
const scope=computed(()=>JSON.stringify([contextGeneration.value,session.value?.user.id,session.value?.tenant.id,session.value?.permission_version,session.value?.account_version,session.value?.user.role,permitted.value]))
type Position={cursor:string|null;anchor?:ImportSourceItem}
let disposed=false,epoch=0,controller:AbortController|undefined,position:Position={cursor:null},history:Position[]=[],attempt={position,history}
const pageNumber=ref(1)
function clear(){++epoch;controller?.abort();visible.value=false;busy.value=false;result.value=null;error.value=null;position={cursor:null};history=[];attempt={position,history};pageNumber.value=1}
async function read(target:Position={cursor:null},stack:Position[]=[]){
 if(disposed||!permitted.value||busy.value)return
 ++epoch;const turn=epoch,ctx={tenantId:session.value!.tenant.id,actorId:session.value!.user.id};controller?.abort();controller=new AbortController();const signal=controller.signal;visible.value=true;busy.value=true;result.value=null;error.value=null;attempt={position:target,history:stack}
 try{const raw=await api.request<unknown>(`/assets/import-sources?limit=20${target.cursor?'&cursor='+encodeURIComponent(target.cursor):''}`,{signal});if(turn!==epoch||signal.aborted)return;result.value=readImportSourcePage(raw,ctx,20,target.anchor);position=target;history=stack;pageNumber.value=stack.length+1;await nextTick();if(turn===epoch)summary.value?.focus()}
 catch(cause){if(turn===epoch&&!isCancelled(cause))error.value=cause}finally{if(turn===epoch)busy.value=false}
}
function retry(){return read(attempt.position,attempt.history)}
function next(){const data=result.value;if(!data?.next_cursor)return;return read({cursor:data.next_cursor,anchor:data.items.at(-1)!},[...history,position])}
function previous(){if(!history.length)return;return read(history.at(-1)!,history.slice(0,-1))}
function select(id:string){if(props.canOpen&&!busy.value&&permitted.value&&result.value?.items.some(item=>item.id===id))emit('select',id)}
async function cancel(){const turn=++epoch;controller?.abort();busy.value=false;result.value=null;error.value=null;await nextTick();if(!disposed&&turn===epoch&&permitted.value)readButton.value?.$el.focus()}
watch(scope,clear,{flush:'sync'});onUnmounted(()=>{disposed=true;clear()})
</script>
<template>
 <section v-if="permitted" class="owned-import-sources" aria-label="我的暂存来源">
  <h3>继续已有来源</h3>
  <div class="source-actions"><el-button ref="readButton" :disabled="busy" @click="read()">查看我的暂存来源</el-button><el-button v-if="visible&&!busy" @click="retry">重新读取本页来源</el-button><el-button v-if="busy" @click="cancel">取消读取来源列表</el-button></div>
  <p v-if="busy" role="status">正在读取来源记录…</p><ApiErrorNotice v-if="error" :error="error" :on-retry="retry"/>
  <template v-if="result"><p ref="summary" tabindex="-1" role="status">第 {{pageNumber}} 页，当前 {{result.items.length}} 条来源记录。不是资产入库记录。</p>
   <p v-if="!canOpen">需先成功读取解析规范，并完成当前请求核对；如已有文件或来源，请先关闭当前来源或重新选择文件。</p>
   <p v-if="!result.items.length">当前页没有来源记录。</p>
   <div v-else class="sources-scroll" tabindex="0" role="region" aria-label="暂存来源列表，可滚动查看"><table><caption>当前创建者的来源记录，按受理时间倒序</caption><thead><tr><th scope="col">文件</th><th scope="col">阶段</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="item in result.items" :key="item.id"><th scope="row">{{item.original_name}}<br>{{item.declared_format}} · {{item.size_bytes}} bytes<br>{{item.created_at}}<br>来源编号 {{item.id}}</th><td>存储 {{storageLabels[item.storage_status]}}<br>解析 {{parseLabels[item.parse_status]}}<br>映射{{item.mapping_saved?'已保存':'未保存'}} · v{{item.version}}</td><td><el-button :disabled="!canOpen||busy" :aria-label="`重新打开 ${item.original_name}，来源 ${item.id}`" @click="select(item.id)">重新打开</el-button></td></tr></tbody></table></div>
   <nav aria-label="来源列表分页"><el-button :disabled="pageNumber===1||busy" @click="previous">上一页来源</el-button><el-button :disabled="!result.next_cursor||busy" @click="next">下一页来源</el-button></nav>
  </template>
 </section>
</template>
<style scoped>
.owned-import-sources{margin-top:16px;border-top:1px solid #dce4ee;padding-top:12px;line-height:1.7}.source-actions,nav{display:flex;flex-wrap:wrap;gap:12px;margin:12px 0}.el-button{min-height:44px;height:auto;white-space:normal;margin:0}.sources-scroll{overflow:auto;max-height:480px}table{width:100%;border-collapse:collapse;text-align:left}caption{text-align:left}th,td{padding:8px;border-bottom:1px solid #dce5ef;vertical-align:top;min-width:110px;overflow-wrap:anywhere}tbody th{min-width:220px;font-weight:400}[tabindex]:focus-visible{outline:3px solid #175cd3;outline-offset:2px}
</style>
