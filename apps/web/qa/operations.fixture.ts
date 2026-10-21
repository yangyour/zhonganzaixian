// Development-only browser entrypoint. Synthetic identities and intercepted API responses only.
import {createApp,h} from 'vue'
import {createMemoryHistory,createRouter,RouterView,useRoute} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Events from '../src/components/DashboardEventsPanel.vue'
import Reports from '../src/pages/InspectionQuarterlyReportsPage.vue'
import {session,contextGeneration} from '../src/stores/session'
import eventFixture from '../../../fixtures/monitoring-event-center-controller.json'
import reportFixture from '../tests/fixtures/quarterly-report/selected.json'
const reports=new URLSearchParams(location.search).get('view')==='reports'
const data=reports?reportFixture.data:eventFixture.workspace.data
session.value={user:{id:reports?reportFixture.data.created_by:eventFixture.workspace.data.events[0]!.updated_by,display_name:'Synthetic',role:'padmin'},tenant:{id:data.tenant_id,name:'Synthetic'},allowed_tenants:[],capabilities:reports?['inspection_quarterly_reports:read']:['dashboard:read','tickets:read'],permission_version:7,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'project_local'}
const detail={setup(){const route=useRoute();return()=>h('p',{role:'status'},'合成详情目标：'+String(route.query.event_id??''))}}
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:reports?Reports:Events},{path:'/monitoring-events',component:detail}]})
await router.push('/')
createApp({render:()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box'},[
 h('p','合成身份与模拟 API，仅用于交互验收，非后端授权或客户验收。'),
 h('button',{onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤销权限'),h(RouterView)
])}).use(router).use(ElementPlus).mount('#fixture')
