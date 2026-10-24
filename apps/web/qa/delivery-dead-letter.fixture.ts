// Development-only synthetic UI. Browser specs intercept every request; no real recipient/provider exists.
import {createApp,h,ref} from 'vue'
import {createRouter,createWebHistory,RouterView} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import '../src/styles/theme.css'
import Panel from '../src/components/DeliveryDeadLetterPanel.vue'
import {session,contextGeneration} from '../src/stores/session'
import {deliveryScope as s,deliveryEntry} from '../tests/deliveryJournal.fixture'
import type {DeliveryEntry} from '../src/utils/deliveryJournal'
if(!import.meta.env.DEV)throw new Error('Synthetic delivery entry is development-only')
session.value={user:{id:s.actor,display_name:'SYNTHETIC',role:'padmin'},tenant:{id:s.tenant,name:'SYNTHETIC'},allowed_tenants:[],permission_version:1,account_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic',capabilities:['configurations:write','ticket_notes:read_internal','audit:read']}
const entry=ref({...deliveryEntry(),version:2,recorded_status:'FAILED'} as DeliveryEntry)
const router=createRouter({history:createWebHistory(),routes:[{path:'/synthetic-disposition',component:{render:()=>h('main',{style:'padding:24px;max-width:1100px;margin:auto'},[h('h1','SYNTHETIC 人工死信处置'),h('p','合成身份与拦截API，不是供应商或指定环境验收。'),h('button',{onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤权'),h(Panel,{entry:entry.value})])}}]})
await router.push('/synthetic-disposition');createApp({render:()=>h(RouterView)}).use(router).use(ElementPlus).mount('#fixture')
