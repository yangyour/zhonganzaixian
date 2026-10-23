// Development-only synthetic session; not a product route or authorization substitute.
import {createApp,h} from 'vue'
import {createMemoryHistory,createRouter,RouterView} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import '../src/styles/prototype.css'
import '../src/styles/functional-prototype.css'
import '../src/styles/compact-workspaces.css'
import '../src/styles/workspace.css'
import '../src/styles/controls.css'
import '../src/styles/theme.css'
import Events from '../src/pages/MonitoringEventsPage.vue'
import {session} from '../src/stores/session'
import fixture from '../../../fixtures/monitoring-event-center-controller.json'
const data=fixture.workspace.data
session.value={user:{id:data.events[0]!.updated_by,display_name:'Synthetic',role:'padmin'},tenant:{id:data.tenant_id,name:'Synthetic'},allowed_tenants:[],capabilities:['tickets:read','ticket_notes:write_internal','configurations:write'],permission_version:3,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'project_local'}
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/monitoring-events',name:'monitoring-events',component:Events}]})
await router.push('/monitoring-events')
createApp({render:()=>h('div',{class:'prototype-shell',style:'min-width:0;padding:12px;box-sizing:border-box'},[h('main',{id:'main-content',style:'min-width:0'},h(RouterView))])}).use(router).use(ElementPlus).mount('#fixture')
