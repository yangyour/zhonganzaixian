// Development-only synthetic browser entrypoint. Playwright intercepts diagnostics; no production authority.
import {createApp,h} from 'vue'
import {createMemoryHistory,createRouter,RouterView} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Page from '../src/pages/ObservationWindowDiagnosticsPage.vue'
import {session,contextGeneration} from '../src/stores/session'
import {api} from '../src/api/client'
import capture from '../../../fixtures/observation-window-http-synthetic-20261021.json'
if(!import.meta.env.DEV)throw Error('Synthetic acceptance entry is development-only')
const data=capture.captures[0]!.response
session.value={user:{id:data.actor_id,display_name:'Synthetic',role:'padmin'},tenant:{id:data.tenant_id,name:'Synthetic'},allowed_tenants:[],capabilities:['configurations:read'],permission_version:data.permission_version,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only'}
api.setCsrfToken('SYNTHETIC_NOT_CREDENTIAL')
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Page},{path:'/threshold-diagnostics',component:{render:()=>h('p','阈值条件诊断合成目标')}}]})
await router.push('/')
createApp({render:()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box'},[h('p','合成身份与模拟 API，仅作交互验收，不是客户或生产验收。'),h('button',{onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤销权限'),h(RouterView)])}).use(router).use(ElementPlus).mount('#fixture')
