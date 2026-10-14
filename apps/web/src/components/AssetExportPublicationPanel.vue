<script setup lang="ts">
import {computed,onUnmounted,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {createExportPublicationWorkspace,emptyPublicationState} from '../utils/assetExportPublication'
import {displayTime} from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{jobId:string;jobVersion?:number}>()
const emit=defineEmits<{changed:[]}>()
const state=shallowRef(emptyPublicationState()),confirming=ref(false)
const workspace=createExportPublicationWorkspace(api,s=>state.value=s)
let disposed=false
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&can('assets:read')&&['padmin','msp'].includes(session.value.user.role)&&!switchingTenant.value))
const context=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,props.jobId,permitted.value]))
function syncProgress(){if(!disposed&&!state.value.attempt&&state.value.current&&props.jobVersion!==undefined&&state.value.current.version!==props.jobVersion)emit('changed')}
async function refresh(){if(await workspace.read())syncProgress()}
async function recover(){if(await workspace.retry())syncProgress()}
watch(context,()=>{const s=session.value;confirming.value=false;workspace.reset(permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role,canRead:true}:null,props.jobId);if(permitted.value)void refresh()},{immediate:true,flush:'sync'})
async function publish(){if(!permitted.value||state.value.busy||state.value.attempt||confirming.value)return;const turn=context.value;confirming.value=true;let success=false;try{await ElMessageBox.confirm('发布后可取得15分钟有效的下载链接。确认发布这份已准备好的台账文件？','发布台账文件',{confirmButtonText:'确认发布',cancelButtonText:'取消',type:'warning'});if(!disposed&&turn===context.value&&permitted.value)success=await workspace.publish()}catch{}finally{if(!disposed&&turn===context.value){confirming.value=false;if(success)syncProgress()}}}
function mayLeave(){if(workspace.unresolved()||confirming.value){ElMessage.warning('发布或下载授权结果尚未确认，请先恢复原请求');return false}return true}
onBeforeRouteLeave(mayLeave);const unregister=contextTransitionGuards.register(mayLeave)
function beforeUnload(e:BeforeUnloadEvent){if(workspace.unresolved()){e.preventDefault();e.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
onUnmounted(()=>{disposed=true;workspace.dispose();unregister();window.removeEventListener('beforeunload',beforeUnload)})
defineExpose({unresolved:()=>workspace.unresolved()||confirming.value,mayLeave})
</script>
<template>
 <section class="publication-panel" aria-label="发布与下载">
  <h3>发布与下载</h3>
  <p>准备文件与发布文件分开确认。下载链接仅限当前账号使用，15分钟后失效。</p>
  <ApiErrorNotice :error="state.error" />
  <p v-if="state.busy" role="status">正在处理，请稍候…</p>
  <p v-if="state.attempt&&!state.busy" role="alert">上一次请求结果未确认。请恢复原请求，避免重复操作。</p>
  <button v-if="state.attempt&&!state.busy" type="button" @click="recover">恢复原请求</button>
  <template v-if="state.current">
   <p v-if="!state.current.publication_enabled">当前环境尚未启用文件发布。</p>
   <p v-else-if="!state.current.prepared">文件还未准备好，请稍后刷新。</p>
   <p v-else-if="state.current.status==='PUBLISHED'">文件已发布，可申请下载链接。{{state.current.row_count}} 行 · {{state.current.size_bytes}} 字节</p>
   <p v-else>文件已准备好，尚未发布。{{state.current.row_count}} 行 · {{state.current.size_bytes}} 字节</p>
   <div class="actions">
    <button v-if="state.current.publication_enabled&&state.current.prepared&&state.current.status==='NOT_PUBLISHED'" type="button" :disabled="state.busy||!!state.attempt||confirming" @click="publish">发布已准备文件</button>
    <button v-if="state.current.download_grant_available" type="button" :disabled="state.busy||!!state.attempt||confirming" @click="workspace.grant()">取得下载链接</button>
   </div>
  </template>
  <button type="button" :disabled="state.busy||confirming" @click="refresh">刷新发布状态</button>
  <p v-if="state.grant" class="download"><a :href="state.grant.content_url" referrerpolicy="no-referrer">下载台账 XLSX</a><br />有效至 {{displayTime(state.grant.expires_at)}}。过期后可重新申请链接。</p>
  <p class="hint">文件不会自动下载；站内信通知尚未接通。</p>
 </section>
</template>
<style scoped>
.publication-panel{border-top:1px solid #dbe4ef;margin-top:18px;padding-top:14px}.publication-panel p{line-height:1.7}.actions{display:flex;gap:12px;flex-wrap:wrap;margin:12px 0}.publication-panel button,.download a{display:inline-flex;align-items:center;min-height:44px;padding:9px 14px;box-sizing:border-box;border:1px solid #cbd7e5;border-radius:8px;background:#fff;color:#245a85;font:inherit;cursor:pointer}.publication-panel button:disabled{opacity:.5;cursor:not-allowed}.publication-panel :is(a,button):focus-visible{outline:3px solid #176bb5;outline-offset:2px}.hint{color:#607086;font-size:13px}.download{overflow-wrap:anywhere}
</style>
