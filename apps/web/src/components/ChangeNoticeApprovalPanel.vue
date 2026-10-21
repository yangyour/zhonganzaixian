<script setup lang="ts">
import {computed,onUnmounted,ref,shallowRef,watch} from 'vue'
import {ElMessageBox} from 'element-plus'
import {api} from '../api/client'
import type {ChangeNoticeContext,ChangeNoticeDetail} from '../utils/changeNoticeDrafts'
import {createApprovalWorkspace,emptyApprovalState,type ApprovalWorkspaceState} from '../utils/changeNoticeApprovalWorkspace'
import type {ApprovalAction,ApprovalStatus} from '../utils/changeNoticeApproval'
import {displayTime} from '../utils/display'
import ApiErrorNotice from './ApiErrorNotice.vue'
const props=defineProps<{candidate:ChangeNoticeDetail|null;context:ChangeNoticeContext|null;contextKey:string;blocked?:boolean}>()
const state=shallowRef<ApprovalWorkspaceState>(emptyApprovalState()),note=ref(''),targetPage=ref(0)
const workspace=createApprovalWorkspace(api,s=>{state.value=s})
const labels:Record<ApprovalAction,string>={SUBMIT:'提交此修订审核',WITHDRAW:'撤回此修订',APPROVE:'批准此修订',REJECT:'驳回此修订'}
const statuses:Record<ApprovalStatus,string>={DRAFT:'待提交',IN_REVIEW:'审核中',APPROVED:'已批准（未生效）',REJECTED:'已驳回',WITHDRAWN:'已撤回'}
const changedTarget=computed(()=>state.value.target&&props.candidate&&(state.value.target.id!==props.candidate.id||state.value.target.version!==props.candidate.version))
const newerDraft=computed(()=>state.value.target&&props.candidate?.id===state.value.target.id&&props.candidate.version>state.value.target.version)
const staleBaseline=computed(()=>state.value.baseline&&state.value.current&&state.value.baseline.version!==state.value.current.version)
const locked=computed(()=>Boolean(props.blocked)||!props.context?.canWrite||Boolean(state.value.busy)||Boolean(state.value.attempt)||!state.value.current||Boolean(staleBaseline.value)||Boolean(newerDraft.value))
const actions=computed(()=>state.value.baseline?.allowed_actions.filter(a=>state.value.current?.allowed_actions.includes(a))??[])
const dirty=computed(()=>Boolean(state.value.baseline)&&state.value.attempt?.state!=='accepted'&&note.value!=='')
watch(()=>props.contextKey,()=>{workspace.reset(props.context);note.value='';targetPage.value=0},{immediate:true,flush:'sync'})
async function mayLeave(){if(workspace.unresolved())return false;if(!dirty.value)return true;try{await ElMessageBox.confirm('审批说明尚未保存，是否放弃本次输入？','保留审批输入',{confirmButtonText:'放弃输入',cancelButtonText:'保留输入',type:'warning'});return true}catch{return false}}
async function pin(){if(!props.candidate||state.value.busy||workspace.unresolved())return false;const turn=props.contextKey,target=structuredClone(props.candidate);if(!await mayLeave()||turn!==props.contextKey)return false;note.value='';targetPage.value=0;return workspace.pin(target)}
function prepare(){if(props.blocked||!workspace.prepare())return false;return true}
async function submit(action:ApprovalAction){
 if(locked.value||!actions.value.includes(action)||!state.value.baseline||!state.value.target)return false
 const turn=props.contextKey,base=state.value.baseline,target=state.value.target,input=note.value
 try{await ElMessageBox.confirm(`${labels[action]}：${target.id}，草稿修订 v${target.version}，审批版本 v${base.version}。只记录内部审核，不生效、不执行设备变更、不抑制告警。是否确认？`,'确认明确修订审批',{confirmButtonText:labels[action],cancelButtonText:'取消',type:'warning'})}catch{return false}
 if(turn!==props.contextKey||base.version!==state.value.baseline?.version||target.id!==state.value.target?.id||target.version!==state.value.target?.version||target.content_sha256!==state.value.target?.content_sha256||input!==note.value||locked.value)return false
 return workspace.submit(action,input===''?null:input)
}
onUnmounted(()=>workspace.dispose())
defineExpose({pin,prepare,submit,mayLeave,unresolved:workspace.unresolved,hasUnsaved:()=>dirty.value,state,note,refresh:workspace.refresh,retry:workspace.retry})
</script>
<template>
 <section class="approval-panel panel" aria-label="明确修订审批">
  <div class="approval-actions"><h2>明确修订审批</h2><button type="button" :disabled="!candidate||Boolean(state.busy)||workspace.unresolved()" @click="pin">读取所选草稿修订的审批</button></div>
  <p>内部批准、运行生效和设备执行分别核验。本审批不执行设备变更，不启用窗口匹配、自动P3或告警抑制。</p>
  <p v-if="!candidate&&!state.target">先选择一条已保存草稿，再明确读取该修订的审批。</p>
  <p v-if="changedTarget" role="status">草稿选择已变化；这里仍固定在原草稿修订，未自动切换审批对象。</p>
  <p v-if="newerDraft" role="alert">已知草稿存在更新修订，原修订审批动作已停用。请核对后明确读取新修订，旧审批不自动沿用。</p>
  <p v-if="blocked" role="status">草稿原请求尚未确认，审批动作暂时锁定；请先确认草稿请求结果。</p>
  <ApiErrorNotice :error="state.error" />
  <template v-if="state.target">
   <p class="approval-identity">固定对象 {{state.target.id}} · 草稿修订 v{{state.target.version}}<br />内容摘要 {{state.target.content_sha256}}</p>
   <div class="approval-actions"><button type="button" :disabled="Boolean(state.busy)" @click="workspace.refresh">刷新此修订审批</button><button type="button" :disabled="Boolean(state.busy)" @click="workspace.history(false)">读取此修订审批历史</button></div>
   <p v-if="state.busy" aria-live="polite">{{state.busy==='write'?'正在确认原请求，结果尚未知':'正在读取，状态尚未确认'}}</p>
   <section v-if="state.current" class="approval-current"><h3>{{statuses[state.current.state]}} · 审批版本 v{{state.current.version}}</h3>
    <dl><div><dt>申请人</dt><dd>{{state.current.applicant_id}}</dd></div><div><dt>提交时间（Asia/Shanghai）</dt><dd>{{state.current.submitted_at?displayTime(state.current.submitted_at):'尚未提交'}}</dd></div><div><dt>审核人</dt><dd>{{state.current.reviewer_id??'尚无决定'}}</dd></div><div><dt>决定时间（Asia/Shanghai）</dt><dd>{{state.current.decided_at?displayTime(state.current.decided_at):'尚无决定'}}</dd></div><div><dt>决定使用的审批策略发布</dt><dd>{{state.current.review_policy_release_id??'尚无决定引用'}}</dd></div><div><dt>决定说明</dt><dd class="approval-note">{{state.current.decision_note??'未填写'}}</dd></div></dl>
    <p v-if="!context?.canWrite||!state.current.allowed_actions.length">当前服务端未授权可执行审批动作。技术写权限不等于Q09审批资格；未配置或不符合策略时不会默认授权。</p>
    <button v-else type="button" :disabled="Boolean(state.busy)||workspace.unresolved()||blocked" @click="prepare">按审批版本 v{{state.current.version}} 准备动作</button>
   </section>
   <details class="approval-content"><summary>核对固定修订内容、目标与计划窗口</summary><p>申请人 {{state.target.applicant_id}}</p><p>计划开始 {{displayTime(state.target.planned_start_at)}}（Asia/Shanghai）<br />计划结束 {{state.target.planned_end_at?displayTime(state.target.planned_end_at):'未知，不补默认窗口'}}</p><p>工单 {{state.target.ticket_id??'未关联'}}</p><pre>{{state.target.change_summary}}</pre><ol :start="targetPage*10+1"><li v-for="id in state.target.asset_ids.slice(targetPage*10,targetPage*10+10)" :key="id">{{id}}</li></ol><div v-if="state.target.asset_ids.length>10" class="approval-actions"><button type="button" :disabled="targetPage===0" @click="targetPage--">上一组审批目标</button><span>{{targetPage+1}} / {{Math.ceil(state.target.asset_ids.length/10)}}</span><button type="button" :disabled="(targetPage+1)*10>=state.target.asset_ids.length" @click="targetPage++">下一组审批目标</button></div></details>
   <section v-if="state.baseline" class="approval-editor"><h3>本次输入固定审批版本 v{{state.baseline.version}}</h3><p v-if="staleBaseline" role="alert">已发现更新审批版本。未替换本次版本或说明；请核对后明确按新审批版本准备。</p><label>审批说明（驳回必填，最多2000 Unicode码点）<textarea v-model="note" aria-label="审批说明" maxlength="4000" :disabled="locked" /></label><div class="approval-actions"><button v-for="action in actions" :key="action" type="button" :disabled="locked" @click="submit(action)">{{labels[action]}}</button></div></section>
   <section v-if="state.attempt" class="approval-attempt" aria-live="polite"><h3>原审批请求 · v{{state.attempt.baseline.version}}</h3><p v-if="state.attempt.state==='uncertain'">结果未知，可能已经提交。原对象、版本、说明和幂等key已锁定；刷新不能确认原请求，也不能另发新动作。</p><p v-else-if="state.attempt.state==='rejected'">请求已明确拒绝，原说明和版本保留。请刷新核对后，明确准备动作；不会自动合并到新草稿修订。</p><p v-else>已核对原请求收据。这不是最新审批状态；后续动作须重新读取服务端权限。</p><button v-if="state.attempt.state==='uncertain'" type="button" :disabled="Boolean(state.busy)||!context?.canWrite" @click="workspace.retry">按原key和内容重试审批</button></section>
   <section v-if="state.receipt" class="approval-receipt"><h3>原请求确认收据 · 审批 v{{state.receipt.version}}</h3><p>{{statuses[state.receipt.state]}} · 草稿修订 v{{state.receipt.draft_revision}}</p><p>未生效 · 不执行设备变更 · 不抑制告警 · 自动P3决定未知</p></section>
   <section v-if="state.history" class="approval-history"><h3>不可变审批动作历史</h3><p v-if="!state.history.items.length">此页没有审批动作；不代表已批准或生效。</p><article v-for="event in state.history.items" :key="event.version"><h4>审批 v{{event.version}} · {{labels[event.action]}}</h4><p>{{statuses[event.from_state]}} → {{statuses[event.to_state]}}<br />{{displayTime(event.occurred_at)}}（Asia/Shanghai） · {{event.actor_id}}<br />权限版本 {{event.permission_version}} · 策略发布 {{event.review_policy_release_id??'不适用'}}</p><p class="approval-note">{{event.note??'未填写说明'}}</p></article><button type="button" :disabled="Boolean(state.busy)||!state.history.has_more" @click="workspace.history(true)">更早审批动作</button></section>
  </template>
 </section>
