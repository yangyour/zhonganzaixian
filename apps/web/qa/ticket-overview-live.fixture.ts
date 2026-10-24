import {createApp,h,ref} from 'vue'
import {createRouter,createWebHistory} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles/theme.css'
import Overview from '../src/components/TicketDetailOverview.vue'
import {api,ApiError} from '../src/api/client'
import {session,contextGeneration,switchingTenant} from '../src/stores/session'
import {ticket,id} from '../tests/ticketReader.fixture'
const record=ticket(),refresh=ref(0),closed=ref(false),calls=ref(0),writes=ref(0),mode=ref('normal')
const snapshot={tenant_id:id(1),ticket_id:id(10),version:2,state:'IN_PROGRESS',reopen_count:0,occurred_at:'2026-10-24T00:00:00Z',resolved_at:null,closed_at:null,response_due_at:'2026-10-24T01:00:00Z',recovery_due_at:'2026-10-24T02:00:00Z',solution_snapshot_hash:null,close_mode:null,assignee_label:'工程师',assigned_engineer_id:id(2),production_approved:false,notification_status:'NOT_INTEGRATED',sla_clock_status:'NOT_INTEGRATED'}
const clock={status:'RUNNING',deadline:'2026-10-24T01:00:00Z',stopped_at:null,elapsed_ms:1000,budget_ms:60000,breached:false,missing:null}
const sla={status:'PROJECTED',source:'postgresql',production_approved:false,clock:{tenant_id:id(1),ticket_id:id(10),tier:'STANDARD',priority:'P1',observed_at:'2026-10-24T00:00:01Z',lifecycle_version:2,configuration_reference:'sha256:'+'a'.repeat(64),calculation_policy_reference:null,clock_policy:null,response:{...clock},recovery:{...clock},paused_at:null,completed_pauses:[],reminders_delivered:false,production_approved:false}}
const pending:Array<()=>void>=[]
api.request=(async(path:string,options?:{method?:string})=>{
 calls.value++;if(options?.method==='POST'){writes.value++;throw new ApiError(503,'SYNTHETIC_NO_RUNTIME_WRITES','合成检查不会写入真实工单','SYNTHETIC')}
 let value:unknown
 if(path.endsWith('/lifecycle'))value={lifecycle:snapshot,status:'TEST_LIFECYCLE',source:'postgresql',production_approved:false}
 else if(path.endsWith('/sla'))value=sla
 else if(path.endsWith('/sla/reminders'))value={tenant_id:id(1),ticket_id:id(10),lifecycle_version:2,scan_state:'PENDING',last_outcome:null,last_scan_at:null,items:[],source:'postgresql',production_approved:false}
 else if(path.endsWith('/sla/policy'))value={tenant_id:id(1),ticket_id:id(10),lifecycle_version:2,status:'CONFIG_PENDING',release_id:null,release_version:null,clock_policy:null,bound_at:null,reference:null,missing:'SYNTHETIC_NOT_CONFIGURED',source:'postgresql',production_approved:false}
 else throw new ApiError(404,'SYNTHETIC_UNEXPECTED_PATH','未配置的合成读取','SYNTHETIC')
 if(mode.value==='pending')await new Promise<void>(resolve=>pending.push(resolve))
 return structuredClone(value)
}) as typeof api.request
session.value={user:{id:id(2),role:'msp',display_name:'合成处理人'},tenant:{id:id(1),name:'合成客户'},allowed_tenants:[],capabilities:['tickets:read','tickets:lifecycle_write'],permission_version:7,account_version:1,csrf_token:'SYNTHETIC_NO_RUNTIME',mode:'remote_test',identity_source:'synthetic-browser-only'};switchingTenant.value=false
const router=createRouter({history:createWebHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<p />'}}]})
createApp({render:()=>h('main',{style:'padding:20px;max-width:1440px;margin:auto'},[
 h('h1','工单详情合成交互检查'),h('p','真实 Vue 组件 + 合成 API 响应。不是数据库、实际设备、正式工单或客户验收。'),
 h('div',{class:'actions'},[
 h('button',{onClick:()=>{mode.value='pending';refresh.value++}},'模拟读取等待'),
 h('button',{onClick:()=>{session.value={...session.value!,account_version:(session.value?.account_version??1)+1}}},'模拟账号版本变化'),
 h('button',{onClick:()=>{pending.splice(0,1).forEach(done=>done())}},'释放一个旧读取'),
 h('button',{onClick:()=>{mode.value='normal';refresh.value++}},'恢复正常读取'),
 h('button',{onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤权')]),
 h('p',{'data-testid':'transport-count'},`读取/尝试 ${calls.value} 次 · 写入尝试 ${writes.value} 次（全部拒绝）`),
 closed.value?h('p','详情已关闭'):h(Overview,{ticket:record,refreshKey:refresh.value,onClose:()=>closed.value=true})
])}).use(router).use(ElementPlus).mount('#fixture')
