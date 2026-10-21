<script setup lang="ts">
import {computed,ref,shallowRef,watch,onUnmounted,nextTick} from 'vue'
import {api,isCancelled} from '../api/client'
import {session,can,switchingTenant,contextGeneration} from '../stores/session'
import type {BackupPlanDetail} from '../utils/backupPlanDrafts'
import {validPreviewDate,readBackupSchedulePreview,type BackupSchedulePreview} from '../utils/backupSchedulePreview'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{plan:BackupPlanDetail}>()
const date=ref(''),busy=ref(false),error=shallowRef<unknown>(null),result=shallowRef<BackupSchedulePreview|null>(null),input=ref<HTMLInputElement|null>(null)
const allowed=computed(()=>session.value?.mode==='remote_test'&&session.value.user.role==='padmin'&&session.value.tenant.id===props.plan.tenant_id&&can('backup_plan_drafts:read')&&!switchingTenant.value)
let epoch=0,abort:AbortController|null=null,alive=true
function reset(){++epoch;abort?.abort();abort=null;busy.value=false;error.value=null;result.value=null}
async function cancel(){reset();await nextTick();input.value?.focus()}
async function load(){
 if(!alive||!allowed.value||busy.value||!validPreviewDate(date.value))return
 reset();const turn=epoch,controller=new AbortController(),plan={...props.plan},requested=date.value;abort=controller;busy.value=true
 try{const raw=await api.request(`/backup-plan-drafts/${plan.id}/schedule-preview?date=${requested}&expected_version=${plan.version}`,{method:'GET',signal:controller.signal});if(alive&&turn===epoch&&!controller.signal.aborted)result.value=readBackupSchedulePreview(raw,plan,requested)}
 catch(cause){if(alive&&turn===epoch&&!isCancelled(cause))error.value=cause}
 finally{if(alive&&turn===epoch){busy.value=false;abort=null}}
}
watch(()=>[contextGeneration.value,session.value?.user.id,session.value?.tenant.id,session.value?.permission_version,session.value?.user.role,allowed.value,props.plan.id,props.plan.version,props.plan.daily_local_time,props.plan.archived,props.plan.requested_enabled],()=>{reset();date.value=''},{flush:'sync'})
watch(date,reset,{flush:'sync'})
onUnmounted(()=>{alive=false;reset()})
</script>
<template>
<section v-if="allowed" class="schedule-preview" :aria-busy="busy">
 <h3>指定日期的时间预览（未发布）</h3>
 <p>按当前草稿 v{{plan.version}} 的 Asia/Shanghai 时间换算。不是下次执行时间，不创建任务、不连接设备；归档草稿也仅供核对日期。</p>
 <form @submit.prevent="load"><label>明确预览日期<input ref="input" v-model="date" type="date" min="0001-01-01" max="9999-12-31" required /></label><button :disabled="busy||!validPreviewDate(date)">{{busy?'预览中…':'预览指定日期'}}</button><button v-if="busy" type="button" @click="cancel">取消预览</button></form>
 <p v-if="date&&!validPreviewDate(date)" role="alert">请选择有效日期。</p>
 <ApiErrorNotice :error="error" :retrying="busy" @retry="load" />
 <p v-if="error">旧预览已清除；如计划版本冲突，请先重新读取计划再预览。</p>
 <div role="status"><template v-if="result"><p>请求本地时间：{{result.requested_local_date_time}}（Asia/Shanghai）</p><p v-if="result.candidate_instant">UTC 候选时间：{{result.candidate_instant}}</p><p v-else>{{result.resolution==='LOCAL_TIME_GAP'?'该本地时间不存在':'该本地时间对应多个 UTC 时刻'}}，不自动选择执行时刻。</p><p>执行授权：否；已创建任务：否；补跑规则：未配置。</p></template><p v-else>{{busy?'正在核对指定日期…':'尚无日期预览。'}}</p></div>
</section>
</template>
<style scoped>
.schedule-preview{margin-top:18px;padding:12px;border:1px solid #8293a6;min-width:0;color:#24374a}.schedule-preview p{line-height:1.7;overflow-wrap:anywhere}.schedule-preview form{display:flex;gap:12px;flex-wrap:wrap;align-items:end}.schedule-preview label{display:flex;flex-direction:column;gap:6px;max-width:100%}.schedule-preview input,.schedule-preview button{box-sizing:border-box;min-height:44px;max-width:100%;padding:8px;font:inherit;border:1px solid #8293a6;border-radius:4px;background:white;color:#24374a}.schedule-preview button:disabled{opacity:.6}.schedule-preview :focus-visible{outline:3px solid #176bb5;outline-offset:3px}
</style>
