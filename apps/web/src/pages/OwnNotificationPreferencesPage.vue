<script setup lang="ts">
import {computed,onUnmounted,reactive,ref,shallowRef,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '../utils/feedback'
import {api} from '../api/client'
import {can,contextGeneration,session,switchingTenant} from '../stores/session'
import {contextTransitionGuards} from '../utils/contextTransitionGuard'
import {createOwnPreferenceWorkspace,emptyOwnPreferenceState,type OwnPreferenceState} from '../utils/ownPreferenceWorkspace'
import {ownPreferenceChannels,ownPreferenceDefinition,readOwnPreferencePayload,type OwnPreferenceChannel,type OwnPreferenceDefinition,type OwnPreferenceSnapshot} from '../utils/ownNotificationPreferences'
import {displayTime} from '../utils/display'
import ReadRecoveryActions from '../components/ReadRecoveryActions.vue'
import ApiErrorNotice from '../components/ApiErrorNotice.vue'
import OwnPreferenceFacts from '../components/OwnPreferenceFacts.vue'
import NotificationPreferencePublication from '../components/NotificationPreferencePublication.vue'
const fresh=()=>({level:'',channelsMode:'unknown',channels:[] as OwnPreferenceChannel[],quietMode:'absent',start:'',end:''})
const form=reactive(fresh()),editing=ref(false),dirty=ref(false),acknowledge=ref(false),confirming=ref(false),validation=ref(''),expanded=ref<number|null>(null)
const baseline=shallowRef<OwnPreferenceSnapshot|null>(null),state=shallowRef<OwnPreferenceState>(emptyOwnPreferenceState()),workspace=createOwnPreferenceWorkspace(api,s=>{state.value=s})
const readScope=ref(0)
const publicationOpen=ref(false)
let disposed=false
const permitted=computed(()=>Boolean(session.value?.mode==='remote_test'&&['viewer','tadmin','msp','padmin'].includes(session.value.user.role)&&can('notification_preferences:read_own')&&!switchingTenant.value))
const writable=computed(()=>permitted.value&&can('notification_preferences:write_own'))
const contextKey=computed(()=>JSON.stringify([contextGeneration.value,session.value?.tenant.id,session.value?.user.id,session.value?.permission_version,session.value?.account_version,session.value?.user.role,permitted.value,writable.value]))
const busy=computed(()=>Boolean(state.value.busy)||confirming.value),locked=computed(()=>busy.value||state.value.attempt!==null||!editing.value||!writable.value)
const currentPreference=computed(()=>state.value.current?.current??null)
const quietSummary=computed(()=>{const q=currentPreference.value?.requested_quiet_window;return q?.enabled===true?`${q.start} — ${q.end}`:q?.enabled===false?'明确关闭':'未指定'})
const historyId=computed(()=>state.value.current?.current?.id??state.value.receipt?.id??baseline.value?.id??null)
const originalPayload=computed(()=>state.value.attempt?JSON.parse(state.value.attempt.payload):null)
const channelLabels:Record<OwnPreferenceChannel,string>={EMAIL:'邮件',SMS:'短信',DINGTALK:'钉钉',WECHAT_WORK:'企业微信',WEBHOOK:'Webhook',VOICE:'语音'}
function applyDefinition(d:OwnPreferenceDefinition){const q=d.requested_quiet_window;Object.assign(form,{level:d.requested_level??'',channelsMode:d.requested_channels===null?'unknown':'explicit',channels:d.requested_channels===null?[]:[...d.requested_channels],quietMode:q===null?'absent':q.enabled===null?'unknown':q.enabled?'enabled':'disabled',start:q?.start??'',end:q?.end??''})}
function definition():OwnPreferenceDefinition{return{requested_level:(form.level||null) as OwnPreferenceDefinition['requested_level'],requested_channels:form.channelsMode==='unknown'?null:[...form.channels],requested_quiet_window:form.quietMode==='absent'?null:{enabled:form.quietMode==='unknown'?null:form.quietMode==='enabled',start:form.quietMode==='enabled'?form.start:null,end:form.quietMode==='enabled'?form.end:null}}}
function changed(){dirty.value=true;acknowledge.value=false;validation.value=''}
function change(key:'level'|'channelsMode'|'quietMode'|'start'|'end',value:string){if(disposed||locked.value)return false;if(key==='level'&&!['','P1_ONLY','P1_P2','ALL'].includes(value)||key==='channelsMode'&&!['unknown','explicit'].includes(value)||key==='quietMode'&&!['absent','unknown','enabled','disabled'].includes(value))return false;form[key]=value;if(key==='channelsMode'&&value==='unknown')form.channels=[];if(key==='quietMode'&&value!=='enabled'){form.start='';form.end=''}changed();return true}
function toggleChannel(channel:OwnPreferenceChannel,selected:boolean){if(disposed||locked.value||form.channelsMode!=='explicit'||!ownPreferenceChannels.includes(channel))return false;const values=new Set(form.channels);if(selected)values.add(channel);else values.delete(channel);form.channels=[...values].sort();changed();return true}
function changeAck(value:boolean){if(disposed||locked.value)return false;acknowledge.value=value;return true}
const value=(e:Event)=>(e.target as HTMLInputElement).value,checked=(e:Event)=>(e.target as HTMLInputElement).checked
watch(contextKey,()=>{readScope.value++;Object.assign(form,fresh());editing.value=false;dirty.value=false;acknowledge.value=false;confirming.value=false;validation.value='';expanded.value=null;baseline.value=null;const s=session.value;workspace.reset(!disposed&&permitted.value&&s?{tenantId:s.tenant.id,actorId:s.user.id,permissionVersion:s.permission_version,role:s.user.role,canRead:true,canWrite:writable.value}:null);if(!disposed&&permitted.value)void workspace.load()},{immediate:true,flush:'sync'})
watch(()=>state.value.history,()=>{if(!state.value.history?.items.some(r=>r.version===expanded.value))expanded.value=null})
async function refresh(){if(disposed||!permitted.value||busy.value)return false;return workspace.load()}
async function retryRead(){if(disposed||!permitted.value||busy.value)return false;return workspace.retryRead()}
async function loadHistory(next=false){const id=historyId.value;if(disposed||!permitted.value||busy.value||!id)return false;return workspace.history(id,next)}
async function discard(){if(workspace.unresolved()){ElMessage.warning('原保存请求正在提交或结果未知，请先恢复完全相同的原请求；刷新不能确认原结果');return false}if(confirming.value)return false;if(!dirty.value&&!state.value.attempt)return true;const turn=contextKey.value;confirming.value=true;try{await ElMessageBox.confirm('继续会清除本页未保存编辑和原收据显示；服务器已保存的请求及历史仍保留，确认继续？','离开本人偏好编辑',{confirmButtonText:'清除并继续',cancelButtonText:'留在本页',type:'warning'});return!disposed&&turn===contextKey.value&&!workspace.unresolved()}catch{return false}finally{if(!disposed&&turn===contextKey.value)confirming.value=false}}
async function startEditing(){if(disposed||!writable.value||busy.value||!state.value.current)return false;const turn=contextKey.value,candidate=structuredClone(state.value.current);if(!await discard()||disposed||turn!==contextKey.value||!workspace.begin())return false;readScope.value++;baseline.value=candidate.current;applyDefinition(candidate.current?ownPreferenceDefinition(candidate.current):{requested_level:null,requested_channels:null,requested_quiet_window:null});editing.value=true;dirty.value=false;acknowledge.value=false;validation.value='';return true}
async function submit(){if(disposed||locked.value||!acknowledge.value)return false;if(state.value.current&&(baseline.value?state.value.current.current?.version!==baseline.value.version:state.value.current.exists)){validation.value='独立回读的当前版本已变化，请以当前版本重新开始编辑；未发送保存请求';return false}const action=baseline.value?'UPDATE':'CREATE';let input;try{input=readOwnPreferencePayload({expected_version:baseline.value?.version??0,intent:'SAVE_REQUEST_DRAFT_ONLY',...definition()},action)}catch{validation.value='请求未保存：请核对等级、唯一渠道和两个不同的HH:mm静音边界';return false}validation.value='';return workspace.submit(action,input,baseline.value)}
async function retry(){if(disposed||!writable.value||busy.value)return false;return workspace.retry()}
function unlockRejected(){if(disposed||!writable.value||busy.value||state.value.attempt?.state!=='rejected'||!workspace.begin())return false;readScope.value++;acknowledge.value=false;dirty.value=true;return true}
function expand(version:number){if(!state.value.history?.items.some(r=>r.version===version))return;expanded.value=expanded.value===version?null:version}
onBeforeRouteLeave(discard);const unregister=contextTransitionGuards.register(discard)
function beforeUnload(event:BeforeUnloadEvent){if(dirty.value||state.value.attempt||workspace.unresolved()){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload);onUnmounted(()=>{disposed=true;unregister();workspace.dispose();window.removeEventListener('beforeunload',beforeUnload)})
defineExpose({form,state,baseline,editing,dirty,acknowledge,locked,change,toggleChannel,changeAck,definition,refresh,startEditing,submit,retry,unlockRejected,loadHistory,expand,discard,cancelRead:workspace.cancelRead,retryRead,hasUnresolved:workspace.unresolved})
</script>
<template>
 <section class="own-preference-page"><h1 class="page-title-sr">个人通知偏好设置 </h1>
 
 <p class="panel" v-if="!permitted">当前身份没有本人偏好读取权限，或正在切换租户；未展示旧数据</p>
 <template v-else>
  
  <div class="notification-channels"><article v-for="channel in ownPreferenceChannels" :key="channel" class="proto-panel notification-channel"><div class="proto-section-head"><h2>{{channelLabels[channel]}}</h2><span class="proto-badge">{{ !state.current ? '未读取' : !state.current.exists ? '无草稿' : currentPreference?.requested_channels === null ? '未指定' : currentPreference?.requested_channels.includes(channel) ? '已请求' : '未请求' }}</span></div><div class="channel-content"><span>当前渠道偏好</span><strong>{{ !state.current ? '尚未读取' : !state.current.exists ? '尚无请求草稿' : state.current.current?.requested_channels === null ? '未指定' : state.current.current?.requested_channels.includes(channel) ? '已请求此渠道' : '未请求此渠道' }}</strong><small>待通知策略启用后生效</small></div></article></div>
  <div class="preference-workspace">

  <section class="panel current-preference"><div class="actions"><h2>当前通知偏好</h2><button type="button" :disabled="busy" @click="refresh">刷新当前记录</button></div><p>仅限维护本人在当前租户下的通知偏好设置。</p>
   <p v-if="!state.current">{{state.busy==='read'?'正在读取，是否已有请求未知':state.error?'读取未通过，是否已有请求未知':'尚未读取，是否已有请求未知'}}</p>
   <template v-else><p v-if="!state.current.exists" class="no-preference">当前本人没有已保存请求；没有自动创建默认偏好</p><div v-else-if="state.current.current"><h3>当前版本 v{{state.current.current.version}}</h3><p>{{displayTime(state.current.current.updated_at)}}（Asia/Shanghai）</p><OwnPreferenceFacts :value="state.current.current" /></div><button v-if="writable" type="button" :disabled="busy||workspace.unresolved()" @click="startEditing">{{state.current.exists?'以当前版本开始编辑':'开始新建本人请求草稿'}}</button><p v-else>当前只有读取权限</p></template>
  </section>
  <form v-form-feedback v-if="editing" class="panel preference-form" @submit.prevent="submit"><h2>编辑个人通知偏好</h2><p>当前编辑基线：{{baseline?'v'+baseline.version:'新建草稿（初始版本 v0）'}}</p>
   <fieldset :disabled="locked"><legend>通知等级、渠道与免打扰设置</legend><div class="form-grid">
    <label>接收告警等级<select aria-label="请求通知等级" :value="form.level" @change="change('level',value($event))"><option value="">暂不指定</option><option value="P1_ONLY">仅 P1 紧急告警</option><option value="P1_P2">P1 与 P2 告警</option><option value="ALL">全部告警等级</option></select></label>
    <label>渠道配置方式<select aria-label="渠道请求状态" :value="form.channelsMode" @change="change('channelsMode',value($event))"><option value="unknown">暂不指定</option><option value="explicit">指定接收渠道（支持留空）</option></select></label>
   </div><div class="channel-grid"><label class="check" v-for="channel in ownPreferenceChannels" :key="channel"><input type="checkbox" :aria-label="'请求渠道'+channelLabels[channel]" :disabled="form.channelsMode!=='explicit'" :checked="form.channels.includes(channel)" @change="toggleChannel(channel,checked($event))" />{{channelLabels[channel]}}</label></div>
   <label>免打扰时段配置<select aria-label="静音请求状态" :value="form.quietMode" @change="change('quietMode',value($event))"><option value="absent">暂不提交免打扰设置</option><option value="unknown">保留待定状态</option><option value="disabled">明确请求关闭静音</option><option value="enabled">启用指定免打扰时段</option></select></label>
   <div class="form-grid"><label>免打扰开始（HH:mm）<input aria-label="请求静音开始" :disabled="form.quietMode!=='enabled'" :value="form.start" maxlength="5" placeholder="22:00" @input="change('start',value($event))" /></label><label>免打扰结束（HH:mm）<input aria-label="请求静音结束" :disabled="form.quietMode!=='enabled'" :value="form.end" maxlength="5" placeholder="07:00" @input="change('end',value($event))" /></label></div><p class="hint">按 Asia/Shanghai 时区计算，支持跨午夜时段（起止时间不可相同）。</p>
   <label class="check acknowledge"><input type="checkbox" aria-label="确认仅保存本人请求" :checked="acknowledge" @change="changeAck(checked($event))" />我已核对所填偏好，理解当前仅保存个人偏好草稿，待平台通知策略启用后联动生效</label></fieldset>
   <p v-if="validation" role="alert" class="warning">{{validation}}</p><button type="submit" :disabled="locked||!acknowledge">保存本人请求草稿</button>
  </form>
  <ReadRecoveryActions :busy="state.busy" :can-retry="!confirming&&workspace.canRetryRead()" :scope-key="contextKey+':'+readScope" label="本人偏好" :cancel="workspace.cancelRead" :retry="retryRead" />
 <ApiErrorNotice :error="state.error" />
  <section v-if="state.attempt" class="panel original-preference"><h2>保存请求状态与回执</h2><p>状态：{{state.attempt.state==='uncertain'?'结果未知，请先重试确认保存结果':state.attempt.state==='accepted'?'已收到原保存回执':'本次提交未通过，尚未确认保存'}}</p><p v-if="originalPayload">期望基线版本 v{{originalPayload.expected_version}} · 仅保存草稿</p><p>若请求状态未明，请点击下方按钮重试原请求。</p><button v-if="state.attempt.state==='uncertain'" type="button" :disabled="busy||!writable" @click="retry">重试完全相同原请求</button><button v-if="state.attempt.state==='rejected'" type="button" :disabled="busy||!writable" @click="unlockRejected">返回修改原草稿</button><p v-if="state.attempt.state==='rejected'">若遇到版本冲突，请先刷新当前记录并以最新版本重新编辑。</p>
   <div v-if="state.receipt" class="original-receipt"><h3>原保存回执 v{{state.receipt.version}}</h3><p>{{displayTime(state.receipt.updated_at)}}（Asia/Shanghai）</p><OwnPreferenceFacts :value="state.receipt" /></div>
  </section>
  <section class="panel preference-history"><div class="actions"><h2>偏好修订历史 <small class="history-note">不是通知发送或供应商送达记录</small></h2><button type="button" :disabled="busy||!historyId" @click="loadHistory()">加载历史记录</button></div><p v-if="!state.history">尚未加载修订历史</p><template v-else><p>当前页共 {{state.history.items.length}} 条记录</p><ul><li v-for="item in state.history.items" :key="item.version"><button type="button" class="history-toggle" :aria-expanded="expanded===item.version" @click="expand(item.version)">v{{item.version}} · {{item.action==='CREATE'?'创建请求':'更新请求'}} · {{displayTime(item.recorded_at)}}</button><OwnPreferenceFacts v-if="expanded===item.version" :value="item" /></li></ul><button type="button" :disabled="busy||!state.history.has_more" @click="loadHistory(true)">下一页本人历史</button></template></section>
  </div>
  <button v-if="!publicationOpen" type="button" :disabled="busy||dirty||workspace.unresolved()" @click="publicationOpen=true">管理本人偏好的内部发布</button>
  <NotificationPreferencePublication v-if="publicationOpen" :draft-id="currentPreference?.id??null" :draft-version="currentPreference?.version??null" :blocked="busy||dirty||editing||workspace.unresolved()" />
 </template></section>
</template>
<style scoped>
.own-preference-page{min-width:0}
.own-preference-page .panel{margin-top:12px;border-radius:3px;padding:18px;min-width:0}
.own-preference-page p{line-height:1.7;overflow-wrap:anywhere;font-size:12px;color:#6f7d8e}
.own-preference-page h2{font-size:14px;margin:0}
.own-preference-page h3{font-size:13px}
.own-preference-page fieldset{border:0;padding:16px 0;border-radius:0;min-width:0}
.own-preference-page legend{padding:0 6px;font-size:12px;color:#8b96a3}
.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}
.preference-form>h2{border-left:3px solid #2d79f6;padding-left:10px}
.preference-form label{display:grid;gap:7px;min-width:0}
.own-preference-page button,.preference-form input:not([type=checkbox]),.preference-form select{min-height:32px;border-radius:3px;padding:6px 10px;font-size:12px;border:1px solid #cbd7e5;background:#fff;color:#294665;font-family:inherit}
.preference-form input:not([type=checkbox]),.preference-form select{width:100%;box-sizing:border-box}
.own-preference-page button{cursor:pointer}
.own-preference-page button[type=submit]{background:#2d79f6;border-color:#2d79f6;color:#fff}
.own-preference-page button:disabled{opacity:.5;cursor:not-allowed}
.own-preference-page :is(button,input,select):focus-visible{outline:3px solid #176bb5;outline-offset:2px}
.preference-form .check{display:flex;align-items:center;gap:10px;min-height:44px;line-height:1.6}
.preference-form input[type=checkbox]{width:20px;height:20px;flex-shrink:0}
.channel-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-top:12px}
.own-preference-page .channel-grid .check{border:1px solid #e7edf5;padding:10px;border-radius:3px;min-height:44px}
.acknowledge{margin:12px 0}
.actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap;justify-content:space-between;border-bottom:1px solid #edf1f7;padding-bottom:12px}
.warning{color:#a04913}
.hint{font-size:13px;color:#607086}
.notification-channels{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-top:12px}
.notification-channel .proto-section-head{padding:12px 16px}
.channel-content{padding:16px;display:grid;gap:8px}
.channel-content>span,.channel-content small{font-size:11px;color:#8b96a3}
.channel-content strong{font-size:14px;color:#52677f;font-weight:400}
.preference-workspace{display:grid;grid-template-columns:minmax(0,1.3fr) minmax(300px,1fr);gap:12px;align-items:start}
.preference-workspace .current-preference{grid-column:1;grid-row:1}
.preference-workspace .preference-history{grid-column:2;grid-row:1}
.preference-workspace .preference-form,.preference-workspace .original-preference{grid-column:1/-1}
.own-preference-page .original-preference{border-left:3px solid #e5ab52}
.original-receipt{border:1px solid #dce5f0;border-radius:3px;background:#f8fbff;padding:14px;min-width:0;margin-top:14px}
.preference-history ul{list-style:none;padding:0}
.preference-history li{border-bottom:1px solid #dce5f0;padding:10px 0}
.history-toggle{width:100%;text-align:left;overflow-wrap:anywhere;border:0;border-radius:0;color:#327ff2;padding:5px 0}
.own-preference-page :deep(.preference-facts){font-size:12px;gap:12px}
.preference-quiet-summary{font-size:18px!important;line-height:1.5!important;overflow-wrap:anywhere}
@media(max-width:1000px){.preference-workspace{grid-template-columns:1fr}.preference-workspace .preference-history{grid-column:1;grid-row:auto}}
@media(max-width:760px){.form-grid{grid-template-columns:1fr}.channel-grid,.notification-channels{grid-template-columns:repeat(2,minmax(0,1fr))}.own-preference-page .panel{padding:14px}.own-preference-page button,.preference-form input:not([type=checkbox]),.preference-form select{min-height:44px}.preference-quiet-summary{font-size:16px!important}}
</style>
