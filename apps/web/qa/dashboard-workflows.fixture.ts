// Development-only synthetic interaction target; never production authority.
import {createApp,h} from 'vue'
import {createMemoryHistory,createRouter,RouterView,useRoute} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Panel from '../src/components/DashboardWorkflowsPanel.vue'
import {session,contextGeneration} from '../src/stores/session'
import {workflowContext as c} from '../tests/dashboardWorkflow.fixture'
if(!import.meta.env.DEV)throw Error('Synthetic dashboard workflow browser entry is development-only')
session.value={user:{id:c.actorId,display_name:'Synthetic',role:'padmin'},tenant:{id:c.tenantId,name:'Synthetic'},allowed_tenants:[],capabilities:['dashboard:read','tickets:read'],permission_version:c.permissionVersion,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only'}
const detail={setup(){const route=useRoute();return()=>h('p',{role:'status'},'合成工单详情目标：'+String(route.query.ticket_id??''))}}
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Panel},{path:'/ticket-workflows',component:detail}]})
await router.push('/')
createApp({render:()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box'},[h('h1','首页工单合成交互验收'),h('p','合成身份与模拟API，不是客户验收或生产授权。'),h('button',{onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤销权限'),h(RouterView)])}).use(router).use(ElementPlus).mount('#fixture')
