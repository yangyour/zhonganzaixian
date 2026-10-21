// Development-only synthetic identity; real page, readers and download utility. Never production authority.
import {createApp,h} from 'vue'
import {createMemoryHistory,createRouter,RouterView} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Page from '../src/pages/InspectionQuarterlyReportsPage.vue'
import {session,contextGeneration} from '../src/stores/session'
import capture from '../../../fixtures/quarterly-pdf-http-synthetic-20261021.json'
if(!import.meta.env.DEV)throw Error('Synthetic PDF browser entry is development-only')
const d=capture.detail
session.value={user:{id:d.created_by,display_name:'合成管理员',role:'padmin'},tenant:{id:d.tenant_id,name:'合成PDF租户'},allowed_tenants:[],capabilities:['inspection_quarterly_reports:read'],permission_version:1,account_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'synthetic-unit-only'}
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Page},{path:'/:pathMatch(.*)*',component:{render:()=>h('p','已离开合成季度报告页面')}}]})
await router.push('/')
createApp({render:()=>h('div',{style:'max-width:1100px;margin:auto;padding:12px;box-sizing:border-box'},[h('p','合成身份与模拟响应，不是生产授权或正式验收。'),h('button',{style:'min-height:44px',onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤销权限'),h(RouterView)])}).use(router).use(ElementPlus).mount('#fixture')
