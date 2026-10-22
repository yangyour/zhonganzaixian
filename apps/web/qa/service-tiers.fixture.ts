import {createApp,h} from 'vue'
import {createRouter,createMemoryHistory,RouterView} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Page from '../src/pages/ServiceTiersPage.vue'
import {session,contextGeneration} from '../src/stores/session'
import {api} from '../src/api/client'
import {tenant,actor} from './service-tiers.data'
if(!import.meta.env.DEV)throw Error('Synthetic service tier browser entry is development-only')
session.value={user:{id:actor,role:'padmin',display_name:'Synthetic'},tenant:{id:tenant,name:'Synthetic'},allowed_tenants:[],capabilities:['sla_scenarios:read','configurations:write'],permission_version:1,account_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only'}
api.setCsrfToken('SYNTHETIC_NOT_CREDENTIAL')
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Page},{path:'/sla-scenarios',component:{render:()=>h('h1','合成场景目标，不读取真实数据')}}]})
await router.push('/')
createApp({render:()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box;min-width:0'},[
 h('p','合成身份与模拟API，不是生产授权、实际费用或正式客户验收。'),
 h('button',{style:'min-height:44px',onClick:()=>{session.value=null;api.invalidateContext();contextGeneration.value++}},'模拟撤销权限'),
 h('button',{style:'min-height:44px',onClick:()=>{if(session.value)session.value={...session.value,account_version:(session.value.account_version??0)+1}}},'模拟账号版本变化'),h(RouterView)
])}).use(router).use(ElementPlus).mount('#fixture')
