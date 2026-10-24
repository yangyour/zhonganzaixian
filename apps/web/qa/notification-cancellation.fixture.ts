// Synthetic identity and intercepted API for browser interaction only.
import {createApp,h} from 'vue'
import {createRouter,createMemoryHistory,RouterView} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import '../src/styles/prototype.css'
import '../src/styles/functional-prototype.css'
import '../src/styles/compact-workspaces.css'
import '../src/styles/workspace.css'
import '../src/styles/controls.css'
import '../src/styles/theme.css'
import Page from '../src/pages/NotificationRoutingPage.vue'
import {session,contextGeneration} from '../src/stores/session'
session.value={user:{id:'79000000-0000-4000-8000-000000000002',display_name:'SYNTHETIC',role:'padmin'},
 tenant:{id:'79000000-0000-4000-8000-000000000001',name:'SYNTHETIC'},allowed_tenants:[],permission_version:7,account_version:1,
 mode:'remote_test',csrf_token:'SYNTHETIC_NOT_CREDENTIAL',identity_source:'synthetic',
 capabilities:['configurations:write','ticket_notes:read_internal','audit:read','notification_preferences:read_own']}
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/notification-routing',component:Page}]})
await router.push('/notification-routing')
createApp({render:()=>h('main',{id:'main-content',style:'padding:24px;max-width:1440px;margin:auto'},[
 h('p','合成身份与拦截API，仅验证桌面交互。'),h('button',{onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤销权限'),h(RouterView),
])}).use(router).use(ElementPlus).mount('#fixture')