</template>
<style scoped>
.approval-panel{min-width:0;padding:18px;margin-top:16px}.approval-panel h2{font-size:16px}.approval-panel h3{font-size:14px}.approval-panel p,.approval-panel dd,.approval-panel li{font-size:13px;line-height:1.7;overflow-wrap:anywhere}.approval-actions{display:flex;align-items:center;flex-wrap:wrap;gap:10px}.approval-panel button,.approval-panel summary{min-height:44px}.approval-panel button{padding:8px 12px;border:1px solid #bccbdc;border-radius:4px;background:white;color:#294665;cursor:pointer}.approval-panel button:disabled{opacity:.5;cursor:not-allowed}.approval-panel button:focus-visible,.approval-panel textarea:focus-visible,.approval-panel summary:focus-visible{outline:3px solid #176bb5;outline-offset:2px}.approval-panel dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.approval-panel dt{color:#607086;font-size:12px}.approval-panel dd{margin:4px 0}.approval-panel label{display:grid;gap:8px;font-size:13px}.approval-panel textarea{box-sizing:border-box;width:100%;min-height:100px;padding:10px;margin-bottom:12px;font:inherit}.approval-note,.approval-panel pre{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;font:inherit}.approval-identity{background:#f5f8fc;padding:12px}.approval-editor,.approval-attempt,.approval-receipt,.approval-history{border-top:1px solid #dce5f0;margin-top:16px;padding-top:10px}.approval-history article{border-bottom:1px solid #edf1f6}.approval-content{margin-top:16px}.approval-content summary{cursor:pointer}@media(max-width:760px){.approval-panel{padding:14px}.approval-panel dl{grid-template-columns:1fr}.approval-actions{align-items:stretch}.approval-actions button{max-width:100%;white-space:normal}}
</style>
