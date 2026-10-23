import {createApp,h} from 'vue'
import {createRouter,createMemoryHistory,RouterView} from 'vue-router'
import {api,ApiError,type RequestOptions} from '../src/api/client'
import {session} from '../src/stores/session'
import type {Session} from '../src/api/types'
import NotificationRoutingPage from '../src/pages/NotificationRoutingPage.vue'
import NotificationPreferencePublication from '../src/components/NotificationPreferencePublication.vue'
import type {ControlEntry} from '../src/utils/notificationControl'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import '../src/styles/prototype.css'
import '../src/styles/functional-prototype.css'
import '../src/styles/compact-workspaces.css'
import '../src/styles/workspace.css'
import '../src/styles/controls.css'
import '../src/styles/theme.css'

const USER='11111111-1111-4111-8111-111111111111',TENANT='22222222-2222-4222-8222-222222222222'
const BINDING='33333333-3333-4333-8333-333333333333',REQUEST='44444444-4444-4444-8444-444444444444',POLICY='55555555-5555-4555-8555-555555555555'
const params=new URLSearchParams(location.search),trace:Array<{path:string;method:string;key?:string}>=[]
const auth:Session={user:{id:USER,display_name:'SYNTHETIC 操作人',role:'padmin'},tenant:{id:TENANT,name:'SYNTHETIC 隔离界面'},allowed_tenants:[],capabilities:['tickets:read','ticket_notes:read_internal','configurations:write','notification_preferences:read_own','notification_preferences:write_own','users:read'],permission_version:1,csrf_token:'synthetic-only',mode:'remote_test',identity_source:'project_local'}
session.value=auth
const now='2026-10-23T12:00:00Z'
const source={message_id:REQUEST,intent_id:REQUEST,receiver_id:USER,ticket_id:REQUEST,priority:'P2',phase:'OPENED',occurred_at:now,status:'WAITING_CONFIGURATION',reason:'PUBLISHED_ROUTING_CONFIGURATION_REQUIRED'}
const request:ControlEntry={kind:'REQUEST',id:REQUEST,version:0,command_id:REQUEST,state:source,recorded_at:now}
let policy:ControlEntry|null=null,binding:ControlEntry|null=null,preference:ControlEntry|null=null,lost=false
const commands=new Map<string,ControlEntry>()
const restored=sessionStorage.getItem('notification-qa-server')
if(restored){const value=JSON.parse(restored);policy=value.policy;binding=value.binding;preference=value.preference;lost=value.lost;Object.assign(request,value.request);for(const [key,entry] of value.commands)commands.set(key,entry)}
function persist(){sessionStorage.setItem('notification-qa-server',JSON.stringify({policy,binding,preference,lost,request,commands:[...commands]}))}
function record(kind:ControlEntry['kind'],id:string,version:number,key:string,state:Record<string,unknown>):ControlEntry{return{kind,id,version,command_id:key,state,recorded_at:now}}
api.request=async <T>(path:string,options:RequestOptions={}):Promise<T>=>{
 trace.push({path,method:options.method??'GET',key:options.idempotencyKey})
 if(params.has('slow')&&(options.method??'GET')==='GET')await new Promise(resolve=>setTimeout(resolve,1200))
 if(params.has('unavailable')&&path.startsWith('/notification-routing'))throw new ApiError(503,'NOTIFICATION_ROUTING_NOT_CONFIGURED','通知路由控制尚未启用，已有站内通知仍可使用','synthetic')
 let result:unknown
 if(options.method==='POST'){
  const key=options.idempotencyKey!;const body=options.body as Record<string,unknown>
  if(commands.has(key))return structuredClone(commands.get(key)) as T
  if(path==='/notification-routing/policy'){policy=record('POLICY',POLICY,Number(body.expected_version)+1,key,{...(body.policy as object),external_dispatch_enabled:false,customer_production_approved:false});result=policy}
  else if(path==='/notification-routing/bindings'){binding=record('BINDING',BINDING,Number(body.expected_version)+1,key,{...(body.binding as object),external_dispatch_enabled:false});result=binding}
  else if(path==='/my/notification-preferences/publication'){preference=record('PREFERENCE',USER,Number(body.expected_version)+1,key,{draft_id:body.draft_id,draft_version:body.draft_version,published:body.published,external_dispatch_enabled:false});result=preference}
  else if(path.endsWith('/plan')){request.version++;request.command_id=key;request.state={...source,status:'ROUTED',reason:'PLANNED'};result=request}
  else throw new ApiError(400,'UNEXPECTED_SYNTHETIC_WRITE','合成入口拒绝未登记写操作','synthetic')
  commands.set(key,structuredClone(result as ControlEntry))
  const lose=params.has('lose')&&!lost;if(lose)lost=true;persist()
  if(lose)throw new ApiError(503,'SYNTHETIC_RESPONSE_LOST','合成场景：写入响应丢失，请核对原命令','synthetic')
 }else if(path==='/notification-routing/policy')result={record:policy,external_dispatch_enabled:false,customer_production_approved:false}
 else if(path.startsWith('/notification-routing/commands/')){result=commands.get(path.split('/').at(-1)!);if(!result)throw new ApiError(404,'NOT_FOUND','未找到原命令','synthetic')}
 else if(path.startsWith('/notification-routing/entries?')){const query=new URLSearchParams(path.split('?')[1]);const kind=query.get('kind');result={items:kind==='REQUEST'?[request]:kind==='BINDING'&&binding?[binding]:[],next_id:null,next_time:null,source:'postgresql'}}
 else if(path.startsWith('/notification-routing/bindings/'))result={record:binding}
 else if(path==='/email/bindings')result={items:[{id:BINDING,user_id:USER,display_name:'SYNTHETIC 邮件接收人',active:true}]}
 else if(path==='/wecom/bindings'){if(params.has('partial'))throw new ApiError(503,'CHANNEL_UNAVAILABLE','合成企业微信依赖未配置','synthetic');result={items:[]}}
 else if(path==='/my/notification-preferences/publication')result={record:preference,external_dispatch_enabled:false}
 else throw new ApiError(400,'UNEXPECTED_SYNTHETIC_READ','合成入口拒绝未登记读取','synthetic')
 return structuredClone(result) as T
}
Object.assign(window,{notificationQaTrace:trace})
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:NotificationRoutingPage},{path:'/preference',component:{render:()=>h(NotificationPreferencePublication,{draftId:REQUEST,draftVersion:1,blocked:false})}},{path:'/:pathMatch(.*)*',component:{render:()=>h('p','SYNTHETIC 导航目标')}}]})
const app=createApp({render:()=>h('div',{class:'prototype-shell'},[h('main',{id:'main-content',style:'padding:20px;max-width:1500px;margin:auto'},[
 h('p',{role:'note'},'合成 API 桌面流程验证；不连接真实服务或发送消息。'),
 h('div',{style:'display:flex;gap:16px;margin-bottom:16px'},[
  h('button',{onClick:()=>{session.value={...auth,user:{...auth.user,role:'viewer'},capabilities:['tickets:read','notification_preferences:read_own','notification_preferences:write_own'],permission_version:2}}},'QA 撤销内部权限'),
  h('button',{onClick:()=>router.push('/preference')},'QA 本人发布入口')]),h(RouterView)])])})
app.use(router);await router.push('/');await router.isReady();app.mount('#app')
