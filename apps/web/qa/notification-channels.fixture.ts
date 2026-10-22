// Development-only synthetic identity. Every API request is intercepted by Playwright.
import {createApp,h} from 'vue'
import {createRouter,createMemoryHistory,RouterView} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Page from '../src/pages/TicketNotificationsPage.vue'
import {session,contextGeneration} from '../src/stores/session'
import {api} from '../src/api/client'
import {id} from '../tests/helpers/notificationDeliveryFixture'
if(!import.meta.env.DEV)throw Error('Synthetic notification browser entry is development-only')
session.value={user:{id:id(2),role:'msp',display_name:'Synthetic'},tenant:{id:id(1),name:'Synthetic'},allowed_tenants:[],capabilities:['tickets:read','ticket_notes:read_internal'],permission_version:1,account_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only'}
api.setCsrfToken('SYNTHETIC_NOT_CREDENTIAL')
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Page},{path:'/ticket-workflows',component:{render:()=>h('h1','合成工单目标，不读取真实工单')}}]})
await router.push('/')
createApp({render:()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box;min-width:0'},[
 h('p','合成身份与模拟API；不是生产授权、真实发送或正式客户验收。'),
 h('button',{style:'min-height:44px',onClick:()=>{session.value=null;api.invalidateContext();contextGeneration.value++}},'模拟撤销权限'),
 h('button',{style:'min-height:44px',onClick:()=>{if(session.value)session.value={...session.value,account_version:(session.value.account_version??0)+1}}},'模拟账号版本变化'),h(RouterView)
])}).use(router).use(ElementPlus).mount('#fixture')
