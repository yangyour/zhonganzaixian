// Development-only isolated UI fixture. Browser tests intercept every API request; no device or real identity is used.
import {createApp,h} from 'vue'
import {createMemoryHistory,createRouter,RouterView} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import '../src/styles/prototype.css'
import '../src/styles/compact-workspaces.css'
import '../src/styles/workspace.css'
import '../src/styles/controls.css'
import '../src/styles/experience.css'
import '../src/styles/theme.css'
import Panel from '../src/components/AssetMonitoringMappingPanel.vue'
import {session,contextGeneration} from '../src/stores/session'
import type {Asset} from '../src/api/types'
import {mappingScope as scope} from '../tests/monitoringObjectMappings.fixture'
session.value={user:{id:scope.actor,role:'padmin',display_name:'SYNTHETIC_UI'},tenant:{id:scope.tenant,name:'SYNTHETIC_UI'},allowed_tenants:[],capabilities:['assets:read','configurations:read','configurations:write'],permission_version:1,account_version:1,csrf_token:'SYNTHETIC_UI_ONLY',mode:'remote_test',identity_source:'isolated_browser'}
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{render:()=>h(Panel,{asset:{id:scope.asset,tenant_id:scope.tenant,version:1} as Asset})}},{path:'/events',name:'monitoring-events',component:{template:'<h2>合成事件详情</h2>'}}]})
await router.push('/')
createApp({render:()=>h('div',{class:'prototype-shell',style:'padding:16px;min-width:0;box-sizing:border-box'},[
 h('main',{id:'main-content'},[h('h1','监控问题消费 · 合成桌面验证'),
 h('button',{onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤销权限'),h(RouterView)])
])}).use(router).use(ElementPlus).mount('#fixture')
