import {createApp,h,shallowRef,ref} from 'vue'
import {createRouter,createWebHistory} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles/theme.css'
import Panel from '../src/components/ServiceObservationsPanel.vue'
import {createServiceReader,emptyServiceState} from '../src/utils/serviceObservations'
import {api} from '../src/api/client'
const state=shallowRef(emptyServiceState()),permitted=ref(true)
const reader=createServiceReader(api,value=>state.value=value)
reader.reset({tenantId:'10000000-0000-4000-8000-000000000001',actorId:'20000000-0000-4000-8000-000000000001',permissionVersion:7,canRead:true})
const router=createRouter({history:createWebHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<p />'}}]})
createApp({render:()=>h('main',{style:'max-width:1000px;margin:24px auto'},[
 h('h1','服务观测'),h('p','合成身份与模拟API，仅检查交互，不是数据库联调或客户验收。'),
 h('button',{onClick:()=>{reader.reset(null);permitted.value=false}},'模拟撤权'),
 ...(['health','timeline'] as const).map(mode=>h('section',{'aria-label':mode==='health'?'服务健康':'故障时间线'},[
 h('h2',mode==='health'?'服务健康':'故障时间线'),h(Panel,{state:state.value,mode,permitted:permitted.value,onRefresh:()=>reader.load(),onCancel:()=>reader.cancel()})]))
])}).use(router).use(ElementPlus).mount('#fixture')
