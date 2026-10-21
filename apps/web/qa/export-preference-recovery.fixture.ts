// Development-only. Synthetic identity plus intercepted API, never a production authentication route.
import {createApp,h} from 'vue'
import {createMemoryHistory,createRouter,RouterView} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import ExportPage from '../src/pages/AssetExportJobsPage.vue'
import PreferencePage from '../src/pages/OwnNotificationPreferencesPage.vue'
import {formFeedback} from '../src/directives/formFeedback'
import {session,contextGeneration} from '../src/stores/session'
import {api} from '../src/api/client'
if(!import.meta.env.DEV)throw Error('Synthetic recovery browser entry is development-only')
const preference=new URLSearchParams(location.search).get('page')==='preference'
const prefix=preference?'7a000000-0000-4000-8000-':'7f000000-0000-4000-8000-'
const id=(n:number)=>prefix+n.toString(16).padStart(12,'0')
session.value={user:{id:id(preference?2:1),display_name:'Synthetic',role:preference?'viewer':'padmin'},tenant:{id:id(preference?1:2),name:'Synthetic'},allowed_tenants:[],capabilities:preference?['notification_preferences:read_own','notification_preferences:write_own']:['assets:read'],permission_version:preference?7:1,account_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only'}
api.setCsrfToken('SYNTHETIC_NOT_CREDENTIAL')
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:preference?PreferencePage:ExportPage},{path:'/my-export-messages',component:{render:()=>h('p','合成导出消息目标，不读取真实收件箱')}}]})
await router.push('/')
createApp({render:()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box'},[
 h('p','合成身份与模拟API；不是客户验收、供应商发送或生产授权。'),
 h('button',{onClick:()=>{session.value=null;api.invalidateContext();contextGeneration.value++}},'模拟撤销权限'),
 h('button',{onClick:()=>{if(session.value)session.value={...session.value,account_version:(session.value.account_version??0)+1}}},'模拟账号版本变化'),h(RouterView)
])}).use(router).use(ElementPlus).directive('form-feedback',formFeedback).mount('#fixture')
