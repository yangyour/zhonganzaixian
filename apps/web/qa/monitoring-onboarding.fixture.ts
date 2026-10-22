// Development-only entry. Synthetic identity, no real devices or credentials.
import{createApp,h}from'vue'
import{createMemoryHistory,createRouter,RouterView}from'vue-router'
import ElementPlus from'element-plus'
import'element-plus/dist/index.css'
import'../src/styles.css'
import'../src/styles/prototype.css'
import'../src/styles/compact-workspaces.css'
import'../src/styles/workspace.css'
import'../src/styles/controls.css'
import Page from'../src/pages/MonitoringOnboardingPage.vue'
import{session,contextGeneration}from'../src/stores/session'
session.value={user:{id:'55000000-0000-4000-8000-000000000002',role:'padmin',display_name:'Synthetic'},tenant:{id:'55000000-0000-4000-8000-000000000001',name:'Synthetic'},allowed_tenants:[],capabilities:['assets:read','configurations:read'],permission_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'project_local'}
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Page},{path:'/other',component:{template:'<p>其他页面</p>'}}]});await router.push('/')
createApp({render:()=>h('div',{class:'prototype-shell',style:'padding:12px;box-sizing:border-box;min-width:0'},[h('nav',[h('button',{onClick:()=>{session.value=null;contextGeneration.value++}},'模拟撤销权限'),h('button',{onClick:()=>router.push('/other')},'离开配置页面')]),h('main',{id:'main-content',style:'min-width:0'},h(RouterView))])}).use(router).use(ElementPlus).mount('#fixture')
